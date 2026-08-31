package com.example.babymonitor;

import android.Manifest;
import android.app.Activity;
import android.content.*;
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

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

public class MainActivity extends Activity {
    private static final int REQ_BABY_PERMS = 100;
    private static final int REQ_NOTIF = 101;
    private static final String ROLE_PREF = "setup_role";

    private EditText relayUrl;
    private EditText manualPairingCode;
    private TextView babyState;
    private TextView parentState;
    private TextView batteryState;
    private TextView videoState;
    private TextView parentPairingState;
    private ImageView videoView;
    private ImageView pairingQrView;
    private LinearLayout roleChooser;
    private LinearLayout babyPanel;
    private LinearLayout parentPanel;
    private LinearLayout advancedPanel;
    private LinearLayout commonControls;
    private long displayedVideoAt = 0L;
    private boolean scanInProgress = false;

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

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        handlePairingIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();

        if (relayUrl != null) relayUrl.setText(AppPrefs.relay(this));

        String selectedRole = AppPrefs.prefs(this).getString(ROLE_PREF, "");
        if ("baby".equals(selectedRole) || "parent".equals(selectedRole)) {
            showRole(selectedRole);
        } else {
            showRoleChoice();
        }

        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);

        String action = getIntent() == null ? null : getIntent().getAction();
        if (BootReceiver.ACTION_RESUME_BABY.equals(action)) {
            getIntent().setAction(null);
            selectRole("baby", false);
            handler.postDelayed(this::startBaby, 400);
        } else if (BootReceiver.ACTION_RESUME_PARENT.equals(action)) {
            getIntent().setAction(null);
            selectRole("parent", false);
            handler.postDelayed(this::startParent, 400);
        }

        handlePairingIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingIntent(intent);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(247, 248, 250));
        scroll.addView(root);

        ImageView brandLogo = new ImageView(this);
        brandLogo.setImageResource(R.drawable.argus_icon);
        brandLogo.setAdjustViewBounds(true);
        brandLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        brandLogo.setContentDescription("ARGUS logo");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(150)
        );
        logoParams.setMargins(0, 0, 0, dp(2));
        root.addView(brandLogo, logoParams);

        TextView subtitle = text("Private live audio + camera", 14, Color.rgb(100, 106, 116), Gravity.CENTER);
        root.addView(subtitle, spaced(0, 0, 0, dp(18)));

        roleChooser = panel();
        TextView chooseTitle = text("Which phone is this?", 24, Color.rgb(28, 32, 39), Gravity.CENTER);
        chooseTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        roleChooser.addView(chooseTitle, spaced(0, 0, 0, dp(8)));

        roleChooser.addView(text(
                "Choose once. ARGUS will remember this phone.",
                15, Color.rgb(91, 97, 107), Gravity.CENTER
        ), spaced(0, 0, 0, dp(20)));

        Button childChoice = primaryButton("THIS IS THE CHILD PHONE");
        childChoice.setOnClickListener(v -> selectRole("baby", true));
        roleChooser.addView(childChoice,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));

        Button parentChoice = primaryButton("THIS IS THE PARENT PHONE");
        parentChoice.setOnClickListener(v -> selectRole("parent", true));
        roleChooser.addView(parentChoice, spacedHeight(dp(10), dp(64)));

        root.addView(roleChooser, matchWrap());

        babyPanel = panel();
        TextView childRole = roleLabel("CHILD PHONE");
        babyPanel.addView(childRole, spaced(0, 0, 0, dp(8)));

        babyPanel.addView(heading("Leave this phone near the child"), matchWrap());
        babyPanel.addView(text(
                "Camera and microphone stay active while the screen is locked.",
                14, Color.rgb(92, 99, 109), Gravity.START
        ), spaced(0, dp(4), 0, dp(4)));
        babyPanel.addView(text(
                "ARGUS keeps transmitting in the background until you stop it.",
                13, Color.rgb(92, 99, 109), Gravity.START
        ), spaced(0, 0, 0, dp(12)));

        babyState = statusText("Stopped");
        babyPanel.addView(babyState, spaced(0, 0, 0, dp(14)));

        Button startBaby = primaryButton("START TRANSMITTING");
        startBaby.setOnClickListener(v -> startBaby());
        babyPanel.addView(startBaby,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        babyPanel.addView(sectionTitle("Connect the Parent phone"), spaced(0, dp(22), 0, dp(6)));
        babyPanel.addView(text(
                "Scan the QR on the Parent phone. If QR does not work, copy or share the pairing code instead.",
                14, Color.rgb(92, 99, 109), Gravity.START
        ), spaced(0, 0, 0, dp(12)));

        pairingQrView = new ImageView(this);
        pairingQrView.setAdjustViewBounds(true);
        pairingQrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pairingQrView.setBackgroundColor(Color.WHITE);
        pairingQrView.setContentDescription("ARGUS secure pairing QR");
        LinearLayout.LayoutParams qrP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250));
        babyPanel.addView(pairingQrView, qrP);

        TextView qrHint = text(
                "QR is fastest. Pairing code works on every phone.",
                13, Color.rgb(97, 104, 114), Gravity.CENTER
        );
        babyPanel.addView(qrHint, spaced(0, dp(8), 0, dp(10)));

        Button copyCode = primaryButton("COPY PAIRING CODE");
        copyCode.setOnClickListener(v -> copyPairingCode());
        babyPanel.addView(copyCode,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        Button shareLink = button("Share pairing code");
        shareLink.setOnClickListener(v -> sharePairingLink());
        babyPanel.addView(shareLink, spacedHeight(dp(8), dp(50)));

        root.addView(babyPanel, matchWrap());

        parentPanel = panel();
        parentPanel.addView(roleLabel("PARENT PHONE"), spaced(0, 0, 0, dp(8)));
        parentPanel.addView(heading("Watch the Child phone"), matchWrap());

        parentPairingState = text(
                "Not connected yet",
                14, Color.rgb(92, 99, 109), Gravity.START
        );
        parentPanel.addView(parentPairingState, spaced(0, dp(4), 0, dp(12)));

        Button scanQr = primaryButton("SCAN CHILD QR");
        scanQr.setOnClickListener(v -> scanPairingQr());
        parentPanel.addView(scanQr,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        parentPanel.addView(text(
                "Point the camera at the QR shown on the Child phone.",
                13, Color.rgb(97, 104, 114), Gravity.START
        ), spaced(0, dp(8), 0, dp(14)));

        parentPanel.addView(sectionTitle("QR not working?"), spaced(0, dp(4), 0, dp(4)));
        parentPanel.addView(text(
                "On the Child phone tap Copy pairing code or Share pairing code, then paste the code here.",
                13, Color.rgb(97, 104, 114), Gravity.START
        ), spaced(0, 0, 0, dp(8)));

        manualPairingCode = new EditText(this);
        manualPairingCode.setHint("Paste pairing code here");
        manualPairingCode.setTextSize(14);
        manualPairingCode.setSingleLine(false);
        manualPairingCode.setMaxLines(4);
        manualPairingCode.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        parentPanel.addView(manualPairingCode, spaced(0, 0, 0, dp(8)));

        Button connectCode = primaryButton("CONNECT WITH CODE");
        connectCode.setOnClickListener(v -> connectManualCode());
        parentPanel.addView(connectCode,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        videoView = new ImageView(this);
        videoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        videoView.setBackgroundColor(Color.rgb(225, 228, 233));
        videoView.setContentDescription("Live child-room camera");
        parentPanel.addView(videoView, spacedHeight(dp(16), dp(240)));

        videoState = text("Waiting for camera…", 13, Color.rgb(92, 99, 109), Gravity.CENTER);
        parentPanel.addView(videoState, spaced(0, dp(8), 0, dp(10)));

        parentState = statusText("Stopped");
        parentPanel.addView(parentState, spaced(0, 0, 0, dp(10)));

        batteryState = text("Child battery: —", 19, Color.rgb(37, 72, 60), Gravity.CENTER);
        batteryState.setTypeface(null, android.graphics.Typeface.BOLD);
        batteryState.setPadding(dp(12), dp(14), dp(12), dp(14));
        batteryState.setBackgroundColor(Color.WHITE);
        parentPanel.addView(batteryState, spaced(0, 0, 0, dp(12)));

        Button startParent = button("Start watching");
        startParent.setOnClickListener(v -> startParent());
        parentPanel.addView(startParent,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        root.addView(parentPanel, matchWrap());

        commonControls = new LinearLayout(this);
        commonControls.setOrientation(LinearLayout.VERTICAL);

        Button stop = button("Stop ARGUS");
        stop.setOnClickListener(v -> stopEverything());
        commonControls.addView(stop, spaced(0, dp(14), 0, dp(6)));

        Button changeRole = button("Change phone type");
        changeRole.setOnClickListener(v -> changePhoneRole());
        commonControls.addView(changeRole, matchWrap());

        Button advanced = button("Advanced settings");
        commonControls.addView(advanced, spaced(0, dp(6), 0, 0));

        advancedPanel = panel();
        advancedPanel.setVisibility(View.GONE);

        relayUrl = new EditText(this);
        relayUrl.setHint("wss://relay.example/ws");
        relayUrl.setSingleLine(true);
        relayUrl.setTextSize(14);
        relayUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        relayUrl.setText(AppPrefs.relay(this));

        advancedPanel.addView(text(
                "Secure relay",
                14, Color.rgb(55, 60, 69), Gravity.START
        ), spaced(0, 0, 0, dp(4)));
        advancedPanel.addView(relayUrl, spaced(0, 0, 0, dp(8)));

        Button rotatePairing = button("Create a new pairing QR");
        rotatePairing.setOnClickListener(v -> rotatePairing());
        advancedPanel.addView(rotatePairing, spaced(0, 0, 0, dp(6)));

        Button backgroundBtn = button("Background battery settings");
        backgroundBtn.setOnClickListener(v -> openBackgroundSettings());
        advancedPanel.addView(backgroundBtn, spaced(0, 0, 0, dp(6)));

        Button settingsBtn = button("Android app settings");
        settingsBtn.setOnClickListener(v -> openAppSettings());
        advancedPanel.addView(settingsBtn, matchWrap());

        commonControls.addView(advancedPanel, spaced(0, dp(8), 0, 0));
        advanced.setOnClickListener(v -> advancedPanel.setVisibility(
                advancedPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        root.addView(commonControls, matchWrap());

        TextView footer = text(
                "End-to-end encrypted • no recordings",
                12, Color.rgb(125, 131, 140), Gravity.CENTER
        );
        root.addView(footer, spaced(0, dp(18), 0, 0));

        setContentView(scroll);
        showRoleChoice();
    }

    private void showRoleChoice() {
        roleChooser.setVisibility(View.VISIBLE);
        babyPanel.setVisibility(View.GONE);
        parentPanel.setVisibility(View.GONE);
        commonControls.setVisibility(View.GONE);
        advancedPanel.setVisibility(View.GONE);
    }

    private void selectRole(String role, boolean startImmediately) {
        AppPrefs.prefs(this).edit().putString(ROLE_PREF, role).apply();
        showRole(role);

        if (!startImmediately) return;

        if ("baby".equals(role)) {
            handler.postDelayed(this::startBaby, 150);
        } else if (AppPrefs.pairing(this) != null) {
            handler.postDelayed(this::startParent, 150);
        }
    }

    private void showRole(String role) {
        boolean baby = "baby".equals(role);
        roleChooser.setVisibility(View.GONE);
        babyPanel.setVisibility(baby ? View.VISIBLE : View.GONE);
        parentPanel.setVisibility(baby ? View.GONE : View.VISIBLE);
        commonControls.setVisibility(View.VISIBLE);

        if (baby) renderPairingQr();
        refreshState();
    }

    private PairingConfig ensurePairing() {
        PairingConfig p = AppPrefs.pairing(this);
        if (p != null) return p;

        try {
            p = PairingConfig.generate();
            AppPrefs.savePairing(this, p);
            return p;
        } catch (Exception e) {
            Toast.makeText(this, "Could not create secure pairing", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void renderPairingQr() {
        if (pairingQrView == null) return;
        PairingConfig p = ensurePairing();
        if (p == null) {
            pairingQrView.setImageDrawable(null);
            return;
        }

        try {
            String payload = pairingLink(p);
            int px = 720;
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, px, px);
            Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);

            int[] pixels = new int[px * px];
            for (int y = 0; y < px; y++) {
                int offset = y * px;
                for (int x = 0; x < px; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            bitmap.setPixels(pixels, 0, px, 0, 0, px, px);
            pairingQrView.setImageBitmap(bitmap);
        } catch (Exception e) {
            pairingQrView.setImageDrawable(null);
            Toast.makeText(this, "Could not create pairing QR", Toast.LENGTH_LONG).show();
        }
    }

    private String pairingLink(PairingConfig p) {
        return new Uri.Builder()
                .scheme("argus")
                .authority("pair")
                .appendQueryParameter("code", p.encode())
                .build()
                .toString();
    }

    private void copyPairingCode() {
        PairingConfig p = ensurePairing();
        if (p == null) return;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Could not access clipboard", Toast.LENGTH_LONG).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText("ARGUS pairing code", p.encode()));
        Toast.makeText(this, "Pairing code copied", Toast.LENGTH_SHORT).show();
    }

    private void sharePairingLink() {
        PairingConfig p = ensurePairing();
        if (p == null) return;

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(
                Intent.EXTRA_TEXT,
                "ARGUS pairing code:\n" + p.encode()
                        + "\n\nPaste this code into ARGUS on the Parent phone."
        );
        startActivity(Intent.createChooser(send, "Send ARGUS pairing code"));
    }

    private void connectManualCode() {
        if (manualPairingCode == null) return;

        String value = manualPairingCode.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "Paste the Child phone pairing code first", Toast.LENGTH_LONG).show();
            return;
        }

        acceptPairingPayload(value, true);
    }

    private void scanPairingQr() {
        if (scanInProgress) return;
        scanInProgress = true;

        try {
            GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build();
            GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);

            scanner.startScan()
                    .addOnSuccessListener(barcode -> {
                        scanInProgress = false;
                        String rawValue = barcode.getRawValue();
                        if (rawValue == null || rawValue.trim().isEmpty()) {
                            Toast.makeText(this, "No QR data was found", Toast.LENGTH_LONG).show();
                            return;
                        }
                        acceptPairingPayload(rawValue, true);
                    })
                    .addOnCanceledListener(() -> scanInProgress = false)
                    .addOnFailureListener(e -> {
                        scanInProgress = false;
                        Toast.makeText(
                                this,
                                "Could not open the QR scanner. Use the pairing code below instead.",
                                Toast.LENGTH_LONG
                        ).show();
                    });
        } catch (RuntimeException e) {
            scanInProgress = false;
            Toast.makeText(
                    this,
                    "Could not open the QR scanner. Use the pairing code below instead.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void handlePairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;

        Uri data = intent.getData();
        if (!"argus".equalsIgnoreCase(data.getScheme())
                || !"pair".equalsIgnoreCase(data.getHost())) {
            return;
        }

        String code = data.getQueryParameter("code");
        if (code == null || code.trim().isEmpty()) return;

        acceptPairingPayload(code, true);
        intent.setData(null);
    }

    private void acceptPairingPayload(String payload, boolean autoStart) {
        String code = payload == null ? "" : payload.trim();

        try {
            int argusIndex = code.indexOf("argus://");
            if (argusIndex >= 0) {
                String link = code.substring(argusIndex).trim();
                int whitespace = firstWhitespace(link);
                if (whitespace > 0) link = link.substring(0, whitespace);

                Uri uri = Uri.parse(link);
                if (!"argus".equalsIgnoreCase(uri.getScheme())
                        || !"pair".equalsIgnoreCase(uri.getHost())) {
                    throw new IllegalArgumentException("Wrong ARGUS QR type");
                }
                code = uri.getQueryParameter("code");
            } else {
                String marker = "ARGUS pairing code:";
                int markerIndex = code.indexOf(marker);
                if (markerIndex >= 0) {
                    code = code.substring(markerIndex + marker.length()).trim();
                    int whitespace = firstWhitespace(code);
                    if (whitespace > 0) code = code.substring(0, whitespace);
                }
            }

            if (code == null || code.trim().isEmpty()) {
                throw new IllegalArgumentException("Empty code");
            }

            PairingConfig p = PairingConfig.parse(code.trim());
            AppPrefs.savePairing(this, p);
            AppPrefs.prefs(this).edit().putString(ROLE_PREF, "parent").apply();
            showRole("parent");

            if (manualPairingCode != null) manualPairingCode.setText("");

            Toast.makeText(this, "Connected to the Child phone", Toast.LENGTH_SHORT).show();

            if (autoStart) handler.postDelayed(this::startParent, 400);
        } catch (Exception e) {
            Toast.makeText(this, "That is not a valid ARGUS pairing code", Toast.LENGTH_LONG).show();
        }
    }

    private int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return i;
        }
        return -1;
    }

    private void rotatePairing() {
        try {
            PairingConfig p = PairingConfig.generate();
            AppPrefs.savePairing(this, p);
            renderPairingQr();
            Toast.makeText(this, "New pairing QR created", Toast.LENGTH_SHORT).show();

            if ("baby".equals(AppPrefs.mode(this))) {
                stopService(new Intent(this, SenderService.class));
                handler.postDelayed(this::startBaby, 250);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not create a new pairing QR", Toast.LENGTH_LONG).show();
        }
    }

    private void startBaby() {
        saveRelay();
        if (!validRelay()) return;
        if (ensurePairing() == null) return;
        renderPairingQr();

        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!mic || !camera) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA},
                    REQ_BABY_PERMS
            );
            return;
        }

        stopService(new Intent(this, ReceiverService.class));
        LiveVideoStore.clear();
        AppPrefs.setMode(this, "baby");
        AppPrefs.prefs(this).edit().putString(ROLE_PREF, "baby").apply();

        Intent i = new Intent(this, SenderService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);

        Toast.makeText(
                this,
                "ARGUS is transmitting. You can lock this phone.",
                Toast.LENGTH_LONG
        ).show();
        refreshState();
    }

    private void startParent() {
        saveRelay();
        if (!validRelay()) return;

        if (AppPrefs.pairing(this) == null) {
            Toast.makeText(this, "Scan the Child QR or enter its pairing code first", Toast.LENGTH_LONG).show();
            return;
        }

        stopService(new Intent(this, SenderService.class));
        LiveVideoStore.clear();
        displayedVideoAt = 0L;
        AppPrefs.setMode(this, "parent");
        AppPrefs.prefs(this).edit().putString(ROLE_PREF, "parent").apply();

        try {
            Intent i = new Intent(this, ReceiverService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            refreshState();
        } catch (RuntimeException e) {
            AppPrefs.setMode(this, "none");
            AppPrefs.state(this, "parent", "Could not start parent listener");
            Toast.makeText(
                    this,
                    "Connected, but Android could not start monitoring. Tap Start watching to retry.",
                    Toast.LENGTH_LONG
            ).show();
            refreshState();
        }
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

        if (videoView != null) videoView.setImageDrawable(null);

        AppPrefs.setMode(this, "none");
        Toast.makeText(this, "ARGUS stopped", Toast.LENGTH_SHORT).show();
        refreshState();
    }

    private void changePhoneRole() {
        stopService(new Intent(this, SenderService.class));
        stopService(new Intent(this, ReceiverService.class));
        LiveVideoStore.clear();
        displayedVideoAt = 0L;
        AppPrefs.setMode(this, "none");
        AppPrefs.prefs(this).edit().remove(ROLE_PREF).apply();
        showRoleChoice();
    }

    private void openBackgroundSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException e) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        Intent i = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(i);
    }

    private void refreshState() {
        if (babyState == null || parentState == null || batteryState == null) return;

        String baby = AppPrefs.getState(this, "baby", "Stopped");
        String parent = AppPrefs.getState(this, "parent", "Stopped");
        babyState.setText(baby);
        parentState.setText(parent);

        boolean paired = AppPrefs.pairing(this) != null;
        if (parentPairingState != null) {
            parentPairingState.setText(
                    paired
                            ? "Paired with Child phone"
                            : "Not connected yet — scan QR or enter pairing code"
            );
            parentPairingState.setTextColor(
                    paired ? Color.rgb(37, 105, 75) : Color.rgb(92, 99, 109)
            );
        }

        int pct = AppPrefs.prefs(this).getInt("baby_battery", -1);
        boolean charging = AppPrefs.prefs(this).getBoolean("baby_charging", false);
        long at = AppPrefs.prefs(this).getLong("baby_battery_at", 0L);

        if (pct >= 0 && at > 0) {
            batteryState.setText(
                    "Child battery: " + pct + "%" + (charging ? " • charging" : "")
            );
            batteryState.setTextColor(
                    pct <= 20 ? Color.rgb(177, 53, 53) : Color.rgb(37, 72, 60)
            );
        } else {
            batteryState.setText("Child battery: —");
            batteryState.setTextColor(Color.rgb(92, 99, 109));
        }

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
            videoState.setText("LIVE");
            videoState.setTextColor(Color.rgb(37, 105, 75));
        } else if ("parent".equals(AppPrefs.mode(this))) {
            videoState.setText("Waiting for camera…");
            videoState.setTextColor(Color.rgb(92, 99, 109));
        } else {
            videoState.setText("Camera stopped");
            videoState.setTextColor(Color.rgb(92, 99, 109));
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
        TextView t = text(s, 20, Color.rgb(32, 36, 43), Gravity.START);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 17, Color.rgb(32, 36, 43), Gravity.START);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView roleLabel(String s) {
        TextView t = text(s, 13, Color.WHITE, Gravity.CENTER);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setBackgroundColor(Color.rgb(33, 46, 74));
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
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams spaced(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams spacedHeight(int top, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
        p.setMargins(0, top, 0, 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_BABY_PERMS) {
            boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            boolean camera = checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;

            if (mic && camera) {
                startBaby();
            } else {
                Toast.makeText(
                        this,
                        "Camera and microphone permissions are required on the Child phone",
                        Toast.LENGTH_LONG
                ).show();
            }
        }
    }
}
