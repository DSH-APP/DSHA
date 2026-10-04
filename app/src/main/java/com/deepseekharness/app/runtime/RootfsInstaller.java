package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import java.io.*;

/** Signed bundled Python/pnpm installation only; no process selection or application lifecycle. */
final class RootfsInstaller {
  private static final Object PYTHON_LOCK = new Object(), PNPM_LOCK = new Object();

  static boolean ensurePython(Context context, File rootfs) {
    if (!rootfs.isDirectory()) return false;
    try {
      python(context, rootfs);
      return true;
    } catch (IOException | RuntimeException error) {
      android.util.Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "Ubuntu Python 安装失败: %s",
              com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error))));
      return false;
    }
  }

  static boolean ensurePnpm(Context context, File rootfs) {
    try {
      pnpm(context, rootfs);
      return true;
    } catch (IOException | RuntimeException error) {
      android.util.Log.w(
          "DSHA",
          com.deepseekharness.app.util.UiText.format(
              "离线 pnpm 安装失败: %s",
              com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error))));
      return false;
    }
  }

  /** 标准版统一用 glibc Python；不再把另一套 Termux Python 重复写入 rootfs。 */
  static void python(Context ctx, File requested) throws IOException {
    File stage = RuntimeAssetFiles.root(ctx, requested);
    synchronized (PYTHON_LOCK) {
      File py = new File(stage, "usr/bin/python3.12");
      File enc = new File(stage, "usr/lib/python3.12/encodings/__init__.py");
      if (!py.isFile() || py.length() == 0 || !enc.isFile()) {
        try (InputStream input = openPythonAsset(ctx)) {
          TarGzipExtractor.extractAuto(input, stage, 0);
        }
      }
      if (!py.isFile() || !enc.isFile())
        throw new IOException(com.deepseekharness.app.util.UiText.text("Ubuntu Python 运行环境不完整"));
      // 标准库的 C 扩展还依赖 SQLite/readline；仅有 Python 主程序并不代表它们可用。
      File sqlite = new File(stage, "usr/lib/aarch64-linux-gnu/libsqlite3.so.0");
      File readline = new File(stage, "usr/lib/aarch64-linux-gnu/libreadline.so.8");
      if (!sqlite.isFile()
          || sqlite.length() == 0
          || !readline.isFile()
          || readline.length() == 0) {
        try (InputStream input = ctx.getAssets().open("python-support.bin")) {
          TarGzipExtractor.extractAuto(input, stage, 0);
        }
      }
      if (!sqlite.isFile() || !readline.isFile())
        throw new IOException(com.deepseekharness.app.util.UiText.text("Python 动态库不完整"));
      py.setExecutable(true, false);
      File command = new File(stage, "usr/bin/python3");
      if (!command.getCanonicalFile().equals(py.getCanonicalFile())) {
        if ((command.exists() || Compat.isSymbolicLink(command)) && !command.delete())
          throw new IOException(com.deepseekharness.app.util.UiText.text("无法更新 Python 命令入口"));
        try {
          Compat.symlink("python3.12", command);
        } catch (Exception error) {
          Compat.copy(py, command, true);
          command.setExecutable(true, false);
        }
      }
      RuntimeAssetFiles.write(
          ctx,
          new File(stage, "root/.dsha-python-version"),
          "3.12-glibc-arm64\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
          false);
    }
  }

  /** 放在独立目录，不覆盖用户通过 npm 安装或升级的全局包管理器。 */
  static void pnpm(Context ctx, File requested) throws IOException {
    File stage = RuntimeAssetFiles.root(ctx, requested);
    synchronized (PNPM_LOCK) {
      File entry = new File(stage, "usr/local/lib/dsha-pnpm/bin/pnpm.cjs");
      File marker = new File(stage, "root/.dsha-pnpm-version");
      if (!entry.isFile()
          || !marker.isFile()
          || !com.deepseekharness.app.BuildConfig.BUNDLED_PNPM_VERSION.equals(
              new String(Compat.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8)
                  .trim())) {
        try (InputStream input = ctx.getAssets().open("pnpm-runtime.bin")) {
          TarGzipExtractor.extractAuto(input, stage, 0);
        }
        if (!entry.isFile())
          throw new IOException(com.deepseekharness.app.util.UiText.text("离线 pnpm 入口缺失"));
        RuntimeAssetFiles.write(
            ctx,
            marker,
            (com.deepseekharness.app.BuildConfig.BUNDLED_PNPM_VERSION + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            false);
      }
      File wrapper = new File(stage, "root/dsh-bin/pnpm");
      if (!wrapper.isFile() || wrapper.length() == 0) {
        File directory = wrapper.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs())
          throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建 pnpm 命令目录"));
        if ((wrapper.exists() || Compat.isSymbolicLink(wrapper)) && !wrapper.delete())
          throw new IOException(com.deepseekharness.app.util.UiText.text("无法更新 pnpm 命令入口"));
        RuntimeAssetFiles.write(
            ctx,
            wrapper,
            ("#!/bin/sh\n"
                    + "exec /usr/local/bin/node /usr/local/lib/dsha-pnpm/bin/pnpm.cjs \"$@\"\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            true);
      }
    }
  }

  private static InputStream openPythonAsset(Context ctx) throws IOException {
    try {
      return ctx.getAssets().open("glibc-python.bin");
    } catch (IOException ignored) {
      // 兼容旧资产构建入口，新的标准版只打包 bin。
      try {
        return ctx.getAssets().open("glibc-python.tar.gz");
      } catch (IOException missing) {
        return ctx.getAssets().open("glibc-python.tar");
      }
    }
  }

  private RootfsInstaller() {}
}
