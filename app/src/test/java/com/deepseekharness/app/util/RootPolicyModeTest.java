package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * root 命令的三态拦截：严格 / 放宽部分 / 完全放宽。
 *
 * <p>参考 Operit 的“命令里出现 rm -rf 或 format 就拒”思路，但 DSHA 按用户对 root 的预期拆成三档：
 * 严格档只放行可完整识别的单条 argv，放宽档放行任意命令但保留不可逆操作黑名单，完全放宽档不再按
 * 命令内容判断。三档都必须保留短信读取的原生授权闸门，且只有 root 通道能使用放宽档。
 */
public class RootPolicyModeTest {

  private static DeviceShellPolicy.Plan plan(String command, DeviceShellPolicy.Mode mode) {
    return DeviceShellPolicy.inspect(command, mode);
  }

  @Test
  public void strictDeniesUnrecognisedCommands() {
    assertFalse(plan("curl http://example.com", DeviceShellPolicy.Mode.STRICT).allowed());
    assertFalse(plan("sh -c 'ls; id'", DeviceShellPolicy.Mode.STRICT).allowed());
    assertTrue(plan("ls /sdcard", DeviceShellPolicy.Mode.STRICT).allowed());
  }

  @Test
  public void relaxedAllowsArbitraryCommandsButKeepsIrreversibleOnes() {
    String[] allowed = {
      "curl http://example.com",
      "sh -c 'ls; id'",
      "settings put global x 1",
      "echo hello && id",
      "whoami; getprop ro.build.version.release"
    };
    for (String command : allowed)
      assertTrue("放宽档应放行: " + command, plan(command, DeviceShellPolicy.Mode.RELAXED).allowed());

    String[] blocked = {
      "mkfs.ext4 /dev/block/sda1",
      "mke2fs -t ext4 /dev/block/sda1",
      "dd if=/dev/zero of=/dev/block/sda",
      "fastboot flash boot boot.img",
      "wipefs -a /dev/block/sda",
      "reboot",
      "shutdown -r now",
      "pm clear com.other.app",
      "cmd package clear com.other.app",
      "echo reboot"
    };
    for (String command : blocked)
      assertFalse("放宽档仍须拒绝: " + command, plan(command, DeviceShellPolicy.Mode.RELAXED).allowed());
  }

  @Test
  public void relaxedGradesDeleteTargetsByRecoverability() {
    String[] catastrophic = {
      "rm -rf /",
      "rm -rf /*",
      "rm -r -f /",
      "rm -rf /data",
      "rm -rf /data/*",
      "rm -rf /system/bin",
      "rm -rf /sdcard",
      "rm -rf /sdcard/DCIM",
      "rm -rf /sdcard/Download",
      "rm -rf /data/local/tmp",
      "true; rm -rf /sdcard/Download"
    };
    for (String command : catastrophic)
      assertFalse(
          "整块数据或系统分区不可恢复: " + command, plan(command, DeviceShellPolicy.Mode.RELAXED).allowed());

    String[] recoverable = {
      "rm -rf /sdcard/Download/report.txt",
      "rm -rf /sdcard/Download/project",
      "rm -rf /data/local/tmp/build",
      "rm -f /sdcard/Download/x.zip"
    };
    for (String command : recoverable)
      assertTrue(
          "子路径删除属于正常用法: " + command, plan(command, DeviceShellPolicy.Mode.RELAXED).allowed());
  }

  @Test
  public void fullModeStopsJudgingCommands() {
    String[] anything = {
      "mkfs.ext4 /dev/block/sda1",
      "reboot",
      "pm clear com.other.app",
      "rm -rf /data",
      "mount -o remount,rw /system",
      "settings put global x 1"
    };
    for (String command : anything)
      assertTrue(
          "完全放宽档不再按命令内容拦截: " + command, plan(command, DeviceShellPolicy.Mode.FULL).allowed());
  }

  @Test
  public void shellPlanCarriesRawCommandAndMode() {
    for (DeviceShellPolicy.Mode mode :
        new DeviceShellPolicy.Mode[] {
          DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.FULL
        }) {
      String command = "sh -c 'id; echo $HOME'; echo ok";
      DeviceShellPolicy.Plan value = plan(command, mode);
      assertEquals(DeviceShellPolicy.Kind.SHELL, value.kind);
      assertEquals(command, value.shell);
      assertEquals(mode, value.mode);
      assertNotNull(value.reason);
    }
  }

  @Test
  public void nativeGatesSurviveEveryMode() {
    String sms = "content query --uri content://sms/inbox";
    for (DeviceShellPolicy.Mode mode : DeviceShellPolicy.Mode.values())
      assertEquals(
          "短信读取必须保持 SENSITIVE_READ: " + mode,
          DeviceShellPolicy.Kind.SENSITIVE_READ,
          plan(sms, mode).kind);
    for (DeviceShellPolicy.Mode mode :
        new DeviceShellPolicy.Mode[] {
          DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.FULL
        }) {
      assertFalse(plan("echo x; content query --uri content://sms/inbox", mode).allowed());
      assertFalse(
          plan("cat /data/data/com.android.providers.telephony/databases/mmssms.db", mode)
              .allowed());
    }
  }

  @Test
  public void invalidOrOversizedShellCommandsFailClosed() {
    for (DeviceShellPolicy.Mode mode :
        new DeviceShellPolicy.Mode[] {
          DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.FULL
        }) {
      assertFalse(plan("", mode).allowed());
      assertFalse(plan("   ", mode).allowed());
      assertFalse(plan("echo x\0y", mode).allowed());
      assertFalse(plan("echo " + "x".repeat(DeviceShellPolicy.MAX_SHELL_COMMAND), mode).allowed());
    }
  }

  @Test
  public void unknownModeFallsBackToStrict() {
    assertEquals(DeviceShellPolicy.Mode.STRICT, DeviceShellPolicy.Mode.parse(""));
    assertEquals(DeviceShellPolicy.Mode.STRICT, DeviceShellPolicy.Mode.parse(null));
    assertEquals(DeviceShellPolicy.Mode.STRICT, DeviceShellPolicy.Mode.parse("放宽"));
    assertEquals(DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.parse(" RELAXED "));
    assertEquals(DeviceShellPolicy.Mode.FULL, DeviceShellPolicy.Mode.parse("full"));
    assertEquals("strict", DeviceShellPolicy.Mode.idOf(null));
  }
}
