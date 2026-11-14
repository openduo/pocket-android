// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.app;

import dev.openduo.pocket.BuildConfig;

/** On-disk identity for explicit, same-package updates; independent of the Java namespace. */
public final class AppIdentity {
    public static final String SETTINGS = BuildConfig.PERSISTENCE_PREFIX + ".settings";
    public static final String DATABASE = BuildConfig.PERSISTENCE_PREFIX + ".db";
    public static final String TOKEN_KEY = BuildConfig.PERSISTENCE_PREFIX + ".device-token.v1";
    public static final String SIGHT = BuildConfig.PERSISTENCE_PREFIX + ".sight";
    public static final String TAILNET_HOSTNAME = BuildConfig.TAILNET_HOSTNAME;

    private AppIdentity() {}
}
