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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.text.LiteralText;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.action.ClickAction;

public class ChatPaginationTest {
    @Test
    public void pagesHaveCompactFootersAndWorkingControls() {
        Player source = mock(Player.class);
        List<Text> sent = capture(source);
        List<Text> records = records(27);
        ChatPagination.send(source, records);
        assertEquals(14, sent.size());
        assertEquals("record-0", plain(sent.get(0)));
        Text footer = sent.get(13);
        assertEquals("======== « 1/3 » ========", plain(footer));
        assertTrue(plain(footer).length() * 8 <= 237);
        assertEquals(1, callbacks(footer).size());
        ClickAction.ExecuteCallback next = callbacks(footer).get(0);
        next.getResult().accept(mock(Player.class));
        assertEquals(14, sent.size());
        next.getResult().accept(source);
        assertEquals("record-13", plain(sent.get(14)));
        Text secondFooter = sent.get(27);
        assertEquals("======== « 2/3 » ========", plain(secondFooter));
        assertEquals(2, callbacks(secondFooter).size());
        callbacks(secondFooter).get(1).getResult().accept(source);
        assertEquals("record-26", plain(sent.get(28)));
        Text lastFooter = sent.get(29);
        assertEquals("======== « 3/3 » ========", plain(lastFooter));
        assertEquals(1, callbacks(lastFooter).size());
        callbacks(lastFooter).get(0).getResult().accept(source);
        assertEquals("record-13", plain(sent.get(30)));
    }

    @Test
    public void emptySinglePageAndConsoleResultsStayAccessible() {
        Player player = mock(Player.class);
        List<Text> sent = capture(player);
        ChatPagination.send(player, records(0));
        assertTrue(sent.isEmpty());
        ChatPagination.send(player, records(1));
        assertEquals("======== « 1/1 » ========", plain(sent.get(1)));
        assertTrue(callbacks(sent.get(1)).isEmpty());
        CommandSource console = mock(CommandSource.class);
        List<Text> consoleOutput = capture(console);
        ChatPagination.send(console, records(27));
        assertEquals(27, consoleOutput.size());
        assertEquals("record-26", plain(consoleOutput.get(26)));
    }

    private static List<Text> capture(CommandSource source) {
        List<Text> sent = new ArrayList<>();
        doAnswer(
                        invocation -> {
                            sent.add(invocation.getArgument(0));
                            return null;
                        })
                .when(source)
                .sendMessage(any(Text.class));
        return sent;
    }

    private static List<Text> records(int count) {
        List<Text> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(Text.of("record-" + i));
        }
        return records;
    }

    private static String plain(Text text) {
        StringBuilder result = new StringBuilder(((LiteralText) text).getContent());
        for (Text child : text.getChildren()) {
            result.append(plain(child));
        }
        return result.toString();
    }

    private static List<ClickAction.ExecuteCallback> callbacks(Text text) {
        List<ClickAction.ExecuteCallback> callbacks = new ArrayList<>();
        text.getClickAction()
                .ifPresent(
                        action -> {
                            if (action instanceof ClickAction.ExecuteCallback) {
                                callbacks.add((ClickAction.ExecuteCallback) action);
                            }
                        });
        for (Text child : text.getChildren()) {
            callbacks.addAll(callbacks(child));
        }
        return callbacks;
    }
}
