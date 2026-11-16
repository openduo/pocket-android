// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import dev.openduo.pocket.core.Attachment;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.ui.InsetsFrame;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;
import dev.openduo.pocket.ui.components.BottomNav;
import dev.openduo.pocket.ui.components.Sheets;
import dev.openduo.pocket.ui.pages.AmbientPage;
import dev.openduo.pocket.ui.pages.ConversationPage;
import dev.openduo.pocket.ui.pages.OnboardingPage;
import dev.openduo.pocket.ui.pages.PairingPage;
import dev.openduo.pocket.ui.pages.SettingsPage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Retained page views preserve reading positions, form input, focus and text drafts. */
public final class MainActivity extends Activity implements PageHost, PocketController.Listener {
    private static final int PERMISSION = 12;
    private Ui ui;
    private dev.openduo.pocket.ui.PageStates pageStates;
    private PocketController controller;
    private LinearLayout shell, navSlot;
    private FrameLayout pages;
    private final Map<String, View> views = new LinkedHashMap<>();
    private String page;
    private Runnable permissionAction;
    private Intent pendingIntent;
    private String deliveryId;
    private boolean pairIntentHandled, inboundPresented;
    private ExternalInputs inputs;
    private dev.openduo.pocket.app.input.InteractionDispatcher interaction;
    private dev.openduo.pocket.app.input.InputBindings inputBindings;
    private dev.openduo.pocket.app.input.HardwareInput hardwareInput;
    private final dev.openduo.pocket.ui.FocusNavigation focusNavigation =
            new dev.openduo.pocket.ui.FocusNavigation();
    private dev.openduo.pocket.ui.pages.InputSettingsPage inputSettings;
    private Dialog imagePreview;
    private boolean loginWaiting;
    private dev.openduo.pocket.ui.components.TailnetLoginSheet loginSheet;
    private dev.openduo.pocket.ui.pages.SessionsPage sessions;
    private dev.openduo.pocket.ui.pages.SessionsPage.State sessionsState;
    private dev.openduo.pocket.ui.pages.PassportPage passport;
    private dev.openduo.pocket.ui.pages.LocationPage location;
    private boolean keyboardVisible;
    private ConversationPage conversation;
    private AmbientPage ambient;
    private SettingsPage settings;
    private PairingPage pairing;
    private OnboardingPage onboarding;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(dev.openduo.pocket.text.AppText.context(base));
    }

    @Override
    public void onCreate(Bundle saved) {
        if (Ui.lightTheme(this)) setTheme(dev.openduo.pocket.R.style.PocketLightTheme);
        super.onCreate(saved);
        Object retained = getLastNonConfigurationInstance();
        if (retained instanceof dev.openduo.pocket.ui.pages.SessionsPage.State)
            sessionsState = (dev.openduo.pocket.ui.pages.SessionsPage.State) retained;
        ui = new Ui(this);
        pageStates =
                new dev.openduo.pocket.ui.PageStates(
                        saved == null ? null : saved.getBundle("uiPages"));
        ui.immersive();
        controller = PocketController.get(this);
        interaction = new dev.openduo.pocket.app.input.InteractionDispatcher(this, controller);
        inputBindings = new dev.openduo.pocket.app.input.InputBindings(this);
        hardwareInput = new dev.openduo.pocket.app.input.HardwareInput(inputBindings, interaction);
        page =
                saved == null
                        ? controller.configured() ? "conversation" : "onboarding"
                        : saved.getString("page", "conversation");
        deliveryId =
                saved == null
                        ? java.util.UUID.randomUUID().toString()
                        : saved.getString("deliveryId", java.util.UUID.randomUUID().toString());
        pairIntentHandled = saved != null && saved.getBoolean("pairIntentHandled");
        inboundPresented = saved != null && saved.getBoolean("inboundPresented");
        loginWaiting = saved != null && saved.getBoolean("loginWaiting");
        boolean rail = ui.metrics.navigationRail();
        shell = rail ? ui.row() : ui.column();
        shell.setBackgroundColor(ui.palette.background);
        pages = new FrameLayout(this);
        navSlot = ui.column();
        if (rail) {
            navSlot.setBackgroundColor(ui.palette.surface);
            shell.addView(
                    navSlot,
                    new LinearLayout.LayoutParams(ui.dp(ui.metrics.navigationRailWidthDp()), -1));
            shell.addView(pages, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            shell.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
            shell.addView(navSlot);
        }
        InsetsFrame frame = new InsetsFrame(ui);
        frame.keyboardListener(
                shown -> {
                    keyboardVisible = shown;
                    if (pairing != null) pairing.keyboard(shown);
                    if (location != null) location.keyboard(shown);
                    navSlot.setVisibility(
                            shown
                                            || ui.metrics.compactNavigation()
                                            || !page.matches("conversation|ambient|settings")
                                    ? View.GONE
                                    : View.VISIBLE);
                });
        frame.addView(shell);
        setContentView(frame);
        inputs = new ExternalInputs(this, controller, saved);
        controller.addListener(this);
        handleIntent(getIntent());
        changed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        controller.onForeground(true);
        if (location != null && page.equals("location")) location.visible();
        inputs.started();
        if (controller.settings.keepAwake())
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && ui != null) ui.immersive();
        if (!focused) cancelInputs();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (hardwareInput != null
                && hasWindowFocus()
                && hardwareInput.dispatch(event, getCurrentFocus())) return true;
        return super.dispatchKeyEvent(event);
    }

    private void cancelInputs() {
        if (interaction != null && interaction.navigating()) return;
        if (hardwareInput != null) hardwareInput.cancel();
        if (interaction != null) interaction.cancel();
    }

    @Override
    protected void onStop() {
        cancelInputs();
        inputs.stopped();
        if (pairing != null) pairing.hidden();
        if (conversation != null) conversation.hidden();
        if (location != null) location.hidden();
        if (!isChangingConfigurations()) controller.onForeground(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelInputs();
        if (inputSettings != null) inputSettings.close();
        if (loginSheet != null) loginSheet.close();
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
    public Object onRetainNonConfigurationInstance() {
        return sessions == null ? sessionsState : sessions.retain();
    }

    @Override
    public void changed() {
        inputs.changed();
        if (controller.snapshot() == null) {
            if (pages.getChildCount() == 0)
                pages.addView(ui.label(ui.t("正在读取对话…"), 16, ui.palette.secondary, false));
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
        if (loginWaiting) continueLogin(false);
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
    public dev.openduo.pocket.input.InputTarget input() {
        return interaction;
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
        focusNavigation.remember(page, views.get(page));
        cancelInputs();
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
            View pageView;
            switch (page) {
                case "input":
                    inputSettings =
                            new dev.openduo.pocket.ui.pages.InputSettingsPage(this, inputBindings);
                    pageView = inputSettings.render();
                    break;
                case "conversation":
                    conversation = new ConversationPage(this, controller);
                    pageView = conversation.render();
                    break;
                case "ambient":
                    ambient = new AmbientPage(this, controller);
                    pageView = ambient.render();
                    break;
                case "settings":
                    settings = new SettingsPage(this, controller);
                    pageView = settings.render();
                    break;
                case "location":
                    location = new dev.openduo.pocket.ui.pages.LocationPage(this, controller);
                    pageView = location.render();
                    break;
                case "passport":
                    passport = new dev.openduo.pocket.ui.pages.PassportPage(this, controller);
                    pageView = passport.render();
                    break;
                case "sessions":
                    sessions = new dev.openduo.pocket.ui.pages.SessionsPage(this, controller);
                    sessions.restore(sessionsState);
                    sessionsState = null;
                    pageView = sessions.render();
                    break;
                case "pairing":
                    pairing = new PairingPage(this, controller);
                    pageView = pairing.render();
                    break;
                case "onboarding":
                    onboarding = new OnboardingPage(this, controller);
                    pageView = onboarding.render();
                    break;
                default:
                    pageView = extraPage(page);
                    break;
            }
            selected = ui.adaptivePage(pageView);
            selected.setSaveFromParentEnabled(false);
            views.put(page, selected);
            pages.addView(selected, new FrameLayout.LayoutParams(-1, -1));
            pageStates.restore(page, selected);
        }
        for (Map.Entry<String, View> entry : views.entrySet())
            entry.getValue().setVisibility(entry.getKey().equals(page) ? View.VISIBLE : View.GONE);
        if (sessions != null && page.equals("sessions")) sessions.visible(true);
        if (location != null && page.equals("location")) location.visible();
        if (pairing != null && page.equals("pairing")) pairing.keyboard(keyboardVisible);
        if (location != null && page.equals("location")) location.keyboard(keyboardVisible);
        navSlot.removeAllViews();
        boolean navigation =
                page.equals("conversation") || page.equals("ambient") || page.equals("settings");
        navSlot.setVisibility(
                navigation && !keyboardVisible && !ui.metrics.compactNavigation()
                        ? View.VISIBLE
                        : View.GONE);
        if (navigation && !ui.metrics.compactNavigation())
            navSlot.addView(
                    new BottomNav(ui, this::navigate)
                            .render(page, ui.metrics.navigationRail(), ui.metrics.iconOnlyRail()),
                    new LinearLayout.LayoutParams(-1, ui.metrics.navigationRail() ? -1 : -2));
        ui.immersive();
        focusNavigation.restore(page, selected);
    }

    private View extraPage(String name) {
        LinearLayout body = ui.column();
        ui.inset(body, ui.pagePaddingDp(), 10);
        body.addView(ui.button(ui.t("返回"), ui.palette.secondary, 0, this::back));
        body.addView(
                ui.label(
                        name.equals("diagnostics") ? ui.t("诊断") : name, 22, ui.palette.text, true));
        if (name.equals("diagnostics")) {
            PocketController.Snapshot s = controller.snapshot();
            String report =
                    getString(dev.openduo.pocket.R.string.app_name)
                            + " "
                            + dev.openduo.pocket.BuildConfig.VERSION_NAME
                            + "\nAdapter: "
                            + dev.openduo.pocket.device.DeviceRuntime.current().id()
                            + "\nCapabilities: "
                            + dev.openduo.pocket.device.DeviceCapabilities.discover(this)
                            + "\nAndroid "
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
                            ui.t("分享诊断"),
                            ui.palette.accent,
                            ui.palette.raised,
                            () ->
                                    controller.exportDiagnostics(
                                            report,
                                            (file, mime) ->
                                                    shareFile(PocketFiles.uri(this, file), mime))),
                    20);
        } else
            ui.add(
                    body,
                    ui.label(ui.t("这项迁移仍在开发，功能不会以占位界面计入完成。"), 15, ui.palette.secondary, false),
                    20);
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
        for (Map.Entry<String, View> entry : views.entrySet())
            pageStates.capture(entry.getKey(), entry.getValue());
        saved.putBundle("uiPages", pageStates.save());
        saved.putString("page", page);
        inputs.save(saved);
        saved.putString("deliveryId", deliveryId);
        saved.putBoolean("pairIntentHandled", pairIntentHandled);
        saved.putBoolean("inboundPresented", inboundPresented);
        saved.putBoolean("loginWaiting", loginWaiting);
        super.onSaveInstanceState(saved);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        deliveryId = java.util.UUID.randomUUID().toString();
        pairIntentHandled = false;
        inboundPresented = false;
        pendingIntent = null;
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null
                && "duoduopocket".equals(intent.getData().getScheme())) {
            if (pairIntentHandled) return;
            if (controller.snapshot() == null) {
                pendingIntent = intent;
                return;
            }
            navigate("pairing");
            pairing.parseLink(intent.getDataString());
            pairIntentHandled = true;
            return;
        }
        if (!Intent.ACTION_SEND.equals(intent.getAction())
                && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) return;
        try {
            InboundContent content = InboundContent.share(intent);
            if (content.text.contains("duoduopocket://pair?")) {
                if (pairIntentHandled) return;
                if (controller.snapshot() == null) {
                    pendingIntent = intent;
                    return;
                }
                navigate("pairing");
                pairing.parseLink(content.text);
                pairIntentHandled = true;
                return;
            }
            if (!inboundPresented) {
                if (controller.snapshot() == null) page = "conversation";
                else navigate("conversation");
                inboundPresented = true;
            }
            controller.importContent(
                    deliveryId,
                    content.text,
                    content.files,
                    content.retainReads(getApplicationContext(), intent.getFlags()));
        } catch (RuntimeException failure) {
            toast(ui.t("无法导入文件，请检查文件大小与读取权限"));
        }
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
        if (inputs.permissionResult(request, grants)) return;
        if (request != PERMISSION) return;
        Runnable action = permissionAction;
        permissionAction = null;
        boolean ok = grants.length > 0;
        for (int grant : grants) ok &= grant == PackageManager.PERMISSION_GRANTED;
        if (ok) {
            if (action != null) action.run();
            // A recreated Activity must not revive a lambda that belongs to its old views.
            else {
                if (settings != null) settings.changed();
                toast(ui.t("权限已允许，请再次操作"));
            }
        } else
            Sheets.actions(
                    ui,
                    ui.t("需要权限才能使用此功能"),
                    new Sheets.Action(
                            ui.t("打开应用权限设置"),
                            () ->
                                    startActivity(
                                            new Intent(
                                                    android.provider.Settings
                                                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:" + getPackageName())))));
    }

    @Override
    public void pickAttachment(String mime) {
        inputs.pick(mime);
    }

    @Override
    public void takePhoto() {
        inputs.takePhoto();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        inputs.result(request, result, data);
    }

    @Override
    public void openAttachment(Attachment attachment) {
        if (attachment.sha256.isEmpty() && attachment.localPath.isEmpty()) {
            toast(ui.t("服务器未能取得这个附件"));
            return;
        }
        toast(ui.t("正在打开附件…"));
        controller.download(
                attachment,
                (file, mime) -> {
                    Uri uri = PocketFiles.uri(this, file, attachment.name, mime);
                    if (mime.startsWith("image/")) {
                        if (imagePreview != null) imagePreview.dismiss();
                        imagePreview =
                                dev.openduo.pocket.ui.components.ImagePreview.show(
                                        ui, file, () -> shareFile(uri, mime));
                    } else
                        try {
                            startActivity(
                                    readable(
                                            new Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(uri, mime),
                                            uri));
                        } catch (android.content.ActivityNotFoundException failure) {
                            shareFile(uri, mime);
                        }
                });
    }

    private void shareFile(Uri uri, String mime) {
        try {
            startActivity(
                    Intent.createChooser(
                            readable(
                                    new Intent(Intent.ACTION_SEND)
                                            .setType(mime)
                                            .putExtra(Intent.EXTRA_STREAM, uri),
                                    uri),
                            ui.t("分享附件")));
        } catch (android.content.ActivityNotFoundException failure) {
            toast(ui.t("没有可接收文件的应用"));
        }
    }

    private static Intent readable(Intent intent, Uri uri) {
        intent.setClipData(android.content.ClipData.newRawUri("Attachment", uri));
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Override
    public void loginTailnet() {
        loginWaiting = true;
        controller.loginTailnet();
        continueLogin(true);
    }

    private void continueLogin(boolean automatic) {
        if (loginSheet == null)
            loginSheet =
                    new dev.openduo.pocket.ui.components.TailnetLoginSheet(
                            this,
                            controller,
                            automatic,
                            () -> {
                                loginSheet = null;
                                loginWaiting = false;
                            });
        else loginSheet.update();
    }
}
