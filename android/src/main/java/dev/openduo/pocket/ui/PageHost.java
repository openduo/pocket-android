// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.ui;

import android.app.Activity;

import dev.openduo.pocket.core.Attachment;

public interface PageHost {
    Activity activity();

    Ui ui();

    void navigate(String page);

    void back();

    void toast(String message);

    void permission(String[] names, Runnable granted);

    void pickAttachment(String mime);

    void takePhoto();

    void openAttachment(Attachment attachment);

    void loginTailnet();
}
