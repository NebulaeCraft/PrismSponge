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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sk89q.worldedit.*;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;

public class RejectingHookTest {
    @Test
    public void bootstrapFailureBlocksOrdinaryWeApiAndToolSessions() throws Exception {
        Extent original = mock(Extent.class);
        EditSessionEvent event =
                new EditSessionEvent(mock(World.class), null, -1, EditSession.Stage.BEFORE_CHANGE);
        event.setExtent(original);
        new RejectingWorldEditHook("injected failure").onSession(event);
        assertThrows(
                WorldEditException.class,
                () -> event.getExtent().setBlock(new Vector(1, 2, 3), new BaseBlock(1, 0)));
        verify(original, never()).setBlock(any(Vector.class), any(BaseBlock.class));
    }

    public static final class CancelEvent extends EditSessionEvent {
        boolean cancelled;

        public CancelEvent() {
            super(mock(World.class), null, -1, EditSession.Stage.BEFORE_HISTORY);
        }

        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }

    @Test
    public void bootstrapFailureCancelsFaweBeforeItCanDiscardAnUnapprovedExtent() {
        CancelEvent event = new CancelEvent();
        new RejectingWorldEditHook("injected failure").onSession(event);
        assertTrue(event.cancelled);
    }
}
