package com.deepseekharness.app.util;

/** Re-render only complete recovery status templates; unknown diagnostics stay verbatim. */
public final class RecoveryStatusText {
  private RecoveryStatusText() {}

  private static final String[] READY = {
    "独立应急 DSH 已就绪；正式环境保持原状。",
    "Independent recovery DSH is ready. The main environment is preserved."
  };
  private static final String[] READ_ONLY = {
    "系统限制进程身份读取；应急对话与只读诊断可用，正式环境写入仍被阻止。",
    "The system restricts process identity access. Recovery chat and read-only diagnostics are available; writes to the main environment remain blocked."
  };
  private static final String[][] NOTICES = {
    {"", ""},
    {
      " 尚未配置模型凭据，可返回原生应急页面填写本次临时密钥。",
      " No model credential is configured. Enter a temporary key on the native recovery page."
    },
    {
      " 原生凭据暂不可读取，已保留原密文；可填写本次临时密钥。",
      " The saved credential is unavailable and has been preserved. You can enter a temporary key."
    }
  };

  public static String render(String state, String detail) {
    if (detail == null) return "";
    String[] prefix =
        switch (state == null ? "" : state) {
          case "READY" -> READY;
          case "READY_READ_ONLY" -> READ_ONLY;
          default -> null;
        };
    if (prefix == null) return detail;
    for (String[] notice : NOTICES)
      for (int source = 0; source < 2; source++) {
        if (!detail.equals(prefix[source] + notice[source])) continue;
        int target = "en".equals(UiText.language()) ? 1 : 0;
        return prefix[target] + notice[target];
      }
    return detail;
  }
}
