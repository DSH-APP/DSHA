package com.deepseekharness.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

/**
 * 手机公共存储访问授权的唯一判据。
 *
 * <p>未授权时：容器不挂载 {@code /storage/emulated/0}，网页文件选择器也拒绝打开系统文档选择器，
 * 只能使用应用私有目录。这样“没同意就读取手机文件”在两条路径上都被挡住。</p>
 */
public final class StorageAccess {
  private StorageAccess() {}

  /** Android 11+ 用“所有文件访问”；更早版本还需要读/写外部存储的运行时权限。 */
  public static boolean granted(Context context) {
    if (context == null) return false;
    if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
    return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
  }
}
