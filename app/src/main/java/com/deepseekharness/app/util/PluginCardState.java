package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件卡片的纯推导：来源分类、主状态语气与提示条顺序。
 *
 * <p>只接收已核验的布尔 / 字符串事实，不读磁盘、不执行插件操作；列表卡片、平板左栏与详情窗格共用同一份结果，
 * 避免两处各自判断后出现颜色或提示不一致。
 */
public final class PluginCardState {
  private PluginCardState() {}

  /** 来源分类，决定图标与徽标；优先级与历史说明文案一致：临时 → 官方 → 随 DSH → 内置 → 安装目录 → 第三方。 */
  public enum Kind {
    DYNAMIC,
    OFFICIAL,
    RUNTIME,
    BUILTIN,
    LOCATED,
    THIRD
  }

  /** 主状态语气；只映射到 shell_* 语义色，不表示具体文案。 */
  public enum Tone {
    GOOD,
    ACCENT,
    WARN,
    BAD,
    MUTED
  }

  /** 提示条种类；文案与动作由界面层渲染。 */
  public enum Note {
    RESTART,
    UPDATE,
    MESSAGE
  }

  public static Kind kind(
      boolean dynamic,
      boolean official,
      boolean runtimeProvided,
      boolean builtin,
      boolean runtimeLocated) {
    if (dynamic) return Kind.DYNAMIC;
    if (official) return Kind.OFFICIAL;
    if (runtimeProvided) return Kind.RUNTIME;
    if (builtin) return Kind.BUILTIN;
    if (runtimeLocated) return Kind.LOCATED;
    return Kind.THIRD;
  }

  /** 已确认的当前运行结论；待核验（stale）或未知不算已加载，也不算失败。 */
  public static boolean runtimeLoaded(String runtimeState, boolean stale) {
    if (stale || runtimeState == null) return false;
    return switch (runtimeState) {
      case "loaded", "running", "active" -> true;
      default -> false;
    };
  }

  public static boolean runtimeFailed(String runtimeState, boolean stale) {
    return !stale && "failed".equals(runtimeState);
  }

  /**
   * 主状态语气：排队中与实体缺失先提示处理；已启用时按当前 Web 证据区分好 / 坏 / 进行中；禁用为弱化色。
   */
  public static Tone tone(
      boolean pending,
      boolean available,
      boolean enabled,
      boolean dynamic,
      boolean loaded,
      boolean failed) {
    if (pending || !available) return Tone.WARN;
    if (enabled || dynamic) {
      if (failed) return Tone.BAD;
      return loaded ? Tone.GOOD : Tone.ACCENT;
    }
    return Tone.MUTED;
  }

  /**
   * 配置徽章的语义语气：只决定配色，不改写文案，也不参与「当前 Web 是否已加载」的判定。
   *
   * <p>已启用用成功色、已禁用用中性色；排队、实体缺失与元数据错误用警示色；可开启用强调色。
   */
  public static Tone configTone(
      boolean pending,
      boolean available,
      boolean enabled,
      boolean detected,
      boolean dynamic,
      boolean metadataError) {
    if (pending || !available || metadataError) return Tone.WARN;
    if (dynamic) return Tone.MUTED;
    if (enabled) return Tone.GOOD;
    return detected ? Tone.ACCENT : Tone.MUTED;
  }

  /**
   * 当前 Web 徽章的语义语气：已加载成功、未加载中性、待核验/未知警示、加载失败危险。
   *
   * <p>这里只根据既有结论着色；待核验与未知不会被当成已加载，也不会被当成失败。
   */
  public static Tone runtimeTone(String runtimeState, boolean stale) {
    if (stale) return Tone.WARN;
    if (runtimeState == null) return Tone.WARN;
    return switch (runtimeState) {
      case "loaded", "running", "active" -> Tone.GOOD;
      case "failed" -> Tone.BAD;
      case "pending", "waiting", "loading" -> Tone.ACCENT;
      case "stopped", "unloaded", "disabled", "inactive" -> Tone.MUTED;
      default -> Tone.WARN;
    };
  }

  /**
   * 提示条按「需要用户处理的程度」排序：重启生效 → 可更新 → 最近一次操作消息。
   *
   * <p>排队中不提示更新，避免与正在执行的任务并列出现两个可点的变更入口。
   */
  public static List<Note> notes(
      boolean restartRequired, boolean updateAvailable, boolean pending, boolean hasMessage) {
    List<Note> notes = new ArrayList<>();
    if (restartRequired) notes.add(Note.RESTART);
    if (updateAvailable && !pending) notes.add(Note.UPDATE);
    if (hasMessage) notes.add(Note.MESSAGE);
    return notes;
  }
}
