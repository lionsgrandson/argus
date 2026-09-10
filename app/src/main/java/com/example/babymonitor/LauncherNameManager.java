package com.example.babymonitor;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.LinkedHashMap;
import java.util.Map;

final class LauncherNameManager {
    private static final String PREFS = "launcher_name_v1";
    private static final String PREF_LABEL = "label";
    private static final String DEFAULT_LABEL = "Break Watch";

    private static final LinkedHashMap<String, String> ALIASES = new LinkedHashMap<>();
    private static final String[] LEGACY_ALIASES = new String[] {
            "LauncherBaby",
            "LauncherBlockBaby",
            "LauncherBabyBlock",
            "LauncherBreakBaby",
            "LauncherBabyBreak",
            "LauncherTetrisBaby",
            "LauncherBabyTetris",
            "LauncherArgusBaby",
            "LauncherBabyArgus"
    };

    static {
        ALIASES.put("Break Watch", "LauncherBreakWatch");
        ALIASES.put("ARGUS", "LauncherArgus");
    }

    static String[] labels() {
        return ALIASES.keySet().toArray(new String[0]);
    }

    static String currentLabel(Context context) {
        if (context == null) return DEFAULT_LABEL;
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_LABEL, DEFAULT_LABEL);
        return ALIASES.containsKey(saved) ? saved : DEFAULT_LABEL;
    }

    static void ensureValidName(Context context) {
        if (context == null) return;
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_LABEL, "");
        if (!ALIASES.containsKey(saved)) {
            setName(context, DEFAULT_LABEL);
        }
    }

    static boolean setName(Context context, String label) {
        if (context == null || label == null || !ALIASES.containsKey(label)) return false;

        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();
        String selectedAlias = ALIASES.get(label);
        ComponentName selected = new ComponentName(packageName, packageName + "." + selectedAlias);

        pm.setComponentEnabledSetting(
                selected,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
        );

        for (Map.Entry<String, String> entry : ALIASES.entrySet()) {
            if (entry.getKey().equals(label)) continue;
            ComponentName component = new ComponentName(packageName, packageName + "." + entry.getValue());
            pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
        }

        for (String alias : LEGACY_ALIASES) {
            try {
                ComponentName component = new ComponentName(packageName, packageName + "." + alias);
                pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
            } catch (Exception ignored) {
            }
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_LABEL, label).apply();
        return true;
    }

    private LauncherNameManager() {}
}
