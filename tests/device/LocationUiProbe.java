// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.location.LocationManager;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import top.kuaner.pockete2.app.MainActivity;
import top.kuaner.pockete2.core.PocketController;
import top.kuaner.pockete2.text.AppText;

import java.util.function.BooleanSupplier;

/** Real narrow-screen coordinate selection and a hardware-dependent no-provider fallback. */
final class LocationUiProbe {
    private final Probe probe;
    private int assertions;

    LocationUiProbe(Probe probe) {
        this.probe = probe;
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 3000;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(20);
    }

    int run(Context context, Activity screen, PocketController controller) throws Exception {
        check(
                controller.snapshot().trying,
                "location UI acceptance stays inside the disposable room");
        String draft = controller.draft(), quote = controller.snapshot().quote;
        boolean voice = controller.snapshot().voiceInput;
        int pending = controller.snapshot().outbox.size();
        LocationManager location = context.getSystemService(LocationManager.class);
        android.os.Bundle sources = new android.os.Bundle();
        sources.putString(
                "checkpoint",
                "location-providers="
                        + String.join(",", location.getAllProviders())
                        + "; enabled="
                        + String.join(",", location.getProviders(true)));
        probe.sendStatus(0, sources);
        try {
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("location"));
            probe.waitForIdleSync();
            EditText[] query = {null};
            View[] use = {null};
            probe.runOnMainSync(
                    () -> {
                        View root = screen.getWindow().getDecorView();
                        query[0] = probe.visibleInput(root);
                        use[0] = probe.textView(root, AppText.get(context).t("使用这个位置"));
                    });
            check(
                    query[0] != null && use[0] != null && !use[0].isEnabled(),
                    "the real location page starts with an explicit unselected pin");
            probe.runOnMainSync(
                    () -> {
                        query[0].setText("12.500000, 45.250000");
                        query[0].onEditorAction(EditorInfo.IME_ACTION_SEARCH);
                    });
            probe.waitForIdleSync();
            Rect button = new Rect(), viewport = new Rect();
            probe.runOnMainSync(
                    () -> {
                        screen.getWindow().getDecorView().getWindowVisibleDisplayFrame(viewport);
                        use[0].getGlobalVisibleRect(button);
                    });
            check(
                    use[0].isEnabled() && button.height() >= 48 && viewport.contains(button),
                    "the actual compact viewport keeps the selected-location action fully"
                            + " reachable");
            if (location.getProviders(true).stream()
                    .allMatch(
                            provider ->
                                    provider.equals(LocationManager.PASSIVE_PROVIDER)
                                            || provider.equals("fused"))) {
                probe.runOnMainSync(
                        () ->
                                probe.textView(
                                                screen.getWindow().getDecorView(),
                                                AppText.get(context).t("当前位置"))
                                        .performClick());
                probe.waitForIdleSync();
                String fallback =
                        location.getAllProviders().stream()
                                        .allMatch(
                                                provider ->
                                                        provider.equals(
                                                                        LocationManager
                                                                                .PASSIVE_PROVIDER)
                                                                || provider.equals("fused"))
                                ? "这台设备没有可用的定位来源，可在地图选点或输入坐标"
                                : "定位未开启，可在地图选点或搜索坐标";
                check(
                        probe.textView(
                                                screen.getWindow().getDecorView(),
                                                AppText.get(context).t(fallback))
                                        != null
                                && use[0].isEnabled(),
                        "the real E2 without an enabled location source explains the fallback and"
                            + " retains the selected coordinates");
            }
            probe.screenshot(context, "location-coordinate-fallback");
            probe.runOnMainSync(use[0]::performClick);
            await(() -> controller.draft().contains("12.500000, 45.250000"));
            check(
                    controller.draft().startsWith(draft)
                            && controller.draft().contains("12.500000, 45.250000")
                            && controller.snapshot().quote.equals(quote)
                            && controller.snapshot().outbox.size() == pending,
                    "location insertion appends to the existing composer without losing quote or"
                            + " sending a message");
            return assertions;
        } finally {
            controller.draft(draft);
            controller.setVoiceInput(voice);
            await(() -> controller.draft().equals(draft));
            probe.runOnMainSync(() -> ((MainActivity) screen).navigate("conversation"));
        }
    }
}
