// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.net;

import dev.openduo.pocket.audio.PacketStream;
import dev.openduo.pocket.core.Attachment;
import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.Message;
import dev.openduo.pocket.core.RustProtocol;

import org.json.JSONArray;
import org.json.JSONObject;

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

    private RustProtocol.Request request(JSONObject command, byte[] body) throws Failure {
        try {
            return RustProtocol.request(endpoint, token, room, command, body);
        } catch (RustProtocol.Failure error) {
            throw new Failure("房间返回的数据无效", 0, false);
        }
    }

    private byte[] request(JSONObject command, byte[] body, long timeout, int status)
            throws Failure {
        RustProtocol.Request request = request(command, body);
        return call(request.method, request.path, request.headers, request.body, timeout, status);
    }

    public State state() throws Failure {
        return new State(json(request(Json.object("type", "state"), null, 20_000, 200)));
    }

    public Rows rows(long cursor) throws Failure {
        return readRows(Json.object("type", "rows", "cursor", cursor));
    }

    public Rows history(String date) throws Failure {
        return readRows(Json.object("type", "history", "date", date));
    }

    private Rows readRows(JSONObject command) throws Failure {
        JSONObject value = json(request(command, null, 20_000, 200));
        List<Message> messages = new ArrayList<>();
        JSONArray entries = value.optJSONArray("entries");
        if (entries == null) throw new Failure("历史响应缺少 entries", 200, false);
        for (int i = 0; i < entries.length(); i++) {
            Message message = mapEntry(entries.optJSONObject(i), -1);
            if (message != null) messages.add(message);
        }
        return new Rows(messages, value.optLong("seq", 0));
    }

    private static JSONObject surface(String display, boolean spoken) {
        return Json.object("display", display, "spoken", spoken);
    }

    public void sendText(
            String text,
            List<Attachment> attachments,
            String key,
            String display,
            boolean spoken,
            String place)
            throws Failure {
        JSONArray values = new JSONArray();
        for (Attachment a : attachments) {
            JSONObject value = Json.object("name", a.name, "mime", a.mime);
            try {
                if (!a.remotePath.isEmpty()) value.put("path", a.remotePath);
                if (!a.sha256.isEmpty()) value.put("sha256", a.sha256);
            } catch (org.json.JSONException impossible) {
                throw new AssertionError(impossible);
            }
            values.put(value);
        }
        request(
                Json.object(
                        "type",
                        "text",
                        "id",
                        key,
                        "text",
                        text,
                        "attachments",
                        values,
                        "surface",
                        surface(display, spoken),
                        "place",
                        place == null ? JSONObject.NULL : place),
                null,
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
        sendVoice(voiceBody(packets, null), source, key, display, spoken, place, timeoutMs);
    }

    /** Load once before retries; packet validation is performed by the shared Rust parser. */
    public static byte[] voiceBody(File packets, State state) throws Failure {
        admit(packets.length(), state, true);
        try (PacketStream parser = new PacketStream()) {
            byte[] bytes = Files.readAllBytes(packets.toPath());
            if (bytes.length == 0) throw new java.io.IOException("Empty voice note");
            parser.validate(bytes);
            admit(bytes.length, state, true);
            return bytes;
        } catch (Failure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new Failure("无法读取待发送语音", 0, false);
        }
    }

    public void sendVoice(
            byte[] bytes,
            String source,
            String key,
            String display,
            boolean spoken,
            String place,
            long timeoutMs)
            throws Failure {
        request(
                Json.object(
                        "type",
                        "voice",
                        "id",
                        key,
                        "source",
                        source,
                        "surface",
                        surface(display, spoken),
                        "place",
                        place == null ? JSONObject.NULL : place),
                bytes,
                timeoutMs,
                202);
    }

    private static void admit(long size, State state, boolean voice) throws Failure {
        if (state != null && state.uploadsDisabled) throw new Failure("房间已关闭上传", 503, false);
        if (state != null && state.uploadMaxBytes > 0 && size > state.uploadMaxBytes)
            throw new Failure(
                    voice ? "录音太长，超过上传上限" : "文件太大（上限 " + state.uploadMaxBytes / 1_048_576 + " MB）",
                    413,
                    false);
    }

    public Attachment upload(Attachment attachment, State state) throws Failure {
        admit(attachment.size, state, false);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(new File(attachment.localPath).toPath());
        } catch (Exception failure) {
            throw new Failure("读不了这个文件", 0, false);
        }
        admit(bytes.length, state, false);
        JSONObject response =
                json(
                        request(
                                Json.object(
                                        "type",
                                        "upload",
                                        "name",
                                        attachment.name,
                                        "mime",
                                        attachment.mime),
                                bytes,
                                60_000,
                                200));
        if (response.optString("path").isEmpty()) throw new Failure("上传响应缺少 path", 200, false);
        try {
            JSONObject result =
                    RustProtocol.call(
                            Json.object(
                                    "op",
                                    "uploaded",
                                    "attachment",
                                    Json.object("name", attachment.name, "mime", attachment.mime),
                                    "digest",
                                    sha256(bytes),
                                    "frame",
                                    response));
            return attachment.withUpload(result.optString("sha256"), result.optString("path"));
        } catch (RustProtocol.Failure failure) {
            throw new Failure("上传响应的文件校验不一致", 200, false);
        }
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

    public byte[] attachment(Attachment a) throws Failure {
        if (a.sha256.isEmpty()) throw new Failure("这个附件不可下载", 404, false);
        return request(
                Json.object(
                        "type",
                        "attachment",
                        "attachment",
                        Json.object("name", a.name, "mime", a.mime, "sha256", a.sha256)),
                null,
                60_000,
                200);
    }

    public byte[] readAloud(String text) throws Failure {
        return request(Json.object("type", "speech", "text", text), null, 60_000, 200);
    }

    public JSONObject pair(String code) throws Failure {
        JSONObject request = RustProtocol.call(Json.object("op", "pair_request", "code", code));
        byte[] response =
                call(
                        request.optString("method"),
                        request.optString("path"),
                        request.optJSONObject("headers"),
                        RustProtocol.bytes(request.optJSONArray("body")),
                        30_000,
                        200);
        // The response stays opaque until Rust validates the required credential/scope fields.
        try {
            JSONArray body = new JSONArray();
            for (byte b : response) body.put(b & 255);
            return RustProtocol.call(Json.object("op", "decode_pair", "body", body));
        } catch (RustProtocol.Failure error) {
            throw new Failure("配对响应不完整，请更新 ambient", 200, false);
        }
    }

    public String livePath(long cursor, boolean hasCursor, boolean audio) {
        return livePath(cursor, hasCursor, audio, true);
    }

    public String livePath(long cursor, boolean hasCursor, boolean audio, boolean media) {
        return RustProtocol.request(
                        endpoint,
                        token,
                        room,
                        Json.object(
                                "type",
                                "live",
                                "cursor",
                                hasCursor ? cursor : JSONObject.NULL,
                                "audio",
                                audio,
                                "media",
                                media),
                        null)
                .path;
    }

    private byte[] call(
            String method, String path, JSONObject headers, byte[] body, long timeout, int expected)
            throws Failure {
        boolean anonymous = !needsToken(path);
        if (!anonymous && token.isEmpty()) throw new Failure("请先配对房间", 401, false);
        ChannelTransport.Response response;
        try {
            response =
                    transport.request(
                            endpoint, anonymous ? "" : token, method, path, headers, body, timeout);
        } catch (Exception failure) {
            throw new Failure("暂时无法连接房间", 0, true);
        }
        if (response.status != expected) throw failure(response);
        return response.body;
    }

    public static Failure failure(ChannelTransport.Response response) {
        String reason;
        try {
            reason =
                    new JSONObject(new String(response.body, StandardCharsets.UTF_8))
                            .optString("error", "请求失败");
        } catch (Exception failure) {
            reason = "请求失败";
        }
        return new Failure(reason, response.status, false);
    }

    static boolean needsToken(String path) {
        String route = path.split("\\?", 2)[0];
        return !route.equals("/healthz") && !route.equals("/api/pair");
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
        List<String> ids = Json.strings(row.optJSONArray("message_ids"));
        String id = row.optString("utt_id");
        if (type == Message.MINE && id.isEmpty()) id = "typed:" + seq + ":" + row.optString("at");
        if (answer) {
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
                .withMessageIds(ids)
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
