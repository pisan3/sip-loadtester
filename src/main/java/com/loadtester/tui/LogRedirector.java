package com.loadtester.tui;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Redirects all console output (System.out, System.err) to a log file.
 * <p>
 * This must be called <b>before</b> any SLF4J logger is used, because
 * {@code slf4j-simple} writes to {@code System.err} and captures the stream
 * reference at class-load time. Redirecting early ensures all SLF4J output
 * goes to the file as well.
 * <p>
 * Also silences Log4j 1.x (used by JAIN-SIP RI) by resetting the root logger.
 * <p>
 * Call {@link #restore()} to switch back to the original console streams
 * (e.g. after Lanterna exits, so any final error messages still appear).
 */
public final class LogRedirector {

    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static PrintStream logStream;

    private LogRedirector() {}

    /**
     * Redirect System.out and System.err to the specified file.
     * Silences Log4j root logger to prevent "no appenders" warnings.
     *
     * @param logFilePath path to the log file (created/appended)
     * @throws IOException if the file cannot be opened
     */
    public static void redirectToFile(String logFilePath) throws IOException {
        originalOut = System.out;
        originalErr = System.err;

        logStream = new PrintStream(new FileOutputStream(logFilePath, true), true);
        System.setOut(logStream);
        System.setErr(logStream);

        // Silence Log4j 1.x root logger to prevent console output
        silenceLog4j();
    }

    /**
     * Restore original System.out and System.err streams.
     * Closes the log file stream.
     */
    public static void restore() {
        if (originalOut != null) {
            System.setOut(originalOut);
        }
        if (originalErr != null) {
            System.setErr(originalErr);
        }
        if (logStream != null) {
            logStream.close();
            logStream = null;
        }
    }

    /**
     * Returns the original System.err (before redirection).
     * Useful for fatal errors that must reach the real terminal.
     */
    public static PrintStream getOriginalErr() {
        return originalErr != null ? originalErr : System.err;
    }

    /**
     * Returns the original System.out (before redirection).
     * Needed by Lanterna to write terminal escape sequences to the real terminal.
     */
    public static PrintStream getOriginalOut() {
        return originalOut != null ? originalOut : System.out;
    }

    private static void silenceLog4j() {
        try {
            // Remove all appenders from the root logger to prevent any console output
            org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
            rootLogger.removeAllAppenders();
            // Add a null appender to suppress the "no appenders" warning
            rootLogger.addAppender(new org.apache.log4j.varia.NullAppender());
        } catch (Exception ignored) {
            // Log4j might not be on the classpath in tests — that's fine
        }
    }
}
