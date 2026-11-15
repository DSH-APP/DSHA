package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外接设备事件采样的纯判定：是否发生页面变化、是否已经稳定（settle）、样本是否不完整。
 *
 * <p>判定只看同一份前台页面摘要（{@link ExternalFocusEvents#digest}），不读屏幕文本；变化按“最新事件”判断，
 * 稳定按“末尾连续多少个样本仍指向同一页面”判断。采样节奏由调用方的 adb 往返决定，因此这里不承诺毫秒级窗口，
 * 只如实返回样本数、变化发生的样本序号与末尾稳定样本数。读数失败（样本为 null）记为 partial，不把缺样本说成没有变化。
 */
public final class ExternalWatchPolicy {
  public static final int MIN_SAMPLES = 2;
  public static final int MAX_SAMPLES = 12;
  public static final int DEFAULT_SAMPLES = 6;
  public static final int DEFAULT_QUIET_SAMPLES = 2;

  private ExternalWatchPolicy() {}

  public enum State {
    CHANGED,
    UNCHANGED,
    UNCERTAIN
  }

  /**
   * 一次采样窗口的结论。{@code samples} 是实际可用的样本数；{@code changedAtSample} 是相对整个采样列表的 0 基序号（-1
   * 表示没有变化）；{@code quietRun} 是末尾连续稳定样本数；{@code partial} 表示计划样本没有全部拿到。
   */
  public record Outcome(
      State state,
      boolean settled,
      boolean sawChange,
      int samples,
      int changedAtSample,
      int quietRun,
      int newEvents,
      ExternalFocusEvents.Snapshot foreground,
      String digest,
      boolean partial) {}

  /**
   * @param samples 每个元素是一次采样解析出的页面事件；读取失败的样本传 null
   * @param targetEpoch 目标代次（重新绑定会改变摘要，旧 since 自动失效）
   * @param mode 观察模式（events / window），参与摘要
   * @param sinceDigest 调用方上次拿到的摘要；空串表示以本窗口第一个样本为基线
   * @param quietSamples 末尾至少连续多少个样本指向同一页面才算 settled
   */
  public static Outcome analyze(
      List<List<ExternalFocusEvents.Event>> samples,
      long targetEpoch,
      String mode,
      String sinceDigest,
      int quietSamples) {
    int planned = samples == null ? 0 : samples.size();
    List<ExternalFocusEvents.Snapshot> snapshots = new ArrayList<>(planned);
    int usable = 0;
    int baseIndex = -1;
    ExternalFocusEvents.Snapshot baseline = null;
    for (int i = 0; i < planned; i++) {
      List<ExternalFocusEvents.Event> events = samples.get(i);
      if (events == null) {
        snapshots.add(null);
        continue;
      }
      usable++;
      ExternalFocusEvents.Snapshot snapshot = ExternalFocusEvents.latest(events);
      snapshots.add(snapshot);
      if (baseline == null && snapshot != null) {
        baseline = snapshot;
        baseIndex = i;
      }
    }
    if (usable < MIN_SAMPLES || baseline == null) {
      return new Outcome(
          State.UNCERTAIN, false, false, usable, -1, 0, 0, baseline, "", planned > usable);
    }
    String reference =
        sinceDigest == null || sinceDigest.isEmpty()
            ? digest(targetEpoch, mode, baseline)
            : sinceDigest;
    ExternalFocusEvents.Snapshot finalSnapshot = baseline;
    for (int i = baseIndex + 1; i < planned; i++) {
      ExternalFocusEvents.Snapshot snapshot = snapshots.get(i);
      if (snapshot != null) finalSnapshot = snapshot;
    }
    String finalDigest = digest(targetEpoch, mode, finalSnapshot);
    int changedAt = -1;
    for (int i = baseIndex; i < planned; i++) {
      ExternalFocusEvents.Snapshot snapshot = snapshots.get(i);
      if (snapshot == null) continue;
      if (!digest(targetEpoch, mode, snapshot).equals(reference)) {
        changedAt = i;
        break;
      }
    }
    Set<String> seen = new HashSet<>();
    for (ExternalFocusEvents.Event event : samples.get(baseIndex)) seen.add(key(event));
    int newEvents = 0;
    for (int i = baseIndex + 1; i < planned; i++) {
      List<ExternalFocusEvents.Event> events = samples.get(i);
      if (events == null) continue;
      for (ExternalFocusEvents.Event event : events) {
        if (event != null && seen.add(key(event))) newEvents++;
      }
    }
    int quietRun = 0;
    for (int i = planned - 1; i >= baseIndex; i--) {
      ExternalFocusEvents.Snapshot snapshot = snapshots.get(i);
      if (snapshot == null) break;
      if (!digest(targetEpoch, mode, snapshot).equals(finalDigest)) break;
      quietRun++;
    }
    boolean settled = quietRun >= Math.max(1, quietSamples);
    State state = changedAt >= 0 ? State.CHANGED : State.UNCHANGED;
    return new Outcome(
        state,
        settled,
        newEvents > 0,
        usable,
        changedAt,
        quietRun,
        newEvents,
        finalSnapshot,
        finalDigest,
        planned > usable);
  }

  /** since 只接受摘要格式（16–64 位小写十六进制）或空串；不认识的形状由调用方拒绝，不做模糊匹配。 */
  public static boolean validSince(String value) {
    return value == null || value.isEmpty() || value.matches("[0-9a-f]{16,64}");
  }

  private static String digest(
      long targetEpoch, String mode, ExternalFocusEvents.Snapshot snapshot) {
    return ExternalFocusEvents.digest(
        targetEpoch, mode, snapshot.packageName(), snapshot.activity());
  }

  private static String key(ExternalFocusEvents.Event event) {
    return event.tag() + "|" + event.deviceTimeMs() + "|" + event.component();
  }
}
