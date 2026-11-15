package com.deepseekharness.app.util;

/**
 * “DSH CLI”PTY 的固定 guest 命令：只生成 argv，不读取凭据、不拼用户输入、不自动跑模型任务。
 *
 * <p>入口仍然复用普通 PTY 会话，因此关闭、保护票、维护屏障的算法一行都不用改；这里只决定
 * 会话启动后先打印什么、最后停在哪里。{@code exec /bin/bash -l} 让同一个会话组长变成登录
 * shell，不会留下等待子 shell 的父进程。
 */
public final class DshaCliPreset {
  public static final String TITLE = "DSH CLI";

  private DshaCliPreset() {}

  /** 固定 argv：{@code /bin/bash -c <script>}。语言只影响静态 banner。 */
  public static String[] guestCommand(String languageTag) {
    return new String[] {"/bin/bash", "-c", script(languageTag)};
  }

  /** banner 与预检脚本；不含用户输入插值，也不写任何凭据。 */
  public static String script(String languageTag) {
    boolean chinese = languageTag != null && languageTag.startsWith("zh");
    return String.join(
        "\n",
        "stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true",
        "if _dsha_cli_version=$(dsh --version 2>/dev/null); then",
        "  printf 'DSH CLI · dsh %s\\n' \"$_dsha_cli_version\"",
        "else",
        "  "
            + say(
                chinese
                    ? "DSH CLI：dsh 不可用，请先完成环境安装；当前仍是普通 shell。"
                    : "DSH CLI: dsh is unavailable; finish the environment installation first. This is still a plain shell."),
        "fi",
        say(
            chinese
                ? "DSH CLI：这里没有交互式 dsh TUI，只有 dsh 启动器 + 登录 shell。"
                : "DSH CLI: there is no interactive dsh TUI here, only the dsh launcher plus a login shell."),
        say(
            chinese
                ? "一次性任务用 dsh --profile headless \"<任务>\"；headless 默认不读取 GUI 保存的凭据，需要时自行提供环境变量或 .env。"
                : "For one-shot tasks use dsh --profile headless \"<task>\"; headless does not read GUI-saved credentials by default, so provide env vars or .env yourself."),
        say(
            chinese
                ? "不要在终端启动 dsh web，也不要对 Web 正在使用的 profile 执行 dsh plugin --profile web ...；Web 由 DSHA 管理。"
                : "Do not start dsh web here, and do not run dsh plugin --profile web ... against the profile Web is using; DSHA manages Web."),
        say(
            chinese
                ? "可先用 dsh --help 查看启动器命令。"
                : "Start with dsh --help to inspect launcher commands."),
        "exec /bin/bash -l");
  }

  private static String say(String line) {
    return "printf '%s\\n' " + ShellQuote.arg(line);
  }
}
