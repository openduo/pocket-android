// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import org.json.*;

import top.kuaner.pockete2.audio.PacketStream;
import top.kuaner.pockete2.core.*;
import top.kuaner.pockete2.net.*;
import top.kuaner.pockete2.passport.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Contracts {
    private static int checked;

    private static void check(boolean condition, String name) {
        checked++;
        if (!condition) throw new AssertionError(name);
    }

    private static void rejects(Runnable run, String name) {
        boolean rejected = false;
        try {
            run.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, name);
    }

    public static void main(String[] args) throws Exception {
        check(
                !top.kuaner.pockete2.passport.PassportPolicy.ENABLED,
                "standalone E2 keeps deferred Passport integration disabled");
        Endpoint.PairingLink p =
                Endpoint.PairingLink.parse(
                        "请用 duoduopocket://pair?host=example.test&port=8443&tls=0&code=a%2Bb-opaque"
                                + " 配对");
        check(
                p.endpoint.origin().equals("http://example.test:8443")
                        && p.code.equals("a+b-opaque"),
                "opaque pairing code and TLS");
        check(
                new Endpoint("2001:db8::1", 443, true).origin().equals("https://[2001:db8::1]"),
                "IPv6 origin");
        rejects(() -> new Endpoint("https://example.test", 443, true), "host cannot inject scheme");
        rejects(
                () -> new Endpoint("example.test@evil.test", 443, true),
                "host cannot inject userinfo");
        rejects(
                () -> Endpoint.PairingLink.parse("duoduopocket://pair?host=test&code="),
                "missing code");
        check(Endpoint.query("A B&中").equals("A%20B%26%E4%B8%AD"), "query encoding");
        String qrLink = "duoduopocket://pair?host=example.test&code=opaque%2Bvalue";
        com.google.zxing.common.BitMatrix qr =
                new com.google.zxing.qrcode.QRCodeWriter()
                        .encode(qrLink, com.google.zxing.BarcodeFormat.QR_CODE, 256, 256);
        byte[] luminance = new byte[256 * 256];
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) luminance[y * 256 + x] = (byte) (qr.get(x, y) ? 0 : 255);
        String scanned = top.kuaner.pockete2.pairing.QrCode.read(luminance, 256, 256);
        check(
                qrLink.equals(scanned)
                        && Endpoint.PairingLink.parse(scanned).code.equals("opaque+value"),
                "local QR scan preserves authoritative pairing link");
        check(
                top.kuaner.pockete2.pairing.QrCode.read(new byte[10], 640, 480) == null,
                "short camera luminance buffer rejected");
        Message heard =
                ChannelClient.mapEntry(
                        Json.object("seq", 40, "kind", "heard", "speaker", "V2", "text", "nearby"),
                        40);
        Attachment localAttachment =
                new Attachment(
                        "original.pdf",
                        "application/pdf",
                        "/private/app/local-file",
                        String.join("", Collections.nCopies(64, "a")),
                        "/uploaded.pdf",
                        42,
                        Attachment.FAILED);
        Message remoteAttachment =
                ChannelClient.mapEntry(
                        Json.object(
                                "kind",
                                "typed",
                                "attachments",
                                new JSONArray().put(localAttachment.json(true))),
                        39);
        check(
                remoteAttachment.attachments.get(0).localPath.isEmpty()
                        && remoteAttachment.attachments.get(0).state == Attachment.UPLOADED
                        && remoteAttachment.attachments.get(0).name.equals("original.pdf")
                        && remoteAttachment
                                .attachments
                                .get(0)
                                .sha256
                                .equals(localAttachment.sha256),
                "wire attachments retain remote metadata without authority to read a private local"
                        + " file or impersonate an upload state");
        check(
                Attachment.from(localAttachment.json(true))
                                .localPath
                                .equals(localAttachment.localPath)
                        && Attachment.from(localAttachment.json(true)).state == Attachment.FAILED,
                "local draft persistence still restores its owned file and upload lifecycle state");
        check(
                heard.speaker.equals("V2") && heard.kind == Message.HEARD,
                "heard speaker labels survive mapping");
        Message spoken =
                ChannelClient.mapEntry(
                        Json.object(
                                "seq",
                                41,
                                "kind",
                                "answer",
                                "text",
                                "part",
                                "full_text",
                                "whole",
                                "truncated",
                                true),
                        41);
        Message retained =
                Message.from(
                        spoken.withText("whole", true, true)
                                .withIdentity("stable")
                                .withDuration(20)
                                .json());
        check(
                retained.text.equals("whole")
                        && retained.spoken == Message.PARTLY_SPOKEN
                        && retained.truncated,
                "spoken metadata survives replacement and persistence");
        check(
                ChannelClient.mapEntry(
                                        Json.object(
                                                "kind",
                                                "answer",
                                                "unspoken",
                                                true,
                                                "text",
                                                "quiet"),
                                        42)
                                .spoken
                        == Message.UNSPOKEN,
                "unspoken answer is not labelled played");
        check(
                ChannelClient.mapEntry(
                                Json.object("kind", "heard", "speaker", "V?", "text", "nearby"), 43)
                        .speaker
                        .isEmpty(),
                "unknown speaker label stays hidden");
        AmbientInput edge = new AmbientInput();
        check(
                edge.releaseMicrophone() == null,
                "an edge without hello cannot change a room's microphone on exit");
        check(
                edge.meta(Json.object("conn", "first", "media", 1), "room").isEmpty(),
                "seat is not claimed before an encoded packet");
        List<JSONObject> hello = edge.packet("room", true);
        check(
                hello.size() == 2
                        && hello.get(0).getBoolean("aec")
                        && hello.get(1).getBoolean("on"),
                "real capture sends hello then microphone-on");
        edge.meta(Json.object("role", "peer", "state", "listening"), "room");
        check(
                !edge.sendsAudio() && edge.takeover("room").size() == 2 && edge.sendsAudio(),
                "peer waits for explicit takeover");
        edge.press("phone", true);
        edge.press("passport", true);
        check(
                edge.releaseMicrophone().getBoolean("on"),
                "a muted capture edge restores the room mic before releasing its seat");
        check(
                edge.press("phone", false).isEmpty() && edge.pressMuted(),
                "phone release cannot unmute an overlapping Passport press");
        check(
                edge.press("passport", false).get(0).getBoolean("on"),
                "last press restores microphone capture switch");
        check(
                edge.releaseMicrophone().getBoolean("on"),
                "teardown retains mic restoration when the queued press release can be discarded");
        edge.socketOpened();
        check(
                !edge.helloSent() && edge.meta(Json.object("conn", "next"), "room").size() == 2,
                "new socket reclaims capture after meta");
        check(
                edge.releaseMicrophone() == null,
                "the fresh unmuted hello does not inherit a former socket's restore obligation");
        edge.captureStopped();
        check(
                !edge.sendsAudio() && edge.meta(Json.object("conn", "third"), "room").isEmpty(),
                "stopped capture never claims a new seat");
        TurnState turn = new TurnState();
        turn.handle(
                Json.object(
                        "type",
                        "turn",
                        "phase",
                        "received",
                        "message_ids",
                        new JSONArray(Arrays.asList("short"))),
                100);
        turn.handle(
                Json.object("type", "turn", "phase", "tool", "label", "mcp__server__search"), 110);
        turn.handle(
                Json.object(
                        "type",
                        "turn",
                        "phase",
                        "tool",
                        "label",
                        "mcp__server__search",
                        "input_summary",
                        "{\"query\":\"hello   world\"}"),
                120);
        turn.handle(
                Json.object("type", "turn", "phase", "tool", "label", "mcp__server__search ✓"),
                130);
        check(
                turn.working().steps.size() == 1
                        && turn.working().steps.get(0).done
                        && turn.working().steps.get(0).summary.equals("hello world"),
                "three tool frames collapse");
        turn.settled(
                Message.answer(
                        "ans:shorter", "unrelated", true, false, 1, Collections.emptyList()));
        check(turn.working() != null, "substring id must not settle turn");
        Message answer =
                turn.handle(
                        Json.object(
                                "type",
                                "answer_final",
                                "message_ids",
                                new JSONArray(Arrays.asList("short")),
                                "text",
                                "done"),
                        210);
        check(
                answer.id.equals("ans:short")
                        && turn.working() == null
                        && turn.trace(answer.id).elapsedMs == 110,
                "correlated final and trace");
        turn.restore(
                Json.object(
                        "phase", "thinking", "message_ids", new JSONArray(Arrays.asList("new"))));
        check(turn.working() != null, "ready restores working turn");
        turn.restore(null);
        check(turn.working() == null, "ready clears ended turn");
        byte[] wire = {3, 0, 1, 2, 3, 2, 0, 4, 5};
        PacketStream packets = new PacketStream();
        List<byte[]> decoded = new ArrayList<>();
        for (byte b : wire) decoded.addAll(packets.push(new byte[] {b}));
        packets.finish();
        check(
                decoded.size() == 2 && Arrays.equals(decoded.get(0), new byte[] {1, 2, 3}),
                "arbitrary stream chunks");
        boolean cut = false;
        try {
            packets.push(new byte[] {3});
            packets.finish();
        } catch (java.io.IOException expected) {
            cut = true;
        }
        check(cut, "truncated packet rejected");
        byte[] reply =
                PocketProtocol.reply(123, true, new String(new char[2500]).replace('\0', '中'));
        PocketProtocol.Receiver receiver =
                new PocketProtocol.Receiver(PocketProtocol.Direction.PHONE_TO_DEVICE);
        PocketProtocol.Message result = null;
        for (byte[] fragment : PocketProtocol.fragments(PocketProtocol.REPLY, reply, 23, true))
            result = receiver.push(fragment);
        check(
                result != null
                        && result.quiet
                        && result.data.length <= 4096
                        && result.text(5).endsWith("…")
                        && !result.text(5).contains("�"),
                "UTF8 fitting, fragmentation and quiet");
        PocketProtocol.Receiver central =
                new PocketProtocol.Receiver(PocketProtocol.Direction.DEVICE_TO_PHONE);
        check(
                central.push(
                                PocketProtocol.fragments(
                                        PocketProtocol.PRESS_START,
                                        PocketProtocol.u16s(5),
                                        23,
                                        false)[0])
                        != null,
                "central decodes device traffic");
        check(
                central.push(
                                PocketProtocol.fragments(
                                        PocketProtocol.APP_STATE, new byte[] {0, 0}, 23, false)[0])
                        == null,
                "central rejects wrong direction");
        PressAssembler press = new PressAssembler();
        check(
                Arrays.equals(PocketProtocol.appState(0, true), new byte[] {0, 1})
                        && Arrays.equals(PocketProtocol.appState(2, false), new byte[] {2, 0}),
                "Passport app-state carries the resolved UI language without changing"
                        + " reachability");
        press.handle(message(PocketProtocol.PRESS_START, PocketProtocol.u16s(9)));
        press.handle(message(PocketProtocol.AUDIO, new byte[] {9, 0, 0, 0, 1}));
        press.handle(message(PocketProtocol.AUDIO, new byte[] {9, 0, 2, 0, 2}));
        press.handle(message(PocketProtocol.AUDIO, new byte[] {9, 0, 2, 0, 2}));
        PressAssembler.Press note =
                press.handle(message(PocketProtocol.PRESS_END, PocketProtocol.u16s(9, 3))).get(0);
        check(
                note.packets.size() == 2 && note.gaps == 1 && note.duplicates == 1,
                "press loss and duplicate accounting");
        check(
                press.handle(message(PocketProtocol.PRESS_END, PocketProtocol.u16s(9, 3)))
                        .isEmpty(),
                "duplicate end does not upload twice");
        press.handle(message(PocketProtocol.AUDIO, new byte[] {10, 0, (byte) 255, (byte) 255, 1}));
        press.handle(message(PocketProtocol.AUDIO, new byte[] {10, 0, 0, 0, 1}));
        note = press.lost().get(0);
        check(
                note.implicit
                        && note.packets.size() == 2
                        && note.gaps == 0
                        && note.end.equals("link_lost"),
                "implicit press, seq wrap, link-loss upload");
        FakeTransport fake = new FakeTransport();
        ChannelClient client =
                new ChannelClient(
                        new Endpoint("server.test", 443, true), "private-test-token", "A B", fake);
        client.sendText("hello", Collections.emptyList(), "stable-id", "phone", false, "");
        check(
                fake.path.equals("/api/messages?room=A%20B")
                        && fake.headers.getString("Idempotency-Key").equals("stable-id")
                        && fake.token.equals("private-test-token"),
                "message path, auth, stable id");
        fake.status = 500;
        boolean http = false;
        try {
            client.sendText("hello", Collections.emptyList(), "stable-id", "phone", false, "");
        } catch (ChannelClient.Failure e) {
            http = !e.transport && e.status == 500;
        }
        check(http, "5xx remains HTTP failure");
        fake.broken = true;
        boolean network = false;
        try {
            client.state();
        } catch (ChannelClient.Failure e) {
            network = e.transport && e.status == 0;
        }
        check(network, "network failure distinguished");
        SessionViewer viewer = new SessionViewer();
        check(
                viewer.show(true).getString("type").equals("sessions_watch")
                        && viewer.watchFrame().isNull("open"),
                "session list watch");
        viewer.handle(
                Json.object(
                        "type",
                        "sessions",
                        "sessions",
                        new JSONArray()
                                .put(
                                        Json.object(
                                                "key",
                                                "job:qa",
                                                "name",
                                                "QA",
                                                "group",
                                                "job",
                                                "status",
                                                "active",
                                                "tools",
                                                new JSONArray(),
                                                "notify",
                                                true))));
        viewer.open("job:qa");
        JSONObject notification = viewer.notify("  hello  ", "notify-1");
        check(
                notification.getString("text").equals("hello")
                        && viewer.notify("duplicate", "notify-2") == null,
                "one correlated session notification");
        viewer.handle(Json.object("type", "session_notify_result", "id", "wrong", "ok", true));
        check(viewer.snapshot().pending.equals("notify-1"), "unrelated notify result ignored");
        viewer.dropPending();
        check(
                viewer.snapshot().pending.isEmpty() && viewer.snapshot().outcome.equals("failed"),
                "disconnect settles uncertain notify");
        viewer.handle(
                Json.object(
                        "type",
                        "session_rows",
                        "key",
                        "job:qa",
                        "initial",
                        true,
                        "gap",
                        true,
                        "rows",
                        new JSONArray()
                                .put(
                                        Json.object(
                                                "id",
                                                "1",
                                                "at",
                                                "2026-11-14T00:00:00Z",
                                                "kind",
                                                "reply",
                                                "text",
                                                "old"))));
        viewer.handle(
                Json.object(
                        "type",
                        "session_rows",
                        "key",
                        "job:qa",
                        "rows",
                        new JSONArray()
                                .put(
                                        Json.object(
                                                "id",
                                                "1",
                                                "at",
                                                "2026-11-14T00:00:01Z",
                                                "kind",
                                                "reply",
                                                "text",
                                                "new"))));
        check(
                viewer.snapshot().rows.size() == 2
                        && viewer.snapshot().rows.get(1).text.equals("new"),
                "session gap and stable row replacement");
        check(
                viewer.show(false).getString("type").equals("sessions_unwatch"),
                "watch ends when page closes");
        ReplyTracker tracker = new ReplyTracker();
        tracker.expect("press-1");
        List<ReplyTracker.Output> proactive =
                tracker.answer(Arrays.asList("other"), "unrelated", "a", true, 1000000);
        check(
                tracker.waiting && proactive.get(0).quiet,
                "unrelated answer does not end press wait and is quiet");
        List<ReplyTracker.Output> matched =
                tracker.answer(Arrays.asList("press-1"), "matched", "b", true, 1000000);
        long matchedId = PocketProtocol.u32(matched.get(0).payload, 0);
        check(!tracker.waiting && !matched.get(0).quiet, "press answer alerts while foreground");
        tracker.handed(matched.get(0));
        check(tracker.linkReady(true).isEmpty(), "ready reconnect does not repeat handed answer");
        check(
                tracker.answer(Arrays.asList("press-1"), "matched", "", false, 1000100).isEmpty(),
                "log row settles live final without duplicate reply");
        ReplyTracker restored = new ReplyTracker();
        restored.restore(tracker.json());
        check(
                restored.answer(Arrays.asList("other"), "unrelated", "", false, 1000100).isEmpty(),
                "restart retains earlier reply dedup signatures");
        long priorSerial = restored.serial;
        restored.resetRoom();
        check(
                restored.latestText.isEmpty()
                        && restored.linkReady(false).isEmpty()
                        && restored.serial == priorSerial,
                "room switch clears reply text and keeps monotone ids");
        List<ReplyTracker.Output> after =
                restored.answer(Arrays.asList("new"), "later", "", false, 999000);
        check(
                PocketProtocol.u32(after.get(0).payload, 0) > matchedId,
                "reply counter survives backward clock and relaunch");
        restored.adopt();
        check(
                restored.turn("idle", Collections.emptyList()).size() == 2 && !restored.waiting,
                "adopted wait ends on idle");
        JSONObject camera =
                Json.object(
                        "width",
                        640,
                        "height",
                        480,
                        "video",
                        Json.object(
                                "codec",
                                "avc1.42e01f",
                                "fps",
                                15,
                                "min_fps",
                                5,
                                "bitrate",
                                200000,
                                "key_interval_ms",
                                1000));
        top.kuaner.pockete2.sight.SightProtocol.Ask ask =
                new top.kuaner.pockete2.sight.SightProtocol.Ask(camera);
        check(
                ask.fps == 15 && ask.profile == 66 && ask.level == 31,
                "camera ask retains exact server profile and rate");
        JSONObject invalid = new JSONObject(camera.toString());
        invalid.getJSONObject("video").put("min_fps", true);
        rejects(
                () -> new top.kuaner.pockete2.sight.SightProtocol.Ask(invalid),
                "camera boolean cannot masquerade as integer");
        top.kuaner.pockete2.sight.SightProtocol.Uplink video =
                new top.kuaner.pockete2.sight.SightProtocol.Uplink();
        byte[] keyFrame =
                video.frame(
                        true, 100, new byte[] {0, 0, 0, 1, 5}, 640, 480, "room", 15, 200000, 1024);
        check(
                keyFrame[0] == 2 && keyFrame[1] == 3 && PocketProtocol.u32(keyFrame, 2) == 0,
                "media-v1 video config and rate flags");
        byte[] delta = video.frame(false, 50, new byte[] {1}, 640, 480, "room", 15, 200000, 1024);
        check(
                delta[1] == 0 && PocketProtocol.u32(delta, 6) == 101,
                "camera clock remains monotone");
        check(
                video.frame(false, 102, new byte[2000], 640, 480, "room", 15, 200000, 1024) == null
                        && video.frame(
                                        false,
                                        103,
                                        new byte[] {1},
                                        640,
                                        480,
                                        "room",
                                        15,
                                        200000,
                                        1024)
                                == null,
                "oversized frame requires a new key");
        check(
                video.frame(true, 104, new byte[] {1}, 640, 480, "room", 5, 66000, 1024) != null,
                "new key resumes video and declares rate change");
        video.reset();
        check(
                PocketProtocol.u32(
                                video.frame(
                                        true, 1, new byte[] {1}, 640, 480, "room", 5, 66000, 1024),
                                2)
                        == 0,
                "video numbering resets only for a new connection");
        check(
                client.livePath(12, true, true, false).contains("since=12")
                        && !client.livePath(12, true, true, false).contains("media="),
                "incompatible media falls back to cursor-resumed audio");
        check(
                Arrays.equals(
                        top.kuaner.pockete2.sight.SightProtocol.annexB(
                                new byte[] {0, 0, 0, 2, 7, 8, 0, 0, 0, 1, 9}),
                        new byte[] {0, 0, 0, 1, 7, 8, 0, 0, 0, 1, 9}),
                "AVCC access units convert to Annex-B");
        Outbox.Item surface =
                new Outbox.Item(
                                "surface",
                                "x",
                                false,
                                "",
                                "",
                                0,
                                Collections.emptyList(),
                                Message.QUEUED,
                                "",
                                "",
                                1,
                                0)
                        .surface("phone", true, "place");
        surface = Outbox.Item.from(surface.state(Message.FAILED, "retry").json());
        check(
                surface.display.equals("phone") && surface.spoken && surface.place.equals("place"),
                "first-attempt surface survives persistence and retry");
        PlaceLedger ledger = new PlaceLedger(null);
        check(ledger.arrived("A", "市区", 1000000), "first arrival recorded");
        String placeA = ledger.pending();
        check(
                !placeA.contains("市") && java.net.URLDecoder.decode(placeA, "UTF-8").contains("+"),
                "place header is ASCII with explicit timezone offset");
        check(!ledger.arrived("A", "市区", 2000000), "same place retains first arrival");
        ledger.settle(placeA);
        check(ledger.pending().isEmpty(), "only accepted place settles");
        ledger.arrived("B", "新区", 3000000);
        String placeB = ledger.pending();
        check(
                new JSONObject(java.net.URLDecoder.decode(placeB, "UTF-8"))
                        .getJSONObject("previous")
                        .getString("name")
                        .equals("A"),
                "new place carries previous name");
        ledger.settle(placeB);
        ledger.settle(placeA);
        check(ledger.pending().isEmpty(), "late old receipt cannot replace newer place");
        ledger.arrived("C", "新区", 4000789);
        ledger.settle(ledger.pending());
        check(
                ledger.pending().isEmpty(),
                "millisecond arrival settles at wire timestamp precision");
        check(
                !ledger.arrived("old", "新区", 3000123),
                "delayed old location cannot replace latest arrival");
        check(
                PlaceLedger.area("北京市", "朝阳区").equals("北京市朝阳区")
                        && PlaceLedger.area("San Francisco", "Mission")
                                .equals("Mission, San Francisco"),
                "CJK and western area ordering");
        java.nio.ByteBuffer yuvY =
                java.nio.ByteBuffer.wrap(
                        new byte[] {
                            9, 16, 40, 80, 100, 0, 0, 120, (byte) 150, (byte) 200, (byte) 235
                        });
        yuvY.position(1);
        top.kuaner.pockete2.sight.PreviewFrame.Plane yp =
                new top.kuaner.pockete2.sight.PreviewFrame.Plane(yuvY, 6, 1);
        top.kuaner.pockete2.sight.PreviewFrame.Plane uv =
                new top.kuaner.pockete2.sight.PreviewFrame.Plane(
                        java.nio.ByteBuffer.wrap(new byte[] {(byte) 128, 0, (byte) 128}), 3, 2);
        top.kuaner.pockete2.sight.PreviewFrame unrotated =
                top.kuaner.pockete2.sight.PreviewFrame.yuv(4, 2, yp, uv, uv, 0, 4);
        int[] basePixels = new int[8];
        unrotated.copyPixels(basePixels);
        check(
                unrotated.width == 4
                        && unrotated.height == 2
                        && basePixels[0] == 0xff000000
                        && basePixels[7] == 0xffffffff,
                "viewfinder respects plane positions, padded rows and shared interleaved chroma");
        top.kuaner.pockete2.sight.PreviewFrame clockwise =
                top.kuaner.pockete2.sight.PreviewFrame.yuv(4, 2, yp, uv, uv, 90, 4);
        int[] rotatedPixels = new int[8];
        clockwise.copyPixels(rotatedPixels);
        check(
                clockwise.width == 2
                        && clockwise.height == 4
                        && rotatedPixels[1] == basePixels[0]
                        && rotatedPixels[6] == basePixels[7],
                "rear viewfinder rotation matches the encoded camera plane rotation");
        check(
                top.kuaner.pockete2.sight.PreviewFrame.yuv(4, 2, yp, uv, uv, 270, 2).height == 2,
                "viewfinder downsamples before RGB allocation");
        rejects(
                () -> top.kuaner.pockete2.sight.PreviewFrame.yuv(4, 2, yp, uv, uv, 45, 4),
                "invalid viewfinder rotation rejected");
        check(
                top.kuaner.pockete2.sight.SightProtocol.quadrant(50, 0) == 0
                        && top.kuaner.pockete2.sight.SightProtocol.quadrant(65, 0) == 90
                        && top.kuaner.pockete2.sight.SightProtocol.quadrant(355, 270) == 0,
                "camera rotation has hysteresis and handles wraparound");
        check(
                top.kuaner.pockete2.sight.SightProtocol.bufferRotation(90, false, 90) == 180
                        && top.kuaner.pockete2.sight.SightProtocol.bufferRotation(270, true, 270)
                                == 0,
                "camera buffers use lens sensor and physical device rotation");
        top.kuaner.pockete2.sight.SightRecovery recovery =
                new top.kuaner.pockete2.sight.SightRecovery();
        recovery.failed("camera_taken", 1_000);
        check(
                recovery.delay(1_001) > 0
                        && recovery.delay(1_499) > 0
                        && recovery.delay(1_500) == 0,
                "transient camera failure waits for a monotonic backoff before retrying");
        recovery.retry();
        recovery.failed("encoder_failed", 2_000);
        check(
                recovery.delay(2_000) > 500,
                "a restarted encoder keeps its failure budget before any transmitted key frame");
        recovery.retry();
        recovery.failed("encoder_failed", 5_000);
        recovery.retry();
        recovery.failed("camera_taken", 15_000);
        check(
                recovery.exhausted() && recovery.delay(Long.MAX_VALUE - 1) == -1,
                "repeated native failures stop automatic retries instead of reopening forever");
        recovery.inputsChanged();
        check(
                !recovery.exhausted() && recovery.failure().isEmpty(),
                "an explicit lifecycle or control change permits another transient attempt");
        recovery.failed("no_format", 20_000);
        recovery.inputsChanged();
        check(
                recovery.failure().equals("no_format") && recovery.delay(30_000) == -1,
                "unsupported camera format holds until a different ask or lens");
        recovery.reset();
        recovery.failed("encoder_failed", 30_000);
        check(
                recovery.delay(30_000) == 500,
                "a new ask or confirmed key frame resets the native failure budget");
        org.json.JSONObject translated =
                new org.json.JSONObject(
                        new String(
                                java.nio.file.Files.readAllBytes(
                                        java.nio.file.Paths.get(
                                                System.getProperty("pocket.e2.root"),
                                                "android/src/main/assets/i18n/en.json")),
                                java.nio.charset.StandardCharsets.UTF_8));
        top.kuaner.pockete2.text.TextCatalog english =
                new top.kuaner.pockete2.text.TextCatalog("en", Locale.CHINA, translated);
        check(
                english.t("设置").equals("Settings")
                        && english.t("后置摄像头画面").equals("Rear camera view"),
                "English application controls and accessibility copy share the catalog");
        check(
                english.explain("没发出去 · 轻点重试（503）").equals("Not sent · Tap to retry (503)")
                        && english.explain("文件太大（上限 3.5 MB）")
                                .equals("File too large (limit 3.5 MB)")
                        && english.explain("E2 阵列初始化失败（-2）")
                                .equals("E2 array initialisation failed (-2)"),
                "previously persisted app errors localise with numeric context intact");
        check(
                english.explain("我写了设置，别改这个文件名.txt").equals("我写了设置，别改这个文件名.txt")
                        && english.route("USB 系统音频输出 → 系统音频输出")
                                .equals("USB 系统音频输出 → System audio output"),
                "unknown content and actual audio product names are not substring-translated");
        check(
                english.traceSummary(1, 1200).equals("Done · 1 step · 1 s")
                        && english.traceSummary(2, 0).equals("Done · 2 steps"),
                "English tool summaries use correct singular and elapsed time");
        check(
                english.language("zh-Hans", Locale.US).t("设置").equals("设置")
                        && english.t("设置").equals("Settings"),
                "scoped language catalogs are immutable and do not mutate other views");
        check(
                english.language("system", Locale.US).en
                        && english.language("system", Locale.JAPAN).en
                        && !english.language("system", Locale.CHINA).en,
                "system language follows the supported device locale");
        check(
                english.format("（地图坐标：%.6f, %.6f）", 31.25, 121.5)
                        .equals(" (map coordinates: 31.250000, 121.500000)"),
                "location templates retain coordinate precision in English");
        check(
                new java.text.SimpleDateFormat(english.t("M月d日 EEEE HH:mm"), english.locale)
                        .format(new Date(0))
                        .contains("Jan"),
                "date labels use translated patterns and the selected locale");
        checked += TryChannelContracts.run();
        checked += SessionViewerContracts.run();
        checked += ImportInboxContracts.run();
        checked += UploadContracts.run();
        checked += VoiceUploadContracts.run();
        checked += AmbientOutputContracts.run();
        checked += RedialContracts.run();
        System.out.println("PASS " + checked + " contract assertions");
    }

    private static PocketProtocol.Message message(int type, byte[] payload) {
        PocketProtocol.Message out = null;
        PocketProtocol.Receiver r = new PocketProtocol.Receiver();
        for (byte[] b : PocketProtocol.fragments(type, payload, 185, false)) out = r.push(b);
        return out;
    }

    private static final class FakeTransport implements ChannelTransport {
        String path, token;
        JSONObject headers;
        int status = 202;
        boolean broken;

        public Response request(
                Endpoint e,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            if (broken) throw new java.io.IOException();
            this.path = path;
            this.token = token;
            this.headers = headers;
            return new Response(
                    status, "{\"error\":\"server_error\"}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
