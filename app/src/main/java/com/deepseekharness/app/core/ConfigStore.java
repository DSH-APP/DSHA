package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.deepseekharness.app.data.KeyVault;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.PreferenceValue;

/**
 * 配置的唯一读写入口：SharedPreferences + Keystore 加密的 API key。
 * 所有「设置」页的开关最终都落到这里，键名沿用历史值保证升级不丢。
 */
public class ConfigStore {

  private final SharedPreferences prefs;
  private final KeyVault vault;
  private static final Object COLD_SELECTION_LOCK = new Object();

  public ConfigStore(Context ctx) {
    this.prefs = ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    this.vault = new KeyVault(ctx);
    // 启动计数备份已移除；覆盖安装与旧备份恢复均不能重新启用。
    if (prefs.contains(Constants.KEY_AUTO_BACKUP) || prefs.contains("backup_launch_count"))
      prefs.edit().remove(Constants.KEY_AUTO_BACKUP).remove("backup_launch_count").apply();
    // 原生 DSH 权限档位已退休；覆盖安装后的历史选择不能继续影响正式 Web。
    if (prefs.contains(Constants.KEY_PERMISSION_MODE))
      prefs.edit().remove(Constants.KEY_PERMISSION_MODE).apply();
  }

  public boolean isWelcomed() {
    return flag(Constants.KEY_WELCOMED, false);
  }

  public void setWelcomed(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_WELCOMED, v).apply();
  }

  public boolean allowsLimitedEntry(String identity) {
    return identity != null
        && !identity.isEmpty()
        && identity.equals(text("limited_entry_identity", ""));
  }

  public void allowLimitedEntry(String identity) {
    prefs.edit().putString("limited_entry_identity", identity).apply();
  }

  public String getUiTheme() {
    return com.deepseekharness.app.util.UiThemePreference.normalize(text("ui_theme", "system"));
  }

  /**
   * 语言偏好：{@code system}（默认）或 UiLanguagePreference 中的十种规范语言标签。
   *
   * <p>从未设置过的用户走 {@code system}，由系统首选语言及中文脚本/地区决定生效语言；
   * 老版本写过 {@code zh}/{@code en} 的用户保持原选择，升级不改变语言。
   */
  public String getUiLanguagePreference() {
    return com.deepseekharness.app.util.UiLanguagePreference.normalize(text("ui_language", null));
  }

  /** 用户是否从未显式选择过语言（界面据此显示「跟随系统」）。 */
  public boolean hasChosenUiLanguage() {
    return com.deepseekharness.app.util.UiLanguagePreference.supported(text("ui_language", null));
  }

  /** 实际生效的规范 BCP-47 语言：显式选择优先，否则解析系统语言。 */
  public String getUiLanguage() {
    return com.deepseekharness.app.util.UiLanguagePreference.resolve(
        getUiLanguagePreference(), com.deepseekharness.app.util.SystemLanguage.tag());
  }

  public String getUiLanguageForBackup() {
    return getUiLanguagePreference();
  }

  public void setUiLanguage(String value) {
    prefs
        .edit()
        .putString(
            "ui_language", com.deepseekharness.app.util.UiLanguagePreference.normalize(value))
        .apply();
  }

  public void setUiTheme(String value) {
    prefs
        .edit()
        .putString("ui_theme", com.deepseekharness.app.util.UiThemePreference.normalize(value))
        .apply();
  }

  /** 调色板（与日夜正交）；未注册的旧值回到标准色。 */
  public String getUiPalette() {
    return com.deepseekharness.app.util.UiThemePalette.normalize(text("ui_palette", null));
  }

  public void setUiPalette(String value) {
    prefs
        .edit()
        .putString("ui_palette", com.deepseekharness.app.util.UiThemePalette.normalize(value))
        .apply();
  }

  /** 主题切换动画样式；未知值回到圆形扩散。 */
  public String getThemeTransition() {
    return com.deepseekharness.app.util.ThemeMotion.normalizeStyle(
        text("ui_theme_transition", null));
  }

  public void setThemeTransition(String value) {
    prefs
        .edit()
        .putString(
            "ui_theme_transition", com.deepseekharness.app.util.ThemeMotion.normalizeStyle(value))
        .apply();
  }

  /** 原生亮暗是否同步到内嵌 Web 的外观设置；默认开启，用户可在主题页关闭。 */
  public boolean isWebThemeSync() {
    return prefs.getBoolean("ui_theme_web_sync", true);
  }

  public void setWebThemeSync(boolean value) {
    prefs.edit().putBoolean("ui_theme_web_sync", value).apply();
  }

  public com.deepseekharness.app.util.PluginSort.Mode getPluginSort() {
    return com.deepseekharness.app.util.PluginSort.Mode.parse(
        text("plugin_sort_order", "NAME_ASC"));
  }

  public void setPluginSort(com.deepseekharness.app.util.PluginSort.Mode mode) {
    prefs.edit().putString("plugin_sort_order", mode.name()).apply();
  }

  public com.deepseekharness.app.util.PluginDownloadSource getPluginDownloadSource() {
    return com.deepseekharness.app.util.PluginDownloadSource.parse(
        text("plugin_download_source", "auto"));
  }

  public void setPluginDownloadSource(com.deepseekharness.app.util.PluginDownloadSource source) {
    prefs.edit().putString("plugin_download_source", source.value).apply();
  }

  // ================= 接入 =================

  public String getDnsMode() {
    return com.deepseekharness.app.util.ResolverConfig.mode(text("dns_mode", "auto"));
  }

  public void setDnsMode(String value) {
    prefs
        .edit()
        .putString("dns_mode", com.deepseekharness.app.util.ResolverConfig.mode(value))
        .apply();
  }

  public String getApiKey() {
    return readApiKey().requireValue();
  }

  public com.deepseekharness.app.util.CredentialRead readApiKey() {
    Object stored = prefs.getAll().get(Constants.KEY_API_KEY);
    if (stored == null) return com.deepseekharness.app.util.CredentialRead.missing();
    if (!(stored instanceof String))
      return com.deepseekharness.app.util.CredentialRead.failed(
          com.deepseekharness.app.util.CredentialRead.Reason.UNREADABLE);
    var read = vault.read((String) stored);
    if (read.usable())
      com.deepseekharness.app.util.SensitiveData.registerKnownSecret(read.requireValue());
    return read;
  }

  public static String credentialMessage(com.deepseekharness.app.util.CredentialRead read) {
    if (read.state == com.deepseekharness.app.util.CredentialRead.State.TEMPORARILY_UNAVAILABLE)
      return com.deepseekharness.app.util.UiText.text("API Key 暂时无法读取，请解锁设备后重试。原凭据已保留。");
    if (read.reason == com.deepseekharness.app.util.CredentialRead.Reason.KEY_INVALIDATED)
      return com.deepseekharness.app.util.UiText.text("保存 API Key 的设备密钥已失效，请重新输入。原记录未自动删除。");
    return com.deepseekharness.app.util.UiText.text("当前设备无法读取已保存的 API Key，请重试或重新输入。原记录已保留。");
  }

  public void setApiKey(String v) {
    saveApiKey(v);
  }

  /** 加密失败时保留旧凭据，让界面能明确报告保存失败。 */
  public boolean saveApiKey(String value) {
    try {
      String encrypted = prepareCredential(value);
      boolean saved = prefs.edit().putString(Constants.KEY_API_KEY, encrypted).commit();
      if (saved) com.deepseekharness.app.util.SensitiveData.registerKnownSecret(value);
      return saved;
    } catch (java.io.IOException failed) {
      return false;
    }
  }

  private String prepareCredential(String value) throws java.io.IOException {
    return com.deepseekharness.app.util.CredentialWrite.prepare(value, vault::encrypt, vault::read);
  }

  public String getPort() {
    return String.valueOf(getPortInt());
  }

  public String getWebPortMode() {
    return com.deepseekharness.app.util.WebPortSettings.mode(
        prefs.contains(Constants.KEY_WEB_PORT_MODE)
            ? text(Constants.KEY_WEB_PORT_MODE, "auto")
            : null,
        prefs.contains(Constants.KEY_PORT) ? text(Constants.KEY_PORT, "") : null);
  }

  public int getRequestedWebPort() {
    return com.deepseekharness.app.util.WebPortSettings.requested(getWebPortMode(), getPortInt());
  }

  public void setWebPort(String mode, int fixedPort) {
    com.deepseekharness.app.util.WebPortSettings.requested(mode, fixedPort);
    prefs
        .edit()
        .putString(Constants.KEY_WEB_PORT_MODE, mode)
        .putString(Constants.KEY_PORT, String.valueOf(fixedPort))
        .apply();
  }

  public int getPortInt() {
    int p = parsePort(text(Constants.KEY_PORT, String.valueOf(Constants.DSH_WEB_PORT)));
    return com.deepseekharness.app.util.WebPortPolicy.reserved(p) ? Constants.DSH_WEB_PORT : p;
  }

  public void setPort(String v) {
    int p = parsePort(v);
    prefs.edit().putString(Constants.KEY_PORT, String.valueOf(p)).apply();
  }

  private String importedPortMode(org.json.JSONObject data) throws java.io.IOException {
    if (!data.has("portMode"))
      return com.deepseekharness.app.util.WebPortSettings.mode(null, data.optString("port"));
    Object value = data.opt("portMode");
    if (!(value instanceof String)) throw new java.io.IOException("SETTINGS_WEB_PORT_MODE");
    try {
      String mode = com.deepseekharness.app.util.WebPortSettings.requireMode((String) value);
      if ("fixed".equals(mode)) importedPort(data);
      return mode;
    } catch (IllegalArgumentException invalid) {
      throw new java.io.IOException("SETTINGS_WEB_PORT_MODE", invalid);
    }
  }

  private int importedPort(org.json.JSONObject data) throws java.io.IOException {
    if (!"fixed".equals(data.opt("portMode"))) return parsePort(data.optString("port"));
    try {
      return com.deepseekharness.app.util.WebPortSettings.fixedImport(data.opt("port"));
    } catch (IllegalArgumentException invalid) {
      throw new java.io.IOException("SETTINGS_WEB_FIXED_PORT", invalid);
    }
  }

  private int parsePort(String v) {
    try {
      int p = Integer.parseInt(v == null ? "" : v.trim());
      return (p >= 1 && p <= 65535) ? p : Constants.DSH_WEB_PORT;
    } catch (NumberFormatException e) {
      return Constants.DSH_WEB_PORT;
    }
  }

  // ================= 行为 =================

  public boolean isConfirmShell() {
    return flag(Constants.KEY_CONFIRM_SHELL, true);
  }

  public void setConfirmShell(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_CONFIRM_SHELL, v).apply();
  }

  public boolean isRootShellAllowed() {
    return flag(Constants.KEY_ALLOW_ROOT_SHELL, false);
  }

  public void setRootShellAllowed(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, v).apply();
  }

  /** root 命令拦截档位；非法或缺失一律回落严格档（fail-closed）。 */
  public com.deepseekharness.app.util.DeviceShellPolicy.Mode rootPolicyMode() {
    return com.deepseekharness.app.util.DeviceShellPolicy.Mode.parse(
        text(Constants.KEY_ROOT_POLICY_MODE, "strict"));
  }

  public void setRootPolicyMode(com.deepseekharness.app.util.DeviceShellPolicy.Mode mode) {
    prefs
        .edit()
        .putString(
            Constants.KEY_ROOT_POLICY_MODE,
            (mode == null ? com.deepseekharness.app.util.DeviceShellPolicy.Mode.STRICT : mode).id)
        .apply();
  }

  public boolean isCheckUpdate() {
    return flag(Constants.KEY_CHECK_UPDATE, true);
  }

  public void setCheckUpdate(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_CHECK_UPDATE, v).apply();
  }

  public boolean isDesktopMode() {
    return flag(Constants.KEY_DESKTOP_MODE, false);
  }

  public void setDesktopMode(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_DESKTOP_MODE, v).apply();
  }

  /** 屏幕常亮：默认关闭，按窗口生命周期应用，不随备份恢复。 */
  public boolean isKeepScreenOn() {
    return flag(Constants.KEY_KEEP_SCREEN_ON, false);
  }

  public void setKeepScreenOn(boolean value) {
    prefs.edit().putBoolean(Constants.KEY_KEEP_SCREEN_ON, value).apply();
  }

  /** 隐藏后台：从最近任务隐藏本应用任务；默认关闭，只影响任务可见性，不随备份恢复。 */
  public boolean isHideFromRecents() {
    return flag(Constants.KEY_HIDE_FROM_RECENTS, false);
  }

  public void setHideFromRecents(boolean value) {
    prefs.edit().putBoolean(Constants.KEY_HIDE_FROM_RECENTS, value).apply();
  }

  /** 隐藏后台严格模式（实验）：离开应用时结束 UI 任务；默认关闭，只影响界面任务，不随备份恢复。 */
  public boolean isHideRecentsStrict() {
    return flag(Constants.KEY_HIDE_RECENTS_STRICT, false);
  }

  public void setHideRecentsStrict(boolean value) {
    prefs.edit().putBoolean(Constants.KEY_HIDE_RECENTS_STRICT, value).apply();
  }

  /** 任务状态上岛默认开启：它只是给已有通知补充短文本与提升请求，失败会静默降级。 */
  public boolean isTaskIslandEnabled() {
    return flag(Constants.KEY_TASK_ISLAND, true);
  }

  public void setTaskIslandEnabled(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_TASK_ISLAND, v).apply();
  }

  /** 岛上用系统计时器显示耗时；开启后状态不变就不再按秒重绘通知。 */
  public boolean isTaskIslandChronometer() {
    return flag(Constants.KEY_TASK_ISLAND_CHRONOMETER, false);
  }

  public void setTaskIslandChronometer(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_TASK_ISLAND_CHRONOMETER, v).apply();
  }

  public boolean isPictureInPictureEnabled() {
    // 历史键可能来自默认开启或归档，不能证明用户明确选择；原值继续保留。
    // 本机选择不进入 BACKUP_SETTING_KEYS，因此恢复设置也不会重新开启小窗。
    return Boolean.TRUE.equals(prefs.getAll().get(Constants.KEY_PICTURE_IN_PICTURE_USER_CHOICE));
  }

  public void setPictureInPictureEnabled(boolean value) {
    prefs
        .edit()
        .putBoolean(Constants.KEY_PICTURE_IN_PICTURE, value)
        .putBoolean(Constants.KEY_PICTURE_IN_PICTURE_USER_CHOICE, value)
        .apply();
  }

  public String getPictureInPictureLayout() {
    return com.deepseekharness.app.util.PictureInPicturePolicy.layout(
        text(Constants.KEY_PICTURE_IN_PICTURE_LAYOUT, "auto"));
  }

  public void setPictureInPictureLayout(String value) {
    prefs
        .edit()
        .putString(
            Constants.KEY_PICTURE_IN_PICTURE_LAYOUT,
            com.deepseekharness.app.util.PictureInPicturePolicy.layout(value))
        .apply();
  }

  /** 桌面图标启动后的页面；未知或缺失值一律回到主界面。不在 BACKUP_SETTING_KEYS 中。 */
  public String getEntryTarget() {
    return com.deepseekharness.app.util.EntryTarget.normalize(
        text(Constants.KEY_ENTRY_TARGET, com.deepseekharness.app.util.EntryTarget.MAIN));
  }

  /** 同步提交：桥接口需要如实回报是否已写入。 */
  public boolean setEntryTarget(String value) {
    return prefs
        .edit()
        .putString(
            Constants.KEY_ENTRY_TARGET, com.deepseekharness.app.util.EntryTarget.normalize(value))
        .commit();
  }

  /** 最近一次已核验网页的背景色；没有记录时返回 null。 */
  public Integer getEntryWebBackground() {
    Object value = prefs.getAll().get(Constants.KEY_ENTRY_WEB_BACKGROUND);
    return value instanceof Integer ? (Integer) value : null;
  }

  public void setEntryWebBackground(int color) {
    Integer current = getEntryWebBackground();
    if (current != null && current == color) return;
    prefs.edit().putInt(Constants.KEY_ENTRY_WEB_BACKGROUND, color).apply();
  }

  /** 最近一次前台是否为 Web；系统回收后恢复时据此决定回到 Web 还是主界面。 */
  public boolean isLastForegroundWeb() {
    return flag(Constants.KEY_ENTRY_LAST_WEB, false);
  }

  /** 只在值变化时写入：Web / 主界面每次回到前台都会调用。 */
  public void setLastForegroundWeb(boolean web) {
    if (isLastForegroundWeb() == web) return;
    prefs.edit().putBoolean(Constants.KEY_ENTRY_LAST_WEB, web).apply();
  }

  public boolean isBackupKey() {
    return flag(Constants.KEY_BACKUP_KEY, true);
  }

  public void setBackupKey(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_BACKUP_KEY, v).apply();
  }

  public boolean isGeckoCore() {
    return flag(Constants.KEY_GECKO_CORE, false);
  }

  public void setGeckoCore(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_GECKO_CORE, v).apply();
  }

  /** 默认稳定的 proot；用户可选择 proroot。 */
  public boolean isProroot() {
    return "proroot".equals(text(Constants.KEY_CONTAINER_RUNTIME, "proot"));
  }

  public void setProroot(boolean v) {
    prefs.edit().putString(Constants.KEY_CONTAINER_RUNTIME, v ? "proroot" : "proot").apply();
  }

  /** 首次安装同样默认稳定 proot；只由用户显式选择启用 proroot。 */
  public boolean preferFastColdMode() {
    return preferFastColdModeFrom(prefs.getAll());
  }

  static boolean preferFastColdModeFrom(java.util.Map<String, ?> values) {
    return false;
  }

  public boolean isProrootStaticLoader() {
    return flag("proroot_static_loader", true);
  }

  public void setProrootStaticLoader(boolean value) {
    prefs.edit().putBoolean("proroot_static_loader", value).apply();
  }

  public boolean isProotSeccompDisabled() {
    return flag("proot_disable_seccomp", false);
  }

  public void setProotSeccompDisabled(boolean value) {
    prefs.edit().putBoolean("proot_disable_seccomp", value).apply();
  }

  /** Called after cold package installation and real postchecks, never after a probe alone. */
  public com.deepseekharness.app.runtime.RuntimeHostPorts.Settings recordSuccessfulColdRuntime(
      com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
      String rootIdentity,
      String packageSlotSha)
      throws java.io.IOException {
    synchronized (COLD_SELECTION_LOCK) {
      if (mode == null
          || rootIdentity == null
          || rootIdentity.isEmpty()
          || packageSlotSha == null
          || !packageSlotSha.matches("[a-f0-9]{64}"))
        throw new java.io.IOException("COLD_RUNTIME_SELECTION_RECORD");
      if (mode == com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT_DYNAMIC
          && (!preferFastColdModeFrom(prefs.getAll()) || !isProrootStaticLoader()))
        throw new java.io.IOException("COLD_RUNTIME_SELECTION_CHANGED");
      var edit =
          prefs
              .edit()
              .putString(Constants.KEY_CONTAINER_RUNTIME, mode.runtime)
              .putString("cold_runtime_root", rootIdentity)
              .putString("cold_runtime_packages", packageSlotSha)
              .putString("cold_runtime_mode", mode.name());
      if (mode == com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT_DYNAMIC)
        edit.putBoolean("proroot_static_loader", false);
      if (!"proroot".equals(mode.runtime)) edit.putBoolean("proot_disable_seccomp", mode.noSeccomp);
      if (!edit.commit()) throw new java.io.IOException("COLD_RUNTIME_SELECTION_PERSISTENCE");
      return runtimeSettingsSnapshot();
    }
  }

  public java.util.Map<String, Object> snapshotColdRuntimeSelection() throws java.io.IOException {
    synchronized (COLD_SELECTION_LOCK) {
      return com.deepseekharness.app.util.ColdRuntimeSelection.snapshot(prefs.getAll());
    }
  }

  public void restoreColdRuntimeSelection(
      java.util.Map<String, Object> before, String expectedRootIdentity)
      throws java.io.IOException {
    synchronized (COLD_SELECTION_LOCK) {
      var selected =
          com.deepseekharness.app.util.ColdRuntimeSelection.rollback(
              before, prefs.getAll(), expectedRootIdentity);
      var edit = prefs.edit();
      for (String key : com.deepseekharness.app.util.ColdRuntimeSelection.KEYS) {
        Object value = selected.get(key);
        if (value == null) edit.remove(key);
        else if (value instanceof String) edit.putString(key, (String) value);
        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) edit.putInt(key, (Integer) value);
        else if (value instanceof Long) edit.putLong(key, (Long) value);
        else if (value instanceof Float) edit.putFloat(key, (Float) value);
        else throw new java.io.IOException("COLD_SELECTION_TYPE");
      }
      if (!edit.commit()) throw new java.io.IOException("COLD_SELECTION_ROLLBACK_PERSISTENCE");
    }
  }

  /** One SharedPreferences image for a complete native runtime invocation. */
  public com.deepseekharness.app.runtime.RuntimeHostPorts.Settings runtimeSettingsSnapshot() {
    return runtimeSettingsFrom(prefs.getAll());
  }

  static com.deepseekharness.app.runtime.RuntimeHostPorts.Settings runtimeSettingsFrom(
      java.util.Map<String, ?> values) {
    return new com.deepseekharness.app.runtime.RuntimeHostPorts.Settings(
        com.deepseekharness.app.util.ResolverConfig.mode(
            com.deepseekharness.app.util.PreferenceValue.text(values.get("dns_mode"), "auto")),
        "proroot"
            .equals(
                com.deepseekharness.app.util.PreferenceValue.text(
                    values.get(Constants.KEY_CONTAINER_RUNTIME), "proot")),
        com.deepseekharness.app.util.PreferenceValue.flag(
            values.get("proroot_static_loader"), true),
        com.deepseekharness.app.util.PreferenceValue.flag(
            values.get("proot_disable_seccomp"), false));
  }

  public boolean isLanMode() {
    return flag(Constants.KEY_LAN_MODE, false);
  }

  public void setLanMode(boolean v) {
    prefs.edit().putBoolean(Constants.KEY_LAN_MODE, v).apply();
  }

  /** Password verifier remains local and is never included in backup exports. */
  public boolean hasLanPassword() {
    return !text(com.deepseekharness.app.util.LanPassword.PREF_KEY, "").isEmpty();
  }

  /** Call off the UI thread: deriving the verifier is deliberately expensive. */
  public void setLanPassword(String password) {
    String verifier = com.deepseekharness.app.util.LanPassword.create(password);
    com.deepseekharness.app.LanProxyService.stopLanListener();
    if (!prefs
        .edit()
        .putString(com.deepseekharness.app.util.LanPassword.PREF_KEY, verifier)
        .commit()) throw new IllegalStateException("LAN password could not be saved");
    com.deepseekharness.app.LanProxyService.stopLanListener();
  }

  // ================= 其他 =================

  public String getWorkdir() {
    return text(Constants.KEY_WORKDIR, Constants.DEFAULT_WORKDIR);
  }

  public void setWorkdir(String v) {
    prefs.edit().putString(Constants.KEY_WORKDIR, v).apply();
  }

  public int getWebFailures() {
    return integer("web_consecutive_failures", 0);
  }

  /** 本机备用端口不迁移到其他设备，也不覆盖用户设置的首选端口。 */
  public int fallbackWebPort(int preferred) {
    return integer("web_fallback_for", 0) == preferred ? integer("web_fallback_port", 0) : 0;
  }

  public void rememberFallbackWebPort(int preferred, int actual) {
    if (actual < 1 || actual > 65535 || com.deepseekharness.app.util.WebPortPolicy.reserved(actual))
      throw new IllegalArgumentException("WEB_AUTHENTICATED_PORT");
    java.util.Set<String> ports = new java.util.LinkedHashSet<>();
    Object old = prefs.getAll().get("web_origin_ports");
    if (old instanceof java.util.Set<?>)
      for (Object value : (java.util.Set<?>) old)
        if (value instanceof String) ports.add((String) value);
    ports.add(String.valueOf(actual));
    prefs
        .edit()
        .putInt("web_fallback_for", preferred)
        .putInt("web_fallback_port", actual)
        .putStringSet("web_origin_ports", ports)
        .apply();
  }

  /** Local origins are only offered to the explicit, checked browser-data recovery flow. */
  public java.util.Set<Integer> webOriginPorts() {
    java.util.Set<Integer> ports = new java.util.LinkedHashSet<>();
    ports.add(Constants.DSH_WEB_PORT);
    int configured = getPortInt();
    if (configured > 0
        && configured <= 65535
        && !com.deepseekharness.app.util.WebPortPolicy.reserved(configured)) ports.add(configured);
    Object values = prefs.getAll().get("web_origin_ports");
    if (values instanceof java.util.Set<?>) {
      for (Object value : (java.util.Set<?>) values) {
        try {
          if (!(value instanceof String)) continue;
          int port = Integer.parseInt((String) value);
          if (port > 0
              && port <= 65535
              && !com.deepseekharness.app.util.WebPortPolicy.reserved(port)) ports.add(port);
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return java.util.Collections.unmodifiableSet(ports);
  }

  public boolean isStartupRecoveryRequested() {
    return flag("web_startup_recovery_requested", false);
  }

  public void requestStartupRecovery(boolean value) {
    prefs.edit().putBoolean("web_startup_recovery_requested", value).commit();
  }

  public boolean isEcoMode() {
    return flag("runtime_eco_mode", false);
  }

  public void setEcoMode(boolean value) {
    prefs.edit().putBoolean("runtime_eco_mode", value).apply();
  }

  public String getWebFailureStage() {
    return text("web_failure_stage", "");
  }

  public String getWebFailureReason() {
    return text("web_failure_reason", "");
  }

  public String getDiagnosticFailureStage() {
    return text("diagnostic_failure_stage", getWebFailureStage());
  }

  public String getDiagnosticFailureReason() {
    return text("diagnostic_failure_reason", getWebFailureReason());
  }

  public void recordEnvironmentFailure(String stage, String reason) {
    String safe = com.deepseekharness.app.util.SensitiveData.redact(reason);
    prefs
        .edit()
        .putString("diagnostic_failure_stage", stage)
        .putString(
            "diagnostic_failure_reason", safe.length() > 6000 ? safe.substring(0, 6000) : safe)
        .putLong("diagnostic_failure_time", System.currentTimeMillis())
        .commit();
  }

  public void recordWebRecovery(int count, String stage, String reason) {
    if (!stage.isEmpty() && !reason.isEmpty()) recordEnvironmentFailure(stage, reason);
    prefs
        .edit()
        .putInt("web_consecutive_failures", count)
        .putString("web_failure_stage", stage)
        .putString("web_failure_reason", reason.length() > 500 ? reason.substring(0, 500) : reason)
        .commit();
  }

  /** 只导出可迁移的运行设置；API Key 遵循现有「备份密钥」开关。 */
  public org.json.JSONObject exportBackupSettings() throws org.json.JSONException {
    org.json.JSONObject out = new org.json.JSONObject();
    out.put("formatVersion", 1)
        .put("port", getPort())
        .put("portMode", getWebPortMode())
        .put("workdir", getWorkdir())
        .put("confirmShell", isConfirmShell())
        .put("desktopMode", isDesktopMode())
        .put("checkUpdate", isCheckUpdate())
        .put("pictureInPicture", isPictureInPictureEnabled())
        .put("pictureInPictureLayout", getPictureInPictureLayout())
        .put("ecoMode", isEcoMode())
        .put("uiTheme", getUiTheme())
        .put("uiLanguage", getUiLanguageForBackup());
    if (isBackupKey() && !getApiKey().isEmpty()) out.put("apiKey", getApiKey());
    return out;
  }

  /** 新宿主导出策略与历史手动开关分离，凭据不可用时明确失败而不是导出空凭据。 */
  public org.json.JSONObject exportPortableSettings(boolean includeKey)
      throws org.json.JSONException, java.io.IOException {
    org.json.JSONObject out = new org.json.JSONObject();
    out.put("formatVersion", 1)
        .put("port", getPort())
        .put("portMode", getWebPortMode())
        .put("workdir", getWorkdir())
        .put("confirmShell", isConfirmShell())
        .put("desktopMode", isDesktopMode())
        .put("checkUpdate", isCheckUpdate())
        .put("ecoMode", isEcoMode())
        .put("pictureInPicture", isPictureInPictureEnabled())
        .put("pictureInPictureLayout", getPictureInPictureLayout())
        .put("uiTheme", getUiTheme())
        .put("uiLanguage", getUiLanguageForBackup());
    if (includeKey) {
      String key;
      try {
        key = readApiKey().requireValue();
      } catch (com.deepseekharness.app.util.CredentialRead.Unavailable unavailable) {
        throw new java.io.IOException(unavailable.getMessage());
      }
      if (!key.isEmpty()) out.put("apiKey", key);
    }
    return out;
  }

  public void importBackupSettings(org.json.JSONObject data) throws java.io.IOException {
    SharedPreferences.Editor edit = prefs.edit().remove(Constants.KEY_PERMISSION_MODE);
    if (data.has("port"))
      edit.putString(Constants.KEY_PORT, String.valueOf(importedPort(data)))
          .putString(Constants.KEY_WEB_PORT_MODE, importedPortMode(data));
    if (data.has("workdir"))
      edit.putString(Constants.KEY_WORKDIR, data.optString("workdir", Constants.DEFAULT_WORKDIR));
    if (data.has("confirmShell"))
      edit.putBoolean(Constants.KEY_CONFIRM_SHELL, data.optBoolean("confirmShell", true));
    if (data.has("desktopMode"))
      edit.putBoolean(Constants.KEY_DESKTOP_MODE, data.optBoolean("desktopMode"));
    if (data.has("pictureInPicture"))
      edit.putBoolean(Constants.KEY_PICTURE_IN_PICTURE, data.optBoolean("pictureInPicture", false));
    if (data.has("pictureInPictureLayout"))
      edit.putString(
          Constants.KEY_PICTURE_IN_PICTURE_LAYOUT,
          com.deepseekharness.app.util.PictureInPicturePolicy.layout(
              data.optString("pictureInPictureLayout")));
    if (data.has("checkUpdate"))
      edit.putBoolean(Constants.KEY_CHECK_UPDATE, data.optBoolean("checkUpdate", true));
    if (data.has("apiKey")) {
      String plain = data.optString("apiKey");
      String encrypted = prepareCredential(plain);
      edit.putString(Constants.KEY_API_KEY, encrypted);
    }
    if (data.has("ecoMode")) edit.putBoolean("runtime_eco_mode", data.optBoolean("ecoMode"));
    if (data.has("uiTheme"))
      edit.putString(
          "ui_theme",
          com.deepseekharness.app.util.UiThemePreference.normalize(data.optString("uiTheme")));
    if (data.has("uiLanguage")) {
      // 老备份里的 zh/en 保持原样；新备份可能带 system（跟随系统）。
      String preference =
          com.deepseekharness.app.util.UiLanguagePreference.normalize(data.optString("uiLanguage"));
      edit.putString("ui_language", preference);
    }
    if (!edit.commit())
      throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("原生设置写入失败"));
  }

  private static final String[] BACKUP_SETTING_KEYS = {
    Constants.KEY_PORT,
    Constants.KEY_WEB_PORT_MODE,
    Constants.KEY_WORKDIR,
    Constants.KEY_CONFIRM_SHELL,
    Constants.KEY_DESKTOP_MODE,
    Constants.KEY_CHECK_UPDATE,
    Constants.KEY_API_KEY,
    Constants.KEY_PICTURE_IN_PICTURE,
    Constants.KEY_PICTURE_IN_PICTURE_LAYOUT,
    "runtime_eco_mode",
    "ui_theme",
    "ui_language"
  };

  public java.util.Map<String, Object> hostSettingsState() {
    java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
    java.util.Map<String, ?> all = prefs.getAll();
    for (String key : BACKUP_SETTING_KEYS) if (all.containsKey(key)) values.put(key, all.get(key));
    return values;
  }

  public java.util.Map<String, Object> prepareHostSettings(
      org.json.JSONObject data, boolean includeKey) throws java.io.IOException {
    java.util.Map<String, Object> next = hostSettingsState();
    if (data.has("port")) {
      next.put(Constants.KEY_PORT, String.valueOf(importedPort(data)));
      next.put(Constants.KEY_WEB_PORT_MODE, importedPortMode(data));
    }
    if (data.has("workdir")) {
      String path = data.optString("workdir");
      if (path.length() > 2048 || path.indexOf('\0') >= 0)
        throw new java.io.IOException("SETTINGS_PATH");
      next.put(Constants.KEY_WORKDIR, path);
    }
    for (String[] pair :
        new String[][] {
          {"confirmShell", Constants.KEY_CONFIRM_SHELL},
          {"desktopMode", Constants.KEY_DESKTOP_MODE},
          {"checkUpdate", Constants.KEY_CHECK_UPDATE},
          {"ecoMode", "runtime_eco_mode"}
        }) if (data.has(pair[0])) next.put(pair[1], data.optBoolean(pair[0]));
    if (data.has("pictureInPicture"))
      next.put(Constants.KEY_PICTURE_IN_PICTURE, data.optBoolean("pictureInPicture", false));
    if (data.has("pictureInPictureLayout"))
      next.put(
          Constants.KEY_PICTURE_IN_PICTURE_LAYOUT,
          com.deepseekharness.app.util.PictureInPicturePolicy.layout(
              data.optString("pictureInPictureLayout")));
    if (data.has("uiTheme"))
      next.put(
          "ui_theme",
          com.deepseekharness.app.util.UiThemePreference.normalize(data.optString("uiTheme")));
    if (data.has("uiLanguage"))
      next.put(
          "ui_language",
          com.deepseekharness.app.util.UiLanguagePreference.normalize(
              data.optString("uiLanguage")));
    if (includeKey && data.has("apiKey") && !data.optString("apiKey").isEmpty()) {
      String cipher = prepareCredential(data.optString("apiKey"));
      next.put(Constants.KEY_API_KEY, cipher);
    }
    return next;
  }

  /** 事务只提交事先校验/加密的白名单状态，不能从归档键名写设备授权。 */
  public void applyHostSettings(java.util.Map<String, Object> values) throws java.io.IOException {
    java.util.Set<String> allowed =
        new java.util.HashSet<>(java.util.Arrays.asList(BACKUP_SETTING_KEYS));
    // 旧中断事务可能带这个退休字段；接受原日志格式，但不再恢复其值。
    allowed.add(Constants.KEY_PERMISSION_MODE);
    if (!allowed.containsAll(values.keySet())) throw new java.io.IOException("SETTINGS_KEYS");
    SharedPreferences.Editor edit = prefs.edit().remove(Constants.KEY_PERMISSION_MODE);
    for (String key : BACKUP_SETTING_KEYS) {
      Object value = values.get(key);
      if (value == null) edit.remove(key);
      else if (value instanceof String) edit.putString(key, (String) value);
      else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
      else if (value instanceof Number) edit.putLong(key, ((Number) value).longValue());
      else throw new java.io.IOException("SETTINGS_TYPE");
    }
    if (!edit.commit()) throw new java.io.IOException("SETTINGS_COMMIT_FAILED");
  }

  /** 保存的是 Keystore 密文和原始偏好值，供跨进程中断恢复使用。 */
  public void beginRestoreSettings() throws Exception {
    org.json.JSONObject before = new org.json.JSONObject();
    java.util.Map<String, ?> all = prefs.getAll();
    for (String key : BACKUP_SETTING_KEYS)
      before.put(key, all.containsKey(key) ? all.get(key) : org.json.JSONObject.NULL);
    if (!prefs.edit().putString("backup_restore_previous_settings", before.toString()).commit())
      throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("无法保留恢复前设置"));
  }

  public void finishRestoreSettings(boolean rollback) throws Exception {
    String saved = text("backup_restore_previous_settings", "");
    if (saved.isEmpty()) return;
    SharedPreferences.Editor edit = prefs.edit().remove(Constants.KEY_PERMISSION_MODE);
    if (rollback) {
      org.json.JSONObject before = new org.json.JSONObject(saved);
      for (String key : BACKUP_SETTING_KEYS) {
        Object value = before.opt(key);
        if (value == null || value == org.json.JSONObject.NULL) edit.remove(key);
        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) edit.putInt(key, (Integer) value);
        else if (value instanceof Long) edit.putLong(key, (Long) value);
        else edit.putString(key, String.valueOf(value));
      }
    }
    if (!edit.remove("backup_restore_previous_settings").commit())
      throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("恢复设置事务写入失败"));
  }

  public void recordBackupResult(String uri, String name, String failure, int scope) {
    SharedPreferences.Editor edit =
        prefs
            .edit()
            .putString("backup_last_error", failure)
            .putLong("backup_last_attempt", System.currentTimeMillis());
    if (failure.isEmpty()) {
      edit.putString("backup_last_uri", uri)
          .putString("backup_last_name", name)
          .putLong("backup_last_success", System.currentTimeMillis())
          .putInt("backup_last_scope", scope);
    }
    edit.apply();
  }

  public String getLastBackupUri() {
    return text("backup_last_uri", "");
  }

  public void recordVerifiedNativeBackup(String uri, String name, String scope) {
    if (!java.util.Set.of("application", "sessions", "settings", "plugins", "projects")
        .contains(scope)) throw new IllegalArgumentException("BACKUP_SCOPE");
    prefs
        .edit()
        .putString("backup_last_uri", uri)
        .putString("backup_last_name", name)
        .putLong("backup_last_success", System.currentTimeMillis())
        .putLong("backup_last_attempt", System.currentTimeMillis())
        .putString("backup_last_error", "")
        .putString("backup_last_native_scope", scope)
        .apply();
  }

  public String getLastBackupName() {
    return text("backup_last_name", "");
  }

  public String getLastBackupError() {
    return text("backup_last_error", "");
  }

  public long getLastBackupSuccess() {
    return longValue("backup_last_success", 0);
  }

  private String text(String key, String fallback) {
    return PreferenceValue.text(prefs.getAll().get(key), fallback);
  }

  private boolean flag(String key, boolean fallback) {
    return PreferenceValue.flag(prefs.getAll().get(key), fallback);
  }

  private int integer(String key, int fallback) {
    return PreferenceValue.integer(prefs.getAll().get(key), fallback);
  }

  private long longValue(String key, long fallback) {
    return PreferenceValue.integer(prefs.getAll().get(key), fallback);
  }
}
