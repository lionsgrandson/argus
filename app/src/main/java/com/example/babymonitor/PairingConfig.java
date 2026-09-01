package com.example.babymonitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

final class PairingConfig {
    private static final String SHORT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int SHORT_CODE_LENGTH = 10;

    final String roomId;
    final String authToken;
    final byte[] encryptionKey;
    private final String shortCode;

    private PairingConfig(String roomId, String authToken, byte[] encryptionKey, String shortCode) {
        this.roomId = roomId;
        this.authToken = authToken;
        this.encryptionKey = encryptionKey;
        this.shortCode = shortCode;
    }

    static PairingConfig generate() {
        SecureRandom random = new SecureRandom();
        StringBuilder code = new StringBuilder(SHORT_CODE_LENGTH);
        for (int i = 0; i < SHORT_CODE_LENGTH; i++) {
            code.append(SHORT_ALPHABET.charAt(random.nextInt(SHORT_ALPHABET.length())));
        }
        return fromShortCode(code.toString());
    }

    String encode() {
        if (shortCode != null) return shortCode;
        return "BM2." + roomId + "." + authToken + "." + b64(encryptionKey);
    }

    boolean isShortCode() {
        return shortCode != null;
    }

    static PairingConfig parse(String code) {
        if (code == null) throw new IllegalArgumentException("Pairing code is empty");
        String trimmed = code.trim();
        String normalized = trimmed.toUpperCase(Locale.US).replaceAll("[\\s-]", "");

        if (normalized.length() == SHORT_CODE_LENGTH && isValidShortCode(normalized)) {
            return fromShortCode(normalized);
        }
        if (normalized.startsWith("ARGUS")
                && normalized.length() == SHORT_CODE_LENGTH + 5
                && isValidShortCode(normalized.substring(5))) {
            return fromShortCode(normalized.substring(5));
        }

        String[] parts = trimmed.split("\\.");
        if (parts.length != 4 || !"BM2".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid ARGUS pairing code");
        }
        byte[] room = decode(parts[1]);
        byte[] auth = decode(parts[2]);
        byte[] key = decode(parts[3]);
        if (room.length != 12 || auth.length != 16 || key.length != 32) {
            throw new IllegalArgumentException("Invalid pairing code length");
        }
        return new PairingConfig(parts[1], parts[2], key, null);
    }

    private static PairingConfig fromShortCode(String code) {
        try {
            byte[] roomDigest = sha256("ARGUS3|room|" + code);
            byte[] authDigest = sha256("ARGUS3|auth|" + code);
            byte[] key = sha256("ARGUS3|key|" + code);
            String roomId = b64(Arrays.copyOf(roomDigest, 12));
            String authToken = b64(Arrays.copyOf(authDigest, 16));
            return new PairingConfig(roomId, authToken, key, code);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create ARGUS pairing", e);
        }
    }

    private static boolean isValidShortCode(String code) {
        if (code.length() != SHORT_CODE_LENGTH) return false;
        for (int i = 0; i < code.length(); i++) {
            if (SHORT_ALPHABET.indexOf(code.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static byte[] sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
