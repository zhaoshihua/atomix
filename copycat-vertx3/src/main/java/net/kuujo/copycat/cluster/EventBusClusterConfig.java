/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.cluster;

import java.util.Arrays;
import java.util.Collection;

/**
 * Event bus cluster configuration.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class EventBusClusterConfig extends ClusterConfig<EventBusMember> {

  public EventBusClusterConfig() {
  }

  public EventBusClusterConfig(ClusterConfig<EventBusMember> cluster) {
    super(cluster);
  }

  public EventBusClusterConfig(EventBusMember localMember, EventBusMember... remoteMembers) {
    this(localMember, Arrays.asList(remoteMembers));
  }

  public EventBusClusterConfig(EventBusMember localMember, Collection<EventBusMember> remoteMembers) {
    super(localMember, remoteMembers);
  }

  @Override
  public EventBusClusterConfig copy() {
    return new EventBusClusterConfig(localMember, remoteMembers);
  }

}
