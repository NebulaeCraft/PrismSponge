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
package com.helion3.prism.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.helion3.prism.Prism;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.query.Sort;
import com.helion3.prism.api.records.Actionable;
import com.helion3.prism.api.records.ActionableResult;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.api.storage.StorageAdapter;
import com.helion3.prism.api.storage.StorageAdapterRecords;
import com.helion3.prism.configuration.Config;
import com.helion3.prism.configuration.Configuration;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.spongepowered.api.GameRegistry;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.Task;

public class ApplierCommandTest {

    private Object previousPrism;
    private Object previousRegistry;
    private StorageAdapterRecords records;
    private QuerySession session;
    private Logger logger;
    private Task.Builder backgroundBuilder;
    private Task.Builder serverBuilder;
    private AtomicReference<Runnable> backgroundTask;
    private AtomicReference<Runnable> serverTask;

    @BeforeEach
    public void initializeScheduler() throws Exception {
        Prism prism = new Prism();
        Config config = new Config();
        config.getLimitCategory().setMaximumActionable(2);
        Configuration configuration = mock(Configuration.class);
        when(configuration.getConfig()).thenReturn(config);
        replaceField(Prism.class, prism, "configuration", configuration);
        replaceField(Prism.class, prism, "pluginContainer", mock(PluginContainer.class));
        logger = mock(Logger.class);
        replaceField(Prism.class, prism, "logger", logger);

        records = mock(StorageAdapterRecords.class);
        StorageAdapter adapter = mock(StorageAdapter.class);
        when(adapter.records()).thenReturn(records);
        replaceField(Prism.class, prism, "storageAdapter", adapter);
        previousPrism = replaceField(Prism.class, null, "instance", prism);

        backgroundTask = new AtomicReference<>();
        serverTask = new AtomicReference<>();
        backgroundBuilder = captureTask(backgroundTask);
        serverBuilder = captureTask(serverTask);
        GameRegistry registry = mock(GameRegistry.class);
        when(registry.createBuilder(Task.Builder.class))
                .thenReturn(backgroundBuilder, serverBuilder);
        previousRegistry = replaceField(Sponge.class, null, "registry", registry);

        session = new QuerySession(mock(CommandSource.class));
        session.newQuery();
    }

    @AfterEach
    public void restoreSingletons() throws Exception {
        replaceField(Prism.class, null, "instance", previousPrism);
        replaceField(Sponge.class, null, "registry", previousRegistry);
    }

    private static Object replaceField(Class<?> owner, Object target, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object previous = field.get(target);
        field.set(target, value);
        return previous;
    }

    private Task.Builder captureTask(AtomicReference<Runnable> task) {
        Task.Builder builder = mock(Task.Builder.class, RETURNS_SELF);
        doAnswer(
                        invocation -> {
                            task.set(invocation.getArgument(0));
                            return builder;
                        })
                .when(builder)
                .execute(any(Runnable.class));
        return builder;
    }

    private void runBackgroundTask() throws Exception {
        assertNotNull(backgroundTask.get());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(backgroundTask.get()).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void queriesInBackgroundAndRollsBackOnServerThread() throws Exception {
        assertAppliesOnServerThread(Sort.NEWEST_FIRST);
    }

    @Test
    public void queriesInBackgroundAndRestoresOnServerThread() throws Exception {
        assertAppliesOnServerThread(Sort.OLDEST_FIRST);
    }

    private void assertAppliesOnServerThread(Sort sort) throws Exception {
        Thread serverThread = Thread.currentThread();
        Result result = mock(Result.class, withSettings().extraInterfaces(Actionable.class));
        Actionable actionable = (Actionable) result;
        when(actionable.rollback())
                .thenAnswer(
                        invocation -> {
                            assertEquals(serverThread, Thread.currentThread());
                            return ActionableResult.success(null);
                        });
        when(actionable.restore())
                .thenAnswer(
                        invocation -> {
                            assertEquals(serverThread, Thread.currentThread());
                            return ActionableResult.success(null);
                        });
        when(records.query(session, false))
                .thenAnswer(
                        invocation -> {
                            assertNotEquals(serverThread, Thread.currentThread());
                            return CompletableFuture.completedFuture(
                                    Collections.singletonList(result));
                        });

        ApplierCommand.runApplier(session, sort);
        assertEquals(2, session.getQuery().getLimit());
        verify(backgroundBuilder).async();
        verify(records, never()).query(session, false);

        runBackgroundTask();
        verify(records).query(session, false);
        verify(actionable, never()).rollback();
        verify(actionable, never()).restore();
        verify(serverBuilder, never()).async();

        assertNotNull(serverTask.get());
        serverTask.get().run();
        if (sort == Sort.NEWEST_FIRST) {
            verify(actionable).rollback();
            verify(actionable, never()).restore();
        } else {
            verify(actionable).restore();
            verify(actionable, never()).rollback();
        }
    }

    @Test
    public void reportsThrownQueryFailureOnServerThread() throws Exception {
        Exception failure = new Exception("Database unavailable");
        when(records.query(session, false)).thenThrow(failure);

        ApplierCommand.runApplier(session, Sort.NEWEST_FIRST);
        runBackgroundTask();
        assertNotNull(serverTask.get());
        serverTask.get().run();
        verify(logger).error("Failed to query records for rollback/restore", failure);
    }

    @Test
    public void reportsExceptionalFutureOnServerThread() throws Exception {
        CompletableFuture<java.util.List<Result>> future = new CompletableFuture<>();
        when(records.query(session, false)).thenReturn(future);

        ApplierCommand.runApplier(session, Sort.NEWEST_FIRST);
        runBackgroundTask();
        assertNull(serverTask.get());

        Exception failure = new Exception("Query failed");
        future.completeExceptionally(failure);
        assertNotNull(serverTask.get());
        serverTask.get().run();
        verify(logger).error("Failed to query records for rollback/restore", failure);
    }

    @Test
    public void largeApplicationsYieldBetweenTicksAndKeepRecordingSuppressed() throws Exception {
        replaceField(
                com.helion3.prism.configuration.category.WorldEditCategory.class,
                Prism.getInstance().getConfig().getWorldEditCategory(),
                "blocksPerTick",
                2);
        java.util.concurrent.atomic.AtomicInteger applied =
                new java.util.concurrent.atomic.AtomicInteger();
        Result result = mock(Result.class, withSettings().extraInterfaces(Actionable.class));
        when(((Actionable) result).rollback())
                .thenAnswer(
                        invocation -> {
                            org.junit.jupiter.api.Assertions.assertTrue(
                                    com.helion3.prism.worldedit.CaptureGuard.active());
                            applied.incrementAndGet();
                            return ActionableResult.success(null);
                        });
        when(records.query(session, false))
                .thenReturn(CompletableFuture.completedFuture(Collections.nCopies(5, result)));
        ApplierCommand.runApplier(session, Sort.NEWEST_FIRST);
        runBackgroundTask();
        serverTask.get().run();
        org.junit.jupiter.api.Assertions.assertTrue(applied.get() > 0 && applied.get() <= 2);
        for (int tick = 0; tick < 5 && applied.get() < 5; tick++) {
            serverTask.get().run();
        }
        assertEquals(5, applied.get());
        org.junit.jupiter.api.Assertions.assertFalse(
                com.helion3.prism.worldedit.CaptureGuard.active());
    }
}
