package com.deepseekharness.app.vscreen;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.deepseekharness.app.util.VirtualScreenInputPolicy;
import com.deepseekharness.app.util.VirtualScreenInputResult;
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
  private VirtualScreenInputResult lastResult = VirtualScreenInputResult.REJECTED;
  private VirtualScreenInputResult strokeResult = VirtualScreenInputResult.REJECTED;

  interface CommandRunner {
    VirtualScreenInputResult run(String[] argv, long timeoutMs);
  }

  private CommandRunner commandRunner = this::runCommand;

  /** 宿主 {@code /system/bin/input} 探测；测试实例固定为「不存在」，不读宿主文件系统。 */
  private java.util.function.BooleanSupplier hostCommand = VirtualScreenInput::systemCommandExists;

  /**
   * 测试缝：{@code true} 表示命令可用性由注入的 runner 代表。GitHub runner 没有
   * {@code /system/bin/input}，真实探测会把只跑 fake runner 的测试提前拒成 REJECTED
   * （T97 合入后 CI unit-source 红）；生产实例不设置这个标记。
   */
  private boolean forcedCommandAvailable;

  static VirtualScreenInput commandForTest(int display, CommandRunner runner) {
    return commandForTest(display, runner, true);
  }

  /**
   * 仅测试：{@code forcedAvailable=false} 复现生产实例在没有 input 命令时的拒绝；
   * 两种情况都不读宿主文件系统，避免测试结果随 CI/本机环境漂移。
   */
  static VirtualScreenInput commandForTest(
      int display, CommandRunner runner, boolean forcedAvailable) {
    VirtualScreenInput result =
        new VirtualScreenInput(
            display, VirtualScreenInputPolicy.BACKEND_COMMAND, "", null, null, null);
    if (runner != null) result.commandRunner = runner;
    result.hostCommand = () -> false;
    result.forcedCommandAvailable = forcedAvailable;
    return result;
  }

  VirtualScreenInputResult commandForTest(String[] argv) {
    return run(argv, 2500);
  }

  VirtualScreenInputResult commandForTest(String[] argv, long timeoutMs) {
    return run(argv, timeoutMs);
  }

  static final class InputApi {
    final Object manager;
    final Method inject;

    InputApi(Object manager, Method inject) {
      this.manager = manager;
      this.inject = inject;
    }
  }

  interface GlobalApiLookup {
    Class<?> load() throws ClassNotFoundException;
  }

  static InputApi resolveInputApi(Class<?> legacy, GlobalApiLookup global) throws Exception {
    Class<?> type = legacy;
    Method singleton;
    try {
      singleton = type.getMethod("getInstance");
    } catch (NoSuchMethodException missing) {
      type = global.load();
      singleton = type.getMethod("getInstance");
    }
    return new InputApi(
        singleton.invoke(null), type.getMethod("injectInputEvent", InputEvent.class, int.class));
  }

  VirtualScreenInputResult lastResult() {
    return lastResult;
  }

  private VirtualScreenInputResult remember(VirtualScreenInputResult result) {
    return lastResult = result;
  }

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
    Object manager = null;
    Method inject = null;
    Method setDisplay =
        lookup("android.view.InputEvent", "setDisplayId", new Class<?>[] {int.class});
    String missing = "";
    try {
      InputApi api =
          resolveInputApi(
              Class.forName("android.hardware.input.InputManager"),
              () -> Class.forName("android.hardware.input.InputManagerGlobal"));
      manager = api.manager;
      inject = api.inject;
      if (manager == null) missing = "InputManager.getInstance(invoke-null)";
    } catch (Throwable failure) {
      missing = "input-api: " + com.deepseekharness.app.util.VirtualScreenFailures.cause(failure);
    }
    if (setDisplay == null)
      missing += (missing.isEmpty() ? "" : ",") + VirtualScreenInputPolicy.MEMBER_SET_DISPLAY;
    String backend =
        VirtualScreenInputPolicy.backend(
            manager != null, inject != null, setDisplay != null, systemCommandExists());
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

  synchronized VirtualScreenInputResult touch(String id, int action, float x, float y) {
    long now = SystemClock.uptimeMillis();
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend))
      return remember(reflectTouch(id, action, x, y, now));
    if (VirtualScreenInputPolicy.BACKEND_COMMAND.equals(backend))
      return remember(commandTouch(id, action, x, y, now));
    return remember(VirtualScreenInputResult.REJECTED);
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

  synchronized VirtualScreenInputResult key(int code) {
    if (VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend))
      return remember(reflectKey(code));
    if (VirtualScreenInputPolicy.BACKEND_COMMAND.equals(backend))
      return remember(
          run(
              VirtualScreenInputPolicy.keyArgs(display, code),
              VirtualScreenInputPolicy.commandTimeoutMs(0)));
    return remember(VirtualScreenInputResult.REJECTED);
  }

  synchronized VirtualScreenInputResult text(String text) {
    if (!commandAvailable()) return remember(VirtualScreenInputResult.REJECTED);
    return remember(
        run(
            new String[] {
              VirtualScreenInputPolicy.COMMAND,
              "-d",
              String.valueOf(display),
              "text",
              text.replace(" ", "%s")
            },
            VirtualScreenInputPolicy.commandTimeoutMs(0)));
  }

  /** 反射后端：逐事件直接提交，按下/移动/抬起都即时生效。 */
  private VirtualScreenInputResult reflectTouch(String id, int action, float x, float y, long now) {
    if (action == MotionEvent.ACTION_DOWN) {
      if (stroke.active()) reflectCancel(now);
      stroke.down(id, x, y, now);
      strokeResult = VirtualScreenInputResult.REJECTED;
    } else if (action == MotionEvent.ACTION_MOVE) {
      if (!stroke.move(id, x, y, now)) return VirtualScreenInputResult.REJECTED;
    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      if (!stroke.active() || !id.equals(stroke.id())) return VirtualScreenInputResult.REJECTED;
    } else return VirtualScreenInputResult.REJECTED;
    boolean cancelled = action == MotionEvent.ACTION_CANCEL;
    MotionEvent event =
        MotionEvent.obtain(
            stroke.downAt(),
            now,
            action,
            cancelled ? stroke.lastX() : x,
            cancelled ? stroke.lastY() : y,
            0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      VirtualScreenInputResult result = send(event);
      strokeResult = VirtualScreenInputResult.after(strokeResult, result);
      return strokeResult;
    } finally {
      event.recycle();
      if (action == MotionEvent.ACTION_UP || cancelled) stroke.cancel(id);
    }
  }

  /** 反射后端补发上一笔手势的 CANCEL：不补的话应用会停在按下态。 */
  private void reflectCancel(long now) {
    MotionEvent event =
        MotionEvent.obtain(
            stroke.downAt(), now, MotionEvent.ACTION_CANCEL, stroke.lastX(), stroke.lastY(), 0);
    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
    try {
      remember(VirtualScreenInputResult.after(strokeResult, send(event)));
    } finally {
      event.recycle();
      stroke.cancel(stroke.id());
    }
  }

  private VirtualScreenInputResult reflectKey(int code) {
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
    VirtualScreenInputResult first = send(down);
    if (first == VirtualScreenInputResult.REJECTED) return first;
    return VirtualScreenInputResult.after(first, send(up));
  }

  /**
   * 提交一个事件。提交后的异常保留 UNKNOWN；降级只影响后续新动作，当前动作绝不重放。
   * 隐藏 API 的查找与调用会在不同阶段被拒，只有运行期失败才知道它真的用不了。
   */
  private VirtualScreenInputResult send(InputEvent event) {
    boolean submitting = false;
    try {
      setDisplay.invoke(event, display);
      submitting = true;
      return Boolean.TRUE.equals(inject.invoke(manager, event, 0))
          ? VirtualScreenInputResult.ACCEPTED
          : VirtualScreenInputResult.REJECTED;
    } catch (Throwable refused) {
      // 仅为后续新动作切换后端；当前提交可能已执行，绝不在另一个后端重放。
      degradeToCommand(refused);
      return submitting ? VirtualScreenInputResult.UNKNOWN : VirtualScreenInputResult.REJECTED;
    }
  }

  /** 反射后端运行期被拒：后续使用命令后端，命令缺失则只读；原因写进 status。 */
  private void degradeToCommand(Throwable refused) {
    if (!VirtualScreenInputPolicy.BACKEND_REFLECTION.equals(backend)) return;
    backend =
        commandAvailable()
            ? VirtualScreenInputPolicy.BACKEND_COMMAND
            : VirtualScreenInputPolicy.BACKEND_UNAVAILABLE;
    unavailable =
        "reflection: " + com.deepseekharness.app.util.VirtualScreenFailures.cause(refused);
    stroke.cancel(stroke.id());
  }

  /**
   * 命令后端：按下/移动只记录，抬起时用一次 {@code input -d <displayId> tap|swipe} 重放；
   * 取消只丢弃记录（取消不是抬起，绝不重放）。中间态对界面不可见、按住按时长重放为长按，
   * 这些能力差别写在 {@link VirtualScreenInputPolicy} 与 {@link VirtualScreenStroke}。
   */
  private VirtualScreenInputResult commandTouch(String id, int action, float x, float y, long now) {
    if (action == MotionEvent.ACTION_DOWN) {
      stroke.down(id, x, y, now);
      return VirtualScreenInputResult.ACCEPTED;
    }
    if (action == MotionEvent.ACTION_MOVE)
      return stroke.move(id, x, y, now)
          ? VirtualScreenInputResult.ACCEPTED
          : VirtualScreenInputResult.REJECTED;
    if (action == MotionEvent.ACTION_CANCEL)
      return stroke.cancel(id)
          ? VirtualScreenInputResult.ACCEPTED
          : VirtualScreenInputResult.REJECTED;
    if (action != MotionEvent.ACTION_UP) return VirtualScreenInputResult.REJECTED;
    VirtualScreenStroke.Release release = stroke.up(id, x, y, now);
    if (!release.replays()) return VirtualScreenInputResult.REJECTED;
    return run(release.argv, VirtualScreenInputPolicy.commandTimeoutMs(release.durationMs));
  }

  /**
   * 执行一次有界回退命令。超时用 destroyForcibly（只发 SIGTERM 的话忽略它的进程会活过预算）、
   * 输出被截断时保守判失败（判据不完整就不算成功），非零退出码与命令自己的 Error/Exception 都算失败。
   */
  private VirtualScreenInputResult run(String[] argv, long timeoutMs) {
    return remember(commandRunner.run(argv, timeoutMs));
  }

  private VirtualScreenInputResult runCommand(String[] argv, long timeoutMs) {
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
                } catch (java.io.IOException incomplete) {
                  truncated[0] = true;
                }
              });
      reader.setDaemon(true);
      reader.start();
      boolean ended = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
      if (!ended) {
        process.destroyForcibly();
        reader.join(500);
        return process == null
            ? VirtualScreenInputResult.REJECTED
            : VirtualScreenInputResult.UNKNOWN;
      }
      reader.join(500);
      if (reader.isAlive() || truncated[0])
        return process == null
            ? VirtualScreenInputResult.REJECTED
            : VirtualScreenInputResult.UNKNOWN;
      if (process.exitValue() != 0)
        return process == null
            ? VirtualScreenInputResult.REJECTED
            : VirtualScreenInputResult.UNKNOWN;
      String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
      return text.contains("Error") || text.contains("Exception")
          ? VirtualScreenInputResult.UNKNOWN
          : VirtualScreenInputResult.ACCEPTED;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return process == null ? VirtualScreenInputResult.REJECTED : VirtualScreenInputResult.UNKNOWN;
    } catch (Throwable failure) {
      return process == null ? VirtualScreenInputResult.REJECTED : VirtualScreenInputResult.UNKNOWN;
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

  private boolean commandAvailable() {
    return forcedCommandAvailable || hostCommand.getAsBoolean();
  }

  private static boolean systemCommandExists() {
    try {
      return new File(VirtualScreenInputPolicy.COMMAND).exists();
    } catch (Throwable unavailable) {
      return false;
    }
  }
}
