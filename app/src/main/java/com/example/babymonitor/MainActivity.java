package com.example.babymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.ClipDescription;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    private static final int REQ_BABY_PERMS = 100;
    private static final int REQ_NOTIF = 101;

    private EditText relayUrl;
    private EditText parentCode;
    private TextView babyCode;
    private TextView babyState;
    private TextView parentState;
    private TextView batteryState;
    private TextView videoState;
    private ImageView videoView;
    private LinearLayout babyPanel;
    private LinearLayout parentPanel;
    private LinearLayout advancedPanel;
    private Button babyTab;
    private Button parentTab;
    private long displayedVideoAt = 0L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshState();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        relayUrl.setText(AppPrefs.relay(this));
        updateBabyCode();
        String mode = AppPrefs.mode(this);
        showRole("parent".equals(mode) ? "parent" : "baby");
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);

        String action = getIntent() == null ? null : getIntent().getAction();
        if (BootReceiver.ACTION_RESUME_BABY.equals(action)) {
            getIntent().setAction(null);
            showRole("baby");
            handler.postDelayed(this::startBaby, 500);
        } else if (BootReceiver.ACTION_RESUME_PARENT.equals(action)) {
            getIntent().setAction(null);
            showRole("parent");
            handler.postDelayed(this::startParent, 500);
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(247, 248, 250));
        scroll.addView(root);

        TextView title = text("ARGUS", 28, Color.rgb(24, 27, 32), Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView subtitle = text("Encrypted live audio + camera", 14, Color.rgb(100, 106, 116), Gravity.CENTER);
        LinearLayout.LayoutParams subtitleP = matchWrap();
        subtitleP.setMargins(0, dp(5), 0, dp(22));
        root.addView(subtitle, subtitleP);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        babyTab = button("Baby phone");
        parentTab = button("Parent phone");
        babyTab.setOnClickListener(v -> showRole("baby"));
        parentTab.setOnClickListener(v -> showRole("parent"));
        tabs.addView(babyTab, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams parentTabP = new LinearLayout.LayoutParams(0, dp(48), 1f);
        parentTabP.setMargins(dp(8), 0, 0, 0);
        tabs.addView(parentTab, parentTabP);
        LinearLayout.LayoutParams tabsP = matchWrap();
        tabsP.setMargins(0, 0, 0, dp(18));
        root.addView(tabs, tabsP);

        babyPanel = panel();
        babyPanel.addView(heading("Baby phone"), matchWrap());
        babyPanel.addView(text("Camera and microphone keep running when this phone is locked.", 14,
                Color.rgb(92, 99, 109), Gravity.START), spaced(0, dp(6), 0, dp(14)));
        babyState = statusText("Stopped");
        babyPanel.addView(babyState, spaced(0, 0, 0, dp(14)));

        babyCode = text("No pairing code yet", 13, Color.rgb(66, 72, 82), Gravity.START);
        babyCode.setPadding(dp(12), dp(12), dp(12), dp(12));
        babyCode.setBackgroundColor(Color.WHITE);
        babyCode.setTextIsSelectable(false);
        babyPanel.addView(babyCode, spaced(0, 0, 0, dp(8)));

        LinearLayout pairingButtons = new LinearLayout(this);
        pairingButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("Copy code");
        Button generate = button("New code");
        copy.setOnClickListener(v -> copyPairing());
        generate.setOnClickListener(v -> generatePairing());
        pairingButtons.addView(copy, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams newP = new LinearLayout.LayoutParams(0, dp(46), 1f);
        newP.setMargins(dp(8), 0, 0, 0);
        pairingButtons.addView(generate, newP);
        babyPanel.addView(pairingButtons, spaced(0, 0, 0, dp(14)));

        Button startBaby = primaryButton("Start baby monitor");
        startBaby.setOnClickListener(v -> startBaby());
        babyPanel.addView(startBaby, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        root.addView(babyPanel, matchWrap());

        parentPanel = panel();
        parentPanel.addView(heading("Parent phone"), matchWrap());

        videoView = new ImageView(this);
        videoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        videoView.setBackgroundColor(Color.rgb(225, 228, 233));
        videoView.setContentDescription("Live baby-room camera");
        parentPanel.addView(videoView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));

        videoState = text("Waiting for camera…", 13, Color.rgb(92, 99, 109), Gravity.CENTER);
        parentPanel.addView(videoState, spaced(0, dp(8), 0, dp(10)));
        parentState = statusText("Stopped");
        parentPanel.addView(parentState, spaced(0, 0, 0, dp(5)));
        batteryState = text("Baby battery: —", 13, Color.rgb(92, 99, 109), Gravity.START);
        parentPanel.addView(batteryState, spaced(0, 0, 0, dp(14)));

        parentCode = new EditText(this);
        parentCode.setHint("Paste pairing code");
        parentCode.setSingleLine(false);
        parentCode.setMinLines(2);
        parentCode.setTextSize(14);
        parentPanel.addView(parentCode, spaced(0, 0, 0, dp(12)));

        Button startParent = primaryButton("Start watching");
        startParent.setOnClickListener(v -> startParent());
        parentPanel.addView(startParent, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        root.addView(parentPanel, matchWrap());

        Button stop = button("Stop monitoring on this phone");
        stop.setOnClickListener(v -> stopEverything());
        root.addView(stop, spaced(0, dp(14), 0, dp(8)));

        Button advanced = button("Advanced settings");
        root.addView(advanced, matchWrap());

        advancedPanel = panel();
        advancedPanel.setVisibility(View.GONE);
        relayUrl = new EditText(this);
        relayUrl.setHint("wss://relay.example/ws");
        relayUrl.setSingleLine(true);
        relayUrl.setTextSize(14);
        relayUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        relayUrl.setText(AppPrefs.relay(this));
        advancedPanel.addView(text("Secure relay", 14, Color.rgb(55, 60, 69), Gravity.START), spaced(0, 0, 0, dp(4)));
        advancedPanel.addView(relayUrl, spaced(0, 0, 0, dp(8)));
        Button settingsBtn = button("Android app settings");
        settingsBtn.setOnClickListener(v -> openAppSettings());
        advancedPanel.addView(settingsBtn, matchWrap());
        root.addView(advancedPanel, spaced(0, dp(8), 0, 0));
        advanced.setOnClickListener(v -> advancedPanel.setVisibility(
                advancedPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        TextView footer = text("End-to-end encrypted • no recordings", 12, Color.rgb(125, 131, 140), Gravity.CENTER);
        root.addView(footer, spaced(0, dp(18), 0, 0));

        setContentView(scroll);
        showRole("baby");
    }

    private void showRole(String role) {
        boolean baby = "baby".equals(role);
        babyPanel.setVisibility(baby ? View.VISIBLE : View.GONE);
        parentPanel.setVisibility(baby ? View.GONE : View.VISIBLE);
        babyTab.setEnabled(!baby);
        parentTab.setEnabled(baby);
    }

    private void generatePairing() {
        saveRelay();
        try {
            PairingConfig p = PairingConfig.generate();
            AppPrefs.savePairing(this, p);
            updateBabyCode();
            Toast.makeText(this, "New secure pairing code created", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not securely store pairing code", Toast.LENGTH_LONG).show();
        }
    }

    private void updateBabyCode() {
        PairingConfig p = AppPrefs.pairing(this);
        if (p == null) {
            babyCode.setText("No pairing code yet");
            return;
        }
        String code = p.encode();
        String preview = code.length() > 28 ? code.substring(0, 14) + "…" + code.substring(code.length() - 10) : code;
        babyCode.setText("Pairing code: " + preview);
    }

    private void copyPairing() {
        PairingConfig p = AppPrefs.pairing(this);
        if (p == null) {
            generatePairing();
            p = AppPrefs.pairing(this);
            if (p == null) return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("ARGUS pairing code", p.encode());
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "Pairing code copied", Toast.LENGTH_SHORT).show();
    }

    private void startBaby() {
        saveRelay();
        if (!validRelay()) return;
        if (AppPrefs.pairing(this) == null) generatePairing();
        if (AppPrefs.pairing(this) == null) return;

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!mic || !camera) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQ_BABY_PERMS);
            return;
        }

        stopService(new Intent(this, ReceiverService.class));
        LiveVideoStore.clear();
        AppPrefs.setMode(this, "baby");
        Intent i = new Intent(this, SenderService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Monitoring started. You can lock this phone.", Toast.LENGTH_LONG).show();
        refreshState();
    }

    private void startParent() {
        saveRelay();
        if (!validRelay()) return;
        String code = parentCode.getText().toString().trim();
        if (!code.isEmpty()) {
            try {
                AppPrefs.savePairing(this, PairingConfig.parse(code));
                parentCode.setText("");
            } catch (Exception e) {
                Toast.makeText(this, "Invalid pairing code", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (AppPrefs.pairing(this) == null) {
            Toast.makeText(this, "Paste the Baby phone pairing code", Toast.LENGTH_LONG).show();
            return;
        }

        stopService(new Intent(this, SenderService.class));
        LiveVideoStore.clear();
        displayedVideoAt = 0L;
        AppPrefs.setMode(this, "parent");
        Intent i = new Intent(this, ReceiverService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, "Parent monitor started", Toast.LENGTH_SHORT).show();
        refreshState();
    }

    private void saveRelay() {
        if (relayUrl != null) AppPrefs.saveRelay(this, relayUrl.getText().toString());
    }

    private boolean validRelay() {
        String r = AppPrefs.relay(this);
        if (!r.startsWith("wss://")) {
            Toast.makeText(this, "Relay URL must start with wss://", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void stopEverything() {
        stopService(new Intent(this, SenderService.class));
        stopService(new Intent(this, ReceiverService.class));
        LiveVideoStore.clear();
        displayedVideoAt = 0L;
        videoView.setImageDrawable(null);
        AppPrefs.setMode(this, "none");
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show();
        refreshState();
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void refreshState() {
        String baby = AppPrefs.getState(this, "baby", "Stopped");
        String parent = AppPrefs.getState(this, "parent", "Stopped");
        babyState.setText(baby);
        parentState.setText(parent);

        int pct = AppPrefs.prefs(this).getInt("baby_battery", -1);
        boolean charging = AppPrefs.prefs(this).getBoolean("baby_charging", false);
        long at = AppPrefs.prefs(this).getLong("baby_battery_at", 0L);
        batteryState.setText(pct >= 0 && at > 0
                ? "Baby battery: " + pct + "%" + (charging ? " • charging" : "")
                : "Baby battery: —");

        LiveVideoStore.Frame frame = LiveVideoStore.latest();
        long now = System.currentTimeMillis();
        if (frame != null && frame.receivedAt > displayedVideoAt) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length);
            if (bitmap != null) {
                videoView.setImageBitmap(bitmap);
                displayedVideoAt = frame.receivedAt;
            }
        }
        if (frame != null && now - frame.receivedAt < 3000) {
            videoState.setText("Live camera");
        } else if ("parent".equals(AppPrefs.mode(this))) {
            videoState.setText("Waiting for camera…");
        } else {
            videoState.setText("Camera stopped");
        }
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(16), dp(16), dp(16));
        p.setBackgroundColor(Color.rgb(238, 240, 244));
        return p;
    }

    private TextView heading(String s) {
        TextView t = text(s, 19, Color.rgb(32, 36, 43), Gravity.START);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView statusText(String s) {
        TextView t = text(s, 15, Color.rgb(38, 86, 70), Gravity.START);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView text(String s, float size, int color, int gravity) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(gravity);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        return b;
    }

    private Button primaryButton(String s) {
        Button b = button(s);
        b.setTextSize(16);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spaced(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BABY_PERMS) {
            boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (mic && camera) startBaby();
            else Toast.makeText(this, "Camera and microphone permissions are required for the Baby phone", Toast.LENGTH_LONG).show();
        }
    }
}