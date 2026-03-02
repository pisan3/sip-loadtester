package com.loadtester.scenario;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.report.TestReport;
import com.loadtester.sip.DefaultSipStackFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for ConcurrentCallScenario using real SIP credentials.
 * <p>
 * Runs 10 concurrent A-calls-B calls through the real PBX with 30 seconds
 * of RTP media per call.
 * <p>
 * This test is @Disabled by default and meant to be run manually.
 */
class ConcurrentCallScenarioIntegrationTest {

    @Disabled("Integration test — run manually against real PBX")
    @Test
    void tenConcurrentCallsAgainstRealPbx() {
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

        ConcurrentCallScenario scenario = new ConcurrentCallScenario(
                phoneAConfig, phoneBConfig,
                new DefaultSipStackFactory(), localIp, 10);
        scenario.setMediaDurationMs(30_000);
        scenario.setTimeoutSeconds(30);

        TestReport report = scenario.execute();
        System.out.println(report.getSummary());

        assertThat(report.allPassed())
                .as("All checks should pass:\n%s", report.getSummary())
                .isTrue();
    }
}
