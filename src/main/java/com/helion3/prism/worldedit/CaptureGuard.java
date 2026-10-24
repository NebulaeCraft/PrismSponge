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

/** Suppresses only synchronous events caused by the wrapped Prism/WE world mutation. */
public final class CaptureGuard implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<java.util.Map<String, Integer>> POSITIONS =
            ThreadLocal.withInitial(java.util.HashMap::new);
    private final String position;

    private CaptureGuard(String position) {
        this.position = position;
        if (position == null) {
            DEPTH.set(DEPTH.get() + 1);
        } else {
            POSITIONS.get().merge(position, 1, Integer::sum);
        }
    }

    public static CaptureGuard enter() {
        return new CaptureGuard(null);
    }

    public static CaptureGuard enter(String world, int x, int y, int z) {
        return new CaptureGuard(world + ":" + x + ":" + y + ":" + z);
    }

    public static boolean active() {
        return DEPTH.get() != 0;
    }

    public static boolean owns(String world, int x, int y, int z) {
        return active() || POSITIONS.get().containsKey(world + ":" + x + ":" + y + ":" + z);
    }

    @Override
    public void close() {
        if (position != null) {
            POSITIONS
                    .get()
                    .computeIfPresent(position, (key, count) -> count == 1 ? null : count - 1);
            if (POSITIONS.get().isEmpty()) {
                POSITIONS.remove();
            }
            return;
        }
        int depth = DEPTH.get() - 1;
        if (depth == 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }
}
