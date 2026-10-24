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

import com.sk89q.jnbt.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;

public class TileDataTest {
    @Test
    public void preservesTypedArraysNestedInventoryAndUnicodeText() throws Exception {
        Map<String, Tag> values = new HashMap<>();
        values.put("id", new StringTag("mod:machine"));
        values.put("Text1", new StringTag("中文告示�?"));
        values.put("Bytes", new ByteArrayTag(new byte[] {-128, 0, 127}));
        values.put("Ints", new IntArrayTag(new int[] {-1, Integer.MAX_VALUE}));
        values.put("Energy", new LongTag(Long.MAX_VALUE));
        Map<String, Tag> item = new HashMap<>();
        item.put("Count", new ByteTag((byte) 64));
        item.put("id", new StringTag("minecraft:diamond"));
        values.put(
                "Items",
                new ListTag(CompoundTag.class, Collections.singletonList(new CompoundTag(item))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (NBTOutputStream output = new NBTOutputStream(bytes)) {
            output.writeNamedTag("", new CompoundTag(values));
        }
        DataContainer data = TileData.decode(bytes.toByteArray(), -1, 64, 17);
        assertEquals("中文告示�?", data.getString(DataQuery.of("Text1")).get());
        assertArrayEquals(
                new byte[] {-128, 0, 127}, (byte[]) data.get(DataQuery.of("Bytes")).get());
        assertArrayEquals(
                new int[] {-1, Integer.MAX_VALUE}, (int[]) data.get(DataQuery.of("Ints")).get());
        assertEquals(Long.MAX_VALUE, data.getLong(DataQuery.of("Energy")).get().longValue());
        assertEquals(-1, data.getInt(DataQuery.of("x")).get().intValue());
        assertEquals(1, data.getViewList(DataQuery.of("Items")).get().size());
    }

    @Test
    public void scopedDeduplicationDoesNotHideNeighboringPhysicsChanges() {
        assertFalse(CaptureGuard.active());
        try (CaptureGuard ignored = CaptureGuard.enter("world", 1, 2, 3)) {
            assertTrue(CaptureGuard.owns("world", 1, 2, 3));
            assertFalse(CaptureGuard.owns("world", 2, 2, 3));
            assertFalse(CaptureGuard.active());
        }
        assertFalse(CaptureGuard.owns("world", 1, 2, 3));
    }
}
