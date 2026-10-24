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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;

/** Reads standard binary NBT without requiring WE to remain installed for Prism restores. */
public final class TileData {
    private static final int LIMIT = 8 * 1024 * 1024;

    private TileData() {}

    public static DataContainer decode(byte[] bytes, int x, int y, int z) throws IOException {
        if (bytes.length > LIMIT) {
            throw new IOException("Oversized tile NBT");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readUnsignedByte() != 10) {
                throw new IOException("Expected compound tile NBT");
            }
            string(input);
            DataContainer data = (DataContainer) read(input, 10, 0);
            if (input.available() != 0) {
                throw new IOException("Trailing tile NBT data");
            }
            data.set(DataQuery.of("x"), x);
            data.set(DataQuery.of("y"), y);
            data.set(DataQuery.of("z"), z);
            return data;
        }
    }

    private static String string(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int length(DataInputStream input, int width) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > LIMIT / width || length > input.available() / width) {
            throw new IOException("Invalid NBT collection length");
        }
        return length;
    }

    private static Object read(DataInputStream input, int type, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException("Tile NBT nesting exceeds 64 levels");
        }
        switch (type) {
            case 1:
                return input.readByte();
            case 2:
                return input.readShort();
            case 3:
                return input.readInt();
            case 4:
                return input.readLong();
            case 5:
                return input.readFloat();
            case 6:
                return input.readDouble();
            case 7:
                {
                    byte[] bytes = new byte[length(input, 1)];
                    input.readFully(bytes);
                    return bytes;
                }
            case 8:
                return string(input);
            case 9:
                {
                    int child = input.readUnsignedByte();
                    int count = length(input, 1);
                    List<Object> values = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        values.add(read(input, child, depth + 1));
                    }
                    return values;
                }
            case 10:
                {
                    DataContainer compound = DataContainer.createNew();
                    int child;
                    while ((child = input.readUnsignedByte()) != 0) {
                        compound.set(DataQuery.of(string(input)), read(input, child, depth + 1));
                    }
                    return compound;
                }
            case 11:
                {
                    int[] values = new int[length(input, 4)];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = input.readInt();
                    }
                    return values;
                }
            case 12:
                {
                    long[] values = new long[length(input, 8)];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = input.readLong();
                    }
                    return values;
                }
            default:
                throw new IOException("Unknown NBT tag type: " + type);
        }
    }
}
