package com.deepseekharness.app.util;

/**
 * 把维护事务的稳定错误码转换成可执行的提示，同时保留错误码供诊断页检索。
 * 未登记的正文原样返回，避免把第三方插件或用户输出误翻译。
 */
public final class MaintenanceErrorText {
    private MaintenanceErrorText() { }

    public static String render(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        if (value.trim().equalsIgnoreCase("Permission denied")) return UiText.choose(
                "当前步骤被系统拒绝访问，尚不能判断是文件权限还是进程核验失败。请保持 DSHA 在前台，先点“恢复中断维护”重试；应用会保留原件，不会把失败显示成已恢复。\nPermission denied",
                "The system denied access during this step. Keep DSHA in the foreground and retry Recover interrupted maintenance; originals stay protected and a failed recovery is never shown as complete.\nPermission denied");
        String code = value.matches("[A-Z_0-9]{2,100}") ? value : "";
        if (code.isEmpty()) return UiStateText.render(value);
        String message = switch (code) {
            case "RUNTIME_RETENTION_LIMIT", "RECOVERY_RETENTION_LIMIT", "RETAINED_OPERATION_LIMIT", "OPERATION_LIMIT" ->
                    UiText.choose("维护历史记录已达到安全上限；原件和失败候选均未删除。请打开“保留数据”检查后再重试。",
                            "The safe maintenance-history limit was reached. Originals and failed candidates were retained. Review Retained data, then retry.");
            case "RUNTIME_RECOVERY_REQUIRED", "ENVIRONMENT_RECOVERY_REQUIRED", "RECOVERY_PENDING", "RECOVERY_MULTIPLE_TRANSACTIONS", "MULTIPLE_RUNTIME_TRANSACTIONS", "MULTIPLE_ENVIRONMENT_TRANSACTIONS" ->
                    UiText.choose("检测到未完成的维护事务；请先选择“恢复中断维护”，确认原环境回切后再继续。",
                            "An interrupted maintenance transaction was found. Recover interrupted maintenance and confirm the original environment before continuing.");
            case "STOP_DSH_AND_TERMINALS_FIRST" ->
                    UiText.choose("请先停止 DSH、终端和正在运行的数据作业；原件未改动，稍后可重试。",
                            "Stop DSH, terminals, and active data jobs first. Originals were not changed; retry shortly.");
            case "WEB_PROCESS_SIGNAL_DENIED" ->
                    UiText.choose("系统拒绝了旧 Web 进程信号。应用会保留原环境；请保持 DSHA 前台并再次点“恢复中断维护”，系统将只对本次已登记的 Web 启动器执行优雅退出。",
                            "The system denied the signal to the old Web process. The original environment is retained. Keep DSHA in the foreground and retry Recover interrupted maintenance; only this run's registered Web launcher will be asked to exit gracefully.");
            case "RUNTIME_HEALTH_INCOMPLETE", "TRIAL_PROCESS_UNCONFIRMED", "TRIAL_RENDERER_FAILED", "TRIAL_PLUGIN_FAILED" ->
                    UiText.choose("新运行时试运行未通过；原运行环境和个人数据已保留。请查看日志后重试或回退。",
                            "The new runtime did not pass its trial. The original runtime and personal data were retained. Review the log, then retry or roll back.");
            case "NO_SPACE" ->
                    UiText.choose("可用空间不足；原件未删除。请先清理可再生缓存或导出副本后重试。",
                            "There is not enough free space. Originals were retained. Clean reproducible caches or export a copy, then retry.");
            case "PERMISSION_DENIED" ->
                    UiText.choose("文件访问权限不足；原件未改动，请重新授权后重试。",
                            "File access was denied. Originals were not changed; restore permission and retry.");
            case "CANCELLED" ->
                    UiText.choose("操作已取消；如果已进入提交阶段，系统会先恢复一致状态。",
                            "The operation was cancelled. If commit had started, the system will converge to a consistent state first.");
            default -> "";
        };
        if (message.isEmpty()) return UiStateText.render(value);
        return message + "\n" + UiText.choose("错误代码：", "Error code: ") + code;
    }
}
