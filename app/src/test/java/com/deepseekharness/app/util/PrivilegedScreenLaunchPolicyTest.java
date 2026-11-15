package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

/** T130：实体屏特权 Core 的受管启动参数必须逐项精确核验，不能变成通用 app_process 入口。 */
public final class PrivilegedScreenLaunchPolicyTest {
  private static final String APK = "/data/app/com.dsh.client/base.apk";
  private static final String PACKAGE = "com.dsh.client";

  private static String physical(String apk, String pkg) {
    return "app_process -Djava.class.path="
        + apk
        + " /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore --launch --physical --port 0 --package "
        + pkg;
  }

  @Test
  public void exactPhysicalLauncherIsAcceptedByTheManagedEntry() {
    String command = physical(APK, PACKAGE);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectVirtualScreenLaunch(command, APK, PACKAGE).kind);
    assertEquals(
        DeviceShellPolicy.Kind.VIRTUAL_SCREEN,
        DeviceShellPolicy.inspectPhysicalScreenLaunch(command, APK, PACKAGE).kind);
    assertEquals(
        DeviceShellPolicy.Kind.VIRTUAL_SCREEN,
        DeviceShellPolicy.inspectManagedVirtualScreen(command, APK, PACKAGE).kind);
  }

  @Test
  public void physicalLauncherRejectsAppendedArgumentsAndUnknownVerbs() {
    String command = physical(APK, PACKAGE);
    for (String bad :
        new String[] {
          command + " --token " + "0123456789abcdef0123456789abcdef0123456789abcdef",
          command + " --signal 9",
          command.replace("--physical", "--probe-exit"),
          command.replace("--port 0", "--port 8801"),
          command.replace("--port 0", "--port 0 --token x"),
          command.replace("--launch", "--server"),
          command.replace("VirtualScreenCore", "com.example.Other")
        })
      assertEquals(
          bad,
          DeviceShellPolicy.Kind.DENY,
          DeviceShellPolicy.inspectManagedVirtualScreen(bad, APK, PACKAGE).kind);
  }

  @Test
  public void physicalLauncherIsBoundToTheCurrentCanonicalApkAndPackage() {
    String command = physical(APK, PACKAGE);
    for (String otherApk :
        new String[] {
          "/data/app/../../data/local/tmp/probe.apk",
          "/data/app/com.attacker.client/base.apk",
          "/data/app/com.dsh.client/../other.apk",
          "/data/app/com.dsh.client/base.apk/../base.apk"
        })
      assertEquals(
          otherApk,
          DeviceShellPolicy.Kind.DENY,
          DeviceShellPolicy.inspectManagedVirtualScreen(
                  command.replace(APK, otherApk), APK, PACKAGE)
              .kind);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectManagedVirtualScreen(command, APK, "com.other.app").kind);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectManagedVirtualScreen(command, APK, null).kind);
  }

  @Test
  public void virtualProbeAndPhysicalFormsDoNotCrossAuthorize() {
    String identity =
        "123:456:2000:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:11111111-2222-3333-4444-555555555555";
    String probe =
        "app_process -Djava.class.path="
            + APK
            + " /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore --probe-exit --identity "
            + identity
            + " --package "
            + PACKAGE;
    assertEquals(
        DeviceShellPolicy.Kind.VIRTUAL_SCREEN,
        DeviceShellPolicy.inspectManagedVirtualScreen(probe, APK, PACKAGE).kind);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectPhysicalScreenLaunch(probe, APK, PACKAGE).kind);

    String virtual =
        "app_process -Djava.class.path="
            + APK
            + " /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 0 --package "
            + PACKAGE;
    assertEquals(
        DeviceShellPolicy.Kind.VIRTUAL_SCREEN,
        DeviceShellPolicy.inspectManagedVirtualScreen(virtual, APK, PACKAGE).kind);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectPhysicalScreenLaunch(virtual, APK, PACKAGE).kind);
  }
}
