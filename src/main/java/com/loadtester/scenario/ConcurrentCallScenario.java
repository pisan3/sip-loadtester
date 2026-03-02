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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates N concurrent A-calls-B scenarios through the PBX.
 * <p>
 * Architecture:
 * <ul>
 *   <li>1 B-phone: registers once, auto-answers each incoming INVITE immediately
 *       with 180 Ringing + 200 OK and a dedicated RTP session per call</li>
 *   <li>N A-phones: each has its own SipPhone/SipStack/port/RTP, registers, makes 1 call</li>
 * </ul>
 * <p>
 * Key design: B must answer each INVITE immediately (in the SIP callback) before the
 * next INVITE arrives, because the PBX returns 486 Busy Here if B has an unanswered call.
 */
public class ConcurrentCallScenario {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentCallScenario.class);

    private static final double TONE_FREQUENCY = 1000.0;
    private static final double TONE_AMPLITUDE = 0.8;
    private static final int STAGGER_DELAY_MS = 500;

    private final SipAccountConfig phoneAConfig;
    private final SipAccountConfig phoneBConfig;
    private final SipStackFactory stackFactory;
    private final String localIp;
    private final int concurrentCalls;
    private int mediaDurationMs = 30_000;
    private int timeoutSeconds = 30;

    public ConcurrentCallScenario(SipAccountConfig phoneAConfig, SipAccountConfig phoneBConfig,
                                  SipStackFactory stackFactory, String localIp, int concurrentCalls) {
        this.phoneAConfig = phoneAConfig;
        this.phoneBConfig = phoneBConfig;
        this.stackFactory = stackFactory;
        this.localIp = localIp;
        this.concurrentCalls = concurrentCalls;
    }

    public void setMediaDurationMs(int mediaDurationMs) {
        this.mediaDurationMs = mediaDurationMs;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Execute the concurrent call scenario and return a test report.
     */
    public TestReport execute() {
        TestReport report = new TestReport("Concurrent " + concurrentCalls + " calls");
        ExecutorService executor = Executors.newCachedThreadPool();

        // --- B-phone setup ---
        RtpSession rtpSessionBDefault = new UdpRtpSession();

        // Shared state between listener and main thread
        ConcurrentHashMap<String, String> bSdpOffers = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, CountDownLatch> bByeLatches = new ConcurrentHashMap<>();
        // Tracks which call IDs arrived, in order
        CopyOnWriteArrayList<String> arrivedCallIds = new CopyOnWriteArrayList<>();
        CountDownLatch phoneBRegistered = new CountDownLatch(1);
        AtomicReference<String> phoneBError = new AtomicReference<>();
        AtomicInteger bAnsweredCount = new AtomicInteger(0);
        AtomicInteger bAutoAnswerErrors = new AtomicInteger(0);
        CountDownLatch allBAnswered = new CountDownLatch(concurrentCalls);

        // AtomicReference to break circular dependency: listener needs phoneB ref
        AtomicReference<SipPhone> phoneBRef = new AtomicReference<>();

        SipPhoneListener listenerB = new SipPhoneListener() {
            @Override public void onRegistered() { phoneBRegistered.countDown(); }
            @Override public void onRegistrationFailed(int code, String reason) {
                phoneBError.set("B Registration failed: " + code + " " + reason);
                phoneBRegistered.countDown();
            }
            @Override public void onIncomingCall(String callerUri, String sdpOffer) {
                // no-op; we use the 3-arg version
            }
            @Override public void onIncomingCall(String callId, String callerUri, String sdpOffer) {
                // AUTO-ANSWER immediately so PBX doesn't return 486 for the next call
                log.info("[B] Incoming call {} from {} — auto-answering", callId, callerUri);
                bSdpOffers.put(callId, sdpOffer);
                bByeLatches.putIfAbsent(callId, new CountDownLatch(1));
                arrivedCallIds.add(callId);

                SipPhone phoneB = phoneBRef.get();
                if (phoneB == null) {
                    log.error("[B] phoneB ref not set, cannot auto-answer call {}", callId);
                    bAutoAnswerErrors.incrementAndGet();
                    allBAnswered.countDown();
                    return;
                }

                try {
                    phoneB.sendRinging(callId);
                    phoneB.answer(callId);

                    // Set RTP remote for B's call leg from A's SDP offer
                    CallLeg leg = phoneB.getCallLeg(callId);
                    if (leg != null && sdpOffer != null) {
                        SdpUtil.SdpInfo aOffer = SdpUtil.parseSdp(sdpOffer);
                        RtpSession legRtp = leg.getRtpSession();
                        if (!legRtp.isRunning()) {
                            legRtp.start(0);
                        }
                        legRtp.setRemote(InetAddress.getByName(aOffer.connectionAddress()), aOffer.mediaPort());
                    }

                    bAnsweredCount.incrementAndGet();
                    log.info("[B] Auto-answered call {} successfully", callId);
                } catch (Exception e) {
                    log.error("[B] Auto-answer failed for call {}: {}", callId, e.getMessage(), e);
                    bAutoAnswerErrors.incrementAndGet();
                }
                allBAnswered.countDown();
            }
            @Override public void onRinging() {}
            @Override public void onAnswered(String sdpAnswer) {}
            @Override public void onCallFailed(int code, String reason) {}
            @Override public void onBye() {}
            @Override public void onBye(String callId) {
                log.info("[B] BYE received for call {}", callId);
                CountDownLatch latch = bByeLatches.get(callId);
                if (latch != null) latch.countDown();
            }
            @Override public void onError(Exception e) {
                log.error("[B] Error: {}", e.getMessage(), e);
                phoneBError.set("B Error: " + e.getMessage());
            }
        };

        SipPhone phoneB = new SipPhone(phoneBConfig, stackFactory, rtpSessionBDefault, listenerB,
                () -> new UdpRtpSession());
        phoneBRef.set(phoneB);

        // --- A-phones setup ---
        SipPhone[] phonesA = new SipPhone[concurrentCalls];
        RtpSession[] rtpSessionsA = new RtpSession[concurrentCalls];
        CountDownLatch[] aRegisteredLatches = new CountDownLatch[concurrentCalls];
        CountDownLatch[] aRingingLatches = new CountDownLatch[concurrentCalls];
        CountDownLatch[] aAnsweredLatches = new CountDownLatch[concurrentCalls];
        CountDownLatch[] aByeLatches = new CountDownLatch[concurrentCalls];
        @SuppressWarnings("unchecked")
        AtomicReference<String>[] aErrors = new AtomicReference[concurrentCalls];

        for (int i = 0; i < concurrentCalls; i++) {
            final int idx = i;
            aRegisteredLatches[i] = new CountDownLatch(1);
            aRingingLatches[i] = new CountDownLatch(1);
            aAnsweredLatches[i] = new CountDownLatch(1);
            aByeLatches[i] = new CountDownLatch(1);
            aErrors[i] = new AtomicReference<>();
            rtpSessionsA[i] = new UdpRtpSession();

            SipPhoneListener listenerA = new SipPhoneListener() {
                @Override public void onRegistered() { aRegisteredLatches[idx].countDown(); }
                @Override public void onRegistrationFailed(int code, String reason) {
                    aErrors[idx].set("A[" + idx + "] Registration failed: " + code + " " + reason);
                    aRegisteredLatches[idx].countDown();
                }
                @Override public void onIncomingCall(String callerUri, String sdpOffer) {}
                @Override public void onRinging() { aRingingLatches[idx].countDown(); }
                @Override public void onAnswered(String sdpAnswer) { aAnsweredLatches[idx].countDown(); }
                @Override public void onCallFailed(int code, String reason) {
                    aErrors[idx].set("A[" + idx + "] Call failed: " + code + " " + reason);
                    aAnsweredLatches[idx].countDown();
                    aRingingLatches[idx].countDown();
                }
                @Override public void onBye() { aByeLatches[idx].countDown(); }
                @Override public void onError(Exception e) {
                    aErrors[idx].set("A[" + idx + "] Error: " + e.getMessage());
                }
            };

            phonesA[i] = new SipPhone(phoneAConfig, stackFactory, rtpSessionsA[i], listenerA);
        }

        try {
            // --- Step 1: Initialize & register B ---
            int portB = findFreeUdpPort();
            phoneB.initialize(stackFactory, localIp, portB);

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

            // --- Step 2: Initialize & register all A-phones ---
            for (int i = 0; i < concurrentCalls; i++) {
                int portA = findFreeUdpPort();
                phonesA[i].initialize(stackFactory, localIp, portA);
            }

            start = Instant.now();
            for (int i = 0; i < concurrentCalls; i++) {
                phonesA[i].register();
            }
            boolean allARegistered = true;
            for (int i = 0; i < concurrentCalls; i++) {
                if (!aRegisteredLatches[i].await(timeoutSeconds, TimeUnit.SECONDS)) {
                    allARegistered = false;
                    aErrors[i].compareAndSet(null, "Registration timeout");
                }
            }
            Duration regADur = Duration.between(start, Instant.now());
            int aRegFails = 0;
            for (int i = 0; i < concurrentCalls; i++) {
                if (aErrors[i].get() != null) aRegFails++;
            }
            if (aRegFails > 0) {
                report.addCheck(TestReport.CheckResult.fail("A-phones Registration",
                        aRegFails + "/" + concurrentCalls + " failed", regADur));
                for (int i = 0; i < concurrentCalls; i++) {
                    if (aErrors[i].get() != null) {
                        log.error("A[{}] registration error: {}", i, aErrors[i].get());
                    }
                }
                report.finish();
                return report;
            }
            report.addCheck(TestReport.CheckResult.pass("A-phones Registration (" + concurrentCalls + ")", regADur));

            // --- Step 3: All A-phones call B with staggered timing ---
            // B auto-answers each INVITE immediately in the callback, so
            // by the time the next INVITE arrives, B is no longer "busy".
            start = Instant.now();
            for (int i = 0; i < concurrentCalls; i++) {
                phonesA[i].call(phoneBConfig.sipUri());
                if (i < concurrentCalls - 1) {
                    Thread.sleep(STAGGER_DELAY_MS);
                }
            }

            // Wait for all A-phones to get 200 OK (B auto-answered)
            int answeredCount = 0;
            for (int i = 0; i < concurrentCalls; i++) {
                if (aAnsweredLatches[i].await(timeoutSeconds, TimeUnit.SECONDS)) {
                    if (aErrors[i].get() == null) answeredCount++;
                }
            }
            Duration callSetupDur = Duration.between(start, Instant.now());

            // Also wait for B to confirm all auto-answers completed
            allBAnswered.await(timeoutSeconds, TimeUnit.SECONDS);

            if (answeredCount < concurrentCalls) {
                String detail = answeredCount + "/" + concurrentCalls + " answered";
                if (bAutoAnswerErrors.get() > 0) {
                    detail += ", " + bAutoAnswerErrors.get() + " B auto-answer errors";
                }
                for (int i = 0; i < concurrentCalls; i++) {
                    if (aErrors[i].get() != null) {
                        log.error("A[{}]: {}", i, aErrors[i].get());
                    }
                }
                report.addCheck(TestReport.CheckResult.fail("Call Setup (INVITE->200 OK)",
                        detail, callSetupDur));
                report.finish();
                return report;
            }
            report.addCheck(TestReport.CheckResult.pass(
                    "Call Setup (" + concurrentCalls + " calls)", callSetupDur));

            // --- Step 4: RTP media exchange for all calls ---
            List<String> callIds = new ArrayList<>(arrivedCallIds);
            start = Instant.now();
            sendAndReceiveMediaConcurrent(phonesA, rtpSessionsA, phoneB, callIds, report, executor);
            Duration mediaDur = Duration.between(start, Instant.now());

            // --- Step 5: All A-phones send BYE ---
            start = Instant.now();
            for (int i = 0; i < concurrentCalls; i++) {
                try {
                    phonesA[i].hangup();
                } catch (Exception e) {
                    log.warn("A[{}] hangup failed: {}", i, e.getMessage());
                }
            }

            int byeCount = 0;
            for (int i = 0; i < concurrentCalls; i++) {
                if (aByeLatches[i].await(timeoutSeconds, TimeUnit.SECONDS)) {
                    byeCount++;
                }
            }
            Duration byeDur = Duration.between(start, Instant.now());
            if (byeCount < concurrentCalls) {
                report.addCheck(TestReport.CheckResult.fail("BYE/200 OK",
                        byeCount + "/" + concurrentCalls + " acknowledged", byeDur));
            } else {
                report.addCheck(TestReport.CheckResult.pass("BYE/200 OK (" + concurrentCalls + ")", byeDur));
            }

        } catch (Exception e) {
            log.error("Concurrent scenario execution error", e);
            report.addCheck(TestReport.CheckResult.fail("Unexpected error", e.getMessage()));
        } finally {
            for (int i = 0; i < concurrentCalls; i++) {
                try { if (phonesA[i] != null) phonesA[i].shutdown(); } catch (Exception e) { /* ignore */ }
            }
            try { phoneB.shutdown(); } catch (Exception e) { /* ignore */ }
            executor.shutdownNow();
            report.finish();
        }

        return report;
    }

    /**
     * Send/receive RTP tone concurrently for all active calls.
     */
    private void sendAndReceiveMediaConcurrent(SipPhone[] phonesA, RtpSession[] rtpSessionsA,
                                                SipPhone phoneB, List<String> callIds,
                                                TestReport report, ExecutorService executor) {
        try {
            int totalPackets = mediaDurationMs / 20;
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < Math.min(concurrentCalls, callIds.size()); i++) {
                final int idx = i;
                final String cid = callIds.get(i);
                final RtpSession rtpA = rtpSessionsA[i];

                CallLeg leg = phoneB.getCallLeg(cid);
                if (leg == null) {
                    log.warn("No call leg for callId={}, skipping media", cid);
                    continue;
                }
                final RtpSession rtpB = leg.getRtpSession();

                long ssrcA = 0x10000000L + idx;
                long ssrcB = 0x20000000L + idx;

                futures.add(executor.submit(() -> {
                    try { sendTone(rtpA, totalPackets, ssrcA); }
                    catch (Exception e) { log.error("A[{}] send error: {}", idx, e.getMessage()); }
                }));

                futures.add(executor.submit(() -> {
                    try { sendTone(rtpB, totalPackets, ssrcB); }
                    catch (Exception e) { log.error("B-leg[{}] send error: {}", cid, e.getMessage()); }
                }));

                futures.add(executor.submit(() -> {
                    try { receivePackets(rtpA, mediaDurationMs + 2000); }
                    catch (Exception e) { log.error("A[{}] recv error: {}", idx, e.getMessage()); }
                }));

                futures.add(executor.submit(() -> {
                    try { receivePackets(rtpB, mediaDurationMs + 2000); }
                    catch (Exception e) { log.error("B-leg[{}] recv error: {}", cid, e.getMessage()); }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get(mediaDurationMs + 10_000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    log.warn("Media thread timed out");
                    f.cancel(true);
                }
            }

            // Analyze results per-call
            int tonePassA2B = 0, tonePassB2A = 0;
            int rtpPassA2B = 0, rtpPassB2A = 0;
            for (int i = 0; i < Math.min(concurrentCalls, callIds.size()); i++) {
                String cid = callIds.get(i);
                CallLeg leg = phoneB.getCallLeg(cid);
                RtpSession rtpA = rtpSessionsA[i];

                if (leg != null) {
                    RtpSession rtpB = leg.getRtpSession();
                    var bPackets = rtpB.getReceivedPackets();
                    if (!bPackets.isEmpty()) {
                        rtpPassA2B++;
                        if (detectTone(bPackets)) tonePassA2B++;
                    }
                }

                var aPackets = rtpA.getReceivedPackets();
                if (!aPackets.isEmpty()) {
                    rtpPassB2A++;
                    if (detectTone(aPackets)) tonePassB2A++;
                }
            }

            report.addCheck(toneResult("RTP A->B", rtpPassA2B, concurrentCalls));
            report.addCheck(toneResult("RTP B->A", rtpPassB2A, concurrentCalls));
            report.addCheck(toneResult("Tone A->B", tonePassA2B, concurrentCalls));
            report.addCheck(toneResult("Tone B->A", tonePassB2A, concurrentCalls));

        } catch (Exception e) {
            report.addCheck(TestReport.CheckResult.fail("Media exchange", e.getMessage()));
        }
    }

    private boolean detectTone(List<RtpPacket> packets) {
        int totalBytes = packets.stream().mapToInt(RtpPacket::getPayloadLength).sum();
        byte[] allSamples = new byte[totalBytes];
        int offset = 0;
        for (RtpPacket pkt : packets) {
            byte[] payload = pkt.getPayload();
            System.arraycopy(payload, 0, allSamples, offset, payload.length);
            offset += payload.length;
        }
        ToneDetector.DetectionResult result = ToneDetector.detect(allSamples, TONE_FREQUENCY, 150.0, 300.0);
        return result.detected();
    }

    private TestReport.CheckResult toneResult(String name, int passed, int total) {
        String detail = passed + "/" + total;
        if (passed == total) {
            return TestReport.CheckResult.pass(name + " (" + detail + ")", detail, Duration.ZERO);
        } else {
            return TestReport.CheckResult.fail(name, detail + " passed", Duration.ZERO);
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
            Thread.sleep(20);
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

    private static int findFreeUdpPort() throws Exception {
        try (DatagramSocket ds = new DatagramSocket(0)) {
            return ds.getLocalPort();
        }
    }
}
