// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.SessionViewer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Session boundary validation, chronological ordering, sender identity and notification ownership.
 */
public final class SessionViewerContracts {
    private int checks;

    public static int run() throws Exception {
        SessionViewerContracts contracts = new SessionViewerContracts();
        contracts.verify();
        return contracts.checks;
    }

    private void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static JSONObject summary(String key, String name, String group, String at) {
        return Json.object(
                "key",
                key,
                "name",
                name,
                "group",
                group,
                "status",
                "idle",
                "tools",
                new JSONArray(),
                "last_event_at",
                at,
                "notify",
                true);
    }

    private static JSONObject row(String id, String sender) {
        return Json.object(
                "id",
                id,
                "at",
                "2026-11-14T01:00:00Z",
                "kind",
                "message",
                "text",
                "设置 stays as user content",
                "from",
                sender);
    }

    private void verify() throws Exception {
        SessionViewer viewer = new SessionViewer();
        viewer.show(true);
        JSONArray list =
                new JSONArray()
                        .put(summary("ambient:home", "Home", "chat", "2026-11-14T08:00:00+08:00"))
                        .put(summary("lark:team", "团队 · Settings", "chat", "2026-11-14T00:30:00Z"))
                        .put(summary("job:daily.001", "Daily job", "job", "not-a-date"));
        viewer.handle(Json.object("type", "sessions", "sessions", list));
        check(
                viewer.snapshot().sessions.get(0).key.equals("lark:team")
                        && viewer.snapshot().sessions.get(2).activity() == Long.MIN_VALUE,
                "session activity orders absolute instants across timezones, leaving invalid dates"
                        + " last");
        JSONArray active = new JSONArray(list.toString());
        active.getJSONObject(2).put("tools", new JSONArray().put("Read"));
        viewer.handle(Json.object("type", "sessions", "sessions", active));
        check(
                viewer.snapshot().sessions.get(0).key.equals("job:daily.001")
                        && viewer.snapshot().sessions.get(1).source().equals("lark"),
                "running jobs lead the activity order and source prefixes remain protocol"
                        + " identities");
        long revision = viewer.snapshot().revision;
        JSONArray malformed = new JSONArray(active.toString());
        malformed.getJSONObject(1).put("name", 5);
        viewer.handle(Json.object("type", "sessions", "sessions", malformed));
        check(
                viewer.snapshot().revision == revision && viewer.snapshot().sessions.size() == 3,
                "a malformed session list cannot partially replace visible summaries");
        malformed = new JSONArray(active.toString());
        malformed.getJSONObject(0).put("notify", "true");
        viewer.handle(Json.object("type", "sessions", "sessions", malformed));
        check(
                viewer.snapshot().revision == revision,
                "notification capability rejects string booleans at the session boundary");

        viewer.open("ambient:home");
        viewer.handle(
                Json.object(
                        "type",
                        "session_rows",
                        "key",
                        "ambient:home",
                        "initial",
                        true,
                        "rows",
                        new JSONArray()
                                .put(row("1", "lark:team"))
                                .put(row("2", "job:unlisted.abc123"))
                                .put(row("3", "feishu:unlisted:opaque"))
                                .put(row("4", "ambient:home"))));
        SessionViewer.Snapshot snapshot = viewer.snapshot();
        check(
                snapshot.sender(snapshot.rows.get(0)).equals("团队 · Settings")
                        && snapshot.sender(snapshot.rows.get(1)).equals("unlisted")
                        && snapshot.sender(snapshot.rows.get(2)).equals("unlisted")
                        && snapshot.sender(snapshot.rows.get(3)).isEmpty(),
                "cross-session senders use literal known names, readable unknown keys and no"
                        + " self-label");
        revision = snapshot.revision;
        viewer.handle(
                Json.object(
                        "type",
                        "session_rows",
                        "key",
                        "ambient:home",
                        "initial",
                        true,
                        "rows",
                        new JSONArray()
                                .put(row("5", "lark:team"))
                                .put(row("6", "lark:team").put("failed", "yes"))));
        check(
                viewer.snapshot().revision == revision && viewer.snapshot().rows.size() == 4,
                "malformed row windows leave the existing thread intact, including initial"
                        + " windows");
        viewer.handle(Json.object("type", "sessions_error", "reason", "unknown"));
        check(
                viewer.snapshot().revision == revision && viewer.snapshot().error.isEmpty(),
                "unknown viewer error words do not turn into an authored failure state");

        JSONObject sent = viewer.notify("  hello  ", "correlated");
        viewer.open("lark:team");
        viewer.handle(
                Json.object("type", "session_notify_result", "id", "correlated", "ok", "true"));
        check(
                sent.getString("key").equals("ambient:home")
                        && viewer.snapshot().pending.equals("correlated"),
                "malformed acknowledgements cannot settle a notification after the reader changes"
                        + " sessions");
        viewer.handle(
                Json.object(
                        "type",
                        "session_notify_result",
                        "id",
                        "correlated",
                        "ok",
                        false,
                        "reason",
                        "unknown"));
        check(
                viewer.snapshot().pending.equals("correlated"),
                "unknown notification refusal keeps the current correlated send pending");
        viewer.handle(Json.object("type", "session_notify_result", "id", "correlated", "ok", true));
        snapshot = viewer.snapshot();
        check(
                snapshot.outcome.equals("sent")
                        && snapshot.openKey.equals("lark:team")
                        && snapshot.notifyKey.equals("ambient:home")
                        && snapshot.notifyId.equals("correlated")
                        && snapshot.notifyText.equals("hello"),
                "late notification receipts retain their original recipient and do not clear"
                        + " another draft");
        viewer.open("ambient:home");
        check(
                viewer.snapshot().outcome.equals("sent"),
                "changing the open session does not erase an unobserved delivery receipt");
        viewer.notify("retry", "refused");
        viewer.handle(
                Json.object(
                        "type",
                        "session_notify_result",
                        "id",
                        "refused",
                        "ok",
                        false,
                        "reason",
                        "not_notifiable"));
        check(
                viewer.snapshot().pending.isEmpty()
                        && viewer.snapshot().outcome.equals("not_notifiable"),
                "a known refusal settles the send with its actual reason");
        long generation = viewer.snapshot().generation;
        viewer.reset();
        check(
                viewer.snapshot().generation > generation
                        && viewer.snapshot().notifyId.isEmpty()
                        && viewer.snapshot().notifyKey.isEmpty()
                        && viewer.snapshot().sessions.isEmpty(),
                "room replacement clears session send ownership and all prior rows");
    }
}
