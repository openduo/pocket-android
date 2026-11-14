// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import dev.openduo.pocket.app.PocketSessionService;
import dev.openduo.pocket.audio.CaptureSession;
import dev.openduo.pocket.audio.OpusDecoder;
import dev.openduo.pocket.audio.SpeechPlayer;
import dev.openduo.pocket.net.ChannelClient;
import dev.openduo.pocket.net.ChannelConnection;
import dev.openduo.pocket.net.GoTransport;
import dev.openduo.pocket.net.RoomAccess;
import dev.openduo.pocket.passport.PassportPolicy;
import dev.openduo.pocket.trymode.TryChannel;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final String UPLOAD_BACKGROUND_PAUSED = "后台发送受系统限制，回到 多多 后会继续";

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
        public final UpgradeNotice upgrade;
        public final Ambient ambient;
        public final List<Message> rows;
        public final List<Outbox.Item> outbox;
        public final List<Attachment> chips;
        public final List<ImportProgress> imports;
        public final List<String> configIssues;
        public final Map<String, TurnState.Trace> traces;
        public final TurnState.Working working;
        public final SessionViewer.Snapshot sessions;
        public final SessionViewer.Access sessionsAccess;
        public final dev.openduo.pocket.passport.PassportCoordinator.Snapshot passport;
        public final String status,
                roomName,
                lastError,
                draft,
                quote,
                tailnetState,
                authUrl,
                olderLabel,
                readingId,
                sight,
                sightRefusal,
                arrivalStatus;
        public final String ambientError;
        public final String importTarget;
        public final String lastHeard;
        public final String audioRoute;
        public final boolean cameraOffered,
                cameraStatusVisible,
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
                ambientLinkPaused,
                accessoryActive,
                pressMuted,
                echoCancellation;
        public final int latencyMs;
        public final long cursor, holdDurationMs, revision, draftGeneration, draftEditAck;

        Snapshot(PocketController c) {
            connection = c.connection;
            upgrade = c.upgrade;
            ambient = c.ambient;
            ambientError = c.ambientFailure;
            ambientLinkPaused = c.ambientLinkPaused;
            arrivalStatus = c.arrivals.status();
            sessions = c.viewer.snapshot();
            sessionsAccess = c.sessionsAccess();
            passport = c.passport.snapshot();
            rows = Collections.unmodifiableList(new ArrayList<>(c.cachedRows));
            outbox = Collections.unmodifiableList(new ArrayList<>(c.pending));
            chips = Collections.unmodifiableList(new ArrayList<>(c.chips));
            imports = c.imports.progress();
            configIssues = c.roomState == null ? Collections.emptyList() : c.roomState.configIssues;
            traces = c.turn.traces(rows);
            working = c.turn.working();
            status = c.presence();
            dev.openduo.pocket.sight.SightSession.Snapshot camera = c.sightSession.snapshot();
            sight = camera.status;
            sightRefusal = camera.refusal;
            lastHeard = c.lastHeard;
            audioRoute = (c.capture == null ? "" : c.capture.route() + " → ") + c.outputRoute;
            cameraOffered = camera.offered;
            cameraStatusVisible = camera.statusVisible;
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
            olderLoading = c.olderRequestEpoch == c.connectionEpoch;
            uploadingVoice = c.uploadForeground;
            ambientActive = c.ambientWanted;
            accessoryActive =
                    PassportPolicy.ENABLED && !c.trying && c.passport.snapshot().link.remembered;
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
    private final dev.openduo.pocket.location.ArrivalMonitor arrivals;
    private final dev.openduo.pocket.diagnostics.AppLog log;
    private final dev.openduo.pocket.app.AnswerNotifications notifications;

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
    private final dev.openduo.pocket.net.NetworkPath networkPath;
    private ChannelConnection channel;
    private RoomAccess roomAccess;
    private TryChannel tryChannel;
    private ChannelConnection readChannel;
    private final dev.openduo.pocket.audio.AudioFocus audioFocus;
    private final dev.openduo.pocket.audio.AudioDevices audioDevices;
    private String outputRoute = "系统音频输出";
    private boolean audioSuspended;
    private final ScheduledExecutorService state =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "DuoDuo Pocket state"));
    private final ExecutorService network =
            Executors.newFixedThreadPool(3, r -> new Thread(r, "DuoDuo Pocket network"));
    private final java.util.concurrent.ThreadPoolExecutor sendNetwork =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(1),
                    r -> new Thread(r, "DuoDuo Pocket send"));
    private final ExecutorService liveConnector =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(1),
                    r -> new Thread(r, "DuoDuo Pocket live connector"),
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private final java.util.concurrent.ThreadPoolExecutor socketWriter =
            new java.util.concurrent.ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(128),
                    r -> new Thread(r, "DuoDuo Pocket socket writer"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final TurnState turn = new TurnState();
    private final SessionViewer viewer = new SessionViewer();
    private final dev.openduo.pocket.passport.PassportCoordinator passport;
    private final List<Attachment> chips = new ArrayList<>();
    private List<Message> cachedRows = new ArrayList<>();
    private final List<Message> liveNotes = new ArrayList<>();
    private List<Outbox.Item> pending = new ArrayList<>();
    private volatile Snapshot snapshot;
    private Connection connection = Connection.NEEDS_SETUP;
    private UpgradeNotice upgrade = UpgradeNotice.NONE;
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
    private final RedialBackoff redial = new RedialBackoff();
    private long retryNotBefore;
    private int latencyMs, visibleLimit = 200;
    private boolean ambientLinkPaused;
    private String ambientFailure = "";
    private volatile int connectionEpoch;
    private int roomStateRequestEpoch = -1;
    private volatile int closingEpoch = -1, closingSourceEpoch = -1;
    private volatile long sendEpoch;
    private volatile long tailnetActionEpoch;
    private boolean tailnetLogoutRequested;
    private boolean restoreOutboxBeforeConnect;
    private long revision, holdPackets, lastHoldPublish;
    private long draftGeneration, draftEditAck;
    private final java.util.concurrent.atomic.AtomicLong draftEdits =
            new java.util.concurrent.atomic.AtomicLong();
    private boolean foreground, trying, opening, ambientWanted, ambientMuted;
    private SendOperation sending;
    private boolean holdRequested, holdLive, captureStopping;
    private int olderRequestEpoch = -1;
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
    private final dev.openduo.pocket.sight.SightSession sightSession;
    private boolean legacyMedia;
    private String lastHeard = "";
    private volatile long readRequest;
    private long nextReadRequest;
    private boolean readInFlight;
    private boolean uploadForeground, serviceRunning, voiceUploadDeferred;
    private long pairingEpoch;
    private boolean pairingActive;
    private final AttachmentStore attachmentStore;
    private final VoiceInbox voiceInbox, tryVoiceInbox;
    private VoiceInbox.Note heldVoice;
    private VoiceInbox heldInbox;
    private final Map<String, VoiceAdmission> voiceAdmissions = new java.util.LinkedHashMap<>();
    private final ImportCoordinator imports;
    private final dev.openduo.pocket.sight.DeviceHeat deviceHeat;
    private int heatLevel;

    private static final class VoiceAdmission {
        final VoiceInbox inbox;
        final VoiceInbox.Note note;

        VoiceAdmission(VoiceInbox inbox, VoiceInbox.Note note) {
            this.inbox = inbox;
            this.note = note;
        }
    }

    /** Mutable fields belong to the state executor; each HTTP attempt captures immutable inputs. */
    private static final class SendOperation {
        final Outbox.Item item;
        final ChannelClient client;
        final String identity;
        final long ticket;
        final dev.openduo.pocket.net.RequestScope requests;
        final VoiceRetry retry = new VoiceRetry();
        byte[] body;
        ChannelClient.Failure error;

        SendOperation(
                Outbox.Item item,
                ChannelClient client,
                String identity,
                long ticket,
                dev.openduo.pocket.net.RequestScope requests) {
            this.item = item;
            this.client = client;
            this.identity = identity;
            this.ticket = ticket;
            this.requests = requests;
        }
    }

    private PocketController(Context context) {
        this.context = context;
        settings = new Settings(context);
        attachmentStore = new AttachmentStore(context);
        voiceInbox = new VoiceInbox(new File(context.getFilesDir(), "voice"));
        tryVoiceInbox = new VoiceInbox(new File(context.getCacheDir(), "try-voice"));
        arrivals = new dev.openduo.pocket.location.ArrivalMonitor(context);
        arrivals.refresh();
        log = dev.openduo.pocket.diagnostics.AppLog.get(context);
        log.event("launch", "api=" + android.os.Build.VERSION.SDK_INT);
        notifications = new dev.openduo.pocket.app.AnswerNotifications(context, settings);
        dev.openduo.pocket.app.BackgroundSync.schedule(context);
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
        networkPath =
                new dev.openduo.pocket.net.NetworkPath(
                        context, () -> state.execute(this::networkPathChanged));
        sightSession =
                new dev.openduo.pocket.sight.SightSession(
                        context,
                        state,
                        Executors.newSingleThreadExecutor(
                                r -> new Thread(r, "DuoDuo Pocket camera close")),
                        () -> channel.isOpen(),
                        () -> socketWriter.getQueue().size() > 8,
                        new dev.openduo.pocket.sight.SightSession.Listener() {
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
                new dev.openduo.pocket.audio.AudioFocus(
                        context,
                        state,
                        focus -> {
                            log.event("audio", "focus=" + focus);
                            if (focus <= 0) {
                                JSONObject restoreMic = ambientInput.releaseMicrophone();
                                audioSuspended = true;
                                releaseOnState(false, true);
                                stopCapture();
                                stopPlayback();
                                if (ambientWanted) {
                                    ambient = Ambient.PAUSED;
                                }
                                reconnect(restoreMic);
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
                new dev.openduo.pocket.audio.AudioDevices(
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
                new dev.openduo.pocket.passport.PassportCoordinator(
                        context,
                        state,
                        new dev.openduo.pocket.passport.PassportCoordinator.Listener() {
                            public void changed() {
                                refreshService();
                                publish();
                            }

                            public void press(
                                    dev.openduo.pocket.passport.PressAssembler.Press press) {
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
                    if (PassportPolicy.ENABLED) passport.tick();
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
                    recoverVoiceNotes();
                    reload();
                    publish();
                    if (settings.isComplete()) startNetwork();
                });
        deviceHeat =
                new dev.openduo.pocket.sight.DeviceHeat(
                        context,
                        level ->
                                state.execute(
                                        () -> {
                                            heatLevel = level;
                                            log.event("thermal", String.valueOf(level));
                                            updateSight();
                                            publish();
                                        }));
        networkPath.start();
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

    private boolean accessoryWaiting() {
        return PassportPolicy.ENABLED && !trying && passport.snapshot().waiting;
    }

    public void start() {
        state.execute(
                () -> {
                    resetRedial("start");
                    if (!trying) {
                        transport.rebind();
                        startNetwork();
                    }
                });
    }

    /** Re-read compatibility from a fresh live ready without changing saved pairing or outbox. */
    public void checkCompatibility() {
        state.execute(
                () -> {
                    if (!accessAllowed()) return;
                    resetRedial("compatibility_recheck");
                    reconnect(ambientInput.releaseMicrophone());
                    publish();
                });
    }

    /** Rechecks a held live socket after Android reports a default-network/path change. */
    private void networkPathChanged() {
        if (!foreground && !ambientWanted && pending.isEmpty() && !accessoryWaiting()) return;
        resetRedial("network_path");
        channel.rebind();
        verifyLiveOnState();
    }

    /** Mirrors the upstream foreground/keepalive check without blocking the state executor. */
    private void verifyLiveOnState() {
        if (opening) return;
        if (!channel.isOpen()) {
            connect();
            return;
        }
        final int epoch = connectionEpoch;
        final ChannelConnection target = channel;
        liveConnector.execute(
                () -> {
                    if (epoch != connectionEpoch || target != channel) return;
                    try {
                        target.ping(3000);
                        state.execute(
                                () -> {
                                    if (epoch == connectionEpoch && target == channel)
                                        refreshRoomState(
                                                epoch, target, client(), true, new RedialBackoff());
                                });
                    } catch (Exception failure) {
                        state.execute(
                                () -> {
                                    if (epoch == connectionEpoch && target == channel)
                                        linkClosed(
                                                "Network path changed; live connection is stale",
                                                epoch);
                                });
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
                    if (value) voiceUploadDeferred = false;
                    if (value) {
                        recoverVoiceNotes();
                        reload();
                    }
                    notifications.foreground(value);
                    log.event("foreground", String.valueOf(value));
                    arrivals.refresh();
                    if (PassportPolicy.ENABLED) passport.foreground(value);
                    surface();
                    if (!value && !ambientWanted) stopPlayback();
                    else if (value && audioSuspended && !audioFocus.interrupted()) {
                        audioSuspended = false;
                        if (ambientWanted) ambient = Ambient.STARTING;
                    }
                    if (sessionsAccess().canView && viewer.snapshot().watching && channel.isOpen())
                        send(value ? viewer.watchFrame() : Json.object("type", "sessions_unwatch"));
                    if (!value) viewer.dropPending();
                    if (!value) {
                        updateSight();
                        releaseOnState(true);
                        if (trying || !settings.keepListening()) ambientOff();
                        if (!ambientWanted
                                && pending.isEmpty()
                                && !accessoryWaiting()
                                && closingEpoch != connectionEpoch) {
                            stopPlayback();
                            channel.closeLive();
                            connectionEpoch++;
                            opening = false;
                            connection = Connection.OFFLINE;
                        }
                    } else if (trying || settings.isComplete()) {
                        resetRedial("foreground");
                        channel.rebind();
                        if (trying) connect();
                        else startNetwork();
                        verifyLiveOnState();
                        resumeAmbientLink();
                        if (ambientWanted && capture == null) startCapture();
                        updateSight();
                    }
                    if (value && connection == Connection.ONLINE) pump();
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
                    if (PassportPolicy.ENABLED && !trying) passport.languageChanged();
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
                    tailnetLogoutRequested = false;
                    tailnetActionEpoch++;
                    resetRedial("tailnet_login");
                    startNetwork(true);
                });
    }

    private void startNetwork(boolean interactive) {
        if (trying) return;
        long action = tailnetActionEpoch;
        network.execute(
                () -> {
                    try {
                        transport.start(
                                settings.networkLog(),
                                status -> state.execute(() -> networkStatus(status)));
                        if (interactive)
                            synchronized (transport) {
                                if (action == tailnetActionEpoch) transport.login();
                            }
                    } catch (Exception failure) {
                        state.execute(
                                () -> {
                                    if (interactive && action != tailnetActionEpoch) return;
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
        if (tailnetLogoutRequested && tailnetState.equals("Running")) tailnetState = "NeedsLogin";
        if (!previousState.equals(tailnetState)) log.event("network", tailnetState);
        authUrl = tailnetLogoutRequested ? "" : status.optString("auth_url");
        if (!trying && tailnetNeedsLogin() && !settings.unauthorized()) {
            if (connection == Connection.ONLINE
                    || connection == Connection.CONNECTING
                    || sending != null
                    || ambientWanted
                    || holdRequested
                    || !readingId.isEmpty()
                    || roomAccess != null)
                pauseRoomAccess(
                        settings.isComplete() ? Connection.OFFLINE : Connection.NEEDS_SETUP);
            lastError = "请完成 Tailscale 授权";
        }
        if (!trying
                && !tailnetLogoutRequested
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
                    if (trying) stopTryingOnState(false);
                    tailnetLogoutRequested = true;
                    long action = ++tailnetActionEpoch;
                    tailnetState = "NeedsLogin";
                    authUrl = "";
                    pauseRoomAccess(
                            settings.unauthorized()
                                    ? Connection.NEEDS_PAIRING
                                    : settings.isComplete()
                                            ? Connection.OFFLINE
                                            : Connection.NEEDS_SETUP);
                    lastError = settings.unauthorized() ? "设备凭据已失效，请重新配对" : "请完成 Tailscale 授权";
                    publish();
                    network.execute(
                            () -> {
                                try {
                                    synchronized (transport) {
                                        if (action == tailnetActionEpoch) transport.logout();
                                    }
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                if (action == tailnetActionEpoch) notice("退出网络失败");
                                            });
                                }
                            });
                });
    }

    public void networkLogging(boolean value) {
        settings.networkLog(value);
        network.execute(() -> transport.logging(value));
    }

    private ChannelClient client() {
        return access().client();
    }

    /** The live socket may redial many times under one unchanged credential generation. */
    private RoomAccess access() {
        Endpoint endpoint = trying ? TryChannel.ENDPOINT : settings.endpoint();
        String token = trying ? TryChannel.TOKEN : settings.token();
        String room = currentRoom(), identity = repo.roomIdentity();
        if (roomAccess != null && roomAccess.matches(channel, endpoint, token, room, identity))
            return roomAccess;
        retireRoomAccess();
        RoomAccess next =
                new RoomAccess(
                        channel,
                        endpoint,
                        token,
                        room,
                        identity,
                        trying
                                ? null
                                : rejected ->
                                        state.execute(
                                                () -> {
                                                    if (roomAccess == rejected
                                                            && rejected.rejected()) unauthorized();
                                                }));
        if (accessAllowed()) roomAccess = next;
        else next.close();
        return next;
    }

    private boolean accessAllowed() {
        return trying
                || settings.isComplete()
                        && !settings.unauthorized()
                        && !tailnetNeedsLogin()
                        && !pairingActive;
    }

    private boolean currentAccess(RoomAccess access) {
        return roomAccess == access && access.current();
    }

    private void retireRoomAccess() {
        RoomAccess retired = roomAccess;
        roomAccess = null;
        if (retired == null) return;
        retired.close();
        boolean changed = false;
        for (int i = 0; i < chips.size(); i++)
            if (chips.get(i).state == Attachment.UPLOADING) {
                chips.set(i, chips.get(i).failed());
                changed = true;
            }
        if (changed)
            try {
                saveDraft();
            } catch (RuntimeException storageFailure) {
                log.event("attachment", "pause_state_save_deferred");
            }
    }

    private boolean tailnetNeedsLogin() {
        return tailnetLogoutRequested || tailnetState.startsWith("Needs");
    }

    private void connect() {
        if (opening
                || android.os.SystemClock.elapsedRealtime() < retryNotBefore
                || channel.isOpen()
                || pairingActive
                || !trying
                        && (!settings.isComplete()
                                || settings.unauthorized()
                                || tailnetNeedsLogin())
                || (!foreground && !ambientWanted && pending.isEmpty() && !accessoryWaiting()))
            return;
        if (restoreOutboxBeforeConnect) {
            try {
                repo.select(repo.roomIdentity());
                reload();
                restoreOutboxBeforeConnect = false;
            } catch (RuntimeException storageFailure) {
                notice("无法保存待发送消息");
                return;
            }
        }
        opening = true;
        ambientInput.socketOpened();
        sightSession.connectionReset();
        if (PassportPolicy.ENABLED && !trying) passport.beginReplay();
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
        liveCerebellumOk = null;
        log.event("live_dial", "generation=" + epoch);
        publish();
        liveConnector.execute(
                () -> {
                    if (epoch != connectionEpoch || target != channel) return;
                    try {
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
                                // Keep only the retiring transport valid for its final control
                                // write. Its listener still uses the original epoch above.
                                () ->
                                        epoch == connectionEpoch
                                                || closingEpoch == connectionEpoch
                                                        && epoch == closingSourceEpoch);
                        state.execute(
                                () -> {
                                    if (epoch == connectionEpoch) {
                                        opening = false;
                                        if (!demo) arrivals.settle(place);
                                        log.event("live", "opened");
                                        refreshRoomState(
                                                epoch, target, client, resume, new RedialBackoff());
                                        refreshLatency(epoch, target, client);
                                        surface();
                                    }
                                });
                    } catch (Exception failure) {
                        state.execute(
                                () -> {
                                    if (epoch != connectionEpoch) return;
                                    opening = false;
                                    if (!demo
                                            && (failure instanceof ChannelClient.Failure
                                                            && ((ChannelClient.Failure) failure)
                                                                    .unauthorized()
                                                    || String.valueOf(failure.getMessage())
                                                            .startsWith(
                                                                    "tsbridge: live unauthorized")))
                                        unauthorized();
                                    else linkClosed("暂时无法连接房间", epoch);
                                });
                    }
                });
    }

    /** Supplemental HTTP cannot hold the live dial or retire a working socket on a timeout. */
    private void refreshRoomState(
            int epoch,
            ChannelConnection target,
            ChannelClient client,
            boolean resumed,
            RedialBackoff retry) {
        if (roomStateRequestEpoch == epoch) return;
        roomStateRequestEpoch = epoch;
        network.execute(
                () -> {
                    if (epoch != connectionEpoch || target != channel) return;
                    try {
                        ChannelClient.State room = client.state();
                        state.execute(
                                () -> {
                                    if (epoch != connectionEpoch || target != channel) return;
                                    roomStateRequestEpoch = -1;
                                    roomState = room;
                                    if (!resumed || !repo.metadata("history_loaded").equals("1"))
                                        historyToday();
                                    publish();
                                });
                    } catch (ChannelClient.Failure failure) {
                        state.execute(
                                () -> {
                                    if (epoch != connectionEpoch || target != channel) return;
                                    roomStateRequestEpoch = -1;
                                    if (failure.unauthorized() && !trying) {
                                        unauthorized();
                                        return;
                                    }
                                    long delay = retry.failedMs();
                                    log.event("room_state", "retry_ms=" + delay);
                                    state.schedule(
                                            () -> {
                                                if (epoch == connectionEpoch
                                                        && target == channel
                                                        && target.isOpen())
                                                    refreshRoomState(
                                                            epoch, target, client, resumed, retry);
                                            },
                                            delay,
                                            TimeUnit.MILLISECONDS);
                                });
                    }
                });
    }

    private void refreshLatency(int epoch, ChannelConnection target, ChannelClient client) {
        network.execute(
                () -> {
                    if (epoch != connectionEpoch || target != channel) return;
                    try {
                        int latency = client.health();
                        state.execute(
                                () -> {
                                    if (epoch != connectionEpoch || target != channel) return;
                                    latencyMs = latency;
                                    publish();
                                });
                    } catch (ChannelClient.Failure failure) {
                        log.event("health", "unavailable");
                    }
                });
    }

    private void reconnect() {
        reconnect(null);
    }

    private void reconnect(JSONObject closingFrame) {
        sightSession.connectionReset();
        int sourceEpoch = connectionEpoch;
        int epoch = ++connectionEpoch;
        closingEpoch = closingSourceEpoch = -1;
        opening = false;
        ambientInput.socketOpened();
        socketWriter.getQueue().clear();
        stopPlayback();
        ChannelConnection target = channel;
        if (closingFrame == null) {
            target.closeLive();
            connect();
            return;
        }
        // Room controls outlive their socket. Give the final unmute one bounded writer turn
        // before closing; never wait for a network write on the state owner.
        closingEpoch = epoch;
        closingSourceEpoch = sourceEpoch;
        opening = true;
        try {
            socketWriter.execute(
                    () -> {
                        if (epoch != connectionEpoch) return;
                        try {
                            target.send(closingFrame);
                        } catch (Exception failure) {
                            log.event("live", "release_failed");
                        } finally {
                            state.execute(() -> finishReconnect(epoch, target));
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            finishReconnect(epoch, target);
        }
    }

    private void finishReconnect(int epoch, ChannelConnection target) {
        if (epoch != connectionEpoch) return;
        closingEpoch = closingSourceEpoch = -1;
        opening = false;
        target.closeLive();
        connect();
    }

    private void linkClosed(String reason, int epoch) {
        if (epoch != connectionEpoch) return;
        if (!trying && reason.contains("4401")) {
            unauthorized();
            return;
        }
        // A failed write or a full writer queue need not produce a reader-close callback.
        // Retire the whole generation before redialling, even if the bridge still holds it.
        int retryEpoch = ++connectionEpoch;
        socketWriter.getQueue().clear();
        channel.closeLive();
        sightSession.connectionReset();
        if (PassportPolicy.ENABLED) passport.online(false);
        viewer.dropPending();
        connection = Connection.OFFLINE;
        opening = false;
        ambientInput.socketOpened();
        if (readingId.isEmpty()) stopPlayback();
        if (ambientWanted && (reason.contains("PolicyViolation") || reason.contains("1008")))
            ambientFailure = "频道拒绝了这个房间";
        long delay = redial.failedMs();
        retryNotBefore = android.os.SystemClock.elapsedRealtime() + delay;
        log.event("live_retry", "generation=" + retryEpoch + " delay_ms=" + delay);
        if (ambientWanted && redial.sustained() && ambientFailure.isEmpty() && !audioSuspended) {
            ambientLinkPaused = true;
            ambientInput.captureStopped();
            // A local held note has its own lifetime and may still be cancelled or sent offline.
            if (!holdRequested) stopCapture();
        }
        lastError = ambientFailure.isEmpty() ? "连不上房间，正在重试" : ambientFailure;
        publish();
        state.schedule(
                () -> {
                    if (retryEpoch == connectionEpoch) connect();
                },
                delay,
                TimeUnit.MILLISECONDS);
    }

    private void resetRedial(String reason) {
        redial.reset();
        retryNotBefore = 0;
        log.event("live_redial_reset", reason);
    }

    private void unauthorized() {
        if (trying) return;
        settings.unauthorized(true);
        pauseRoomAccess(Connection.NEEDS_PAIRING);
        lastError = "设备凭据已失效，请重新配对";
        publish();
    }

    private void pauseRoomAccess(Connection next) {
        retireRoomAccess();
        connection = next;
        opening = false;
        connectionEpoch++;
        closingEpoch = closingSourceEpoch = -1;
        socketWriter.getQueue().clear();
        channel.closeLive();
        sightSession.connectionReset();
        viewer.dropPending();
        Outbox.Item interrupted = sending == null ? null : repo.findPending(sending.item.id);
        restoreOutboxBeforeConnect = true;
        finishSend();
        releaseOnState(true);
        ambientOff();
        stopPlayback();
        if (interrupted != null && interrupted.state == Message.SENDING)
            try {
                repo.transition(interrupted.state(Message.QUEUED, ""));
            } catch (RuntimeException storageFailure) {
                log.event("send", "pause_state_save_deferred");
            }
        reload();
    }

    private void frame(String text, boolean resumed) {
        try {
            JSONObject frame = new JSONObject(text);
            String type = frame.optString("type");
            boolean accessoryAlert =
                    PassportPolicy.ENABLED
                            && passport.snapshot().link.ready
                            && passport.snapshot().waiting;
            if (!trying) {
                notifications.frame(frame, accessoryAlert);
                if (PassportPolicy.ENABLED) passport.frame(frame);
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
                admitRows(
                        message == null
                                ? Collections.<Message>emptyList()
                                : Collections.singletonList(message),
                        frame.optLong("seq"));
            } else if (type.equals("ready")) {
                connection = Connection.ONLINE;
                if (PassportPolicy.ENABLED && !trying) passport.online(true);
                opening = false;
                resetRedial("ready");
                upgrade = upgrade.afterReady(frame);
                lastError = ambientFailure;
                repo.admit(Collections.<Message>emptyList(), frame.optLong("seq"));
                turn.restore(frame.optJSONObject("turn"));
                if (turn.working() != null && !turn.working().messages.isEmpty())
                    for (Message answer : repo.recentAnswers()) turn.settled(answer);
                if (resumed) reconcileReady(frame);
                viewer.dropPending();
                if (foreground && sessionsAccess().canView && viewer.snapshot().watching)
                    send(viewer.watchFrame());
                resumeAmbientLink();
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
                repo.provisional(provisional);
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
        RoomAccess access = access();
        ChannelClient client = access.client();
        network.execute(
                () -> {
                    try {
                        ChannelClient.Rows rows = client.history(day);
                        state.execute(
                                () -> {
                                    if (epoch != connectionEpoch || !currentAccess(access)) return;
                                    admitRows(rows.messages, repo.cursor());
                                    repo.metadata("history_loaded", "1");
                                    oldestDate =
                                            cachedRows.isEmpty()
                                                    ? day
                                                    : Json.day(cachedRows.get(0).at);
                                    publish();
                                });
                    } catch (ChannelClient.Failure failure) {
                        state.execute(
                                () -> {
                                    if (epoch == connectionEpoch && currentAccess(access))
                                        notice("暂时无法加载历史，可稍后重试");
                                });
                    }
                });
    }

    public void loadOlder() {
        state.execute(
                () -> {
                    if (olderRequestEpoch == connectionEpoch) return;
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
                    olderRequestEpoch = connectionEpoch;
                    publish();
                    int epoch = connectionEpoch;
                    String before = oldestDate;
                    if (before.isEmpty())
                        before =
                                roomState == null || roomState.date.isEmpty()
                                        ? Json.day(System.currentTimeMillis())
                                        : roomState.date;
                    final String start = before;
                    RoomAccess access = access();
                    ChannelClient client = access.client();
                    network.execute(
                            () -> {
                                List<Message> found = new ArrayList<>();
                                String scanned = start;
                                try {
                                    for (int i = 0; i < 14 && found.isEmpty(); i++) {
                                        if (epoch != connectionEpoch) return;
                                        scanned =
                                                java.time.LocalDate.parse(scanned)
                                                        .minusDays(1)
                                                        .toString();
                                        found.addAll(client.history(scanned).messages);
                                    }
                                    final String oldest = scanned;
                                    state.execute(
                                            () -> {
                                                if (epoch != connectionEpoch
                                                        || !currentAccess(access)) return;
                                                oldestDate = oldest;
                                                olderRequestEpoch = -1;
                                                visibleLimit += Math.max(100, found.size());
                                                admitRows(found, repo.cursor());
                                                olderLabel =
                                                        found.isEmpty()
                                                                ? oldest + " 之前暂未找到 · 继续找"
                                                                : "查看更早的记录";
                                                publish();
                                            });
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                if (epoch != connectionEpoch
                                                        || !currentAccess(access)) return;
                                                olderRequestEpoch = -1;
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
                    VoiceAdmission retained = voiceAdmissions.get(id);
                    if (retained != null) {
                        if (!retained.note.room.equals(repo.roomIdentity())) return;
                        if (!admitVoice(retained)) {
                            reload();
                            publish();
                            return;
                        }
                    }
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
                        if (item.state != Message.FAILED && retained == null) return;
                        if (item.state == Message.FAILED)
                            repo.transition(item.state(Message.QUEUED, ""));
                    }
                    reload();
                    publish();
                    if (connection == Connection.ONLINE) pump();
                    else {
                        resetRedial("message_retry");
                        connect();
                    }
                });
    }

    public void discard(String id) {
        state.execute(
                () -> {
                    VoiceAdmission retained = voiceAdmissions.get(id);
                    if (retained != null) {
                        if (!retained.note.room.equals(repo.roomIdentity())) return;
                        try {
                            retained.inbox.discard(retained.note);
                            voiceAdmissions.remove(id);
                            if (lastError.equals(VoiceInbox.UNSAVED)) lastError = "";
                        } catch (java.io.IOException failure) {
                            notice("录音未能删除，请稍后重试");
                            return;
                        }
                    }
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
                || !trying && (settings.unauthorized() || tailnetNeedsLogin())
                || upgrade != UpgradeNotice.NONE) return;
        Outbox.Item next = null;
        for (Outbox.Item item : repo.pending())
            if (item.state == Message.QUEUED) {
                next = item;
                break;
            }
        if (next == null) return;
        if (!trying && next.voice && voiceUploadDeferred && !foreground) return;
        if (next.display.isEmpty())
            next =
                    next.surface(
                            foreground ? settings.keepAwake() ? "phone_awake" : "phone" : "later",
                            ambientWanted || foreground && settings.ambientOutput(),
                            trying ? "" : arrivals.pending());
        final Outbox.Item item = next;
        uploadForeground = !trying && item.voice;
        if (uploadForeground) {
            refreshService();
            if (!serviceRunning) {
                uploadForeground = false;
                voiceUploadDeferred = true;
                repo.transition(item.state(Message.QUEUED, UPLOAD_BACKGROUND_PAUSED));
                lastError = UPLOAD_BACKGROUND_PAUSED;
                reload();
                publish();
                return;
            }
            voiceUploadDeferred = false;
            if (lastError.equals(UPLOAD_BACKGROUND_PAUSED)) lastError = "";
        }
        dev.openduo.pocket.net.RequestScope requests;
        RoomAccess access = access();
        try {
            requests = new dev.openduo.pocket.net.RequestScope(access);
        } catch (Exception failure) {
            log.event("send", "request_scope_failed");
            uploadForeground = false;
            refreshService();
            linkClosed("Unable to prepare send", connectionEpoch);
            return;
        }
        SendOperation operation =
                new SendOperation(
                        item, access.client(requests), repo.roomIdentity(), ++sendEpoch, requests);
        sending = operation;
        try {
            repo.transition(item.state(Message.SENDING, ""));
        } catch (RuntimeException storageFailure) {
            retireSend();
            uploadForeground = false;
            refreshService();
            reload();
            notice("无法保存待发送消息");
            return;
        }
        if (lastError.equals("无法保存待发送消息")) lastError = "";
        reload();
        publish();
        ChannelClient.State room = roomState;
        sendWork(
                operation,
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
        if (PassportPolicy.ENABLED) passport.accepted(item.id);
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
        retireSend();
        uploadForeground = false;
        refreshService();
    }

    private void retireSend() {
        SendOperation retired = sending;
        sending = null;
        sendEpoch++;
        sendNetwork.getQueue().clear();
        if (retired != null) retired.requests.close();
    }

    private void sendWork(SendOperation operation, Runnable work) {
        if (!currentSend(operation)) return;
        try {
            sendNetwork.execute(
                    () -> {
                        if (operation.ticket == sendEpoch) work.run();
                    });
        } catch (java.util.concurrent.RejectedExecutionException occupied) {
            // A cancelled request is returning. Retain the current send while its one slot clears.
            state.schedule(() -> sendWork(operation, work), 25, TimeUnit.MILLISECONDS);
        }
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
        sendWork(
                operation,
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
        if (error != null && !error.transport)
            upgrade = upgrade.afterResponse(error.status, error.reason);
        if (error == null) {
            messageAccepted(current);
            log.event("send", "accepted " + (current.voice ? "voice" : "text"));
            repo.transition(current.state(Outbox.ACCEPTED, ""));
        } else if (error.unauthorized() && !trying) {
            repo.transition(current.state(Message.QUEUED, ""));
            unauthorized();
        } else {
            if (PassportPolicy.ENABLED && current.source.equals("passport"))
                passport.uploadFailed(current.id, error.transport);
            repo.transition(
                    current.state(
                            error.transport ? Message.QUEUED : Message.FAILED,
                            sendFailure(current, error)));
        }
        reload();
        if (error != null && error.transport) {
            linkClosed("发送连接中断", connectionEpoch);
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
        if (!accessAllowed()) {
            replaceChip(attachment.failed());
            notice(settings.unauthorized() ? "设备凭据已失效，请重新配对" : "请完成 Tailscale 授权");
            return;
        }
        RoomAccess access = access();
        ChannelClient client = access.client();
        ChannelClient.State room = roomState;
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
                                    if (currentAccess(access)) replaceChip(result);
                                });
                    } catch (ChannelClient.Failure failure) {
                        state.execute(
                                () -> {
                                    if (!currentAccess(access)) return;
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
                        heldInbox = trying ? tryVoiceInbox : voiceInbox;
                        heldVoice = heldInbox.begin(repo.roomIdentity());
                        heldFile = heldVoice.file;
                        heldFileOutput = new FileOutputStream(heldFile);
                        heldOutput = new BufferedOutputStream(heldFileOutput);
                        holdRequested = true;
                        holdLive = false;
                        holdPackets = 0;
                        lastHoldPublish = 0;
                        audioSuspended = false;
                        resetRedial("local_press");
                        if (!channel.isOpen()) connect();
                        for (JSONObject outgoing : ambientInput.press("phone", true))
                            send(outgoing);
                        interruptPlayback();
                        if (capture == null) startCapture();
                        else if (!captureStopping) holdLive = true;
                        publish();
                    } catch (Exception failure) {
                        cancelHeldVoice();
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
        boolean writeFailed = false;
        try {
            if (heldOutput != null && !cancel && holdPackets >= 15) {
                heldOutput.flush();
                heldFileOutput.getFD().sync();
            }
        } catch (Exception failure) {
            writeFailed = true;
            lastError = "语音保存失败，请检查存储空间";
        } finally {
            try {
                if (heldOutput != null) heldOutput.close();
            } catch (Exception failure) {
                writeFailed = true;
                lastError = "语音保存失败，请检查存储空间";
            }
        }
        heldOutput = null;
        heldFileOutput = null;
        if (!ambientWanted || ambientLinkPaused) stopCapture();
        for (JSONObject outgoing : ambientInput.press("phone", false)) send(outgoing);
        if (cancel || holdPackets < 15) {
            cancelHeldVoice();
            if (!cancel) lastError = "按住说话，麦克风就绪后再松开";
        } else {
            try {
                VoiceInbox.Note note =
                        heldInbox.seal(heldVoice, holdPackets, interrupted || writeFailed);
                if (note == null) cancelHeldVoice();
                else {
                    VoiceAdmission admission = new VoiceAdmission(heldInbox, note);
                    voiceAdmissions.put(note.id, admission);
                    admitVoice(admission);
                }
            } catch (java.io.IOException failure) {
                // The recording manifest still owns the bytes; recovery never auto-sends it.
                log.event("voice", "seal_deferred");
                lastError = "语音保存失败，请检查存储空间";
            }
        }
        heldVoice = null;
        heldInbox = null;
        heldFile = null;
        reload();
        publish();
        if (!interrupted && !writeFailed) pump();
    }

    private void cancelHeldVoice() {
        try {
            if (heldOutput != null) heldOutput.close();
        } catch (java.io.IOException ignored) {
        }
        heldOutput = null;
        heldFileOutput = null;
        try {
            if (heldVoice != null) heldInbox.discard(heldVoice);
        } catch (java.io.IOException failure) {
            log.event("voice", "cancel_cleanup_deferred");
            lastError = "录音未能删除，请稍后重试";
        }
        heldVoice = null;
        heldInbox = null;
        heldFile = null;
    }

    private boolean admitVoice(VoiceAdmission admission) {
        if (!repo.roomIdentity().equals(admission.note.room)) return false;
        try {
            admission.inbox.remember(admission.note);
            repo.enqueueVoice(admission.note);
        } catch (java.io.IOException | RuntimeException failure) {
            log.event("voice", "admission_deferred");
            lastError = VoiceInbox.UNSAVED;
            return false;
        }
        voiceAdmissions.remove(admission.note.id);
        try {
            admission.inbox.acknowledge(admission.note.id);
            repo.acknowledgeVoice(admission.note.id);
        } catch (java.io.IOException | RuntimeException failure) {
            log.event("voice", "acknowledgement_deferred");
        }
        if (lastError.equals(VoiceInbox.UNSAVED)) lastError = "";
        return true;
    }

    private void recoverVoiceNotes() {
        recoverVoiceInbox(voiceInbox, realRepo);
        if (trying) recoverVoiceInbox(tryVoiceInbox, repo);
        for (VoiceAdmission admission : new ArrayList<>(voiceAdmissions.values()))
            if (admission.note.room.equals(repo.roomIdentity())) admitVoice(admission);
    }

    private boolean discardVoiceAdmissions() {
        for (VoiceAdmission admission : new ArrayList<>(voiceAdmissions.values()))
            if (admission.note.room.equals(repo.roomIdentity()))
                try {
                    admission.inbox.discard(admission.note);
                    voiceAdmissions.remove(admission.note.id);
                } catch (java.io.IOException failure) {
                    lastError = "录音未能删除，请稍后重试";
                    return false;
                }
        return true;
    }

    private void recoverVoiceInbox(VoiceInbox inbox, RoomRepository repository) {
        try {
            java.util.Set<String> active = new java.util.HashSet<>(voiceAdmissions.keySet());
            if (heldVoice != null) active.add(heldVoice.id);
            java.util.Set<String> receipts = repository.voiceReceipts();
            VoiceInbox.Scan scan = inbox.pending(receipts, active);
            for (VoiceInbox.Note note : scan.notes)
                voiceAdmissions.put(note.id, new VoiceAdmission(inbox, note));
            for (String id : receipts) if (!inbox.awaiting(id)) repository.acknowledgeVoice(id);
            if (scan.unreadable > 0) lastError = "有录音未能恢复，请检查存储空间后重新打开应用";
        } catch (java.io.IOException | RuntimeException failure) {
            log.event("voice", "recovery_deferred");
            lastError = "有录音未能恢复，请检查存储空间后重新打开应用";
        }
    }

    private void startCapture() {
        if (capture != null
                || captureStopping
                || audioSuspended
                || ambientLinkPaused && !holdRequested
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
                                                releaseOnState(false, true);
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
                if (holdPackets % 50 == 0) heldOutput.flush();
                if (holdPackets * 20 - lastHoldPublish >= 100) {
                    lastHoldPublish = holdPackets * 20;
                    publish();
                }
                if (heldFile.length() > 16L * 1024 * 1024) {
                    releaseOnState(true);
                    notice("语音已达到本机大小上限");
                }
            } catch (Exception failure) {
                releaseOnState(false, true);
                notice("语音保存失败，请检查存储空间");
            }
        }
        if (!ambientWanted || ambientLinkPaused || !channel.isOpen()) return;
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
                        ambientLinkPaused = false;
                        ambientFailure = "";
                        resetRedial("ambient_start");
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
        if (!ambientWanted) return;
        JSONObject restoreMic = ambientInput.releaseMicrophone();
        ambientWanted = false;
        ambientLinkPaused = false;
        if (lastError.equals(ambientFailure)) lastError = "";
        ambientFailure = "";
        setOutputMuted(false);
        ambient = Ambient.OFF;
        ambientInput.captureStopped();
        sightSession.clear();
        stopCapture();
        stopPlayback();
        refreshService();
        if (channel.isOpen()) reconnect(restoreMic);
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
                    if (audioSuspended || ambientLinkPaused || !ambientFailure.isEmpty()) {
                        reopenAmbientOnState();
                        return;
                    }
                    audioSuspended = false;
                    for (JSONObject outgoing : ambientInput.takeover(currentRoom())) send(outgoing);
                    if (capture == null) startCapture();
                    ambient = Ambient.STARTING;
                    publish();
                });
    }

    public void reopenAmbient() {
        state.execute(this::reopenAmbientOnState);
    }

    private void reopenAmbientOnState() {
        if (!ambientWanted) return;
        if (!audioFocus.acquire(true)) {
            audioSuspended = true;
            notice("其他应用正在使用音频，请稍后重试");
            return;
        }
        JSONObject restoreMic = ambientInput.releaseMicrophone();
        if (lastError.equals(ambientFailure)) lastError = "";
        ambientFailure = "";
        ambientLinkPaused = audioSuspended = false;
        resetRedial("ambient_reopen");
        stopCapture();
        reconnect(restoreMic);
        publish();
    }

    private void resumeAmbientLink() {
        if (ambientWanted
                && ambientLinkPaused
                && foreground
                && channel.isOpen()
                && connection == Connection.ONLINE
                && !audioSuspended
                && ambientFailure.isEmpty()) ambientLinkPaused = false;
    }

    private void startService() {
        refreshService();
    }

    private void refreshService() {
        boolean bluetooth = PassportPolicy.ENABLED && passport.snapshot().link.remembered;
        boolean needed = !trying && (ambientWanted || uploadForeground || bluetooth);
        if (needed) {
            // The service may receive an older queued notification before this call returns.
            // Publish current intent before Android can create it or it can stop itself as idle.
            publish();
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
                    lastError = "无法在后台收音，请回到 多多 后重新开启";
                }
                log.event("service", "foreground_start_denied");
            }
        } else if (serviceRunning) {
            serviceRunning = false;
            // A requested foreground service must acknowledge its start before stopping itself.
            // The service observes this snapshot after promotion, even if its creation is pending.
            publish();
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
                                            if (player == null || generation != player.generation())
                                                return;
                                            stopPlayback();
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
                    if (!accessAllowed()) {
                        notice(settings.unauthorized() ? "设备凭据已失效，请重新配对" : "请完成 Tailscale 授权");
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
                    RoomAccess access = access();
                    String room = currentRoom();
                    publish();
                    network.execute(
                            () -> {
                                dev.openduo.pocket.audio.PacketStream packets =
                                        new dev.openduo.pocket.audio.PacketStream();
                                final boolean[] received = {false};
                                final Exception[] parseError = {null};
                                try (OpusDecoder read = new OpusDecoder()) {
                                    dev.openduo.pocket.net.ChannelTransport.Response response =
                                            access.stream(
                                                    request,
                                                    "/api/speech?room=" + Endpoint.query(room),
                                                    Json.object("text", message.text)
                                                            .toString()
                                                            .getBytes(StandardCharsets.UTF_8),
                                                    bytes -> {
                                                        if (!access.current()
                                                                || readRequest != request
                                                                || parseError[0] != null) return;
                                                        try {
                                                            for (byte[] packet :
                                                                    packets.push(bytes)) {
                                                                received[0] = true;
                                                                if (!output.enqueueWaiting(
                                                                        speech,
                                                                        read.packet(packet),
                                                                        () ->
                                                                                access.current()
                                                                                        && readRequest
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
                                        throw ChannelClient.failure(response);
                                    state.execute(
                                            () -> {
                                                if (currentAccess(access)
                                                        && readRequest == request) {
                                                    readInFlight = false;
                                                    finishReadIfDrained();
                                                    publish();
                                                }
                                            });
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                if (!currentAccess(access)
                                                        || readRequest != request) return;
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
                                                                                        failure))
                                                                        : "连接不上，无法朗读";
                                                if (!received[0]) stopPlayback();
                                                else finishReadIfDrained();
                                                notice(reason);
                                            });
                                }
                            });
                });
    }

    private static String speechError(ChannelClient.Failure failure) {
        switch (failure.status) {
            case 422:
                if (failure.reason.equals("nothing_to_speak")) return "这条消息没有可朗读的内容";
                break;
            case 413:
                return "这条消息太长，无法朗读";
            case 502:
                return "语音合成失败，稍后再试";
            case 503:
                return "语音服务暂不可用";
            case 401:
                return "请重新配对房间";
        }
        return "朗读失败（" + failure.status + "）";
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
                    retireRoomAccess();
                    retireSend();
                    final long pairTicket = ++pairingEpoch;
                    pairingActive = true;
                    uploadForeground = false;
                    repo.select(repo.roomIdentity());
                    reload();
                    connection = Connection.CONNECTING;
                    channel.closeLive();
                    opening = false;
                    releaseOnState(true);
                    if (PassportPolicy.ENABLED) passport.online(false);
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
                                                    retireSend();
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
                                                    recoverVoiceNotes();
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
                    retireRoomAccess();
                    opening = false;
                    retireSend();
                    uploadForeground = false;
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
                    if (!discardVoiceAdmissions()) {
                        reload();
                        publish();
                        return;
                    }
                    repo.clear();
                    if (PassportPolicy.ENABLED) passport.online(false);
                    settings.clearConnection();
                    resetRoomTransient();
                    dev.openduo.pocket.app.BackgroundSync.schedule(context);
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
                            dev.openduo.pocket.text.AppText.get(context).en);
        } catch (Exception failure) {
            notice("无法准备体验房间，请检查本机存储空间");
            return false;
        }
        pairingEpoch++;
        pairingActive = false;
        releaseOnState(true);
        saveDraft();
        retireRoomAccess();
        retireSend();
        connectionEpoch++;
        opening = uploadForeground = false;
        channel.closeLive();
        socketWriter.getQueue().clear();
        stopCapture();
        stopPlayback();
        ambientWanted = false;
        ambient = Ambient.OFF;
        sightSession.clear();
        if (PassportPolicy.ENABLED) passport.online(false);
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
        retireRoomAccess();
        retireSend();
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
        recoverVoiceNotes();
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
        voiceAdmissions.values().removeIf(admission -> admission.inbox == tryVoiceInbox);
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
                    if (trying || !accessAllowed()) {
                        main.post(completed);
                        return;
                    }
                    RoomAccess access = access();
                    ChannelClient client = access.client();
                    long cursor = repo.cursor();
                    boolean bootstrapped = repo.hasCursor();
                    network.execute(
                            () -> {
                                ChannelClient.Rows rows = null;
                                try {
                                    if (access.current()) {
                                        transport.start(
                                                settings.networkLog(),
                                                status ->
                                                        state.execute(() -> networkStatus(status)));
                                        rows = client.rows(cursor);
                                    }
                                } catch (Exception failure) {
                                    log.event("background", "catchup_failed");
                                }
                                ChannelClient.Rows fetched = rows;
                                state.execute(
                                        () -> {
                                            try {
                                                if (fetched != null && currentAccess(access)) {
                                                    admitRows(fetched.messages, fetched.cursor);
                                                    for (Message row : fetched.messages)
                                                        if (row.kind == Message.DUODUO) {
                                                            notifications.frame(
                                                                    Json.object(
                                                                            "type",
                                                                            "answer_final",
                                                                            "text",
                                                                            row.text,
                                                                            "message_ids",
                                                                            new JSONArray(
                                                                                    row.messageIds)),
                                                                    PassportPolicy.ENABLED
                                                                            && passport.snapshot()
                                                                                    .link
                                                                                    .ready,
                                                                    bootstrapped);
                                                        }
                                                    publish();
                                                }
                                            } finally {
                                                main.post(completed);
                                            }
                                        });
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

    public dev.openduo.pocket.sight.PreviewFrame sightPreview() {
        return sightSession.preview();
    }

    public void sightPreviewVisible(boolean value) {
        sightSession.previewWanted(value);
    }

    private void updateSight() {
        sightSession.update(
                ambientWanted,
                ambientInput.role().equals("master")
                        && !ambientLinkPaused
                        && !audioSuspended
                        && ambientFailure.isEmpty(),
                ambientInput.media(),
                foreground,
                heatLevel);
    }

    public void scanPassport() {
        if (PassportPolicy.ENABLED) state.execute(passport::scan);
    }

    public void resumeAccessory() {
        if (PassportPolicy.ENABLED)
            state.execute(
                    () -> {
                        if (!trying && passport.snapshot().link.remembered) passport.resume();
                    });
    }

    public void connectPassport(String address) {
        if (PassportPolicy.ENABLED) state.execute(() -> passport.connect(address));
    }

    public void forgetPassport() {
        if (PassportPolicy.ENABLED) state.execute(passport::forget);
    }

    private void passportPress(dev.openduo.pocket.passport.PressAssembler.Press press) {
        if (!PassportPolicy.ENABLED) return;
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

    private SessionViewer.Access sessionsAccess() {
        return trying
                ? SessionViewer.Access.NOTIFY
                : SessionViewer.Access.fromScope(settings.scope());
    }

    public void showSessions(boolean shown) {
        state.execute(
                () -> {
                    if (shown && !sessionsAccess().canView) return;
                    JSONObject frame = viewer.show(shown);
                    if (foreground && sessionsAccess().canView) send(frame);
                    publish();
                });
    }

    public void sessionList() {
        state.execute(
                () -> {
                    if (!sessionsAccess().canView) return;
                    JSONObject frame = viewer.list();
                    if (foreground) send(frame);
                    publish();
                });
    }

    public void openSession(String key) {
        state.execute(
                () -> {
                    if (!sessionsAccess().canView) return;
                    JSONObject frame = viewer.open(key);
                    if (foreground) send(frame);
                    publish();
                });
    }

    public void notifySession(String text) {
        state.execute(
                () -> {
                    SessionViewer.Access access = sessionsAccess();
                    if (!access.canNotify) {
                        notice("这台设备没有发通知的权限，需要重新配对");
                        return;
                    }
                    if (connection != Connection.ONLINE) {
                        notice("连接恢复后再发送通知");
                        return;
                    }
                    JSONObject frame = viewer.notify(access, text, UUID.randomUUID().toString());
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
                    boolean demo = trying;
                    String identity = repo.roomIdentity();
                    String cacheScope = demo ? identity.substring(4) : "";
                    RoomAccess access = demo || settings.isComplete() ? access() : null;
                    network.execute(
                            () -> {
                                try {
                                    File file;
                                    boolean downloaded = false;
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
                                        if (file == null) {
                                            if (access == null)
                                                throw new java.io.IOException("Room is not paired");
                                            file =
                                                    attachmentStore.cache(
                                                            attachment,
                                                            access.client().attachment(attachment),
                                                            cacheScope);
                                            downloaded = true;
                                        }
                                        if (demo && !preview)
                                            file =
                                                    attachmentStore.shareable(
                                                            file, attachment, true);
                                    }
                                    File ready = file;
                                    boolean remote = downloaded;
                                    state.execute(
                                            () -> {
                                                if (demo != trying
                                                        || !identity.equals(repo.roomIdentity())
                                                        || remote && !currentAccess(access)) {
                                                    if (demo) {
                                                        ready.delete();
                                                        if (ready.getParentFile() != null)
                                                            ready.getParentFile().delete();
                                                    }
                                                    main.post(result::failed);
                                                } else
                                                    main.post(
                                                            () -> {
                                                                Snapshot current = snapshot;
                                                                String target =
                                                                        identity.equals("unpaired")
                                                                                ? ""
                                                                                : identity;
                                                                if (current != null
                                                                        && current.trying == demo
                                                                        && current.importTarget
                                                                                .equals(target)
                                                                        && (!remote
                                                                                || access
                                                                                        .current()))
                                                                    result.ready(
                                                                            ready, attachment.mime);
                                                                else result.failed();
                                                            });
                                            });
                                } catch (Exception failure) {
                                    state.execute(
                                            () -> {
                                                if (!preview
                                                        && demo == trying
                                                        && identity.equals(repo.roomIdentity())
                                                        && (access == null
                                                                || currentAccess(access)))
                                                    notice(
                                                            access == null
                                                                    ? "请先配对房间"
                                                                    : "附件读取失败，请稍后重试");
                                                main.post(result::failed);
                                            });
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

    private void admitRows(List<Message> rows, long cursor) {
        repo.admit(rows, cursor);
        for (Message row : rows) turn.settled(row);
        reload();
    }

    private void reload() {
        cachedRows = repo.rows(visibleLimit);
        cachedRows.addAll(liveNotes);
        pending = repo.pending();
        for (VoiceAdmission admission : voiceAdmissions.values())
            if (admission.note.room.equals(repo.roomIdentity())) {
                cachedRows.add(admission.note.row(true));
                pending.add(admission.note.item(true));
            }
        cachedRows.sort(java.util.Comparator.comparingLong(row -> row.at));
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
        upgrade = UpgradeNotice.NONE;
        resetRedial("room_reset");
        ambientLinkPaused = false;
        ambientFailure = "";
        setOutputMuted(false);
        liveNotes.clear();
        lastHeard = oldestDate = "";
        olderRequestEpoch = -1;
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
        if (tailnetNeedsLogin()) return "需要登录 Tailscale";
        if (connection == Connection.OFFLINE) return "连不上 · 正在重试";
        if (connection == Connection.CONNECTING) return "连接中…";
        if (turn.working() != null) return turn.working().label();
        return roomState != null && !roomState.daemonOk ? "暂时无法回复" : "在线";
    }

    private void publish() {
        notifications.interrupted(ambientWanted && audioSuspended);
        if (ambientWanted) {
            if (!ambientFailure.isEmpty()) ambient = Ambient.FAILED;
            else if (audioSuspended) ambient = Ambient.PAUSED;
            else if (ambientLinkPaused) ambient = Ambient.OFFLINE;
            else if (!ambientInput.helloSent()) ambient = Ambient.STARTING;
            else if (ambientInput.role().equals("peer")) ambient = Ambient.SEAT_TAKEN;
            else if (ambientInput.roomState().equals("unowned")) ambient = Ambient.DEAF;
            else ambient = playerActive ? Ambient.SPEAKING : Ambient.LISTENING;
        }
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
