// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.qa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import dev.openduo.pocket.app.MainActivity;
import dev.openduo.pocket.audio.OpusDecoder;
import dev.openduo.pocket.audio.OpusEncoder;
import dev.openduo.pocket.audio.PacketStream;
import dev.openduo.pocket.core.*;
import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.ChannelConnection;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Opt-in actual ambient service acceptance. Native microphone packets are never uploaded. */
final class AmbientRoomProbe {
    private final String expected, text, fixtureName;
    private final boolean brain;
    private final Probe probe;
    private PocketController controller;
    private Executor state;
    private int assertions;

    AmbientRoomProbe(Probe probe) {
        this(probe, false);
    }

    AmbientRoomProbe(Probe probe, boolean brain) {
        this.probe = probe;
        this.brain = brain;
        expected = brain ? "环境工具验证成功" : "环境模式连通成功";
        text =
                brain
                        ? "多多，这是DuoDuo Pocket环境工具集成测试。请调用工具查询电脑现在的日期和时间，告诉我查询结果，最后说"
                                + expected
                                + "。"
                        : "多多这是DuoDuo Pocket环境语音链路集成测试请只回复" + expected + "。";
        fixtureName = brain ? "ambient-brain-20ms" : "ambient-room-20ms";
    }

    private Object field(String name) throws Exception {
        Field field = PocketController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void channel(ChannelConnection value) throws Exception {
        Field field = PocketController.class.getDeclaredField("channel");
        field.setAccessible(true);
        field.set(controller, value);
    }

    private <T> T onState(Callable<T> action) throws Exception {
        FutureTask<T> work = new FutureTask<>(action);
        state.execute(work);
        return work.get(5, TimeUnit.SECONDS);
    }

    private void reconnect() throws Exception {
        Method method = PocketController.class.getDeclaredMethod("reconnect");
        method.setAccessible(true);
        method.invoke(controller);
    }

    private void invoke(String name) throws Exception {
        Method method = PocketController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(controller);
    }

    private void await(BooleanSupplier condition, long timeout) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < until)
            Thread.sleep(25);
    }

    private void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    private void checkpoint(String value) {
        android.os.Bundle report = new android.os.Bundle();
        report.putString("checkpoint", value + "; assertions=" + assertions);
        probe.sendStatus(0, report);
    }

    private JSONObject remoteState(ChannelConnection connection) throws Exception {
        ChannelConnection.Response status =
                connection.request(
                        controller.settings.endpoint(),
                        controller.settings.token(),
                        "GET",
                        "/api/state?room=" + Endpoint.query(controller.settings.room()),
                        Json.object(),
                        new byte[0],
                        20_000);
        if (status.status != 200) throw new java.io.IOException("Actual room state request failed");
        return new JSONObject(new String(status.body, StandardCharsets.UTF_8));
    }

    private boolean closing() {
        try {
            return onState(
                    () -> ((Integer) field("closingEpoch")).equals(field("connectionEpoch")));
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static void save(File journal, JSONObject record) throws Exception {
        android.util.AtomicFile atomic = new android.util.AtomicFile(journal);
        java.io.FileOutputStream out = atomic.startWrite();
        try {
            out.write(record.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (Throwable failure) {
            atomic.failWrite(out);
            throw failure;
        }
    }

    private boolean heard(Message row, long since) {
        return row.seq > since
                && row.kind == Message.HEARD
                && row.text.contains("环境")
                && row.text.contains("测试")
                && row.text.contains(expected);
    }

    private boolean answer(Message row, long since) {
        return row.seq > since && row.kind == Message.DUODUO && row.text.contains(expected);
    }

    /** Recover completed output evidence if acceptance stopped after the real response arrived. */
    private JSONObject retainedOutput(File events, long since) throws Exception {
        if (!events.exists()) return null;
        org.json.JSONArray frames =
                new org.json.JSONArray(
                        new String(Files.readAllBytes(events.toPath()), StandardCharsets.UTF_8));
        Set<String> speeches = new HashSet<>();
        boolean transcript = false, heard = false, reply = false;
        long completed = 0, packets = 0, sent = 0;
        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.getJSONObject(i);
            String type = frame.optString("type");
            if (type.equals("speech")) speeches.add(frame.optString("speech_id"));
            if (type.equals("transcript")) {
                JSONObject row = frame.optJSONObject("row");
                if (row != null && row.optString("text").contains(expected)) transcript = true;
            }
            if (type.equals("row")) {
                Message row =
                        ChannelClient.mapEntry(frame.optJSONObject("row"), frame.optLong("seq"));
                if (row != null) {
                    heard |= heard(row, since);
                    reply |= answer(row, since);
                }
            }
            if (type.equals("playback")
                    && frame.optString("state").equals("done")
                    && speeches.contains(frame.optString("speech_id")))
                completed = Math.max(completed, frame.optLong("played_ms"));
            if (type.equals("qa_summary")) {
                packets = frame.optLong("down_packets");
                sent = frame.optLong("fixture_sent");
            }
        }
        return transcript && heard && reply && completed > 0 && packets > 0 && sent > 0
                ? Json.object("outputMs", completed, "outputPackets", packets)
                : null;
    }

    String run(Context context) throws Exception {
        Activity activity =
                probe.startActivitySync(
                        new Intent(Intent.ACTION_MAIN)
                                .setClassName(
                                        context.getPackageName(), MainActivity.class.getName())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe.runOnMainSync(
                () ->
                        activity.getWindow()
                                .addFlags(
                                        android.view.WindowManager.LayoutParams
                                                .FLAG_KEEP_SCREEN_ON));
        controller = PocketController.get(context);
        state = (Executor) field("state");
        await(
                () ->
                        controller.snapshot() != null
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                30_000);
        PocketController.Snapshot initial = controller.snapshot();
        check(
                initial != null
                        && controller.configured()
                        && !initial.trying
                        && initial.connection == PocketController.Connection.ONLINE,
                "actual ambient acceptance starts in the already authorized room");
        check(
                !initial.ambientActive
                        && !initial.holdRecording
                        && !initial.uploadingVoice
                        && !initial.cameraOn
                        && initial.outbox.isEmpty()
                        && initial.imports.isEmpty()
                        && initial.readingId.isEmpty()
                        && initial.chips.stream().noneMatch(a -> a.state == Attachment.UPLOADING),
                "actual ambient acceptance requires the app's capture, outgoing work and playback"
                        + " idle");
        ChannelConnection original = onState(() -> (ChannelConnection) field("channel"));
        ChannelClient client =
                new ChannelClient(
                        controller.settings.endpoint(),
                        controller.settings.token(),
                        controller.settings.room(),
                        original);
        File qa = new File(context.getFilesDir(), "qa");
        qa.mkdirs();
        File journal = new File(qa, fixtureName + "-once.json");
        File fixture = new File(qa, fixtureName + "-fixture.packets");
        boolean fresh = !journal.exists();
        JSONObject record =
                fresh
                        ? Json.object(
                                "target",
                                initial.importTarget,
                                "since",
                                initial.cursor,
                                "at",
                                System.currentTimeMillis(),
                                "stage",
                                "prepared")
                        : new JSONObject(
                                new String(
                                        Files.readAllBytes(journal.toPath()),
                                        StandardCharsets.UTF_8));
        check(
                record.optString("target").equals(initial.importTarget),
                "the once-only ambient journal belongs to the current room");
        if (!fresh && !record.optBoolean("actualOutput")) {
            JSONObject retained =
                    retainedOutput(
                            new File(
                                    context.getExternalCacheDir(),
                                    "qa/"
                                            + (brain ? "ambient-brain" : "ambient-room")
                                            + "-events.json"),
                            record.getLong("since"));
            if (retained != null) {
                record.put("actualOutput", true);
                record.put("outputMs", retained.getLong("outputMs"));
                record.put("outputPackets", retained.getLong("outputPackets"));
                save(journal, record);
            }
        }
        List<String> chips = new ArrayList<>();
        for (Attachment chip : initial.chips) chips.add(chip.localPath);
        try (OpusEncoder encoder = new OpusEncoder()) {
            Observed source = new Observed(original, encoder.encode(new short[320]));
            try {
                onState(
                        () -> {
                            channel(source);
                            reconnect();
                            return null;
                        });
                await(() -> source.connectionSeen, 10_000);
                check(
                        source.connectionSeen && !source.role.equals("peer"),
                        "the actual socket supplies connection metadata before the first hello");
                JSONObject server = remoteState(original);
                File evidenceDir = new File(context.getExternalCacheDir(), "qa");
                evidenceDir.mkdirs();
                save(new File(evidenceDir, "ambient-room-state.json"), server);
                JSONObject owner = server.optJSONObject("capture");
                check(
                        owner != null && owner.has("owner") && owner.isNull("owner"),
                        "actual state confirms an unoccupied capture seat before this probe can"
                                + " say hello");
                check(
                        server.optBoolean("cerebellum_ok") && server.optBoolean("daemon_ok"),
                        "actual state reports both ambient speech service and brain ready: ear="
                                + server.optBoolean("cerebellum_ok")
                                + ", brain="
                                + server.optBoolean("daemon_ok"));
                if (record.optString("stage").equals("prepared")) {
                    if (!fixture.exists()) {
                        byte[] body = KnownSpeech.nativePackets(client.readAloud(text));
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(fixture)) {
                            out.write(body);
                            out.getFD().sync();
                        }
                    }
                }
                PacketStream packets = new PacketStream();
                source.fixture = packets.push(Files.readAllBytes(fixture.toPath()));
                packets.finish();
                long samples = 0, energy = 0;
                int shortest = Integer.MAX_VALUE, longest = 0;
                try (OpusDecoder decoder = new OpusDecoder()) {
                    for (byte[] packet : source.fixture) {
                        short[] pcm = decoder.packet(packet);
                        shortest = Math.min(shortest, pcm.length);
                        longest = Math.max(longest, pcm.length);
                        samples += pcm.length;
                        for (short sample : pcm) energy += (long) sample * sample;
                    }
                }
                JSONObject geometry =
                        Json.object(
                                "packets",
                                source.fixture.size(),
                                "samples",
                                samples,
                                "shortest",
                                shortest,
                                "longest",
                                longest,
                                "rms",
                                samples == 0 ? 0 : Math.sqrt((double) energy / samples),
                                "stage",
                                record.optString("stage"));
                save(new File(evidenceDir, "ambient-room-fixture.json"), geometry);
                check(
                        !source.fixture.isEmpty()
                                && shortest == 320
                                && longest == 320
                                && energy > 0,
                        "actual synthesis decodes into non-silent 20 ms Opus packets before native"
                                + " cadence can transmit them");
                record.put("packets", source.fixture.size());
                save(journal, record);
                controller.ambientToggle();
                onState(
                        () -> {
                            invoke("ensurePlayer");
                            ((dev.openduo.pocket.audio.SpeechPlayer) field("player")).silent(true);
                            return null;
                        });
                await(
                        () ->
                                source.role.equals("master")
                                        && source.nativePackets.get() > 0
                                        && !controller.snapshot().ambientMuted,
                        15_000);
                check(
                        source.role.equals("master")
                                && source.nativePackets.get() > 0
                                && controller.snapshot().ambientActive
                                && !controller.snapshot().ambientMuted
                                && dev.openduo.pocket.audio.input.RecordingInput.isActive(),
                        "native capture reaches actual hello/master/uplink while every microphone"
                                + " packet is replaced by silence and only the local AudioTrack is"
                                + " muted");
                int warming = source.nativePackets.get();
                await(() -> source.nativePackets.get() >= warming + 150, 6000);
                check(
                        source.nativePackets.get() >= warming + 150,
                        "three seconds of native-paced silence warm the actual room stream before"
                                + " the once-only fixture");
                long since = record.getLong("since");
                boolean transmitted = record.optString("stage").equals("prepared");
                if (transmitted) {
                    record.put("stage", "transmitting");
                    save(journal, record);
                    source.dispatch = true;
                    await(() -> source.position == source.fixture.size(), 45_000);
                    check(
                            source.position == source.fixture.size()
                                    && source.lastFixtureAt - source.firstFixtureAt + 20
                                            >= source.fixture.size() * 20L * 85 / 100,
                            "one complete marked synthetic utterance traverses the actual ambient"
                                    + " WebSocket at native capture cadence");
                    record.put("stage", "sent");
                    save(journal, record);
                    checkpoint("one marked ambient fixture sent once; surrounding audio excluded");
                }
                await(
                        () ->
                                controller.snapshot().rows.stream()
                                                .anyMatch(row -> heard(row, since))
                                        && controller.snapshot().rows.stream()
                                                .anyMatch(row -> answer(row, since)),
                        brain ? 180_000 : 90_000);
                check(
                        controller.snapshot().rows.stream().anyMatch(row -> heard(row, since)),
                        "actual ambient ASR logs the known marked speech");
                check(
                        controller.snapshot().rows.stream().anyMatch(row -> answer(row, since)),
                        "the actual service supplies the requested ambient-test answer");
                if (brain) {
                    if (transmitted) {
                        boolean tool =
                                source.events.stream()
                                        .anyMatch(
                                                frame ->
                                                        frame.optString("type").equals("turn")
                                                                && frame.optString("phase")
                                                                        .equals("tool")
                                                                && !frame.optString("label")
                                                                        .isEmpty());
                        boolean toolDone =
                                source.events.stream()
                                        .anyMatch(
                                                frame ->
                                                        frame.optString("type").equals("turn")
                                                                && frame.optString("phase")
                                                                        .equals("tool")
                                                                && frame.optString("label")
                                                                        .endsWith(" ✓"));
                        check(
                                tool && toolDone,
                                "the actual ambient brain emits a tool call and its completion");
                        check(
                                controller.snapshot().traces.values().stream()
                                        .anyMatch(
                                                trace ->
                                                        !trace.steps.isEmpty()
                                                                && trace.steps.stream()
                                                                        .allMatch(
                                                                                step -> step.done)),
                                "the production reducer retains the completed real tool trace");
                        record.put("actualTool", true);
                        save(journal, record);
                    } else
                        check(
                                record.optBoolean("actualTool"),
                                "the retained once-only journal contains actual tool acceptance");
                }
                if (transmitted) {
                    await(() -> source.downPackets.get() > 0 && source.played.get() > 0, 30_000);
                    check(
                            source.transcripts.get() > 0
                                    && source.downPackets.get() > 0
                                    && source.played.get() > 0,
                            "actual transcript and answer synthesis decode through the normal"
                                    + " hardware timeline at zero local volume and send playback"
                                    + " watermarks");
                    record.put("actualOutput", true);
                    record.put("stage", "complete");
                    save(journal, record);
                } else {
                    check(
                            record.optBoolean("actualOutput"),
                            "the retained journal records prior actual ambient downlink acceptance"
                                    + " without replaying the utterance");
                }
                record.put("stage", "complete");
                save(journal, record);
                checkpoint("actual ambient recognition, reply and downlink accepted");

                int opened = source.opens.get(), hello = source.hellos.get();
                source.failNextBinary.set(true);
                await(
                        () ->
                                source.opens.get() > opened
                                        && source.hellos.get() > hello
                                        && source.role.equals("master")
                                        && controller.snapshot().connection
                                                == PocketController.Connection.ONLINE,
                        25_000);
                check(
                        !source.failNextBinary.get()
                                && source.opens.get() == opened + 1
                                && source.hellos.get() == hello + 1
                                && source.role.equals("master")
                                && controller.snapshot().connection
                                        == PocketController.Connection.ONLINE,
                        "an injected local write fault closes the actual socket and redials the"
                                + " actual server with a fresh hello and capture seat");
                int before = source.nativePackets.get();
                await(() -> source.nativePackets.get() > before + 10, 4000);
                check(
                        source.nativePackets.get() > before + 10
                                && dev.openduo.pocket.audio.input.RecordingInput.isActive(),
                        "actual reconnection resumes native-paced silence uplink without resending"
                                + " the fixture");
                ChannelClient.Rows rows = client.rows(since);
                check(
                        rows.messages.stream().filter(row -> heard(row, since)).count() == 1,
                        "the actual server log contains exactly one marked ambient utterance");
                controller.beginHold();
                await(
                        () -> controller.snapshot().holdLive && controller.snapshot().pressMuted,
                        3000);
                check(
                        controller.snapshot().holdLive
                                && remoteState(original)
                                        .getJSONObject("controls")
                                        .getJSONObject("mute")
                                        .getBoolean("active"),
                        "an actual native held note switches the real room microphone off");
                onState(
                        () -> {
                            invoke("stopPlayback");
                            return null;
                        });
                controller.endAmbient();
                controller.releaseHold(true);
                await(
                        () ->
                                !dev.openduo.pocket.audio.input.RecordingInput.isActive()
                                        && !controller.snapshot().ambientActive
                                        && controller.snapshot().connection
                                                == PocketController.Connection.ONLINE,
                        10_000);
                check(
                        !remoteState(original)
                                .getJSONObject("controls")
                                .getJSONObject("mute")
                                .getBoolean("active"),
                        "leaving ambient during the held note restores the actual server mic"
                                + " before releasing the old seat");
                List<String> after = new ArrayList<>();
                for (Attachment chip : controller.snapshot().chips) after.add(chip.localPath);
                check(
                        !controller.snapshot().ambientActive
                                && !controller.snapshot().ambientMuted
                                && !dev.openduo.pocket.audio.input.RecordingInput.isActive()
                                && controller.snapshot().importTarget.equals(initial.importTarget)
                                && controller.draft().equals(initial.draft)
                                && controller.snapshot().quote.equals(initial.quote)
                                && after.equals(chips)
                                && controller.snapshot().outbox.isEmpty(),
                        "ending actual ambient releases capture and preserves room, composer and"
                                + " files");
                return "PASS "
                        + assertions
                        + " real ambient room assertions; "
                        + (transmitted
                                ? "one marked synthetic utterance"
                                : "retained once-only log")
                        + ", actual ASR/reply/downlink, local-fault redial and room-mic"
                        + " restoration; acoustic microphone recognition remains separate";
            } finally {
                source.dispatch = false;
                controller.releaseHold(true);
                onState(
                        () -> {
                            invoke("stopPlayback");
                            return null;
                        });
                controller.endAmbient();
                await(() -> !closing(), 12_000);
                onState(
                        () -> {
                            channel(original);
                            reconnect();
                            return null;
                        });
                await(() -> !dev.openduo.pocket.audio.input.RecordingInput.isActive(), 4000);
                File eventDir = new File(context.getExternalCacheDir(), "qa");
                eventDir.mkdirs();
                File evidence =
                        new File(
                                eventDir,
                                (brain ? "ambient-brain" : "ambient-room") + "-events.json");
                source.events.add(
                        Json.object(
                                "type", "qa_summary",
                                "native_packets", source.nativePackets.get(),
                                "fixture_packets", source.fixture.size(),
                                "fixture_sent", source.position,
                                "fixture_elapsed",
                                        source.lastFixtureAt - source.firstFixtureAt + 20,
                                "opens", source.opens.get(),
                                "hellos", source.hellos.get(),
                                "played", source.played.get(),
                                "down_packets", source.downPackets.get()));
                Files.write(
                        evidence.toPath(),
                        new org.json.JSONArray(source.events)
                                .toString()
                                .getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static final class Observed implements ChannelConnection {
        final ChannelConnection delegate;
        final byte[] silence;
        final AtomicInteger opens = new AtomicInteger(), hellos = new AtomicInteger();
        final AtomicInteger nativePackets = new AtomicInteger(), downPackets = new AtomicInteger();
        final AtomicInteger transcripts = new AtomicInteger(), played = new AtomicInteger();
        final AtomicBoolean failNextBinary = new AtomicBoolean();
        final List<JSONObject> events = new CopyOnWriteArrayList<>();
        volatile String roomState = "", role = "";
        volatile List<byte[]> fixture = Collections.emptyList();
        volatile int position;
        volatile long firstFixtureAt, lastFixtureAt;
        volatile boolean dispatch, media, connectionSeen, helloSent;

        Observed(ChannelConnection delegate, byte[] silence) {
            this.delegate = delegate;
            this.silence = silence;
        }

        public Response request(
                Endpoint endpoint,
                String token,
                String method,
                String path,
                JSONObject headers,
                byte[] body,
                long timeout)
                throws Exception {
            return delegate.request(endpoint, token, method, path, headers, body, timeout);
        }

        public Response stream(
                Endpoint endpoint,
                String token,
                long id,
                String path,
                byte[] body,
                StreamListener listener,
                BooleanSupplier current)
                throws Exception {
            return delegate.stream(endpoint, token, id, path, body, listener, current);
        }

        public void cancelStream(long id) {
            delegate.cancelStream(id);
        }

        public void live(
                Endpoint endpoint,
                String token,
                String path,
                JSONObject headers,
                LiveListener listener,
                BooleanSupplier current)
                throws Exception {
            opens.incrementAndGet();
            roomState = role = "";
            media = connectionSeen = helloSent = false;
            delegate.live(
                    endpoint,
                    token,
                    path,
                    headers,
                    new LiveListener() {
                        public void text(String text) {
                            try {
                                JSONObject value = new JSONObject(text);
                                if (events.size() < 2000) events.add(value);
                                String type = value.optString("type");
                                if (type.equals("meta")) {
                                    if (value.has("conn")) connectionSeen = true;
                                    roomState = value.optString("state", roomState);
                                    role = value.optString("role", role);
                                    if (value.has("media")) media = value.optInt("media") == 1;
                                }
                                if (type.equals("transcript")) transcripts.incrementAndGet();
                            } catch (org.json.JSONException ignored) {
                            }
                            listener.text(text);
                        }

                        public void binary(byte[] bytes) {
                            downPackets.incrementAndGet();
                            listener.binary(bytes);
                        }

                        public void closed(String reason) {
                            listener.closed(reason);
                        }
                    },
                    current);
        }

        public void send(JSONObject frame) throws Exception {
            if (frame.optString("type").equals("hello")) {
                // /api/state confirmed an unoccupied seat. Never repeat hello as a takeover;
                // dispatch stays silent until the actual channel confirms master ownership.
                if (helloSent || role.equals("peer"))
                    throw new java.io.IOException("Ambient acceptance refused a takeover");
                helloSent = true;
                hellos.incrementAndGet();
            }
            if (frame.optString("type").equals("hello") || frame.optString("type").equals("mute"))
                events.add(Json.object("type", "qa_sent", "frame", frame));
            if (frame.optString("type").equals("played")) played.incrementAndGet();
            delegate.send(frame);
        }

        public void sendBinary(byte[] microphonePacket) throws Exception {
            if (failNextBinary.compareAndSet(true, false))
                throw new java.io.IOException("Prepared local write fault on the actual socket");
            // Deliberately ignore all microphone bytes: native capture only clocks the fixture.
            boolean speech = dispatch && position < fixture.size();
            byte[] packet = speech ? fixture.get(position) : silence;
            byte[] outgoing = packet;
            if (media) {
                outgoing = new byte[packet.length + 1];
                outgoing[0] = 1;
                System.arraycopy(packet, 0, outgoing, 1, packet.length);
            }
            try {
                delegate.sendBinary(outgoing);
            } catch (Exception failure) {
                dispatch = false;
                throw failure;
            }
            nativePackets.incrementAndGet();
            if (speech) {
                lastFixtureAt = android.os.SystemClock.elapsedRealtime();
                if (position == 0) firstFixtureAt = lastFixtureAt;
                if (++position == fixture.size()) dispatch = false;
            }
        }

        public boolean isOpen() {
            return delegate.isOpen();
        }

        public void closeLive() {
            delegate.closeLive();
        }
    }
}
