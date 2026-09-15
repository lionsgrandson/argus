package com.example.babymonitor;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class WakeManager {
    private static final String PREF_FCM_TOKEN = "argus_fcm_token_v1";
    private static final int HTTP_TIMEOUT_MS = 8000;

    static void initialize(Context context) {
        if (!ensureFirebase(context)) return;
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) return;
                onNewToken(context, task.getResult());
            });
        } catch (RuntimeException ignored) { }
    }

    static void onNewToken(Context context, String token) {
        if (context == null || token == null || token.trim().isEmpty()) return;
        Context app = context.getApplicationContext();
        AppPrefs.prefs(app).edit().putString(PREF_FCM_TOKEN, token.trim()).apply();
        refreshRegistrationAsync(app);
    }

    static void refreshRegistrationAsync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!ensureFirebase(app)) return;

        String stored = AppPrefs.prefs(app).getString(PREF_FCM_TOKEN, "");
        if (stored != null && !stored.trim().isEmpty()) {
            registerAsync(app, stored.trim());
            return;
        }

        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) return;
                String token = task.getResult().trim();
                if (token.isEmpty()) return;
                AppPrefs.prefs(app).edit().putString(PREF_FCM_TOKEN, token).apply();
                registerAsync(app, token);
            });
        } catch (RuntimeException ignored) { }
    }

    static void requestRemoteWakeAsync(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        PairingConfig pairing = AppPrefs.pairing(app);
        if (pairing == null) return;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("room", pairing.roomId);
                post(app, "/wake/request", pairing, body);
            } catch (Exception ignored) { }
        }, "ArgusWakeRequest").start();
    }

    static void handleRemoteWake(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!"baby".equals(AppPrefs.mode(app))) return;

        // Android 14+ does not let a background app create a microphone/camera
        // foreground service just because FCM woke it. Keep this path silent and
        // compliant: refresh state/config, but do not force an Activity or show a
        // fallback notification.
        if (Build.VERSION.SDK_INT >= 34) {
            AppPrefs.setPeerOnline(app, "baby", false);
            AppPrefs.state(app, "baby", "התקבלה בקשת חיבור מרחוק");
            RemoteConfig.refreshIfDue(app);
            return;
        }

        try {
            Intent service = new Intent(app, SenderService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(service);
            else app.startService(service);
        } catch (RuntimeException ignored) {
            AppPrefs.setPeerOnline(app, "baby", false);
        }
    }

    private static void registerAsync(Context context, String token) {
        PairingConfig pairing = AppPrefs.pairing(context);
        if (pairing == null || !"baby".equals(AppPrefs.mode(context))) return;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("room", pairing.roomId);
                body.put("token", token);
                post(context, "/wake/register", pairing, body);
            } catch (Exception ignored) { }
        }, "ArgusWakeRegister").start();
    }

    private static boolean ensureFirebase(Context context) {
        if (context == null) return false;
        if (blank(BuildConfig.FIREBASE_API_KEY)
                || blank(BuildConfig.FIREBASE_APP_ID)
                || blank(BuildConfig.FIREBASE_PROJECT_ID)
                || blank(BuildConfig.FIREBASE_SENDER_ID)) {
            return false;
        }

        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build();
                FirebaseApp.initializeApp(context, options);
            }
            return !FirebaseApp.getApps(context).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void post(Context context, String path, PairingConfig pairing, JSONObject body) throws Exception {
        String base = relayHttpBase(context);
        if (base.isEmpty()) return;

        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + pairing.authToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Cache-Control", "no-store");

        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(data);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream != null) {
            try (InputStream in = stream) {
                byte[] buffer = new byte[512];
                while (in.read(buffer) != -1) { }
            }
        }
        connection.disconnect();
    }

    private static String relayHttpBase(Context context) {
        String relay = AppPrefs.relay(context);
        if (blank(relay)) relay = BuildConfig.DEFAULT_RELAY_URL;
        if (blank(relay)) return "";
        try {
            URI uri = new URI(relay.trim());
            if (uri.getHost() == null) return "";
            String scheme = "wss".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
            int port = uri.getPort();
            return scheme + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception ignored) {
            try {
                Uri uri = Uri.parse(relay.trim());
                if (uri.getHost() == null) return "";
                return "https://" + uri.getHost();
            } catch (Exception ignoredAgain) {
                return "";
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private WakeManager() {}
}
