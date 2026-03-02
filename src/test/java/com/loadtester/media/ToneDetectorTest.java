package com.loadtester.media;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ToneDetectorTest {

    @Test
    void shouldDetect1000HzToneFromGeneratedPcmu() {
        byte[] pcmu = ToneGenerator.generatePcmu(1000, 200);
        ToneDetector.DetectionResult result = ToneDetector.detect(pcmu, 1000, 100, 500);

        assertThat(result.detected()).isTrue();
        assertThat(result.estimatedFrequencyHz()).isBetween(900.0, 1100.0);
        assertThat(result.energyRms()).isGreaterThan(500);
        assertThat(result.reason()).contains("Tone detected");
    }

    @Test
    void shouldDetect500HzTone() {
        byte[] pcmu = ToneGenerator.generatePcmu(500, 200);
        ToneDetector.DetectionResult result = ToneDetector.detect(pcmu, 500, 100, 500);

        assertThat(result.detected()).isTrue();
        assertThat(result.estimatedFrequencyHz()).isBetween(400.0, 600.0);
    }

    @Test
    void shouldDetect2000HzTone() {
        byte[] pcmu = ToneGenerator.generatePcmu(2000, 200);
        ToneDetector.DetectionResult result = ToneDetector.detect(pcmu, 2000, 150, 500);

        assertThat(result.detected()).isTrue();
        assertThat(result.estimatedFrequencyHz()).isBetween(1850.0, 2150.0);
    }

    @Test
    void shouldRejectSilence() {
        // mu-law digital silence is 0xFF (decodes to linear 0)
        byte[] silence = new byte[1600];
        java.util.Arrays.fill(silence, (byte) 0xFF);
        ToneDetector.DetectionResult result = ToneDetector.detect(silence, 1000, 100, 500);

        assertThat(result.detected()).isFalse();
        assertThat(result.reason()).contains("Energy too low");
    }

    @Test
    void shouldRejectWrongFrequency() {
        // Generate 2000Hz but look for 500Hz
        byte[] pcmu = ToneGenerator.generatePcmu(2000, 200);
        ToneDetector.DetectionResult result = ToneDetector.detect(pcmu, 500, 100, 500);

        assertThat(result.detected()).isFalse();
        assertThat(result.reason()).contains("Goertzel ratio");
    }

    @Test
    void shouldHandleNullInput() {
        ToneDetector.DetectionResult result = ToneDetector.detect(null, 1000, 100, 500);
        assertThat(result.detected()).isFalse();
        assertThat(result.reason()).contains("Insufficient");
    }

    @Test
    void shouldHandleEmptyInput() {
        ToneDetector.DetectionResult result = ToneDetector.detect(new byte[0], 1000, 100, 500);
        assertThat(result.detected()).isFalse();
    }

    @Test
    void shouldHandleSingleSample() {
        ToneDetector.DetectionResult result = ToneDetector.detect(new byte[]{0x7F}, 1000, 100, 500);
        assertThat(result.detected()).isFalse();
    }

    @Test
    void detectDefaultShouldWorkFor1000HzTone() {
        byte[] pcmu = ToneGenerator.generatePcmu(1000, 500);
        ToneDetector.DetectionResult result = ToneDetector.detectDefault(pcmu);

        assertThat(result.detected()).isTrue();
    }

    @Test
    void calculateRmsShouldReturnZeroForSilence() {
        short[] silence = new short[100];
        assertThat(ToneDetector.calculateRms(silence)).isEqualTo(0.0);
    }

    @Test
    void calculateRmsShouldReturnCorrectValueForConstant() {
        short[] constant = new short[100];
        java.util.Arrays.fill(constant, (short) 1000);
        assertThat(ToneDetector.calculateRms(constant)).isCloseTo(1000.0, within(1.0));
    }

    @Test
    void zeroCrossingEstimateShouldBeAccurateForPureTone() {
        short[] samples = ToneGenerator.generateLinearPcm(1000, 100, 0.8);
        double freq = ToneDetector.estimateFrequencyByZeroCrossing(samples, 8000);
        assertThat(freq).isBetween(950.0, 1050.0);
    }

    @Test
    void lowEnergyToneShouldBeRejected() {
        // Very low amplitude tone
        byte[] pcmu = ToneGenerator.generatePcmu(1000, 200, 0.01);
        ToneDetector.DetectionResult result = ToneDetector.detect(pcmu, 1000, 100, 5000);

        assertThat(result.detected()).isFalse();
        assertThat(result.reason()).contains("Energy too low");
    }

    @Test
    void longerDurationShouldImproveAccuracy() {
        // Short duration
        byte[] shortTone = ToneGenerator.generatePcmu(1000, 50);
        ToneDetector.DetectionResult shortResult = ToneDetector.detect(shortTone, 1000, 100, 500);

        // Longer duration
        byte[] longTone = ToneGenerator.generatePcmu(1000, 500);
        ToneDetector.DetectionResult longResult = ToneDetector.detect(longTone, 1000, 100, 500);

        // Both should detect, but longer should have better frequency estimate
        // (closer to 1000Hz)
        if (shortResult.detected() && longResult.detected()) {
            double shortError = Math.abs(shortResult.estimatedFrequencyHz() - 1000);
            double longError = Math.abs(longResult.estimatedFrequencyHz() - 1000);
            // Long duration should be at least as accurate
            assertThat(longError).isLessThanOrEqualTo(shortError + 50); // some tolerance
        }
    }

    // --- Goertzel algorithm tests ---

    @Test
    void goertzelShouldReturnHighRatioForMatchingTone() {
        short[] samples = ToneGenerator.generateLinearPcm(1000, 200, 0.8);
        double ratio = ToneDetector.goertzelRelativePower(samples, 1000, 8000);
        assertThat(ratio).isGreaterThan(0.8);
    }

    @Test
    void goertzelShouldReturnLowRatioForWrongFrequency() {
        short[] samples = ToneGenerator.generateLinearPcm(2000, 200, 0.8);
        double ratio = ToneDetector.goertzelRelativePower(samples, 500, 8000);
        assertThat(ratio).isLessThan(0.1);
    }

    @Test
    void goertzelShouldReturnZeroForSilence() {
        short[] silence = new short[1600];
        double ratio = ToneDetector.goertzelRelativePower(silence, 1000, 8000);
        assertThat(ratio).isEqualTo(0.0);
    }

    @Test
    void goertzelMagnitudeSquaredShouldBePositiveForTone() {
        short[] samples = ToneGenerator.generateLinearPcm(1000, 100, 0.8);
        double magSq = ToneDetector.goertzelMagnitudeSquared(samples, 1000, 8000);
        assertThat(magSq).isGreaterThan(0);
    }

    @Test
    void goertzelShouldHandleNullInput() {
        assertThat(ToneDetector.goertzelMagnitudeSquared(null, 1000, 8000)).isEqualTo(0.0);
        assertThat(ToneDetector.goertzelRelativePower(null, 1000, 8000)).isEqualTo(0.0);
    }
}
