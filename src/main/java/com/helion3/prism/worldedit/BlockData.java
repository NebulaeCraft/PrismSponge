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

import java.util.Arrays;
import java.util.Objects;

/** A registry block state and lossless, typed NBT, independent of runtime numeric IDs. */
public final class BlockData {
    public final String state;
    private final byte[] nbt;

    public BlockData(String state, byte[] nbt) {
        this.state = Objects.requireNonNull(state, "state");
        this.nbt = nbt == null ? new byte[0] : nbt.clone();
    }

    public byte[] nbt() {
        return nbt.clone();
    }

    public int bytes() {
        return 64 + state.length() * 2 + nbt.length;
    }

    public String type() {
        return state.split("\\[", 2)[0];
    }

    public boolean air() {
        return type().equals("minecraft:air");
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BlockData)) {
            return false;
        }
        BlockData block = (BlockData) other;
        return state.equals(block.state) && Arrays.equals(nbt, block.nbt);
    }

    @Override
    public int hashCode() {
        return 31 * state.hashCode() + Arrays.hashCode(nbt);
    }
}
