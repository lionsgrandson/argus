package com.example.babymonitor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class UpdateActivity extends Activity {
    private boolean checked = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(Color.WHITE);

        ProgressBar progress = new ProgressBar(this);
        root.addView(progress);

        TextView text = new TextView(this);
        text.setText("Checking for updates...");
        text.setTextSize(18);
        text.setTextColor(Color.DKGRAY);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = 24;
        root.addView(text, params);

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (UpdateManager.resumePending(this)) {
            checked = true;
            return;
        }
        if (!checked) {
            checked = true;
            UpdateManager.checkAndPrompt(this, true);
        }
    }
}
