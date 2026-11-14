// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.Attachment;
import dev.openduo.pocket.core.Endpoint;
import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.ChannelTransport;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/** Servers may omit an upload digest; the exact uploaded body remains its source of truth. */
public final class UploadContracts {
    private static int checked;
    private static final String DIGEST =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    public static int run() throws Exception {
        File file = File.createTempFile("duoduo-pocket-upload-contract-", ".txt");
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), bytes);
        UploadTransport transport = new UploadTransport();
        ChannelClient client =
                new ChannelClient(
                        new Endpoint("example.test", 443, true), "test", "room", transport);
        Attachment local =
                new Attachment(
                        "example.txt",
                        "text/plain",
                        file.getPath(),
                        "stale",
                        "",
                        bytes.length,
                        Attachment.UPLOADING);
        try {
            Attachment result = null;
            for (int variant = 0; variant < 3; variant++) {
                transport.response = Json.object("path", "/uploads/example.txt");
                if (variant == 1) transport.response.put("sha256", JSONObject.NULL);
                if (variant == 2) transport.response.put("sha256", "");
                result = client.upload(local, null);
                check(
                        result.sha256.equals(DIGEST)
                                && result.state == Attachment.UPLOADED
                                && result.remotePath.equals("/uploads/example.txt")
                                && result.localPath.equals(file.getPath())
                                && Arrays.equals(bytes, transport.uploaded),
                        "absent/null/empty upload digest derives from the exact body, not stale"
                                + " metadata: "
                                + variant);
            }
            client.sendText(
                    "attachment", Collections.singletonList(result), "stable", "phone", false, "");
            JSONObject sent = transport.message.getJSONArray("attachments").getJSONObject(0);
            check(
                    sent.getString("sha256").equals(DIGEST)
                            && sent.getString("path").equals(result.remotePath)
                            && !sent.has("localPath")
                            && Attachment.fromWire(result.json(false)).sha256.equals(DIGEST),
                    "the derived digest survives persistence and is sent with the real attachment"
                            + " metadata");
            transport.response =
                    Json.object(
                            "path",
                            "/uploads/example.txt",
                            "sha256",
                            DIGEST.toUpperCase(Locale.ROOT));
            check(
                    client.upload(local, null).sha256.equals(DIGEST),
                    "a matching server digest is accepted and canonicalized");
            boolean rejected = true;
            for (String digest :
                    new String[] {String.join("", Collections.nCopies(64, "0")), "bad"}) {
                transport.response = Json.object("path", "/uploads/example.txt", "sha256", digest);
                try {
                    client.upload(local, null);
                    rejected = false;
                } catch (ChannelClient.Failure failure) {
                    rejected &= !failure.transport && failure.status == 200;
                }
            }
            check(
                    rejected,
                    "malformed or conflicting server digests cannot admit a successful upload");
            transport.response = Json.object("sha256", DIGEST);
            boolean missingPath = false;
            try {
                client.upload(local, null);
            } catch (ChannelClient.Failure failure) {
                missingPath = !failure.transport && failure.status == 200;
            }
            check(
                    missingPath,
                    "a local digest cannot hide an upload acknowledgement without a path");
            return checked;
        } finally {
            file.delete();
        }
    }

    private static final class UploadTransport implements ChannelTransport {
        JSONObject response, message;
        byte[] uploaded;

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            if (method.equals("POST") && path.startsWith("/api/upload?")) {
                uploaded = body.clone();
                return new Response(200, response.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (method.equals("POST") && path.startsWith("/api/messages?")) {
                message = new JSONObject(new String(body, StandardCharsets.UTF_8));
                return new Response(202, new byte[0]);
            }
            throw new AssertionError("Unexpected request in upload contract");
        }
    }
}
