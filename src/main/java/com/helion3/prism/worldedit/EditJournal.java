/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.helion3.prism.worldedit;

import com.helion3.prism.api.query.Query;
import com.helion3.prism.configuration.category.WorldEditCategory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import org.slf4j.Logger;

/** Bounded capture handoff, recoverable file publication and coordinated retention. */
public final class EditJournal implements AutoCloseable {
    private final Path root;
    private final SqlEditIndex index;
    private final Logger logger;
    private final long capacity;
    private final long pendingCapacity;
    private final long minimumFree;
    private final AtomicLong buffered = new AtomicLong();
    private final Set<Handle> handles = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService writer;
    private final Object files = new Object();
    private volatile String failure;
    private volatile boolean closing;
    private volatile long pendingBytes;
    private volatile long freeBytes;

    public EditJournal(Path root, SqlEditIndex index, WorldEditCategory config, Logger logger)
            throws IOException {
        this.root = root;
        this.index = index;
        this.logger = logger;
        capacity = config.getBufferMib() * 1024L * 1024;
        pendingCapacity = config.getPendingMib() * 1024L * 1024;
        minimumFree = config.getMinimumFreeMib() * 1024L * 1024;
        Files.createDirectories(root);
        freeBytes = Files.getFileStore(root).getUsableSpace();
        try (DirectoryStream<Path> checkpoints = Files.newDirectoryStream(root, "*.capture")) {
            for (Path checkpoint : checkpoints) {
                try {
                    DeltaArchive.recover(root, checkpoint);
                } catch (IOException error) {
                    fail(error);
                }
            }
        }
        // A footer-less temporary file cannot prove which prepared writes reached the world.
        try (DirectoryStream<Path> temporary = Files.newDirectoryStream(root, "*.tmp")) {
            for (Path path : temporary) {
                logger.error("Incomplete WE capture retained for investigation: {}", path);
            }
        }
        writer =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "Prism-WE-Journal");
                            thread.setDaemon(true);
                            return thread;
                        });
        writer.scheduleWithFixedDelay(this::maintain, 0, 5, TimeUnit.SECONDS);
    }

    public Path getRoot() {
        return root;
    }

    public long bufferedBytes() {
        return buffered.get();
    }

    public void reserveProvenance(long bytes) {
        requireAvailable();
        reservePrepared(bytes);
    }
    // Reserve before allowing a mutation, so a chunk can be rejected without losing its before
    // image.
    public void reservePrepared(long bytes) {
        long total = buffered.addAndGet(bytes);
        if (total > capacity) {
            buffered.addAndGet(-bytes);
            throw new IllegalStateException(
                    "Prism WE capture buffer exhausted before chunk dispatch");
        }
    }

    public void releaseProvenance(long bytes) {
        buffered.addAndGet(-bytes);
    }

    public void captureFailed(Exception error) {
        fail(error);
    }

    public void requireAvailable() {
        if (closing
                || failure != null
                || buffered.get() >= capacity
                || pendingBytes >= pendingCapacity
                || freeBytes < minimumFree) {
            throw new IllegalStateException(
                    "Prism cannot record this WorldEdit edit: "
                            + (failure == null
                                    ? "recording backlog or insufficient disk space"
                                    : failure));
        }
    }

    public Handle begin(String world, String player, String actor, String source) {
        requireAvailable();
        if (handles.size() >= 64) {
            throw new IllegalStateException("Too many unfinished WorldEdit captures");
        }
        EditOperation operation = new EditOperation();
        operation.world = world;
        operation.player = player;
        operation.actor = actor;
        operation.source = source;
        Handle handle = new Handle(operation);
        handles.add(handle);
        return handle;
    }

    public final class Handle {
        public final EditOperation operation;
        private DeltaArchive.Writer archive;
        private boolean finished;
        private boolean broken;

        private Handle(EditOperation operation) {
            this.operation = operation;
        }

        public synchronized void append(BlockDelta delta) {
            if (finished) {
                throw new IllegalStateException("WE capture already finalized");
            }
            if (delta.before.equals(delta.after)) {
                return;
            }
            if (delta.bytes() > 8 * 1024 * 1024) {
                fail(new IOException("WE NBT exceeds 8 MiB per change"));
                partial();
                return;
            }
            buffered.addAndGet(delta.bytes());
            writer.execute(
                    () -> {
                        int previous = archive == null ? 0 : archive.bufferedBytes();
                        try {
                            if (broken) {
                                buffered.addAndGet(-delta.bytes());
                                return;
                            }
                            if (archive == null) {
                                archive = new DeltaArchive.Writer(root, operation);
                            }
                            archive.append(delta);
                            buffered.addAndGet(archive.bufferedBytes() - previous - delta.bytes());
                        } catch (IOException | RuntimeException error) {
                            buffered.addAndGet(-delta.bytes());
                            broken = true;
                            partial();
                            fail(error);
                        }
                    });
        }

        public void partial() {
            operation.status = "partial";
        }

        private synchronized void abortIfOpen() {
            if (!finished) {
                partial();
                finish();
            }
        }

        public synchronized void finish() {
            if (finished) {
                return;
            }
            finished = true;
            writer.execute(
                    () -> {
                        try {
                            if (archive != null && !broken) {
                                int remaining = archive.bufferedBytes();
                                archive.finish();
                                buffered.addAndGet(-remaining);
                                publish(operation);
                            }
                        } catch (Exception error) {
                            fail(error);
                        } finally {
                            if (archive != null) {
                                try {
                                    archive.close();
                                } catch (IOException error) {
                                    fail(error);
                                }
                            }
                            handles.remove(this);
                        }
                    });
        }
    }

    private void fail(Exception error) {
        failure =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        logger.error(
                "WorldEdit capture failed; new edits will be rejected. Existing capture files are retained.",
                error);
    }

    private void publish(EditOperation operation) {
        synchronized (files) {
            try {
                // Re-read the footer and all frames before exposing an operation through SQL.
                EditOperation durable =
                        DeltaArchive.manifest(DeltaArchive.file(root, operation.id));
                for (EditOperation.Frame frame : durable.frames) {
                    DeltaArchive.read(DeltaArchive.file(root, durable.id), frame, delta -> {});
                }
                index.publish(durable);
                Files.deleteIfExists(root.resolve(durable.id + ".pending"));
            } catch (Exception error) {
                logger.error(
                        "WE file is retained for SQL publication retry: {}", operation.id, error);
            }
        }
    }

    public void maintain() {
        synchronized (files) {
            try {
                long size = 0;
                try (DirectoryStream<Path> pending = Files.newDirectoryStream(root, "*.pending")) {
                    for (Path marker : pending) {
                        String id = marker.getFileName().toString().replace(".pending", "");
                        Path archive = DeltaArchive.file(root, id);
                        if (!Files.exists(archive)) {
                            Path temporary = root.resolve(id + ".tmp");
                            if (Files.exists(temporary)) {
                                DeltaArchive.manifest(temporary);
                                Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE);
                            } else {
                                throw new IOException("WE publication file missing: " + id);
                            }
                        }
                        publish(DeltaArchive.manifest(archive));
                        if (Files.exists(marker)) {
                            size += Files.size(archive);
                        }
                    }
                }
                pendingBytes = size;
                freeBytes = Files.getFileStore(root).getUsableSpace();
            } catch (Exception error) {
                fail(error);
            }
        }
    }

    /** Holds the archive read lease until all selected frames have passed integrity checks. */
    public void query(Query query, BiConsumer<EditOperation, BlockDelta> consumer)
            throws Exception {
        synchronized (files) {
            for (Map.Entry<String, Set<String>> selected : index.candidates(query).entrySet()) {
                Path archive = DeltaArchive.file(root, selected.getKey());
                EditOperation operation = DeltaArchive.manifest(archive);
                if (!operation.id.equals(selected.getKey())) {
                    throw new IOException("WE archive identity mismatch");
                }
                for (EditOperation.Frame frame : operation.frames) {
                    if (selected.getValue().contains(frame.section())) {
                        DeltaArchive.read(
                                archive,
                                frame,
                                delta -> {
                                    if (DeltaConditions.matches(
                                            query.getConditions(), operation, delta)) {
                                        consumer.accept(operation, delta);
                                    }
                                });
                    }
                }
            }
        }
    }

    public void purge(long before) throws Exception {
        synchronized (files) {
            for (String id : index.expired(before)) {
                index.markDeleting(id);
                Files.deleteIfExists(DeltaArchive.file(root, id));
                Files.deleteIfExists(root.resolve(id + ".pending"));
                index.delete(id);
            }
        }
    }

    @Override
    public synchronized void close() throws InterruptedException {
        if (writer.isTerminated()) {
            return;
        }
        closing = true;
        if (!writer.isShutdown()) {
            for (Handle handle : handles) {
                handle.abortIfOpen();
            }
            writer.execute(this::maintain);
            writer.shutdown();
        }
        if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
            logger.error(
                    "WE journal shutdown exceeded 30 seconds; capture files retained. SQL connection must remain open until writer stops.");
            throw new IllegalStateException("WE journal has not stopped");
        }
    }
}
