package com.example.babymonitor;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;

import java.util.Locale;

final class HebrewLocale {
    private static final Locale HEBREW = new Locale("he", "IL");

    static Context wrap(Context base) {
        Locale.setDefault(HEBREW);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(HEBREW);
        config.setLayoutDirection(HEBREW);
        return base.createConfigurationContext(config);
    }

    static void apply(Context context) {
        Locale.setDefault(HEBREW);
        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocale(HEBREW);
        config.setLayoutDirection(HEBREW);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    static void forceRtl(View view) {
        if (view == null) return;
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
    }

    private HebrewLocale() {}
}
