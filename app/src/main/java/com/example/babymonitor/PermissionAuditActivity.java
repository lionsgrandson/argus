package com.example.babymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Runs a one-pass audit of the Android permissions/settings ARGUS actually needs.
 *
 * There is intentionally no custom button UI here. Normal runtime permissions are
 * requested with Android's own permission dialogs. Special access that Android does
 * not expose as a runtime dialog is opened directly in the matching system Settings
 * screen, then this activity continues with the next missing item when the user
 * returns.
 */
public final class PermissionAuditActivity extends Activity {
    private static final int REQ_CAMERA = 701;
    private static final int REQ_MIC = 702;
    private static final int REQ_NOTIFICATIONS = 703;

    private int step = 0;
    private boolean waitingForSettings = false;
    private long specialSettingsOpenedAt = 0L;
    private String lastRuntimePermission = null;

    public static boolean needsAudit(Context context, String role) {
        if (context == null) return false;

        if ("baby".equals(role)) {
            if (!hasPermission(context, Manifest.permission.CAMERA)) return true;
            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) return true;
        }

        if (Build.VERSION.SDK_INT >= 33
                && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            return true;
        }

        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                return true;
            }
        }

        if (Build.VERSION.SDK_INT >= 26
                && !context.getPackageManager().canRequestPackageInstalls()) {
            return true;
        }

        return false;
    }

    private static boolean hasPermission(Context context, String permission) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setDimAmount(0f);
        getWindow().getDecorView().setBackgroundColor(android.graphics.Color.TRANSPARENT);
        getWindow().getDecorView().postDelayed(this::advance, 180);
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForSettings
                && SystemClock.elapsedRealtime() - specialSettingsOpenedAt > 350L) {
            waitingForSettings = false;
            getWindow().getDecorView().postDelayed(this::advance, 180);
        }
    }

    private void advance() {
        if (isFinishing() || isDestroyed()) return;
        String role = AppPrefs.prefs(this).getString("setup_role", "");

        while (step < 5) {
            int current = step++;

            if (current == 0) {
                if ("baby".equals(role) && !hasPermission(this, Manifest.permission.CAMERA)) {
                    requestRuntime(Manifest.permission.CAMERA, REQ_CAMERA);
                    return;
                }
                continue;
            }

            if (current == 1) {
                if ("baby".equals(role) && !hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
                    requestRuntime(Manifest.permission.RECORD_AUDIO, REQ_MIC);
                    return;
                }
                continue;
            }

            if (current == 2) {
                if (Build.VERSION.SDK_INT >= 33
                        && !hasPermission(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    requestRuntime(Manifest.permission.POST_NOTIFICATIONS, REQ_NOTIFICATIONS);
                    return;
                }
                continue;
            }

            if (current == 3) {
                if (!batteryOptimizationExempt()) {
                    openSpecialSettings(new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())),
                            new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    return;
                }
                continue;
            }

            if (current == 4) {
                if (Build.VERSION.SDK_INT >= 26
                        && !getPackageManager().canRequestPackageInstalls()) {
                    openSpecialSettings(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())),
                            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName())));
                    return;
                }
            }
        }

        finish();
        overridePendingTransition(0, 0);
    }

    private void requestRuntime(String permission, int requestCode) {
        lastRuntimePermission = permission;
        requestPermissions(new String[]{permission}, requestCode);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (!granted && lastRuntimePermission != null
                && Build.VERSION.SDK_INT >= 23
                && !shouldShowRequestPermissionRationale(lastRuntimePermission)) {
            // Android will no longer present a normal permission prompt after a
            // permanent denial. Open the app's system permission page instead.
            Intent details = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            lastRuntimePermission = null;
            openSpecialSettings(details, null);
            return;
        }

        lastRuntimePermission = null;
        advance();
    }

    private boolean batteryOptimizationExempt() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openSpecialSettings(Intent primary, Intent fallback) {
        try {
            waitingForSettings = true;
            specialSettingsOpenedAt = SystemClock.elapsedRealtime();
            startActivity(primary);
        } catch (RuntimeException first) {
            if (fallback != null) {
                try {
                    waitingForSettings = true;
                    specialSettingsOpenedAt = SystemClock.elapsedRealtime();
                    startActivity(fallback);
                    return;
                } catch (RuntimeException ignored) { }
            }
            waitingForSettings = false;
            advance();
        }
    }
}
