package com.example.babymonitor;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SenderService extends Service {
    private static final int NOTIF_ID = 1001;
    private static final int SAMPLE_RATE = 8000;
    private static final int SAMPLES_PER_FRAME = 160;
    private static final String ACTION_STOP = "com.example.babymonitor.STOP_BABY";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean peerOnline = new AtomicBoolean(false);
    private final AtomicBoolean cameraEnabled = new AtomicBoolean(false);
    private final AtomicBoolean micEnabled = new AtomicBoolean(true);
    private final AtomicLong sequence = new AtomicLong(1);
    private final Object packetSendLock = new Object();
    private final long sessionId = new SecureRandom().nextLong();
    private volatile long controlSession = Long.MIN_VALUE;
    private volatile long lastControlSequence = 0L;
    private volatile SecureWebSocket ws;
    private AudioRecord recorder;
    private CameraStreamer cameraStreamer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private PairingConfig pairing;
    private PacketCodec codec;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppPrefs.setMode(this, "none");
            stopSelf();
            return START_NOT_STICKY;
        }

        RootSupport.preAuthorizeAsync();

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notification("מתחיל שידור"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, notification("מתחיל שידור"));
        }

        if (running.compareAndSet(false, true)) {
            pairing = AppPrefs.pairing(this);
            String relay = AppPrefs.relay(this);
            if (pairing == null || relay.isEmpty()) {
                ErrorReporter.report(this, "baby", "E100", "חסרים פרטי חיבור", null);
                stopSelf();
                return START_NOT_STICKY;
            }
            cameraEnabled.set(AppPrefs.childCameraEnabled(this));
            micEnabled.set(AppPrefs.childMicEnabled(this));
            try {
                codec = new PacketCodec(pairing.encryptionKey);
            } catch (Exception e) {
                ErrorReporter.report(this, "baby", "E401", "לא ניתן להכין את הצפנת החיבור", e);
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireLocks();
            startCamera();
            new Thread(this::connectionLoop, "ArgusChildConnection").start();
            new Thread(this::audioLoop, "ArgusChildAudio").start();
            new Thread(this::statusLoop, "ArgusChildStatus").start();
        }
        return START_STICKY;
    }

    private void startCamera() {
        cameraStreamer = new CameraStreamer(this,
                () -> peerOnline.get() && cameraEnabled.get(),
                jpeg -> sendEncrypted(PacketCodec.TYPE_VIDEO_JPEG, jpeg, jpeg.length));
        cameraStreamer.start();
    }

    private void sendEncrypted(byte type, byte[] payload, int length) {
        synchronized (packetSendLock) {
            SecureWebSocket socket = ws;
            if (!running.get() || !peerOnline.get() || socket == null || !socket.isOpen()) return;
            try {
                long next = sequence.getAndIncrement();
                socket.sendBinary(codec.encrypt(type, sessionId, next, payload, length));
            } catch (Exception e) {
                ErrorReporter.report(this, "baby", "E207", "שליחת מידע לטלפון ההורה נכשלה", e);
                socket.close();
            }
        }
    }

    private void connectionLoop() {
        int delayMs = 1000;
        while (running.get()) {
            try {
                AppPrefs.setPeerOnline(this, "baby", false);
                AppPrefs.state(this, "baby", "מתחבר");
                updateNotification("מתחבר");
                final Object closedLock = new Object();
                final AtomicBoolean closed = new AtomicBoolean(false);
                SecureWebSocket socket = new SecureWebSocket(AppPrefs.relay(this), pairing, "baby", new SecureWebSocket.Listener() {
                    @Override public void onOpen() {
                        AppPrefs.state(SenderService.this, "baby", "ממתין לטלפון ההורה");
                        updateNotification("ממתין לטלפון ההורה");
                    }
                    @Override public void onText(String text) {
                        if ("PEER:ONLINE".equals(text)) {
                            peerOnline.set(true);
                            AppPrefs.setPeerOnline(SenderService.this, "baby", true);
                            AppPrefs.setPairConfirmed(SenderService.this, true);
                            ErrorReporter.clear(SenderService.this, "baby");
                            AppPrefs.state(SenderService.this, "baby", liveDescription());
                            updateNotification(liveDescription());
                        } else if ("PEER:OFFLINE".equals(text)) {
                            peerOnline.set(false);
                            AppPrefs.setPeerOnline(SenderService.this, "baby", false);
                            AppPrefs.state(SenderService.this, "baby", "טלפון ההורה התנתק");
                            updateNotification("ממתין לטלפון ההורה");
                        }
                    }
                    @Override public void onBinary(byte[] data) {
                        handleStreamControl(data);
                    }
                    @Override public void onClosed(String reason) {
                        peerOnline.set(false);
                        AppPrefs.setPeerOnline(SenderService.this, "baby", false);
                        synchronized (closedLock) { closed.set(true); closedLock.notifyAll(); }
                    }
                    @Override public void onError(Exception error) {
                        peerOnline.set(false);
                        AppPrefs.setPeerOnline(SenderService.this, "baby", false);
                        AppPrefs.state(SenderService.this, "baby", "מתחבר מחדש");
                    }
                });
                ws = socket;
                socket.connect();
                delayMs = 1000;
                synchronized (closedLock) {
                    while (running.get() && !closed.get()) closedLock.wait(1000);
                }
            } catch (Exception e) {
                AppPrefs.setPeerOnline(this, "baby", false);
                ErrorReporter.reportConnection(this, "baby", e);
                updateNotification("שגיאת חיבור " + AppPrefs.lastErrorCode(this));
            } finally {
                SecureWebSocket old = ws;
                ws = null;
                if (old != null) old.close();
                peerOnline.set(false);
                AppPrefs.setPeerOnline(this, "baby", false);
            }
            if (running.get()) {
                try { Thread.sleep(delayMs); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                delayMs = Math.min(delayMs * 2, 15000);
            }
        }
    }

    private void handleStreamControl(byte[] data) {
        try {
            PacketCodec.Decoded d = codec.decrypt(data);
            if (d.type != PacketCodec.TYPE_STREAM_CONTROL) return;
            if (controlSession == Long.MIN_VALUE || d.session != controlSession) {
                controlSession = d.session;
                lastControlSequence = 0L;
            }
            if (d.sequence <= lastControlSequence) return;
            lastControlSequence = d.sequence;

            JSONObject j = new JSONObject(new String(d.payload, StandardCharsets.UTF_8));
            boolean camera = j.optBoolean("camera", false);
            boolean mic = j.optBoolean("mic", true);
            cameraEnabled.set(camera);
            micEnabled.set(mic);
            AppPrefs.setChildMedia(this, camera, mic);
            if (peerOnline.get()) {
                AppPrefs.state(this, "baby", liveDescription());
                updateNotification(liveDescription());
            }
        } catch (Exception e) {
            ErrorReporter.report(this, "baby", "E402", "פקודת השידור מהטלפון ההורה לא נקראה", e);
        }
    }

    private String liveDescription() {
        if (cameraEnabled.get() && micEnabled.get()) return "מצלמה ומיקרופון פעילים";
        if (cameraEnabled.get()) return "מצלמה פעילה";
        if (micEnabled.get()) return "מיקרופון פעיל";
        return "מחובר, השידור מושהה";
    }

    private void audioLoop() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ErrorReporter.report(this, "baby", "E301", "חסרה הרשאת מיקרופון", null);
            stopSelf();
            return;
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min, SAMPLES_PER_FRAME * 2 * 8);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord init failed");
            }
            recorder.startRecording();
            byte[] pcm = new byte[SAMPLES_PER_FRAME * 2];
            byte[] ulaw = new byte[SAMPLES_PER_FRAME];
            while (running.get()) {
                int read = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (read <= 0) continue;
                if (!peerOnline.get() || !micEnabled.get()) continue;
                int encoded = MuLaw.encodePcm16(pcm, read, ulaw);
                sendEncrypted(PacketCodec.TYPE_AUDIO, ulaw, encoded);
            }
        } catch (Exception e) {
            ErrorReporter.report(this, "baby", "E302", "המיקרופון לא הצליח להתחיל או להמשיך הקלטה", e);
            updateNotification("שגיאת מיקרופון E302");
            stopSelf();
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) { }
                recorder.release();
                recorder = null;
            }
        }
    }

    private void statusLoop() {
        while (running.get()) {
            try {
                if (peerOnline.get()) {
                    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    int pct = -1;
                    boolean charging = false;
                    if (battery != null) {
                        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        if (level >= 0 && scale > 0) pct = Math.round(level * 100f / scale);
                        int st = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
                    }
                    JSONObject j = new JSONObject();
                    j.put("battery", pct);
                    j.put("charging", charging);
                    j.put("camera", cameraEnabled.get() && cameraStreamer != null && cameraStreamer.isReady());
                    j.put("mic", micEnabled.get());
                    j.put("time", System.currentTimeMillis());
                    byte[] clear = j.toString().getBytes(StandardCharsets.UTF_8);
                    sendEncrypted(PacketCodec.TYPE_STATUS, clear, clear.length);
                }
            } catch (Exception e) {
                ErrorReporter.report(this, "baby", "E403", "שליחת מצב הטלפון נכשלה", e);
            }
            try { Thread.sleep(5000); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARGUS:ChildCameraMic");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ARGUS:ChildWifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) {
            ErrorReporter.report(this, "baby", "E404", "לא ניתן לנעול את חיבור ה WiFi ברקע", e);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, SenderService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 10, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "argus_sender")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("ARGUS פעיל")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .setSound(null)
                .setVibrate(null)
                .setDefaults(0)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(content)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "עצור", stopPi).build())
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notification(text));
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel c = new NotificationChannel("argus_sender", "שירות הרקע של ARGUS", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("שירות השידור של טלפון הילד");
        c.setSound(null, null);
        c.enableVibration(false);
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if ("baby".equals(AppPrefs.mode(this)) && running.get()) {
            AppPrefs.state(this, "baby", liveDescription());
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running.set(false);
        peerOnline.set(false);
        AppPrefs.setPeerOnline(this, "baby", false);
        SecureWebSocket socket = ws;
        ws = null;
        if (socket != null) socket.close();
        if (recorder != null) try { recorder.stop(); } catch (Exception ignored) { }
        if (cameraStreamer != null) {
            cameraStreamer.stop();
            cameraStreamer = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        AppPrefs.state(this, "baby", "נעצר");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
