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

import com.sk89q.jnbt.*;
import com.sk89q.worldedit.blocks.BaseBlock;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;

/** Forge's native IBlockState implements Sponge BlockState through SpongeForge mixins. */
public final class WorldEditStateCodec {
    private static Method byId;
    private static Method fromMeta;

    private WorldEditStateCodec() {}

    public static void initialize() throws ReflectiveOperationException {
        Class<?> block = Class.forName("net.minecraft.block.Block");
        byId = method(block, "func_149729_e", "getBlockById", int.class);
        fromMeta = method(block, "func_176203_a", "getStateFromMeta", int.class);
    }

    private static Method method(
            Class<?> type, String runtime, String development, Class<?> parameter)
            throws NoSuchMethodException {
        try {
            return type.getMethod(runtime, parameter);
        } catch (NoSuchMethodException missing) {
            return type.getMethod(development, parameter);
        }
    }

    public static BlockData encode(BaseBlock block) throws IOException {
        try {
            Object nativeBlock = byId.invoke(null, block.getId());
            if (nativeBlock == null) {
                throw new IOException("Unregistered WE block ID " + block.getId());
            }
            Object nativeState = fromMeta.invoke(nativeBlock, block.getData());
            if (!(nativeState instanceof BlockState)) {
                throw new IOException("SpongeForge BlockState bridge unavailable");
            }
            byte[] nbt = new byte[0];
            if (block.hasNbtData()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (NBTOutputStream output = new NBTOutputStream(bytes)) {
                    output.writeNamedTag("", block.getNbtData());
                }
                nbt = bytes.toByteArray();
            }
            return new BlockData(((BlockState) nativeState).getId(), nbt);
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Cannot convert WE block state", failure);
        }
    }

    public static DataContainer tile(byte[] nbt, int x, int y, int z) throws IOException {
        try (NBTInputStream input = new NBTInputStream(new ByteArrayInputStream(nbt))) {
            Tag tag = input.readNamedTag().getTag();
            if (!(tag instanceof CompoundTag)) {
                throw new IOException("Expected compound tile NBT");
            }
            DataContainer data = compound((CompoundTag) tag);
            data.set(DataQuery.of("x"), x);
            data.set(DataQuery.of("y"), y);
            data.set(DataQuery.of("z"), z);
            return data;
        }
    }

    private static DataContainer compound(CompoundTag tag) throws IOException {
        DataContainer data = DataContainer.createNew();
        for (Map.Entry<String, Tag> entry : tag.getValue().entrySet()) {
            data.set(DataQuery.of(entry.getKey()), value(entry.getValue()));
        }
        return data;
    }

    private static Object value(Tag tag) throws IOException {
        if (tag instanceof CompoundTag) {
            return compound((CompoundTag) tag);
        }
        if (tag instanceof ListTag) {
            List<Object> values = new ArrayList<>();
            for (Tag child : ((ListTag) tag).getValue()) {
                values.add(value(child));
            }
            return values;
        }
        return tag.getValue();
    }
}
