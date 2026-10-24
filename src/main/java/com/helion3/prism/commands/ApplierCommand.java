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

import com.helion3.prism.util.Translation;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.query.Sort;
import com.helion3.prism.api.records.Actionable;
import com.helion3.prism.api.records.ActionableResult;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.util.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;

public class ApplierCommand {

    private ApplierCommand() {}

    public static CommandSpec getCommand(Sort sort) {
        return CommandSpec.builder()
                .permission("prism.rollback")
                .arguments(GenericArguments.remainingJoinedStrings(Text.of(Translation.from("argument.parameters"))))
                .executor(
                        (source, args) -> {
                            // Create a new query session
                            final QuerySession session = new QuerySession(source);
                            session.addFlag(Flag.NO_GROUP);
                            try {
                                source.sendMessage(Format.heading(Translation.from("query.running")));

                                CompletableFuture<Void> future =
                                        session.newQueryFromArguments(
                                                args.<String>getOne(Translation.from("argument.parameters")).get());
                                // Ignore user order flag, if used, for proper rollback/restore
                                // order to be used.
                                session.setSortBy(sort);
                                future.thenAccept((v) -> runApplier(session, sort));
                            } catch (Exception e) {
                                source.sendMessage(Format.error(Text.of(Translation.error(e))));
                                e.printStackTrace();
                            }

                            return CommandResult.success();
                        })
                .build();
    }

    /**
     * Use a designated QuerySession
     *
     * @param session
     * @param sort
     */
    public static void runApplier(QuerySession session, Sort sort) {
        session.getQuery()
                .setLimit(
                        Prism.getInstance().getConfig().getLimitCategory().getMaximumActionable());
        Task.builder()
                .async()
                .execute(
                        () -> {
                            try {
                                CompletableFuture<List<Result>> futureResults =
                                        Prism.getInstance()
                                                .getStorageAdapter()
                                                .records()
                                                .query(session, false);
                                futureResults.whenComplete(
                                        (results, error) ->
                                                finishQuery(session, sort, results, error));
                            } catch (Exception e) {
                                finishQuery(session, sort, null, e);
                            }
                        })
                .submit(Prism.getInstance().getPluginContainer());
    }

    /** Return to the server thread before accessing players or changing the world. */
    private static void finishQuery(
            QuerySession session, Sort sort, List<Result> results, Throwable error) {
        Task.builder()
                .execute(
                        () -> {
                            if (error != null) {
                                session.getCommandSource()
                                        .sendMessage(
                                                Format.error(
                                                        Translation.from("error.generic")));
                                Prism.getInstance()
                                        .getLogger()
                                        .error(
                                                "Failed to query records for rollback/restore",
                                                error);
                                return;
                            }
                            applyResults(session, sort, results);
                        })
                .submit(Prism.getInstance().getPluginContainer());
    }

    private static void applyResults(QuerySession session, Sort sort, List<Result> results) {
        CommandSource source = session.getCommandSource();
        if (results.isEmpty()) {
            source.sendMessage(Format.error(Translation.from("query.empty")));
            return;
        }

        List<ActionableResult> actionResults = new ArrayList<>();
        if (session.isTruncated()) {
            source.sendMessage(
                    Format.error(
                            Translation.from("query.actionable-limit")));
        }
        com.helion3.prism.worldedit.TickBatch.apply(
                results.size(),
                index -> {
                    Result result = results.get(index);
                    try {
                        if (result instanceof Actionable) {
                            Actionable actionable = (Actionable) result;
                            if (sort.equals(Sort.NEWEST_FIRST)) {
                                actionResults.add(actionable.rollback());
                            } else {
                                actionResults.add(actionable.restore());
                            }
                        }
                    } catch (Exception e) {
                        source.sendMessage(Format.error(Text.of(Translation.error(e))));
                        e.printStackTrace();
                    }
                },
                () -> finishApplication(session, actionResults));
    }

    private static void finishApplication(
            QuerySession session, List<ActionableResult> actionResults) {
        CommandSource source = session.getCommandSource();
        if (source instanceof Player) {
            int changes = 0;

            if (session.hasFlag(Flag.CLEAN)) {
                changes +=
                        WorldUtil.removeIllegalBlocks(
                                ((Player) source).getLocation(), session.getRadius());
                changes +=
                        WorldUtil.removeItemEntitiesAroundLocation(
                                ((Player) source).getLocation(), session.getRadius());
            }

            if (session.hasFlag(Flag.DRAIN)) {
                changes +=
                        WorldUtil.removeLiquidsAroundLocation(
                                ((Player) source).getLocation(), session.getRadius());
            }

            if (changes > 0) {
                source.sendMessage(Format.bonus(Translation.from("clean.running")));
            }
        }
        sendResults(source, actionResults);
    }

    private static void sendResults(CommandSource source, List<ActionableResult> actionResults) {
        int appliedCount = 0;
        int skippedCount = 0;
        for (ActionableResult result : actionResults) {
            if (result.applied()) {
                appliedCount++;
            } else {
                skippedCount++;
            }
        }

        Map<String, String> tokens = new HashMap<>();
        tokens.put("appliedCount", "" + appliedCount);
        tokens.put("skippedCount", "" + skippedCount);

        final String messageTemplate;
        if (skippedCount > 0) {
            messageTemplate = Translation.from("rollback.success.withskipped");
        } else {
            messageTemplate = Translation.from("rollback.success");
        }

        source.sendMessage(
                Format.heading(
                        Text.of(Template.parseTemplate(messageTemplate, tokens)),
                        " ",
                        Format.bonus(Translation.from("rollback.success.bonus"))));

        if (source instanceof Player) {
            Prism.getInstance()
                    .getLastActionResults()
                    .put(((Player) source).getUniqueId(), actionResults);
        }
    }
}
