package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DshaCliPresetTest {
  @Test
  public void fixedArgvRunsBashAndEndsInLoginShell() {
    assertEquals("DSH CLI", DshaCliPreset.TITLE);
    for (String language : new String[] {"zh", "zh-Hant", "en", "fr", null}) {
      String[] argv = DshaCliPreset.guestCommand(language);
      assertEquals(3, argv.length);
      assertEquals("/bin/bash", argv[0]);
      assertEquals("-c", argv[1]);
      assertTrue(argv[2].startsWith("stty sane 2>/dev/null || stty echo icanon"));
      assertTrue(argv[2].endsWith("\nexec /bin/bash -l"));
      // 固定预设不插值用户输入，也不写凭据。
      assertFalse(argv[2].contains("$1"));
      assertFalse(argv[2].contains("$@"));
      assertFalse(argv[2].contains("DSH_HOME="));
      assertFalse(argv[2].contains("DSH_PERMISSION_MODE="));
      assertFalse(argv[2].contains("exec dsh "));
    }
  }

  @Test
  public void bannerExplainsNoInteractiveTuiAndCredentialBoundary() {
    String zh = DshaCliPreset.guestCommand("zh")[2];
    assertTrue(zh.contains("没有交互式 dsh TUI"));
    assertTrue(zh.contains("headless"));
    assertTrue(zh.contains("默认不读取 GUI 保存的凭据"));
    assertTrue(zh.contains("dsh web"));
    assertTrue(zh.contains("dsh --help"));
    String en = DshaCliPreset.guestCommand("en")[2];
    assertTrue(en.contains("no interactive dsh TUI"));
    assertTrue(en.contains("headless does not read GUI-saved credentials"));
    assertTrue(en.contains("dsh web"));
    // 未知语言回退英文，避免输出空 banner。
    assertEquals(en, DshaCliPreset.guestCommand("fr")[2]);
    assertEquals(en, DshaCliPreset.guestCommand(null)[2]);
  }

  @Test
  public void unavailableDshIsReportedInsideThePrecheckWithoutFakingSuccess() {
    String zh = DshaCliPreset.guestCommand("zh")[2];
    assertTrue(zh.contains("if _dsha_cli_version=$(dsh --version 2>/dev/null); then"));
    int otherwise = zh.indexOf("\nelse\n");
    int unavailable = zh.indexOf("dsh 不可用");
    int end = zh.indexOf("\nfi\n");
    assertTrue(otherwise >= 0 && unavailable > otherwise && end > unavailable);
    int precheck = zh.indexOf("; then\n");
    assertTrue(precheck >= 0);
    // 预检只在 if 分支打印版本；失败分支不得打印伪版本或以失败码退出。
    int versionLine = zh.indexOf("printf 'DSH CLI · dsh %s\\n'");
    assertTrue(versionLine > precheck && versionLine < otherwise);
    assertTrue(zh.contains("当前仍是普通 shell"));
    assertTrue(zh.endsWith("exec /bin/bash -l"));
  }
}
