// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of PocketCore.TurnState: correlated turns, stable streaming answers and tool traces. */
public final class TurnState {
    public static final class Step {
        public final String name, summary;
        public final boolean done, hasInput;

        Step(String name, String summary, boolean done, boolean hasInput) {
            this.name = name;
            this.summary = summary;
            this.done = done;
            this.hasInput = hasInput;
        }

        public String line() {
            return name + (summary.isEmpty() ? "" : " " + summary);
        }
    }

    public static final class Working {
        public final List<String> messages;
        public final String phase, text, speechId;
        public final List<Step> steps;
        public final long startedAt;

        Working(
                List<String> messages,
                String phase,
                String text,
                String speechId,
                List<Step> steps,
                long startedAt) {
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
            this.phase = phase;
            this.text = text;
            this.speechId = speechId;
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.startedAt = startedAt;
        }

        public String label() {
            switch (phase) {
                case "received":
                    return "收到了";
                case "thinking":
                    return "在想…";
                case "tool":
                    return "在查";
                default:
                    return "正在回复";
            }
        }
    }

    public static final class Trace {
        public final List<Step> steps;
        public final long elapsedMs;

        Trace(List<Step> steps, long elapsedMs) {
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.elapsedMs = elapsedMs;
        }

        public String summary() {
            return "任务已完成 · "
                    + steps.size()
                    + " 步"
                    + (elapsedMs > 0 ? " · " + Math.max(1, elapsedMs / 1000) + " 秒" : "");
        }
    }

    private Working working;
    private final Map<String, Trace> traces = new LinkedHashMap<>();

    public Working working() {
        return working;
    }

    public Trace trace(String key) {
        return traces.get(key);
    }

    public Map<String, Trace> traces(List<Message> rows) {
        Map<String, Trace> result = new LinkedHashMap<>();
        for (Message row : rows) {
            if (row.kind != Message.DUODUO) continue;
            Trace trace = traces.get(row.id);
            if (trace == null && !row.messageIds.isEmpty())
                trace = traces.get(Json.answerKey(row.messageIds));
            if (trace == null) trace = traces.get("text:" + row.text.trim());
            if (trace != null) result.put(row.id, trace);
        }
        return Collections.unmodifiableMap(result);
    }

    public void reset() {
        working = null;
        traces.clear();
    }

    public void restore(JSONObject turn) {
        if (turn == null) {
            working = null;
            return;
        }
        List<String> ids = Json.strings(turn.optJSONArray("message_ids"));
        String phase = turn.optString("phase");
        if (working != null)
            working =
                    new Working(
                            union(working.messages, ids),
                            working.phase,
                            working.text,
                            working.speechId,
                            working.steps,
                            working.startedAt);
        else if (phase.matches("received|thinking|tool"))
            working = new Working(ids, phase, "", "", Collections.<Step>emptyList(), 0);
    }

    public Message handle(JSONObject frame, long now) {
        String type = frame.optString("type");
        List<String> ids = Json.strings(frame.optJSONArray("message_ids"));
        if (type.equals("turn")) {
            String phase = frame.optString("phase");
            if (phase.equals("idle")) {
                if (working != null && names(ids)) working = null;
                return null;
            }
            if (!phase.matches("received|thinking|tool")) return null;
            if (working == null)
                working = new Working(ids, phase, "", "", Collections.<Step>emptyList(), now);
            else
                working =
                        new Working(
                                union(working.messages, ids),
                                working.phase,
                                working.text,
                                working.speechId,
                                working.steps,
                                working.startedAt);
            List<Step> steps = new ArrayList<>(working.steps);
            if (phase.equals("tool"))
                tool(
                        steps,
                        frame.optString("label"),
                        frame.has("input_summary") ? frame.optString("input_summary") : null);
            String next =
                    working.phase.equals("streaming")
                            ? "streaming"
                            : phase.equals("received") ? working.phase : phase;
            working =
                    new Working(
                            working.messages,
                            next,
                            working.text,
                            working.speechId,
                            steps,
                            working.startedAt);
        } else if (type.equals("duoduo_said") && !frame.optString("kind").equals("reaction")) {
            String speech = frame.optString("speech_id"), chunk = frame.optString("text");
            Working before =
                    working == null
                            ? new Working(
                                    ids, "streaming", "", "", Collections.<Step>emptyList(), now)
                            : working;
            working =
                    new Working(
                            union(before.messages, ids),
                            "streaming",
                            before.speechId.equals(speech) ? before.text + chunk : chunk,
                            speech,
                            completed(before.steps),
                            before.startedAt);
        } else if (type.equals("answer_final")) {
            String text = frame.optString("text");
            String key =
                    ids.isEmpty()
                            ? "provisional:" + frame.optString("speech_id", String.valueOf(now))
                            : Json.answerKey(ids);
            if (working != null && names(ids)) {
                if (!working.steps.isEmpty()) {
                    Trace trace =
                            new Trace(
                                    completed(working.steps),
                                    working.startedAt > 0 ? now - working.startedAt : 0);
                    traces.put(key, trace);
                    if (!text.trim().isEmpty()) traces.put("text:" + text.trim(), trace);
                }
                working = null;
            } else if (working != null && working.speechId.equals(frame.optString("speech_id"))) {
                working =
                        new Working(
                                working.messages,
                                "thinking",
                                "",
                                "",
                                working.steps,
                                working.startedAt);
            }
            if (!text.trim().isEmpty())
                return new Message(
                                key,
                                Message.DUODUO,
                                now,
                                text,
                                "",
                                0,
                                Message.DELIVERED,
                                "",
                                true,
                                false,
                                Collections.<Attachment>emptyList(),
                                -1)
                        .withMessageIds(ids);
        }
        return null;
    }

    public void settled(Message row) {
        if (row.kind != Message.DUODUO || working == null || working.messages.isEmpty()) return;
        if (names(row.messageIds)) working = null;
    }

    public void failed(String id) {
        if (working == null || !working.messages.contains(id)) return;
        List<String> ids = new ArrayList<>(working.messages);
        ids.remove(id);
        working =
                ids.isEmpty()
                        ? null
                        : new Working(
                                ids,
                                working.phase,
                                working.text,
                                working.speechId,
                                working.steps,
                                working.startedAt);
    }

    private boolean names(List<String> ids) {
        if (working.messages.isEmpty()) return true;
        for (String id : ids) if (working.messages.contains(id)) return true;
        return false;
    }

    private static List<String> union(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String id : b) if (!out.contains(id)) out.add(id);
        return out;
    }

    private static List<Step> completed(List<Step> steps) {
        List<Step> out = new ArrayList<>();
        for (Step step : steps) out.add(new Step(step.name, step.summary, true, step.hasInput));
        return out;
    }

    private static void tool(List<Step> steps, String raw, String input) {
        boolean done = input == null && raw.endsWith(" ✓");
        String name = displayName(done ? raw.substring(0, raw.length() - 2) : raw);
        if (done) {
            for (int i = 0; i < steps.size(); i++) {
                Step s = steps.get(i);
                if (!s.done && s.name.equals(name)) {
                    steps.set(i, new Step(name, s.summary, true, s.hasInput));
                    return;
                }
            }
            steps.add(new Step(name, "", true, false));
            return;
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step s = steps.get(i);
            if (s.name.equals(name) && !s.done && !s.hasInput) {
                if (input != null) steps.set(i, new Step(name, summarize(input), false, true));
                return;
            }
        }
        steps.add(new Step(name, input == null ? "" : summarize(input), false, input != null));
    }

    public static String displayName(String raw) {
        String text = raw.trim();
        if (text.startsWith("mcp__")) {
            String[] parts = text.split("__");
            text = parts[parts.length - 1];
        }
        return text.isEmpty() ? "tool" : text;
    }

    public static String summarize(String input) {
        String text = input;
        try {
            JSONObject object = new JSONObject(input);
            text = "";
            for (String key :
                    new String[] {
                        "description",
                        "command",
                        "file_path",
                        "path",
                        "pattern",
                        "query",
                        "url",
                        "prompt",
                        "text"
                    }) {
                Object value = object.opt(key);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    text = (String) value;
                    break;
                }
            }
            if (text.isEmpty() && object.optJSONArray("files") != null)
                text = object.optJSONArray("files").optString(0);
            if (text.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (java.util.Iterator<String> it = object.keys(); it.hasNext(); )
                    keys.add(it.next());
                Collections.sort(keys);
                for (String key : keys) {
                    Object value = object.opt(key);
                    if (value instanceof String && !((String) value).trim().isEmpty()) {
                        text = (String) value;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.codePointCount(0, text.length()) <= 34
                ? text
                : text.substring(0, text.offsetByCodePoints(0, 34)) + "…";
    }
}
