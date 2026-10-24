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
package com.helion3.prism.commands;

import com.google.common.collect.Lists;
import com.helion3.prism.Prism;
import com.helion3.prism.api.records.ActionableResult;
import com.helion3.prism.util.Format;
import com.helion3.prism.util.Template;
import com.helion3.prism.util.Translation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.entity.Entity;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.world.BlockChangeFlags;

public class UndoCommand {
    private UndoCommand() {}

    public static CommandSpec getCommand() {
        return CommandSpec.builder()
                .permission("prism.undo")
                .executor(
                        (source, args) -> {
                            if (!(source instanceof Player)) {
                                throw new CommandException(
                                        Format.error(Translation.from("error.player-only")));
                            }

                            List<ActionableResult> results =
                                    Prism.getInstance()
                                            .getLastActionResults()
                                            .get(((Player) source).getUniqueId());
                            if (results == null || results.isEmpty()) {
                                throw new CommandException(
                                        Format.error(Translation.from("undo.empty")));
                            }
                            // Reverse the order of the list to undo last action.
                            final List<ActionableResult> reversed = Lists.reverse(results);
                            Prism.getInstance()
                                    .getLastActionResults()
                                    .remove(((Player) source).getUniqueId());

                            int[] counts = new int[2];

                            com.helion3.prism.worldedit.TickBatch.apply(
                                    reversed.size(),
                                    index -> {
                                        ActionableResult result = reversed.get(index);
                                        if (result.getTransaction().isPresent()) {
                                            Object rawOriginal =
                                                    result.getTransaction().get().getOriginal();
                                            Object rawFinal =
                                                    result.getTransaction().get().getFinal();

                                            if (rawOriginal instanceof BlockSnapshot) {
                                                if (((BlockSnapshot) rawOriginal)
                                                        .restore(true, BlockChangeFlags.NONE)) {
                                                    counts[0]++;
                                                } else {
                                                    counts[1]++;
                                                }
                                            }

                                            if (rawFinal instanceof Entity) {
                                                Entity entity = (Entity) rawFinal;
                                                if (!entity.isRemoved()) {
                                                    entity.remove();
                                                    counts[0]++;
                                                } else {
                                                    counts[1]++;
                                                }
                                            }
                                        } else {
                                            counts[1]++;
                                        }
                                    },
                                    () -> {
                                        Map<String, String> tokens = new HashMap<>();
                                        tokens.put("appliedCount", "" + counts[0]);
                                        tokens.put("skippedCount", "" + counts[1]);

                                        final String messageTemplate;
                                        if (counts[1] > 0) {
                                            messageTemplate =
                                                    Translation.from(
                                                            "rollback.success.withskipped");
                                        } else {
                                            messageTemplate = Translation.from("rollback.success");
                                        }

                                        source.sendMessage(
                                                Format.heading(
                                                        Text.of(
                                                                Template.parseTemplate(
                                                                        messageTemplate, tokens)),
                                                        " ",
                                                        Format.bonus(
                                                                Translation.from(
                                                                        "rollback.success.bonus"))));
                                    });

                            return CommandResult.success();
                        })
                .build();
    }
}
