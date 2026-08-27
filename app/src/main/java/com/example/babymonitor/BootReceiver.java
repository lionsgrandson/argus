package com.example.babymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;

public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_RESUME_BABY = "com.example.babymonitor.RESUME_CHILD";
    static final String ACTION_RESUME_PARENT = "com.example.babymonitor.RESUME_PARENT";

    private static final String CHANNEL_ID = "argus_resume_v2";
    private static final int NOTIFICATION_ID = 1077;

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
            // Pairing and desired mode are in credential-encrypted storage.
            // USER_UNLOCKED will arrive after the first unlock.
            return;
        }

        String mode = AppPrefs.mode(appContext);
        if (!"baby".equals(mode) && !"parent".equals(mode)) return;

        PendingResult pendingResult = goAsync();
        new Thread(() -> {
            try {
                resumeAfterRestart(appContext, mode);
            } finally {
                pendingResult.finish();
            }
        }, "ArgusBootResume").start();
    }

    private void resumeAfterRestart(Context context, String mode) {
        // Rooted path: if ARGUS has already been granted su access, root launches
        // the normal resume Activity after unlock. The Activity starts the same
        // foreground camera/microphone service used during an ordinary manual start.
        // Once started, ARGUS continues running when the screen is locked or the
        // user leaves the Activity.
        String resumeAction = "baby".equals(mode) ? ACTION_RESUME_BABY : ACTION_RESUME_PARENT;
        if (RootSupport.tryLaunchResumeActivity(context, resumeAction)) {
            cancelResumeNotification(context);
            return;
        }

        // Non-rooted compatibility path. Android 10 and older can resume the
        // camera/microphone foreground service directly from reboot.
        if ("baby".equals(mode) && Build.VERSION.SDK_INT < 30) {
            if (tryStart(context, SenderService.class)) {
                cancelResumeNotification(context);
                return;
            }
        }

        // Parent listening does not require while-in-use camera/microphone access,
        // so normal boot auto-resume remains possible through Android 14.
        if ("parent".equals(mode) && Build.VERSION.SDK_INT < 35) {
            if (tryStart(context, ReceiverService.class)) {
                cancelResumeNotification(context);
                return;
            }
        }

        // Current non-root Android requires user interaction before a background
        // app can start camera/microphone capture after reboot.
        postResumeNotification(context, mode);
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

    private void postResumeNotification(Context context, String mode) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ARGUS restart",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Resume ARGUS after a phone restart when Android requires user interaction");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class)
                .setAction("baby".equals(mode) ? ACTION_RESUME_BABY : ACTION_RESUME_PARENT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                77,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String label = "baby".equals(mode) ? "Child transmission" : "Parent listener";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Resume ARGUS")
                .setContentText("Phone restarted — tap to resume " + label + ".")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID, notification);
    }

    private void cancelResumeNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID);
    }
}
