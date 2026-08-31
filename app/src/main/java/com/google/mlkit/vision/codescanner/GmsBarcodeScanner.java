package com.google.mlkit.vision.codescanner;

import android.app.Activity;
import android.content.Intent;

import com.example.babymonitor.ArgusQrScannerActivity;
import com.google.mlkit.vision.barcode.common.Barcode;

public final class GmsBarcodeScanner {
    private final Activity activity;
    private static ScanTask pendingTask;

    GmsBarcodeScanner(Activity activity) {
        this.activity = activity;
    }

    public ScanTask startScan() {
        ScanTask task = new ScanTask();
        synchronized (GmsBarcodeScanner.class) {
            pendingTask = task;
        }

        try {
            activity.startActivity(new Intent(activity, ArgusQrScannerActivity.class));
        } catch (RuntimeException e) {
            synchronized (GmsBarcodeScanner.class) {
                if (pendingTask == task) pendingTask = null;
            }
            task.fail(e);
        }

        return task;
    }

    public static void deliverSuccess(String rawValue) {
        ScanTask task;
        synchronized (GmsBarcodeScanner.class) {
            task = pendingTask;
            pendingTask = null;
        }
        if (task != null) task.succeed(new Barcode(rawValue));
    }

    public static void deliverCanceled() {
        ScanTask task;
        synchronized (GmsBarcodeScanner.class) {
            task = pendingTask;
            pendingTask = null;
        }
        if (task != null) task.cancel();
    }

    public static void deliverFailure(Exception error) {
        ScanTask task;
        synchronized (GmsBarcodeScanner.class) {
            task = pendingTask;
            pendingTask = null;
        }
        if (task != null) task.fail(error);
    }

    public static final class ScanTask {
        public interface OnSuccessListener {
            void onSuccess(Barcode barcode);
        }

        public interface OnCanceledListener {
            void onCanceled();
        }

        public interface OnFailureListener {
            void onFailure(Exception error);
        }

        private static final int STATE_PENDING = 0;
        private static final int STATE_SUCCESS = 1;
        private static final int STATE_CANCELED = 2;
        private static final int STATE_FAILED = 3;

        private int state = STATE_PENDING;
        private Barcode barcode;
        private Exception failure;
        private OnSuccessListener successListener;
        private OnCanceledListener canceledListener;
        private OnFailureListener failureListener;

        public synchronized ScanTask addOnSuccessListener(OnSuccessListener listener) {
            successListener = listener;
            dispatch();
            return this;
        }

        public synchronized ScanTask addOnCanceledListener(OnCanceledListener listener) {
            canceledListener = listener;
            dispatch();
            return this;
        }

        public synchronized ScanTask addOnFailureListener(OnFailureListener listener) {
            failureListener = listener;
            dispatch();
            return this;
        }

        synchronized void succeed(Barcode value) {
            if (state != STATE_PENDING) return;
            state = STATE_SUCCESS;
            barcode = value;
            dispatch();
        }

        synchronized void cancel() {
            if (state != STATE_PENDING) return;
            state = STATE_CANCELED;
            dispatch();
        }

        synchronized void fail(Exception error) {
            if (state != STATE_PENDING) return;
            state = STATE_FAILED;
            failure = error;
            dispatch();
        }

        private void dispatch() {
            if (state == STATE_SUCCESS && successListener != null) {
                OnSuccessListener listener = successListener;
                successListener = null;
                listener.onSuccess(barcode);
            } else if (state == STATE_CANCELED && canceledListener != null) {
                OnCanceledListener listener = canceledListener;
                canceledListener = null;
                listener.onCanceled();
            } else if (state == STATE_FAILED && failureListener != null) {
                OnFailureListener listener = failureListener;
                failureListener = null;
                listener.onFailure(failure);
            }
        }
    }
}
