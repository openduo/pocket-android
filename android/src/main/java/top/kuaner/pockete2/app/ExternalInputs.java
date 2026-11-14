// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.app;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import org.json.JSONObject;

import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.components.Sheets;

import java.io.*;

/** Activity-owned external operations. Only values, never permission lambdas, cross recreation. */
final class ExternalInputs {
    private static final int PICK = 10, CAMERA = 11, PHOTO_PERMISSION = 13;
    private final PageHost host;
    private final PocketController controller;
    private ImportRoute pickerRoute, photoRoute;
    private String photoStage = "";
    private File cameraFile;
    private boolean photoResumed, active;

    ExternalInputs(PageHost host, PocketController controller, Bundle saved) {
        this.host = host;
        this.controller = controller;
        if (saved == null) return;
        try {
            if (saved.containsKey("external.picker"))
                pickerRoute = ImportRoute.from(new JSONObject(saved.getString("external.picker")));
            if (pickerRoute != null && pickerRoute.kind == ImportRoute.Kind.CAMERA)
                throw new IOException("Invalid picker route");
            if (saved.containsKey("external.photo")) {
                JSONObject photo = new JSONObject(saved.getString("external.photo"));
                photoRoute = ImportRoute.from(photo.getJSONObject("route"));
                if (photoRoute.kind != ImportRoute.Kind.CAMERA)
                    throw new IOException("Invalid photo route");
                photoStage = photo.getString("stage");
                if (!photoStage.matches("permission|camera|import"))
                    throw new IOException("Invalid photo stage");
                String path = photo.optString("file");
                if (!path.isEmpty()) {
                    cameraFile = new File(path);
                    validatePhoto(cameraFile);
                }
                if (!photoStage.equals("permission") && cameraFile == null)
                    throw new IOException("Missing photo source");
            }
        } catch (Exception invalid) {
            pickerRoute = null;
            clearPhoto();
        }
    }

    void save(Bundle saved) {
        if (pickerRoute != null) saved.putString("external.picker", pickerRoute.json().toString());
        if (photoRoute != null) {
            try {
                saved.putString(
                        "external.photo",
                        new JSONObject()
                                .put("route", photoRoute.json())
                                .put("stage", photoStage)
                                .put("file", cameraFile == null ? "" : cameraFile.getPath())
                                .toString());
            } catch (org.json.JSONException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    void started() {
        active = true;
        changed();
    }

    void stopped() {
        active = false;
    }

    void changed() {
        if (photoStage.equals("import") && cameraFile != null && !cameraFile.exists()) clearPhoto();
        PocketController.Snapshot snapshot = controller.snapshot();
        if (!active || photoRoute == null || snapshot == null) return;
        if (photoRoute.trying() && !photoRoute.visibleIn(snapshot.importTarget, snapshot.trying)) {
            discardPhoto();
            return;
        }
        if (photoStage.equals("permission")
                && host.activity().checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) launchCamera();
        else if (photoStage.equals("import") && !photoResumed) importPhoto();
    }

    void pick(String mime) {
        if (pickerRoute != null) return;
        pickerRoute =
                controller.importRoute(
                        mime.startsWith("image/")
                                ? ImportRoute.Kind.MEDIA
                                : ImportRoute.Kind.FILES);
        try {
            host.activity()
                    .startActivityForResult(
                            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .setType(mime)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .addFlags(
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                            PICK);
        } catch (ActivityNotFoundException failure) {
            pickerRoute = null;
            host.toast(host.ui().t("没有文件选择器，可从其他应用分享到 Pocket E2"));
        }
    }

    void takePhoto() {
        if (photoRoute != null && !photoStage.equals("import")) return;
        photoRoute = controller.importRoute(ImportRoute.Kind.CAMERA);
        photoStage = "permission";
        cameraFile = null;
        photoResumed = false;
        if (host.activity().checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) launchCamera();
        else
            host.activity()
                    .requestPermissions(
                            new String[] {Manifest.permission.CAMERA}, PHOTO_PERMISSION);
    }

    boolean permissionResult(int request, int[] grants) {
        if (request != PHOTO_PERMISSION) return false;
        if (photoRoute == null || !photoStage.equals("permission")) return true;
        boolean granted = grants.length > 0;
        for (int result : grants) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted) launchCamera();
        else {
            clearPhoto();
            Sheets.actions(
                    host.ui(),
                    host.ui().t("需要权限才能使用此功能"),
                    new Sheets.Action(
                            host.ui().t("打开应用权限设置"),
                            () ->
                                    host.activity()
                                            .startActivity(
                                                    new Intent(
                                                            android.provider.Settings
                                                                    .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse(
                                                                    "package:"
                                                                            + host.activity()
                                                                                    .getPackageName())))));
        }
        return true;
    }

    private void launchCamera() {
        if (photoRoute == null || !photoStage.equals("permission")) return;
        try {
            File dir = new File(host.activity().getCacheDir(), "shared");
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new IOException("Cannot create photo directory");
            cameraFile = File.createTempFile("photo-", ".jpg", dir);
            photoStage = "camera";
            Uri uri = PocketFiles.uri(host.activity(), cameraFile);
            Intent camera =
                    new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            .addFlags(
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("Photo", uri));
            host.activity().startActivityForResult(camera, CAMERA);
        } catch (ActivityNotFoundException failure) {
            discardPhoto();
            host.toast(host.ui().t("这台设备没有可用的相机应用"));
        } catch (IOException failure) {
            discardPhoto();
            host.toast(host.ui().t("无法创建照片文件"));
        } catch (SecurityException failure) {
            discardPhoto();
            host.toast(host.ui().t("无法启动相机，请检查相机权限"));
        }
    }

    void result(int request, int result, Intent data) {
        if (request == PICK) {
            ImportRoute route = pickerRoute;
            pickerRoute = null;
            if (result != Activity.RESULT_OK || data == null) return;
            if (route == null) {
                host.toast(host.ui().t("文件选择请求已失效，请重新选择"));
                return;
            }
            try {
                InboundContent content = InboundContent.picked(data);
                controller.importContent(
                        route,
                        "",
                        content.files,
                        content.retainReads(
                                host.activity().getApplicationContext(), data.getFlags()));
                host.navigate("conversation");
            } catch (RuntimeException failure) {
                host.toast(host.ui().t("无法导入文件，请检查文件大小与读取权限"));
            }
        } else if (request == CAMERA
                && photoRoute != null
                && cameraFile != null
                && photoStage.equals("camera")) {
            if (result == Activity.RESULT_OK && cameraFile.length() > 0) {
                photoStage = "import";
                importPhoto();
                host.navigate("conversation");
            } else discardPhoto();
        }
    }

    private void importPhoto() {
        photoResumed = true;
        controller.importPhoto(photoRoute, cameraFile);
    }

    private void validatePhoto(File file) throws IOException {
        File root = new File(host.activity().getCacheDir(), "shared").getCanonicalFile();
        if (!root.equals(file.getCanonicalFile().getParentFile()))
            throw new IOException("Invalid saved photo source");
        ImportBatch.requirePhotoName(file.getName());
    }

    private void discardPhoto() {
        if (cameraFile != null) cameraFile.delete();
        clearPhoto();
    }

    private void clearPhoto() {
        photoRoute = null;
        cameraFile = null;
        photoStage = "";
        photoResumed = false;
    }
}
