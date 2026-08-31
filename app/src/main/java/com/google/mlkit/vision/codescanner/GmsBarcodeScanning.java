package com.google.mlkit.vision.codescanner;

import android.app.Activity;

public final class GmsBarcodeScanning {
    private GmsBarcodeScanning() {}

    public static GmsBarcodeScanner getClient(
            Activity activity,
            GmsBarcodeScannerOptions options
    ) {
        return new GmsBarcodeScanner(activity);
    }
}
