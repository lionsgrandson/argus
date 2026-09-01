package com.example.babymonitor;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    private static final String PREFS = "baby_monitor";
    private static final int PAIRING_EPOCH = 4;
    private static final long ERROR_VISIBLE_MS = 5 * 60 * 1000L;
    static final String DEFAULT_RELAY = BuildConfig.DEFAULT_RELAY_URL;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean resetForCurrentPairingEpoch(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getInt("pairing_epoch", 0) == PAIRING_EPOCH) return false;
        p.edit().clear().putInt("pairing_epoch", PAIRING_EPOCH).commit();
        return true;
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
        try {
            return PairingConfig.parse(SecretStore.decrypt(protectedCode));
        } catch (Exception e) {
            return null;
        }
    }

    static void clearPairing(Context c) {
        prefs(c).edit()
                .remove("pairing_protected")
                .putBoolean("pair_confirmed", false)
                .putBoolean("baby_peer_online", false)
                .putBoolean("parent_peer_online", false)
                .remove("last_error_code")
                .remove("last_error_message")
                .remove("last_error_detail")
                .remove("last_error_role")
                .remove("last_error_at")
                .remove("last_error_count")
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

    static void setMode(Context c, String mode) {
        prefs(c).edit().putString("desired_mode", mode).apply();
    }

    static String mode(Context c) {
        return prefs(c).getString("desired_mode", "none");
    }

    static void state(Context c, String role, String text) {
        prefs(c).edit()
                .putString(role + "_state", text)
                .putLong(role + "_state_at", System.currentTimeMillis())
                .apply();
    }

    static String getState(Context c, String role, String fallback) {
        String code = lastErrorCode(c);
        long at = lastErrorAt(c);
        String errorRole = lastErrorRole(c);
        if (!code.isEmpty() && System.currentTimeMillis() - at <= ERROR_VISIBLE_MS
                && (role.equals(errorRole) || "setup".equals(errorRole))) {
            return "שגיאה " + code + ": " + lastErrorMessage(c);
        }
        return prefs(c).getString(role + "_state", fallback);
    }

    static void saveError(Context c, String role, String code, String message, String detail) {
        SharedPreferences p = prefs(c);
        int count = code.equals(p.getString("last_error_code", ""))
                ? p.getInt("last_error_count", 0) + 1 : 1;
        p.edit()
                .putString("last_error_role", role == null ? "setup" : role)
                .putString("last_error_code", code)
                .putString("last_error_message", message)
                .putString("last_error_detail", detail == null ? "" : detail)
                .putLong("last_error_at", System.currentTimeMillis())
                .putInt("last_error_count", count)
                .apply();
    }

    static String lastErrorCode(Context c) {
        return prefs(c).getString("last_error_code", "");
    }

    static String lastErrorMessage(Context c) {
        return prefs(c).getString("last_error_message", "");
    }

    static String lastErrorDetail(Context c) {
        return prefs(c).getString("last_error_detail", "");
    }

    static String lastErrorRole(Context c) {
        return prefs(c).getString("last_error_role", "");
    }

    static long lastErrorAt(Context c) {
        return prefs(c).getLong("last_error_at", 0L);
    }

    static int lastErrorCount(Context c) {
        return prefs(c).getInt("last_error_count", 0);
    }

    static void clearError(Context c, String role) {
        String storedRole = lastErrorRole(c);
        if (!storedRole.isEmpty() && role != null && !role.equals(storedRole) && !"setup".equals(storedRole)) return;
        prefs(c).edit()
                .remove("last_error_role")
                .remove("last_error_code")
                .remove("last_error_message")
                .remove("last_error_detail")
                .remove("last_error_at")
                .remove("last_error_count")
                .apply();
    }

    static void parentBattery(Context c, int percent, boolean charging) {
        prefs(c).edit()
                .putInt("baby_battery", percent)
                .putBoolean("baby_charging", charging)
                .putLong("baby_battery_at", System.currentTimeMillis())
                .apply();
    }

    private AppPrefs() {}
}
