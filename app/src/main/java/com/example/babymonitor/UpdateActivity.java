package com.example.babymonitor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class UpdateActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent settings = new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_CHECK_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(settings);
        finish();
    }
}
