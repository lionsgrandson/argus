package com.example.babymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;

public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_RESUME_BABY = "com.example.babymonitor.RESUME_CHILD";
    static final String ACTION_RESUME_PARENT = "com.example.babymonitor.RESUME_PARENT";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        boolean restartEvent = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_UNLOCKED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!restartEvent) return;

        Context appContext = context.getApplicationContext();
        UserManager userManager = (UserManager) appContext.getSystemService(Context.USER_SERVICE);
        if (userManager != null && !userManager.isUserUnlocked()) {
            return;
        }

        String mode = AppPrefs.mode(appContext);
        if (!"baby".equals(mode) && !"parent".equals(mode)) return;

        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                resumeAfterRestart(appContext, mode);
                WakeManager.refreshRegistrationAsync(appContext);
            } finally {
                pendingResult.finish();
            }
        }, "ArgusBootResume").start();
    }

    private void resumeAfterRestart(Context context, String mode) {
        if ("baby".equals(mode)) {
            // Android 14+ treats microphone/camera permissions as while-in-use and
            // does not allow a BOOT_COMPLETED receiver to create this foreground
            // service. Do not try to bypass that restriction or force an Activity
            // onto the screen. FCM can still refresh the device's wake registration.
            if (Build.VERSION.SDK_INT >= 34) {
                AppPrefs.setPeerOnline(context, "baby", false);
                AppPrefs.state(context, "baby", "ממתין להפעלה לאחר אתחול");
                return;
            }

            if (!tryStart(context, SenderService.class)) {
                AppPrefs.setPeerOnline(context, "baby", false);
                AppPrefs.state(context, "baby", "ממתין להפעלה לאחר אתחול");
            }
            return;
        }

        // Starting a mediaPlayback foreground service from BOOT_COMPLETED is
        // restricted for apps targeting Android 15+ on Android 15+.
        if (Build.VERSION.SDK_INT >= 35) {
            AppPrefs.setPeerOnline(context, "parent", false);
            AppPrefs.state(context, "parent", "ממתין להפעלה לאחר אתחול");
            return;
        }

        if (!tryStart(context, ReceiverService.class)) {
            AppPrefs.setPeerOnline(context, "parent", false);
            AppPrefs.state(context, "parent", "ממתין להפעלה לאחר אתחול");
        }
    }

    private boolean tryStart(Context context, Class<?> serviceClass) {
        try {
            Intent service = new Intent(context, serviceClass);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
