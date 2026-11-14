// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.app.input;

import android.Manifest;
import android.content.pm.PackageManager;

import dev.openduo.pocket.core.PocketController;
import dev.openduo.pocket.input.InputAction;
import dev.openduo.pocket.input.InputTarget;
import dev.openduo.pocket.ui.PageHost;

/** Main-thread foreground intents. Permission grants never resurrect a released gesture. */
public final class InteractionDispatcher implements InputTarget {
    private final PageHost host;
    private final PocketController controller;
    private boolean navigating;
    private final String prefix = "window:" + java.util.UUID.randomUUID() + ":";

    public InteractionDispatcher(PageHost host, PocketController controller) {
        this.host = host;
        this.controller = controller;
    }

    @Override
    public String owner(String local) {
        return prefix + local;
    }

    @Override
    public void begin(String owner) {
        if (!microphone()) return;
        voicePage();
        controller.beginHold(owner);
    }

    @Override
    public void end(String owner, boolean cancel) {
        controller.releaseHold(owner, cancel);
    }

    @Override
    public void invoke(InputAction action, String owner) {
        PocketController.Snapshot snapshot = controller.snapshot();
        if (snapshot == null) return;
        switch (action) {
            case TOGGLE_VOICE:
                if (!snapshot.holdRecording && !microphone()) return;
                voicePage();
                controller.toggleHold(owner);
                break;
            case CANCEL_OR_STOP:
                controller.cancelOrStop();
                break;
            case SEND_VOICE:
                controller.sendHold();
                break;
            case TOGGLE_AMBIENT:
                if (snapshot.ambientActive || microphone()) controller.ambientToggle();
                break;
            case MUTE_AMBIENT:
                controller.ambientMute();
                break;
            case READ_LATEST:
                controller.readLatest();
                break;
            case CONVERSATION:
                host.navigate("conversation");
                break;
            case SETTINGS:
                host.navigate("settings");
                break;
            case HOLD_TO_TALK:
                begin(owner);
                break;
            case DISABLED:
                break;
        }
    }

    public boolean navigating() {
        return navigating;
    }

    private void voicePage() {
        navigating = true;
        try {
            host.navigate("conversation");
        } finally {
            navigating = false;
        }
    }

    public void cancel() {
        controller.cancelVoiceInputs(prefix);
    }

    private boolean microphone() {
        if (host.activity().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) return true;
        host.permission(
                new String[] {Manifest.permission.RECORD_AUDIO},
                () -> host.toast(host.ui().t("麦克风已允许，请再次开始录音")));
        return false;
    }
}
