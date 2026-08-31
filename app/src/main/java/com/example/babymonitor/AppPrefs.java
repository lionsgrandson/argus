package com.example.babymonitor;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    private static final String PREFS = "baby_monitor";
    static final String DEFAULT_RELAY = BuildConfig.DEFAULT_RELAY_URL;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void saveRelay(Context c, String relay) {
        prefs(c).edit().putString("relay", relay.trim()).apply();
    }

    static String relay(Context c) {
        return prefs(c).getString("relay", DEFAULT_RELAY).trim();
    }

    static void savePairing(Context c, PairingConfig p) {
        String protectedCode = SecretStore.encrypt(p.encode());
        prefs(c).edit()
                .putString("pairing_protected", protectedCode)
                .remove("room").remove("auth").remove("key").remove("pairing_code")
                .apply();
    }

    static PairingConfig pairing(Context c) {
        String protectedCode = prefs(c).getString("pairing_protected", "");
        if (protectedCode == null || protectedCode.isEmpty()) return null;
        try { return PairingConfig.parse(SecretStore.decrypt(protectedCode)); }
        catch (Exception e) { return null; }
    }

    static void clearPairing(Context c) {
        prefs(c).edit()
                .remove("pairing_protected")
                .putBoolean("pair_confirmed", false)
                .putBoolean("baby_peer_online", false)
                .putBoolean("parent_peer_online", false)
                .apply();
    }

    static void setPairConfirmed(Context c, boolean confirmed) {
        prefs(c).edit().putBoolean("pair_confirmed", confirmed).apply();
    }

    static boolean pairConfirmed(Context c) {
        return prefs(c).getBoolean("pair_confirmed", false);
    }

    static void setPeerOnline(Context c, String role, boolean online) {
        prefs(c).edit().putBoolean(role + "_peer_online", online).apply();
    }

    static boolean peerOnline(Context c, String role) {
        return prefs(c).getBoolean(role + "_peer_online", false);
    }

    static void setParentMedia(Context c, boolean camera, boolean mic) {
        prefs(c).edit()
                .putBoolean("parent_camera_enabled", camera)
                .putBoolean("parent_mic_enabled", mic)
                .apply();
    }

    static boolean parentCameraEnabled(Context c) {
        return prefs(c).getBoolean("parent_camera_enabled", false);
    }

    static boolean parentMicEnabled(Context c) {
        return prefs(c).getBoolean("parent_mic_enabled", true);
    }

    static void setChildMedia(Context c, boolean camera, boolean mic) {
        prefs(c).edit()
                .putBoolean("child_camera_enabled", camera)
                .putBoolean("child_mic_enabled", mic)
                .apply();
    }

    static boolean childCameraEnabled(Context c) {
        return prefs(c).getBoolean("child_camera_enabled", false);
    }

    static boolean childMicEnabled(Context c) {
        return prefs(c).getBoolean("child_mic_enabled", true);
    }

    static void setMode(Context c, String mode) { prefs(c).edit().putString("desired_mode", mode).apply(); }
    static String mode(Context c) { return prefs(c).getString("desired_mode", "none"); }

    static void state(Context c, String role, String text) {
        prefs(c).edit().putString(role + "_state", text).putLong(role + "_state_at", System.currentTimeMillis()).apply();
    }

    static String getState(Context c, String role, String fallback) {
        return prefs(c).getString(role + "_state", fallback);
    }

    static void parentBattery(Context c, int percent, boolean charging) {
        prefs(c).edit().putInt("baby_battery", percent).putBoolean("baby_charging", charging)
                .putLong("baby_battery_at", System.currentTimeMillis()).apply();
    }

    private AppPrefs() {}
}