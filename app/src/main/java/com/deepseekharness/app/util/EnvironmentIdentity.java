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
    /**
     * 前台服务和看门狗的运行时门禁。包内描述无法认证时必须阻止启动；同一候选已经
     * 尝试失败时，则允许后续门禁决定是否继续使用经过健康确认的兼容前代。
     */
    public static boolean shouldBlockRuntimeStart(boolean latest, boolean candidateReadable,
                                                  boolean busy, boolean pending,
                                                  String candidate, String attempted) {
        if (latest) return false;
        if (!candidateReadable) return true;
        return shouldAutoStart(false, busy, pending, candidate, attempted);
    }
    /** 成功文案不是就绪证明；维护之后仍未就绪时留在原生页，不能再次进入跳转循环。 */
    public static boolean mayAdvanceAfterMaintenance(boolean automatic,boolean mine,boolean succeeded,boolean busy,boolean pending,boolean ready){
        return automatic&&mine&&succeeded&&!busy&&!pending&&ready;
    }
}
