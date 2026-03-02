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
}
