package com.example.babymonitor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Keeps the private update channel checking on a simple 30 minute cadence.
 * Android may defer inexact alarms while the phone is in Doze, so the manual
 * settings action remains the immediate check path.
 */
final class FrequentUpdateScheduler {
    private static final int UPDATE_ALARM_REQUEST = 6202;
    private static final long UPDATE_INTERVAL_MS = 30L * 60L * 1000L;

    static void schedule(Context context) {
        if (context == null) return;

        Context app = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(app, UpdateCheckReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                app,
                UPDATE_ALARM_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Replace the older four-hour updater alarm with this schedule.
        alarmManager.cancel(pendingIntent);
        alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + UPDATE_INTERVAL_MS,
                UPDATE_INTERVAL_MS,
                pendingIntent
        );
    }

    private FrequentUpdateScheduler() {}
}
