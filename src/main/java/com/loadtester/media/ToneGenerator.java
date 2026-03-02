package com.loadtester.media;

/**
 * Generates audio tones encoded as G.711 PCMU (mu-law) samples.
 * <p>
 * Pure computation — no I/O, no framework dependencies.
 * All methods are stateless and can be called from any thread.
 */
public class ToneGenerator {

    /** Standard telephony sample rate. */
    public static final int SAMPLE_RATE = 8000;

    /** Samples per 20ms RTP packet at 8kHz. */
    public static final int SAMPLES_PER_PACKET = 160;

    private ToneGenerator() {
    }

    /**
     * Generate linear PCM 16-bit samples for a sine wave.
     *
     * @param frequencyHz tone frequency in Hz (e.g. 1000)
     * @param durationMs  duration in milliseconds
     * @param amplitude   peak amplitude (0.0 – 1.0)
     * @return array of 16-bit signed PCM samples
     */
    public static short[] generateLinearPcm(double frequencyHz, int durationMs, double amplitude) {
        if (frequencyHz <= 0) throw new IllegalArgumentException("frequencyHz must be > 0");
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be > 0");
        if (amplitude < 0 || amplitude > 1.0) throw new IllegalArgumentException("amplitude must be 0.0–1.0");

        int numSamples = (SAMPLE_RATE * durationMs) / 1000;
        short[] samples = new short[numSamples];
        double maxVal = Short.MAX_VALUE * amplitude;

        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            samples[i] = (short) (maxVal * Math.sin(2.0 * Math.PI * frequencyHz * t));
        }
        return samples;
    }

    /**
     * Generate G.711 mu-law encoded samples for a sine wave tone.
     *
     * @param frequencyHz tone frequency in Hz
     * @param durationMs  duration in milliseconds
     * @return mu-law encoded byte array (one byte per sample, 8kHz)
     */
    public static byte[] generatePcmu(double frequencyHz, int durationMs) {
        return generatePcmu(frequencyHz, durationMs, 0.8);
    }

    /**
     * Generate G.711 mu-law encoded samples for a sine wave tone with specified amplitude.
     *
     * @param frequencyHz tone frequency in Hz
     * @param durationMs  duration in milliseconds
     * @param amplitude   peak amplitude (0.0 – 1.0)
     * @return mu-law encoded byte array
     */
    public static byte[] generatePcmu(double frequencyHz, int durationMs, double amplitude) {
        short[] linear = generateLinearPcm(frequencyHz, durationMs, amplitude);
        byte[] encoded = new byte[linear.length];
        for (int i = 0; i < linear.length; i++) {
            encoded[i] = linearToMuLaw(linear[i]);
        }
        return encoded;
    }

    /**
     * Generate one RTP packet's worth of PCMU tone data (20ms = 160 samples).
     *
     * @param frequencyHz  tone frequency
     * @param amplitude    peak amplitude (0.0 – 1.0)
     * @param sampleOffset the sample offset into the waveform (for continuity across packets)
     * @return 160 bytes of mu-law encoded audio
     */
    public static byte[] generatePcmuPacket(double frequencyHz, double amplitude, long sampleOffset) {
        byte[] packet = new byte[SAMPLES_PER_PACKET];
        double maxVal = Short.MAX_VALUE * amplitude;
        for (int i = 0; i < SAMPLES_PER_PACKET; i++) {
            double t = (double) (sampleOffset + i) / SAMPLE_RATE;
            short sample = (short) (maxVal * Math.sin(2.0 * Math.PI * frequencyHz * t));
            packet[i] = linearToMuLaw(sample);
        }
        return packet;
    }

    /**
     * Encode a 16-bit linear PCM sample to G.711 mu-law.
     * Implementation follows ITU-T G.711.
     */
    public static byte linearToMuLaw(short sample) {
        final int MULAW_BIAS = 0x84; // 132 per ITU-T G.711
        final int CLIP = 32635;

        int sign = (sample >> 8) & 0x80;
        if (sign != 0) {
            sample = (short) -sample;
        }
        if (sample > CLIP) {
            sample = CLIP;
        }

        sample = (short) (sample + MULAW_BIAS);
        int exponent = 7;
        for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {
        }
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        byte muLawByte = (byte) (sign | (exponent << 4) | mantissa);
        return (byte) ~muLawByte;
    }

    /**
     * Decode a G.711 mu-law byte to 16-bit linear PCM.
     */
    public static short muLawToLinear(byte muLaw) {
        int muLawVal = ~muLaw & 0xFF;
        int sign = muLawVal & 0x80;
        int exponent = (muLawVal >> 4) & 0x07;
        int mantissa = muLawVal & 0x0F;
        int sample = ((mantissa << 3) + 0x84) << exponent;
        sample -= 0x84;
        return (short) (sign != 0 ? -sample : sample);
    }
}
