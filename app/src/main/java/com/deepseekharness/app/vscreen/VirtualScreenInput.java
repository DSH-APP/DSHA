package com.deepseekharness.app.vscreen;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.deepseekharness.app.util.VirtualScreenInputPolicy;
import com.deepseekharness.app.util.VirtualScreenStroke;
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
 *
 * <p>查找成功不等于调用可用：反射后端第一次被拒（hidden-API 在 invoke 阶段拦截、manager 失效）
 * 就切到命令后端，而不是让每一次动作都以「被拒绝」告终。
 */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenInput {
  private static final int MAX_OUTPUT = 4096;

  private final int display;
  private final VirtualScreenStroke stroke;
  private final Object manager;
  private final Method inject, setDisplay;
  private volatile String backend;
  private volatile String unavailable;

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
    this.stroke = new VirtualScreenStroke(display);
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
    String missing =
        VirtualScreenInputPolicy.missing(
            getInstance != null, manager != null, inject != null, setDisplay != null);
    String backend =
        VirtualScreenInputPolicy.backend(
            manager != null, inject != null, setDisplay != null, commandAvailable());
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

  /** 缺失的隐藏成员、调用失败原因或降级原因；反射后端可用时为空串。 */
  String unavailableReason() {
    return unavailable;
  }

  /** 是否真的能注入输入；false 时虚拟屏仍可看，但点不动。 */
  boolean available() {
    return !VirtualScreenInputPolicy.BACKEND_UNAVAILABLE.equals(backend);
  }

  synchronized boolean touch(String id, int action, float x, float y) throws Exception {
    long now = SystemClock.uptimeMillis();
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend))
      return reflectTouch(id, action, x, y, now);
    if (VirtualScreenInputPolicy.BACKEND_COMMAND.equals(backend))
      return commandTouch(id, action, x, y, now);
    return false;
  }

  synchronized boolean active() {
    return stroke.active();
  }

  synchronized void expire() {
    if (!stroke.stale(SystemClock.uptimeMillis())) return;
    try {
      cancel();
    } catch (Exception ignored) {
      stroke.cancel(stroke.id());
    }
  }

  synchronized void cancel() throws Exception {
    if (!stroke.active()) return;
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend)) {
      reflectCancel(SystemClock.uptimeMillis());
      return;
    }
    // 命令后端在抬起前没有注入任何中间态：丢掉记录即可。取消绝不能重放成一条手势。
    stroke.cancel(stroke.id());
  }

  synchronized boolean key(int code) throws Exception {
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend)) return reflectKey(code);
    if (VirtualScreenInputPolicy.BACKEND_COMMAND.equals(backend))
      return run(
          VirtualScreenInputPolicy.keyArgs(display, code),
          VirtualScreenInputPolicy.commandTimeoutMs(0));
    return false;
  }

  /** 反射后端：逐事件直接提交，按下/移动/抬起都即时生效。 */
  private boolean reflectTouch(String id, int action, float x, float y, long now) throws Exception {
    if (action == MotionEvent.ACTION_DOWN) {
      if (stroke.active()) reflectCancel(now);
      stroke.down(id, x, y, now);
    } else if (action == MotionEvent.ACTION_MOVE) {
      if (!stroke.move(id, x, y, now)) return false;
    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      if (!stroke.active() || !id.equals(stroke.id())) return false;
    } else {
      return false;
    }
    // 取消必须落在最后已知位置：调用方（预览与手势分发）在取消时传的是 (0,0)，
    // 按 (0,0) 注入等于把这次取消丢到屏幕左上角。
    boolean cancelled = action == MotionEvent.ACTION_CANCEL;
    float atX = cancelled ? stroke.lastX() : x;
    float atY = cancelled ? stroke.lastY() : y;
    MotionEvent event = MotionEvent.obtain(stroke.downAt(), now, action, atX, atY, 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      return send(event);
    } finally {
      event.recycle();
      if (action == MotionEvent.ACTION_UP || cancelled) stroke.cancel(id);
    }
  }

  /** 反射后端补发上一笔手势的 CANCEL：不补的话应用会停在按下态。 */
  private void reflectCancel(long now) throws Exception {
    MotionEvent event =
        MotionEvent.obtain(
            stroke.downAt(), now, MotionEvent.ACTION_CANCEL, stroke.lastX(), stroke.lastY(), 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      send(event);
    } finally {
      event.recycle();
      stroke.cancel(stroke.id());
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

  /**
   * 提交一个事件。失败不再向上抛：先尝试切到命令后端，再如实返回 false。
   * 隐藏 API 的查找与调用会在不同阶段被拒，只有运行期失败才知道它真的用不了。
   */
  private boolean send(InputEvent event) {
    try {
      setDisplay.invoke(event, display);
      return Boolean.TRUE.equals(inject.invoke(manager, event, 0));
    } catch (Throwable refused) {
      degradeToCommand(refused);
      return false;
    }
  }

  /** 反射后端运行期被拒：有命令后端就切过去并把原因写进 status；没有就保持现状（原因仍可见）。 */
  private void degradeToCommand(Throwable refused) {
    if (!VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend)) return;
    if (!commandAvailable()) return;
    backend = VirtualScreenInputPolicy.BACKEND_COMMAND;
    unavailable = "reflection(invoke-failed:" + refused.getClass().getSimpleName() + ")";
    stroke.cancel(stroke.id());
  }

  /**
   * 命令后端：按下/移动只记录，抬起时用一次 {@code input -d <displayId> tap|swipe} 重放；
   * 取消只丢弃记录（取消不是抬起，绝不重放）。中间态对界面不可见、按住按时长重放为长按，
   * 这些能力差别写在 {@link VirtualScreenInputPolicy} 与 {@link VirtualScreenStroke}。
   */
  private boolean commandTouch(String id, int action, float x, float y, long now) {
    if (action == MotionEvent.ACTION_DOWN) {
      stroke.down(id, x, y, now);
      return true;
    }
    if (action == MotionEvent.ACTION_MOVE) return stroke.move(id, x, y, now);
    if (action == MotionEvent.ACTION_CANCEL) {
      stroke.cancel(id);
      return true;
    }
    if (action != MotionEvent.ACTION_UP) return false;
    VirtualScreenStroke.Release release = stroke.up(id, x, y, now);
    if (!release.replays()) return false;
    return run(release.argv, VirtualScreenInputPolicy.commandTimeoutMs(release.durationMs));
  }

  /**
   * 执行一次有界回退命令。超时用 destroyForcibly（只发 SIGTERM 的话忽略它的进程会活过预算）、
   * 输出被截断时保守判失败（判据不完整就不算成功），非零退出码与命令自己的 Error/Exception 都算失败。
   */
  private boolean run(String[] argv, long timeoutMs) {
    Process process = null;
    try {
      process = new ProcessBuilder(argv).redirectErrorStream(true).start();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      boolean[] truncated = {false};
      Process started = process;
      Thread reader =
          new Thread(
              () -> {
                try (InputStream in = started.getInputStream()) {
                  byte[] buffer = new byte[1024];
                  int count;
                  while ((count = in.read(buffer)) >= 0) {
                    if (output.size() + count > MAX_OUTPUT) {
                      truncated[0] = true;
                      continue;
                    }
                    output.write(buffer, 0, count);
                  }
                } catch (java.io.IOException ignored) {
                }
              });
      reader.setDaemon(true);
      reader.start();
      boolean ended = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!ended) {
        process.destroyForcibly();
        reader.join(500);
        return false;
      }
      reader.join(500);
      if (truncated[0]) return false;
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
