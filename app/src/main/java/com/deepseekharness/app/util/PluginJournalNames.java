package com.deepseekharness.app.util;

/**
 * 插件事务目录 {@code plugin-install-operations/} 的子项命名规则（单一来源）。
 *
 * <p>写方（plugin-transactions.py）只创建小写 UUID 事务目录与 {@code completed} 历史目录。旧版
 * 曾把完成证明 {@code .completed-proof-v1.json} 原子写在同一目录，崩溃会留下 {@code .tmp-*}、
 * {@code .previous}、{@code .previous-retained-*}、{@code .restore-*} 兄弟文件；它们只是可再生
 * 的缓存残留。其余名字不可能是本项目写出的事务，归为外来条目：原样保留、不阻塞维护、不重放。
 *
 * <p>Python 侧镜像实现位于 plugin-transactions.py 的 {@code journal_name_kind}，两侧共用
 * {@code app/src/test/resources/plugins/journal-names.json} 做一致性测试。
 */
public final class PluginJournalNames {
  public enum Kind {
    /** {@code completed} 历史目录。 */
    HISTORY,
    /** 小写 UUID：本项目写出的事务记录。 */
    TRANSACTION,
    /** 旧版目录内完成证明及其原子写残留；忽略、保留。 */
    LEGACY_PROOF,
    /** 其他任何名字：外来条目，保留原件但不阻塞。 */
    FOREIGN
  }

  public static final String HISTORY = "completed", LEGACY_PROOF = ".completed-proof-v1.json";

  private static final String LEGACY_PROOF_RESIDUE =
      java.util.regex.Pattern.quote(LEGACY_PROOF)
          + "(\\.previous|\\.(tmp|previous-retained|restore)-"
          + Ids.UUID_PATTERN
          + ")?";

  private PluginJournalNames() {}

  public static Kind kind(String name) {
    if (name == null) return Kind.FOREIGN;
    if (name.equals(HISTORY)) return Kind.HISTORY;
    if (Ids.uuid(name)) return Kind.TRANSACTION;
    if (name.matches(LEGACY_PROOF_RESIDUE)) return Kind.LEGACY_PROOF;
    return Kind.FOREIGN;
  }
}
