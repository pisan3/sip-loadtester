package com.loadtester.scenario;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.media.RtpSession;
import com.loadtester.report.TestReport;
import com.loadtester.sip.SipPhone;
import com.loadtester.sip.SipStackFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SustainedLoadScenario.
 * <p>
 * Tests the scenario's configuration, metrics tracking, phone slot management,
 * and live stats formatting. Full integration tests with real SIP stacks
 * require real credentials and are in a separate integration test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SustainedLoadScenarioTest {

    private SipAccountConfig phoneAConfig;
    private SipAccountConfig phoneBConfig;

    @Mock private SipStackFactory mockStackFactory;

    @BeforeEach
    void setUp() {
        phoneAConfig = new SipAccountConfig("alice", "secret1", "example.com", "10.0.0.1", 5060);
        phoneBConfig = new SipAccountConfig("bob", "secret2", "example.com", "10.0.0.1", 5060);
    }

    @Test
    void constructorShouldSetDefaults() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        // Metrics should start at zero
        assertThat(scenario.getCallsStarted().get()).isEqualTo(0);
        assertThat(scenario.getCallsCompleted().get()).isEqualTo(0);
        assertThat(scenario.getCallsFailed().get()).isEqualTo(0);
        assertThat(scenario.getActiveCalls().get()).isEqualTo(0);
        assertThat(scenario.getSetupLatencies()).isEmpty();
        assertThat(scenario.getToneTestsPassed().get()).isEqualTo(0);
        assertThat(scenario.getToneTestsTotal().get()).isEqualTo(0);
    }

    @Test
    void settersShouldUpdateConfiguration() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 10);

        // These should not throw
        scenario.setCallDurationMs(5000);
        scenario.setTotalDurationMs(120_000);
        scenario.setTimeoutSeconds(60);
    }

    @Test
    void phoneSlotResetLatchesShouldCreateNewLatches() {
        SustainedLoadScenario.PhoneSlot slot = new SustainedLoadScenario.PhoneSlot();
        slot.answeredLatch = new CountDownLatch(1);
        slot.byeLatch = new CountDownLatch(1);
        slot.error = new AtomicReference<>("some error");

        // Reset
        slot.resetLatches();

        // New latches should have count of 1 (not yet counted down)
        assertThat(slot.answeredLatch.getCount()).isEqualTo(1);
        assertThat(slot.byeLatch.getCount()).isEqualTo(1);
        assertThat(slot.error.get()).isNull();
    }

    @Test
    void phoneSlotResetLatchesShouldClearError() {
        SustainedLoadScenario.PhoneSlot slot = new SustainedLoadScenario.PhoneSlot();
        slot.answeredLatch = new CountDownLatch(1);
        slot.byeLatch = new CountDownLatch(1);
        slot.error = new AtomicReference<>("call failed: 486 Busy Here");

        slot.resetLatches();

        assertThat(slot.error.get()).isNull();
    }

    @Test
    void metricsCountersShouldBeThreadSafe() throws Exception {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        int numThreads = 10;
        int incrementsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < incrementsPerThread; i++) {
                        scenario.getCallsStarted().incrementAndGet();
                        scenario.getCallsCompleted().incrementAndGet();
                        scenario.getCallsFailed().incrementAndGet();
                        scenario.getActiveCalls().incrementAndGet();
                        scenario.getSetupLatencies().add((long) i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        long expected = (long) numThreads * incrementsPerThread;
        assertThat(scenario.getCallsStarted().get()).isEqualTo(expected);
        assertThat(scenario.getCallsCompleted().get()).isEqualTo(expected);
        assertThat(scenario.getCallsFailed().get()).isEqualTo(expected);
        assertThat(scenario.getActiveCalls().get()).isEqualTo((int) expected);
        assertThat(scenario.getSetupLatencies()).hasSize((int) expected);
    }

    @Test
    void liveStatsShouldNotThrowWithZeroMetrics() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        // Should not throw even with all-zero metrics
        assertThatCode(() -> scenario.printLiveStats(java.time.Instant.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void liveStatsShouldNotThrowWithPopulatedMetrics() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        scenario.getCallsStarted().set(50);
        scenario.getCallsCompleted().set(48);
        scenario.getCallsFailed().set(2);
        scenario.getActiveCalls().set(5);
        scenario.getSetupLatencies().add(200L);
        scenario.getSetupLatencies().add(300L);
        scenario.getSetupLatencies().add(250L);
        scenario.getToneTestsPassed().set(3);
        scenario.getToneTestsTotal().set(3);

        assertThatCode(() -> scenario.printLiveStats(
                java.time.Instant.now().minusSeconds(30)))
                .doesNotThrowAnyException();
    }

    @Test
    void testReportThreadSafety() throws Exception {
        TestReport report = new TestReport("Concurrent Test");

        int numThreads = 10;
        int checksPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < checksPerThread; i++) {
                        report.addCheck(TestReport.CheckResult.pass(
                                "Check-" + threadIdx + "-" + i, java.time.Duration.ofMillis(i)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(report.getChecks()).hasSize(numThreads * checksPerThread);
        assertThat(report.allPassed()).isTrue();
    }

    // --- New tests for stop, stagger, public metrics, event listener ---

    @Test
    void requestStopShouldSetFlag() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        assertThat(scenario.isStopRequested()).isFalse();

        scenario.requestStop();

        assertThat(scenario.isStopRequested()).isTrue();
    }

    @Test
    void staggerDelayShouldBeConfigurable() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        // Default should be 500ms
        assertThat(scenario.getStaggerDelayMs()).isEqualTo(500);

        scenario.setStaggerDelayMs(200);
        assertThat(scenario.getStaggerDelayMs()).isEqualTo(200);

        scenario.setStaggerDelayMs(1000);
        assertThat(scenario.getStaggerDelayMs()).isEqualTo(1000);
    }

    @Test
    void publicMetricAccessorsShouldReturnCorrectValues() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 10);

        // Manipulate internal counters via package-private accessors
        scenario.getCallsStarted().set(100);
        scenario.getCallsCompleted().set(95);
        scenario.getCallsFailed().set(5);
        scenario.getActiveCalls().set(8);
        scenario.getToneTestsPassed().set(7);
        scenario.getToneTestsTotal().set(7);

        // Public accessors should reflect the same values
        assertThat(scenario.getCallsStartedCount()).isEqualTo(100);
        assertThat(scenario.getCallsCompletedCount()).isEqualTo(95);
        assertThat(scenario.getCallsFailedCount()).isEqualTo(5);
        assertThat(scenario.getActiveCallsCount()).isEqualTo(8);
        assertThat(scenario.getConcurrentCalls()).isEqualTo(10);
        assertThat(scenario.getToneTestsPassedCount()).isEqualTo(7);
        assertThat(scenario.getToneTestsTotalCount()).isEqualTo(7);
    }

    @Test
    void latencyStatsShouldComputeCorrectly() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        // Empty latencies
        assertThat(scenario.getAvgSetupLatencyMs()).isEqualTo(0);
        assertThat(scenario.getP95SetupLatencyMs()).isEqualTo(0);
        assertThat(scenario.getP99SetupLatencyMs()).isEqualTo(0);

        // Add some latencies: 100, 200, 300, 400, 500
        for (long l = 100; l <= 500; l += 100) {
            scenario.getSetupLatencies().add(l);
        }

        assertThat(scenario.getAvgSetupLatencyMs()).isEqualTo(300); // (100+200+300+400+500)/5
        assertThat(scenario.getP95SetupLatencyMs()).isEqualTo(500); // 5*0.95=4.75 -> index 4 -> 500
        assertThat(scenario.getP99SetupLatencyMs()).isEqualTo(500); // 5*0.99=4.95 -> index 4 -> 500
    }

    @Test
    void latencyP95ShouldWorkWithManyValues() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        // Add 100 latencies: 1, 2, 3, ..., 100
        for (long l = 1; l <= 100; l++) {
            scenario.getSetupLatencies().add(l);
        }

        assertThat(scenario.getAvgSetupLatencyMs()).isEqualTo(50); // sum(1..100)/100 = 5050/100 = 50
        assertThat(scenario.getP95SetupLatencyMs()).isEqualTo(96); // 100*0.95=95 -> index 95 -> value 96
        assertThat(scenario.getP99SetupLatencyMs()).isEqualTo(100); // 100*0.99=99 -> index 99 -> value 100
    }

    @Test
    void totalDurationMsShouldBeExposed() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        // Default
        assertThat(scenario.getTotalDurationMs()).isEqualTo(60_000);

        scenario.setTotalDurationMs(120_000);
        assertThat(scenario.getTotalDurationMs()).isEqualTo(120_000);
    }

    @Test
    void scenarioStartShouldBeNullBeforeExecute() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        assertThat(scenario.getScenarioStart()).isNull();
    }

    @Test
    void eventListenerShouldReceiveStopEvent() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        List<String> events = new ArrayList<>();
        scenario.setEventListener(events::add);

        scenario.requestStop();

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).contains("Stop requested");
    }

    @Test
    void eventListenerExceptionShouldNotPropagate() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        scenario.setEventListener(msg -> { throw new RuntimeException("listener error"); });

        // requestStop fires an event — the listener exception should not propagate
        assertThatCode(scenario::requestStop).doesNotThrowAnyException();
        assertThat(scenario.isStopRequested()).isTrue();
    }

    @Test
    void nullEventListenerShouldNotCauseErrors() {
        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig, mockStackFactory, "127.0.0.1", 5);

        // No listener set — requestStop should work fine
        assertThatCode(scenario::requestStop).doesNotThrowAnyException();
    }
}
