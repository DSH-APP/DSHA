package com.deepseekharness.app.util;

/**
 * 后台任务页的纯展示逻辑：任务种类归类、去向页面与耗时文字。
 *
 * <p>种类来自 {@code RuntimeTasks.begin(kind)} 的固定中文登记名（也接受已渲染的英文），只决定图标与
 * “点按去哪里”，不影响任务本身；不认识的种类归为 OTHER，行仍显示但不可点。
 */
public final class BackgroundTaskModel {
  private BackgroundTaskModel() {}

  public enum Category {
    MAINTENANCE,
    BACKUP,
    TERMINAL,
    PLUGIN,
    CONTAINER,
    SKILL,
    WORKS,
    TOOLS,
    OTHER
  }

  /** 行点按后的去向；NONE 表示只读展示。 */
  public enum Destination {
    MAINTENANCE_DETAIL,
    DATA,
    TERMINAL,
    PLUGINS,
    INSTALL,
    NONE
  }

  public static Category classify(String kind) {
    String value = kind == null ? "" : kind.trim();
    return switch (value) {
      case "数据维护", "Data maintenance" -> Category.MAINTENANCE;
      case "安装与环境", "Installation and environment" -> Category.MAINTENANCE;
      case "终端", "Terminal", "Terminals" -> Category.TERMINAL;
      case "插件管理", "Plugin management" -> Category.PLUGIN;
      case "容器命令", "后台容器进程", "Container command", "Background container process" ->
          Category.CONTAINER;
      case "技能读取", "技能导入", "Reading skills", "Importing skills" -> Category.SKILL;
      case "作品文件浏览", "作品文件导出", "Browsing work files", "Exporting work files" -> Category.WORKS;
      case "基础工具安装", "Installing base tools" -> Category.TOOLS;
      default -> Category.OTHER;
    };
  }

  public static Destination destination(Category category) {
    if (category == null) return Destination.NONE;
    return switch (category) {
      case MAINTENANCE -> Destination.MAINTENANCE_DETAIL;
      case BACKUP -> Destination.DATA;
      case TERMINAL -> Destination.TERMINAL;
      case PLUGIN -> Destination.PLUGINS;
      case TOOLS -> Destination.INSTALL;
      default -> Destination.NONE;
    };
  }

  /**
   * 已运行时长：不足 1 分钟显示秒，不足 1 小时显示分秒，更长显示时分。负值按 0。
   *
   * <p>复用 {@link Fmt} 的时长模板：另建渲染结果相同的模板（如「%d 秒」对「%s 秒」）会让
   * UiStateText 切换语言时选错译文（UiStateTextTest）。
   */
  public static String elapsed(long millis, String language) {
    long seconds = Math.max(0, millis) / 1000;
    if (seconds < 60)
      return String.format(
          java.util.Locale.ROOT, UiText.choose(language, "%s 秒", "%s seconds"), seconds);
    long minutes = seconds / 60;
    if (minutes < 60)
      return String.format(
          java.util.Locale.ROOT,
          UiText.choose(language, "%s 分 %s 秒", "%s min %s s"),
          minutes,
          seconds % 60);
    return String.format(
        java.util.Locale.ROOT,
        UiText.choose(language, "%s 时 %s 分", "%s h %s min"),
        minutes / 60,
        minutes % 60);
  }
}
