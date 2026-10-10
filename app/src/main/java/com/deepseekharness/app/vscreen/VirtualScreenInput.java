package com.deepseekharness.app.vscreen;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.deepseekharness.app.util.VirtualScreenInputPolicy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 仅供特权 core：优先反射直接提交事件（不启进程），反射不可用时退到
 * {@code input -d <displayId>} 命令。两条后端的差别见 {@link VirtualScreenInputPolicy}。
 *
 * <p>探测与构造**绝不抛异常**：隐藏成员缺失、hidden-API 策略拒绝、系统命令不存在都只导致降级，
 * 不再让 {@code /vscreen/create} 连坐失败（issue #113：SDK 37 上任意一个隐藏成员查不到，
 * 整个虚拟屏都起不来）。后端身份与缺失成员经 {@link #backend()} / {@link #unavailableReason()}
 * 上报到 {@code /vscreen/status}，让调用方一眼看出输入为什么不动。
 */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenInput {
  private static final long COMMAND_TIMEOUT_MS = 6000;
  private static final int MAX_OUTPUT = 4096;

  private final int display;
  private final String backend;
  private final String unavailable;
  private final Object manager;
  private final Method inject, setDisplay;
  private String stroke = "";
  private long downAt, lastAt;
  private float x, y, startX, startY;

  private VirtualScreenInput(
      int display,
      String backend,
      String unavailable,
      Object manager,
      Method inject,
      Method setDisplay) {
    this.display = display;
    this.backend = backend;
    this.unavailable = unavailable;
    this.manager = manager;
    this.inject = inject;
    this.setDisplay = setDisplay;
  }

  /** 探测一次并选定后端；任何探测失败都只降级，不抛给调用方。 */
  static VirtualScreenInput create(int display) {
    Method getInstance =
        lookup("android.hardware.input.InputManager", "getInstance", new Class<?>[0]);
    Method inject =
        lookup(
            "android.hardware.input.InputManager",
            "injectInputEvent",
            new Class<?>[] {InputEvent.class, int.class});
    Method setDisplay =
        lookup("android.view.InputEvent", "setDisplayId", new Class<?>[] {int.class});
    Object manager = getInstance == null ? null : invokeStatic(getInstance);
    boolean getInstanceUsable = manager != null;
    String missing =
        VirtualScreenInputPolicy.missing(getInstanceUsable, inject != null, setDisplay != null);
    // 成员查得到但调用不成立（新版本要求 ActivityThread 上下文）也算不可用，理由要说清是调用失败
    if (getInstance != null && !getInstanceUsable)
      missing =
          missing.isEmpty()
              ? "InputManager.getInstance(调用失败)"
              : missing + ",InputManager.getInstance(调用失败)";
    String backend =
        VirtualScreenInputPolicy.backend(
            getInstanceUsable, inject != null, setDisplay != null, commandAvailable());
    return new VirtualScreenInput(
        display,
        backend,
        VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend) ? "" : missing,
        manager,
        inject,
        setDisplay);
  }

  /** 当前后端：reflection / input-command / unavailable（见 VirtualScreenInputPolicy）。 */
  String backend() {
    return backend;
  }

  /** 缺失的隐藏成员或调用失败原因；反射后端可用时为空串。 */
  String unavailableReason() {
    return unavailable;
  }

  /** 是否真的能注入输入；false 时虚拟屏仍可看，但点不动。 */
  boolean available() {
    return !VirtualScreenInputPolicy.BACKEND_UNAVAILABLE.equals(backend);
  }

  synchronized boolean touch(String id, int action, float x, float y) throws Exception {
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend))
      return reflectTouch(id, action, x, y);
    if (VirtualScreenInputPolicy.BACKEND_COMMAND.equals(backend))
      return commandTouch(id, action, x, y);
    return false;
  }

  synchronized boolean active() {
    return !stroke.isEmpty();
  }

  synchronized void expire() {
    if (active() && SystemClock.uptimeMillis() - lastAt > 3000)
      try {
        cancel();
      } catch (Exception ignored) {
        stroke = "";
      }
  }

  synchronized void cancel() throws Exception {
    if (!active()) return;
    // 命令后端在抬起前没有注入任何中间态，丢掉记录即可；反射后端必须补一个 CANCEL。
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend)) {
      touch(stroke, MotionEvent.ACTION_CANCEL, x, y);
      return;
    }
    stroke = "";
  }

  synchronized boolean key(int code) throws Exception {
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend)) return reflectKey(code);
    if (VirtualScreenInputPolicy.BACKEND_COMMAND.equals(backend))
      return run(VirtualScreenInputPolicy.keyArgs(display, code));
    return false;
  }

  /** 反射后端：逐事件直接提交，按下/移动/抬起都即时生效。 */
  private boolean reflectTouch(String id, int action, float x, float y) throws Exception {
    if (action == MotionEvent.ACTION_DOWN) {
      if (!stroke.isEmpty()) cancel();
      stroke = id;
      downAt = SystemClock.uptimeMillis();
    } else if (stroke.isEmpty() || !stroke.equals(id)) return false;
    this.x = x;
    this.y = y;
    lastAt = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(downAt, lastAt, action, x, y, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      return send(event);
    } finally {
      event.recycle();
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) stroke = "";
    }
  }

  private boolean reflectKey(int code) throws Exception {
    long at = SystemClock.uptimeMillis();
    KeyEvent down =
        new KeyEvent(
            at,
            at,
            KeyEvent.ACTION_DOWN,
            code,
            0,
            0,
            KeyEvent.KEYCODE_UNKNOWN,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD);
    KeyEvent up =
        new KeyEvent(
            at,
            at,
            KeyEvent.ACTION_UP,
            code,
            0,
            0,
            KeyEvent.KEYCODE_UNKNOWN,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD);
    boolean accepted = send(down);
    return send(up) && accepted;
  }

  private boolean send(InputEvent event) throws Exception {
    setDisplay.invoke(event, display);
    return Boolean.TRUE.equals(inject.invoke(manager, event, 0));
  }

  /**
   * 命令后端：按下/移动只记录，抬起时用一次 {@code input -d <displayId> tap|swipe} 重放。
   * 中间态对界面不可见，这是回退方案的已知能力差（见 VirtualScreenInputPolicy）。
   */
  private boolean commandTouch(String id, int action, float x, float y) {
    if (action == MotionEvent.ACTION_DOWN) {
      stroke = id;
      startX = x;
      startY = y;
      this.x = x;
      this.y = y;
      downAt = SystemClock.uptimeMillis();
      lastAt = downAt;
      return true;
    }
    if (stroke.isEmpty() || !stroke.equals(id)) return false;
    this.x = x;
    this.y = y;
    lastAt = SystemClock.uptimeMillis();
    if (action == MotionEvent.ACTION_MOVE) return true;
    if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return false;
    stroke = "";
    String[] argv =
        VirtualScreenInputPolicy.isTap(startX, startY, x, y)
            ? VirtualScreenInputPolicy.tapArgs(display, x, y)
            : VirtualScreenInputPolicy.swipeArgs(
                display, startX, startY, x, y, (int) (lastAt - downAt));
    return run(argv);
  }

  /** 执行一次有界回退命令；超时、非零退出码与命令自己的 Error/Exception 都算失败。 */
  private boolean run(String[] argv) {
    Process process = null;
    try {
      process = new ProcessBuilder(argv).redirectErrorStream(true).start();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Process started = process;
      Thread reader =
          new Thread(
              () -> {
                try (InputStream in = started.getInputStream()) {
                  byte[] buffer = new byte[1024];
                  int count;
                  while ((count = in.read(buffer)) >= 0) {
                    if (output.size() >= MAX_OUTPUT) continue;
                    output.write(buffer, 0, Math.min(count, MAX_OUTPUT - output.size()));
                  }
                } catch (java.io.IOException ignored) {
                }
              });
      reader.setDaemon(true);
      reader.start();
      boolean ended = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      reader.join(300);
      if (!ended) return false;
      if (process.exitValue() != 0) return false;
      String text = output.toString(StandardCharsets.UTF_8);
      return !text.contains("Error") && !text.contains("Exception");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Throwable failure) {
      return false;
    } finally {
      if (process != null) process.destroy();
    }
  }

  /** 反射查找：缺失、被 hidden-API 策略拒绝、类被移走都算不可用，不抛给调用方。 */
  private static Method lookup(String type, String name, Class<?>[] arguments) {
    try {
      return Class.forName(type).getMethod(name, arguments);
    } catch (Throwable missing) {
      return null;
    }
  }

  /** 隐藏 API 的静态入口在新版本可能改成需要 ActivityThread 上下文；调用失败同样只降级。 */
  private static Object invokeStatic(Method method) {
    try {
      return method.invoke(null);
    } catch (Throwable failure) {
      return null;
    }
  }

  private static boolean commandAvailable() {
    try {
      return new File(VirtualScreenInputPolicy.COMMAND).exists();
    } catch (Throwable unavailable) {
      return false;
    }
  }
}
