package com.deepseekharness.app.util;

import java.util.List;

/** 安装监督 shell 的握手与退出状态；只使用旧 Android sh 已有的内建命令。 */
public final class IsolatedCommand {
  private IsolatedCommand() {}

  public static String script(List<String> command, String statusPath) {
    return script(command, statusPath, false);
  }

  /** Legacy system setsid self-reads the shell leader; the bundled native leader emits its own line. */
  public static String script(
      List<String> command, String statusPath, boolean shellBirthHandshake) {
    if (command == null || command.isEmpty())
      throw new IllegalArgumentException("EMPTY_INSTALL_COMMAND");
    StringBuilder shell = new StringBuilder();
    if (shellBirthHandshake)
      shell
          .append("IFS= read -r DSHA_BIRTH_STAT < /proc/$$/stat || exit 125\n")
          .append("echo \"DSHA_SESSION_STAT_V1 $DSHA_BIRTH_STAT\" || exit 125\n");
    shell.append(
        "IFS= read -r DSHA_START || exit 125\n" + "[ \"$DSHA_START\" = DSHA_START ] || exit 125\n");
    for (String argument : command) shell.append(ShellQuote.arg(argument)).append(' ');
    // $? 只含十进制数字；echo 不涉及转义/选项，也不依赖外部 printf 或 PATH。
    shell
        .append("\nresult=$?\necho \"$result\" > ")
        .append(ShellQuote.arg(statusPath))
        .append(" || exit 125\nkill -STOP $$\nexit 125\n");
    return shell.toString();
  }

  public static int status(String value) {
    if (value == null || !value.matches("[0-9]{1,3}\\n?")) return -1;
    int code = Integer.parseInt(value.trim());
    return code <= 255 ? code : -1;
  }
}
