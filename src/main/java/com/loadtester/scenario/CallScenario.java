package com.loadtester.scenario;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.media.*;
import com.loadtester.report.TestReport;
import com.loadtester.sip.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates a complete A-calls-B scenario through the PBX.
 * <p>
 * Sequence:
 * 1. Phone B registers
 * 2. Phone A registers
 * 3. Phone A sends INVITE to B
 * 4. Phone B receives INVITE, sends 180 Ringing, then 200 OK
 * 5. Phone A receives 200 OK, sends ACK
 * 6. Both phones send/receive RTP tone for a configurable duration
 * 7. Verify tone was detected at both ends
 * 8. Phone A sends BYE
 * 9. Report results
 */
public class CallScenario {

    private static final Logger log = LoggerFactory.getLogger(CallScenario.class);

    private static final double TONE_FREQUENCY = 1000.0; // Hz
    private static final double TONE_AMPLITUDE = 0.8;
    private static final int DEFAULT_MEDIA_DURATION_MS = 3000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final SipAccountConfig phoneAConfig;
    private final SipAccountConfig phoneBConfig;
    private final SipStackFactory stackFactory;
    private final String localIp;
    private int mediaDurationMs = DEFAULT_MEDIA_DURATION_MS;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    // State
    private SipPhone phoneA;
    private SipPhone phoneB;
    private RtpSession rtpSessionA;
    private RtpSession rtpSessionB;

    public CallScenario(SipAccountConfig phoneAConfig, SipAccountConfig phoneBConfig,
                        SipStackFactory stackFactory, String localIp) {
        this.phoneAConfig = phoneAConfig;
        this.phoneBConfig = phoneBConfig;
        this.stackFactory = stackFactory;
        this.localIp = localIp;
    }

    public CallScenario(SipAccountConfig phoneAConfig, SipAccountConfig phoneBConfig,
                        SipStackFactory stackFactory, String localIp,
                        RtpSession rtpSessionA, RtpSession rtpSessionB) {
        this(phoneAConfig, phoneBConfig, stackFactory, localIp);
        this.rtpSessionA = rtpSessionA;
        this.rtpSessionB = rtpSessionB;
    }

    public void setMediaDurationMs(int mediaDurationMs) {
        this.mediaDurationMs = mediaDurationMs;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Execute the full call scenario and return a test report.
     */
    public TestReport execute() {
        TestReport report = new TestReport("A-calls-B");

        // Create RTP sessions if not injected
        if (rtpSessionA == null) rtpSessionA = new UdpRtpSession();
        if (rtpSessionB == null) rtpSessionB = new UdpRtpSession();

        // Latches for async SIP events
        CountDownLatch phoneARegistered = new CountDownLatch(1);
        CountDownLatch phoneBRegistered = new CountDownLatch(1);
        CountDownLatch phoneARinging = new CountDownLatch(1);
        CountDownLatch phoneAAnswered = new CountDownLatch(1);
        CountDownLatch phoneBIncomingCall = new CountDownLatch(1);
        CountDownLatch phoneABye = new CountDownLatch(1);

        AtomicReference<String> phoneAError = new AtomicReference<>();
        AtomicReference<String> phoneBError = new AtomicReference<>();
        AtomicReference<String> phoneBSdpOffer = new AtomicReference<>();

        // Create phone A with listener
        SipPhoneListener listenerA = new SipPhoneListener() {
            @Override public void onRegistered() { phoneARegistered.countDown(); }
            @Override public void onRegistrationFailed(int code, String reason) {
                phoneAError.set("Registration failed: " + code + " " + reason);
                phoneARegistered.countDown();
            }
            @Override public void onIncomingCall(String callerUri, String sdpOffer) {}
            @Override public void onRinging() { phoneARinging.countDown(); }
            @Override public void onAnswered(String sdpAnswer) { phoneAAnswered.countDown(); }
            @Override public void onCallFailed(int code, String reason) {
                phoneAError.set("Call failed: " + code + " " + reason);
                phoneAAnswered.countDown();
                phoneARinging.countDown();
            }
            @Override public void onBye() { phoneABye.countDown(); }
            @Override public void onError(Exception e) {
                phoneAError.set("Error: " + e.getMessage());
            }
        };

        // Create phone B with listener
        SipPhoneListener listenerB = new SipPhoneListener() {
            @Override public void onRegistered() { phoneBRegistered.countDown(); }
            @Override public void onRegistrationFailed(int code, String reason) {
                phoneBError.set("Registration failed: " + code + " " + reason);
                phoneBRegistered.countDown();
            }
            @Override public void onIncomingCall(String callerUri, String sdpOffer) {
                phoneBSdpOffer.set(sdpOffer);
                phoneBIncomingCall.countDown();
            }
            @Override public void onRinging() {}
            @Override public void onAnswered(String sdpAnswer) {}
            @Override public void onCallFailed(int code, String reason) {}
            @Override public void onBye() {}
            @Override public void onError(Exception e) {
                phoneBError.set("Error: " + e.getMessage());
            }
        };

        phoneA = new SipPhone(phoneAConfig, stackFactory, rtpSessionA, listenerA);
        phoneB = new SipPhone(phoneBConfig, stackFactory, rtpSessionB, listenerB);

        try {
            // --- Step 1: Initialize phones ---
            int portB = findFreeUdpPort();
            int portA = findFreeUdpPort();
            phoneB.initialize(stackFactory, localIp, portB);
            phoneA.initialize(stackFactory, localIp, portA);

            // --- Step 2: Register Phone B ---
            Instant start = Instant.now();
            phoneB.register();
            boolean regB = phoneBRegistered.await(timeoutSeconds, TimeUnit.SECONDS);
            Duration regBDur = Duration.between(start, Instant.now());
            if (!regB || phoneBError.get() != null) {
                report.addCheck(TestReport.CheckResult.fail("Phone B Registration",
                        phoneBError.get() != null ? phoneBError.get() : "Timeout", regBDur));
                report.finish();
                return report;
            }
            report.addCheck(TestReport.CheckResult.pass("Phone B Registration", regBDur));

            // --- Step 3: Register Phone A ---
            start = Instant.now();
            phoneA.register();
            boolean regA = phoneARegistered.await(timeoutSeconds, TimeUnit.SECONDS);
            Duration regADur = Duration.between(start, Instant.now());
            if (!regA || phoneAError.get() != null) {
                report.addCheck(TestReport.CheckResult.fail("Phone A Registration",
                        phoneAError.get() != null ? phoneAError.get() : "Timeout", regADur));
                report.finish();
                return report;
            }
            report.addCheck(TestReport.CheckResult.pass("Phone A Registration", regADur));

            // --- Step 4: Phone A calls Phone B ---
            start = Instant.now();
            phoneA.call(phoneBConfig.sipUri());

            // Wait for B to get INVITE
            boolean gotInvite = phoneBIncomingCall.await(timeoutSeconds, TimeUnit.SECONDS);
            if (!gotInvite) {
                report.addCheck(TestReport.CheckResult.fail("INVITE delivery",
                        "Phone B did not receive INVITE", Duration.between(start, Instant.now())));
                report.finish();
                return report;
            }
            report.addCheck(TestReport.CheckResult.pass("INVITE delivery", Duration.between(start, Instant.now())));

            // --- Step 5: Phone B sends 180 Ringing ---
            start = Instant.now();
            phoneB.sendRinging();

            boolean gotRinging = phoneARinging.await(timeoutSeconds, TimeUnit.SECONDS);
            Duration ringingDur = Duration.between(start, Instant.now());
            if (!gotRinging) {
                report.addCheck(TestReport.CheckResult.fail("180 Ringing", "Phone A did not receive 180", ringingDur));
            } else {
                report.addCheck(TestReport.CheckResult.pass("180 Ringing", ringingDur));
            }

            // --- Step 6: Phone B answers (200 OK) ---
            start = Instant.now();
            phoneB.answer();

            // Set RTP remote for B based on A's SDP offer
            if (phoneBSdpOffer.get() != null) {
                SdpUtil.SdpInfo aOffer = SdpUtil.parseSdp(phoneBSdpOffer.get());
                if (!rtpSessionB.isRunning()) {
                    rtpSessionB.start(0);
                }
                rtpSessionB.setRemote(InetAddress.getByName(aOffer.connectionAddress()), aOffer.mediaPort());
            }

            boolean answered = phoneAAnswered.await(timeoutSeconds, TimeUnit.SECONDS);
            Duration answerDur = Duration.between(start, Instant.now());
            if (!answered || phoneAError.get() != null) {
                report.addCheck(TestReport.CheckResult.fail("200 OK (Answer)",
                        phoneAError.get() != null ? phoneAError.get() : "Timeout", answerDur));
                report.finish();
                return report;
            }
            report.addCheck(TestReport.CheckResult.pass("200 OK (Answer)", answerDur));

            // --- Step 7: RTP media exchange ---
            start = Instant.now();
            sendAndReceiveMedia(report);
            Duration mediaDur = Duration.between(start, Instant.now());

            // --- Step 8: Hangup ---
            start = Instant.now();
            phoneA.hangup();
            boolean bye = phoneABye.await(timeoutSeconds, TimeUnit.SECONDS);
            Duration byeDur = Duration.between(start, Instant.now());
            if (!bye) {
                report.addCheck(TestReport.CheckResult.fail("BYE/200 OK", "No BYE acknowledgement", byeDur));
            } else {
                report.addCheck(TestReport.CheckResult.pass("BYE/200 OK", byeDur));
            }

        } catch (Exception e) {
            log.error("Scenario execution error", e);
            report.addCheck(TestReport.CheckResult.fail("Unexpected error", e.getMessage()));
        } finally {
            // Cleanup
            try { phoneA.shutdown(); } catch (Exception e) { /* ignore */ }
            try { phoneB.shutdown(); } catch (Exception e) { /* ignore */ }
            report.finish();
        }

        return report;
    }

    /**
     * Send RTP tone from both phones and verify reception.
     */
    private void sendAndReceiveMedia(TestReport report) {
        try {
            int totalPackets = mediaDurationMs / 20; // 20ms per packet
            long ssrcA = 0x12345678L;
            long ssrcB = 0x87654321L;

            // Send tone from A in a background thread
            CompletableFuture<Void> sendA = CompletableFuture.runAsync(() -> {
                try {
                    sendTone(rtpSessionA, totalPackets, ssrcA);
                } catch (Exception e) {
                    log.error("Error sending tone from A", e);
                }
            });

            // Send tone from B in a background thread
            CompletableFuture<Void> sendB = CompletableFuture.runAsync(() -> {
                try {
                    sendTone(rtpSessionB, totalPackets, ssrcB);
                } catch (Exception e) {
                    log.error("Error sending tone from B", e);
                }
            });

            // Receive packets at both sides concurrently
            CompletableFuture<Void> recvA = CompletableFuture.runAsync(() -> {
                try {
                    receivePackets(rtpSessionA, mediaDurationMs + 1000);
                } catch (Exception e) {
                    log.error("Error receiving at A", e);
                }
            });

            CompletableFuture<Void> recvB = CompletableFuture.runAsync(() -> {
                try {
                    receivePackets(rtpSessionB, mediaDurationMs + 1000);
                } catch (Exception e) {
                    log.error("Error receiving at B", e);
                }
            });

            // Wait for everything to complete
            CompletableFuture.allOf(sendA, sendB, recvA, recvB)
                    .get(mediaDurationMs + 5000, TimeUnit.MILLISECONDS);

            // Analyze received media
            analyzeReceivedMedia(rtpSessionA, "B->A", report);
            analyzeReceivedMedia(rtpSessionB, "A->B", report);

        } catch (Exception e) {
            report.addCheck(TestReport.CheckResult.fail("Media exchange", e.getMessage()));
        }
    }

    private void sendTone(RtpSession session, int packetCount, long ssrc) throws Exception {
        for (int seq = 0; seq < packetCount; seq++) {
            long sampleOffset = (long) seq * ToneGenerator.SAMPLES_PER_PACKET;
            byte[] payload = ToneGenerator.generatePcmuPacket(TONE_FREQUENCY, TONE_AMPLITUDE, sampleOffset);

            RtpPacket pkt = new RtpPacket(RtpPacket.PAYLOAD_TYPE_PCMU, seq,
                    sampleOffset, ssrc, payload);
            if (seq == 0) pkt.setMarker(true);

            session.sendPacket(pkt);
            Thread.sleep(20); // 20ms pacing
        }
    }

    private void receivePackets(RtpSession session, int durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            session.receivePacket(Math.min(remaining, 100));
        }
    }

    private void analyzeReceivedMedia(RtpSession session, String direction, TestReport report) {
        var packets = session.getReceivedPackets();
        if (packets.isEmpty()) {
            report.addCheck(TestReport.CheckResult.fail("RTP " + direction, "No RTP packets received"));
            report.addCheck(TestReport.CheckResult.fail("Tone " + direction, "No media to analyze"));
            return;
        }

        report.addCheck(TestReport.CheckResult.pass("RTP " + direction,
                packets.size() + " packets received", Duration.ZERO));

        // Concatenate payloads for tone analysis
        int totalBytes = packets.stream().mapToInt(RtpPacket::getPayloadLength).sum();
        byte[] allSamples = new byte[totalBytes];
        int offset = 0;
        for (RtpPacket pkt : packets) {
            byte[] payload = pkt.getPayload();
            System.arraycopy(payload, 0, allSamples, offset, payload.length);
            offset += payload.length;
        }

        ToneDetector.DetectionResult result = ToneDetector.detect(
                allSamples, TONE_FREQUENCY, 150.0, 300.0);
        if (result.detected()) {
            report.addCheck(TestReport.CheckResult.pass("Tone " + direction,
                    String.format("freq=%.0fHz energy=%.0f", result.estimatedFrequencyHz(), result.energyRms()),
                    Duration.ZERO));
        } else {
            report.addCheck(TestReport.CheckResult.fail("Tone " + direction,
                    result.reason(), Duration.ZERO));
        }
    }

    /**
     * Find a free UDP port by binding to port 0 and releasing it.
     */
    private static int findFreeUdpPort() throws Exception {
        try (DatagramSocket ds = new DatagramSocket(0)) {
            return ds.getLocalPort();
        }
    }
}
