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

package com.helion3.prism.util;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.QuerySession;
import com.helion3.prism.api.records.Result;
import com.helion3.prism.api.records.ResultAggregate;
import com.helion3.prism.api.records.ResultComplete;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.world.World;

public class LookupCallback extends AsyncCallback {

    private final QuerySession querySession;

    public LookupCallback(QuerySession querySession) {
        this.querySession = querySession;
    }

    @Override
    public void success(List<Result> results) {
        List<Text> messages = new ArrayList<>();
        for (Result result : results) {
            messages.add(buildResult(result));
        }

        if (messages.isEmpty()) {
            this.empty();
            return;
        }

        if (querySession.isTruncated()) {
            querySession
                    .getCommandSource()
                    .sendMessage(Format.error(Translation.from("query.limit")));
        }
        ChatPagination.send(querySession.getCommandSource(), messages);
    }

    @Override
    public void empty() {
        this.querySession
                .getCommandSource()
                .sendMessage(Format.error(Translation.from("query.nothing")));
    }

    @Override
    public void error(Exception ex) {
        this.querySession
                .getCommandSource()
                .sendMessage(Format.error(Translation.from("error.generic")));
        Prism.getInstance()
                .getLogger()
                .error("Exception thrown by {}", getClass().getSimpleName(), ex);
    }

    private Text buildResult(Result result) {
        Text.Builder resultMessage = Text.builder();
        Text sourceName = Format.source(result.getSourceName(), result.data.contains(DataQueries.Player));
        resultMessage.append(Text.of(TextColors.DARK_AQUA, sourceName, " "));
        resultMessage.append(Text.of(TextColors.WHITE,
                Translation.eventVerb(result.getEventId(), result.getEventVerb()), " "));

        Text.Builder hoverMessage = Text.builder();
        hoverMessage.append(Format.prefix(), Text.NEW_LINE);
        hoverMessage.append(
                Text.of(
                        TextColors.DARK_GRAY,
                        Translation.from("hover.source"),
                        TextColors.WHITE,
                        sourceName,
                        Text.NEW_LINE));
        hoverMessage.append(
                Text.of(
                        TextColors.DARK_GRAY,
                        Translation.from("hover.event"),
                        TextColors.WHITE,
                        Translation.eventName(result.getEventId(), result.getEventName()),
                        Text.NEW_LINE));

        String quantity = result.data.getString(DataQueries.Quantity).orElse(null);
        if (StringUtils.isNotBlank(quantity)) {
            resultMessage.append(Text.of(TextColors.DARK_AQUA, quantity, " "));
            hoverMessage.append(
                    Text.of(
                            TextColors.DARK_GRAY,
                            Translation.from("hover.quantity"),
                            TextColors.WHITE,
                            quantity,
                            Text.NEW_LINE));
        }

        String target = result.data.getString(DataQueries.Target).orElse(Translation.from("unknown"));
        if (StringUtils.isNotBlank(target)) {
            resultMessage.append(Text.of(TextColors.DARK_AQUA, Format.item(target, false), " "));
            hoverMessage.append(
                    Text.of(
                            TextColors.DARK_GRAY,
                            Translation.from("hover.target"),
                            TextColors.WHITE,
                            Format.item(target, false), " (", target, ")",
                            Text.NEW_LINE));
        }

        String id = result.data.getString(DataQueries.Id).orElse(null);
        for (String field :
                new String[] {"WEOperation", "WESource", "WEBefore", "WEAfter", "WEStatus"}) {
            result.data
                    .getString(org.spongepowered.api.data.DataQuery.of(field))
                    .ifPresent(
                            value ->
                                    hoverMessage.append(
                                            Text.of(
                                                    TextColors.DARK_GRAY,
                                                    Translation.from("hover." + field),
                                                    TextColors.WHITE,
                                                    field.equals("WEStatus") ? Translation.status(value) : value,
                                                    Text.NEW_LINE)));
        }
        if (StringUtils.isNotBlank(id)) {
            hoverMessage.append(
                    Text.of(TextColors.DARK_GRAY, Translation.from("hover.id"), TextColors.WHITE, id, Text.NEW_LINE));
        }

        String container = result.data.getString(DataQueries.Container).orElse(null);
        if (StringUtils.isNotBlank(container)) {
            hoverMessage.append(
                    Text.of(
                            TextColors.DARK_GRAY,
                            Translation.from("hover.container"),
                            TextColors.WHITE,
                            Format.item(container, false), " (", container, ")",
                            Text.NEW_LINE));
        }

        if (result instanceof ResultAggregate) {
            long count = result.data.getLong(DataQueries.Count).orElse(0L);
            if (count > 0) {
                resultMessage.append(Text.of(TextColors.GREEN, "x", count, " "));
                hoverMessage.append(
                        Text.of(TextColors.DARK_GRAY, Translation.from("hover.count"), TextColors.WHITE, count));
            }
        }

        if (result instanceof ResultComplete) {
            ResultComplete resultComplete = (ResultComplete) result;

            resultMessage.append(Text.of(TextColors.WHITE, resultComplete.getRelativeTime()));
            hoverMessage.append(
                    Text.of(
                            TextColors.DARK_GRAY,
                            Translation.from("hover.time"),
                            TextColors.WHITE,
                            resultComplete.getTime(),
                            Text.NEW_LINE));

            DataView location =
                    (DataView) resultComplete.data.get(DataQueries.Location).orElse(null);
            if (location != null) {
                int x = location.getInt(DataQueries.X).orElse(0);
                int y = location.getInt(DataQueries.Y).orElse(0);
                int z = location.getInt(DataQueries.Z).orElse(0);
                World world =
                        location.get(DataQueries.WorldUuid)
                                .flatMap(TypeUtil::uuidFromObject)
                                .flatMap(Sponge.getServer()::getWorld)
                                .orElse(null);

                hoverMessage.append(
                        Text.of(
                                TextColors.DARK_GRAY,
                                Translation.from("hover.location"),
                                TextColors.WHITE,
                                Format.location(x, y, z, world, false)));
                if (this.querySession.hasFlag(Flag.EXTENDED)) {
                    resultMessage.append(
                            Text.of(
                                    Text.NEW_LINE,
                                    TextColors.GRAY,
                                    " - ",
                                    Format.location(x, y, z, world, true)));
                    hoverMessage.append(
                            Text.of(
                                    Text.NEW_LINE,
                                    Text.NEW_LINE,
                                    TextColors.GRAY,
                                    Translation.from("hover.teleport")));
                }
            }
        }

        resultMessage.onHover(TextActions.showText(hoverMessage.build()));
        return resultMessage.build();
    }
}
