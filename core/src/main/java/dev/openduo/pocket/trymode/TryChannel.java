// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.trymode;

import dev.openduo.pocket.audio.PacketStream;
import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.net.ChannelConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * A disposable local API-v2 channel. All UI, outbox, history, speech and session code above this
 * boundary is shared with real rooms. Uploaded bytes use a private temporary directory so trying
 * large files cannot retain every upload on the small device's Java heap.
 */
public final class TryChannel implements ChannelConnection, AutoCloseable {
    public static final Endpoint ENDPOINT = new Endpoint("try.duoduo.local", 80, false);
    public static final String ROOM = "try", TOKEN = "local-try-only";
    private static final String VOICE = "application/vnd.ambient.opus-packets";
    private static final long STEP_MS = 900;

    public interface Resources {
        byte[] read(String name) throws IOException;
    }

    private final Resources resources;
    private final File directory;
    private final boolean english;
    private final long stepMs;
    private final List<TryScript.Turn> turns;
    private final ScheduledThreadPoolExecutor clock =
            new ScheduledThreadPoolExecutor(
                    1, task -> new Thread(task, "DuoDuo Pocket try channel"));
    private final List<JSONObject> log = new ArrayList<>();
    private final Map<String, Response> receipts = new LinkedHashMap<>();
    private final Map<String, File> files = new LinkedHashMap<>();
    private final Map<String, String> recognising = new LinkedHashMap<>();
    private final Map<String, JSONArray> sessionRows = new LinkedHashMap<>();
    private final Map<Long, AtomicBoolean> streams = new ConcurrentHashMap<>();
    private LiveListener live;
    private BooleanSupplier liveCurrent = () -> false;
    private volatile boolean active = true;
    private boolean audio, greeted, watching;
    private int next, liveSerial;
    private long lastSeq;
    private String openedSession = "";

    public TryChannel(Resources resources, File directory, boolean english) throws IOException {
        this(resources, directory, english, STEP_MS);
    }

    /** A deterministic pacing override is used by transport contracts, never by product UI. */
    public TryChannel(Resources resources, File directory, boolean english, long stepMs)
            throws IOException {
        this.resources = resources;
        this.directory = directory;
        this.english = english;
        this.stepMs = Math.max(1, stepMs);
        this.turns = TryScript.turns(english);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create local channel directory");
        clock.setRemoveOnCancelPolicy(true);
        sessionRows.put("try-chat", TryScript.rows(english, "try-chat"));
        sessionRows.put("try-job", TryScript.rows(english, "try-job"));
        append(
                Json.object(
                        "at",
                        now(),
                        "kind",
                        "answer",
                        "speaker",
                        "多多",
                        "text",
                        TryScript.welcome(english),
                        "unspoken",
                        true));
    }

    @Override
    public synchronized Response request(
            Endpoint endpoint,
            String token,
            String method,
            String path,
            JSONObject headers,
            byte[] body,
            long timeoutMs)
            throws Exception {
        requireActive(endpoint);
        URI uri = new URI(path);
        Map<String, String> query = query(uri);
        String route = method + " " + uri.getPath();
        if (route.equals("GET /healthz")) return json(200, Json.object("ok", true));
        if (route.equals("GET /api/state"))
            return json(
                    200,
                    Json.object(
                            "room",
                            ROOM,
                            "room_name",
                            english ? "Try it" : "体验房间",
                            "date",
                            Json.day(System.currentTimeMillis()),
                            "daemon_ok",
                            true,
                            "cerebellum_ok",
                            true));
        if (route.equals("GET /api/imlog")) {
            JSONArray entries = new JSONArray();
            boolean since = query.containsKey("since");
            long cursor = since ? Long.parseLong(query.get("since")) : 0;
            for (JSONObject row : log)
                if (since
                        ? row.optLong("seq") > cursor
                        : Json.day(Json.time(row.optString("at"))).equals(query.get("date")))
                    entries.put(row);
            return json(200, Json.object("room", ROOM, "entries", entries, "seq", lastSeq));
        }
        if (route.equals("POST /api/messages")) return message(headers, body);
        if (route.equals("POST /api/upload")) {
            String sha = store(body);
            return json(
                    200,
                    Json.object(
                            "name",
                            query.getOrDefault("name", "file"),
                            "mime",
                            header(headers, "Content-Type"),
                            "sha256",
                            sha,
                            "path",
                            "try/" + sha));
        }
        if (route.equals("GET /api/attachment")) {
            File file = files.get(query.get("sha256"));
            return file == null
                    ? error(404, "not_found")
                    : new Response(200, Files.readAllBytes(file.toPath()));
        }
        if (route.equals("POST /api/speech")) {
            JSONObject value;
            try {
                value = new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (Exception invalid) {
                return error(400, "bad_body");
            }
            String text = value.optString("text");
            if (text.trim().isEmpty()) return error(422, "nothing_to_speak");
            String clip =
                    text.equals(TryScript.ambient(english))
                            ? english ? "try-en-ambient" : "try-zh-ambient"
                            : "";
            for (TryScript.Turn turn : turns) if (turn.text.equals(text)) clip = turn.audio;
            if (clip.isEmpty()) return error(503, "cerebellum_unavailable");
            try {
                return new Response(200, resources.read(clip + ".opus-packets"));
            } catch (IOException missing) {
                return error(503, "cerebellum_unavailable");
            }
        }
        return error(404, "not_found");
    }

    private Response message(JSONObject headers, byte[] body) throws Exception {
        String id = header(headers, "Idempotency-Key").toLowerCase(Locale.ROOT);
        try {
            if (!UUID.fromString(id).toString().equals(id))
                return error(400, "idempotency_key_required");
        } catch (IllegalArgumentException invalid) {
            return error(400, "idempotency_key_required");
        }
        Response receipt = receipts.get(id);
        if (receipt != null) return new Response(receipt.status, receipt.body.clone());
        String mime =
                header(headers, "Content-Type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String at = now();
        JSONObject row;
        if (mime.equals("application/json")) {
            JSONObject value;
            try {
                value = new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (Exception invalid) {
                return error(400, "bad_body");
            }
            JSONArray attachments = value.optJSONArray("attachments");
            if (value.optString("text").trim().isEmpty()
                    && (attachments == null || attachments.length() == 0))
                return error(400, "bad_body");
            row =
                    Json.object(
                            "at",
                            at,
                            "kind",
                            "typed",
                            "text",
                            value.optString("text"),
                            "utt_id",
                            id);
            if (attachments != null) row.put("attachments", attachments);
        } else if (mime.equals(VOICE)) {
            String source = header(headers, "X-Voice-Source");
            if (!source.matches("phone|passport")) return error(400, "bad_body");
            int packets = 0;
            try {
                PacketStream reader = new PacketStream();
                for (int offset = 0; offset < body.length; offset += 4096)
                    packets +=
                            reader.push(
                                            Arrays.copyOfRange(
                                                    body,
                                                    offset,
                                                    Math.min(body.length, offset + 4096)))
                                    .size();
                reader.finish();
            } catch (IOException invalid) {
                return error(400, "bad_body");
            }
            if (packets == 0) return error(400, "bad_body");
            row =
                    Json.object(
                            "at",
                            at,
                            "kind",
                            "typed",
                            "text",
                            TryScript.voiceText(english, packets),
                            "voice_source",
                            source,
                            "utt_id",
                            id);
        } else return error(415, "unsupported_media_type");
        receipt = json(202, Json.object("message_id", id, "at", at));
        receipts.put(id, receipt);
        if (mime.equals(VOICE)) {
            recognising.put(id, row.optString("text"));
            at(
                    stepMs,
                    () -> {
                        recognising.remove(id);
                        append(row);
                        frame(
                                Json.object(
                                        "type",
                                        "message",
                                        "id",
                                        id,
                                        "state",
                                        "cleaned",
                                        "text",
                                        row.optString("text")));
                        answer(id);
                    });
        } else {
            append(row);
            answer(id);
        }
        return new Response(receipt.status, receipt.body.clone());
    }

    private void answer(String id) {
        TryScript.Turn turn = turns.get(next);
        next = next + 1 < turns.size() ? next + 1 : Math.min(1, turns.size() - 1);
        JSONArray ids = new JSONArray().put(id);
        frame(Json.object("type", "turn", "message_ids", ids, "phase", "received"));
        long delay = stepMs;
        at(
                delay,
                () -> frame(Json.object("type", "turn", "message_ids", ids, "phase", "thinking")));
        for (TryScript.Tool tool : turn.tools) {
            at(
                    delay += stepMs,
                    () ->
                            frame(
                                    Json.object(
                                            "type",
                                            "turn",
                                            "message_ids",
                                            ids,
                                            "phase",
                                            "tool",
                                            "label",
                                            tool.name,
                                            "input_summary",
                                            tool.input)));
            at(
                    delay += stepMs,
                    () ->
                            frame(
                                    Json.object(
                                            "type",
                                            "turn",
                                            "message_ids",
                                            ids,
                                            "phase",
                                            "tool",
                                            "label",
                                            tool.name + " ✓")));
        }
        at(
                delay + stepMs,
                () -> {
                    String speech = "c-" + id;
                    frame(
                            Json.object(
                                    "type",
                                    "answer_final",
                                    "speech_id",
                                    speech,
                                    "message_ids",
                                    ids,
                                    "text",
                                    turn.text));
                    boolean spoken = speak(speech, turn.audio);
                    append(
                            Json.object(
                                    "at",
                                    now(),
                                    "speaker",
                                    "多多",
                                    "kind",
                                    "answer",
                                    "text",
                                    turn.text,
                                    "unspoken",
                                    !spoken,
                                    "message_ids",
                                    ids));
                    if (turn.file) {
                        try {
                            byte[] bytes =
                                    resources.read(
                                            english ? "weekly-notes-en.md" : "weekly-notes.md");
                            String sha = store(bytes);
                            append(
                                    Json.object(
                                            "at",
                                            now(),
                                            "speaker",
                                            "多多",
                                            "kind",
                                            "answer",
                                            "text",
                                            "",
                                            "attachments",
                                            new JSONArray()
                                                    .put(
                                                            Json.object(
                                                                    "name",
                                                                    TryScript.notesName(english),
                                                                    "mime",
                                                                    "text/markdown",
                                                                    "sha256",
                                                                    sha,
                                                                    "path",
                                                                    "try/" + sha,
                                                                    "size",
                                                                    bytes.length)),
                                            "unspoken",
                                            true,
                                            "message_ids",
                                            ids));
                        } catch (IOException failure) {
                            frame(Json.object("type", "error", "error", "try_file_unavailable"));
                        }
                    }
                    frame(Json.object("type", "turn", "message_ids", ids, "phase", "idle"));
                });
    }

    @Override
    public synchronized void live(
            Endpoint endpoint,
            String token,
            String path,
            JSONObject headers,
            LiveListener listener,
            BooleanSupplier current)
            throws Exception {
        requireActive(endpoint);
        if (!current.getAsBoolean()) return;
        Map<String, String> query = query(new URI(path));
        live = listener;
        liveCurrent = current;
        audio = "1".equals(query.get("audio"));
        watching = false;
        openedSession = "";
        frame(Json.object("type", "meta", "conn", "try-live-" + ++liveSerial));
        if (audio) frame(Json.object("type", "audio_params", "rate", 16000));
        if (query.containsKey("since")) {
            long cursor = Long.parseLong(query.get("since"));
            for (JSONObject row : log)
                if (row.optLong("seq") > cursor)
                    frame(Json.object("type", "row", "seq", row.optLong("seq"), "row", row));
        }
        JSONArray messages = new JSONArray();
        for (String id : recognising.keySet()) messages.put(Json.object("id", id));
        frame(
                Json.object(
                        "type",
                        "ready",
                        "seq",
                        lastSeq,
                        "api",
                        2,
                        "turn",
                        JSONObject.NULL,
                        "messages",
                        messages));
    }

    @Override
    public synchronized void send(JSONObject outgoing) throws Exception {
        if (!isOpen()) throw new IOException("Local live socket is closed");
        // Copy caller-owned JSON before it can outlive this call.
        JSONObject value = new JSONObject(outgoing.toString());
        switch (value.optString("type")) {
            case "hello":
                frame(Json.object("type", "meta", "role", "master", "state", "listening"));
                if (!greeted) {
                    greeted = true;
                    boolean spoken =
                            speak("c-try-hello", english ? "try-en-ambient" : "try-zh-ambient");
                    append(
                            Json.object(
                                    "at",
                                    now(),
                                    "speaker",
                                    "多多",
                                    "kind",
                                    "answer",
                                    "text",
                                    TryScript.ambient(english),
                                    "unspoken",
                                    !spoken));
                }
                break;
            case "hush":
                frame(Json.object("type", "stop_audio"));
                break;
            case "sessions_watch":
                watching = true;
                openedSession = value.optString("open");
                frame(Json.object("type", "sessions", "sessions", TryScript.sessions(english)));
                if (sessionRows.containsKey(openedSession))
                    frame(
                            Json.object(
                                    "type",
                                    "session_rows",
                                    "key",
                                    openedSession,
                                    "initial",
                                    true,
                                    "rows",
                                    sessionRows.get(openedSession)));
                break;
            case "sessions_unwatch":
                watching = false;
                openedSession = "";
                break;
            case "session_notify":
                String id = value.optString("id"),
                        key = value.optString("key"),
                        text = value.optString("text");
                boolean allowed = key.equals("try-chat") && !id.isEmpty() && !text.trim().isEmpty();
                frame(
                        Json.object(
                                "type",
                                "session_notify_result",
                                "id",
                                id,
                                "ok",
                                allowed,
                                "reason",
                                allowed
                                        ? ""
                                        : !sessionRows.containsKey(key)
                                                ? "not_found"
                                                : !key.equals("try-chat")
                                                        ? "not_notifiable"
                                                        : "failed"));
                if (!allowed) break;
                JSONArray rows = sessionRows.get(key);
                String rowId = "n-" + id;
                for (int i = 0; i < rows.length(); i++)
                    if (rowId.equals(rows.getJSONObject(i).optString("id"))) return;
                JSONObject row =
                        Json.object(
                                "id",
                                rowId,
                                "at",
                                now(),
                                "kind",
                                "notify",
                                "from_label",
                                TryScript.notifySource(english),
                                "text",
                                text + (english ? " (local sample, not sent)" : "（本地模拟，未发送）"));
                rows.put(row);
                if (watching && key.equals(openedSession))
                    frame(
                            Json.object(
                                    "type",
                                    "session_rows",
                                    "key",
                                    key,
                                    "rows",
                                    new JSONArray().put(row)));
                break;
            default:
                break;
        }
    }

    @Override
    public synchronized void sendBinary(byte[] packet) throws IOException {
        if (!isOpen()) throw new IOException("Local live socket is closed");
        // Ambient audio is deliberately discarded: trying never recognises or retains the input.
    }

    private boolean speak(String id, String clip) {
        if (!audio || !isOpen()) return false;
        try {
            byte[] bytes = resources.read(clip + ".opus-packets");
            PacketStream parser = new PacketStream();
            frame(Json.object("type", "speech", "speech_id", id));
            for (int offset = 0; offset < bytes.length; offset += 4096)
                for (byte[] packet :
                        parser.push(
                                Arrays.copyOfRange(
                                        bytes, offset, Math.min(bytes.length, offset + 4096))))
                    if (isOpen()) live.binary(packet);
            parser.finish();
            frame(Json.object("type", "playback", "speech_id", id, "state", "done"));
            return true;
        } catch (IOException missing) {
            return false;
        }
    }

    @Override
    public Response stream(
            Endpoint endpoint,
            String token,
            long id,
            String path,
            byte[] body,
            StreamListener listener,
            BooleanSupplier current)
            throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        streams.put(id, cancelled);
        try {
            if (!active || !current.getAsBoolean()) return new Response(499, new byte[0]);
            Response response =
                    request(
                            endpoint,
                            token,
                            "POST",
                            path,
                            Json.object("Content-Type", "application/json"),
                            body,
                            60_000);
            if (response.status != 200) return response;
            for (int offset = 0; offset < response.body.length; offset += 4096) {
                if (!active || cancelled.get() || !current.getAsBoolean())
                    return new Response(499, new byte[0]);
                listener.data(
                        Arrays.copyOfRange(
                                response.body,
                                offset,
                                Math.min(offset + 4096, response.body.length)));
            }
            return new Response(200, new byte[0]);
        } finally {
            streams.remove(id, cancelled);
        }
    }

    @Override
    public void cancelStream(long id) {
        AtomicBoolean cancelled = streams.get(id);
        if (cancelled != null) cancelled.set(true);
    }

    @Override
    public synchronized boolean isOpen() {
        return active && live != null && liveCurrent.getAsBoolean();
    }

    @Override
    public synchronized void ping(long timeoutMs) throws IOException {
        if (!isOpen()) throw new IOException("Local live socket is closed");
    }

    @Override
    public void rebind() {
        // The disposable channel never touches the system network or embedded node.
    }

    @Override
    public synchronized void closeLive() {
        live = null;
        audio = watching = false;
        openedSession = "";
    }

    @Override
    public synchronized void close() {
        active = false;
        closeLive();
        clock.shutdownNow();
        for (AtomicBoolean cancelled : streams.values()) cancelled.set(true);
        log.clear();
        receipts.clear();
        recognising.clear();
        sessionRows.clear();
        for (File file : files.values()) file.delete();
        files.clear();
        directory.delete();
    }

    private void at(long delay, Runnable task) {
        clock.schedule(
                () -> {
                    synchronized (TryChannel.this) {
                        if (active) task.run();
                    }
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    private void append(JSONObject value) {
        lastSeq = Math.max(lastSeq + 1, System.currentTimeMillis());
        try {
            value.put("seq", lastSeq);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        log.add(value);
        frame(Json.object("type", "row", "seq", lastSeq, "row", value));
    }

    private void frame(JSONObject frame) {
        if (isOpen()) live.text(frame.toString());
    }

    private String store(byte[] bytes) throws IOException {
        String sha;
        try {
            StringBuilder value = new StringBuilder();
            for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes))
                value.append(String.format(Locale.ROOT, "%02x", part & 255));
            sha = value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        if (!files.containsKey(sha)) {
            File file = new File(directory, sha);
            Files.write(file.toPath(), bytes);
            files.put(sha, file);
        }
        return sha;
    }

    private void requireActive(Endpoint endpoint) throws IOException {
        if (!active || !ENDPOINT.identity().equals(endpoint.identity()))
            throw new IOException("Local channel is closed or origin does not match");
    }

    private static String now() {
        return java.time.Instant.now().toString();
    }

    private static Response json(int status, JSONObject value) {
        return new Response(status, value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Response error(int status, String reason) {
        return json(status, Json.object("error", reason));
    }

    private static String header(JSONObject headers, String key) {
        for (java.util.Iterator<String> keys = headers.keys(); keys.hasNext(); ) {
            String name = keys.next();
            if (name.equalsIgnoreCase(key)) return headers.optString(name);
        }
        return "";
    }

    private static Map<String, String> query(URI uri) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() != null)
            for (String item : uri.getRawQuery().split("&")) {
                String[] parts = item.split("=", 2);
                result.putIfAbsent(
                        URLDecoder.decode(parts[0], "UTF-8"),
                        parts.length == 1 ? "" : URLDecoder.decode(parts[1], "UTF-8"));
            }
        return result;
    }
}
