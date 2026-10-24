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

import com.helion3.prism.api.flags.Flag;
import com.helion3.prism.api.query.*;
import com.helion3.prism.api.records.*;
import com.helion3.prism.api.storage.*;
import com.helion3.prism.util.DataQueries;
import com.helion3.prism.util.DataUtil;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;

/** Merge and validate before limiting. Only selected changes become Sponge result objects. */
public final class MergedRecords implements StorageAdapterRecords {
    private final StorageAdapterRecords ordinary;
    private final EditJournal journal;

    public MergedRecords(StorageAdapterRecords ordinary, EditJournal journal) {
        this.ordinary = ordinary;
        this.journal = journal;
    }

    @Override
    public StorageWriteResult write(List<DataContainer> containers) throws Exception {
        return ordinary.write(containers);
    }

    @Override
    public StorageDeleteResult delete(Query query) throws Exception {
        return ordinary.delete(query);
    }

    @Override
    public CompletableFuture<List<Result>> query(QuerySession session, boolean translate)
            throws Exception {
        int limit = session.getQuery().getLimit();
        boolean grouped = !session.hasFlag(Flag.NO_GROUP);
        Comparator<Candidate> comparator =
                Comparator.comparingLong((Candidate c) -> c.time)
                        .thenComparingLong(c -> c.sequence)
                        .thenComparingLong(c -> c.storageId);
        if (session.getSortBy() == Sort.NEWEST_FIRST) {
            comparator = comparator.reversed();
        }
        final Comparator<Candidate> order = comparator;
        PriorityQueue<Candidate> top =
                new PriorityQueue<>(Math.min(limit, 1024) + 1, order.reversed());
        Map<List<String>, Group> groups = new HashMap<>();
        java.util.function.Consumer<Candidate> collect =
                candidate -> {
                    if (grouped) {
                        Group group =
                                groups.computeIfAbsent(
                                        candidate.key(), ignored -> new Group(candidate));
                        group.count++;
                        if (order.compare(candidate, group.best) < 0) {
                            group.best = candidate;
                        }
                    } else {
                        top.add(candidate);
                        if (top.size() > limit) {
                            top.poll();
                            session.setTruncated(true);
                        }
                    }
                };
        QuerySession raw = new QuerySession(session.getCommandSource());
        raw.addFlag(Flag.NO_GROUP);
        raw.setSortBy(session.getSortBy());
        raw.newQuery().addConditions(session.getQuery().getConditions());
        int pageSize =
                grouped ? 1000 : Math.min(1000, limit == Integer.MAX_VALUE ? limit : limit + 1);
        raw.getQuery().setLimit(pageSize);
        int fetched = 0;
        while (true) {
            List<Result> page = ordinary.query(raw, false).get();
            for (Result result : page) {
                collect.accept(new Candidate(result));
            }
            fetched += page.size();
            // A sorted source's top N+1 is sufficient for a globally sorted top N merge.
            if (page.size() < pageSize || (!grouped && fetched > limit)) {
                break;
            }
            raw.getQuery().setOffset(fetched);
        }
        // Validate every selected frame, including matches outside the output limit.
        journal.query(
                session.getQuery(),
                (operation, delta) -> collect.accept(new Candidate(operation, delta)));
        List<Candidate> selected = new ArrayList<>();
        if (grouped) {
            for (Group group : groups.values()) {
                selected.add(group.best);
            }
        } else {
            selected.addAll(top);
        }
        selected.sort(order);
        if (selected.size() > limit) {
            session.setTruncated(true);
            selected = new ArrayList<>(selected.subList(0, limit));
        }
        List<Result> results = new ArrayList<>(selected.size());
        for (Candidate candidate : selected) {
            if (grouped) {
                Group group = groups.get(candidate.key());
                ResultAggregate result = new ResultAggregate();
                result.data =
                        DataContainer.createNew()
                                .set(DataQueries.EventName, candidate.event())
                                .set(DataQueries.Target, candidate.target())
                                .set(DataQueries.Cause, candidate.cause())
                                .set(DataQueries.Created, candidate.time / 1000)
                                .set(DataQueries.Captured, candidate.time)
                                .set(DataQueries.Sequence, candidate.sequence)
                                .set(DataQueries.Count, group.count);
                if (!candidate.player().isEmpty()) {
                    result.data.set(DataQueries.Player, candidate.player());
                }
                results.add(result);
            } else {
                results.add(
                        candidate.result == null
                                ? new WorldEditResult(candidate.operation, candidate.delta)
                                : candidate.result);
            }
        }
        if (translate) {
            List<UUID> uuids = new ArrayList<>();
            for (Result result : results) {
                result.data
                        .getString(DataQueries.Player)
                        .ifPresent(player -> uuids.add(UUID.fromString(player)));
            }
            if (!uuids.isEmpty()) {
                return DataUtil.translateUuidsToNames(results, uuids);
            }
        }
        return CompletableFuture.completedFuture(results);
    }

    private static final class Group {
        Candidate best;
        long count;

        Group(Candidate best) {
            this.best = best;
        }
    }

    private static final class Candidate {
        final Result result;
        final EditOperation operation;
        final BlockDelta delta;
        final long time;
        final long sequence;
        final long storageId;

        Candidate(Result result) {
            this.result = result;
            operation = null;
            delta = null;
            time =
                    result.data
                            .getLong(DataQueries.Captured)
                            .orElseGet(
                                    () ->
                                            result.data.getLong(DataQueries.Created).orElse(0L)
                                                    * 1000);
            sequence = result.data.getLong(DataQueries.Sequence).orElse(0L);
            storageId = result.data.getLong(DataQuery.of("StorageId")).orElse(0L);
        }

        Candidate(EditOperation operation, BlockDelta delta) {
            result = null;
            this.operation = operation;
            this.delta = delta;
            time = delta.time;
            sequence = delta.order;
            storageId = 0;
        }

        String event() {
            return result == null ? "worldedit" : result.getEventId();
        }

        String target() {
            return result == null
                    ? delta.target()
                    : result.data.getString(DataQueries.Target).orElse("");
        }

        String player() {
            return result == null
                    ? (operation.player == null ? "" : operation.player)
                    : result.data.getString(DataQueries.Player).orElse("");
        }

        String cause() {
            return result == null
                    ? (operation.player == null ? operation.actor : operation.player)
                    : result.getSourceName();
        }

        List<String> key() {
            String day =
                    Instant.ofEpochMilli(time)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .toString();
            return Arrays.asList(event(), target(), player(), cause(), day);
        }
    }
}
