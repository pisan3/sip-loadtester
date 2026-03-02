package com.loadtester.tui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ResultsWindow static helper methods.
 */
class ResultsWindowTest {

    @Test
    void formatDurationSeconds() {
        assertThat(ResultsWindow.formatDuration(45)).isEqualTo("0m 45s");
    }

    @Test
    void formatDurationMinutes() {
        assertThat(ResultsWindow.formatDuration(90)).isEqualTo("1m 30s");
    }

    @Test
    void formatDurationZero() {
        assertThat(ResultsWindow.formatDuration(0)).isEqualTo("0m 00s");
    }

    @Test
    void formatDurationExactMinute() {
        assertThat(ResultsWindow.formatDuration(60)).isEqualTo("1m 00s");
    }

    @Test
    void formatDurationHours() {
        assertThat(ResultsWindow.formatDuration(3661)).isEqualTo("1h 01m 01s");
    }

    @Test
    void formatDurationExactHour() {
        assertThat(ResultsWindow.formatDuration(3600)).isEqualTo("1h 00m 00s");
    }

    @Test
    void actionEnumValues() {
        assertThat(ResultsWindow.Action.values()).containsExactly(
                ResultsWindow.Action.NEW_TEST, ResultsWindow.Action.QUIT);
    }
}
