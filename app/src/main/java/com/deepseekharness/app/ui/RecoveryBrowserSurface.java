package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.View;
import java.util.function.Consumer;

/** 应急页面只接收本实例地址；不读取正式 Controller、桥或启动健康状态。 */
public interface RecoveryBrowserSurface extends AutoCloseable {
  View attach(Activity activity, Consumer<String> error);

  void load(String authUrl, String baseUrl, String cookie);

  void syncLanguage(String language);

  boolean back();

  void detach();

  void close();
}
