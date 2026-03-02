package com.loadtester.tui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for DashboardWindow static helper methods.
 */
class DashboardWindowTest {

    // --- Progress bar tests ---

    @Test
    void buildProgressBarAtZeroPercent() {
        String bar = DashboardWindow.buildProgressBar(0.0);
        assertThat(bar).isEqualTo("[-------------------------]   0%");
    }

    @Test
    void buildProgressBarAt50Percent() {
        String bar = DashboardWindow.buildProgressBar(0.5);
        // 25 * 0.5 = 12 filled, 13 empty
        assertThat(bar).startsWith("[############");
        assertThat(bar).contains("50%");
    }

    @Test
    void buildProgressBarAt100Percent() {
        String bar = DashboardWindow.buildProgressBar(1.0);
        assertThat(bar).isEqualTo("[#########################] 100%");
    }

    @Test
    void buildProgressBarAt75Percent() {
        String bar = DashboardWindow.buildProgressBar(0.75);
        assertThat(bar).contains("75%");
    }

    @Test
    void buildProgressBarOverflowClamps() {
        // > 1.0 should clamp the bar to 100% filled but show real percentage
        String bar = DashboardWindow.buildProgressBar(1.5);
        assertThat(bar).contains("150%");
        assertThat(bar).startsWith("[#########################]");
    }

    // --- Duration formatting tests ---

    @Test
    void formatDurationZero() {
        assertThat(DashboardWindow.formatDuration(0)).isEqualTo("00:00");
    }

    @Test
    void formatDurationSeconds() {
        assertThat(DashboardWindow.formatDuration(45)).isEqualTo("00:45");
    }

    @Test
    void formatDurationMinutes() {
        assertThat(DashboardWindow.formatDuration(90)).isEqualTo("01:30");
    }

    @Test
    void formatDurationHour() {
        assertThat(DashboardWindow.formatDuration(3661)).isEqualTo("01:01:01");
    }

    // --- ConfigResult tests ---

    @Test
    void configResultQuitShouldBeMarkedAsQuit() {
        ConfigWindow.ConfigResult result = ConfigWindow.ConfigResult.quit();
        assertThat(result.isQuit()).isTrue();
    }

    @Test
    void configResultShouldHoldAllValues() {
        ConfigWindow.ConfigResult result = new ConfigWindow.ConfigResult(
                "proxy.example.com", 5060, "example.com",
                "alice", "secret1", "auth_alice",
                "bob", "secret2", "auth_bob",
                "10.0.0.1",
                30, 10, 60, 500, 30,
                false
        );

        assertThat(result.proxyHost()).isEqualTo("proxy.example.com");
        assertThat(result.proxyPort()).isEqualTo(5060);
        assertThat(result.domain()).isEqualTo("example.com");
        assertThat(result.aUser()).isEqualTo("alice");
        assertThat(result.aPassword()).isEqualTo("secret1");
        assertThat(result.aAuthUser()).isEqualTo("auth_alice");
        assertThat(result.bUser()).isEqualTo("bob");
        assertThat(result.bPassword()).isEqualTo("secret2");
        assertThat(result.bAuthUser()).isEqualTo("auth_bob");
        assertThat(result.localIp()).isEqualTo("10.0.0.1");
        assertThat(result.concurrentCalls()).isEqualTo(30);
        assertThat(result.callDurationSec()).isEqualTo(10);
        assertThat(result.totalDurationSec()).isEqualTo(60);
        assertThat(result.staggerDelayMs()).isEqualTo(500);
        assertThat(result.timeoutSeconds()).isEqualTo(30);
        assertThat(result.isQuit()).isFalse();
    }
}
