package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * 虚拟屏输入后端的纯逻辑：探测结论、后端选择、回退命令参数。
 *
 * <p>输入注入原本只有一条路——反射隐藏 API {@code InputManager.getInstance()} /
 * {@code InputManager.injectInputEvent(InputEvent,int)} / {@code InputEvent.setDisplayId(int)}。
 * 这三个成员会随 Android 大版本被移除或被 hidden-API 策略拒绝查找，任意一个找不到，
 * 整个 {@code /vscreen/create} 都被连坐（issue #113：SDK 37 上恒报
 * {@code VSCREEN_NoSuchMethodException}，虚拟屏完全起不来）。这里把「用哪条路、缺了什么、
 * 命令怎么拼」抽成纯逻辑，Android 侧只负责真的探测与真的执行。
 *
 * <p>两条后端不是等价替换，能力差别写在这里免得被当成等价：
 * <ul>
 *   <li>{@link #BACKEND_REFLECTION}：直接提交事件，按下/移动/抬起逐事件生效；
 *   <li>{@link #BACKEND_COMMAND}：一次手势在抬起时用一次
 *       {@code input -d <displayId> tap|swipe} 重放，移动的中间态对界面不可见。
 * </ul>
 */
public final class VirtualScreenInputPolicy {
  private VirtualScreenInputPolicy() {}

  /** 回退后端使用的系统命令；{@code /vscreen/type} 一直走的就是它。 */
  public static final String COMMAND = "/system/bin/input";

  public static final String BACKEND_REFLECTION = "reflection";
  public static final String BACKEND_COMMAND = "input-command";
  public static final String BACKEND_UNAVAILABLE = "unavailable";

  /** 反射后端需要的三个隐藏成员，按查找顺序排列（查找顺序＝真实失败顺序）。 */
  public static final String MEMBER_GET_INSTANCE = "InputManager.getInstance";

  public static final String MEMBER_INJECT_EVENT = "InputManager.injectInputEvent(InputEvent,int)";
  public static final String MEMBER_SET_DISPLAY = "InputEvent.setDisplayId(int)";

  /** 两条后端都不可用时的稳定码：虚拟屏能看，但不能注入输入。 */
  public static final String INPUT_UNAVAILABLE = "VSCREEN_INPUT_UNAVAILABLE";

  public static final int MIN_DURATION_MS = 1;
  public static final int MAX_DURATION_MS = 3000;

  /** 回退命令的超时预算下限（命令本身的启动开销）与上限。 */
  public static final long COMMAND_MIN_TIMEOUT_MS = 2500;

  public static final long COMMAND_MAX_TIMEOUT_MS = 6000;

  /**
   * 缺失/不可用的隐藏成员清单（按查找顺序、逗号分隔）；空串表示反射后端可用。
   *
   * <p>{@code getInstance} 分「查不到」与「查得到但调用不成立」两态：后者在较新的系统上
   * 是因为它改成了需要 ActivityThread 上下文，清单里必须写成不同的一项，否则同一个成员会出现两次。
   */
  public static String missing(
      boolean getInstanceFound,
      boolean getInstanceCallable,
      boolean injectEvent,
      boolean setDisplay) {
    StringBuilder out = new StringBuilder();
    if (!getInstanceFound) append(out, MEMBER_GET_INSTANCE);
    else if (!getInstanceCallable) append(out, MEMBER_GET_INSTANCE + "(invoke-failed)");
    if (!injectEvent) append(out, MEMBER_INJECT_EVENT);
    if (!setDisplay) append(out, MEMBER_SET_DISPLAY);
    return out.toString();
  }

  /**
   * 回退命令的超时预算：按住越久命令本身跑得越久，但上限必须远小于心跳预算
   * （core 的 route 是类锁，一次手势会把 ping/status 串行化在后面），否则会被误判成 core 不可达。
   */
  public static long commandTimeoutMs(int gestureMs) {
    return Math.max(COMMAND_MIN_TIMEOUT_MS, Math.min(COMMAND_MAX_TIMEOUT_MS, gestureMs + 2500L));
  }

  /**
   * 后端选择：三个隐藏成员都在才用反射；否则退到 {@code input} 命令（{@code commandAvailable}
   * 由调用方按系统命令是否存在探测）；命令也没有就是没有后端——此时虚拟屏仍应创建成功，
   * 只是不能注入输入，不把「输入不可用」升级成「虚拟屏不可用」。
   */
  public static String backend(
      boolean getInstance, boolean injectEvent, boolean setDisplay, boolean commandAvailable) {
    if (getInstance && injectEvent && setDisplay) return BACKEND_REFLECTION;
    return commandAvailable ? BACKEND_COMMAND : BACKEND_UNAVAILABLE;
  }

  /** 点击：{@code input -d <displayId> tap x y}。 */
  public static String[] tapArgs(int displayId, float x, float y) {
    return new String[] {
      COMMAND, "-d", String.valueOf(displayId), "tap", coordinate(x), coordinate(y)
    };
  }

  /** 滑动：{@code input -d <displayId> swipe x1 y1 x2 y2 ms}。 */
  public static String[] swipeArgs(int displayId, float x1, float y1, float x2, float y2, int ms) {
    return new String[] {
      COMMAND,
      "-d",
      String.valueOf(displayId),
      "swipe",
      coordinate(x1),
      coordinate(y1),
      coordinate(x2),
      coordinate(y2),
      String.valueOf(duration(ms))
    };
  }

  /** 按键：{@code input -d <displayId> keyevent <code>}。 */
  public static String[] keyArgs(int displayId, int keycode) {
    return new String[] {
      COMMAND, "-d", String.valueOf(displayId), "keyevent", String.valueOf(keycode)
    };
  }

  /** 一次手势是否算点击：按下到抬起没有位移。 */
  public static boolean isTap(float x1, float y1, float x2, float y2) {
    float dx = x2 - x1, dy = y2 - y1;
    return dx * dx + dy * dy < 4f;
  }

  /** {@code input swipe} 的时长：0 会被命令拒绝，超上限按上限截断。 */
  public static int duration(int ms) {
    return Math.max(MIN_DURATION_MS, Math.min(MAX_DURATION_MS, ms));
  }

  /**
   * 命令参数里的坐标：向下取一位小数、固定 Locale，非有限值退 0。
   *
   * <p>必须向下取整：core 用 {@code n < edge} 校验，四舍五入会把 1007.96 变成正好等于显示边缘的
   * 1008.0，命令侧收到越界坐标。也不能用本地化小数点，argv 里必须是 {@code .}。
   */
  public static String coordinate(float value) {
    if (!Float.isFinite(value)) return "0.0";
    double floored = Math.floor(Math.max(0f, value) * 10.0) / 10.0;
    return String.format(Locale.US, "%.1f", floored);
  }

  private static void append(StringBuilder out, String name) {
    if (out.length() > 0) out.append(',');
    out.append(name);
  }
}
