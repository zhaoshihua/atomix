/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.protocol;

import com.google.common.collect.Maps;
import io.atomix.cluster.NodeId;
import io.atomix.primitive.session.SessionId;
import io.atomix.utils.serializer.Serializer;
import io.atomix.utils.concurrent.Futures;

import java.net.ConnectException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Test Raft client protocol.
 */
public class LocalRaftClientProtocol extends LocalRaftProtocol implements RaftClientProtocol {
  private Function<HeartbeatRequest, CompletableFuture<HeartbeatResponse>> heartbeatHandler;
  private final Map<Long, Consumer<PublishRequest>> publishListeners = Maps.newConcurrentMap();

  public LocalRaftClientProtocol(NodeId nodeId, Serializer serializer, Map<NodeId, LocalRaftServerProtocol> servers, Map<NodeId, LocalRaftClientProtocol> clients) {
    super(serializer, servers, clients);
    clients.put(nodeId, this);
  }

  private CompletableFuture<LocalRaftServerProtocol> getServer(NodeId nodeId) {
    LocalRaftServerProtocol server = server(nodeId);
    if (server != null) {
      return Futures.completedFuture(server);
    } else {
      return Futures.exceptionalFuture(new ConnectException());
    }
  }

  @Override
  public CompletableFuture<OpenSessionResponse> openSession(NodeId nodeId, OpenSessionRequest request) {
    return getServer(nodeId).thenCompose(protocol -> protocol.openSession(encode(request))).thenApply(this::decode);
  }

  @Override
  public CompletableFuture<CloseSessionResponse> closeSession(NodeId nodeId, CloseSessionRequest request) {
    return getServer(nodeId).thenCompose(protocol -> protocol.closeSession(encode(request))).thenApply(this::decode);
  }

  @Override
  public CompletableFuture<KeepAliveResponse> keepAlive(NodeId nodeId, KeepAliveRequest request) {
    return getServer(nodeId).thenCompose(protocol -> protocol.keepAlive(encode(request))).thenApply(this::decode);
  }

  @Override
  public CompletableFuture<QueryResponse> query(NodeId nodeId, QueryRequest request) {
    return getServer(nodeId).thenCompose(protocol -> protocol.query(encode(request))).thenApply(this::decode);
  }

  @Override
  public CompletableFuture<CommandResponse> command(NodeId nodeId, CommandRequest request) {
    return getServer(nodeId).thenCompose(protocol -> protocol.command(encode(request))).thenApply(this::decode);
  }

  @Override
  public CompletableFuture<MetadataResponse> metadata(NodeId nodeId, MetadataRequest request) {
    return getServer(nodeId).thenCompose(protocol -> protocol.metadata(encode(request))).thenApply(this::decode);
  }

  CompletableFuture<byte[]> heartbeat(byte[] request) {
    if (heartbeatHandler != null) {
      return heartbeatHandler.apply(decode(request)).thenApply(this::encode);
    } else {
      return Futures.exceptionalFuture(new ConnectException());
    }
  }

  @Override
  public void registerHeartbeatHandler(Function<HeartbeatRequest, CompletableFuture<HeartbeatResponse>> handler) {
    this.heartbeatHandler = handler;
  }

  @Override
  public void unregisterHeartbeatHandler() {
    this.heartbeatHandler = null;
  }

  @Override
  public void reset(Set<NodeId> members, ResetRequest request) {
    members.forEach(nodeId -> {
      LocalRaftServerProtocol server = server(nodeId);
      if (server != null) {
        server.reset(request.session(), encode(request));
      }
    });
  }

  void publish(long sessionId, byte[] request) {
    Consumer<PublishRequest> listener = publishListeners.get(sessionId);
    if (listener != null) {
      listener.accept(decode(request));
    }
  }

  @Override
  public void registerPublishListener(SessionId sessionId, Consumer<PublishRequest> listener, Executor executor) {
    publishListeners.put(sessionId.id(), request -> executor.execute(() -> listener.accept(request)));
  }

  @Override
  public void unregisterPublishListener(SessionId sessionId) {
    publishListeners.remove(sessionId.id());
  }
}
