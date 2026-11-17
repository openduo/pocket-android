// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui.pages;

import android.graphics.Rect;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;

/** A pairing link is authoritative for host, TLS, port and opaque code. No fixed code length. */
public final class PairingPage implements PocketController.Listener {
    private final PageHost host;
    private final PocketController controller;
    private final Ui ui;
    private LinearLayout root;
    private EditText address, port, code, link;
    private Switch tls;
    private TextView error, commit;
    private View footer;
    private boolean pairing;
    private boolean keyboardVisible;
    private android.app.Dialog scanner;

    public PairingPage(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
        ui = host.ui();
    }

    public View render() {
        if (root != null) return root;
        root = ui.column();
        LinearLayout header = ui.row();
        header.addView(ui.iconButton("chevron_left", ui.t("返回"), ui.palette.text, host::back));
        header.addView(ui.label(ui.t("连接房间"), 18, ui.palette.text, true));
        root.addView(header);
        LinearLayout body = ui.column();
        ui.inset(body, ui.pagePaddingDp(), 10);
        LinearLayout intro = ui.column();
        boolean narrow = ui.metrics.narrowWidth();
        ui.add(intro, ui.label(ui.t("配对链接"), 12, ui.palette.subtle, false), 0);
        LinearLayout linkRow = ui.row();
        link = ui.input(ui.t("配对链接"), "", false);
        link.setId(dev.openduo.pocket.R.id.pair_link);
        linkRow.addView(link, new LinearLayout.LayoutParams(0, -2, 1));
        Runnable scan =
                () ->
                        host.permission(
                                new String[] {android.Manifest.permission.CAMERA},
                                () -> {
                                    hidden();
                                    ui.hideKeyboard(link);
                                    scanner =
                                            dev.openduo.pocket.ui.components.QrScanner.show(
                                                    ui, this::parseLink);
                                });
        Runnable read = () -> parseLink(link.getText().toString());
        if (narrow) {
            linkRow.addView(ui.iconButton("camera", ui.t("扫描房间二维码"), ui.palette.accent, scan));
            linkRow.addView(ui.iconButton("chevron_right", ui.t("读取链接"), ui.palette.accent, read));
        }
        ui.add(intro, linkRow, 6);
        if (!narrow) {
            LinearLayout linkActions = ui.row();
            linkActions.addView(
                    ui.button(ui.t("扫描房间二维码"), ui.palette.accent, ui.palette.raised, scan),
                    new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1);
            rp.leftMargin = ui.dp(8);
            linkActions.addView(
                    ui.button(ui.t("读取链接"), ui.palette.accent, ui.palette.raised, read), rp);
            ui.add(intro, linkActions, 8);
        }
        TextView explanation =
                ui.label(
                        narrow ? ui.t("链接可从房间管理页获取") : ui.t("在房间管理页生成配对链接，粘贴到这里。房间和设备凭据会由配对结果保存。"),
                        13,
                        ui.palette.secondary,
                        false);
        ui.add(intro, explanation, narrow ? 6 : 10);
        address = field(body, ui.t("主机地址"), controller.settings.host());
        address.setId(dev.openduo.pocket.R.id.pair_host);
        LinearLayout advanced = ui.row();
        port = ui.input(ui.t("端口"), String.valueOf(controller.settings.port()), false);
        port.setId(dev.openduo.pocket.R.id.pair_port);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        advanced.addView(port, new LinearLayout.LayoutParams(0, -2, 1));
        tls = new Switch(ui.context);
        tls.setId(dev.openduo.pocket.R.id.pair_tls);
        tls.setText(narrow ? "TLS" : "HTTPS");
        tls.setContentDescription("HTTPS");
        tls.setTextColor(ui.palette.text);
        tls.setChecked(controller.settings.tls());
        tls.setPadding(ui.dp(12), 0, 0, 0);
        advanced.addView(tls, new LinearLayout.LayoutParams(0, ui.controlHeight(), 1));
        ui.add(body, advanced, 8);
        code = field(body, ui.t("配对码"), "");
        code.setId(dev.openduo.pocket.R.id.pair_code);
        code.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        ui.add(
                body,
                ui.button(ui.t("登录 Tailscale"), ui.palette.secondary, 0, host::loginTailnet),
                12);
        LinearLayout actions = ui.column();
        ui.inset(actions, ui.pagePaddingDp(), 4);
        error = ui.label(ui.t("配对需要先登录同一个 Tailscale 网络。"), 12, ui.palette.secondary, false);
        actions.addView(error);
        commit = ui.button(ui.t("配对并连接"), ui.palette.onAccent, ui.palette.accent, this::pair);
        ui.add(actions, commit, 4);
        View form = ui.scroll(body);
        form.setId(dev.openduo.pocket.R.id.page_scroll);
        if (ui.metrics.twoPanePairing()) {
            body.addView(actions);
            LinearLayout panes = ui.row();
            ui.inset(intro, ui.pagePaddingDp(), 20);
            panes.addView(ui.scroll(intro), new LinearLayout.LayoutParams(0, -1, 1));
            panes.addView(form, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            body.addView(intro, 0);
            root.addView(form, new LinearLayout.LayoutParams(-1, 0, 1));
            footer = ui.scrollFooter(actions);
            root.addView(footer);
        }
        root.getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        () -> {
                            if (!keyboardVisible || !root.isShown()) return;
                            View focused = root.findFocus();
                            if (focused instanceof EditText) {
                                Rect field =
                                        new Rect(
                                                0,
                                                0,
                                                focused.getWidth(),
                                                focused.getHeight() + ui.dp(12));
                                focused.requestRectangleOnScreen(field, true);
                            }
                        });
        controller.addListener(this);
        return root;
    }

    private EditText field(LinearLayout parent, String hint, String text) {
        ui.add(parent, ui.label(hint, 12, ui.palette.subtle, false), 12);
        EditText input = ui.input(hint, text, false);
        ui.add(parent, input, 6);
        return input;
    }

    public void parseLink(String value) {
        render();
        try {
            Endpoint.PairingLink p = Endpoint.PairingLink.parse(value);
            address.setText(p.endpoint.host);
            port.setText(String.valueOf(p.endpoint.port));
            tls.setChecked(p.endpoint.tls);
            code.setText(p.code);
            link.setText(value);
            error.setText(ui.t("链接已读取，点按「配对并连接」继续。"));
        } catch (IllegalArgumentException failure) {
            error.setText(ui.explain(failure.getMessage()));
        }
    }

    private void pair() {
        if (pairing) return;
        String hostValue = address.getText().toString().trim(),
                pairCode = code.getText().toString().trim();
        if (pairCode.isEmpty()) {
            error.setText(ui.t("请填写配对码，或先读取配对链接"));
            return;
        }
        int portValue;
        try {
            portValue = Integer.parseInt(port.getText().toString().trim());
            new Endpoint(hostValue, portValue, tls.isChecked());
        } catch (IllegalArgumentException failure) {
            error.setText(ui.t("请检查主机和端口（1–65535）"));
            return;
        }
        pairing = true;
        commit.setEnabled(false);
        commit.setText(ui.t("正在配对…"));
        error.setText(ui.t("正在连接房间，请稍候…"));
        ui.hideKeyboard(code);
        controller.pair(
                hostValue,
                portValue,
                tls.isChecked(),
                "",
                "",
                pairCode,
                () -> {
                    pairing = false;
                    commit.setEnabled(true);
                    commit.setText(ui.t("配对并连接"));
                    PocketController.Snapshot s = controller.snapshot();
                    if (s != null && s.lastError.isEmpty() && controller.configured())
                        host.navigate("conversation");
                    else if (s != null) error.setText(ui.explain(s.lastError));
                });
    }

    @Override
    public void changed() {
        if (root != null && pairing) {
            PocketController.Snapshot s = controller.snapshot();
            if (s != null && !s.lastError.isEmpty()) error.setText(ui.explain(s.lastError));
        }
    }

    public void close() {
        hidden();
        controller.removeListener(this);
    }

    public void hidden() {
        if (scanner != null) {
            scanner.dismiss();
            scanner = null;
        }
    }

    public void keyboard(boolean visible) {
        keyboardVisible = visible;
        if (footer != null) footer.setVisibility(visible ? View.GONE : View.VISIBLE);
    }
}
