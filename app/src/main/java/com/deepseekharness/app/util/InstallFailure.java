package com.deepseekharness.app.util;

/** Stable error code and original redacted reason, independent of exception.toString/localized class names. */
public record InstallFailure(InstallStage stage, String code, String reason) {
  public static InstallFailure from(InstallStage stage, Throwable error) {
    String kind =
        error instanceof java.io.IOException
            ? "IO"
            : error instanceof IllegalArgumentException ? "INPUT" : "RUNTIME";
    String reason = SensitiveData.redact(String.valueOf(error.getMessage()));
    if (reason.equals("null")) reason = error.getClass().getSimpleName();
    if (reason.length() > 1200) reason = reason.substring(0, 1200);
    return new InstallFailure(stage, "INSTALL_" + stage.name() + "_" + kind, reason);
  }

  public String display() {
    return UiText.format("安装步骤失败（%s）：%s", code, reason);
  }
}
