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

import com.google.common.collect.Range;
import com.helion3.prism.api.query.*;
import com.helion3.prism.util.DataQueries;
import java.util.*;
import java.util.regex.Pattern;

/** Exact predicates plus conservative, parameterized SQL section selection. */
public final class DeltaConditions {
    private DeltaConditions() {}

    public static boolean matches(
            List<Condition> conditions, EditOperation operation, BlockDelta delta) {
        for (Condition condition : conditions) {
            if (!matches(condition, operation, delta)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Condition condition, EditOperation operation, BlockDelta delta) {
        if (condition instanceof ConditionGroup) {
            ConditionGroup group = (ConditionGroup) condition;
            boolean and = group.getOperator() == ConditionGroup.Operator.AND;
            for (Condition child : group.getConditions()) {
                boolean match = matches(child, operation, delta);
                if (and != match) {
                    return !and;
                }
            }
            return and;
        }
        FieldCondition field = (FieldCondition) condition;
        String name = field.getFieldName().toString();
        Object actual;
        if (name.equals(DataQueries.EventName.toString())) {
            actual = "worldedit";
        } else if (name.equals(DataQueries.Player.toString())) {
            actual = operation.player;
        } else if (name.equals(DataQueries.Cause.toString())) {
            actual = operation.actor;
        } else if (name.equals(DataQueries.Target.toString())) {
            actual = delta.target();
        } else if (name.equals(DataQueries.Created.toString())) {
            actual = delta.time;
        } else if (name.equals(DataQueries.Location.then(DataQueries.WorldUuid).toString())) {
            actual = operation.world;
        } else if (name.equals(DataQueries.Location.then(DataQueries.X).toString())) {
            actual = delta.x;
        } else if (name.equals(DataQueries.Location.then(DataQueries.Y).toString())) {
            actual = delta.y;
        } else if (name.equals(DataQueries.Location.then(DataQueries.Z).toString())) {
            actual = delta.z;
        } else {
            throw new IllegalArgumentException("Unsupported WE query field: " + name);
        }
        return compare(actual, field.getMatchRule(), field.getValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean compare(Object actual, MatchRule rule, Object expected) {
        if (actual == null) {
            return rule == MatchRule.EXCLUDES;
        }
        if (expected instanceof Date) {
            expected = ((Date) expected).getTime();
        }
        if (rule == MatchRule.BETWEEN) {
            return ((Range) expected).contains((Comparable) actual);
        }
        if (rule == MatchRule.EQUALS || rule == MatchRule.INCLUDES || rule == MatchRule.EXCLUDES) {
            boolean equal =
                    expected instanceof Pattern
                            ? ((Pattern) expected).matcher(actual.toString()).matches()
                            : actual instanceof Number && expected instanceof Number
                                    ? ((Number) actual).longValue()
                                            == ((Number) expected).longValue()
                                    : actual.toString().equals(expected.toString());
            return rule == MatchRule.EXCLUDES ? !equal : equal;
        }
        long value = ((Number) actual).longValue();
        long bound = ((Number) expected).longValue();
        return rule == MatchRule.GREATER_THAN_EQUAL ? value >= bound : value <= bound;
    }

    public static String sql(List<Condition> conditions, List<Object> parameters) {
        List<String> parts = new ArrayList<>();
        for (Condition condition : conditions) {
            parts.add(sql(condition, parameters));
        }
        return parts.isEmpty() ? "1=1" : "(" + String.join(" AND ", parts) + ")";
    }

    private static String sql(Condition condition, List<Object> parameters) {
        if (condition instanceof ConditionGroup) {
            ConditionGroup group = (ConditionGroup) condition;
            List<String> parts = new ArrayList<>();
            for (Condition child : group.getConditions()) {
                parts.add(sql(child, parameters));
            }
            return parts.isEmpty()
                    ? (group.getOperator() == ConditionGroup.Operator.AND ? "1=1" : "1=0")
                    : "(" + String.join(" " + group.getOperator().name() + " ", parts) + ")";
        }
        FieldCondition field = (FieldCondition) condition;
        String name = field.getFieldName().toString();
        Object value = field.getValue();
        String column;
        boolean section = name.startsWith("Location.") && !name.endsWith("WorldUuid");
        if (name.equals("Location.X")) {
            column = "s.sx";
        } else if (name.equals("Location.Y")) {
            column = "s.sy";
        } else if (name.equals("Location.Z")) {
            column = "s.sz";
        } else if (name.equals("Location.WorldUuid")) {
            column = "o.world";
        } else if (name.equals("Player")) {
            column = "o.player";
        } else if (name.equals("Cause")) {
            column = "o.actor";
        } else if (name.equals("EventName")) {
            column = "'worldedit'";
        } else if (name.equals("Created")) {
            long time =
                    value instanceof Date ? ((Date) value).getTime() : ((Number) value).longValue();
            if (field.getMatchRule() == MatchRule.GREATER_THAN_EQUAL) {
                parameters.add(time);
                return "s.last_time >= ?";
            }
            if (field.getMatchRule() == MatchRule.LESS_THAN_EQUAL) {
                parameters.add(time);
                return "s.first_time <= ?";
            }
            return "1=1";
        } else {
            return "1=1";
        }
        if (value instanceof Pattern
                || field.getMatchRule() == MatchRule.EXCLUDES
                || field.getMatchRule() == MatchRule.INCLUDES) {
            return "1=1";
        }
        if (value instanceof Range) {
            Range<?> range = (Range<?>) value;
            parameters.add(((Number) range.lowerEndpoint()).intValue() >> 4);
            parameters.add(((Number) range.upperEndpoint()).intValue() >> 4);
            return column + " >= ? AND " + column + " <= ?";
        }
        if (section) {
            value = ((Number) value).intValue() >> 4;
        }
        parameters.add(value);
        String operator =
                field.getMatchRule() == MatchRule.GREATER_THAN_EQUAL
                        ? ">="
                        : field.getMatchRule() == MatchRule.LESS_THAN_EQUAL ? "<=" : "=";
        return column + " " + operator + " ?";
    }
}
