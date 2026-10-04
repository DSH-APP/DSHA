package com.deepseekharness.app;

import com.deepseekharness.app.bridge.AndroidDeviceFiles;
import com.deepseekharness.app.util.DeviceFileOperations;
import com.deepseekharness.app.util.DeviceShellPolicy;
import java.io.IOException;

/** ADB FILE 的一次性受管入口；只加载当前已安装 APK，所有写入复用原生策略。 */
public final class DeviceFileCore {
  private DeviceFileCore() {}

  public static void main(String[] args) {
    int exit = 126;
    try {
      int uid = android.os.Process.myUid();
      if (uid != 0 && uid != 2000) throw new IOException("PRIVILEGED_DEVICE_CHANNEL_REQUIRED");
      if (args == null
          || args.length < 6
          || args.length > 133
          || !args[0].equals("--package")
          || !args[2].equals("--uid")
          || !args[4].equals("--")) throw new IOException("FILE_INVOCATION_INVALID");
      com.deepseekharness.app.runtime.PrivilegedPackageContext.launchIdentity(
          args[1], Integer.parseInt(args[3]));
      DeviceShellPolicy.Plan plan =
          DeviceShellPolicy.inspectFileArguments(
              java.util.Arrays.asList(args).subList(5, args.length));
      if (plan.kind != DeviceShellPolicy.Kind.FILE) throw new IOException("FILE_PLAN_REQUIRED");
      DeviceShellExecutor.validateRootReadPaths(plan);
      DeviceFileOperations.execute(plan, new AndroidDeviceFiles());
      System.out.println("DEVICE_FILE_OPERATION_COMPLETE");
      exit = 0;
    } catch (java.io.InterruptedIOException cancelled) {
      System.out.println("[EXECUTION_UNKNOWN] FILE_OPERATION_CANCELLED");
      exit = 125;
    } catch (Exception error) {
      System.out.println(
          "[POLICY_BLOCKED] "
              + com.deepseekharness.app.util.SensitiveData.redact(error.getMessage()));
    }
    System.out.flush();
    System.exit(exit);
  }
}
