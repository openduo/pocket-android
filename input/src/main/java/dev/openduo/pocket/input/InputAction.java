// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.input;

/** Stable preference values and user intents. No Android keys, device models or window state. */
public enum InputAction {
    HOLD_TO_TALK,
    TOGGLE_VOICE,
    SEND_VOICE,
    CANCEL_OR_STOP,
    TOGGLE_AMBIENT,
    MUTE_AMBIENT,
    READ_LATEST,
    CONVERSATION,
    SETTINGS,
    DISABLED
}
