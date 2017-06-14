/*
 * Copyright 2015 the original author or authors.
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
 * limitations under the License
 */
package io.atomix.protocols.raft.server.storage.system;

import io.atomix.cluster.NodeId;
import io.atomix.protocols.raft.server.storage.Storage;
import io.atomix.protocols.raft.server.storage.StorageLevel;
import io.atomix.util.buffer.Buffer;
import io.atomix.util.buffer.FileBuffer;
import io.atomix.util.buffer.HeapBuffer;
import io.atomix.util.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Manages persistence of server configurations.
 * <p>
 * The server metastore is responsible for persisting server configurations according to the configured
 * {@link Storage#level() storage level}. Each server persists their current {@link #loadTerm() term}
 * and last {@link #loadVote() vote} as is dictated by the Raft consensus algorithm. Additionally, the
 * metastore is responsible for storing the last know server {@link Configuration}, including cluster
 * membership.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class MetaStore implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MetaStore.class);
    private final Serializer serializer;
    private final FileBuffer metadataBuffer;
    private final Buffer configurationBuffer;

    public MetaStore(String name, Storage storage, Serializer serializer) {
        this.serializer = checkNotNull(serializer, "serializer cannot be null");

        if (!(storage.directory().isDirectory() || storage.directory().mkdirs())) {
            throw new IllegalArgumentException(String.format("Can't create storage directory [%s].", storage.directory()));
        }

        // Note that for raft safety, irrespective of the storage level, <term, vote> metadata is always persisted on disk.
        File metaFile = new File(storage.directory(), String.format("%s.meta", name));
        metadataBuffer = FileBuffer.allocate(metaFile, 12);

        if (storage.level() == StorageLevel.MEMORY) {
            configurationBuffer = HeapBuffer.allocate(32);
        } else {
            File confFile = new File(storage.directory(), String.format("%s.conf", name));
            configurationBuffer = FileBuffer.allocate(confFile, 32);
        }
    }

    /**
     * Stores the current server term.
     *
     * @param term The current server term.     */
    public synchronized void storeTerm(long term) {
        LOGGER.trace("Store term {}", term);
        metadataBuffer.writeLong(0, term).flush();
    }

    /**
     * Loads the stored server term.
     *
     * @return The stored server term.
     */
    public synchronized long loadTerm() {
        return metadataBuffer.readLong(0);
    }

    /**
     * Stores the last voted server.
     *
     * @param vote The server vote.
     */
    public synchronized void storeVote(NodeId vote) {
        LOGGER.trace("Store vote {}", vote);
        metadataBuffer.writeString(8, vote != null ? vote.id() : null).flush();
    }

    /**
     * Loads the last vote for the server.
     *
     * @return The last vote for the server.
     */
    public synchronized NodeId loadVote() {
        return NodeId.nodeId(metadataBuffer.readString(8));
    }

    /**
     * Stores the current cluster configuration.
     *
     * @param configuration The current cluster configuration.
     */
    public synchronized void storeConfiguration(Configuration configuration) {
        LOGGER.trace("Store configuration {}", configuration);
        byte[] bytes = serializer.encode(configuration);
        configurationBuffer.position(0)
                .writeInt(bytes.length)
                .write(bytes);
        configurationBuffer.flush();
    }

    /**
     * Loads the current cluster configuration.
     *
     * @return The current cluster configuration.
     */
    public synchronized Configuration loadConfiguration() {
        if (configurationBuffer.position(0).readByte() == 1) {
            return serializer.decode(configurationBuffer.readBytes(configurationBuffer.readInt()));
        }
        return null;
    }

    @Override
    public synchronized void close() {
        metadataBuffer.close();
        configurationBuffer.close();
    }

    @Override
    public String toString() {
        if (configurationBuffer instanceof FileBuffer) {
            return String.format(
                    "%s[%s,%s]",
                    getClass().getSimpleName(),
                    metadataBuffer.file(),
                    ((FileBuffer) configurationBuffer).file()
            );
        } else {
            return String.format(
                    "%s[%s]",
                    getClass().getSimpleName(),
                    metadataBuffer.file()
            );
        }
    }

}