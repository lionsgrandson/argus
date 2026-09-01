package com.example.babymonitor;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

public final class ArgusApp extends Application {
    private static volatile Context appContext;

    @Override public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        boolean reset = AppPrefs.resetForCurrentPairingEpoch(this);
        ErrorReporter.install(this);
        if (reset) {
            try { stopService(new Intent(this, SenderService.class)); } catch (Exception ignored) { }
            try { stopService(new Intent(this, ReceiverService.class)); } catch (Exception ignored) { }
        }
    }

    static Context context() {
        return appContext;
    }
}
