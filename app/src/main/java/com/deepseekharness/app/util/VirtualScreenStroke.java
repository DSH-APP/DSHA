package com.deepseekharness.app.util;

/**
 * 命令后端的单次手势状态机（纯逻辑）：按下/移动只记录，抬起时决定怎么重放，取消一律丢弃。
 *
 * <p>为什么单独抽出来：这段状态机原来住在 Android 类里，JVM 单测够不着，于是「取消被当成抬起」
 * 与「长按被当成点击」两个缺陷都没被发现（issue #113 评审）。这里只做记录与判定，
 * 命令参数由 {@link VirtualScreenInputPolicy} 生成。
 *
 * <p>与反射后端的差别：反射后端逐事件提交，取消要真的补发一个 CANCEL；命令后端在抬起前
 * 什么都没注入，所以取消只是丢弃记录——**取消不是抬起，绝不能反向注入一条手势**。
 */
public final class VirtualScreenStroke {
  /** 超过这个时长没有新事件的手势算悬空，由看门狗丢弃。 */
  public static final long STALE_MS = 3000;

  /** 位移在阈值内但按住超过这个时长，按长按重放（起止同点的 swipe 就是按住）。 */
  public static final long LONG_PRESS_MS = 400;

  /** 收尾结果：要执行的命令参数与按住时长；没有可重放的手势时 {@link #replays()} 为 false。 */
  public static final class Release {
    /** 没有可重放的手势（取消、id 不匹配、本来就没有手势）。 */
    public static final Release NONE = new Release(null, 0);

    public final String[] argv;
    public final int durationMs;

    Release(String[] argv, int durationMs) {
      this.argv = argv;
      this.durationMs = durationMs;
    }

    public boolean replays() {
      return argv != null;
    }
  }

  private final int display;
  private String id = "";
  private float startX, startY, lastX, lastY;
  private long downAt, lastAt;

  public VirtualScreenStroke(int display) {
    this.display = display;
  }

  /** 按下：开始一次新手势；已有未收尾的手势直接丢弃（命令后端没有已注入的中间态）。 */
  public void down(String id, float x, float y, long now) {
    this.id = id;
    startX = x;
    startY = y;
    lastX = x;
    lastY = y;
    downAt = now;
    lastAt = now;
  }

  /** 移动：id 不匹配或没有进行中的手势返回 false，只更新记录。 */
  public boolean move(String id, float x, float y, long now) {
    if (!active() || !this.id.equals(id)) return false;
    lastX = x;
    lastY = y;
    lastAt = now;
    return true;
  }

  /**
   * 抬起：给出重放计划。位移在点击阈值内且按住不到 {@link #LONG_PRESS_MS} 才算点击，
   * 否则用起点到终点的 swipe（起止同点＝按住）重放，时长就是真实按住时长。
   */
  public Release up(String id, float x, float y, long now) {
    if (!active() || !this.id.equals(id)) return Release.NONE;
    float fromX = startX, fromY = startY;
    int held = (int) Math.max(0, now - downAt);
    reset();
    if (VirtualScreenInputPolicy.isTap(fromX, fromY, x, y) && held < LONG_PRESS_MS)
      return new Release(VirtualScreenInputPolicy.tapArgs(display, x, y), held);
    return new Release(VirtualScreenInputPolicy.swipeArgs(display, fromX, fromY, x, y, held), held);
  }

  /** 取消：丢弃进行中的手势，返回是否真的丢弃了。**绝不重放**——取消不是抬起。 */
  public boolean cancel(String id) {
    if (!active() || (id != null && !id.isEmpty() && !this.id.equals(id))) return false;
    reset();
    return true;
  }

  /** 悬空手势（超过 {@link #STALE_MS} 没有新事件）：丢弃并返回是否丢弃了。 */
  public boolean expire(long now) {
    if (!stale(now)) return false;
    reset();
    return true;
  }

  /** 是否已经悬空（进行中且超过 {@link #STALE_MS} 没有新事件）。 */
  public boolean stale(long now) {
    return active() && now - lastAt > STALE_MS;
  }

  public boolean active() {
    return !id.isEmpty();
  }

  /** 进行中手势的 id；没有手势时为空串（反射后端补发 CANCEL 用）。 */
  public String id() {
    return id;
  }

  /** 手势按下时刻（反射后端构造 MotionEvent 的 downTime）。 */
  public long downAt() {
    return downAt;
  }

  /** 手势最后已知坐标：反射后端补发 CANCEL 用它，避免把取消注到调用方传的 (0,0)。 */
  public float lastX() {
    return lastX;
  }

  public float lastY() {
    return lastY;
  }

  private void reset() {
    id = "";
    startX = 0;
    startY = 0;
    lastX = 0;
    lastY = 0;
    downAt = 0;
    lastAt = 0;
  }
}
