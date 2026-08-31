package com.google.mlkit.vision.barcode.common;

/**
 * Small compatibility wrapper used by ARGUS so MainActivity no longer depends
 * on the Google Play Services code scanner implementation.
 */
public final class Barcode {
    public static final int FORMAT_QR_CODE = 256;

    private final String rawValue;

    public Barcode(String rawValue) {
        this.rawValue = rawValue;
    }

    public String getRawValue() {
        return rawValue;
    }
}
