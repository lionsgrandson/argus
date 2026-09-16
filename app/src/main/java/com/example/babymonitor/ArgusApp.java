package com.example.babymonitor;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public final class ArgusApp extends Application {
    private static volatile Context appContext;
    private static volatile boolean permissionAuditLaunchedThisProcess = false;

    private final Handler permissionHandler = new Handler(Looper.getMainLooper());
    private boolean permissionPollScheduled = false;
    private int permissionPollCount = 0;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(HebrewLocale.wrap(base));
    }

    @Override public void onCreate() {
        super.onCreate();
        HebrewLocale.apply(this);
        appContext = getApplicationContext();
        boolean reset = AppPrefs.resetForCurrentPairingEpoch(this);
        ErrorReporter.install(this);
        LauncherNameManager.ensureValidName(this);
        FrequentUpdateScheduler.schedule(this);
        WakeManager.initialize(this);
        registerUpdatePromptLifecycle();

        if (reset) {
            try { stopService(new Intent(this, SenderService.class)); } catch (Exception ignored) { }
            try { stopService(new Intent(this, ReceiverService.class)); } catch (Exception ignored) { }
        }
    }

    private void registerUpdatePromptLifecycle() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}

            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof PermissionAuditActivity) return;
                if (activity instanceof UpdateActivity || activity instanceof SettingsActivity) return;

                UpdateSettingsOverlay.attach(activity);
                UpdateManager.resumePending(activity);
                WakeManager.refreshRegistrationAsync(activity);

                if (activity instanceof MainActivity) {
                    schedulePermissionAudit(activity);
                }
            }

            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private void schedulePermissionAudit(Activity activity) {
        if (permissionAuditLaunchedThisProcess || permissionPollScheduled) return;

        permissionPollScheduled = true;
        permissionPollCount = 0;

        permissionHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (permissionAuditLaunchedThisProcess
                        || activity.isFinishing()
                        || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                    permissionPollScheduled = false;
                    return;
                }

                // Let MainActivity finish its own immediate role/camera/mic setup first.
                // If a system permission dialog is already open, wait until the app has
                // focus again before launching the remainder of the audit.
                if (!activity.hasWindowFocus()) {
                    if (++permissionPollCount < 120) {
                        permissionHandler.postDelayed(this, 500);
                    } else {
                        permissionPollScheduled = false;
                    }
                    return;
                }

                String role = AppPrefs.prefs(activity).getString("setup_role", "");
                if (!"baby".equals(role) && !"parent".equals(role)) {
                    if (++permissionPollCount < 120) {
                        permissionHandler.postDelayed(this, 500);
                    } else {
                        permissionPollScheduled = false;
                    }
                    return;
                }

                permissionPollScheduled = false;
                if (!PermissionAuditActivity.needsAudit(activity, role)) return;

                permissionAuditLaunchedThisProcess = true;
                try {
                    Intent audit = new Intent(activity, PermissionAuditActivity.class);
                    activity.startActivity(audit);
                    activity.overridePendingTransition(0, 0);
                } catch (RuntimeException ignored) {
                    permissionAuditLaunchedThisProcess = false;
                }
            }
        }, 1200);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        HebrewLocale.apply(this);
    }

    static Context context() {
        return appContext;
    }
}
