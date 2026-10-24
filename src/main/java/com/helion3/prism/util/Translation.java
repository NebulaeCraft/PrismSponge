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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

public final class Translation {

    private static final Properties english = load("en_US");
    private static volatile Properties lang = load("zh_CN");
    private static volatile Locale locale = Locale.SIMPLIFIED_CHINESE;

    private Translation() {}

    /** Select the server's chat language. Unsupported languages fall back to English. */
    public static void setLanguage(String language) {
        boolean chinese = "zh_CN".equalsIgnoreCase(language) || "zh-CN".equalsIgnoreCase(language);
        lang = load(chinese ? "zh_CN" : "en_US");
        locale = chinese ? Locale.SIMPLIFIED_CHINESE : Locale.US;
    }

    public static Locale locale() {
        return locale;
    }

    private static Properties load(String language) {
        Properties messages = new Properties();
        try (InputStream stream =
                Translation.class.getResourceAsStream(
                        "/assets/prism/lang/" + language + ".properties")) {
            if (stream == null) {
                throw new IllegalStateException("Missing Prism language resource: " + language);
            }
            messages.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load Prism language resource: " + language, ex);
        }
        return messages;
    }

    /** Get a message, retaining the key if an extension has no translation. */
    public static String from(String key) {
        return lang.getProperty(key, english.getProperty(key, key));
    }

    public static String from(String key, Object... arguments) {
        return String.format(Locale.ROOT, from(key), arguments);
    }

    public static String eventVerb(String id, String fallback) {
        String key = "event." + id + ".verb";
        return lang.getProperty(key, english.getProperty(key, fallback));
    }

    public static String eventName(String id, String fallback) {
        String key = "event." + id + ".name";
        return lang.getProperty(key, english.getProperty(key, fallback));
    }

    /** Parameter errors are already localized; implementation exceptions belong in the log. */
    public static String error(Throwable error) {
        if (error instanceof com.helion3.prism.api.parameters.ParameterException
                && error.getMessage() != null) {
            return error.getMessage();
        }
        return from("error.generic");
    }

    public static String status(String value) {
        String key = "we.status." + value;
        return lang.getProperty(key, english.getProperty(key, value));
    }
}
