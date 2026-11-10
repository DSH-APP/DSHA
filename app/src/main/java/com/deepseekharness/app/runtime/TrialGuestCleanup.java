package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.SensitiveData;
import java.io.IOException;

/**
 * 隔离试运行的收尾顺序：先按记录优雅停止；只有优雅停止未确认时，才对本轮 guest 做身份
 * 核验后的有界强制结束并复核。
 *
 * <p>为什么不能一看到停止未确认就抛错：试运行结束到 guest 真正退出之间可能只是慢一拍的
 * 优雅收尾（对话写入、插件卸载）。旧实现把「3 秒窗口内没退完」直接当成环境损坏，于是本次
 * 更新必然失败。这里只做两件仍受身份约束的事：等待本次持有的启动器，以及按记录强杀本轮
 * 唯一的 guest；停止仍无法确认才把可导出诊断交给调用方，由调用方保留现场。
 *
 * <p>启动器残留不构成失败：proroot 启动器可能晚退（Android 16 实测），确认口径只看本轮
 * guest 的记录与「没有本应用 Web」，启动器只做一次有界清理。
 *
 * <p>本类不碰 Android API，也不扫描 /proc、不按 PID 或名称结束任何其它进程：强杀能力由
 * {@link Io#force()} 提供，那是 WebProcessManager 里按记录与保存的出生身份核验过的入口。
 */
final class TrialGuestCleanup {
  interface Io {
    /** 与 WebProcessManager.stop() 同语义：空串=已确认没有本应用 Web。 */
    String stop() throws IOException;

    /** 本轮记录绑定的 guest 的有界强制结束：空串=已确认退出。不得作用于其它进程。 */
    String force();

    /** 本次持有的启动器句柄是否已退出；没有句柄时返回 true。 */
    boolean launcherExited();

    /** 有界等待本次持有的启动器退出；没有句柄时立即返回。 */
    void awaitLauncher(long millis);

    /** 只清理本次持有的启动器句柄。 */
    void destroyLauncher();

    /** 可导出的诊断；实现不得抛出。 */
    void diagnostic(String detail);
  }

  private TrialGuestCleanup() {}

  /**
   * @return 空串表示已确认停止；否则为已写入诊断的详情，调用方应据此保留现场并报告
   *     TRIAL_PROCESS_UNCONFIRMED。
   */
  static String stop(Io io) throws IOException {
    String first = io.stop();
    if (first.isEmpty()) {
      io.awaitLauncher(3000);
      if (!io.launcherExited()) {
        io.destroyLauncher();
        io.awaitLauncher(1000);
      }
      return "";
    }
    // 优雅停止未确认：先给本次启动器一个退出窗口，再只强杀本轮记录绑定的 guest。
    io.awaitLauncher(3000);
    String forced = io.force();
    if (!io.launcherExited()) {
      io.destroyLauncher();
      io.awaitLauncher(1000);
    }
    String retry = io.stop();
    if (retry.isEmpty()) return "";
    StringBuilder detail = new StringBuilder("WEB_GUESTS_UNCONFIRMED stop=").append(first);
    if (!forced.isEmpty()) detail.append(" force=").append(forced);
    detail.append(" retry=").append(retry);
    return unconfirmed(io, detail.toString());
  }

  /** 供调用方在 stop() 之后仍拿不到全局确认时写入同一处诊断。 */
  static String unconfirmed(Io io, String detail) {
    String safe = SensitiveData.redact(detail);
    try {
      io.diagnostic(safe);
    } catch (RuntimeException ignored) {
      // 诊断写入失败不能替换真正的停止结论。
    }
    return safe;
  }
}
