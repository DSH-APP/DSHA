package com.deepseekharness.app.util;

/** 只认执行器追加在输出末尾的状态；缺失结果时禁止重放。 */
public final class DeviceCommandResult {
    private DeviceCommandResult() { }
    public static int exitCode(String output) {
        if (output == null) return 125;
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile("\\[EXIT=([0-9]{1,3}|timeout|interrupted)\\]\\s*$").matcher(output);
        if (!marker.find()) return 125;
        String value = marker.group(1);
        if (value.equals("timeout")) return 124;
        if (value.equals("interrupted")) return 125;
        int code = Integer.parseInt(value);
        return code <= 255 ? code : 125;
    }
}
