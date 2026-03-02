package com.loadtester.sip;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes SIP Digest Authentication responses per RFC 2617.
 * <p>
 * Pure computation — no SIP framework dependencies.
 * Handles both WWW-Authenticate (401) and Proxy-Authenticate (407) challenges.
 */
public class DigestAuthHelper {

    private DigestAuthHelper() {
    }

    /**
     * Parsed challenge parameters from a WWW-Authenticate or Proxy-Authenticate header.
     */
    public record Challenge(
            String realm,
            String nonce,
            String opaque,
            String qop,
            String algorithm
    ) {
    }

    /**
     * Parse a Digest challenge from a WWW-Authenticate or Proxy-Authenticate header value.
     *
     * @param headerValue the full header value, e.g. "Digest realm=\"example.com\", nonce=\"abc123\""
     * @return parsed challenge parameters
     */
    public static Challenge parseChallenge(String headerValue) {
        if (headerValue == null || !headerValue.toLowerCase().startsWith("digest ")) {
            throw new IllegalArgumentException("Not a Digest challenge: " + headerValue);
        }

        String params = headerValue.substring(7).trim();
        String realm = extractParam(params, "realm");
        String nonce = extractParam(params, "nonce");
        String opaque = extractParam(params, "opaque");
        String qop = extractParam(params, "qop");
        String algorithm = extractParam(params, "algorithm");

        if (realm == null || nonce == null) {
            throw new IllegalArgumentException("Challenge missing realm or nonce");
        }

        return new Challenge(realm, nonce, opaque, qop, algorithm != null ? algorithm : "MD5");
    }

    /**
     * Compute the Digest authentication response.
     *
     * @param username SIP username
     * @param password SIP password
     * @param realm    authentication realm
     * @param nonce    server nonce
     * @param method   SIP method (REGISTER, INVITE, etc.)
     * @param uri      request URI (e.g. sip:example.com)
     * @param qop      quality of protection (null or "auth")
     * @param nc       nonce count (hex string, e.g. "00000001")
     * @param cnonce   client nonce
     * @return the response digest (hex string)
     */
    public static String computeResponse(String username, String password, String realm,
                                         String nonce, String method, String uri,
                                         String qop, String nc, String cnonce) {
        // HA1 = MD5(username:realm:password)
        String ha1 = md5Hex(username + ":" + realm + ":" + password);

        // HA2 = MD5(method:uri)
        String ha2 = md5Hex(method + ":" + uri);

        // Response
        if ("auth".equals(qop)) {
            // response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
            return md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            // response = MD5(HA1:nonce:HA2)
            return md5Hex(ha1 + ":" + nonce + ":" + ha2);
        }
    }

    /**
     * Build a complete Authorization or Proxy-Authorization header value.
     *
     * @param username  SIP username
     * @param password  SIP password
     * @param method    SIP method
     * @param uri       request URI
     * @param challenge the parsed challenge from the server
     * @return the complete header value string
     */
    public static String buildAuthorizationHeader(String username, String password,
                                                  String method, String uri,
                                                  Challenge challenge) {
        String nc = "00000001";
        String cnonce = generateCNonce();

        String response = computeResponse(username, password, challenge.realm(),
                challenge.nonce(), method, uri, challenge.qop(), nc, cnonce);

        StringBuilder sb = new StringBuilder();
        sb.append("Digest ");
        sb.append("username=\"").append(username).append("\"");
        sb.append(", realm=\"").append(challenge.realm()).append("\"");
        sb.append(", nonce=\"").append(challenge.nonce()).append("\"");
        sb.append(", uri=\"").append(uri).append("\"");
        sb.append(", response=\"").append(response).append("\"");
        sb.append(", algorithm=").append(challenge.algorithm());

        if (challenge.qop() != null) {
            sb.append(", qop=").append(challenge.qop());
            sb.append(", nc=").append(nc);
            sb.append(", cnonce=\"").append(cnonce).append("\"");
        }
        if (challenge.opaque() != null) {
            sb.append(", opaque=\"").append(challenge.opaque()).append("\"");
        }

        return sb.toString();
    }

    /**
     * Extract a parameter value from a Digest challenge string.
     * Handles both quoted and unquoted values.
     */
    static String extractParam(String params, String name) {
        // Look for name= or name =
        String searchKey = name + "=";
        int idx = findParamIndex(params, name);
        if (idx < 0) return null;

        int valueStart = idx + searchKey.length();
        while (valueStart < params.length() && params.charAt(valueStart) == ' ') {
            valueStart++;
        }

        if (valueStart >= params.length()) return null;

        if (params.charAt(valueStart) == '"') {
            // Quoted value
            int endQuote = params.indexOf('"', valueStart + 1);
            if (endQuote < 0) return params.substring(valueStart + 1);
            return params.substring(valueStart + 1, endQuote);
        } else {
            // Unquoted value
            int end = params.indexOf(',', valueStart);
            if (end < 0) end = params.length();
            return params.substring(valueStart, end).trim();
        }
    }

    private static int findParamIndex(String params, String name) {
        String lower = params.toLowerCase();
        String key = name.toLowerCase() + "=";
        int idx = 0;
        while (idx < lower.length()) {
            int found = lower.indexOf(key, idx);
            if (found < 0) return -1;
            // Make sure it's at start or preceded by comma/space
            if (found == 0 || lower.charAt(found - 1) == ',' || lower.charAt(found - 1) == ' ') {
                return found;
            }
            idx = found + 1;
        }
        return -1;
    }

    static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    static String generateCNonce() {
        return Long.toHexString(System.nanoTime());
    }
}
