// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.ui.pages;

import android.app.Dialog;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;

import dev.openduo.pocket.app.input.InputBindings;
import dev.openduo.pocket.input.InputAction;
import dev.openduo.pocket.ui.PageHost;
import dev.openduo.pocket.ui.Ui;
import dev.openduo.pocket.ui.components.Sheets;

/** Learn only events the current window receives; remotes keep navigation throughout setup. */
public final class InputSettingsPage {
    private final PageHost host;
    private final Ui ui;
    private final InputBindings bindings;
    private LinearLayout rows;
    private Dialog dialog;

    public InputSettingsPage(PageHost host, InputBindings bindings) {
        this.host = host;
        this.ui = host.ui();
        this.bindings = bindings;
    }

    public View render() {
        LinearLayout root = ui.column();
        ui.inset(root, ui.pagePaddingDp(), 10);
        root.addView(ui.button(ui.t("返回"), ui.palette.secondary, 0, host::back));
        ui.add(root, ui.label(ui.t("按键与遥控器"), 22, ui.palette.text, true), 10);
        ui.add(
                root,
                ui.label(
                        ui.t("方向键移动焦点，确认键激活，返回键返回。实体键仅在 App 位于前台时生效。"),
                        14,
                        ui.palette.secondary,
                        false),
                12);
        ui.add(
                root,
                ui.button(ui.t("添加或修改按键"), ui.palette.onAccent, ui.palette.accent, this::learn),
                16);
        rows = ui.column();
        ui.add(root, rows, 16);
        refresh();
        return ui.scroll(root);
    }

    private void refresh() {
        rows.removeAllViews();
        rows.addView(ui.label(ui.t("设备默认"), 15, ui.palette.text, true));
        for (InputBindings.Binding binding : bindings.defaults()) {
            ui.add(
                    rows,
                    ui.label(
                            keyName(binding.key, binding.scan) + " · " + label(ui, binding.action),
                            14,
                            ui.palette.secondary,
                            false),
                    8);
        }
        ui.add(rows, ui.label(ui.t("自定义按键"), 15, ui.palette.text, true), 20);
        if (bindings.all().isEmpty())
            ui.add(
                    rows,
                    ui.label(ui.t("尚未设置。添加按键后，将覆盖该输入设备的默认动作。"), 14, ui.palette.secondary, false),
                    8);
        for (InputBindings.Binding binding : bindings.all()) {
            String title = keyName(binding.key, binding.scan) + " · " + label(ui, binding.action);
            if (!binding.device.isEmpty()) title += "\n" + binding.device;
            final String heading = title;
            ui.add(
                    rows,
                    ui.button(
                            title,
                            ui.palette.text,
                            ui.palette.raised,
                            () -> {
                                dialog =
                                        Sheets.actions(
                                                ui,
                                                heading,
                                                new Sheets.Action(ui.t("重新分配"), this::learn),
                                                new Sheets.Action(
                                                        ui.t("移除自定义映射"),
                                                        () -> {
                                                            bindings.remove(binding.id);
                                                            refresh();
                                                        }));
                            }),
                    8);
        }
    }

    private void learn() {
        LinearLayout body = ui.column();
        ui.add(
                body,
                ui.label(ui.t("按一下要分配的实体键。方向、确认、返回、音量及系统键保留原有用途。"), 15, ui.palette.text, false),
                12);
        Dialog learning = Sheets.content(ui, ui.t("等待按键"), body);
        dialog = learning;
        KeyEvent[] pressed = {null};
        learning.setOnKeyListener(
                (window, code, event) -> {
                    if (InputBindings.reserved(event) || !event.hasNoModifiers()) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (pressed[0] == null && event.getRepeatCount() == 0)
                            pressed[0] = new KeyEvent(event);
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP
                            && pressed[0] != null
                            && event.getKeyCode() == pressed[0].getKeyCode()
                            && event.getDeviceId() == pressed[0].getDeviceId()) {
                        KeyEvent key = pressed[0];
                        pressed[0] = null;
                        if (!event.isCanceled()) {
                            learning.dismiss();
                            choose(key);
                        }
                        return true;
                    }
                    return false;
                });
    }

    private void choose(KeyEvent key) {
        java.util.List<Sheets.Action> actions = new java.util.ArrayList<>();
        for (InputAction action : InputAction.values())
            actions.add(
                    new Sheets.Action(
                            label(ui, action),
                            () -> {
                                bindings.save(key, action);
                                refresh();
                            }));
        actions.add(
                new Sheets.Action(
                        ui.t("恢复设备默认"),
                        () -> {
                            bindings.remove(InputBindings.id(key));
                            refresh();
                        }));
        dialog =
                Sheets.actions(
                        ui,
                        keyName(key.getKeyCode(), key.getScanCode()),
                        actions.toArray(new Sheets.Action[0]));
    }

    private String keyName(int key, int scan) {
        if (key == KeyEvent.KEYCODE_UNKNOWN) return ui.format("扫描码 %d", scan);
        return KeyEvent.keyCodeToString(key).replace("KEYCODE_", "");
    }

    public static String label(Ui ui, InputAction action) {
        switch (action) {
            case HOLD_TO_TALK:
                return ui.t("按住录音，松开发送");
            case TOGGLE_VOICE:
                return ui.t("开始／发送语音");
            case SEND_VOICE:
                return ui.t("发送录音");
            case CANCEL_OR_STOP:
                return ui.t("取消录音／停止播报");
            case TOGGLE_AMBIENT:
                return ui.t("开启／关闭环境模式");
            case MUTE_AMBIENT:
                return ui.t("静音／取消静音");
            case READ_LATEST:
                return ui.t("朗读最新回复");
            case CONVERSATION:
                return ui.t("打开对话");
            case SETTINGS:
                return ui.t("打开设置");
            case DISABLED:
                return ui.t("不执行动作");
            default:
                throw new AssertionError(action);
        }
    }

    public void close() {
        if (dialog != null) dialog.dismiss();
    }
}
