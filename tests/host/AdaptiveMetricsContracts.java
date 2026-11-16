// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.ui.AdaptiveMetrics;

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
        AdaptiveMetrics speaker = AdaptiveMetrics.of(259, 610, 1f);
        check(
                speaker.compactNavigation()
                        && !phone.compactNavigation()
                        && !speaker.navigationRail(),
                "very narrow windows use page navigation without a persistent bar");
        check(
                !phone.navigationRail()
                        && !medium.navigationRail()
                        && wide.navigationRail()
                        && AdaptiveMetrics.of(740, 360, 1f).navigationRail(),
                "navigation moves to a rail for wide or short landscape windows");
        check(
                AdaptiveMetrics.of(740, 360, 1f).iconOnlyRail()
                        && AdaptiveMetrics.of(1280, 800, 2f).iconOnlyRail()
                        && !wide.iconOnlyRail()
                        && wide.navigationRailWidthDp()
                                > AdaptiveMetrics.of(740, 360, 1f).navigationRailWidthDp(),
                "short or large-text rails keep icon targets without clipped labels");
        check(
                wide.twoPaneWorkspace()
                        && !medium.twoPaneWorkspace()
                        && !AdaptiveMetrics.of(1100, 460, 1f).twoPaneWorkspace()
                        && !AdaptiveMetrics.of(1180, 900, 1.8f).twoPaneWorkspace()
                        && AdaptiveMetrics.of(1280, 800, 2f).twoPaneWorkspace(),
                "workspace panes require usable width and height at the current text scale");
        check(
                AdaptiveMetrics.of(740, 360, 1f).twoPaneOnboarding()
                        && AdaptiveMetrics.of(600, 320, 1f).twoPaneOnboarding()
                        && AdaptiveMetrics.of(840, 600, 1f).twoPaneOnboarding()
                        && !AdaptiveMetrics.of(600, 960, 1f).twoPaneOnboarding()
                        && !AdaptiveMetrics.of(259, 545, 1f).twoPaneOnboarding(),
                "onboarding uses adjacent choices when two readable columns fit, including short"
                    + " landscape");
        check(
                AdaptiveMetrics.of(840, 600, 1f).twoPanePairing()
                        && !AdaptiveMetrics.of(740, 360, 1f).twoPanePairing()
                        && AdaptiveMetrics.of(961, 541, 1f).twoPaneSettings()
                        && AdaptiveMetrics.of(840, 600, 1f).twoPaneSettings()
                        && !AdaptiveMetrics.of(600, 960, 1f).twoPaneSettings(),
                "pairing and settings use their own form footprints after shared navigation");
        check(
                AdaptiveMetrics.of(961, 541, 1f).contentWidthDp(true) == 809
                        && AdaptiveMetrics.of(961, 541, 1f).contentWidthDp(false) == 897
                        && !AdaptiveMetrics.of(961, 541, 1f).twoPaneWorkspace()
                        && AdaptiveMetrics.of(1000, 600, 1f).twoPaneWorkspace(),
                "workspace decisions account for rail and margins rather than device identity");
        check(
                wide.ambientControlWidthDp() <= 440
                        && AdaptiveMetrics.of(1280, 800, 2f).ambientControlWidthDp() < 440,
                "ambient status keeps a bounded pane while conversation uses the remainder");
        check(
                phone.messageMaxWidthDp() <= phone.widthDp() - 2 * phone.pagePaddingDp()
                        && phone.pagePaddingDp() > 0,
                "compact messages fit inside the available page width");
        check(
                medium.messageMaxWidthDp() < wide.messageMaxWidthDp()
                        && wide.messageMaxWidthDp() == 760,
                "long messages keep readable line lengths as the page grows");
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
