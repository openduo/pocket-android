// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure viewer state and uplink rules, shared by reconnection and the compact session page. */
public final class SessionViewer {
    public static final class Summary {
        public final String key, name, group, status, at;
        public final List<String> tools;
        public final boolean notify;

        Summary(JSONObject o) {
            key = o.optString("key");
            name = o.optString("name");
            group = o.optString("group");
            status = o.optString("status");
            at = o.optString("last_event_at");
            tools = Collections.unmodifiableList(Json.strings(o.optJSONArray("tools")));
            notify = o.optBoolean("notify");
        }

        public boolean running() {
            return !tools.isEmpty()
                    || status.equalsIgnoreCase("active")
                    || status.equalsIgnoreCase("running");
        }
    }

    public static final class Row {
        public final String id, at, kind, text, from, label, input, fromLabel;
        public final boolean failed;

        Row(JSONObject o) {
            id = o.optString("id");
            at = o.optString("at");
            kind = o.optString("kind");
            text = o.optString("text");
            from = o.optString("from");
            label = o.optString("label");
            input = o.optString("input_summary");
            fromLabel = o.optString("from_label");
            failed = o.optBoolean("failed");
        }

        public String toolLine() {
            return TurnState.displayName(label)
                    + (input.isEmpty() ? "" : " " + TurnState.summarize(input));
        }
    }

    public static final class Snapshot {
        public final boolean watching, listed, loaded;
        public final String openKey, error, pending, outcome;
        public final List<Summary> sessions;
        public final List<Row> rows;
        public final long revision;

        Snapshot(SessionViewer state) {
            watching = state.watching;
            listed = state.listed;
            loaded = state.loaded;
            openKey = state.openKey;
            error = state.error;
            pending = state.pending;
            outcome = state.outcome;
            List<Summary> sorted = new ArrayList<>(state.sessions);
            sorted.sort(
                    Comparator.comparing(Summary::running)
                            .reversed()
                            .thenComparing((Summary s) -> s.at, Comparator.reverseOrder()));
            sessions = Collections.unmodifiableList(sorted);
            rows = Collections.unmodifiableList(new ArrayList<>(state.rows.values()));
            revision = state.revision;
        }

        public Summary open() {
            for (Summary s : sessions) if (s.key.equals(openKey)) return s;
            return null;
        }
    }

    private boolean watching, listed, loaded;
    private String openKey = "", error = "", pending = "", outcome = "";
    private long revision;
    private List<Summary> sessions = new ArrayList<>();
    private final Map<String, Row> rows = new LinkedHashMap<>();

    public Snapshot snapshot() {
        return new Snapshot(this);
    }

    public JSONObject watchFrame() {
        return watching
                ? Json.object(
                        "type",
                        "sessions_watch",
                        "open",
                        openKey.isEmpty() ? JSONObject.NULL : openKey)
                : Json.object("type", "sessions_unwatch");
    }

    public JSONObject show(boolean shown) {
        watching = shown;
        if (!shown) {
            openKey = "";
            dropPending();
        }
        revision++;
        return watchFrame();
    }

    public JSONObject open(String key) {
        if (!key.equals(openKey)) {
            openKey = key;
            rows.clear();
            loaded = false;
            outcome = "";
        }
        watching = true;
        revision++;
        return watchFrame();
    }

    public JSONObject list() {
        openKey = "";
        watching = true;
        revision++;
        return watchFrame();
    }

    public void reset() {
        watching = false;
        listed = false;
        loaded = false;
        openKey = "";
        pending = "";
        outcome = "";
        error = "";
        sessions.clear();
        rows.clear();
        revision++;
    }

    public void dropPending() {
        if (!pending.isEmpty()) {
            pending = "";
            outcome = "failed";
            revision++;
        }
    }

    public JSONObject notify(String text, String id) {
        Summary open = snapshot().open();
        String body = text.trim();
        if (open == null || !open.notify || body.isEmpty() || !pending.isEmpty()) return null;
        pending = id;
        outcome = "";
        revision++;
        return Json.object("type", "session_notify", "id", id, "key", openKey, "text", body);
    }

    public boolean handle(JSONObject f) {
        String type = f.optString("type");
        if (type.equals("sessions")) {
            if (!watching) return true;
            JSONArray a = f.optJSONArray("sessions");
            if (a == null) return true;
            List<Summary> next = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null
                        || o.optString("key").isEmpty()
                        || !o.optString("group").matches("chat|job")
                        || o.optJSONArray("tools") == null) return true;
                next.add(new Summary(o));
            }
            sessions = next;
            listed = true;
            error = "";
        } else if (type.equals("sessions_error")) {
            if (watching) error = f.optString("reason");
        } else if (type.equals("session_rows")) {
            if (!watching || !openKey.equals(f.optString("key"))) return true;
            JSONArray a = f.optJSONArray("rows");
            if (a == null) return true;
            List<Row> parsed = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null
                        || o.optString("id").isEmpty()
                        || !o.optString("kind").matches("message|reply|tool|system|notify"))
                    return true;
                parsed.add(new Row(o));
            }
            if (f.optBoolean("initial")) {
                rows.clear();
                loaded = true;
            }
            if (f.optBoolean("gap") && !parsed.isEmpty()) {
                String id = "gap:" + parsed.get(0).id;
                rows.put(id, new Row(Json.object("id", id, "kind", "gap", "text", "此处有未显示的事件")));
            }
            for (Row row : parsed) rows.put(row.id, row);
            error = "";
        } else if (type.equals("session_notify_result")) {
            if (!pending.equals(f.optString("id"))) return true;
            pending = "";
            outcome = f.optBoolean("ok") ? "sent" : f.optString("reason", "failed");
        } else return false;
        revision++;
        return true;
    }
}
