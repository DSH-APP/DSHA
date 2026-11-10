package com.deepseekharness.app.util;

/**
 * 内嵌纵向滚动框与外层页面之间的手势归属判定（纯逻辑，无 Android 依赖）。
 *
 * <p>规则：按下时只要内框能滚就先挡住外层；手指累计位移越过 slop 后按「从按下点起的总位移」 一次性决定归属，整个手势内不再改判。主方向为横向，或内框在该方向已到边缘时交给外层；否则内框独占。
 * 不能按单帧位移逐帧判断：贴边时一帧抖动就会放开外层，外层按按下点累计位移立即越过 slop 抢走手势。
 */
public final class NestedScrollArbiter {
  public enum Owner {
    UNDECIDED,
    INNER,
    OUTER
  }

  private final float slop;
  private float downX;
  private float downY;
  private Owner owner = Owner.OUTER;

  public NestedScrollArbiter(float slop) {
    this.slop = Math.max(0f, slop);
  }

  /**
   * 手势开始。
   *
   * @param canScrollUp 内框还能向顶部滚（手指下滑时生效）
   * @param canScrollDown 内框还能向底部滚（手指上滑时生效）
   * @return 是否请求外层不拦截
   */
  public boolean down(float x, float y, boolean canScrollUp, boolean canScrollDown) {
    downX = x;
    downY = y;
    owner = canScrollUp || canScrollDown ? Owner.UNDECIDED : Owner.OUTER;
    return owner != Owner.OUTER;
  }

  /** 手势移动；返回是否请求外层不拦截。 */
  public boolean move(float x, float y, boolean canScrollUp, boolean canScrollDown) {
    if (owner == Owner.UNDECIDED) {
      float dx = x - downX;
      float dy = y - downY;
      float ax = Math.abs(dx);
      float ay = Math.abs(dy);
      if (Math.max(ax, ay) > slop) {
        boolean vertical = ay >= ax;
        boolean innerCan = dy > 0 ? canScrollUp : canScrollDown;
        owner = vertical && innerCan ? Owner.INNER : Owner.OUTER;
      }
    }
    return owner != Owner.OUTER;
  }

  /** 抬起或取消。 */
  public void end() {
    owner = Owner.OUTER;
  }

  public Owner owner() {
    return owner;
  }
}
