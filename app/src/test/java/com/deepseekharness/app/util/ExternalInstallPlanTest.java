package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.ExternalInstallPlan.Kind;
import com.deepseekharness.app.util.ExternalInstallPlan.RootManager;
import com.deepseekharness.app.util.ExternalInstallPlan.Step;
import java.util.List;
import org.junit.Test;

/** SuperADB 式安装计划：argv 形状、恒带 -s、远端转义与非法输入拒绝。 */
public class ExternalInstallPlanTest {

  private static final String S = "0123abcd";

  @Test
  public void kindsFollowSuperAdb() {
    assertEquals(Kind.APK, ExternalInstallPlan.kindOf("a.APK", null));
    assertEquals(Kind.SPLIT_BUNDLE, ExternalInstallPlan.kindOf("a.apks", null));
    assertEquals(Kind.XAPK, ExternalInstallPlan.kindOf("a.xapk", null));
    assertEquals(Kind.AAB, ExternalInstallPlan.kindOf("a.aab", null));
    assertEquals(Kind.MODULE_ZIP, ExternalInstallPlan.kindOf("m.zip", List.of("module.prop", "x")));
    assertEquals(Kind.PLAIN_ZIP, ExternalInstallPlan.kindOf("m.zip", List.of("sub/module.prop")));
    assertEquals(Kind.UNKNOWN, ExternalInstallPlan.kindOf(null, null));
  }

  @Test
  public void detectOrderMagiskThenKsuThenApatch() {
    assertEquals(RootManager.MAGISK, ExternalInstallPlan.detect(true, true, true));
    assertEquals(RootManager.KERNELSU, ExternalInstallPlan.detect(false, true, true));
    assertEquals(RootManager.APATCH, ExternalInstallPlan.detect(false, false, true));
    assertEquals(RootManager.NONE, ExternalInstallPlan.detect(false, false, false));
    assertEquals(
        List.of("adb", "-s", S, "shell", "su -c 'ksud -V'"),
        ExternalInstallPlan.detectCommands(S).get(2));
  }

  @Test
  public void apkWithoutRootIsAdbInstall() {
    List<Step> s = ExternalInstallPlan.apk(S, "/tmp/x.apk", false);
    assertEquals(List.of("adb", "-s", S, "install", "-r", "/tmp/x.apk"), s.get(0).argv());
  }

  @Test
  public void apkWithRootPushesThenPmInstalls() {
    List<Step> s = ExternalInstallPlan.apk(S, "/tmp/x.apk", true);
    assertEquals(
        List.of("adb", "-s", S, "push", "/tmp/x.apk", "/data/local/tmp/dsha_install/a000.apk"),
        s.get(1).argv());
    assertEquals(
        "su -c 'pm install -r '\\''/data/local/tmp/dsha_install/a000.apk'\\'''",
        s.get(2).argv().get(4));
  }

  @Test
  public void rootSessionFlow() {
    assertEquals(
        1234L, ExternalInstallPlan.parseSessionId("Success: created install session [1234]\n"));
    assertEquals(-1L, ExternalInstallPlan.parseSessionId("Failure [INSTALL_FAILED]"));
    assertEquals(-1L, ExternalInstallPlan.parseSessionId(null));
    List<Step> prep = ExternalInstallPlan.sessionPrepare(S, List.of("/a/base.apk", "/a/s.apk"), 99);
    assertEquals("su -c 'pm install-create -r -S 99'", prep.get(prep.size() - 1).argv().get(4));
    List<Step> s = ExternalInstallPlan.sessionSteps(S, 1234, new long[] {60, 39});
    assertTrue(s.get(1).argv().get(4).startsWith("su -c 'pm install-write -S 39 1234 a001.apk "));
    assertEquals("su -c 'pm install-commit 1234'", s.get(2).argv().get(4));
    assertEquals(
        "su -c 'pm install-abandon 1234'",
        ExternalInstallPlan.sessionAbandon(S, 1234).argv().get(4));
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalInstallPlan.sessionSteps(S, -1, new long[] {1}));
  }

  @Test
  public void splitsWithoutRootUseInstallMultiple() {
    assertEquals(
        List.of("adb", "-s", S, "install-multiple", "-r", "/a/base.apk", "/a/s.apk"),
        ExternalInstallPlan.splitsWithoutRoot(S, List.of("/a/base.apk", "/a/s.apk")).get(0).argv());
  }

  @Test
  public void modulePerManagerAndL2() {
    List<Step> m = ExternalInstallPlan.module(S, "/a/mod.zip", RootManager.MAGISK);
    Step install = m.get(2);
    assertEquals("L2", install.level());
    assertTrue(install.argv().get(4).startsWith("su -c 'magisk --install-module "));
    assertTrue(
        ExternalInstallPlan.module(S, "/a/mod.zip", RootManager.KERNELSU)
            .get(2)
            .argv()
            .get(4)
            .startsWith("su -c 'ksud module install "));
    assertTrue(
        ExternalInstallPlan.module(S, "/a/mod.zip", RootManager.APATCH)
            .get(2)
            .argv()
            .get(4)
            .startsWith("su -c 'apd module install "));
    assertEquals(
        List.of("adb", "-s", S, "push", "/a/mod.zip", "/sdcard/mod.zip"),
        ExternalInstallPlan.module(S, "/a/mod.zip", RootManager.NONE).get(0).argv());
  }

  @Test
  public void obbGoesUnderPackageDir() {
    List<Step> s =
        ExternalInstallPlan.obb(S, "com.example.game", "/a/x.obb", "main.12.com.example.game.obb");
    assertEquals(
        List.of(
            "adb",
            "-s",
            S,
            "push",
            "/a/x.obb",
            "/sdcard/Android/obb/com.example.game/main.12.com.example.game.obb"),
        s.get(1).argv());
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalInstallPlan.obb(S, "com.x;rm", "/a/x.obb", "main.1.com.x.obb"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalInstallPlan.obb(S, "com.x.y", "/a/x.obb", "../main.1.obb"));
  }

  @Test
  public void rejectsBadSerialAndPaths() {
    for (String bad : new String[] {null, "", "-a", "a b", "a;b", "x$(id)"})
      assertThrows(
          IllegalArgumentException.class, () -> ExternalInstallPlan.apk(bad, "/a.apk", false));
    for (String bad : new String[] {null, "rel.apk", "/a/../b.apk", "/a\n.apk"})
      assertThrows(IllegalArgumentException.class, () -> ExternalInstallPlan.apk(S, bad, false));
    assertThrows(
        IllegalArgumentException.class, () -> ExternalInstallPlan.plainZip(S, "/a/.hidden.zip"));
    // 无线调试目标 host:port 允许用于 L1
    assertEquals(
        "192.168.1.5:5555",
        ExternalInstallPlan.apk("192.168.1.5:5555", "/a.apk", false).get(0).argv().get(2));
  }

  /** 计划生成的每条远端 shell 与推送目标都必须通过执行器的反向核验。 */
  @Test
  public void everyGeneratedStepPassesExecutorCheck() {
    List<List<Step>> plans =
        List.of(
            ExternalInstallPlan.apk(S, "/a/x.apk", true),
            ExternalInstallPlan.sessionPrepare(S, List.of("/a/b.apk", "/a/c.apk"), 9),
            ExternalInstallPlan.sessionSteps(S, 77, new long[] {4, 5}),
            List.of(ExternalInstallPlan.sessionAbandon(S, 77)),
            ExternalInstallPlan.obb(
                S, "com.example.game", "/a/x.obb", "main.3.com.example.game.obb"),
            ExternalInstallPlan.module(S, "/a/m.zip", RootManager.MAGISK),
            ExternalInstallPlan.module(S, "/a/m.zip", RootManager.KERNELSU),
            ExternalInstallPlan.module(S, "/a/m.zip", RootManager.APATCH),
            ExternalInstallPlan.module(
                S, "/a/0123abcd-0000-4000-8000-000000000000-m.zip", RootManager.NONE),
            ExternalInstallPlan.plainZip(S, "/a/0123abcd-0000-4000-8000-000000000000-z.zip"));
    for (List<Step> plan : plans)
      for (Step step : plan) {
        List<String> argv = step.argv();
        if (argv.get(3).equals("shell"))
          assertTrue(argv.get(4), ExternalInstallPlan.plannedRemoteShell(argv.get(4)));
        if (argv.get(3).equals("push"))
          assertTrue(argv.get(5), ExternalInstallPlan.plannedRemotePush(argv.get(5)));
      }
    for (List<String> probe : ExternalInstallPlan.detectCommands(S))
      assertTrue(probe.get(4), ExternalInstallPlan.plannedRemoteShell(probe.get(4)));
    // UUID 前缀不出现在 /sdcard 文件名里
    assertEquals(
        "/sdcard/z.zip",
        ExternalInstallPlan.plainZip(S, "/a/0123abcd-0000-4000-8000-000000000000-z.zip")
            .get(0)
            .argv()
            .get(5));
  }

  @Test
  public void tamperedRemoteShellIsRejected() {
    String q = "'\\''";
    for (String bad :
        new String[] {
          "su -c 'pm install-commit 1; reboot'",
          "su -c 'pm install-commit 1'; reboot",
          "su -c 'magisk --install-module " + q + "/sdcard/evil.zip" + q + "'",
          "su -c 'pm install-write -S 4 7 a001.apk "
              + q
              + "/data/local/tmp/dsha_install/a002.apk"
              + q
              + "'",
          "su -c 'pm uninstall com.android.systemui'",
          "su -c 'id -u' ",
          "su  -c 'id -u'",
          "rm -rf '/'",
          "mkdir -p '/sdcard/Android/obb/com.x;id'",
          "sh -c 'id'",
          ""
        }) assertFalse(bad, ExternalInstallPlan.plannedRemoteShell(bad));
    assertTrue(
        ExternalInstallPlan.plannedRemoteShell(
            "su -c 'pm install-write -S 4 7 a001.apk "
                + q
                + "/data/local/tmp/dsha_install/a001.apk"
                + q
                + "'"));
    assertFalse(ExternalInstallPlan.plannedRemoteShell(null));
    assertFalse(ExternalInstallPlan.plannedRemotePush("/sdcard/Android/obb/com.x/../../x"));
  }

  @Test
  public void bundleEntriesAndXapkObb() {
    assertEquals(
        List.of("splits/base-master.apk", "splits/base-arm64_v8a.apk"),
        ExternalInstallPlan.bundleApkEntries(
            List.of(
                "toc.pb",
                "standalones/standalone-arm64.apk",
                "splits/base-arm64_v8a.apk",
                "splits/base-master.apk")));
    assertEquals(
        List.of("base.apk", "config.arm64_v8a.apk"),
        ExternalInstallPlan.bundleApkEntries(
            List.of(
                "manifest.json",
                "config.arm64_v8a.apk",
                "base.apk",
                "icon.png",
                "Android/obb/x/y.apk")));
    assertEquals(List.of(), ExternalInstallPlan.bundleApkEntries(List.of("../evil.apk")));
    List<String> many = new java.util.ArrayList<>();
    for (int i = 0; i <= ExternalInstallPlan.MAX_SPLITS; i++) many.add("s" + i + ".apk");
    assertEquals(List.of(), ExternalInstallPlan.bundleApkEntries(many));
    assertEquals(
        "com.example.game",
        ExternalInstallPlan.xapkObb("Android/obb/com.example.game/main.3.com.example.game.obb")[0]);
    assertEquals(
        null, ExternalInstallPlan.xapkObb("Android/obb/com.example.game/main.3.com.other.obb"));
    assertEquals(null, ExternalInstallPlan.xapkObb("Android/obb/../main.3.com.x.obb"));
  }
}
