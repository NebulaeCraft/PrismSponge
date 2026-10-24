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

import com.boydti.fawe.Fawe;
import com.boydti.fawe.config.Settings;
import com.boydti.fawe.example.IFaweQueueMap;
import com.boydti.fawe.example.MappedFaweQueue;
import com.boydti.fawe.object.*;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.*;
import com.sk89q.worldedit.function.operation.*;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Adapter for the Forge112 5505943 queue API, isolated from ordinary WE class loading. */
@SuppressWarnings("rawtypes")
public final class FaweHook extends WorldEditHook {
    private final Map<FaweQueue, QueueCapture> queues =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final String unavailable;

    public FaweHook(EditJournal journal, Map<String, String> worlds)
            throws ReflectiveOperationException {
        super(journal, worlds);
        unavailable = compatibilityReason();
    }

    FaweHook(EditJournal journal, Map<String, String> worlds, Encoder encoder) {
        super(journal, worlds, encoder);
        unavailable = null;
    }

    private String compatibilityReason() {
        if (Fawe.get() == null
                || Fawe.get().getVersion() == null
                || Fawe.get().getVersion().hash != 0x5505943) {
            return "Unsupported FAWE build; this adapter requires Forge112 5505943";
        } else if (Settings.IMP.EXTENT.ALLOWED_PLUGINS.stream()
                .noneMatch(
                        value ->
                                getClass()
                                        .getName()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(value.toLowerCase(Locale.ROOT)))) {
            return "Add com.helion3.prism to FAWE extent.allowed-plugins and restart";
        } else {
            return null;
        }
    }

    @Override
    public void register() {
        super.register();
        if (unavailable != null) {
            com.helion3.prism.Prism.getInstance()
                    .getLogger()
                    .error("WorldEdit capture is blocked: {}", unavailable);
        } else {
            com.helion3.prism.Prism.getInstance()
                    .getLogger()
                    .info(
                            "FAWE Forge112 5505943 capture enabled: queued-map lookup and batch dispatch completion.");
        }
    }

    @Override
    @Subscribe
    public void onSession(EditSessionEvent event) {
        if (event.getWorld() == null) {
            return;
        }
        if (unavailable != null) {
            reject(event, unavailable);
            return;
        }
        try {
            EditSession session =
                    (EditSession) event.getClass().getMethod("getEditSession").invoke(event);
            FaweQueue queue = (FaweQueue) session.getClass().getMethod("getQueue").invoke(session);
            if (!queue.supports(FaweQueue.Capability.CHANGE_TASKS)
                    || !queue.getClass()
                            .getName()
                            .equals("com.boydti.fawe.forge.v112.ForgeQueue_All")) {
                reject(event, "Unsupported FAWE queue; offline/anvil edits cannot be audited");
                return;
            }
            QueueCapture capture;
            synchronized (queues) {
                capture = queues.computeIfAbsent(queue, QueueCapture::new);
            }
            if (event.getStage() == EditSession.Stage.BEFORE_CHANGE) {
                journal.requireAvailable();
                event.setExtent(recordingExtent(event.getExtent(), event, capture));
            }
            // Native history installs its own callback between BEFORE_CHANGE and BEFORE_HISTORY.
            if (event.getStage() == EditSession.Stage.BEFORE_HISTORY) {
                capture.install();
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reject(event, failure.getMessage());
        }
    }

    Extent recordingExtent(Extent delegate, EditSessionEvent event, QueueCapture capture) {
        return new QueueExtent(delegate, event, capture);
    }

    private void reject(EditSessionEvent event, String reason) {
        if (event.getActor() != null) {
            event.getActor().printError("Prism: " + reason);
        }
        try {
            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot safely reject unsupported FAWE edit", failure);
        }
    }

    private final class QueueExtent extends AbstractDelegateExtent {
        private final EditSessionEvent event;
        private final QueueCapture capture;
        private EditJournal.Handle handle;

        QueueExtent(Extent delegate, EditSessionEvent event, QueueCapture capture) {
            super(delegate);
            this.event = event;
            this.capture = capture;
        }

        @Override
        public boolean setBlock(Vector position, BaseBlock block) throws WorldEditException {
            if (CaptureGuard.active()) {
                return super.setBlock(position, block);
            }
            try {
                journal.requireAvailable();
                capture.install();
                if (handle == null) {
                    handle = begin(event, "fawe-5505943");
                }
                Owner owner = new Owner(handle, CaptureOrder.next());
                int local =
                        ((position.getBlockY() & 255) << 8)
                                | ((position.getBlockZ() & 15) << 4)
                                | (position.getBlockX() & 15);
                QueueCapture.Enqueue enqueue =
                        capture.beginEnqueue(
                                position.getBlockX() >> 4, position.getBlockZ() >> 4, local, owner);
                boolean accepted;
                try (CaptureGuard ignored =
                        CaptureGuard.enter(
                                handle.operation.world,
                                position.getBlockX(),
                                position.getBlockY(),
                                position.getBlockZ())) {
                    accepted = super.setBlock(position, block);
                } catch (WorldEditException | RuntimeException failure) {
                    enqueue.restore();
                    handle.partial();
                    throw failure;
                } finally {
                    capture.endEnqueue(enqueue);
                }
                if (!accepted) {
                    enqueue.restore();
                }
                return accepted;
            } catch (RuntimeException failure) {
                if (handle != null) {
                    handle.partial();
                }
                if (event.getActor() != null) {
                    event.getActor().printError("Prism: " + failure.getMessage());
                }
                throw new RecordingException("Prism FAWE recording failed", failure);
            }
        }

        public boolean setBlock(int x, int y, int z, BaseBlock block) throws WorldEditException {
            return setBlock(new Vector(x, y, z), block);
        }

        @Override
        protected Operation commitBefore() {
            return new Operation() {
                @Override
                public Operation resume(RunContext context) {
                    if (handle != null) {
                        EditJournal.Handle pending = handle;
                        handle = null;
                        capture.notifyFinish(pending, () -> {});
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

    static final class Owner {
        final EditJournal.Handle handle;
        final long order;

        Owner(EditJournal.Handle handle, long order) {
            this.handle = handle;
            this.order = order;
        }
    }

    final class QueueCapture {
        final FaweQueue queue;
        final Map<FaweChunk, Map<Integer, Owner>> owners = new IdentityHashMap<>();
        final Set<EditJournal.Handle> notified = Collections.newSetFromMap(new IdentityHashMap<>());
        final Queue<Runnable> completedDispatches = new ConcurrentLinkedQueue<>();
        final ThreadLocal<Enqueue> enqueuing = new ThreadLocal<>();
        RunnableVal2<FaweChunk, FaweChunk> installed;
        RunnableVal2<FaweChunk, FaweChunk> parent;

        QueueCapture(FaweQueue queue) {
            this.queue = queue;
        }

        FaweChunk queuedChunk(int x, int z) {
            // ForgeQueue_All.getFaweChunk is a factory, not a lookup of queued data.
            return ((MappedFaweQueue) queue).getFaweQueueMap().getFaweChunk(x, z);
        }

        final class Enqueue {
            final int x;
            final int z;
            final int position;
            final Owner owner;
            final Enqueue previous;
            final Map<FaweChunk, Owner> replaced = new IdentityHashMap<>();

            Enqueue(int x, int z, int position, Owner owner) {
                this.x = x;
                this.z = z;
                this.position = position;
                this.owner = owner;
                previous = enqueuing.get();
            }

            void bind(int x, int z, FaweChunk chunk) {
                if (x == this.x && z == this.z && !replaced.containsKey(chunk)) {
                    replaced.put(chunk, own(chunk, position, owner));
                }
            }

            void restore() {
                replaced.forEach(
                        (chunk, before) ->
                                QueueCapture.this.restore(chunk, position, owner, before));
            }
        }

        Enqueue beginEnqueue(int x, int z, int position, Owner owner) {
            Enqueue context = new Enqueue(x, z, position, owner);
            enqueuing.set(context);
            return context;
        }

        void endEnqueue(Enqueue context) {
            if (context.previous == null) {
                enqueuing.remove();
            } else {
                enqueuing.set(context.previous);
            }
        }

        private void installDispatchHook() {
            MappedFaweQueue mapped = (MappedFaweQueue) queue;
            IFaweQueueMap current = mapped.getFaweQueueMap();
            if (current instanceof DispatchMap && ((DispatchMap) current).capture() == this) {
                return;
            }
            try {
                Field field = MappedFaweQueue.class.getDeclaredField("map");
                field.setAccessible(true);
                field.set(mapped, new DispatchMap(current));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "Cannot install FAWE 5505943 dispatch completion hook", failure);
            }
        }

        private void completeBatch() {
            Runnable completion;
            while ((completion = completedDispatches.poll()) != null) {
                completion.run();
            }
        }

        /** Both legacy queue maps join their dispatched workers before next() returns. */
        private final class DispatchMap implements IFaweQueueMap {
            final IFaweQueueMap delegate;

            DispatchMap(IFaweQueueMap delegate) {
                this.delegate = Objects.requireNonNull(delegate, "FAWE queue map");
            }

            QueueCapture capture() {
                return QueueCapture.this;
            }

            @Override
            public Collection<FaweChunk> getFaweCunks() {
                return delegate.getFaweCunks();
            }

            @Override
            public void forEachChunk(RunnableVal<FaweChunk> task) {
                delegate.forEachChunk(task);
            }

            @Override
            public FaweChunk getFaweChunk(int x, int z) {
                FaweChunk chunk = delegate.getFaweChunk(x, z);
                Enqueue context = enqueuing.get();
                if (context != null) {
                    context.bind(x, z, chunk);
                }
                return chunk;
            }

            @Override
            public FaweChunk getCachedFaweChunk(int x, int z) {
                return delegate.getCachedFaweChunk(x, z);
            }

            @Override
            public void add(FaweChunk chunk) {
                delegate.add(chunk);
            }

            @Override
            public void clear() {
                delegate.clear();
            }

            @Override
            public int size() {
                return delegate.size();
            }

            @Override
            public boolean next(int amount, long time) {
                boolean remaining = delegate.next(amount, time);
                // FaweChunk.addNotifyTask is never executed by Forge112's dispatch path.
                // Verify applied states here, before SetQueue runs operation completion tasks.
                completeBatch();
                return remaining;
            }
        }

        synchronized Owner own(FaweChunk chunk, int position, Owner owner) {
            Map<Integer, Owner> positions =
                    owners.computeIfAbsent(chunk, ignored -> new HashMap<>());
            if (!positions.containsKey(position)) {
                journal.reserveProvenance(128);
            }
            return positions.put(position, owner);
        }

        synchronized void restore(FaweChunk chunk, int position, Owner owner, Owner previous) {
            Map<Integer, Owner> positions = owners.get(chunk);
            if (positions != null && positions.get(position) == owner) {
                if (previous == null) {
                    positions.remove(position);
                    journal.releaseProvenance(128);
                } else {
                    positions.put(position, previous);
                }
            }
        }

        synchronized void notifyFinish(EditJournal.Handle handle, Runnable reset) {
            if (notified.add(handle)) {
                queue.addNotifyTask(
                        () -> {
                            synchronized (QueueCapture.this) {
                                for (Map<Integer, Owner> positions : owners.values()) {
                                    positions
                                            .entrySet()
                                            .removeIf(
                                                    entry -> {
                                                        if (entry.getValue().handle != handle) {
                                                            return false;
                                                        }
                                                        handle.partial();
                                                        journal.releaseProvenance(128);
                                                        return true;
                                                    });
                                }
                                owners.entrySet().removeIf(entry -> entry.getValue().isEmpty());
                                notified.remove(handle);
                            }
                            handle.finish();
                            reset.run();
                        });
            }
        }

        synchronized void install() {
            installDispatchHook();
            if (queue.getChangeTask() == installed && installed != null) {
                return;
            }
            parent = queue.getChangeTask();
            final RunnableVal2<FaweChunk, FaweChunk> original = parent;
            installed =
                    new RunnableVal2<FaweChunk, FaweChunk>() {
                        @Override
                        public void run(FaweChunk previous, FaweChunk next) {
                            try {
                                if (original != null) {
                                    original.run(previous, next);
                                }
                            } finally {
                                capture(previous, next);
                            }
                        }
                    };
            queue.setChangeTask(installed);
        }

        private void capture(FaweChunk previous, FaweChunk next) {
            Map<Integer, Owner> positions;
            synchronized (this) {
                positions = owners.remove(next);
            }
            if (positions == null || positions.isEmpty()) {
                return;
            }
            // Preserve immutable snapshots here. FAWE reuses/mutates chunk arrays after this
            // callback.
            List<Map.Entry<Owner, BlockDelta>> prepared = new ArrayList<>();
            journal.releaseProvenance(128L * positions.size());
            try {
                for (Map.Entry<Integer, Owner> entry : positions.entrySet()) {
                    int local = entry.getKey();
                    int x = local & 15;
                    int z = (local >> 4) & 15;
                    int y = local >> 8;
                    Owner owner = entry.getValue();
                    BlockData before = encoder.encode(chunkBlock(previous, x, y, z));
                    BlockData expected = encoder.encode(chunkBlock(next, x, y, z));
                    if (before.bytes() + expected.bytes() + 96 > 8 * 1024 * 1024) {
                        throw new IOException("WE block NBT exceeds 8 MiB per change");
                    }
                    journal.reservePrepared(before.bytes() + expected.bytes() + 128L);
                    prepared.add(
                            new AbstractMap.SimpleImmutableEntry<>(
                                    owner,
                                    new BlockDelta(
                                            (next.getX() << 4) + x,
                                            y,
                                            (next.getZ() << 4) + z,
                                            owner.order / 1000,
                                            owner.order,
                                            before,
                                            expected)));
                }
            } catch (IOException | RuntimeException failure) {
                for (Map.Entry<Owner, BlockDelta> entry : prepared) {
                    BlockDelta delta = entry.getValue();
                    journal.releaseProvenance(delta.before.bytes() + delta.after.bytes() + 128L);
                }
                positions.values().forEach(owner -> owner.handle.partial());
                journal.captureFailed(failure);
                // ForgeChunk_All catches this before merging blocks. Do not let an unaudited chunk
                // apply.
                throw new IllegalStateException(
                        "Prism could not capture FAWE chunk before dispatch", failure);
            }
            completedDispatches.add(
                    () -> {
                        // Dispatch and chunk completion execute on FAWE's world owner thread,
                        // before the next dispatch.
                        for (Map.Entry<Owner, BlockDelta> entry : prepared) {
                            BlockDelta intended = entry.getValue();
                            try {
                                BlockData actual =
                                        encoder.encode(
                                                queue.getWEWorld()
                                                        .getBlock(
                                                                new Vector(
                                                                        intended.x,
                                                                        intended.y,
                                                                        intended.z)));
                                if (!actual.equals(intended.after)) {
                                    entry.getKey().handle.partial();
                                }
                                if (!actual.equals(intended.before)) {
                                    long order = CaptureOrder.next();
                                    entry.getKey()
                                            .handle
                                            .append(
                                                    new BlockDelta(
                                                            intended.x,
                                                            intended.y,
                                                            intended.z,
                                                            order / 1000,
                                                            order,
                                                            intended.before,
                                                            actual));
                                }
                            } catch (IOException | RuntimeException failure) {
                                entry.getKey().handle.partial();
                                journal.captureFailed(failure);
                            } finally {
                                journal.releaseProvenance(
                                        intended.before.bytes() + intended.after.bytes() + 128L);
                            }
                        }
                    });
        }

        private BaseBlock chunkBlock(FaweChunk chunk, int x, int y, int z) {
            int combined = chunk.getBlockCombinedId(x, y, z);
            // Do not use FaweCache.hasNBT: its vanilla block list is not authoritative for mod
            // tiles.
            return new BaseBlock(combined >> 4, combined & 15, chunk.getTile(x, y, z));
        }
    }
}
