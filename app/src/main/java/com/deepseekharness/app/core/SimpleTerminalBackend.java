package com.deepseekharness.app.core;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.TerminalProcessCloser;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.TerminalReady;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** View-independent backend and environment gate for the simple terminal. */
public final class SimpleTerminalBackend {
  private SimpleTerminalBackend() {}

  public static TerminalSession.Backend create(ProotBootstrap proot) {
    return new TerminalSession.Backend() {
      private ProcessIdentity sessionLeader;
      private TerminalReady reported;

      @Override
      public AutoCloseable beginLifetime() {
        return RuntimeTasks.beginDetached();
      }

      @Override
      public Process open() throws Exception {
        try (EnvironmentTaskGate.Lease lease = acquire(proot, UiText.text("启动简易终端"))) {
          return lease.run(
              () -> {
                checkPending(proot);
                if (!proot.isEnvironmentReady())
                  throw new IOException(UiText.text("环境未就绪，请先到「安装」页完成安装"));
                try {
                  return proot.execRootfsInteractive();
                } catch (RuntimeException | Error error) {
                  throw new TerminalSession.UncertainStart(error);
                }
              });
        }
      }

      @Override
      public void write(Process process, String text) throws IOException {
        try (EnvironmentTaskGate.Lease lease = acquire(proot, UiText.text("终端命令发送"))) {
          lease.run(
              () -> {
                checkPending(proot);
                TerminalSession.Backend.super.write(process, text);
                return null;
              });
        } catch (IOException error) {
          throw error;
        } catch (Exception error) {
          throw new IOException(UiText.text("终端命令发送失败"), error);
        }
      }

      @Override
      public void onReady(Process process, TerminalReady identity) throws IOException {
        if (reported != null
            && (reported.session != identity.session || reported.started != identity.started))
          throw new IOException(UiText.text("终端会话身份发生变化"));
        reported = identity;
        sessionLeader =
            TerminalProcessCloser.captureSimpleLeader(identity.session, identity.started);
      }

      @Override
      public void terminate(Process process, long group) throws Exception {
        if (group > 1) {
          if (group > Integer.MAX_VALUE) throw new IOException(UiText.text("终端进程组无效"));
          if (reported == null || reported.session != group)
            throw new IOException(UiText.text("终端组号缺少本次出生身份"));
          if (sessionLeader == null)
            sessionLeader =
                TerminalProcessCloser.captureSimpleLeader(reported.session, reported.started);
          TerminalProcessCloser.closeSimple(sessionLeader, 3000);
        } else {
          // The fixed READY handshake did not complete; a guest may exist even if its launcher
          // exited.
          throw new IOException(UiText.text("未取得终端本次会话身份，保留环境占用"));
        }
        // Reuse the existing process identity-aware stop and verified launcher exit.
        if (!ProcessTermination.awaitExit(process, 1000)) Compat.destroy(process);
        if (!ProcessTermination.awaitExit(process, 1000))
          throw new IOException(UiText.text("旧终端启动器尚未退出"));
        try {
          process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
          process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
          process.getErrorStream().close();
        } catch (IOException ignored) {
        }
        sessionLeader = null;
        reported = null;
      }
    };
  }

  private static EnvironmentTaskGate.Lease acquire(ProotBootstrap proot, String kind)
      throws IOException {
    String blocked = blockMessage(proot);
    if (!blocked.isEmpty()) throw new TerminalSession.EnvironmentUnavailable(blocked);
    EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire(kind);
    if (lease == null)
      throw new TerminalSession.EnvironmentUnavailable(UiText.text("已有环境任务启动，请稍后再打开终端"));
    return lease;
  }

  private static void checkPending(ProotBootstrap proot) throws IOException {
    if (MaintenanceCoordinator.pending(proot.getRootfsDir().getParentFile().getParentFile())
        || MaintenanceCoordinator.isExclusive())
      throw new TerminalSession.EnvironmentUnavailable(UiText.text("环境维护尚未完成，请先恢复维护，再打开终端"));
  }

  public static String blockMessage(ProotBootstrap proot) {
    if (MaintenanceCoordinator.pending(proot.getRootfsDir().getParentFile().getParentFile()))
      return UiText.text("上次环境维护未完成，请到安装与修复页恢复维护，再打开终端");
    if (MaintenanceCoordinator.isEnvironmentTaskBusy()) {
      String kind = EnvironmentTaskGate.activeKind();
      return kind.isEmpty()
          ? UiText.text("正在执行环境任务，请稍后再打开终端或发送命令")
          : UiText.format(
              "正在%s，请稍后再打开终端或发送命令", com.deepseekharness.app.util.UiStateText.render(kind));
    }
    return "";
  }
}
