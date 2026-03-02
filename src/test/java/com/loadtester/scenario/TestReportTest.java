package com.loadtester.scenario;

import com.loadtester.media.*;
import com.loadtester.report.TestReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for TestReport — pure data collection, no mocking needed.
 */
class TestReportTest {

    @Test
    void newReportShouldHaveNoChecks() {
        TestReport report = new TestReport("test");
        assertThat(report.getChecks()).isEmpty();
        assertThat(report.allPassed()).isTrue(); // vacuously true
        assertThat(report.passedCount()).isEqualTo(0);
        assertThat(report.failedCount()).isEqualTo(0);
    }

    @Test
    void addPassingCheckShouldBeTracked() {
        TestReport report = new TestReport("test");
        report.addCheck(TestReport.CheckResult.pass("Registration", Duration.ofMillis(100)));

        assertThat(report.getChecks()).hasSize(1);
        assertThat(report.allPassed()).isTrue();
        assertThat(report.passedCount()).isEqualTo(1);
    }

    @Test
    void addFailingCheckShouldMakeAllPassedFalse() {
        TestReport report = new TestReport("test");
        report.addCheck(TestReport.CheckResult.pass("Step 1", Duration.ofMillis(50)));
        report.addCheck(TestReport.CheckResult.fail("Step 2", "timeout", Duration.ofMillis(5000)));

        assertThat(report.allPassed()).isFalse();
        assertThat(report.passedCount()).isEqualTo(1);
        assertThat(report.failedCount()).isEqualTo(1);
    }

    @Test
    void summaryShouldContainScenarioName() {
        TestReport report = new TestReport("A-calls-B");
        report.finish();
        String summary = report.getSummary();
        assertThat(summary).contains("A-calls-B");
    }

    @Test
    void summaryShouldShowPassAndFailStatus() {
        TestReport report = new TestReport("test");
        report.addCheck(TestReport.CheckResult.pass("Good", Duration.ofMillis(10)));
        report.addCheck(TestReport.CheckResult.fail("Bad", "broke", Duration.ofMillis(20)));
        report.finish();

        String summary = report.getSummary();
        assertThat(summary).contains("[PASS] Good");
        assertThat(summary).contains("[FAIL] Bad");
        assertThat(summary).contains("broke");
        assertThat(summary).contains("1 passed");
        assertThat(summary).contains("1 failed");
        assertThat(summary).contains("FAILURE");
    }

    @Test
    void allPassingSummaryShouldShowSuccess() {
        TestReport report = new TestReport("test");
        report.addCheck(TestReport.CheckResult.pass("Step 1", Duration.ofMillis(10)));
        report.addCheck(TestReport.CheckResult.pass("Step 2", Duration.ofMillis(20)));
        report.finish();

        assertThat(report.getSummary()).contains("SUCCESS");
    }

    @Test
    void totalDurationShouldBeNonNegative() {
        TestReport report = new TestReport("test");
        assertThat(report.totalDuration().toMillis()).isGreaterThanOrEqualTo(0);
        report.finish();
        assertThat(report.totalDuration().toMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void checksListShouldBeUnmodifiable() {
        TestReport report = new TestReport("test");
        assertThatThrownBy(() ->
                report.getChecks().add(TestReport.CheckResult.pass("hack", Duration.ZERO)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void failWithoutDurationConvenienceMethod() {
        TestReport.CheckResult result = TestReport.CheckResult.fail("oops", "something broke");
        assertThat(result.passed()).isFalse();
        assertThat(result.name()).isEqualTo("oops");
        assertThat(result.detail()).isEqualTo("something broke");
        assertThat(result.duration()).isEqualTo(Duration.ZERO);
    }
}
