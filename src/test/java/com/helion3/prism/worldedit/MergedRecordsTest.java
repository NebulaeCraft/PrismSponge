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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.*;
import com.helion3.prism.api.records.*;
import com.helion3.prism.api.storage.StorageAdapterRecords;
import com.helion3.prism.configuration.*;
import com.helion3.prism.util.DataQueries;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.data.DataContainer;

public class MergedRecordsTest {
    @TempDir Path root;
    private Object previousPrism;
    private String url;
    private EditJournal journal;
    private MergedRecords records;

    @BeforeEach
    public void initialize() throws Exception {
        Prism prism = new Prism();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getConfig()).thenReturn(new Config());
        Field config = Prism.class.getDeclaredField("configuration");
        config.setAccessible(true);
        config.set(prism, configuration);
        Field instance = Prism.class.getDeclaredField("instance");
        instance.setAccessible(true);
        previousPrism = instance.get(null);
        instance.set(null, prism);
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlEditIndex index = new SqlEditIndex(() -> DriverManager.getConnection(url), "prism_");
        EditOperation operation = DeltaArchiveTest.operation();
        operation.player = null;
        operation.actor = "environment";
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            for (int i = 0; i < 12; i++) {
                long time = i % 2 == 0 ? 2000 : 4000;
                writer.append(
                        new BlockDelta(
                                i,
                                0,
                                0,
                                time,
                                time * 1000 + i,
                                new BlockData("minecraft:air", null),
                                new BlockData("minecraft:stone", null)));
            }
            writer.finish();
        }
        journal =
                new EditJournal(
                        root,
                        index,
                        new com.helion3.prism.configuration.category.WorldEditCategory(),
                        mock(Logger.class));
        journal.close();
        StorageAdapterRecords ordinary = mock(StorageAdapterRecords.class);
        when(ordinary.query(any(QuerySession.class), eq(false)))
                .thenAnswer(
                        invocation -> {
                            QuerySession query = invocation.getArgument(0);
                            List<Result> page = Arrays.asList(result(1000), result(3000));
                            if (query.getSortBy() == Sort.NEWEST_FIRST) {
                                page = Arrays.asList(page.get(1), page.get(0));
                            }
                            return CompletableFuture.completedFuture(
                                    query.getQuery().getOffset() == 0
                                            ? page
                                            : Collections.emptyList());
                        });
        records = new MergedRecords(ordinary, journal);
    }

    @AfterEach
    public void cleanup() throws Exception {
        journal.close();
        Field instance = Prism.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, previousPrism);
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private Result result(long time) {
        Result result = new ResultComplete();
        result.data =
                DataContainer.createNew()
                        .set(DataQueries.EventName, "place")
                        .set(DataQueries.Cause, "environment")
                        .set(DataQueries.Target, "minecraft:dirt")
                        .set(DataQueries.Created, time / 1000)
                        .set(DataQueries.Captured, time)
                        .set(DataQueries.Sequence, time * 1000);
        return result;
    }

    private QuerySession session(Sort sort, boolean grouped, int limit) {
        QuerySession session = new QuerySession(mock(CommandSource.class));
        session.newQuery().setLimit(limit);
        session.setSortBy(sort);
        if (!grouped) {
            session.addFlag(Flag.NO_GROUP);
        }
        return session;
    }

    @Test
    public void appliesOneGlobalBlockLimitAfterMergingAndSortingBothSources() throws Exception {
        QuerySession newest = session(Sort.NEWEST_FIRST, false, 7);
        List<Result> results = records.query(newest, false).get();
        assertEquals(7, results.size());
        assertTrue(newest.isTruncated());
        assertEquals(4000, results.get(0).data.getLong(DataQueries.Captured).get().longValue());
        assertEquals(3000, results.get(6).data.getLong(DataQueries.Captured).get().longValue());
        QuerySession oldest = session(Sort.OLDEST_FIRST, false, 2);
        results = records.query(oldest, false).get();
        assertEquals(1000, results.get(0).data.getLong(DataQueries.Captured).get().longValue());
        assertEquals(2000, results.get(1).data.getLong(DataQueries.Captured).get().longValue());
    }

    @Test
    public void groupedCountsIncludeAllChangesEvenWhenOnlyOneGroupIsReturned() throws Exception {
        QuerySession query = session(Sort.NEWEST_FIRST, true, 1);
        List<Result> results = records.query(query, false).get();
        assertEquals(1, results.size());
        assertEquals("worldedit", results.get(0).getEventId());
        assertEquals(12, results.get(0).data.getLong(DataQueries.Count).get().longValue());
        assertTrue(query.isTruncated());
    }
}
