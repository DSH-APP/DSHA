package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DeviceSupportReportTest {
  @Before
  public void chinese() {
    UiText.setLanguage("zh");
  }

  @After
  public void restore() {
    UiText.setLanguage("zh");
  }

  private DeviceSupportReport report(
      int sdk, String[] abis, String manufacturer, String pkg, String version) {
    return DeviceSupportReport.from(
        sdk, abis, manufacturer, "", "Model", "", pkg, version, "0.2.1-rc.3", 243);
  }

  @Test
  public void android10BlocksDeploy() {
    DeviceSupportReport r =
        report(29, new String[] {"arm64-v8a"}, "Xiaomi", "com.google.android.webview", "141.0.0.0");
    assertEquals(DeviceSupportReport.Gate.BLOCK, r.gate);
    assertTrue(r.blocksDeploy());
    assertTrue(r.conclusion().contains("无法部署"));
    assertTrue(r.hint().contains("Android 10"));
    assertFalse(r.openWebViewSettings);
  }

  @Test
  public void nonArm64BlocksDeploy() {
    DeviceSupportReport r =
        report(
            34, new String[] {"armeabi-v7a"}, "Google", "com.google.android.webview", "141.0.0.0");
    assertEquals(DeviceSupportReport.Gate.BLOCK, r.gate);
    assertTrue(r.hint().contains("armeabi-v7a"));
  }

  @Test
  public void harmonyIsRiskNotBlock() {
    DeviceSupportReport r =
        DeviceSupportReport.from(
            34,
            new String[] {"arm64-v8a"},
            "HUAWEI",
            "",
            "ALN-AL00",
            "HarmonyOS 4.2",
            "com.google.android.webview",
            "120.0.0.0",
            "0.2.1-rc.3",
            243);
    assertEquals(DeviceSupportReport.Gate.RISK, r.gate);
    assertFalse(r.blocksDeploy());
    assertTrue(r.conclusion().contains("鸿蒙"));
    UiText.setLanguage("en");
    DeviceSupportReport en =
        DeviceSupportReport.from(
            34,
            new String[] {"arm64-v8a"},
            "HUAWEI",
            "",
            "ALN-AL00",
            "HarmonyOS 4.2",
            "com.google.android.webview",
            "120.0.0.0",
            "0.2.1-rc.3",
            243);
    assertTrue(en.hint().contains("HarmonyOS"));
    assertFalse(
        en.hint().toLowerCase().contains("not planned")
            && en.conclusion().contains("cannot deploy"));
  }

  @Test
  public void huaweiWithGoogleWebViewIsOk() {
    DeviceSupportReport r =
        report(34, new String[] {"arm64-v8a"}, "HUAWEI", "com.google.android.webview", "141.0.0.0");
    assertEquals(DeviceSupportReport.Gate.OK, r.gate);
  }

  @Test
  public void honorVendorWebViewIsRisk() {
    DeviceSupportReport r =
        report(34, new String[] {"arm64-v8a"}, "HONOR", "com.hihonor.webview", "12.0.0");
    assertEquals(DeviceSupportReport.Gate.RISK, r.gate);
    assertTrue(r.openWebViewSettings);
    assertTrue(r.webViewLabel.contains("荣耀") || r.webViewLabel.toLowerCase().contains("honor"));
    assertTrue(r.copyText.contains("com.hihonor.webview"));
  }

  @Test
  public void missingWebViewIsAdvise() {
    DeviceSupportReport r = report(34, new String[] {"arm64-v8a"}, "Google", "", "");
    assertEquals(DeviceSupportReport.Gate.ADVISE, r.gate);
    assertFalse(r.blocksDeploy());
    assertEquals("系统未提供版本信息", r.webViewLabel);
  }

  @Test
  public void oldGoogleWebViewIsAdvise() {
    DeviceSupportReport r =
        report(30, new String[] {"arm64-v8a"}, "Xiaomi", "com.google.android.webview", "91.0.4472");
    assertEquals(DeviceSupportReport.Gate.ADVISE, r.gate);
    assertTrue(r.openWebViewSettings);
  }

  @Test
  public void currentGoogleWebViewIsOk() {
    DeviceSupportReport r =
        report(
            34,
            new String[] {"arm64-v8a", "armeabi-v7a"},
            "OnePlus",
            "com.google.android.webview",
            "141.0.7390.122");
    assertEquals(DeviceSupportReport.Gate.OK, r.gate);
    assertFalse(r.showHint());
    assertTrue(r.copyText.contains("API 34"));
    assertTrue(r.copyText.contains("arm64-v8a"));
    assertTrue(r.copyText.contains("141.0.7390.122"));
  }

  @Test
  public void displayHarmonyTokenCountsAsRisk() {
    DeviceSupportReport r =
        DeviceSupportReport.from(
            34,
            new String[] {"arm64-v8a"},
            "unknown",
            "phone",
            "X",
            "HarmonyOS 4.2",
            "com.google.android.webview",
            "120.0.0.0",
            "v",
            1);
    assertEquals(DeviceSupportReport.Gate.RISK, r.gate);
  }
}
