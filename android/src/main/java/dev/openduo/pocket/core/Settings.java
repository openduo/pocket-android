// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import android.content.Context;
import android.content.SharedPreferences;

/** Presentation preferences and one paired room. Device tokens use AndroidKeyStore. */
public final class Settings {
    private final SharedPreferences prefs;
    private final SecretStore secrets;
    private String token;

    public Settings(Context context) {
        prefs = context.getSharedPreferences("duoduo-pocket.settings", Context.MODE_PRIVATE);
        secrets = new SecretStore(context);
        token = secrets.read(hasEndpoint() ? endpoint().identity() + "|" + room() : "");
    }

    public String host() {
        return prefs.getString("host", "");
    }

    public int port() {
        return prefs.getInt("port", 443);
    }

    public boolean tls() {
        return prefs.getBoolean("tls", true);
    }

    public String room() {
        return prefs.getString("room", "");
    }

    public String roomName() {
        return prefs.getString("roomName", room());
    }

    public synchronized String token() {
        return token;
    }

    public String deviceId() {
        return prefs.getString("deviceId", "");
    }

    public String scope() {
        return prefs.getString("scope", "room");
    }

    public boolean unauthorized() {
        return prefs.getBoolean("unauthorized", false);
    }

    public void unauthorized(boolean value) {
        prefs.edit().putBoolean("unauthorized", value).apply();
    }

    public boolean isComplete() {
        return hasEndpoint() && !token().isEmpty();
    }

    public boolean hasEndpoint() {
        return !host().isEmpty() && !room().isEmpty();
    }

    public Endpoint endpoint() {
        return new Endpoint(host(), port(), tls());
    }

    public String baseUrl() {
        return endpoint().origin();
    }

    public synchronized void saveConnection(
            String host,
            int port,
            boolean tls,
            String room,
            String roomName,
            String deviceId,
            String scope,
            String token) {
        new Endpoint(host, port, tls);
        if (room == null || room.isEmpty() || token == null || token.isEmpty())
            throw new IllegalArgumentException("配对响应不完整");
        secrets.write(token, new Endpoint(host, port, tls).identity() + "|" + room);
        if (!prefs.edit()
                .putString("host", host.trim())
                .putInt("port", port)
                .putBoolean("tls", tls)
                .putString("room", room)
                .putString("roomName", roomName)
                .putString("deviceId", deviceId)
                .putString("scope", scope)
                .putBoolean("unauthorized", false)
                .commit()) {
            throw new IllegalStateException("无法保存房间设置");
        }
        this.token = token;
    }

    public synchronized void clearConnection() {
        secrets.write("", "");
        token = "";
        prefs.edit()
                .remove("host")
                .remove("room")
                .remove("roomName")
                .remove("deviceId")
                .remove("scope")
                .remove("unauthorized")
                .apply();
    }

    public String appearance() {
        return prefs.getString("appearance", "dark");
    }

    public void appearance(String value) {
        prefs.edit().putString("appearance", value).apply();
    }

    public boolean voiceInput() {
        return prefs.getBoolean("voiceInput", true);
    }

    public void voiceInput(boolean value) {
        prefs.edit().putBoolean("voiceInput", value).apply();
    }

    public boolean haptics() {
        return prefs.getBoolean("haptics", true);
    }

    public void haptics(boolean value) {
        prefs.edit().putBoolean("haptics", value).apply();
    }

    public boolean ambientOutput() {
        return prefs.getBoolean("ambientOutput", false);
    }

    public void ambientOutput(boolean value) {
        prefs.edit().putBoolean("ambientOutput", value).apply();
    }

    public boolean keepAwake() {
        return prefs.getBoolean("keepAwake", false);
    }

    public void keepAwake(boolean value) {
        prefs.edit().putBoolean("keepAwake", value).apply();
    }

    public boolean keepListening() {
        return prefs.getBoolean("keepListening", true);
    }

    public boolean ambientTranscript() {
        return prefs.getBoolean("ambientTranscript", false);
    }

    public boolean headsetMic() {
        return prefs.getBoolean("headsetMic", false);
    }

    public void headsetMic(boolean value) {
        prefs.edit().putBoolean("headsetMic", value).apply();
    }

    public void ambientTranscript(boolean value) {
        prefs.edit().putBoolean("ambientTranscript", value).apply();
    }

    public boolean answerNotifications() {
        return prefs.getBoolean("answerNotifications", true);
    }

    public void answerNotifications(boolean value) {
        prefs.edit().putBoolean("answerNotifications", value).apply();
    }

    public void keepListening(boolean value) {
        prefs.edit().putBoolean("keepListening", value).apply();
    }

    public boolean onboardingSeen() {
        return prefs.getBoolean("onboardingSeen", false);
    }

    public void onboardingSeen(boolean value) {
        prefs.edit().putBoolean("onboardingSeen", value).apply();
    }

    public String language() {
        return prefs.getString("language", "system");
    }

    public void language(String value) {
        if (!value.matches("system|zh-Hans|en"))
            throw new IllegalArgumentException("Unknown UI language");
        prefs.edit().putString("language", value).apply();
    }

    public boolean networkLog() {
        return prefs.getBoolean("networkLog", false);
    }

    public void networkLog(boolean value) {
        prefs.edit().putBoolean("networkLog", value).apply();
    }
}
