package com.deepseekharness.app.util;

/** 注入失败只在实际 Python 导入已通过时继续；缓存存在本身不是依赖就绪证据。 */
public final class AdbDependencyPreparation {
  private AdbDependencyPreparation() {}

  public interface Steps {
    boolean importsReady();

    String fillCache();

    String installCache();
  }

  public static final class Result {
    public final boolean continueSetup;
    public final String output;

    private Result(boolean continueSetup, String output) {
      this.continueSetup = continueSetup;
      this.output = output;
    }
  }

  public static Result prepare(Steps steps) {
    if (Thread.currentThread().isInterrupted()) return cancelled("");
    boolean ready = steps.importsReady();
    if (Thread.currentThread().isInterrupted()) return cancelled("");
    if (ready) return new Result(true, "");
    String filled = steps.fillCache() + "\n";
    if (Thread.currentThread().isInterrupted()) return cancelled(filled);
    if (!AdbResult.marker(filled, "WHEELS_CACHE_READY")) return afterFailure(steps, filled);
    String installed = filled + steps.installCache() + "\n";
    if (Thread.currentThread().isInterrupted()) return cancelled(installed);
    if (!AdbResult.marker(installed, "WHEELS_JAVA_EXTRACTED"))
      return afterFailure(steps, installed);
    return new Result(true, installed);
  }

  private static Result afterFailure(Steps steps, String output) {
    boolean ready = steps.importsReady();
    if (Thread.currentThread().isInterrupted()) return cancelled(output);
    StringBuilder result = new StringBuilder(output);
    if (ready)
      result
          .append(UiText.text("WHEELS_OPTIONAL_SKIP: Python 依赖导入已通过，跳过失败的缓存注入或安装并继续准备 ADB"))
          .append('\n');
    return new Result(ready, result.toString());
  }

  private static Result cancelled(String output) {
    StringBuilder result =
        new StringBuilder(output).append(UiText.text("ADB_CANCELLED: 环境准备已取消")).append('\n');
    return new Result(false, result.toString());
  }
}
