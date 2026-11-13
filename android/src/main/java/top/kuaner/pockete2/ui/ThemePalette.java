// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.ui;

/** Semantic colour tokens from Pocket, applied to E2Studio's borderless native components. */
public final class ThemePalette {
    public final int background, surface, raised, selected, text, secondary, subtle, placeholder;
    public final int accent,
            onAccent,
            accentTint,
            mine,
            mineText,
            answer,
            answerText,
            ok,
            attention,
            attentionTint,
            danger;
    public final boolean light;

    private ThemePalette(boolean light) {
        this.light = light;
        background = light ? 0xfff5f4ed : 0xff0e0e0d;
        surface = light ? 0xfffaf9f5 : 0xff171716;
        raised = light ? 0xffe9e8df : 0xff1f1f1d;
        selected = light ? 0xffdeddd5 : 0xff2a2a27;
        text = light ? 0xff141413 : 0xfff5f4ed;
        secondary = light ? 0xff60605d : 0xffb4b3ac;
        subtle = light ? 0xff686864 : 0xff8d8c86;
        placeholder = light ? 0xff80807c : 0xff5c5b57;
        accent = light ? 0xff1a7175 : 0xff1fd9de;
        onAccent = light ? 0xfffaf9f5 : 0xff0a1414;
        accentTint = light ? 0xffdcebed : 0xff0f3537;
        mine = light ? 0xff1a7175 : 0xff12484b;
        mineText = light ? 0xfffaf9f5 : 0xffeffcfc;
        answer = raised;
        answerText = light ? 0xff141413 : 0xffdddcd5;
        ok = light ? 0xff0e7957 : 0xff4ac994;
        attention = light ? 0xff7a3e00 : 0xffd9a066;
        attentionTint = light ? 0xffece2cc : 0xff2a2015;
        danger = light ? 0xffc02b31 : 0xffff6464;
    }

    public static ThemePalette dark() {
        return new ThemePalette(false);
    }

    public static ThemePalette light() {
        return new ThemePalette(true);
    }
}
