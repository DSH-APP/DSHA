package com.deepseekharness.app;

/**
 * Parses the one process-local browser launch URL printed by dsh web.
 *
 * <p>The URL contains a credential. It is deliberately reconstructed with the
 * known loopback origin after validation, rather than returning arbitrary text
 * from a mutable log file.
 */
final class WebLaunchUrl {

    private static final java.util.regex.Pattern LINE = java.util.regex.Pattern.compile(
            "(?m)^\\s*dsh web:\\s*http://(?:127\\.0\\.0\\.1|localhost):(\\d{1,5})/\\?token=([A-Za-z0-9_-]{16,512})(?:\\s+\\(LAN:\\s+https?://[^\\r\\n]*\\))?\\s*$");
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile(
            "[A-Za-z0-9_-]{16,512}");

    private WebLaunchUrl() {
    }

    /**
     * Returns a safe loopback launch URL for {@code expectedPort}, or an empty
     * string when the log does not contain an exact current dsh launch line.
     * When a log has multiple lines, use the last matching one: a restarted
     * dsh process appends a fresh token after the prior process's line.
     */
    static String fromDshWebLog(String log, int expectedPort) {
        if (log == null || expectedPort < 1 || expectedPort > 65535) return "";
        java.util.regex.Matcher m = LINE.matcher(log);
        String token = "";
        while (m.find()) {
            int port;
            try {
                port = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (port == expectedPort) token = m.group(2);
        }
        return token.isEmpty() ? "" : loopbackUrl(expectedPort, token);
    }

    /** Extracts a validated token from a URL that this class itself created. */
    static String tokenFromUrl(String url, int expectedPort) {
        if (url == null || expectedPort < 1 || expectedPort > 65535) return "";
        String prefix = "http://127.0.0.1:" + expectedPort + "/?token=";
        if (!url.startsWith(prefix)) return "";
        String token = url.substring(prefix.length());
        return TOKEN.matcher(token).matches() ? token : "";
    }

    static boolean isValidToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    private static String loopbackUrl(int port, String token) {
        return "http://127.0.0.1:" + port + "/?token=" + token;
    }
}
