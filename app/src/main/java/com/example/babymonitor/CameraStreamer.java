package com.example.babymonitor;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Collections;

final class CameraStreamer {
    interface CaptureGate { boolean shouldCapture(); }
    interface FrameSink { void onJpeg(byte[] jpeg); }

    private static final int CAPTURE_INTERVAL_MS = 250;
    private static final int MAX_JPEG_BYTES = 48 * 1024;
    private static final long TARGET_AREA = 320L * 240L;

    private final Context context;
    private final CaptureGate gate;
    private final FrameSink sink;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CaptureRequest request;
    private volatile boolean running;
    private volatile boolean ready;

    CameraStreamer(Context context, CaptureGate gate, FrameSink sink) {
        this.context = context.getApplicationContext();
        this.gate = gate;
        this.sink = sink;
    }

    boolean isReady() { return ready; }

    void start() {
        if (running) return;
        running = true;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ErrorReporter.report(context, "baby", "E303", "חסרה הרשאת מצלמה", null);
            return;
        }

        thread = new HandlerThread("ArgusCamera");
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) throw new IllegalStateException("CameraManager unavailable");
            String id = chooseCamera(manager);
            if (id == null) throw new IllegalStateException("No camera found");
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Size size = chooseSize(characteristics);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onImageAvailable, handler);

            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    camera = opened;
                    createSession(characteristics);
                }

                @Override public void onDisconnected(CameraDevice disconnected) {
                    disconnected.close();
                    if (camera == disconnected) camera = null;
                    ready = false;
                    ErrorReporter.report(context, "baby", "E305", "המצלמה התנתקה", null);
                }

                @Override public void onError(CameraDevice failed, int error) {
                    failed.close();
                    if (camera == failed) camera = null;
                    ready = false;
                    ErrorReporter.report(context, "baby", "E306", "המצלמה אינה זמינה", new IllegalStateException("CameraDevice error=" + error));
                }
            }, handler);
        } catch (Exception e) {
            ready = false;
            ErrorReporter.report(context, "baby", "E306", "המצלמה אינה זמינה. המיקרופון עדיין יכול לעבוד", e);
        }
    }

    private void createSession(CameraCharacteristics characteristics) {
        if (!running || camera == null || reader == null) return;
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (!running || camera == null || reader == null) {
                        configured.close();
                        return;
                    }
                    session = configured;
                    try {
                        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        builder.addTarget(reader.getSurface());
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        builder.set(CaptureRequest.JPEG_QUALITY, (byte) 35);
                        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics));
                        request = builder.build();
                        ready = true;
                        handler.removeCallbacks(captureTask);
                        handler.post(captureTask);
                    } catch (Exception e) {
                        ready = false;
                        ErrorReporter.report(context, "baby", "E307", "הגדרת המצלמה נכשלה", e);
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    ready = false;
                    ErrorReporter.report(context, "baby", "E307", "הגדרת המצלמה נכשלה", null);
                }
            }, handler);
        } catch (Exception e) {
            ready = false;
            ErrorReporter.report(context, "baby", "E307", "הגדרת המצלמה נכשלה", e);
        }
    }

    private final Runnable captureTask = new Runnable() {
        @Override public void run() {
            if (!running || handler == null) return;
            CameraCaptureSession current = session;
            CaptureRequest currentRequest = request;
            if (ready && gate.shouldCapture() && current != null && currentRequest != null) {
                try {
                    current.capture(currentRequest, null, handler);
                } catch (Exception e) {
                    ready = false;
                    ErrorReporter.report(context, "baby", "E308", "צילום תמונה לשידור נכשל", e);
                }
            }
            handler.postDelayed(this, CAPTURE_INTERVAL_MS);
        }
    };

    private void onImageAvailable(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null || !running || !gate.shouldCapture()) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
            if (jpeg.length > 0 && jpeg.length <= MAX_JPEG_BYTES) sink.onJpeg(jpeg);
            else if (jpeg.length > MAX_JPEG_BYTES) {
                ErrorReporter.report(context, "baby", "E309", "תמונת המצלמה גדולה מדי לשליחה", new IllegalStateException("jpegBytes=" + jpeg.length));
            }
        } catch (Exception e) {
            ErrorReporter.report(context, "baby", "E309", "קריאת תמונת המצלמה נכשלה", e);
        } finally {
            if (image != null) image.close();
        }
    }

    private static String chooseCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            if (fallback == null) fallback = id;
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return fallback;
    }

    private static Size chooseSize(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(320, 240);

        Size best = null;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * size.getHeight();
            if (area <= TARGET_AREA && (best == null || area > (long) best.getWidth() * best.getHeight())) best = size;
        }
        if (best != null) return best;

        best = sizes[0];
        for (Size size : sizes) {
            if ((long) size.getWidth() * size.getHeight() < (long) best.getWidth() * best.getHeight()) best = size;
        }
        return best;
    }

    private int jpegOrientation(CameraCharacteristics characteristics) {
        Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        int sensorDegrees = sensor == null ? 0 : sensor;
        int rotation = Surface.ROTATION_0;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) rotation = wm.getDefaultDisplay().getRotation();
        } catch (Exception e) {
            ErrorReporter.report(context, "baby", "E310", "לא ניתן לקרוא את כיוון המסך למצלמה", e);
        }

        int deviceDegrees;
        switch (rotation) {
            case Surface.ROTATION_90: deviceDegrees = 90; break;
            case Surface.ROTATION_180: deviceDegrees = 180; break;
            case Surface.ROTATION_270: deviceDegrees = 270; break;
            default: deviceDegrees = 0;
        }
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensorDegrees + deviceDegrees) % 360;
        }
        return (sensorDegrees - deviceDegrees + 360) % 360;
    }

    void stop() {
        running = false;
        ready = false;
        Handler currentHandler = handler;
        if (currentHandler != null) currentHandler.removeCallbacks(captureTask);
        request = null;

        if (session != null) {
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }
        if (camera != null) {
            try { camera.close(); } catch (Exception ignored) { }
            camera = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) { }
            reader = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        handler = null;
    }
}
