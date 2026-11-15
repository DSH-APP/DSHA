package com.deepseekharness.app.util;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 敏感界面分档策略（T119，纯逻辑）。
 *
 * <p>约定（用户 2026-11-09 决定）：
 *
 * <ul>
 *   <li>密码文本读写是硬阻断，不能由本策略关闭；本策略只决定「整屏如何应对」。
 *   <li>验证码 / 支付 / 系统安全 / 无法识别的敏感界面默认 {@link Mode#ASK}：本次动作仍需显式确认。
 *   <li>可配置为 {@link Mode#HANDOFF}：暂停助手并把界面交给用户本人操作，不派发输入。
 *   <li>{@link Mode#OFF} 只关闭整屏分档，回到普通屏幕确认策略；密码文本读写硬阻断不受影响。
 *   <li>应用名单默认空；精确包名，有上限；名单只改变确认/交接方式，不解除密码文本硬阻断。
 *   <li>「全部允许」（{@code ScreenConfirmationPolicy.ALLOW_ALL}）只省普通确认，不能把 ASK 降成静默放行。
 * </ul>
 *
 * <p>误伤与风险：包名/关键词分类只是保守提示，不是「安全证明」。自定义控件、语言/ROM 差异、无障碍关闭都会
 * 让分类失败；失败方向保持「多问一次 / 交给用户」，不回退成永久拒绝。同 UID 的插件与终端可以绕过受管入口，
 * 这本来就不是内核沙箱。
 */
public final class SensitiveScreenPolicy {
  /** 敏感界面类别。PASSWORD 的文本读写另由输入层硬阻断，不在此决定。 */
  public enum Category {
    PASSWORD,
    OTP,
    PAYMENT,
    SYSTEM_SECURITY,
    SECURE_UNKNOWN
  }

  /**
   * 整屏应对方式。{@link #OFF} 只关闭「整屏分档/交接」这一层，按普通屏幕确认策略处理；密码文本读写硬阻断
   * 由输入层执行，与本开关无关。默认 {@link #ASK}，没有「静默放行敏感类」的档位。
   */
  public enum Mode {
    OFF,
    ASK,
    HANDOFF
  }

  /** 一次动作的结论：放行（应用名单豁免）、询问、交接。 */
  public enum Decision {
    ALLOW,
    ASK,
    HANDOFF
  }

  public static final int MAX_APPS_PER_LIST = 64;
  static final int MAX_ENCODED = 100_000;
  private static final String SCHEMA = "1";

  private final EnumMap<Category, Mode> modes;
  private final Set<String> trustApps;
  private final Set<String> handoffApps;
  private final long revision;

  private SensitiveScreenPolicy(
      EnumMap<Category, Mode> modes,
      Set<String> trustApps,
      Set<String> handoffApps,
      long revision) {
    if (modes == null || trustApps == null || handoffApps == null || revision < 0)
      throw new IllegalArgumentException();
    this.modes = new EnumMap<>(Category.class);
    for (Category category : Category.values())
      this.modes.put(category, modes.getOrDefault(category, Mode.ASK));
    this.trustApps = Collections.unmodifiableSet(new LinkedHashSet<>(trustApps));
    this.handoffApps = Collections.unmodifiableSet(new LinkedHashSet<>(handoffApps));
    this.revision = revision;
  }

  /** 默认：所有类别 ASK，应用名单为空。 */
  public static SensitiveScreenPolicy defaults() {
    return new SensitiveScreenPolicy(new EnumMap<>(Category.class), Set.of(), Set.of(), 0);
  }

  public long revision() {
    return revision;
  }

  public Mode mode(Category category) {
    if (category == null) return Mode.ASK;
    return modes.getOrDefault(category, Mode.ASK);
  }

  public Set<String> trustApps() {
    return trustApps;
  }

  public Set<String> handoffApps() {
    return handoffApps;
  }

  /**
   * 应用名单优先于类别；名单只改变确认方式，密码文本硬阻断不在这里。
   *
   * <p>{@code category == null} 只表示「调用方还没有分类结果 / 不需要分类」，返回 {@link Decision#ALLOW}
   * 表示本层不接管，沿用普通屏幕确认策略；它**不是**「已确认安全」。调用方必须先判定类别，只有拿到具体类别
   * 才调用本方法；不支持用 null 表示「跳过确认」。
   */
  public Decision decide(Category category, String packageName) {
    if (category == null) return Decision.ALLOW;
    if (packageName != null && trustApps.contains(packageName)) return Decision.ALLOW;
    if (packageName != null && handoffApps.contains(packageName)) return Decision.HANDOFF;
    switch (mode(category)) {
      case OFF:
        return Decision.ALLOW;
      case HANDOFF:
        return Decision.HANDOFF;
      case ASK:
      default:
        return Decision.ASK;
    }
  }

  /** 改一个类别的应对方式；修订号递增，供授权请求与一次性确认失效。 */
  public SensitiveScreenPolicy withMode(Category category, Mode mode) {
    if (category == null || mode == null) return this;
    EnumMap<Category, Mode> next = new EnumMap<>(modes);
    next.put(category, mode);
    return new SensitiveScreenPolicy(next, trustApps, handoffApps, nextRevision());
  }

  /** 替换应用名单；同一包名不能同时在两个名单里，总数/单名单都有上限。 */
  public SensitiveScreenPolicy withApps(Set<String> trust, Set<String> handoff) {
    Set<String> nextTrust = checkedPackages(trust);
    Set<String> nextHandoff = checkedPackages(handoff);
    for (String pkg : nextTrust)
      if (nextHandoff.contains(pkg)) throw new IllegalArgumentException("SENSITIVE_APP_DUPLICATE");
    return new SensitiveScreenPolicy(modes, nextTrust, nextHandoff, nextRevision());
  }

  /**
   * 仅用于修订号栅栏：保持类别与名单不变，把修订号抬到指定值（不得回退）。
   * 记录损坏被解码成默认策略后，进程内仍必须保证合并修订号单调。
   */
  public SensitiveScreenPolicy withRevision(long revision) {
    if (revision < this.revision)
      throw new IllegalArgumentException("SENSITIVE_REVISION_BACKWARDS");
    return new SensitiveScreenPolicy(modes, trustApps, handoffApps, revision);
  }

  /** 恢复默认（全部 ASK、名单为空），但仍递增修订号，保证旧确认不会复活。 */
  public SensitiveScreenPolicy reset() {
    return new SensitiveScreenPolicy(
        new EnumMap<>(Category.class), Set.of(), Set.of(), nextRevision());
  }

  private long nextRevision() {
    return revision >= Long.MAX_VALUE - 1 ? Long.MAX_VALUE : revision + 1;
  }

  private static Set<String> checkedPackages(Set<String> values) {
    Set<String> result = new LinkedHashSet<>();
    if (values == null) return result;
    for (String pkg : values) {
      if (!ScreenConfirmationPolicy.validPackage(pkg))
        throw new IllegalArgumentException("INVALID_SENSITIVE_APP");
      result.add(pkg);
      if (result.size() > MAX_APPS_PER_LIST)
        throw new IllegalArgumentException("SENSITIVE_APP_LIMIT");
    }
    return result;
  }

  /** 有界、自描述的持久化格式；页面类别顺序固定，任何多余/缺失/重复项都按损坏处理。 */
  public String encode() {
    StringBuilder value = new StringBuilder(SCHEMA).append('\n').append(revision).append('\n');
    for (Category category : Category.values()) {
      value.append(category.name()).append('=').append(mode(category).name()).append('\n');
    }
    for (String pkg : trustApps) value.append("T=").append(pkg).append('\n');
    for (String pkg : handoffApps) value.append("H=").append(pkg).append('\n');
    return value.toString();
  }

  /** 严格解码；任何格式问题（含未来版本、超长、重复项）都回默认策略，绝不静默降级成放行。 */
  public static SensitiveScreenPolicy decode(String value) {
    try {
      if (value == null || value.isEmpty() || value.length() > MAX_ENCODED) return defaults();
      String[] lines = value.split("\n", -1);
      // 行尾换行是 encode 的一部分：最后一项必须为空串。
      if (lines.length < 2 + Category.values().length + 1
          || lines.length > 2 + Category.values().length + 1 + 2 * MAX_APPS_PER_LIST
          || !lines[lines.length - 1].isEmpty()
          || !SCHEMA.equals(lines[0])) return defaults();
      long revision = Long.parseLong(lines[1]);
      if (revision < 0) return defaults();
      EnumMap<Category, Mode> modes = new EnumMap<>(Category.class);
      for (int i = 2; i < 2 + Category.values().length; i++) {
        String[] parts = lines[i].split("=", -1);
        if (parts.length != 2
            || modes.put(Category.valueOf(parts[0]), Mode.valueOf(parts[1])) != null)
          return defaults();
      }
      if (modes.size() != Category.values().length) return defaults();
      Set<String> trust = new LinkedHashSet<>();
      Set<String> handoff = new LinkedHashSet<>();
      for (int i = 2 + Category.values().length; i < lines.length - 1; i++) {
        String line = lines[i];
        if (line.startsWith("T=")) {
          String pkg = line.substring(2);
          if (!ScreenConfirmationPolicy.validPackage(pkg) || !trust.add(pkg)) return defaults();
        } else if (line.startsWith("H=")) {
          String pkg = line.substring(2);
          if (!ScreenConfirmationPolicy.validPackage(pkg) || !handoff.add(pkg)) return defaults();
        } else return defaults();
        if (trust.size() > MAX_APPS_PER_LIST || handoff.size() > MAX_APPS_PER_LIST)
          return defaults();
      }
      for (String pkg : trust) if (handoff.contains(pkg)) return defaults();
      return new SensitiveScreenPolicy(modes, trust, handoff, revision);
    } catch (RuntimeException malformed) {
      return defaults();
    }
  }

  /** 名称只用于诊断/文案；未知类别按「无法识别的敏感界面」处理。 */
  public static String labelCode(Category category) {
    return category == null ? Category.SECURE_UNKNOWN.name() : category.name();
  }
}
