package com.loadtester.tui;

import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;
import com.loadtester.config.DotEnvLoader;
import com.loadtester.config.SipAccountConfig;
import com.loadtester.report.TestReport;
import com.loadtester.scenario.SustainedLoadScenario;
import com.loadtester.sip.DefaultSipStackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main interactive TUI application for the SIP load tester.
 * <p>
 * Manages the lifecycle of Lanterna screens and coordinates the flow:
 * Configuration -> Dashboard (running) -> Results -> (loop or quit).
 */
public class InteractiveApp {

    private static final Logger log = LoggerFactory.getLogger(InteractiveApp.class);

    private final Path envPath;

    public InteractiveApp(Path envPath) {
        this.envPath = envPath;
    }

    /**
     * Run the interactive TUI. Blocks until the user quits.
     * <p>
     * Logging strategy: all SLF4J, Log4j, and System.out/err output is redirected
     * to {@code loadtester-tui.log} so it doesn't corrupt the Lanterna terminal.
     * Lanterna itself is created with the <b>original</b> stdout stream so it can
     * still write terminal escape sequences to the real terminal.
     */
    public void run() {
        // Step 1: Save the real terminal output stream BEFORE any redirection.
        // Lanterna needs it to send escape sequences to the actual terminal.
        OutputStream realOut = System.out;

        // Step 2: Redirect System.out/err to log file.
        // slf4j-simple writes to System.err; this must happen before SLF4J
        // class loads to be fully effective, but even if SLF4J already loaded,
        // it will still capture most output since it writes via System.err ref.
        try {
            LogRedirector.redirectToFile("loadtester-tui.log");
        } catch (Exception e) {
            // If we can't redirect, continue anyway — TUI will be glitchy but functional
            System.err.println("Warning: could not redirect logs: " + e.getMessage());
        }

        // Step 3: Create Lanterna terminal using the ORIGINAL stdout stream
        try (Terminal terminal = new DefaultTerminalFactory(realOut, System.in, Charset.defaultCharset())
                .createTerminal();
             Screen screen = new TerminalScreen(terminal)) {

            screen.startScreen();
            WindowBasedTextGUI textGUI = new MultiWindowTextGUI(screen);

            boolean running = true;
            while (running) {
                // --- Phase 1: Configuration ---
                Map<String, String> envDefaults = DotEnvLoader.loadOrEmpty(envPath);
                ConfigWindow configWindow = new ConfigWindow(envDefaults);
                textGUI.addWindowAndWait(configWindow);

                ConfigWindow.ConfigResult config = configWindow.getResult();
                if (config == null || config.isQuit()) {
                    break;
                }

                // --- Phase 2: Run test ---
                SipAccountConfig phoneAConfig = new SipAccountConfig(
                        config.aUser(), config.aPassword(), config.domain(),
                        config.proxyHost(), config.proxyPort(),
                        config.aUser(), config.aAuthUser());
                SipAccountConfig phoneBConfig = new SipAccountConfig(
                        config.bUser(), config.bPassword(), config.domain(),
                        config.proxyHost(), config.proxyPort(),
                        config.bUser(), config.bAuthUser());

                SustainedLoadScenario scenario = new SustainedLoadScenario(
                        phoneAConfig, phoneBConfig,
                        new DefaultSipStackFactory(), config.localIp(),
                        config.concurrentCalls());
                scenario.setCallDurationMs(config.callDurationSec() * 1000L);
                scenario.setTotalDurationMs(config.totalDurationSec() * 1000L);
                scenario.setTimeoutSeconds(config.timeoutSeconds());
                scenario.setStaggerDelayMs(config.staggerDelayMs());

                DashboardWindow dashboard = new DashboardWindow(
                        scenario, config.concurrentCalls(), config.totalDurationSec());

                // Run scenario on background thread
                AtomicReference<TestReport> reportRef = new AtomicReference<>();
                CompletableFuture<Void> scenarioFuture = CompletableFuture.runAsync(() -> {
                    try {
                        TestReport report = scenario.execute();
                        reportRef.set(report);
                    } catch (Exception e) {
                        log.error("Scenario execution error", e);
                    }
                });

                // Show dashboard and start its updater
                dashboard.startUpdater();
                textGUI.addWindow(dashboard);

                // Wait for scenario to finish, then update dashboard
                scenarioFuture.thenRun(() -> {
                    TestReport report = reportRef.get();
                    String status = report != null && report.allPassed()
                            ? "COMPLETED - ALL TESTS PASSED"
                            : "COMPLETED - TESTS FAILED";
                    dashboard.markFinished(status);

                    // Close dashboard after a short delay to show final status
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    dashboard.stopUpdater();
                    if (textGUI instanceof MultiWindowTextGUI) {
                        textGUI.getGUIThread().invokeLater(dashboard::close);
                    }
                });

                // Block until dashboard closes
                textGUI.waitForWindowToClose(dashboard);
                dashboard.stopUpdater();

                // --- Phase 3: Results ---
                TestReport report = reportRef.get();
                if (report == null) {
                    // Scenario never completed properly
                    log.warn("No test report available");
                    continue;
                }

                Instant start = scenario.getScenarioStart();
                long durationSec = start != null
                        ? Duration.between(start, Instant.now()).getSeconds()
                        : config.totalDurationSec();

                ResultsWindow results = new ResultsWindow(
                        report,
                        scenario.getCallsCompletedCount(),
                        scenario.getCallsFailedCount(),
                        durationSec > 0 ? (double) scenario.getCallsCompletedCount() / durationSec : 0,
                        scenario.getAvgSetupLatencyMs(),
                        scenario.getP95SetupLatencyMs(),
                        scenario.getP99SetupLatencyMs(),
                        scenario.getToneTestsPassedCount(),
                        scenario.getToneTestsTotalCount(),
                        durationSec
                );
                textGUI.addWindowAndWait(results);

                if (results.getAction() == ResultsWindow.Action.QUIT) {
                    running = false;
                }
                // else: Action.NEW_TEST -> loop back to config
            }

            screen.stopScreen();

        } catch (Exception e) {
            log.error("TUI error", e);
            // Use original stderr (not redirected) so user sees fatal errors
            PrintStream realErr = LogRedirector.getOriginalErr();
            realErr.println("TUI error: " + e.getMessage());
            e.printStackTrace(realErr);
        } finally {
            LogRedirector.restore();
        }
    }
}
