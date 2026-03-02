package com.loadtester.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.loadtester.scenario.SustainedLoadScenario;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

/**
 * Live dashboard window showing test progress and statistics in real time.
 * <p>
 * Polls the running {@link SustainedLoadScenario} for metrics every second
 * and updates the display. Supports stopping the test via 'S' key.
 */
public class DashboardWindow extends BasicWindow {

    private final SustainedLoadScenario scenario;
    private final int targetConcurrent;
    private final int totalDurationSec;

    // Stats labels
    private final Label elapsedLabel;
    private final Label progressBarLabel;
    private final Label activeLabel;
    private final Label startedLabel;
    private final Label completedLabel;
    private final Label failedLabel;
    private final Label rateLabel;
    private final Label passRateLabel;
    private final Label avgLatencyLabel;
    private final Label p95LatencyLabel;
    private final Label p99LatencyLabel;
    private final Label toneLabel;
    private final Label statusLabel;

    // Event log
    private final TextBox eventLog;
    private static final int MAX_LOG_LINES = 50;

    private ScheduledExecutorService updater;
    private volatile boolean finished;
    private volatile String finalStatus;

    public DashboardWindow(SustainedLoadScenario scenario, int targetConcurrent, int totalDurationSec) {
        super("SIP Load Tester - Running");
        setHints(List.of(Hint.CENTERED, Hint.FIT_TERMINAL_WINDOW));

        this.scenario = scenario;
        this.targetConcurrent = targetConcurrent;
        this.totalDurationSec = totalDurationSec;

        Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));
        mainPanel.setPreferredSize(new TerminalSize(68, 30));

        // --- Title ---
        mainPanel.addComponent(new EmptySpace());

        // --- Progress ---
        Panel progressPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        progressPanel.addComponent(new Label("  Elapsed: "));
        elapsedLabel = new Label("00:00 / " + formatDuration(totalDurationSec));
        progressPanel.addComponent(elapsedLabel);
        progressPanel.addComponent(new Label("   "));
        progressBarLabel = new Label(buildProgressBar(0));
        progressPanel.addComponent(progressBarLabel);
        mainPanel.addComponent(progressPanel);
        mainPanel.addComponent(new EmptySpace());

        // --- Separator ---
        mainPanel.addComponent(new Label("  " + "-".repeat(62)).addStyle(SGR.BOLD));

        // --- Call Metrics ---
        Panel metricsGrid = new Panel(new GridLayout(4));
        metricsGrid.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Beginning));

        metricsGrid.addComponent(new Label("  Active Calls:"));
        activeLabel = new Label("0/" + targetConcurrent);
        metricsGrid.addComponent(activeLabel);
        metricsGrid.addComponent(new Label("  Calls Started:"));
        startedLabel = new Label("0");
        metricsGrid.addComponent(startedLabel);

        metricsGrid.addComponent(new Label("  Completed:"));
        completedLabel = new Label("0");
        metricsGrid.addComponent(completedLabel);
        metricsGrid.addComponent(new Label("  Failed:"));
        failedLabel = new Label("0");
        metricsGrid.addComponent(failedLabel);

        metricsGrid.addComponent(new Label("  Calls/sec:"));
        rateLabel = new Label("0.0");
        metricsGrid.addComponent(rateLabel);
        metricsGrid.addComponent(new Label("  Pass Rate:"));
        passRateLabel = new Label("-");
        metricsGrid.addComponent(passRateLabel);

        mainPanel.addComponent(metricsGrid);
        mainPanel.addComponent(new EmptySpace());

        // --- Latency ---
        mainPanel.addComponent(new Label("  " + "-".repeat(62)).addStyle(SGR.BOLD));
        Panel latencyPanel = new Panel(new GridLayout(6));
        latencyPanel.addComponent(new Label("  Setup Latency"));
        latencyPanel.addComponent(new Label("  Avg:"));
        avgLatencyLabel = new Label("-");
        latencyPanel.addComponent(avgLatencyLabel);
        latencyPanel.addComponent(new Label("  P95:"));
        p95LatencyLabel = new Label("-");
        latencyPanel.addComponent(p95LatencyLabel);
        latencyPanel.addComponent(new Label("  P99:"));
        p99LatencyLabel = new Label(("-"));
        latencyPanel.addComponent(p99LatencyLabel);
        // missing component for 6-col grid
        mainPanel.addComponent(latencyPanel);

        // --- Tone ---
        Panel tonePanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        tonePanel.addComponent(new Label("  Tone Detection:  "));
        toneLabel = new Label("- (10% sampling)");
        tonePanel.addComponent(toneLabel);
        mainPanel.addComponent(tonePanel);

        mainPanel.addComponent(new EmptySpace());
        mainPanel.addComponent(new Label("  " + "-".repeat(62)).addStyle(SGR.BOLD));

        // --- Event Log ---
        mainPanel.addComponent(new Label("  Recent Events:"));
        eventLog = new TextBox(new TerminalSize(64, 8));
        eventLog.setReadOnly(true);
        mainPanel.addComponent(eventLog);

        mainPanel.addComponent(new EmptySpace());

        // --- Status + Controls ---
        mainPanel.addComponent(new Label("  " + "-".repeat(62)).addStyle(SGR.BOLD));
        statusLabel = new Label("  Running...");
        statusLabel.setForegroundColor(TextColor.ANSI.GREEN);
        mainPanel.addComponent(statusLabel);

        Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttonPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Center));
        Button stopButton = new Button("Stop Test (S)", this::onStop);
        Button quitButton = new Button("Force Quit (Q)", this::onForceQuit);
        buttonPanel.addComponent(stopButton);
        buttonPanel.addComponent(new Label("   "));
        buttonPanel.addComponent(quitButton);
        mainPanel.addComponent(buttonPanel);

        setComponent(mainPanel);

        // Register event listener on scenario
        scenario.setEventListener(this::addLogEvent);
    }

    /**
     * Start the background updater that refreshes stats every second.
     */
    public void startUpdater() {
        updater = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dashboard-updater");
            t.setDaemon(true);
            return t;
        });
        updater.scheduleAtFixedRate(this::updateStats, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Stop the background updater.
     */
    public void stopUpdater() {
        if (updater != null) {
            updater.shutdownNow();
        }
    }

    /**
     * Mark the dashboard as finished with the given status message.
     */
    public void markFinished(String status) {
        this.finished = true;
        this.finalStatus = status;
        // Do one final update
        if (getTextGUI() != null) {
            getTextGUI().getGUIThread().invokeLater(this::updateStatsOnGUIThread);
        }
    }

    private void updateStats() {
        if (getTextGUI() != null) {
            getTextGUI().getGUIThread().invokeLater(this::updateStatsOnGUIThread);
        }
    }

    private void updateStatsOnGUIThread() {
        Instant start = scenario.getScenarioStart();
        long elapsedSec = 0;
        if (start != null) {
            elapsedSec = Duration.between(start, Instant.now()).getSeconds();
        }

        long completed = scenario.getCallsCompletedCount();
        long failed = scenario.getCallsFailedCount();
        long started = scenario.getCallsStartedCount();
        int active = scenario.getActiveCallsCount();
        double rate = elapsedSec > 0 ? (double) completed / elapsedSec : 0;
        long totalAttempted = completed + failed;
        double passRate = totalAttempted > 0 ? (double) completed / totalAttempted * 100 : 0;

        // Progress
        double progress = totalDurationSec > 0 ? Math.min(1.0, (double) elapsedSec / totalDurationSec) : 0;
        elapsedLabel.setText(String.format("%s / %s", formatDuration(elapsedSec), formatDuration(totalDurationSec)));
        progressBarLabel.setText(buildProgressBar(progress));

        // Call metrics
        activeLabel.setText(active + "/" + targetConcurrent);
        startedLabel.setText(String.valueOf(started));
        completedLabel.setText(String.valueOf(completed));
        failedLabel.setText(String.valueOf(failed));
        if (failed > 0) {
            failedLabel.setForegroundColor(TextColor.ANSI.RED);
        }
        rateLabel.setText(String.format("%.1f", rate));
        passRateLabel.setText(totalAttempted > 0 ? String.format("%.1f%%", passRate) : "-");

        // Latency
        long avgMs = scenario.getAvgSetupLatencyMs();
        long p95Ms = scenario.getP95SetupLatencyMs();
        long p99Ms = scenario.getP99SetupLatencyMs();
        avgLatencyLabel.setText(avgMs > 0 ? avgMs + "ms" : "-");
        p95LatencyLabel.setText(p95Ms > 0 ? p95Ms + "ms" : "-");
        p99LatencyLabel.setText(p99Ms > 0 ? p99Ms + "ms" : "-");

        // Tone
        int tonePassed = scenario.getToneTestsPassedCount();
        int toneTotal = scenario.getToneTestsTotalCount();
        toneLabel.setText(toneTotal > 0
                ? tonePassed + "/" + toneTotal + " passed (10% sampling)"
                : "- (10% sampling)");

        // Status
        if (finished) {
            statusLabel.setText("  " + (finalStatus != null ? finalStatus : "Finished"));
            statusLabel.setForegroundColor(
                    (finalStatus != null && finalStatus.contains("FAIL"))
                            ? TextColor.ANSI.RED : TextColor.ANSI.GREEN);
        } else if (scenario.isStopRequested()) {
            statusLabel.setText("  Stopping...");
            statusLabel.setForegroundColor(TextColor.ANSI.YELLOW);
        }
    }

    private void addLogEvent(String message) {
        if (getTextGUI() != null) {
            getTextGUI().getGUIThread().invokeLater(() -> {
                Instant start = scenario.getScenarioStart();
                long sec = 0;
                if (start != null) {
                    sec = Duration.between(start, Instant.now()).getSeconds();
                }
                String timestamp = String.format("[%02d:%02d]", sec / 60, sec % 60);
                String logLine = timestamp + " " + message;

                String existing = eventLog.getText();
                String[] lines = existing.split("\n", -1);
                StringBuilder sb = new StringBuilder();
                sb.append(logLine);
                int count = 1;
                for (String line : lines) {
                    if (count >= MAX_LOG_LINES) break;
                    if (!line.isEmpty()) {
                        sb.append("\n").append(line);
                        count++;
                    }
                }
                eventLog.setText(sb.toString());
            });
        }
    }

    private void onStop() {
        if (!scenario.isStopRequested()) {
            scenario.requestStop();
            statusLabel.setText("  Stopping...");
            statusLabel.setForegroundColor(TextColor.ANSI.YELLOW);
        }
    }

    private void onForceQuit() {
        scenario.requestStop();
        finished = true;
        finalStatus = "Force quit by user";
        close();
    }

    public boolean isFinished() {
        return finished;
    }

    static String buildProgressBar(double fraction) {
        int width = 25;
        double clamped = Math.max(0, Math.min(1.0, fraction));
        int filled = (int) (clamped * width);
        int empty = width - filled;
        StringBuilder sb = new StringBuilder("[");
        sb.append("#".repeat(filled));
        sb.append("-".repeat(empty));
        sb.append("] ");
        sb.append(String.format("%3d%%", (int) (fraction * 100)));
        return sb.toString();
    }

    static String formatDuration(long seconds) {
        if (seconds >= 3600) {
            return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
