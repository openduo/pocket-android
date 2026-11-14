// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import top.kuaner.pockete2.app.PocketFiles;
import top.kuaner.pockete2.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Separate-UID stalled pipes and provider opens, through the normal controller and native UI. */
final class ImportCancellationProbe {
    private final Probe probe;
    private final Context context;
    private final AtomicReference<Activity> current;
    private final PocketController controller;
    private int assertions;

    ImportCancellationProbe(
            Probe probe,
            Context context,
            AtomicReference<Activity> current,
            PocketController controller) {
        this.probe = probe;
        this.context = context;
        this.current = current;
        this.controller = controller;
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private void await(java.util.function.BooleanSupplier condition, long timeout)
            throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(20);
    }

    private Uri uri(String id) {
        return DocumentsContract.buildDocumentUri(Documents.AUTHORITY, id);
    }

    private int handles() {
        try (android.database.Cursor cursor =
                context.getContentResolver()
                        .query(
                                uri("status"),
                                new String[] {DocumentsContract.Document.COLUMN_SIZE},
                                null,
                                null,
                                null)) {
            if (cursor == null || !cursor.moveToFirst())
                throw new AssertionError("Missing provider status");
            return cursor.getInt(0);
        }
    }

    int run() throws Exception {
        probe.runOnMainSync(
                () ->
                        current.get()
                                .startActivity(
                                        new android.content.Intent()
                                                .setClassName(
                                                        "top.kuaner.pockete2.qa",
                                                        "top.kuaner.pockete2.qa.ProviderWarmup")));
        probe.waitForIdleSync();
        await(
                () -> {
                    android.view.accessibility.AccessibilityNodeInfo root =
                            probe.getUiAutomation().getRootInActiveWindow();
                    return root != null
                            && context.getPackageName().contentEquals(root.getPackageName());
                },
                3000);
        check(
                handles() == 0,
                "the external provider grants fresh fixture reads after instrumentation startup");
        File shared = new File(context.getCacheDir(), "shared");
        shared.mkdirs();
        File document = File.createTempFile("qa-after-cancel-", ".txt", shared);
        ImportRoute blocked = controller.importRoute(ImportRoute.Kind.FILES);
        CountDownLatch copied = new CountDownLatch(1);
        try {
            String draft = controller.draft();
            int chips = controller.snapshot().chips.size();
            Files.write(
                    document.toPath(),
                    "The next import must remain readable.".getBytes(StandardCharsets.UTF_8));
            controller.importContent(
                    blocked,
                    "must never enter the draft",
                    Collections.singletonList(uri("blocked")),
                    copied::countDown);
            await(
                    () ->
                            controller.snapshot().imports.stream()
                                    .anyMatch(p -> p.id.equals(blocked.id) && p.bytes >= 8192),
                    4000);
            check(
                    controller.snapshot().imports.stream()
                                    .anyMatch(
                                            p ->
                                                    p.id.equals(blocked.id)
                                                            && p.bytes == 8192
                                                            && p.total == 1)
                            && handles() == 1
                            && copied.getCount() == 1,
                    "actual bytes arrive from a separate UID before its provider pipe deliberately"
                            + " stalls without EOF: active="
                            + handles()
                            + ", callbacks="
                            + copied.getCount()
                            + ", progress="
                            + controller.snapshot().imports.stream()
                                    .map(p -> p.index + "/" + p.total + ":" + p.bytes)
                                    .collect(java.util.stream.Collectors.joining(",")));
            controller.sendDraft();
            await(() -> controller.snapshot().lastError.equals("请等文件导入完成，或取消导入后再发送"), 1000);
            check(
                    controller.draft().equals(draft)
                            && controller.snapshot().chips.size() == chips
                            && controller.snapshot().lastError.equals("请等文件导入完成，或取消导入后再发送"),
                    "send cannot silently omit an unfinished import or clear the user's existing"
                            + " composer");
            Activity before = current.get();
            probe.runOnMainSync(before::recreate);
            await(() -> current.get() != before, 4000);
            probe.waitForIdleSync();
            check(
                    current.get() != before
                            && controller.snapshot().imports.stream()
                                    .anyMatch(p -> p.id.equals(blocked.id)),
                    "Activity replacement retains the same running import and delivery identity");
            probe.runOnMainSync(
                    () -> {
                        android.widget.EditText input =
                                probe.visibleInput(current.get().getWindow().getDecorView());
                        input.requestFocus();
                        ((android.view.inputmethod.InputMethodManager)
                                        context.getSystemService(Context.INPUT_METHOD_SERVICE))
                                .showSoftInput(
                                        input,
                                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    });
            Thread.sleep(300);
            android.graphics.Rect bounds = new android.graphics.Rect();
            probe.runOnMainSync(
                    () -> {
                        android.view.View cancel =
                                probe.describedView(
                                        current.get().getWindow().getDecorView(), "Cancel import");
                        if (cancel != null) cancel.getGlobalVisibleRect(bounds);
                    });
            check(
                    bounds.width()
                                    >= Math.round(
                                            40 * context.getResources().getDisplayMetrics().density)
                            && bounds.height()
                                    >= Math.round(
                                            40
                                                    * context.getResources()
                                                            .getDisplayMetrics()
                                                            .density),
                    "the stalled import's cancel action stays visible and usable above the real"
                            + " keyboard at the actual narrow viewport");
            probe.screenshot(context, "conversation-import-blocked-en");
            long cancelledAt = android.os.SystemClock.elapsedRealtime();
            probe.runOnMainSync(
                    () ->
                            probe.describedView(
                                            current.get().getWindow().getDecorView(),
                                            "Cancel import")
                                    .performClick());
            check(
                    copied.await(2, TimeUnit.SECONDS)
                            && android.os.SystemClock.elapsedRealtime() - cancelledAt < 2000,
                    "the native cancel control releases a genuinely stalled provider read without"
                            + " waiting for the writer's timeout");
            await(() -> handles() == 0 && controller.snapshot().imports.isEmpty(), 1500);
            File inbox = new File(context.getCacheDir(), "try-inbox/" + blocked.id);
            await(() -> !inbox.exists() && !new File(inbox.getPath() + ".partial").exists(), 1500);
            check(
                    handles() == 0
                            && controller.snapshot().imports.isEmpty()
                            && controller.draft().equals(draft)
                            && controller.snapshot().chips.size() == chips
                            && !inbox.exists()
                            && !new File(inbox.getPath() + ".partial").exists(),
                    "cancellation closes the provider descriptor and removes partial bytes without"
                            + " admitting any batch text or chips");
            CountDownLatch repeated = new CountDownLatch(1);
            controller.importContent(
                    blocked,
                    "cancelled replay",
                    Collections.singletonList(uri("blocked")),
                    repeated::countDown);
            check(
                    repeated.await(1, TimeUnit.SECONDS)
                            && handles() == 0
                            && controller.draft().equals(draft),
                    "repeating a cancelled delivery cannot reopen its provider or replay its"
                            + " content");

            Uri normal = PocketFiles.uri(context, document, "after cancellation.txt", "text/plain");
            ImportRoute opening = controller.importRoute(ImportRoute.Kind.FILES);
            CountDownLatch opened = new CountDownLatch(1);
            controller.importContent(
                    opening,
                    "cancelled open",
                    Arrays.asList(normal, uri("slow-open")),
                    opened::countDown);
            await(() -> handles() == 1, 3000);
            check(
                    handles() == 1 && opened.getCount() == 1,
                    "the external provider can stall opening a second file after the first file"
                            + " has been copied");
            await(
                    () ->
                            controller.snapshot().imports.stream()
                                    .anyMatch(
                                            p ->
                                                    p.id.equals(opening.id)
                                                            && p.index == 1
                                                            && p.total == 2
                                                            && p.bytes == 0
                                                            && p.name.equals("slow-open.txt")),
                    1000);
            check(
                    controller.snapshot().imports.stream()
                            .anyMatch(
                                    p ->
                                            p.id.equals(opening.id)
                                                    && p.index == 1
                                                    && p.total == 2
                                                    && p.bytes == 0
                                                    && p.name.equals("slow-open.txt")),
                    "coalesced progress describes the current file without mixing the previous"
                            + " file's name or byte count into its index");
            controller.cancelImport(opening.id);
            check(
                    opened.await(2, TimeUnit.SECONDS)
                            && handles() == 0
                            && controller.draft().equals(draft)
                            && controller.snapshot().chips.size() == chips
                            && !new File(context.getCacheDir(), "try-inbox/" + opening.id).exists()
                            && !new File(
                                            context.getCacheDir(),
                                            "try-inbox/" + opening.id + ".partial")
                                    .exists(),
                    "the cancellation signal also reaches an unfinished remote provider open off"
                            + " the state thread: active="
                            + handles()
                            + ", callbacks="
                            + opened.getCount());

            String rejected = UUID.randomUUID().toString();
            CountDownLatch rejectedDone = new CountDownLatch(1);
            controller.importContent(
                    rejected,
                    "failed batch text",
                    Arrays.asList(normal, uri("rejected")),
                    rejectedDone::countDown);
            check(
                    rejectedDone.await(3, TimeUnit.SECONDS)
                            && controller.draft().equals(draft)
                            && controller.snapshot().chips.size() == chips,
                    "a second-file provider failure rolls back the first copied file and the entire"
                            + " batch's text");
            CountDownLatch next = new CountDownLatch(1);
            controller.importContent(
                    UUID.randomUUID().toString(),
                    "QA after cancellation",
                    Collections.singletonList(normal),
                    next::countDown);
            check(
                    next.await(3, TimeUnit.SECONDS)
                            && controller.snapshot().chips.size() == chips + 1
                            && controller.draft().equals(draft + "\nQA after cancellation"),
                    "a normal delivery succeeds on the same bounded import queue after both forms"
                            + " of cancellation");
        } finally {
            controller.cancelImport(blocked.id);
            document.delete();
        }
        return assertions;
    }
}
