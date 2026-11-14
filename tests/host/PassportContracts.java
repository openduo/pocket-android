// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.core.Json;
import dev.openduo.pocket.passport.*;
import dev.openduo.pocket.text.Graphemes;

import org.json.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Differential tests against frozen outputs from the pinned, unmodified upstream Swift code. */
public final class PassportContracts {
    private static int checked;
    private static final List<String> failures = new ArrayList<>();

    public static int run(Path root) throws Exception {
        checked = 0;
        failures.clear();
        JSONObject fixture =
                new JSONObject(
                        new String(
                                Files.readAllBytes(
                                        root.resolve(
                                                "tests/fixtures/passport/upstream-vectors.json")),
                                StandardCharsets.UTF_8));
        if (!fixture.getString("commit").equals("b9d91bb9b77a12c3e59e6f359af1e4537a4ded39"))
            throw new AssertionError("Passport oracle revision");
        JSONArray cases = fixture.getJSONArray("vectors");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject item = cases.getJSONObject(i);
            String name = item.getString("name");
            try {
                Object actual;
                switch (item.getString("kind")) {
                    case "wire":
                        actual = wire(item);
                        break;
                    case "receive":
                        actual = receive(item);
                        break;
                    case "press":
                        actual = press(item);
                        break;
                    case "reply":
                        actual = replies(item);
                        break;
                    case "result":
                        actual = results(item);
                        break;
                    default:
                        throw new AssertionError("Unknown Passport vector kind");
                }
                compare(item.get("expected"), actual, name);
            } catch (Exception failure) {
                failures.add(name + ": " + failure);
            }
        }
        int upstreamComparisons = checked;
        generations();
        queue();
        graphemes(root);
        if (!failures.isEmpty())
            throw new AssertionError(
                    "Passport reference mismatches:\n" + String.join("\n", failures));
        System.out.println(
                "PASS "
                        + cases.length()
                        + " upstream Passport scenarios / "
                        + upstreamComparisons
                        + " comparisons; "
                        + (checked - upstreamComparisons)
                        + " supporting checks");
        return checked;
    }

    private static void graphemes(Path root) throws Exception {
        int scenarios = 0;
        for (String line :
                Files.readAllLines(
                        root.resolve("tests/fixtures/passport/GraphemeBreakTest.txt"),
                        StandardCharsets.UTF_8)) {
            String data = line.split("#", 2)[0].trim();
            if (data.isEmpty()) continue;
            StringBuilder text = new StringBuilder();
            List<Integer> expected = new ArrayList<>();
            for (String token : data.split("\\s+")) {
                if (token.equals("÷")) expected.add(text.length());
                else if (!token.equals("×")) text.appendCodePoint(Integer.parseInt(token, 16));
            }
            compare(
                    expected.toString(),
                    Graphemes.boundaries(text.toString()).toString(),
                    "Unicode 16 grapheme case " + ++scenarios);
        }
        if (scenarios == 0) throw new AssertionError("Missing Unicode conformance cases");
        System.out.println("Checked " + scenarios + " Unicode 16 grapheme cases");
    }

    private static void compare(Object expected, Object actual, String path) {
        checked++;
        if (expected instanceof JSONArray && actual instanceof JSONArray) {
            JSONArray a = (JSONArray) expected, b = (JSONArray) actual;
            if (a.length() != b.length()) {
                failures.add(path + ": array sizes " + a.length() + " != " + b.length());
                return;
            }
            for (int i = 0; i < a.length(); i++) compare(a.opt(i), b.opt(i), path + "[" + i + "]");
        } else if (expected instanceof JSONObject && actual instanceof JSONObject) {
            JSONObject a = (JSONObject) expected, b = (JSONObject) actual;
            for (java.util.Iterator<String> keys = a.keys(); keys.hasNext(); ) {
                String key = keys.next();
                compare(a.opt(key), b.opt(key), path + "." + key);
            }
        } else if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            String left = String.valueOf(expected), right = String.valueOf(actual);
            failures.add(
                    path
                            + ": "
                            + (left.length() > 100 ? left.substring(0, 100) + "…" : left)
                            + " != "
                            + (right.length() > 100 ? right.substring(0, 100) + "…" : right));
        }
    }

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    private static JSONArray wire(JSONObject item) {
        JSONObject m = item.getJSONObject("message");
        int type;
        byte[] payload;
        switch (m.getString("type")) {
            case "result":
                type = PocketProtocol.RESULT;
                payload =
                        PocketProtocol.result(
                                m.getInt("id"), m.getInt("code"), m.getString("text"));
                break;
            case "reply":
                type = PocketProtocol.REPLY;
                payload =
                        PocketProtocol.reply(
                                m.getLong("id"), m.getBoolean("final"), m.getString("text"));
                break;
            case "done":
                type = PocketProtocol.REPLY_DONE;
                payload = PocketProtocol.u32(m.getLong("id"));
                break;
            case "app":
                type = PocketProtocol.APP_STATE;
                payload =
                        m.isNull("language")
                                ? new byte[] {(byte) m.getInt("state")}
                                : PocketProtocol.appState(
                                        m.getInt("state"), m.getInt("language") == 1);
                break;
            default:
                type = PocketProtocol.WORK;
                byte[] label = m.getString("text").getBytes(StandardCharsets.UTF_8);
                payload = new byte[label.length + 1];
                payload[0] = (byte) m.getInt("phase");
                System.arraycopy(label, 0, payload, 1, label.length);
        }
        JSONArray out = new JSONArray();
        for (byte[] pdu :
                PocketProtocol.fragments(
                        type, payload, item.getInt("mtu"), item.optBoolean("quiet")))
            out.put(hex(pdu));
        return out;
    }

    private static JSONArray receive(JSONObject item) {
        boolean device = item.getString("direction").equals("device");
        PocketProtocol.Receiver receiver =
                new PocketProtocol.Receiver(
                        device
                                ? PocketProtocol.Direction.DEVICE_TO_PHONE
                                : PocketProtocol.Direction.PHONE_TO_DEVICE);
        JSONArray out = new JSONArray(), pdus = item.getJSONArray("pdus");
        for (int i = 0; i < pdus.length(); i++) {
            PocketProtocol.Message m = receiver.push(bytes(pdus.getString(i)));
            out.put(
                    m == null
                            ? JSONObject.NULL
                            : Json.object(
                                    "type",
                                    m.type,
                                    "payload",
                                    hex(m.data),
                                    "quiet",
                                    !device && m.quiet));
        }
        compare(item.getInt("errors"), receiver.errors, item.getString("name") + ".errors");
        return out;
    }

    private static JSONArray press(JSONObject item) {
        PressAssembler assembler = new PressAssembler();
        JSONArray out = new JSONArray(), actions = item.getJSONArray("actions");
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.getJSONObject(i);
            List<PressAssembler.Press> complete;
            if (a.getString("op").equals("lost")) complete = assembler.lost();
            else {
                PocketProtocol.Receiver receiver = new PocketProtocol.Receiver();
                PocketProtocol.Message m = null;
                for (byte[] pdu :
                        PocketProtocol.fragments(
                                a.getInt("type"), bytes(a.getString("payload")), 517, false))
                    m = receiver.push(pdu);
                complete = assembler.handle(m);
            }
            JSONArray presses = new JSONArray();
            for (PressAssembler.Press p : complete) {
                JSONArray packets = new JSONArray();
                for (byte[] packet : p.packets) packets.put(hex(packet));
                presses.put(
                        Json.object(
                                "id",
                                p.id,
                                "packets",
                                packets,
                                "gaps",
                                p.gaps,
                                "duplicates",
                                p.duplicates,
                                "declared",
                                p.declared,
                                "implicit",
                                p.implicit,
                                "end",
                                p.end.equals("press_end")
                                        ? "pressEnd"
                                        : p.end.equals("link_lost") ? "linkLost" : p.end));
            }
            out.put(Json.object("active", assembler.active(), "completed", presses));
        }
        return out;
    }

    private static JSONArray outputs(List<ReplyTracker.Output> messages) {
        JSONArray out = new JSONArray();
        for (ReplyTracker.Output m : messages)
            out.put(Json.object("type", m.type, "payload", hex(m.payload), "quiet", m.quiet));
        return out;
    }

    private static JSONArray replies(JSONObject item) {
        ReplyTracker t = new ReplyTracker();
        JSONArray out = new JSONArray(), actions = item.getJSONArray("actions");
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.getJSONObject(i);
            boolean fg = a.optBoolean("foreground");
            long now = a.optLong("now", 1000) * 1000;
            List<ReplyTracker.Output> messages = Collections.emptyList();
            switch (a.getString("op")) {
                case "expect":
                    t.expect(a.isNull("id") ? null : a.getString("id"));
                    break;
                case "frame":
                    messages = t.handle(a.getJSONObject("frame"), fg, now);
                    break;
                case "rows":
                    List<JSONObject> rows = new ArrayList<>();
                    JSONArray list = a.getJSONArray("rows");
                    for (int j = 0; j < list.length(); j++) rows.add(list.getJSONObject(j));
                    messages = t.rows(rows, fg, now);
                    break;
                case "restore_turn":
                    messages = t.restoreTurn(a.getJSONObject("turn"));
                    break;
                case "failed":
                    messages = t.messageFailed(a.getString("id"));
                    break;
                case "new_press":
                    t.newPress();
                    break;
                case "forget":
                    t.forgetDelivered();
                    break;
                case "hand":
                    t.handed(
                            new ReplyTracker.Output(
                                    PocketProtocol.REPLY,
                                    PocketProtocol.reply(a.getLong("id"), true, ""),
                                    false));
                    break;
                case "link":
                    messages = t.linkReady(fg);
                    break;
                case "relaunch":
                    JSONObject memory = new JSONObject(t.json().toString());
                    t = new ReplyTracker();
                    t.restore(memory);
                    break;
                default:
                    throw new AssertionError("Unknown reply action");
            }
            out.put(
                    Json.object(
                            "outputs",
                            outputs(messages),
                            "waiting",
                            t.waiting,
                            "serial",
                            t.serial,
                            "latestId",
                            t.latestId,
                            "latestText",
                            t.latestText));
        }
        return out;
    }

    private static JSONArray results(JSONObject item) {
        VoiceResultWait wait = new VoiceResultWait("note");
        JSONArray out = new JSONArray(), actions = item.getJSONArray("actions");
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.getJSONObject(i);
            VoiceResultWait.Result result;
            switch (a.getString("op")) {
                case "accepted":
                    result = wait.accepted();
                    break;
                case "expire":
                    result = wait.expired(a.getBoolean("connected"));
                    break;
                default:
                    result = wait.handle(a.getJSONObject("frame"));
            }
            out.put(
                    Json.object(
                            "result",
                            result == null
                                    ? JSONObject.NULL
                                    : Json.object("code", result.code, "text", result.text),
                            "done",
                            wait.done(),
                            "rowText",
                            wait.rowText() == null ? JSONObject.NULL : wait.rowText()));
        }
        return out;
    }

    private static void generations() {
        PassportResults results = new PassportResults();
        results.beginConnection();
        results.expect("note", 7, 100);
        JSONObject ready = Json.object("type", "ready", "messages", new JSONArray());
        compare(0, results.frame(ready).size(), "ready before receipt cannot fail a press");
        compare(0, results.accepted("note").size(), "same connection ready predates its receipt");
        results.beginConnection();
        List<PassportResults.Settlement> settled = results.frame(ready);
        compare(3, settled.get(0).result.code, "new connection missing press returns send_failed");
        compare(0, results.expire(30000, true).size(), "result settles exactly once");
        results.expect("next", 8, 1000);
        compare(0, results.expire(25999, false).size(), "deadline begins at press end");
        compare(
                4,
                results.expire(26000, false).get(0).result.code,
                "offline deadline returns code four");
    }

    private static void queue() {
        PassportOutbox outbox = new PassportOutbox();
        outbox.offer(PocketProtocol.REPLY, PocketProtocol.reply(7, false, "one"), true, null);
        outbox.next(23); // This partial is in flight and must finish without interleaving.
        outbox.offer(PocketProtocol.REPLY, PocketProtocol.reply(7, false, "two"), true, null);
        outbox.offer(PocketProtocol.REPLY, PocketProtocol.reply(7, false, "three"), false, null);
        outbox.offer(PocketProtocol.REPLY_DONE, PocketProtocol.u32(7), false, null);
        PocketProtocol.Receiver receiver =
                new PocketProtocol.Receiver(PocketProtocol.Direction.PHONE_TO_DEVICE);
        List<PocketProtocol.Message> complete = new ArrayList<>();
        byte[] next;
        while ((next = outbox.next(23)) != null) {
            PocketProtocol.Message m = receiver.push(next);
            if (m != null) complete.add(m);
            outbox.acknowledge();
        }
        compare(3, complete.size(), "partial coalescing preserves final control order");
        compare("one", complete.get(0).text(5), "in-flight partial is not replaced");
        compare("three", complete.get(1).text(5), "only newest queued partial is sent");
        compare(false, complete.get(1).quiet, "replacement retains its current quiet flag");
    }

    public static void main(String[] args) throws Exception {
        try {
            run(Paths.get(args[0]));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
