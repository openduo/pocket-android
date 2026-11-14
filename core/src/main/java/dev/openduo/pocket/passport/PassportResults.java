// Copyright 2026 openduo and pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.passport;

import org.json.JSONObject;

import java.util.*;

/** RESULT deadlines and connection-generation fencing, independent of BLE and Android clocks. */
public final class PassportResults {
    public static final long DEADLINE_MS = 25_000;

    public static final class Settlement {
        public final String messageId;
        public final int pressId;
        public final VoiceResultWait.Result result;

        Settlement(Pending p, VoiceResultWait.Result result) {
            messageId = p.wait.messageId;
            pressId = p.pressId;
            this.result = result;
        }
    }

    private static final class Pending {
        final VoiceResultWait wait;
        final int pressId;
        final long deadline;
        long acceptedGeneration = -1;

        Pending(String id, int pressId, long now) {
            wait = new VoiceResultWait(id);
            this.pressId = pressId;
            deadline = now + DEADLINE_MS;
        }
    }

    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private long generation, readyGeneration = -1;
    private JSONObject lastReady;

    public int size() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
        lastReady = null;
        readyGeneration = -1;
    }

    public void beginConnection() {
        generation++;
    }

    public void expect(String id, int press, long now) {
        pending.put(id, new Pending(id, press, now));
    }

    public long remainingMs(String id, long now) {
        Pending p = pending.get(id);
        return p == null ? Long.MAX_VALUE : Math.max(0, p.deadline - now);
    }

    public List<Settlement> frame(JSONObject frame) {
        List<Settlement> out = new ArrayList<>();
        boolean ready = frame.optString("type").equals("ready");
        if (ready) {
            lastReady = frame;
            readyGeneration = generation;
        }
        for (Pending p : new ArrayList<>(pending.values())) {
            VoiceResultWait.Result result = ready ? settleAccepted(p) : p.wait.handle(frame);
            settle(p, result, out);
        }
        return out;
    }

    public List<Settlement> accepted(String id) {
        List<Settlement> out = new ArrayList<>();
        Pending p = pending.get(id);
        if (p != null) {
            p.acceptedGeneration = generation;
            settle(p, settleAccepted(p), out);
        }
        return out;
    }

    private VoiceResultWait.Result settleAccepted(Pending p) {
        if (p.acceptedGeneration < 0) return null;
        VoiceResultWait.Result result = p.wait.accepted();
        if (result == null && lastReady != null && readyGeneration > p.acceptedGeneration)
            result = p.wait.handle(lastReady);
        return result;
    }

    public List<Settlement> failed(String id, boolean transport) {
        List<Settlement> out = new ArrayList<>();
        Pending p = pending.get(id);
        if (p != null) settle(p, p.wait.failed(transport), out);
        return out;
    }

    public List<Settlement> expire(long now, boolean connected) {
        List<Settlement> out = new ArrayList<>();
        for (Pending p : new ArrayList<>(pending.values()))
            if (now >= p.deadline) settle(p, p.wait.expired(connected), out);
        return out;
    }

    private void settle(Pending p, VoiceResultWait.Result result, List<Settlement> out) {
        if (result == null) return;
        pending.remove(p.wait.messageId);
        out.add(new Settlement(p, result));
    }
}
