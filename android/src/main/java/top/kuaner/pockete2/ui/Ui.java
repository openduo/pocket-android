// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** E2Studio-style filled surfaces, system fonts and real Lucide vector controls. */
public final class Ui {
    public final Context context;
    public final ThemePalette palette;
    public final top.kuaner.pockete2.text.TextCatalog words;
    public final AdaptiveMetrics metrics;
    public final int systemBarBackground;
    private final boolean lightSystemBars;
    private final float density;

    public Ui(Context context) {
        this(context, lightTheme(context) ? ThemePalette.light() : ThemePalette.dark());
    }

    public Ui(Context context, ThemePalette palette) {
        this(context, palette, top.kuaner.pockete2.text.AppText.get(context));
    }

    public Ui(Context context, ThemePalette palette, top.kuaner.pockete2.text.TextCatalog words) {
        this.context = context;
        this.palette = palette;
        this.words = words;
        Configuration configuration = context.getResources().getConfiguration();
        android.util.DisplayMetrics display = context.getResources().getDisplayMetrics();
        int widthDp =
                configuration.screenWidthDp > 0
                        ? configuration.screenWidthDp
                        : Math.round(display.widthPixels / display.density);
        int heightDp =
                configuration.screenHeightDp > 0
                        ? configuration.screenHeightDp
                        : Math.round(display.heightPixels / display.density);
        metrics = AdaptiveMetrics.of(widthDp, heightDp, configuration.fontScale);
        // E2 firmware keeps white system icons, including in a light application window.
        boolean e2 =
                android.os.Build.VERSION.SDK_INT == 27
                        && "E2 AI Recorder".equals(android.os.Build.MODEL);
        lightSystemBars = palette.light && !e2;
        systemBarBackground = e2 ? 0xff0e0e0d : palette.background;
        density = context.getResources().getDisplayMetrics().density;
    }

    public String t(String authored) {
        return words.t(authored);
    }

    public String explain(String authored) {
        return words.explain(authored);
    }

    public String format(String authored, Object... values) {
        return words.format(authored, values);
    }

    public static boolean lightTheme(Context context) {
        String appearance =
                context.getSharedPreferences("pocket-e2.settings", Context.MODE_PRIVATE)
                        .getString("appearance", "dark");
        return appearance.equals("light")
                || appearance.equals("system")
                        && (context.getResources().getConfiguration().uiMode
                                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public int dp(float value) {
        return Math.round(value * density);
    }

    public int touchTarget() {
        return dp(metrics.minTouchTargetDp());
    }

    public int iconButtonSize() {
        return dp(metrics.iconButtonDp());
    }

    public int controlHeight() {
        return dp(metrics.controlHeightDp());
    }

    public int rowHeight() {
        return dp(metrics.rowMinHeightDp());
    }

    public int pagePadding() {
        return dp(metrics.pagePaddingDp());
    }

    public int pagePaddingDp() {
        return metrics.pagePaddingDp();
    }

    public int sheetWidth() {
        return dp(metrics.sheetMaxWidthDp());
    }

    public int sheetHeight() {
        return dp(metrics.sheetMaxHeightDp());
    }

    public int screenWidth() {
        return dp(metrics.widthDp());
    }

    public int navigationHeight() {
        return dp(metrics.navigationHeightDp());
    }

    public int viewfinderHeight() {
        return dp(metrics.viewfinderHeightDp());
    }

    public int inlineImageWidth() {
        return dp(metrics.inlineImageWidthDp());
    }

    public int inlineImageHeight() {
        return dp(metrics.inlineImageHeightDp());
    }

    public int qrMaxWidth() {
        return dp(metrics.qrMaxWidthDp());
    }

    public int attachmentNameMaxWidth() {
        return dp(metrics.attachmentNameMaxDp());
    }

    public int markdownTableCellMinWidth() {
        return dp(metrics.markdownTableCellMinDp());
    }

    public int markdownTableColumnMinWidth() {
        return dp(metrics.markdownTableColumnMinDp());
    }

    public int markdownTableColumnMaxWidth() {
        return dp(metrics.markdownTableColumnMaxDp());
    }

    public int markdownTableFitThreshold() {
        return dp(metrics.markdownTableFitThresholdDp());
    }

    /** Constrains page content on medium and expanded windows while keeping phones full width. */
    public View adaptivePage(View child) {
        AdaptivePage page = new AdaptivePage(context, this);
        page.addView(child, new FrameLayout.LayoutParams(-1, -1));
        return page;
    }

    public View adaptiveWidth(View child) {
        AdaptivePage page = new AdaptivePage(context, this);
        page.addView(child, new FrameLayout.LayoutParams(-1, -2));
        return page;
    }

    public LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    public AdaptiveColumn adaptiveColumn() {
        return new AdaptiveColumn(this);
    }

    /** A footer may scroll on very short windows instead of being clipped by a weighted body. */
    public ScrollView scrollFooter(View child) {
        ScrollView scroll = scroll(child);
        scroll.setFillViewport(false);
        return scroll;
    }

    public LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    public TextView label(String text, float sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setIncludeFontPadding(false);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    public EditText input(String hint, String text, boolean multiline) {
        return configureInput(new EditText(context), hint, text, multiline);
    }

    public <T extends EditText> T configureInput(
            T field, String hint, String text, boolean multiline) {
        field.setHint(hint);
        field.setText(text);
        field.setTextColor(palette.text);
        field.setHintTextColor(palette.subtle);
        field.setTextSize(15);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setMinHeight(controlHeight());
        field.setBackground(surface(palette.raised, 12));
        field.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (multiline
                                ? InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                                : 0));
        field.setSingleLine(!multiline);
        if (multiline) {
            field.setMaxLines(4);
            field.setGravity(Gravity.TOP);
        }
        field.setMinimumHeight(controlHeight());
        return field;
    }

    public View icon(String name, int color, int size) {
        ImageView image = new ImageView(context);
        int resource =
                context.getResources()
                        .getIdentifier("ic_lucide_" + name, "drawable", context.getPackageName());
        if (resource == 0)
            resource =
                    context.getResources()
                            .getIdentifier(
                                    "ic_lucide_circle_dot", "drawable", context.getPackageName());
        image.setImageResource(resource);
        image.setImageTintList(ColorStateList.valueOf(color));
        image.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    public View iconButton(String icon, String description, int color, Runnable action) {
        FrameLayout wrap = new FrameLayout(context);
        View image = icon(icon, color, 21);
        wrap.addView(image, new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
        wrap.setContentDescription(description);
        wrap.setMinimumWidth(touchTarget());
        wrap.setMinimumHeight(touchTarget());
        clickable(wrap, 0, 14, action);
        return wrap;
    }

    public TextView button(String text, int fg, int bg, Runnable action) {
        TextView view = label(text, 14, fg, true);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(touchTarget());
        view.setMinHeight(controlHeight());
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        clickable(view, bg, 12, action);
        return view;
    }

    public void clickable(View view, int bg, int radius, Runnable action) {
        view.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x25ffffff),
                        surface(bg, radius),
                        surface(0xffffffff, radius)));
        view.setClickable(true);
        view.setFocusable(true);
        view.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(android.widget.Button.class.getName());
                    }
                });
        if (action != null) view.setOnClickListener(v -> action.run());
    }

    public GradientDrawable surface(int color, int radius) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(radius));
        return fill;
    }

    public void inset(View view, int horizontal, int vertical) {
        view.setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical));
    }

    public void add(LinearLayout parent, View child, int gapDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gapDp);
        parent.addView(child, params);
    }

    public void toast(String value) {
        if (value != null && !value.isEmpty())
            Toast.makeText(context, value, Toast.LENGTH_SHORT).show();
    }

    public void copy(String label, String value) {
        context.getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText(label, value));
        toast(t("已复制"));
    }

    public void shareText(String text) {
        try {
            context.startActivity(
                    Intent.createChooser(
                            new Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, text),
                            t("分享")));
        } catch (android.content.ActivityNotFoundException ignored) {
            toast(t("没有可接收分享的应用"));
        }
    }

    public void hideKeyboard(View view) {
        if (view != null)
            context.getSystemService(InputMethodManager.class)
                    .hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public void immersive() {
        if (!(context instanceof Activity)) return;
        immersive(((Activity) context).getWindow());
    }

    public void immersive(Window window) {
        window.clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(systemBarBackground);
        window.setNavigationBarColor(systemBarBackground);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.getDecorView()
                .setSystemUiVisibility(
                        (lightSystemBars
                                        ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                                        : 0)
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    public ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(child);
        return scroll;
    }

    public View divider() {
        View line = new View(context);
        line.setBackgroundColor(palette.selected);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return line;
    }

    public static void updateText(TextView view, String value) {
        if (view != null && !TextUtils.equals(view.getText(), value)) view.setText(value);
    }
}
