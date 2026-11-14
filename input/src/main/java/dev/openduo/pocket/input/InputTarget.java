// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
package dev.openduo.pocket.input;

/** Shared foreground action boundary for touch, keyboard, accessibility and hardware adapters. */
public interface InputTarget extends KeyGestureRouter.Target {
    String owner(String local);
}
