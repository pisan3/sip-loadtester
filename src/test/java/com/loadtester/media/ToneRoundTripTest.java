package com.loadtester.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end test: Generate tone -> encode to RTP -> send over loopback UDP -> receive -> detect tone.
 * <p>
 * This validates the entire media pipeline without any SIP involvement.
 */
class ToneRoundTripTest {

    private UdpRtpSession sessionA;
    private UdpRtpSession sessionB;

    @AfterEach
    void tearDown() {
        if (sessionA != null) sessionA.stop();
        if (sessionB != null) sessionB.stop();
    }

    @Test
    void toneFromAToB_ShouldBeDetectedAtB() throws Exception {
        sessionA = new UdpRtpSession();
        sessionB = new UdpRtpSession();
        sessionA.start(0);
        sessionB.start(0);

        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        sessionA.setRemote(localhost, sessionB.getLocalPort());

        double frequency = 1000.0;
        double amplitude = 0.8;
        long ssrc = 0xCAFEBABEL;
        int packetCount = 50; // 1 second of audio (50 * 20ms)

        // Send tone from A
        for (int seq = 0; seq < packetCount; seq++) {
            long sampleOffset = (long) seq * ToneGenerator.SAMPLES_PER_PACKET;
            byte[] payload = ToneGenerator.generatePcmuPacket(frequency, amplitude, sampleOffset);

            RtpPacket pkt = new RtpPacket(RtpPacket.PAYLOAD_TYPE_PCMU, seq,
                    sampleOffset, ssrc, payload);
            if (seq == 0) pkt.setMarker(true);

            sessionA.sendPacket(pkt);
            Thread.sleep(5); // Small delay to not overwhelm the socket
        }

        // Receive at B
        long deadline = System.currentTimeMillis() + 3000;
        while (sessionB.getReceivedPackets().size() < packetCount &&
                System.currentTimeMillis() < deadline) {
            sessionB.receivePacket(100);
        }

        var receivedPackets = sessionB.getReceivedPackets();
        assertThat(receivedPackets).isNotEmpty();
        assertThat(receivedPackets.size()).isGreaterThanOrEqualTo(packetCount / 2); // Allow some loss

        // Concatenate payloads
        int totalBytes = receivedPackets.stream().mapToInt(RtpPacket::getPayloadLength).sum();
        byte[] allSamples = new byte[totalBytes];
        int offset = 0;
        for (RtpPacket pkt : receivedPackets) {
            byte[] pl = pkt.getPayload();
            System.arraycopy(pl, 0, allSamples, offset, pl.length);
            offset += pl.length;
        }

        // Detect tone
        ToneDetector.DetectionResult result = ToneDetector.detect(allSamples, 1000, 150, 300);
        assertThat(result.detected())
                .as("Tone should be detected at B. Reason: %s", result.reason())
                .isTrue();
        assertThat(result.estimatedFrequencyHz()).isBetween(850.0, 1150.0);
    }

    @Test
    void bidirectionalTone_ShouldBeDetectedAtBothEnds() throws Exception {
        sessionA = new UdpRtpSession();
        sessionB = new UdpRtpSession();
        sessionA.start(0);
        sessionB.start(0);

        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        sessionA.setRemote(localhost, sessionB.getLocalPort());
        sessionB.setRemote(localhost, sessionA.getLocalPort());

        int packetCount = 50;
        long ssrcA = 0x11111111L;
        long ssrcB = 0x22222222L;

        // Send from both directions concurrently
        Thread senderA = new Thread(() -> {
            try {
                for (int seq = 0; seq < packetCount; seq++) {
                    long so = (long) seq * ToneGenerator.SAMPLES_PER_PACKET;
                    byte[] payload = ToneGenerator.generatePcmuPacket(1000, 0.8, so);
                    sessionA.sendPacket(new RtpPacket(0, seq, so, ssrcA, payload));
                    Thread.sleep(5);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Thread senderB = new Thread(() -> {
            try {
                for (int seq = 0; seq < packetCount; seq++) {
                    long so = (long) seq * ToneGenerator.SAMPLES_PER_PACKET;
                    byte[] payload = ToneGenerator.generatePcmuPacket(1000, 0.8, so);
                    sessionB.sendPacket(new RtpPacket(0, seq, so, ssrcB, payload));
                    Thread.sleep(5);
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        senderA.start();
        senderB.start();

        // Receive at both ends
        Thread receiverA = new Thread(() -> {
            try {
                long dl = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < dl) sessionA.receivePacket(100);
            } catch (Exception e) { /* ignore */ }
        });

        Thread receiverB = new Thread(() -> {
            try {
                long dl = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < dl) sessionB.receivePacket(100);
            } catch (Exception e) { /* ignore */ }
        });

        receiverA.start();
        receiverB.start();

        senderA.join(5000);
        senderB.join(5000);
        receiverA.join(5000);
        receiverB.join(5000);

        // Analyze at A (should have packets from B)
        assertThat(sessionA.getReceivedPackets()).isNotEmpty();
        byte[] samplesAtA = concatenatePayloads(sessionA);
        ToneDetector.DetectionResult resultAtA = ToneDetector.detect(samplesAtA, 1000, 150, 300);
        assertThat(resultAtA.detected())
                .as("Tone B->A should be detected. Reason: %s", resultAtA.reason())
                .isTrue();

        // Analyze at B (should have packets from A)
        assertThat(sessionB.getReceivedPackets()).isNotEmpty();
        byte[] samplesAtB = concatenatePayloads(sessionB);
        ToneDetector.DetectionResult resultAtB = ToneDetector.detect(samplesAtB, 1000, 150, 300);
        assertThat(resultAtB.detected())
                .as("Tone A->B should be detected. Reason: %s", resultAtB.reason())
                .isTrue();
    }

    @Test
    void differentFrequenciesShouldBeDistinguishable() throws Exception {
        sessionA = new UdpRtpSession();
        sessionB = new UdpRtpSession();
        sessionA.start(0);
        sessionB.start(0);

        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        sessionA.setRemote(localhost, sessionB.getLocalPort());

        // Send 500Hz tone
        for (int seq = 0; seq < 50; seq++) {
            long so = (long) seq * ToneGenerator.SAMPLES_PER_PACKET;
            byte[] payload = ToneGenerator.generatePcmuPacket(500, 0.8, so);
            sessionA.sendPacket(new RtpPacket(0, seq, so, 0x1234L, payload));
            Thread.sleep(5);
        }

        // Receive
        long deadline = System.currentTimeMillis() + 3000;
        while (sessionB.getReceivedPackets().size() < 25 && System.currentTimeMillis() < deadline) {
            sessionB.receivePacket(100);
        }

        byte[] samples = concatenatePayloads(sessionB);

        // Should detect 500Hz
        ToneDetector.DetectionResult result500 = ToneDetector.detect(samples, 500, 100, 300);
        assertThat(result500.detected()).isTrue();

        // Should NOT detect 2000Hz
        ToneDetector.DetectionResult result2000 = ToneDetector.detect(samples, 2000, 100, 300);
        assertThat(result2000.detected()).isFalse();
    }

    private byte[] concatenatePayloads(RtpSession session) {
        var packets = session.getReceivedPackets();
        int totalBytes = packets.stream().mapToInt(RtpPacket::getPayloadLength).sum();
        byte[] all = new byte[totalBytes];
        int offset = 0;
        for (RtpPacket pkt : packets) {
            byte[] pl = pkt.getPayload();
            System.arraycopy(pl, 0, all, offset, pl.length);
            offset += pl.length;
        }
        return all;
    }
}
