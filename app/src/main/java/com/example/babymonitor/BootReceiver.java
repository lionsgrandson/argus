package com.example.babymonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

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

        String mode = AppPrefs.mode(context);
        if (!"baby".equals(mode) && !"parent".equals(mode)) return;

        // Android 10 and older can resume the camera/microphone foreground service
        // directly from the reboot path. Android 11+ restricts camera/microphone
        // access when a service is created while the app is backgrounded, so on
        // current Android versions we preserve the desired mode and provide the
        // required user-visible resume action instead of starting a broken stream.
        if ("baby".equals(mode) && Build.VERSION.SDK_INT < 30) {
            if (tryStart(context, SenderService.class)) {
                cancelResumeNotification(context);
                return;
            }
        }

        // Parent listening has no while-in-use camera/microphone permission, so it
        // can still auto-resume from boot through Android 14. Android 15 blocks
        // BOOT_COMPLETED from launching mediaPlayback foreground services.
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
