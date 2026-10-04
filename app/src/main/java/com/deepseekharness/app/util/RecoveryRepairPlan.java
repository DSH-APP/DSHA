package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 应急候选的单次决议；身份、源摘要或数据代次变化后必须重新预览。 */
public final class RecoveryRepairPlan {
  public enum State {
    PENDING,
    APPLYING,
    APPLIED,
    REJECTED,
    FAILED,
    EXPIRED
  }

  /**
   * Recovery remains a five-tool, host-controlled surface.  CLI dependency
   * repair is deliberately a separate action so the assistant can recover a
   * missing package without receiving an arbitrary shell or node_modules
   * write primitive.  The host rehydrates the pinned managed tree from the
   * signed APK and runs the existing isolated trial before committing it.
   */
  public static final Set<String> ACTIONS =
      Set.of(
          "recover-maintenance",
          "repair-runtime",
          "repair-cli-dependencies",
          "profile-settings",
          "new-web-profile",
          "new-global-patch");

  public final String id = UUID.randomUUID().toString();
  public final String nonce = UUID.randomUUID().toString();
  public final String session, action, target, sourceSha256, dataGeneration, content;
  public final long generation;
  private State state = State.PENDING;
  private String message = "";
  private static final AtomicInteger NATIVE_REPAIRS = new AtomicInteger();

  /** 只统计已经原生确认的修复；应急服务停止不能把仍在运行的事务计为结束。 */
  public static int activeNativeRepairs() {
    return NATIVE_REPAIRS.get();
  }

  public static final class NativeRepairLease implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();

    private NativeRepairLease() {
      NATIVE_REPAIRS.incrementAndGet();
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) NATIVE_REPAIRS.decrementAndGet();
    }
  }

  public RecoveryRepairPlan(
      String session,
      long generation,
      String action,
      String target,
      String sourceSha256,
      String dataGeneration,
      String content)
      throws IOException {
    if (session == null
        || !session.matches("[a-f0-9]{32}")
        || generation < 1
        || !ACTIONS.contains(action)
        || target == null
        || !target.matches("[a-z0-9][a-z0-9-]{0,79}")
        || !sha(sourceSha256)
        || !sha(dataGeneration)) throw new IOException("REPAIR_PLAN_INVALID");
    if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 262144)
      throw new IOException("REPAIR_CONTENT_LIMIT");
    this.session = session;
    this.generation = generation;
    this.action = action;
    this.target = target;
    this.sourceSha256 = sourceSha256;
    this.dataGeneration = dataGeneration;
    this.content = content;
  }

  private static boolean sha(String value) {
    return value != null && value.matches("[a-f0-9]{64}");
  }

  public synchronized State state() {
    return state;
  }

  public synchronized String message() {
    return message;
  }

  /** 启动进程只能证明存活、不能核验出生身份时，诊断可用而确认必须保持未决。 */
  public static void requireWritable(boolean blocked, String reason) throws IOException {
    if (blocked)
      throw new IOException(
          "REPAIR_READ_ONLY: "
              + (reason == null || reason.isEmpty() ? "PROCESS_IDENTITY_UNCONFIRMED" : reason));
  }

  public synchronized void begin(
      String currentSession, long currentGeneration, String nonce, String source, String data)
      throws IOException {
    if (state != State.PENDING) throw new IOException("REPAIR_ALREADY_DECIDED");
    if (!session.equals(currentSession)
        || generation != currentGeneration
        || !this.nonce.equals(nonce)) throw new IOException("REPAIR_SESSION_CHANGED");
    if (!sourceSha256.equals(source) || !dataGeneration.equals(data)) {
      state = State.EXPIRED;
      throw new IOException("REPAIR_SOURCE_CHANGED");
    }
    state = State.APPLYING;
  }

  public synchronized NativeRepairLease beginNative(
      String currentSession, long currentGeneration, String nonce, String source, String data)
      throws IOException {
    begin(currentSession, currentGeneration, nonce, source, data);
    return new NativeRepairLease();
  }

  public synchronized void reject() throws IOException {
    if (state != State.PENDING) throw new IOException("REPAIR_ALREADY_DECIDED");
    state = State.REJECTED;
  }

  public synchronized void expire() {
    if (state == State.PENDING) state = State.EXPIRED;
  }

  public synchronized void complete(boolean success, String message) throws IOException {
    if (state != State.APPLYING) throw new IOException("REPAIR_NOT_APPLYING");
    this.message = message == null ? "" : message;
    state = success ? State.APPLIED : State.FAILED;
  }

  public static String digest(byte[] bytes) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder();
      for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return result.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
