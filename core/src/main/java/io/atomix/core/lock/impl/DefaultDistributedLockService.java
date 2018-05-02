/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.core.lock.impl;

import io.atomix.core.lock.DistributedLockType;
import io.atomix.primitive.service.AbstractPrimitiveService;
import io.atomix.primitive.service.BackupInput;
import io.atomix.primitive.service.BackupOutput;
import io.atomix.primitive.service.ServiceConfig;
import io.atomix.primitive.session.PrimitiveSession;
import io.atomix.utils.concurrent.Scheduled;
import io.atomix.utils.serializer.KryoNamespace;
import io.atomix.utils.serializer.Serializer;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.atomix.core.lock.impl.DistributedLockEvents.FAILED;
import static io.atomix.core.lock.impl.DistributedLockEvents.LOCKED;

/**
 * Raft atomic value service.
 */
public class DefaultDistributedLockService extends AbstractPrimitiveService implements DistributedLockService {
  private static final Serializer SERIALIZER = Serializer.using(KryoNamespace.builder()
      .register((KryoNamespace) DistributedLockType.instance().namespace())
      .register(LockHolder.class)
      .build());

  private LockHolder lock;
  private Queue<LockHolder> queue = new ArrayDeque<>();
  private final Map<Long, Scheduled> timers = new HashMap<>();

  public DefaultDistributedLockService(ServiceConfig config) {
    super(DistributedLockService.class, config);
  }

  @Override
  public Serializer serializer() {
    return SERIALIZER;
  }

  @Override
  public void backup(BackupOutput output) {
    output.writeObject(lock);
    output.writeObject(queue);
  }

  @Override
  public void restore(BackupInput input) {
    lock = input.readObject();
    queue = input.readObject();

    // After the snapshot is installed, we need to cancel any existing timers and schedule new ones based on the
    // state provided by the snapshot.
    timers.values().forEach(Scheduled::cancel);
    timers.clear();
    for (LockHolder holder : queue) {
      if (holder.expire > 0) {
        timers.put(holder.index, getScheduler().schedule(Duration.ofMillis(holder.expire - getWallClock().getTime().unixTimestamp()), () -> {
          timers.remove(holder.index);
          queue.remove(holder);
          PrimitiveSession session = getSessions().getSession(holder.session);
          if (session != null && session.getState().active()) {
            session.publish(FAILED, new LockEvent(holder.id, holder.index));
          }
        }));
      }
    }
  }

  @Override
  public void onExpire(PrimitiveSession session) {
    releaseSession(session);
  }

  @Override
  public void onClose(PrimitiveSession session) {
    releaseSession(session);
  }

  @Override
  public void lock(int id, long timeout) {
    // If the lock is not already owned, immediately grant the lock to the requester.
    // Note that we still have to publish an event to the session. The event is guaranteed to be received
    // by the client-side primitive after the LOCK response.
    if (lock == null) {
      lock = new LockHolder(
          id,
          getCurrentIndex(),
          getCurrentSession().sessionId().id(),
          0);
      getCurrentSession().publish(LOCKED, new LockEvent(id, getCurrentIndex()));
      // If the timeout is 0, that indicates this is a tryLock request. Immediately fail the request.
    } else if (timeout == 0) {
      getCurrentSession().publish(FAILED, new LockEvent(id, getCurrentIndex()));
      // If a timeout exists, add the request to the queue and set a timer. Note that the lock request expiration
      // time is based on the *state machine* time - not the system time - to ensure consistency across servers.
    } else if (timeout > 0) {
      LockHolder holder = new LockHolder(
          id,
          getCurrentIndex(),
          getCurrentSession().sessionId().id(),
          getWallClock().getTime().unixTimestamp() + timeout);
      queue.add(holder);
      timers.put(getCurrentIndex(), getScheduler().schedule(Duration.ofMillis(timeout), () -> {
        // When the lock request timer expires, remove the request from the queue and publish a FAILED
        // event to the session. Note that this timer is guaranteed to be executed in the same thread as the
        // state machine commands, so there's no need to use a lock here.
        timers.remove(getCurrentIndex());
        queue.remove(holder);
        if (getCurrentSession().getState().active()) {
          getCurrentSession().publish(FAILED, new LockEvent(id, getCurrentIndex()));
        }
      }));
      // If the lock is -1, just add the request to the queue with no expiration.
    } else {
      LockHolder holder = new LockHolder(
          id,
          getCurrentIndex(),
          getCurrentSession().sessionId().id(),
          0);
      queue.add(holder);
    }
  }

  @Override
  public void unlock(int id) {
    if (lock != null) {
      // If the commit's session does not match the current lock holder, ignore the request.
      if (lock.session != getCurrentSession().sessionId().id()) {
        return;
      }

      // If the current lock ID does not match the requested lock ID, ignore the request. This ensures that
      // internal releases of locks that were never acquired by the client-side primitive do not cause
      // legitimate locks to be unlocked.
      if (lock.id != id) {
        return;
      }

      // The lock has been released. Populate the lock from the queue.
      lock = queue.poll();
      while (lock != null) {
        // If the waiter has a lock timer, cancel the timer.
        Scheduled timer = timers.remove(lock.index);
        if (timer != null) {
          timer.cancel();
        }

        // If the lock session is for some reason inactive, continue on to the next waiter. Otherwise,
        // publish a LOCKED event to the new lock holder's session.
        PrimitiveSession session = getSessions().getSession(lock.session);
        if (session == null || !session.getState().active()) {
          lock = queue.poll();
        } else {
          session.publish(LOCKED, new LockEvent(lock.id, getCurrentIndex()));
          break;
        }
      }
    }
  }

  /**
   * Handles a session that has been closed by a client or expired by the cluster.
   * <p>
   * When a session is removed, if the session is the current lock holder then the lock is released and the next
   * session waiting in the queue is granted the lock. Additionally, all pending lock requests for the session
   * are removed from the lock queue.
   *
   * @param session the closed session
   */
  private void releaseSession(PrimitiveSession session) {
    // Remove all instances of the session from the lock queue.
    queue.removeIf(lock -> lock.session == session.sessionId().id());

    // If the removed session is the current holder of the lock, nullify the lock and attempt to grant it
    // to the next waiter in the queue.
    if (lock != null && lock.session == session.sessionId().id()) {
      lock = queue.poll();
      while (lock != null) {
        // If the waiter has a lock timer, cancel the timer.
        Scheduled timer = timers.remove(lock.index);
        if (timer != null) {
          timer.cancel();
        }

        // If the lock session is inactive, continue on to the next waiter. Otherwise,
        // publish a LOCKED event to the new lock holder's session.
        PrimitiveSession lockSession = getSessions().getSession(lock.session);
        if (lockSession == null || !lockSession.getState().active()) {
          lock = queue.poll();
        } else {
          lockSession.publish(LOCKED, new LockEvent(lock.id, lock.index));
          break;
        }
      }
    }
  }

  private class LockHolder {
    private final int id;
    private final long index;
    private final long session;
    private final long expire;

    public LockHolder(int id, long index, long session, long expire) {
      this.id = id;
      this.index = index;
      this.session = session;
      this.expire = expire;
    }

    @Override
    public String toString() {
      return toStringHelper(this)
          .add("id", id)
          .add("index", index)
          .add("session", session)
          .add("expire", expire)
          .toString();
    }
  }
}