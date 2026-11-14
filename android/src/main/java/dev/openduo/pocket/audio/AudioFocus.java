// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/** Owns focus independently of the capture lease, with explicit interruption delivery. */
public final class AudioFocus {
    private final AudioManager manager;
    private final IntConsumer change;
    private final Executor owner;
    private volatile long epoch;
    private volatile boolean granted, transientLoss;
    private AudioFocusRequest request;
    private boolean ambient;

    public AudioFocus(Context context, Executor owner, IntConsumer change) {
        manager = context.getSystemService(AudioManager.class);
        this.owner = owner;
        this.change = change;
    }

    public boolean acquire(boolean room) {
        if (request != null && transientLoss) return false;
        if (request != null && room == ambient && granted) return true;
        release();
        ambient = room;
        long ticket = ++epoch;
        request =
                new AudioFocusRequest.Builder(
                                room
                                        ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                                        : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setUsage(
                                                room
                                                        ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                                                        : AudioAttributes.USAGE_ASSISTANT)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build())
                        .setOnAudioFocusChangeListener(
                                focus -> {
                                    owner.execute(
                                            () -> {
                                                if (ticket != epoch) return;
                                                granted = focus > 0;
                                                transientLoss =
                                                        focus
                                                                        == AudioManager
                                                                                .AUDIOFOCUS_LOSS_TRANSIENT
                                                                || focus
                                                                        == AudioManager
                                                                                .AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
                                                change.accept(focus);
                                            });
                                },
                                new Handler(Looper.getMainLooper()))
                        .setAcceptsDelayedFocusGain(false)
                        .build();
        granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (granted) return true;
        request = null;
        return false;
    }

    public boolean interrupted() {
        return request != null && !granted;
    }

    public void release() {
        epoch++;
        granted = transientLoss = false;
        if (request != null) {
            manager.abandonAudioFocusRequest(request);
            request = null;
        }
    }
}
