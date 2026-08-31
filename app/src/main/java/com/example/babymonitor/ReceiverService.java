package com.example.babymonitor;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ReceiverService extends Service {
    private static final int NOTIF_ID = 1002;
    private static final int ALERT_ID = 1099;
    private static final int SAMPLE_RATE = 8000;
    static final String ACTION_SET_STREAM = "com.example.babymonitor.SET_STREAM";
    static final String EXTRA_CAMERA = "camera";
    static final String EXTRA_MIC = "mic";
    private static final String ACTION_STOP = "com.example.babymonitor.STOP_PARENT";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean peerOnline = new AtomicBoolean(false);
    private final AtomicLong controlSequence = new AtomicLong(1);
    private final long controlSession = new SecureRandom().nextLong();
    private volatile SecureWebSocket ws;
    private PairingConfig pairing;
    private PacketCodec codec;
    private AudioTrack track;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastAudioAt = 0L;
    private volatile long lastVideoAt = 0L;
    private volatile long lastUiStateAt = 0L;
    private volatile long lastAlertAt = 0L;
    private volatile boolean hadLiveMedia = false;
    private volatile boolean lowBatteryNotified = false;
    private volatile long currentSession = Long.MIN_VALUE;
    private volatile long lastSequence = 0L;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AppPrefs.setMode(this, "none");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_SET_STREAM.equals(intent.getAction())) {
            boolean camera = intent.getBooleanExtra(EXTRA_CAMERA, true);
            boolean mic = intent.getBooleanExtra(EXTRA_MIC, true);
            AppPrefs.setParentMedia(this, camera, mic);
            if (running.get()) sendStreamControl();
            return START_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, notification("Starting"));
        }

        if (running.compareAndSet(false, true)) {
            LiveVideoStore.clear();
            pairing = AppPrefs.pairing(this);
            String relay = AppPrefs.relay(this);
            if (pairing == null || relay.isEmpty()) {
                AppPrefs.state(this, "parent", "Missing pairing or relay");
                stopSelf();
                return START_NOT_STICKY;
            }
            codec = new PacketCodec(pairing.encryptionKey);
            initAudio();
            acquireLocks();
            watchdog.scheduleAtFixedRate(this::watchConnection, 3, 3, TimeUnit.SECONDS);
            new Thread(this::connectionLoop, "ArgusParentConnection").start();
        }
        return START_STICKY;
    }

    private void initAudio() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, 3200))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
    }

    private void connectionLoop() {
        int delayMs = 1000;
        while (running.get()) {
            try {
                AppPrefs.setPeerOnline(this, "parent", false);
                AppPrefs.state(this, "parent", "Connecting");
                updateNotification("Connecting");
                final Object closedLock = new Object();
                final AtomicBoolean closed = new AtomicBoolean(false);
                SecureWebSocket socket = new SecureWebSocket(AppPrefs.relay(this), pairing, "parent", new SecureWebSocket.Listener() {
                    @Override public void onOpen() {
                        AppPrefs.state(ReceiverService.this, "parent", "Waiting for Child phone");
                        updateNotification("Waiting for Child phone");
                    }
                    @Override public void onText(String text) {
                        if ("PEER:ONLINE".equals(text)) {
                            peerOnline.set(true);
                            AppPrefs.setPeerOnline(ReceiverService.this, "parent", true);
                            AppPrefs.setPairConfirmed(ReceiverService.this, true);
                            AppPrefs.state(ReceiverService.this, "parent", "Connected");
                            updateNotification("Connected");
                            sendStreamControl();
                        } else if ("PEER:OFFLINE".equals(text)) {
                            peerOnline.set(false);
                            AppPrefs.setPeerOnline(ReceiverService.this, "parent", false);
                            AppPrefs.state(ReceiverService.this, "parent", "Child phone disconnected");
                            if (hadLiveMedia) triggerConnectionAlarm("Child phone disconnected");
                        }
                    }
                    @Override public void onBinary(byte[] data) { handleEncrypted(data); }
                    @Override public void onClosed(String reason) {
                        peerOnline.set(false);
                        AppPrefs.setPeerOnline(ReceiverService.this, "parent", false);
                        synchronized (closedLock) { closed.set(true); closedLock.notifyAll(); }
                    }
                    @Override public void onError(Exception error) {
                        peerOnline.set(false);
                        AppPrefs.setPeerOnline(ReceiverService.this, "parent", false);
                        AppPrefs.state(ReceiverService.this, "parent", "Reconnecting");
                    }
                });
                ws = socket;
                socket.connect();
                delayMs = 1000;
                synchronized (closedLock) {
                    while (running.get() && !closed.get()) closedLock.wait(1000);
                }
            } catch (Exception e) {
                AppPrefs.setPeerOnline(this, "parent", false);
                AppPrefs.state(this, "parent", "Reconnecting");
                updateNotification("Reconnecting");
                if (hadLiveMedia) triggerConnectionAlarm("Parent phone lost connection");
            } finally {
                SecureWebSocket old = ws;
                ws = null;
                if (old != null) old.close();
                peerOnline.set(false);
                AppPrefs.setPeerOnline(this, "parent", false);
            }
            if (running.get()) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { }
                delayMs = Math.min(delayMs * 2, 15000);
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
        } catch (Exception ignored) {
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

            if (d.type == PacketCodec.TYPE_AUDIO) {
                if (AppPrefs.parentMicEnabled(this)) {
                    byte[] pcm = new byte[d.payload.length * 2];
                    int n = MuLaw.decodeToPcm16(d.payload, pcm);
                    AudioTrack t = track;
                    if (t != null) t.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING);
                    lastAudioAt = System.currentTimeMillis();
                    hadLiveMedia = true;
                    clearConnectionAlarm();
                    publishLiveState();
                }
            } else if (d.type == PacketCodec.TYPE_VIDEO_JPEG) {
                if (AppPrefs.parentCameraEnabled(this)) {
                    LiveVideoStore.put(d.payload);
                    lastVideoAt = System.currentTimeMillis();
                    hadLiveMedia = true;
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
        } catch (Exception ignored) {
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
        if (videoLive && audioLive) state = "LIVE camera + audio";
        else if (videoLive) state = "LIVE camera";
        else if (audioLive) state = "LIVE audio";
        else if (!camera && !mic) state = "Transmission paused";
        else state = "Connected";
        AppPrefs.state(this, "parent", state);
        updateNotification(state);
    }

    private void watchConnection() {
        if (!running.get() || !hadLiveMedia || !peerOnline.get()) return;
        boolean camera = AppPrefs.parentCameraEnabled(this);
        boolean mic = AppPrefs.parentMicEnabled(this);
        if (!camera && !mic) return;
        long now = System.currentTimeMillis();
        boolean staleAudio = mic && now - lastAudioAt > 8000;
        boolean staleVideo = camera && now - lastVideoAt > 8000;
        if ((mic && !camera && staleAudio) || (camera && !mic && staleVideo) || (camera && mic && staleAudio && staleVideo)) {
            triggerConnectionAlarm("No Child stream received");
        }
    }

    private synchronized void triggerConnectionAlarm(String reason) {
        if (!running.get() || !hadLiveMedia) return;
        AppPrefs.state(this, "parent", "ALERT - " + reason);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 21, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "argus_alerts")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("ARGUS connection lost")
                .setContentText(reason)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true).setContentIntent(pi).build();
        nm.notify(ALERT_ID, n);
        long now = System.currentTimeMillis();
        if (now - lastAlertAt >= 10000) {
            lastAlertAt = now;
            try {
                ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 1000);
                new Handler(Looper.getMainLooper()).postDelayed(tone::release, 1500);
            } catch (Exception ignored) { }
        }
    }

    private void clearConnectionAlarm() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ALERT_ID);
    }

    private void notifyLowBattery(int pct) {
        Notification n = new Notification.Builder(this, "argus_warnings")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Child phone battery low")
                .setContentText("Child phone is at " + pct + "%")
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true).build();
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
        } catch (Exception ignored) { }
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
                .setContentTitle("ARGUS Parent phone")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(content)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build())
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notification(text));
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("argus_receiver", "ARGUS listener", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alerts = new NotificationChannel("argus_alerts", "ARGUS connection alarms", NotificationManager.IMPORTANCE_HIGH);
        alerts.enableVibration(true);
        alerts.setDescription("Connection loss warnings after monitoring has started");
        alerts.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        nm.createNotificationChannel(alerts);
        NotificationChannel warnings = new NotificationChannel("argus_warnings", "ARGUS battery warnings", NotificationManager.IMPORTANCE_DEFAULT);
        warnings.enableVibration(true);
        warnings.setDescription("Child phone low battery warnings");
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
        clearConnectionAlarm();
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        AppPrefs.state(this, "parent", "Stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
