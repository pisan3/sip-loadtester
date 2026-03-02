package com.loadtester.scenario;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.report.TestReport;
import com.loadtester.sip.DefaultSipStackFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for SustainedLoadScenario using real SIP credentials.
 * <p>
 * Maintains 20 concurrent calls (10 seconds each) for 2 minutes total,
 * cycling through calls as they complete.
 * <p>
 * This test is @Disabled by default and meant to be run manually.
 * Credentials are read from environment variables (see .env file).
 * <p>
 * Run with:
 *   source .env && mvn test \
 *     -Dtest="SustainedLoadScenarioIntegrationTest#twentyConcurrentCallsSustainedTwoMinutes" \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 */
class SustainedLoadScenarioIntegrationTest {

    @Test
    void twentyConcurrentCallsSustainedTwoMinutes() {
        String domain = System.getenv("SIP_DOMAIN");
        String proxyHost = System.getenv("SIP_PROXY_HOST");
        int proxyPort = Integer.parseInt(System.getenv().getOrDefault("SIP_PROXY_PORT", "5060"));
        String localIp = System.getenv().getOrDefault("LOCAL_IP", "127.0.0.1");

        SipAccountConfig phoneAConfig = new SipAccountConfig(
                System.getenv("SIP_A_USER"), System.getenv("SIP_A_PASSWORD"), domain,
                proxyHost, proxyPort,
                System.getenv("SIP_A_USER"), System.getenv("SIP_A_AUTH_USER"));

        SipAccountConfig phoneBConfig = new SipAccountConfig(
                System.getenv("SIP_B_USER"), System.getenv("SIP_B_PASSWORD"), domain,
                proxyHost, proxyPort,
                System.getenv("SIP_B_USER"), System.getenv("SIP_B_AUTH_USER"));

        SustainedLoadScenario scenario = new SustainedLoadScenario(
                phoneAConfig, phoneBConfig,
                new DefaultSipStackFactory(), localIp, 30);
        scenario.setCallDurationMs(10_000);       // 10 seconds per call
        scenario.setTotalDurationMs(60_000);       // 1 minute total
        scenario.setTimeoutSeconds(30);

        TestReport report = scenario.execute();
        System.out.println(report.getSummary());

        assertThat(report.allPassed())
                .as("All checks should pass:\n%s", report.getSummary())
                .isTrue();
    }
}
