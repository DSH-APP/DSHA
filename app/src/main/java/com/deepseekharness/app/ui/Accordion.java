package com.deepseekharness.app.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单开手风琴（B2）：同一屏的多段折叠说明里只允许一段展开。
 *
 * <p>建立在 {@link DisclosureCard} 之上，所以仍是同一种披露方式：摘要、展开箭头、始终可见的
 * 后果行与读屏语义都不变；本类只协调“同一时刻只有一段展开”。安全后果不能只放在展开区，
 * 各段模型里的 {@code consequence} 在收起时仍然可见。
 *
 * <p>宿主在每次 {@code DisclosureCard.bind(...)} 后调用 {@link #register} 注册该段的重新绑定
 * 动作；任一段被展开时，本类关闭其它段并重绑它们的摘要（不重建视图，不丢焦点）。
 */
final class Accordion {
  private final DisclosureCard.State delegate;
  private final Map<String, Runnable> rebinds = new LinkedHashMap<>();
  private final DisclosureCard.State state =
      new DisclosureCard.State() {
        @Override
        public Boolean expanded(String key) {
          return delegate.expanded(key);
        }

        @Override
        public void setExpanded(String key, boolean value) {
          delegate.setExpanded(key, value);
        }
      };

  Accordion(DisclosureCard.State delegate) {
    this.delegate = delegate;
  }

  /** 传给 {@code DisclosureCard.bind} 的状态；展开状态仍由 delegate 持久化。 */
  DisclosureCard.State state() {
    return state;
  }

  /** 注册某一段的重新绑定动作；同键重复注册时替换（页面重建后不残留旧引用）。 */
  void register(String key, Runnable rebind) {
    if (key == null || rebind == null) return;
    rebinds.put(key, rebind);
  }

  /** {@code onToggle} 回调：某段刚展开时关闭其它段。 */
  void onToggle(String key) {
    if (key == null || !Boolean.TRUE.equals(delegate.expanded(key))) return;
    closeOthers(key);
  }

  /** 程序化展开（例如放宽高风险开关时）：展开该段并关闭其它段。 */
  void open(String key) {
    if (key == null) return;
    delegate.setExpanded(key, true);
    closeOthers(key);
    // 程序化改动没有经过该段自己的 toggle：全部重绑一次，让刚展开的段也按新状态渲染。
    for (Runnable rebind : rebinds.values()) rebind.run();
  }

  /** @return 是否真的关掉了别的段 */
  private boolean closeOthers(String openKey) {
    boolean changed = false;
    for (String key : rebinds.keySet()) {
      if (key.equals(openKey)) continue;
      if (Boolean.TRUE.equals(delegate.expanded(key))) {
        delegate.setExpanded(key, false);
        changed = true;
      }
    }
    if (!changed) return false;
    for (Map.Entry<String, Runnable> entry : rebinds.entrySet()) {
      if (!entry.getKey().equals(openKey)) entry.getValue().run();
    }
    return true;
  }
}
