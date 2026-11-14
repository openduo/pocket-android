// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.AmbientOutput;
import dev.openduo.pocket.core.Json;

import org.json.JSONObject;

/** Ported output cases from pinned PocketCore.AmbientEdgeTests, plus mixed hardware/skip clocks. */
public final class AmbientOutputContracts {
    private static int assertions;

    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }

    public static int run() {
        AmbientOutput output = new AmbientOutput();
        output.speech("s-filler");
        check(
                !output.acceptsStop(Json.object("type", "stop_audio"))
                        && output.interrupted(true).optString("speech_id").equals("s-filler"),
                "a filler ignores room stops but a local press can interrupt it");
        output.speech("c-first");
        String first = output.playbackKey();
        check(
                output.played(first, 60, false) == null
                        && output.playedMs() == 60
                        && output.interrupted(false) == null,
                "message-mode playback records hardware progress without reporting to the room");
        output.speech("c-second");
        String second = output.playbackKey();
        check(
                output.played(first, 1200, true) == null && output.playedMs() == 0,
                "an older speech tail cannot credit the next declaration");
        check(
                !output.acceptsStop(Json.object("speech_id", "c-first"))
                        && output.acceptsStop(Json.object("speech_id", "c-second"))
                        && output.acceptsStop(Json.object()),
                "only the current or unnamed room stop clears queued answers");
        JSONObject played = output.played(second, 120, true);
        check(
                played.optLong("ms") == 120 && output.played(second, 80, true) == null,
                "hardware watermarks remain monotonic");
        check(
                output.skipped(960, true).optLong("ms") == 180
                        && output.skipped(320, true).optLong("ms") == 200,
                "silent new blocks acknowledge at once instead of waiting on playback");
        check(
                output.played(second, 200, true).optLong("ms") == 280
                        && output.played(second, 200, true) == null,
                "earlier queued blocks can finish at zero volume without losing or duplicating"
                        + " skipped duration");
        output.stop();
        check(
                output.played(second, 500, true) == null
                        && output.skipped(320, true) == null
                        && output.interrupted(true) == null
                        && output.playedMs() == 0,
                "a stopped speech rejects stale receipts and has no interrupt report");
        output.read("local");
        check(
                output.reading()
                        && !output.acceptsStop(Json.object())
                        && output.interrupted(true) == null
                        && output.skipped(320, true) == null
                        && output.played(output.playbackKey(), 60, true) == null
                        && output.playedMs() == 60,
                "a local read is outside the room clock and survives its stop_audio");
        output.speech("c-live");
        check(
                !output.reading() && output.id().equals("c-live") && output.playedMs() == 0,
                "a live declaration replaces local-read accounting");
        output.stop();
        check(
                !output.acceptsStop(Json.object("speech_id", "c-live"))
                        && output.acceptsStop(Json.object()),
                "an unnamed stop still clears any old queue after the current owner ended");
        output.speech("c-repeated");
        String oldDeclaration = output.playbackKey();
        output.speech("c-repeated");
        check(
                !oldDeclaration.equals(output.playbackKey())
                        && output.played(oldDeclaration, 1200, true) == null
                        && output.playedMs() == 0
                        && output.played(output.playbackKey(), 20, true).optLong("ms") == 20,
                "reusing a wire id without clearing the hardware queue still isolates declaration"
                        + " receipts");
        return assertions;
    }
}
