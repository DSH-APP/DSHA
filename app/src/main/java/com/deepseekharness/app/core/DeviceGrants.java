package com.deepseekharness.app.core;

import android.content.Context;

/** 独立原生授权存储，不从环境、插件文件或备份自动恢复授权。 */
public final class DeviceGrants {
    private final android.content.SharedPreferences preferences;
    public DeviceGrants(Context context) {
        preferences = context.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
    }
    public boolean smsReadAllowed() { return preferences.getBoolean("sms_read", false); }
    public boolean setSmsReadAllowed(boolean allowed) {
        return preferences.edit().putBoolean("sms_read", allowed).commit();
    }
}
