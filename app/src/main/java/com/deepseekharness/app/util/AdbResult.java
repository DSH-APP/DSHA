package com.deepseekharness.app.util;

/** ADB 文本协议及输入校验；不依赖 Android，可单独验证成功/警告边界。 */
public final class AdbResult {
    private AdbResult() { }
    public enum PairState { FAILED, PAIRED, CONNECTED }

    public static boolean marker(String output, String marker) {
        if (output == null) return false;
        for (String line : output.split("\\r?\\n")) {
            if (line.equals(marker) || line.startsWith(marker + ":") || line.startsWith(marker + " ")) return true;
        }
        return false;
    }

    public static PairState pairState(String output) {
        if (marker(output, "CONNECT_OK") && !marker(output, "CONNECT_WARN")) return PairState.CONNECTED;
        return marker(output, "PAIR_OK") ? PairState.PAIRED : PairState.FAILED;
    }

    public static boolean shellReady(String output) {
        return output != null && output.trim().endsWith("[EXIT=0]")
                && java.util.regex.Pattern.compile("(?m)^uid=\\d+.*$").matcher(output).find();
    }

    /** 空值表示自动发现；非空输入不能悄悄落回默认端口。 */
    public static int port(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return 0;
        if (!s.matches("[0-9]{1,5}")) throw new IllegalArgumentException("端口必须是 1—65535 的整数");
        int value = Integer.parseInt(s);
        if (value < 1 || value > 65535) throw new IllegalArgumentException("端口必须在 1—65535 之间");
        return value;
    }

    public static boolean code(String code) {
        return code != null && code.matches("[0-9]{6}");
    }
}
