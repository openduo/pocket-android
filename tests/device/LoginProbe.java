// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.*;
import android.graphics.*;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.pairing.QrCode;

/** Actual tsnet status and rendered login QR; does not authorize or change room credentials. */
final class LoginProbe {
    private final Probe probe;

    LoginProbe(Probe probe) {
        this.probe = probe;
    }

    int run(Context context) throws Exception {
        Activity activity =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        PocketController controller = PocketController.get(context);
        probe.runOnMainSync(() -> ((MainActivity) activity).loginTailnet());
        long until = android.os.SystemClock.elapsedRealtime() + 25000;
        while ((controller.snapshot() == null || controller.snapshot().authUrl.isEmpty())
                && (controller.snapshot() == null
                        || !controller.snapshot().tailnetState.equals("Running"))
                && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        PocketController.Snapshot snapshot = controller.snapshot();
        if (snapshot == null
                || !snapshot.tailnetState.equals("Running") && snapshot.authUrl.isEmpty())
            throw new AssertionError(
                    "Actual Tailscale node produced no login URL; backend="
                            + (snapshot == null ? "unloaded" : snapshot.tailnetState));
        probe.waitForIdleSync();
        probe.screenshot(context, "tailscale-login");
        if (snapshot.tailnetState.equals("Running")) return 1;
        Bitmap screen = probe.getUiAutomation().takeScreenshot();
        try {
            int width = screen.getWidth(), height = screen.getHeight();
            int[] pixels = new int[width * height];
            screen.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] luminance = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int rgb = pixels[i];
                luminance[i] =
                        (byte)
                                ((Color.red(rgb) * 77
                                                + Color.green(rgb) * 150
                                                + Color.blue(rgb) * 29)
                                        >> 8);
            }
            if (!snapshot.authUrl.equals(QrCode.read(luminance, width, height)))
                throw new AssertionError(
                        "The visible login QR must decode to the actual node authorization URL at"
                                + " the current viewport");
        } finally {
            screen.recycle();
        }
        return 2;
    }
}
