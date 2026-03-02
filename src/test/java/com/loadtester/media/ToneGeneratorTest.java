package com.loadtester.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ToneGeneratorTest {

    @Test
    void generateLinearPcmShouldProduceCorrectNumberOfSamples() {
        // 8000 samples/sec * 0.1 sec = 800 samples
        short[] samples = ToneGenerator.generateLinearPcm(1000, 100, 0.8);
        assertThat(samples).hasSize(800);
    }

    @Test
    void generateLinearPcmShouldProduceCorrectSampleCountFor20ms() {
        // 20ms at 8kHz = 160 samples
        short[] samples = ToneGenerator.generateLinearPcm(1000, 20, 0.8);
        assertThat(samples).hasSize(160);
    }

    @Test
    void generateLinearPcmShouldProduceSineWave() {
        // Generate a 1000Hz tone for 10ms
        short[] samples = ToneGenerator.generateLinearPcm(1000, 10, 1.0);

        // First sample should be zero (sin(0) = 0)
        assertThat(samples[0]).isEqualTo((short) 0);

        // The waveform should have positive and negative values
        boolean hasPositive = false;
        boolean hasNegative = false;
        for (short s : samples) {
            if (s > 0) hasPositive = true;
            if (s < 0) hasNegative = true;
        }
        assertThat(hasPositive).isTrue();
        assertThat(hasNegative).isTrue();
    }

    @Test
    void generateLinearPcmAmplitudeShouldBeRespected() {
        short[] fullAmplitude = ToneGenerator.generateLinearPcm(1000, 100, 1.0);
        short[] halfAmplitude = ToneGenerator.generateLinearPcm(1000, 100, 0.5);

        // Find max absolute value in each
        int maxFull = 0, maxHalf = 0;
        for (short s : fullAmplitude) maxFull = Math.max(maxFull, Math.abs(s));
        for (short s : halfAmplitude) maxHalf = Math.max(maxHalf, Math.abs(s));

        // Half amplitude should be roughly half of full amplitude
        assertThat((double) maxHalf / maxFull).isBetween(0.4, 0.6);
    }

    @Test
    void generateLinearPcmFrequencyShouldMatchViaZeroCrossings() {
        // 1000Hz for 100ms at 8kHz = 800 samples
        // A 1000Hz tone should have ~200 zero crossings in 100ms
        // (2 crossings per cycle * 100 cycles)
        short[] samples = ToneGenerator.generateLinearPcm(1000, 100, 0.8);

        int zeroCrossings = countZeroCrossings(samples);
        // Allow some tolerance (edge effects)
        assertThat(zeroCrossings).isBetween(195, 205);
    }

    @Test
    void generatePcmuShouldProduceCorrectLength() {
        byte[] pcmu = ToneGenerator.generatePcmu(1000, 100);
        // 8000 samples/sec * 0.1 sec = 800 bytes (1 byte per mu-law sample)
        assertThat(pcmu).hasSize(800);
    }

    @Test
    void generatePcmuPacketShouldProduce160Bytes() {
        byte[] packet = ToneGenerator.generatePcmuPacket(1000, 0.8, 0);
        assertThat(packet).hasSize(160);
    }

    @Test
    void generatePcmuPacketShouldBeContinuousAcrossPackets() {
        // Generate two consecutive packets
        byte[] packet1 = ToneGenerator.generatePcmuPacket(1000, 0.8, 0);
        byte[] packet2 = ToneGenerator.generatePcmuPacket(1000, 0.8, 160);

        // Both should have content (not all zeros)
        assertThat(packet1).isNotEqualTo(new byte[160]);
        assertThat(packet2).isNotEqualTo(new byte[160]);
    }

    @Test
    void muLawEncodingRoundTripShouldPreserveSignal() {
        // mu-law is lossy, but the round-trip should preserve the sign
        // and approximate magnitude
        short[] testValues = {0, 100, 1000, 10000, 32000, -100, -1000, -10000, -32000};

        for (short original : testValues) {
            byte encoded = ToneGenerator.linearToMuLaw(original);
            short decoded = ToneGenerator.muLawToLinear(encoded);

            // Same sign
            if (original > 0) assertThat(decoded).isGreaterThan((short) 0);
            else if (original < 0) assertThat(decoded).isLessThan((short) 0);

            // Reasonable approximation (mu-law quantization error)
            if (Math.abs(original) > 100) {
                double ratio = (double) decoded / original;
                assertThat(ratio).isBetween(0.7, 1.3);
            }
        }
    }

    @Test
    void muLawZeroShouldEncodeAndDecodeNearZero() {
        byte encoded = ToneGenerator.linearToMuLaw((short) 0);
        short decoded = ToneGenerator.muLawToLinear(encoded);
        assertThat(Math.abs(decoded)).isLessThan(100);
    }

    @Test
    void invalidParametersShouldThrow() {
        assertThatThrownBy(() -> ToneGenerator.generateLinearPcm(0, 100, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToneGenerator.generateLinearPcm(-1, 100, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToneGenerator.generateLinearPcm(1000, 0, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToneGenerator.generateLinearPcm(1000, 100, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToneGenerator.generateLinearPcm(1000, 100, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void differentFrequenciesShouldProduceDifferentZeroCrossingRates() {
        short[] tone500 = ToneGenerator.generateLinearPcm(500, 100, 0.8);
        short[] tone2000 = ToneGenerator.generateLinearPcm(2000, 100, 0.8);

        int crossings500 = countZeroCrossings(tone500);
        int crossings2000 = countZeroCrossings(tone2000);

        // 2000Hz should have roughly 4x the zero crossings of 500Hz
        assertThat((double) crossings2000 / crossings500).isBetween(3.5, 4.5);
    }

    private int countZeroCrossings(short[] samples) {
        int count = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i - 1] >= 0 && samples[i] < 0) ||
                    (samples[i - 1] < 0 && samples[i] >= 0)) {
                count++;
            }
        }
        return count;
    }
}
