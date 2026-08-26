package com.example.babymonitor;

import android.app.*;
import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_RESUME_BABY = "com.example.babymonitor.RESUME_BABY";
    static final String ACTION_RESUME_PARENT = "com.example.babymonitor.RESUME_PARENT";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        String mode = AppPrefs.mode(context);
        if (!"baby".equals(mode) && !"parent".equals(mode)) return;

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel("baby_resume", "Baby monitor restart reminders", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Tap after a phone restart to resume monitoring");
            nm.createNotificationChannel(c);
        }

        Intent open = new Intent(context, MainActivity.class)
                .setAction("baby".equals(mode) ? ACTION_RESUME_BABY : ACTION_RESUME_PARENT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 77, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String label = "baby".equals(mode) ? "baby-room microphone" : "parent listener";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, "baby_resume") : new Notification.Builder(context);
        Notification n = b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Resume Baby Monitor")
                .setContentText("Phone restarted — tap to resume the " + label)
                .setContentIntent(pi).setAutoCancel(true).build();
        nm.notify(1077, n);
    }
}
