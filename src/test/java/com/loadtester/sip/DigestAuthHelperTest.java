package com.loadtester.sip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DigestAuthHelperTest {

    @Test
    void parseChallengeBasic() {
        String header = "Digest realm=\"example.com\", nonce=\"abc123\"";
        DigestAuthHelper.Challenge challenge = DigestAuthHelper.parseChallenge(header);

        assertThat(challenge.realm()).isEqualTo("example.com");
        assertThat(challenge.nonce()).isEqualTo("abc123");
        assertThat(challenge.opaque()).isNull();
        assertThat(challenge.qop()).isNull();
        assertThat(challenge.algorithm()).isEqualTo("MD5");
    }

    @Test
    void parseChallengeWithAllParams() {
        String header = "Digest realm=\"sip.example.com\", nonce=\"dcd98b\", " +
                "opaque=\"5ccc069\", qop=\"auth\", algorithm=MD5";
        DigestAuthHelper.Challenge challenge = DigestAuthHelper.parseChallenge(header);

        assertThat(challenge.realm()).isEqualTo("sip.example.com");
        assertThat(challenge.nonce()).isEqualTo("dcd98b");
        assertThat(challenge.opaque()).isEqualTo("5ccc069");
        assertThat(challenge.qop()).isEqualTo("auth");
        assertThat(challenge.algorithm()).isEqualTo("MD5");
    }

    @Test
    void parseChallengeNonDigestShouldThrow() {
        assertThatThrownBy(() -> DigestAuthHelper.parseChallenge("Basic realm=\"test\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a Digest");
    }

    @Test
    void parseChallengeNullShouldThrow() {
        assertThatThrownBy(() -> DigestAuthHelper.parseChallenge(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseChallengeMissingRealmShouldThrow() {
        assertThatThrownBy(() -> DigestAuthHelper.parseChallenge("Digest nonce=\"abc\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("realm or nonce");
    }

    @Test
    void parseChallengeMissingNonceShouldThrow() {
        assertThatThrownBy(() -> DigestAuthHelper.parseChallenge("Digest realm=\"test\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("realm or nonce");
    }

    @Test
    void computeResponseWithoutQopShouldFollowRfc2617() {
        // Known test vector from RFC 2617 Section 3.5 (adapted for SIP)
        String username = "Mufasa";
        String password = "Circle Of Life";
        String realm = "testrealm@host.com";
        String nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093";
        String method = "REGISTER";
        String uri = "sip:testrealm@host.com";

        String response = DigestAuthHelper.computeResponse(
                username, password, realm, nonce, method, uri, null, null, null);

        // Verify it's a 32-char hex string
        assertThat(response).hasSize(32);
        assertThat(response).matches("[0-9a-f]{32}");

        // Manually compute to verify:
        // HA1 = MD5("Mufasa:testrealm@host.com:Circle Of Life")
        String ha1 = DigestAuthHelper.md5Hex("Mufasa:testrealm@host.com:Circle Of Life");
        // HA2 = MD5("REGISTER:sip:testrealm@host.com")
        String ha2 = DigestAuthHelper.md5Hex("REGISTER:sip:testrealm@host.com");
        // response = MD5(HA1:nonce:HA2)
        String expected = DigestAuthHelper.md5Hex(ha1 + ":" + nonce + ":" + ha2);
        assertThat(response).isEqualTo(expected);
    }

    @Test
    void computeResponseWithQopAuthShouldIncludeNcAndCnonce() {
        String username = "alice";
        String password = "secret";
        String realm = "example.com";
        String nonce = "abc123";
        String method = "REGISTER";
        String uri = "sip:example.com";
        String qop = "auth";
        String nc = "00000001";
        String cnonce = "0a4f113b";

        String response = DigestAuthHelper.computeResponse(
                username, password, realm, nonce, method, uri, qop, nc, cnonce);

        // Manually verify
        String ha1 = DigestAuthHelper.md5Hex("alice:example.com:secret");
        String ha2 = DigestAuthHelper.md5Hex("REGISTER:sip:example.com");
        String expected = DigestAuthHelper.md5Hex(ha1 + ":abc123:00000001:0a4f113b:auth:" + ha2);
        assertThat(response).isEqualTo(expected);
    }

    @Test
    void buildAuthorizationHeaderShouldContainAllFields() {
        DigestAuthHelper.Challenge challenge = new DigestAuthHelper.Challenge(
                "example.com", "abc123", "opaque456", "auth", "MD5");

        String header = DigestAuthHelper.buildAuthorizationHeader(
                "alice", "secret", "REGISTER", "sip:example.com", challenge);

        assertThat(header).startsWith("Digest ");
        assertThat(header).contains("username=\"alice\"");
        assertThat(header).contains("realm=\"example.com\"");
        assertThat(header).contains("nonce=\"abc123\"");
        assertThat(header).contains("uri=\"sip:example.com\"");
        assertThat(header).contains("response=\"");
        assertThat(header).contains("algorithm=MD5");
        assertThat(header).contains("qop=auth");
        assertThat(header).contains("nc=00000001");
        assertThat(header).contains("cnonce=\"");
        assertThat(header).contains("opaque=\"opaque456\"");
    }

    @Test
    void buildAuthorizationHeaderWithoutQopShouldOmitNcAndCnonce() {
        DigestAuthHelper.Challenge challenge = new DigestAuthHelper.Challenge(
                "example.com", "abc123", null, null, "MD5");

        String header = DigestAuthHelper.buildAuthorizationHeader(
                "alice", "secret", "REGISTER", "sip:example.com", challenge);

        assertThat(header).startsWith("Digest ");
        assertThat(header).contains("username=\"alice\"");
        assertThat(header).doesNotContain("qop=");
        assertThat(header).doesNotContain("nc=");
        assertThat(header).doesNotContain("cnonce=");
        assertThat(header).doesNotContain("opaque=");
    }

    @Test
    void md5HexShouldProduceCorrectHash() {
        // Well-known MD5 test vector
        assertThat(DigestAuthHelper.md5Hex("")).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
        assertThat(DigestAuthHelper.md5Hex("abc")).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
        assertThat(DigestAuthHelper.md5Hex("hello")).isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    void extractParamShouldHandleQuotedValues() {
        String params = "realm=\"example.com\", nonce=\"abc\"";
        assertThat(DigestAuthHelper.extractParam(params, "realm")).isEqualTo("example.com");
        assertThat(DigestAuthHelper.extractParam(params, "nonce")).isEqualTo("abc");
    }

    @Test
    void extractParamShouldHandleUnquotedValues() {
        String params = "realm=\"example.com\", algorithm=MD5, qop=auth";
        assertThat(DigestAuthHelper.extractParam(params, "algorithm")).isEqualTo("MD5");
        assertThat(DigestAuthHelper.extractParam(params, "qop")).isEqualTo("auth");
    }

    @Test
    void extractParamShouldReturnNullForMissingParam() {
        String params = "realm=\"example.com\"";
        assertThat(DigestAuthHelper.extractParam(params, "nonce")).isNull();
    }

    @Test
    void parseChallengeWithStaleParam() {
        // Some servers include stale=true/false
        String header = "Digest realm=\"example.com\", nonce=\"abc123\", stale=false";
        DigestAuthHelper.Challenge challenge = DigestAuthHelper.parseChallenge(header);
        assertThat(challenge.realm()).isEqualTo("example.com");
        assertThat(challenge.nonce()).isEqualTo("abc123");
    }

    @Test
    void differentMethodsShouldProduceDifferentResponses() {
        DigestAuthHelper.Challenge challenge = new DigestAuthHelper.Challenge(
                "example.com", "abc", null, null, "MD5");

        String regResponse = DigestAuthHelper.computeResponse(
                "alice", "pass", "example.com", "abc", "REGISTER", "sip:example.com",
                null, null, null);
        String inviteResponse = DigestAuthHelper.computeResponse(
                "alice", "pass", "example.com", "abc", "INVITE", "sip:example.com",
                null, null, null);

        assertThat(regResponse).isNotEqualTo(inviteResponse);
    }
}
