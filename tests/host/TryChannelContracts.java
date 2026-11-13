// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.core.Attachment;
import top.kuaner.pockete2.core.Endpoint;
import top.kuaner.pockete2.core.Json;
import top.kuaner.pockete2.core.Message;
import top.kuaner.pockete2.net.ChannelClient;
import top.kuaner.pockete2.net.ChannelConnection;
import top.kuaner.pockete2.net.ChannelTransport.Response;
import top.kuaner.pockete2.trymode.TryChannel;
import top.kuaner.pockete2.trymode.TryScript;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Real ChannelClient exercised against the local channel boundary, including delayed delivery. */
public final class TryChannelContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        if (!condition.getAsBoolean()) throw new AssertionError("Local channel event timed out");
    }

    public static int run() throws Exception {
        File temporary = Files.createTempDirectory("pocket-e2-try-contract-").toFile();
        File directory = new File(temporary, "channel");
        byte[] framed = new byte[6000];
        for (int i = 0; i < framed.length; i += 3) {
            framed[i] = 1;
            framed[i + 2] = (byte) 0xf8;
        }
        byte[] notes = "# prepared example\n".getBytes(StandardCharsets.UTF_8);
        Events first = new Events(), second = new Events();
        TryChannel channel =
                new TryChannel(name -> name.endsWith(".md") ? notes : framed, directory, false, 15);
        try {
            ChannelClient client =
                    new ChannelClient(
                            TryChannel.ENDPOINT, TryChannel.TOKEN, TryChannel.ROOM, channel);
            check(
                    client.health() >= 0 && client.state().room.equals("try"),
                    "local health and state traverse ChannelClient");
            boolean wrongOrigin = false;
            try {
                channel.request(
                        new Endpoint("example.test", 443, true),
                        "",
                        "GET",
                        "/healthz",
                        Json.object(),
                        new byte[0],
                        100);
            } catch (java.io.IOException expected) {
                wrongOrigin = true;
            }
            check(wrongOrigin, "try transport cannot be used with a real origin");
            channel.live(
                    TryChannel.ENDPOINT,
                    TryChannel.TOKEN,
                    client.livePath(0, false, true),
                    Json.object(),
                    first,
                    () -> true);
            check(
                    first.text.get(0).optString("type").equals("meta")
                            && first.last("ready").optInt("api") == 2,
                    "local socket emits protocol meta before ready");

            byte[] bytes = "uploaded example".getBytes(StandardCharsets.UTF_8);
            File upload = new File(temporary, "upload");
            Files.write(upload.toPath(), bytes);
            Attachment attachment =
                    client.upload(
                            new Attachment(
                                    "例 子.md",
                                    "text/markdown",
                                    upload.getPath(),
                                    "",
                                    "",
                                    bytes.length,
                                    Attachment.UPLOADING),
                            client.state());
            check(
                    attachment.sha256.matches("[0-9a-f]{64}")
                            && Arrays.equals(bytes, client.attachment(attachment)),
                    "try uploads and attachments make a digest-verified round trip");
            String id = UUID.randomUUID().toString();
            JSONObject headers =
                    Json.object("Idempotency-Key", id, "Content-Type", "application/json");
            Response original =
                    channel.request(
                            TryChannel.ENDPOINT,
                            TryChannel.TOKEN,
                            "POST",
                            "/api/messages?room=try",
                            headers,
                            Json.object("text", "first")
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8),
                            100);
            Response repeated =
                    channel.request(
                            TryChannel.ENDPOINT,
                            TryChannel.TOKEN,
                            "POST",
                            "/api/messages?room=try",
                            headers,
                            Json.object("text", "changed retry")
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8),
                            100);
            check(
                    original.status == 202 && Arrays.equals(original.body, repeated.body),
                    "duplicate idempotency key returns the original receipt");
            await(
                    () ->
                            first.text.stream()
                                    .anyMatch(f -> f.optString("type").equals("answer_final")));
            ChannelClient.Rows rows = client.rows(0);
            check(
                    rows.messages.stream()
                                            .filter(m -> m.kind == Message.MINE && m.id.equals(id))
                                            .count()
                                    == 1
                            && rows.messages.stream()
                                    .anyMatch(m -> m.id.equals(id) && m.text.equals("first")),
                    "duplicate submission cannot append or alter the typed log row");
            check(
                    rows.messages.stream().anyMatch(m -> m.id.equals("ans:" + id) && m.seq >= 0),
                    "prepared final settles through the real logged answer mapper");
            check(
                    client.rows(rows.cursor).messages.isEmpty()
                            && client.history("2000-01-01").messages.isEmpty(),
                    "try history honours both monotone cursors and local dates");

            String voiceId = UUID.randomUUID().toString();
            Response invalid =
                    channel.request(
                            TryChannel.ENDPOINT,
                            TryChannel.TOKEN,
                            "POST",
                            "/api/messages?room=try",
                            Json.object(
                                    "Idempotency-Key",
                                    voiceId,
                                    "Content-Type",
                                    "application/vnd.ambient.opus-packets",
                                    "X-Voice-Source",
                                    "phone"),
                            new byte[] {4, 0, 1},
                            100);
            check(invalid.status == 400, "truncated try voice is rejected before admission");
            File voice = new File(temporary, "voice");
            Files.write(voice.toPath(), new byte[] {1, 0, (byte) 0xf8});
            client.sendVoice(voice, "phone", voiceId, "phone", false, "");
            await(
                    () ->
                            first.text.stream()
                                    .anyMatch(
                                            f ->
                                                    f.optString("type").equals("message")
                                                            && f.optString("id").equals(voiceId)
                                                            && f.optString("state")
                                                                    .equals("cleaned")));
            check(
                    client.rows(0).messages.stream()
                            .anyMatch(
                                    m ->
                                            m.id.equals(voiceId)
                                                    && m.voiceSource.equals("phone")
                                                    && m.text.contains("不识别")),
                    "voice uses actual packet framing and explicit prepared recognition text");

            long cursor = client.rows(0).cursor;
            channel.closeLive();
            channel.live(
                    TryChannel.ENDPOINT,
                    TryChannel.TOKEN,
                    client.livePath(cursor, true, true),
                    Json.object(),
                    second,
                    () -> true);
            check(
                    second.text.stream()
                            .filter(f -> f.optString("type").equals("row"))
                            .allMatch(f -> f.optLong("seq") > cursor),
                    "reopened local live emits only rows after its cursor");
            int oldCount = first.text.size();
            channel.send(Json.object("type", "hello", "room", "try"));
            check(
                    second.last("meta").optString("role").equals("master")
                            && second.binary.get() > 0
                            && first.text.size() == oldCount,
                    "local ambient claims the seat and sends prepared packets only to the current"
                            + " socket");
            long greetings =
                    client.rows(0).messages.stream()
                            .filter(m -> m.text.equals(TryScript.ambient(false)))
                            .count();
            channel.send(Json.object("type", "hello", "room", "try"));
            check(
                    greetings == 1
                            && client.rows(0).messages.stream()
                                            .filter(m -> m.text.equals(TryScript.ambient(false)))
                                            .count()
                                    == 1,
                    "ambient hello is not repeated on seat reclaims");

            channel.send(Json.object("type", "sessions_watch", "open", "try-chat"));
            String notify = UUID.randomUUID().toString();
            JSONObject notification =
                    Json.object(
                            "type",
                            "session_notify",
                            "id",
                            notify,
                            "key",
                            "try-chat",
                            "text",
                            "hello");
            channel.send(notification);
            check(
                    second.last("session_notify_result").optBoolean("ok")
                            && second.last("session_rows")
                                    .getJSONArray("rows")
                                    .getJSONObject(0)
                                    .optString("text")
                                    .contains("未发送"),
                    "session notification acknowledgement and local disclosure traverse live"
                            + " frames");
            channel.send(notification);
            channel.send(Json.object("type", "sessions_watch", "open", "try-chat"));
            JSONArray watched = second.last("session_rows").getJSONArray("rows");
            long count = 0;
            for (int i = 0; i < watched.length(); i++)
                if (watched.getJSONObject(i).optString("id").equals("n-" + notify)) count++;
            check(count == 1, "local session notifications are idempotent and retained on rewatch");
            channel.send(
                    Json.object(
                            "type",
                            "session_notify",
                            "id",
                            "denied",
                            "key",
                            "try-job",
                            "text",
                            "hello"));
            check(
                    !second.last("session_notify_result").optBoolean("ok"),
                    "sample session without notify capability rejects notifications");

            client.sendText(
                    "table",
                    Collections.emptyList(),
                    UUID.randomUUID().toString(),
                    "phone",
                    false,
                    "");
            client.sendText(
                    "file",
                    Collections.singletonList(attachment),
                    UUID.randomUUID().toString(),
                    "phone",
                    false,
                    "");
            await(
                    () ->
                            second.text.stream()
                                    .anyMatch(
                                            f ->
                                                    f.optString("type").equals("row")
                                                            && f.optJSONObject("row")
                                                                            .optJSONArray(
                                                                                    "attachments")
                                                                    != null
                                                            && f.optJSONObject("row")
                                                                    .optString("kind")
                                                                    .equals("answer")));
            Message fileRow =
                    client.rows(0).messages.stream()
                            .filter(m -> m.kind == Message.DUODUO && !m.attachments.isEmpty())
                            .findFirst()
                            .get();
            check(
                    Arrays.equals(client.attachment(fileRow.attachments.get(0)), notes),
                    "scripted file is downloaded through the ordinary attachment API");
            check(
                    second.text.stream()
                            .anyMatch(
                                    f ->
                                            f.optString("phase").equals("tool")
                                                    && f.optString("label").equals("Write")),
                    "prepared tool calls use actual turn protocol frames");

            byte[] speechBody =
                    Json.object("text", TryScript.turns(false).get(0).text)
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream speech = new ByteArrayOutputStream();
            Response result =
                    channel.stream(
                            TryChannel.ENDPOINT,
                            TryChannel.TOKEN,
                            1,
                            "/api/speech?room=try",
                            speechBody,
                            chunk -> speech.write(chunk, 0, chunk.length),
                            () -> true);
            check(
                    result.status == 200 && Arrays.equals(speech.toByteArray(), framed),
                    "read aloud uses incremental framed speech from the same local transport");
            AtomicInteger delivered = new AtomicInteger();
            Response cancelled =
                    channel.stream(
                            TryChannel.ENDPOINT,
                            TryChannel.TOKEN,
                            2,
                            "/api/speech?room=try",
                            speechBody,
                            chunk -> {
                                delivered.addAndGet(chunk.length);
                                channel.cancelStream(2);
                            },
                            () -> true);
            check(
                    cancelled.status == 499 && delivered.get() == 4096,
                    "local speech cancellation prevents remaining chunks");
            boolean unavailable = false;
            try {
                client.readAloud("unknown text");
            } catch (ChannelClient.Failure expected) {
                unavailable = expected.status == 503;
            }
            check(unavailable, "unprepared read aloud reports unavailable synthesis");
        } finally {
            channel.close();
        }
        check(
                !directory.exists() && !channel.isOpen(),
                "closing try removes all owned uploads and closes live");
        boolean closed = false;
        try {
            channel.request(
                    TryChannel.ENDPOINT,
                    TryChannel.TOKEN,
                    "GET",
                    "/healthz",
                    Json.object(),
                    new byte[0],
                    100);
        } catch (java.io.IOException expected) {
            closed = true;
        }
        check(closed, "stale local clients cannot send after exiting try");
        try (java.util.stream.Stream<java.nio.file.Path> children =
                Files.walk(temporary.toPath())) {
            children.sorted(Collections.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        check(
                TryScript.turns(true).get(0).audio.equals("try-en-1")
                        && TryScript.sessions(true)
                                .getJSONObject(0)
                                .optString("name")
                                .startsWith("Sample"),
                "try scripts, sessions and clips follow the selected language");
        return checked;
    }

    private static final class Events implements ChannelConnection.LiveListener {
        final List<JSONObject> text = new CopyOnWriteArrayList<>();
        final AtomicInteger binary = new AtomicInteger();

        public void text(String value) {
            try {
                text.add(new JSONObject(value));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        public void binary(byte[] bytes) {
            binary.incrementAndGet();
        }

        public void closed(String reason) {}

        JSONObject last(String type) {
            for (int i = text.size() - 1; i >= 0; i--)
                if (text.get(i).optString("type").equals(type)) return text.get(i);
            throw new AssertionError("No " + type + " event");
        }
    }
}
