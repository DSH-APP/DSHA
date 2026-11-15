package com.deepseekharness.app;

import android.util.Base64;
import com.deepseekharness.app.util.SensitiveData;
import java.nio.charset.StandardCharsets;

/** 由 su 启动的短期进程；在真正的 root 身份下复用路径、应用清单与命令守卫。 */
public final class RootShellMain {
  public static final String RESULT = "DSHA_ROOT_RESULT=";

  private RootShellMain() {}

  /** 收到请求前的兜底期限：App 写完请求就关闭 stdin，正常情况下远远用不到。 */
  private static final long BOOT_DEADLINE_MS = 90_000L;

  private static final long STARTED = System.nanoTime();
  private static volatile long deadlineMs = BOOT_DEADLINE_MS;
  private static volatile com.deepseekharness.app.util.RootHelperWatchdog.Owner owner;
  private static volatile ShellService active;

  /**
   * T38：App 的 uid 对本 root 进程发信号会被拒绝（EPERM），所以本进程自己负责退出。超过请求给出的期限，
   * 或发起请求的 App 进程已退出 / PID 被复用（覆盖安装、被系统结束），就结束本次子进程并立即退出。
   */
  private static void startWatchdog() {
    Thread watchdog =
        new Thread(
            () -> {
              while (true) {
                long elapsed = (System.nanoTime() - STARTED) / 1_000_000L;
                com.deepseekharness.app.util.RootHelperWatchdog.Owner current = owner;
                String stat = current == null || !current.known() ? null : readStat(current.pid);
                com.deepseekharness.app.util.RootHelperWatchdog.Verdict verdict =
                    com.deepseekharness.app.util.RootHelperWatchdog.check(
                        elapsed, deadlineMs, current, stat);
                if (verdict != com.deepseekharness.app.util.RootHelperWatchdog.Verdict.CONTINUE) {
                  try {
                    ShellService shell = active;
                    if (shell != null) shell.terminateOwned();
                    System.err.println("DSHA_ROOT_WATCHDOG=" + verdict);
                  } finally {
                    Runtime.getRuntime().halt(125);
                  }
                }
                try {
                  Thread.sleep(500);
                } catch (InterruptedException ignored) {
                  // 看门狗不响应中断；只按期限与 owner 判定退出。
                }
              }
            },
            "dsha-root-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  private static String readStat(int pid) {
    try {
      return new String(
              java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/proc/" + pid + "/stat")),
              StandardCharsets.US_ASCII)
          .trim();
    } catch (java.io.IOException | RuntimeException gone) {
      return null;
    }
  }

  public static void main(String[] args) {
    String output;
    startWatchdog();
    try {
      if (android.os.Process.myUid() != 0) throw new SecurityException("su 未授予 root 身份");
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[2048];
      int count;
      // 原始 root 命令的 JSON 请求比严格档单条命令更大；仍设硬上限，避免无限读入。
      while ((count = System.in.read(buffer)) != -1) {
        if (bytes.size() + count > 65_536) throw new IllegalArgumentException("设备命令过长");
        bytes.write(buffer, 0, count);
      }
      org.json.JSONObject request =
          new org.json.JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      // 旧版 App 不传这些字段：沿用兜底期限，不按 owner 判定。
      deadlineMs =
          com.deepseekharness.app.util.RootHelperWatchdog.deadlineMs(
              request.optLong("helperDeadlineMs", -1), BOOT_DEADLINE_MS);
      owner =
          new com.deepseekharness.app.util.RootHelperWatchdog.Owner(
              request.optInt("ownerPid", -1), request.optLong("ownerStarted", -1));
      String command = request.optString("command", "");
      if (!command.isEmpty()
          && command.getBytes(StandardCharsets.UTF_8).length
              > com.deepseekharness.app.util.DeviceShellPolicy.MAX_SHELL_COMMAND)
        throw new IllegalArgumentException("设备命令超过上限");
      int smsUser = request.optInt("smsUser", -1);
      String operation = request.optString("operation", "");
      boolean externalArgv = operation.equals("external-argv");
      boolean externalAdbSession = operation.equals("external-adb-session");
      boolean externalAdbSteps = operation.equals("external-adb-steps");
      boolean rawShell = operation.equals("root-unrestricted");
      boolean identityRequired =
          externalArgv
              || externalAdbSession
              || externalAdbSteps
              || smsUser >= 0
              || operation.equals("virtual-screen-start")
              || rawShell
              || !command.isEmpty()
                  && com.deepseekharness.app.util.DeviceShellPolicy.inspect(command).kind
                      == com.deepseekharness.app.util.DeviceShellPolicy.Kind.STOP;
      ShellService shell;
      if (request.has("selfPackage") || request.has("selfUid"))
        shell =
            new ShellService(
                com.deepseekharness.app.runtime.PrivilegedPackageContext.launchIdentity(
                    request.getString("selfPackage"), request.getInt("selfUid")));
      else if (identityRequired) throw new SecurityException("SELF_PACKAGE_UNVERIFIED");
      else
        shell =
            new ShellService(); // Historical ordinary reads do not require a guessed app identity.
      active = shell;
      if (externalAdbSteps) {
        if (android.os.Process.myUid() != 0) throw new SecurityException("外接命令需要 root");
        String expectedRoot = request.optString("expectedRoot", "");
        String importRoot = request.optString("importRoot", "");
        java.util.List<String> serverArgv = stringList(request.getJSONArray("serverArgv"));
        String serverPolicy =
            com.deepseekharness.app.util.ExternalToolPolicy.rejectionAdbServer(
                serverArgv, expectedRoot);
        if (serverPolicy != null) throw new SecurityException(serverPolicy);
        org.json.JSONArray rawSteps = request.getJSONArray("steps");
        if (rawSteps.length() == 0 || rawSteps.length() > 64)
          throw new IllegalArgumentException("外接步骤数量无效");
        java.util.List<java.util.List<String>> steps = new java.util.ArrayList<>();
        for (int i = 0; i < rawSteps.length(); i++) {
          java.util.List<String> step = stringList(rawSteps.getJSONArray(i));
          String policy =
              com.deepseekharness.app.util.ExternalToolPolicy.rejection(
                  step, expectedRoot, importRoot.isEmpty() ? null : importRoot);
          if (policy != null) throw new SecurityException(policy + " @step " + i);
          steps.add(step);
        }
        long timeout = request.optLong("timeoutMs", 120_000L);
        if (timeout <= 0 || timeout > 900_000L) throw new IllegalArgumentException("外接期限无效");
        org.json.JSONObject envObject = request.optJSONObject("environment");
        java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        if (envObject != null) {
          java.util.Iterator<String> keys = envObject.keys();
          while (keys.hasNext()) {
            String key = keys.next(), value = envObject.getString(key);
            if (!key.matches("[A-Z_][A-Z0-9_]{0,63}")
                || value.length() > 4096
                || value.indexOf('\0') >= 0) throw new IllegalArgumentException("外接环境变量无效");
            environment.put(key, value);
          }
        }
        output =
            shell.execExternalAdbSteps(
                serverArgv,
                steps,
                environment,
                timeout,
                request.optString("nonce", ""),
                request.optBoolean("continueOnFailure", false));
      } else if (externalArgv || externalAdbSession) {
        if (android.os.Process.myUid() != 0) throw new SecurityException("外接命令需要 root");
        org.json.JSONArray values = request.getJSONArray("argv");
        if (values.length() == 0 || values.length() > 32)
          throw new IllegalArgumentException("外接参数过多");
        java.util.List<String> argv = new java.util.ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
          String value = values.getString(i);
          if (value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("外接参数无效");
          argv.add(value);
        }
        org.json.JSONObject envObject = request.optJSONObject("environment");
        java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        if (envObject != null) {
          java.util.Iterator<String> keys = envObject.keys();
          while (keys.hasNext()) {
            String key = keys.next(), value = envObject.getString(key);
            if (!key.matches("[A-Z_][A-Z0-9_]{0,63}")
                || value.length() > 4096
                || value.indexOf('\0') >= 0) throw new IllegalArgumentException("外接环境变量无效");
            environment.put(key, value);
          }
        }
        long timeout = request.optLong("timeoutMs", 120_000L);
        if (timeout <= 0 || timeout > 900_000L) throw new IllegalArgumentException("外接期限无效");
        String expectedRoot = request.optString("expectedRoot", "");
        String policy =
            com.deepseekharness.app.util.ExternalToolPolicy.rejection(argv, expectedRoot);
        if (policy != null) throw new SecurityException(policy);
        if (externalAdbSession) {
          org.json.JSONArray serverValues = request.getJSONArray("serverArgv");
          if (serverValues.length() == 0 || serverValues.length() > 32)
            throw new IllegalArgumentException("外接 ADB server 参数无效");
          java.util.List<String> serverArgv = new java.util.ArrayList<>();
          for (int i = 0; i < serverValues.length(); i++) {
            String value = serverValues.getString(i);
            if (value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
              throw new IllegalArgumentException("外接 ADB server 参数无效");
            serverArgv.add(value);
          }
          String serverPolicy =
              com.deepseekharness.app.util.ExternalToolPolicy.rejectionAdbServer(
                  serverArgv, expectedRoot);
          if (serverPolicy != null) throw new SecurityException(serverPolicy);
          output = shell.execExternalAdbSession(serverArgv, argv, environment, timeout);
        } else output = shell.execExternal(argv, environment, timeout);
      } else if (operation.equals("virtual-screen-start"))
        output = shell.execVirtualScreen(command);
      else if (rawShell) output = shell.execUnrestricted(command);
      else if (operation.isEmpty())
        output = smsUser < 0 ? shell.exec(command) : shell.execAuthorizedSms(command, smsUser);
      else throw new SecurityException("未知 Root 操作");
    } catch (Throwable e) {
      output = "[ROOT_EXEC_FAILED] " + SensitiveData.redact(String.valueOf(e)) + "\n[EXIT=126]";
    }
    System.out.println(
        RESULT + Base64.encodeToString(output.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    System.out.flush();
    System.exit(0);
  }

  /** JSON 字符串数组 → argv；空串、NUL、过长或超过 32 项一律拒绝。 */
  private static java.util.List<String> stringList(org.json.JSONArray values)
      throws org.json.JSONException {
    if (values.length() == 0 || values.length() > 32)
      throw new IllegalArgumentException("外接参数数量无效");
    java.util.List<String> out = new java.util.ArrayList<>();
    for (int i = 0; i < values.length(); i++) {
      String value = values.getString(i);
      if (value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
        throw new IllegalArgumentException("外接参数无效");
      out.add(value);
    }
    return out;
  }
}
