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
package org.apache.pulsar.broker.service.nonpersistent;

import static org.apache.bookkeeper.mledger.impl.EntryImpl.create;
import static org.apache.pulsar.common.policies.data.BacklogQuota.BacklogQuotaType;
import static org.apache.pulsar.common.protocol.Commands.DEFAULT_CONSUMER_EPOCH;
import com.carrotsearch.hppc.ObjectObjectHashMap;
import io.netty.buffer.ByteBuf;
import io.netty.util.concurrent.FastThreadLocal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.Position;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.loadbalance.extensions.ExtensibleLoadManagerImpl;
import org.apache.pulsar.broker.namespace.NamespaceService;
import org.apache.pulsar.broker.resources.NamespaceResources;
import org.apache.pulsar.broker.service.AbstractReplicator;
import org.apache.pulsar.broker.service.AbstractTopic;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.BrokerServiceException;
import org.apache.pulsar.broker.service.BrokerServiceException.ConsumerBusyException;
import org.apache.pulsar.broker.service.BrokerServiceException.NamingException;
import org.apache.pulsar.broker.service.BrokerServiceException.NotAllowedException;
import org.apache.pulsar.broker.service.BrokerServiceException.SubscriptionBusyException;
import org.apache.pulsar.broker.service.BrokerServiceException.TopicBusyException;
import org.apache.pulsar.broker.service.BrokerServiceException.TopicFencedException;
import org.apache.pulsar.broker.service.BrokerServiceException.UnsupportedVersionException;
import org.apache.pulsar.broker.service.Consumer;
import org.apache.pulsar.broker.service.Dispatcher;
import org.apache.pulsar.broker.service.GetStatsOptions;
import org.apache.pulsar.broker.service.Replicator;
import org.apache.pulsar.broker.service.StreamingStats;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.SubscriptionOption;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.TopicAttributes;
import org.apache.pulsar.broker.service.TopicPolicyListener;
import org.apache.pulsar.broker.service.TransportCnx;
import org.apache.pulsar.broker.service.schema.exceptions.IncompatibleSchemaException;
import org.apache.pulsar.broker.service.schema.exceptions.NotExistSchemaException;
import org.apache.pulsar.broker.stats.ClusterReplicationMetrics;
import org.apache.pulsar.broker.stats.NamespaceStats;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.transaction.TxnID;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.api.proto.CommandSubscribe.InitialPosition;
import org.apache.pulsar.common.api.proto.CommandSubscribe.SubType;
import org.apache.pulsar.common.api.proto.KeySharedMeta;
import org.apache.pulsar.common.naming.SystemTopicNames;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.ClusterPolicies.ClusterUrl;
import org.apache.pulsar.common.policies.data.ManagedLedgerInternalStats.CursorStats;
import org.apache.pulsar.common.policies.data.PersistentTopicInternalStats;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.PublisherStats;
import org.apache.pulsar.common.policies.data.TopicPolicies;
import org.apache.pulsar.common.policies.data.stats.ConsumerStatsImpl;
import org.apache.pulsar.common.policies.data.stats.NonPersistentPublisherStatsImpl;
import org.apache.pulsar.common.policies.data.stats.NonPersistentReplicatorStatsImpl;
import org.apache.pulsar.common.policies.data.stats.NonPersistentSubscriptionStatsImpl;
import org.apache.pulsar.common.policies.data.stats.NonPersistentTopicStatsImpl;
import org.apache.pulsar.common.policies.data.stats.PublisherStatsImpl;
import org.apache.pulsar.common.policies.data.stats.SubscriptionStatsImpl;
import org.apache.pulsar.common.policies.data.stats.TopicStatsImpl;
import org.apache.pulsar.common.protocol.schema.SchemaData;
import org.apache.pulsar.common.schema.SchemaType;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.apache.pulsar.utils.StatsOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NonPersistentTopic extends AbstractTopic implements Topic, TopicPolicyListener {

    // Subscriptions to this topic
    private final Map<String, NonPersistentSubscription> subscriptions = new ConcurrentHashMap<>();

    private final Map<String, NonPersistentReplicator> replicators = new ConcurrentHashMap<>();

    // Ever increasing counter of entries added
    private static final AtomicLongFieldUpdater<NonPersistentTopic> ENTRIES_ADDED_COUNTER_UPDATER =
            AtomicLongFieldUpdater.newUpdater(NonPersistentTopic.class, "entriesAddedCounter");
    private volatile long entriesAddedCounter = 0;

    private volatile boolean migrated = false;
    private static final FastThreadLocal<TopicStats> threadLocalTopicStats = new FastThreadLocal<TopicStats>() {
        @Override
        protected TopicStats initialValue() {
            return new TopicStats();
        }
    };

    private volatile TopicAttributes topicAttributes = null;
    private static final AtomicReferenceFieldUpdater<NonPersistentTopic, TopicAttributes>
            TOPIC_ATTRIBUTES_FIELD_UPDATER = AtomicReferenceFieldUpdater.newUpdater(
                    NonPersistentTopic.class, TopicAttributes.class, "topicAttributes");

    private static class TopicStats {
        public double averageMsgSize;
        public double aggMsgRateIn;
        public double aggMsgThroughputIn;
        public double aggMsgRateOut;
        public double aggMsgThroughputOut;
        public final ObjectObjectHashMap<String, PublisherStats> remotePublishersStats;

        public TopicStats() {
            remotePublishersStats = new ObjectObjectHashMap<>();
            reset();
        }

        public void reset() {
            averageMsgSize = 0;
            aggMsgRateIn = 0;
            aggMsgThroughputIn = 0;
            aggMsgRateOut = 0;
            aggMsgThroughputOut = 0;
            remotePublishersStats.clear();
        }
    }

    public NonPersistentTopic(String topic, BrokerService brokerService) {
        super(topic, brokerService);
        this.isFenced = false;
        registerTopicPolicyListener();
    }

    private CompletableFuture<Void> updateClusterMigrated() {
        return getMigratedClusterUrlAsync(brokerService.getPulsar(), topic)
                .thenAccept(url -> migrated = url.isPresent());
    }

    public CompletableFuture<Void> initialize() {
        return brokerService.pulsar().getPulsarResources().getNamespaceResources()
                .getPoliciesAsync(TopicName.get(topic).getNamespaceObject())
                .thenCompose(optPolicies -> {
                    final Policies policies;
                    if (optPolicies.isEmpty()) {
                        log.warn("[{}] Policies not present and isEncryptionRequired will be set to false", topic);
                        isEncryptionRequired = false;
                        policies = new Policies();
                    } else {
                        policies = optPolicies.get();
                        updateTopicPolicyByNamespacePolicy(policies);
                        isEncryptionRequired = policies.encryption_required;
                        isAllowAutoUpdateSchema = policies.is_allow_auto_update_schema;
                    }
                    updatePublishRateLimiter();
                    updateResourceGroupLimiter(policies);
                    return updateClusterMigrated();
                });
    }

    @Override
    public void publishMessage(ByteBuf data, PublishContext callback) {
        if (isExceedMaximumMessageSize(data.readableBytes(), callback)) {
            callback.completed(new NotAllowedException("Exceed maximum message size")
                    , -1, -1);
            return;
        }
        callback.completed(null, 0L, 0L);
        ENTRIES_ADDED_COUNTER_UPDATER.incrementAndGet(this);

        subscriptions.forEach((name, subscription) -> {
            ByteBuf duplicateBuffer = data.retainedDuplicate();
            Entry entry = create(0L, 0L, duplicateBuffer);
            // entry internally retains data so, duplicateBuffer should be released here
            duplicateBuffer.release();
            if (subscription.getDispatcher() != null) {
                // Dispatcher needs to call the set method to support entry filter feature.
                subscription.getDispatcher().sendMessages(Arrays.asList(entry));
            } else {
                // it happens when subscription is created but dispatcher is not created as consumer is not added
                // yet
                entry.release();
            }
        });

        if (!replicators.isEmpty()) {
            replicators.forEach((name, replicator) -> {
                ByteBuf duplicateBuffer = data.retainedDuplicate();
                Entry entry = create(0L, 0L, duplicateBuffer);
                // entry internally retains data so, duplicateBuffer should be release here
                duplicateBuffer.release();
                replicator.sendMessage(entry);
            });
        }
    }

    protected CompletableFuture<Long> incrementTopicEpoch(Optional<Long> currentEpoch) {
        // Non-persistent topic does not have any durable metadata, so we're just
        // keeping the epoch in memory
        return CompletableFuture.completedFuture(currentEpoch.orElse(-1L) + 1);
    }

    protected CompletableFuture<Long> setTopicEpoch(long newEpoch) {
        // Non-persistent topic does not have any durable metadata, so we're just
        // keeping the epoch in memory
        return CompletableFuture.completedFuture(newEpoch);
    }


    @Override
    public void checkMessageDeduplicationInfo() {
        // No-op
    }

    @Override
    public boolean shouldProducerMigrate() {
        return true;
    }

    @Override
    public boolean isReplicationBacklogExist() {
        return false;
    }

    @Override
    public CompletableFuture<Void> checkIfTransactionBufferRecoverCompletely() {
        return  CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Consumer> subscribe(SubscriptionOption option) {
        return internalSubscribe(option.getCnx(), option.getSubscriptionName(), option.getConsumerId(),
                option.getSubType(), option.getPriorityLevel(), option.getConsumerName(),
                option.getStartMessageId(), option.getMetadata(), option.isReadCompacted(),
                option.getStartMessageRollbackDurationSec(), option.getReplicatedSubscriptionStateArg(),
                option.getKeySharedMeta(), option.getSubscriptionProperties().orElse(null),
                option.getSchemaType());
    }

    @Override
    public CompletableFuture<Consumer> subscribe(final TransportCnx cnx, String subscriptionName, long consumerId,
                                                 SubType subType, int priorityLevel, String consumerName,
                                                 boolean isDurable, MessageId startMessageId,
                                                 Map<String, String> metadata, boolean readCompacted,
                                                 InitialPosition initialPosition,
                                                 long resetStartMessageBackInSec, boolean replicateSubscriptionState,
                                                 KeySharedMeta keySharedMeta) {
        return internalSubscribe(cnx, subscriptionName, consumerId, subType, priorityLevel, consumerName,
                startMessageId, metadata, readCompacted, resetStartMessageBackInSec,
                replicateSubscriptionState, keySharedMeta, null, null);
    }

    private CompletableFuture<Consumer> internalSubscribe(final TransportCnx cnx, String subscriptionName,
                                                          long consumerId, SubType subType, int priorityLevel,
                                                          String consumerName, MessageId startMessageId,
                                                          Map<String, String> metadata, boolean readCompacted,
                                                          long resetStartMessageBackInSec,
                                                          Boolean replicateSubscriptionState,
                                                          KeySharedMeta keySharedMeta,
                                                          Map<String, String> subscriptionProperties,
                                                          SchemaType schemaType) {

        return brokerService.checkTopicNsOwnership(getName()).thenCompose(__ -> {
            final CompletableFuture<Consumer> future = new CompletableFuture<>();

            if (hasBatchMessagePublished && !cnx.isBatchMessageCompatibleVersion()) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Consumer doesn't support batch-message {}", topic, subscriptionName);
                }
                future.completeExceptionally(new UnsupportedVersionException("Consumer doesn't support batch-message"));
                return future;
            }

            if (subscriptionName.startsWith(replicatorPrefix)) {
                log.warn("[{}] Failed to create subscription for {}", topic, subscriptionName);
                future.completeExceptionally(
                        new NamingException("Subscription with reserved subscription name attempted"));
                return future;
            }

            if (readCompacted) {
                future.completeExceptionally(new NotAllowedException("readCompacted only valid on persistent topics"));
                return future;
            }

            lock.readLock().lock();
            try {
                if (isFenced) {
                    log.warn("[{}] Attempting to subscribe to a fenced topic", topic);
                    future.completeExceptionally(new TopicFencedException("Topic is temporarily unavailable"));
                    return future;
                }

                handleConsumerAdded(subscriptionName, consumerName);
            } finally {
                lock.readLock().unlock();
            }

            NonPersistentSubscription subscription = subscriptions.computeIfAbsent(subscriptionName,
                    name -> new NonPersistentSubscription(this, subscriptionName, subscriptionProperties));

            Consumer consumer = new Consumer(subscription, subType, topic, consumerId, priorityLevel, consumerName,
                    false, cnx, cnx.getAuthRole(), metadata, readCompacted, keySharedMeta, MessageId.latest,
                    DEFAULT_CONSUMER_EPOCH, schemaType);
            if (isMigrated()) {
                consumer.topicMigrated(getMigratedClusterUrl());
            }

            addConsumerToSubscription(subscription, consumer).thenRun(() -> {
                if (!cnx.isActive()) {
                    try {
                        consumer.close();
                    } catch (BrokerServiceException e) {
                        if (e instanceof ConsumerBusyException) {
                            log.warn("[{}][{}] Consumer {} {} already connected", topic, subscriptionName, consumerId,
                                    consumerName);
                        } else if (e instanceof SubscriptionBusyException) {
                            log.warn("[{}][{}] {}", topic, subscriptionName, e.getMessage());
                        }

                        decrementUsageCount();
                        future.completeExceptionally(e);
                        return;
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] [{}] [{}] Subscribe failed -- count: {}", topic, subscriptionName,
                                consumer.consumerName(), currentUsageCount());
                    }
                    future.completeExceptionally(
                            new BrokerServiceException.ConnectionClosedException(
                                    "Connection was closed while the opening the cursor "));
                } else {
                    log.info("[{}][{}] Created new subscription for {}", topic, subscriptionName, consumerId);
                    future.complete(consumer);
                }
            }).exceptionally(e -> {
                Throwable throwable = e.getCause();
                if (throwable instanceof ConsumerBusyException) {
                    log.warn("[{}][{}] Consumer {} {} already connected", topic, subscriptionName, consumerId,
                            consumerName);
                } else if (throwable instanceof SubscriptionBusyException) {
                    log.warn("[{}][{}] {}", topic, subscriptionName, e.getMessage());
                }

                decrementUsageCount();
                future.completeExceptionally(throwable);
                return null;
            });

            return future;
        });
    }

    @Override
    public CompletableFuture<Subscription> createSubscription(String subscriptionName, InitialPosition initialPosition,
            boolean replicateSubscriptionState, Map<String, String> properties) {
        return CompletableFuture.completedFuture(new NonPersistentSubscription(this, subscriptionName,
                properties));
    }

    @Override
    public CompletableFuture<Void> delete() {
        return delete(false, false);
    }

    /**
     * Forcefully close all producers/consumers/replicators and deletes the topic.
     *
     * @return
     */
    @Override
    public CompletableFuture<Void> deleteForcefully() {
        return delete(false, true);
    }

    private CompletableFuture<Void> delete(boolean failIfHasSubscriptions, boolean closeIfClientsConnected) {
        CompletableFuture<Void> deleteFuture = new CompletableFuture<>();

        lock.writeLock().lock();
        try {
            if (isFenced) {
                log.warn("[{}] Topic is already being closed or deleted", topic);
                deleteFuture.completeExceptionally(new TopicFencedException("Topic is already fenced"));
                return deleteFuture;
            }

            CompletableFuture<Void> closeClientFuture = new CompletableFuture<>();
            if (closeIfClientsConnected) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                replicators.forEach((cluster, replicator) -> futures.add(replicator.terminate()));
                producers.values().forEach(producer -> futures.add(producer.disconnect()));
                subscriptions.forEach((s, sub) -> futures.add(sub.close(true, Optional.empty())));
                FutureUtil.waitForAll(futures).thenRun(() -> {
                    closeClientFuture.complete(null);
                }).exceptionally(ex -> {
                    log.error("[{}] Error closing clients", topic, ex);
                    isFenced = false;
                    closeClientFuture.completeExceptionally(ex);
                    return null;
                });
            } else {
                closeClientFuture.complete(null);
            }

            closeClientFuture.thenAccept(delete -> {

                if (currentUsageCount() == 0) {
                    isFenced = true;

                    List<CompletableFuture<Void>> futures = new ArrayList<>();

                    if (failIfHasSubscriptions) {
                        if (!subscriptions.isEmpty()) {
                            isFenced = false;
                            deleteFuture.completeExceptionally(new TopicBusyException("Topic has subscriptions:"
                                    + subscriptions.keySet().stream().toList()));
                            return;
                        }
                    } else {
                        subscriptions.forEach((s, sub) -> futures.add(sub.delete()));
                    }

                    futures.add(deleteSchema().thenApply(schemaVersion -> null));
                    futures.add(deleteTopicPolicies());
                    FutureUtil.waitForAll(futures).whenComplete((v, ex) -> {
                        if (ex != null) {
                            log.error("[{}] Error deleting topic", topic, ex);
                            isFenced = false;
                            deleteFuture.completeExceptionally(ex);
                        } else {
                            // topic GC iterates over topics map and removing from the map with the same thread creates
                            // deadlock. so, execute it in different thread
                            brokerService.executor().execute(() -> {
                                brokerService.removeTopicFromCache(NonPersistentTopic.this);
                                unregisterTopicPolicyListener();
                                log.info("[{}] Topic deleted", topic);
                                deleteFuture.complete(null);
                            });
                        }
                    });
                } else {
                    deleteFuture.completeExceptionally(new TopicBusyException(
                            "Topic has " + currentUsageCount() + " connected producers/consumers"));
                }
            }).exceptionally(ex -> {
                deleteFuture.completeExceptionally(
                        new TopicBusyException("Failed to close clients before deleting topic.",
                                FutureUtil.unwrapCompletionException(ex)));
                return null;
            });
        } finally {
            lock.writeLock().unlock();
        }

        return deleteFuture;
    }


    @Override
    public CompletableFuture<Void> close(boolean closeWithoutWaitingClientDisconnect) {
        return close(true, closeWithoutWaitingClientDisconnect);
    }

    /**
     * Close this topic - close all producers and subscriptions associated with this topic.
     *
     * @param disconnectClients disconnect clients
     * @param closeWithoutWaitingClientDisconnect don't wait for client disconnect and forcefully close managed-ledger
     * @return Completable future indicating completion of close operation
     */
    @Override
    public CompletableFuture<Void> close(
            boolean disconnectClients, boolean closeWithoutWaitingClientDisconnect) {
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();

        lock.writeLock().lock();
        try {
            if (!disconnectClients) {
                transferring = true;
            }
            if (!isFenced || closeWithoutWaitingClientDisconnect) {
                isFenced = true;
            } else {
                log.warn("[{}] Topic is already being closed or deleted", topic);
                closeFuture.completeExceptionally(new TopicFencedException("Topic is already fenced"));
                return closeFuture;
            }
        } finally {
            lock.writeLock().unlock();
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        replicators.forEach((cluster, replicator) -> futures.add(replicator.terminate()));
        if (disconnectClients) {
            futures.add(ExtensibleLoadManagerImpl.getAssignedBrokerLookupData(
                brokerService.getPulsar(), topic).thenAccept(lookupData -> {
                    producers.values().forEach(producer -> futures.add(producer.disconnect(lookupData)));
                    // Topics unloaded due to the ExtensibleLoadManager undergo closing twice: first with
                    // disconnectClients = false, second with disconnectClients = true. The check below identifies the
                    // cases when Topic.close is called outside the scope of the ExtensibleLoadManager. In these
                    // situations, we must pursue the regular Subscription.close, as Topic.close is invoked just once.
                    if (isTransferring()) {
                        subscriptions.forEach((s, sub) -> futures.add(sub.disconnect(lookupData)));
                    } else {
                        subscriptions.forEach((s, sub) -> futures.add(sub.close(true, lookupData)));
                    }
                }
            ));
        } else {
            subscriptions.forEach((s, sub) -> futures.add(sub.close(false, Optional.empty())));
        }

        if (entryFilters != null) {
            entryFilters.getRight().forEach(filter -> {
                try {
                    filter.close();
                } catch (Throwable e) {
                    log.warn("Error shutting down entry filter {}", filter, e);
                }
            });
        }

        CompletableFuture<Void> clientCloseFuture =
                closeWithoutWaitingClientDisconnect ? CompletableFuture.completedFuture(null)
                        : FutureUtil.waitForAll(futures);

        clientCloseFuture.thenRun(() -> {
            log.info("[{}] Topic closed", topic);
            // unload topic iterates over topics map and removing from the map with the same thread creates deadlock.
            // so, execute it in different thread
            brokerService.executor().execute(() -> {

                if (disconnectClients) {
                    brokerService.removeTopicFromCache(NonPersistentTopic.this);
                    unregisterTopicPolicyListener();
                }
                closeFuture.complete(null);

            });
        }).exceptionally(exception -> {
            log.error("[{}] Error closing topic", topic, exception);
            isFenced = false;
            closeFuture.completeExceptionally(exception);
            return null;
        });

        return closeFuture;
    }

    public CompletableFuture<Void> stopReplProducers() {
        List<CompletableFuture<Void>> closeFutures = new ArrayList<>();
        replicators.forEach((region, replicator) -> closeFutures.add(replicator.terminate()));
        return FutureUtil.waitForAll(closeFutures);
    }

    @Override
    public CompletableFuture<Void> checkReplication() {
        TopicName name = TopicName.get(topic);
        if (!name.isGlobal() || NamespaceService.isHeartbeatNamespace(name)
                || ExtensibleLoadManagerImpl.isInternalTopic(topic)) {
            return CompletableFuture.completedFuture(null);
        }

        if (log.isDebugEnabled()) {
            log.debug("[{}] Checking replication status", name);
        }

        Set<String> configuredClusters = new HashSet<>(topicPolicies.getReplicationClusters().get());

        String localCluster = brokerService.pulsar().getConfiguration().getClusterName();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Check for missing replicators
        for (String cluster : configuredClusters) {
            if (cluster.equals(localCluster)) {
                continue;
            }

            if (!replicators.containsKey(cluster)) {
                futures.add(startReplicator(cluster));
            }
        }

        // Check for replicators to be stopped
        replicators.forEach((cluster, replicator) -> {
            if (!cluster.equals(localCluster)) {
                if (!configuredClusters.contains(cluster)) {
                    futures.add(removeReplicator(cluster));
                }
            }
        });
        return FutureUtil.waitForAll(futures);
    }

    CompletableFuture<Void> startReplicator(String remoteCluster) {
        log.info("[{}] Starting replicator to remote: {}", topic, remoteCluster);
        String localCluster = brokerService.pulsar().getConfiguration().getClusterName();
        return addReplicationCluster(remoteCluster, NonPersistentTopic.this, localCluster);
    }

    protected CompletableFuture<Void> addReplicationCluster(String remoteCluster, NonPersistentTopic nonPersistentTopic,
            String localCluster) {
        return AbstractReplicator.validatePartitionedTopicAsync(nonPersistentTopic.getName(), brokerService)
                .thenCompose(__ -> brokerService.pulsar().getPulsarResources().getClusterResources()
                        .getClusterAsync(remoteCluster)
                        .thenApply(clusterData ->
                                brokerService.getReplicationClient(remoteCluster, clusterData)))
                .thenAccept(replicationClient -> {
                    replicators.computeIfAbsent(remoteCluster, r -> {
                        try {
                            return new NonPersistentReplicator(NonPersistentTopic.this, localCluster,
                                    remoteCluster, brokerService, (PulsarClientImpl) replicationClient);
                        } catch (PulsarServerException e) {
                            log.error("[{}] Replicator startup failed {}", topic, remoteCluster, e);
                        }
                        return null;
                    });

                    // clean up replicator if startup is failed
                    if (replicators.containsKey(remoteCluster) && replicators.get(remoteCluster) == null) {
                        replicators.remove(remoteCluster);
                    }
                });
    }

    CompletableFuture<Void> removeReplicator(String remoteCluster) {
        log.info("[{}] Removing replicator to {}", topic, remoteCluster);
        final CompletableFuture<Void> future = new CompletableFuture<>();

        String name = NonPersistentReplicator.getReplicatorName(replicatorPrefix, remoteCluster);

        replicators.get(remoteCluster).terminate().thenRun(() -> {
            log.info("[{}] Successfully removed replicator {}", name, remoteCluster);
            replicators.remove(remoteCluster);

        }).exceptionally(e -> {
            log.error("[{}] Failed to close replication producer {} {}", topic, name, e.getMessage(), e);
            future.completeExceptionally(e);
            return null;
        });

        return future;
    }

    private CompletableFuture<Void> checkReplicationAndRetryOnFailure() {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        checkReplication().thenAccept(res -> {
            log.info("[{}] Policies updated successfully", topic);
            result.complete(null);
        }).exceptionally(th -> {
            log.error("[{}] Policies update failed {}, scheduled retry in {} seconds", topic, th.getMessage(),
                    POLICY_UPDATE_FAILURE_RETRY_TIME_SECONDS, th);
            brokerService.executor().schedule(this::checkReplicationAndRetryOnFailure,
                    POLICY_UPDATE_FAILURE_RETRY_TIME_SECONDS, TimeUnit.SECONDS);
            result.completeExceptionally(th);
            return null;
        });
        return result;
    }

    @Override
    public void checkMessageExpiry() {
        // No-op
    }

    @Override
    public int getNumberOfConsumers() {
        return getNumberOfConsumers(subscriptions.values());
    }

    @Override
    public int getNumberOfSameAddressConsumers(final String clientAddress) {
        return getNumberOfSameAddressConsumers(clientAddress, subscriptions.values());
    }

    @Override
    public Map<String, NonPersistentSubscription> getSubscriptions() {
        return subscriptions;
    }

    @Override
    public Map<String, NonPersistentReplicator> getReplicators() {
        return replicators;
    }

    @Override
    public Map<String, ? extends Replicator> getShadowReplicators() {
        return Map.of();
    }

    @Override
    public Subscription getSubscription(String subscription) {
        return subscriptions.get(subscription);
    }

    public Replicator getPersistentReplicator(String remoteCluster) {
        return replicators.get(remoteCluster);
    }

    @Override
    public void updateRates(NamespaceStats nsStats, NamespaceBundleStats bundleStats,
            StatsOutputStream topicStatsStream, ClusterReplicationMetrics replStats, String namespace,
            boolean hydratePublishers) {

        TopicStats topicStats = threadLocalTopicStats.get();
        topicStats.reset();

        replicators.forEach((region, replicator) -> replicator.updateRates());

        final MutableInt producerCount = new MutableInt();
        topicStatsStream.startObject(topic);

        topicStatsStream.startList("publishers");
        producers.values().forEach(producer -> {
            producer.updateRates();
            PublisherStatsImpl publisherStats = producer.getStats();

            topicStats.aggMsgRateIn += publisherStats.msgRateIn;
            topicStats.aggMsgThroughputIn += publisherStats.msgThroughputIn;

            if (producer.isRemote()) {
                topicStats.remotePublishersStats.put(producer.getRemoteCluster(), publisherStats);
            } else {
                // Exclude producers for replication from "publishers" and "producerCount"
                producerCount.increment();
                if (hydratePublishers) {
                    StreamingStats.writePublisherStats(topicStatsStream, publisherStats);
                }
            }
        });
        topicStatsStream.endList();

        nsStats.producerCount += producerCount.intValue();
        bundleStats.producerCount += producerCount.intValue();

        // Start replicator stats
        topicStatsStream.startObject("replication");
        nsStats.replicatorCount += topicStats.remotePublishersStats.size();

        // Close replication
        topicStatsStream.endObject();

        // Start subscription stats
        topicStatsStream.startObject("subscriptions");
        nsStats.subsCount += subscriptions.size();

        subscriptions.forEach((subscriptionName, subscription) -> {
            double subMsgRateOut = 0;
            double subMsgThroughputOut = 0;
            double subMsgRateRedeliver = 0;
            double subMsgAckRate = 0;

            // Start subscription name & consumers
            try {
                topicStatsStream.startObject(subscriptionName);
                topicStatsStream.startList("consumers");

                for (Consumer consumer : subscription.getConsumers()) {
                    ++nsStats.consumerCount;
                    ++bundleStats.consumerCount;

                    consumer.updateRates();

                    ConsumerStatsImpl consumerStats = consumer.getStats();
                    subMsgRateOut += consumerStats.msgRateOut;
                    subMsgAckRate += consumerStats.messageAckRate;

                    subMsgThroughputOut += consumerStats.msgThroughputOut;
                    subMsgRateRedeliver += consumerStats.msgRateRedeliver;

                    // Populate consumer specific stats here
                    StreamingStats.writeConsumerStats(topicStatsStream, subscription.getType(), consumerStats);
                }

                // Close Consumer stats
                topicStatsStream.endList();

                // Populate subscription specific stats here
                topicStatsStream.writePair("msgBacklog", subscription.getNumberOfEntriesInBacklog(false));
                topicStatsStream.writePair("msgRateExpired", subscription.getExpiredMessageRate());
                topicStatsStream.writePair("msgRateOut", subMsgRateOut);
                topicStatsStream.writePair("messageAckRate", subMsgAckRate);
                topicStatsStream.writePair("msgThroughputOut", subMsgThroughputOut);
                topicStatsStream.writePair("msgRateRedeliver", subMsgRateRedeliver);
                topicStatsStream.writePair("type", subscription.getTypeString());

                // Write entry filter stats
                Dispatcher dispatcher0 = subscription.getDispatcher();
                if (null != dispatcher0) {
                    topicStatsStream.writePair("filterProcessedMsgCount",
                            dispatcher0.getFilterProcessedMsgCount());
                    topicStatsStream.writePair("filterAcceptedMsgCount",
                            dispatcher0.getFilterAcceptedMsgCount());
                    topicStatsStream.writePair("filterRejectedMsgCount",
                            dispatcher0.getFilterRejectedMsgCount());
                    topicStatsStream.writePair("filterRescheduledMsgCount",
                            dispatcher0.getFilterRescheduledMsgCount());
                }

                if (subscription.getDispatcher() != null) {
                    subscription.getDispatcher().getMessageDropRate().calculateRate();
                    topicStatsStream.writePair("msgDropRate",
                            subscription.getDispatcher().getMessageDropRate().getValueRate());
                }

                // Close consumers
                topicStatsStream.endObject();

                topicStats.aggMsgRateOut += subMsgRateOut;
                topicStats.aggMsgThroughputOut += subMsgThroughputOut;
                nsStats.msgBacklog += subscription.getNumberOfEntriesInBacklog(false);
            } catch (Exception e) {
                log.error("Got exception when creating consumer stats for subscription {}: {}", subscriptionName,
                        e.getMessage(), e);
            }
        });

        // Close subscription
        topicStatsStream.endObject();

        // Remaining dest stats.
        topicStats.averageMsgSize = topicStats.aggMsgRateIn == 0.0 ? 0.0
                : (topicStats.aggMsgThroughputIn / topicStats.aggMsgRateIn);
        topicStatsStream.writePair("producerCount", producerCount.intValue());
        topicStatsStream.writePair("averageMsgSize", topicStats.averageMsgSize);
        topicStatsStream.writePair("msgRateIn", topicStats.aggMsgRateIn);
        topicStatsStream.writePair("msgRateOut", topicStats.aggMsgRateOut);
        topicStatsStream.writePair("msgThroughputIn", topicStats.aggMsgThroughputIn);
        topicStatsStream.writePair("msgThroughputOut", topicStats.aggMsgThroughputOut);
        topicStatsStream.writePair("msgInCount", getMsgInCounter());
        topicStatsStream.writePair("bytesInCount", getBytesInCounter());
        topicStatsStream.writePair("msgOutCount", getMsgOutCounter());
        topicStatsStream.writePair("bytesOutCount", getBytesOutCounter());
        topicStatsStream.writePair("filteredEntriesCount", getFilteredEntriesCount());

        nsStats.msgRateIn += topicStats.aggMsgRateIn;
        nsStats.msgRateOut += topicStats.aggMsgRateOut;
        nsStats.msgThroughputIn += topicStats.aggMsgThroughputIn;
        nsStats.msgThroughputOut += topicStats.aggMsgThroughputOut;

        bundleStats.msgRateIn += topicStats.aggMsgRateIn;
        bundleStats.msgRateOut += topicStats.aggMsgRateOut;
        bundleStats.msgThroughputIn += topicStats.aggMsgThroughputIn;
        bundleStats.msgThroughputOut += topicStats.aggMsgThroughputOut;
        // add publish-latency metrics
        this.addEntryLatencyStatsUsec.refresh();
        NamespaceStats.add(this.addEntryLatencyStatsUsec.getBuckets(), nsStats.addLatencyBucket);
        this.addEntryLatencyStatsUsec.reset();
        // Close topic object
        topicStatsStream.endObject();
    }

    @Override
    public NonPersistentTopicStatsImpl getStats(boolean getPreciseBacklog, boolean subscriptionBacklogSize,
                                                boolean getEarliestTimeInBacklog) {
        try {
            return asyncGetStats(getPreciseBacklog, subscriptionBacklogSize, getPreciseBacklog).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("[{}] Fail to get stats", topic, e);
            return null;
        }
    }

    @Override
    public TopicStatsImpl getStats(GetStatsOptions getStatsOptions) {
        try {
            return asyncGetStats(getStatsOptions).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("[{}] Fail to get stats", topic, e);
            return null;
        }
    }

    @Override
    public CompletableFuture<NonPersistentTopicStatsImpl> asyncGetStats(boolean getPreciseBacklog,
                                                                        boolean subscriptionBacklogSize,
                                                                        boolean getEarliestTimeInBacklog) {
        GetStatsOptions getStatsOptions = new GetStatsOptions(getPreciseBacklog, subscriptionBacklogSize,
                getEarliestTimeInBacklog, false, false);
        return (CompletableFuture<NonPersistentTopicStatsImpl>) asyncGetStats(getStatsOptions);
    }

    @Override
    public CompletableFuture<? extends TopicStatsImpl> asyncGetStats(GetStatsOptions getStatsOptions) {
        CompletableFuture<NonPersistentTopicStatsImpl> future = new CompletableFuture<>();
        NonPersistentTopicStatsImpl stats = new NonPersistentTopicStatsImpl();

        ObjectObjectHashMap<String, PublisherStatsImpl> remotePublishersStats = new ObjectObjectHashMap<>();

        producers.values().forEach(producer -> {
            NonPersistentPublisherStatsImpl publisherStats = (NonPersistentPublisherStatsImpl) producer.getStats();
            stats.msgRateIn += publisherStats.msgRateIn;
            stats.msgThroughputIn += publisherStats.msgThroughputIn;

            if (producer.isRemote()) {
                remotePublishersStats.put(producer.getRemoteCluster(), publisherStats);
            } else if (!getStatsOptions.isExcludePublishers()) {
                // Exclude producers for replication from "publishers"
                stats.addPublisher(publisherStats);
            }
        });

        stats.averageMsgSize = stats.msgRateIn == 0.0 ? 0.0 : (stats.msgThroughputIn / stats.msgRateIn);
        stats.msgInCounter = getMsgInCounter();
        stats.bytesInCounter = getBytesInCounter();
        stats.systemTopicBytesInCounter = getSystemTopicBytesInCounter();
        stats.waitingPublishers = getWaitingProducersCount();
        stats.bytesOutCounter = bytesOutFromRemovedSubscriptions.longValue();
        stats.msgOutCounter = msgOutFromRemovedSubscriptions.longValue();
        stats.bytesOutInternalCounter = bytesOutFromRemovedSystemSubscriptions.longValue();

        subscriptions.forEach((name, subscription) -> {
            NonPersistentSubscriptionStatsImpl subStats = subscription.getStats(getStatsOptions);

            stats.msgRateOut += subStats.msgRateOut;
            stats.msgThroughputOut += subStats.msgThroughputOut;
            stats.bytesOutCounter += subStats.bytesOutCounter;
            stats.msgOutCounter += subStats.msgOutCounter;
            stats.getSubscriptions().put(name, subStats);

            if (isSystemCursor(name)) {
                stats.bytesOutInternalCounter += subStats.bytesOutCounter;
            }
        });

        replicators.forEach((cluster, replicator) -> {
            NonPersistentReplicatorStatsImpl replicatorStats = replicator.computeStats();

            // Add incoming msg rates
            PublisherStatsImpl pubStats = remotePublishersStats.get(replicator.getRemoteCluster());
            if (pubStats != null) {
                replicatorStats.msgRateIn = pubStats.msgRateIn;
                replicatorStats.msgThroughputIn = pubStats.msgThroughputIn;
                replicatorStats.inboundConnection = pubStats.getAddress();
                replicatorStats.inboundConnectedSince = pubStats.getConnectedSince();
            }

            stats.msgRateOut += replicatorStats.msgRateOut;
            stats.msgThroughputOut += replicatorStats.msgThroughputOut;

            stats.getReplication().put(replicator.getRemoteCluster(), replicatorStats);
        });

        stats.topicEpoch = topicEpoch.orElse(null);
        stats.ownerBroker = brokerService.pulsar().getBrokerId();
        future.complete(stats);
        return future;
    }

    @Override
    public CompletableFuture<PersistentTopicInternalStats> getInternalStats(boolean includeLedgerMetadata) {

        PersistentTopicInternalStats stats = new PersistentTopicInternalStats();
        stats.entriesAddedCounter = ENTRIES_ADDED_COUNTER_UPDATER.get(this);

        stats.cursors = new TreeMap<>();
        subscriptions.forEach((name, subs) -> stats.cursors.put(name, new CursorStats()));
        replicators.forEach((name, subs) -> stats.cursors.put(name, new CursorStats()));

        return CompletableFuture.completedFuture(stats);
    }

    public boolean isActive() {
        if (TopicName.get(topic).isGlobal()) {
            // No local consumers and no local producers
            return !subscriptions.isEmpty() || hasLocalProducers();
        }
        return currentUsageCount() != 0 || !subscriptions.isEmpty();
    }

    @Override
    public CompletableFuture<Void> checkClusterMigration() {
        if (ExtensibleLoadManagerImpl.isInternalTopic(topic)) {
            return CompletableFuture.completedFuture(null);
        }

        Optional<ClusterUrl> url = getMigratedClusterUrl();
        if (url.isPresent()) {
            this.migrated = true;
            producers.forEach((__, producer) -> {
                producer.topicMigrated(url);
            });
            subscriptions.forEach((__, sub) -> {
                sub.getConsumers().forEach((consumer) -> {
                    consumer.topicMigrated(url);
                });
            });
            return disconnectReplicators().thenCompose(__ -> checkAndUnsubscribeSubscriptions());
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> checkAndUnsubscribeSubscriptions() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        subscriptions.forEach((s, subscription) -> {
            if (subscription.getConsumers().isEmpty()) {
                futures.add(subscription.delete());
            }
        });

        return FutureUtil.waitForAll(futures);
    }

    private CompletableFuture<Void> disconnectReplicators() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        replicators.forEach((r, replicator) -> {
            futures.add(replicator.terminate());
        });
        return FutureUtil.waitForAll(futures);
    }

    @Override
    public void checkGC() {
        if (!isDeleteWhileInactive()) {
            // This topic is not included in GC
            return;
        }
        int maxInactiveDurationInSec = topicPolicies.getInactiveTopicPolicies().get().getMaxInactiveDurationSeconds();
        if (isActive()) {
            lastActive = System.nanoTime();
        } else {
            if (System.nanoTime() - lastActive > TimeUnit.SECONDS.toNanos(maxInactiveDurationInSec)) {

                if (TopicName.get(topic).isGlobal()) {
                    // For global namespace, close repl producers first.
                    // Once all repl producers are closed, we can delete the topic,
                    // provided no remote producers connected to the broker.
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Global topic inactive for {} seconds, closing repl producers.", topic,
                            maxInactiveDurationInSec);
                    }

                    stopReplProducers().thenCompose(v -> delete(true, false))
                            .thenCompose(__ -> tryToDeletePartitionedMetadata())
                            .thenRun(() -> log.info("[{}] Topic deleted successfully due to inactivity", topic))
                            .exceptionally(e -> {
                                Throwable throwable = e.getCause();
                                if (throwable instanceof TopicBusyException) {
                                    // topic became active again
                                    if (log.isDebugEnabled()) {
                                        log.debug("[{}] Did not delete busy topic: {}", topic,
                                                throwable.getMessage());
                                    }
                                    replicators.forEach((region, replicator) -> replicator.startProducer());
                                } else {
                                    log.warn("[{}] Inactive topic deletion failed", topic, e);
                                }
                                return null;
                            });

                }
            }
        }
    }

    private CompletableFuture<Void> tryToDeletePartitionedMetadata() {
        if (TopicName.get(topic).isPartitioned() && !deletePartitionedTopicMetadataWhileInactive()) {
            return CompletableFuture.completedFuture(null);
        }
        TopicName topicName = TopicName.get(TopicName.get(topic).getPartitionedTopicName());
        NamespaceResources.PartitionedTopicResources partitionedTopicResources = brokerService.pulsar()
                .getPulsarResources().getNamespaceResources().getPartitionedTopicResources();
        return partitionedTopicResources.partitionedTopicExistsAsync(topicName)
                .thenCompose(partitionedTopicExist -> {
                    if (!partitionedTopicExist) {
                        return CompletableFuture.completedFuture(null);
                    } else {
                        return getBrokerService().pulsar().getPulsarResources().getNamespaceResources()
                                .getPartitionedTopicResources().runWithMarkDeleteAsync(topicName,
                                        () -> partitionedTopicResources.deletePartitionedTopicAsync(topicName));
                    }
                });
    }

    @Override
    public void checkInactiveSubscriptions() {
        // no-op
        // subscriptions will be removed after all the consumers disconnected.
    }

    @Override
    public void checkBackloggedCursors() {
        // no-op
    }

    @Override
    public void checkCursorsToCacheEntries() {
        // no-op
    }

    @Override
    public void checkDeduplicationSnapshot() {
        // no-op
    }

    @Override
    public CompletableFuture<Void> onPoliciesUpdate(Policies data) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] isEncryptionRequired changes: {} -> {}", topic, isEncryptionRequired,
                    data.encryption_required);
        }

        updateTopicPolicyByNamespacePolicy(data);

        isEncryptionRequired = data.encryption_required;
        isAllowAutoUpdateSchema = data.is_allow_auto_update_schema;

        List<CompletableFuture<Void>> producerCheckFutures = new ArrayList<>(producers.size());
        producers.values().forEach(producer -> producerCheckFutures.add(
                producer.checkPermissionsAsync().thenRun(producer::checkEncryption)));

        return FutureUtil.waitForAll(producerCheckFutures).thenCompose((__) -> {
            List<CompletableFuture<Void>> consumerCheckFutures = new ArrayList<>();
            subscriptions.forEach((subName, sub) -> sub.getConsumers().forEach(consumer -> {
                consumerCheckFutures.add(consumer.checkPermissionsAsync());
            }));

            return FutureUtil.waitForAll(consumerCheckFutures)
                    .thenCompose((___) -> checkReplicationAndRetryOnFailure());
        }).exceptionally(ex -> {
            log.error("[{}] update namespace polices : {} error", this.getName(), data, ex);
            throw FutureUtil.wrapToCompletionException(ex);
        });
    }

    @Override
    public void onUpdate(TopicPolicies data) {
        if (data == null) {
            return;
        }
        updateTopicPolicy(data);
    }

    /**
     *
     * @return Backlog quota for topic
     */
    @Override
    public BacklogQuota getBacklogQuota(BacklogQuotaType backlogQuotaType) {
        // No-op
        throw new UnsupportedOperationException("getBacklogQuota method is not supported on non-persistent topic");
    }

    /**
     *
     * @return quota exceeded status for blocking producer creation
     */
    @Override
    public CompletableFuture<Void> checkBacklogQuotaExceeded(String producerName, BacklogQuotaType backlogQuotaType) {
        // No-op
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isReplicated() {
        return replicators.size() > 1;
    }

    @Override
    public boolean isShadowReplicated() {
        return false;
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String subscriptionName) {
        // checkInactiveSubscriptions iterates over subscriptions map and removing from the map with the same thread.
        // That creates deadlock. so, execute remove it in different thread.
        return CompletableFuture.runAsync(() -> {
            NonPersistentSubscription sub = subscriptions.remove(subscriptionName);
            if (sub != null) {
                // preserve accumulative stats form removed subscription
                GetStatsOptions getStatsOptions = new GetStatsOptions(false, false, false, false, false);
                SubscriptionStatsImpl stats = sub.getStats(getStatsOptions);
                bytesOutFromRemovedSubscriptions.add(stats.bytesOutCounter);
                msgOutFromRemovedSubscriptions.add(stats.msgOutCounter);

                if (isSystemCursor(subscriptionName)
                        || subscriptionName.startsWith(SystemTopicNames.SYSTEM_READER_PREFIX)) {
                    bytesOutFromRemovedSystemSubscriptions.add(stats.bytesOutCounter);
                }
            }
        }, brokerService.executor());
    }

    @Override
    public Position getLastPosition() {
        throw new UnsupportedOperationException("getLastPosition is not supported on non-persistent topic");
    }

    @Override
    public CompletableFuture<MessageId> getLastMessageId() {
        throw new UnsupportedOperationException("getLastMessageId is not supported on non-persistent topic");
    }

    private static final Logger log = LoggerFactory.getLogger(NonPersistentTopic.class);

    @Override
    public CompletableFuture<Void> addSchemaIfIdleOrCheckCompatible(SchemaData schema) {
        return hasSchema().thenCompose((hasSchema) -> {
            int numActiveConsumersWithoutAutoSchema = subscriptions.values().stream()
                    .mapToInt(subscription -> subscription.getConsumers().stream()
                            .filter(consumer -> consumer.getSchemaType() != SchemaType.AUTO_CONSUME)
                            .toList().size())
                    .sum();
            if (hasSchema
                    || (!producers.isEmpty())
                    || (numActiveConsumersWithoutAutoSchema != 0)
                    || ENTRIES_ADDED_COUNTER_UPDATER.get(this) != 0) {
                return checkSchemaCompatibleForConsumer(schema)
                        .exceptionally(ex -> {
                            Throwable realCause = FutureUtil.unwrapCompletionException(ex);
                            if (realCause instanceof NotExistSchemaException) {
                                throw FutureUtil.wrapToCompletionException(
                                        new IncompatibleSchemaException("Failed to add schema to an active topic"
                                                + " with empty(BYTES) schema: new schema type " + schema.getType()));
                            }
                            throw FutureUtil.wrapToCompletionException(realCause);
                        });
            } else {
                return addSchema(schema).thenCompose(schemaVersion -> CompletableFuture.completedFuture(null));
            }
        });
    }

    @Override
    public void publishTxnMessage(TxnID txnID, ByteBuf headersAndPayload, PublishContext publishContext) {
        throw new UnsupportedOperationException("PublishTxnMessage is not supported by non-persistent topic");
    }

    @Override
    public CompletableFuture<Void> endTxn(TxnID txnID, int txnAction, long lowWaterMark) {
        return FutureUtil.failedFuture(
                new Exception("Unsupported operation endTxn in non-persistent topic."));
    }

    @Override
    public CompletableFuture<Void> truncate() {
        return FutureUtil.failedFuture(new NotAllowedException("Unsupported truncate"));
    }

    protected boolean isTerminated() {
        return false;
    }


    @Override
    protected boolean isMigrated() {
        return this.migrated;
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public long getBestEffortOldestUnacknowledgedMessageAgeSeconds() {
        return -1;
    }

    @Override
    public TopicAttributes getTopicAttributes() {
        if (topicAttributes != null) {
            return topicAttributes;
        }
        return TOPIC_ATTRIBUTES_FIELD_UPDATER.updateAndGet(this,
                old -> old != null ? old : new TopicAttributes(TopicName.get(topic)));
    }
}
