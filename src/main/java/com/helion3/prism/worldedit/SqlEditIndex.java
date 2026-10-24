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

import com.helion3.prism.api.query.Query;
import java.sql.*;
import java.util.*;

/** The SQL database stores one operation and one row per affected section. */
public final class SqlEditIndex {
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final Connections connections;
    private final String operations;
    private final String sections;

    public SqlEditIndex(Connections connections, String prefix) throws SQLException {
        if (!prefix.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL table prefix");
        }
        this.connections = connections;
        operations = prefix + "we_operations";
        sections = prefix + "we_sections";
        try (Connection connection = connections.open();
                Statement statement = connection.createStatement()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String engine =
                    product.equalsIgnoreCase("MySQL") || product.equalsIgnoreCase("MariaDB")
                            ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                            : "";
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + operations
                            + " (id VARCHAR(36) PRIMARY KEY, world VARCHAR(36) NOT NULL, "
                            + "player VARCHAR(36), actor VARCHAR(255), source VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, "
                            + "started BIGINT NOT NULL, ended BIGINT NOT NULL, changes BIGINT NOT NULL)"
                            + engine);
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + sections
                            + " (operation_id VARCHAR(36) NOT NULL, world VARCHAR(36) NOT NULL, "
                            + "sx INT NOT NULL, sy INT NOT NULL, sz INT NOT NULL, first_time BIGINT NOT NULL, last_time BIGINT NOT NULL, changes BIGINT NOT NULL, "
                            + "PRIMARY KEY(operation_id,sx,sy,sz), FOREIGN KEY(operation_id) REFERENCES "
                            + operations
                            + "(id) ON DELETE CASCADE)"
                            + engine);
            createIndex(connection, operations, "player_time", "player,started");
            createIndex(connection, operations, "ended", "ended");
            createIndex(connection, sections, "location", "world,sx,sz,sy,last_time");
        }
    }

    private void createIndex(Connection connection, String table, String suffix, String columns)
            throws SQLException {
        String index = table + "_" + suffix;
        String metadataTable =
                connection.getMetaData().storesUpperCaseIdentifiers()
                        ? table.toUpperCase(Locale.ROOT)
                        : table;
        try (ResultSet indexes =
                connection
                        .getMetaData()
                        .getIndexInfo(connection.getCatalog(), null, metadataTable, false, false)) {
            while (indexes.next()) {
                if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX " + index + " ON " + table + "(" + columns + ")");
        }
    }

    public void publish(EditOperation operation) throws SQLException {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                if (contains(connection, operation.id)) {
                    connection.rollback();
                    return;
                }
                try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO " + operations + " VALUES(?,?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, operation.id);
                    insert.setString(2, operation.world);
                    insert.setString(3, operation.player);
                    insert.setString(4, operation.actor);
                    insert.setString(5, operation.source);
                    insert.setString(6, operation.status);
                    insert.setLong(7, operation.started);
                    insert.setLong(8, operation.ended);
                    insert.setLong(9, operation.count);
                    insert.executeUpdate();
                }
                Map<String, long[]> summary = new LinkedHashMap<>();
                for (EditOperation.Frame frame : operation.frames) {
                    long[] values =
                            summary.computeIfAbsent(
                                    frame.section(),
                                    ignored ->
                                            new long[] {
                                                frame.sx,
                                                frame.sy,
                                                frame.sz,
                                                frame.first,
                                                frame.last,
                                                0
                                            });
                    values[3] = Math.min(values[3], frame.first);
                    values[4] = Math.max(values[4], frame.last);
                    values[5] += frame.count;
                }
                try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO " + sections + " VALUES(?,?,?,?,?,?,?,?)")) {
                    int batch = 0;
                    for (long[] values : summary.values()) {
                        insert.setString(1, operation.id);
                        insert.setString(2, operation.world);
                        for (int i = 0; i < values.length; i++) {
                            insert.setLong(i + 3, values[i]);
                        }
                        insert.addBatch();
                        if (++batch % 256 == 0) {
                            insert.executeBatch();
                        }
                    }
                    if (batch % 256 != 0) {
                        insert.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private boolean contains(Connection connection, String id) throws SQLException {
        try (PreparedStatement exists =
                connection.prepareStatement("SELECT id FROM " + operations + " WHERE id=?")) {
            exists.setString(1, id);
            try (ResultSet result = exists.executeQuery()) {
                return result.next();
            }
        }
    }

    public Map<String, Set<String>> candidates(Query query) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        String where = DeltaConditions.sql(query.getConditions(), parameters);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Connection connection = connections.open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT o.id,s.sx,s.sy,s.sz FROM "
                                        + operations
                                        + " o JOIN "
                                        + sections
                                        + " s ON o.id=s.operation_id WHERE o.status<>'deleting' AND "
                                        + where)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getString(1), ignored -> new HashSet<>())
                            .add(
                                    BlockDelta.section(
                                            rows.getInt(2), rows.getInt(3), rows.getInt(4)));
                }
            }
        }
        return result;
    }

    public List<String> expired(long before) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = connections.open();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT id FROM "
                                        + operations
                                        + " WHERE ended<=? OR status='deleting'")) {
            query.setLong(1, before);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
            }
        }
        return result;
    }

    public void markDeleting(String id) throws SQLException {
        update("UPDATE " + operations + " SET status='deleting' WHERE id=?", id);
    }

    public void delete(String id) throws SQLException {
        update("DELETE FROM " + operations + " WHERE id=?", id);
    }

    private void update(String sql, String id) throws SQLException {
        try (Connection connection = connections.open();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }
}
