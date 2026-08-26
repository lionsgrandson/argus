package com.example.babymonitor;

import java.util.Base64;
import java.security.SecureRandom;

final class PairingConfig {
    final String roomId;
    final String authToken;
    final byte[] encryptionKey;

    private PairingConfig(String roomId, String authToken, byte[] encryptionKey) {
        this.roomId = roomId;
        this.authToken = authToken;
        this.encryptionKey = encryptionKey;
    }

    static PairingConfig generate() {
        SecureRandom random = new SecureRandom();
        byte[] room = new byte[12];
        byte[] auth = new byte[16];
        byte[] key = new byte[32];
        random.nextBytes(room);
        random.nextBytes(auth);
        random.nextBytes(key);
        return new PairingConfig(b64(room), b64(auth), key);
    }

    String encode() {
        return "BM2." + roomId + "." + authToken + "." + b64(encryptionKey);
    }

    static PairingConfig parse(String code) {
        if (code == null) throw new IllegalArgumentException("Pairing code is empty");
        String[] parts = code.trim().split("\\.");
        if (parts.length != 4 || !"BM2".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid Baby Monitor pairing code");
        }
        byte[] room = decode(parts[1]);
        byte[] auth = decode(parts[2]);
        byte[] key = decode(parts[3]);
        if (room.length != 12 || auth.length != 16 || key.length != 32) {
            throw new IllegalArgumentException("Invalid pairing code length");
        }
        return new PairingConfig(parts[1], parts[2], key);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
