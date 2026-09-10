package com.example.babymonitor;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;

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
        UpdateManager.schedule(this);
        registerUpdatePromptLifecycle();

        if (reset) {
            try { stopService(new Intent(this, SenderService.class)); } catch (Exception ignored) { }
            try { stopService(new Intent(this, ReceiverService.class)); } catch (Exception ignored) { }
        }
    }

    private void registerUpdatePromptLifecycle() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}

            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof UpdateActivity) return;
                UpdateManager.resumePending(activity);
                UpdateManager.checkAndPrompt(activity, false);
            }

            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        HebrewLocale.apply(this);
    }

    static Context context() {
        return appContext;
    }
}
