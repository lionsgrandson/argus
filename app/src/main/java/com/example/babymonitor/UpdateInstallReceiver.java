package com.example.babymonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !UpdateManager.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;

        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
        );

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirmIntent);
                } catch (Exception e) {
                    UpdateManager.showInstallFailure(context, "Android could not open the update confirmation.");
                }
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) return;

        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        UpdateManager.showInstallFailure(
                context,
                message == null || message.trim().isEmpty()
                        ? "The update could not be installed."
                        : message
        );
    }
}
