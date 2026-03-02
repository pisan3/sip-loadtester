package com.loadtester.media;

/**
 * Detects the presence of a sine wave tone in G.711 PCMU audio samples.
 * <p>
 * Primary detection uses the Goertzel algorithm to measure energy at the
 * target frequency relative to total signal energy. This is robust against
 * noise, PBX transcoding artifacts, and mu-law quantization effects.
 * <p>
 * Zero-crossing is retained as a secondary frequency estimator for reporting.
 * Pure computation — no I/O dependencies.
 */
public class ToneDetector {

    private ToneDetector() {
    }

    /**
     * Result of tone detection analysis.
     */
    public record DetectionResult(
            boolean detected,
            double estimatedFrequencyHz,
            double energyRms,
            String reason
    ) {
    }

    /**
     * Minimum ratio of target-frequency power to total signal power
     * for a tone to be considered "detected". A pure tone would be ~1.0;
     * through a PBX with some noise/artifacts, 0.3 is a reasonable threshold.
     */
    private static final double MIN_GOERTZEL_RATIO = 0.3;

    /**
     * Analyze PCMU samples and determine if the expected tone is present.
     *
     * @param pcmuSamples       mu-law encoded audio bytes
     * @param expectedFrequency the frequency to look for (Hz)
     * @param frequencyTolerance how far off the estimated frequency can be (Hz) — used for zero-crossing fallback
     * @param minEnergyRms      minimum RMS energy to consider as "signal present"
     * @return detection result
     */
    public static DetectionResult detect(byte[] pcmuSamples, double expectedFrequency,
                                         double frequencyTolerance, double minEnergyRms) {
        if (pcmuSamples == null || pcmuSamples.length < 2) {
            return new DetectionResult(false, 0, 0, "Insufficient samples");
        }

        // Decode mu-law to linear PCM
        short[] linear = new short[pcmuSamples.length];
        for (int i = 0; i < pcmuSamples.length; i++) {
            linear[i] = ToneGenerator.muLawToLinear(pcmuSamples[i]);
        }

        return detectLinear(linear, expectedFrequency, frequencyTolerance, minEnergyRms);
    }

    /**
     * Analyze linear PCM samples for tone presence.
     */
    public static DetectionResult detectLinear(short[] samples, double expectedFrequency,
                                               double frequencyTolerance, double minEnergyRms) {
        if (samples == null || samples.length < 2) {
            return new DetectionResult(false, 0, 0, "Insufficient samples");
        }

        double energyRms = calculateRms(samples);
        if (energyRms < minEnergyRms) {
            return new DetectionResult(false, 0, energyRms,
                    String.format("Energy too low: %.1f < %.1f", energyRms, minEnergyRms));
        }

        // Primary detection: Goertzel algorithm
        double goertzelRatio = goertzelRelativePower(samples, expectedFrequency, ToneGenerator.SAMPLE_RATE);

        // Estimate frequency via zero-crossing for the report
        double estimatedFreq = estimateFrequencyByZeroCrossing(samples, ToneGenerator.SAMPLE_RATE);

        if (goertzelRatio >= MIN_GOERTZEL_RATIO) {
            return new DetectionResult(true, estimatedFreq, energyRms,
                    String.format("Tone detected (Goertzel ratio=%.2f, zeroCross=%.0fHz)",
                            goertzelRatio, estimatedFreq));
        }

        // Goertzel failed — report why
        return new DetectionResult(false, estimatedFreq, energyRms,
                String.format("Goertzel ratio=%.3f < %.3f (estimated=%.1fHz, expected=%.1fHz)",
                        goertzelRatio, MIN_GOERTZEL_RATIO, estimatedFreq, expectedFrequency));
    }

    /**
     * Calculate RMS energy of linear PCM samples.
     */
    public static double calculateRms(short[] samples) {
        if (samples == null || samples.length == 0) return 0;
        double sumSquares = 0;
        for (short s : samples) {
            sumSquares += (double) s * s;
        }
        return Math.sqrt(sumSquares / samples.length);
    }

    /**
     * Estimate frequency using zero-crossing rate.
     * <p>
     * For a pure sine wave, the number of zero crossings per second
     * equals twice the frequency.
     */
    public static double estimateFrequencyByZeroCrossing(short[] samples, int sampleRate) {
        if (samples == null || samples.length < 2) return 0;

        int zeroCrossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i - 1] >= 0 && samples[i] < 0) ||
                    (samples[i - 1] < 0 && samples[i] >= 0)) {
                zeroCrossings++;
            }
        }

        double durationSec = (double) samples.length / sampleRate;
        return zeroCrossings / (2.0 * durationSec);
    }

    /**
     * Compute the Goertzel magnitude squared for a specific target frequency.
     * <p>
     * The Goertzel algorithm efficiently computes a single DFT bin,
     * equivalent to measuring energy at exactly the target frequency.
     *
     * @param samples    linear PCM samples
     * @param targetFreq target frequency in Hz
     * @param sampleRate sample rate in Hz
     * @return magnitude squared of the DFT bin at targetFreq
     */
    public static double goertzelMagnitudeSquared(short[] samples, double targetFreq, int sampleRate) {
        if (samples == null || samples.length == 0) return 0;

        int N = samples.length;
        double k = (targetFreq * N) / sampleRate;
        double w = (2.0 * Math.PI * k) / N;
        double cosW = Math.cos(w);
        double coeff = 2.0 * cosW;

        double s0 = 0, s1 = 0, s2 = 0;
        for (int i = 0; i < N; i++) {
            s0 = samples[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        // Magnitude squared = s1^2 + s2^2 - coeff * s1 * s2
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    /**
     * Compute the ratio of power at the target frequency to total signal power.
     * <p>
     * For a pure tone at the target frequency this approaches 1.0.
     * For noise or a different frequency this will be close to 0.0.
     *
     * @param samples    linear PCM samples
     * @param targetFreq target frequency in Hz
     * @param sampleRate sample rate in Hz
     * @return ratio in [0.0, 1.0+] — values above 1.0 are possible due to normalization
     */
    public static double goertzelRelativePower(short[] samples, double targetFreq, int sampleRate) {
        if (samples == null || samples.length == 0) return 0;

        int N = samples.length;
        double magSq = goertzelMagnitudeSquared(samples, targetFreq, sampleRate);

        // Total energy (sum of squares)
        double totalEnergy = 0;
        for (short s : samples) {
            totalEnergy += (double) s * s;
        }

        if (totalEnergy == 0) return 0;

        // Normalize: Goertzel magnitude squared for a pure tone of amplitude A
        // over N samples is (A * N/2)^2 = A^2 * N^2/4.
        // Total energy for that tone is A^2 * N/2.
        // So ratio = magSq / (totalEnergy * N/2) should be ~1.0 for a pure tone.
        double ratio = magSq / (totalEnergy * N / 2.0);
        return ratio;
    }

    /**
     * Convenience method with sensible defaults for 1000Hz tone detection.
     */
    public static DetectionResult detectDefault(byte[] pcmuSamples) {
        return detect(pcmuSamples, 1000.0, 100.0, 500.0);
    }
}
