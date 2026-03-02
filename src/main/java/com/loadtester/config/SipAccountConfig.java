package com.loadtester.config;

/**
 * Configuration for a SIP account (phone endpoint).
 * Immutable record holding all credentials and connection details.
 * <p>
 * {@code username} is used for SIP URIs (From, To, Contact headers).
 * {@code authUsername} is used for digest authentication; if null, {@code username} is used.
 */
public record SipAccountConfig(
        String username,
        String password,
        String domain,
        String proxyHost,
        int proxyPort,
        String displayName,
        String authUsername
) {
    public SipAccountConfig {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (password == null) {
            throw new IllegalArgumentException("password must not be null");
        }
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain must not be blank");
        }
        if (proxyHost == null || proxyHost.isBlank()) {
            throw new IllegalArgumentException("proxyHost must not be blank");
        }
        if (proxyPort < 1 || proxyPort > 65535) {
            throw new IllegalArgumentException("proxyPort must be between 1 and 65535");
        }
    }

    /**
     * Convenience constructor without display name or auth username.
     */
    public SipAccountConfig(String username, String password, String domain,
                            String proxyHost, int proxyPort) {
        this(username, password, domain, proxyHost, proxyPort, username, null);
    }

    /**
     * Convenience constructor with display name but no separate auth username.
     */
    public SipAccountConfig(String username, String password, String domain,
                            String proxyHost, int proxyPort, String displayName) {
        this(username, password, domain, proxyHost, proxyPort, displayName, null);
    }

    /**
     * Returns the username to use for digest authentication.
     * Falls back to {@code username} if no separate auth username is set.
     */
    public String effectiveAuthUsername() {
        return (authUsername != null && !authUsername.isBlank()) ? authUsername : username;
    }

    /**
     * Returns the SIP URI for this account (e.g. sip:alice@example.com).
     */
    public String sipUri() {
        return "sip:" + username + "@" + domain;
    }

    /**
     * Returns the proxy address as host:port.
     */
    public String proxyAddress() {
        return proxyHost + ":" + proxyPort;
    }
}
