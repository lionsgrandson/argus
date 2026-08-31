package com.google.mlkit.vision.codescanner;

public final class GmsBarcodeScannerOptions {
    private GmsBarcodeScannerOptions() {}

    public static final class Builder {
        public Builder setBarcodeFormats(int... formats) {
            return this;
        }

        public Builder enableAutoZoom() {
            return this;
        }

        public GmsBarcodeScannerOptions build() {
            return new GmsBarcodeScannerOptions();
        }
    }
}
