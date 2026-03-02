package com.loadtester.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuration form window for the SIP load tester TUI.
 * <p>
 * Displays fields for SIP credentials, network settings, and test parameters.
 * Fields are pre-filled from a .env map if provided.
 */
public class ConfigWindow extends BasicWindow {

    private static final Pattern POSITIVE_INT = Pattern.compile("^[1-9]\\d*$");
    private static final Pattern NON_NEGATIVE_INT = Pattern.compile("^\\d+$");

    // SIP Proxy fields
    private final TextBox proxyHostField;
    private final TextBox proxyPortField;
    private final TextBox domainField;

    // Phone A fields
    private final TextBox aUserField;
    private final TextBox aPasswordField;
    private final TextBox aAuthUserField;

    // Phone B fields
    private final TextBox bUserField;
    private final TextBox bPasswordField;
    private final TextBox bAuthUserField;

    // Network
    private final TextBox localIpField;

    // Test Parameters
    private final TextBox concurrentField;
    private final TextBox callDurationField;
    private final TextBox totalDurationField;
    private final TextBox staggerDelayField;
    private final TextBox timeoutField;

    private volatile ConfigResult result;

    public ConfigWindow(Map<String, String> envDefaults) {
        super("SIP Load Tester - Configuration");
        setHints(List.of(Hint.CENTERED));

        Panel mainPanel = new Panel(new LinearLayout(Direction.VERTICAL));
        mainPanel.setPreferredSize(new TerminalSize(60, 35));

        // --- SIP Proxy ---
        mainPanel.addComponent(new Label("--- SIP Proxy ---").setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Center)));
        mainPanel.addComponent(new EmptySpace());

        proxyHostField = addField(mainPanel, "Proxy Host:", envDefaults.getOrDefault("SIP_PROXY_HOST", ""), 35);
        proxyPortField = addField(mainPanel, "Proxy Port:", envDefaults.getOrDefault("SIP_PROXY_PORT", "5060"), 10);
        domainField = addField(mainPanel, "Domain:", envDefaults.getOrDefault("SIP_DOMAIN", ""), 35);

        mainPanel.addComponent(new EmptySpace());

        // --- Phone A (Caller) ---
        mainPanel.addComponent(new Label("--- Phone A (Caller) ---").setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Center)));
        mainPanel.addComponent(new EmptySpace());

        aUserField = addField(mainPanel, "Username:", envDefaults.getOrDefault("SIP_A_USER", ""), 25);
        aPasswordField = addMaskedField(mainPanel, "Password:", envDefaults.getOrDefault("SIP_A_PASSWORD", ""), 25);
        aAuthUserField = addField(mainPanel, "Auth User:", envDefaults.getOrDefault("SIP_A_AUTH_USER", ""), 25);

        mainPanel.addComponent(new EmptySpace());

        // --- Phone B (Callee) ---
        mainPanel.addComponent(new Label("--- Phone B (Callee) ---").setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Center)));
        mainPanel.addComponent(new EmptySpace());

        bUserField = addField(mainPanel, "Username:", envDefaults.getOrDefault("SIP_B_USER", ""), 25);
        bPasswordField = addMaskedField(mainPanel, "Password:", envDefaults.getOrDefault("SIP_B_PASSWORD", ""), 25);
        bAuthUserField = addField(mainPanel, "Auth User:", envDefaults.getOrDefault("SIP_B_AUTH_USER", ""), 25);

        mainPanel.addComponent(new EmptySpace());

        // --- Network ---
        mainPanel.addComponent(new Label("--- Network ---").setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Center)));
        mainPanel.addComponent(new EmptySpace());

        localIpField = addField(mainPanel, "Local IP:", envDefaults.getOrDefault("LOCAL_IP", detectLocalIp()), 20);

        mainPanel.addComponent(new EmptySpace());

        // --- Test Parameters ---
        mainPanel.addComponent(new Label("--- Test Parameters ---").setLayoutData(
                LinearLayout.createLayoutData(LinearLayout.Alignment.Center)));
        mainPanel.addComponent(new EmptySpace());

        concurrentField = addField(mainPanel, "Concurrent Calls:", "30", 10);
        callDurationField = addField(mainPanel, "Call Duration (s):", "10", 10);
        totalDurationField = addField(mainPanel, "Total Duration (s):", "60", 10);
        staggerDelayField = addField(mainPanel, "Stagger Delay (ms):", "500", 10);
        timeoutField = addField(mainPanel, "SIP Timeout (s):", "30", 10);

        mainPanel.addComponent(new EmptySpace());
        mainPanel.addComponent(new EmptySpace());

        // --- Buttons ---
        Panel buttonPanel = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttonPanel.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.Center));

        Button startButton = new Button("Start Test", this::onStart);
        Button quitButton = new Button("Quit", this::onQuit);

        buttonPanel.addComponent(startButton);
        buttonPanel.addComponent(new Label("   "));
        buttonPanel.addComponent(quitButton);

        mainPanel.addComponent(buttonPanel);

        setComponent(mainPanel);
    }

    private TextBox addField(Panel panel, String label, String defaultValue, int width) {
        Panel row = new Panel(new LinearLayout(Direction.HORIZONTAL));
        Label lbl = new Label(String.format("%-20s", label));
        TextBox field = new TextBox(new TerminalSize(width, 1), defaultValue);
        row.addComponent(lbl);
        row.addComponent(field);
        panel.addComponent(row);
        return field;
    }

    private TextBox addMaskedField(Panel panel, String label, String defaultValue, int width) {
        Panel row = new Panel(new LinearLayout(Direction.HORIZONTAL));
        Label lbl = new Label(String.format("%-20s", label));
        TextBox field = new TextBox(new TerminalSize(width, 1), defaultValue)
                .setMask('*');
        row.addComponent(lbl);
        row.addComponent(field);
        panel.addComponent(row);
        return field;
    }

    private void onStart() {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            String msg = String.join("\n", errors);
            MessageDialog.showMessageDialog(getTextGUI(), "Validation Error", msg, MessageDialogButton.OK);
            return;
        }

        result = new ConfigResult(
                proxyHostField.getText().trim(),
                Integer.parseInt(proxyPortField.getText().trim()),
                domainField.getText().trim(),
                aUserField.getText().trim(),
                aPasswordField.getText().trim(),
                aAuthUserField.getText().trim(),
                bUserField.getText().trim(),
                bPasswordField.getText().trim(),
                bAuthUserField.getText().trim(),
                localIpField.getText().trim(),
                Integer.parseInt(concurrentField.getText().trim()),
                Integer.parseInt(callDurationField.getText().trim()),
                Integer.parseInt(totalDurationField.getText().trim()),
                Integer.parseInt(staggerDelayField.getText().trim()),
                Integer.parseInt(timeoutField.getText().trim()),
                false // not quit
        );
        close();
    }

    private void onQuit() {
        result = ConfigResult.quit();
        close();
    }

    List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (proxyHostField.getText().isBlank()) errors.add("Proxy Host is required");
        if (!NON_NEGATIVE_INT.matcher(proxyPortField.getText().trim()).matches())
            errors.add("Proxy Port must be a valid number");
        if (domainField.getText().isBlank()) errors.add("Domain is required");
        if (aUserField.getText().isBlank()) errors.add("Phone A Username is required");
        if (aPasswordField.getText().isBlank()) errors.add("Phone A Password is required");
        if (bUserField.getText().isBlank()) errors.add("Phone B Username is required");
        if (bPasswordField.getText().isBlank()) errors.add("Phone B Password is required");
        if (localIpField.getText().isBlank()) errors.add("Local IP is required");

        if (!POSITIVE_INT.matcher(concurrentField.getText().trim()).matches())
            errors.add("Concurrent Calls must be a positive integer");
        if (!POSITIVE_INT.matcher(callDurationField.getText().trim()).matches())
            errors.add("Call Duration must be a positive integer");
        if (!POSITIVE_INT.matcher(totalDurationField.getText().trim()).matches())
            errors.add("Total Duration must be a positive integer");
        if (!NON_NEGATIVE_INT.matcher(staggerDelayField.getText().trim()).matches())
            errors.add("Stagger Delay must be a non-negative integer");
        if (!POSITIVE_INT.matcher(timeoutField.getText().trim()).matches())
            errors.add("SIP Timeout must be a positive integer");

        return errors;
    }

    /**
     * Returns the configuration result after the window closes.
     * Null if still open; check {@link ConfigResult#quit()} for quit action.
     */
    public ConfigResult getResult() {
        return result;
    }

    private static String detectLocalIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * Immutable configuration result from the config form.
     */
    public record ConfigResult(
            String proxyHost,
            int proxyPort,
            String domain,
            String aUser,
            String aPassword,
            String aAuthUser,
            String bUser,
            String bPassword,
            String bAuthUser,
            String localIp,
            int concurrentCalls,
            int callDurationSec,
            int totalDurationSec,
            int staggerDelayMs,
            int timeoutSeconds,
            boolean isQuit
    ) {
        static ConfigResult quit() {
            return new ConfigResult("", 0, "", "", "", "", "", "", "", "", 0, 0, 0, 0, 0, true);
        }
    }
}
