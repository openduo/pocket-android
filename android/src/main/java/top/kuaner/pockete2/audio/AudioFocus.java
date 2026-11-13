// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.util.function.IntConsumer;

/** Owns focus independently of the capture lease, with explicit interruption delivery. */
public final class AudioFocus {
    private final AudioManager manager;
    private final IntConsumer change;
    private volatile long epoch;
    private AudioFocusRequest request;
    private boolean ambient;

    public AudioFocus(Context context, IntConsumer change) {
        manager = context.getSystemService(AudioManager.class);
        this.change = change;
    }

    public boolean acquire(boolean room) {
        if (request != null && room == ambient) return true;
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
                                    if (ticket == epoch) change.accept(focus);
                                },
                                new Handler(Looper.getMainLooper()))
                        .setAcceptsDelayedFocusGain(false)
                        .build();
        if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            return true;
        request = null;
        return false;
    }

    public void release() {
        epoch++;
        if (request != null) {
            manager.abandonAudioFocusRequest(request);
            request = null;
        }
    }
}
