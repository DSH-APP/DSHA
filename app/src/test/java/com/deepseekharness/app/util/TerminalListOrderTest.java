package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;

import com.deepseekharness.app.util.TerminalListOrder.Key;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** 混合会话列表：按首次出现先后排列；PTY 与简易 id 可相同但互不混淆。 */
public class TerminalListOrderTest {
  private static List<Long> ids(long... values) {
    Long[] boxed = new Long[values.length];
    for (int i = 0; i < values.length; i++) boxed[i] = values[i];
    return Arrays.asList(boxed);
  }

  @Test
  public void sessionsKeepTheOrderTheyFirstAppeared() {
    TerminalListOrder order = new TerminalListOrder();
    assertEquals(List.of(new Key(true, 1)), order.arrange(ids(1), ids()));
    // 简易 1 后创建：排在 PTY 1 之后，尽管两者 id 都是 1。
    assertEquals(List.of(new Key(true, 1), new Key(false, 1)), order.arrange(ids(1), ids(1)));
    assertEquals(
        List.of(new Key(true, 1), new Key(false, 1), new Key(true, 2)),
        order.arrange(ids(1, 2), ids(1)));
    // 输入顺序不影响结果。
    assertEquals(
        List.of(new Key(true, 1), new Key(false, 1), new Key(true, 2)),
        order.arrange(ids(2, 1), ids(1)));
  }

  @Test
  public void closedSessionsAreForgottenAndNewOnesGoLast() {
    TerminalListOrder order = new TerminalListOrder();
    order.arrange(ids(1, 2), ids());
    order.arrange(ids(1, 2), ids(1));
    // PTY 1 关闭后再新建 PTY 3：排在末尾。
    assertEquals(
        List.of(new Key(true, 2), new Key(false, 1), new Key(true, 3)),
        order.arrange(ids(2, 3), ids(1)));
    assertEquals(List.of(), order.arrange(ids(), ids()));
  }

  @Test
  public void simultaneousArrivalsAreStable() {
    TerminalListOrder order = new TerminalListOrder();
    // 首次刷新同时看到多个会话：同类按 id，跨类 PTY 在前。
    assertEquals(
        List.of(new Key(true, 1), new Key(true, 4), new Key(false, 2), new Key(false, 3)),
        order.arrange(ids(4, 1), ids(3, 2)));
    assertEquals(List.of(new Key(false, 5)), order.arrange(null, Arrays.asList(5L, null, 5L)));
  }
}
