package com.example.babymonitor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

final class PairingConfig {
    private static final String CODE_PREFIX = "A4";
    private static final String SHORT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int RANDOM_CODE_LENGTH = 10;
    private static final int FULL_CODE_LENGTH = CODE_PREFIX.length() + RANDOM_CODE_LENGTH;

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
        StringBuilder code = new StringBuilder(FULL_CODE_LENGTH);
        code.append(CODE_PREFIX);
        for (int i = 0; i < RANDOM_CODE_LENGTH; i++) {
            code.append(SHORT_ALPHABET.charAt(random.nextInt(SHORT_ALPHABET.length())));
        }
        return fromShortCode(code.toString());
    }

    String encode() {
        return shortCode;
    }

    boolean isShortCode() {
        return true;
    }

    static PairingConfig parse(String code) {
        if (code == null || code.trim().isEmpty()) {
            ErrorReporter.report("setup", "E101", "קוד החיבור ריק", null);
            throw new IllegalArgumentException("E101 empty pairing code");
        }

        String trimmed = code.trim();
        String normalized = trimmed.toUpperCase(Locale.US).replaceAll("[\\s-]", "");

        if (normalized.length() == FULL_CODE_LENGTH
                && normalized.startsWith(CODE_PREFIX)
                && isValidRandomPart(normalized.substring(CODE_PREFIX.length()))) {
            return fromShortCode(normalized);
        }

        if (looksLikeOldCode(trimmed, normalized)) {
            ErrorReporter.report("setup", "E102", "קוד החיבור ישן ואופס. צרו קוד חדש", null);
            throw new IllegalArgumentException("E102 obsolete pairing code");
        }

        ErrorReporter.report("setup", "E101", "קוד החיבור אינו תקין", null);
        throw new IllegalArgumentException("E101 invalid pairing code");
    }

    private static PairingConfig fromShortCode(String code) {
        try {
            byte[] roomDigest = sha256("ARGUS4|room|" + code);
            byte[] authDigest = sha256("ARGUS4|auth|" + code);
            byte[] key = sha256("ARGUS4|key|" + code);
            String roomId = b64(Arrays.copyOf(roomDigest, 12));
            String authToken = b64(Arrays.copyOf(authDigest, 16));
            return new PairingConfig(roomId, authToken, key, code);
        } catch (Exception e) {
            ErrorReporter.report("setup", "E103", "לא ניתן ליצור קוד חיבור", e);
            throw new IllegalStateException("E103 unable to create ARGUS pairing", e);
        }
    }

    private static boolean looksLikeOldCode(String trimmed, String normalized) {
        if (trimmed.startsWith("BM2.")) return true;
        if (normalized.startsWith("ARGUS")) return true;
        if (normalized.length() == 10) {
            for (int i = 0; i < normalized.length(); i++) {
                if (SHORT_ALPHABET.indexOf(normalized.charAt(i)) < 0) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean isValidRandomPart(String part) {
        if (part.length() != RANDOM_CODE_LENGTH) return false;
        for (int i = 0; i < part.length(); i++) {
            if (SHORT_ALPHABET.indexOf(part.charAt(i)) < 0) return false;
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
}
