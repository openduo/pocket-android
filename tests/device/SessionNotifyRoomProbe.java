// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.core.SessionViewer;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Once-only notification acceptance for an explicitly selected ambient/keepalive test session. */
final class SessionNotifyRoomProbe {
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

    private static File journal(File directory, String room, String key) throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest((room + "\u0000" + key).getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder("session-notify-");
        for (byte value : digest)
            name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new File(directory, name + ".json");
    }

    private static JSONObject read(File file) throws Exception {
        try {
            return new JSONObject(
                    new String(
                            new android.util.AtomicFile(file).readFully(), StandardCharsets.UTF_8));
        } catch (java.io.FileNotFoundException absent) {
            if (file.exists()
                    || new File(file.getPath() + ".bak").exists()
                    || new File(file.getPath() + ".new").exists()) throw absent;
            return null;
        }
    }

    String run(Context context, String key) throws Exception {
        if (key.isEmpty())
            return "NOT_RUN actual session notification: explicitly select a dedicated test"
                    + " session with POCKET_TEST_NOTIFY_TARGET";
        check(
                key.startsWith("ambient:") && key.length() > 8
                        || key.startsWith("job:") && key.length() > 4,
                "actual notifications require an explicit ambient room or keepalive test job");
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(
                () ->
                        screen.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
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
        if (!initial.sessionsAccess.canNotify)
            return "NOT_RUN actual session notification: the device needs a notify-scope pairing";
        if (key.equals("ambient:" + controller.settings.room()))
            return "NOT_RUN actual session notification: the host hides this connection's own"
                    + " room; select a separate dedicated test session";
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
            SessionViewer.Summary recipient =
                    controller.snapshot().sessions.sessions.stream()
                            .filter(item -> item.key.equals(key))
                            .findFirst()
                            .orElse(null);
            if (recipient == null || !recipient.notify)
                return "NOT_RUN actual session notification: the selected test session is not"
                        + " listed as accepting notifications";
            controller.openSession(key);
            await(
                    () ->
                            controller.snapshot().sessions.openKey.equals(key)
                                    && controller.snapshot().sessions.loaded,
                    15_000);
            check(
                    controller.snapshot().sessions.openKey.equals(key)
                            && controller.snapshot().sessions.loaded,
                    "the actual notification target is exactly the selected test session");
            File directory = new File(context.getFilesDir(), "qa");
            directory.mkdirs();
            File journal = journal(directory, initial.importTarget, key);
            JSONObject record = read(journal);
            if (record == null)
                record =
                        new JSONObject()
                                .put("target", initial.importTarget)
                                .put("key", key)
                                .put("marker", "pocket-notify-" + UUID.randomUUID())
                                .put("stage", "prepared");
            check(
                    record.optString("target").equals(initial.importTarget)
                            && record.optString("key").equals(key)
                            && !record.optString("marker").isEmpty(),
                    "the private once-only journal belongs to this exact source and recipient");
            String marker = record.getString("marker");
            String text =
                    "DuoDuo Pocket session notification acceptance ["
                            + marker
                            + "]. Please reply with: "
                            + marker;
            if (record.optString("stage").equals("prepared")) {
                String previousId = controller.snapshot().sessions.notifyId;
                record.put("stage", "dispatching");
                save(journal, record);
                controller.notifySession(text);
                await(
                        () ->
                                !controller.snapshot().sessions.notifyId.isEmpty()
                                        && !controller
                                                .snapshot()
                                                .sessions
                                                .notifyId
                                                .equals(previousId),
                        3000);
                SessionViewer.Snapshot send = controller.snapshot().sessions;
                check(
                        !send.notifyId.equals(previousId)
                                && send.notifyKey.equals(key)
                                && send.notifyText.equals(text),
                        "the shipping controller assigns the actual notification recipient and"
                                + " body");
                record.put("id", send.notifyId);
                save(journal, record);
                await(() -> controller.snapshot().sessions.pending.isEmpty(), 30_000);
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
                        record.optString("stage").matches("sent|verified"),
                        "an interrupted unconfirmed send is never automatically retransmitted");
            }
            await(
                    () ->
                            controller.snapshot().sessions.rows.stream()
                                    .anyMatch(
                                            row ->
                                                    row.kind.equals("notify")
                                                            && row.text.equals(text)),
                    30_000);
            long copies =
                    controller.snapshot().sessions.rows.stream()
                            .filter(row -> row.kind.equals("notify") && row.text.equals(text))
                            .count();
            check(
                    controller.snapshot().sessions.openKey.equals(key) && copies == 1,
                    "the actual recipient session contains exactly one marked notification");
            check(
                    controller.snapshot().importTarget.equals(initial.importTarget)
                            && controller.draft().equals(initial.draft)
                            && controller.snapshot().quote.equals(initial.quote)
                            && controller.snapshot().chips.size() == initial.chips.size()
                            && controller.snapshot().outbox.isEmpty(),
                    "actual session delivery preserves the room composer and ordinary message"
                            + " outbox");
            record.put("stage", "verified");
            save(journal, record);
            return "PASS " + assertions + " actual selected-session notification assertions";
        } finally {
            controller.showSessions(false);
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("conversation"));
        }
    }
}
