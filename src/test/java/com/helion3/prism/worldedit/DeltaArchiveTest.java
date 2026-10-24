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

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class DeltaArchiveTest {
    @TempDir Path root;

    static EditOperation operation() {
        EditOperation operation = new EditOperation();
        operation.world = UUID.randomUUID().toString();
        operation.player = UUID.randomUUID().toString();
        operation.actor = "Builder";
        operation.source = "test";
        return operation;
    }

    @Test
    public void roundTripsNegativeCoordinatesNbtAndRepeatedWrites() throws Exception {
        EditOperation operation = operation();
        BlockData first =
                new BlockData("mod:machine[facing=north,active=true]", new byte[] {0, 1, -1, 42});
        BlockData second = new BlockData("minecraft:air", null);
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            writer.append(new BlockDelta(-17, 255, -1, 100, 1, first, second));
            writer.append(new BlockDelta(-17, 255, -1, 101, 2, second, first));
            writer.flush();
            writer.append(new BlockDelta(32, 0, 16, 102, 3, second, first));
            writer.finish();
        }
        EditOperation durable = DeltaArchive.manifest(DeltaArchive.file(root, operation.id));
        assertEquals(3, durable.count);
        assertEquals(2, durable.frames.size());
        List<BlockDelta> found = new ArrayList<>();
        DeltaArchive.read(DeltaArchive.file(root, operation.id), durable.frames.get(0), found::add);
        assertEquals(2, found.size());
        assertEquals(-17, found.get(0).x);
        assertEquals(255, found.get(0).y);
        assertEquals(-1, found.get(0).z);
        assertEquals(first, found.get(0).before);
        assertEquals(first, found.get(1).after);
        assertTrue(found.get(0).order < found.get(1).order);
    }

    @Test
    public void rejectsCorruptionAndTruncatedArchives() throws Exception {
        EditOperation operation = operation();
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            writer.append(
                    new BlockDelta(
                            0,
                            0,
                            0,
                            100,
                            1,
                            new BlockData("minecraft:stone", null),
                            new BlockData("minecraft:air", null)));
            writer.finish();
        }
        Path file = DeltaArchive.file(root, operation.id);
        EditOperation durable = DeltaArchive.manifest(file);
        try (RandomAccessFile data = new RandomAccessFile(file.toFile(), "rw")) {
            data.seek(durable.frames.get(0).offset + 12);
            data.writeByte(0);
        }
        assertThrows(
                IOException.class,
                () ->
                        DeltaArchive.read(
                                file,
                                durable.frames.get(0),
                                delta -> fail("Corrupt frame must not emit changes")));
        try (RandomAccessFile data = new RandomAccessFile(file.toFile(), "rw")) {
            data.setLength(12);
        }
        assertThrows(IOException.class, () -> DeltaArchive.manifest(file));
    }

    @Test
    public void millionDenseChangesUse256SqlSectionRows() throws Exception {
        EditOperation operation = operation();
        BlockData air = new BlockData("minecraft:air", null);
        BlockData stone = new BlockData("minecraft:stone[variant=stone]", null);
        long start = System.nanoTime();
        try (DeltaArchive.Writer writer = new DeltaArchive.Writer(root, operation)) {
            long order = 0;
            for (int x = 0; x < 128; x++) {
                for (int z = 0; z < 128; z++) {
                    for (int y = 0; y < 64; y++) {
                        writer.append(new BlockDelta(x, y, z, 1000, ++order, air, stone));
                    }
                }
                assertTrue(writer.bufferedBytes() < 1024 * 1024);
            }
            writer.finish();
        }
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        SqlEditIndex index = new SqlEditIndex(() -> DriverManager.getConnection(url), "prism_");
        EditOperation durable = DeltaArchive.manifest(DeltaArchive.file(root, operation.id));
        index.publish(durable);
        index.publish(durable);
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            try (ResultSet rows =
                    statement.executeQuery("SELECT COUNT(*) FROM prism_we_operations")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
            try (ResultSet rows =
                    statement.executeQuery("SELECT COUNT(*),SUM(changes) FROM prism_we_sections")) {
                rows.next();
                assertEquals(256, rows.getInt(1));
                assertEquals(1048576, rows.getLong(2));
            }
        }
        AtomicLong count = new AtomicLong();
        for (EditOperation.Frame frame : durable.frames) {
            DeltaArchive.read(
                    DeltaArchive.file(root, durable.id), frame, delta -> count.incrementAndGet());
        }
        assertEquals(1048576, count.get());
        System.out.println(
                "WE dense benchmark: blocks="
                        + count.get()
                        + ", SQL rows=257, file bytes="
                        + Files.size(DeltaArchive.file(root, operation.id))
                        + ", encode/index/verify ms="
                        + (System.nanoTime() - start) / 1000000);
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }
}
