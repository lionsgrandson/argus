package com.example.babymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UpdateCheckReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult pendingResult = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                UpdateManager.checkAndNotifyBlocking(app);
            } finally {
                pendingResult.finish();
            }
        }, "ArgusUpdateAlarm").start();
    }
}
