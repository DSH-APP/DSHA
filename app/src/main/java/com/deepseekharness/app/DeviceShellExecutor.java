package com.deepseekharness.app;

import com.deepseekharness.app.util.DeviceAppPolicy;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** 设备 shell 的执行前守卫。调用者不能通过确认开关、root 或内部标志跳过判定。 */
public final class DeviceShellExecutor {
  private DeviceShellExecutor() {}

  interface Runner {
    String run(List<String> argv);
  }

  static String execute(String command, Runner runner) {
    return execute(command, runner, null);
  }

  static String execute(
      String command, Runner runner, com.deepseekharness.app.util.SelfPackageIdentity identity) {
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
    if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
    // UserService 不持有 App 的授权记录；短信必须先经过原生授权桥再走 ADB。
    if (plan.kind == DeviceShellPolicy.Kind.SENSITIVE_READ)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 短信读取请使用 ADB 通道，并在 DSHA 授权\n[EXIT=126]");
    try {
      int uid = android.os.Process.myUid();
      if (uid == 0 || uid == 2000) validateRootReadPaths(plan);
      if (plan.kind == DeviceShellPolicy.Kind.STOP) {
        if (identity == null) throw new IOException("SELF_PACKAGE_UNVERIFIED");
        String users = checked(runner.run(Arrays.asList("pm", "list", "packages", "-U", "-3")));
        String systems = checked(runner.run(Arrays.asList("pm", "list", "packages", "-U", "-s")));
        DeviceAppPolicy.Snapshot snapshot = DeviceAppPolicy.snapshot(users, systems, identity.name);
        String processes =
            plan.command().equals("kill")
                ? checked(runner.run(Arrays.asList("ps", "-A", "-o", "PID,UID,NAME")))
                : "";
        final List<String> targets;
        try {
          targets = snapshot.targets(plan, processes);
        } catch (IllegalArgumentException blocked) {
          return snapshot.grouped() + "[POLICY_BLOCKED] " + blocked.getMessage() + "\n[EXIT=126]";
        }
        StringBuilder output = new StringBuilder(snapshot.grouped());
        for (String target : targets) {
          // 按包名操作，避免检查完 PID 后号码复用而误伤系统进程。
          String result = runner.run(Arrays.asList("am", "force-stop", target));
          output
              .append(com.deepseekharness.app.util.UiText.text("停止用户应用："))
              .append(target)
              .append('\n')
              .append(result)
              .append('\n');
          if (!result.endsWith("[EXIT=0]")) break;
        }
        return output.toString().trim();
      }
      if (plan.kind == DeviceShellPolicy.Kind.FILE) {
        com.deepseekharness.app.util.DeviceFileOperations.execute(
            plan, new com.deepseekharness.app.bridge.AndroidDeviceFiles());
        return "DEVICE_FILE_OPERATION_COMPLETE\n[EXIT=0]";
      }
      return runner.run(plan.argv);
    } catch (java.io.InterruptedIOException cancelled) {
      return "[EXECUTION_UNKNOWN] FILE_OPERATION_CANCELLED\n[EXIT=125]";
    } catch (IOException | IllegalArgumentException error) {
      return "[POLICY_BLOCKED] " + SensitiveData.redact(error.getMessage()) + "\n[EXIT=126]";
    }
  }

  /** Privileged typed entry used only by VirtualScreenManager, never by generic shell text. */
  static String executeVirtualScreen(String command, Runner runner) {
    return executeVirtualScreen(command, runner, null);
  }

  static String executeVirtualScreen(
      String command, Runner runner, com.deepseekharness.app.util.SelfPackageIdentity identity) {
    int uid = android.os.Process.myUid();
    if (uid != 0 && uid != 2000)
      return com.deepseekharness.app.util.UiText.text(
          "[POLICY_BLOCKED] 虚拟屏启动仅允许特权设备通道\n[EXIT=126]");
    try {
      if (identity == null) throw new IOException("SELF_PACKAGE_UNVERIFIED");
      DeviceShellPolicy.Plan plan =
          DeviceShellPolicy.inspectVirtualScreenLaunch(command, identity.source, identity.name);
      if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
      return runner.run(plan.argv);
    } catch (IOException | IllegalArgumentException error) {
      return "[POLICY_BLOCKED] " + SensitiveData.redact(error.getMessage()) + "\n[EXIT=126]";
    }
  }

  /** Recheck SMS-provider file reads at the privileged execution boundary. */
  static void validateRootReadPaths(DeviceShellPolicy.Plan plan) throws IOException {
    boolean recursive = DeviceShellPolicy.rootReadMayDescend(plan);
    for (String path : DeviceShellPolicy.rootReadPaths(plan)) {
      if (isSmsReadPath(path, recursive)) throw new IOException("短信数据库仅允许经原生授权的当前用户 content query");
      final String canonical;
      try {
        canonical = new File(path).getCanonicalPath();
      } catch (IOException unreadable) {
        throw new IOException("无法核验 Root 读取路径", unreadable);
      }
      if (isSmsReadPath(canonical, recursive))
        throw new IOException("短信数据库仅允许经原生授权的当前用户 content query");
    }
  }

  private static boolean isSmsReadPath(String path, boolean recursive) {
    return DeviceShellPolicy.smsProviderPath(path)
        || recursive && DeviceShellPolicy.smsProviderDescendant(path);
  }

  private static String checked(String output) throws IOException {
    if (output == null || !output.endsWith("[EXIT=0]") || output.contains("[OUTPUT_TRUNCATED]"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法取得完整设备信息，未执行写入或停止操作"));
    return output.substring(0, output.length() - "[EXIT=0]".length()).trim();
  }
}
