package com.deepseekharness.app.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.deepseekharness.app.data.KeyVault;
import com.deepseekharness.app.util.Constants;

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
    }

    public boolean isWelcomed() {
        return prefs.getBoolean(Constants.KEY_WELCOMED, false);
    }

    public void setWelcomed(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_WELCOMED, v).apply();
    }

    public String getUiTheme() {
        return com.deepseekharness.app.util.UiThemePreference.normalize(prefs.getString("ui_theme", "system"));
    }
    public void setUiTheme(String value) {
        prefs.edit().putString("ui_theme", com.deepseekharness.app.util.UiThemePreference.normalize(value)).apply();
    }

    // ================= 接入 =================

    public String getApiKey() {
        return vault.decrypt(prefs.getString(Constants.KEY_API_KEY, ""));
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
        int p = parsePort(prefs.getString(Constants.KEY_PORT, String.valueOf(Constants.DSH_WEB_PORT)));
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
        return prefs.getBoolean(Constants.KEY_CONFIRM_SHELL, true);
    }

    public void setConfirmShell(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_CONFIRM_SHELL, v).apply();
    }

    public boolean isRootShellAllowed() {
        return prefs.getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false);
    }

    public void setRootShellAllowed(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, v).apply();
    }

    public boolean isCheckUpdate() {
        return prefs.getBoolean(Constants.KEY_CHECK_UPDATE, true);
    }

    public void setCheckUpdate(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_CHECK_UPDATE, v).apply();
    }

    public boolean isDesktopMode() {
        return prefs.getBoolean(Constants.KEY_DESKTOP_MODE, false);
    }

    public void setDesktopMode(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_DESKTOP_MODE, v).apply();
    }

    public boolean isBackupKey() {
        return prefs.getBoolean(Constants.KEY_BACKUP_KEY, true);
    }

    public void setBackupKey(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_BACKUP_KEY, v).apply();
    }

    public boolean isGeckoCore() {
        return prefs.getBoolean(Constants.KEY_GECKO_CORE, false);
    }

    public void setGeckoCore(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_GECKO_CORE, v).apply();
    }

    /** 默认 proroot；关掉用传统 proot。 */
    public boolean isProroot() {
        return "proroot".equals(prefs.getString(Constants.KEY_CONTAINER_RUNTIME, "proot"));
    }

    public void setProroot(boolean v) {
        prefs.edit().putString(Constants.KEY_CONTAINER_RUNTIME, v ? "proroot" : "proot").apply();
    }

    public boolean isLanMode() {
        return prefs.getBoolean(Constants.KEY_LAN_MODE, false);
    }

    public void setLanMode(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_LAN_MODE, v).apply();
    }

    public int getAutoBackupLaunches() {
        try {
            return Integer.parseInt(prefs.getString(Constants.KEY_AUTO_BACKUP, "5"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void setAutoBackupLaunches(int v) {
        prefs.edit().putString(Constants.KEY_AUTO_BACKUP, String.valueOf(Math.max(0, v))).apply();
    }

    // ================= 其他 =================

    public String getPermissionMode() {
        return prefs.getString(Constants.KEY_PERMISSION_MODE, "danger-full-access");
    }

    public void setPermissionMode(String v) {
        prefs.edit().putString(Constants.KEY_PERMISSION_MODE, v).apply();
    }

    public String getWorkdir() {
        return prefs.getString(Constants.KEY_WORKDIR, Constants.DEFAULT_WORKDIR);
    }

    public void setWorkdir(String v) {
        prefs.edit().putString(Constants.KEY_WORKDIR, v).apply();
    }

    public int getWebFailures() { return prefs.getInt("web_consecutive_failures", 0); }
    public boolean isEcoMode() { return prefs.getBoolean("runtime_eco_mode", false); }
    public void setEcoMode(boolean value) { prefs.edit().putBoolean("runtime_eco_mode", value).apply(); }
    public String getWebFailureStage() { return prefs.getString("web_failure_stage", ""); }
    public String getWebFailureReason() { return prefs.getString("web_failure_reason", ""); }
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
                .put("autoBackupLaunches", getAutoBackupLaunches()).put("ecoMode", isEcoMode()).put("uiTheme", getUiTheme());
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
        if (data.has("autoBackupLaunches")) edit.putString(Constants.KEY_AUTO_BACKUP, String.valueOf(Math.max(0, data.optInt("autoBackupLaunches", 5))));
        if (data.has("apiKey")) {
            String plain = data.optString("apiKey");
            String encrypted = vault.encrypt(plain);
            if (!plain.isEmpty() && encrypted.isEmpty()) throw new java.io.IOException("API Key 加密失败，未写入恢复配置");
            edit.putString(Constants.KEY_API_KEY, encrypted);
        }
        if (data.has("ecoMode")) edit.putBoolean("runtime_eco_mode", data.optBoolean("ecoMode"));
        if (data.has("uiTheme")) edit.putString("ui_theme", com.deepseekharness.app.util.UiThemePreference.normalize(data.optString("uiTheme")));
        if (!edit.commit()) throw new java.io.IOException("原生设置写入失败");
    }

    private static final String[] BACKUP_SETTING_KEYS = {
            Constants.KEY_PORT, Constants.KEY_WORKDIR, Constants.KEY_PERMISSION_MODE,
            Constants.KEY_CONFIRM_SHELL, Constants.KEY_DESKTOP_MODE, Constants.KEY_CHECK_UPDATE,
            Constants.KEY_AUTO_BACKUP, Constants.KEY_API_KEY, "runtime_eco_mode", "ui_theme"
    };

    /** 保存的是 Keystore 密文和原始偏好值，供跨进程中断恢复使用。 */
    public void beginRestoreSettings() throws Exception {
        org.json.JSONObject before = new org.json.JSONObject();
        java.util.Map<String, ?> all = prefs.getAll();
        for (String key : BACKUP_SETTING_KEYS) before.put(key, all.containsKey(key) ? all.get(key) : org.json.JSONObject.NULL);
        if (!prefs.edit().putString("backup_restore_previous_settings", before.toString()).commit())
            throw new java.io.IOException("无法保留恢复前设置");
    }

    public void finishRestoreSettings(boolean rollback) throws Exception {
        String saved = prefs.getString("backup_restore_previous_settings", "");
        if (saved.isEmpty()) return;
        SharedPreferences.Editor edit = prefs.edit();
        if (rollback) {
            org.json.JSONObject before = new org.json.JSONObject(saved);
            for (String key : BACKUP_SETTING_KEYS) {
                Object value = before.opt(key);
                if (value == null || value == org.json.JSONObject.NULL) edit.remove(key);
                else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
                else edit.putString(key, String.valueOf(value));
            }
        }
        if (!edit.remove("backup_restore_previous_settings").commit()) throw new java.io.IOException("恢复设置事务写入失败");
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

    public String getLastBackupUri() { return prefs.getString("backup_last_uri", ""); }
    public String getLastBackupName() { return prefs.getString("backup_last_name", ""); }
    public String getLastBackupError() { return prefs.getString("backup_last_error", ""); }
    public long getLastBackupSuccess() { return prefs.getLong("backup_last_success", 0); }

    /** 每次手动启动计数，达到阈值才触发；看门狗重启不计数。 */
    public synchronized boolean countLaunchForBackup() {
        int every = getAutoBackupLaunches();
        if (every <= 0) return false;
        int count = prefs.getInt("backup_launch_count", 0) + 1;
        boolean due = count >= every;
        prefs.edit().putInt("backup_launch_count", due ? 0 : count).apply();
        return due;
    }
}
