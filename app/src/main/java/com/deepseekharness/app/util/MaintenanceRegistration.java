package com.deepseekharness.app.util;

/**
 * 维护事务完成后的内置插件注册：运行时已经提交并通过健康回执，注册成功与否不再决定运行时去留。
 *
 * <p>build352 的维护期注册放在事务校验内部，此时受管文件已切换到新 runtimeId、但
 * {@code runtime-health/<id>.json} 尚未写入，{@code environmentReady()} 必然为 false，导致每次
 * 跨 runtime 的覆盖升级都以 {@code ENV_NOT_READY} 回滚。现在注册移到事务成功之后执行，失败只
 * 记录事件并交给启动路径按分级预算重试，绝不把已健康的运行时重新回滚。
 */
public final class MaintenanceRegistration {
  public static final String OK = "MAINTENANCE_REGISTER_OK";
  public static final String DEFERRED = "MAINTENANCE_REGISTER_DEFERRED";

  private MaintenanceRegistration() {}

  /** 注册动作；允许抛出受检异常，由 {@link #attempt(Attempt)} 归并为结果文本。 */
  public interface Attempt {
    void run() throws Exception;
  }

  /** 执行一次维护期注册；成功返回 OK，任何失败都降级为带原因的结果文本，不向外抛出。 */
  public static String attempt(Attempt attempt) {
    try {
      attempt.run();
      return OK;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return DEFERRED + ":INTERRUPTED";
    } catch (Exception error) {
      return DEFERRED + ":" + reason(error);
    }
  }

  /** 只保留有界、无换行的原因，供诊断事件记录。 */
  static String reason(Throwable error) {
    if (error == null) return "UNKNOWN";
    String message = error.getMessage();
    if (message == null || message.isBlank()) return error.getClass().getSimpleName();
    String normalized = message.replaceAll("\\s+", " ").trim();
    if (normalized.length() > 120) normalized = normalized.substring(0, 120) + "…";
    return normalized;
  }
}
