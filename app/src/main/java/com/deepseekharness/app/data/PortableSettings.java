package com.deepseekharness.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.backup.PortablePreferenceProjection;
import java.util.*;

/** 系统迁移只接触明确白名单投影；原偏好键继续使用，不把设备授权和 Keystore 密文带到新设备。 */
public final class PortableSettings {
  public static final String NAME = "dsha-portable-settings";
  private SharedPreferences.OnSharedPreferenceChangeListener listener;
  private SharedPreferences activePreferences, portablePreferences;
  private volatile String error = "";
  private volatile long binding;
  private final java.util.concurrent.ExecutorService writer;

  public interface Owner {
    PortableSettings portableSettings();
  }

  public PortableSettings() {
    this(
        java.util.concurrent.Executors.newSingleThreadExecutor(
            work -> {
              Thread thread = new Thread(work, "portable-settings");
              thread.setDaemon(true);
              return thread;
            }));
  }

  PortableSettings(java.util.concurrent.ExecutorService writer) {
    this.writer = java.util.Objects.requireNonNull(writer);
  }

  public static PortableSettings from(Context context) {
    Context application = context.getApplicationContext();
    if (!(application instanceof Owner))
      throw new IllegalStateException("PORTABLE_SETTINGS_OWNER_UNAVAILABLE");
    PortableSettings settings = ((Owner) application).portableSettings();
    if (settings == null) throw new IllegalStateException("PORTABLE_SETTINGS_UNAVAILABLE");
    return settings;
  }

  public static void initialize(Context context) {
    Context application = context.getApplicationContext();
    from(application)
        .initializePreferences(
            application.getSharedPreferences(Constants.PREFS, 0),
            application.getSharedPreferences(NAME, 0));
  }

  synchronized void initializePreferences(SharedPreferences active, SharedPreferences portable) {
    if (listener != null) return;
    try {
      if (PortablePreferenceProjection.mayImport(active.getAll(), portable.getAll())) {
        SharedPreferences.Editor restore = active.edit();
        copy(portable.getAll(), restore);
        // 失败时保留原投影；不能用尚未落盘的活跃偏好清空最后的迁移来源。
        if (!restore.commit()) {
          error = "SETTINGS_PROJECTION_FAILED";
          return;
        }
      }
      activePreferences = active;
      portablePreferences = portable;
      long acceptedBinding = ++binding;
      listener = (preferences, key) -> changed(acceptedBinding, preferences, key);
      active.registerOnSharedPreferenceChangeListener(listener);
      writer.execute(() -> write(acceptedBinding, active, portable));
    } catch (RuntimeException failure) {
      error = "SETTINGS_PROJECTION_UNAVAILABLE";
    }
  }

  private synchronized void changed(
      long expectedBinding, SharedPreferences preferences, String key) {
    if (listener == null || binding != expectedBinding || preferences != activePreferences) return;
    if (key == null || PortablePreferenceProjection.transferable(key)) {
      SharedPreferences portable = portablePreferences;
      writer.execute(() -> write(expectedBinding, preferences, portable));
    }
  }

  /**
   * 应用内格式化不会重启进程，因此需先解除旧监听、排空旧写入，再建立空投影。
   * 否则格式化前排队的异步投影可能在清理后把旧设置写回来。
   */
  public static void resetForFreshStart(Context context) throws java.io.IOException {
    Context application = context.getApplicationContext();
    from(application)
        .resetPreferences(
            application.getSharedPreferences(Constants.PREFS, 0),
            application.getSharedPreferences(NAME, 0));
  }

  synchronized void resetPreferences(SharedPreferences active, SharedPreferences portable)
      throws java.io.IOException {
    if (listener != null) {
      active.unregisterOnSharedPreferenceChangeListener(listener);
      listener = null;
    }
    binding++;
    activePreferences = null;
    portablePreferences = null;
    try {
      writer.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new java.io.IOException("SETTINGS_PROJECTION_DRAIN_FAILED", failure);
    }
    if (!active.edit().clear().commit() || !portable.edit().clear().commit())
      throw new java.io.IOException("SETTINGS_PROJECTION_CLEAR_FAILED");
    error = "";
    initializePreferences(active, portable);
  }

  private void write(long expectedBinding, SharedPreferences active, SharedPreferences portable) {
    if (binding != expectedBinding) return;
    try {
      SharedPreferences.Editor out = portable.edit().clear().putInt("projectionVersion", 1);
      copy(active.getAll(), out);
      boolean committed = out.commit();
      if (binding == expectedBinding) error = committed ? "" : "SETTINGS_PROJECTION_FAILED";
    } catch (RuntimeException failure) {
      if (binding == expectedBinding) error = "SETTINGS_PROJECTION_UNAVAILABLE";
    }
  }

  private static void copy(Map<String, ?> input, SharedPreferences.Editor output) {
    for (var entry : PortablePreferenceProjection.project(input).entrySet()) {
      Object value = entry.getValue();
      if (value instanceof String) output.putString(entry.getKey(), (String) value);
      else if (value instanceof Boolean) output.putBoolean(entry.getKey(), (Boolean) value);
    }
  }

  public static boolean transferable(String key) {
    return PortablePreferenceProjection.transferable(key);
  }

  public static String errorCode(Context context) {
    return from(context).errorCode();
  }

  public String errorCode() {
    return error;
  }
}
