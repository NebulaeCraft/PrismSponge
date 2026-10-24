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
package com.helion3.prism.api.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helion3.prism.Prism;
import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.configuration.Config;
import com.helion3.prism.configuration.Configuration;
import com.helion3.prism.storage.h2.H2SQLQuery;
import com.helion3.prism.storage.mysql.MySQLQuery;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.api.command.CommandSource;

public class SQLQueryLimitTest {

    private static Prism previousInstance;
    private Connection connection;

    @BeforeAll
    public static void initializePrism() throws Exception {
        previousInstance = Prism.getInstance();
        Prism prism = new Prism();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getConfig()).thenReturn(new Config());
        Field configurationField = Prism.class.getDeclaredField("configuration");
        configurationField.setAccessible(true);
        configurationField.set(prism, configuration);
        setPrismInstance(prism);
    }

    @AfterAll
    public static void restorePrism() throws Exception {
        setPrismInstance(previousInstance);
    }

    private static void setPrismInstance(Prism prism) throws Exception {
        Field instance = Prism.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, prism);
    }

    @BeforeEach
    public void createDatabase() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:");
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE prism_records (id INT PRIMARY KEY, Created BIGINT, "
                            + "EventName VARCHAR(16), Target VARCHAR(255), Player UUID, Cause VARCHAR(64))");
            statement.execute("CREATE TABLE prism_extra (record_id INT, json VARCHAR(255))");
            for (int id = 1; id <= 6; id++) {
                String target = id <= 4 ? "stone" : "dirt";
                statement.execute(
                        "INSERT INTO prism_records VALUES ("
                                + id
                                + ", "
                                + id
                                + ", 'block-break', '"
                                + target
                                + "', NULL, 'environment')");
                statement.execute(
                        "INSERT INTO prism_extra VALUES (" + id + ", 'detail-" + id + "')");
            }
            com.helion3.prism.worldedit.CaptureSchema.migrate(connection, "prism_records");
        }
    }

    @AfterEach
    public void closeDatabase() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    private QuerySession session(int limit, boolean grouped, Sort sort) {
        QuerySession session = new QuerySession(mock(CommandSource.class));
        session.newQuery().setLimit(limit);
        session.setSortBy(sort);
        if (!grouped) {
            session.addFlag(Flag.NO_GROUP);
        }
        return session;
    }

    @Test
    public void limitsNewestH2DetailsAfterSorting() throws Exception {
        assertH2Details(Sort.NEWEST_FIRST, 6, 5);
    }

    @Test
    public void limitsOldestH2DetailsAfterSorting() throws Exception {
        assertH2Details(Sort.OLDEST_FIRST, 1, 2);
    }

    private void assertH2Details(Sort sort, int first, int second) throws Exception {
        String sql = H2SQLQuery.from(session(2, false, sort)).toString();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            assertEquals(first, rows.getLong("Created"));
            assertEquals("detail-" + first, rows.getString("json"));
            assertTrue(rows.next());
            assertEquals(second, rows.getLong("Created"));
            assertFalse(
                    rows.next(), "The database must return only the configured number of records");
        }
    }

    @Test
    public void limitsH2GroupsWithoutTruncatingTheirCounts() throws Exception {
        String sql = H2SQLQuery.from(session(1, true, Sort.NEWEST_FIRST)).toString();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            int expectedCount = "stone".equals(rows.getString("target")) ? 4 : 2;
            assertEquals(expectedCount, rows.getInt("total"));
            assertFalse(rows.next());
        }
    }

    @Test
    public void appliesMySQLLimitToDetailsAndGroupsInBothSortOrders() {
        for (Sort sort : Sort.values()) {
            for (boolean grouped : new boolean[] {false, true}) {
                String sql = MySQLQuery.from(session(7, grouped, sort)).toString();
                assertTrue(
                        sql.endsWith(
                                grouped
                                        ? "ORDER BY created " + sort.getString() + " LIMIT 7"
                                        : "r.id " + sort.getString() + " LIMIT 7"),
                        sql);
                assertEquals(grouped, sql.contains("GROUP BY"));
                assertEquals(!grouped, sql.contains("LEFT JOIN"));
            }
        }
    }

    @Test
    public void preservesTheDefaultSessionLimitForBothSQLBackends() {
        QuerySession session = new QuerySession(mock(CommandSource.class));
        session.newQuery();
        assertTrue(H2SQLQuery.from(session).toString().endsWith("LIMIT 1000"));
        assertTrue(MySQLQuery.from(session).toString().endsWith("LIMIT 1000"));
    }

    @Test
    public void rejectsNonPositiveLimitsForBothSQLBackends() {
        for (int limit : new int[] {0, -1}) {
            QuerySession session = session(limit, false, Sort.NEWEST_FIRST);
            assertThrows(IllegalArgumentException.class, () -> H2SQLQuery.from(session));
            assertThrows(IllegalArgumentException.class, () -> MySQLQuery.from(session));
        }
    }
}
