// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class HistoryPagerContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() throws Exception {
        try (Fixture f = new Fixture()) {
            f.count = 500;
            f.pager.older(null, false, "2026-11-15", () -> true);
            f.pager.older(null, false, "2026-11-15", () -> true);
            check(
                    f.pager.limit() == 600 && f.days.isEmpty(),
                    "cached rows expand without network or room access");
            f.pager.older(null, false, "2026-11-15", () -> true);
            check(f.notices.size() == 1, "offline paging stops after cached rows are visible");
        }
        try (Fixture f = new Fixture()) {
            f.pager.older(f.client, true, "2026-11-15", () -> f.current);
            f.pager.older(f.client, true, "2026-11-15", () -> f.current);
            f.time.drain();
            check(
                    f.days.size() == 14
                            && f.days.get(0).equals("2026-11-14")
                            && f.days.get(13).equals("2026-11-01")
                            && !f.pager.loading(),
                    "one older request scans at most fourteen days despite duplicate activation");
            f.pager.older(f.client, true, "2026-11-15", () -> f.current);
            f.time.drain();
            check(
                    f.days.get(14).equals("2026-10-31") && f.pager.label().contains("2026-10-18"),
                    "empty scans retain their boundary and the next activation continues earlier");
            f.pager.reset();
            f.pager.older(f.client, true, "2026-11-15", () -> f.current);
            f.current = false;
            f.time.drain();
            check(
                    f.days.size() == 28 && !f.pager.loading(),
                    "retired connection work never starts history HTTP");
        }
        try (Fixture f = new Fixture()) {
            f.pager.today(f.client, "2026-11-15", () -> f.current);
            f.time.worker.runNext();
            AsyncHarness.Task lateBootstrap = f.time.state.tasks.remove();
            f.pager.older(f.client, true, "2026-11-15", () -> f.current);
            f.time.drain();
            lateBootstrap.run();
            lateBootstrap.get();
            f.pager.older(f.client, true, "2026-11-15", () -> f.current);
            f.time.drain();
            check(
                    f.days.get(15).equals("2026-10-31") && f.today == 1,
                    "a late today bootstrap cannot move the scanned boundary forward");
            f.pager.today(f.client, "2026-11-15", () -> f.current);
            f.time.worker.runNext();
            f.pager.reset();
            f.time.drain();
            check(
                    f.today == 1 && f.pager.limit() == 200,
                    "a prior room's completed history cannot enter a reset pager");
        }
        try (Fixture f = new Fixture()) {
            f.withRow = true;
            f.pager.older(f.client, true, "2026-11-15", () -> f.current);
            f.time.drain();
            check(
                    f.days.size() == 1 && f.rows.size() == 1 && f.pager.limit() == 300,
                    "a nonempty day admits its rows once without reading or advancing a live"
                            + " cursor");
        }
        return checked;
    }

    private static final class Fixture implements AutoCloseable {
        final AsyncHarness time = new AsyncHarness();
        final List<String> days = new ArrayList<>(), notices = new ArrayList<>();
        final List<Message> rows = new ArrayList<>();
        boolean current = true, withRow;
        int count, today;
        final ChannelClient client =
                new ChannelClient(
                        new Endpoint("example.test", 443, true),
                        "fixture",
                        "qa",
                        (endpoint, token, method, path, headers, body, timeout) -> {
                            days.add(path.substring(path.indexOf("&date=") + 6));
                            return new ChannelTransport.Response(
                                    200,
                                    Json.object(
                                                    "seq",
                                                    900,
                                                    "entries",
                                                    withRow
                                                            ? new org.json.JSONArray()
                                                                    .put(
                                                                            Json.object(
                                                                                    "kind",
                                                                                            "answer",
                                                                                    "text",
                                                                                            "history",
                                                                                    "seq", 1))
                                                            : new org.json.JSONArray())
                                            .toString()
                                            .getBytes(StandardCharsets.UTF_8));
                        });
        final HistoryPager pager =
                new HistoryPager(
                        time.state,
                        time.worker,
                        new HistoryPager.Store() {
                            public int count() {
                                return count;
                            }

                            public void admit(List<Message> admitted, boolean bootstrap) {
                                rows.addAll(admitted);
                                if (bootstrap) today++;
                            }

                            public String oldestDay() {
                                return "2026-11-15";
                            }
                        },
                        new HistoryPager.Events() {
                            public void changed() {}

                            public void notice(String message) {
                                notices.add(message);
                            }
                        });

        public void close() {
            time.close();
        }
    }
}
