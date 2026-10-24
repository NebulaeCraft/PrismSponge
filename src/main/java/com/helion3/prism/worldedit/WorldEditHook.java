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

import com.sk89q.worldedit.*;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.*;
import com.sk89q.worldedit.function.operation.*;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Loaded reflectively only when WE6 is installed. Uses only the shared WE6 API. */
public class WorldEditHook implements AutoCloseable {
    protected final EditJournal journal;
    protected final Map<String, String> worlds;

    interface Encoder {
        BlockData encode(BaseBlock block) throws IOException;
    }

    protected final Encoder encoder;

    public WorldEditHook(EditJournal journal, Map<String, String> worlds)
            throws ReflectiveOperationException {
        this(journal, worlds, WorldEditStateCodec::encode);
        WorldEditStateCodec.initialize();
    }

    WorldEditHook(EditJournal journal, Map<String, String> worlds, Encoder encoder) {
        this.journal = journal;
        this.worlds = worlds;
        this.encoder = encoder;
    }

    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
    }

    @Subscribe
    public void onSession(EditSessionEvent event) {
        if (event.getWorld() == null || event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        event.setExtent(new CaptureExtent(event.getExtent(), event));
    }

    protected EditJournal.Handle begin(EditSessionEvent event, String source) {
        String world = resolveWorld(event);
        if (world == null) {
            throw new IllegalStateException(
                    "Prism has no UUID mapping for WE world " + event.getWorld().getName());
        }
        Actor actor = event.getActor();
        return journal.begin(
                world,
                actor != null && actor.isPlayer() ? actor.getUniqueId().toString() : null,
                actor == null ? "worldedit" : actor.getName(),
                source);
    }

    private String resolveWorld(EditSessionEvent event) {
        Object current = event.getWorld();
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof org.spongepowered.api.world.World) {
                return ((org.spongepowered.api.world.World) current).getUniqueId().toString();
            }
            Object parent = null;
            for (String accessor : new String[] {"getWorld", "getParent"}) {
                try {
                    Object candidate = current.getClass().getMethod(accessor).invoke(current);
                    if (candidate != current) {
                        parent = candidate;
                        break;
                    }
                } catch (ReflectiveOperationException absent) {
                    /* Try the next wrapper type. */
                }
            }
            current = parent;
        }
        return worlds.get(event.getWorld().getName());
    }

    protected final class CaptureExtent extends AbstractDelegateExtent {
        private final EditSessionEvent event;
        private EditJournal.Handle handle;

        CaptureExtent(Extent extent, EditSessionEvent event) {
            super(extent);
            this.event = event;
        }

        @Override
        public boolean setBlock(Vector position, BaseBlock block) throws WorldEditException {
            if (CaptureGuard.active()) {
                return super.setBlock(position, block);
            }
            try {
                journal.requireAvailable();
                if (handle == null) {
                    handle = begin(event, "worldedit6");
                }
                BlockData before = encoder.encode(getExtent().getBlock(position));
                BlockData requested = encoder.encode(block);
                long reserved = before.bytes() + requested.bytes() + 128L;
                if (reserved > 8 * 1024 * 1024) {
                    throw new IOException("WE block NBT exceeds 8 MiB per change");
                }
                journal.reservePrepared(reserved);
                try {
                    boolean changed;
                    try (CaptureGuard ignored =
                            CaptureGuard.enter(
                                    handle.operation.world,
                                    position.getBlockX(),
                                    position.getBlockY(),
                                    position.getBlockZ())) {
                        changed = super.setBlock(position, block);
                    }
                    if (changed) {
                        BlockData after = encoder.encode(getExtent().getBlock(position));
                        long order = CaptureOrder.next();
                        handle.append(
                                new BlockDelta(
                                        position.getBlockX(),
                                        position.getBlockY(),
                                        position.getBlockZ(),
                                        order / 1000,
                                        order,
                                        before,
                                        after));
                    }
                    return changed;
                } finally {
                    journal.releaseProvenance(reserved);
                }
            } catch (WorldEditException failure) {
                if (handle != null) {
                    handle.partial();
                }
                throw failure;
            } catch (IOException | RuntimeException failure) {
                if (handle != null) {
                    handle.partial();
                }
                if (event.getActor() != null) {
                    event.getActor().printError("Prism: " + failure.getMessage());
                }
                throw new RecordingException("Prism WorldEdit recording failed", failure);
            }
        }

        // FAWE adds this overload to Extent; keeping it explicit prevents bypassing the vector
        // overload.
        public boolean setBlock(int x, int y, int z, BaseBlock block) throws WorldEditException {
            return setBlock(new Vector(x, y, z), block);
        }

        @Override
        protected Operation commitBefore() {
            return new Operation() {
                @Override
                public Operation resume(RunContext context) {
                    if (handle != null) {
                        handle.finish();
                        handle = null;
                    }
                    return null;
                }

                @Override
                public void cancel() {
                    if (handle != null) {
                        handle.partial();
                        handle.finish();
                        handle = null;
                    }
                }

                @Override
                public void addStatusMessages(List<String> messages) {}
            };
        }
    }

    static final class RecordingException extends WorldEditException {
        private static final long serialVersionUID = 1L;

        RecordingException(String message, Throwable cause) {
            super(message);
            initCause(cause);
        }
    }

    @Override
    public void close() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }
}
