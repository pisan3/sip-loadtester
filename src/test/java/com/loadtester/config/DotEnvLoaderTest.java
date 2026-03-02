package com.loadtester.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DotEnvLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadShouldParseSimpleKeyValuePairs() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                SIP_PROXY_HOST=sipproxy22.telavox.se
                SIP_PROXY_PORT=5060
                SIP_DOMAIN=siptest.telavox.se
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).containsEntry("SIP_PROXY_HOST", "sipproxy22.telavox.se");
        assertThat(env).containsEntry("SIP_PROXY_PORT", "5060");
        assertThat(env).containsEntry("SIP_DOMAIN", "siptest.telavox.se");
        assertThat(env).hasSize(3);
    }

    @Test
    void loadShouldSkipCommentsAndBlankLines() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                # This is a comment
                
                KEY1=value1
                
                # Another comment
                KEY2=value2
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).hasSize(2);
        assertThat(env).containsEntry("KEY1", "value1");
        assertThat(env).containsEntry("KEY2", "value2");
    }

    @Test
    void loadShouldStripDoubleQuotes() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                PASSWORD="my secret password"
                SIMPLE=noQuotes
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).containsEntry("PASSWORD", "my secret password");
        assertThat(env).containsEntry("SIMPLE", "noQuotes");
    }

    @Test
    void loadShouldStripSingleQuotes() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                TOKEN='abc123'
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).containsEntry("TOKEN", "abc123");
    }

    @Test
    void loadShouldHandleEqualsInValue() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                BASE64=dGVzdA==
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).containsEntry("BASE64", "dGVzdA==");
    }

    @Test
    void loadShouldHandleEmptyValue() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                EMPTY_KEY=
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).containsEntry("EMPTY_KEY", "");
    }

    @Test
    void loadShouldTrimWhitespaceAroundKeyAndValue() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                  KEY_WITH_SPACES  =  value_with_spaces  
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).containsEntry("KEY_WITH_SPACES", "value_with_spaces");
    }

    @Test
    void loadShouldSkipLinesWithoutEquals() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                VALID_KEY=valid_value
                invalid line without equals
                ANOTHER_KEY=another_value
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).hasSize(2);
        assertThat(env).containsEntry("VALID_KEY", "valid_value");
        assertThat(env).containsEntry("ANOTHER_KEY", "another_value");
    }

    @Test
    void loadShouldSkipEmptyKeys() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                =value_with_no_key
                VALID=ok
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).hasSize(1);
        assertThat(env).containsEntry("VALID", "ok");
    }

    @Test
    void loadShouldReturnUnmodifiableMap() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "KEY=value\n");

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThatThrownBy(() -> env.put("NEW", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void loadShouldPreserveInsertionOrder() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                Z_LAST=3
                A_FIRST=1
                M_MIDDLE=2
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env.keySet()).containsExactly("Z_LAST", "A_FIRST", "M_MIDDLE");
    }

    @Test
    void loadShouldThrowIOExceptionForMissingFile() {
        Path nonExistent = tempDir.resolve("nonexistent.env");

        assertThatThrownBy(() -> DotEnvLoader.load(nonExistent))
                .isInstanceOf(IOException.class);
    }

    @Test
    void loadOrEmptyShouldReturnEmptyMapForMissingFile() {
        Path nonExistent = tempDir.resolve("nonexistent.env");

        Map<String, String> env = DotEnvLoader.loadOrEmpty(nonExistent);

        assertThat(env).isEmpty();
    }

    @Test
    void loadOrEmptyShouldLoadExistingFile() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "KEY=value\n");

        Map<String, String> env = DotEnvLoader.loadOrEmpty(envFile);

        assertThat(env).containsEntry("KEY", "value");
    }

    @Test
    void loadShouldHandleEmptyFile() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "");

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).isEmpty();
    }

    @Test
    void loadShouldHandleFileWithOnlyComments() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                # Comment 1
                # Comment 2
                # Comment 3
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).isEmpty();
    }

    @Test
    void loadShouldHandleHashInPassword() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                PASSWORD=my#secret#pass
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        // Hash in value should be preserved (no inline comment stripping)
        assertThat(env).containsEntry("PASSWORD", "my#secret#pass");
    }

    @Test
    void loadShouldNotStripMismatchedQuotes() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                MIXED="value'
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        // Mismatched quotes should be preserved
        assertThat(env).containsEntry("MIXED", "\"value'");
    }

    @Test
    void stripQuotesShouldHandleSingleCharacter() {
        assertThat(DotEnvLoader.stripQuotes("x")).isEqualTo("x");
    }

    @Test
    void stripQuotesShouldHandleEmptyString() {
        assertThat(DotEnvLoader.stripQuotes("")).isEqualTo("");
    }

    @Test
    void stripQuotesShouldHandleEmptyQuotedString() {
        assertThat(DotEnvLoader.stripQuotes("\"\"")).isEqualTo("");
        assertThat(DotEnvLoader.stripQuotes("''")).isEqualTo("");
    }

    @Test
    void loadShouldParseRealEnvFormat() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, """
                # SIP Load Tester - Environment Configuration
                
                # SIP Proxy / PBX
                SIP_PROXY_HOST=sipproxy22.telavox.se
                SIP_PROXY_PORT=5060
                SIP_DOMAIN=siptest.telavox.se
                
                # Phone A (caller)
                SIP_A_USER=0101535069
                SIP_A_PASSWORD=OVbMLEHa1Je
                SIP_A_AUTH_USER=u0101535069
                
                # Phone B (callee)
                SIP_B_USER=0101535901
                SIP_B_PASSWORD=rtmv90k4Nt
                SIP_B_AUTH_USER=u0101535901
                
                # Network
                LOCAL_IP=10.0.2.15
                """);

        Map<String, String> env = DotEnvLoader.load(envFile);

        assertThat(env).hasSize(10);
        assertThat(env).containsEntry("SIP_PROXY_HOST", "sipproxy22.telavox.se");
        assertThat(env).containsEntry("SIP_PROXY_PORT", "5060");
        assertThat(env).containsEntry("SIP_DOMAIN", "siptest.telavox.se");
        assertThat(env).containsEntry("SIP_A_USER", "0101535069");
        assertThat(env).containsEntry("SIP_A_PASSWORD", "OVbMLEHa1Je");
        assertThat(env).containsEntry("SIP_A_AUTH_USER", "u0101535069");
        assertThat(env).containsEntry("SIP_B_USER", "0101535901");
        assertThat(env).containsEntry("SIP_B_PASSWORD", "rtmv90k4Nt");
        assertThat(env).containsEntry("SIP_B_AUTH_USER", "u0101535901");
        assertThat(env).containsEntry("LOCAL_IP", "10.0.2.15");
    }
}
