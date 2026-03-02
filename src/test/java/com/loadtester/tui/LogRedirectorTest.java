package com.loadtester.tui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LogRedirector}.
 */
class LogRedirectorTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreStreams() {
        // Always restore so we don't break other tests
        LogRedirector.restore();
    }

    @Test
    void redirectToFile_capturesSystemOut() throws Exception {
        String logFile = tempDir.resolve("test.log").toString();
        PrintStream originalOut = System.out;

        LogRedirector.redirectToFile(logFile);

        System.out.println("hello from stdout");
        System.out.flush();

        LogRedirector.restore();

        String content = Files.readString(Path.of(logFile));
        assertThat(content).contains("hello from stdout");
        assertThat(System.out).isSameAs(originalOut);
    }

    @Test
    void redirectToFile_capturesSystemErr() throws Exception {
        String logFile = tempDir.resolve("test.log").toString();
        PrintStream originalErr = System.err;

        LogRedirector.redirectToFile(logFile);

        System.err.println("hello from stderr");
        System.err.flush();

        LogRedirector.restore();

        String content = Files.readString(Path.of(logFile));
        assertThat(content).contains("hello from stderr");
        assertThat(System.err).isSameAs(originalErr);
    }

    @Test
    void restore_returnsOriginalStreams() throws Exception {
        String logFile = tempDir.resolve("test.log").toString();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        LogRedirector.redirectToFile(logFile);

        assertThat(System.out).isNotSameAs(originalOut);
        assertThat(System.err).isNotSameAs(originalErr);

        LogRedirector.restore();

        assertThat(System.out).isSameAs(originalOut);
        assertThat(System.err).isSameAs(originalErr);
    }

    @Test
    void getOriginalErr_returnsPreRedirectStream() throws Exception {
        String logFile = tempDir.resolve("test.log").toString();
        PrintStream originalErr = System.err;

        LogRedirector.redirectToFile(logFile);

        assertThat(LogRedirector.getOriginalErr()).isSameAs(originalErr);
    }

    @Test
    void restore_withoutRedirect_doesNotCrash() {
        // Calling restore without redirect should be harmless
        LogRedirector.restore();
    }

    @Test
    void getOriginalErr_withoutRedirect_returnsSystemErr() {
        assertThat(LogRedirector.getOriginalErr()).isSameAs(System.err);
    }

    @Test
    void redirectToFile_appendsToExistingFile() throws Exception {
        Path logPath = tempDir.resolve("test.log");
        Files.writeString(logPath, "existing content\n");

        LogRedirector.redirectToFile(logPath.toString());

        System.out.println("new content");
        System.out.flush();

        LogRedirector.restore();

        String content = Files.readString(logPath);
        assertThat(content).contains("existing content");
        assertThat(content).contains("new content");
    }

    @Test
    void getOriginalOut_returnsPreRedirectStream() throws Exception {
        String logFile = tempDir.resolve("test.log").toString();
        PrintStream originalOut = System.out;

        LogRedirector.redirectToFile(logFile);

        assertThat(LogRedirector.getOriginalOut()).isSameAs(originalOut);
    }

    @Test
    void getOriginalOut_withoutRedirect_returnsSystemOut() {
        assertThat(LogRedirector.getOriginalOut()).isSameAs(System.out);
    }
}
