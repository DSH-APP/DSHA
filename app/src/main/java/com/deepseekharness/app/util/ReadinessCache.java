package com.deepseekharness.app.util;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 终端输入 / 切换等热路径只读的环境就绪结论缓存。
 *
 * <p>全量就绪检查（描述符解析与哈希）仍由安装、维护与启动门禁执行；本缓存只在以下条件全部满足时复用上次结论：
 * 维护代次已知且当前无维护、代次未变化、廉价文件指纹未变化、未超过有效期。任一条件不满足即重新执行全量检查。
 * 维护进行中始终走全量检查，不放宽门禁。
 */
public final class ReadinessCache {
  /** 维护代次未知（未安装提供者）。 */
  public static final long EPOCH_UNKNOWN = -1;

  private final BooleanSupplier fullCheck;
  private final Supplier<String> fingerprint;
  private final LongSupplier maintenanceEpoch;
  private final LongSupplier clockMillis;
  private final long ttlMillis;

  private boolean valid;
  private boolean ready;
  private long epoch;
  private String key;
  private long checkedAt;

  public ReadinessCache(
      BooleanSupplier fullCheck,
      Supplier<String> fingerprint,
      LongSupplier maintenanceEpoch,
      LongSupplier clockMillis,
      long ttlMillis) {
    this.fullCheck = Objects.requireNonNull(fullCheck);
    this.fingerprint = Objects.requireNonNull(fingerprint);
    this.maintenanceEpoch = Objects.requireNonNull(maintenanceEpoch);
    this.clockMillis = Objects.requireNonNull(clockMillis);
    if (ttlMillis <= 0) throw new IllegalArgumentException("READINESS_TTL_INVALID");
    this.ttlMillis = ttlMillis;
  }

  /** 维护代次为偶数表示空闲，奇数表示维护进行中；未知时不缓存。 */
  public static boolean idleEpoch(long epoch) {
    return epoch >= 0 && (epoch & 1) == 0;
  }

  /** 热路径读取：缓存有效时不执行全量检查。 */
  public boolean cached() {
    long nowEpoch = maintenanceEpoch.getAsLong();
    String nowKey = safeFingerprint();
    long now = clockMillis.getAsLong();
    synchronized (this) {
      if (valid
          && idleEpoch(nowEpoch)
          && nowEpoch == epoch
          && nowKey != null
          && nowKey.equals(key)
          && now >= checkedAt
          && now - checkedAt < ttlMillis) return ready;
    }
    return refresh();
  }

  /** 全量检查并在条件稳定时更新缓存；门禁调用方直接用它，结论与未缓存时一致。 */
  public boolean refresh() {
    // 先取代次与指纹再检查：检查期间若环境变化，下一次读取会因键不一致而重算，不会存下过期结论。
    long beforeEpoch = maintenanceEpoch.getAsLong();
    String beforeKey = safeFingerprint();
    boolean result = fullCheck.getAsBoolean();
    long afterEpoch = maintenanceEpoch.getAsLong();
    long now = clockMillis.getAsLong();
    synchronized (this) {
      if (idleEpoch(beforeEpoch) && beforeEpoch == afterEpoch && beforeKey != null) {
        valid = true;
        ready = result;
        epoch = beforeEpoch;
        key = beforeKey;
        checkedAt = now;
      } else {
        valid = false;
      }
    }
    return result;
  }

  public synchronized void invalidate() {
    valid = false;
  }

  private String safeFingerprint() {
    try {
      return fingerprint.get();
    } catch (RuntimeException unavailable) {
      return null;
    }
  }
}
