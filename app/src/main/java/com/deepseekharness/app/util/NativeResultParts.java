package com.deepseekharness.app.util;

/**
 * 数据页结果卡（原生数据导出 / 恢复 / 救援）的字段拼装：计数行与错误合并。
 *
 * <p>纯逻辑，不 import Android API；模板走 {@link UiText}，十语目录已有「%s 项 · %s」与「失败」，
 * 不新增翻译键。错误按固定顺序合并为多行：主错误在前、投影错误在后，空串不占行。
 */
public final class NativeResultParts {
  private NativeResultParts() {}

  /** 「N 项 · 字节」；字节文本由调用方用既有 Fmt.bytes 渲染。 */
  public static String meta(long entries, String bytes) {
    return UiText.format("%s 项 · %s", entries, bytes == null ? "" : bytes);
  }

  /** 主错误与投影错误的合并文本；两者都为空时返回空串（调用方据此隐藏错误块）。 */
  public static String problems(String failure, String projection) {
    String first = failure == null ? "" : failure.trim();
    String second = projection == null ? "" : projection.trim();
    if (first.isEmpty()) return second;
    if (second.isEmpty()) return first;
    return first + "\n" + second;
  }
}
