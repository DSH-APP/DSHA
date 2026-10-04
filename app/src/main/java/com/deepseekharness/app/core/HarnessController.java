package com.deepseekharness.app.core;

import android.content.Context;
import android.util.Log;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.RuntimeHostPorts;
import java.io.IOException;
import java.util.function.Consumer;

/** Application entry point. Collaborators share its one application-owned Web session. */
public class HarnessController {
  private final Context context;
  private final ConfigStore config;
  private final ProotBootstrap proot;
  private final WebLifecycleController web;
  private final HostConfigService hostConfig;

  public HarnessController(Context context) {
    this(context, new ProotBootstrap(context));
  }

  HarnessController(Context context, ProotBootstrap bootstrap) {
    this.context = context.getApplicationContext();
    this.config = new ConfigStore(this.context);
    this.proot = bootstrap;
    HarnessSessionState session =
        this.context instanceof DshaApp
            ? ((DshaApp) this.context).harnessSession()
            : new HarnessSessionState();
    this.hostConfig = new HostConfigService(this.context, config);
    this.web = new WebLifecycleController(this, session);
  }

  /** Compatibility resolver; the application owns the service and there is no static registry. */
  public static HarnessController get(Context context) {
    return DshaApp.from(context).harnessController();
  }

  public Context context() {
    return context;
  }

  public ConfigStore config() {
    return config;
  }

  public ProotBootstrap proot() {
    return proot;
  }

  public ProotBootstrap getProot() {
    return proot;
  }

  public RuntimeHostPorts runtimeHostPorts() {
    return proot.hostPorts();
  }

  public boolean isEnvironmentReady() {
    return proot.isEnvironmentReady();
  }

  public boolean hasOfflineBundle() {
    return proot.hasOfflineBundle();
  }

  public boolean isWebRunning() {
    return web.isWebRunning();
  }

  public boolean isWebStoppedForMaintenance() throws IOException {
    return web.isWebStoppedForMaintenance();
  }

  public boolean hasLiveWebProcesses() {
    return web.hasLiveWebProcesses();
  }

  public String lastStopError() {
    return web.lastStopError();
  }

  public WebStopCoordinator.Result lastStopResult() {
    return web.lastStopResult();
  }

  public int getWebPort() {
    return web.getWebPort();
  }

  public String getWebAuthUrl() {
    return web.getWebAuthUrl();
  }

  public String getWebAuthFailure() {
    return web.getWebAuthFailure();
  }

  public String runCoreCommand() {
    return web.runCoreCommand();
  }

  public boolean startWeb(Consumer<String> status) {
    return web.startWeb(status);
  }

  public boolean startWebSafely(Consumer<String> status) {
    return web.startWebSafely(status);
  }

  public boolean restartWebAutomatically(long expectedGeneration, Consumer<String> status) {
    return web.restartWebAutomatically(expectedGeneration, status);
  }

  public boolean isWebCompatibilityFallback() {
    return web.isWebCompatibilityFallback();
  }

  public String exchangeDshAuthCookie() {
    return web.exchangeDshAuthCookie();
  }

  public void stopWeb() {
    web.stopWeb();
  }

  public void stopWeb(Consumer<String> status) {
    web.stopWeb(status);
  }

  /** Candidate held by one native confirmation; it cannot be reused after any new Web intent. */
  public record ForceStopRequest(
      long generation,
      com.deepseekharness.app.runtime.WebProcessManager.ForceCandidate candidate) {}

  public ForceStopRequest prepareForceStop() throws IOException {
    return web.prepareForceStop();
  }

  public boolean forceStop(ForceStopRequest request, Consumer<String> status) {
    return web.forceStop(request, status);
  }

  public boolean isStarting() {
    return web.isStarting();
  }

  public boolean isStopping() {
    return web.isStopping();
  }

  public boolean isUserStopped() {
    return web.isUserStopped();
  }

  public boolean canAutoRestart() {
    return web.canAutoRestart();
  }

  public boolean isRestartBlocked() {
    return web.isRestartBlocked();
  }

  public void reportWebHealth(long generation, boolean healthy) {
    web.reportWebHealth(generation, healthy);
  }

  public StartupDiagnostics startupDiagnostics() {
    return web.startupDiagnostics();
  }

  public void recoverWeb(boolean safe, String disabledPlugin, Consumer<String> status) {
    web.recoverWeb(safe, disabledPlugin, status);
  }

  public void failedWebPage(long generation, String reason) {
    web.failedWebPage(generation, reason);
  }

  void reportSlowStart(long generation, Consumer<String> status) {
    web.reportSlowStart(generation, status);
  }

  public long getWebGeneration() {
    return web.getWebGeneration();
  }

  public void resetExtraction() {
    proot.markNotExtracted();
  }

  public String resetConfig() throws IOException {
    return hostConfig.reset();
  }

  com.deepseekharness.app.backup.HostDataTransaction.Settings nativeSettingsTransaction() {
    return hostConfig.settings();
  }

  public String smokeTest() {
    return proot.smokeTest();
  }

  public static String fmtBytes(long bytes) {
    return com.deepseekharness.app.util.Fmt.bytes(bytes);
  }

  public void logActivity(String value) {
    Log.i("DSHA", value == null ? "" : value);
  }

  /** Reads packaged text, closes the source, and normalizes only its line endings. */
  public String readAsset(String name) {
    try {
      return readAssetText(context.getAssets().open(name));
    } catch (Exception e) {
      String reason =
          com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e.getMessage()));
      if (reason.length() > 200) reason = reason.substring(0, 200);
      Log.w(
          "DSHA",
          "Packaged asset read failed: "
              + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(name))
              + " ("
              + e.getClass().getSimpleName()
              + ": "
              + reason
              + ")");
      return "";
    }
  }

  static String readAssetText(java.io.InputStream source) throws IOException {
    try (java.io.InputStream in = source;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      // 资产在 Windows 检出时可能是 CRLF，注入容器后脚本认不了 \r → 统一转 LF
      return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
    }
  }

  /** Detects the private LAN IPv4 address used by the sharing entry point. */
  public static String getLanAddress() {
    try {
      String fallback = null;
      java.util.Enumeration<java.net.NetworkInterface> nis =
          java.net.NetworkInterface.getNetworkInterfaces();
      while (nis != null && nis.hasMoreElements()) {
        java.net.NetworkInterface ni = nis.nextElement();
        if (!ni.isUp() || ni.isLoopback()) continue;
        String ifName = ni.getName() == null ? "" : ni.getName();
        boolean wifiLike =
            ifName.startsWith("wlan")
                || ifName.startsWith("eth")
                || ifName.startsWith("radio")
                || ifName.startsWith("wifi");
        java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
        while (as.hasMoreElements()) {
          java.net.InetAddress a = as.nextElement();
          if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
          String ip = a.getHostAddress();
          if (ip != null && com.deepseekharness.app.util.LanAddressPolicy.privateIpv4(ip)) {
            if (fallback == null) fallback = ip;
            if (wifiLike) return ip; // 目标网卡命中，直接返回
          }
        }
      }
      return fallback;
    } catch (Exception ignored) {
    }
    return null;
  }
}
