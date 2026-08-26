package com.example.babymonitor;

final class MuLaw {
    private static final int BIAS = 0x84;
    private static final int CLIP = 32635;

    static byte encode(short pcm) {
        int sample = pcm;
        int sign = (sample >> 8) & 0x80;
        if (sign != 0) sample = -sample;
        if (sample > CLIP) sample = CLIP;
        sample += BIAS;

        int exponent = 7;
        for (int mask = 0x4000; (sample & mask) == 0 && exponent > 0; mask >>= 1) exponent--;
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }

    static short decode(byte ulaw) {
        int u = (~ulaw) & 0xFF;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int sample = ((mantissa << 3) + BIAS) << exponent;
        sample -= BIAS;
        if (sign != 0) sample = -sample;
        return (short) sample;
    }

    static int encodePcm16(byte[] pcm, int pcmBytes, byte[] out) {
        int samples = Math.min(pcmBytes / 2, out.length);
        for (int i = 0; i < samples; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            short s = (short) ((hi << 8) | lo);
            out[i] = encode(s);
        }
        return samples;
    }

    static int decodeToPcm16(byte[] ulaw, byte[] out) {
        int samples = Math.min(ulaw.length, out.length / 2);
        for (int i = 0; i < samples; i++) {
            short s = decode(ulaw[i]);
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
        }
        return samples * 2;
    }

    private MuLaw() {}
}
