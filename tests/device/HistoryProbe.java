// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.ChannelConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Prepared live/history outcomes through the real controller and a separate native SQLite room. */
final class HistoryProbe {
    private static final String DATABASE = "duoduo-pocket-history-qa.db";
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private RoomRepository repository;
    private final Source source = new Source();
    private int assertions;

    HistoryProbe(Probe probe) {
        this.probe = probe;
    }

    private Object historyField(String name) throws Exception {
        Object history = field("history");
        Field field = history.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(history);
    }

    private void historyField(String name, Object value) throws Exception {
        Object history = field("history");
        Field field = history.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(history, value);
    }

    private dev.openduo.pocket.net.RoomLink link() throws Exception {
        return (dev.openduo.pocket.net.RoomLink) field("liveLink");
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void field(String name, Object value) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private JSONObject answer(String id, long seq, String text, boolean attachment) {
        JSONObject row =
                Json.object(
                        "kind",
                        "answer",
                        "seq",
                        seq,
                        "at",
                        java.time.Instant.now().toString(),
                        "text",
                        text,
                        "message_ids",
                        new JSONArray().put(id));
        if (attachment)
            try {
                row.put(
                        "attachments",
                        new JSONArray()
                                .put(
                                        Json.object(
                                                "name", "prepared.txt",
                                                "mime", "text/plain",
                                                "path", "/qa/prepared.txt")));
            } catch (org.json.JSONException impossible) {
                throw new AssertionError(impossible);
            }
        return row;
    }

    private void frame(JSONObject frame) throws Exception {
        onState(
                () -> {
                    Method method =
                            PocketController.class.getDeclaredMethod(
                                    "frame", String.class, boolean.class);
                    method.setAccessible(true);
                    method.invoke(controller, frame.toString(), true);
                    return null;
                });
    }

    private void turn(String id) throws Exception {
        frame(
                Json.object(
                        "type",
                        "turn",
                        "phase",
                        "thinking",
                        "message_ids",
                        new JSONArray().put(id)));
        check(controller.snapshot().working != null, "prepared turn starts a working bubble");
    }

    private void row(JSONObject row) throws Exception {
        frame(Json.object("type", "row", "seq", row.optLong("seq"), "row", row));
    }

    private void reset() throws Exception {
        onState(
                () -> {
                    repository.clear();
                    repository.metadata("cursor", "0");
                    repository.metadata("history_loaded", "1");
                    ((TurnState) field("turn")).reset();
                    historyField("oldest", source.today);
                    historyField("limit", 200);
                    source.history.clear();
                    source.catchup = new JSONArray();
                    invoke("reload");
                    invoke("publish");
                    return null;
                });
    }

    private void finalAnswer(String id, String text) throws Exception {
        frame(
                Json.object(
                        "type",
                        "answer_final",
                        "speech_id",
                        "qa-" + id,
                        "message_ids",
                        new JSONArray().put(id),
                        "text",
                        text));
    }

    private void answerOrdering() throws Exception {
        final int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        List<JSONObject> rows =
                Arrays.asList(
                        answer("qa-multiple", 220, "Prepared repeated text", false),
                        answer("qa-multiple", 221, "Prepared repeated text", false),
                        answer("qa-multiple", 222, "Prepared repeated text", false));
        for (int[] order : orders) {
            reset();
            finalAnswer("qa-multiple", "Prepared repeated text");
            onState(
                    () -> {
                        List<Message> batch = new ArrayList<>();
                        for (int index : order)
                            batch.add(ChannelClient.mapEntry(rows.get(index), -1));
                        repository.admit(batch, 222);
                        repository.admit(batch, 222);
                        invoke("reload");
                        invoke("publish");
                        return null;
                    });
            Set<Long> sequences = new HashSet<>();
            for (Message message : controller.snapshot().rows) sequences.add(message.seq);
            check(
                    controller.snapshot().rows.size() == 3
                            && sequences.equals(new HashSet<>(Arrays.asList(220L, 221L, 222L))),
                    "repeated-text answers of one turn survive batched admission and replay in"
                            + " order "
                            + Arrays.toString(order));
            finalAnswer("qa-multiple", "Prepared repeated text");
            check(
                    controller.snapshot().rows.size() == 3
                            && controller.snapshot().rows.stream().allMatch(m -> m.seq >= 0),
                    "a late repeated final neither duplicates nor overwrites the three durable"
                            + " answer rows");
        }
        reset();
        finalAnswer("qa-unrelated-a", "Prepared identical answer");
        finalAnswer("qa-unrelated-b", "Prepared identical answer");
        row(answer("qa-unrelated-b", 223, "Prepared identical answer", true));
        check(
                controller.snapshot().rows.size() == 2
                        && controller.snapshot().rows.stream().filter(m -> m.seq < 0).count() == 1,
                "a same-text answer to another input cannot consume the first provisional");
        row(answer("qa-unrelated-a", 224, "Prepared identical answer", true));
        check(
                controller.snapshot().rows.size() == 2
                        && controller.snapshot().rows.stream().allMatch(m -> m.seq >= 0),
                "both independently correlated answers settle once");
    }

    private long notices(Context context, Set<String> before) {
        return Arrays.stream(
                        context.getSystemService(android.app.NotificationManager.class)
                                .getActiveNotifications())
                .filter(
                        n ->
                                !before.contains(n.getKey())
                                        && n.getTag() != null
                                        && n.getTag().startsWith("answer:"))
                .count();
    }

    private void backgroundNotices(Context context, Activity screen) throws Exception {
        reset();
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("answer.notices", Context.MODE_PRIVATE);
        String seen = prefs.getString("seen", null);
        Set<String> before = new HashSet<>();
        for (android.service.notification.StatusBarNotification n :
                manager.getActiveNotifications()) before.add(n.getKey());
        boolean enabled =
                manager.areNotificationsEnabled() && controller.settings.answerNotifications();
        try {
            probe.runOnMainSync(() -> screen.moveTaskToBack(true));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (onState(() -> (Boolean) field("foreground"))
                    && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            check(
                    onState(() -> !(Boolean) field("foreground")),
                    "the actual Activity has stopped before notification catch-up");
            finalAnswer("qa-notice", "Prepared local notice " + UUID.randomUUID());
            String text = controller.snapshot().rows.get(0).text;
            String afterLive = prefs.getString("seen", "");
            if (enabled) {
                await(() -> notices(context, before) == 1, 3000);
                check(
                        notices(context, before) == 1,
                        "the prepared final creates one actual native background notification");
            }
            source.catchup = new JSONArray().put(answer("qa-notice", 225, text, true));
            CountDownLatch completed = new CountDownLatch(1);
            controller.backgroundFetch(completed::countDown);
            check(
                    completed.await(5, TimeUnit.SECONDS),
                    "background attachment catch-up finishes with the actual Activity stopped");
            check(
                    afterLive.equals(prefs.getString("seen", ""))
                            && controller.snapshot().rows.size() == 1,
                    "attachment catch-up reuses the live answer's local notice identity and"
                            + " replaces its provisional once");
            if (enabled)
                check(
                        notices(context, before) == 1,
                        "live then periodic attachment catch-up produces only one native"
                                + " notification");

            onState(
                    () -> {
                        repository.clear();
                        invoke("reload");
                        return null;
                    });
            source.catchup =
                    new JSONArray()
                            .put(
                                    answer(
                                            "qa-bootstrap",
                                            226,
                                            "Prepared silent initial history",
                                            true));
            CountDownLatch bootstrap = new CountDownLatch(1);
            controller.backgroundFetch(bootstrap::countDown);
            check(
                    bootstrap.await(5, TimeUnit.SECONDS),
                    "first-cursor background bootstrap completes");
            check(
                    controller.snapshot().rows.size() == 1
                            && (!enabled || notices(context, before) == 1),
                    "an initial cursor admits attachment history without notifying old answers");
        } finally {
            for (android.service.notification.StatusBarNotification n :
                    manager.getActiveNotifications())
                if (!before.contains(n.getKey())) manager.cancel(n.getTag(), n.getId());
            if (seen == null) prefs.edit().remove("seen").commit();
            else prefs.edit().putString("seen", seen).commit();
            context.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(context.getPackageName(), MainActivity.class.getName())
                            .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (onState(() -> !(Boolean) field("foreground"))
                    && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        }
    }

    private void retiredHistory() throws Exception {
        for (boolean failed : new boolean[] {false, true}) {
            reset();
            String retiredDate = java.time.LocalDate.parse(source.today).minusDays(1).toString();
            String replacementDate =
                    java.time.LocalDate.parse(source.today).minusDays(11).toString();
            HistoryGate retired = new HistoryGate(new JSONArray(), failed);
            HistoryGate replacement =
                    new HistoryGate(
                            new JSONArray()
                                    .put(
                                            answer(
                                                    "qa-current-history",
                                                    230,
                                                    "Prepared current history",
                                                    true)),
                            false);
            source.gates.put(retiredDate, retired);
            source.gates.put(replacementDate, replacement);
            try {
                controller.loadOlder();
                check(
                        retired.started.await(3, TimeUnit.SECONDS)
                                && controller.snapshot().olderLoading,
                        "the retired day request is actually held while its loading indicator is"
                                + " visible");
                onState(
                        () -> {
                            link().invalidate();
                            field("lastError", "Prepared current connection");
                            historyField(
                                    "oldest",
                                    java.time.LocalDate.parse(source.today)
                                            .minusDays(10)
                                            .toString());
                            invoke("publish");
                            return null;
                        });
                check(
                        !controller.snapshot().olderLoading,
                        "retiring a connection releases its history loading indicator before the"
                                + " old HTTP request returns");
                controller.loadOlder();
                check(
                        replacement.started.await(3, TimeUnit.SECONDS),
                        "the replacement connection can start another history request immediately");
                int requests = source.dateRequests.get();
                retired.release.countDown();
                check(
                        retired.returned.await(3, TimeUnit.SECONDS),
                        "the old HTTP outcome is released while its replacement is pending");
                Thread.sleep(200);
                onState(() -> null);
                check(
                        controller.snapshot().olderLoading
                                && controller
                                        .snapshot()
                                        .lastError
                                        .equals("Prepared current connection")
                                && controller.snapshot().rows.isEmpty()
                                && source.dateRequests.get() == requests,
                        "a retired "
                                + (failed ? "failure" : "empty response")
                                + " cannot clear current loading, show an old error, or keep"
                                + " scanning old dates");
                replacement.release.countDown();
                await(
                        () ->
                                !controller.snapshot().olderLoading
                                        && controller.snapshot().rows.size() == 1,
                        3000);
                check(
                        !controller.snapshot().olderLoading
                                && controller.snapshot().rows.size() == 1
                                && controller
                                        .snapshot()
                                        .rows
                                        .get(0)
                                        .text
                                        .equals("Prepared current history"),
                        "only the current history response settles the replacement request");
            } finally {
                retired.release.countDown();
                replacement.release.countDown();
                source.gates.clear();
            }
        }
    }

    String run(Context context) throws Exception {
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        await(() -> controller.snapshot() != null, 5000);
        PocketController.Snapshot initial = controller.snapshot();
        check(
                initial != null
                        && !initial.trying
                        && !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.outbox.isEmpty()
                        && initial.working == null
                        && !initial.olderLoading
                        && !initial.sessions.watching,
                "history acceptance requires the actual room idle");
        ChannelConnection original = onState(() -> (ChannelConnection) field("channel"));
        RoomRepository originalRepo = onState(() -> (RoomRepository) field("repo"));
        Object originalState = onState(() -> field("roomState"));
        Object originalTurn = onState(() -> field("turn"));
        Object originalNotes = onState(() -> field("liveNotes"));
        Object originalLimit = onState(() -> historyField("limit"));
        Object originalDate = onState(() -> historyField("oldest"));
        Object originalLabel = onState(() -> historyField("label"));
        android.content.SharedPreferences noticePrefs =
                context.getSharedPreferences("answer.notices", Context.MODE_PRIVATE);
        String originalSeen = noticePrefs.getString("seen", null);
        String originalError = initial.lastError;
        context.deleteDatabase(DATABASE);
        repository = new RoomRepository(context, DATABASE);
        repository.select("qa:history");
        repository.metadata("cursor", "0");
        repository.metadata("history_loaded", "1");
        try {
            onState(
                    () -> {
                        link().invalidate();
                        original.closeLive();
                        field("repo", repository);
                        field("channel", source);
                        field("turn", new TurnState());
                        field("liveNotes", new ArrayList<Message>());
                        field("roomState", null);
                        link().ready();
                        field("retryNotBefore", 0L);
                        field("connection", PocketController.Connection.OFFLINE);
                        invoke("reload");
                        invoke("connect");
                        return null;
                    });
            await(
                    () -> controller.snapshot().connection == PocketController.Connection.ONLINE,
                    5000);
            long roomDeadline = SystemClock.elapsedRealtime() + 3000;
            while (onState(() -> field("roomState") == null)
                    && SystemClock.elapsedRealtime() < roomDeadline) Thread.sleep(20);
            check(
                    onState(() -> field("repo") != field("realRepo") && field("channel") == source),
                    "all history stimuli use separate SQLite storage and a transport without"
                            + " network delegation");

            turn("qa-live");
            row(answer("qa-live-longer", 1, "Prepared unrelated answer", true));
            check(
                    controller.snapshot().working != null,
                    "a complete but unrelated attachment answer cannot end the turn");
            row(answer("qa-live", 2, "Prepared attachment answer", true));
            check(
                    controller.snapshot().working == null,
                    "a correlated attachment log row ends a turn whose answer_final was missed");

            reset();
            turn("qa-today");
            source.history.put(
                    source.today,
                    new JSONArray().put(answer("qa-today", 3, "Prepared today", true)));
            onState(
                    () -> {
                        invoke("historyToday");
                        return null;
                    });
            await(() -> controller.snapshot().rows.size() == 1, 3000);
            check(
                    controller.snapshot().working == null && controller.snapshot().rows.size() == 1,
                    "HTTP day refresh both admits the attachment answer and ends its waiting"
                            + " bubble");

            reset();
            turn("qa-older");
            source.history.put(
                    java.time.LocalDate.parse(source.today).minusDays(1).toString(),
                    new JSONArray().put(answer("qa-older", 4, "Prepared older", true)));
            controller.loadOlder();
            await(
                    () ->
                            controller.snapshot().rows.size() == 1
                                    && !controller.snapshot().olderLoading,
                    3000);
            check(
                    controller.snapshot().working == null && controller.snapshot().rows.size() == 1,
                    "older-day loading applies the same correlated settlement");

            reset();
            turn("qa-catchup");
            source.catchup =
                    new JSONArray().put(answer("qa-catchup", 5, "Prepared catch-up", true));
            CountDownLatch complete = new CountDownLatch(1);
            controller.backgroundFetch(complete::countDown);
            check(complete.await(5, TimeUnit.SECONDS), "periodic catch-up completes");
            check(
                    controller.snapshot().working == null && controller.snapshot().rows.size() == 1,
                    "periodic catch-up settles the correlated working bubble");

            reset();
            JSONObject cached = answer("qa-midnight", 6, "Prepared before midnight", true);
            cached.put(
                    "at",
                    java.time.LocalDate.now()
                            .minusDays(1)
                            .atTime(23, 59)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toInstant()
                            .toString());
            onState(
                    () -> {
                        List<Message> rows = new ArrayList<>();
                        rows.add(ChannelClient.mapEntry(cached, 6));
                        for (int i = 0; i < 205; i++)
                            rows.add(
                                    ChannelClient.mapEntry(
                                            Json.object(
                                                    "kind",
                                                    "heard",
                                                    "seq",
                                                    7 + i,
                                                    "at",
                                                    java.time.Instant.now().toString(),
                                                    "text",
                                                    "Prepared heard " + i),
                                            7 + i));
                        repository.admit(rows, 211);
                        invoke("reload");
                        invoke("publish");
                        return null;
                    });
            check(
                    controller.snapshot().rows.stream().noneMatch(m -> m.kind == Message.DUODUO),
                    "the cached answer is outside the initial 200-row viewport");
            frame(
                    Json.object(
                            "type",
                            "ready",
                            "api",
                            2,
                            "seq",
                            211,
                            "turn",
                            Json.object(
                                    "phase",
                                    "thinking",
                                    "message_ids",
                                    new JSONArray().put("qa-midnight"))));
            check(
                    controller.snapshot().working == null,
                    "ready restores then settles from cached answers across midnight beyond the"
                            + " visible row window");

            reset();
            turn("qa-provisional");
            frame(Json.object("type", "turn", "phase", "tool", "label", "Bash"));
            frame(
                    Json.object(
                            "type",
                            "answer_final",
                            "speech_id",
                            "qa-speech",
                            "message_ids",
                            new JSONArray().put("qa-provisional"),
                            "text",
                            "Prepared final with attachment"));
            check(
                    controller.snapshot().rows.size() == 1,
                    "the final answer is shown before its log row");
            JSONObject logged =
                    answer("qa-provisional", 212, "Prepared final with attachment", true);
            row(logged);
            row(logged);
            check(
                    controller.snapshot().rows.size() == 1
                            && controller.snapshot().rows.get(0).seq == 212
                            && controller.snapshot().rows.get(0).attachments.size() == 1,
                    "an attachment log row replaces the provisional answer once even when"
                            + " replayed");
            String loggedId = controller.snapshot().rows.get(0).id;
            check(
                    controller.snapshot().traces.containsKey(loggedId),
                    "the attachment answer retains its completed tool trace after identity"
                            + " replacement");

            onState(
                    () -> {
                        repository.close();
                        repository = new RoomRepository(context, DATABASE);
                        repository.select("qa:history");
                        field("repo", repository);
                        invoke("reload");
                        invoke("publish");
                        return null;
                    });
            JSONArray ids =
                    onState(() -> repository.rows(10).get(0).json().optJSONArray("message_ids"));
            check(
                    ids != null && ids.length() == 1 && ids.optString(0).equals("qa-provisional"),
                    "explicit answer correlation survives closing and reopening native SQLite");
            probe.waitForIdleSync();
            probe.screenshot(context, "history-attachment-settled");
            finalAnswer("qa-provisional", "Prepared final with attachment");
            check(
                    controller.snapshot().rows.size() == 1
                            && controller.snapshot().rows.get(0).seq == 212,
                    "a final arriving after its attachment log row cannot recreate a provisional");
            answerOrdering();
            retiredHistory();
            backgroundNotices(context, screen);
        } finally {
            onState(
                    () -> {
                        link().invalidate();
                        source.closeLive();
                        repository.clear();
                        repository.close();
                        field("repo", originalRepo);
                        field("channel", original);
                        field("roomState", originalState);
                        field("lastError", originalError);
                        field("turn", originalTurn);
                        field("liveNotes", originalNotes);
                        historyField("limit", originalLimit);
                        historyField("oldest", originalDate);
                        historyField("label", originalLabel);
                        link().ready();
                        field("retryNotBefore", 0L);
                        field("connection", PocketController.Connection.OFFLINE);
                        if (originalSeen == null) noticePrefs.edit().remove("seen").commit();
                        else noticePrefs.edit().putString("seen", originalSeen).commit();
                        invoke("restoreDraft");
                        invoke("reload");
                        invoke("connect");
                        invoke("publish");
                        return null;
                    });
            context.deleteDatabase(DATABASE);
        }
        await(() -> controller.snapshot().connection == PocketController.Connection.ONLINE, 30_000);
        check(
                controller.snapshot().importTarget.equals(initial.importTarget)
                        && controller.snapshot().connection == PocketController.Connection.ONLINE
                        && controller.draft().equals(initial.draft)
                        && controller.snapshot().quote.equals(initial.quote)
                        && controller.snapshot().chips.size() == initial.chips.size()
                        && controller.snapshot().outbox.isEmpty(),
                "the actual paired room and composer are restored without sending a real message");
        return "PASS "
                + assertions
                + " native history assertions; live and HTTP outcomes are prepared";
    }

    private static final class HistoryGate {
        final JSONArray rows;
        final boolean failed;
        final CountDownLatch started = new CountDownLatch(1),
                release = new CountDownLatch(1),
                returned = new CountDownLatch(1);

        HistoryGate(JSONArray rows, boolean failed) {
            this.rows = rows;
            this.failed = failed;
        }

        JSONArray response() throws java.io.IOException, InterruptedException {
            started.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS))
                    throw new java.io.IOException("Prepared history gate expired");
                if (failed) throw new java.io.IOException("Prepared retired history failure");
                return rows;
            } finally {
                returned.countDown();
            }
        }
    }

    /** No delegate, upload, download, speech stream or capture is available in this fixture. */
    private static final class Source implements ChannelConnection {
        final String today = Json.day(System.currentTimeMillis());
        final Map<String, JSONArray> history = new ConcurrentHashMap<>();
        final Map<String, HistoryGate> gates = new ConcurrentHashMap<>();
        final AtomicInteger dateRequests = new AtomicInteger();
        volatile JSONArray catchup = new JSONArray();
        volatile boolean open;

        private Response json(JSONObject value) {
            return new Response(200, value.toString().getBytes(StandardCharsets.UTF_8));
        }

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            if (!method.equals("GET"))
                throw new AssertionError("History probe cannot send messages");
            if (path.equals("/healthz")) return json(Json.object("ok", true));
            if (path.startsWith("/api/state?"))
                return json(Json.object("room", "qa", "date", today));
            if (path.startsWith("/api/imlog?")) {
                int date = path.indexOf("&date=");
                JSONArray rows = date >= 0 ? history.get(path.substring(date + 6)) : catchup;
                if (date >= 0) {
                    dateRequests.incrementAndGet();
                    HistoryGate gate = gates.get(path.substring(date + 6));
                    if (gate != null) rows = gate.response();
                }
                return json(
                        Json.object("entries", rows == null ? new JSONArray() : rows, "seq", 211));
            }
            throw new AssertionError("Unexpected history probe request");
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current) {
            if (!current.getAsBoolean()) return;
            open = true;
            listener.text(
                    Json.object("type", "ready", "api", 2, "seq", 0, "turn", JSONObject.NULL)
                            .toString());
        }

        public void send(JSONObject frame) {}

        public void sendBinary(byte[] bytes) {
            throw new AssertionError("No capture in history probe");
        }

        public boolean isOpen() {
            return open;
        }

        public void closeLive() {
            open = false;
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current) {
            throw new AssertionError("No speech in history probe");
        }

        public void cancelStream(long id) {}
    }
}
