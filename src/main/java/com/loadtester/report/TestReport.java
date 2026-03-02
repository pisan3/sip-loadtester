package com.loadtester.report;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects timing measurements and pass/fail results for a test scenario.
 */
public class TestReport {

    /**
     * A single assertion/check result.
     */
    public record CheckResult(String name, boolean passed, String detail, Duration duration) {
        public static CheckResult pass(String name, String detail, Duration duration) {
            return new CheckResult(name, true, detail, duration);
        }
        public static CheckResult fail(String name, String detail, Duration duration) {
            return new CheckResult(name, false, detail, duration);
        }
        public static CheckResult pass(String name, Duration duration) {
            return new CheckResult(name, true, "OK", duration);
        }
        public static CheckResult fail(String name, String detail) {
            return new CheckResult(name, false, detail, Duration.ZERO);
        }
    }

    private final String scenarioName;
    private final Instant startTime;
    private Instant endTime;
    private final List<CheckResult> checks = new ArrayList<>();

    public TestReport(String scenarioName) {
        this.scenarioName = scenarioName;
        this.startTime = Instant.now();
    }

    public void addCheck(CheckResult check) {
        checks.add(check);
    }

    public void finish() {
        this.endTime = Instant.now();
    }

    public boolean allPassed() {
        return checks.stream().allMatch(CheckResult::passed);
    }

    public long passedCount() {
        return checks.stream().filter(CheckResult::passed).count();
    }

    public long failedCount() {
        return checks.stream().filter(c -> !c.passed()).count();
    }

    public List<CheckResult> getChecks() {
        return Collections.unmodifiableList(checks);
    }

    public Duration totalDuration() {
        if (endTime == null) return Duration.between(startTime, Instant.now());
        return Duration.between(startTime, endTime);
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n====================================\n");
        sb.append(" SCENARIO: ").append(scenarioName).append("\n");
        sb.append("====================================\n\n");

        for (CheckResult check : checks) {
            String status = check.passed() ? "PASS" : "FAIL";
            String dur = check.duration() != null && !check.duration().isZero()
                    ? String.format(" (%dms)", check.duration().toMillis()) : "";
            sb.append(String.format("  [%s] %s%s\n", status, check.name(), dur));
            if (!check.passed()) {
                sb.append(String.format("         -> %s\n", check.detail()));
            }
        }

        sb.append("\n------------------------------------\n");
        sb.append(String.format("  Total: %d checks, %d passed, %d failed\n",
                checks.size(), passedCount(), failedCount()));
        sb.append(String.format("  Duration: %dms\n", totalDuration().toMillis()));
        sb.append(String.format("  Result: %s\n", allPassed() ? "SUCCESS" : "FAILURE"));
        sb.append("====================================\n");

        return sb.toString();
    }
}
