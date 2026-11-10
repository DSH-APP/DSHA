package com.deepseekharness.app.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.Nullable;
import java.util.List;

/**
 * SAF 目录选择：明确交给系统 DocumentsUI，而不是被 OEM 文件管理器接管的默认选择器。
 *
 * <p>厂商文件管理器常把 {@code ACTION_OPEN_DOCUMENT_TREE} 的默认处理程序改成自家界面；
 * 那种界面往往看不到 SD 卡 / USB 等存储挂载点，也未必按 SAF 合同返回可持久化的 tree 授权。
 * 这里把意图显式指向系统文档选择器（{@code com.android.documentsui} /
 * {@code com.google.android.documentsui}），没有系统选择器时才退回默认解析。{@link Status}
 * 让界面在默认处理程序被改写时给出可操作的提示与入口。
 */
final class SystemDocumentPicker {
  /** AOSP 与 Google 的系统文档选择器包名；厂商定制一般仍是其中之一。 */
  private static final String[] SYSTEM_PACKAGES = {
    "com.android.documentsui", "com.google.android.documentsui"
  };

  private SystemDocumentPicker() {}

  /** 本机 SAF 目录选择的处理情况。 */
  static final class Status {
    /** 可见、已导出且启用的系统 DocumentsUI 入口；null 表示本机没有。 */
    final ComponentName system;

    /** 系统默认解析到的处理包名；null 表示没有任何可用处理程序。 */
    final String defaultPackage;

    /** 默认处理程序的显示名，用于提示；可能为 null。 */
    final String defaultLabel;

    Status(ComponentName system, @Nullable String defaultPackage, @Nullable String defaultLabel) {
      this.system = system;
      this.defaultPackage = defaultPackage;
      this.defaultLabel = defaultLabel;
    }

    boolean hasSystem() {
      return system != null;
    }

    /**
     * 默认处理程序不是系统 DocumentsUI，即厂商接管。
     *
     * <p>系统意图选择器（{@code android} / {@code com.android.intentresolver}）不算接管：
     * 用户本来就要在多个处理程序里挑一个，直接强制系统选择器即可，不用额外提示。
     */
    boolean overridden() {
      return system != null
          && defaultPackage != null
          && !isSystemPackage(defaultPackage)
          && !isResolver(defaultPackage);
    }

    /** 给用户看的默认处理程序名字，缺失时退回包名。 */
    String defaultName() {
      if (defaultLabel != null && !defaultLabel.isEmpty()) return defaultLabel;
      return defaultPackage == null ? "" : defaultPackage;
    }
  }

  /** 查询可见的系统选择器与默认处理程序；调用方在点击时执行一次。 */
  static Status inspect(Context context) {
    PackageManager manager = context.getPackageManager();
    ComponentName system = systemComponent(context);
    Intent tree = treeIntent(null);
    ResolveInfo resolved = manager.resolveActivity(tree, PackageManager.MATCH_DEFAULT_ONLY);
    ActivityInfo fallback = resolved == null ? null : resolved.activityInfo;
    String packageName = fallback == null ? null : fallback.packageName;
    String label = null;
    if (packageName != null) {
      try {
        label =
            String.valueOf(manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)));
      } catch (PackageManager.NameNotFoundException ignored) {
        // 解析结果的包在查询后被卸载或隐藏：保留包名，提示里不显示应用名。
      } catch (RuntimeException ignored) {
        // 个别 ROM 的应用信息查询会抛 SecurityException；提示不能因此中断选择。
      }
    }
    return new Status(system, packageName, label);
  }

  /** 可见、已导出且启用的系统 DocumentsUI 入口；null 表示本机没有。 */
  @Nullable
  static ComponentName systemComponent(Context context) {
    List<ResolveInfo> handlers =
        context.getPackageManager().queryIntentActivities(treeIntent(null), 0);
    for (ResolveInfo candidate : handlers) {
      ActivityInfo activity = candidate.activityInfo;
      if (activity == null || !activity.exported || !activity.enabled) continue;
      if (!isSystemPackage(activity.packageName)) continue;
      return new ComponentName(activity.packageName, activity.name);
    }
    return null;
  }

  /** 把树选择意图明确交给系统 DocumentsUI；找不到系统选择器时保持原意图。 */
  static Intent preferSystem(Context context, Intent intent) {
    ComponentName system = systemComponent(context);
    if (system != null) intent.setComponent(system);
    return intent;
  }

  /** 树选择意图；{@code initial} 是 DocumentsUI 的起始目录，可为 null。 */
  static Intent treeIntent(@Nullable Uri initial) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    if (initial != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
    return intent;
  }

  static boolean isSystemPackage(String packageName) {
    for (String known : SYSTEM_PACKAGES) if (known.equals(packageName)) return true;
    return false;
  }

  /** 系统意图选择器不是被厂商改写的默认处理程序。 */
  static boolean isResolver(String packageName) {
    return "android".equals(packageName) || "com.android.intentresolver".equals(packageName);
  }

  /**
   * 系统 DocumentsUI 的显式树选择合同（与框架的 OpenDocumentTree 合同等价）。
   *
   * <p>结果解析沿用框架合同语义；只在创建意图时把 component 指向 DocumentsUI，因此返回的
   * tree URI 与系统选择器正常授权完全一致，授权簿与路径护栏都不需要改动。本类只创建意图，
   * 不负责启动：调用方必须经 {@code RecentsPrivacyGuard.launch} 启动，严格模式才不会在
   * 结果在途时结束 UI 任务。
   */
  static final class TreeContract extends ActivityResultContract<Uri, Uri> {
    @Override
    public Intent createIntent(Context context, @Nullable Uri input) {
      return preferSystem(context, treeIntent(input));
    }

    @Override
    public Uri parseResult(int resultCode, @Nullable Intent intent) {
      return resultCode == Activity.RESULT_OK && intent != null ? intent.getData() : null;
    }
  }
}
