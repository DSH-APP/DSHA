package com.deepseekharness.app.util;

/**
 * 识屏模式切换的二次确认与结果反馈：只做分类和措辞，不读 Android、不写偏好、不缓存语言。
 *
 * <p>切到实体 / 虚拟都会新授权一块可读屏、可点按的屏，设置页必须先二次确认；关闭与重复选择不弹。
 * 待清理的失败说明与虚拟识屏页共用「待清理 / 核验关闭」的说法，避免两处各写一套。
 */
public final class ScreenModeFeedback {
  private ScreenModeFeedback() {}

  public enum Kind {
    SUCCESS,
    UNCHANGED,
    FAILURE
  }

  /** 一次切换后的行内状态与 toast；空串表示该处不显示。 */
  public static final class Result {
    public final Kind kind;
    public final String status;
    public final String toast;

    Result(Kind kind, String status, String toast) {
      this.kind = kind;
      this.status = status;
      this.toast = toast;
    }
  }

  /** 只有会新授权一块屏的切换才需要确认；关闭与重复选择不弹。 */
  public static boolean needsConfirmation(
      ScreenModePolicy.Mode current, ScreenModePolicy.Mode target) {
    return target != null && target != ScreenModePolicy.Mode.OFF && target != current;
  }

  public static String confirmTitle(ScreenModePolicy.Mode target) {
    return target == ScreenModePolicy.Mode.VIRTUAL
        ? UiText.choose("开启虚拟识屏？", "Turn on virtual screen?")
        : UiText.choose("开启实体识屏？", "Turn on physical screen?");
  }

  public static String confirmMessage(ScreenModePolicy.Mode target) {
    if (target == ScreenModePolicy.Mode.VIRTUAL)
      return UiText.choose(
          "开启后，助手在一块独立的虚拟屏上读取和操作，不会直接操作你的主屏。虚拟屏由本应用持有；不需要时可在本页切回「关闭」并回收。",
          "Once on, the assistant reads and operates a separate virtual screen, not your main screen. The app owns that screen; switch back to Off on this page to reclaim it.");
    return UiText.choose(
        "开启后，助手可以读取主屏当前显示的内容，并在你明确要求时点按或输入。每次任务仍需你确认开始；不需要时可在本页切回「关闭」，撤销无障碍也会自动关闭。",
        "Once on, the assistant can read what the main screen shows and tap or type only when you ask. Each task still needs your start confirmation; switch back to Off on this page anytime, and revoking accessibility turns it off too.");
  }

  public static String confirmAction(ScreenModePolicy.Mode target) {
    return target == ScreenModePolicy.Mode.VIRTUAL
        ? UiText.choose("开启虚拟识屏", "Turn on virtual")
        : UiText.choose("开启实体识屏", "Turn on physical");
  }

  /** 模式显示名；关闭状态沿用概览胶囊的状态词，保证十语种一致。 */
  public static String modeName(ScreenModePolicy.Mode mode) {
    switch (mode) {
      case PHYSICAL:
        return UiText.choose("实体识屏", "Physical screen");
      case VIRTUAL:
        return UiText.choose("虚拟识屏", "Virtual screen");
      default:
        return offStateWord();
    }
  }

  /**
   * 状态词「关闭」：中文保留「关闭 / 關閉」，其余语种改用现成的「已关闭」译条（i18n x1970619），
   * 避免复用「关闭按钮」词条时得到 Close / Fermer / Закрыть 这类动词译法。
   */
  public static String offStateWord() {
    String language = UiText.language();
    if (UiLanguagePreference.ZH.equals(language) || UiLanguagePreference.ZH_HANT.equals(language))
      return UiText.choose("关闭", "Off");
    return UiText.choose("已关闭", "Off");
  }

  /** 虚拟屏待清理时不能离开 virtual；文案与虚拟识屏页一致，指向「核验关闭」。 */
  public static String cleanupPendingToast() {
    return UiText.choose(
        "虚拟屏还在收尾（待清理），暂不能切换；请到虚拟识屏页点「核验关闭」后再试。",
        "The virtual screen is still being cleaned up; open the virtual screen page, confirm it closed, then try again.");
  }

  public static Result result(
      ScreenModePolicy.Mode target,
      ScreenModePolicy.Mode current,
      ScreenModePolicy.Switch outcome,
      String code) {
    String reason = code == null ? "" : code;
    if (outcome == null)
      return new Result(
          Kind.FAILURE,
          status(current, UiText.choose(" · 切换未完成", " · not switched")),
          UiText.choose("切换失败，请重试。", "Could not switch. Try again."));
    switch (outcome) {
      case ALLOWED:
        if (target == ScreenModePolicy.Mode.VIRTUAL && !reason.isEmpty())
          return new Result(
              Kind.FAILURE,
              status(target, UiText.choose(" · 虚拟屏未创建", " · screen not created")),
              UiText.format(
                  UiText.choose(
                      "已开启虚拟识屏，但虚拟屏未创建：%s",
                      "Virtual mode is on but the screen was not created: %s"),
                  reason));
        return new Result(
            Kind.SUCCESS,
            status(target, UiText.choose(" · 已切换", " · switched")),
            successToast(target));
      case UNCHANGED:
        if (target == ScreenModePolicy.Mode.VIRTUAL && !reason.isEmpty())
          return new Result(
              Kind.FAILURE,
              status(target, UiText.choose(" · 虚拟屏未创建", " · screen not created")),
              UiText.format(
                  UiText.choose(
                      "已开启虚拟识屏，但虚拟屏未创建：%s",
                      "Virtual mode is on but the screen was not created: %s"),
                  reason));
        return new Result(Kind.UNCHANGED, modeName(target), unchangedToast(target));
      case NOT_ADMITTED:
        return new Result(
            Kind.FAILURE,
            status(current, UiText.choose(" · 条件未满足", " · requirements not met")),
            UiText.format(
                UiText.choose("条件未满足，无法开启：%s", "Requirements not met: %s"), modeName(target)));
      case CLEANUP_PENDING:
        return new Result(
            Kind.FAILURE,
            status(current, UiText.choose(" · 待清理", " · cleanup pending")),
            cleanupPendingToast());
      default:
        return new Result(
            Kind.FAILURE,
            status(current, UiText.choose(" · 切换未完成", " · not switched")),
            UiText.choose("切换失败，请重试。", "Could not switch. Try again."));
    }
  }

  private static String status(ScreenModePolicy.Mode mode, String suffix) {
    return modeName(mode) + suffix;
  }

  private static String successToast(ScreenModePolicy.Mode target) {
    switch (target) {
      case PHYSICAL:
        return UiText.choose(
            "已切换到实体识屏；可在此页随时关闭。", "Physical screen is on; turn it off on this page anytime.");
      case VIRTUAL:
        return UiText.choose(
            "已切换到虚拟识屏；可在此页随时关闭。", "Virtual screen is on; turn it off on this page anytime.");
      default:
        return UiText.choose("已关闭识屏。", "Screen access is off.");
    }
  }

  private static String unchangedToast(ScreenModePolicy.Mode target) {
    switch (target) {
      case PHYSICAL:
        return UiText.choose("当前已是实体识屏。", "Physical screen is already on.");
      case VIRTUAL:
        return UiText.choose("当前已是虚拟识屏。", "Virtual screen is already on.");
      default:
        return UiText.choose("识屏已是关闭。", "Screen access is already off.");
    }
  }
}
