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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.boydti.fawe.example.DefaultFaweQueueMap;
import com.boydti.fawe.example.IFaweQueueMap;
import com.boydti.fawe.example.MappedFaweQueue;
import com.boydti.fawe.example.WeakFaweQueueMap;
import com.boydti.fawe.object.*;
import com.boydti.fawe.util.SetQueue;
import com.helion3.prism.api.query.Query;
import com.helion3.prism.configuration.category.WorldEditCategory;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.world.World;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

/**
 * Exercises the actual legacy callback types from the supplied FAWE jar without a running server.
 */
@SuppressWarnings("rawtypes")
public class FaweCaptureTest {
    @TempDir Path root;
    private String url;
    private EditJournal journal;
    private FaweHook hook;
    private FaweQueue queue;
    private AtomicReference<RunnableVal2<FaweChunk, FaweChunk>> callback;
    private List<Runnable> queueDone;
    private FaweChunk previous;
    private FaweChunk next;
    private World world;

    @BeforeEach
    public void initialize() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlEditIndex index = new SqlEditIndex(() -> DriverManager.getConnection(url), "prism_");
        journal = new EditJournal(root, index, new WorldEditCategory(), mock(Logger.class));
        hook =
                new FaweHook(
                        journal,
                        Collections.emptyMap(),
                        block -> new BlockData("test:block" + block.getId(), null));
        MappedFaweQueue mapped = mock(MappedFaweQueue.class);
        queue = mapped;
        replaceMap(mapped, mock(IFaweQueueMap.class));
        when(mapped.getFaweQueueMap()).thenCallRealMethod();
        when(mapped.next(anyInt(), anyLong())).thenCallRealMethod();
        callback = new AtomicReference<>();
        queueDone = new ArrayList<>();
        when(queue.getChangeTask()).thenAnswer(invocation -> callback.get());
        doAnswer(
                        invocation -> {
                            callback.set(invocation.getArgument(0));
                            return null;
                        })
                .when(queue)
                .setChangeTask(any());
        doAnswer(
                        invocation -> {
                            queueDone.add(invocation.getArgument(0));
                            return null;
                        })
                .when(queue)
                .addNotifyTask(any(Runnable.class));
        previous = mock(FaweChunk.class);
        next = mock(FaweChunk.class);
        when(next.getX()).thenReturn(-1);
        when(next.getZ()).thenReturn(2);
        when(previous.getBlockCombinedId(1, 10, 2)).thenReturn(16);
        when(next.getBlockCombinedId(1, 10, 2)).thenReturn(32);
        world = mock(World.class);
        when(queue.getWEWorld()).thenReturn(world);
    }

    @AfterEach
    public void cleanup() throws Exception {
        journal.close();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private EditJournal.Handle handle() {
        return journal.begin(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "Builder",
                "fawe-5505943");
    }

    private static void replaceMap(MappedFaweQueue queue, IFaweQueueMap map) throws Exception {
        Field field = MappedFaweQueue.class.getDeclaredField("map");
        field.setAccessible(true);
        field.set(queue, map);
    }

    @Test
    public void chainsNativeHistoryAndWaitsForRealChunkCompletion() throws Exception {
        AtomicInteger nativeHistory = new AtomicInteger();
        callback.set(
                new RunnableVal2<FaweChunk, FaweChunk>() {
                    @Override
                    public void run(FaweChunk before, FaweChunk after) {
                        nativeHistory.incrementAndGet();
                    }
                });
        FaweHook.QueueCapture capture = hook.new QueueCapture(queue);
        EditJournal.Handle handle = handle();
        capture.own(next, (10 << 8) | (2 << 4) | 1, new FaweHook.Owner(handle, 1234));
        capture.install();
        capture.install();
        callback.get().run(previous, next);
        assertEquals(1, nativeHistory.get());
        assertEquals(1, capture.completedDispatches.size());
        verify(next, never()).addNotifyTask(any(Runnable.class));
        assertTrue(journal.bufferedBytes() > 0);
        // Before the completion callback there is deliberately no SQL-visible operation.
        AtomicInteger count = new AtomicInteger();
        journal.query(new Query(), (op, delta) -> count.incrementAndGet());
        assertEquals(0, count.get());
        when(world.getBlock(any(Vector.class))).thenReturn(new BaseBlock(2, 0));
        queue.next(1, 1000);
        capture.notifyFinish(handle, () -> {});
        queueDone.forEach(Runnable::run);
        journal.close();
        journal.query(
                new Query(),
                (op, delta) -> {
                    count.incrementAndGet();
                    assertEquals(-15, delta.x);
                    assertEquals(34, delta.z);
                    assertEquals("test:block1", delta.before.state);
                    assertEquals("test:block2", delta.after.state);
                });
        assertEquals(1, count.get());
        assertEquals(0, journal.bufferedBytes());
    }

    @Test
    public void overwrittenQueuedOwnerDoesNotProducePhantomChangeAndUndoCanReinstallCallback()
            throws Exception {
        FaweHook.QueueCapture capture = hook.new QueueCapture(queue);
        EditJournal.Handle superseded = handle();
        EditJournal.Handle applied = handle();
        int local = (10 << 8) | (2 << 4) | 1;
        capture.own(next, local, new FaweHook.Owner(superseded, 1));
        capture.own(next, local, new FaweHook.Owner(applied, 2));
        FaweHook.Owner rejected = new FaweHook.Owner(superseded, 3);
        FaweHook.Owner previousOwner = capture.own(next, local, rejected);
        capture.restore(next, local, rejected, previousOwner);
        assertEquals(128, journal.bufferedBytes());
        capture.install();
        callback.set(null);
        capture.install();
        assertNotNull(callback.get());
        callback.get().run(previous, next);
        when(world.getBlock(any(Vector.class))).thenReturn(new BaseBlock(2, 0));
        queue.next(1, 1000);
        capture.notifyFinish(applied, () -> {});
        queueDone.forEach(Runnable::run);
        superseded.finish();
        journal.close();
        AtomicInteger count = new AtomicInteger();
        journal.query(
                new Query(),
                (op, delta) -> {
                    assertEquals(applied.operation.id, op.id);
                    count.incrementAndGet();
                });
        assertEquals(1, count.get());
    }

    @Test
    public void rejectedWorldWriteDoesNotBecomeSuccessfulHistory() throws Exception {
        FaweHook.QueueCapture capture = hook.new QueueCapture(queue);
        EditJournal.Handle handle = handle();
        capture.own(next, (10 << 8) | (2 << 4) | 1, new FaweHook.Owner(handle, 1));
        capture.install();
        callback.get().run(previous, next);
        // FAWE swallowed a failure before placing the requested block: real world is unchanged.
        when(world.getBlock(any(Vector.class))).thenReturn(new BaseBlock(1, 0));
        queue.next(1, 1000);
        assertEquals("partial", handle.operation.status);
        capture.notifyFinish(handle, () -> {});
        queueDone.forEach(Runnable::run);
        journal.close();
        AtomicInteger count = new AtomicInteger();
        journal.query(new Query(), (op, delta) -> count.incrementAndGet());
        assertEquals(0, count.get());
    }

    @Test
    public void realQueueMapDispatchPublishesSet383Data8WithoutClosingTheJournal()
            throws Exception {
        assertNativeQueueMapPublishes(false);
    }

    @Test
    public void weakQueueMapDispatchPublishesSet383Data8WithoutClosingTheJournal()
            throws Exception {
        assertNativeQueueMapPublishes(true);
    }

    private void assertNativeQueueMapPublishes(boolean weak) throws Exception {
        MappedFaweQueue mapped = (MappedFaweQueue) queue;
        IFaweQueueMap nativeMap =
                weak ? new WeakFaweQueueMap(mapped) : new DefaultFaweQueueMap(mapped);
        replaceMap(mapped, nativeMap);
        mapped.tasks = new java.util.concurrent.ConcurrentLinkedDeque<>();
        doCallRealMethod().when(mapped).addNotifyTask(any(Runnable.class));
        doCallRealMethod().when(mapped).runTasks();
        when(mapped.getStage()).thenReturn(SetQueue.QueueStage.NONE);
        when(mapped.size()).thenCallRealMethod();
        doCallRealMethod().when(mapped).setBlock(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        when(world.getName()).thenReturn("testWorld");
        String worldId = UUID.randomUUID().toString();
        FaweHook nativeHook =
                new FaweHook(
                        journal,
                        Collections.singletonMap("testWorld", worldId),
                        block ->
                                new BlockData(
                                        "test:block" + block.getId() + ":" + block.getData(),
                                        null));
        AtomicInteger factories = new AtomicInteger();
        AtomicReference<BaseBlock> applied = new AtomicReference<>(new BaseBlock(1, 0));
        when(world.getBlock(any(Vector.class))).thenAnswer(invocation -> applied.get());
        when(mapped.getFaweChunk(anyInt(), anyInt()))
                .thenAnswer(
                        invocation -> {
                            factories.incrementAndGet();
                            FaweChunk chunk = mock(FaweChunk.class);
                            when(chunk.getX()).thenReturn(invocation.getArgument(0));
                            when(chunk.getZ()).thenReturn(invocation.getArgument(1));
                            AtomicInteger combined = new AtomicInteger();
                            when(chunk.getBlockCombinedId(1, 10, 2))
                                    .thenAnswer(call -> combined.get());
                            doAnswer(
                                            call -> {
                                                combined.set(
                                                        ((int) call.getArgument(3) << 4)
                                                                | (int) call.getArgument(4));
                                                return null;
                                            })
                                    .when(chunk)
                                    .setBlock(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
                            when(chunk.call())
                                    .thenAnswer(
                                            call -> {
                                                callback.get().run(previous, chunk);
                                                applied.set(
                                                        new BaseBlock(
                                                                combined.get() >> 4,
                                                                combined.get() & 15));
                                                return chunk;
                                            });
                            return chunk;
                        });
        FaweHook.QueueCapture capture = nativeHook.new QueueCapture(mapped);
        Extent delegate = mock(Extent.class);
        when(delegate.setBlock(any(Vector.class), any(BaseBlock.class)))
                .thenAnswer(
                        invocation -> {
                            Vector position = invocation.getArgument(0);
                            BaseBlock block = invocation.getArgument(1);
                            return mapped.setBlock(
                                    position.getBlockX(),
                                    position.getBlockY(),
                                    position.getBlockZ(),
                                    block.getId(),
                                    block.getData());
                        });
        EditSessionEvent event =
                new EditSessionEvent(world, null, -1, EditSession.Stage.BEFORE_CHANGE);
        Extent extent = nativeHook.recordingExtent(delegate, event, capture);
        if (weak) {
            java.lang.reflect.Method setter =
                    extent.getClass()
                            .getDeclaredMethod(
                                    "setBlock", int.class, int.class, int.class, BaseBlock.class);
            setter.setAccessible(true);
            assertEquals(true, setter.invoke(extent, -15, 10, 34, new BaseBlock(383, 8)));
        } else {
            assertTrue(extent.setBlock(new Vector(-15, 10, 34), new BaseBlock(383, 8)));
        }
        FaweChunk queued = nativeMap.getFaweChunk(-1, 2);
        assertSame(queued, capture.queuedChunk(-1, 2));
        assertEquals(
                1, factories.get(), "Getting the queued chunk must not invoke the factory twice");
        Operations.completeBlindly(extent.commit());
        assertFalse(mapped.next(1, 1000));
        mapped.size(); // Actual MappedFaweQueue runs operation completion tasks when empty/NONE.
        assertEquals(383, applied.get().getId());
        assertEquals(8, applied.get().getData());
        AtomicInteger found = new AtomicInteger();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            journal.query(
                    new Query(),
                    (op, delta) -> {
                        assertEquals("test:block383:8", delta.after.state);
                        assertEquals(-15, delta.x);
                        assertEquals(34, delta.z);
                        found.incrementAndGet();
                    });
            if (found.get() == 0) {
                Thread.sleep(10);
            }
        } while (found.get() == 0 && System.nanoTime() < deadline);
        assertEquals(
                1,
                found.get(),
                "A completed edit must be queryable while the server is still running");
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(root)) {
            assertTrue(files.anyMatch(path -> path.toString().endsWith(".pwe")));
        }
        verify(queued, never()).executeNotifyTasks();
    }

    @Test
    public void workerSnapshotsAreConfirmedOnDispatcherThreadAfterBatchJoin() throws Exception {
        MappedFaweQueue mapped = (MappedFaweQueue) queue;
        IFaweQueueMap nativeMap = mapped.getFaweQueueMap();
        FaweHook.QueueCapture capture = hook.new QueueCapture(queue);
        EditJournal.Handle handle = handle();
        capture.own(next, (10 << 8) | (2 << 4) | 1, new FaweHook.Owner(handle, 1));
        capture.install();
        Thread dispatcher = Thread.currentThread();
        AtomicInteger verifications = new AtomicInteger();
        when(world.getBlock(any(Vector.class)))
                .thenAnswer(
                        invocation -> {
                            assertSame(dispatcher, Thread.currentThread());
                            verifications.incrementAndGet();
                            return new BaseBlock(2, 0);
                        });
        java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            when(nativeMap.next(anyInt(), anyLong()))
                    .thenAnswer(
                            invocation -> {
                                worker.submit(() -> callback.get().run(previous, next))
                                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                                assertEquals(
                                        0,
                                        verifications.get(),
                                        "A snapshot callback is not a completed world write");
                                return false;
                            });
            mapped.next(2, 1000);
            assertEquals(1, verifications.get());
            assertTrue(capture.completedDispatches.isEmpty());
        } finally {
            worker.shutdownNow();
        }
        capture.notifyFinish(handle, () -> {});
        queueDone.forEach(Runnable::run);
    }
}
