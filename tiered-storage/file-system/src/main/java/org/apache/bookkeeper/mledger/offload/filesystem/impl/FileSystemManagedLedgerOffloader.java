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
package org.apache.bookkeeper.mledger.offload.filesystem.impl;

import static org.apache.bookkeeper.mledger.offload.OffloadUtils.buildLedgerMetadataFormat;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import io.netty.util.Recycler;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.bookkeeper.client.api.LedgerEntries;
import org.apache.bookkeeper.client.api.LedgerEntry;
import org.apache.bookkeeper.client.api.ReadHandle;
import org.apache.bookkeeper.common.util.OrderedScheduler;
import org.apache.bookkeeper.mledger.LedgerOffloader;
import org.apache.bookkeeper.mledger.LedgerOffloaderStats;
import org.apache.bookkeeper.mledger.offload.filesystem.FileSystemLedgerOffloaderFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.MapFile;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.OffloadPolicies;
import org.apache.pulsar.common.policies.data.OffloadPoliciesImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileSystemManagedLedgerOffloader implements LedgerOffloader {

    private static final Logger log = LoggerFactory.getLogger(FileSystemManagedLedgerOffloader.class);
    private static final String STORAGE_BASE_PATH = "storageBasePath";
    private static final String DRIVER_NAMES = "filesystem";
    private static final String MANAGED_LEDGER_NAME = "ManagedLedgerName";
    static final long METADATA_KEY_INDEX = -1;
    private final Configuration configuration;
    private final String driverName;
    private final String storageBasePath;
    private final FileSystem fileSystem;
    private OrderedScheduler scheduler;
    private static final long ENTRIES_PER_READ = 100;
    private OrderedScheduler assignmentScheduler;
    private OffloadPolicies offloadPolicies;
    private final LedgerOffloaderStats offloaderStats;

    public static boolean driverSupported(String driver) {
        return DRIVER_NAMES.equals(driver);
    }

    @Override
    public String getOffloadDriverName() {
        return driverName;
    }

    public static FileSystemManagedLedgerOffloader create(OffloadPoliciesImpl conf,
                                                          OrderedScheduler scheduler,
                                                          LedgerOffloaderStats offloaderStats) throws IOException {
        return new FileSystemManagedLedgerOffloader(conf, scheduler, offloaderStats);
    }

    private FileSystemManagedLedgerOffloader(OffloadPoliciesImpl conf, OrderedScheduler scheduler,
                                             LedgerOffloaderStats offloaderStats) throws IOException {
        this.offloadPolicies = conf;
        this.configuration = new Configuration();
        if (conf.getFileSystemProfilePath() != null) {
            String[] paths = conf.getFileSystemProfilePath().split(",");
            for (int i = 0; i < paths.length; i++) {
                configuration.addResource(new Path(paths[i]));
            }
        }
        if (!"".equals(conf.getFileSystemURI()) && conf.getFileSystemURI() != null) {
            configuration.set("fs.defaultFS", conf.getFileSystemURI());
        }
        if (configuration.get("fs.hdfs.impl") == null) {
            this.configuration.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem");
        }

        if (configuration.get("fs.file.impl") == null) {
            this.configuration.set("fs.file.impl", "org.apache.hadoop.fs.LocalFileSystem");
        }

        this.configuration.setClassLoader(FileSystemLedgerOffloaderFactory.class.getClassLoader());
        this.driverName = conf.getManagedLedgerOffloadDriver();
        this.storageBasePath = configuration.get("fs.defaultFS");
        this.scheduler = scheduler;
        this.fileSystem = FileSystem.get(configuration);
        this.assignmentScheduler = OrderedScheduler.newSchedulerBuilder()
                .numThreads(conf.getManagedLedgerOffloadMaxThreads())
                .name("offload-assignment").build();
        this.offloaderStats = offloaderStats;
    }

    @VisibleForTesting
    public FileSystemManagedLedgerOffloader(OffloadPoliciesImpl conf,
                                            OrderedScheduler scheduler,
                                            String testHDFSPath,
                                            String baseDir,
                                            LedgerOffloaderStats offloaderStats) throws IOException {
        this.offloadPolicies = conf;
        this.configuration = new Configuration();
        this.configuration.set("fs.hdfs.impl", "org.apache.hadoop.hdfs.DistributedFileSystem");
        this.configuration.set("fs.defaultFS", testHDFSPath);
        this.configuration.setClassLoader(FileSystemLedgerOffloaderFactory.class.getClassLoader());
        this.driverName = conf.getManagedLedgerOffloadDriver();
        this.configuration.set("hadoop.tmp.dir", baseDir);
        this.storageBasePath = baseDir;
        this.scheduler = scheduler;
        this.fileSystem = FileSystem.get(configuration);
        this.assignmentScheduler = OrderedScheduler.newSchedulerBuilder()
                .numThreads(conf.getManagedLedgerOffloadMaxThreads())
                .name("offload-assignment").build();
        this.offloaderStats = offloaderStats;
    }

    @Override
    public Map<String, String> getOffloadDriverMetadata() {
        String path = storageBasePath == null ? "null" : storageBasePath;
        return ImmutableMap.of(
                STORAGE_BASE_PATH, path
        );
    }

    /*
    * ledgerMetadata stored in an index of -1
    * */
    @Override
    public CompletableFuture<Void> offload(ReadHandle readHandle, UUID uuid, Map<String, String> extraMetadata) {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        scheduler.chooseThread(readHandle.getId()).execute(
                new LedgerReader(readHandle, uuid, extraMetadata, promise, storageBasePath, configuration,
                        assignmentScheduler, offloadPolicies.getManagedLedgerOffloadPrefetchRounds(),
                        this.offloaderStats));
        return promise;
    }

    private static class LedgerReader implements Runnable {

        private final ReadHandle readHandle;
        private final UUID uuid;
        private final Map<String, String> extraMetadata;
        private final CompletableFuture<Void> promise;
        private final String storageBasePath;
        private final Configuration configuration;
        volatile Exception fileSystemWriteException = null;
        private OrderedScheduler assignmentScheduler;
        private int managedLedgerOffloadPrefetchRounds = 1;
        private final LedgerOffloaderStats offloaderStats;

        private LedgerReader(ReadHandle readHandle,
                             UUID uuid,
                             Map<String, String> extraMetadata,
                             CompletableFuture<Void> promise,
                             String storageBasePath,
                             Configuration configuration,
                             OrderedScheduler assignmentScheduler,
                             int managedLedgerOffloadPrefetchRounds,
                             LedgerOffloaderStats offloaderStats) {
            this.readHandle = readHandle;
            this.uuid = uuid;
            this.extraMetadata = extraMetadata;
            this.promise = promise;
            this.storageBasePath = storageBasePath;
            this.configuration = configuration;
            this.assignmentScheduler = assignmentScheduler;
            this.managedLedgerOffloadPrefetchRounds = managedLedgerOffloadPrefetchRounds;
            this.offloaderStats = offloaderStats;
        }

        @Override
        public void run() {
            if (!readHandle.isClosed() || readHandle.getLastAddConfirmed() < 0) {
                promise.completeExceptionally(
                        new IllegalArgumentException("An empty or open ledger should never be offloaded"));
                return;
            }
            if (readHandle.getLength() <= 0) {
                log.warn("Ledger [{}] has zero length, but it contains {} entries. "
                    + " Attempting to offload ledger since it contains entries.", readHandle.getId(),
                    readHandle.getLastAddConfirmed() + 1);
            }
            long ledgerId = readHandle.getId();
            final String managedLedgerName = extraMetadata.get(MANAGED_LEDGER_NAME);
            String storagePath = getStoragePath(storageBasePath, managedLedgerName);
            String dataFilePath = getDataFilePath(storagePath, ledgerId, uuid);
            final String topicName = TopicName.fromPersistenceNamingEncoding(managedLedgerName);
            LongWritable key = new LongWritable();
            BytesWritable value = new BytesWritable();
            try {
                MapFile.Writer dataWriter = new MapFile.Writer(configuration,
                        new Path(dataFilePath),
                        MapFile.Writer.keyClass(LongWritable.class),
                        MapFile.Writer.valueClass(BytesWritable.class));
                //store the ledgerMetadata in -1 index
                key.set(METADATA_KEY_INDEX);
                byte[] ledgerMetadata = buildLedgerMetadataFormat(readHandle.getLedgerMetadata());
                value.set(ledgerMetadata, 0, ledgerMetadata.length);
                dataWriter.append(key, value);
                AtomicLong haveOffloadEntryNumber = new AtomicLong(0);
                long needToOffloadFirstEntryNumber = 0;
                CountDownLatch countDownLatch;
                //avoid prefetch too much data into memory
                Semaphore semaphore = new Semaphore(managedLedgerOffloadPrefetchRounds);
                do {
                    long end = Math.min(needToOffloadFirstEntryNumber + ENTRIES_PER_READ - 1,
                            readHandle.getLastAddConfirmed());
                    log.debug("read ledger entries. start: {}, end: {}", needToOffloadFirstEntryNumber, end);
                    long startReadTime = System.nanoTime();
                    LedgerEntries ledgerEntriesOnce = readHandle.readAsync(needToOffloadFirstEntryNumber, end).get();
                    long cost = System.nanoTime() - startReadTime;
                    this.offloaderStats.recordReadLedgerLatency(topicName, cost, TimeUnit.NANOSECONDS);
                    semaphore.acquire();
                    countDownLatch = new CountDownLatch(1);
                    assignmentScheduler.chooseThread(ledgerId)
                            .execute(FileSystemWriter.create(ledgerEntriesOnce,
                                    dataWriter, semaphore, countDownLatch, haveOffloadEntryNumber, this));
                    needToOffloadFirstEntryNumber = end + 1;
                } while (needToOffloadFirstEntryNumber - 1 != readHandle.getLastAddConfirmed()
                        && fileSystemWriteException == null);
                countDownLatch.await();
                if (fileSystemWriteException != null) {
                    throw fileSystemWriteException;
                }
                IOUtils.closeStream(dataWriter);
                promise.complete(null);
            } catch (Exception e) {
                log.error("Exception when get CompletableFuture<LedgerEntries> : ManagerLedgerName: {}, "
                        + "LedgerId: {}, UUID: {} ", managedLedgerName, ledgerId, uuid, e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                this.offloaderStats.recordOffloadError(topicName);
                promise.completeExceptionally(e);
            }
        }
    }

    private static class FileSystemWriter implements Runnable {

        private LedgerEntries ledgerEntriesOnce;

        private final LongWritable key = new LongWritable();
        private final BytesWritable value = new BytesWritable();

        private MapFile.Writer dataWriter;
        private CountDownLatch countDownLatch;
        private AtomicLong haveOffloadEntryNumber;
        private LedgerReader ledgerReader;
        private Semaphore semaphore;
        private Recycler.Handle<FileSystemWriter> recyclerHandle;

        private FileSystemWriter(Recycler.Handle<FileSystemWriter> recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        private static final Recycler<FileSystemWriter> RECYCLER = new Recycler<FileSystemWriter>() {
            @Override
            protected FileSystemWriter newObject(Recycler.Handle<FileSystemWriter> handle) {
                return new FileSystemWriter(handle);
            }
        };

        private void recycle() {
            this.dataWriter = null;
            this.countDownLatch = null;
            this.haveOffloadEntryNumber = null;
            this.ledgerReader = null;
            this.ledgerEntriesOnce = null;
            this.semaphore = null;
            recyclerHandle.recycle(this);
        }


        public static FileSystemWriter create(LedgerEntries ledgerEntriesOnce,
                                              MapFile.Writer dataWriter,
                                              Semaphore semaphore,
                                              CountDownLatch countDownLatch,
                                              AtomicLong haveOffloadEntryNumber,
                                              LedgerReader ledgerReader) {
            FileSystemWriter writer = RECYCLER.get();
            writer.ledgerReader = ledgerReader;
            writer.dataWriter = dataWriter;
            writer.countDownLatch = countDownLatch;
            writer.haveOffloadEntryNumber = haveOffloadEntryNumber;
            writer.ledgerEntriesOnce = ledgerEntriesOnce;
            writer.semaphore = semaphore;
            return writer;
        }

        @Override
        public void run() {
            String managedLedgerName = ledgerReader.extraMetadata.get(MANAGED_LEDGER_NAME);
            String topicName = TopicName.fromPersistenceNamingEncoding(managedLedgerName);
            if (ledgerReader.fileSystemWriteException == null) {
                Iterator<LedgerEntry> iterator = ledgerEntriesOnce.iterator();
                while (iterator.hasNext()) {
                    LedgerEntry entry = iterator.next();
                    long entryId = entry.getEntryId();
                    key.set(entryId);
                    byte[] currentEntryBytes;
                    int currentEntrySize;
                    try {
                        currentEntryBytes = entry.getEntryBytes();
                        currentEntrySize = currentEntryBytes.length;
                        value.set(currentEntryBytes, 0, currentEntrySize);
                        dataWriter.append(key, value);
                    } catch (IOException e) {
                        ledgerReader.fileSystemWriteException = e;
                        ledgerReader.offloaderStats.recordWriteToStorageError(topicName);
                        break;
                    }
                    haveOffloadEntryNumber.incrementAndGet();
                    ledgerReader.offloaderStats.recordOffloadBytes(topicName, currentEntrySize);
                }
            }
            countDownLatch.countDown();
            ledgerEntriesOnce.close();
            semaphore.release();
            this.recycle();
        }
    }

    @Override
    public CompletableFuture<ReadHandle> readOffloaded(long ledgerId, UUID uuid,
                                                       Map<String, String> offloadDriverMetadata) {

        final String ledgerName = offloadDriverMetadata.get(MANAGED_LEDGER_NAME);
        CompletableFuture<ReadHandle> promise = new CompletableFuture<>();
        String storagePath = getStoragePath(storageBasePath, ledgerName);
        String dataFilePath = getDataFilePath(storagePath, ledgerId, uuid);
        scheduler.chooseThread(ledgerId).execute(() -> {
            try {
                MapFile.Reader reader = new MapFile.Reader(new Path(dataFilePath),
                        configuration);
                promise.complete(FileStoreBackedReadHandleImpl.open(
                        scheduler.chooseThread(ledgerId), reader, ledgerId, this.offloaderStats, ledgerName));
            } catch (Throwable t) {
                log.error("Failed to open FileStoreBackedReadHandleImpl: ManagerLedgerName: {}, "
                        + "LegerId: {}, UUID: {}", ledgerName, ledgerId, uuid, t);
                promise.completeExceptionally(t);
            }
        });
        return promise;
    }

    private static String getStoragePath(String storageBasePath, String managedLedgerName) {
        return storageBasePath == null ? managedLedgerName + "/" : storageBasePath + "/" + managedLedgerName + "/";
    }

    private static String getDataFilePath(String storagePath, long ledgerId, UUID uuid) {
        return storagePath + ledgerId + "-" + uuid.toString();
    }

    @Override
    public CompletableFuture<Void> deleteOffloaded(long ledgerId, UUID uid, Map<String, String> offloadDriverMetadata) {
        String ledgerName = offloadDriverMetadata.get(MANAGED_LEDGER_NAME);
        String storagePath = getStoragePath(storageBasePath, ledgerName);
        String dataFilePath = getDataFilePath(storagePath, ledgerId, uid);
        String topicName = TopicName.fromPersistenceNamingEncoding(ledgerName);
        CompletableFuture<Void> promise = new CompletableFuture<>();
        try {
            fileSystem.delete(new Path(dataFilePath), true);
            promise.complete(null);
        } catch (IOException e) {
            log.error("Failed to delete Offloaded: ", e);
            promise.completeExceptionally(e);
        }
        return promise.whenComplete((__, t) ->
                this.offloaderStats.recordDeleteOffloadOps(topicName, t == null));
    }

    @Override
    public OffloadPolicies getOffloadPolicies() {
        return offloadPolicies;
    }

    @Override
    public void close() {
        if (fileSystem != null) {
            try {
                fileSystem.close();
            } catch (Exception e) {
                log.error("FileSystemManagedLedgerOffloader close failed!", e);
            }
        }
        if (assignmentScheduler != null) {
            MoreExecutors.shutdownAndAwaitTermination(assignmentScheduler, 5, TimeUnit.SECONDS);
        }
    }
}
