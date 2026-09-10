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

    private static final LinkedHashMap<String, String> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("ARGUS", "LauncherArgus");
        ALIASES.put("Baby", "LauncherBaby");
        ALIASES.put("Block Baby", "LauncherBlockBaby");
        ALIASES.put("Baby Block", "LauncherBabyBlock");
        ALIASES.put("Break Baby", "LauncherBreakBaby");
        ALIASES.put("Baby Break", "LauncherBabyBreak");
        ALIASES.put("Tetris Baby", "LauncherTetrisBaby");
        ALIASES.put("Baby Tetris", "LauncherBabyTetris");
        ALIASES.put("ARGUS Baby", "LauncherArgusBaby");
        ALIASES.put("Baby ARGUS", "LauncherBabyArgus");
    }

    static String[] labels() {
        return ALIASES.keySet().toArray(new String[0]);
    }

    static String currentLabel(Context context) {
        if (context == null) return "Block Baby";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_LABEL, "Block Baby");
    }

    static boolean setName(Context context, String label) {
        if (context == null || label == null || !ALIASES.containsKey(label)) return false;

        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();

        for (Map.Entry<String, String> entry : ALIASES.entrySet()) {
            ComponentName component = new ComponentName(packageName, packageName + "." + entry.getValue());
            int state = entry.getKey().equals(label)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP);
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_LABEL, label).apply();
        return true;
    }

    private LauncherNameManager() {}
}
