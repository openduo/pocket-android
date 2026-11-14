// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import org.json.JSONObject;

/** Client API compatibility, ported from PocketCore/UpgradeNotice.swift. */
public enum UpgradeNotice {
    NONE,
    APP_UPDATE,
    CHANNEL_UPGRADE;

    public static final int CLIENT_API_MAJOR = 2;

    /** Only a messages response diagnoses these requirements; unrelated 404s do not. */
    public static UpgradeNotice fromResponse(int status, String reason) {
        if (status == 410 && "update_required".equals(reason)) return APP_UPDATE;
        return status == 404 ? CHANNEL_UPGRADE : NONE;
    }

    public static UpgradeNotice fromReady(JSONObject ready) {
        Object api = ready.opt("api");
        if (!(api instanceof Number)) return CHANNEL_UPGRADE;
        double version = ((Number) api).doubleValue();
        return Double.isFinite(version)
                        && version == Math.rint(version)
                        && version >= CLIENT_API_MAJOR
                ? NONE
                : CHANNEL_UPGRADE;
    }

    /** An app update remains required for this room until a new app process/room is selected. */
    public UpgradeNotice afterReady(JSONObject ready) {
        return this == APP_UPDATE ? this : fromReady(ready);
    }

    public UpgradeNotice afterResponse(int status, String reason) {
        UpgradeNotice next = fromResponse(status, reason);
        return this == APP_UPDATE || next == NONE ? this : next;
    }
}
