package com.loadtester.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple .env file parser.
 * <p>
 * Reads key=value pairs from a file, ignoring blank lines and lines starting with {@code #}.
 * Values may optionally be quoted with single or double quotes (quotes are stripped).
 * Inline comments after the value are NOT supported (to allow {@code #} in passwords).
 */
public class DotEnvLoader {

    private DotEnvLoader() { /* utility class */ }

    /**
     * Load environment variables from the given .env file path.
     *
     * @param path path to the .env file
     * @return unmodifiable map of key-value pairs (insertion order preserved)
     * @throws IOException if the file cannot be read
     */
    public static Map<String, String> load(Path path) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();

                // Skip blank lines and comments
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int eqIndex = trimmed.indexOf('=');
                if (eqIndex < 0) {
                    // Line without '=' — skip silently (lenient parsing)
                    continue;
                }

                String key = trimmed.substring(0, eqIndex).trim();
                String value = trimmed.substring(eqIndex + 1).trim();

                if (key.isEmpty()) {
                    continue;
                }

                // Strip surrounding quotes (single or double)
                value = stripQuotes(value);

                env.put(key, value);
            }
        }
        return Collections.unmodifiableMap(env);
    }

    /**
     * Try to load from the given path; return an empty map on any error (file missing, unreadable, etc.).
     */
    public static Map<String, String> loadOrEmpty(Path path) {
        try {
            if (Files.exists(path)) {
                return load(path);
            }
        } catch (IOException e) {
            // fall through
        }
        return Collections.emptyMap();
    }

    static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
