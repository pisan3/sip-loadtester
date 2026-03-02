package com.loadtester.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SipAccountConfigTest {

    @Test
    void validConfigShouldBeCreated() {
        SipAccountConfig config = new SipAccountConfig(
                "alice", "secret", "example.com", "10.0.0.1", 5060, "Alice");

        assertThat(config.username()).isEqualTo("alice");
        assertThat(config.password()).isEqualTo("secret");
        assertThat(config.domain()).isEqualTo("example.com");
        assertThat(config.proxyHost()).isEqualTo("10.0.0.1");
        assertThat(config.proxyPort()).isEqualTo(5060);
        assertThat(config.displayName()).isEqualTo("Alice");
    }

    @Test
    void convenienceConstructorShouldUseUsernameAsDisplayName() {
        SipAccountConfig config = new SipAccountConfig(
                "bob", "pass", "example.com", "10.0.0.1", 5060);
        assertThat(config.displayName()).isEqualTo("bob");
    }

    @Test
    void sipUriShouldBeCorrectlyFormatted() {
        SipAccountConfig config = new SipAccountConfig(
                "alice", "secret", "example.com", "10.0.0.1", 5060);
        assertThat(config.sipUri()).isEqualTo("sip:alice@example.com");
    }

    @Test
    void proxyAddressShouldBeCorrectlyFormatted() {
        SipAccountConfig config = new SipAccountConfig(
                "alice", "secret", "example.com", "192.168.1.1", 5080);
        assertThat(config.proxyAddress()).isEqualTo("192.168.1.1:5080");
    }

    @Test
    void blankUsernameShouldThrow() {
        assertThatThrownBy(() ->
                new SipAccountConfig("", "pass", "example.com", "10.0.0.1", 5060))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void nullUsernameShouldThrow() {
        assertThatThrownBy(() ->
                new SipAccountConfig(null, "pass", "example.com", "10.0.0.1", 5060))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void nullPasswordShouldThrow() {
        assertThatThrownBy(() ->
                new SipAccountConfig("alice", null, "example.com", "10.0.0.1", 5060))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }

    @Test
    void blankDomainShouldThrow() {
        assertThatThrownBy(() ->
                new SipAccountConfig("alice", "pass", "", "10.0.0.1", 5060))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain");
    }

    @Test
    void blankProxyHostShouldThrow() {
        assertThatThrownBy(() ->
                new SipAccountConfig("alice", "pass", "example.com", "", 5060))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxyHost");
    }

    @Test
    void invalidPortShouldThrow() {
        assertThatThrownBy(() ->
                new SipAccountConfig("alice", "pass", "example.com", "10.0.0.1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxyPort");

        assertThatThrownBy(() ->
                new SipAccountConfig("alice", "pass", "example.com", "10.0.0.1", 70000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proxyPort");
    }

    @Test
    void emptyPasswordShouldBeAllowed() {
        // Some systems might use empty password
        SipAccountConfig config = new SipAccountConfig(
                "alice", "", "example.com", "10.0.0.1", 5060);
        assertThat(config.password()).isEmpty();
    }

    @Test
    void boundaryPortsShouldWork() {
        // Port 1 — minimum valid
        SipAccountConfig config1 = new SipAccountConfig(
                "alice", "pass", "example.com", "10.0.0.1", 1);
        assertThat(config1.proxyPort()).isEqualTo(1);

        // Port 65535 — maximum valid
        SipAccountConfig config2 = new SipAccountConfig(
                "alice", "pass", "example.com", "10.0.0.1", 65535);
        assertThat(config2.proxyPort()).isEqualTo(65535);
    }

    @Test
    void effectiveAuthUsernameShouldFallBackToUsername() {
        SipAccountConfig config = new SipAccountConfig(
                "alice", "pass", "example.com", "10.0.0.1", 5060);
        assertThat(config.effectiveAuthUsername()).isEqualTo("alice");
    }

    @Test
    void effectiveAuthUsernameShouldUseAuthUsernameWhenSet() {
        SipAccountConfig config = new SipAccountConfig(
                "1001", "pass", "example.com", "10.0.0.1", 5060,
                "Phone A", "u1001");
        assertThat(config.effectiveAuthUsername()).isEqualTo("u1001");
        assertThat(config.username()).isEqualTo("1001");
        assertThat(config.sipUri()).isEqualTo("sip:1001@example.com");
    }
}
