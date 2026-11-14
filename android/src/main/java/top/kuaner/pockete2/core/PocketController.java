// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import top.kuaner.pockete2.app.PocketSessionService;
import top.kuaner.pockete2.audio.CaptureSession;
import top.kuaner.pockete2.audio.OpusDecoder;
import top.kuaner.pockete2.audio.SpeechPlayer;
import top.kuaner.pockete2.net.ChannelClient;
import top.kuaner.pockete2.net.ChannelConnection;
import top.kuaner.pockete2.net.GoTransport;
import top.kuaner.pockete2.trymode.TryChannel;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single-writer application state; network and capture callbacks are admitted on this executor. */
public final class PocketController {
    public enum Connection {
        NEEDS_SETUP,
        CONNECTING,
        ONLINE,
        OFFLINE,
        NEEDS_PAIRING
    }

    public enum Ambient {
        OFF,
        STARTING,
        LISTENING,
        SPEAKING,
        PAUSED,
        SEAT_TAKEN,
        DEAF,
        OFFLINE,
        FAILED
    }

    public interface Listener {
        void changed();
    }

    public interface FileResult {
        void ready(File file, String mime);

        default void failed() {}
    }

    public static final class Snapshot {
        public final Connection connection;
        public final Ambient ambient;
        public final List<Message> rows;
        public final List<Outbox.Item> outbox;
        public final List<Attachment> chips;
        public final List<ImportProgress> imports;
        public final List<String> configIssues;
        public final Map<String, TurnState.Trace> traces;
        public final TurnState.Working working;
        public final SessionViewer.Snapshot sessions;
        public final top.kuaner.pockete2.passport.PassportCoordinator.Snapshot passport;
        public final String status,
                roomName,
                lastError,
                draft,
                quote,
                tailnetState,
                authUrl,
                olderLabel,
                readingId,
                sight;
        public final String importTarget;
        public final String lastHeard;
        public final String audioRoute;
        public final boolean cameraOffered,
                cameraOn,
                cameraRequested,
                cameraRetry,
                cameraFront,
                cameraCanFlip;
        public final int heatLevel;
        public final boolean voiceInput,
                voiceAvailable,
                daemonAvailable,
                ambientMuted,
                holdRecording,
                holdLive,
                trying,
                olderLoading,
                uploadingVoice,
                ambientActive,
                accessoryActive,
                pressMuted,
                echoCancellation;
        public final int latencyMs;
        public final long cursor, holdDurationMs, revision, draftGeneration, draftEditAck;

        Snapshot(PocketController c) {
            connection = c.connection;
            ambient = c.ambient;
            sessions = c.viewer.snapshot();
            passport = c.passport.snapshot();
            rows = Collections.unmodifiableList(new ArrayList<>(c.cachedRows));
            outbox = Collections.unmodifiableList(new ArrayList<>(c.pending));
            chips = Collections.unmodifiableList(new ArrayList<>(c.chips));
            imports = c.imports.progress();
            configIssues = c.roomState == null ? Collections.emptyList() : c.roomState.configIssues;
            traces = c.turn.traces();
            working = c.turn.working();
            status = c.presence();
            top.kuaner.pockete2.sight.SightSession.Snapshot camera = c.sightSession.snapshot();
            sight = camera.status;
            lastHeard = c.lastHeard;
            audioRoute = (c.capture == null ? "" : c.capture.route() + " → ") + c.outputRoute;
            cameraOffered = camera.offered;
            cameraOn = camera.on;
            cameraRequested = camera.requested;
            cameraRetry = camera.retry;
            cameraFront = camera.front;
            cameraCanFlip = camera.canFlip;
            heatLevel = c.heatLevel;
            roomName = c.trying ? "体验房间" : c.settings.roomName();
            lastError = c.lastError;
            draft = c.draft;
            String identity = c.repo.roomIdentity();
            importTarget = identity.equals("unpaired") ? "" : identity;
            draftGeneration = c.draftGeneration;
            draftEditAck = c.draftEditAck;
            quote = c.quote;
            tailnetState = c.tailnetState;
            authUrl = c.authUrl;
            olderLabel = c.olderLabel;
            readingId = c.readingId;
            voiceInput = c.settings.voiceInput();
            voiceAvailable = c.voiceAvailable();
            daemonAvailable = c.roomState == null || c.roomState.daemonOk;
            ambientMuted = c.ambientMuted;
            holdRecording = c.holdRequested;
            holdLive = c.holdLive;
            trying = c.trying;
            olderLoading = c.olderLoading;
            uploadingVoice = c.uploadForeground;
            ambientActive = c.ambientWanted;
            accessoryActive = !c.trying && c.passport.snapshot().link.remembered;
            pressMuted = c.ambientInput.pressMuted();
            echoCancellation = c.capture != null && c.capture.echoCancellation();
            latencyMs = c.latencyMs;
            cursor = c.repo.cursor();
            holdDurationMs = c.holdPackets * 20L;
            revision = c.revision;
        }
    }

    private static PocketController instance;

    public static synchronized PocketController get(Context context) {
        if (instance == null) instance = new PocketController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    public final Settings settings;
    private final RoomRepository realRepo;
    private RoomRepository repo;
    private final top.kuaner.pockete2.location.ArrivalMonitor arrivals;
    private final top.kuaner.pockete2.diagnostics.AppLog log;
    private final top.kuaner.pockete2.app.AnswerNotifications notifications;

    public boolean arrivalsEnabled() {
        return arrivals.enabled();
    }

    public boolean arrivalsNeedPermission() {
        return arrivals.needsPermission();
    }

    public void arrivalsEnabled(boolean value) {
        state.execute(
                () -> {
                    arrivals.enabled(value);
                    publish();
                });
    }

    private final GoTransport transport;
    private ChannelConnection channel;
    private TryChannel tryChannel;
    private ChannelConnection readChannel;
    private final top.kuaner.pockete2.audio.AudioFocus audioFocus;
    private final top.kuaner.pockete2.audio.AudioDevices audioDevices;
    private String outputRoute = "系统音频输出";
    private boolean audioSuspended;
    private final ScheduledExecutorService state =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Pocket E2 state"));
    private final ExecutorService network =
            Executors.newFixedThreadPool(3, r -> new Thread(r, "Pocket E2 network"));
    private final java.util.concurrent.ThreadPoolExecutor socketWriter =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(128),
                    r -> new Thread(r, "Pocket E2 socket writer"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final TurnState turn = new TurnState();
    private final SessionViewer viewer = new SessionViewer();
    private final top.kuaner.pockete2.passport.PassportCoordinator passport;
    private final List<Attachment> chips = new ArrayList<>();
    private List<Message> cachedRows = new ArrayList<>();
    private final List<Message> liveNotes = new ArrayList<>();
    private List<Outbox.Item> pending = new ArrayList<>();
    private volatile Snapshot snapshot;
    private Connection connection = Connection.NEEDS_SETUP;
    private Ambient ambient = Ambient.OFF;
    private String lastError = "",
            draft = "",
            quote = "",
            tailnetState = "NotStarted",
            authUrl = "",
            olderLabel = "查看更早的记录",
            readingId = "";
    private String oldestDate = "";
    private final AmbientInput ambientInput = new AmbientInput();
    private final AmbientOutput ambientOutput = new AmbientOutput();
    private int latencyMs, visibleLimit = 200, reconnectAttempt;
    private volatile int connectionEpoch;
    private volatile long sendEpoch;
    private long revision, holdPackets, lastHoldPublish;
    private long draftGeneration, draftEditAck;
    private final java.util.concurrent.atomic.AtomicLong draftEdits =
            new java.util.concurrent.atomic.AtomicLong();
    private boolean foreground, trying, opening, ambientWanted, ambientMuted;
    private SendOperation sending;
    private boolean holdRequested, holdLive, olderLoading, captureStopping;
    private final java.util.concurrent.atomic.AtomicBoolean publishing =
            new java.util.concurrent.atomic.AtomicBoolean();
    private ChannelClient.State roomState;
    private Boolean liveCerebellumOk;
    private volatile CaptureSession capture;
    private int captureEpoch;
    private BufferedOutputStream heldOutput;
    private FileOutputStream heldFileOutput;
    private File heldFile;
    private OpusDecoder decoder;
    private SpeechPlayer player;
    private boolean playerActive;
    private final top.kuaner.pockete2.sight.SightSession sightSession;
    private boolean legacyMedia;
    private String lastHeard = "";
    private volatile long readRequest;
    private long nextReadRequest;
    private boolean readInFlight;
    private boolean uploadForeground, serviceRunning;
    private long pairingEpoch;
    private boolean pairingActive;
    private final AttachmentStore attachmentStore;
    private final ImportCoordinator imports;
    private final top.kuaner.pockete2.sight.DeviceHeat deviceHeat;
    private int heatLevel;

    /** Mutable fields belong to the state executor; each HTTP attempt captures immutable inputs. */
    private static final class SendOperation {
        final Outbox.Item item;
        final ChannelClient client;
        final String identity;
        final long ticket;
        final VoiceRetry retry = new VoiceRetry();
        byte[] body;
        ChannelClient.Failure error;

        SendOperation(Outbox.Item item, ChannelClient client, String identity, long ticket) {
            this.item = item;
            this.client = client;
            this.identity = identity;
            this.ticket = ticket;
        }
    }

    private PocketController(Context context) {
        this.context = context;
        settings = new Settings(context);
        attachmentStore = new AttachmentStore(context);
        arrivals = new top.kuaner.pockete2.location.ArrivalMonitor(context);
        arrivals.refresh();
        log = top.kuaner.pockete2.diagnostics.AppLog.get(context);
        log.event("launch", "api=" + android.os.Build.VERSION.SDK_INT);
        notifications = new top.kuaner.pockete2.app.AnswerNotifications(context, settings);
        top.kuaner.pockete2.app.BackgroundSync.schedule(context);
        realRepo = new RoomRepository(context);
        repo = realRepo;
        imports =
                new ImportCoordinator(
                        context,
                        state,
                        main,
                        realRepo,
                        attachmentStore,
                        new ImportCoordinator.Host() {
                            public RoomRepository repository() {
                                return repo;
                            }

                            public boolean trying() {
                                return trying;
                            }

                            public long uploadLimit() {
                                return !trying && roomState != null ? roomState.uploadMaxBytes : 0;
                            }

                            public void admit(ImportBatch batch) {
                                admitImportedDraft(batch);
                            }

                            public void notice(String message) {
                                PocketController.this.notice(message);
                            }

                            public void changed() {
                                publish();
                            }
                        });
        transport = new GoTransport(context);
        channel = transport;
        sightSession =
                new top.kuaner.pockete2.sight.SightSession(
                        context,
                        state,
                        Executors.newSingleThreadExecutor(
                                r -> new Thread(r, "Pocket E2 camera close")),
                        () -> channel.isOpen(),
                        () -> socketWriter.getQueue().size() > 8,
                        new top.kuaner.pockete2.sight.SightSession.Listener() {
                            public void changed() {
                                publish();
                            }

                            public void text(JSONObject frame) {
                                send(frame);
                            }

                            public void binary(byte[] frame) {
                                sendBinary(frame);
                            }
                        });
        audioFocus =
                new top.kuaner.pockete2.audio.AudioFocus(
                        context,
                        state,
                        focus -> {
                            log.event("audio", "focus=" + focus);
                            if (focus <= 0) {
                                audioSuspended = true;
                                releaseOnState(false, true);
                                stopCapture();
                                stopPlayback();
                                if (ambientWanted) {
                                    ambient = Ambient.PAUSED;
                                }
                                reconnect();
                                notifications.interrupted();
                                notice("音频被系统打断，收音已暂停");
                            } else {
                                audioSuspended = false;
                                if (ambientWanted) {
                                    ambient = Ambient.STARTING;
                                    startCapture();
                                    reconnect();
                                    publish();
                                }
                            }
                        });
        audioDevices =
                new top.kuaner.pockete2.audio.AudioDevices(
                        context,
                        () ->
                                state.execute(
                                        () -> {
                                            log.event("audio", "route_change");
                                            if (capture != null
                                                    && capture.isStarted()
                                                    && !captureStopping) {
                                                if (holdRequested) releaseOnState(false, true);
                                                stopCapture();
                                            }
                                            publish();
                                        }));
        passport =
                new top.kuaner.pockete2.passport.PassportCoordinator(
                        context,
                        state,
                        new top.kuaner.pockete2.passport.PassportCoordinator.Listener() {
                            public void changed() {
                                refreshService();
                                publish();
                            }

                            public void press(
                                    top.kuaner.pockete2.passport.PressAssembler.Press press) {
                                passportPress(press);
                            }

                            public void capturing(boolean active) {
                                for (JSONObject outgoing : ambientInput.press("passport", active))
                                    send(outgoing);
                                if (active) interruptPlayback();
                                publish();
                            }

                            public void expired(String id) {
                                Outbox.Item item = repo.findPending(id);
                                if (item != null && item.state != Message.FAILED) {
                                    repo.transition(item.state(Message.FAILED, "识别未完成 · 轻点重试"));
                                    reload();
                                    publish();
                                }
                            }
                        });
        state.scheduleAtFixedRate(
                () -> {
                    passport.tick();
                    sightSession.busy();
                },
                1,
                1,
                TimeUnit.SECONDS);
        state.execute(
                () -> {
                    repo.select(settings);
                    if (settings.isComplete()) {
                        try {
                            repo.inheritUnpairedDraft();
                        } catch (Exception failure) {
                            log.event("import", "unpaired_restore_deferred");
                            lastError = "导入内容尚未保存，重新打开应用后会重试";
                        }
                    }
                    passport.room(repo.roomIdentity());
                    if (snapshot == null) {
                        cleanupTry();
                        chips.clear();
                    }
                    restoreDraft();
                    imports.recover(true);
                    reload();
                    publish();
                    if (settings.isComplete()) startNetwork();
                });
        deviceHeat =
                new top.kuaner.pockete2.sight.DeviceHeat(
                        context,
                        level ->
                                state.execute(
                                        () -> {
                                            heatLevel = level;
                                            log.event("thermal", String.valueOf(level));
                                            updateSight();
                                            publish();
                                        }));
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean configured() {
        return settings.isComplete();
    }

    private boolean voiceAvailable() {
        return roomState == null
                || (liveCerebellumOk == null ? roomState.cerebellumOk : liveCerebellumOk)
                        && !roomState.uploadsDisabled;
    }

    public String draft() {
        Snapshot current = snapshot;
        return current == null ? "" : current.draft;
    }

    public float level() {
        CaptureSession current = capture;
        return current == null ? 0 : current.level();
    }

    public void start() {
        state.execute(
                () -> {
                    if (!trying) {
                        transport.rebind();
                        startNetwork();
                    }
                });
    }

    public void stop() {
        state.execute(
                () -> {
                    connectionEpoch++;
                    opening = false;
                    channel.closeLive();
                    stopCapture();
                    stopPlayback();
                });
    }

    public void onForeground(boolean value) {
        state.execute(
                () -> {
                    foreground = value;
                    notifications.foreground(value);
                    log.event("foreground", String.valueOf(value));
                    arrivals.refresh();
                    passport.foreground(value);
                    surface();
                    if (!value && !ambientWanted) stopPlayback();
                    else if (value && audioSuspended && !audioFocus.interrupted()) {
                        audioSuspended = false;
                        if (ambientWanted) ambient = Ambient.STARTING;
                    }
                    if (viewer.snapshot().watching && channel.isOpen())
                        send(value ? viewer.watchFrame() : Json.object("type", "sessions_unwatch"));
                    if (!value) viewer.dropPending();
                    if (!value) {
                        updateSight();
                        releaseOnState(true);
                        if (trying || !settings.keepListening()) ambientOff();
                        if (!ambientWanted && pending.isEmpty() && !passport.snapshot().waiting) {
                            stopPlayback();
                            channel.closeLive();
                            connectionEpoch++;
                            opening = false;
                        }
                    } else if (trying || settings.isComplete()) {
                        if (trying) connect();
                        else startNetwork();
                        if (ambientWanted && capture == null) startCapture();
                        updateSight();
                    }
                    publish();
                });
    }

    public void draft(String value) {
        state.execute(
                () -> {
                    draft = value == null ? "" : value;
                    draftGeneration++;
                    if (!trying) saveDraft();
                    publish();
                });
    }

    public long editDraft(String before, String after, long generation) {
        long ticket = draftEdits.incrementAndGet();
        state.execute(
                () -> {
                    if (generation == draftGeneration) {
                        draft = DraftText.edit(draft, before, after);
                        if (!trying) saveDraft();
                    }
                    draftEditAck = Math.max(draftEditAck, ticket);
                    publish();
                });
        return ticket;
    }

    public void appendDraft(String text) {
        state.execute(
                () -> {
                    draft = DraftText.append(draft, text);
                    settings.voiceInput(false);
                    saveDraft();
                    publish();
                });
    }

    public void quote(String value) {
        state.execute(
                () -> {
                    quote = value == null ? "" : value;
                    settings.voiceInput(false);
                    saveDraft();
                    publish();
                });
    }

    public void toggleVoiceInput() {
        state.execute(
                () -> {
                    settings.voiceInput(!settings.voiceInput());
                    publish();
                });
    }

    public void setVoiceInput(boolean value) {
        state.execute(
                () -> {
                    settings.voiceInput(value);
                    publish();
                });
    }

    public void setHaptics(boolean value) {
        settings.haptics(value);
    }

    public void changeLanguage(String value) {
        settings.language(value);
        state.execute(
                () -> {
                    notifications.language();
                    if (!trying) passport.languageChanged();
                    if (trying) {
                        stopTryingOnState(false);
                        if (!startTryingOnState()) {
                            refreshService();
                            if (settings.isComplete()) startNetwork();
                        }
                    } else publish();
                });
    }

    public void setKeepAwake(boolean value) {
        settings.keepAwake(value);
        state.execute(
                () -> {
                    surface();
                    publish();
                });
    }

    public void setReplyAudio(boolean value) {
        settings.ambientOutput(value);
        state.execute(this::reconnect);
    }

    public void setHeadsetMic(boolean value) {
        settings.headsetMic(value);
        state.execute(
                () -> {
                    if (holdRequested) notice("本次录音结束后切换麦克风");
                    else if (capture != null) stopCapture();
                    publish();
                });
    }

    private void startNetwork() {
        startNetwork(false);
    }

    public void loginTailnet() {
        state.execute(
                () -> {
                    if (trying) stopTryingOnState(false);
                    startNetwork(true);
                });
    }

    private void startNetwork(boolean interactive) {
        if (trying) return;
        network.execute(
                () -> {
                    try {
                        transport.start(
                                settings.networkLog(),
                                status -> state.execute(() -> networkStatus(status)));
                        if (interactive) transport.login();
                    } catch (Exception failure) {
                        state.execute(
                                () -> {
                                    tailnetState = "Failed";
                                    lastError = "无法启动 Tailscale 网络节点";
                                    publish();
                                });
                    }
                });
    }

    private void networkStatus(JSONObject status) {
        String previousState = tailnetState;
        tailnetState = status.optString("backend_state", "NotStarted");
        if (!previousState.equals(tailnetState)) log.event("network", tailnetState);
        authUrl = status.optString("auth_url");
        if (!trying
                && tailnetState.equals("Running")
                && settings.isComplete()
                && !settings.unauthorized()
                && !channel.isOpen()) connect();
        publish();
    }

    public void refreshTailnet() {
        network.execute(transport::reportStatus);
    }

    public void logoutTailnet() {
        state.execute(
                () -> {
                    ambientOff();
                    connectionEpoch++;
                    channel.closeLive();
                    opening = false;
                    network.execute(
                            () -> {
                                try {
                                    transport.logout();
                                } catch (Exception failure) {
                                    state.execute(() -> notice("退出网络失败"));
                                }
                            });
                });
    }

    public void networkLogging(boolean value) {
        settings.networkLog(value);
        network.execute(() -> transport.logging(value));
    }

    private ChannelClient client() {
        return trying
                ? new ChannelClient(TryChannel.ENDPOINT, TryChannel.TOKEN, TryChannel.ROOM, channel)
                : new ChannelClient(settings, channel);
    }

    private void connect() {
        if (opening
                || channel.isOpen()
                || pairingActive
                || !trying && (!settings.isComplete() || settings.unauthorized())
                || (!foreground
                        && !ambientWanted
                        && pending.isEmpty()
                        && !passport.snapshot().waiting)) return;
        opening = true;
        ambientInput.socketOpened();
        sightSession.connectionReset();
        if (!trying) passport.beginReplay();
        int epoch = ++connectionEpoch;
        connection = Connection.CONNECTING;
        ChannelConnection target = channel;
        Endpoint endpoint = trying ? TryChannel.ENDPOINT : settings.endpoint();
        String token = trying ? TryChannel.TOKEN : settings.token();
        final boolean demo = trying;
        final String place = !demo && ambientWanted ? arrivals.pending() : "";
        ChannelClient client = client();
        long cursor = repo.cursor();
        boolean resume = repo.hasCursor();
        boolean audio =
                !audioSuspended && (ambientWanted || foreground && settings.ambientOutput());
        publish();
        network.execute(
                () -> {
                    try {
                        int latency = client.health();
                        ChannelClient.State room = client.state();
                        state.execute(
                                () -> {
                                    if (epoch == connectionEpoch) {
                                        roomState = room;
                                        liveCerebellumOk = null;
                                        latencyMs = latency;
                                    }
                                });
                        target.live(
                                endpoint,
                                token,
                                client.livePath(cursor, resume, audio, !legacyMedia),
                                place.isEmpty()
                                        ? Json.object()
                                        : Json.object("X-Ambient-Place", place),
                                new ChannelConnection.LiveListener() {
                                    @Override
                                    public void text(String text) {
                                        state.execute(
                                                () -> {
                                                    if (epoch == connectionEpoch)
                                                        frame(text, resume);
                                                });
                                    }

                                    @Override
                                    public void binary(byte[] bytes) {
                                        byte[] copy = bytes.clone();
                                        state.execute(
                                                () -> {
                                                    if (epoch == connectionEpoch)
                                                        PocketController.this.binary(copy);
                                                });
                                    }

                                    @Override
                                    public void closed(String reason) {
                                        state.execute(() -> linkClosed(reason, epoch));
                                    }
                                },
                                () -> epoch == connectionEpoch);
                        state.execute(
                                () -> {
                                    if (epoch == connectionEpoch) {
                                        opening = false;
                                        if (!demo) arrivals.settle(place);
                                        log.event("live", "opened");
                                        surface();
                                    }
                                });
                    } catch (Exception failure) {
                        state.execute(
                                () -> {
                                    if (epoch != connectionEpoch) return;
                                    opening = false;
                                    if (failure instanceof ChannelClient.Failure
                                                    && ((ChannelClient.Failure) failure)
                                                            .unauthorized()
                                            || String.valueOf(failure.getMessage())
                                                    .startsWith("tsbridge: live unauthorized"))
                                        unauthorized();
                                    else linkClosed("暂时无法连接房间", epoch);
                                });
                    }
                });
    }

    private void reconnect() {
        sightSession.connectionReset();
        connectionEpoch++;
        opening = false;
        ambientInput.socketOpened();
        socketWriter.getQueue().clear();
        channel.closeLive();
        stopPlayback();
        connect();
    }

    private void linkClosed(String reason, int epoch) {
        if (epoch != connectionEpoch) return;
        if (reason.contains("4401")) {
            unauthorized();
            return;
        }
        sightSession.connectionReset();
        passport.online(false);
        viewer.dropPending();
        connection = Connection.OFFLINE;
        opening = false;
        ambientInput.socketOpened();
        if (readingId.isEmpty()) stopPlayback();
        if (ambientWanted) {
            ambient = Ambient.OFFLINE;
            stopCapture();
        }
        lastError = "连不上房间，正在重试";
        publish();
        int[] delays = {500, 1000, 2000, 5000, 10000};
        int delay = delays[Math.min(reconnectAttempt++, delays.length - 1)];
        state.schedule(
                () -> {
                    if (epoch == connectionEpoch) connect();
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    private void unauthorized() {
        settings.unauthorized(true);
        connection = Connection.NEEDS_PAIRING;
        opening = false;
        connectionEpoch++;
        channel.closeLive();
        ambientOff();
        lastError = "设备凭据已失效，请重新配对";
        publish();
    }

    private void frame(String text, boolean resumed) {
        try {
            JSONObject frame = new JSONObject(text);
            String type = frame.optString("type");
            boolean accessoryAlert = passport.snapshot().link.ready && passport.snapshot().waiting;
            if (!trying) {
                notifications.frame(frame, accessoryAlert);
                passport.frame(frame);
            }
            if (viewer.handle(frame)) {
                if (foreground) publish();
                return;
            }
            if (type.equals("row")) {
                Message message =
                        ChannelClient.mapEntry(frame.optJSONObject("row"), frame.optLong("seq"));
                if (message != null && message.kind == Message.MINE)
                    messageAccepted(repo.findPending(message.id));
                repo.admit(
                        message == null
                                ? Collections.<Message>emptyList()
                                : Collections.singletonList(message),
                        frame.optLong("seq"));
                if (message != null) turn.settled(message);
                reload();
            } else if (type.equals("ready")) {
                connection = Connection.ONLINE;
                if (!trying) passport.online(true);
                opening = false;
                reconnectAttempt = 0;
                lastError = frame.optInt("api", 0) < 2 ? "家里的 ambient 需要升级（API v2）" : "";
                repo.admit(Collections.<Message>emptyList(), frame.optLong("seq"));
                turn.restore(frame.optJSONObject("turn"));
                if (resumed) reconcileReady(frame);
                if (!resumed || !repo.metadata("history_loaded").equals("1")) historyToday();
                viewer.dropPending();
                if (foreground && viewer.snapshot().watching) send(viewer.watchFrame());
                if (ambientWanted && !audioSuspended && capture == null) startCapture();
                pump();
            } else if (type.equals("message")) {
                lifecycle(frame);
            } else if (type.equals("meta")) {
                try {
                    if (legacyMedia && frame.has("media"))
                        throw new IllegalArgumentException("Unoffered media");
                    for (JSONObject outgoing : ambientInput.meta(frame, currentRoom()))
                        send(outgoing);
                } catch (IllegalArgumentException incompatible) {
                    if (!legacyMedia) {
                        legacyMedia = true;
                        reconnect();
                        notice("媒体协议不兼容，已回退为纯音频连接");
                    } else {
                        ambientOff();
                        notice("实时媒体版本不兼容，请更新 ambient");
                    }
                    return;
                }
                updateSight();
                if (ambientWanted && !audioSuspended) {
                    if (ambientInput.roomState().equals("unowned")) ambient = Ambient.DEAF;
                    else if (ambientInput.role().equals("peer")) ambient = Ambient.SEAT_TAKEN;
                    else if (ambientInput.helloSent() && ambientInput.role().equals("master"))
                        ambient = playerActive ? Ambient.SPEAKING : Ambient.LISTENING;
                }
            } else if (type.equals("audio_params")) {
                if (frame.optInt("rate", 16000) != 16000) {
                    notice("房间音频采样率不兼容");
                    ambientOff();
                }
            } else if (type.equals("transcript")) {
                JSONObject heard = frame.optJSONObject("row");
                if (heard != null && !heard.optString("text").isEmpty()) {
                    String label = heard.optString("speaker");
                    lastHeard =
                            (label.isEmpty() || label.equals("V?") ? "" : label + "  ")
                                    + heard.optString("text");
                }
            } else if (type.equals("understood")
                            && frame.has("addressed")
                            && !frame.optBoolean("addressed")
                    || type.equals("wake_ignored")) {
                roomNote("听到了，不是在叫我");
            } else if (type.equals("ack_silenced")) {
                roomNote("听到了，这次没有另外回应");
            } else if (type.equals("key_frame")) {
                if (ambientInput.metaSeen()) sightSession.keyFrame();
            } else if (type.equals("speech")) {
                speech(frame.optString("speech_id"));
            } else if (type.equals("stop_audio")) {
                if (ambientOutput.acceptsStop(frame)) stopPlayback();
            } else if (type.equals("cerebellum") && roomState != null) {
                if (frame.has("ok")) {
                    liveCerebellumOk = frame.optBoolean("ok");
                    if (!liveCerebellumOk) notice("语音服务暂不可用");
                    else if (lastError.equals("语音服务暂不可用")) lastError = "";
                }
            } else if (type.equals("camera")) {
                if (ambientInput.metaSeen()) sightSession.asked(frame);
            }
            Message provisional = turn.handle(frame, System.currentTimeMillis());
            if (provisional != null) {
                Message prior = repo.row(provisional.id);
                if (prior != null && !prior.text.equals(provisional.text))
                    provisional =
                            new Message(
                                    "provisional:"
                                            + frame.optString(
                                                    "speech_id",
                                                    String.valueOf(System.currentTimeMillis())),
                                    provisional.kind,
                                    provisional.at,
                                    provisional.text,
                                    "",
                                    0,
                                    Message.DELIVERED,
                                    "",
                                    true,
                                    false,
                                    provisional.attachments,
                                    -1);
                repo.put(provisional);
                reload();
            }
            settleSend();
            publish();
        } catch (Exception failure) {
            notice("房间数据读取失败，正在重新同步");
        }
    }

    private void lifecycle(JSONObject frame) {
        String id = frame.optString("id");
        Outbox.Item item = repo.findPending(id);
        if (item == null) {
            if (frame.optString("state").equals("failed")
                    && frame.optString("reason").equals("brain_unreachable")) {
                Message logged = repo.row(id);
                if (logged != null) {
                    repo.put(logged.withDelivery(Message.FAILED, "没送到房间 · 可重新发送"));
                    turn.failed(id);
                    reload();
                }
            }
            return;
        }
        Message row = repo.row(id);
        if (row == null) return;
        if (frame.optString("state").equals("cleaned")) {
            if (item.state == Message.SENDING || item.state == Outbox.ACCEPTED)
                messageAccepted(item);
            repo.cleaned(id, frame.optString("text"));
        } else if (frame.optString("state").equals("failed")) {
            String reason = frame.optString("reason");
            turn.failed(id);
            if (reason.equals("empty_transcript")) {
                repo.discard(id);
                lastError = "没听清，再说一次";
            } else {
                String error =
                        reason.equals("asr_failed")
                                ? "识别出错 · 轻点重试"
                                : reason.equals("perception_unavailable")
                                        ? "语音服务暂不可用 · 轻点重试"
                                        : "没发出去 · 轻点重试";
                repo.transition(item.state(Message.FAILED, error));
            }
        }
        reload();
    }

    private void reconcileReady(JSONObject frame) {
        List<String> open = new ArrayList<>();
        JSONArray messages = frame.optJSONArray("messages");
        if (messages != null)
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message != null) open.add(message.optString("id"));
            }
        for (Outbox.Item item : repo.pending())
            if (item.state == Outbox.ACCEPTED && !open.contains(item.id)) {
                Message row = repo.row(item.id);
                if (row != null && row.seq < 0) {
                    repo.transition(item.state(Message.FAILED, "发送未完成 · 轻点重试"));
                }
            }
        reload();
    }

    private void historyToday() {
        if (roomState == null || roomState.date.isEmpty()) return;
        String day = roomState.date;
        int epoch = connectionEpoch;
        ChannelClient client = client();
        network.execute(
                () -> {
                    try {
                        ChannelClient.Rows rows = client.history(day);
                        state.execute(
                                () -> {
                                    if (epoch != connectionEpoch) return;
                                    repo.admit(rows.messages, repo.cursor());
                                    repo.metadata("history_loaded", "1");
                                    oldestDate =
                                            cachedRows.isEmpty()
                                                    ? day
                                                    : Json.day(cachedRows.get(0).at);
                                    reload();
                                    publish();
                                });
                    } catch (ChannelClient.Failure failure) {
                        state.execute(
                                () -> {
                                    if (epoch == connectionEpoch) notice("暂时无法加载历史，可稍后重试");
                                });
                    }
                });
    }

    public void loadOlder() {
        state.execute(
                () -> {
                    if (olderLoading) return;
                    if (repo.countRows() > visibleLimit) {
                        visibleLimit += 200;
                        reload();
                        publish();
                        return;
                    }
                    if (connection != Connection.ONLINE) {
                        notice("本机较早的记录已显示，联网后可继续加载");
                        return;
                    }
                    olderLoading = true;
                    publish();
                    int epoch = connectionEpoch;
                    String before = oldestDate;
                    if (before.isEmpty())
                        before =
                                roomState == null || roomState.date.isEmpty()
                                        ? Json.day(System.currentTimeMillis())
                                        : roomState.date;
                    final String start = before;
                    ChannelClient client = client();
                    network.execute(
                            () -> {
                                List<Message> found = new ArrayList<>();
                                String scanned = start;
                                try {
                                    for (int i = 0; i < 14 && found.isEmpty(); i++) {
                                        scanned =
                                                java.time.LocalDate.parse(scanned)
                                                        .minusDays(1)
                                                        .toString();
                                        found.addAll(client.history(scanned).messages);
                                    }
                                    final String oldest = scanned;
                                    state.execute(
                                            () -> {
                                                if (epoch != connectionEpoch) return;
                                                oldestDate = oldest;
                                                olderLoading = false;
                                                visibleLimit += Math.max(100, found.size());
                                                repo.admit(found, repo.cursor());
                                                olderLabel =
                                                        found.isEmpty()
                                                                ? oldest + " 之前暂未找到 · 继续找"
                                                                : "查看更早的记录";
                                                reload();
                                                publish();
                                            });
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                olderLoading = false;
                                                notice("更早记录加载失败，轻点重试");
                                            });
                                }
                            });
                });
    }

    public void sendDraft() {
        state.execute(
                () -> {
                    if (imports.pending()) {
                        notice("请等文件导入完成，或取消导入后再发送");
                        return;
                    }
                    boolean textMode = !settings.voiceInput() || ambientWanted;
                    String text = textMode ? draft.trim() : "";
                    if (textMode && !quote.isEmpty())
                        text = "> " + quote.replace("\n", "\n> ") + "\n\n" + text;
                    if (text.isEmpty() && chips.isEmpty()) return;
                    for (Attachment attachment : chips)
                        if (attachment.state != Attachment.UPLOADED) {
                            notice("请等附件上传完成，失败的附件可点按重试");
                            return;
                        }
                    String id = UUID.randomUUID().toString();
                    if (!trying && !settings.isComplete()) {
                        notice("请先连接房间，或从欢迎页进入体验");
                        return;
                    }
                    Outbox.Item item =
                            new Outbox.Item(
                                    id,
                                    text,
                                    false,
                                    "",
                                    "",
                                    0,
                                    chips,
                                    Message.QUEUED,
                                    "",
                                    "",
                                    System.currentTimeMillis(),
                                    0);
                    repo.enqueue(Message.mine(id, text, "", 0, Message.QUEUED, chips), item);
                    if (textMode) {
                        draft = "";
                        draftGeneration++;
                        quote = "";
                    }
                    chips.clear();
                    saveDraft();
                    reload();
                    publish();
                    pump();
                });
    }

    public void retry(String id) {
        state.execute(
                () -> {
                    Outbox.Item item = repo.findPending(id);
                    if (item == null) {
                        Message logged = repo.row(id);
                        if (logged == null
                                || logged.kind != Message.MINE
                                || logged.seq < 0
                                || logged.delivery != Message.FAILED) return;
                        String newId = UUID.randomUUID().toString();
                        repo.enqueue(
                                Message.mine(
                                        newId,
                                        logged.text,
                                        "",
                                        0,
                                        Message.QUEUED,
                                        logged.attachments),
                                new Outbox.Item(
                                        newId,
                                        logged.text,
                                        false,
                                        "",
                                        "",
                                        0,
                                        logged.attachments,
                                        Message.QUEUED,
                                        "",
                                        "",
                                        System.currentTimeMillis(),
                                        0));
                    } else {
                        if (item.state != Message.FAILED) return;
                        repo.transition(item.state(Message.QUEUED, ""));
                    }
                    reload();
                    publish();
                    if (connection == Connection.ONLINE) pump();
                    else connect();
                });
    }

    public void discard(String id) {
        state.execute(
                () -> {
                    repo.discard(id);
                    settleSend();
                    reload();
                    publish();
                });
    }

    private void pump() {
        if (sending != null
                || pairingActive
                || connection != Connection.ONLINE
                || !lastError.isEmpty() && lastError.contains("API")) return;
        Outbox.Item next = null;
        for (Outbox.Item item : repo.pending())
            if (item.state == Message.QUEUED) {
                next = item;
                break;
            }
        if (next == null) return;
        if (next.display.isEmpty())
            next =
                    next.surface(
                            foreground ? settings.keepAwake() ? "phone_awake" : "phone" : "later",
                            ambientWanted || foreground && settings.ambientOutput(),
                            trying ? "" : arrivals.pending());
        final Outbox.Item item = next;
        SendOperation operation =
                new SendOperation(item, client(), repo.roomIdentity(), ++sendEpoch);
        sending = operation;
        uploadForeground =
                !trying && item.voice && (foreground || passport.snapshot().link.remembered);
        if (uploadForeground) refreshService();
        repo.transition(item.state(Message.SENDING, ""));
        reload();
        publish();
        ChannelClient.State room = roomState;
        network.execute(
                () -> {
                    byte[] body = null;
                    ChannelClient.Failure error = null;
                    try {
                        if (item.voice)
                            body = ChannelClient.voiceBody(new File(item.voiceFile), room);
                    } catch (ChannelClient.Failure failure) {
                        error = failure;
                    }
                    final byte[] prepared = body;
                    final ChannelClient.Failure failure = error;
                    state.execute(
                            () -> {
                                if (!currentSend(operation)) return;
                                operation.body = prepared;
                                if (failure == null) sendAttempt(operation);
                                else completeSend(operation, failure);
                            });
                });
    }

    private boolean currentSend(SendOperation operation) {
        return sending == operation
                && operation.ticket == sendEpoch
                && repo.roomIdentity().equals(operation.identity);
    }

    private void messageAccepted(Outbox.Item item) {
        if (item == null || trying) return;
        passport.accepted(item.id);
        arrivals.settle(item.place);
    }

    /** A live result, logged row or user discard ends the upload independently of HTTP. */
    private void settleSend() {
        if (sending == null) return;
        Outbox.Item item = repo.findPending(sending.item.id);
        if (item == null || item.state != Message.SENDING) {
            finishSend();
            pump();
        }
    }

    private void finishSend() {
        sending = null;
        sendEpoch++;
        uploadForeground = false;
        refreshService();
    }

    private void sendAttempt(SendOperation operation) {
        if (!currentSend(operation)) return;
        Outbox.Item current = repo.findPending(operation.item.id);
        if (current == null || current.state != Message.SENDING) {
            settleSend();
            return;
        }
        if (operation.item.voice && operation.retry.remainingMs() == 0) {
            completeSend(
                    operation,
                    operation.error == null
                            ? new ChannelClient.Failure("暂时无法连接房间", 0, true)
                            : operation.error);
            return;
        }
        Outbox.Item item = operation.item;
        byte[] body = operation.body;
        ChannelClient.Failure previousError = operation.error;
        network.execute(
                () -> {
                    if (operation.ticket != sendEpoch) return;
                    ChannelClient.Failure problem = null;
                    try {
                        if (item.voice) {
                            long remaining = operation.retry.remainingMs();
                            if (remaining == 0)
                                throw previousError == null
                                        ? new ChannelClient.Failure("暂时无法连接房间", 0, true)
                                        : previousError;
                            operation.client.sendVoice(
                                    body,
                                    item.source,
                                    item.id,
                                    item.display,
                                    item.spoken,
                                    item.place,
                                    remaining);
                        } else {
                            operation.client.sendText(
                                    item.text,
                                    item.attachments,
                                    item.id,
                                    item.display,
                                    item.spoken,
                                    item.place);
                        }
                    } catch (ChannelClient.Failure failure) {
                        problem = failure;
                    }
                    final ChannelClient.Failure error = problem;
                    state.execute(
                            () -> {
                                if (!currentSend(operation)) return;
                                Outbox.Item pending = repo.findPending(item.id);
                                if (pending == null || pending.state != Message.SENDING) {
                                    settleSend();
                                    return;
                                }
                                operation.error = error;
                                if (error != null
                                        && item.voice
                                        && (error.transport || error.status >= 500)) {
                                    long delay = operation.retry.nextDelayMs();
                                    if (delay > 0) {
                                        state.schedule(
                                                () -> sendAttempt(operation),
                                                delay,
                                                TimeUnit.MILLISECONDS);
                                        return;
                                    }
                                }
                                completeSend(operation, error);
                            });
                });
    }

    private void completeSend(SendOperation operation, ChannelClient.Failure error) {
        if (!currentSend(operation)) return;
        Outbox.Item current = repo.findPending(operation.item.id);
        if (current == null || current.state != Message.SENDING) {
            settleSend();
            return;
        }
        finishSend();
        if (error == null) {
            messageAccepted(current);
            log.event("send", "accepted " + (current.voice ? "voice" : "text"));
            repo.transition(current.state(Outbox.ACCEPTED, ""));
        } else if (error.unauthorized()) {
            repo.transition(current.state(Message.QUEUED, ""));
            unauthorized();
        } else {
            if (current.source.equals("passport"))
                passport.uploadFailed(current.id, error.transport);
            repo.transition(
                    current.state(
                            error.transport ? Message.QUEUED : Message.FAILED,
                            sendFailure(current, error)));
        }
        reload();
        if (error != null && error.transport) {
            int epoch = ++connectionEpoch;
            socketWriter.getQueue().clear();
            channel.closeLive();
            linkClosed("发送连接中断", epoch);
        }
        publish();
        if (error == null || !error.transport) pump();
    }

    private static String sendFailure(Outbox.Item item, ChannelClient.Failure error) {
        if (error.transport) return "等待网络恢复";
        if (item.voice) {
            if (error.status == 503) return "语音服务暂不可用 · 轻点重试";
            if (error.status == 413) return "录音太长，超过上传上限";
            if (error.reason.equals("无法读取待发送语音")) return error.reason;
        }
        return "没发出去 · 轻点重试" + (error.status > 0 ? "（" + error.status + "）" : "");
    }

    public void importAttachment(Uri uri) {
        importAttachment(uri, () -> {});
    }

    public void importAttachment(Uri uri, Runnable finished) {
        importContent(UUID.randomUUID().toString(), "", Collections.singletonList(uri), finished);
    }

    public void importContent(String delivery, String text, List<Uri> files, Runnable finished) {
        imports.content(delivery, text, files, finished);
    }

    public ImportRoute importRoute(ImportRoute.Kind kind) {
        Snapshot current = snapshot;
        if (current == null) throw new IllegalStateException("Room state is not loaded");
        return new ImportRoute(UUID.randomUUID().toString(), current.importTarget, kind);
    }

    public void importContent(ImportRoute route, String text, List<Uri> files, Runnable finished) {
        imports.content(route, text, files, finished);
    }

    public void importPhoto(ImportRoute route, File original) {
        imports.photo(route, original);
    }

    public void importPhoto(String delivery, File original) {
        imports.photo(delivery, original);
    }

    public void cancelImport(String delivery) {
        state.execute(() -> imports.cancel(delivery));
    }

    private void admitImportedDraft(ImportBatch batch) {
        List<Attachment> combined = new ArrayList<>(chips);
        combined.addAll(batch.attachments);
        String next = batch.appendTo(draft);
        if (repo.importDraft(batch.id, next, quote, encodeChips(combined))) {
            draft = next;
            chips.clear();
            chips.addAll(combined);
            if (!batch.text.isEmpty()) settings.voiceInput(false);
            publish();
            for (Attachment attachment : batch.attachments) upload(attachment);
        }
    }

    public void removeAttachment(String path) {
        state.execute(
                () -> {
                    for (int i = chips.size() - 1; i >= 0; i--)
                        if (chips.get(i).localPath.equals(path)) {
                            new File(path).delete();
                            chips.remove(i);
                        }
                    saveDraft();
                    publish();
                });
    }

    public void retryAttachment(String path) {
        state.execute(
                () -> {
                    for (Attachment attachment : chips)
                        if (attachment.localPath.equals(path)) {
                            upload(attachment);
                            break;
                        }
                });
    }

    private void upload(Attachment attachment) {
        if (!trying && !settings.isComplete()) {
            replaceChip(attachment.failed());
            notice("请先连接房间");
            return;
        }
        boolean demo = trying;
        ChannelClient client = client();
        ChannelClient.State room = roomState;
        String identity = repo.roomIdentity();
        replaceChip(
                new Attachment(
                        attachment.name,
                        attachment.mime,
                        attachment.localPath,
                        attachment.sha256,
                        attachment.remotePath,
                        attachment.size,
                        Attachment.UPLOADING));
        network.execute(
                () -> {
                    try {
                        Attachment result = client.upload(attachment, room);
                        state.execute(
                                () -> {
                                    if (identity.equals(repo.roomIdentity()) && demo == trying)
                                        replaceChip(result);
                                });
                    } catch (ChannelClient.Failure failure) {
                        state.execute(
                                () -> {
                                    if (!identity.equals(repo.roomIdentity()) || demo != trying)
                                        return;
                                    if (replaceChip(attachment.failed()))
                                        notice(
                                                failure.status == 413
                                                        ? "文件太大，请移除或选择较小文件"
                                                        : "附件上传失败，点按重试");
                                });
                    }
                });
    }

    private boolean replaceChip(Attachment value) {
        for (int i = 0; i < chips.size(); i++)
            if (chips.get(i).localPath.equals(value.localPath)) {
                chips.set(i, value);
                saveDraft();
                publish();
                return true;
            }
        return false;
    }

    public void beginHold() {
        state.execute(
                () -> {
                    if (holdRequested) return;
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        notice("请允许麦克风权限");
                        return;
                    }
                    if (!trying && !settings.isComplete()) {
                        notice("请先连接房间");
                        return;
                    }
                    if (!trying && !voiceAvailable()) {
                        notice("语音服务暂不可用");
                        return;
                    }
                    try {
                        File dir =
                                new File(
                                        trying ? context.getCacheDir() : context.getFilesDir(),
                                        trying ? "try-voice" : "voice");
                        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException();
                        heldFile = new File(dir, UUID.randomUUID().toString() + ".packets");
                        heldFileOutput = new FileOutputStream(heldFile);
                        heldOutput = new BufferedOutputStream(heldFileOutput);
                        holdRequested = true;
                        holdLive = false;
                        holdPackets = 0;
                        lastHoldPublish = 0;
                        audioSuspended = false;
                        for (JSONObject outgoing : ambientInput.press("phone", true))
                            send(outgoing);
                        interruptPlayback();
                        if (capture == null) startCapture();
                        else if (!captureStopping) holdLive = true;
                        publish();
                    } catch (Exception failure) {
                        notice("无法准备语音存储");
                    }
                });
    }

    public void releaseHold(boolean cancel) {
        state.execute(() -> releaseOnState(cancel));
    }

    private void releaseOnState(boolean cancel) {
        releaseOnState(cancel, false);
    }

    private void releaseOnState(boolean cancel, boolean interrupted) {
        if (!holdRequested) return;
        holdRequested = false;
        holdLive = false;
        try {
            if (heldOutput != null && !cancel && holdPackets >= 15) {
                heldOutput.flush();
                heldFileOutput.getFD().sync();
            }
        } catch (Exception failure) {
            cancel = true;
            lastError = "语音保存失败，请检查存储空间";
        } finally {
            try {
                if (heldOutput != null) heldOutput.close();
            } catch (Exception failure) {
                cancel = true;
                lastError = "语音保存失败，请检查存储空间";
            }
        }
        heldOutput = null;
        heldFileOutput = null;
        if (!ambientWanted) stopCapture();
        for (JSONObject outgoing : ambientInput.press("phone", false)) send(outgoing);
        if (cancel || holdPackets < 15) {
            if (heldFile != null) heldFile.delete();
            if (!cancel) lastError = "按住说话，麦克风就绪后再松开";
        } else {
            String id = UUID.randomUUID().toString();
            Outbox.Item item =
                    new Outbox.Item(
                            id,
                            "",
                            true,
                            heldFile.getPath(),
                            "phone",
                            holdPackets * 20L,
                            Collections.<Attachment>emptyList(),
                            interrupted ? Message.FAILED : Message.QUEUED,
                            interrupted ? "录音被打断 · 轻点重试" : "",
                            "",
                            System.currentTimeMillis(),
                            0);
            repo.enqueue(
                    Message.mine(
                                    id,
                                    "",
                                    "phone",
                                    holdPackets * 20L,
                                    item.state,
                                    Collections.<Attachment>emptyList())
                            .withDelivery(item.state, item.error),
                    item);
            reload();
            if (!interrupted) pump();
        }
        heldFile = null;
        publish();
    }

    private void startCapture() {
        if (capture != null
                || captureStopping
                || audioSuspended
                || ambientWanted && !holdRequested && connection != Connection.ONLINE
                || (!holdRequested && !ambientWanted)) return;
        if (!audioFocus.acquire(ambientWanted)) {
            audioSuspended = audioFocus.interrupted();
            releaseOnState(true);
            if (ambientWanted) ambient = Ambient.PAUSED;
            notice("其他应用正在使用音频，请稍后重试");
            return;
        }
        int epoch = ++captureEpoch;
        capture =
                new CaptureSession(
                        context,
                        settings.headsetMic(),
                        new CaptureSession.Listener() {
                            @Override
                            public void started() {
                                state.execute(
                                        () -> {
                                            if (epoch == captureEpoch) {
                                                if (holdRequested) holdLive = true;
                                                publish();
                                            }
                                        });
                            }

                            @Override
                            public void packet(byte[] packet) {
                                state.execute(
                                        () -> {
                                            if (epoch == captureEpoch) captured(packet);
                                        });
                            }

                            @Override
                            public void stopped(String reason) {
                                state.execute(
                                        () -> {
                                            if (epoch != captureEpoch) return;
                                            capture = null;
                                            boolean intentional = captureStopping;
                                            captureStopping = false;
                                            if (!reason.isEmpty() && !intentional) {
                                                releaseOnState(true);
                                                if (ambientWanted) {
                                                    ambient = Ambient.FAILED;
                                                    ambientWanted = false;
                                                    refreshService();
                                                }
                                                notice(reason);
                                            } else if (holdRequested || ambientWanted)
                                                startCapture();
                                            if (!holdRequested && !ambientWanted && !playerActive)
                                                audioFocus.release();
                                        });
                            }
                        });
    }

    private void stopCapture() {
        ambientInput.captureStopped();
        if (capture != null) {
            captureStopping = true;
            capture.stop();
        }
    }

    private void captured(byte[] packet) {
        if (captureStopping) return;
        if (holdRequested && heldOutput != null) {
            try {
                heldOutput.write(packet.length & 255);
                heldOutput.write(packet.length >>> 8);
                heldOutput.write(packet);
                holdPackets++;
                if (holdPackets * 20 - lastHoldPublish >= 100) {
                    lastHoldPublish = holdPackets * 20;
                    publish();
                }
                if (heldFile.length() > 16L * 1024 * 1024) {
                    releaseOnState(true);
                    notice("语音已达到本机大小上限");
                }
            } catch (Exception failure) {
                releaseOnState(true);
                notice("语音保存失败，请检查存储空间");
            }
        }
        if (!ambientWanted || !channel.isOpen()) return;
        boolean before = ambientInput.helloSent();
        for (JSONObject outgoing :
                ambientInput.packet(currentRoom(), capture != null && capture.echoCancellation()))
            send(outgoing);
        if (!before && ambientInput.helloSent()) {
            ambient = Ambient.STARTING;
            publish();
        }
        if (!ambientInput.sendsAudio()) return;
        byte[] bytes = packet;
        if (ambientInput.media()) {
            bytes = new byte[packet.length + 1];
            bytes[0] = 1;
            System.arraycopy(packet, 0, bytes, 1, packet.length);
        }
        sendBinary(bytes);
    }

    public void ambientToggle() {
        state.execute(
                () -> {
                    if (ambientWanted) ambientOff();
                    else {
                        if (!trying && !settings.isComplete()) {
                            notice("请先连接房间");
                            return;
                        }
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                            notice("请先授予麦克风权限");
                            return;
                        }
                        ambientWanted = true;
                        ambient = Ambient.STARTING;
                        audioSuspended = false;
                        setOutputMuted(false);
                        startService();
                        startCapture();
                        reconnect();
                        publish();
                    }
                });
    }

    public void endAmbient() {
        state.execute(this::ambientOff);
    }

    private void ambientOff() {
        ambientWanted = false;
        setOutputMuted(false);
        ambient = Ambient.OFF;
        ambientInput.captureStopped();
        sightSession.clear();
        stopCapture();
        stopPlayback();
        refreshService();
        if (channel.isOpen()) reconnect();
        publish();
    }

    /** The notification's stop action ends capture; an accepted upload remains durable. */
    public void stopSessionCapture() {
        state.execute(
                () -> {
                    if (ambientWanted) ambientOff();
                    else refreshService();
                });
    }

    public void ambientMute() {
        state.execute(
                () -> {
                    setOutputMuted(!ambientMuted);
                    surface();
                    publish();
                });
    }

    private void setOutputMuted(boolean muted) {
        ambientMuted = muted;
        if (player != null) player.silent(muted);
    }

    public void ambientHush() {
        state.execute(
                () -> {
                    stopPlayback();
                    send(Json.object("type", "hush"));
                    publish();
                });
    }

    public void takeover() {
        state.execute(
                () -> {
                    audioSuspended = false;
                    for (JSONObject outgoing : ambientInput.takeover(currentRoom())) send(outgoing);
                    if (capture == null) startCapture();
                    ambient = Ambient.STARTING;
                    publish();
                });
    }

    private void startService() {
        refreshService();
    }

    private void refreshService() {
        boolean bluetooth = passport.snapshot().link.remembered;
        boolean needed = !trying && (ambientWanted || uploadForeground || bluetooth);
        if (needed) {
            try {
                context.startForegroundService(
                        new Intent(context, PocketSessionService.class)
                                .putExtra("microphone", ambientWanted)
                                .putExtra("bluetooth", bluetooth)
                                .putExtra("upload", uploadForeground));
                serviceRunning = true;
            } catch (IllegalStateException | SecurityException failure) {
                serviceRunning = false;
                if (ambientWanted) {
                    ambientWanted = false;
                    ambient = Ambient.FAILED;
                    stopCapture();
                    lastError = "无法在后台收音，请回到 Pocket E2 后重新开启";
                }
                log.event("service", "foreground_start_denied");
            }
        } else if (serviceRunning) {
            context.stopService(new Intent(context, PocketSessionService.class));
            serviceRunning = false;
        }
    }

    private void surface() {
        if (channel.isOpen())
            send(
                    Json.object(
                            "type",
                            "surface",
                            "display",
                            foreground ? settings.keepAwake() ? "phone_awake" : "phone" : "later",
                            "spoken",
                            (ambientWanted || settings.ambientOutput() && foreground)
                                    && !ambientMuted
                                    && !audioSuspended));
    }

    private void send(JSONObject frame) {
        int epoch = connectionEpoch;
        ChannelConnection target = channel;
        socketTask(epoch, () -> target.send(frame));
    }

    private void sendBinary(byte[] bytes) {
        int epoch = connectionEpoch;
        ChannelConnection target = channel;
        socketTask(epoch, () -> target.sendBinary(bytes));
    }

    private interface SocketTask {
        void run() throws Exception;
    }

    private void socketTask(int epoch, SocketTask task) {
        try {
            socketWriter.execute(
                    () -> {
                        if (epoch != connectionEpoch) return;
                        try {
                            task.run();
                        } catch (Exception failure) {
                            state.execute(() -> linkClosed("实时连接中断", epoch));
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            state.execute(() -> linkClosed("实时连接拥塞", epoch));
        }
    }

    private void speech(String id) {
        if (id.isEmpty()) return;
        if (!readingId.isEmpty()) stopPlayback();
        if (decoder != null) decoder.close();
        decoder = null;
        if (ambientInput.pressMuted()) {
            ambientOutput.stop();
            if (reportsPlayback()) send(Json.object("type", "interrupted", "speech_id", id));
            return;
        }
        ambientOutput.speech(id);
        try {
            decoder = new OpusDecoder();
            ensurePlayer();
        } catch (Exception failure) {
            notice("无法启动语音播放");
        }
    }

    private void binary(byte[] bytes) {
        if (!ambientInput.metaSeen()) return;
        if (ambientInput.media()) {
            if (bytes.length < 2 || bytes[0] != 1) return;
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        if (decoder == null
                || ambientInput.pressMuted()
                || !readingId.isEmpty()
                || !foreground && !ambientWanted) return;
        try {
            short[] pcm = decoder.packet(bytes);
            if (ambientWanted && ambientMuted) {
                JSONObject report = ambientOutput.skipped(pcm.length, reportsPlayback());
                if (report != null) send(report);
            } else player.enqueue(ambientOutput.playbackKey(), pcm);
        } catch (Exception failure) {
            notice("语音解码失败");
            stopPlayback();
        }
    }

    private void ensurePlayer() {
        if (!audioFocus.acquire(ambientWanted))
            throw new IllegalStateException("Audio focus unavailable");
        if (player != null) return;
        player =
                new SpeechPlayer(
                        new SpeechPlayer.Listener() {
                            @Override
                            public void route(String name) {
                                state.execute(
                                        () -> {
                                            outputRoute = name;
                                            publish();
                                        });
                            }

                            @Override
                            public void played(
                                    int generation, String id, long ms, boolean drained) {
                                state.execute(
                                        () -> {
                                            if (player == null || generation != player.generation())
                                                return;
                                            JSONObject report =
                                                    ambientOutput.played(id, ms, reportsPlayback());
                                            if (report != null) send(report);
                                        });
                            }

                            @Override
                            public void active(int generation, boolean active) {
                                state.execute(
                                        () -> {
                                            if (player == null || generation != player.generation())
                                                return;
                                            if (playerActive == active) return;
                                            playerActive = active;
                                            if (ambientWanted
                                                    && !audioSuspended
                                                    && connection == Connection.ONLINE
                                                    && ambientInput.role().equals("master"))
                                                ambient =
                                                        active
                                                                ? Ambient.SPEAKING
                                                                : Ambient.LISTENING;
                                            if (!active) finishReadIfDrained();
                                            if (!active
                                                    && capture == null
                                                    && !ambientWanted
                                                    && !readInFlight
                                                    && !player.hasPending()) audioFocus.release();
                                            publish();
                                        });
                            }

                            @Override
                            public void failed(int generation, String reason) {
                                state.execute(
                                        () -> {
                                            if (player != null && generation == player.generation())
                                                notice(reason);
                                        });
                            }
                        });
        player.silent(ambientMuted);
    }

    private boolean reportsPlayback() {
        return ambientWanted && ambientInput.helloSent();
    }

    private void finishReadIfDrained() {
        if (!readInFlight && ambientOutput.reading() && (player == null || !player.hasPending())) {
            readingId = "";
            if (capture == null && !ambientWanted) audioFocus.release();
        }
    }

    private void interruptPlayback() {
        JSONObject report = ambientOutput.interrupted(reportsPlayback());
        stopPlayback();
        if (report != null) send(report);
    }

    private void stopPlayback() {
        if (readInFlight) {
            if (readChannel != null) readChannel.cancelStream(readRequest);
            readInFlight = false;
        }
        readRequest = 0;
        readChannel = null;
        if (player != null) player.stop();
        if (decoder != null) decoder.close();
        decoder = null;
        ambientOutput.stop();
        readingId = "";
        playerActive = false;
        if (ambientWanted
                && !audioSuspended
                && connection == Connection.ONLINE
                && ambientInput.role().equals("master")) ambient = Ambient.LISTENING;
        if (capture == null && !ambientWanted) audioFocus.release();
    }

    public void readAloud(Message message) {
        state.execute(
                () -> {
                    if (readingId.equals(message.id)) {
                        stopPlayback();
                        publish();
                        return;
                    }
                    if (holdRequested) {
                        notice("请先结束收音");
                        return;
                    }
                    if (!trying && !settings.isComplete()) {
                        notice("请先连接房间");
                        return;
                    }
                    interruptPlayback();
                    try {
                        ensurePlayer();
                    } catch (Exception failure) {
                        notice("其他应用正在使用音频，请稍后再朗读");
                        return;
                    }
                    readingId = message.id;
                    ambientOutput.read(message.id);
                    final SpeechPlayer output = player;
                    final String speech = ambientOutput.playbackKey();
                    final long request = ++nextReadRequest;
                    readRequest = request;
                    readInFlight = true;
                    ChannelConnection target = channel;
                    readChannel = target;
                    Endpoint endpoint = trying ? TryChannel.ENDPOINT : settings.endpoint();
                    String token = trying ? TryChannel.TOKEN : settings.token();
                    String room = currentRoom();
                    publish();
                    network.execute(
                            () -> {
                                top.kuaner.pockete2.audio.PacketStream packets =
                                        new top.kuaner.pockete2.audio.PacketStream();
                                final boolean[] received = {false};
                                final Exception[] parseError = {null};
                                try (OpusDecoder read = new OpusDecoder()) {
                                    top.kuaner.pockete2.net.ChannelTransport.Response response =
                                            target.stream(
                                                    endpoint,
                                                    token,
                                                    request,
                                                    "/api/speech?room=" + Endpoint.query(room),
                                                    Json.object("text", message.text)
                                                            .toString()
                                                            .getBytes(StandardCharsets.UTF_8),
                                                    bytes -> {
                                                        if (readRequest != request
                                                                || parseError[0] != null) return;
                                                        try {
                                                            for (byte[] packet :
                                                                    packets.push(bytes)) {
                                                                received[0] = true;
                                                                if (!output.enqueueWaiting(
                                                                        speech,
                                                                        read.packet(packet),
                                                                        () ->
                                                                                readRequest
                                                                                        == request))
                                                                    break;
                                                            }
                                                        } catch (Exception failure) {
                                                            parseError[0] = failure;
                                                            target.cancelStream(request);
                                                        }
                                                    },
                                                    () -> readRequest == request);
                                    if (parseError[0] != null) throw parseError[0];
                                    packets.finish();
                                    if (response.status != 200)
                                        throw new ChannelClient.Failure(
                                                "speech", response.status, false);
                                    state.execute(
                                            () -> {
                                                if (readRequest == request) {
                                                    readInFlight = false;
                                                    finishReadIfDrained();
                                                    publish();
                                                }
                                            });
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                if (readRequest != request) return;
                                                readInFlight = false;
                                                String reason =
                                                        received[0]
                                                                ? "朗读中断，已播放的不是完整回答"
                                                                : failure
                                                                                instanceof
                                                                                ChannelClient
                                                                                        .Failure
                                                                        ? speechError(
                                                                                ((ChannelClient
                                                                                                        .Failure)
                                                                                                failure)
                                                                                        .status)
                                                                        : "连接不上，无法朗读";
                                                if (!received[0]) stopPlayback();
                                                else finishReadIfDrained();
                                                notice(reason);
                                            });
                                }
                            });
                });
    }

    private static String speechError(int status) {
        switch (status) {
            case 422:
                return "这条消息没有可朗读的内容";
            case 413:
                return "这条消息太长，无法朗读";
            case 502:
                return "语音合成失败，稍后再试";
            case 503:
                return "语音服务暂不可用";
            case 401:
                return "请重新配对房间";
            default:
                return "朗读失败（" + status + "）";
        }
    }

    public void pair(
            String host,
            int port,
            boolean tls,
            String ignoredRoom,
            String ignoredName,
            String code,
            Listener result) {
        state.execute(
                () -> {
                    Endpoint endpoint;
                    try {
                        endpoint = new Endpoint(host, port, tls);
                    } catch (IllegalArgumentException failure) {
                        notice(failure.getMessage());
                        if (result != null) main.post(result::changed);
                        return;
                    }
                    connectionEpoch++;
                    sendEpoch++;
                    final long pairTicket = ++pairingEpoch;
                    pairingActive = true;
                    sending = null;
                    uploadForeground = false;
                    repo.select(repo.roomIdentity());
                    reload();
                    connection = Connection.CONNECTING;
                    channel.closeLive();
                    opening = false;
                    releaseOnState(true);
                    passport.online(false);
                    stopCapture();
                    ambientWanted = false;
                    ambient = Ambient.OFF;
                    stopPlayback();
                    sightSession.clear();
                    refreshService();
                    network.execute(
                            () -> {
                                try {
                                    transport.start(
                                            settings.networkLog(),
                                            status -> state.execute(() -> networkStatus(status)));
                                    JSONObject value =
                                            new ChannelClient(endpoint, "", "", transport)
                                                    .pair(code);
                                    state.execute(
                                            () -> {
                                                if (pairTicket != pairingEpoch) return;
                                                pairingActive = false;
                                                try {
                                                    if (trying) cleanupTry();
                                                    settings.saveConnection(
                                                            endpoint.host,
                                                            endpoint.port,
                                                            endpoint.tls,
                                                            value.optString("room"),
                                                            value.optString(
                                                                    "room_name",
                                                                    value.optString("room")),
                                                            value.optString("device_id"),
                                                            value.optString("scope"),
                                                            value.optString("token"));
                                                    trying = false;
                                                    sending = null;
                                                    sendEpoch++;
                                                    viewer.reset();
                                                    turn.reset();
                                                    resetRoomTransient();
                                                    chips.clear();
                                                    boolean wasUnpaired =
                                                            repo.roomIdentity().equals("unpaired");
                                                    repo.select(settings);
                                                    if (wasUnpaired) repo.inheritUnpairedDraft();
                                                    passport.room(repo.roomIdentity());
                                                    restoreDraft();
                                                    imports.recover(false);
                                                    for (Attachment attachment :
                                                            new ArrayList<>(chips))
                                                        if (attachment.sha256.isEmpty())
                                                            upload(attachment);
                                                    connection = Connection.CONNECTING;
                                                    lastError = "";
                                                    reload();
                                                    connect();
                                                    publish();
                                                } catch (Exception failure) {
                                                    notice("配对成功，但本机保存失败，请重新配对");
                                                }
                                                if (result != null) main.post(result::changed);
                                            });
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                if (pairTicket != pairingEpoch) return;
                                                pairingActive = false;
                                                lastError =
                                                        failure instanceof ChannelClient.Failure
                                                                        && ((ChannelClient.Failure)
                                                                                        failure)
                                                                                .reason.equals(
                                                                                        "invalid_code")
                                                                ? "配对码已失效，请重新获取链接"
                                                                : "配对失败，请检查网络、地址和配对码";
                                                if (trying) connect();
                                                publish();
                                                if (result != null) main.post(result::changed);
                                            });
                                }
                            });
                });
    }

    public void forgetPairing() {
        state.execute(
                () -> {
                    ambientWanted = false;
                    pairingEpoch++;
                    pairingActive = false;
                    connectionEpoch++;
                    opening = false;
                    sending = null;
                    uploadForeground = false;
                    sendEpoch++;
                    viewer.reset();
                    channel.closeLive();
                    releaseOnState(true);
                    stopCapture();
                    stopPlayback();
                    sightSession.clear();
                    ambient = Ambient.OFF;
                    refreshService();
                    if (trying) {
                        cleanupTry();
                        trying = false;
                    }
                    repo.clear();
                    passport.online(false);
                    settings.clearConnection();
                    resetRoomTransient();
                    top.kuaner.pockete2.app.BackgroundSync.schedule(context);
                    repo.select(settings);
                    connection = Connection.NEEDS_SETUP;
                    draft = "";
                    draftGeneration++;
                    quote = "";
                    chips.clear();
                    turn.reset();
                    reload();
                    publish();
                });
    }

    public void startTrying() {
        state.execute(this::startTryingOnState);
    }

    private boolean startTryingOnState() {
        if (trying) return true;
        TryChannel local;
        try {
            local =
                    new TryChannel(
                            this::readTryResource,
                            new File(context.getCacheDir(), "try-channel/" + UUID.randomUUID()),
                            top.kuaner.pockete2.text.AppText.get(context).en);
        } catch (Exception failure) {
            notice("无法准备体验房间，请检查本机存储空间");
            return false;
        }
        pairingEpoch++;
        pairingActive = false;
        releaseOnState(true);
        saveDraft();
        sendEpoch++;
        connectionEpoch++;
        sending = null;
        opening = uploadForeground = false;
        channel.closeLive();
        socketWriter.getQueue().clear();
        stopCapture();
        stopPlayback();
        ambientWanted = false;
        ambient = Ambient.OFF;
        sightSession.clear();
        passport.online(false);
        realRepo.select(realRepo.roomIdentity());
        trying = true;
        channel = tryChannel = local;
        repo = new RoomRepository(context, null);
        repo.select("try:" + UUID.randomUUID());
        viewer.reset();
        turn.reset();
        resetRoomTransient();
        chips.clear();
        draft = quote = lastError = "";
        draftGeneration++;
        refreshService();
        connection = Connection.CONNECTING;
        reload();
        publish();
        connect();
        return true;
    }

    public void stopTrying() {
        state.execute(() -> stopTryingOnState(true));
    }

    private void stopTryingOnState(boolean resumeReal) {
        if (!trying) return;
        releaseOnState(true);
        connectionEpoch++;
        sendEpoch++;
        sending = null;
        opening = uploadForeground = false;
        stopPlayback();
        stopCapture();
        ambientWanted = false;
        ambient = Ambient.OFF;
        cleanupTry();
        trying = false;
        resetRoomTransient();
        viewer.reset();
        chips.clear();
        draft = quote = "";
        turn.reset();
        repo.select(settings);
        passport.room(repo.roomIdentity());
        restoreDraft();
        imports.recover(false);
        if (resumeReal) refreshService();
        connection = settings.isComplete() ? Connection.CONNECTING : Connection.NEEDS_SETUP;
        reload();
        publish();
        if (resumeReal && settings.isComplete()) startNetwork();
    }

    private String currentRoom() {
        return trying ? TryChannel.ROOM : settings.room();
    }

    private byte[] readTryResource(String name) throws java.io.IOException {
        try (java.io.InputStream input = context.getAssets().open("try/" + name);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private void cleanupTry() {
        if (tryChannel != null) tryChannel.close();
        tryChannel = null;
        if (repo != realRepo) {
            repo.clear();
            repo.close();
            repo = realRepo;
        }
        channel = transport;
        for (String name :
                new String[] {"try-channel", "try-attachments", "try-downloads", "try-voice"})
            deleteTryFiles(new File(context.getCacheDir(), name));
        imports.clearTry();
        File[] shared = new File(context.getCacheDir(), "shared").listFiles();
        if (shared != null)
            for (File file : shared) if (file.getName().startsWith("try-")) file.delete();
    }

    private static void deleteTryFiles(File directory) {
        File[] children = directory.listFiles();
        if (children != null)
            for (File file : children) {
                if (file.isDirectory()) deleteTryFiles(file);
                else file.delete();
            }
        directory.delete();
    }

    public void backgroundFetch(Runnable completed) {
        state.execute(
                () -> {
                    if (trying || !settings.isComplete() || settings.unauthorized()) {
                        main.post(completed);
                        return;
                    }
                    ChannelClient client = client();
                    String identity = repo.roomIdentity();
                    long cursor = repo.cursor();
                    boolean bootstrapped = repo.hasCursor();
                    network.execute(
                            () -> {
                                try {
                                    transport.start(
                                            settings.networkLog(),
                                            status -> state.execute(() -> networkStatus(status)));
                                    ChannelClient.Rows rows = client.rows(cursor);
                                    state.execute(
                                            () -> {
                                                try {
                                                    if (!trying
                                                            && identity.equals(
                                                                    repo.roomIdentity())) {
                                                        repo.admit(rows.messages, rows.cursor);
                                                        for (Message row : rows.messages)
                                                            if (row.kind == Message.DUODUO) {
                                                                List<String> ids =
                                                                        row.id.startsWith("ans:")
                                                                                ? Arrays.asList(
                                                                                        row.id
                                                                                                .substring(
                                                                                                        4)
                                                                                                .split(
                                                                                                        ","))
                                                                                : Collections
                                                                                        .<String>
                                                                                                emptyList();
                                                                notifications.frame(
                                                                        Json.object(
                                                                                "type",
                                                                                "answer_final",
                                                                                "text",
                                                                                row.text,
                                                                                "message_ids",
                                                                                new JSONArray(ids)),
                                                                        passport.snapshot()
                                                                                .link
                                                                                .ready,
                                                                        bootstrapped);
                                                            }
                                                        reload();
                                                        publish();
                                                    }
                                                } finally {
                                                    main.post(completed);
                                                }
                                            });
                                } catch (Exception failure) {
                                    log.event("background", "catchup_failed");
                                    main.post(completed);
                                }
                            });
                });
    }

    public void exportDiagnostics(String report, FileResult result) {
        network.execute(
                () -> {
                    try {
                        File zip =
                                log.export(
                                        context,
                                        report,
                                        new File(
                                                context.getNoBackupFilesDir(),
                                                "tailnet/tsnet.log"));
                        main.post(() -> result.ready(zip, "application/zip"));
                    } catch (Exception failure) {
                        state.execute(() -> notice("诊断导出失败，请检查存储空间"));
                    }
                });
    }

    public void toggleSight() {
        state.execute(sightSession::toggle);
    }

    public void flipSight() {
        state.execute(sightSession::flip);
    }

    public top.kuaner.pockete2.sight.PreviewFrame sightPreview() {
        return sightSession.preview();
    }

    public void sightPreviewVisible(boolean value) {
        sightSession.previewWanted(value);
    }

    private void updateSight() {
        sightSession.update(
                ambientWanted,
                ambientInput.role().equals("master"),
                ambientInput.media(),
                foreground,
                heatLevel);
    }

    public void scanPassport() {
        state.execute(passport::scan);
    }

    public void resumeAccessory() {
        state.execute(
                () -> {
                    if (!trying && passport.snapshot().link.remembered) passport.resume();
                });
    }

    public void connectPassport(String address) {
        state.execute(() -> passport.connect(address));
    }

    public void forgetPassport() {
        state.execute(passport::forget);
    }

    private void passportPress(top.kuaner.pockete2.passport.PressAssembler.Press press) {
        if (press.packets.isEmpty()) {
            passport.rejected(press.id, 1);
            return;
        }
        if (trying || !settings.isComplete()) {
            passport.rejected(press.id, 4);
            return;
        }
        if (press.overflow) {
            passport.rejected(press.id, 3);
            notice("Passport 语音超过本机大小上限");
            return;
        }
        try {
            File dir = new File(context.getFilesDir(), "voice");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException();
            String id = UUID.randomUUID().toString();
            File file = new File(dir, id + ".packets");
            try (FileOutputStream out = new FileOutputStream(file)) {
                for (byte[] packet : press.packets) {
                    out.write(packet.length & 255);
                    out.write(packet.length >>> 8);
                    out.write(packet);
                }
                out.getFD().sync();
            }
            passport.expect(id, press.id);
            Outbox.Item item =
                    new Outbox.Item(
                            id,
                            "",
                            true,
                            file.getPath(),
                            "passport",
                            press.packets.size() * 20L,
                            Collections.<Attachment>emptyList(),
                            Message.QUEUED,
                            "",
                            "",
                            System.currentTimeMillis(),
                            0);
            repo.enqueue(
                    Message.mine(
                            id, "", "passport", item.durationMs, Message.QUEUED, item.attachments),
                    item);
            reload();
            publish();
            if (connection == Connection.ONLINE) pump();
            else {
                startNetwork();
                connect();
            }
        } catch (Exception failure) {
            passport.rejected(press.id, 3);
            notice("Passport 语音未能保存");
        }
    }

    public void showSessions(boolean shown) {
        state.execute(
                () -> {
                    JSONObject frame = viewer.show(shown);
                    if (foreground && (trying || settings.scope().matches("sessions|notify")))
                        send(frame);
                    publish();
                });
    }

    public void sessionList() {
        state.execute(
                () -> {
                    JSONObject frame = viewer.list();
                    if (foreground) send(frame);
                    publish();
                });
    }

    public void openSession(String key) {
        state.execute(
                () -> {
                    JSONObject frame = viewer.open(key);
                    if (foreground) send(frame);
                    publish();
                });
    }

    public void notifySession(String text) {
        state.execute(
                () -> {
                    if (connection != Connection.ONLINE) {
                        notice("连接恢复后再发送通知");
                        return;
                    }
                    JSONObject frame = viewer.notify(text, UUID.randomUUID().toString());
                    if (frame != null) send(frame);
                    publish();
                });
    }

    public void download(Attachment attachment, FileResult result) {
        loadAttachment(attachment, result, false);
    }

    public void previewAttachment(Attachment attachment, FileResult result) {
        loadAttachment(attachment, result, true);
    }

    private void loadAttachment(Attachment attachment, FileResult result, boolean preview) {
        state.execute(
                () -> {
                    boolean local = !attachment.localPath.isEmpty();
                    if (!local && !trying && !settings.isComplete()) {
                        if (!preview) notice("请先配对房间");
                        main.post(result::failed);
                        return;
                    }
                    boolean demo = trying;
                    String identity = repo.roomIdentity();
                    String cacheScope = demo ? identity.substring(4) : "";
                    ChannelClient client = client();
                    network.execute(
                            () -> {
                                try {
                                    File file;
                                    if (local && new File(attachment.localPath).isFile())
                                        file =
                                                preview
                                                        ? new File(attachment.localPath)
                                                        : attachmentStore.shareable(
                                                                new File(attachment.localPath),
                                                                attachment,
                                                                demo);
                                    else {
                                        file = attachmentStore.cached(attachment, cacheScope);
                                        if (file == null)
                                            file =
                                                    attachmentStore.cache(
                                                            attachment,
                                                            client.attachment(attachment),
                                                            cacheScope);
                                        if (demo && !preview)
                                            file =
                                                    attachmentStore.shareable(
                                                            file, attachment, true);
                                    }
                                    File ready = file;
                                    state.execute(
                                            () -> {
                                                if (demo != trying
                                                        || !identity.equals(repo.roomIdentity())) {
                                                    if (demo) {
                                                        ready.delete();
                                                        if (ready.getParentFile() != null)
                                                            ready.getParentFile().delete();
                                                    }
                                                    main.post(result::failed);
                                                } else
                                                    main.post(
                                                            () ->
                                                                    result.ready(
                                                                            ready,
                                                                            attachment.mime));
                                            });
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                if (!preview) notice("附件读取失败，请稍后重试");
                                            });
                                    main.post(result::failed);
                                }
                            });
                });
    }

    private void restoreDraft() {
        draftGeneration++;
        draft = repo.metadata("draft");
        quote = repo.metadata("quote");
        chips.clear();
        try {
            JSONArray a = new JSONArray(repo.metadata("chips"));
            for (int i = 0; i < a.length(); i++) {
                Attachment value = Attachment.from(a.optJSONObject(i));
                if (value != null && new File(value.localPath).isFile())
                    chips.add(value.state == Attachment.UPLOADING ? value.failed() : value);
            }
        } catch (org.json.JSONException ignored) {
        }
    }

    private void saveDraft() {
        repo.draft(draft, quote, encodeChips(chips));
    }

    private static String encodeChips(List<Attachment> values) {
        JSONArray a = new JSONArray();
        try {
            for (Attachment chip : values) a.put(chip.json(true));
        } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
        }
        return a.toString();
    }

    public void clearNotice() {
        state.execute(
                () -> {
                    lastError = "";
                    publish();
                });
    }

    private void notice(String message) {
        log.event("notice", message);
        lastError = message;
        publish();
    }

    private void reload() {
        cachedRows = repo.rows(visibleLimit);
        cachedRows.addAll(liveNotes);
        cachedRows.sort(java.util.Comparator.comparingLong(row -> row.at));
        pending = repo.pending();
        revision++;
    }

    private void roomNote(String text) {
        liveNotes.add(
                new Message(
                        "note:" + UUID.randomUUID(),
                        Message.HEARD,
                        System.currentTimeMillis(),
                        text,
                        "",
                        0,
                        Message.DELIVERED,
                        "",
                        true,
                        false,
                        Collections.emptyList(),
                        -1));
        while (liveNotes.size() > 100) liveNotes.remove(0);
        reload();
    }

    private void resetRoomTransient() {
        setOutputMuted(false);
        liveNotes.clear();
        lastHeard = oldestDate = "";
        olderLoading = false;
        olderLabel = "查看更早的记录";
        visibleLimit = 200;
        roomState = null;
        liveCerebellumOk = null;
    }

    private String presence() {
        if (trying) return "体验中 · 预设回答";
        if (holdRequested) return holdLive ? "在听你说" : "准备麦克风…";
        if (ambientWanted) {
            switch (ambient) {
                case SPEAKING:
                    return "环境模式 · 正在说";
                case LISTENING:
                    return ambientMuted ? "环境模式 · 在听 · 静音" : "环境模式 · 在听";
                case SEAT_TAKEN:
                    return "另一台设备在听";
                case DEAF:
                    return "环境模式 · 听不到了";
                case PAUSED:
                    return "环境模式 · 已暂停";
                case OFFLINE:
                    return "环境模式 · 连不上";
                case FAILED:
                    return "环境模式 · 收音失败";
                default:
                    return "环境模式 · 正在打开";
            }
        }
        if (connection == Connection.NEEDS_PAIRING) return "需要重新配对";
        if (!settings.isComplete()) return "尚未连接房间";
        if (connection == Connection.OFFLINE) return "连不上 · 正在重试";
        if (connection == Connection.CONNECTING) return "连接中…";
        if (turn.working() != null) return turn.working().label();
        return roomState != null && !roomState.daemonOk ? "暂时无法回复" : "在线";
    }

    private void publish() {
        snapshot = new Snapshot(this);
        if (!publishing.compareAndSet(false, true)) return;
        main.postDelayed(
                () -> {
                    publishing.set(false);
                    for (Listener listener : listeners) listener.changed();
                },
                30);
    }
}
