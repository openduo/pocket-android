// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.core.VoiceRetry;
import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.ChannelTransport;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Upstream elapsed-time retry policy and durable-body admission before the HTTP boundary. */
public final class VoiceUploadContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    public static int run() throws Exception {
        long[] clock = {100_000};
        VoiceRetry retry = new VoiceRetry(25_000, () -> clock[0]);
        check(
                retry.remainingMs() == 25_000,
                "one voice upload starts with the upstream 25-second budget");
        for (long expected : new long[] {500, 1000, 2000, 4000, 4000}) {
            check(
                    retry.nextDelayMs() == expected,
                    "voice backoff grows to the upstream four-second cap");
            clock[0] += expected;
        }
        clock[0] = 124_990;
        check(
                retry.nextDelayMs() == 10 && retry.remainingMs() == 10,
                "a retry wait is clamped to the entire upload's remaining elapsed time");
        clock[0] += 10;
        check(
                retry.remainingMs() == 0 && retry.nextDelayMs() == -1,
                "no extra request or backoff is admitted at the deadline");
        clock[0] += 100_000;
        check(
                retry.remainingMs() == 0 && retry.nextDelayMs() == -1,
                "a delayed executor cannot reopen an expired upload budget");

        File file = File.createTempFile("duoduo-pocket-voice-contract-", ".packets");
        byte[] framed = {3, 0, (byte) 0xf8, (byte) 0xff, (byte) 0xfe};
        Capture transport = new Capture();
        ChannelClient client =
                new ChannelClient(
                        new Endpoint("example.test", 443, true),
                        "fixture-token",
                        "room",
                        transport);
        try {
            Files.write(file.toPath(), framed);
            byte[] admitted = ChannelClient.voiceBody(file, client.state());
            Files.write(file.toPath(), new byte[] {0, 0});
            file.delete();
            client.sendVoice(
                    admitted, "phone", "stable-key", "later", true, "fixture-place", 24_000);
            client.sendVoice(
                    admitted, "phone", "stable-key", "later", true, "fixture-place", 19_000);
            check(
                    transport.calls == 2
                            && Arrays.equals(transport.body, framed)
                            && transport.headers.optString("Idempotency-Key").equals("stable-key")
                            && transport.headers.optString("X-Voice-Source").equals("phone")
                            && transport.headers.optString("X-Ambient-Display").equals("later")
                            && transport.headers.optString("X-Ambient-Spoken").equals("1")
                            && transport
                                    .headers
                                    .optString("X-Ambient-Place")
                                    .equals("fixture-place")
                            && transport
                                    .headers
                                    .optString("Content-Type")
                                    .equals("application/vnd.ambient.opus-packets")
                            && transport.timeout == 19_000,
                    "the admitted Opus body and original surface survive side-file removal on"
                            + " retry");
            boolean rejected = true;
            for (byte[] invalid : new byte[][] {{}, {3}, {3, 0, 1}, {0, 0}, {(byte) 0xfc, 4, 1}}) {
                Files.write(file.toPath(), invalid);
                try {
                    ChannelClient.voiceBody(file, null);
                    rejected = false;
                } catch (ChannelClient.Failure failure) {
                    rejected &= failure.status == 0 && !failure.transport;
                }
            }
            check(
                    rejected && transport.calls == 2,
                    "empty, truncated, zero-length and oversized packet frames fail locally without"
                            + " HTTP");
            Files.write(file.toPath(), framed);
            transport.state = Json.object("limits", Json.object("upload_max_bytes", 4));
            rejected = false;
            try {
                ChannelClient.voiceBody(file, client.state());
            } catch (ChannelClient.Failure failure) {
                rejected = failure.status == 413 && !failure.transport;
            }
            check(
                    rejected && transport.calls == 2,
                    "voice notes obey the advertised upload byte limit before any send");
            transport.state =
                    Json.object("limits", Json.object("upload_max_bytes", JSONObject.NULL));
            rejected = false;
            try {
                ChannelClient.voiceBody(file, client.state());
            } catch (ChannelClient.Failure failure) {
                rejected = failure.status == 503 && !failure.transport;
            }
            check(
                    rejected && transport.calls == 2,
                    "a null upload limit disables voice uploads as in the reference client");
            return checked;
        } finally {
            file.delete();
        }
    }

    private static final class Capture implements ChannelTransport {
        JSONObject state = Json.object(), headers;
        byte[] body;
        int calls;
        long timeout;

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout) {
            if (method.equals("GET") && path.startsWith("/api/state?"))
                return new Response(200, state.toString().getBytes(StandardCharsets.UTF_8));
            if (!method.equals("POST") || !path.startsWith("/api/messages?"))
                throw new AssertionError("Unexpected request in voice contract");
            calls++;
            this.headers = headers;
            this.body = body.clone();
            this.timeout = timeout;
            return new Response(202, new byte[0]);
        }
    }
}
