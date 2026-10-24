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
import static org.mockito.Mockito.mock;

import com.google.common.collect.Range;
import com.helion3.prism.api.query.*;
import com.helion3.prism.configuration.category.WorldEditCategory;
import com.helion3.prism.util.DataQueries;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

public class EditJournalTest {
    @TempDir Path root;
    private String url;
    private SqlEditIndex index;

    @BeforeEach
    public void setup() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        index = new SqlEditIndex(() -> DriverManager.getConnection(url), "prism_");
    }

    @AfterEach
    public void cleanup() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    @Test
    public void recoversPublicationAndFiltersExactLocationsWithinSection() throws Exception {
        EditOperation operation = DeltaArchiveTest.operation();
        BlockData air = new BlockData("minecraft:air", null);
        BlockData stone = new BlockData("minecraft:stone", null);
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            writer.append(new BlockDelta(-16, 0, -16, 1000, 1, air, stone));
            writer.append(new BlockDelta(-1, 1, -1, 1001, 2, air, stone));
            writer.append(new BlockDelta(1000, 0, 1000, 1002, 3, air, stone));
            writer.finish();
        }
        EditJournal journal =
                new EditJournal(root, index, new WorldEditCategory(), mock(Logger.class));
        journal.close();
        assertFalse(Files.exists(root.resolve(operation.id + ".pending")));
        Query query = new Query();
        query.addCondition(
                FieldCondition.of(DataQueries.Location.then(DataQueries.X), Range.open(-17, -15)));
        query.addCondition(
                FieldCondition.of(DataQueries.Player, MatchRule.EQUALS, operation.player));
        query.addCondition(FieldCondition.of(DataQueries.EventName, MatchRule.EQUALS, "worldedit"));
        AtomicInteger matched = new AtomicInteger();
        journal.query(
                query,
                (op, delta) -> {
                    assertEquals(-16, delta.x);
                    matched.incrementAndGet();
                });
        assertEquals(1, matched.get());
        journal.purge(Long.MAX_VALUE);
        assertFalse(Files.exists(DeltaArchive.file(root, operation.id)));
        assertTrue(index.candidates(new Query()).isEmpty());
    }

    @Test
    public void retriesSqlOutageWithoutDuplicateRows() throws Exception {
        AtomicBoolean unavailable = new AtomicBoolean(false);
        SqlEditIndex faulty =
                new SqlEditIndex(
                        () -> {
                            if (unavailable.get()) {
                                throw new SQLException("Injected outage");
                            }
                            return DriverManager.getConnection(url);
                        },
                        "retry_");
        EditOperation operation = DeltaArchiveTest.operation();
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            writer.append(
                    new BlockDelta(
                            1,
                            1,
                            1,
                            1000,
                            1,
                            new BlockData("minecraft:air", null),
                            new BlockData("mod:block", null)));
            writer.finish();
        }
        unavailable.set(true);
        EditJournal failed =
                new EditJournal(root, faulty, new WorldEditCategory(), mock(Logger.class));
        failed.close();
        assertTrue(Files.exists(root.resolve(operation.id + ".pending")));
        unavailable.set(false);
        EditJournal recovered =
                new EditJournal(root, faulty, new WorldEditCategory(), mock(Logger.class));
        recovered.close();
        assertFalse(Files.exists(root.resolve(operation.id + ".pending")));
        assertEquals(1, faulty.candidates(new Query()).size());
    }

    @Test
    public void failsQueryOnMissingPayloadRatherThanReturningPartialResults() throws Exception {
        EditOperation operation = DeltaArchiveTest.operation();
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            writer.append(
                    new BlockDelta(
                            0,
                            0,
                            0,
                            1000,
                            1,
                            new BlockData("minecraft:air", null),
                            new BlockData("minecraft:stone", null)));
            writer.finish();
        }
        EditJournal journal =
                new EditJournal(root, index, new WorldEditCategory(), mock(Logger.class));
        journal.close();
        Files.delete(DeltaArchive.file(root, operation.id));
        assertThrows(
                IOException.class,
                () ->
                        journal.query(
                                new Query(),
                                (op, delta) -> fail("Must fail before world application")));
    }

    @Test
    public void stopsAdmissionOnExhaustedBudgetAndOnCaptureFailure() throws Exception {
        EditJournal journal =
                new EditJournal(root, index, new WorldEditCategory(), mock(Logger.class));
        try {
            journal.reserveProvenance(64L * 1024 * 1024);
            assertThrows(IllegalStateException.class, journal::requireAvailable);
            journal.releaseProvenance(64L * 1024 * 1024);
            journal.requireAvailable();
            journal.captureFailed(new IOException("Injected disk failure"));
            assertThrows(IllegalStateException.class, journal::requireAvailable);
        } finally {
            journal.close();
        }
    }

    @Test
    public void migrationKeepsLegacyRowsAndIsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_records(id INT PRIMARY KEY,Created BIGINT)");
            statement.execute("INSERT INTO legacy_records VALUES(1,123)");
            CaptureSchema.migrate(connection, "legacy_records");
            CaptureSchema.migrate(connection, "legacy_records");
            try (ResultSet rows = statement.executeQuery("SELECT * FROM legacy_records")) {
                rows.next();
                assertEquals(123, rows.getLong("Created"));
                assertNull(rows.getObject("Captured"));
            }
        }
    }

    @Test
    public void crashRecoveryPublishesOnlyFlushedConfirmedFramesAsPartial() throws Exception {
        EditOperation operation = DeltaArchiveTest.operation();
        BlockData air = new BlockData("minecraft:air", null);
        BlockData stone = new BlockData("minecraft:stone", null);
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            writer.append(new BlockDelta(1, 2, 3, 1000, 1, air, stone));
            writer.flush();
            writer.append(new BlockDelta(4, 5, 6, 1001, 2, air, stone));
            // Abrupt close without finish simulates losing the writer's unflushed tail.
        }
        EditJournal recovered =
                new EditJournal(root, index, new WorldEditCategory(), mock(Logger.class));
        recovered.close();
        AtomicInteger matched = new AtomicInteger();
        recovered.query(
                new Query(),
                (op, delta) -> {
                    assertEquals("partial", op.status);
                    assertEquals(1, delta.x);
                    matched.incrementAndGet();
                });
        assertEquals(1, matched.get());
    }
}
