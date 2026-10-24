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

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Independently checksummed, section-local compressed frames with a seekable footer. */
public final class DeltaArchive {
    private static final int MAGIC = 0x50574531;
    private static final int VERSION = 1;
    private static final int MAX_FRAME = 16 * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private DeltaArchive() {}

    public static Path file(Path root, String id) {
        UUID.fromString(id);
        return root.resolve(id + ".pwe");
    }

    public static EditOperation manifest(Path file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION || input.length() < 16) {
                throw new IOException("Unsupported or incomplete Prism WE archive: " + file);
            }
            input.seek(input.length() - 8);
            int length = input.readInt();
            if (input.readInt() != MAGIC
                    || length <= 0
                    || length > 64 * 1024 * 1024
                    || length > input.length() - 16) {
                throw new IOException("Invalid WE archive footer: " + file);
            }
            byte[] json = new byte[length];
            input.seek(input.length() - 8 - length);
            input.readFully(json);
            EditOperation result =
                    GSON.fromJson(new String(json, StandardCharsets.UTF_8), EditOperation.class);
            UUID.fromString(result.id);
            UUID.fromString(result.world);
            if (result.frames == null || result.source == null || result.status == null) {
                throw new IOException("Incomplete WE manifest");
            }
            for (EditOperation.Frame frame : result.frames) {
                if (frame.offset < 8
                        || frame.length < 12
                        || frame.length > MAX_FRAME * 2
                        || frame.offset + frame.length > input.length() - 8 - length) {
                    throw new IOException("Invalid WE frame offset");
                }
            }
            return result;
        } catch (RuntimeException ex) {
            throw new IOException("Invalid WE archive manifest: " + file, ex);
        }
    }

    public static void read(Path file, EditOperation.Frame frame, Consumer<BlockDelta> consumer)
            throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(frame.offset);
            int rawLength = input.readInt();
            int packedLength = input.readInt();
            int expectedCrc = input.readInt();
            if (rawLength < 0
                    || rawLength > MAX_FRAME
                    || packedLength < 0
                    || packedLength > MAX_FRAME * 2
                    || packedLength != frame.length - 12) {
                throw new IOException("Invalid WE frame size");
            }
            byte[] packed = new byte[packedLength];
            input.readFully(packed);
            ByteArrayOutputStream raw = new ByteArrayOutputStream(rawLength);
            try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = gzip.read(buffer)) != -1) {
                    if (raw.size() + count > rawLength) {
                        throw new IOException("Oversized WE frame");
                    }
                    raw.write(buffer, 0, count);
                }
            }
            byte[] bytes = raw.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(bytes);
            if (bytes.length != rawLength || (int) crc.getValue() != expectedCrc) {
                throw new IOException("WE frame checksum mismatch");
            }
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int paletteSize = data.readInt();
                if (paletteSize < 1 || paletteSize > 131072) {
                    throw new IOException("Invalid WE palette");
                }
                List<BlockData> palette = new ArrayList<>(paletteSize);
                for (int i = 0; i < paletteSize; i++) {
                    String state = new String(readBytes(data), StandardCharsets.UTF_8);
                    palette.add(new BlockData(state, readBytes(data)));
                }
                int count = data.readInt();
                if (count != frame.count || count < 0 || count > MAX_FRAME / 26) {
                    throw new IOException("Invalid WE change count");
                }
                for (int i = 0; i < count; i++) {
                    int local = data.readUnsignedShort();
                    if (local > 4095) {
                        throw new IOException("Invalid WE local coordinate");
                    }
                    long time = data.readLong();
                    long order = data.readLong();
                    int before = data.readInt();
                    int after = data.readInt();
                    if (before < 0 || after < 0 || before >= paletteSize || after >= paletteSize) {
                        throw new IOException("Invalid WE palette reference");
                    }
                    consumer.accept(
                            new BlockDelta(
                                    (frame.sx << 4) + (local & 15),
                                    (frame.sy << 4) + (local >> 8),
                                    (frame.sz << 4) + ((local >> 4) & 15),
                                    time,
                                    order,
                                    palette.get(before),
                                    palette.get(after)));
                }
                if (data.available() != 0) {
                    throw new IOException("Trailing WE frame data");
                }
            }
        }
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_FRAME || length > input.available()) {
            throw new IOException("Invalid WE value size");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /** Recover only complete, checksummed frames that were recorded after world confirmation. */
    public static void recover(Path root, Path checkpoint) throws IOException {
        String id = checkpoint.getFileName().toString().replace(".capture", "");
        Path complete = file(root, id);
        if (Files.exists(complete)) {
            Files.write(
                    root.resolve(id + ".pending"),
                    GSON.toJson(manifest(complete)).getBytes(StandardCharsets.UTF_8));
            Files.delete(checkpoint);
            return;
        }
        Path temporary = root.resolve(id + ".tmp");
        EditOperation operation;
        long end = 8;
        try (BufferedReader input = Files.newBufferedReader(checkpoint, StandardCharsets.UTF_8)) {
            operation = GSON.fromJson(input.readLine(), EditOperation.class);
            if (operation == null || !id.equals(operation.id)) {
                throw new IOException("Invalid WE checkpoint identity");
            }
            operation.frames.clear();
            operation.count = 0;
            String line;
            while ((line = input.readLine()) != null) {
                EditOperation.Frame frame;
                try {
                    frame = GSON.fromJson(line, EditOperation.Frame.class);
                } catch (RuntimeException truncated) {
                    break;
                }
                if (frame == null
                        || frame.offset < end
                        || frame.offset + frame.length > Files.size(temporary)) {
                    break;
                }
                try {
                    read(temporary, frame, delta -> {});
                } catch (IOException incomplete) {
                    break;
                }
                operation.frames.add(frame);
                operation.count += frame.count;
                end = frame.offset + frame.length;
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid WE checkpoint", invalid);
        }
        operation.status = "partial";
        operation.ended = System.currentTimeMillis();
        byte[] footer = GSON.toJson(operation).getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
            output.setLength(end);
            output.seek(end);
            output.write(footer);
            output.writeInt(footer.length);
            output.writeInt(MAGIC);
            output.getFD().sync();
        }
        Files.write(root.resolve(id + ".pending"), footer);
        Files.move(temporary, complete, StandardCopyOption.ATOMIC_MOVE);
        Files.delete(checkpoint);
    }

    public static final class Writer implements Closeable {
        private final Path root;
        private final EditOperation operation;
        private final RandomAccessFile output;
        private final RandomAccessFile checkpoint;
        private final Map<String, List<BlockDelta>> buffers = new LinkedHashMap<>();
        private int buffered;
        private boolean closed;

        public Writer(Path root, EditOperation operation) throws IOException {
            this.root = root;
            this.operation = operation;
            Files.createDirectories(root);
            Path temporary = root.resolve(operation.id + ".tmp");
            if (Files.exists(temporary) || Files.exists(file(root, operation.id))) {
                throw new IOException("Duplicate WE operation file");
            }
            output = new RandomAccessFile(temporary.toFile(), "rw");
            RandomAccessFile openedCheckpoint = null;
            try {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.getFD().sync();
                openedCheckpoint =
                        new RandomAccessFile(
                                root.resolve(operation.id + ".capture").toFile(), "rw");
                openedCheckpoint.write(
                        (GSON.toJson(operation) + "\n").getBytes(StandardCharsets.UTF_8));
                openedCheckpoint.getFD().sync();
                checkpoint = openedCheckpoint;
            } catch (IOException failure) {
                output.close();
                if (openedCheckpoint != null) {
                    openedCheckpoint.close();
                }
                throw failure;
            }
        }

        public void append(BlockDelta delta) throws IOException {
            if (closed) {
                throw new IOException("WE archive already closed");
            }
            if (delta.bytes() > MAX_FRAME / 2) {
                throw new IOException("WE block NBT exceeds archive limit");
            }
            buffers.computeIfAbsent(delta.section(), ignored -> new ArrayList<>()).add(delta);
            buffered += delta.bytes();
            if (buffered >= 1024 * 1024) {
                flush();
            }
        }

        public void flush() throws IOException {
            for (List<BlockDelta> changes : buffers.values()) {
                writeFrame(changes);
            }
            buffers.clear();
            buffered = 0;
            output.getFD().sync();
            checkpoint.getFD().sync();
        }

        public int bufferedBytes() {
            return buffered;
        }

        private void writeFrame(List<BlockDelta> changes) throws IOException {
            Map<BlockData, Integer> palette = new LinkedHashMap<>();
            for (BlockDelta delta : changes) {
                palette.computeIfAbsent(delta.before, ignored -> palette.size());
                palette.computeIfAbsent(delta.after, ignored -> palette.size());
            }
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(raw)) {
                data.writeInt(palette.size());
                for (BlockData state : palette.keySet()) {
                    writeBytes(data, state.state.getBytes(StandardCharsets.UTF_8));
                    writeBytes(data, state.nbt());
                }
                data.writeInt(changes.size());
                for (BlockDelta delta : changes) {
                    data.writeShort(((delta.y & 15) << 8) | ((delta.z & 15) << 4) | (delta.x & 15));
                    data.writeLong(delta.time);
                    data.writeLong(delta.order);
                    data.writeInt(palette.get(delta.before));
                    data.writeInt(palette.get(delta.after));
                }
            }
            byte[] bytes = raw.toByteArray();
            if (bytes.length > MAX_FRAME) {
                throw new IOException("Oversized WE frame");
            }
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(bytes);
            }
            CRC32 crc = new CRC32();
            crc.update(bytes);
            EditOperation.Frame frame = new EditOperation.Frame();
            BlockDelta first = changes.get(0);
            frame.sx = first.x >> 4;
            frame.sy = first.y >> 4;
            frame.sz = first.z >> 4;
            frame.offset = output.getFilePointer();
            frame.length = compressed.size() + 12;
            frame.count = changes.size();
            frame.first = changes.stream().mapToLong(delta -> delta.time).min().getAsLong();
            frame.last = changes.stream().mapToLong(delta -> delta.time).max().getAsLong();
            output.writeInt(bytes.length);
            output.writeInt(compressed.size());
            output.writeInt((int) crc.getValue());
            output.write(compressed.toByteArray());
            operation.frames.add(frame);
            operation.count += frame.count;
            checkpoint.write((GSON.toJson(frame) + "\n").getBytes(StandardCharsets.UTF_8));
        }

        public void finish() throws IOException {
            flush();
            operation.ended = System.currentTimeMillis();
            byte[] footer = GSON.toJson(operation).getBytes(StandardCharsets.UTF_8);
            output.write(footer);
            output.writeInt(footer.length);
            output.writeInt(MAGIC);
            output.getFD().sync();
            close();
            Path pending = root.resolve(operation.id + ".pending");
            Files.write(pending, footer, StandardOpenOption.CREATE_NEW);
            try (RandomAccessFile marker = new RandomAccessFile(pending.toFile(), "rw")) {
                marker.getFD().sync();
            }
            Files.move(
                    root.resolve(operation.id + ".tmp"),
                    file(root, operation.id),
                    StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(root.resolve(operation.id + ".capture"));
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                try {
                    output.close();
                } finally {
                    checkpoint.close();
                    buffers.clear();
                }
            }
        }
    }
}
