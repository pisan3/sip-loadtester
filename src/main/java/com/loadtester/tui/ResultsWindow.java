package com.loadtester.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.loadtester.report.TestReport;

import java.util.List;

/**
 * Results window showing the final test report after scenario completion.
 * <p>
 * Displays all check results, latency stats, and overall pass/fail verdict.
 * Offers buttons to run a new test or quit.
 */
public class ResultsWindow extends BasicWindow {

    public enum Action { NEW_TEST, QUIT }

    private volatile Action action = Action.QUIT;

    public ResultsWindow(TestReport report,
                         long completedCalls, long failedCalls, double callsPerSec,
                         long avgLatencyMs, long p95LatencyMs, long p99LatencyMs,
                         int tonesPassed, int tonesTotal,
                         long durationSec) {
        super("SIP Load Tester - Results");
        setHints(List.of(Hint.CENTERED));

        Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));
        mainPanel.setPreferredSize(new TerminalSize(64, 28));

        boolean allPassed = report.allPassed();

        // --- Verdict Header ---
        mainPanel.addComponent(new EmptySpace());
        Label verdictLabel = new Label(allPassed
                ? "  *** TEST PASSED ***" : "  *** TEST FAILED ***");
        verdictLabel.addStyle(SGR.BOLD);
        verdictLabel.setForegroundColor(allPassed ? TextColor.ANSI.GREEN : TextColor.ANSI.RED);
        verdictLabel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Center));
        mainPanel.addComponent(verdictLabel);
        mainPanel.addComponent(new EmptySpace());

        // --- Summary Stats ---
        mainPanel.addComponent(new Label("  " + "=".repeat(56)).addStyle(SGR.BOLD));
        mainPanel.addComponent(new EmptySpace());

        Panel statsGrid = new Panel(new GridLayout(2));

        addStatRow(statsGrid, "Duration:", formatDuration(durationSec));
        addStatRow(statsGrid, "Calls Completed:", String.valueOf(completedCalls));
        addStatRow(statsGrid, "Calls Failed:", String.valueOf(failedCalls));
        addStatRow(statsGrid, "Calls/sec (avg):", String.format("%.2f", callsPerSec));

        long total = completedCalls + failedCalls;
        double passRate = total > 0 ? (double) completedCalls / total * 100 : 0;
        addStatRow(statsGrid, "Pass Rate:", String.format("%.1f%%", passRate));

        addStatRow(statsGrid, "", ""); // spacer

        addStatRow(statsGrid, "Setup Latency (avg):", avgLatencyMs + "ms");
        addStatRow(statsGrid, "Setup Latency (P95):", p95LatencyMs + "ms");
        addStatRow(statsGrid, "Setup Latency (P99):", p99LatencyMs + "ms");

        if (tonesTotal > 0) {
            addStatRow(statsGrid, "", "");
            addStatRow(statsGrid, "Tone Detection:", tonesPassed + "/" + tonesTotal + " passed");
        }

        mainPanel.addComponent(statsGrid);
        mainPanel.addComponent(new EmptySpace());

        // --- Check Results ---
        mainPanel.addComponent(new Label("  " + "=".repeat(56)).addStyle(SGR.BOLD));
        mainPanel.addComponent(new Label("  Checks:"));

        TextBox checksBox = new TextBox(new TerminalSize(60, 6));
        checksBox.setReadOnly(true);
        StringBuilder checksText = new StringBuilder();
        for (TestReport.CheckResult check : report.getChecks()) {
            String icon = check.passed() ? "[PASS]" : "[FAIL]";
            checksText.append(String.format("  %s %s", icon, check.name()));
            if (check.detail() != null && !check.detail().isEmpty()) {
                checksText.append(" - ").append(check.detail());
            }
            checksText.append("\n");
        }
        checksBox.setText(checksText.toString().stripTrailing());
        mainPanel.addComponent(checksBox);

        mainPanel.addComponent(new EmptySpace());
        mainPanel.addComponent(new Label("  " + "=".repeat(56)).addStyle(SGR.BOLD));
        mainPanel.addComponent(new EmptySpace());

        // --- Buttons ---
        Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttonPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Center));

        Button newTestButton = new Button("New Test", () -> {
            action = Action.NEW_TEST;
            close();
        });
        Button quitButton = new Button("Quit", () -> {
            action = Action.QUIT;
            close();
        });

        buttonPanel.addComponent(newTestButton);
        buttonPanel.addComponent(new Label("   "));
        buttonPanel.addComponent(quitButton);
        mainPanel.addComponent(buttonPanel);

        setComponent(mainPanel);
    }

    public Action getAction() {
        return action;
    }

    private void addStatRow(Panel grid, String label, String value) {
        grid.addComponent(new Label("    " + label));
        grid.addComponent(new Label(value));
    }

    static String formatDuration(long seconds) {
        if (seconds >= 3600) {
            return String.format("%dh %02dm %02ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format("%dm %02ds", seconds / 60, seconds % 60);
    }
}
