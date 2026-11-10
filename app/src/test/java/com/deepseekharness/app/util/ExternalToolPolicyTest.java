package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Test;

public final class ExternalToolPolicyTest {
  // The policy intentionally accepts Android/POSIX argv. Keep real fixture files on the JVM's
  // current drive so slash-rooted argv resolves to the same files on Windows, too.
  @org.junit.Rule
  public org.junit.rules.TemporaryFolder temp =
      new org.junit.rules.TemporaryFolder(new java.io.File(System.getProperty("user.dir")));

  private java.io.File toolRoot;
  private String toolPath;

  @org.junit.Before
  public void createToolRoot() throws Exception {
    toolRoot = temp.newFolder("rootfs");
    toolPath = posixPath(toolRoot);
  }

  private static String posixPath(java.io.File file) throws java.io.IOException {
    String path = file.getAbsolutePath().replace(java.io.File.separatorChar, '/');
    if (java.io.File.separatorChar == '\\') path = path.substring(2);
    assertEquals(
        "argv must resolve to the actual fixture",
        file.getCanonicalFile(),
        new java.io.File(path).getCanonicalFile());
    return path;
  }

  private List<String> toolPaths(List<String> argv) {
    var resolved = new java.util.ArrayList<>(argv);
    for (int i : new int[] {0, 2, 3})
      resolved.set(i, resolved.get(i).replace("/root/", toolPath + "/"));
    return resolved;
  }

  private String rejection(List<String> argv) {
    return rejection(argv, null);
  }

  private String rejection(List<String> argv, String importRoot) {
    return ExternalToolPolicy.rejection(toolPaths(argv), toolRoot.getPath(), importRoot);
  }

  private String rejectionAdbServer(List<String> argv) {
    return ExternalToolPolicy.rejectionAdbServer(toolPaths(argv), toolRoot.getPath());
  }

  @Test
  public void siblingToolAndLibraryPathsRemainOutsideRoot() throws Exception {
    String sibling = posixPath(temp.newFolder("rootfs-other"));
    for (int index : new int[] {0, 3}) {
      var argv = toolPaths(adb("devices"));
      argv.set(index, argv.get(index).replace(toolPath + "/", sibling + "/"));
      assertEquals(
          "EXTERNAL_TOOL_OUTSIDE_ROOT", ExternalToolPolicy.rejection(argv, toolRoot.getPath()));
    }
    // 先解析工具路径再放入兄弟目录：临时目录可能位于 /root 下，不能再经过 toolPaths 的前缀替换。
    var library = toolPaths(adb("devices"));
    library.set(2, sibling + "/lib");
    assertEquals(
        "EXTERNAL_LIBRARY_OUTSIDE_ROOT", ExternalToolPolicy.rejection(library, toolRoot.getPath()));
  }

  private static List<String> adb(String... tail) {
    var value =
        new java.util.ArrayList<String>(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "/root/lib",
                "/root/usr/bin/adb"));
    java.util.Collections.addAll(value, tail);
    return value;
  }

  private static List<String> fastboot(String... tail) {
    var value =
        new java.util.ArrayList<String>(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "/root/lib",
                "/root/usr/bin/fastboot",
                "-s",
                "serial"));
    java.util.Collections.addAll(value, tail);
    return value;
  }

  @Test
  public void privilegedArgvEnforcesTheSameReadOnlyDisplayAndLogGrammar() {
    java.util.List<String> admitted = new java.util.ArrayList<>();
    for (String command :
        new String[] {
          "wm size 800x600",
          "wm density reset",
          "logcat -c",
          "logcat -dc",
          "logcat --clear",
          "logcat -G 4M",
          "logcat -G4M",
          "logcat -P 1000",
          "logcat --buffer-size=4M",
          "logcat -f /data/local/tmp/out",
          "logcat -v -c",
          "logcat -b main -c"
        })
      if (rejection(adb(("-s serial shell " + command).split(" "))) == null) admitted.add(command);
    assertEquals(java.util.List.of(), admitted);
    for (String command :
        new String[] {
          "wm size",
          "wm density",
          "logcat -d",
          "logcat -t 20",
          "logcat -b main",
          "logcat -v threadtime",
          "logcat -d Runtime:I"
        }) assertNull(command, rejection(adb(("-s serial shell " + command).split(" "))));
  }

  @Test
  public void externalObservationCommandsStayReadOnly() {
    // T134：watch 采样命令与动作后观察固定使用下面两种形状；改动它们前先看真机测量结论。
    assertNull(
        rejection(
            adb(
                ("-s serial shell logcat -d -b events -s wm_resume_activity:V"
                        + " wm_set_resumed_activity:V am_focused_activity:V am_resume_activity:V"
                        + " -v epoch")
                    .split(" "))));
    assertNull(rejection(adb(("-s serial shell dumpsys window").split(" "))));
    assertNull(rejection(adb(("-s serial shell getprop ro.product.device").split(" "))));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED",
        rejection(adb(("-s serial shell logcat -d -b events -c").split(" "))));
  }

  @Test
  public void onlyReadOnlyAdbShellCommandsPass() {
    assertNull(rejection(adb("devices", "-l")));
    assertNull(rejection(adb("-s", "serial", "shell", "getprop", "ro.product.device")));
    assertNull(rejection(adb("-s", "serial", "reboot", "bootloader")));
    assertEquals("EXTERNAL_ADB_REBOOT_ARGS", rejection(adb("-s", "serial", "reboot", "edl")));
    assertEquals("EXTERNAL_ADB_REBOOT_ARGS", rejection(adb("-s", "serial", "reboot", "sideload")));
    assertEquals(
        "EXTERNAL_ADB_OPERATION_BLOCKED", rejection(adb("-s", "serial", "sideload", "x.zip")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED", rejection(adb("-s", "serial", "shell", "dd", "if=x")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED",
        rejection(adb("-s", "serial", "shell", "pm", "install", "x.apk")));
  }

  @Test
  public void fastbootIsReadOnly() {
    assertNull(rejection(fastboot("getvar", "all")));
    assertEquals("EXTERNAL_FASTBOOT_WRITE_BLOCKED", rejection(fastboot("flash", "boot", "x.img")));
    assertEquals("EXTERNAL_FASTBOOT_WRITE_BLOCKED", rejection(fastboot("set_active", "a")));
    assertNull(rejection(fastboot("reboot")));
    assertNull(rejection(fastboot("reboot", "bootloader")));
    assertEquals("EXTERNAL_FASTBOOT_ARGS", rejection(fastboot("reboot", "edl")));
    for (String write :
        new String[] {"erase", "oem", "flashing", "boot", "update", "flashall", "-w"})
      assertEquals(write, "EXTERNAL_FASTBOOT_WRITE_BLOCKED", rejection(fastboot(write, "x")));
  }

  private static List<String> withLibrary(String library, String... tail) {
    var value =
        new java.util.ArrayList<String>(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1", "--library-path", library, "/root/usr/bin/adb"));
    java.util.Collections.addAll(value, tail);
    return value;
  }

  @Test
  public void emptyLibraryPathSegmentsAreRejected() {
    assertNull(rejection(withLibrary("/root/lib:/root/usr/lib", "devices")));
    for (String library :
        new String[] {"/root/lib:", ":/root/lib", "/root/lib::/root/usr/lib", ":"})
      assertEquals(
          library, "EXTERNAL_LIBRARY_OUTSIDE_ROOT", rejection(withLibrary(library, "devices")));
  }

  @Test
  public void adbServerOnlyAcceptsLoopbackNodaemon() {
    assertNull(rejectionAdbServer(adb("-P", "40123", "nodaemon", "server")));
    assertEquals(
        "EXTERNAL_ADB_SERVER_ARGS",
        rejectionAdbServer(adb("-a", "-P", "40123", "nodaemon", "server")));
    assertEquals(
        "EXTERNAL_ADB_SERVER_ARGS", rejectionAdbServer(adb("-P", "40123", "start-server")));
    assertEquals("EXTERNAL_ADB_OPERATION_BLOCKED", rejection(adb("-P", "40123", "kill-server")));
  }

  @Test
  public void adbSessionClientMustUseServerPortAndTools() {
    List<String> server = adb("-P", "40123", "nodaemon", "server");
    assertEquals(
        40123, ExternalToolPolicy.adbSessionPort(server, adb("-P", "40123", "devices", "-l")));
    assertEquals(
        -1, ExternalToolPolicy.adbSessionPort(server, adb("-P", "40124", "devices", "-l")));
    assertEquals(-1, ExternalToolPolicy.adbSessionPort(server, adb("devices", "-l", "x")));
    assertEquals(-1, ExternalToolPolicy.adbSessionPort(server, fastboot("getvar", "all", "x")));
    assertEquals(
        -1,
        ExternalToolPolicy.adbSessionPort(
            adb("-P", "80", "nodaemon", "server"), adb("-P", "80", "devices")));
    assertEquals(-1, ExternalToolPolicy.adbSessionPort(null, adb("-P", "40123", "devices")));
  }

  @Test
  public void wirelessConnectPairAndTargetedShell() {
    assertNull(rejection(adb("connect", "192.168.1.5:5555")));
    assertNull(rejection(adb("pair", "192.168.1.5:37001", "123456")));
    assertNull(rejection(adb("-s", "192.168.1.5:5555", "shell", "id")));
    assertNull(rejection(adb("-s", "192.168.1.5:5555", "wait-for-device")));
    assertEquals("EXTERNAL_ADB_CONNECT_ARGS", rejection(adb("connect", "127.0.0.1:5555")));
    assertEquals("EXTERNAL_ADB_CONNECT_ARGS", rejection(adb("-s", "s", "connect", "1.2.3.4:5")));
    assertEquals("EXTERNAL_ADB_PAIR_ARGS", rejection(adb("pair", "192.168.1.5:37001")));
    assertEquals("EXTERNAL_ADB_SERIAL", rejection(adb("-s", "127.0.0.1:5555", "shell", "id")));
    assertEquals(
        "EXTERNAL_FASTBOOT_SERIAL_REQUIRED",
        rejection(
            List.of(
                "/root/lib/ld-linux-aarch64.so.1",
                "--library-path",
                "/root/lib",
                "/root/usr/bin/fastboot",
                "-s",
                "192.168.1.5:5555",
                "getvar",
                "all")));
  }

  @Test
  public void installShapesRequireImportRootAndPlannedRemoteTargets() throws Exception {
    java.io.File imports = temp.newFolder("imports");
    java.io.File apk = new java.io.File(imports, "0123abcd-0000-4000-8000-000000000000-app.apk");
    java.nio.file.Files.write(apk.toPath(), new byte[] {1});
    String root = posixPath(imports);
    String local = posixPath(apk);
    assertNull(rejection(adb("-s", "s", "install", "-r", local), root));
    assertNull(rejection(adb("-s", "s", "install-multiple", "-r", local, local), root));
    assertNull(
        rejection(adb("-s", "s", "push", local, "/data/local/tmp/dsha_install/a000.apk"), root));
    assertNull(rejection(adb("-s", "s", "push", local, "/sdcard/app.apk"), root));
    // 没有导入区就不放行任何安装 / 推送
    assertEquals(
        "EXTERNAL_IMPORT_ROOT_REQUIRED", rejection(adb("-s", "s", "install", "-r", local)));
    // 导入区外的本地文件
    assertEquals(
        "EXTERNAL_ADB_INSTALL_SOURCE",
        rejection(adb("-s", "s", "install", "-r", "/etc/hosts.apk"), root));
    assertEquals(
        "EXTERNAL_ADB_INSTALL_SOURCE",
        rejection(adb("-s", "s", "install", "-r", root + "/../x.apk"), root));
    // 缺少 -r 或附加参数
    assertEquals("EXTERNAL_ADB_INSTALL_ARGS", rejection(adb("-s", "s", "install", local), root));
    assertEquals(
        "EXTERNAL_ADB_INSTALL_ARGS", rejection(adb("-s", "s", "install", "-r", "-g", local), root));
    // 推到计划之外的远端位置
    for (String remote :
        new String[] {
          "/system/app/x.apk",
          "/data/local/tmp/other.apk",
          "/sdcard/../data/x",
          "/sdcard/a/b",
          "/sdcard/.x"
        })
      assertEquals(
          remote,
          "EXTERNAL_ADB_PUSH_TARGET",
          rejection(adb("-s", "s", "push", local, remote), root));
    assertNull(
        rejection(
            adb(
                "-s",
                "s",
                "push",
                local,
                "/sdcard/Android/obb/com.example.game/main.1.com.example.game.obb"),
            root));
    assertEquals(
        "EXTERNAL_ADB_PUSH_TARGET",
        rejection(
            adb(
                "-s",
                "s",
                "push",
                local,
                "/sdcard/Android/obb/com.example.game/main.1.com.other.obb"),
            root));
  }

  @Test
  public void plannedRemoteShellPassesOnlyGeneratedForms() {
    assertNull(rejection(adb("-s", "s", "shell", "su -c 'pm install-commit 12'")));
    assertNull(rejection(adb("-s", "s", "shell", "mkdir -p '/data/local/tmp/dsha_install'")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED", rejection(adb("-s", "s", "shell", "su -c 'reboot'")));
    assertEquals(
        "EXTERNAL_ADB_SHELL_BLOCKED",
        rejection(adb("-s", "s", "shell", "rm -rf '/data/local/tmp'")));
  }
}
