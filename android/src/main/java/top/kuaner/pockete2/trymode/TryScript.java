// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.trymode;

import org.json.JSONArray;

import top.kuaner.pockete2.core.Json;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Localised prepared content, kept outside the transport and application reducers. */
public final class TryScript {
    public static final class Tool {
        public final String name, input;

        Tool(String name, String key, String value) {
            this.name = name;
            input = Json.object(key, value).toString();
        }
    }

    public static final class Turn {
        public final String text, audio;
        public final List<Tool> tools;
        public final boolean file;

        Turn(String text, String audio, boolean file, Tool... tools) {
            this.text = text;
            this.audio = audio;
            this.file = file;
            this.tools = Collections.unmodifiableList(Arrays.asList(tools));
        }
    }

    private static String l(boolean en, String zh, String english) {
        return en ? english : zh;
    }

    public static String welcome(boolean en) {
        return l(
                en,
                "这是 Pocket E2 的体验房间。这里的回答都是预先准备的。\n\n" + "可以发送文字、按住说话、发图片、查看工具和会话。退出后会丢弃所有体验内容。",
                "Welcome to Pocket E2's try-it room. All replies here are prepared in advance.\n\n"
                    + "Send messages, hold to talk, attach photos, or explore tools and sessions."
                    + " Everything in this room is discarded on exit.");
    }

    public static List<Turn> turns(boolean en) {
        String prefix = en ? "try-en-" : "try-zh-";
        return Collections.unmodifiableList(
                Arrays.asList(
                        new Turn(
                                l(
                                        en,
                                        "你好，我是多多。现在是体验模式：我的回复是事先准备好的，不会真正理解你说的话。连上你自己部署的多多以后，对话才是真的。你可以继续发消息、按住说话、发图片，看看各个功能。",
                                        "Hi, I'm DuoDuo. This is try-it mode: my replies are"
                                            + " prepared in advance and don't understand what you"
                                            + " say. Once you connect your own DuoDuo, the"
                                            + " conversation is real. Keep sending messages, hold"
                                            + " to talk or attach a photo to see how things work."),
                                prefix + "1",
                                false),
                        new Turn(
                                l(
                                        en,
                                        "查好了。明天多云转小雨，15 到 22 度，下午三点以后可能下雨，出门记得带伞。\n\n*以上为预设天气示例。*",
                                        "Done. Tomorrow is cloudy turning to light rain, 15 to 22"
                                            + " °C, likely rain after 3 pm. Take an umbrella.\n\n"
                                            + "*This weather is a prepared example.*"),
                                prefix + "2",
                                false,
                                new Tool("WebSearch", "query", l(en, "明天 天气", "tomorrow weather")),
                                new Tool(
                                        "WebFetch",
                                        "url",
                                        l(
                                                en,
                                                "https://www.weather.com.cn",
                                                "https://www.weather.gov"))),
                        new Turn(
                                l(
                                        en,
                                        "## 本周安排\n\n"
                                                + "| 时间 | 事项 | 地点 |\n"
                                                + "|---|---|---|\n"
                                                + "| 周二 10:00 | 产品评审 | 3 号会议室 |\n"
                                                + "| 周四 15:00 | 给妈妈打电话 | — |\n"
                                                + "| 周五 16:00 | 提交测试版 | 线上 |\n\n"
                                                + "**要准备的：**\n"
                                                + "1. 评审前把演示视频剪好\n"
                                                + "2. 周五前确认隐私政策链接能打开\n\n"
                                                + "*此表格为预设示例。*",
                                        "## This week\n\n"
                                                + "| When | What | Where |\n"
                                                + "|---|---|---|\n"
                                                + "| Tue 10:00 | Product review | Room 3 |\n"
                                                + "| Thu 15:00 | Call Mom | — |\n"
                                                + "| Fri 16:00 | Submit the beta | Online |\n\n"
                                                + "**To prepare:**\n"
                                                + "1. Cut the demo video before the review\n"
                                                + "2. Check the privacy policy link opens before"
                                                + " Friday\n\n"
                                                + "*This table is a prepared example.*"),
                                prefix + "3",
                                false),
                        new Turn(
                                l(
                                        en,
                                        "我把这周的笔记整理成了一个文件，发给你了。\n\n*这是预先准备的文件示例。*",
                                        "I put this week's notes into a file and sent it to"
                                                + " you.\n\n"
                                                + "*This file is a prepared example.*"),
                                prefix + "4",
                                true,
                                new Tool("Write", "file_path", notesName(en)))));
    }

    public static String ambient(boolean en) {
        return l(
                en,
                "环境模式体验：下面播放的是预先准备的音频。麦克风会显示真实的收音电平，不会识别你说的话。",
                "Ambient try-it mode: the audio is prepared in advance. The microphone shows real"
                        + " input levels, without recognising what you say.");
    }

    public static String voiceText(boolean en, long packets) {
        return String.format(
                Locale.ROOT,
                en ? "(Voice, %.1f s. Try-it mode does not transcribe.)" : "（语音 %.1f 秒，体验模式不识别内容）",
                packets * 0.02);
    }

    public static String notesName(boolean en) {
        return en ? "weekly-notes.md" : "本周笔记.md";
    }

    public static String notifySource(boolean en) {
        return l(en, "体验 · 这台 E2", "Try · this E2");
    }

    public static JSONArray sessions(boolean en) {
        String now = java.time.Instant.now().toString();
        return new JSONArray()
                .put(
                        Json.object(
                                "key",
                                "try-chat",
                                "name",
                                l(en, "示例：写周报", "Sample: weekly report"),
                                "group",
                                "chat",
                                "status",
                                "idle",
                                "tools",
                                new JSONArray(),
                                "last_event_at",
                                now,
                                "notify",
                                true))
                .put(
                        Json.object(
                                "key",
                                "try-job",
                                "name",
                                l(en, "示例：每日新闻摘要", "Sample: daily news digest"),
                                "group",
                                "job",
                                "status",
                                "running",
                                "tools",
                                new JSONArray().put("WebSearch"),
                                "last_event_at",
                                now,
                                "notify",
                                false));
    }

    public static JSONArray rows(boolean en, String key) {
        String now = java.time.Instant.now().toString();
        if (key.equals("try-job"))
            return new JSONArray()
                    .put(
                            Json.object(
                                    "id",
                                    "j1",
                                    "at",
                                    now,
                                    "kind",
                                    "system",
                                    "text",
                                    l(en, "任务开始（预设示例）", "Job started (prepared sample)")))
                    .put(
                            Json.object(
                                    "id",
                                    "j2",
                                    "at",
                                    now,
                                    "kind",
                                    "tool",
                                    "label",
                                    "WebSearch",
                                    "input_summary",
                                    "{\"query\":\"today's headlines\"}"));
        if (!key.equals("try-chat")) return new JSONArray();
        return new JSONArray()
                .put(
                        Json.object(
                                "id",
                                "c1",
                                "at",
                                now,
                                "kind",
                                "message",
                                "text",
                                l(
                                        en,
                                        "帮我把这周的进展整理成周报（预设示例）",
                                        "Turn this week's progress into a report (prepared"
                                                + " sample)")))
                .put(
                        Json.object(
                                "id",
                                "c2",
                                "at",
                                now,
                                "kind",
                                "tool",
                                "label",
                                "Read",
                                "input_summary",
                                "{\"file_path\":\"notes/week.md\"}"))
                .put(
                        Json.object(
                                "id",
                                "c2b",
                                "at",
                                now,
                                "kind",
                                "tool",
                                "label",
                                "Bash",
                                "input_summary",
                                "{\"description\":\"List this week's commits\"}"))
                .put(
                        Json.object(
                                "id",
                                "c3",
                                "at",
                                now,
                                "kind",
                                "notify",
                                "from_label",
                                l(en, "示例：每日新闻摘要", "Sample: daily news digest"),
                                "text",
                                l(
                                        en,
                                        "今天的新闻摘要已准备好（预设示例）",
                                        "Today's news digest is ready (prepared sample)")))
                .put(
                        Json.object(
                                "id",
                                "c4",
                                "at",
                                now,
                                "kind",
                                "reply",
                                "text",
                                l(
                                        en,
                                        "这是体验模式的示例会话，内容是事先准备好的。连接自己的房间后，这里显示真实的会话。",
                                        "This is a sample session with prepared content. Connect"
                                            + " your own room to see its live sessions here.")));
    }

    private TryScript() {}
}
