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

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.TextActions;
import org.spongepowered.api.text.format.TextColors;

/** Compact pagination for a 237px chat box, independent of Sponge's 320px padding. */
public final class ChatPagination {
    private static final int RESULTS_PER_PAGE = 13;
    // Eight '=' characters per side leave room for the controls and page counter.
    private static final String SEPARATOR = "========";

    private ChatPagination() {}

    public static void send(CommandSource source, List<Text> messages) {
        if (!(source instanceof Player)) {
            // Consoles cannot click callback links; keep every result accessible there.
            messages.forEach(source::sendMessage);
            return;
        }
        if (!messages.isEmpty()) {
            sendPage(source, new ArrayList<>(messages), 0);
        }
    }

    private static void sendPage(CommandSource source, List<Text> messages, int page) {
        int pages = (messages.size() - 1) / RESULTS_PER_PAGE + 1;
        int start = page * RESULTS_PER_PAGE;
        for (int i = start; i < Math.min(start + RESULTS_PER_PAGE, messages.size()); i++) {
            source.sendMessage(messages.get(i));
        }

        Text.Builder previous = Text.builder("«").color(TextColors.DARK_GRAY);
        if (page > 0) {
            previous.color(TextColors.GOLD)
                    .onHover(TextActions.showText(Text.of(Translation.from("page.previous"))))
                    .onClick(
                            TextActions.executeCallback(
                                    clicker -> {
                                        if (clicker.equals(source)) {
                                            sendPage(source, messages, page - 1);
                                        }
                                    }));
        }
        Text.Builder next = Text.builder("»").color(TextColors.DARK_GRAY);
        if (page + 1 < pages) {
            next.color(TextColors.GOLD)
                    .onHover(TextActions.showText(Text.of(Translation.from("page.next"))))
                    .onClick(
                            TextActions.executeCallback(
                                    clicker -> {
                                        if (clicker.equals(source)) {
                                            sendPage(source, messages, page + 1);
                                        }
                                    }));
        }
        source.sendMessage(
                Text.of(
                        TextColors.DARK_GRAY,
                        SEPARATOR,
                        " ",
                        previous.build(),
                        TextColors.GRAY,
                        " ",
                        page + 1,
                        "/",
                        pages,
                        " ",
                        next.build(),
                        TextColors.DARK_GRAY,
                        " ",
                        SEPARATOR));
    }
}
