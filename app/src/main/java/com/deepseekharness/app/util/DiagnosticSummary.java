package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自检页的纯推导：结果分组顺序、状态卡语气与计数、正常项列数。
 *
 * <p>只看 {@link DiagnosticReport.Health} 枚举，不从展示文案推断健康度；手机与平板布局共用同一份结果。
 */
public final class DiagnosticSummary {
  private DiagnosticSummary() {}

  /** 结果分组：需要处理置顶，其次未核验，最后折叠的正常项。 */
  public enum Group {
    ATTENTION,
    UNVERIFIED,
    OK
  }

  /** 状态卡语气；只映射到 shell_* 语义色与图标，不表示具体文案。 */
  public enum Tone {
    IDLE,
    RUNNING,
    GOOD,
    ATTENTION,
    UNVERIFIED,
    FAILED
  }

  public static Group group(DiagnosticReport.Health health) {
    if (health == DiagnosticReport.Health.NEEDS_ATTENTION) return Group.ATTENTION;
    if (health == DiagnosticReport.Health.UNVERIFIED) return Group.UNVERIFIED;
    return Group.OK;
  }

  /** 返回按分组稳定排序后的下标；同组内保持探针原有顺序。 */
  public static List<Integer> order(List<DiagnosticReport.Health> healths) {
    List<Integer> result = new ArrayList<>(healths.size());
    for (Group wanted : Group.values())
      for (int i = 0; i < healths.size(); i++) if (group(healths.get(i)) == wanted) result.add(i);
    return Collections.unmodifiableList(result);
  }

  public static int count(List<DiagnosticReport.Health> healths, Group wanted) {
    int n = 0;
    for (DiagnosticReport.Health health : healths) if (group(health) == wanted) n++;
    return n;
  }

  /**
   * 状态卡语气。运行中优先；采集失败（或修复失败）即使已有部分结果也报失败；
   * 否则按「需要处理 → 未核验 → 正常」取最严重者。没有任何结果时保持初始态。
   */
  public static Tone tone(boolean running, boolean failed, List<DiagnosticReport.Health> healths) {
    if (running) return Tone.RUNNING;
    if (failed) return Tone.FAILED;
    if (healths.isEmpty()) return Tone.IDLE;
    if (count(healths, Group.ATTENTION) > 0) return Tone.ATTENTION;
    if (count(healths, Group.UNVERIFIED) > 0) return Tone.UNVERIFIED;
    return Tone.GOOD;
  }

  /**
   * 正常项列数：结果区可用宽度（dp）够放两列且字体未放大时用两列。
   * 1.3 倍字体下标题与状态需要换行，保持单列避免截断。
   */
  public static int okColumns(int availableWidthDp, float fontScale) {
    return availableWidthDp >= 560 && fontScale < 1.15f ? 2 : 1;
  }
}
