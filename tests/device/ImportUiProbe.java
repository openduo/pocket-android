// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import dev.openduo.pocket.core.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Native composer lifecycle and optional real system picker/camera acceptance. */
final class ImportUiProbe {
    private final Probe probe;
    private final Bundle arguments;
    private int assertions;

    ImportUiProbe(Probe probe, Bundle arguments) {
        this.probe = probe;
        this.arguments = arguments;
    }

    private void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    private android.widget.EditText awaitEditor(
            java.util.concurrent.atomic.AtomicReference<Activity> current, String text)
            throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 3000;
        android.widget.EditText[] matched = {null};
        do {
            probe.runOnMainSync(
                    () -> {
                        android.widget.EditText input =
                                probe.visibleInput(current.get().getWindow().getDecorView());
                        if (input != null && input.getText().toString().equals(text))
                            matched[0] = input;
                    });
            if (matched[0] != null) return matched[0];
            Thread.sleep(20);
        } while (android.os.SystemClock.elapsedRealtime() < until);
        return null;
    }

    int run(
            Context context,
            java.util.concurrent.atomic.AtomicReference<Activity> current,
            PocketController controller,
            String realDraft,
            int realChips)
            throws Exception {
        java.io.File shared = new java.io.File(context.getCacheDir(), "shared");
        shared.mkdirs();
        java.io.File first = java.io.File.createTempFile("qa-share-", ".bin", shared);
        java.io.File second = java.io.File.createTempFile("qa-share-", ".bin", shared);
        java.io.File camera = null;
        try {
            java.nio.file.Files.write(
                    first.toPath(),
                    "first shared document".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(
                    second.toPath(),
                    "second shared document".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            android.net.Uri one =
                    dev.openduo.pocket.app.PocketFiles.uri(
                            context, first, "旅途 计划.txt", "text/plain");
            android.net.Uri two =
                    dev.openduo.pocket.app.PocketFiles.uri(
                            context, second, "camera notes.md", "text/markdown");
            int beforeCount = controller.snapshot().chips.size();
            String beforeText = controller.draft();
            probe.runOnMainSync(
                    () ->
                            probe.visibleInput(current.get().getWindow().getDecorView())
                                    .requestFocus());
            String afterFirst =
                    beforeText.isEmpty() ? "QA share one" : beforeText + "\nQA share one";
            Intent delivery =
                    new Intent(Intent.ACTION_SEND_MULTIPLE)
                            .setClassName(
                                    context.getPackageName(), "dev.openduo.pocket.app.MainActivity")
                            .setType("*/*")
                            .putExtra(Intent.EXTRA_TEXT, "  QA share one \n")
                            .putParcelableArrayListExtra(
                                    Intent.EXTRA_STREAM,
                                    new ArrayList<>(Arrays.asList(one, two, one)))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.ClipData clip = android.content.ClipData.newRawUri("same files", two);
            clip.addItem(new android.content.ClipData.Item(one));
            delivery.setClipData(clip);
            probe.runOnMainSync(() -> current.get().startActivity(delivery));
            long until = android.os.SystemClock.elapsedRealtime() + 5000;
            while ((controller.snapshot().chips.size() != beforeCount + 2
                            || !controller.draft().equals(afterFirst)
                            || controller.snapshot().chips.stream()
                                    .anyMatch(a -> a.state != Attachment.UPLOADED))
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    controller.draft().equals(afterFirst)
                            && controller.snapshot().chips.size() == beforeCount + 2
                            && controller.snapshot().chips.get(beforeCount).name.equals("旅途 计划.txt")
                            && controller
                                    .snapshot()
                                    .chips
                                    .get(beforeCount + 1)
                                    .name
                                    .equals("camera notes.md"),
                    "native multi-file share appends text once and deduplicates URI representations"
                            + " in order");
            List<String> committedPaths = new ArrayList<>();
            for (Attachment a : controller.snapshot().chips) committedPaths.add(a.localPath);
            probe.waitForIdleSync();
            android.widget.EditText sharedInput = awaitEditor(current, afterFirst);
            check(
                    sharedInput != null
                            && sharedInput.hasFocus()
                            && sharedInput.getText().toString().equals(afterFirst),
                    "shared text reaches the actual focused native editor instead of remaining only"
                            + " in stored state");
            probe.screenshot(context, "conversation-shared-files-en");
            android.graphics.Rect revealed = new android.graphics.Rect();
            probe.runOnMainSync(
                    () -> {
                        android.view.View remove =
                                probe.describedView(
                                        current.get().getWindow().getDecorView(),
                                        "Remove camera notes.md");
                        if (remove != null) remove.getGlobalVisibleRect(revealed);
                    });
            check(
                    revealed.width()
                            >= Math.round(40 * context.getResources().getDisplayMetrics().density),
                    "newly imported files automatically reveal the last chip and its removal target"
                            + " on the narrow viewport");
            android.widget.HorizontalScrollView strip =
                    horizontalScroll(current.get().getWindow().getDecorView());
            probe.runOnMainSync(() -> strip.scrollTo(strip.getChildAt(0).getWidth(), 0));
            probe.waitForIdleSync();
            android.graphics.Rect removeBounds = new android.graphics.Rect();
            probe.runOnMainSync(
                    () -> {
                        android.view.View remove =
                                probe.describedView(
                                        current.get().getWindow().getDecorView(),
                                        "Remove camera notes.md");
                        if (remove != null) remove.getGlobalVisibleRect(removeBounds);
                    });
            check(
                    strip != null
                            && removeBounds.width()
                                    >= Math.round(
                                            40
                                                    * context.getResources()
                                                            .getDisplayMetrics()
                                                            .density),
                    "horizontal attachment scrolling exposes the final removal control at a usable"
                            + " narrow-screen size");
            probe.screenshot(context, "conversation-shared-files-end-en");
            Activity before = current.get();
            probe.runOnMainSync(
                    () -> ((dev.openduo.pocket.app.MainActivity) before).navigate("settings"));
            probe.runOnMainSync(before::recreate);
            until = android.os.SystemClock.elapsedRealtime() + 5000;
            while (current.get() == before && android.os.SystemClock.elapsedRealtime() < until)
                Thread.sleep(20);
            probe.waitForIdleSync();
            Thread.sleep(250);
            List<String> recreatedPaths = new ArrayList<>();
            for (Attachment a : controller.snapshot().chips) recreatedPaths.add(a.localPath);
            check(
                    current.get() != before
                            && controller.draft().equals(afterFirst)
                            && recreatedPaths.equals(committedPaths),
                    "native Activity recreation reuses the share receipt without appending text or"
                            + " files again");
            check(
                    probe.textView(current.get().getWindow().getDecorView(), "Language") != null,
                    "recreating an already-presented share retains the selected settings page");
            probe.runOnMainSync(
                    () ->
                            ((dev.openduo.pocket.app.MainActivity) current.get())
                                    .navigate("conversation"));
            String afterEdit = afterFirst + " typed locally\nQA appended during edit";
            probe.runOnMainSync(
                    () -> {
                        android.widget.EditText editor =
                                probe.visibleInput(current.get().getWindow().getDecorView());
                        editor.requestFocus();
                        controller.appendDraft("QA appended during edit");
                        editor.append(" typed locally");
                    });
            until = android.os.SystemClock.elapsedRealtime() + 4000;
            while (!controller.draft().equals(afterEdit)
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            probe.waitForIdleSync();
            android.widget.EditText mergedEditor = awaitEditor(current, afterEdit);
            check(
                    controller.draft().equals(afterEdit)
                            && mergedEditor != null
                            && mergedEditor.getText().toString().equals(afterEdit),
                    "interleaved native typing and state-side content append retain both texts in"
                            + " the editor and saved draft");
            Intent textTwo =
                    new Intent(Intent.ACTION_SEND)
                            .setClassName(
                                    context.getPackageName(), "dev.openduo.pocket.app.MainActivity")
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "QA share two");
            Intent textThree =
                    new Intent(Intent.ACTION_SEND)
                            .setClassName(
                                    context.getPackageName(), "dev.openduo.pocket.app.MainActivity")
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "QA share three");
            probe.runOnMainSync(
                    () -> {
                        current.get().startActivity(textTwo);
                        current.get().startActivity(textThree);
                    });
            String rapidText = afterEdit + "\nQA share two\nQA share three";
            until = android.os.SystemClock.elapsedRealtime() + 4000;
            while (!controller.draft().equals(rapidText)
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    controller.draft().equals(rapidText),
                    "rapid native share intents append against serialized state instead of"
                            + " overwriting a stale draft");
            Intent selection = new Intent().setData(one);
            android.content.ClipData selected =
                    android.content.ClipData.newRawUri("picked documents", two);
            selected.addItem(new android.content.ClipData.Item(one));
            selection.setClipData(selected);
            java.lang.reflect.Method result =
                    dev.openduo.pocket.app.MainActivity.class.getDeclaredMethod(
                            "onActivityResult", int.class, int.class, Intent.class);
            result.setAccessible(true);
            probe.runOnMainSync(
                    () -> {
                        try {
                            externalField(
                                    current.get(),
                                    "pickerRoute",
                                    controller.importRoute(ImportRoute.Kind.FILES));
                            result.invoke(current.get(), 10, Activity.RESULT_OK, selection);
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                    });
            until = android.os.SystemClock.elapsedRealtime() + 4000;
            while (controller.snapshot().chips.size() != beforeCount + 4
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    controller.snapshot().chips.size() == beforeCount + 4
                            && controller
                                    .snapshot()
                                    .chips
                                    .get(beforeCount + 2)
                                    .name
                                    .equals("camera notes.md")
                            && controller
                                    .snapshot()
                                    .chips
                                    .get(beforeCount + 3)
                                    .name
                                    .equals("旅途 计划.txt"),
                    "picker-result callback keeps selection order and does not duplicate its data"
                            + " URI");
            java.io.File fixture = probe.imageFile(context);
            camera = java.io.File.createTempFile("photo-", ".jpg", shared);
            java.nio.file.Files.copy(
                    fixture.toPath(),
                    camera.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            fixture.delete();
            java.io.File cameraOriginal = camera;
            probe.runOnMainSync(
                    () -> {
                        try {
                            externalField(current.get(), "cameraFile", cameraOriginal);
                            externalField(
                                    current.get(),
                                    "photoRoute",
                                    controller.importRoute(ImportRoute.Kind.CAMERA));
                            externalField(current.get(), "photoStage", "camera");
                            result.invoke(current.get(), 11, Activity.RESULT_OK, null);
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                    });
            until = android.os.SystemClock.elapsedRealtime() + 4000;
            while ((controller.snapshot().chips.size() != beforeCount + 5
                            || cameraOriginal.exists())
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    controller.snapshot().chips.size() == beforeCount + 5
                            && !cameraOriginal.exists()
                            && new java.io.File(
                                            controller
                                                    .snapshot()
                                                    .chips
                                                    .get(beforeCount + 4)
                                                    .localPath)
                                    .isFile()
                            && controller
                                    .snapshot()
                                    .chips
                                    .get(beforeCount + 4)
                                    .mime
                                    .equals("image/jpeg"),
                    "camera-result callback deletes its original only after creating the durable"
                            + " draft copy");
            CountDownLatch copied = new CountDownLatch(2);
            String repeat = UUID.randomUUID().toString();
            controller.importContent(
                    repeat, "QA queued once", Collections.singletonList(one), copied::countDown);
            controller.importContent(
                    repeat, "QA queued once", Collections.singletonList(one), copied::countDown);
            check(
                    copied.await(4, TimeUnit.SECONDS)
                            && controller.snapshot().chips.size() == beforeCount + 6
                            && controller.draft().equals(rapidText + "\nQA queued once"),
                    "overlapping callbacks for one delivery copy and append once while releasing"
                            + " both readers");
            String removed = controller.snapshot().chips.get(beforeCount + 5).localPath;
            controller.removeAttachment(removed);
            until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (controller.snapshot().chips.size() != beforeCount + 5
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            Thread.sleep(150);
            check(
                    controller.snapshot().chips.size() == beforeCount + 5
                            && !new java.io.File(removed).exists(),
                    "removing an imported chip removes its copied file without upload-result"
                            + " resurrection");
            if (arguments != null && "1".equals(arguments.getString("importUi"))) {
                nativeImports(context, current, controller);
                assertions +=
                        new ImportCancellationProbe(probe, context, current, controller).run();
            }
            List<String> tryFiles = new ArrayList<>();
            for (Attachment a : controller.snapshot().chips) tryFiles.add(a.localPath);
            ImportRoute stalePicker = controller.importRoute(ImportRoute.Kind.FILES);
            controller.stopTrying();
            until = android.os.SystemClock.elapsedRealtime() + 4000;
            while (controller.snapshot().trying && android.os.SystemClock.elapsedRealtime() < until)
                Thread.sleep(20);
            CountDownLatch staleReturned = new CountDownLatch(1);
            controller.importContent(
                    stalePicker,
                    "stale picker text",
                    Collections.singletonList(one),
                    staleReturned::countDown);
            check(
                    staleReturned.await(2, TimeUnit.SECONDS)
                            && controller.draft().equals(realDraft)
                            && controller.snapshot().chips.size() == realChips,
                    "an external result from an ended try generation cannot add its files or text"
                            + " to the real composer");
            Activity inTry = current.get();
            probe.runOnMainSync(inTry::recreate);
            until = android.os.SystemClock.elapsedRealtime() + 5000;
            while (current.get() == inTry && android.os.SystemClock.elapsedRealtime() < until)
                Thread.sleep(20);
            probe.waitForIdleSync();
            Thread.sleep(250);
            check(
                    current.get() != inTry
                            && !controller.snapshot().trying
                            && controller.draft().equals(realDraft)
                            && controller.snapshot().chips.size() == realChips
                            && tryFiles.stream().noneMatch(path -> new java.io.File(path).exists()),
                    "try exit and Activity recreation discard imported bytes and do not replay the"
                            + " last share into the real draft");
        } finally {
            first.delete();
            second.delete();
            if (camera != null) camera.delete();
        }
        return assertions;
    }

    private void nativeImports(
            Context context,
            java.util.concurrent.atomic.AtomicReference<Activity> current,
            PocketController controller)
            throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info =
                probe.getUiAutomation().getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        probe.getUiAutomation().setServiceInfo(info);
        try {
            int before = controller.snapshot().chips.size();
            probe.runOnMainSync(
                    () ->
                            ((dev.openduo.pocket.app.MainActivity) current.get())
                                    .pickAttachment("*/*"));
            Thread.sleep(500);
            ImportRoute launchedPicker = (ImportRoute) externalField(current.get(), "pickerRoute");
            Activity beforePicker = current.get();
            probe.runOnMainSync(beforePicker::recreate);
            probe.waitForIdleSync();
            ImportRoute restoredPicker = (ImportRoute) externalField(current.get(), "pickerRoute");
            check(
                    launchedPicker.id.equals(restoredPicker.id)
                            && launchedPicker.target.equals(restoredPicker.target)
                            && restoredPicker.kind == ImportRoute.Kind.FILES,
                    "requesting Activity recreation while the real picker is open retains its"
                            + " delivery, room and file policy until the result resumes it");
            probe.screenshot(context, "native-file-picker");
            if (!clickNative(
                    node -> node.getText() != null && Documents.TITLE.contentEquals(node.getText()),
                    600)) {
                boolean drawer =
                        clickNative(
                                node -> {
                                    String id = node.getViewIdResourceName();
                                    CharSequence description = node.getContentDescription();
                                    return "android:id/home".equals(id)
                                            || description != null
                                                    && Arrays.asList(
                                                                    "Show roots",
                                                                    "显示根目录",
                                                                    "显示根目录列表",
                                                                    "Open navigation drawer")
                                                            .contains(description.toString());
                                },
                                2500);
                if (!drawer
                        || !clickNative(
                                node ->
                                        node.getText() != null
                                                && Documents.TITLE.contentEquals(node.getText()),
                                2500)) {
                    probe.screenshot(context, "native-file-picker-source-missing");
                    throw new AssertionError("Native document source is not reachable");
                }
            }
            probe.screenshot(context, "native-file-picker-document");
            boolean selectedDocument =
                    clickNative(
                            node ->
                                    node.getText() != null
                                            && Documents.NAME.contentEquals(node.getText()),
                            3000);
            if (!selectedDocument) {
                java.io.File directory = new java.io.File(context.getExternalCacheDir(), "qa");
                directory.mkdirs();
                try (java.io.PrintWriter writer =
                        new java.io.PrintWriter(
                                new java.io.File(directory, "native-document-tree.txt"), "UTF-8")) {
                    dumpNative(probe.getUiAutomation().getRootInActiveWindow(), writer, 0);
                }
            }
            check(
                    selectedDocument,
                    "the real system picker can select a document owned by a separate application"
                            + " UID");
            long until = android.os.SystemClock.elapsedRealtime() + 5000;
            while ((controller.snapshot().chips.size() != before + 1
                            || current.get() == beforePicker)
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            if (controller.snapshot().chips.size() != before + 1) {
                probe.screenshot(context, "native-file-picker-return-failed");
                throw new AssertionError(
                        "Native picker did not return an imported document: "
                                + controller.snapshot().lastError);
            }
            Attachment document = controller.snapshot().chips.get(before);
            check(
                    current.get() != beforePicker
                            && document.name.equals(Documents.NAME)
                            && document.mime.equals("text/plain")
                            && new String(
                                            java.nio.file.Files.readAllBytes(
                                                    new java.io.File(document.localPath).toPath()),
                                            java.nio.charset.StandardCharsets.UTF_8)
                                    .equals(Documents.CONTENT),
                    "a real picker result survives Android's deferred Activity recreation and"
                            + " copies the external stream and original name into the composer");
            until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (context.getContentResolver().getPersistedUriPermissions().stream()
                            .anyMatch(p -> Documents.AUTHORITY.equals(p.getUri().getAuthority()))
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            check(
                    context.getContentResolver().getPersistedUriPermissions().stream()
                            .noneMatch(p -> Documents.AUTHORITY.equals(p.getUri().getAuthority())),
                    "the document's persistable read grant is released after its app-owned copy"
                            + " completes");
            ImportRoute permissionRoute = controller.importRoute(ImportRoute.Kind.CAMERA);
            Activity beforePermission = current.get();
            probe.runOnMainSync(
                    () -> {
                        try {
                            externalField(beforePermission, "photoRoute", permissionRoute);
                            externalField(beforePermission, "photoStage", "permission");
                            externalField(beforePermission, "cameraFile", null);
                            externalField(beforePermission, "photoResumed", false);
                            beforePermission.recreate();
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                    });
            until = android.os.SystemClock.elapsedRealtime() + 4000;
            while (current.get() == beforePermission
                    && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            probe.waitForIdleSync();
            ImportRoute permissionRestored =
                    (ImportRoute) externalField(current.get(), "photoRoute");
            Object photoFile = externalField(current.get(), "cameraFile");
            check(
                    current.get() != beforePermission
                            && permissionRoute.id.equals(permissionRestored.id)
                            && permissionRoute.target.equals(permissionRestored.target)
                            && "camera".equals(externalField(current.get(), "photoStage"))
                            && photoFile instanceof java.io.File,
                    "restoring an interrupted photo permission stage after permission was granted"
                            + " opens the real camera with its original route");
            probe.runOnMainSync(
                    () ->
                            current.get()
                                    .onRequestPermissionsResult(
                                            13,
                                            new String[] {android.Manifest.permission.CAMERA},
                                            new int[] {
                                                android.content.pm.PackageManager.PERMISSION_GRANTED
                                            }));
            check(
                    photoFile.equals(externalField(current.get(), "cameraFile")),
                    "a late permission callback cannot launch a second camera or replace its output"
                            + " source");
            Thread.sleep(600);
            probe.screenshot(context, "native-camera-capture");
            android.view.accessibility.AccessibilityNodeInfo cameraRoot =
                    probe.getUiAutomation().getRootInActiveWindow();
            if (cameraRoot != null
                    && (!cameraRoot.findAccessibilityNodeInfosByText("位置信息").isEmpty()
                            || !cameraRoot.findAccessibilityNodeInfosByText("location").isEmpty())
                    && nativeNode(
                                    cameraRoot,
                                    node ->
                                            node.getViewIdResourceName() != null
                                                    && node.getViewIdResourceName()
                                                            .endsWith(":id/permission_deny_button"))
                            != null) {
                check(
                        clickNative(
                                node -> {
                                    String id = node.getViewIdResourceName();
                                    return id != null && id.endsWith(":id/permission_deny_button");
                                },
                                1500),
                        "native still-photo capture does not grant the external camera location"
                                + " access");
            }
            Thread.sleep(200);
            cameraRoot = probe.getUiAutomation().getRootInActiveWindow();
            if (cameraRoot != null
                    && !cameraRoot.findAccessibilityNodeInfosByText("开始拍照").isEmpty()) {
                if (nativeNode(cameraRoot, node -> node.isCheckable() && node.isChecked())
                        != null) {
                    check(
                            clickNative(node -> node.isCheckable() && node.isChecked(), 500),
                            "native camera first-run location tagging can be disabled before"
                                    + " capture");
                }
                check(
                        clickNative(
                                node ->
                                        node.getText() != null
                                                && "开始拍照".contentEquals(node.getText()),
                                1200),
                        "native camera first-run controls are reachable without enabling photo"
                                + " location tagging");
            }
            Thread.sleep(1500);
            probe.screenshot(context, "native-camera-ready");
            check(
                    clickNative(
                            node -> {
                                String id = node.getViewIdResourceName();
                                CharSequence description = node.getContentDescription();
                                return id != null && id.endsWith(":id/shutter_button")
                                        || description != null
                                                && Arrays.asList("Shutter", "拍照", "快门")
                                                        .contains(description.toString());
                            },
                            3500),
                    "the product photo action opens the real native camera shutter");
            until = android.os.SystemClock.elapsedRealtime() + 6000;
            boolean confirmed = false;
            while (controller.snapshot().chips.size() != before + 2
                    && android.os.SystemClock.elapsedRealtime() < until) {
                if (!confirmed)
                    confirmed =
                            clickNative(
                                    node -> {
                                        String id = node.getViewIdResourceName();
                                        CharSequence description = node.getContentDescription();
                                        return id != null && id.endsWith(":id/done_button")
                                                || description != null
                                                        && Arrays.asList("Done", "完成", "确定")
                                                                .contains(description.toString())
                                                || node.getText() != null
                                                        && Arrays.asList("Done", "完成", "确定")
                                                                .contains(
                                                                        node.getText().toString());
                                    },
                                    150);
                Thread.sleep(50);
            }
            if (controller.snapshot().chips.size() != before + 2) {
                probe.screenshot(context, "native-camera-confirm-failed");
                try (java.io.PrintWriter writer =
                        new java.io.PrintWriter(
                                new java.io.File(
                                        context.getExternalCacheDir(), "qa/native-camera-tree.txt"),
                                "UTF-8")) {
                    dumpNative(probe.getUiAutomation().getRootInActiveWindow(), writer, 0);
                }
            }
            android.graphics.BitmapFactory.Options photoBounds =
                    new android.graphics.BitmapFactory.Options();
            photoBounds.inJustDecodeBounds = true;
            if (controller.snapshot().chips.size() == before + 2)
                android.graphics.BitmapFactory.decodeFile(
                        controller.snapshot().chips.get(before + 1).localPath, photoBounds);
            check(
                    controller.snapshot().chips.size() == before + 2
                            && controller.snapshot().chips.get(before + 1).mime.equals("image/jpeg")
                            && photoBounds.outWidth > 0
                            && photoBounds.outHeight > 0
                            && new java.io.File(
                                                    controller
                                                            .snapshot()
                                                            .chips
                                                            .get(before + 1)
                                                            .localPath)
                                            .length()
                                    > 0,
                    "the real camera confirms a JPEG through its output URI and returns to the"
                            + " ordinary draft pipeline");
            probe.waitForIdleSync();
            probe.screenshot(context, "native-camera-return");
        } finally {
            for (int back = 0; back < 3; back++) {
                android.view.accessibility.AccessibilityNodeInfo active =
                        probe.getUiAutomation().getRootInActiveWindow();
                if (active != null
                        && active.getPackageName() != null
                        && (active.getPackageName().toString().equals("com.android.camera2")
                                || active.getPackageName()
                                        .toString()
                                        .equals("com.android.documentsui"))) {
                    probe.getUiAutomation()
                            .injectInputEvent(
                                    new android.view.KeyEvent(
                                            android.view.KeyEvent.ACTION_DOWN,
                                            android.view.KeyEvent.KEYCODE_BACK),
                                    true);
                    probe.getUiAutomation()
                            .injectInputEvent(
                                    new android.view.KeyEvent(
                                            android.view.KeyEvent.ACTION_UP,
                                            android.view.KeyEvent.KEYCODE_BACK),
                                    true);
                    Thread.sleep(100);
                } else break;
            }
            info.flags = originalFlags;
            probe.getUiAutomation().setServiceInfo(info);
        }
    }

    private static void externalField(Activity activity, String name, Object value)
            throws ReflectiveOperationException {
        java.lang.reflect.Field inputs = activity.getClass().getDeclaredField("inputs");
        inputs.setAccessible(true);
        Object external = inputs.get(activity);
        java.lang.reflect.Field field = external.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(external, value);
    }

    private static Object externalField(Activity activity, String name)
            throws ReflectiveOperationException {
        java.lang.reflect.Field inputs = activity.getClass().getDeclaredField("inputs");
        inputs.setAccessible(true);
        Object external = inputs.get(activity);
        java.lang.reflect.Field field = external.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(external);
    }

    private void dumpNative(
            android.view.accessibility.AccessibilityNodeInfo node,
            java.io.PrintWriter writer,
            int depth) {
        if (node == null) return;
        writer.println(
                depth
                        + " "
                        + node.getViewIdResourceName()
                        + " text="
                        + node.getText()
                        + " desc="
                        + node.getContentDescription()
                        + " click="
                        + node.isClickable()
                        + " enabled="
                        + node.isEnabled());
        for (int i = 0; i < node.getChildCount(); i++)
            dumpNative(node.getChild(i), writer, depth + 1);
    }

    private boolean clickNative(
            java.util.function.Predicate<android.view.accessibility.AccessibilityNodeInfo>
                    predicate,
            long timeout)
            throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        do {
            android.view.accessibility.AccessibilityNodeInfo root =
                    probe.getUiAutomation().getRootInActiveWindow();
            android.view.accessibility.AccessibilityNodeInfo match = nativeNode(root, predicate);
            if (match != null) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                match.getBoundsInScreen(bounds);
                for (int i = 0; i < 4 && match != null; i++, match = match.getParent()) {
                    if (match.isClickable()
                            && match.isEnabled()
                            && match.performAction(
                                    android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        return true;
                }
                if (!bounds.isEmpty()) {
                    long now = android.os.SystemClock.uptimeMillis();
                    android.view.MotionEvent down =
                            android.view.MotionEvent.obtain(
                                    now,
                                    now,
                                    android.view.MotionEvent.ACTION_DOWN,
                                    bounds.exactCenterX(),
                                    bounds.exactCenterY(),
                                    0);
                    android.view.MotionEvent up =
                            android.view.MotionEvent.obtain(
                                    now,
                                    now + 50,
                                    android.view.MotionEvent.ACTION_UP,
                                    bounds.exactCenterX(),
                                    bounds.exactCenterY(),
                                    0);
                    down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    try {
                        boolean started = probe.getUiAutomation().injectInputEvent(down, true);
                        return probe.getUiAutomation().injectInputEvent(up, true) && started;
                    } finally {
                        down.recycle();
                        up.recycle();
                    }
                }
            }
            Thread.sleep(30);
        } while (android.os.SystemClock.elapsedRealtime() < until);
        return false;
    }

    private android.view.accessibility.AccessibilityNodeInfo nativeNode(
            android.view.accessibility.AccessibilityNodeInfo root,
            java.util.function.Predicate<android.view.accessibility.AccessibilityNodeInfo>
                    predicate) {
        if (root == null) return null;
        if (root.isVisibleToUser() && root.isEnabled() && predicate.test(root)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child =
                    nativeNode(root.getChild(i), predicate);
            if (child != null) return child;
        }
        return null;
    }

    private android.widget.HorizontalScrollView horizontalScroll(android.view.View root) {
        if (!root.isShown()) return null;
        if (root instanceof android.widget.HorizontalScrollView)
            return (android.widget.HorizontalScrollView) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.HorizontalScrollView found = horizontalScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
