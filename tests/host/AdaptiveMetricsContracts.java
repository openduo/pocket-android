// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import top.kuaner.pockete2.ui.AdaptiveMetrics;

/** Pure host checks for the window-size and typography policy used by every native page. */
final class AdaptiveMetricsContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() {
        check(
                AdaptiveMetrics.classifyWidth(320) == AdaptiveMetrics.WidthClass.COMPACT
                        && AdaptiveMetrics.classifyWidth(599) == AdaptiveMetrics.WidthClass.COMPACT,
                "phone windows use the compact width class up to 599dp");
        check(
                AdaptiveMetrics.classifyWidth(600) == AdaptiveMetrics.WidthClass.MEDIUM
                        && AdaptiveMetrics.classifyWidth(839) == AdaptiveMetrics.WidthClass.MEDIUM,
                "tablet windows use the medium width class from 600dp");
        check(
                AdaptiveMetrics.classifyWidth(840) == AdaptiveMetrics.WidthClass.EXPANDED,
                "wide windows use the expanded width class from 840dp");

        AdaptiveMetrics phone = AdaptiveMetrics.of(320, 800, 1f);
        AdaptiveMetrics medium = AdaptiveMetrics.of(720, 900, 1f);
        AdaptiveMetrics wide = AdaptiveMetrics.of(1440, 900, 1f);
        AdaptiveMetrics tiny = AdaptiveMetrics.of(64, 240, 1f);
        check(
                phone.contentMaxWidthDp() == 320 && phone.pagePaddingDp() > 0,
                "compact content remains full width with positive breathing room");
        check(
                medium.contentMaxWidthDp() <= 640 && wide.contentMaxWidthDp() == 720,
                "medium and expanded content columns have bounded reading widths");
        check(
                medium.sheetMaxWidthDp() <= 720
                        && wide.sheetMaxWidthDp() == 720
                        && medium.sheetMaxHeightDp() <= 900,
                "sheets stay bounded on wide windows");
        check(
                phone.minTouchTargetDp() == 48
                        && medium.minTouchTargetDp() == 48
                        && wide.minTouchTargetDp() == 48,
                "primary controls retain the Android 48dp touch target");

        AdaptiveMetrics largeText = AdaptiveMetrics.of(360, 800, 1.5f);
        check(
                largeText.largeText()
                        && largeText.rowMinHeightDp() > phone.rowMinHeightDp()
                        && largeText.controlHeightDp() > phone.controlHeightDp(),
                "large text raises row and control heights");
        AdaptiveMetrics shortWindow = AdaptiveMetrics.of(360, 300, 1f);
        check(
                shortWindow.sheetMaxHeightDp() <= shortWindow.heightDp()
                        && shortWindow.controlHeightDp() <= 48
                        && shortWindow.viewfinderHeightDp() <= shortWindow.heightDp() / 2,
                "short windows do not receive controls or sheets taller than the viewport");

        for (AdaptiveMetrics metrics :
                new AdaptiveMetrics[] {phone, medium, wide, shortWindow, tiny}) {
            check(
                    metrics.qrMaxWidthDp() <= metrics.widthDp(),
                    "QR content fits the current window width");
            check(
                    metrics.inlineImageWidthDp() <= metrics.widthDp() - metrics.pagePaddingDp() * 2,
                    "inline media fits the page content column");
            check(
                    metrics.attachmentNameMaxDp()
                            <= metrics.widthDp() - metrics.pagePaddingDp() * 2,
                    "attachment labels stay bounded by the page column");
        }
        check(
                AdaptiveMetrics.of(-10, -10, Float.NaN).fontScale() == 1f,
                "invalid configuration values are normalised safely");
        return checked;
    }
}
