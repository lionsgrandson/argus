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
        String resumeAction = "baby".equals(mode) ? ACTION_RESUME_BABY : ACTION_RESUME_PARENT;
        if (RootSupport.tryLaunchResumeActivity(context, resumeAction)) {
            cancelResumeNotification(context);
            return;
        }

        if ("baby".equals(mode) && Build.VERSION.SDK_INT < 30) {
            if (tryStart(context, SenderService.class)) {
                cancelResumeNotification(context);
                return;
            }
        }

        if ("parent".equals(mode) && Build.VERSION.SDK_INT < 35) {
            if (tryStart(context, ReceiverService.class)) {
                cancelResumeNotification(context);
                return;
            }
        }

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
                    "הפעלה מחדש של ARGUS",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("חידוש הפעילות של ARGUS לאחר הפעלה מחדש של הטלפון");
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

        String label = "baby".equals(mode) ? "שידור טלפון הילד" : "האזנת טלפון ההורה";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("חידוש ARGUS")
                .setContentText("הטלפון הופעל מחדש. לחצו כדי לחדש את " + label)
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