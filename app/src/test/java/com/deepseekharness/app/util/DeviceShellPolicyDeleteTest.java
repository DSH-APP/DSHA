package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 严格档递归删除的收口规则。
 *
 * <p>参考 Operit 的 "rm -rf" 拦截：那边是整条命令一律拒。DSHA 的 rm 是受管 argv，删单个文件属于
 * 正常用法，所以按路径收口：基础存储根与它的一级目录不准递归删，那等于一次性清空相册、下载、
 * 文档这些用户可见数据；更深的子目录仍可用。
 */
public class DeviceShellPolicyDeleteTest {

  private static void denied(String command) {
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
    assertFalse("应当拒绝: " + command + "（实际 " + plan.kind + "）", plan.allowed());
    assertEquals(DeviceShellPolicy.Kind.DENY, plan.kind);
  }

  private static void allowed(String command) {
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
    assertTrue("应当放行: " + command + "（实际 " + plan.kind + " " + plan.reason + "）", plan.allowed());
  }

  @Test
  public void recursiveDeleteOfStorageRootOrFirstLevelIsDenied() {
    denied("rm -rf /sdcard");
    denied("rm -rf /sdcard/Download");
    denied("rm -r /sdcard/Documents");
    denied("rm -R /sdcard/Music");
    denied("rm --recursive --force /sdcard/Pictures");
    denied("rm -rf /mnt/runtime/default/emulated/0/Download");
    denied("rm -rf /data/local/tmp");
  }

  @Test
  public void deeperPathsAndSingleFileDeleteStayUsable() {
    allowed("rm -rf /sdcard/Download/2026");
    allowed("rm -f /sdcard/Download/x.zip");
    allowed("rm -rf /data/local/tmp/build");
  }

  @Test
  public void systemRootsAndBlockDevicesKeepTheirOwnRules() {
    denied("rm -rf /");
    denied("rm -rf /data");
    denied("rm -rf /system");
    denied("format");
    denied("mkfs.ext4 /dev/block/sda1");
    denied("dd if=/dev/zero of=/dev/block/sda");
    denied("reboot");
  }

  @Test
  public void quotingDoesNotTurnEchoIntoADeletion() {
    allowed("echo rm -rf /");
  }
}
