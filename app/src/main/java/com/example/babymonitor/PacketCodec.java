package com.example.babymonitor;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class PacketCodec {
    static final byte VERSION = 2;
    static final byte TYPE_AUDIO = 1;
    static final byte TYPE_STATUS = 2;
    static final byte TYPE_VIDEO_JPEG = 3;
    private static final int HEADER = 18;
    private final SecretKeySpec key;

    static final class Decoded {
        final byte type;
        final long session;
        final long sequence;
        final byte[] payload;
        Decoded(byte type, long session, long sequence, byte[] payload) {
            this.type = type; this.session = session; this.sequence = sequence; this.payload = payload;
        }
    }

    PacketCodec(byte[] rawKey) {
        if (rawKey == null || rawKey.length != 32) throw new IllegalArgumentException("Need a 256-bit key");
        this.key = new SecretKeySpec(rawKey.clone(), "AES");
    }

    byte[] encrypt(byte type, long session, long sequence, byte[] plaintext, int length) throws GeneralSecurityException {
        byte[] header = ByteBuffer.allocate(HEADER).put(VERSION).put(type).putLong(session).putLong(sequence).array();
        byte[] nonce = nonce(session, sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        byte[] encrypted = cipher.doFinal(plaintext, 0, length);
        byte[] packet = new byte[HEADER + encrypted.length];
        System.arraycopy(header, 0, packet, 0, HEADER);
        System.arraycopy(encrypted, 0, packet, HEADER, encrypted.length);
        return packet;
    }

    Decoded decrypt(byte[] packet) throws GeneralSecurityException {
        if (packet == null || packet.length < HEADER + 16) throw new GeneralSecurityException("Packet too short");
        ByteBuffer b = ByteBuffer.wrap(packet);
        byte version = b.get();
        if (version != VERSION) throw new GeneralSecurityException("Unsupported protocol");
        byte type = b.get();
        long session = b.getLong();
        long sequence = b.getLong();
        byte[] header = new byte[HEADER];
        System.arraycopy(packet, 0, header, 0, HEADER);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(session, sequence)));
        cipher.updateAAD(header);
        byte[] clear = cipher.doFinal(packet, HEADER, packet.length - HEADER);
        return new Decoded(type, session, sequence, clear);
    }

    private static byte[] nonce(long session, long sequence) {
        if ((sequence & 0xFFFFFFFF00000000L) != 0) throw new IllegalStateException("GCM sequence exhausted");
        return ByteBuffer.allocate(12).putLong(session).putInt((int) sequence).array();
    }
}
