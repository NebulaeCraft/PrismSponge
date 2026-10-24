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

/** A confirmed change; repeated writes that reached the world retain their order. */
public final class BlockDelta {
    public final int x;
    public final int y;
    public final int z;
    public final long time;
    public final long order;
    public final BlockData before;
    public final BlockData after;

    public BlockDelta(
            int x, int y, int z, long time, long order, BlockData before, BlockData after) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.time = time;
        this.order = order;
        this.before = before;
        this.after = after;
    }

    public String section() {
        return section(x >> 4, y >> 4, z >> 4);
    }

    public static String section(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    public int bytes() {
        return 96 + before.bytes() + after.bytes();
    }

    public String target() {
        return (after.air() ? before : after).type().replace('_', ' ');
    }
}
