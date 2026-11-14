// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

import dev.openduo.pocket.input.*;

import java.util.*;

final class InputContracts {
    private static int checked;

    private static void check(boolean value, String label) {
        checked++;
        if (!value) throw new AssertionError(label);
    }

    static int run() {
        List<String> actions = new ArrayList<>();
        KeyGestureRouter keys =
                new KeyGestureRouter(
                        new KeyGestureRouter.Target() {
                            public void begin(String owner) {
                                actions.add("begin:" + owner);
                            }

                            public void end(String owner, boolean cancel) {
                                actions.add("end:" + owner + ":" + cancel);
                            }

                            public void invoke(InputAction action, String owner) {
                                actions.add(action + ":" + owner);
                            }
                        });
        check(
                !keys.down("nav", null, false) && !keys.up("nav", false),
                "unbound navigation passes through");
        keys.down("rec", InputAction.TOGGLE_VOICE, false);
        for (int i = 0; i < 10; i++) keys.down("rec", InputAction.TOGGLE_VOICE, true);
        check(actions.isEmpty(), "toggle waits for release and ignores repeats");
        keys.up("rec", false);
        check(
                actions.equals(Arrays.asList("TOGGLE_VOICE:rec")),
                "one physical gesture invokes once");
        check(!keys.up("rec", false), "orphan release never invokes a second action");
        actions.clear();
        keys.down("rec", InputAction.HOLD_TO_TALK, false);
        keys.down("rec", InputAction.CANCEL_OR_STOP, false);
        keys.up("rec", false);
        check(
                actions.equals(Arrays.asList("begin:rec", "end:rec:false")),
                "binding changes and duplicate down cannot steal a held voice");
        actions.clear();
        keys.down("rec", InputAction.HOLD_TO_TALK, false);
        keys.down("mark", InputAction.CANCEL_OR_STOP, false);
        keys.cancel();
        keys.cancel();
        keys.up("mark", false);
        keys.up("rec", false);
        check(
                actions.equals(Arrays.asList("begin:rec", "end:rec:true")),
                "focus loss cancels once and swallows remaining edges");
        actions.clear();
        keys.down("rec", InputAction.TOGGLE_VOICE, false);
        keys.cancel();
        keys.down("rec", InputAction.TOGGLE_VOICE, true);
        check(actions.isEmpty(), "post-focus repeat cannot revive a cancelled gesture");
        keys.down("rec", InputAction.TOGGLE_VOICE, false);
        keys.up("rec", false);
        check(
                actions.equals(Arrays.asList("TOGGLE_VOICE:rec")),
                "fresh press works even if old key-up was lost");
        actions.clear();
        keys.down("one:rec", InputAction.HOLD_TO_TALK, false);
        keys.down("two:rec", InputAction.HOLD_TO_TALK, false);
        keys.up("two:rec", true);
        keys.up("one:rec", false);
        check(
                actions.equals(
                        Arrays.asList(
                                "begin:one:rec",
                                "begin:two:rec",
                                "end:two:rec:true",
                                "end:one:rec:false")),
                "different devices retain distinct source ownership");
        actions.clear();
        keys.down("disabled", InputAction.DISABLED, false);
        keys.up("disabled", false);
        keys.down("toggle", InputAction.TOGGLE_VOICE, false);
        keys.up("toggle", true);
        check(actions.isEmpty(), "disabled and cancelled clicks perform no business action");
        return checked;
    }
}
