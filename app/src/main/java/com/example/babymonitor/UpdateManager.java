package com.example.babymonitor;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateManager {
    static final String ACTION_INSTALL_STATUS = "com.example.babymonitor.UPDATE_INSTALL_STATUS";

    private static final String PREFS = "argus_app_updates_v1";
    private static final String PREF_LAST_CHECK = "last_check";
    private static final String PREF_DISMISSED_VERSION = "dismissed_version";
    private static final String PREF_DISMISSED_AT = "dismissed_at";
    private static final String PREF_PENDING = "pending_update";
    private static final String PREF_LAST_NOTIFIED_VERSION = "last_notified_version";
    private static final String PREF_LAST_NOTIFIED_AT = "last_notified_at";

    private static final String CHANNEL_ID = "argus_app_updates";
    private static final int UPDATE_NOTIFICATION_ID = 6201;
    private static final int UPDATE_ALARM_REQUEST = 6202;

    private static final long FOREGROUND_CHECK_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long BACKGROUND_CHECK_INTERVAL_MS = 4L * 60L * 60L * 1000L;
    private static final long DISMISS_SNOOZE_MS = 6L * 60L * 60L * 1000L;
    private static final long NOTIFICATION_REPEAT_MS = 12L * 60L * 60L * 1000L;
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;

    private static final AtomicBoolean CHECKING = new AtomicBoolean(false);
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean(false);

    static void schedule(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        ensureNotificationChannel(app);

        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(app, UpdateCheckReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                app,
                UPDATE_ALARM_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long first = SystemClock.elapsedRealtime() + 15L * 60L * 1000L;
        alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                first,
                BACKGROUND_CHECK_INTERVAL_MS,
                pendingIntent
        );
    }

    static void checkAndPrompt(Activity activity, boolean force) {
        if (activity == null || activity.isFinishing()) return;

        SharedPreferences prefs = prefs(activity);
        long now = System.currentTimeMillis();
        long lastCheck = prefs.getLong(PREF_LAST_CHECK, 0L);
        if (!force && lastCheck > 0L && now - lastCheck < FOREGROUND_CHECK_INTERVAL_MS) {
            resumePending(activity);
            return;
        }

        if (!CHECKING.compareAndSet(false, true)) return;
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply();

        new Thread(() -> {
            UpdateInfo info = null;
            Exception failure = null;
            try {
                info = fetchLatest(activity.getApplicationContext());
            } catch (Exception e) {
                failure = e;
            }

            final UpdateInfo latest = info;
            final Exception finalFailure = failure;
            CHECKING.set(false);

            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;

                if (latest != null && latest.enabled && latest.versionCode > BuildConfig.VERSION_CODE) {
                    maybeShowPrompt(activity, latest, force);
                    return;
                }

                if (force) {
                    String message = finalFailure == null
                            ? "You already have the latest version."
                            : "Could not check for updates. Please try again.";
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                    if (activity instanceof UpdateActivity) activity.finish();
                }
            });
        }, "ArgusUpdateCheck").start();
    }

    static void resumePending(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String json = prefs(activity).getString(PREF_PENDING, "");
        if (json == null || json.trim().isEmpty()) return;

        UpdateInfo info = UpdateInfo.fromJson(json);
        if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) {
            clearPending(activity);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            return;
        }

        downloadAndInstall(activity, info);
    }

    static void checkAndNotifyBlocking(Context context) {
        if (context == null) return;
        try {
            UpdateInfo info = fetchLatest(context.getApplicationContext());
            if (info == null || !info.enabled || info.versionCode <= BuildConfig.VERSION_CODE) return;

            SharedPreferences prefs = prefs(context);
            long now = System.currentTimeMillis();
            int lastVersion = prefs.getInt(PREF_LAST_NOTIFIED_VERSION, 0);
            long lastAt = prefs.getLong(PREF_LAST_NOTIFIED_AT, 0L);
            if (lastVersion == info.versionCode && now - lastAt < NOTIFICATION_REPEAT_MS) return;

            ensureNotificationChannel(context);
            Intent open = new Intent(context, UpdateActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent action = PendingIntent.getActivity(
                    context,
                    info.versionCode,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            String version = info.versionName.isEmpty()
                    ? String.valueOf(info.versionCode)
                    : info.versionName;

            Notification notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("App update available")
                    .setContentText("Version " + version + " is ready. Tap to update.")
                    .setContentIntent(action)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build();

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(UPDATE_NOTIFICATION_ID, notification);

            prefs.edit()
                    .putInt(PREF_LAST_NOTIFIED_VERSION, info.versionCode)
                    .putLong(PREF_LAST_NOTIFIED_AT, now)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static UpdateInfo fetchLatest(Context context) throws Exception {
        RemoteConfig.refreshIfDue(context);
        String manifestUrl = RemoteConfig.updateManifestUrl(context);
        if (!validUpdateUrl(manifestUrl)) throw new IllegalArgumentException("Invalid update endpoint");

        String body = ResilientHttp.get(manifestUrl, 64 * 1024);
        JSONObject root = new JSONObject(body);

        boolean enabled = root.optBoolean("enabled", true);
        int versionCode = root.optInt("versionCode", 0);
        String versionName = root.optString("versionName", "").trim();
        String apkUrl = root.optString("apkUrl", "").trim();
        String sha256 = root.optString("sha256", "").trim().toLowerCase(Locale.US);
        long sizeBytes = root.optLong("sizeBytes", 0L);
        boolean required = root.optBoolean("required", false);
        String notes = root.optString("notes", "").trim();

        if (!enabled) return new UpdateInfo(false, 0, "", "", "", 0L, false, "");
        if (versionCode <= 0) throw new IllegalArgumentException("Missing versionCode");
        if (!validUpdateUrl(apkUrl)) throw new IllegalArgumentException("Invalid APK URL");
        if (!sha256.matches("^[0-9a-f]{64}$")) throw new IllegalArgumentException("Invalid APK hash");
        if (sizeBytes < 0L || sizeBytes > MAX_APK_BYTES) throw new IllegalArgumentException("Invalid APK size");

        return new UpdateInfo(true, versionCode, versionName, apkUrl, sha256, sizeBytes, required, notes);
    }

    private static boolean validUpdateUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            return host.equalsIgnoreCase("baby-monitor-secure-relay.mosheschwartzberg.workers.dev");
        } catch (Exception e) {
            return false;
        }
    }

    private static void maybeShowPrompt(Activity activity, UpdateInfo info, boolean force) {
        SharedPreferences prefs = prefs(activity);
        long now = System.currentTimeMillis();
        int dismissedVersion = prefs.getInt(PREF_DISMISSED_VERSION, 0);
        long dismissedAt = prefs.getLong(PREF_DISMISSED_AT, 0L);

        if (!force && !info.required && dismissedVersion == info.versionCode
                && now - dismissedAt < DISMISS_SNOOZE_MS) {
            return;
        }

        String version = info.versionName.isEmpty()
                ? String.valueOf(info.versionCode)
                : info.versionName;
        StringBuilder message = new StringBuilder("Version ").append(version).append(" is available.");
        if (!info.notes.isEmpty()) message.append("\n\n").append(info.notes);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(message.toString())
                .setPositiveButton("Update now", (dialog, which) -> beginUpdate(activity, info));

        if (info.required) {
            builder.setCancelable(false);
        } else {
            builder.setNegativeButton("Later", (dialog, which) -> prefs.edit()
                    .putInt(PREF_DISMISSED_VERSION, info.versionCode)
                    .putLong(PREF_DISMISSED_AT, System.currentTimeMillis())
                    .apply());
        }

        builder.show();
    }

    private static void beginUpdate(Activity activity, UpdateInfo info) {
        savePending(activity, info);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivity(intent);
                Toast.makeText(
                        activity,
                        "Allow app installs from this source once, then return here to continue.",
                        Toast.LENGTH_LONG
                ).show();
            } catch (Exception e) {
                Toast.makeText(activity, "Could not open the install permission screen.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        downloadAndInstall(activity, info);
    }

    private static void downloadAndInstall(Activity activity, UpdateInfo info) {
        if (!DOWNLOADING.compareAndSet(false, true)) return;

        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Downloading update")
                .setMessage("Please keep the app open for a moment.")
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            File apk = null;
            Exception failure = null;
            try {
                apk = downloadApk(activity.getApplicationContext(), info);
            } catch (Exception e) {
                failure = e;
            }

            final File finalApk = apk;
            final Exception finalFailure = failure;
            DOWNLOADING.set(false);

            activity.runOnUiThread(() -> {
                if (progress.isShowing()) progress.dismiss();
                if (activity.isFinishing() || activity.isDestroyed()) return;

                if (finalFailure != null || finalApk == null) {
                    Toast.makeText(activity, "Update download failed. Please try again.", Toast.LENGTH_LONG).show();
                    return;
                }

                try {
                    installApk(activity.getApplicationContext(), finalApk);
                    clearPending(activity);
                    Toast.makeText(activity, "Android will now confirm the app update.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(activity, "Could not start the update installer.", Toast.LENGTH_LONG).show();
                }
            });
        }, "ArgusUpdateDownload").start();
    }

    private static File downloadApk(Context context, UpdateInfo info) throws Exception {
        URL url = new URL(info.apkUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
        connection.connect();

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + status);
        }

        long contentLength = connection.getContentLengthLong();
        if (contentLength > MAX_APK_BYTES) {
            connection.disconnect();
            throw new IllegalStateException("APK too large");
        }

        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create update cache");

        File part = new File(dir, "update-" + info.versionCode + ".apk.part");
        File apk = new File(dir, "update-" + info.versionCode + ".apk");
        if (part.exists()) part.delete();
        if (apk.exists()) apk.delete();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        byte[] buffer = new byte[64 * 1024];

        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_APK_BYTES) throw new IllegalStateException("APK too large");
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }

        if (info.sizeBytes > 0L && total != info.sizeBytes) {
            part.delete();
            throw new IllegalStateException("APK size mismatch");
        }

        String actualHash = toHex(digest.digest());
        if (!actualHash.equalsIgnoreCase(info.sha256)) {
            part.delete();
            throw new SecurityException("APK hash mismatch");
        }

        if (!part.renameTo(apk)) {
            part.delete();
            throw new IllegalStateException("Could not finalize APK");
        }
        return apk;
    }

    private static void installApk(Context context, File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try {
            try (InputStream in = new BufferedInputStream(new FileInputStream(apk));
                 OutputStream out = session.openWrite("app-update.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                session.fsync(out);
            }

            Intent status = new Intent(context, UpdateInstallReceiver.class)
                    .setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            else flags |= PendingIntent.FLAG_IMMUTABLE;

            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status, flags);
            session.commit(pending.getIntentSender());
        } finally {
            session.close();
        }
    }

    private static void ensureNotificationChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Notifies you when a signed app update is available.");
        manager.createNotificationChannel(channel);
    }

    static void showInstallFailure(Context context, String detail) {
        ensureNotificationChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("App update failed")
                .setContentText(detail == null || detail.isEmpty() ? "The update could not be installed." : detail)
                .setAutoCancel(true)
                .build();
        manager.notify(UPDATE_NOTIFICATION_ID + 1, notification);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void savePending(Context context, UpdateInfo info) {
        prefs(context).edit().putString(PREF_PENDING, info.toJson()).apply();
    }

    private static void clearPending(Context context) {
        prefs(context).edit().remove(PREF_PENDING).apply();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    static final class UpdateInfo {
        final boolean enabled;
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final long sizeBytes;
        final boolean required;
        final String notes;

        UpdateInfo(boolean enabled, int versionCode, String versionName, String apkUrl,
                   String sha256, long sizeBytes, boolean required, String notes) {
            this.enabled = enabled;
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.sha256 = sha256 == null ? "" : sha256;
            this.sizeBytes = sizeBytes;
            this.required = required;
            this.notes = notes == null ? "" : notes;
        }

        String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("enabled", enabled);
                json.put("versionCode", versionCode);
                json.put("versionName", versionName);
                json.put("apkUrl", apkUrl);
                json.put("sha256", sha256);
                json.put("sizeBytes", sizeBytes);
                json.put("required", required);
                json.put("notes", notes);
                return json.toString();
            } catch (Exception e) {
                return "";
            }
        }

        static UpdateInfo fromJson(String value) {
            try {
                JSONObject json = new JSONObject(value);
                return new UpdateInfo(
                        json.optBoolean("enabled", true),
                        json.optInt("versionCode", 0),
                        json.optString("versionName", ""),
                        json.optString("apkUrl", ""),
                        json.optString("sha256", ""),
                        json.optLong("sizeBytes", 0L),
                        json.optBoolean("required", false),
                        json.optString("notes", "")
                );
            } catch (Exception e) {
                return null;
            }
        }
    }

    private UpdateManager() {}
}
