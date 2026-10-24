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

import com.helion3.prism.Prism;
import com.helion3.prism.configuration.category.WorldEditCategory;
import java.util.function.IntConsumer;
import org.spongepowered.api.scheduler.Task;

/** World access stays on the server thread; large reversals yield between ticks. */
public final class TickBatch {
    private TickBatch() {}

    public static void apply(int size, IntConsumer action, Runnable complete) {
        WorldEditCategory config = Prism.getInstance().getConfig().getWorldEditCategory();
        new Runnable() {
            private int position;

            @Override
            public void run() {
                long deadline = System.nanoTime() + config.getMillisecondsPerTick() * 1000000L;
                int count = 0;
                while (position < size
                        && count < config.getBlocksPerTick()
                        && (count == 0 || System.nanoTime() < deadline)) {
                    try (CaptureGuard ignored = CaptureGuard.enter()) {
                        action.accept(position++);
                    }
                    count++;
                }
                if (position < size) {
                    Task.builder()
                            .delayTicks(1)
                            .execute(this)
                            .submit(Prism.getInstance().getPluginContainer());
                } else {
                    complete.run();
                }
            }
        }.run();
    }
}
