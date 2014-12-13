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
package net.kuujo.copycat.internal.cluster;

/**
 * Topic constants.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class Topics {

  /**
   * Cluster configure topic.
   */
  public static final String CONFIGURE = "configure";

  /**
   * Member ping topic.
   */
  public static final String PING = "ping";

  /**
   * Member poll topic.
   */
  public static final String POLL = "poll";

  /**
   * Member sync topic.
   */
  public static final String SYNC = "sync";

  /**
   * Member commit topic.
   */
  public static final String COMMIT = "commit";

  /**
   * Member submit topic.
   */
  public static final String SUBMIT = "submit";

}