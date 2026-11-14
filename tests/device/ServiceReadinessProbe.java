// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import dev.openduo.pocket.app.AppIdentity;
import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.core.SessionViewer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;

/** Read-only service prerequisites; no microphone, camera, playback or outgoing room content. */
final class ServiceReadinessProbe {
    private final Probe probe;

    ServiceReadinessProbe(Probe probe) {
        this.probe = probe;
    }

    private static void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(25);
    }

    String run(Context context) throws Exception {
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PocketController controller = PocketController.get(context);
        await(
                () ->
                        controller.snapshot() != null
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                25_000);
        PocketController.Snapshot initial = controller.snapshot();
        if (initial == null
                || !controller.configured()
                || initial.trying
                || initial.connection != PocketController.Connection.ONLINE)
            throw new AssertionError("The installed package must retain pairing and reach Online");
        if (initial.ambientActive
                || initial.holdRecording
                || initial.cameraOn
                || !initial.readingId.isEmpty()
                || !initial.outbox.isEmpty()
                || !initial.imports.isEmpty())
            throw new AssertionError(
                    "Service inspection requires idle recording and outgoing work");
        JSONObject evidence =
                new JSONObject()
                        .put("package", context.getPackageName())
                        .put("settings", AppIdentity.SETTINGS)
                        .put("database", AppIdentity.DATABASE)
                        .put("tailnet_hostname", AppIdentity.TAILNET_HOSTNAME)
                        .put("tailnet", initial.tailnetState)
                        .put("online", true)
                        .put("scope", controller.settings.scope())
                        .put("cursor", initial.cursor)
                        .put("cached_rows", initial.rows.size())
                        .put("voice_available", initial.voiceAvailable)
                        .put("can_view_sessions", initial.sessionsAccess.canView)
                        .put("can_notify_sessions", initial.sessionsAccess.canNotify);
        try {
            if (initial.sessionsAccess.canView) {
                probe.runOnMainSync(() -> ((MainActivity) screen).navigate("sessions"));
                await(
                        () ->
                                controller.snapshot().sessions.listed
                                        || !controller.snapshot().sessions.error.isEmpty(),
                        12_000);
                SessionViewer.Snapshot sessions = controller.snapshot().sessions;
                evidence.put("sessions_listed", sessions.listed)
                        .put("sessions_error", sessions.error);
                JSONArray candidates = new JSONArray();
                for (SessionViewer.Summary item : sessions.sessions)
                    if (item.key.startsWith("ambient:") || item.key.startsWith("job:"))
                        candidates.put(
                                new JSONObject()
                                        .put("key", item.key)
                                        .put("name", item.name)
                                        .put("notify", item.notify));
                evidence.put("test_recipient_candidates", candidates);
            }
            PocketController.Snapshot after = controller.snapshot();
            if (!after.importTarget.equals(initial.importTarget)
                    || !after.draft.equals(initial.draft)
                    || !after.quote.equals(initial.quote)
                    || !after.outbox.isEmpty())
                throw new AssertionError("Read-only inspection must preserve the paired composer");
            File directory = new File(context.getExternalCacheDir(), "service-readiness");
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new java.io.IOException("Could not create private readiness evidence");
            Files.write(
                    new File(directory, "readiness.json").toPath(),
                    evidence.toString(2).getBytes(StandardCharsets.UTF_8));
            return "PASS actual original-package Online/pairing/history inspection; scope="
                    + controller.settings.scope()
                    + "; rows="
                    + initial.rows.size();
        } finally {
            controller.showSessions(false);
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("conversation"));
        }
    }
}
