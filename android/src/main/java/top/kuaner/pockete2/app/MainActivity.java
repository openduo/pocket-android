// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import top.kuaner.pockete2.core.Attachment;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.ui.InsetsFrame;
import top.kuaner.pockete2.ui.PageHost;
import top.kuaner.pockete2.ui.Ui;
import top.kuaner.pockete2.ui.components.BottomNav;
import top.kuaner.pockete2.ui.components.Sheets;
import top.kuaner.pockete2.ui.pages.AmbientPage;
import top.kuaner.pockete2.ui.pages.ConversationPage;
import top.kuaner.pockete2.ui.pages.OnboardingPage;
import top.kuaner.pockete2.ui.pages.PairingPage;
import top.kuaner.pockete2.ui.pages.SettingsPage;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Retained page views preserve reading positions, form input, focus and text drafts. */
public final class MainActivity extends Activity implements PageHost, PocketController.Listener {
    private static final int PICK = 10, CAMERA = 11, PERMISSION = 12;
    private Ui ui;
    private PocketController controller;
    private LinearLayout shell, navSlot;
    private FrameLayout pages;
    private final Map<String, View> views = new LinkedHashMap<>();
    private String page;
    private Runnable permissionAction;
    private Intent pendingIntent;
    private File cameraFile;
    private Dialog imagePreview;
    private boolean loginWaiting;
    private top.kuaner.pockete2.ui.pages.SessionsPage sessions;
    private top.kuaner.pockete2.ui.pages.PassportPage passport;
    private top.kuaner.pockete2.ui.pages.LocationPage location;
    private boolean keyboardVisible;
    private ConversationPage conversation;
    private AmbientPage ambient;
    private SettingsPage settings;
    private PairingPage pairing;
    private OnboardingPage onboarding;

    @Override
    public void onCreate(Bundle saved) {
        if (Ui.lightTheme(this)) setTheme(top.kuaner.pockete2.R.style.PocketLightTheme);
        super.onCreate(saved);
        ui = new Ui(this);
        ui.immersive();
        controller = PocketController.get(this);
        page =
                saved == null
                        ? controller.configured() ? "conversation" : "onboarding"
                        : saved.getString("page", "conversation");
        if (saved != null && saved.containsKey("camera"))
            cameraFile = new File(saved.getString("camera"));
        loginWaiting = saved != null && saved.getBoolean("loginWaiting");
        shell = ui.column();
        shell.setBackgroundColor(ui.palette.background);
        pages = new FrameLayout(this);
        shell.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        navSlot = ui.column();
        shell.addView(navSlot);
        InsetsFrame frame = new InsetsFrame(ui);
        frame.keyboardListener(
                shown -> {
                    keyboardVisible = shown;
                    navSlot.setVisibility(
                            shown || !page.matches("conversation|ambient|settings")
                                    ? View.GONE
                                    : View.VISIBLE);
                });
        frame.addView(shell);
        setContentView(frame);
        controller.addListener(this);
        pendingIntent = getIntent();
        changed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.onForeground(true);
        if (controller.settings.keepAwake())
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onStop() {
        if (pairing != null) pairing.hidden();
        if (conversation != null) conversation.hidden();
        if (location != null) location.hidden();
        if (!isChangingConfigurations()) controller.onForeground(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (imagePreview != null) imagePreview.dismiss();
        controller.removeListener(this);
        if (conversation != null) conversation.close();
        if (ambient != null) ambient.close();
        if (settings != null) settings.close();
        if (pairing != null) pairing.close();
        if (onboarding != null) onboarding.close();
        if (sessions != null) sessions.close();
        if (passport != null) passport.close();
        if (location != null) location.close();
        super.onDestroy();
    }

    @Override
    public void changed() {
        if (controller.snapshot() == null) {
            if (pages.getChildCount() == 0)
                pages.addView(ui.label("正在读取对话…", 16, ui.palette.secondary, false));
            return;
        }
        if (views.isEmpty()) {
            pages.removeAllViews();
            showPage();
        }
        if (pendingIntent != null) {
            Intent intent = pendingIntent;
            pendingIntent = null;
            handleIntent(intent);
        }
        if (loginWaiting) continueLogin();
    }

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public Ui ui() {
        return ui;
    }

    @Override
    public void toast(String value) {
        ui.toast(value);
    }

    @Override
    public void back() {
        onBackPressed();
    }

    @Override
    public void navigate(String destination) {
        if (destination == null || destination.equals(page) && views.containsKey(destination))
            return;
        if (conversation != null) conversation.hidden();
        if (sessions != null && page.equals("sessions")) sessions.visible(false);
        if (location != null && page.equals("location")) location.hidden();
        if (pairing != null && page.equals("pairing")) pairing.hidden();
        page = destination;
        showPage();
    }

    private void showPage() {
        View selected = views.get(page);
        if (selected == null) {
            switch (page) {
                case "conversation":
                    conversation = new ConversationPage(this, controller);
                    selected = conversation.render();
                    break;
                case "ambient":
                    ambient = new AmbientPage(this, controller);
                    selected = ambient.render();
                    break;
                case "settings":
                    settings = new SettingsPage(this, controller);
                    selected = settings.render();
                    break;
                case "location":
                    location = new top.kuaner.pockete2.ui.pages.LocationPage(this, controller);
                    selected = location.render();
                    break;
                case "passport":
                    passport = new top.kuaner.pockete2.ui.pages.PassportPage(this, controller);
                    selected = passport.render();
                    break;
                case "sessions":
                    sessions = new top.kuaner.pockete2.ui.pages.SessionsPage(this, controller);
                    selected = sessions.render();
                    break;
                case "pairing":
                    pairing = new PairingPage(this, controller);
                    selected = pairing.render();
                    break;
                case "onboarding":
                    onboarding = new OnboardingPage(this, controller);
                    selected = onboarding.render();
                    break;
                default:
                    selected = extraPage(page);
                    break;
            }
            views.put(page, selected);
            pages.addView(selected, new FrameLayout.LayoutParams(-1, -1));
        }
        for (Map.Entry<String, View> entry : views.entrySet())
            entry.getValue().setVisibility(entry.getKey().equals(page) ? View.VISIBLE : View.GONE);
        if (sessions != null && page.equals("sessions")) sessions.visible(true);
        navSlot.removeAllViews();
        boolean navigation =
                page.equals("conversation") || page.equals("ambient") || page.equals("settings");
        navSlot.setVisibility(navigation && !keyboardVisible ? View.VISIBLE : View.GONE);
        if (navigation)
            navSlot.addView(
                    new BottomNav(ui, this::navigate).render(page),
                    new LinearLayout.LayoutParams(-1, -2));
        ui.immersive();
    }

    private View extraPage(String name) {
        LinearLayout body = ui.column();
        ui.inset(body, 16, 10);
        body.addView(ui.button("返回", ui.palette.secondary, 0, this::back));
        body.addView(ui.label(name.equals("diagnostics") ? "诊断" : name, 22, ui.palette.text, true));
        if (name.equals("diagnostics")) {
            PocketController.Snapshot s = controller.snapshot();
            String report =
                    "Pocket E2 0.1.0\nAndroid "
                            + android.os.Build.VERSION.RELEASE
                            + " / API "
                            + android.os.Build.VERSION.SDK_INT
                            + "\n"
                            + getResources().getDisplayMetrics().widthPixels
                            + " × "
                            + getResources().getDisplayMetrics().heightPixels
                            + " px\nTailscale: "
                            + s.tailnetState
                            + "\nConnection: "
                            + s.connection
                            + "\nAmbient: "
                            + s.ambient
                            + "\nCached rows: "
                            + s.rows.size()
                            + "\nPending: "
                            + s.outbox.size()
                            + "\nError: "
                            + s.lastError;
            ui.add(body, ui.label(report, 13, ui.palette.secondary, false), 16);
            ui.add(
                    body,
                    ui.button(
                            "分享诊断",
                            ui.palette.accent,
                            ui.palette.raised,
                            () ->
                                    controller.exportDiagnostics(
                                            report,
                                            (file, mime) ->
                                                    shareFile(PocketFiles.uri(this, file), mime))),
                    20);
        } else
            ui.add(body, ui.label("这项迁移仍在开发，功能不会以占位界面计入完成。", 15, ui.palette.secondary, false), 20);
        return ui.scroll(body);
    }

    @Override
    public void onBackPressed() {
        if (page.equals("conversation") || page.equals("onboarding")) super.onBackPressed();
        else
            navigate(
                    controller.snapshot().trying || controller.configured()
                            ? "conversation"
                            : "onboarding");
    }

    @Override
    protected void onSaveInstanceState(Bundle saved) {
        saved.putString("page", page);
        if (cameraFile != null) saved.putString("camera", cameraFile.getPath());
        saved.putBoolean("loginWaiting", loginWaiting);
        super.onSaveInstanceState(saved);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (controller.snapshot() == null) pendingIntent = intent;
        else handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null
                && "duoduopocket".equals(intent.getData().getScheme())) {
            navigate("pairing");
            pairing.parseLink(intent.getDataString());
            return;
        }
        if (!Intent.ACTION_SEND.equals(intent.getAction())
                && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) return;
        navigate("conversation");
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null && text.toString().contains("duoduopocket://pair?")) {
            navigate("pairing");
            pairing.parseLink(text.toString());
            return;
        }
        if (text != null) {
            String draft = controller.draft();
            controller.draft(draft.isEmpty() ? text.toString() : draft + "\n" + text);
            controller.setVoiceInput(false);
        }
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) controller.importAttachment(uri);
        ArrayList<Uri> multiple = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (multiple != null) for (Uri item : multiple) controller.importAttachment(item);
    }

    @Override
    public void permission(String[] names, Runnable granted) {
        ArrayList<String> missing = new ArrayList<>();
        for (String name : names)
            if (checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED) missing.add(name);
        if (missing.isEmpty()) {
            granted.run();
            return;
        }
        permissionAction = granted;
        requestPermissions(missing.toArray(new String[0]), PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] names, int[] grants) {
        super.onRequestPermissionsResult(request, names, grants);
        if (request != PERMISSION) return;
        Runnable action = permissionAction;
        permissionAction = null;
        boolean ok = grants.length > 0;
        for (int grant : grants) ok &= grant == PackageManager.PERMISSION_GRANTED;
        if (ok && action != null) action.run();
        else
            Sheets.actions(
                    ui,
                    "需要权限才能使用此功能",
                    new Sheets.Action(
                            "打开应用权限设置",
                            () ->
                                    startActivity(
                                            new Intent(
                                                    android.provider.Settings
                                                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:" + getPackageName())))));
    }

    @Override
    public void pickAttachment(String mime) {
        try {
            startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType(mime)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                    PICK);
        } catch (android.content.ActivityNotFoundException failure) {
            toast("没有文件选择器，可从其他应用分享到 Pocket E2");
        }
    }

    @Override
    public void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "shared");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException();
            cameraFile = File.createTempFile("photo-", ".jpg", dir);
            Uri uri = PocketFiles.uri(this, cameraFile);
            Intent camera =
                    new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                            .addFlags(
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            camera.setClipData(android.content.ClipData.newRawUri("Photo", uri));
            startActivityForResult(camera, CAMERA);
        } catch (android.content.ActivityNotFoundException failure) {
            if (cameraFile != null) cameraFile.delete();
            toast("这台设备没有可用的相机应用");
        } catch (java.io.IOException failure) {
            toast("无法创建照片文件");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK && data != null) {
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    controller.importAttachment(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) controller.importAttachment(data.getData());
        }
        if (request == CAMERA && cameraFile != null) {
            if (result == RESULT_OK && cameraFile.length() > 0) {
                controller.importAttachment(PocketFiles.uri(this, cameraFile));
                navigate("conversation");
            } else cameraFile.delete();
            cameraFile = null;
        }
    }

    @Override
    public void openAttachment(Attachment attachment) {
        if (attachment.sha256.isEmpty() && attachment.localPath.isEmpty()) {
            toast("服务器未能取得这个附件");
            return;
        }
        toast("正在打开附件…");
        controller.download(
                attachment,
                (file, mime) -> {
                    Uri uri = PocketFiles.uri(this, file);
                    if (mime.startsWith("image/")) {
                        if (imagePreview != null) imagePreview.dismiss();
                        imagePreview =
                                top.kuaner.pockete2.ui.components.ImagePreview.show(
                                        ui, file, () -> shareFile(uri, mime));
                    } else
                        try {
                            startActivity(
                                    new Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, mime)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                        } catch (android.content.ActivityNotFoundException failure) {
                            shareFile(uri, mime);
                        }
                });
    }

    private void shareFile(Uri uri, String mime) {
        try {
            startActivity(
                    Intent.createChooser(
                            new Intent(Intent.ACTION_SEND)
                                    .setType(mime)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "分享附件"));
        } catch (android.content.ActivityNotFoundException failure) {
            toast("没有可接收文件的应用");
        }
    }

    @Override
    public void loginTailnet() {
        loginWaiting = true;
        controller.start();
        controller.refreshTailnet();
        continueLogin();
        if (loginWaiting) toast("正在获取 Tailscale 登录地址…");
    }

    private void continueLogin() {
        PocketController.Snapshot s = controller.snapshot();
        if (s != null && s.tailnetState.equals("Failed")) {
            loginWaiting = false;
            toast("无法启动 Tailscale，请在设置中查看连接状态");
            return;
        }
        if (s != null && s.tailnetState.equals("Running")) {
            loginWaiting = false;
            toast("Tailscale 已连接，节点 pocket-e2");
            return;
        }
        if (s == null || s.authUrl.isEmpty()) return;
        loginWaiting = false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s.authUrl)));
        } catch (android.content.ActivityNotFoundException failure) {
            ui.copy("Tailscale 登录链接", s.authUrl);
        }
    }
}
