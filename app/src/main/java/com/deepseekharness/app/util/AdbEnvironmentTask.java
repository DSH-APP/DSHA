package com.deepseekharness.app.util;

import java.util.function.BooleanSupplier;

/** ADB 的完整同步任务范围：与安装/维护共用凭据，嵌套调用只复用当前 owner。 */
public final class AdbEnvironmentTask {
    private AdbEnvironmentTask() { }
    public static final String BLOCKED = "环境任务进行中，稍后重试";

    public static final class Busy extends Exception {
        public Busy(String detail) { super(detail); }
    }

    public static <T> T run(String kind, BooleanSupplier pendingMaintenance,
                            EnvironmentTaskGate.Operation<T> operation) throws Exception {
        if (EnvironmentTaskGate.ownsCurrentThread()) return owned(pendingMaintenance, operation);
        EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire(kind);
        if (lease == null) throw new Busy(BLOCKED);
        try (lease) {
            return lease.run(() -> owned(pendingMaintenance, operation));
        }
    }

    private static <T> T owned(BooleanSupplier pendingMaintenance,
                                EnvironmentTaskGate.Operation<T> operation) throws Exception {
        // 必须在持有 Lease 后检查磁盘事务；owner 身份也不能绕过中断维护。
        if (pendingMaintenance.getAsBoolean())
            throw new Busy(BLOCKED + com.deepseekharness.app.util.UiText.text("；请先恢复中断的环境维护"));
        return operation.run();
    }
}
