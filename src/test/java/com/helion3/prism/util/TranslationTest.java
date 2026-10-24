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

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class TranslationTest {
    @AfterEach
    public void resetLanguage() {
        Translation.setLanguage("zh_CN");
    }

    @Test
    public void chineseMessagesAndTimeUseUtf8AndKeepTemplateTokens() {
        Translation.setLanguage("zh_CN");
        assertEquals("已开启检查模式。", Translation.from("inspect.enabled"));
        assertEquals("编辑了", Translation.eventVerb("worldedit", "edited"));
        assertEquals("坐标：-1972 68 -11320", Translation.from("inspect.position", -1972, 68, -11320));
        assertTrue(Translation.from("rollback.success").contains("{appliedCount}"));
        assertEquals("刚刚", DateUtil.getTimeSince(new Date()));
        assertEquals(
                "2天3小时4分钟前",
                DateUtil.getTimeSince(
                        new Date(
                                System.currentTimeMillis()
                                        - (2 * 86400L + 3 * 3600L + 4 * 60L) * 1000)));
        assertEquals("custom", Translation.eventVerb("extension", "custom"));
        assertEquals("future-status", Translation.status("future-status"));
    }

    @Test
    public void englishAndUnsupportedLanguagesHaveSafeFallbacks() {
        Translation.setLanguage("en_US");
        assertEquals("Inspection wand enabled.", Translation.from("inspect.enabled"));
        Translation.setLanguage("unsupported");
        assertEquals("edited", Translation.eventVerb("worldedit", "custom"));
        assertEquals("missing.key", Translation.from("missing.key"));
        Translation.setLanguage("zh-CN");
        assertEquals("已开启检查模式。", Translation.from("inspect.enabled"));
    }

    @Test
    public void languageFilesHaveMatchingKeysAndPlaceholders() throws Exception {
        Properties en = load("en_US");
        Properties zh = load("zh_CN");
        assertEquals(en.keySet(), zh.keySet());
        Pattern placeholders = Pattern.compile("\\{[^}]+\\}|%[ds]");
        for (String key : en.stringPropertyNames()) {
            assertFalse(zh.getProperty(key).isEmpty(), key);
            assertEquals(
                    tokens(placeholders, en.getProperty(key)),
                    tokens(placeholders, zh.getProperty(key)),
                    key);
        }
    }

    private static java.util.List<String> tokens(Pattern pattern, String value) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static Properties load(String locale) throws Exception {
        Properties properties = new Properties();
        try (InputStreamReader reader =
                new InputStreamReader(
                        TranslationTest.class.getResourceAsStream(
                                "/assets/prism/lang/" + locale + ".properties"),
                        StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
