package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 有界逐行读取与进程回收；终止策略由宿主提供，以兼容 Android 6。 */
public final class InstallProcess {
  private InstallProcess() {}

  public static int read(
      Process process,
      long timeoutMs,
      boolean cancellable,
      BooleanSupplier cancelled,
      Consumer<String> output,
      Consumer<Process> destroy)
      throws Exception {
    InputStream input = process.getInputStream();
    Lines lines = new Lines(output);
    byte[] buffer = new byte[4096];
    ProcessPipePoller poller = new ProcessPipePoller(process, buffer);
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    Throwable original = null;
    try {
      process.getOutputStream().close();
      while (true) {
        ProcessPipePoller.Event event =
            poller.next(
                deadline,
                () -> {
                  if (cancellable && cancelled.getAsBoolean()) throw new InstallTask.Cancelled();
                  if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException(
                        com.deepseekharness.app.util.UiText.text("安装命令等待被中断"));
                });
        if (event.timedOut)
          throw new IOException(
              com.deepseekharness.app.util.UiText.format(
                  "命令超时（%s 秒），当前步骤未完成，请重新检查", timeoutMs / 1000));
        if (event.count > 0) {
          lines.accept(buffer, event.count);
          continue;
        }
        if (event.exitCode != null) {
          lines.flush();
          if (cancellable && cancelled.getAsBoolean()) throw new InstallTask.Cancelled();
          return event.exitCode;
        }
      }
    } catch (Exception | Error error) {
      original = error;
      throw error;
    } finally {
      Throwable termination = null;
      if (!ProcessTermination.exited(process)) {
        try {
          destroy.accept(process);
        } catch (RuntimeException error) {
          termination = error;
        }
      }
      boolean reaped = ProcessTermination.awaitExit(process, 2000);
      try {
        input.close();
      } catch (IOException ignored) {
      }
      try {
        process.getErrorStream().close();
      } catch (IOException ignored) {
      }
      try {
        process.getOutputStream().close();
      } catch (IOException ignored) {
      }
      if (!reaped) {
        CleanupFailure failure = new CleanupFailure(process, destroy, termination);
        if (original != null) failure.addSuppressed(original);
        throw failure;
      }
    }
  }

  /** 回收失败时携带精确进程句柄；仓库必须保留全局锁，不能把它当成普通取消完成。 */
  public static final class CleanupFailure extends IOException {
    private final Process process;
    private final Consumer<Process> destroy;

    public CleanupFailure(Process process, Consumer<Process> destroy, Throwable cause) {
      super(com.deepseekharness.app.util.UiText.text("本次进程仍未退出，不能开始下一任务"), cause);
      this.process = process;
      this.destroy = destroy;
    }

    public boolean awaitExit(long millis) {
      return ProcessTermination.awaitExit(process, millis);
    }

    public void retry() {
      if (!ProcessTermination.exited(process))
        try {
          destroy.accept(process);
        } catch (RuntimeException ignored) {
        }
    }
  }

  public static CleanupFailure cleanupFailure(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause())
      if (current instanceof CleanupFailure) return (CleanupFailure) current;
    return null;
  }

  private static final class Lines {
    final Consumer<String> output;
    final ByteArrayOutputStream line = new ByteArrayOutputStream();
    boolean overflow;

    Lines(Consumer<String> output) {
      this.output = output;
    }

    void accept(byte[] bytes, int count) {
      for (int i = 0; i < count; i++) {
        if (bytes[i] == '\r' || bytes[i] == '\n') flush();
        else if (line.size() < 16_384) line.write(bytes[i]);
        else overflow = true;
      }
    }

    void flush() {
      String value = new String(line.toByteArray(), StandardCharsets.UTF_8);
      line.reset();
      boolean hide = overflow || value.length() > 4096;
      overflow = false;
      if (hide) {
        if (value.contains("-----BEGIN ") && value.contains("PRIVATE KEY-----"))
          output.accept("-----BEGIN PRIVATE KEY-----");
        output.accept(com.deepseekharness.app.util.UiText.text("[输出行过长，已隐藏]"));
      } else if (!value.isEmpty()) output.accept(value);
    }
  }
}
