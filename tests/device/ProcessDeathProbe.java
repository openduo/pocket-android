// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/**
 * Three native phases prepare, verify and restore around uninstrumented force-stop/cold launches.
 */
final class ProcessDeathProbe {
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private int assertions;

    ProcessDeathProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void await(java.util.function.BooleanSupplier condition, long timeout)
            throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(20);
    }

    private File journal(Context context) {
        return new File(context.getFilesDir(), "qa/process-death.json");
    }

    private JSONObject read(Context context) throws Exception {
        return new JSONObject(
                new String(Files.readAllBytes(journal(context).toPath()), StandardCharsets.UTF_8));
    }

    private List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (Attachment chip : controller.snapshot().chips) paths.add(chip.localPath);
        return paths;
    }

    android.os.Bundle run(Context context, String phase) throws Exception {
        Activity screen =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        controller = PocketController.get(context);
        Field field = PocketController.class.getDeclaredField("state");
        field.setAccessible(true);
        state = (Executor) field.get(controller);
        await(() -> controller.snapshot() != null, 5000);
        probe.runOnMainSync(
                () ->
                        screen.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        android.os.Bundle result = new android.os.Bundle();
        if (phase.equals("prepare")) {
            check(
                    !journal(context).exists(),
                    "a previous process-death run must be restored before starting another");
            PocketController.Snapshot initial = controller.snapshot();
            check(
                    !initial.trying
                            && !initial.ambientActive
                            && !initial.holdRecording
                            && !initial.uploadingVoice
                            && !initial.cameraOn
                            && initial.outbox.isEmpty()
                            && initial.imports.isEmpty()
                            && initial.readingId.isEmpty()
                            && initial.chips.stream()
                                    .noneMatch(a -> a.state == Attachment.UPLOADING),
                    "process-death acceptance requires the actual application idle");
            String fixture = UUID.randomUUID().toString();
            String before = "DuoDuo Pocket preserved draft " + fixture;
            String incoming = "DuoDuo Pocket incoming share " + fixture;
            String seeded = initial.draft.isEmpty() ? before : initial.draft + "\n" + before;
            JSONObject journal =
                    Json.object(
                            "fixture",
                            fixture,
                            "target",
                            initial.importTarget,
                            "draft",
                            initial.draft,
                            "quote",
                            initial.quote,
                            "voice",
                            initial.voiceInput,
                            "paths",
                            new JSONArray(paths()),
                            "before",
                            before,
                            "incoming",
                            incoming,
                            "expected",
                            seeded);
            journal(context).getParentFile().mkdirs();
            android.util.AtomicFile atomic = new android.util.AtomicFile(journal(context));
            FileOutputStream out = atomic.startWrite();
            try {
                out.write(journal.toString().getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(out);
            } catch (Throwable failure) {
                atomic.failWrite(out);
                throw failure;
            }
            controller.setVoiceInput(false);
            controller.draft(seeded);
            onState(() -> null);
            context.getSharedPreferences("duoduo-pocket.settings", 0).edit().commit();
            check(
                    controller.draft().equals(seeded),
                    "the existing composer is durably seeded before external sharing");
            result.putString("fixture", fixture);
        } else if (phase.equals("verify")) {
            JSONObject journal = read(context);
            check(
                    controller.snapshot().importTarget.equals(journal.getString("target")),
                    "actual process restarts preserve the original paired room");
            check(
                    controller.draft().equals(journal.getString("expected"))
                            && controller.snapshot().quote.equals(journal.getString("quote")),
                    "unfinished external sharing recovers exactly once alongside the existing draft"
                            + " and quote");
            Set<String> original = new LinkedHashSet<>();
            JSONArray saved = journal.getJSONArray("paths");
            for (int i = 0; i < saved.length(); i++) original.add(saved.getString(i));
            List<Attachment> added = new ArrayList<>();
            for (Attachment chip : controller.snapshot().chips)
                if (!original.contains(chip.localPath)) added.add(chip);
            check(
                    paths().containsAll(original)
                            && added.size() == 1
                            && added.get(0).name.equals(ProcessSource.NAME),
                    "process death neither drops existing attachments nor admits duplicate fixture"
                            + " chips");
            Attachment chip = added.get(0);
            check(
                    Arrays.equals(
                            Files.readAllBytes(new File(chip.localPath).toPath()),
                            ProcessSource.body()),
                    "the restarted copy contains the complete provider body rather than its first"
                            + " 8192 bytes");
            File committed = new File(chip.localPath).getParentFile();
            Field repoField = PocketController.class.getDeclaredField("repo");
            repoField.setAccessible(true);
            RoomRepository repo = (RoomRepository) repoField.get(controller);
            check(
                    onState(() -> repo.imported(committed.getName()))
                            && onState(() -> repo.importRequests().isEmpty())
                            && !new File(committed.getPath() + ".partial").exists()
                            && controller.snapshot().imports.isEmpty()
                            && controller.snapshot().outbox.isEmpty(),
                    "receipt and draft recover together, incomplete bytes are pruned, and no"
                            + " message is sent");
            android.os.Bundle source =
                    context.getContentResolver().call(ProcessControl.URI, "state", null, null);
            check(
                    source != null && source.getInt("opens") == 2 && source.getInt("active") == 0,
                    "another process kill cannot reopen an admitted source");
            check(
                    context.getContentResolver().getPersistedUriPermissions().stream()
                            .noneMatch(
                                    grant ->
                                            Documents.AUTHORITY.equals(
                                                    grant.getUri().getAuthority())),
                    "recovered file admission releases its retained document grant");
        } else if (phase.equals("restore")) {
            JSONObject journal = read(context);
            check(
                    controller.snapshot().importTarget.equals(journal.getString("target")),
                    "process-death cleanup remains scoped to the original room");
            for (ImportProgress progress : controller.snapshot().imports)
                if (progress.name.equals(ProcessSource.NAME)) controller.cancelImport(progress.id);
            await(() -> controller.snapshot().imports.isEmpty(), 3000);
            Set<String> original = new LinkedHashSet<>();
            JSONArray saved = journal.getJSONArray("paths");
            for (int i = 0; i < saved.length(); i++) original.add(saved.getString(i));
            for (Attachment chip : controller.snapshot().chips)
                if (!original.contains(chip.localPath) && chip.name.equals(ProcessSource.NAME))
                    controller.removeAttachment(chip.localPath);
            List<String> kept = new ArrayList<>();
            for (String line : controller.draft().split("\n", -1))
                if (!line.equals(journal.getString("before"))
                        && !line.equals(journal.getString("incoming"))) kept.add(line);
            controller.draft(String.join("\n", kept));
            controller.setVoiceInput(journal.getBoolean("voice"));
            onState(() -> null);
            context.getSharedPreferences("duoduo-pocket.settings", 0).edit().commit();
            check(
                    controller.draft().equals(journal.getString("draft"))
                            && controller.snapshot().quote.equals(journal.getString("quote"))
                            && paths().equals(new ArrayList<>(original))
                            && controller.settings.voiceInput() == journal.getBoolean("voice"),
                    "process-death cleanup restores the original draft, quote, chips and input"
                            + " preference");
            if (!journal(context).delete())
                throw new IOException("Could not remove process-death journal");
        } else throw new IllegalArgumentException("Unknown process-death phase");
        result.putString(
                "result", "PASS " + assertions + " process-death assertions; phase=" + phase);
        return result;
    }
}
