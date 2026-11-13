// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.qa;

/**
 * Android 8 shell helper: preserve the two user-decision flags of native camera location prompts.
 */
public final class PermissionFlags {
    private static final String CAMERA = "com.android.camera2";
    private static final String[] LOCATION = {
        "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"
    };

    public static void main(String[] args) throws Exception {
        Object manager =
                Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        java.lang.reflect.Method get =
                api.getMethod("getPermissionFlags", String.class, String.class, int.class);
        if (args.length == 0) {
            System.out.println(
                    get.invoke(manager, LOCATION[0], CAMERA, 0)
                            + ","
                            + get.invoke(manager, LOCATION[1], CAMERA, 0));
        } else if (args.length == 1 && args[0].matches("[0-9]+,[0-9]+")) {
            String[] flags = args[0].split(",");
            java.lang.reflect.Method update =
                    api.getMethod(
                            "updatePermissionFlags",
                            String.class,
                            String.class,
                            int.class,
                            int.class,
                            int.class);
            for (int i = 0; i < LOCATION.length; i++)
                update.invoke(manager, LOCATION[i], CAMERA, 3, Integer.parseInt(flags[i]) & 3, 0);
        } else throw new IllegalArgumentException("Expected the saved camera-location flags only");
    }
}
