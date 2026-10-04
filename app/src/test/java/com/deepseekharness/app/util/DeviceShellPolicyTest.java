package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceShellPolicyTest {
  private void denied(String... commands) {
    for (String cmd : commands)
      assertEquals(cmd, DeviceShellPolicy.Kind.DENY, DeviceShellPolicy.inspect(cmd).kind);
  }

  @Test
  public void everyReadableDirectoryIsEligible() {
    for (String cmd :
        new String[] {
          "ls /",
          "ls /system",
          "cat /proc/version",
          "ls /sdcard/DCIM",
          "ls /storage/emulated/0/Android/data",
          "cat /data/user/0/example.app/file",
          "getprop",
          "settings get global airplane_mode_on",
          "settings --user 0 list secure",
          "pm list packages -U -s",
          "dumpsys activity processes",
          "date +%s",
          "logcat -d -t 50",
          "find / -name '*.jpg'"
        }) assertEquals(cmd, DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect(cmd).kind);
  }

  @Test
  public void rootAndProtectedDirectoriesCannotBeMutated() {
    for (String root :
        new String[] {
          "/",
          "/*",
          "/system",
          "/data",
          "/storage",
          "/storage/emulated/0",
          "/sdcard/DCIM",
          "/storage/emulated/0/Pictures",
          "/storage/emulated/0/Android/data",
          "/storage/emulated/0/Android/obb",
          "/mnt/runtime/write/emulated/0/DCIM",
          "/storage/self/primary/Pictures",
          "/mnt/user/0/primary/Android/data"
        }) {
      denied(
          "mkdir " + root,
          "touch " + root,
          "rm -rf " + root,
          "mv " + root + " /sdcard/Download/x",
          "cp /sdcard/Download/x " + root,
          "rmdir " + root);
    }
    denied(
        "rm -rf /sdcard/pIcTuReS/a",
        "rm -rf /storage/emulated/10/DCIM/a",
        "rm -rf /storage/ABCD-0123/DCIM/a");
  }

  @Test
  public void ordinaryFilesAndCopiesFromReadOnlyDirectoriesAreAllowed() {
    for (String cmd :
        new String[] {
          "mkdir -p /sdcard/Download/project/sub",
          "touch /sdcard/Download/a",
          "cp /sdcard/DCIM/photo.jpg /sdcard/Download/photo.jpg",
          "cp /system/build.prop /sdcard/Download/info.txt",
          "mv /sdcard/Download/a /sdcard/Download/b",
          "rename /sdcard/Download/a /sdcard/Download/b",
          "rm -rf /sdcard/Download/project",
          "mkdir /data/local/tmp/dsha-test",
          "touch /storage/ABCD-0123/Documents/a"
        }) assertEquals(cmd, DeviceShellPolicy.Kind.FILE, DeviceShellPolicy.inspect(cmd).kind);
  }

  @Test
  public void unsafePathsAndUnknownOptionsFailClosed() {
    denied(
        "rm -rf /sdcard/*",
        "mv /sdcard/Download/../Pictures/a /sdcard/Download/b",
        "cp a b",
        "mkdir /data/local/tmp",
        "cp --target-directory=/sdcard/Pictures /sdcard/Download/a",
        "touch -r /system/build.prop /sdcard/Pictures/a",
        "rm --preserve-root=no /",
        "rmdir -p /data/local/tmp/empty",
        "rename 's/a/b/' /sdcard/Download/a");
    assertTrue(DeviceShellPolicy.writeAllowed("/sdcard/Pictures2/a"));
    assertFalse(DeviceShellPolicy.writeAllowed("/sdcardEVIL/a"));
  }

  @Test
  public void allDestructiveDeviceClassesAreBlocked() {
    denied(
        "dd if=/dev/zero of=/dev/block/sda",
        "dd if=/dev/block/sda of=/sdcard/Download/image",
        "mkfs.ext4 /dev/block/sda",
        "fdisk -l",
        "parted /dev/block/sda print",
        "setenforce 0",
        "chcon -R u:object_r:system_file:s0 /data",
        "restorecon -RF /data",
        "settings put global x 1",
        "settings delete secure x",
        "setprop persist.foo 1",
        "setprop debug.foo 1",
        "mount -o remount,rw /system",
        "umount /sdcard",
        "fastboot devices",
        "flash boot image",
        "format data",
        "erase userdata",
        "wipe data",
        "pm uninstall example.app",
        "pm clear example.app",
        "pm grant com.dsh.client android.permission.WRITE_SECURE_SETTINGS",
        "cmd settings put global x 1",
        "service call power 1");
  }

  @Test
  public void shellLanguageAndAlternateExecutorsCannotBypass() {
    denied(
        "sh -c 'rm -rf /sdcard/Pictures'",
        "python3 -c 'print(1)'",
        "node -e 'process.exit()'",
        "env ls /",
        "ls /; rm /sdcard/Pictures/a",
        "cat $(getprop)",
        "cat `id`",
        "echo hi > /dev/block/a",
        "echo hi >> /sdcard/Pictures/a",
        "ls / | head",
        "ls / && id",
        "ls /\nid",
        "r''m -rf /sdcard/Pictures",
        "toybox dd if=/dev/zero of=/dev/block/a",
        "/data/local/tmp/ls /",
        "/system/bin/../bin/sh -c id",
        "UNKNOWN",
        "",
        "getprop\0");
    assertEquals(
        DeviceShellPolicy.Kind.READ,
        DeviceShellPolicy.inspect("echo 'rm -rf /*; setenforce 0'").kind);
    assertEquals(
        DeviceShellPolicy.Kind.READ, DeviceShellPolicy.inspect("/system/bin/toybox ls /").kind);
  }

  @Test
  public void writableDiagnosticsAreNotReadOnly() {
    denied(
        "date -s 20260101",
        "date 010100002026",
        "dumpsys battery set level 0",
        "dumpsys deviceidle force-idle",
        "logcat -c",
        "logcat -d -f /sdcard/Download/log",
        "logcat -G 32M",
        "find / -delete",
        "find / -exec rm {} +");
  }

  @Test
  public void processTargetsMustBeExplicit() {
    for (String cmd :
        new String[] {
          "kill 12345",
          "kill -9 12345",
          "pkill -x example.app",
          "killall example.app",
          "am force-stop example.app",
          "am kill example.app"
        }) assertEquals(cmd, DeviceShellPolicy.Kind.STOP, DeviceShellPolicy.inspect(cmd).kind);
    denied(
        "kill -1",
        "kill -9 -1",
        "kill 0",
        "kill 1",
        "kill -STOP 123",
        "pkill -f example",
        "killall node",
        "am kill-all");
  }

  @Test
  public void acceptedArgumentsAreDataAndImmutable() {
    DeviceShellPolicy.Plan plan =
        DeviceShellPolicy.inspect("cp '/sdcard/Download/a b' '/sdcard/Download/c d'");
    assertEquals("/sdcard/Download/a b", plan.argv.get(1));
    assertEquals("/sdcard/Download/c d", plan.argv.get(2));
    try {
      plan.argv.set(0, "dd");
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  @Test
  public void genericDeviceCommandCannotStartAppProcess() {
    denied(
        "app_process /system/bin com.example.Main",
        "app_process -Djava.class.path=/data/app/com.dsh.client/base.apk /system/bin "
            + "com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 8801 --token "
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
  }

  @Test
  public void typedVirtualScreenLauncherRequiresExactCanonicalInstalledApk() {
    String apk = "/data/app/com.dsh.client/base.apk";
    String prefix =
        "app_process -Djava.class.path="
            + apk
            + " /system/bin "
            + "com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 8998 --package com.dsh.clienu";
    assertEquals(
        DeviceShellPolicy.Kind.VIRTUAL_SCREEN,
        DeviceShellPolicy.inspectVirtualScreenLaunch(prefix, apk, "com.dsh.clienu").kind);
    for (String bad :
        new String[] {
          "/data/app/../../data/local/tmp/probe.apk",
          "/data/app/com.attacker.client/base.apk",
          "/data/app/com.dsh.client/../other.apk",
          "/data/app/com.dsh.client/base.apk/../base.apk"
        }) {
      String command = prefix.replace(apk, bad);
      assertEquals(
          command,
          DeviceShellPolicy.Kind.DENY,
          DeviceShellPolicy.inspectVirtualScreenLaunch(command, apk, "com.dsh.clienu").kind);
    }
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectVirtualScreenLaunch(
                prefix.replace("8998", "89999"), apk, "com.dsh.clienu")
            .kind);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectVirtualScreenLaunch(
                prefix + " --token 0123456789abcdef0123456789abcdef0123456789abcdef",
                apk,
                "com.dsh.clienu")
            .kind);
  }

  @Test
  public void missingOrMismatchedLaunchIdentityDoesNotDefault() {
    String apk = "/data/app/clone/base.apk";
    String command =
        "app_process -Djava.class.path="
            + apk
            + " /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port 8800 --package com.dsh.clienu";
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectVirtualScreenLaunch(command, apk).kind);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectVirtualScreenLaunch(command, apk, "com.dsh.client").kind);
    assertEquals(
        DeviceShellPolicy.Kind.DENY,
        DeviceShellPolicy.inspectVirtualScreenLaunch(
                command.replace(" --package com.dsh.clienu", ""), apk, "com.dsh.clienu")
            .kind);
  }

  @Test
  public void recursiveRootReadsCannotDescendIntoSmsProviderParents() {
    for (String command :
        new String[] {
          "find /",
          "du",
          "du data",
          "du /data",
          "grep -R message /data/data",
          "ls -R",
          "ls -R data",
          "ls -R /data/user",
          "cp -R /data/data /sdcard/Download/apps"
        }) {
      DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
      assertNotEquals(command, DeviceShellPolicy.Kind.DENY, plan.kind);
      assertTrue(command, DeviceShellPolicy.rootReadMayDescend(plan));
      assertFalse(command, DeviceShellPolicy.rootReadPaths(plan).isEmpty());
      boolean coversSmsTree = false;
      for (String path : DeviceShellPolicy.rootReadPaths(plan))
        coversSmsTree |=
            path.equals(".")
                || !path.startsWith("/")
                || DeviceShellPolicy.smsProviderDescendant(path);
      assertTrue(
          command + " omitted recursive input path: " + DeviceShellPolicy.rootReadPaths(plan),
          coversSmsTree);
    }
    assertTrue(DeviceShellPolicy.smsProviderDescendant("/"));
    assertTrue(DeviceShellPolicy.smsProviderDescendant("/data"));
    assertTrue(DeviceShellPolicy.smsProviderDescendant("/data/user/0"));
    assertFalse(DeviceShellPolicy.smsProviderDescendant("/data/user/0/com.example.app"));
    assertFalse(DeviceShellPolicy.smsProviderDescendant("/data/data/com.example.app"));
    assertFalse(DeviceShellPolicy.rootReadMayDescend(DeviceShellPolicy.inspect("ls /")));
  }

  @Test
  public void multiSourceRootCopyChecksEverySource() {
    String allowed = "/data/local/tmp/source.txt";
    String protectedPath = "/data/user/0/com.android.providers.telephony/databases/mmssms.db";
    DeviceShellPolicy.Plan plan =
        DeviceShellPolicy.inspect(
            "cp " + allowed + " " + protectedPath + " /sdcard/Download/copied.db");
    assertEquals(DeviceShellPolicy.Kind.FILE, plan.kind);
    assertEquals(
        java.util.Arrays.asList(allowed, protectedPath), DeviceShellPolicy.rootReadPaths(plan));
  }

  @Test
  public void rootReadAndCopySourcesCannotBypassSmsCapability() {
    String[] protectedPaths = {
      "/data/user/0/com.android.providers.telephony/databases/mmssms.db",
      "/data/user_de/10/com.android.providers.telephony/databases/mmssms.db-wal",
      "/data/data/com.android.providers.telephony/databases/mmssms.db-shm",
      "/data_mirror/data_ce/null/0/com.android.providers.telephony/databases/mmssms.db",
      "/data_mirror/data_de/null/10/com.android.providers.telephony/databases/mmssms.db-wal",
      "/mnt/expand/fixture/user/0/com.android.providers.telephony/databases/mmssms.db"
    };
    for (String path : protectedPaths) {
      assertTrue(path, DeviceShellPolicy.smsProviderPath(path));
      DeviceShellPolicy.Plan cat = DeviceShellPolicy.inspect("cat " + path);
      assertEquals(path, DeviceShellPolicy.Kind.READ, cat.kind);
      assertTrue(path, DeviceShellPolicy.rootReadPaths(cat).contains(path));
      DeviceShellPolicy.Plan copy =
          DeviceShellPolicy.inspect("cp " + path + " /sdcard/Download/messages.db");
      assertEquals(path, DeviceShellPolicy.Kind.FILE, copy.kind);
      assertTrue(path, DeviceShellPolicy.rootReadPaths(copy).contains(path));
    }
    assertFalse(DeviceShellPolicy.smsProviderPath("/data/user/0/example.app/databases/data.db"));
    for (String root :
        java.util.List.of(
            "/data_mirror",
            "/data_mirror/data_ce",
            "/data_mirror/data_ce/null",
            "/data_mirror/data_ce/null/0",
            "/mnt/expand/fixture/user/0"))
      assertTrue(root, DeviceShellPolicy.smsProviderDescendant(root));
  }
}
