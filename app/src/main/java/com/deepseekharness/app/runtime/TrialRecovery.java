package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.RuntimeTrialRecords;
import com.deepseekharness.app.util.Ids;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The actual pending-trial recovery algorithm, with platform stop/confirmation supplied explicitly. */
final class TrialRecovery {
  interface Stopper {
    void stopAndConfirm(File payload) throws IOException;

    /**
     * 无 PID / stale 证据时，由平台只读确认该记录对应的试运行进程已不存在。
     *
     * <p>默认 fail-closed：没有证据就保留记录，不能把“读不到”当成“已退出”。实现方只能按记录 id
     * 推导出的试运行 profile 与两次一致出生身份确认，不能按端口 / 名称 / 进程组。
     */
    default void confirmAbsent(String id) throws IOException {
      throw new IOException(
          "TRIAL_PROCESS_UNCONFIRMED",
          new IOException("TRIAL_NO_PID_OR_BOUND_SESSION_EXIT_EVIDENCE"));
    }
  }

  private TrialRecovery() {}

  static void recover(BackupFileSystem fs, File home, Stopper stopper) throws IOException {
    if (fs.stat(home).type.equals("MISSING")) return;
    List<String> entries = fs.list(home);
    if (entries.size() > 64) throw new IOException("TRIAL_RETENTION_LIMIT");
    IOException retained = null;
    for (String id : entries) {
      if (Thread.currentThread().isInterrupted())
        throw new java.io.InterruptedIOException("CANCELLED");
      try {
        if (!id.matches(Ids.UUID_PATTERN)) throw new IOException("TRIAL_DIRECTORY");
        File entry = fs.child(home, id), closed = fs.child(entry, "closed");
        if (!fs.stat(closed).type.equals("MISSING")) {
          if (!id.equals(new String(fs.small(closed, 128), StandardCharsets.US_ASCII)))
            throw new IOException("TRIAL_MARKER");
          if (!fs.stat(fs.child(entry, "payload")).type.equals("MISSING"))
            fs.removeOwned(entry, "payload");
          continue;
        }
        File payload = fs.child(entry, "payload");
        File launched = fs.child(entry, "launched");
        String launchedType = fs.stat(launched).type;
        if (!launchedType.equals("MISSING")) {
          if (!launchedType.equals("FILE")
              || !id.equals(new String(fs.small(launched, 128), StandardCharsets.US_ASCII)))
            throw new IOException("TRIAL_MARKER");
          if (fs.stat(fs.child(payload, ".dsha-web.pid")).type.equals("MISSING")
              && fs.stat(fs.child(payload, ".dsha-web.pid.stale")).type.equals("MISSING")) {
            // 启动尝试可能在写出 PID 前就失败：只有平台能证明该试运行 profile 已不存在时才关闭记录，
            // 读不到 / 仍存在都不算退出证据。
            stopper.confirmAbsent(id);
          } else {
            stopper.stopAndConfirm(payload);
          }
        }
        TrialSupport.write(fs, entry, "closed", id.getBytes(StandardCharsets.US_ASCII));
        fs.removeOwned(entry, "payload");
      } catch (java.io.InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException blocked) {
        if (Thread.currentThread().isInterrupted()) {
          java.io.InterruptedIOException cancelled =
              new java.io.InterruptedIOException("CANCELLED");
          cancelled.initCause(blocked);
          throw cancelled;
        }
        if (retained == null) retained = blocked;
        else retained.addSuppressed(blocked);
      }
    }
    if (retained != null) throw retained;
    RuntimeTrialRecords.pruneClosed(fs, home);
  }
}
