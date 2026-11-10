package com.deepseekharness.app.util;

/**
 * 首次引导与维护失败页共用的本机支持结论。只根据 ABI / API / 厂商 / WebView 包名版本做分档，
 * 不访问 Android API，也不把鸿蒙标成“已确认不支持”。
 */
public final class DeviceSupportReport {
  public enum Gate {
    BLOCK,
    RISK,
    ADVISE,
    OK
  }

  public final Gate gate;
  public final String conclusionZh;
  public final String conclusionEn;
  public final String hintZh;
  public final String hintEn;
  public final String webViewLabel;
  public final String copyText;
  public final boolean openWebViewSettings;

  DeviceSupportReport(
      Gate gate,
      String conclusionZh,
      String conclusionEn,
      String hintZh,
      String hintEn,
      String webViewLabel,
      String copyText,
      boolean openWebViewSettings) {
    this.gate = gate;
    this.conclusionZh = conclusionZh;
    this.conclusionEn = conclusionEn;
    this.hintZh = hintZh;
    this.hintEn = hintEn;
    this.webViewLabel = webViewLabel;
    this.copyText = copyText;
    this.openWebViewSettings = openWebViewSettings;
  }

  public String conclusion() {
    return UiText.choose(conclusionZh, conclusionEn);
  }

  public String hint() {
    return UiText.choose(hintZh, hintEn);
  }

  public boolean blocksDeploy() {
    return gate == Gate.BLOCK;
  }

  public boolean showHint() {
    return gate != Gate.OK;
  }

  public static DeviceSupportReport from(
      int sdk,
      String[] abis,
      String manufacturer,
      String brand,
      String model,
      String display,
      String webViewPackage,
      String webViewVersion,
      String appVersion,
      int appCode) {
    boolean arm64 = hasArm64(abis);
    String abi = primaryAbi(abis);
    String vendor = firstNonBlank(manufacturer, brand);
    String webLabel = webViewLabel(webViewPackage, webViewVersion);
    String identity =
        "DSHA "
            + blank(appVersion)
            + " / "
            + appCode
            + "\nAndroid "
            + sdkLabel(sdk)
            + " (API "
            + Math.max(sdk, 0)
            + ")\n"
            + blank(vendor)
            + " "
            + blank(model)
            + "\nABI "
            + abi
            + "\nWebView "
            + webLabel
            // 格子只显示短标签；复制给支持的文本保留完整包名，便于区分厂商内核。
            + (isBlank(webViewPackage) ? "" : " (" + webViewPackage.trim() + ")");
    if (!isBlank(display)) identity += "\n" + display.trim();

    if (sdk > 0 && sdk < 30) {
      return new DeviceSupportReport(
          Gate.BLOCK,
          "低于 Android 11，无法部署",
          "Below Android 11; cannot deploy",
          "当前是 Android " + sdkLabel(sdk) + "。标准版已取消 Android 10 及以下和兼容内核，无法在本机部署。",
          "This device is Android "
              + sdkLabel(sdk)
              + ". The Standard build no longer supports Android 10 or older, and has no compatibility engine.",
          webLabel,
          identity,
          false);
    }
    if (!arm64) {
      return new DeviceSupportReport(
          Gate.BLOCK,
          "不是 64 位 ARM，无法部署",
          "Not 64-bit ARM; cannot deploy",
          "标准版只提供 arm64-v8a。当前架构是 " + abi + "，无法在本机部署。",
          "The Standard build ships only arm64-v8a. This device reports "
              + abi
              + " and cannot be deployed.",
          webLabel,
          identity,
          false);
    }

    boolean harmony = looksHarmony(manufacturer, brand, display);
    boolean vendorWebView = vendorWebView(webViewPackage);
    boolean missingWebView = isBlank(webViewPackage) && isBlank(webViewVersion);
    boolean oldWebView = oldGoogleWebView(webViewPackage, webViewVersion);

    if (harmony) {
      return new DeviceSupportReport(
          Gate.RISK,
          "鸿蒙未适配",
          "HarmonyOS is not adapted",
          "标准版未适配鸿蒙。若本机仍是 Android 兼容层，可继续部署，但网页内核和更新运行时可能不可用；纯血鸿蒙没有计划。",
          "The Standard build is not adapted for HarmonyOS. If this device still has an Android compatibility layer you may continue, but the browser engine and runtime updates may fail. HarmonyOS NEXT is not planned.",
          webLabel,
          identity,
          vendorWebView || missingWebView || oldWebView);
    }
    if (vendorWebView) {
      return new DeviceSupportReport(
          Gate.RISK,
          "请先更新系统 WebView",
          "Update System WebView first",
          "当前网页内核是 "
              + webLabel
              + "。荣耀/华为自研内核上，网页确认和更新运行时容易卡住。请先到系统设置更新「Android System WebView」（有 Play 商店则优先更新它），然后再部署。",
          "The current browser engine is "
              + webLabel
              + ". Honor/Huawei WebView often stalls page confirmation and runtime updates. Update Android System WebView in system settings first (prefer Play Store if present), then deploy.",
          webLabel,
          identity,
          true);
    }
    if (missingWebView) {
      return new DeviceSupportReport(
          Gate.ADVISE,
          "系统未提供 WebView 版本",
          "System did not provide a WebView version",
          "读不到系统 WebView 包名或版本。部署可以继续，但网页确认可能失败。请确认已安装并启用 Android System WebView 或 Chrome。",
          "The system did not report a WebView package or version. Deployment can continue, but page confirmation may fail. Install and enable Android System WebView or Chrome.",
          webLabel,
          identity,
          true);
    }
    if (oldWebView) {
      return new DeviceSupportReport(
          Gate.ADVISE,
          "建议更新 WebView",
          "Update WebView",
          "当前网页内核是 " + webLabel + "。版本偏旧时，隔离试运行的网页确认容易超时。建议先更新 Android System WebView 或 Chrome。",
          "The current browser engine is "
              + webLabel
              + ". Older versions often time out during isolated runtime confirmation. Update Android System WebView or Chrome first.",
          webLabel,
          identity,
          true);
    }
    return new DeviceSupportReport(Gate.OK, "可用", "Ready", "", "", webLabel, identity, false);
  }

  static boolean hasArm64(String[] abis) {
    if (abis == null) return false;
    for (String abi : abis)
      if (abi != null && abi.toLowerCase(java.util.Locale.ROOT).contains("arm64")) return true;
    return false;
  }

  static String primaryAbi(String[] abis) {
    if (abis == null) return "unknown";
    for (String abi : abis) if (!isBlank(abi)) return abi.trim();
    return "unknown";
  }

  static boolean looksHarmony(String manufacturer, String brand, String display) {
    String blob =
        (blank(manufacturer) + " " + blank(brand) + " " + blank(display))
            .toLowerCase(java.util.Locale.ROOT);
    return blob.contains("harmony") || blob.contains("hongmeng") || blob.contains("openharmony");
  }

  static boolean vendorWebView(String webViewPackage) {
    String pkg = blank(webViewPackage).toLowerCase(java.util.Locale.ROOT);
    if (pkg.isEmpty()) return false;
    if (pkg.contains("huawei") || pkg.contains("honor") || pkg.contains("hihonor")) return true;
    return pkg.contains("webview") && (pkg.contains("hw") || pkg.startsWith("com.hihonor"));
  }

  static boolean oldGoogleWebView(String webViewPackage, String webViewVersion) {
    String pkg = blank(webViewPackage).toLowerCase(java.util.Locale.ROOT);
    if (!(pkg.contains("android.webview")
        || pkg.contains("google.android.webview")
        || pkg.equals("com.android.chrome"))) return false;
    int major = majorVersion(webViewVersion);
    return major > 0 && major < 100;
  }

  static int majorVersion(String version) {
    if (isBlank(version)) return 0;
    StringBuilder digits = new StringBuilder();
    for (int i = 0; i < version.length(); i++) {
      char c = version.charAt(i);
      if (c >= '0' && c <= '9') digits.append(c);
      else if (digits.length() > 0) break;
    }
    if (digits.length() == 0) return 0;
    try {
      return Integer.parseInt(digits.toString());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  static String webViewLabel(String pkg, String version) {
    if (isBlank(pkg) && isBlank(version)) return "系统未提供版本信息";
    String name = shortWebViewName(pkg);
    if (isBlank(version)) return isBlank(name) ? pkg.trim() : name;
    if (isBlank(name)) return version.trim();
    return name + " " + version.trim();
  }

  static String shortWebViewName(String pkg) {
    String p = blank(pkg).toLowerCase(java.util.Locale.ROOT);
    if (p.isEmpty()) return "";
    if (p.contains("huawei")) return "华为 WebView";
    if (p.contains("hihonor") || p.contains("honor")) return "荣耀 WebView";
    if (p.equals("com.android.chrome") || p.contains("chrome")) return "Chrome";
    if (p.contains("android.webview") || p.contains("google.android.webview")) return "系统 WebView";
    return pkg.trim();
  }

  private static String sdkLabel(int sdk) {
    if (sdk <= 0) return "?";
    if (sdk == 29) return "10";
    if (sdk == 30) return "11";
    if (sdk == 31 || sdk == 32) return "12";
    if (sdk == 33) return "13";
    if (sdk == 34) return "14";
    if (sdk == 35) return "15";
    if (sdk == 36) return "16";
    return String.valueOf(sdk);
  }

  private static String firstNonBlank(String a, String b) {
    if (!isBlank(a)) return a.trim();
    if (!isBlank(b)) return b.trim();
    return "";
  }

  private static String blank(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
