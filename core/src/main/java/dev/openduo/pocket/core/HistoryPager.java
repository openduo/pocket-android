// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.core;

import dev.openduo.pocket.net.ChannelClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Local visibility and remote day paging. Store admission and all state changes run on state. */
public final class HistoryPager {
    public interface Store {
        int count();

        void admit(List<Message> rows, boolean today);

        String oldestDay();
    }

    public interface Events {
        void changed();

        void notice(String message);
    }

    private final Executor state, network;
    private final Store store;
    private final Events events;
    private volatile long generation;
    private int limit = 200;
    private String oldest = "", label = "查看更早的记录";
    private Request older;

    private final class Request {
        final long ticket = generation;
        final BooleanSupplier access;

        Request(BooleanSupplier access) {
            this.access = access;
        }

        boolean current() {
            return ticket == generation && access.getAsBoolean();
        }
    }

    public HistoryPager(Executor state, Executor network, Store store, Events events) {
        this.state = state;
        this.network = network;
        this.store = store;
        this.events = events;
    }

    public int limit() {
        return limit;
    }

    public String label() {
        return label;
    }

    public boolean loading() {
        return older != null && older.current();
    }

    public void reset() {
        generation++;
        older = null;
        limit = 200;
        oldest = "";
        label = "查看更早的记录";
    }

    public void today(ChannelClient client, String day, BooleanSupplier current) {
        if (day == null || day.isEmpty()) return;
        Request request = new Request(current);
        network.execute(
                () -> {
                    if (!request.current()) return;
                    try {
                        ChannelClient.Rows rows = client.history(day);
                        state.execute(
                                () -> {
                                    if (!request.current()) return;
                                    store.admit(rows.messages, true);
                                    String first = store.oldestDay();
                                    String candidate = first.isEmpty() ? day : first;
                                    // A late bootstrap must not move pagination forward over
                                    // already-scanned days.
                                    if (oldest.isEmpty() || candidate.compareTo(oldest) < 0)
                                        oldest = candidate;
                                    events.changed();
                                });
                    } catch (ChannelClient.Failure failure) {
                        state.execute(
                                () -> {
                                    if (request.current()) events.notice("暂时无法加载历史，可稍后重试");
                                });
                    }
                });
    }

    public void older(
            ChannelClient client, boolean online, String currentDay, BooleanSupplier current) {
        if (loading()) return;
        if (store.count() > limit) {
            limit += 200;
            events.changed();
            return;
        }
        if (!online) {
            events.notice("本机较早的记录已显示，联网后可继续加载");
            return;
        }
        Request request = new Request(current);
        older = request;
        String before = oldest.isEmpty() ? currentDay : oldest;
        events.changed();
        network.execute(
                () -> {
                    List<Message> found = new ArrayList<>();
                    String scanned = before;
                    try {
                        for (int i = 0; i < 14 && found.isEmpty(); i++) {
                            if (!request.current()) return;
                            scanned = LocalDate.parse(scanned).minusDays(1).toString();
                            found.addAll(client.history(scanned).messages);
                        }
                        String boundary = scanned;
                        state.execute(
                                () -> {
                                    if (older != request || !request.current()) return;
                                    older = null;
                                    oldest = boundary;
                                    limit += Math.max(100, found.size());
                                    store.admit(found, false);
                                    label =
                                            found.isEmpty()
                                                    ? boundary + " 之前暂未找到 · 继续找"
                                                    : "查看更早的记录";
                                    events.changed();
                                });
                    } catch (Exception failure) {
                        state.execute(
                                () -> {
                                    if (older != request || !request.current()) return;
                                    older = null;
                                    events.notice("更早记录加载失败，轻点重试");
                                });
                    }
                });
    }
}
