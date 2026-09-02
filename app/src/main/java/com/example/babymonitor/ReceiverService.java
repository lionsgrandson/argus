package com.example.babymonitor;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ReceiverService extends Service {
    private static final int NOTIF_ID = 1002;
    private static final int ALERT_ID = 1099;
    private static final int OLD_ERROR_NOTIF_ID = 1201;
    private static final int SAMPLE_RATE = 8000;
    private static final long MEDIA_GRACE_MS = 12000L;
    private static final long DISCONNECT_GRACE_MS = 10000L;
    static final String ACTION_SET_STREAM = "com.example.babymonitor.SET_STREAM";
    static final String EXTRA_CAMERA = "camera";
    static final String EXTRA_MIC = "mic";
    private static final String ACTION_STOP = "com.example.babymonitor.STOP_PARENT";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean peerOnline = new AtomicBoolean(false);
    private final ArrayBlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(12);
    private final AtomicLong controlSequence = new AtomicLong(1);
    private final long controlSession = new SecureRandom().nextLong();
    private volatile SecureWebSocket ws;
    private PairingConfig pairing;
    private PacketCodec codec;
    private AudioTrack track;
    private Thread audioPlaybackThread;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastAudioAt = 0L;
    private volatile long lastVideoAt = 0L;
    private volatile long lastUiStateAt = 0L;
    private volatile long mediaChangedAt = System.currentTimeMillis();
    private volatile long disconnectedAt = 0L;
    private volatile Throwable lastConnectionError;
    private volatile boolean connectionErrorReported = false;
    private volatile boolean mediaErrorReported = false;
    private volatile boolean lowBatteryNotified = false;
    private volatile long currentSession = Long.MIN_VALUE;
    private volatile long lastSequence = 0L;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(OLD_ERROR_NOTIF_ID);
        clearConnectionAlarm();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppPrefs.setMode(this, "none");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SET_STREAM.equals(intent.getAction())) {
            boolean camera = intent.getBooleanExtra(EXTRA_CAMERA, false);
            boolean mic = intent.getBooleanExtra(EXTRA_MIC, true);
            AppPrefs.setParentMedia(this, camera, mic);
            beginMediaGrace(camera, mic);
            if (running.get()) sendStreamControl();
            return START_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification("מתחיל"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notification("מתחיל"));
        }
        if (running.compareAndSet(false, true)) {
            LiveVideoStore.clear();
            pairing = AppPrefs.pairing(this);
            String relay = AppPrefs.relay(this);
            if (pairing == null || relay.isEmpty()) {
                ErrorReporter.report(this, "parent", "E100", "חסרים פרטי חיבור", null);
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                codec = new PacketCodec(pairing.encryptionKey);
            } catch (Exception e) {
                ErrorReporter.report(this, "parent", "E401", "לא ניתן להכין את הצפנת החיבור", e);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!initAudio()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            audioPlaybackThread = new Thread(this::audioPlaybackLoop, "ArgusParentAudio");
            audioPlaybackThread.start();
            acquireLocks();
            watchdog.scheduleAtFixedRate(this::watchConnection, 3, 3, TimeUnit.SECONDS);
            new Thread(this::connectionLoop, "ArgusParentConnection").start();
        }
        return START_STICKY;
    }

    private boolean initAudio() {
        try {
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, 1600))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
            return true;
        } catch (Exception e) {
            ErrorReporter.report(this, "parent", "E304", "לא ניתן להפעיל את השמע בטלפון ההורה", e);
            return false;
        }
    }

    private void connectionLoop() {
        int delayMs = 1000;
        while (running.get()) {
            try {
                AppPrefs.setPeerOnline(this, "parent", false);
                AppPrefs.state(this, "parent", "מתחבר");
                updateNotification("מתחבר");
                final Object closedLock = new Object();
                final AtomicBoolean closed = new AtomicBoolean(false);
                SecureWebSocket socket = new SecureWebSocket(AppPrefs.relay(this), pairing, "parent", new SecureWebSocket.Listener() {
                    @Override public void onOpen() {
                        AppPrefs.state(ReceiverService.this, "parent", "ממתין לטלפון הילד");
                        updateNotification("ממתין לטלפון הילד");
                    }
                    @Override public void onText(String text) {
                        if ("PEER:ONLINE".equals(text)) {
                            markPeerOnline();
                        } else if ("PEER:OFFLINE".equals(text)) {
                            markDisconnected(new IOException("Child phone is offline"));
                            AppPrefs.state(ReceiverService.this, "parent", "טלפון הילד התנתק");
                        }
                    }
                    @Override public void onBinary(byte[] data) {
                        handleEncrypted(data);
                    }
                    @Override public void onClosed(String reason) {
                        if (running.get()) markDisconnected(new IOException(reason));
                        synchronized (closedLock) { closed.set(true); closedLock.notifyAll(); }
                    }
                    @Override public void onError(Exception error) {
                        markDisconnected(error);
                        AppPrefs.state(ReceiverService.this, "parent", "מתחבר מחדש");
                    }
                });
                ws = socket;
                socket.connect();
                delayMs = 1000;
                synchronized (closedLock) {
                    while (running.get() && !closed.get()) closedLock.wait(1000);
                }
            } catch (Exception e) {
                markDisconnected(e);
                updateNotification("מתחבר מחדש");
            } finally {
                SecureWebSocket old = ws;
                ws = null;
                if (old != null) old.close();
                if (running.get()) markDisconnected(null);
            }
            if (running.get()) {
                try { Thread.sleep(delayMs); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                delayMs = Math.min(delayMs * 2, 5000);
            }
        }
    }

    private void sendStreamControl() {
        try {
            SecureWebSocket socket = ws;
            if (!running.get() || !peerOnline.get() || socket == null || !socket.isOpen() || codec == null) return;
            JSONObject j = new JSONObject();
            j.put("camera", AppPrefs.parentCameraEnabled(this));
            j.put("mic", AppPrefs.parentMicEnabled(this));
            byte[] clear = j.toString().getBytes(StandardCharsets.UTF_8);
            long next = controlSequence.getAndIncrement();
            socket.sendBinary(codec.encrypt(PacketCodec.TYPE_STREAM_CONTROL, controlSession, next, clear, clear.length));
        } catch (Exception e) {
            markDisconnected(e);
            SecureWebSocket socket = ws;
            if (socket != null) socket.close();
        }
    }

    private void handleEncrypted(byte[] packet) {
        try {
            PacketCodec.Decoded d = codec.decrypt(packet);
            if (currentSession == Long.MIN_VALUE) {
                currentSession = d.session;
                lastSequence = 0;
            } else if (d.session != currentSession) {
                if (d.sequence > 5) return;
                currentSession = d.session;
                lastSequence = 0;
            }
            if (d.sequence <= lastSequence) return;
            lastSequence = d.sequence;
            markPeerOnline();

            if (d.type == PacketCodec.TYPE_AUDIO) {
                if (AppPrefs.parentMicEnabled(this)) {
                    byte[] pcm = new byte[d.payload.length * 2];
                    int n = MuLaw.decodeToPcm16(d.payload, pcm);
                    if (n != pcm.length) {
                        byte[] exact = new byte[n];
                        System.arraycopy(pcm, 0, exact, 0, n);
                        pcm = exact;
                    }
                    if (!audioQueue.offer(pcm)) {
                        audioQueue.poll();
                        audioQueue.offer(pcm);
                    }
                    lastAudioAt = System.currentTimeMillis();
                    clearRecoveredMediaError();
                    clearConnectionAlarm();
                    publishLiveState();
                }
            } else if (d.type == PacketCodec.TYPE_VIDEO_JPEG) {
                if (AppPrefs.parentCameraEnabled(this)) {
                    LiveVideoStore.put(d.payload);
                    lastVideoAt = System.currentTimeMillis();
                    clearRecoveredMediaError();
                    clearConnectionAlarm();
                    publishLiveState();
                }
            } else if (d.type == PacketCodec.TYPE_STATUS) {
                JSONObject j = new JSONObject(new String(d.payload, StandardCharsets.UTF_8));
                int pct = j.optInt("battery", -1);
                boolean charging = j.optBoolean("charging", false);
                AppPrefs.parentBattery(this, pct, charging);
                if (pct >= 0 && pct < 20 && !charging && !lowBatteryNotified) {
                    lowBatteryNotified = true;
                    notifyLowBattery(pct);
                } else if (pct >= 25 || charging) {
                    lowBatteryNotified = false;
                }
            }
        } catch (Exception e) {
            ErrorReporter.report(this, "parent", "E401", "מידע מוצפן מהטלפון הילד לא נקרא", e);
        }
    }

    private void markPeerOnline() {
        if (peerOnline.compareAndSet(false, true)) {
            disconnectedAt = 0L;
            lastConnectionError = null;
            connectionErrorReported = false;
            beginMediaGrace(AppPrefs.parentCameraEnabled(this), AppPrefs.parentMicEnabled(this));
            AppPrefs.setPeerOnline(this, "parent", true);
            AppPrefs.setPairConfirmed(this, true);
            ErrorReporter.clear(this, "parent");
            AppPrefs.state(this, "parent", "מחובר");
            updateNotification("מחובר");
            sendStreamControl();
        }
    }

    private void markDisconnected(Throwable error) {
        peerOnline.set(false);
        audioQueue.clear();
        AppPrefs.setPeerOnline(this, "parent", false);
        if (disconnectedAt == 0L) disconnectedAt = System.currentTimeMillis();
        if (error != null) lastConnectionError = error;
    }

    private void beginMediaGrace(boolean camera, boolean mic) {
        long now = System.currentTimeMillis();
        mediaChangedAt = now;
        mediaErrorReported = false;
        if (camera) lastVideoAt = now;
        if (mic) lastAudioAt = now;
        if ("E405".equals(AppPrefs.lastErrorCode(this))) ErrorReporter.clear(this, "parent");
        clearConnectionAlarm();
    }

    private void audioPlaybackLoop() {
        while (running.get()) {
            try {
                byte[] pcm = audioQueue.poll(1, TimeUnit.SECONDS);
                if (pcm == null) continue;
                AudioTrack current = track;
                if (current != null && AppPrefs.parentMicEnabled(this)) {
                    current.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                ErrorReporter.report(this, "parent", "E304", "השמעת השמע בטלפון ההורה נכשלה", e);
            }
        }
    }

    private void publishLiveState() {
        long now = System.currentTimeMillis();
        if (now - lastUiStateAt < 1000) return;
        lastUiStateAt = now;
        boolean camera = AppPrefs.parentCameraEnabled(this);
        boolean mic = AppPrefs.parentMicEnabled(this);
        boolean videoLive = camera && now - lastVideoAt < 3000;
        boolean audioLive = mic && now - lastAudioAt < 3000;
        String state;
        if (videoLive && audioLive) state = "מצלמה ומיקרופון פעילים";
        else if (videoLive) state = "מצלמה פעילה";
        else if (audioLive) state = "מיקרופון פעיל";
        else if (!camera && !mic) state = "השידור מושהה";
        else state = "מחובר";
        AppPrefs.state(this, "parent", state);
        updateNotification(state);
    }

    private void watchConnection() {
        if (!running.get()) return;
        long now = System.currentTimeMillis();
        if (!peerOnline.get()) {
            if (disconnectedAt > 0L && now - disconnectedAt >= DISCONNECT_GRACE_MS
                    && !connectionErrorReported) {
                connectionErrorReported = true;
                Throwable error = lastConnectionError;
                if (error == null) error = new IOException("Connection did not recover");
                ErrorReporter.reportConnection(this, "parent", error);
            }
            return;
        }
        boolean camera = AppPrefs.parentCameraEnabled(this);
        boolean mic = AppPrefs.parentMicEnabled(this);
        if (!camera && !mic) return;
        if (now - mediaChangedAt < MEDIA_GRACE_MS) return;
        boolean staleAudio = mic && now - lastAudioAt > MEDIA_GRACE_MS;
        boolean staleVideo = camera && now - lastVideoAt > MEDIA_GRACE_MS;
        if ((mic && !camera && staleAudio)
                || (camera && !mic && staleVideo)
                || (camera && mic && staleAudio && staleVideo)) {
            if (mediaErrorReported) return;
            mediaErrorReported = true;
            ErrorReporter.report(this, "parent", "E405", "החיבור קיים אבל לא מתקבל שידור מטלפון הילד", null);
        }
    }

    private void clearRecoveredMediaError() {
        mediaErrorReported = false;
        if ("E405".equals(AppPrefs.lastErrorCode(this))) {
            ErrorReporter.clear(this, "parent");
        }
    }

    private void clearConnectionAlarm() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ALERT_ID);
    }

    private void notifyLowBattery(int pct) {
        Notification n = new Notification.Builder(this, "argus_warnings")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("הסוללה של טלפון הילד נמוכה")
                .setContentText("סוללת הילד על " + pct + "%")
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ALERT_ID + 1, n);
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ARGUS:Parent");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ARGUS:ParentWifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) {
            ErrorReporter.report(this, "parent", "E404", "לא ניתן לנעול את חיבור ה WiFi ברקע", e);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ReceiverService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 11, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "argus_receiver")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("ARGUS פעיל")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "עצור", stopPi).build())
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notification(text));
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("argus_receiver", "האזנה של ARGUS", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel warnings = new NotificationChannel("argus_warnings", "התראות סוללה של ARGUS", NotificationManager.IMPORTANCE_DEFAULT);
        warnings.enableVibration(true);
        warnings.setDescription("התראות על סוללה נמוכה בטלפון הילד");
        nm.createNotificationChannel(warnings);
    }

    @Override public void onDestroy() {
        running.set(false);
        peerOnline.set(false);
        AppPrefs.setPeerOnline(this, "parent", false);
        LiveVideoStore.clear();
        SecureWebSocket socket = ws;
        ws = null;
        if (socket != null) socket.close();
        watchdog.shutdownNow();
        audioQueue.clear();
        if (audioPlaybackThread != null) {
            audioPlaybackThread.interrupt();
            audioPlaybackThread = null;
        }
        clearConnectionAlarm();
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        AppPrefs.state(this, "parent", "נעצר");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
