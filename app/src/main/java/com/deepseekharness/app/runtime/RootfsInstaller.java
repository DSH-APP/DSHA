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
    GuestScriptBudget.checkpoint();
    File stage = RuntimeAssetFiles.root(ctx, requested);
    synchronized (PYTHON_LOCK) {
      GuestScriptBudget.checkpoint();
      File py = new File(stage, "usr/bin/python3.12");
      File enc = new File(stage, "usr/lib/python3.12/encodings/__init__.py");
      if (!py.isFile() || py.length() == 0 || !enc.isFile()) {
        try (InputStream input = openPythonAsset(ctx)) {
          TarGzipExtractor.extractAuto(input, stage, 0);
        }
      }
      if (!py.isFile() || !enc.isFile())
        throw new IOException(com.deepseekharness.app.util.UiText.text("Ubuntu Python 运行环境不完整"));
      GuestScriptBudget.checkpoint();
      // 标准库的 C 扩展还依赖 SQLite/readline；仅有 Python 主程序并不代表它们可用。
      // 与插件查询就绪共用 guest 判据：apt/dpkg 或外部 root 换成的 soname 链接只要在容器内指向非空普通文件即可用。
      String sqlite = "usr/lib/aarch64-linux-gnu/libsqlite3.so.0";
      String readline = "usr/lib/aarch64-linux-gnu/libreadline.so.8";
      boolean sqliteReady = supportReady(stage, sqlite),
          readlineReady = supportReady(stage, readline);
      if (!sqliteReady || !readlineReady) {
        // 悬空或越界的链接会让签名归档以 TAR_FILE_CONFLICT 拒绝写入；只移除这两个链接本身后再写回普通文件。
        // 已可用的一项（含 apt 的有效链接）不重写，避免另一项缺失时因链接冲突整体失败。
        if (!sqliteReady) dropBrokenLink(stage, sqlite);
        if (!readlineReady) dropBrokenLink(stage, readline);
        try (InputStream input = ctx.getAssets().open("python-support.bin")) {
          TarGzipExtractor.extractSelected(
              input,
              stage,
              0,
              name ->
                  !(sqliteReady && name.equals(sqlite))
                      && !(readlineReady && name.equals(readline)));
        }
      }
      if (!supportReady(stage, sqlite) || !supportReady(stage, readline))
        throw new IOException(com.deepseekharness.app.util.UiText.text("Python 动态库不完整"));
      GuestScriptBudget.checkpoint();
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

  /** 与 RuntimeTools.queryReady 同一判据（rootfs 内按 guest 语义解析出的本 UID 可读普通文件），动态库另要求非空。 */
  private static boolean supportReady(File stage, String relative) {
    try {
      var file =
          RuntimeTools.guestFile(
              new com.deepseekharness.app.backup.AndroidBackupFileSystem(), stage, relative);
      return file.regular() && file.node.size > 0 && file.file.canRead();
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  /** 只删除无法解析为普通文件的链接本身（父目录逐级 NOFOLLOW）；普通文件、目录与可用链接不动。 */
  private static void dropBrokenLink(File stage, String relative) {
    try {
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      File link = fs.child(stage, relative);
      if (fs.stat(link).type.equals("LINK") && !supportReady(stage, relative)) fs.delete(link);
    } catch (IOException | RuntimeException unavailable) {
      // 留给随后的签名归档解包报告实际冲突，不在这里扩大删除范围。
    }
  }

  /** 放在独立目录，不覆盖用户通过 npm 安装或升级的全局包管理器。 */
  static void pnpm(Context ctx, File requested) throws IOException {
    GuestScriptBudget.checkpoint();
    File stage = RuntimeAssetFiles.root(ctx, requested);
    synchronized (PNPM_LOCK) {
      GuestScriptBudget.checkpoint();
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      fs.parents(stage, "usr/local/lib/dsha-pnpm/bin/pnpm.cjs");
      fs.parents(stage, "usr/local/lib/dsha-pnpm/dist/pnpm.cjs");
      fs.parents(stage, "root/.dsha-pnpm-version");
      File entry = new File(stage, "usr/local/lib/dsha-pnpm/bin/pnpm.cjs");
      File marker = new File(stage, "root/.dsha-pnpm-version");
      File distribution = new File(stage, "usr/local/lib/dsha-pnpm/dist/pnpm.cjs");
      File metadata = new File(stage, "usr/local/lib/dsha-pnpm/package.json");
      if (!entry.isFile()
          || Compat.isSymbolicLink(entry)
          || !distribution.isFile()
          || Compat.isSymbolicLink(distribution)
          || !pnpmMetadataCurrent(fs, metadata)
          || !marker.isFile()
          || Compat.isSymbolicLink(marker)
          || marker.length() > 128
          || !com.deepseekharness.app.BuildConfig.BUNDLED_PNPM_VERSION.equals(
              new String(Compat.readAllBytes(marker), java.nio.charset.StandardCharsets.UTF_8)
                  .trim())) {
        try (InputStream input = ctx.getAssets().open("pnpm-runtime.bin")) {
          TarGzipExtractor.extractAuto(input, stage, 0);
        }
        if (!entry.isFile() || !distribution.isFile() || !pnpmMetadataCurrent(fs, metadata))
          throw new IOException(com.deepseekharness.app.util.UiText.text("离线 pnpm 入口缺失"));
        RuntimeAssetFiles.write(
            ctx,
            marker,
            (com.deepseekharness.app.BuildConfig.BUNDLED_PNPM_VERSION + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            false);
      }
      File wrapper = new File(stage, "root/dsh-bin/pnpm");
      // A nonempty stale wrapper or a lost executable bit is not a prepared command.
      // The NOFOLLOW atomic publisher repairs regular files and rejects link targets.
      // link2symlink cannot preserve Node's package-relative imports from pnpm's
      // content store. Use copies by default, while explicit env/CLI choices win.
      RuntimeAssetFiles.write(
          ctx,
          wrapper,
          ("#!/bin/sh\n"
                  + "export npm_config_package_import_method=\"${npm_config_package_import_method-"
                  + "${NPM_CONFIG_PACKAGE_IMPORT_METHOD-copy}}\"\n"
                  + "exec /usr/local/bin/node /usr/local/lib/dsha-pnpm/bin/pnpm.cjs \"$@\"\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          true);
    }
  }

  private static boolean pnpmMetadataCurrent(
      com.deepseekharness.app.backup.BackupFileSystem fs, File metadata) {
    try {
      if (!fs.stat(metadata).type.equals("FILE")) return false;
      var value =
          new org.json.JSONObject(
              new String(fs.small(metadata, 65536), java.nio.charset.StandardCharsets.UTF_8));
      return "pnpm".equals(value.optString("name"))
          && com.deepseekharness.app.BuildConfig.BUNDLED_PNPM_VERSION.equals(
              value.optString("version"));
    } catch (IOException | org.json.JSONException invalid) {
      return false;
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
