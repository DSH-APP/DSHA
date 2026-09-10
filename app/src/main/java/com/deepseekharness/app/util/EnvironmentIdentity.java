package com.deepseekharness.app.util;

/** 覆盖升级门禁：同包版本、环境版本与 dsh 版本必须同时匹配。 */
public final class EnvironmentIdentity {
    private EnvironmentIdentity() { }
    public static String expected(String environment, int appCode, String dshVersion) {
        if (environment == null || !environment.matches("[0-9]{1,9}") || appCode < 1
                || dshVersion == null || !dshVersion.matches("[A-Za-z0-9._+-]{1,80}")) return "";
        return appCode + ":" + environment + ":" + dshVersion;
    }
    public static boolean matches(String expected, String installed) {
        return expected != null && !expected.isEmpty() && expected.equals(installed);
    }
    /** 覆盖安装修复包后允许重试一次旧失败；不改变环境身份、不触发已就绪环境重建。 */
    public static String attemptKey(String identity, long apkUpdatedAt) {
        if (identity == null || identity.isEmpty()) return "";
        return apkUpdatedAt > 0 ? identity + "@" + apkUpdatedAt : identity;
    }
    /** 同一安装身份只自动开始一次；失败和进程中断由用户重试，旋转不重复启动。 */
    public static boolean shouldAutoStart(boolean ready, boolean busy, boolean pending, String identity, String attempted) {
        return !ready && !busy && !pending && identity != null && !identity.isEmpty() && !identity.equals(attempted);
    }
}
