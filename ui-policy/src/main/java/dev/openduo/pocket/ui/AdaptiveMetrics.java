// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui;

/**
 * Window-size and typography policy for the UI layer.
 *
 * <p>This class deliberately has no Android or controller dependency. Pages consume named design
 * tokens through {@code Ui}; they do not decide layout from a physical screen or a business state.
 * Window-size classes choose content spacing and pane footprints.
 */
public final class AdaptiveMetrics {
    public enum WidthClass {
        COMPACT,
        MEDIUM,
        EXPANDED
    }

    private final int widthDp;
    private final int heightDp;
    private final float fontScale;
    private final WidthClass widthClass;

    private AdaptiveMetrics(int widthDp, int heightDp, float fontScale) {
        this.widthDp = Math.max(1, widthDp);
        this.heightDp = Math.max(1, heightDp);
        this.fontScale =
                Float.isNaN(fontScale) || Float.isInfinite(fontScale)
                        ? 1f
                        : Math.max(0.85f, Math.min(2.0f, fontScale));
        widthClass = classifyWidth(this.widthDp);
    }

    public static AdaptiveMetrics of(int widthDp, int heightDp, float fontScale) {
        return new AdaptiveMetrics(widthDp, heightDp, fontScale);
    }

    public static WidthClass classifyWidth(int widthDp) {
        if (widthDp < 600) return WidthClass.COMPACT;
        if (widthDp < 840) return WidthClass.MEDIUM;
        return WidthClass.EXPANDED;
    }

    public int widthDp() {
        return widthDp;
    }

    public int heightDp() {
        return heightDp;
    }

    public float fontScale() {
        return fontScale;
    }

    public WidthClass widthClass() {
        return widthClass;
    }

    public boolean compactWidth() {
        return widthClass == WidthClass.COMPACT;
    }

    public boolean largeText() {
        return fontScale >= 1.30f;
    }

    public boolean shortHeight() {
        return heightDp < 480;
    }

    /** A very narrow window gives more of its width to content. */
    public boolean narrowWidth() {
        return widthDp < 320;
    }

    /** The horizontal breathing room used by page content and full-width cards. */
    public int pagePaddingDp() {
        if (widthDp < 320) return 10;
        if (widthClass == WidthClass.COMPACT) return 12;
        if (widthClass == WidthClass.MEDIUM) return 24;
        return 32;
    }

    private boolean fitsPanes(int firstMinDp, int secondMinDp, int gapDp, int minHeightDp) {
        return heightDp >= minHeightDp
                && widthDp - pagePaddingDp() * 2 >= firstMinDp + secondMinDp + gapDp;
    }

    /** Short landscape and medium windows can put onboarding choices beside the introduction. */
    public boolean twoPaneOnboarding() {
        return fitsPanes(shortHeight() ? 260 : 320, shortHeight() ? 260 : 300, 24, 300);
    }

    /** Pairing needs room for the link actions and a complete manual form. */
    public boolean twoPanePairing() {
        return fitsPanes(280, 360, 16, 420);
    }

    /** Settings columns each need space for labels and controls. */
    public boolean twoPaneSettings() {
        return fitsPanes(320, 320, 16, 480);
    }

    /** Two useful workspace panes need enough width after page margins. */
    public boolean twoPaneWorkspace() {
        return fitsPanes(sidebarWidthDp(), largeText() ? 800 : 560, 16, 520);
    }

    public int sidebarWidthDp() {
        return Math.min(300, Math.max(240, widthDp / 4));
    }

    public int ambientControlWidthDp() {
        return Math.min(440, Math.max(320, widthDp / 3));
    }

    /** The chat thread and its composer use the same readable column. */
    public int chatColumnMaxWidthDp() {
        return largeText() ? 1040 : 880;
    }

    public int messageMaxWidthDp() {
        return Math.min(760, Math.max(1, widthDp - pagePaddingDp() * 2));
    }

    /** Bottom sheets stay full width on phones and readable on large windows. */
    public int sheetMaxWidthDp() {
        if (widthClass == WidthClass.COMPACT) return widthDp;
        return Math.min(widthClass == WidthClass.MEDIUM ? 640 : 720, widthDp);
    }

    public int sheetMaxHeightDp() {
        // A short landscape window must never receive a sheet taller than the window itself.
        return Math.min(heightDp, Math.max(240, Math.round(heightDp * 0.82f)));
    }

    public int minTouchTargetDp() {
        return 48;
    }

    public int iconButtonDp() {
        return minTouchTargetDp();
    }

    public int controlHeightDp() {
        if (largeText()) return 56;
        if (shortHeight()) return 48;
        return widthClass == WidthClass.COMPACT ? 50 : 52;
    }

    public int chromeMinWindowHeightDp() {
        return largeText() ? 300 : 240;
    }

    public int rowMinHeightDp() {
        if (largeText()) return 64;
        return widthClass == WidthClass.COMPACT ? 56 : 60;
    }

    public int viewfinderHeightDp() {
        int preferred =
                widthClass == WidthClass.COMPACT
                        ? 180
                        : widthClass == WidthClass.MEDIUM ? 260 : 340;
        int shortWindowLimit = Math.max(120, heightDp / 3);
        return Math.min(preferred, shortWindowLimit);
    }

    public int inlineImageWidthDp() {
        int preferred =
                widthClass == WidthClass.COMPACT
                        ? 156
                        : widthClass == WidthClass.MEDIUM ? 240 : 320;
        int available = Math.max(1, widthDp - pagePaddingDp() * 2);
        return Math.min(preferred, available);
    }

    public int inlineImageHeightDp() {
        int preferred =
                widthClass == WidthClass.COMPACT
                        ? 140
                        : widthClass == WidthClass.MEDIUM ? 210 : 280;
        return Math.min(preferred, Math.max(88, heightDp / 2));
    }

    public int qrMaxWidthDp() {
        int preferred = widthClass == WidthClass.COMPACT ? 216 : 280;
        return Math.min(preferred, Math.max(1, widthDp - pagePaddingDp() * 2));
    }

    public int keyboardThresholdDp() {
        return Math.max(100, Math.round(96 * fontScale));
    }

    public int statusBarFallbackDp() {
        return 24;
    }

    /** A bounded name width inside a chip, expressed in design dp. */
    public int attachmentNameMaxDp() {
        int preferred =
                widthClass == WidthClass.COMPACT
                        ? 180
                        : widthClass == WidthClass.MEDIUM ? 280 : 360;
        int available = Math.max(1, widthDp - pagePaddingDp() * 2);
        return Math.min(preferred, available);
    }

    public int markdownTableCellMinDp() {
        return 88;
    }

    public int markdownTableColumnMinDp() {
        return 64;
    }

    public int markdownTableColumnMaxDp() {
        if (widthClass == WidthClass.COMPACT) return 140;
        return widthClass == WidthClass.MEDIUM ? 180 : 220;
    }

    public int markdownTableFitThresholdDp() {
        return 140;
    }
}
