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

    public ConfigStore(Context ctx) {
        this.prefs = ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
        this.vault = new KeyVault(ctx);
        // 启动计数备份已移除；覆盖安装与旧备份恢复均不能重新启用。
        if (prefs.contains(Constants.KEY_AUTO_BACKUP) || prefs.contains("backup_launch_count"))
            prefs.edit().remove(Constants.KEY_AUTO_BACKUP).remove("backup_launch_count").apply();
    }

    public boolean isWelcomed() {
        return flag(Constants.KEY_WELCOMED, false);
    }

    public void setWelcomed(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_WELCOMED, v).apply();
    }

    public boolean allowsLimitedEntry(String identity) {
        return identity.equals(text("limited_entry_identity", ""));
    }
    public void allowLimitedEntry(String identity) {
        prefs.edit().putString("limited_entry_identity", identity).apply();
    }

    public String getUiTheme() {
        return com.deepseekharness.app.util.UiThemePreference.normalize(text("ui_theme", "system"));
    }
    public String getUiLanguage() {
        return com.deepseekharness.app.util.UiLanguagePreference.normalize(text("ui_language", "zh"));
    }
    public void setUiLanguage(String value) {
        prefs.edit().putString("ui_language", com.deepseekharness.app.util.UiLanguagePreference.normalize(value)).apply();
    }
    public void setUiTheme(String value) {
        prefs.edit().putString("ui_theme", com.deepseekharness.app.util.UiThemePreference.normalize(value)).apply();
    }
    public com.deepseekharness.app.util.PluginSort.Mode getPluginSort() {
        return com.deepseekharness.app.util.PluginSort.Mode.parse(text("plugin_sort_order", "NAME_ASC"));
    }
    public void setPluginSort(com.deepseekharness.app.util.PluginSort.Mode mode) {
        prefs.edit().putString("plugin_sort_order",mode.name()).apply();
    }

    // ================= 接入 =================

    public String getDnsMode() { return com.deepseekharness.app.util.ResolverConfig.mode(text("dns_mode", "auto")); }
    public void setDnsMode(String value) { prefs.edit().putString("dns_mode",com.deepseekharness.app.util.ResolverConfig.mode(value)).apply(); }

    public String getApiKey() {
        return vault.decrypt(text(Constants.KEY_API_KEY, ""));
    }

    public void setApiKey(String v) {
        saveApiKey(v);
    }

    /** 加密失败时保留旧凭据，让界面能明确报告保存失败。 */
    public boolean saveApiKey(String value) {
        String plain = value == null ? "" : value;
        String encrypted = vault.encrypt(plain);
        if (!plain.isEmpty() && encrypted.isEmpty()) return false;
        return prefs.edit().putString(Constants.KEY_API_KEY, encrypted).commit();
    }

    public String getPort() {
        return String.valueOf(getPortInt());
    }

    public int getPortInt() {
        int p = parsePort(text(Constants.KEY_PORT, String.valueOf(Constants.DSH_WEB_PORT)));
        return p == Constants.LAN_BRIDGE_PORT || p == Constants.SHELL_BRIDGE_PORT ? Constants.DSH_WEB_PORT : p;
    }

    public void setPort(String v) {
        int p = parsePort(v);
        prefs.edit().putString(Constants.KEY_PORT, String.valueOf(p)).apply();
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

    /** 默认 proroot；关掉用传统 proot。 */
    public boolean isProroot() {
        return "proroot".equals(text(Constants.KEY_CONTAINER_RUNTIME, "proot"));
    }

    public void setProroot(boolean v) {
        prefs.edit().putString(Constants.KEY_CONTAINER_RUNTIME, v ? "proroot" : "proot").apply();
    }

    public boolean isLanMode() {
        return flag(Constants.KEY_LAN_MODE, false);
    }

    public void setLanMode(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_LAN_MODE, v).apply();
    }

    // ================= 其他 =================

    public String getPermissionMode() {
        return text(Constants.KEY_PERMISSION_MODE, "danger-full-access");
    }

    public void setPermissionMode(String v) {
        prefs.edit().putString(Constants.KEY_PERMISSION_MODE, v).apply();
    }

    public String getWorkdir() {
        return text(Constants.KEY_WORKDIR, Constants.DEFAULT_WORKDIR);
    }

    public void setWorkdir(String v) {
        prefs.edit().putString(Constants.KEY_WORKDIR, v).apply();
    }

    public int getWebFailures() { return integer("web_consecutive_failures", 0); }
    /** 本机备用端口不迁移到其他设备，也不覆盖用户设置的首选端口。 */
    public int fallbackWebPort(int preferred) { return integer("web_fallback_for",0)==preferred?integer("web_fallback_port",0):0; }
    public void rememberFallbackWebPort(int preferred,int actual) {
        prefs.edit().putInt("web_fallback_for",preferred).putInt("web_fallback_port",actual).apply();
    }
    public boolean isStartupRecoveryRequested() { return flag("web_startup_recovery_requested",false); }
    public void requestStartupRecovery(boolean value) { prefs.edit().putBoolean("web_startup_recovery_requested",value).commit(); }
    public boolean isEcoMode() { return flag("runtime_eco_mode", false); }
    public void setEcoMode(boolean value) { prefs.edit().putBoolean("runtime_eco_mode", value).apply(); }
    public String getWebFailureStage() { return text("web_failure_stage", ""); }
    public String getWebFailureReason() { return text("web_failure_reason", ""); }
    public void recordWebRecovery(int count, String stage, String reason) {
        prefs.edit().putInt("web_consecutive_failures", count).putString("web_failure_stage", stage)
                .putString("web_failure_reason", reason.length() > 500 ? reason.substring(0, 500) : reason).commit();
    }

    /** 只导出可迁移的运行设置；API Key 遵循现有「备份密钥」开关。 */
    public org.json.JSONObject exportBackupSettings() throws org.json.JSONException {
        org.json.JSONObject out = new org.json.JSONObject();
        out.put("formatVersion", 1).put("port", getPort()).put("workdir", getWorkdir())
                .put("permissionMode", getPermissionMode()).put("confirmShell", isConfirmShell())
                .put("desktopMode", isDesktopMode()).put("checkUpdate", isCheckUpdate())
                .put("ecoMode", isEcoMode()).put("uiTheme", getUiTheme()).put("uiLanguage", getUiLanguage());
        if (isBackupKey() && !getApiKey().isEmpty()) out.put("apiKey", getApiKey());
        return out;
    }

    public void importBackupSettings(org.json.JSONObject data) throws java.io.IOException {
        SharedPreferences.Editor edit = prefs.edit();
        if (data.has("port")) edit.putString(Constants.KEY_PORT, String.valueOf(parsePort(data.optString("port"))));
        if (data.has("workdir")) edit.putString(Constants.KEY_WORKDIR, data.optString("workdir", Constants.DEFAULT_WORKDIR));
        if (data.has("permissionMode")) edit.putString(Constants.KEY_PERMISSION_MODE, data.optString("permissionMode", "danger-full-access"));
        if (data.has("confirmShell")) edit.putBoolean(Constants.KEY_CONFIRM_SHELL, data.optBoolean("confirmShell", true));
        if (data.has("desktopMode")) edit.putBoolean(Constants.KEY_DESKTOP_MODE, data.optBoolean("desktopMode"));
        if (data.has("checkUpdate")) edit.putBoolean(Constants.KEY_CHECK_UPDATE, data.optBoolean("checkUpdate", true));
        if (data.has("apiKey")) {
            String plain = data.optString("apiKey");
            String encrypted = vault.encrypt(plain);
            if (!plain.isEmpty() && encrypted.isEmpty()) throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("API Key 加密失败，未写入恢复配置"));
            edit.putString(Constants.KEY_API_KEY, encrypted);
        }
        if (data.has("ecoMode")) edit.putBoolean("runtime_eco_mode", data.optBoolean("ecoMode"));
        if (data.has("uiTheme")) edit.putString("ui_theme", com.deepseekharness.app.util.UiThemePreference.normalize(data.optString("uiTheme")));
        if (data.has("uiLanguage")) edit.putString("ui_language", com.deepseekharness.app.util.UiLanguagePreference.normalize(data.optString("uiLanguage")));
        if (!edit.commit()) throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("原生设置写入失败"));
    }

    private static final String[] BACKUP_SETTING_KEYS = {
            Constants.KEY_PORT, Constants.KEY_WORKDIR, Constants.KEY_PERMISSION_MODE,
            Constants.KEY_CONFIRM_SHELL, Constants.KEY_DESKTOP_MODE, Constants.KEY_CHECK_UPDATE,
            Constants.KEY_API_KEY, "runtime_eco_mode", "ui_theme", "ui_language"
    };

    /** 保存的是 Keystore 密文和原始偏好值，供跨进程中断恢复使用。 */
    public void beginRestoreSettings() throws Exception {
        org.json.JSONObject before = new org.json.JSONObject();
        java.util.Map<String, ?> all = prefs.getAll();
        for (String key : BACKUP_SETTING_KEYS) before.put(key, all.containsKey(key) ? all.get(key) : org.json.JSONObject.NULL);
        if (!prefs.edit().putString("backup_restore_previous_settings", before.toString()).commit())
            throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("无法保留恢复前设置"));
    }

    public void finishRestoreSettings(boolean rollback) throws Exception {
        String saved = text("backup_restore_previous_settings", "");
        if (saved.isEmpty()) return;
        SharedPreferences.Editor edit = prefs.edit();
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
        if (!edit.remove("backup_restore_previous_settings").commit()) throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("恢复设置事务写入失败"));
    }

    public void recordBackupResult(String uri, String name, String failure, int scope) {
        SharedPreferences.Editor edit = prefs.edit().putString("backup_last_error", failure)
                .putLong("backup_last_attempt", System.currentTimeMillis());
        if (failure.isEmpty()) {
            edit.putString("backup_last_uri", uri).putString("backup_last_name", name)
                    .putLong("backup_last_success", System.currentTimeMillis()).putInt("backup_last_scope", scope);
        }
        edit.apply();
    }

    public String getLastBackupUri() { return text("backup_last_uri", ""); }
    public String getLastBackupName() { return text("backup_last_name", ""); }
    public String getLastBackupError() { return text("backup_last_error", ""); }
    public long getLastBackupSuccess() { return longValue("backup_last_success", 0); }

    private String text(String key, String fallback) { return PreferenceValue.text(prefs.getAll().get(key), fallback); }
    private boolean flag(String key, boolean fallback) { return PreferenceValue.flag(prefs.getAll().get(key), fallback); }
    private int integer(String key, int fallback) { return PreferenceValue.integer(prefs.getAll().get(key), fallback); }
    private long longValue(String key, long fallback) { return PreferenceValue.integer(prefs.getAll().get(key), fallback); }
}
