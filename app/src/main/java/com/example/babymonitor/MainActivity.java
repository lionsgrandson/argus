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
    private static final int REQ_CHILD_PERMS = 100;
    private static final int REQ_NOTIF = 101;
    private static final String ROLE_PREF = "setup_role";

    private LinearLayout roleChooser;
    private LinearLayout setupPanel;
    private LinearLayout pairingControls;
    private LinearLayout permissionList;
    private LinearLayout livePanel;
    private TextView setupRoleTitle;
    private TextView setupStatus;
    private TextView pairingCodeView;
    private TextView liveConnection;
    private TextView liveMainText;
    private TextView batteryState;
    private TextView audioOnlyState;
    private ImageView pairingQrView;
    private ImageView videoView;
    private Switch cameraSwitch;
    private Switch micSwitch;
    private Button liveStartStop;
    private long displayedVideoAt = 0L;
    private boolean scanInProgress = false;
    private boolean updatingSwitches = false;
    private String renderedSetupRole = "";

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
        handlePairingIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        showCurrentScreen();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);

        String action = getIntent() == null ? null : getIntent().getAction();
        if (BootReceiver.ACTION_RESUME_BABY.equals(action)) {
            getIntent().setAction(null);
            AppPrefs.prefs(this).edit().putString(ROLE_PREF, "baby").apply();
            renderedSetupRole = "";
            showCurrentScreen();
        } else if (BootReceiver.ACTION_RESUME_PARENT.equals(action)) {
            getIntent().setAction(null);
            AppPrefs.prefs(this).edit().putString(ROLE_PREF, "parent").apply();
            renderedSetupRole = "";
            showCurrentScreen();
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
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.argus_icon);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo, height(dp(110)));

        TextView title = text("ARGUS", 26, Color.rgb(25, 29, 36), Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, spaced(0, 0, 0, dp(18)));

        buildRoleChooser(root);
        buildSetupPanel(root);
        buildLivePanel(root);

        setContentView(scroll);
        showCurrentScreen();
    }

    private void buildRoleChooser(LinearLayout root) {
        roleChooser = panel();
        TextView question = text("איזה טלפון זה?", 22, Color.rgb(28, 32, 39), Gravity.CENTER);
        question.setTypeface(null, android.graphics.Typeface.BOLD);
        roleChooser.addView(question, spaced(0, 0, 0, dp(18)));

        Button parent = primaryButton("טלפון הורה");
        parent.setOnClickListener(v -> selectRole("parent"));
        roleChooser.addView(parent, height(dp(64)));

        Button child = primaryButton("טלפון ילד");
        child.setOnClickListener(v -> selectRole("baby"));
        roleChooser.addView(child, spacedHeight(dp(10), dp(64)));

        root.addView(roleChooser, matchWrap());
    }

    private void buildSetupPanel(LinearLayout root) {
        setupPanel = panel();
        setupRoleTitle = heading("הגדרה");
        setupPanel.addView(setupRoleTitle, spaced(0, 0, 0, dp(8)));

        setupStatus = text("לא מחובר", 15, Color.rgb(170, 50, 50), Gravity.CENTER);
        setupStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        setupPanel.addView(setupStatus, spaced(0, 0, 0, dp(16)));

        pairingControls = new LinearLayout(this);
        pairingControls.setOrientation(LinearLayout.VERTICAL);
        setupPanel.addView(pairingControls, matchWrap());

        setupPanel.addView(sectionTitle("הרשאות"), spaced(0, dp(20), 0, dp(8)));
        permissionList = new LinearLayout(this);
        permissionList.setOrientation(LinearLayout.VERTICAL);
        setupPanel.addView(permissionList, matchWrap());

        Button changeRole = button("שינוי סוג הטלפון");
        changeRole.setOnClickListener(v -> changePhoneRole());
        setupPanel.addView(changeRole, spaced(0, dp(20), 0, 0));

        root.addView(setupPanel, matchWrap());
    }

    private void buildLivePanel(LinearLayout root) {
        livePanel = new LinearLayout(this);
        livePanel.setOrientation(LinearLayout.VERTICAL);
        livePanel.setPadding(dp(8), dp(8), dp(8), dp(12));
        livePanel.setBackgroundColor(Color.WHITE);

        liveConnection = text("לא מחובר", 16, Color.WHITE, Gravity.CENTER);
        liveConnection.setTypeface(null, android.graphics.Typeface.BOLD);
        liveConnection.setPadding(dp(12), dp(10), dp(12), dp(10));
        livePanel.addView(liveConnection, spaced(0, 0, 0, dp(18)));

        liveMainText = text("משדר", 28, Color.rgb(28, 32, 39), Gravity.CENTER);
        liveMainText.setTypeface(null, android.graphics.Typeface.BOLD);
        livePanel.addView(liveMainText, spaced(0, dp(12), 0, dp(20)));

        LinearLayout mediaControls = new LinearLayout(this);
        mediaControls.setOrientation(LinearLayout.VERTICAL);
        mediaControls.setPadding(dp(12), dp(12), dp(12), dp(12));
        mediaControls.setBackgroundColor(Color.rgb(245, 246, 248));

        cameraSwitch = new Switch(this);
        cameraSwitch.setText("מצלמה");
        cameraSwitch.setTextSize(17);
        mediaControls.addView(cameraSwitch, matchWrap());

        micSwitch = new Switch(this);
        micSwitch.setText("מיקרופון");
        micSwitch.setTextSize(17);
        mediaControls.addView(micSwitch, spaced(0, dp(8), 0, 0));

        cameraSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updatingSwitches) updateParentMedia();
        });
        micSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!updatingSwitches) updateParentMedia();
        });

        livePanel.addView(mediaControls, spaced(0, 0, 0, dp(14)));

        videoView = new ImageView(this);
        videoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        videoView.setBackgroundColor(Color.rgb(230, 232, 235));
        videoView.setContentDescription("מצלמת טלפון הילד בשידור חי");
        livePanel.addView(videoView, height(dp(340)));

        audioOnlyState = text("מיקרופון פעיל", 24, Color.rgb(35, 90, 67), Gravity.CENTER);
        audioOnlyState.setTypeface(null, android.graphics.Typeface.BOLD);
        audioOnlyState.setPadding(dp(12), dp(90), dp(12), dp(90));
        audioOnlyState.setBackgroundColor(Color.rgb(246, 247, 249));
        livePanel.addView(audioOnlyState, matchWrap());

        batteryState = text("סוללת הילד: --", 19, Color.rgb(37, 72, 60), Gravity.CENTER);
        batteryState.setTypeface(null, android.graphics.Typeface.BOLD);
        batteryState.setPadding(dp(12), dp(14), dp(12), dp(14));
        livePanel.addView(batteryState, spaced(0, dp(12), 0, dp(12)));

        liveStartStop = primaryButton("עצור");
        liveStartStop.setOnClickListener(v -> toggleLive());
        livePanel.addView(liveStartStop, height(dp(58)));

        Button changeRole = button("שינוי סוג הטלפון");
        changeRole.setOnClickListener(v -> changePhoneRole());
        livePanel.addView(changeRole, spaced(0, dp(12), 0, 0));

        root.addView(livePanel, matchWrap());
    }

    private void selectRole(String role) {
        AppPrefs.prefs(this).edit().putString(ROLE_PREF, role).apply();
        AppPrefs.setPairConfirmed(this, false);
        AppPrefs.setPeerOnline(this, "baby", false);
        AppPrefs.setPeerOnline(this, "parent", false);
        renderedSetupRole = "";

        if ("baby".equals(role)) {
            ensurePairing();
            showCurrentScreen();
            handler.postDelayed(this::startBaby, 250);
        } else {
            showCurrentScreen();
        }
    }

    private String selectedRole() {
        return AppPrefs.prefs(this).getString(ROLE_PREF, "");
    }

    private void showCurrentScreen() {
        String role = selectedRole();
        if (!"baby".equals(role) && !"parent".equals(role)) {
            roleChooser.setVisibility(View.VISIBLE);
            setupPanel.setVisibility(View.GONE);
            livePanel.setVisibility(View.GONE);
            return;
        }

        boolean paired = isPairedForUi(role);
        boolean needsSetupRender = !paired
                && (setupPanel.getVisibility() != View.VISIBLE || !role.equals(renderedSetupRole));

        roleChooser.setVisibility(View.GONE);
        setupPanel.setVisibility(paired ? View.GONE : View.VISIBLE);
        livePanel.setVisibility(paired ? View.VISIBLE : View.GONE);

        if (needsSetupRender) {
            renderSetup(role);
            renderedSetupRole = role;
        }
        if (paired) renderLive(role);
    }

    private boolean isPairedForUi(String role) {
        return AppPrefs.pairing(this) != null && AppPrefs.pairConfirmed(this);
    }

    private void renderSetup(String role) {
        boolean child = "baby".equals(role);
        setupRoleTitle.setText(child ? "טלפון ילד" : "טלפון הורה");
        updateSetupStatus(role);

        pairingControls.removeAllViews();
        pairingCodeView = null;
        if (child) {
            pairingControls.addView(text("סרקו את קוד ה QR הזה בטלפון ההורה", 16,
                    Color.rgb(55, 60, 69), Gravity.CENTER), spaced(0, 0, 0, dp(10)));

            pairingQrView = new ImageView(this);
            pairingQrView.setAdjustViewBounds(true);
            pairingQrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pairingQrView.setBackgroundColor(Color.WHITE);
            pairingQrView.setContentDescription("קוד QR לחיבור ARGUS");
            pairingControls.addView(pairingQrView, height(dp(260)));

            pairingControls.addView(text("או שלחו את הקוד הזה להורה", 15,
                    Color.rgb(55, 60, 69), Gravity.CENTER), spaced(0, dp(12), 0, dp(6)));

            pairingCodeView = text("", 22, Color.rgb(28, 32, 39), Gravity.CENTER);
            pairingCodeView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            pairingCodeView.setTextDirection(View.TEXT_DIRECTION_LTR);
            pairingCodeView.setTextIsSelectable(true);
            pairingCodeView.setPadding(dp(10), dp(12), dp(10), dp(12));
            pairingCodeView.setBackgroundColor(Color.WHITE);
            pairingControls.addView(pairingCodeView, matchWrap());

            Button copyCode = primaryButton("העתקת קוד");
            copyCode.setOnClickListener(v -> copyPairingCode());
            pairingControls.addView(copyCode, spacedHeight(dp(8), dp(56)));

            Button refreshQr = button("רענון קוד לחיבור");
            refreshQr.setOnClickListener(v -> rotatePairing());
            pairingControls.addView(refreshQr, spaced(0, dp(8), 0, 0));
            renderPairingQr();
        } else {
            pairingControls.addView(text("סרקו את קוד ה QR שמופיע בטלפון הילד", 16,
                    Color.rgb(55, 60, 69), Gravity.CENTER), spaced(0, 0, 0, dp(12)));
            Button scan = primaryButton("סריקת קוד QR");
            scan.setOnClickListener(v -> scanPairingQr());
            pairingControls.addView(scan, height(dp(60)));

            pairingControls.addView(text("או", 15, Color.rgb(92, 99, 109), Gravity.CENTER),
                    spaced(0, dp(12), 0, dp(6)));

            EditText manualCode = new EditText(this);
            manualCode.setHint("הדביקו את קוד החיבור");
            manualCode.setSingleLine(true);
            manualCode.setTextSize(18);
            manualCode.setGravity(Gravity.CENTER);
            manualCode.setTextDirection(View.TEXT_DIRECTION_LTR);
            manualCode.setTypeface(android.graphics.Typeface.MONOSPACE);
            pairingControls.addView(manualCode, height(dp(58)));

            Button connectCode = primaryButton("חיבור עם קוד");
            connectCode.setOnClickListener(v -> {
                String code = manualCode.getText().toString().trim();
                if (code.isEmpty()) {
                    Toast.makeText(this, "הדביקו קודם את קוד החיבור", Toast.LENGTH_SHORT).show();
                    return;
                }
                acceptPairingPayload(code);
            });
            pairingControls.addView(connectCode, spacedHeight(dp(8), dp(58)));
        }

        renderPermissions(role);
    }

    private void updateSetupStatus(String role) {
        boolean online = AppPrefs.peerOnline(this, role);
        String status;
        if (online) {
            status = "מחובר";
        } else if ("parent".equals(role) && AppPrefs.pairing(this) == null) {
            status = "לא מחובר";
        } else {
            String fallback = "baby".equals(role) ? "ממתין לטלפון ההורה" : "מתחבר לטלפון הילד";
            status = AppPrefs.getState(this, role, fallback);
        }
        setupStatus.setText(status);
        setupStatus.setTextColor(online ? Color.rgb(39, 145, 84) : Color.rgb(164, 105, 12));
    }

    private void renderPermissions(String role) {
        permissionList.removeAllViews();
        if ("baby".equals(role)) {
            addPermissionRow("מצלמה", hasPermission(Manifest.permission.CAMERA),
                    () -> requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CHILD_PERMS));
            addPermissionRow("מיקרופון", hasPermission(Manifest.permission.RECORD_AUDIO),
                    () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CHILD_PERMS));
        }

        if (Build.VERSION.SDK_INT >= 33) {
            addPermissionRow("התראות", hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                    () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        }

        addPermissionRow("פעילות ברקע", batteryAccessActive(), this::openBatterySettings);
    }

    private void addPermissionRow(String label, boolean active, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackgroundColor(active ? Color.rgb(235, 247, 239) : Color.rgb(252, 239, 239));

        TextView name = text(label, 15, Color.rgb(38, 42, 49), Gravity.START);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nameParams);

        Button state = button(active ? "פעיל" : "אפשר");
        state.setEnabled(!active);
        if (!active) state.setOnClickListener(v -> action.run());
        row.addView(state, new LinearLayout.LayoutParams(dp(100), dp(44)));

        permissionList.addView(row, spaced(0, 0, 0, dp(8)));
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryAccessActive() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                openAppSettings();
            }
        }
    }

    private PairingConfig ensurePairing() {
        PairingConfig p = AppPrefs.pairing(this);
        if (p != null) {
            if ("baby".equals(selectedRole()) && !AppPrefs.pairConfirmed(this) && !p.isShortCode()) {
                try {
                    p = PairingConfig.generate();
                    AppPrefs.savePairing(this, p);
                } catch (Exception e) {
                    Toast.makeText(this, "לא ניתן ליצור קוד חיבור", Toast.LENGTH_LONG).show();
                    return null;
                }
            }
            return p;
        }
        try {
            p = PairingConfig.generate();
            AppPrefs.savePairing(this, p);
            return p;
        } catch (Exception e) {
            Toast.makeText(this, "לא ניתן ליצור קוד חיבור", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void renderPairingQr() {
        if (pairingQrView == null) return;
        PairingConfig p = ensurePairing();
        if (p == null) return;
        try {
            String payload = p.encode();
            if (pairingCodeView != null) pairingCodeView.setText(payload);
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
        }
    }

    private void copyPairingCode() {
        PairingConfig p = ensurePairing();
        if (p == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "לא ניתן להעתיק את הקוד", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("ARGUS pairing code", p.encode()));
        Toast.makeText(this, "קוד החיבור הועתק", Toast.LENGTH_SHORT).show();
    }

    private void rotatePairing() {
        try {
            PairingConfig p = PairingConfig.generate();
            AppPrefs.savePairing(this, p);
            AppPrefs.setPairConfirmed(this, false);
            AppPrefs.setPeerOnline(this, "baby", false);
            stopService(new Intent(this, SenderService.class));
            AppPrefs.setMode(this, "none");
            renderPairingQr();
            handler.postDelayed(this::startBaby, 250);
            Toast.makeText(this, "נוצר קוד חיבור חדש", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "לא ניתן לרענן את קוד החיבור", Toast.LENGTH_LONG).show();
        }
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
                        String raw = barcode.getRawValue();
                        if (raw == null || raw.trim().isEmpty()) {
                            Toast.makeText(this, "לא נמצא מידע בקוד ה QR", Toast.LENGTH_LONG).show();
                            return;
                        }
                        acceptPairingPayload(raw);
                    })
                    .addOnCanceledListener(() -> scanInProgress = false)
                    .addOnFailureListener(e -> {
                        scanInProgress = false;
                        Toast.makeText(this, "לא ניתן לסרוק את קוד ה QR", Toast.LENGTH_LONG).show();
                    });
        } catch (RuntimeException e) {
            scanInProgress = false;
            Toast.makeText(this, "לא ניתן לסרוק את קוד ה QR", Toast.LENGTH_LONG).show();
        }
    }

    private void handlePairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        if (!"argus".equalsIgnoreCase(data.getScheme()) || !"pair".equalsIgnoreCase(data.getHost())) return;
        String code = data.getQueryParameter("code");
        if (code == null || code.trim().isEmpty()) return;
        acceptPairingPayload(code);
        intent.setData(null);
    }

    private void acceptPairingPayload(String payload) {
        try {
            String code = payload.trim();
            if (code.startsWith("argus://")) {
                Uri uri = Uri.parse(code);
                code = uri.getQueryParameter("code");
            }
            PairingConfig p = PairingConfig.parse(code);
            AppPrefs.savePairing(this, p);
            AppPrefs.setPairConfirmed(this, false);
            AppPrefs.setPeerOnline(this, "parent", false);
            AppPrefs.state(this, "parent", "מתחבר לטלפון הילד");
            AppPrefs.prefs(this).edit().putString(ROLE_PREF, "parent").apply();
            renderedSetupRole = "";
            Toast.makeText(this, "הקוד התקבל, מתחבר", Toast.LENGTH_SHORT).show();
            startParent();
            showCurrentScreen();
        } catch (Exception e) {
            Toast.makeText(this, "קוד החיבור של ARGUS אינו תקין", Toast.LENGTH_LONG).show();
        }
    }

    private void startBaby() {
        if (ensurePairing() == null) return;
        boolean camera = hasPermission(Manifest.permission.CAMERA);
        boolean mic = hasPermission(Manifest.permission.RECORD_AUDIO);
        if (!camera || !mic) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_CHILD_PERMS);
            return;
        }
        stopService(new Intent(this, ReceiverService.class));
        AppPrefs.setMode(this, "baby");
        AppPrefs.prefs(this).edit().putString(ROLE_PREF, "baby").apply();
        Intent i = new Intent(this, SenderService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        refreshState();
    }

    private void startParent() {
        if (AppPrefs.pairing(this) == null) return;
        stopService(new Intent(this, SenderService.class));
        LiveVideoStore.clear();
        displayedVideoAt = 0L;
        AppPrefs.setMode(this, "parent");
        AppPrefs.prefs(this).edit().putString(ROLE_PREF, "parent").apply();
        Intent i = new Intent(this, ReceiverService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        refreshState();
    }

    private void toggleLive() {
        String role = selectedRole();
        if ("baby".equals(role)) {
            if ("baby".equals(AppPrefs.mode(this))) {
                stopService(new Intent(this, SenderService.class));
                AppPrefs.setMode(this, "none");
                AppPrefs.setPeerOnline(this, "baby", false);
            } else {
                startBaby();
            }
        } else if ("parent".equals(role)) {
            if ("parent".equals(AppPrefs.mode(this))) {
                stopService(new Intent(this, ReceiverService.class));
                AppPrefs.setMode(this, "none");
                AppPrefs.setPeerOnline(this, "parent", false);
            } else {
                startParent();
            }
        }
        refreshState();
    }

    private void updateParentMedia() {
        if (cameraSwitch == null || micSwitch == null) return;
        boolean camera = cameraSwitch.isChecked();
        boolean mic = micSwitch.isChecked();
        AppPrefs.setParentMedia(this, camera, mic);
        if ("parent".equals(AppPrefs.mode(this))) {
            Intent control = new Intent(this, ReceiverService.class)
                    .setAction(ReceiverService.ACTION_SET_STREAM)
                    .putExtra(ReceiverService.EXTRA_CAMERA, camera)
                    .putExtra(ReceiverService.EXTRA_MIC, mic);
            startService(control);
        }
        refreshState();
    }

    private void renderLive(String role) {
        boolean child = "baby".equals(role);
        boolean online = AppPrefs.peerOnline(this, role);
        liveConnection.setText(online ? "מחובר" : "לא מחובר");
        liveConnection.setBackgroundColor(online ? Color.rgb(39, 145, 84) : Color.rgb(190, 57, 57));

        liveMainText.setText(child ? "משדר" : "טלפון הורה");
        cameraSwitch.setVisibility(child ? View.GONE : View.VISIBLE);
        micSwitch.setVisibility(child ? View.GONE : View.VISIBLE);

        View mediaControls = (View) cameraSwitch.getParent();
        mediaControls.setVisibility(child ? View.GONE : View.VISIBLE);

        batteryState.setVisibility(child ? View.GONE : View.VISIBLE);
        videoView.setVisibility(child ? View.GONE : View.VISIBLE);
        audioOnlyState.setVisibility(child ? View.GONE : View.VISIBLE);

        boolean running = child ? "baby".equals(AppPrefs.mode(this)) : "parent".equals(AppPrefs.mode(this));
        liveStartStop.setText(running ? (child ? "עצור שידור" : "עצור") : (child ? "התחל שידור" : "התחל"));
        if (!child && running) {
            liveStartStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(190, 57, 57)));
            liveStartStop.setTextColor(Color.WHITE);
        } else {
            liveStartStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(224, 224, 224)));
            liveStartStop.setTextColor(Color.rgb(28, 32, 39));
        }

        if (!child) {
            updatingSwitches = true;
            cameraSwitch.setChecked(AppPrefs.parentCameraEnabled(this));
            micSwitch.setChecked(AppPrefs.parentMicEnabled(this));
            updatingSwitches = false;
            renderParentMedia();
        }
    }

    private void renderParentMedia() {
        boolean camera = AppPrefs.parentCameraEnabled(this);
        boolean mic = AppPrefs.parentMicEnabled(this);
        long now = System.currentTimeMillis();
        LiveVideoStore.Frame frame = LiveVideoStore.latest();

        if (camera) {
            videoView.setVisibility(View.VISIBLE);
            audioOnlyState.setVisibility(View.GONE);
            if (frame != null && frame.receivedAt > displayedVideoAt) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length);
                if (bitmap != null) {
                    videoView.setImageBitmap(bitmap);
                    displayedVideoAt = frame.receivedAt;
                }
            }
            if (frame == null || now - frame.receivedAt > 5000) {
                videoView.setImageDrawable(null);
                videoView.setBackgroundColor(Color.rgb(230, 232, 235));
            }
        } else {
            videoView.setVisibility(View.GONE);
            audioOnlyState.setVisibility(View.VISIBLE);
            audioOnlyState.setText(mic ? "מיקרופון פעיל" : "השידור מושהה");
        }

        int pct = AppPrefs.prefs(this).getInt("baby_battery", -1);
        boolean charging = AppPrefs.prefs(this).getBoolean("baby_charging", false);
        if (pct >= 0) {
            batteryState.setText("סוללת הילד: " + pct + "%" + (charging ? "  בטעינה" : ""));
            batteryState.setTextColor(pct <= 20 ? Color.rgb(177, 53, 53) : Color.rgb(37, 72, 60));
        } else {
            batteryState.setText("סוללת הילד: --");
            batteryState.setTextColor(Color.rgb(92, 99, 109));
        }
    }

    private void refreshState() {
        showCurrentScreen();
        String role = selectedRole();
        if (("baby".equals(role) || "parent".equals(role))
                && AppPrefs.pairing(this) != null
                && !AppPrefs.pairConfirmed(this)
                && AppPrefs.peerOnline(this, role)) {
            AppPrefs.setPairConfirmed(this, true);
            showCurrentScreen();
        }
        if (livePanel.getVisibility() == View.VISIBLE) renderLive(role);
        if (setupPanel.getVisibility() == View.VISIBLE) {
            updateSetupStatus(role);
            renderPermissions(role);
        }
    }

    private void changePhoneRole() {
        stopService(new Intent(this, SenderService.class));
        stopService(new Intent(this, ReceiverService.class));
        LiveVideoStore.clear();
        displayedVideoAt = 0L;
        AppPrefs.setMode(this, "none");
        AppPrefs.clearPairing(this);
        AppPrefs.prefs(this).edit().remove(ROLE_PREF).apply();
        renderedSetupRole = "";
        showCurrentScreen();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(16), dp(16), dp(16), dp(16));
        p.setBackgroundColor(Color.rgb(245, 246, 248));
        return p;
    }

    private TextView heading(String s) {
        TextView t = text(s, 21, Color.rgb(32, 36, 43), Gravity.CENTER);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 18, Color.rgb(32, 36, 43), Gravity.START);
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
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams height(int h) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
    }

    private LinearLayout.LayoutParams spaced(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = matchWrap();
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams spacedHeight(int top, int h) {
        LinearLayout.LayoutParams p = height(h);
        p.setMargins(0, top, 0, 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        renderPermissions(selectedRole());
        if (requestCode == REQ_CHILD_PERMS
                && hasPermission(Manifest.permission.CAMERA)
                && hasPermission(Manifest.permission.RECORD_AUDIO)
                && "baby".equals(selectedRole())) {
            startBaby();
        }
    }
}
