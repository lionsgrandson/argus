package com.example.babymonitor;

import java.util.concurrent.atomic.AtomicReference;

final class LiveVideoStore {
    static final class Frame {
        final byte[] jpeg;
        final long receivedAt;

        Frame(byte[] jpeg, long receivedAt) {
            this.jpeg = jpeg;
            this.receivedAt = receivedAt;
        }
    }

    private static final AtomicReference<Frame> LATEST = new AtomicReference<>();

    static void put(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        LATEST.set(new Frame(jpeg, System.currentTimeMillis()));
    }

    static Frame latest() {
        return LATEST.get();
    }

    static void clear() {
        LATEST.set(null);
    }

    private LiveVideoStore() {}
}
