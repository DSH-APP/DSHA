package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;

/**
 * 受管 gh 的宿主根路径：只返回 rootfs 的真实（canonical）路径。
 *
 * <p>gh 是静态 Go 二进制，绕过 proroot 的路径翻译，只能用宿主绝对路径打开 CA、配置和文件参数；包装脚本本身
 * 运行在 guest 内，只能看到 proroot 绑定进 guest 的视图。proroot 绑定的是 {@code -r} 解析后的真实路径，
 * 而 Android 的 {@code getFilesDir()} 常给出 {@code /data/user/0/<包名>} 这种别名视图：环境升级后该别名
 * 在 guest 内只剩 L2S 目录，包装脚本的 CA 存在性检查失败，gh 退到缺失/过期的系统 CA，api.github.com 的
 * TLS 校验随之中断。这里统一取真实路径，使同一个字符串既是 gh 能打开的宿主路径，也是 guest 自绑定可达的路径。
 */
public final class GhHostRoot {
  private GhHostRoot() {}

  public static String resolve(File rootfs) {
    if (rootfs == null) return "";
    try {
      return rootfs.getCanonicalPath();
    } catch (IOException error) {
      return rootfs.getAbsolutePath();
    }
  }
}
