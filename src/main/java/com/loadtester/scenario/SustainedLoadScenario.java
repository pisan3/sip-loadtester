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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sustained load scenario: maintains N concurrent calls for a total test duration.
 * <p>
 * When a call ends (BYE acknowledged), the A-phone is reset and immediately starts
 * a new call so the active call count stays at the desired concurrency level.
 * <p>
 * Architecture:
 * <ul>
 *   <li>1 B-phone: registered once, auto-answers all incoming INVITEs with 200 OK</li>
 *   <li>N A-phones: each has its own SipStack, registered once. After a call ends,
 *       {@code resetCallState()} is called and the phone is returned to the free pool</li>
 *   <li>~10% of calls get tone detection (packet accumulation enabled)</li>
 *   <li>Live stats printed every 5 seconds; final summary at the end</li>
 * </ul>
 */
public class SustainedLoadScenario {

    private static final Logger log = LoggerFactory.getLogger(SustainedLoadScenario.class);

    private static final double TONE_FREQUENCY = 1000.0;
    private static final double TONE_AMPLITUDE = 0.8;
    private static final int STATS_INTERVAL_SECONDS = 5;
    private static final double TONE_SAMPLE_RATE = 0.10; // 10% of calls

    private final SipAccountConfig phoneAConfig;
    private final SipAccountConfig phoneBConfig;
    private final SipStackFactory stackFactory;
    private final String localIp;
    private final int concurrentCalls;
    private long callDurationMs = 30_000;
    private long totalDurationMs = 60_000;
    private int timeoutSeconds = 30;
    private int staggerDelayMs = 500;
    private int replacementDelayMs = -1; // -1 means "auto: staggerDelayMs / 2"
    private int maxInFlightInvites = -1; // -1 means "auto: concurrentCalls"

    // --- Stop control ---
    private volatile boolean stopRequested;

    // --- Metrics ---
    private final AtomicLong callsStarted = new AtomicLong();
    private final AtomicLong callsCompleted = new AtomicLong();
    private final AtomicLong callsFailed = new AtomicLong();
    private final AtomicInteger activeCalls = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> setupLatencies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger toneTestsPassed = new AtomicInteger();
    private final AtomicInteger toneTestsTotal = new AtomicInteger();
    private volatile Instant scenarioStart;

    // --- Event callback for TUI ---
    private volatile ScenarioEventListener eventListener;

    // Shared executor for RTP send/receive threads (set during execute())
    private volatile ExecutorService executor;

    // Rate-limiting: prevents too many concurrent INVITEs awaiting auth
    private volatile Semaphore inviteSemaphore;

    public SustainedLoadScenario(SipAccountConfig phoneAConfig, SipAccountConfig phoneBConfig,
                                  SipStackFactory stackFactory, String localIp, int concurrentCalls) {
        this.phoneAConfig = phoneAConfig;
        this.phoneBConfig = phoneBConfig;
        this.stackFactory = stackFactory;
        this.localIp = localIp;
        this.concurrentCalls = concurrentCalls;
    }

    public void setCallDurationMs(long callDurationMs) { this.callDurationMs = callDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public void setStaggerDelayMs(int staggerDelayMs) { this.staggerDelayMs = staggerDelayMs; }
    public void setEventListener(ScenarioEventListener listener) { this.eventListener = listener; }
    public void setMaxInFlightInvites(int max) { this.maxInFlightInvites = max; }
    public void setReplacementDelayMs(int delayMs) { this.replacementDelayMs = delayMs; }

    public int getMaxInFlightInvites() {
        return maxInFlightInvites > 0 ? maxInFlightInvites : concurrentCalls;
    }

    public int getReplacementDelayMs() {
        return replacementDelayMs >= 0 ? replacementDelayMs : staggerDelayMs / 2;
    }

    /**
     * Request the scenario to stop gracefully. No new calls will be launched
     * and the main loop will exit after in-flight calls finish.
     */
    public void requestStop() {
        this.stopRequested = true;
        fireEvent("Stop requested — finishing in-flight calls...");
    }

    public boolean isStopRequested() { return stopRequested; }

    // --- Public metric accessors for TUI dashboard ---
    public long getCallsStartedCount() { return callsStarted.get(); }
    public long getCallsCompletedCount() { return callsCompleted.get(); }
    public long getCallsFailedCount() { return callsFailed.get(); }
    public int getActiveCallsCount() { return activeCalls.get(); }
    public int getConcurrentCalls() { return concurrentCalls; }
    public long getTotalDurationMs() { return totalDurationMs; }
    public Instant getScenarioStart() { return scenarioStart; }

    public long getAvgSetupLatencyMs() {
        List<Long> snapshot = new ArrayList<>(setupLatencies);
        if (snapshot.isEmpty()) return 0;
        return snapshot.stream().mapToLong(Long::longValue).sum() / snapshot.size();
    }

    public long getP95SetupLatencyMs() {
        List<Long> snapshot = new ArrayList<>(setupLatencies);
        if (snapshot.isEmpty()) return 0;
        Collections.sort(snapshot);
        return snapshot.get(Math.min((int) (snapshot.size() * 0.95), snapshot.size() - 1));
    }

    public long getP99SetupLatencyMs() {
        List<Long> snapshot = new ArrayList<>(setupLatencies);
        if (snapshot.isEmpty()) return 0;
        Collections.sort(snapshot);
        return snapshot.get(Math.min((int) (snapshot.size() * 0.99), snapshot.size() - 1));
    }

    public int getToneTestsPassedCount() { return toneTestsPassed.get(); }
    public int getToneTestsTotalCount() { return toneTestsTotal.get(); }

    // Package-private accessors for existing unit tests
    AtomicLong getCallsStarted() { return callsStarted; }
    AtomicLong getCallsCompleted() { return callsCompleted; }
    AtomicLong getCallsFailed() { return callsFailed; }
    AtomicInteger getActiveCalls() { return activeCalls; }
    ConcurrentLinkedQueue<Long> getSetupLatencies() { return setupLatencies; }
    AtomicInteger getToneTestsPassed() { return toneTestsPassed; }
    AtomicInteger getToneTestsTotal() { return toneTestsTotal; }
    int getStaggerDelayMs() { return staggerDelayMs; }

    /**
     * Execute the sustained load scenario and return a test report.
     */
    public TestReport execute() {
        TestReport report = new TestReport(
                String.format("Sustained Load (%d concurrent, %ds)", concurrentCalls, totalDurationMs / 1000));
        executor = Executors.newCachedThreadPool();
        inviteSemaphore = new Semaphore(getMaxInFlightInvites());
        ScheduledExecutorService statsScheduler = Executors.newSingleThreadScheduledExecutor();
        scenarioStart = Instant.now();

        // --- B-phone setup ---
        RtpSession rtpSessionBDefault = new UdpRtpSession();
        CountDownLatch phoneBRegistered = new CountDownLatch(1);
        AtomicReference<String> phoneBError = new AtomicReference<>();
        AtomicReference<SipPhone> phoneBRef = new AtomicReference<>();

        SipPhoneListener listenerB = createBPhoneListener(phoneBRef, phoneBRegistered, phoneBError);
        SipPhone phoneB = new SipPhone(phoneBConfig, stackFactory, rtpSessionBDefault, listenerB,
                () -> new UdpRtpSession());
        phoneBRef.set(phoneB);

        // --- A-phones pool ---
        // Use a blocking queue as the "free phone pool"
        BlockingQueue<PhoneSlot> freePhones = new LinkedBlockingQueue<>();
        List<PhoneSlot> allPhoneSlots = new ArrayList<>();

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
            start = Instant.now();
            for (int i = 0; i < concurrentCalls; i++) {
                PhoneSlot slot = createPhoneSlot(i, freePhones);
                allPhoneSlots.add(slot);

                int portA = findFreeUdpPort();
                slot.phone.initialize(stackFactory, localIp, portA);
                slot.phone.register();
            }

            // Wait for all A-phones to register
            int aRegFails = 0;
            for (PhoneSlot slot : allPhoneSlots) {
                if (!slot.registeredLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    aRegFails++;
                    slot.error.compareAndSet(null, "Registration timeout");
                } else if (slot.error.get() != null) {
                    aRegFails++;
                }
            }
            Duration regADur = Duration.between(start, Instant.now());
            if (aRegFails > 0) {
                report.addCheck(TestReport.CheckResult.fail("A-phones Registration",
                        aRegFails + "/" + concurrentCalls + " failed", regADur));
                report.finish();
                return report;
            }
            report.addCheck(TestReport.CheckResult.pass(
                    "A-phones Registration (" + concurrentCalls + ")", regADur));

            // Disable re-registration for short tests (< REGISTER_EXPIRES = 60s)
            // to avoid 30+ phones bursting simultaneous re-REGISTERs that compete with INVITEs
            boolean shortTest = totalDurationMs < 60_000;
            if (shortTest) {
                log.info("Short test ({}s < 60s) — disabling re-registration on all phones", totalDurationMs / 1000);
                for (PhoneSlot slot : allPhoneSlots) {
                    slot.phone.setReRegistrationEnabled(false);
                }
                phoneB.setReRegistrationEnabled(false);
            }

            // All phones start in free pool
            for (PhoneSlot slot : allPhoneSlots) {
                freePhones.offer(slot);
            }

            // --- Step 3: Start live stats ---
            statsScheduler.scheduleAtFixedRate(() -> printLiveStats(scenarioStart),
                    STATS_INTERVAL_SECONDS, STATS_INTERVAL_SECONDS, TimeUnit.SECONDS);

            // --- Step 4: Main sustained load loop ---
            long deadline = System.currentTimeMillis() + totalDurationMs;

            // Launch initial batch of calls with staggering
            for (int i = 0; i < concurrentCalls && !stopRequested; i++) {
                PhoneSlot slot = freePhones.poll(timeoutSeconds, TimeUnit.SECONDS);
                if (slot == null) {
                    log.warn("Could not get free phone for initial call {}", i);
                    continue;
                }
                launchCall(slot, phoneB, freePhones, deadline, executor);
                if (i < concurrentCalls - 1) {
                    Thread.sleep(staggerDelayMs);
                }
            }

            // Wait for the total duration to expire (or stop requested)
            long remaining = deadline - System.currentTimeMillis();
            while (remaining > 0 && !stopRequested) {
                Thread.sleep(Math.min(remaining, 500));
                remaining = deadline - System.currentTimeMillis();
            }

            // Wait for all active calls to finish (with a grace period)
            log.info("Test duration expired. Waiting for {} in-flight calls to finish...", activeCalls.get());
            long graceDeadline = System.currentTimeMillis() + callDurationMs + (timeoutSeconds * 1000L);
            while (activeCalls.get() > 0 && System.currentTimeMillis() < graceDeadline) {
                Thread.sleep(500);
            }
            if (activeCalls.get() > 0) {
                log.warn("{} calls still active after grace period", activeCalls.get());
            }

            // --- Step 5: Final stats ---
            statsScheduler.shutdownNow();
            Duration totalDur = Duration.between(scenarioStart, Instant.now());
            buildFinalReport(report, totalDur);

        } catch (Exception e) {
            log.error("Sustained load scenario execution error", e);
            report.addCheck(TestReport.CheckResult.fail("Unexpected error", e.getMessage()));
        } finally {
            statsScheduler.shutdownNow();
            for (PhoneSlot slot : allPhoneSlots) {
                try { slot.phone.shutdown(); } catch (Exception e) { /* ignore */ }
            }
            try { phoneB.shutdown(); } catch (Exception e) { /* ignore */ }
            executor.shutdownNow();
            report.finish();
        }

        return report;
    }

    /**
     * Launch a single call on the given phone slot. When the call completes,
     * the phone is reset and returned to the free pool, and if we haven't passed
     * the deadline, a new call is started with the next free phone.
     * <p>
     * Rate-limited by {@link #inviteSemaphore} to prevent too many concurrent
     * INVITE auth cycles overwhelming the SIP proxy.
     */
    void launchCall(PhoneSlot slot, SipPhone phoneB, BlockingQueue<PhoneSlot> freePhones,
                    long deadline, ExecutorService executor) {
        executor.submit(() -> {
            boolean toneTest = ThreadLocalRandom.current().nextDouble() < TONE_SAMPLE_RATE;
            RtpSession rtpA = slot.phone.getRtpSession();
            rtpA.setAccumulatePackets(toneTest);
            if (toneTest) {
                toneTestsTotal.incrementAndGet();
            }

            // Rate-limit: acquire permit before sending INVITE
            boolean acquired = false;
            try {
                acquired = inviteSemaphore.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
                if (!acquired) {
                    log.warn("Slot {} could not acquire invite permit within timeout", slot.index);
                    callsFailed.incrementAndGet();
                    fireEvent(String.format("Call FAILED on slot %d: invite rate-limit timeout", slot.index));
                    return;
                }
                runSingleCall(slot, phoneB, toneTest, executor);
            } catch (Exception e) {
                log.error("Call failed on slot {}: {}", slot.index, e.getMessage(), e);
                callsFailed.incrementAndGet();
                fireEvent(String.format("Call FAILED on slot %d: %s", slot.index, e.getMessage()));
            } finally {
                if (acquired) {
                    inviteSemaphore.release();
                }
                activeCalls.decrementAndGet();
                slot.phone.resetCallState();
                slot.resetLatches();

                // If we still have time and stop not requested, launch a replacement call
                // with a delay to prevent burst of simultaneous INVITEs
                if (System.currentTimeMillis() < deadline && !stopRequested) {
                    freePhones.offer(slot);
                    try {
                        int delay = getReplacementDelayMs();
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }
                        PhoneSlot nextSlot = freePhones.poll(1, TimeUnit.SECONDS);
                        if (nextSlot != null) {
                            launchCall(nextSlot, phoneB, freePhones, deadline, executor);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    // Past deadline, just return phone to pool (no new call)
                    freePhones.offer(slot);
                }
            }
        });
    }

    /**
     * Run a single call lifecycle: INVITE -> 200 OK -> RTP -> BYE
     */
    void runSingleCall(PhoneSlot slot, SipPhone phoneB, boolean toneTest,
                       ExecutorService executor) throws Exception {
        Instant callStart = Instant.now();
        callsStarted.incrementAndGet();
        activeCalls.incrementAndGet();
        fireEvent(String.format("Call #%d started (slot %d)", callsStarted.get(), slot.index));

        // --- INVITE ---
        slot.phone.call(phoneBConfig.sipUri());

        // Wait for 200 OK (answered)
        if (!slot.answeredLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            if (slot.error.get() != null) {
                throw new RuntimeException("Call failed: " + slot.error.get());
            }
            throw new RuntimeException("Timeout waiting for 200 OK");
        }
        if (slot.error.get() != null) {
            throw new RuntimeException("Call failed: " + slot.error.get());
        }

        long setupMs = Duration.between(callStart, Instant.now()).toMillis();
        setupLatencies.add(setupMs);

        // --- RTP media phase ---
        int totalPackets = (int) (callDurationMs / 20);
        long ssrc = 0x10000000L + slot.index + (callsStarted.get() << 16);

        RtpSession rtpA = slot.phone.getRtpSession();

        // Start send and receive threads
        Future<?> sendFuture = executor.submit(() -> {
            try { sendTone(rtpA, totalPackets, ssrc); }
            catch (Exception e) { log.debug("RTP send error on slot {}: {}", slot.index, e.getMessage()); }
        });
        Future<?> recvFuture = executor.submit(() -> {
            try { receivePackets(rtpA, callDurationMs + 2000); }
            catch (Exception e) { log.debug("RTP recv error on slot {}: {}", slot.index, e.getMessage()); }
        });

        // Wait for media phase to complete
        try {
            sendFuture.get(callDurationMs + 10_000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            sendFuture.cancel(true);
        }
        try {
            recvFuture.get(5_000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            recvFuture.cancel(true);
        }

        // --- Tone detection (if sampled) ---
        if (toneTest) {
            List<RtpPacket> received = rtpA.getReceivedPackets();
            if (!received.isEmpty() && detectTone(received)) {
                toneTestsPassed.incrementAndGet();
            }
        }

        // --- BYE ---
        slot.phone.hangup();
        if (!slot.byeLatch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            log.warn("Slot {} BYE timeout", slot.index);
        }

        callsCompleted.incrementAndGet();
        log.debug("Call completed on slot {} (setup: {}ms)", slot.index, setupMs);
        fireEvent(String.format("Call #%d completed (slot %d, setup: %dms)", callsStarted.get(), slot.index, setupMs));
    }

    private SipPhoneListener createBPhoneListener(AtomicReference<SipPhone> phoneBRef,
                                                    CountDownLatch phoneBRegistered,
                                                    AtomicReference<String> phoneBError) {
        return new SipPhoneListener() {
            @Override public void onRegistered() { phoneBRegistered.countDown(); }
            @Override public void onRegistrationFailed(int code, String reason) {
                phoneBError.set("B Registration failed: " + code + " " + reason);
                phoneBRegistered.countDown();
            }
            @Override public void onIncomingCall(String callerUri, String sdpOffer) {
                // no-op; we use the 3-arg version
            }
            @Override public void onIncomingCall(String callId, String callerUri, String sdpOffer) {
                log.debug("[B] Incoming call {} from {} — auto-answering", callId, callerUri);
                SipPhone phoneB = phoneBRef.get();
                if (phoneB == null) {
                    log.error("[B] phoneB ref not set, cannot auto-answer call {}", callId);
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

                        // B sends tone back to A on its own RTP session
                        int totalPackets = (int) (callDurationMs / 20);
                        long ssrc = 0x20000000L + System.nanoTime();
                        ExecutorService exec = executor;
                        if (exec != null && !exec.isShutdown()) {
                            exec.submit(() -> {
                                try { sendTone(legRtp, totalPackets, ssrc); }
                                catch (Exception e) { log.debug("[B] RTP send error: {}", e.getMessage()); }
                            });
                        }
                    }
                    log.debug("[B] Auto-answered call {} successfully", callId);
                } catch (Exception e) {
                    log.error("[B] Auto-answer failed for call {}: {}", callId, e.getMessage(), e);
                }
            }
            @Override public void onRinging() {}
            @Override public void onAnswered(String sdpAnswer) {}
            @Override public void onCallFailed(int code, String reason) {}
            @Override public void onBye() {}
            @Override public void onBye(String callId) {
                log.debug("[B] BYE for call {}", callId);
            }
            @Override public void onError(Exception e) {
                log.error("[B] Error: {}", e.getMessage(), e);
            }
        };
    }

    private PhoneSlot createPhoneSlot(int index, BlockingQueue<PhoneSlot> freePhones) {
        PhoneSlot slot = new PhoneSlot();
        slot.index = index;
        slot.registeredLatch = new CountDownLatch(1);
        slot.answeredLatch = new CountDownLatch(1);
        slot.byeLatch = new CountDownLatch(1);
        slot.error = new AtomicReference<>();

        SipPhoneListener listenerA = new SipPhoneListener() {
            @Override public void onRegistered() { slot.registeredLatch.countDown(); }
            @Override public void onRegistrationFailed(int code, String reason) {
                slot.error.set("A[" + index + "] Registration failed: " + code + " " + reason);
                slot.registeredLatch.countDown();
            }
            @Override public void onIncomingCall(String callerUri, String sdpOffer) {}
            @Override public void onRinging() { /* ringing received */ }
            @Override public void onAnswered(String sdpAnswer) { slot.answeredLatch.countDown(); }
            @Override public void onCallFailed(int code, String reason) {
                slot.error.set("A[" + index + "] Call failed: " + code + " " + reason);
                slot.answeredLatch.countDown();
            }
            @Override public void onBye() { slot.byeLatch.countDown(); }
            @Override public void onError(Exception e) {
                slot.error.set("A[" + index + "] Error: " + e.getMessage());
            }
        };

        RtpSession rtpA = new UdpRtpSession();
        slot.phone = new SipPhone(phoneAConfig, stackFactory, rtpA, listenerA);
        return slot;
    }

    void printLiveStats(Instant scenarioStart) {
        // In TUI mode (event listener set), the dashboard reads metrics directly — skip console output
        if (eventListener != null) return;

        long elapsed = Duration.between(scenarioStart, Instant.now()).getSeconds();
        long completed = callsCompleted.get();
        long failed = callsFailed.get();
        long started = callsStarted.get();
        int active = activeCalls.get();
        double rate = elapsed > 0 ? (double) completed / elapsed : 0;

        long avgSetup = 0;
        List<Long> latencies = new ArrayList<>(setupLatencies);
        if (!latencies.isEmpty()) {
            avgSetup = latencies.stream().mapToLong(Long::longValue).sum() / latencies.size();
        }

        int tonePassed = toneTestsPassed.get();
        int toneTotal = toneTestsTotal.get();

        System.out.printf("[%02d:%02d] Active: %d | Completed: %d | Failed: %d | Rate: %.1f calls/sec | Avg setup: %dms | Tone: %d/%d passed%n",
                elapsed / 60, elapsed % 60, active, completed, failed, rate, avgSetup, tonePassed, toneTotal);
    }

    private void buildFinalReport(TestReport report, Duration totalDur) {
        long completed = callsCompleted.get();
        long failed = callsFailed.get();
        long started = callsStarted.get();
        long totalAttempted = completed + failed;
        double rate = totalDur.getSeconds() > 0 ? (double) completed / totalDur.getSeconds() : 0;

        List<Long> latencies = new ArrayList<>(setupLatencies);
        Collections.sort(latencies);

        long avgSetup = 0, p95Setup = 0, p99Setup = 0;
        if (!latencies.isEmpty()) {
            avgSetup = latencies.stream().mapToLong(Long::longValue).sum() / latencies.size();
            p95Setup = latencies.get(Math.min((int) (latencies.size() * 0.95), latencies.size() - 1));
            p99Setup = latencies.get(Math.min((int) (latencies.size() * 0.99), latencies.size() - 1));
        }

        int tonePassed = toneTestsPassed.get();
        int toneTotal = toneTestsTotal.get();

        double passRate = totalAttempted > 0 ? (double) completed / totalAttempted * 100 : 0;
        boolean success = passRate >= 95.0 && (toneTotal == 0 || tonePassed == toneTotal);

        // Add summary checks
        report.addCheck(success
                ? TestReport.CheckResult.pass("Sustained Load Result",
                    String.format("%.1f%% pass rate, %d/%d calls", passRate, completed, totalAttempted), totalDur)
                : TestReport.CheckResult.fail("Sustained Load Result",
                    String.format("%.1f%% pass rate, %d/%d calls, %d failed", passRate, completed, totalAttempted, failed), totalDur));

        if (toneTotal > 0) {
            report.addCheck(tonePassed == toneTotal
                    ? TestReport.CheckResult.pass("Tone Detection (" + tonePassed + "/" + toneTotal + ")", Duration.ZERO)
                    : TestReport.CheckResult.fail("Tone Detection", tonePassed + "/" + toneTotal + " passed", Duration.ZERO));
        }

        // Print detailed summary to console (skip in TUI mode — dashboard shows results)
        if (eventListener == null) {
            System.out.println();
            System.out.println("====================================");
            System.out.printf(" SCENARIO: Sustained Load (%d concurrent, %ds)%n", concurrentCalls, totalDurationMs / 1000);
            System.out.println("====================================");
            System.out.printf("  Total calls attempted:  %d%n", totalAttempted);
            System.out.printf("  Total calls completed:  %d%n", completed);
            System.out.printf("  Total calls failed:     %d%n", failed);
            System.out.printf("  Calls/sec (avg):        %.2f%n", rate);
            System.out.printf("  Setup latency (avg):    %dms%n", avgSetup);
            System.out.printf("  Setup latency (p95):    %dms%n", p95Setup);
            System.out.printf("  Setup latency (p99):    %dms%n", p99Setup);
            if (toneTotal > 0) {
                System.out.printf("  Tone tests passed:      %d/%d%n", tonePassed, toneTotal);
            }
            System.out.printf("  Duration:               %dms%n", totalDur.toMillis());
            System.out.printf("  Result: %s (%.1f%% pass rate)%n", success ? "SUCCESS" : "FAILURE", passRate);
            System.out.println("====================================");
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

    private void receivePackets(RtpSession session, long durationMs) throws Exception {
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

    private void fireEvent(String message) {
        ScenarioEventListener listener = this.eventListener;
        if (listener != null) {
            try {
                listener.onEvent(message);
            } catch (Exception e) {
                log.debug("Event listener error: {}", e.getMessage());
            }
        }
    }

    /**
     * Callback interface for scenario events (used by the TUI dashboard).
     */
    @FunctionalInterface
    public interface ScenarioEventListener {
        void onEvent(String message);
    }

    /**
     * Holds an A-phone and its per-call latches/state. Latches are reset between calls.
     */
    static class PhoneSlot {
        int index;
        SipPhone phone;
        CountDownLatch registeredLatch;
        CountDownLatch answeredLatch;
        CountDownLatch byeLatch;
        AtomicReference<String> error;

        void resetLatches() {
            this.answeredLatch = new CountDownLatch(1);
            this.byeLatch = new CountDownLatch(1);
            this.error.set(null);
        }
    }
}
