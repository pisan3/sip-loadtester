package com.loadtester;

import com.loadtester.config.SipAccountConfig;
import com.loadtester.report.TestReport;
import com.loadtester.scenario.CallScenario;
import com.loadtester.scenario.ConcurrentCallScenario;
import com.loadtester.sip.DefaultSipStackFactory;

import java.net.InetAddress;

/**
 * CLI entry point for the SIP load tester.
 * <p>
 * Usage:
 *   java -jar sip-loadtester.jar \
 *     --proxy-host 10.0.0.1 --proxy-port 5060 --domain example.com \
 *     --a-user alice --a-password secret1 \
 *     --b-user bob   --b-password secret2 \
 *     [--local-ip 10.0.0.50] [--media-duration 3000] [--timeout 10]
 */
public class Main {

    public static void main(String[] args) {
        try {
            Config cfg = parseArgs(args);
            if (cfg == null) {
                printUsage();
                System.exit(1);
            }

            System.out.println("SIP Load Tester — A-calls-B Scenario");
            System.out.println("Proxy: " + cfg.proxyHost + ":" + cfg.proxyPort);
            System.out.println("Domain: " + cfg.domain);
            System.out.println("Phone A: " + cfg.aUser);
            System.out.println("Phone B: " + cfg.bUser);
            System.out.println("Local IP: " + cfg.localIp);
            if (cfg.concurrent > 1) {
                System.out.println("Concurrent calls: " + cfg.concurrent);
            }
            System.out.println();

            SipAccountConfig phoneAConfig = new SipAccountConfig(
                    cfg.aUser, cfg.aPassword, cfg.domain, cfg.proxyHost, cfg.proxyPort,
                    cfg.aUser, cfg.aAuthUser);
            SipAccountConfig phoneBConfig = new SipAccountConfig(
                    cfg.bUser, cfg.bPassword, cfg.domain, cfg.proxyHost, cfg.proxyPort,
                    cfg.bUser, cfg.bAuthUser);

            TestReport report;
            if (cfg.concurrent > 1) {
                ConcurrentCallScenario scenario = new ConcurrentCallScenario(
                        phoneAConfig, phoneBConfig,
                        new DefaultSipStackFactory(), cfg.localIp, cfg.concurrent);
                scenario.setMediaDurationMs(cfg.mediaDuration);
                scenario.setTimeoutSeconds(cfg.timeout);
                report = scenario.execute();
            } else {
                CallScenario scenario = new CallScenario(
                        phoneAConfig, phoneBConfig,
                        new DefaultSipStackFactory(), cfg.localIp);
                scenario.setMediaDurationMs(cfg.mediaDuration);
                scenario.setTimeoutSeconds(cfg.timeout);
                report = scenario.execute();
            }
            System.out.println(report.getSummary());

            System.exit(report.allPassed() ? 0 : 1);

        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static Config parseArgs(String[] args) {
        Config cfg = new Config();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--proxy-host" -> cfg.proxyHost = args[++i];
                case "--proxy-port" -> cfg.proxyPort = Integer.parseInt(args[++i]);
                case "--domain" -> cfg.domain = args[++i];
                case "--a-user" -> cfg.aUser = args[++i];
                case "--a-password" -> cfg.aPassword = args[++i];
                case "--a-auth-user" -> cfg.aAuthUser = args[++i];
                case "--b-user" -> cfg.bUser = args[++i];
                case "--b-password" -> cfg.bPassword = args[++i];
                case "--b-auth-user" -> cfg.bAuthUser = args[++i];
                case "--local-ip" -> cfg.localIp = args[++i];
                case "--media-duration" -> cfg.mediaDuration = Integer.parseInt(args[++i]);
                case "--timeout" -> cfg.timeout = Integer.parseInt(args[++i]);
                case "--concurrent" -> cfg.concurrent = Integer.parseInt(args[++i]);
                case "--help", "-h" -> { printUsage(); System.exit(0); }
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    return null;
                }
            }
        }

        // Auto-detect local IP if not specified
        if (cfg.localIp == null) {
            try {
                cfg.localIp = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                cfg.localIp = "127.0.0.1";
            }
        }

        // Validate required fields
        if (cfg.proxyHost == null || cfg.domain == null ||
                cfg.aUser == null || cfg.aPassword == null ||
                cfg.bUser == null || cfg.bPassword == null) {
            return null;
        }

        return cfg;
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar sip-loadtester.jar [options]");
        System.out.println();
        System.out.println("Required:");
        System.out.println("  --proxy-host <host>     PBX/proxy IP address or hostname");
        System.out.println("  --proxy-port <port>     PBX/proxy SIP port (default: 5060)");
        System.out.println("  --domain <domain>       SIP domain");
        System.out.println("  --a-user <username>     Phone A SIP username");
        System.out.println("  --a-password <password> Phone A SIP password");
        System.out.println("  --b-user <username>     Phone B SIP username");
        System.out.println("  --b-password <password> Phone B SIP password");
        System.out.println();
        System.out.println("Optional:");
        System.out.println("  --local-ip <ip>         Local IP address (auto-detected if omitted)");
        System.out.println("  --media-duration <ms>   RTP tone duration in ms (default: 3000)");
        System.out.println("  --timeout <seconds>     SIP timeout in seconds (default: 10)");
        System.out.println("  --concurrent <N>        Number of concurrent calls (default: 1)");
        System.out.println("  -h, --help              Show this help");
    }

    private static class Config {
        String proxyHost;
        int proxyPort = 5060;
        String domain;
        String aUser;
        String aPassword;
        String aAuthUser;
        String bUser;
        String bPassword;
        String bAuthUser;
        String localIp;
        int mediaDuration = 3000;
        int timeout = 10;
        int concurrent = 1;
    }
}
