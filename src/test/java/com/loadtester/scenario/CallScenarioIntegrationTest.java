package com.loadtester.scenario;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.media.*;
import com.loadtester.report.TestReport;
import com.loadtester.sip.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for CallScenario using real SIP credentials.
 * <p>
 * This test is @Disabled by default and meant to be run manually
 * when real PBX credentials are available.
 * <p>
 * Run with:
 *   mvn test -Dtest=CallScenarioIntegrationTest \
 *     -DproxyHost=10.0.0.1 -DproxyPort=5060 -Ddomain=example.com \
 *     -DaUser=alice -DaPassword=secret1 \
 *     -DbUser=bob -DbPassword=secret2 \
 *     -DlocalIp=10.0.0.50
 */
@Disabled("Requires real SIP PBX credentials — enable manually")
class CallScenarioIntegrationTest {

    @Test
    void fullCallScenarioAgainstRealPbx() {
        String proxyHost = System.getProperty("proxyHost", "127.0.0.1");
        int proxyPort = Integer.parseInt(System.getProperty("proxyPort", "5060"));
        String domain = System.getProperty("domain", "localhost");
        String aUser = System.getProperty("aUser", "alice");
        String aPassword = System.getProperty("aPassword", "alice123");
        String bUser = System.getProperty("bUser", "bob");
        String bPassword = System.getProperty("bPassword", "bob123");
        String localIp = System.getProperty("localIp", "127.0.0.1");

        SipAccountConfig phoneAConfig = new SipAccountConfig(
                aUser, aPassword, domain, proxyHost, proxyPort);
        SipAccountConfig phoneBConfig = new SipAccountConfig(
                bUser, bPassword, domain, proxyHost, proxyPort);

        CallScenario scenario = new CallScenario(
                phoneAConfig, phoneBConfig,
                new DefaultSipStackFactory(), localIp);
        scenario.setMediaDurationMs(3000);
        scenario.setTimeoutSeconds(15);

        TestReport report = scenario.execute();
        System.out.println(report.getSummary());

        assertThat(report.allPassed())
                .as("All checks should pass:\n%s", report.getSummary())
                .isTrue();
    }
}
