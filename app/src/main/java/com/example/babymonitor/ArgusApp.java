package com.example.babymonitor;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;

public final class ArgusApp extends Application {
    private static volatile Context appContext;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(HebrewLocale.wrap(base));
    }

    @Override public void onCreate() {
        super.onCreate();
        HebrewLocale.apply(this);
        appContext = getApplicationContext();
        boolean reset = AppPrefs.resetForCurrentPairingEpoch(this);
        ErrorReporter.install(this);
        if (reset) {
            try { stopService(new Intent(this, SenderService.class)); } catch (Exception ignored) { }
            try { stopService(new Intent(this, ReceiverService.class)); } catch (Exception ignored) { }
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        HebrewLocale.apply(this);
    }

    static Context context() {
        return appContext;
    }
}
