package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.HostDataTransaction;
import com.deepseekharness.app.backup.NativeConfigurationReset;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Native configuration transactions are separate from the Web lifetime. */
final class HostConfigService {
  private final Context context;
  private final ConfigStore config;

  HostConfigService(Context context, ConfigStore config) {
    this.context = context;
    this.config = config;
  }

  String reset() throws IOException {
    if (!MaintenanceCoordinator.isOwner()) throw new IOException("RESET_REQUIRES_MAINTENANCE");
    File files = context.getFilesDir().getCanonicalFile();
    File saved =
        NativeConfigurationReset.reset(
            new AndroidBackupFileSystem(),
            files,
            config.getWorkdir(),
            NativeConfigurationReset.environment(config.readApiKey()),
            settings(),
            null,
            BackupTask.currentControl(null));
    return UiText.format("配置已重置，对话及原生凭据保留。重置前配置原件：\n%s", saved);
  }

  HostDataTransaction.Settings settings() {
    return new HostDataTransaction.Settings() {
      public Map<String, Object> current() {
        return config.hostSettingsState();
      }

      public void apply(Map<String, Object> values) throws IOException {
        config.applyHostSettings(values);
      }
    };
  }
}
