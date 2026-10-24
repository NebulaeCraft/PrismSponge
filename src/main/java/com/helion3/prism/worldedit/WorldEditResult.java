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

import com.helion3.prism.api.records.*;
import com.helion3.prism.util.BlockUtil;
import com.helion3.prism.util.DataQueries;
import java.util.*;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.Transaction;
import org.spongepowered.api.world.BlockChangeFlags;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

/** Expands a file-backed change into the existing Prism actionable contract. */
public final class WorldEditResult extends ResultComplete implements Actionable {
    private final EditOperation operation;
    private final BlockDelta delta;

    public WorldEditResult(EditOperation operation, BlockDelta delta) {
        this.operation = operation;
        this.delta = delta;
        data = DataContainer.createNew();
        data.set(DataQueries.EventName, "worldedit");
        data.set(DataQueries.Created, delta.time / 1000);
        data.set(DataQueries.Captured, delta.time);
        data.set(DataQueries.Sequence, delta.order);
        data.set(DataQueries.Target, delta.target());
        data.set(DataQueries.Cause, operation.player == null ? operation.actor : operation.player);
        if (operation.player != null) {
            data.set(DataQueries.Player, operation.player);
        }
        data.set(
                DataQueries.Location,
                DataContainer.createNew()
                        .set(DataQueries.WorldUuid, operation.world)
                        .set(DataQueries.X, delta.x)
                        .set(DataQueries.Y, delta.y)
                        .set(DataQueries.Z, delta.z));
        data.set(DataQuery.of("WEOperation"), operation.id);
        data.set(DataQuery.of("WESource"), operation.source);
        data.set(DataQuery.of("WEBefore"), delta.before.state);
        data.set(DataQuery.of("WEAfter"), delta.after.state);
        data.set(DataQuery.of("WEStatus"), operation.status);
    }

    @Override
    public ActionableResult rollback() throws Exception {
        return apply(delta.before);
    }

    @Override
    public ActionableResult restore() throws Exception {
        return apply(delta.after);
    }

    private ActionableResult apply(BlockData block) throws Exception {
        Optional<World> world = Sponge.getServer().getWorld(UUID.fromString(operation.world));
        if (!world.isPresent()) {
            return ActionableResult.skipped(SkipReason.INVALID_LOCATION);
        }
        Optional<BlockState> state = Sponge.getRegistry().getType(BlockState.class, block.state);
        if (!state.isPresent()) {
            return ActionableResult.skipped(SkipReason.INVALID);
        }
        if (BlockUtil.rejectIllegalApplierBlock(state.get().getType())) {
            return ActionableResult.skipped(SkipReason.ILLEGAL_BLOCK);
        }
        Location<World> location = world.get().getLocation(delta.x, delta.y, delta.z);
        BlockSnapshot before = location.createSnapshot();
        DataContainer snapshot = state.get().snapshotFor(location).toContainer();
        if (block.nbt().length != 0) {
            snapshot.set(
                    DataQueries.UnsafeData,
                    TileData.decode(block.nbt(), delta.x, delta.y, delta.z));
        }
        Optional<BlockSnapshot> desired = BlockSnapshot.builder().build(snapshot);
        if (!desired.isPresent()) {
            return ActionableResult.skipped(SkipReason.INVALID);
        }
        try (CaptureGuard ignored = CaptureGuard.enter()) {
            if (!desired.get().restore(true, BlockChangeFlags.NONE)) {
                return ActionableResult.skipped(SkipReason.UNKNOWN);
            }
        }
        return ActionableResult.success(new Transaction<>(before, location.createSnapshot()));
    }
}
