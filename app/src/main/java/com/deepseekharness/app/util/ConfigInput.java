package com.deepseekharness.app.util;

/** 配置输入先整体校验，再写入，避免一半保存或静默改成另一个值。 */
public final class ConfigInput {
    private ConfigInput() { }

    public static int port(String value) {
        int port = number(value, "端口请输入 1—65535 的整数");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口请输入 1—65535 的整数");
        if (port == Constants.LAN_BRIDGE_PORT || port == Constants.SHELL_BRIDGE_PORT)
            throw new IllegalArgumentException("3081 和 3090 已用于 App 桥接，请换一个端口");
        return port;
    }

    public static int backupInterval(String value) {
        int count = number(value, "备份间隔请输入 0—2147483647 的整数，0 为关闭");
        if (count < 0) throw new IllegalArgumentException("备份间隔不能为负数，0 为关闭");
        return count;
    }

    private static int number(String value, String error) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(error); }
    }
}
