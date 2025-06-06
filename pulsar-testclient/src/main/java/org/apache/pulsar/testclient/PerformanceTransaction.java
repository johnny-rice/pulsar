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
package org.apache.pulsar.testclient;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.apache.pulsar.testclient.PerfClientUtils.addShutdownHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.util.concurrent.RateLimiter;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;
import org.HdrHistogram.Recorder;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminBuilder;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.transaction.Transaction;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.testclient.utils.PaddingDecimalFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "transaction", description = "Test pulsar transaction performance.")
public class PerformanceTransaction extends PerformanceBaseArguments{

    private static final LongAdder totalNumEndTxnOpFailed = new LongAdder();
    private static final LongAdder totalNumEndTxnOpSuccess = new LongAdder();
    private static final LongAdder numTxnOpSuccess = new LongAdder();
    private static final LongAdder totalNumTxnOpenTxnFail = new LongAdder();
    private static final LongAdder totalNumTxnOpenTxnSuccess = new LongAdder();

    private static final LongAdder numMessagesAckFailed = new LongAdder();
    private static final LongAdder numMessagesAckSuccess = new LongAdder();
    private static final LongAdder numMessagesSendFailed = new LongAdder();
    private static final LongAdder numMessagesSendSuccess = new LongAdder();

    private static final Recorder messageAckRecorder =
            new Recorder(TimeUnit.SECONDS.toMicros(120000), 5);
    private static final Recorder messageAckCumulativeRecorder =
            new Recorder(TimeUnit.SECONDS.toMicros(120000), 5);

    private static final Recorder messageSendRecorder =
            new Recorder(TimeUnit.SECONDS.toMicros(120000), 5);
    private static final Recorder messageSendRCumulativeRecorder =
            new Recorder(TimeUnit.SECONDS.toMicros(120000), 5);

    @Option(names = "--topics-c", description = "All topics that need ack for a transaction", required =
            true)
    public List<String> consumerTopic = Collections.singletonList("test-consume");

    @Option(names = "--topics-p", description = "All topics that need produce for a transaction",
            required = true)
    public List<String> producerTopic = Collections.singletonList("test-produce");

    @Option(names = {"-threads", "--num-test-threads"}, description = "Number of test threads."
            + "This thread is for a new transaction to ack messages from consumer topics and produce message to "
            + "producer topics, and then commit or abort this transaction. "
            + "Increasing the number of threads increases the parallelism of the performance test, "
            + "thereby increasing the intensity of the stress test.")
    public int numTestThreads = 1;

    @Option(names = {"-au", "--admin-url"}, description = "Pulsar Admin URL", descriptionKey = "webServiceUrl")
    public String adminURL;

    @Option(names = {"-np",
            "--partitions"}, description = "Create partitioned topics with a given number of partitions, 0 means"
            + "not trying to create a topic")
    public Integer partitions = null;

    @Option(names = {"-time",
            "--test-duration"}, description = "Test duration (in second). 0 means keeping publishing")
    public long testTime = 0;

    @Option(names = {"-ss",
            "--subscriptions"}, description = "A list of subscriptions to consume (for example, sub1,sub2)")
    public List<String> subscriptions = Collections.singletonList("sub");

    @Option(names = {"-ns", "--num-subscriptions"}, description = "Number of subscriptions (per topic)")
    public int numSubscriptions = 1;

    @Option(names = {"-sp", "--subscription-position"}, description = "Subscription position")
    private SubscriptionInitialPosition subscriptionInitialPosition = SubscriptionInitialPosition.Earliest;

    @Option(names = {"-st", "--subscription-type"}, description = "Subscription type")
    public SubscriptionType subscriptionType = SubscriptionType.Shared;

    @Option(names = {"-rs", "--replicated" },
            description = "Whether the subscription status should be replicated")
    private boolean replicatedSubscription = false;

    @Option(names = {"-q", "--receiver-queue-size"}, description = "Size of the receiver queue")
    public int receiverQueueSize = 1000;

    @Option(names = {"-tto", "--txn-timeout"}, description = "Set the time value of transaction timeout,"
            + " and the time unit is second. (After --txn-enable setting to true, --txn-timeout takes effect)")
    public long transactionTimeout = 5;

    @Option(names = {"-ntxn",
            "--number-txn"}, description = "Set the number of transaction. 0 means keeping open."
            + "If transaction disabled, it means the number of tasks. The task or transaction produces or "
            + "consumes a specified number of messages.")
    public long numTransactions = 0;

    @Option(names = {"-nmp", "--numMessage-perTransaction-produce"},
            description = "Set the number of messages produced in  a transaction."
                    + "If transaction disabled, it means the number of messages produced in a task.")
    public int numMessagesProducedPerTransaction = 1;

    @Option(names = {"-nmc", "--numMessage-perTransaction-consume"},
            description = "Set the number of messages consumed in a transaction."
                    + "If transaction disabled, it means the number of messages consumed in a task.")
    public int numMessagesReceivedPerTransaction = 1;

    @Option(names = {"--txn-disable"}, description = "Disable transaction")
    public boolean isDisableTransaction = false;

    @Option(names = {"-abort"}, description = "Abort the transaction. (After --txn-disEnable "
            + "setting to false, -abort takes effect)")
    public boolean isAbortTransaction = false;

    @Option(names = "-txnRate", description = "Set the rate of opened transaction or task. 0 means no limit")
    public int openTxnRate = 0;
    public PerformanceTransaction() {
        super("transaction");
    }

    @Override
    public void run() throws Exception {
        super.parseCLI();

        // Dump config variables
        PerfClientUtils.printJVMInformation(log);
        ObjectMapper m = new ObjectMapper();
        ObjectWriter w = m.writerWithDefaultPrettyPrinter();
        log.info("Starting Pulsar perf transaction with config: {}", w.writeValueAsString(this));

        final byte[] payloadBytes = new byte[1024];
        Random random = new Random(0);
        for (int i = 0; i < payloadBytes.length; ++i) {
            payloadBytes[i] = (byte) (random.nextInt(26) + 65);
        }
        if (this.partitions != null) {
            final PulsarAdminBuilder adminBuilder = PerfClientUtils
                    .createAdminBuilderFromArguments(this, this.adminURL);

            try (PulsarAdmin adminClient = adminBuilder.build()) {
                for (String topic : this.producerTopic) {
                    log.info("Creating  produce partitioned topic {} with {} partitions", topic, this.partitions);
                    try {
                        adminClient.topics().createPartitionedTopic(topic, this.partitions);
                    } catch (PulsarAdminException.ConflictException alreadyExists) {
                        if (log.isDebugEnabled()) {
                            log.debug("Topic {} already exists: {}", topic, alreadyExists);
                        }
                        PartitionedTopicMetadata partitionedTopicMetadata =
                                adminClient.topics().getPartitionedTopicMetadata(topic);
                        if (partitionedTopicMetadata.partitions != this.partitions) {
                            log.error(
                                    "Topic {} already exists but it has a wrong number of partitions: {}, expecting {}",
                                    topic, partitionedTopicMetadata.partitions, this.partitions);
                            PerfClientUtils.exit(1);
                        }
                    }
                }
            }
        }

        ClientBuilder clientBuilder = PerfClientUtils.createClientBuilderFromArguments(this)
                .enableTransaction(!this.isDisableTransaction);

        PulsarClient client = clientBuilder.build();
        try {

            ExecutorService executorService = new ThreadPoolExecutor(this.numTestThreads,
                    this.numTestThreads,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>());


            long startTime = System.nanoTime();
            long testEndTime = startTime + (long) (this.testTime * 1e9);
            Thread shutdownHookThread = addShutdownHook(() -> {
                if (!this.isDisableTransaction) {
                    printTxnAggregatedThroughput(startTime);
                } else {
                    printAggregatedThroughput(startTime);
                }
                printAggregatedStats();
            });

            // start perf test
            AtomicBoolean executing = new AtomicBoolean(true);

            RateLimiter rateLimiter = this.openTxnRate > 0
                    ? RateLimiter.create(this.openTxnRate)
                    : null;
            for (int i = 0; i < this.numTestThreads; i++) {
                executorService.submit(() -> {
                    //The producer and consumer clients are built in advance, and then this thread is
                    //responsible for the production and consumption tasks of the transaction through the loop.
                    //A thread may perform tasks of multiple transactions in a traversing manner.
                    List<Producer<byte[]>> producers = null;
                    List<List<Consumer<byte[]>>> consumers = null;
                    AtomicReference<Transaction> atomicReference = null;
                    try {
                        producers = buildProducers(client);
                        consumers = buildConsumer(client);
                        if (!this.isDisableTransaction) {
                            atomicReference = new AtomicReference<>(client.newTransaction()
                                    .withTransactionTimeout(this.transactionTimeout, TimeUnit.SECONDS)
                                    .build()
                                    .get());
                        } else {
                            atomicReference = new AtomicReference<>(null);
                        }
                    } catch (Exception e) {
                        if (PerfClientUtils.hasInterruptedException(e)) {
                            Thread.currentThread().interrupt();
                        } else {
                            log.error("Failed to build Producer/Consumer with exception : ", e);
                        }
                        executorService.shutdownNow();
                        PerfClientUtils.exit(1);
                    }
                    //The while loop has no break, and finally ends the execution through the shutdownNow of
                    //the executorService
                    while (!Thread.currentThread().isInterrupted()) {
                        if (this.numTransactions > 0) {
                            if (totalNumTxnOpenTxnFail.sum()
                                    + totalNumTxnOpenTxnSuccess.sum() >= this.numTransactions) {
                                if (totalNumEndTxnOpFailed.sum()
                                        + totalNumEndTxnOpSuccess.sum() < this.numTransactions) {
                                    continue;
                                }
                                log.info("------------------- DONE -----------------------");
                                executing.compareAndSet(true, false);
                                executorService.shutdownNow();
                                PerfClientUtils.exit(0);
                                break;
                            }
                        }
                        if (this.testTime > 0) {
                            if (System.nanoTime() > testEndTime) {
                                log.info("------------------- DONE -----------------------");
                                executing.compareAndSet(true, false);
                                executorService.shutdownNow();
                                PerfClientUtils.exit(0);
                                break;
                            }
                        }
                        Transaction transaction = atomicReference.get();
                        for (List<Consumer<byte[]>> subscriptions : consumers) {
                            for (Consumer<byte[]> consumer : subscriptions) {
                                for (int j = 0; j < this.numMessagesReceivedPerTransaction; j++) {
                                    Message<byte[]> message = null;
                                    try {
                                        message = consumer.receive();
                                    } catch (PulsarClientException e) {
                                        log.error("Receive message failed", e);
                                        executorService.shutdownNow();
                                        PerfClientUtils.exit(1);
                                    }
                                    long receiveTime = System.nanoTime();
                                    if (!this.isDisableTransaction) {
                                        consumer.acknowledgeAsync(message.getMessageId(), transaction)
                                                .thenRun(() -> {
                                                    long latencyMicros = NANOSECONDS.toMicros(
                                                            System.nanoTime() - receiveTime);
                                                    messageAckRecorder.recordValue(latencyMicros);
                                                    messageAckCumulativeRecorder.recordValue(latencyMicros);
                                                    numMessagesAckSuccess.increment();
                                                }).exceptionally(exception -> {
                                                    if (PerfClientUtils.hasInterruptedException(exception)) {
                                                        Thread.currentThread().interrupt();
                                                        return null;
                                                    }
                                                    log.error(
                                                            "Ack message failed with transaction {} throw exception",
                                                            transaction, exception);
                                                    numMessagesAckFailed.increment();
                                                    return null;
                                                });
                                    } else {
                                        consumer.acknowledgeAsync(message).thenRun(() -> {
                                            long latencyMicros = NANOSECONDS.toMicros(
                                                    System.nanoTime() - receiveTime);
                                            messageAckRecorder.recordValue(latencyMicros);
                                            messageAckCumulativeRecorder.recordValue(latencyMicros);
                                            numMessagesAckSuccess.increment();
                                        }).exceptionally(exception -> {
                                            if (PerfClientUtils.hasInterruptedException(exception)) {
                                                Thread.currentThread().interrupt();
                                                return null;
                                            }
                                            log.error(
                                                    "Ack message failed with transaction {} throw exception",
                                                    transaction, exception);
                                            numMessagesAckFailed.increment();
                                            return null;
                                        });
                                    }
                                }
                            }
                        }

                        for (Producer<byte[]> producer : producers) {
                            for (int j = 0; j < this.numMessagesProducedPerTransaction; j++) {
                                long sendTime = System.nanoTime();
                                if (!this.isDisableTransaction) {
                                    producer.newMessage(transaction).value(payloadBytes)
                                            .sendAsync().thenRun(() -> {
                                                long latencyMicros = NANOSECONDS.toMicros(
                                                        System.nanoTime() - sendTime);
                                                messageSendRecorder.recordValue(latencyMicros);
                                                messageSendRCumulativeRecorder.recordValue(latencyMicros);
                                                numMessagesSendSuccess.increment();
                                            }).exceptionally(exception -> {
                                                if (PerfClientUtils.hasInterruptedException(exception)) {
                                                    Thread.currentThread().interrupt();
                                                    return null;
                                                }
                                                // Ignore the exception when the producer is closed
                                                if (exception.getCause()
                                                        instanceof PulsarClientException.AlreadyClosedException) {
                                                    return null;
                                                }
                                                log.error("Send transaction message failed with exception : ",
                                                        exception);
                                                numMessagesSendFailed.increment();
                                                return null;
                                            });
                                } else {
                                    producer.newMessage().value(payloadBytes)
                                            .sendAsync().thenRun(() -> {
                                                long latencyMicros = NANOSECONDS.toMicros(
                                                        System.nanoTime() - sendTime);
                                                messageSendRecorder.recordValue(latencyMicros);
                                                messageSendRCumulativeRecorder.recordValue(latencyMicros);
                                                numMessagesSendSuccess.increment();
                                            }).exceptionally(exception -> {
                                                if (PerfClientUtils.hasInterruptedException(exception)) {
                                                    Thread.currentThread().interrupt();
                                                    return null;
                                                }
                                                // Ignore the exception when the producer is closed
                                                if (exception.getCause()
                                                        instanceof PulsarClientException.AlreadyClosedException) {
                                                    return null;
                                                }
                                                log.error("Send message failed with exception : ", exception);
                                                numMessagesSendFailed.increment();
                                                return null;
                                            });
                                }
                            }
                        }

                        if (rateLimiter != null) {
                            rateLimiter.tryAcquire();
                        }
                        if (!this.isDisableTransaction) {
                            if (!this.isAbortTransaction) {
                                transaction.commit()
                                        .thenRun(() -> {
                                            numTxnOpSuccess.increment();
                                            totalNumEndTxnOpSuccess.increment();
                                        }).exceptionally(exception -> {
                                            if (PerfClientUtils.hasInterruptedException(exception)) {
                                                Thread.currentThread().interrupt();
                                                return null;
                                            }
                                            log.error("Commit transaction {} failed with exception",
                                                    transaction.getTxnID().toString(),
                                                    exception);
                                            totalNumEndTxnOpFailed.increment();
                                            return null;
                                        });
                            } else {
                                transaction.abort().thenRun(() -> {
                                    numTxnOpSuccess.increment();
                                    totalNumEndTxnOpSuccess.increment();
                                }).exceptionally(exception -> {
                                    if (PerfClientUtils.hasInterruptedException(exception)) {
                                        Thread.currentThread().interrupt();
                                        return null;
                                    }
                                    log.error("Commit transaction {} failed with exception",
                                            transaction.getTxnID().toString(),
                                            exception);
                                    totalNumEndTxnOpFailed.increment();
                                    return null;
                                });
                            }
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    Transaction newTransaction = client.newTransaction()
                                            .withTransactionTimeout(this.transactionTimeout, TimeUnit.SECONDS)
                                            .build()
                                            .get();
                                    atomicReference.compareAndSet(transaction, newTransaction);
                                    totalNumTxnOpenTxnSuccess.increment();
                                    break;
                                } catch (Exception throwable) {
                                    if (PerfClientUtils.hasInterruptedException(throwable)) {
                                        Thread.currentThread().interrupt();
                                    } else {
                                        log.error("Failed to new transaction with exception: ", throwable);
                                        totalNumTxnOpenTxnFail.increment();
                                    }
                                }
                            }
                        } else {
                            totalNumTxnOpenTxnSuccess.increment();
                            totalNumEndTxnOpSuccess.increment();
                            numTxnOpSuccess.increment();
                        }
                    }
                });
            }


            // Print report stats
            long oldTime = System.nanoTime();

            Histogram reportSendHistogram = null;
            Histogram reportAckHistogram = null;

            String statsFileName = "perf-transaction-" + System.currentTimeMillis() + ".hgrm";
            log.info("Dumping latency stats to {}", statsFileName);

            PrintStream histogramLog = new PrintStream(new FileOutputStream(statsFileName), false);
            HistogramLogWriter histogramLogWriter = new HistogramLogWriter(histogramLog);

            // Some log header bits
            histogramLogWriter.outputLogFormatVersion();
            histogramLogWriter.outputLegend();

            while (!Thread.currentThread().isInterrupted() && executing.get()) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                long now = System.nanoTime();
                double elapsed = (now - oldTime) / 1e9;
                long total = totalNumEndTxnOpFailed.sum() + totalNumTxnOpenTxnSuccess.sum();
                double rate = numTxnOpSuccess.sumThenReset() / elapsed;
                reportSendHistogram = messageSendRecorder.getIntervalHistogram(reportSendHistogram);
                reportAckHistogram = messageAckRecorder.getIntervalHistogram(reportAckHistogram);
                String txnOrTaskLog = !this.isDisableTransaction
                        ? "Throughput transaction: {} transaction executes --- {} transaction/s"
                        : "Throughput task: {} task executes --- {} task/s";
                log.info(
                        txnOrTaskLog + "  --- send Latency: mean: {} ms - med: {} "
                                + "- 95pct: {} - 99pct: {} - 99.9pct: {} - 99.99pct: {} - Max: {}"
                                + " --- ack Latency: "
                                + "mean: {} ms - med: {} - 95pct: {} - 99pct: {} - 99.9pct: {} - 99.99pct: {} - Max: "
                                + "{}",
                        INTFORMAT.format(total),
                        DEC.format(rate),
                        DEC.format(reportSendHistogram.getMean() / 1000.0),
                        DEC.format(reportSendHistogram.getValueAtPercentile(50) / 1000.0),
                        DEC.format(reportSendHistogram.getValueAtPercentile(95) / 1000.0),
                        DEC.format(reportSendHistogram.getValueAtPercentile(99) / 1000.0),
                        DEC.format(reportSendHistogram.getValueAtPercentile(99.9) / 1000.0),
                        DEC.format(reportSendHistogram.getValueAtPercentile(99.99) / 1000.0),
                        DEC.format(reportSendHistogram.getMaxValue() / 1000.0),
                        DEC.format(reportAckHistogram.getMean() / 1000.0),
                        DEC.format(reportAckHistogram.getValueAtPercentile(50) / 1000.0),
                        DEC.format(reportAckHistogram.getValueAtPercentile(95) / 1000.0),
                        DEC.format(reportAckHistogram.getValueAtPercentile(99) / 1000.0),
                        DEC.format(reportAckHistogram.getValueAtPercentile(99.9) / 1000.0),
                        DEC.format(reportAckHistogram.getValueAtPercentile(99.99) / 1000.0),
                        DEC.format(reportAckHistogram.getMaxValue() / 1000.0));

                histogramLogWriter.outputIntervalHistogram(reportSendHistogram);
                histogramLogWriter.outputIntervalHistogram(reportAckHistogram);
                reportSendHistogram.reset();
                reportAckHistogram.reset();

                oldTime = now;
            }

            PerfClientUtils.removeAndRunShutdownHook(shutdownHookThread);
        } finally {
            PerfClientUtils.closeClient(client);
        }
    }


    private static void printTxnAggregatedThroughput(long start) {
        double elapsed = (System.nanoTime() - start) / 1e9;
        long numTransactionEndFailed = totalNumEndTxnOpFailed.sum();
        long numTransactionEndSuccess = totalNumEndTxnOpSuccess.sum();
        long total = numTransactionEndFailed + numTransactionEndSuccess;
        double rate = total / elapsed;
        long numMessageAckFailed = numMessagesAckFailed.sum();
        long numMessageAckSuccess = numMessagesAckSuccess.sum();
        long numMessageSendFailed = numMessagesSendFailed.sum();
        long numMessageSendSuccess = numMessagesSendSuccess.sum();
        long numTransactionOpenFailed = totalNumTxnOpenTxnFail.sum();
        long numTransactionOpenSuccess = totalNumTxnOpenTxnSuccess.sum();

        log.info(
                "Aggregated throughput stats --- {} transaction executed --- {} transaction/s "
                        + " --- {} transaction open successfully --- {} transaction open failed"
                        + " --- {} transaction end successfully --- {} transaction end failed"
                        + " --- {} message ack failed --- {} message send failed"
                        + " --- {} message ack success --- {} message send success ",
                total,
                DEC.format(rate),
                numTransactionOpenSuccess,
                numTransactionOpenFailed,
                numTransactionEndSuccess,
                numTransactionEndFailed,
                numMessageAckFailed,
                numMessageSendFailed,
                numMessageAckSuccess,
                numMessageSendSuccess);

    }

    private static void printAggregatedThroughput(long start) {
        double elapsed = (System.nanoTime() - start) / 1e9;
        long total = totalNumEndTxnOpFailed.sum() + totalNumEndTxnOpSuccess.sum();
        double rate = total / elapsed;
        long numMessageAckFailed = numMessagesAckFailed.sum();
        long numMessageAckSuccess = numMessagesAckSuccess.sum();
        long numMessageSendFailed = numMessagesSendFailed.sum();
        long numMessageSendSuccess = numMessagesSendSuccess.sum();
        log.info(
                "Aggregated throughput stats --- {} task executed --- {} task/s"
                        + " --- {} message ack failed --- {} message send failed"
                        + " --- {} message ack success --- {} message send success",
                total,
                TOTALFORMAT.format(rate),
                numMessageAckFailed,
                numMessageSendFailed,
                numMessageAckSuccess,
                numMessageSendSuccess);
    }

    private static void printAggregatedStats() {
        Histogram reportAckHistogram = messageAckCumulativeRecorder.getIntervalHistogram();
        Histogram reportSendHistogram = messageSendRCumulativeRecorder.getIntervalHistogram();
        log.info(
                "Messages ack aggregated latency stats --- Latency: mean: {} ms - med: {} - 95pct: {} - 99pct: {} - "
                        + "99.9pct: {} - "
                        + "99.99pct: {} - 99.999pct: {} - Max: {}",
                DEC.format(reportAckHistogram.getMean() / 1000.0),
                DEC.format(reportAckHistogram.getValueAtPercentile(50) / 1000.0),
                DEC.format(reportAckHistogram.getValueAtPercentile(95) / 1000.0),
                DEC.format(reportAckHistogram.getValueAtPercentile(99) / 1000.0),
                DEC.format(reportAckHistogram.getValueAtPercentile(99.9) / 1000.0),
                DEC.format(reportAckHistogram.getValueAtPercentile(99.99) / 1000.0),
                DEC.format(reportAckHistogram.getValueAtPercentile(99.999) / 1000.0),
                DEC.format(reportAckHistogram.getMaxValue() / 1000.0));
        log.info(
                "Messages send aggregated latency stats --- Latency: mean: {} ms - med: {} - 95pct: {} - 99pct: {} - "
                        + "99.9pct: {} - "
                        + "99.99pct: {} - 99.999pct: {} - Max: {}",
                DEC.format(reportSendHistogram.getMean() / 1000.0),
                DEC.format(reportSendHistogram.getValueAtPercentile(50) / 1000.0),
                DEC.format(reportSendHistogram.getValueAtPercentile(95) / 1000.0),
                DEC.format(reportSendHistogram.getValueAtPercentile(99) / 1000.0),
                DEC.format(reportSendHistogram.getValueAtPercentile(99.9) / 1000.0),
                DEC.format(reportSendHistogram.getValueAtPercentile(99.99) / 1000.0),
                DEC.format(reportSendHistogram.getValueAtPercentile(99.999) / 1000.0),
                DEC.format(reportSendHistogram.getMaxValue() / 1000.0));
    }



    static final DecimalFormat DEC = new PaddingDecimalFormat("0.000", 7);
    static final DecimalFormat INTFORMAT = new PaddingDecimalFormat("0", 7);
    static final DecimalFormat TOTALFORMAT = new DecimalFormat("0.000");
    private static final Logger log = LoggerFactory.getLogger(PerformanceTransaction.class);


    private  List<List<Consumer<byte[]>>> buildConsumer(PulsarClient client)
            throws ExecutionException, InterruptedException {
        ConsumerBuilder<byte[]> consumerBuilder = client.newConsumer(Schema.BYTES)
                .subscriptionType(this.subscriptionType)
                .receiverQueueSize(this.receiverQueueSize)
                .subscriptionInitialPosition(this.subscriptionInitialPosition)
                .replicateSubscriptionState(this.replicatedSubscription);

        Iterator<String> consumerTopicsIterator = this.consumerTopic.iterator();
        List<List<Consumer<byte[]>>> consumers = new ArrayList<>(this.consumerTopic.size());
        while (consumerTopicsIterator.hasNext()){
            String topic = consumerTopicsIterator.next();
            final List<Consumer<byte[]>> subscriptions = new ArrayList<>(this.numSubscriptions);
            final List<Future<Consumer<byte[]>>> subscriptionFutures =
                    new ArrayList<>(this.numSubscriptions);
            log.info("Create subscriptions for topic {}", topic);
            for (int j = 0; j < this.numSubscriptions; j++) {
                String subscriberName = this.subscriptions.get(j);
                subscriptionFutures
                        .add(consumerBuilder.clone().topic(topic).subscriptionName(subscriberName)
                                .subscribeAsync());
            }
            for (Future<Consumer<byte[]>> future : subscriptionFutures) {
                subscriptions.add(future.get());
            }
            consumers.add(subscriptions);
        }
        return consumers;
    }

    private List<Producer<byte[]>> buildProducers(PulsarClient client)
            throws ExecutionException, InterruptedException {

        ProducerBuilder<byte[]> producerBuilder = client.newProducer(Schema.BYTES)
                .sendTimeout(0, TimeUnit.SECONDS);

        final List<Future<Producer<byte[]>>> producerFutures = new ArrayList<>();
        for (String topic : this.producerTopic) {
            log.info("Create producer for topic {}", topic);
            producerFutures.add(producerBuilder.clone().topic(topic).createAsync());
        }
        final List<Producer<byte[]>> producers = new ArrayList<>(producerFutures.size());

        for (Future<Producer<byte[]>> future : producerFutures) {
            producers.add(future.get());
        }
        return  producers;
    }

}
