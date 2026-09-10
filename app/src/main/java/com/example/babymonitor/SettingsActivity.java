package com.example.babymonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    public static final String EXTRA_CHECK_UPDATES = "check_updates";

    private TextView currentNameView;
    private TextView updateStatusView;
    private Button checkUpdatesButton;
    private boolean initialCheckStarted = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshName();
        if (!initialCheckStarted && getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_CHECK_UPDATES, false)) {
            initialCheckStarted = true;
            updateStatusView.setText("בודק אם קיים עדכון...");
            checkUpdatesButton.postDelayed(this::checkForUpdates, 200);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        Button back = button("חזרה");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));

        TextView title = text("הגדרות", 25, Color.rgb(25, 29, 36), Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        top.addView(title, titleParams);
        root.addView(top, matchWrap());

        TextView version = text("גרסה " + BuildConfig.VERSION_NAME, 14,
                Color.rgb(105, 110, 120), Gravity.CENTER);
        root.addView(version, spaced(dp(4), dp(6), dp(4), dp(22)));

        root.addView(sectionTitle("עדכונים"), spaced(0, 0, 0, dp(8)));

        updateStatusView = text("בדיקה אוטומטית מתבצעת כל 30 דקות", 15,
                Color.rgb(80, 86, 96), Gravity.CENTER);
        root.addView(updateStatusView, spaced(0, 0, 0, dp(10)));

        checkUpdatesButton = primaryButton("בדיקת עדכונים עכשיו");
        checkUpdatesButton.setOnClickListener(v -> checkForUpdates());
        root.addView(checkUpdatesButton, height(dp(58)));

        root.addView(sectionTitle("שם האפליקציה"), spaced(0, dp(26), 0, dp(8)));
        currentNameView = text("", 16, Color.rgb(60, 66, 76), Gravity.CENTER);
        root.addView(currentNameView, spaced(0, 0, 0, dp(10)));

        Button changeName = primaryButton("שינוי שם האפליקציה");
        changeName.setOnClickListener(v -> showNamePicker());
        root.addView(changeName, height(dp(58)));

        setContentView(scroll);
        refreshName();
    }

    private void checkForUpdates() {
        updateStatusView.setText("בודק אם קיים עדכון...");
        checkUpdatesButton.setEnabled(false);
        UpdateManager.checkAndPrompt(this, true);
        checkUpdatesButton.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                checkUpdatesButton.setEnabled(true);
                updateStatusView.setText("הבדיקה הסתיימה. אם קיים עדכון תופיע הודעה.");
            }
        }, 9000);
    }

    private void showNamePicker() {
        String[] labels = LauncherNameManager.labels();
        String current = LauncherNameManager.currentLabel(this);
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(current)) {
                checked = i;
                break;
            }
        }

        final int[] selected = new int[]{checked};
        new AlertDialog.Builder(this)
                .setTitle("שם האפליקציה")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> selected[0] = which)
                .setPositiveButton("שמירה", (dialog, which) -> {
                    String label = labels[selected[0]];
                    if (LauncherNameManager.setName(this, label)) {
                        refreshName();
                        Toast.makeText(this, "שם האפליקציה עודכן ל " + label, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "לא ניתן היה לעדכן את שם האפליקציה", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("ביטול", null)
                .show();
    }

    private void refreshName() {
        if (currentNameView != null) {
            currentNameView.setText("שם נוכחי: " + LauncherNameManager.currentLabel(this));
        }
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 19, Color.rgb(32, 37, 45), Gravity.RIGHT);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView text(String value, int size, int color, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(gravity);
        view.setPadding(dp(6), dp(6), dp(6), dp(6));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setAllCaps(false);
        return button;
    }

    private Button primaryButton(String value) {
        Button button = button(value);
        button.setTextSize(17);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams height(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams spaced(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(left, top, right, bottom);
        return params;
    }
}
