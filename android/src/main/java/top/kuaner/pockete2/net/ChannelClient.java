// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.net;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.core.Attachment;
import top.kuaner.pockete2.core.Endpoint;
import top.kuaner.pockete2.core.Json;
import top.kuaner.pockete2.core.Message;
import top.kuaner.pockete2.core.Settings;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Ambient API v2: JSON, idempotent messages and the exact upstream Opus packet framing. */
public final class ChannelClient {
    public static final class Failure extends Exception {
        public final int status;
        public final boolean transport;
        public final String reason;

        public Failure(String message, int status, boolean transport) {
            super(message);
            this.status = status;
            this.transport = transport;
            this.reason = message;
        }

        public boolean unauthorized() {
            return status == 401 && reason.equals("unauthorized");
        }
    }

    public static final class State {
        public final String room, roomName, date;
        public final boolean daemonOk, cerebellumOk, uploadsDisabled, protocolMismatch;
        public final long uploadMaxBytes;
        public final List<String> configIssues;

        State(JSONObject root) {
            room = root.optString("room");
            roomName = root.optString("room_name", room);
            date = root.optString("date");
            daemonOk = root.optBoolean("daemon_ok", true);
            cerebellumOk = root.optBoolean("cerebellum_ok", true);
            JSONObject limits = root.optJSONObject("limits");
            uploadsDisabled =
                    limits != null
                            && limits.has("upload_max_bytes")
                            && limits.isNull("upload_max_bytes");
            uploadMaxBytes = limits == null ? 0 : limits.optLong("upload_max_bytes", 0);
            JSONObject halt = root.optJSONObject("cerebellum_halt");
            protocolMismatch = halt != null && "protocol".equals(halt.optString("reason"));
            configIssues =
                    Collections.unmodifiableList(Json.strings(root.optJSONArray("config_issues")));
        }
    }

    public static final class Rows {
        public final List<Message> messages;
        public final long cursor;

        Rows(List<Message> messages, long cursor) {
            this.messages = messages;
            this.cursor = cursor;
        }
    }

    private final Endpoint endpoint;
    private final String token, room;
    private final ChannelTransport transport;

    public ChannelClient(Settings settings, ChannelTransport transport) {
        this(settings.endpoint(), settings.token(), settings.room(), transport);
    }

    public ChannelClient(Endpoint endpoint, String token, String room, ChannelTransport transport) {
        this.endpoint = endpoint;
        this.token = token;
        this.room = room;
        this.transport = transport;
    }

    public int health() throws Failure {
        long started = System.nanoTime();
        call("GET", "/healthz", Json.object(), new byte[0], 20_000, 200);
        return (int) ((System.nanoTime() - started) / 1_000_000);
    }

    public State state() throws Failure {
        return new State(json(call("GET", path("state"), Json.object(), new byte[0], 20_000, 200)));
    }

    public Rows rows(long cursor) throws Failure {
        return readRows(path("imlog") + "&since=" + cursor);
    }

    public Rows history(String date) throws Failure {
        return readRows(path("imlog") + "&date=" + Endpoint.query(date));
    }

    private Rows readRows(String path) throws Failure {
        JSONObject value = json(call("GET", path, Json.object(), new byte[0], 20_000, 200));
        List<Message> messages = new ArrayList<>();
        JSONArray entries = value.optJSONArray("entries");
        if (entries == null) throw new Failure("历史响应缺少 entries", 200, false);
        for (int i = 0; i < entries.length(); i++) {
            Message message = mapEntry(entries.optJSONObject(i), -1);
            if (message != null) messages.add(message);
        }
        return new Rows(messages, value.optLong("seq", 0));
    }

    public void sendText(
            String text,
            List<Attachment> attachments,
            String key,
            String display,
            boolean spoken,
            String place)
            throws Failure {
        JSONObject body = Json.object("text", text);
        JSONArray values = new JSONArray();
        try {
            for (Attachment attachment : attachments) {
                JSONObject value = Json.object("name", attachment.name, "mime", attachment.mime);
                if (!attachment.remotePath.isEmpty()) value.put("path", attachment.remotePath);
                if (!attachment.sha256.isEmpty()) value.put("sha256", attachment.sha256);
                values.put(value);
            }
            if (values.length() > 0) body.put("attachments", values);
        } catch (Exception failure) {
            throw new Failure("附件信息无效", 0, false);
        }
        JSONObject headers = messageHeaders(key, display, spoken, place);
        try {
            headers.put("Content-Type", "application/json");
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        call(
                "POST",
                path("messages"),
                headers,
                body.toString().getBytes(StandardCharsets.UTF_8),
                30_000,
                202);
    }

    public void sendVoice(
            File packets, String source, String key, String display, boolean spoken, String place)
            throws Failure {
        sendVoice(packets, source, key, display, spoken, place, 25_000);
    }

    public void sendVoice(
            File packets,
            String source,
            String key,
            String display,
            boolean spoken,
            String place,
            long timeoutMs)
            throws Failure {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(packets.toPath());
        } catch (Exception failure) {
            throw new Failure("无法读取待发送语音", 0, false);
        }
        JSONObject headers = messageHeaders(key, display, spoken, place);
        try {
            headers.put("Content-Type", "application/vnd.ambient.opus-packets");
            headers.put("X-Voice-Source", source);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        call("POST", path("messages"), headers, bytes, timeoutMs, 202);
    }

    public Attachment upload(Attachment attachment, State state) throws Failure {
        if (state != null && state.uploadsDisabled) throw new Failure("房间已关闭上传", 503, false);
        if (state != null && state.uploadMaxBytes > 0 && attachment.size > state.uploadMaxBytes)
            throw new Failure("文件太大（上限 " + state.uploadMaxBytes / 1_048_576 + " MB）", 413, false);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(new File(attachment.localPath).toPath());
        } catch (Exception failure) {
            throw new Failure("读不了这个文件", 0, false);
        }
        JSONObject response =
                json(
                        call(
                                "POST",
                                path("upload") + "&name=" + Endpoint.query(attachment.name),
                                Json.object("Content-Type", attachment.mime),
                                bytes,
                                60_000,
                                200));
        if (response.optString("path").isEmpty()) throw new Failure("上传响应缺少 path", 200, false);
        String digest = sha256(bytes);
        String supplied = response.isNull("sha256") ? "" : response.optString("sha256");
        if (!supplied.isEmpty() && !digest.equalsIgnoreCase(supplied))
            throw new Failure("上传响应的文件校验不一致", 200, false);
        // Older servers acknowledge only the path. Downloads and logged attachments need the
        // digest of the exact request body, rather than metadata saved before reading the file.
        return attachment.withUpload(digest, response.optString("path"));
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder value = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public byte[] attachment(Attachment attachment) throws Failure {
        if (attachment.sha256.isEmpty()) throw new Failure("这个附件不可下载", 404, false);
        return call(
                "GET",
                path("attachment")
                        + "&sha256="
                        + Endpoint.query(attachment.sha256)
                        + "&mime="
                        + Endpoint.query(attachment.mime)
                        + "&name="
                        + Endpoint.query(attachment.name),
                Json.object(),
                new byte[0],
                60_000,
                200);
    }

    public byte[] readAloud(String text) throws Failure {
        return call(
                "POST",
                path("speech"),
                Json.object("Content-Type", "application/json"),
                Json.object("text", text).toString().getBytes(StandardCharsets.UTF_8),
                60_000,
                200);
    }

    public JSONObject pair(String code) throws Failure {
        JSONObject response =
                json(
                        call(
                                "POST",
                                "/api/pair",
                                Json.object("Content-Type", "application/json"),
                                Json.object("code", code)
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8),
                                30_000,
                                200));
        if (response.optString("device_id").isEmpty()
                || response.optString("room").isEmpty()
                || response.optString("token").isEmpty()
                || !response.optString("scope").matches("room|sessions|notify")) {
            throw new Failure("配对响应不完整，请更新 ambient", 200, false);
        }
        return response;
    }

    public String livePath(long cursor, boolean hasCursor, boolean audio) {
        return livePath(cursor, hasCursor, audio, true);
    }

    public String livePath(long cursor, boolean hasCursor, boolean audio, boolean media) {
        return "/live?room="
                + Endpoint.query(room)
                + (hasCursor ? "&since=" + cursor : "")
                + "&audio="
                + (audio ? 1 : 0)
                + (media ? "&media=1" : "");
    }

    private String path(String name) {
        return "/api/" + name + "?room=" + Endpoint.query(room);
    }

    private JSONObject messageHeaders(String key, String display, boolean spoken, String place) {
        JSONObject value =
                Json.object(
                        "Idempotency-Key",
                        key,
                        "X-Ambient-Display",
                        display,
                        "X-Ambient-Spoken",
                        spoken ? "1" : "0");
        try {
            if (place != null && !place.isEmpty()) value.put("X-Ambient-Place", place);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        return value;
    }

    private byte[] call(
            String method, String path, JSONObject headers, byte[] body, long timeout, int expected)
            throws Failure {
        boolean anonymous = path.equals("/healthz") || path.equals("/api/pair");
        if (!anonymous && token.isEmpty()) throw new Failure("请先配对房间", 401, false);
        ChannelTransport.Response response;
        try {
            response =
                    transport.request(
                            endpoint, anonymous ? "" : token, method, path, headers, body, timeout);
        } catch (Exception failure) {
            throw new Failure("暂时无法连接房间", 0, true);
        }
        if (response.status != expected) {
            String reason;
            try {
                reason =
                        new JSONObject(new String(response.body, StandardCharsets.UTF_8))
                                .optString("error", "请求失败");
            } catch (Exception failure) {
                reason = "请求失败";
            }
            throw new Failure(reason, response.status, false);
        }
        return response.body;
    }

    private JSONObject json(byte[] bytes) throws Failure {
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new Failure("房间返回的数据无效", 200, false);
        }
    }

    public static Message mapEntry(JSONObject row, long sequence) {
        if (row == null) return null;
        long seq = row.optLong("seq", sequence);
        String kind = row.optString("kind");
        if (kind.equals("reaction")) return null;
        List<Attachment> attachments = new ArrayList<>();
        JSONArray a = row.optJSONArray("attachments");
        if (a != null)
            for (int i = 0; i < a.length(); i++) {
                Attachment value = Attachment.fromWire(a.optJSONObject(i));
                if (value != null) attachments.add(value);
            }
        boolean answer = kind.equals("answer") || row.optString("speaker").equals("多多");
        int type = kind.equals("typed") ? Message.MINE : answer ? Message.DUODUO : Message.HEARD;
        String id = row.optString("utt_id");
        if (type == Message.MINE && id.isEmpty()) id = "typed:" + seq + ":" + row.optString("at");
        if (answer) {
            List<String> ids = Json.strings(row.optJSONArray("message_ids"));
            id =
                    !ids.isEmpty() && attachments.isEmpty()
                            ? Json.answerKey(ids)
                            : "answer:" + seq + ":" + row.optString("at");
        }
        if (type == Message.HEARD) id = "heard:" + seq + ":" + row.optString("at");
        return new Message(
                        id,
                        type,
                        Json.time(row.optString("at")),
                        row.optString("full_text", row.optString("text")),
                        row.optString("voice_source"),
                        0,
                        Message.DELIVERED,
                        "",
                        true,
                        row.optBoolean("truncated"),
                        attachments,
                        seq)
                .withPresentation(
                        row.optString("speaker"),
                        answer
                                ? row.optBoolean("unspoken")
                                                || row.optString("full_text", row.optString("text"))
                                                        .trim()
                                                        .isEmpty()
                                        ? Message.UNSPOKEN
                                        : row.optBoolean("truncated")
                                                ? Message.PARTLY_SPOKEN
                                                : Message.SPOKEN
                                : Message.SPOKEN_UNKNOWN);
    }
}
