package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.GhAuthCommands;
import com.deepseekharness.app.util.GhAuthProbe;
import com.deepseekharness.app.util.GhHostsFile;
import com.deepseekharness.app.util.GhLoginState;
import com.deepseekharness.app.util.GhManagedInstall;
import com.deepseekharness.app.util.RuntimeWorkPort;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * GitHub 登录的本机状态读取与退出动作。
 *
 * <p>只把 hosts.yml 里界面需要的非密钥字段投影出来；{@code oauth_token} 的值在解析前丢弃，不进入日志、
 * SharedPreferences、数据库、用户导出或桥接口。退出调用受管 {@code gh auth logout}，只删除本机记录，
 * 不撤销 GitHub 远端授权。读取与退出都必须由调用方放到后台线程执行。
 *
 * <p>边界：hosts.yml 仍在容器内（网页、插件与终端同 UID 可读），本任务不改变这一点；真正的凭据原生化
 * 属于 T50 设计范围。本页只保证自己不经任何接口向外暴露 token。
 */
public final class GitHubLogin {
  /** 状态读取失败的可行动原因；不含文件内容。 */
  public enum Problem {
    NONE,
    ENV_NOT_READY,
    HOSTS_READ_FAILED,
    HOSTS_TOO_LARGE,
    HOSTS_NOT_REGULAR,
    HOSTS_MALFORMED,
    ACCOUNT_INVALID,
    LOGOUT_TIMEOUT,
    LOGOUT_FAILED,
    LOGOUT_INTERRUPTED
  }

  /** 一次状态读取的不可变快照；hosts 恒非 null，缺失时为空投影。 */
  public static final class Snapshot {
    public final boolean environmentReady;
    public final GhManagedInstall.State managed;
    public final boolean hostsPresent;
    public final Problem problem;
    public final GhLoginState.Login login;
    public final GhHostsFile.Result hosts;

    private Snapshot(
        boolean environmentReady,
        GhManagedInstall.State managed,
        boolean hostsPresent,
        Problem problem,
        GhLoginState.Login login,
        GhHostsFile.Result hosts) {
      this.environmentReady = environmentReady;
      this.managed = managed;
      this.hostsPresent = hostsPresent;
      this.problem = problem;
      this.login = login;
      this.hosts = hosts;
    }
  }

  /** 退出结果；detail 已脱敏、长度受限，只用于失败提示。 */
  public static final class LogoutResult {
    public final boolean ok;
    public final Problem problem;
    public final String detail;

    private LogoutResult(boolean ok, Problem problem, String detail) {
      this.ok = ok;
      this.problem = problem;
      this.detail = detail;
    }
  }

  private static final int MAX_LOGOUT_DETAIL = 320;
  private static final long LOGOUT_TIMEOUT_MS = 60_000;
  private static final long PROBE_TIMEOUT_MS = 45_000;

  private GitHubLogin() {}

  /** 读取本机 GitHub 登录状态；不执行任何 guest 命令，可在后台线程安全调用。 */
  public static Snapshot load(Context context) {
    ProotBootstrap proot;
    try {
      proot = HarnessController.get(context).proot();
    } catch (RuntimeException error) {
      return failed(Problem.ENV_NOT_READY);
    }
    boolean environmentReady = false;
    try {
      environmentReady = proot.isEnvironmentReady();
    } catch (RuntimeException ignored) {
      environmentReady = false;
    }
    File rootfs = proot.getRootfsDir();
    GhManagedInstall.State managed = GhManagedInstall.State.ENV_NOT_READY;
    try {
      Path bin = new File(rootfs, GhManagedInstall.BIN).toPath();
      BasicFileAttributes attrs =
          Files.readAttributes(bin, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      managed = GhManagedInstall.state(environmentReady, true, attrs.isRegularFile(), attrs.size());
    } catch (NoSuchFileException missing) {
      managed = GhManagedInstall.state(environmentReady, false, false, 0);
    } catch (IOException | RuntimeException unreadable) {
      managed = GhManagedInstall.State.UNREADABLE;
    }

    boolean hostsPresent = false;
    Problem problem = environmentReady ? Problem.NONE : Problem.ENV_NOT_READY;
    GhHostsFile.Result hosts = GhHostsFile.parse("");
    File hostsFile = new File(rootfs, GhManagedInstall.HOSTS);
    try {
      Path path = hostsFile.toPath();
      BasicFileAttributes attrs =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      hostsPresent = true;
      if (!attrs.isRegularFile()) {
        problem = Problem.HOSTS_NOT_REGULAR;
      } else if (attrs.size() > GhManagedInstall.MAX_HOSTS_BYTES) {
        problem = Problem.HOSTS_TOO_LARGE;
      } else {
        String text = readBounded(path);
        GhHostsFile.Result parsed = GhHostsFile.parse(text);
        if (parsed.status == GhHostsFile.Status.MALFORMED) {
          problem = Problem.HOSTS_MALFORMED;
        } else {
          hosts = parsed;
        }
      }
    } catch (NoSuchFileException missing) {
      hostsPresent = false;
    } catch (IOException | RuntimeException unreadable) {
      problem = Problem.HOSTS_READ_FAILED;
    }
    GhLoginState.Login login = GhLoginState.classify(hostsPresent, hosts);
    return new Snapshot(environmentReady, managed, hostsPresent, problem, login, hosts);
  }

  /**
   * 只读在线校验 gh 与 token：{@code gh api -i /user}。
   *
   * <p>能区分 CA/TLS 失败、401 token 失效、未登录与网络错误，并在成功时取回作用域。
   * 受管 gh 不可用时直接返回二进制状态，**不触发自举**；运行环境未就绪时不做容器调用。
   * 必须由后台线程调用。
   */
  public static GhAuthProbe.Result probe(Context context) {
    Snapshot snapshot = load(context);
    if (!snapshot.environmentReady) return GhAuthProbe.Result.skippedEnvironment();
    if (snapshot.managed != GhManagedInstall.State.AVAILABLE)
      return GhAuthProbe.Result.binaryUnavailable();
    ProotBootstrap proot;
    try {
      proot = HarnessController.get(context).proot();
    } catch (RuntimeException error) {
      return GhAuthProbe.Result.skippedEnvironment();
    }
    try (RuntimeWorkPort.Work work = RuntimeWorkPort.begin("GitHub 在线校验")) {
      Process process = proot.execRootfs(GhAuthCommands.probe());
      BoundedProcessRunner.Result result =
          BoundedProcessRunner.collect(process, PROBE_TIMEOUT_MS, 64 * 1024, Compat::destroy);
      String output =
          result.tail == null || result.tail.isEmpty()
              ? result.output
              : result.output + "\n" + result.tail;
      return GhAuthProbe.classify(result.exitCode, result.timedOut, output);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return GhAuthProbe.classify(-1, true, "");
    } catch (IOException | RuntimeException error) {
      return GhAuthProbe.Result.error(SensitiveData.redact(String.valueOf(error)));
    }
  }

  /**
   * 调用受管 gh 退出本机登录；必须由后台线程调用。成功只代表本机记录已删除，不代表远端授权已撤销。
   */
  public static LogoutResult logout(Context context, String account) {
    ProotBootstrap proot;
    try {
      proot = HarnessController.get(context).proot();
    } catch (RuntimeException error) {
      return new LogoutResult(false, Problem.ENV_NOT_READY, "");
    }
    String command;
    try {
      command = GhAuthCommands.logout(account);
    } catch (IllegalArgumentException invalid) {
      return new LogoutResult(false, Problem.ACCOUNT_INVALID, "");
    }
    try {
      if (!proot.isEnvironmentReady()) return new LogoutResult(false, Problem.ENV_NOT_READY, "");
      Process process;
      try (RuntimeWorkPort.Work work = RuntimeWorkPort.begin("GitHub 退出登录")) {
        process = proot.execRootfs(command);
        BoundedProcessRunner.Result result =
            BoundedProcessRunner.collect(process, LOGOUT_TIMEOUT_MS, 32 * 1024, Compat::destroy);
        if (result.timedOut) return new LogoutResult(false, Problem.LOGOUT_TIMEOUT, "");
        if (result.exitCode != 0)
          return new LogoutResult(
              false, Problem.LOGOUT_FAILED, SensitiveData.redact(tail(result.output, result.tail)));
        return new LogoutResult(true, Problem.NONE, "");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new LogoutResult(false, Problem.LOGOUT_INTERRUPTED, "");
    } catch (IOException | RuntimeException error) {
      return new LogoutResult(
          false, Problem.LOGOUT_FAILED, SensitiveData.redact(String.valueOf(error)));
    }
  }

  private static Snapshot failed(Problem problem) {
    return new Snapshot(
        false,
        GhManagedInstall.State.UNREADABLE,
        false,
        problem,
        GhLoginState.Login.NOT_LOGGED_IN,
        GhHostsFile.parse(""));
  }

  /** 以 NOFOLLOW 打开并读取有界文件；大小已在调用前检查，仍拒绝超限结果。 */
  private static String readBounded(Path path) throws IOException {
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.allocate(GhManagedInstall.MAX_HOSTS_BYTES + 1);
      while (buffer.hasRemaining()) {
        int read = channel.read(buffer);
        if (read < 0) break;
      }
      if (buffer.position() > GhManagedInstall.MAX_HOSTS_BYTES)
        throw new IOException("GH_HOSTS_TOO_LARGE");
      buffer.flip();
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(buffer)
            .toString();
      } catch (CharacterCodingException error) {
        throw new IOException("GH_HOSTS_ENCODING", error);
      }
    }
  }

  /** 优先使用尾部输出（gh 的失败信息通常在最后几行），并限制长度。 */
  private static String tail(String head, String tail) {
    String value = tail == null || tail.isEmpty() ? head : tail;
    if (value == null) return "";
    value = value.strip();
    return value.length() <= MAX_LOGOUT_DETAIL
        ? value
        : value.substring(value.length() - MAX_LOGOUT_DETAIL);
  }
}
