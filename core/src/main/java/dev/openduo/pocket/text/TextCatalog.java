// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.text;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit application-copy localisation. User content and wire identities never enter this API.
 */
public final class TextCatalog {
    private final Map<String, String> english;
    public final boolean en;
    public final Locale locale;

    public TextCatalog(String language, Locale system, JSONObject translations) {
        Map<String, String> values = new LinkedHashMap<>();
        for (java.util.Iterator<String> keys = translations.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = translations.opt(key);
            if (!(value instanceof String) || ((String) value).isEmpty())
                throw new IllegalArgumentException("Invalid translation: " + key);
            values.put(key, (String) value);
        }
        english = Collections.unmodifiableMap(values);
        en = english(language, system);
        locale = en ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
    }

    private TextCatalog(boolean en, Map<String, String> values) {
        english = values;
        this.en = en;
        locale = en ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
    }

    public TextCatalog language(String language, Locale system) {
        return new TextCatalog(english(language, system), english);
    }

    public static boolean english(String language, Locale system) {
        return language.equals("system")
                ? !system.getLanguage().equals("zh")
                : language.equals("en");
    }

    public String t(String authored) {
        if (authored == null) return "";
        return en ? english.getOrDefault(authored, authored) : authored;
    }

    public String format(String authored, Object... values) {
        return String.format(locale, t(authored), values);
    }

    public Set<String> keys() {
        return english.keySet();
    }

    /**
     * Previously persisted app errors stay readable after a language switch. No broad replacement.
     */
    public String explain(String authored) {
        if (authored == null) return "";
        if (!en || english.containsKey(authored)) return t(authored);
        for (ErrorFormat format : ERRORS) {
            Matcher match = format.match.matcher(authored);
            if (!match.matches()) continue;
            Object[] values = new Object[match.groupCount()];
            for (int i = 0; i < values.length; i++) values[i] = t(match.group(i + 1));
            return format(format.template, values);
        }
        return authored;
    }

    public String route(String route) {
        String[] components = route.split(" → ", -1);
        for (int i = 0; i < components.length; i++) components[i] = t(components[i]);
        return String.join(" → ", components);
    }

    public String steps(int count) {
        return en ? count + (count == 1 ? " step" : " steps") : count + " 步";
    }

    public String traceSummary(int count, long elapsedMs) {
        return t("任务已完成 · ")
                + steps(count)
                + (elapsedMs <= 0 ? "" : " · " + Math.max(1, elapsedMs / 1000) + t(" 秒"));
    }

    private static final class ErrorFormat {
        final Pattern match;
        final String template;

        ErrorFormat(String expression, String template) {
            match = Pattern.compile(expression);
            this.template = template;
        }
    }

    private static final ErrorFormat[] ERRORS = {
        new ErrorFormat("没发出去 · 轻点重试（([0-9]+)）", "没发出去 · 轻点重试（%s）"),
        new ErrorFormat("朗读失败（([0-9]+)）", "朗读失败（%s）"),
        new ErrorFormat("扫描未成功（([0-9]+)）", "扫描未成功（%s）"),
        new ErrorFormat("通知订阅失败（([0-9]+)）", "通知订阅失败（%s）"),
        new ErrorFormat("蓝牙发送失败（([0-9]+)）", "蓝牙发送失败（%s）"),
        new ErrorFormat("E2 阵列(初始化|启动)失败（(-?[0-9]+)）", "E2 阵列%s失败（%s）"),
        new ErrorFormat("文件太大（上限 ([0-9.]+) MB）", "文件太大（上限 %s MB）"),
        new ErrorFormat("([0-9]{4}-[0-9]{2}-[0-9]{2}) 之前暂未找到 · 继续找", "%s 之前暂未找到 · 继续找"),
    };
}
