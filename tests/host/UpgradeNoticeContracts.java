// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.*;

import org.json.JSONObject;

/** The pinned upstream UpgradeNoticeTests plus Android state/strict-wire boundaries. */
final class UpgradeNoticeContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() {
        check(
                UpgradeNotice.fromResponse(410, "update_required") == UpgradeNotice.APP_UPDATE,
                "exact update_required requires an app update");
        check(
                UpgradeNotice.fromResponse(404, "") == UpgradeNotice.CHANNEL_UPGRADE,
                "a missing messages route requires a channel upgrade");
        check(
                UpgradeNotice.fromResponse(410, "gone") == UpgradeNotice.NONE,
                "an unrelated 410 does not require an upgrade");
        for (int status : new int[] {202, 400, 401, 403, 413, 415, 500, 503})
            check(
                    UpgradeNotice.fromResponse(status, "update_required") == UpgradeNotice.NONE,
                    "only the exact status diagnoses an app update: " + status);
        for (int api : new int[] {0, 1, 2, 3})
            check(
                    UpgradeNotice.fromReady(Json.object("api", api))
                            == (api < 2 ? UpgradeNotice.CHANNEL_UPGRADE : UpgradeNotice.NONE),
                    "ready API follows the upstream major-version rule: " + api);
        check(
                UpgradeNotice.fromReady(Json.object()) == UpgradeNotice.CHANNEL_UPGRADE,
                "absent ready API is an old channel");
        for (Object invalid : new Object[] {"2", true, JSONObject.NULL, 2.5})
            check(
                    UpgradeNotice.fromReady(Json.object("api", invalid))
                            == UpgradeNotice.CHANNEL_UPGRADE,
                    "malformed ready API cannot bypass compatibility");
        check(
                UpgradeNotice.APP_UPDATE.afterReady(Json.object("api", 2))
                                == UpgradeNotice.APP_UPDATE
                        && UpgradeNotice.APP_UPDATE.afterReady(Json.object("api", 1))
                                == UpgradeNotice.APP_UPDATE,
                "app update outranks all later ready versions");
        check(
                UpgradeNotice.CHANNEL_UPGRADE.afterReady(Json.object("api", 2))
                        == UpgradeNotice.NONE,
                "a compatible ready clears a channel notice");
        check(
                UpgradeNotice.APP_UPDATE.afterResponse(404, "") == UpgradeNotice.APP_UPDATE,
                "a later missing route cannot downgrade an app update");
        check(
                UpgradeNotice.CHANNEL_UPGRADE.afterResponse(503, "offline")
                        == UpgradeNotice.CHANNEL_UPGRADE,
                "an unrelated response cannot dismiss an upgrade");
        check(
                UpgradeNotice.CHANNEL_UPGRADE.afterResponse(410, "update_required")
                        == UpgradeNotice.APP_UPDATE,
                "an exact app-update response outranks a channel notice");
        return checked;
    }
}
