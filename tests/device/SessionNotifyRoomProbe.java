// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.core.SessionViewer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

/** Once-only notification acceptance restricted to the app's own currently paired room. */
final class SessionNotifyRoomProbe {
    private static final String TEXT = "这是 Pocket E2 当前房间的会话通知集成测试，请只回复：会话通知验证成功。";
    private final Probe probe;
    private PocketController controller;
    private int assertions;

    SessionNotifyRoomProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(25);
    }

    private static void save(File file, JSONObject record) throws Exception {
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        java.io.FileOutputStream out = atomic.startWrite();
        try {
            out.write(record.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (Throwable failure) {
            atomic.failWrite(out);
            throw failure;
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
        await(
                () ->
                        controller.snapshot() != null
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                35_000);
        check(
                controller.snapshot() != null
                        && controller.configured()
                        && !controller.snapshot().trying
                        && controller.snapshot().connection == PocketController.Connection.ONLINE,
                "real notification acceptance starts in the already paired actual room");
        PocketController.Snapshot initial = controller.snapshot();
        check(
                !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.cameraOn
                        && initial.readingId.isEmpty()
                        && initial.outbox.isEmpty()
                        && initial.imports.isEmpty(),
                "real notification acceptance requires idle capture and outgoing work");
        if (!controller.settings.scope().matches("sessions|notify"))
            return "NOT_RUN actual session notification: current credential cannot view sessions";
        try {
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("sessions"));
            await(
                    () ->
                            controller.snapshot().sessions.listed
                                    || !controller.snapshot().sessions.error.isEmpty(),
                    15_000);
            check(
                    controller.snapshot().sessions.listed,
                    "actual scoped service returns the session list");
            String key = "ambient:" + controller.settings.room();
            SessionViewer.Summary own =
                    controller.snapshot().sessions.sessions.stream()
                            .filter(item -> item.key.equals(key))
                            .findFirst()
                            .orElse(null);
            if (own == null || !own.notify)
                return "NOT_RUN actual session notification: the current paired room is not an"
                        + " available notification target";
            controller.openSession(key);
            await(
                    () ->
                            controller.snapshot().sessions.openKey.equals(key)
                                    && controller.snapshot().sessions.loaded,
                    15_000);
            check(
                    controller.snapshot().sessions.openKey.equals(key)
                            && controller.snapshot().sessions.loaded,
                    "the actual notification target is exactly the currently paired room");
            File directory = new File(context.getFilesDir(), "qa");
            directory.mkdirs();
            File journal = new File(directory, "session-notify-own-room-once.json");
            JSONObject record =
                    journal.exists()
                            ? new JSONObject(
                                    new String(
                                            Files.readAllBytes(journal.toPath()),
                                            StandardCharsets.UTF_8))
                            : new JSONObject()
                                    .put("target", initial.importTarget)
                                    .put("key", key)
                                    .put("stage", "prepared");
            check(
                    record.optString("target").equals(initial.importTarget)
                            && record.optString("key").equals(key),
                    "the private once-only notification journal belongs to this exact room");
            if (record.optString("stage").equals("prepared")) {
                record.put("stage", "dispatching");
                save(journal, record);
                controller.notifySession(TEXT);
                await(() -> !controller.snapshot().sessions.notifyId.isEmpty(), 3000);
                SessionViewer.Snapshot send = controller.snapshot().sessions;
                check(
                        send.notifyKey.equals(key) && send.notifyText.equals(TEXT),
                        "the shipping controller assigns the actual notification recipient and"
                                + " body");
                record.put("id", send.notifyId);
                save(journal, record);
                await(() -> controller.snapshot().sessions.pending.isEmpty(), 60_000);
                SessionViewer.Snapshot result = controller.snapshot().sessions;
                check(
                        result.notifyId.equals(record.getString("id"))
                                && result.outcome.equals("sent"),
                        "the actual server confirms delivery for the original correlated"
                                + " notification");
                record.put("stage", "sent");
                save(journal, record);
            } else {
                check(
                        record.optString("stage").equals("sent"),
                        "an interrupted unconfirmed send is never automatically retransmitted");
            }
            await(
                    () ->
                            controller.snapshot().sessions.rows.stream()
                                    .anyMatch(
                                            row ->
                                                    row.kind.equals("notify")
                                                            && row.text.contains("Pocket E2")
                                                            && row.text.contains("会话通知集成测试")),
                    60_000);
            long copies =
                    controller.snapshot().sessions.rows.stream()
                            .filter(
                                    row ->
                                            row.kind.equals("notify")
                                                    && row.text.contains("Pocket E2")
                                                    && row.text.contains("会话通知集成测试"))
                            .count();
            check(
                    copies == 1,
                    "the actual recipient session contains exactly one marked notification");
            check(
                    controller.snapshot().importTarget.equals(initial.importTarget)
                            && controller.draft().equals(initial.draft)
                            && controller.snapshot().quote.equals(initial.quote)
                            && controller.snapshot().chips.size() == initial.chips.size()
                            && controller.snapshot().outbox.isEmpty(),
                    "actual session delivery preserves the room composer and ordinary message"
                            + " outbox");
            return "PASS " + assertions + " actual own-room session notification assertions";
        } finally {
            controller.showSessions(false);
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("conversation"));
        }
    }
}
