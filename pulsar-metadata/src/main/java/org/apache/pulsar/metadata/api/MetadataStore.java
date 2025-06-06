/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.metadata.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.Beta;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.apache.pulsar.metadata.api.MetadataStoreException.BadVersionException;
import org.apache.pulsar.metadata.api.MetadataStoreException.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metadata store client interface.
 *
 * NOTE: This API is still evolving and will be refactored as needed until all the metadata usages are converted into
 * using it.
 */
@Beta
public interface MetadataStore extends AutoCloseable {

    Logger LOGGER = LoggerFactory.getLogger(MetadataStore.class);

    /**
     * Read the value of one key, identified by the path
     *
     * The async call will return a future that yields a {@link GetResult} that will contain the value and the
     * associated {@link Stat} object.
     *
     * If the value is not found, the future will yield an empty {@link Optional}.
     *
     * @param path
     *            the path of the key to get from the store
     * @return a future to track the async request
     */
    CompletableFuture<Optional<GetResult>> get(String path);


    /**
     * Ensure that the next value read from  the local client will be up-to-date with the latest version of the value
     * as it can be seen by all the other clients.
     * @param path
     * @return a handle to the operation
     */
    default CompletableFuture<Void> sync(String path) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Return all the nodes (lexicographically sorted) that are children to the specific path.
     *
     * If the path itself does not exist, it will return an empty list.
     *
     * @param path
     *            the path of the key to get from the store
     * @return a future to track the async request
     */
    CompletableFuture<List<String>> getChildren(String path);

    /**
     * Read whether a specific path exists.
     *
     * Note: In case of keys with multiple levels (eg: '/a/b/c'), checking the existence of a parent (eg. '/a') might
     * not necessarily return true, unless the key had been explicitly created.
     *
     * @param path
     *            the path of the key to check on the store
     * @return a future to track the async request
     */
    CompletableFuture<Boolean> exists(String path);

    /**
     * Put a new value for a given key.
     *
     * The caller can specify an expected version to be atomically checked against the current version of the stored
     * data.
     *
     * The future will return the {@link Stat} object associated with the newly inserted value.
     *
     *
     * @param path
     *            the path of the key to delete from the store
     * @param value
     *            the value to
     * @param expectedVersion
     *            if present, the version will have to match with the currently stored value for the operation to
     *            succeed. Use -1 to enforce a non-existing value.
     * @throws BadVersionException
     *             if the expected version doesn't match the actual version of the data
     * @return a future to track the async request
     */
    CompletableFuture<Stat> put(String path, byte[] value, Optional<Long> expectedVersion);

    /**
     *
     * @param path
     *            the path of the key to delete from the store
     * @param expectedVersion
     *            if present, the version will have to match with the currently stored value for the operation to
     *            succeed
     * @throws NotFoundException
     *             if the path is not found
     * @throws BadVersionException
     *             if the expected version doesn't match the actual version of the data
     * @return a future to track the async request
     */
    CompletableFuture<Void> delete(String path, Optional<Long> expectedVersion);

    default CompletableFuture<Void> deleteIfExists(String path, Optional<Long> expectedVersion) {
        return delete(path, expectedVersion)
                .exceptionally(e -> {
                    if (e.getCause() instanceof NotFoundException) {
                        LOGGER.info("Path {} not found while deleting (this is not a problem)", path);
                        return null;
                    } else {
                        if (expectedVersion.isEmpty()) {
                            LOGGER.info("Failed to delete path {}", path, e);
                        } else {
                            LOGGER.info("Failed to delete path {} with expected version {}", path, expectedVersion, e);
                        }
                        throw new CompletionException(e);
                    }
                });
    }

    /**
     * Delete a key-value pair and all the children nodes.
     *
     * Note: the operation might not be carried in an atomic fashion. If the operation fails, the deletion of the
     *       tree might be only partial.
     *
     * @param path
     *            the path of the key to delete from the store
     * @return a future to track the async request
     */
    CompletableFuture<Void> deleteRecursive(String path);

    /**
     * Register a listener that will be called on changes in the underlying store.
     *
     * @param listener
     *            a consumer of notifications
     */
    void registerListener(Consumer<Notification> listener);

    /**
     * Create a metadata cache specialized for a specific class.
     *
     * @param <T>
     * @param clazz
     *            the class type to be used for serialization/deserialization
     * @param cacheConfig
     *          the cache configuration to be used
     * @return the metadata cache object
     */
    <T> MetadataCache<T> getMetadataCache(Class<T> clazz, MetadataCacheConfig cacheConfig);

    /**
     * Create a metadata cache specialized for a specific class.
     *
     * @param <T>
     * @param clazz
     *            the class type to be used for serialization/deserialization
     * @return the metadata cache object
     */
    default <T> MetadataCache<T> getMetadataCache(Class<T> clazz) {
        return getMetadataCache(clazz, getDefaultMetadataCacheConfig());
    }

    /**
     * Create a metadata cache specialized for a specific class.
     *
     * @param <T>
     * @param typeRef
     *            the type ref description to be used for serialization/deserialization
     * @param cacheConfig
     *          the cache configuration to be used
     * @return the metadata cache object
     */
    <T> MetadataCache<T> getMetadataCache(TypeReference<T> typeRef, MetadataCacheConfig cacheConfig);

    /**
     * Create a metadata cache specialized for a specific class.
     *
     * @param <T>
     * @param typeRef
     *            the type ref description to be used for serialization/deserialization
     * @return the metadata cache object
     */
    default <T> MetadataCache<T> getMetadataCache(TypeReference<T> typeRef) {
        return getMetadataCache(typeRef, getDefaultMetadataCacheConfig());
    }

    /**
     * Create a metadata cache that uses a particular serde object.
     *
     * @param <T>
     * @param serde
     *            the custom serialization/deserialization object
     * @param cacheConfig
     *          the cache configuration to be used
     * @deprecated use {@link #getMetadataCache(String, MetadataSerde, MetadataCacheConfig)}
     * @return the metadata cache object
     */
    @Deprecated
    default <T> MetadataCache<T> getMetadataCache(MetadataSerde<T> serde, MetadataCacheConfig cacheConfig) {
        return getMetadataCache("serde", serde, cacheConfig);
    }

    /**
     * Create a metadata cache that uses a particular serde object.
     *
     * @param <T>
     * @param serde
     *            the custom serialization/deserialization object
     * @return the metadata cache object
     */
    default <T> MetadataCache<T> getMetadataCache(MetadataSerde<T> serde) {
        return getMetadataCache(serde, getDefaultMetadataCacheConfig());
    }

    /**
     * Create a metadata cache that uses a particular serde object.
     *
     * @param <T>
     * @param serde
     *            the custom serialization/deserialization object
     * @return the metadata cache object
     */
    default <T> MetadataCache<T> getMetadataCache(String cacheName, MetadataSerde<T> serde,
                                                  MetadataCacheConfig cacheConfig) {
        return getMetadataCache(serde, cacheConfig);
    }

    /**
     * Returns the default metadata cache config.
     *
     * @return default metadata cache config
     */
    default MetadataCacheConfig getDefaultMetadataCacheConfig() {
        return MetadataCacheConfig.builder().build();
    }
}
