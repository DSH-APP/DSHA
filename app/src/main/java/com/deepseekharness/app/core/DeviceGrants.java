package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.util.NativeConsentPolicy;
import com.deepseekharness.app.util.NativeConsentPolicy.ReadState;
import com.deepseekharness.app.util.NativeConsentPolicy.SaveState;

/** 独立原生授权存储，不从环境、插件文件或备份自动恢复授权。 */
public final class DeviceGrants {
  private static final Object SCREEN_LOCK = new Object();
  private static final String SCREEN_POLICY = "screen_confirmation_v1";
  private static final String SENSITIVE_SCREEN_POLICY = "sensitive_screen_v1";
  private static final String GLOBAL_POLICY = "native_global_consent_v1";
  private static volatile boolean globalChanging;
  private static volatile boolean globalUnavailable;
  private static volatile SaveState lastSave = SaveState.NONE;
  private static final java.util.concurrent.atomic.AtomicLong mutationGeneration =
      new java.util.concurrent.atomic.AtomicLong();
  private static final java.util.concurrent.atomic.AtomicLong observedRevision =
      new java.util.concurrent.atomic.AtomicLong();

  /**
   * 敏感界面策略修订号的本进程下限：记录损坏被解码成默认策略时，合并修订号不能回退到旧值，
   * 否则旧的一次性确认可能在新策略下复活。进程重启后旧请求本来就不存在，因此不需要持久化。
   */
  private static final java.util.concurrent.atomic.AtomicLong sensitiveRevisionFloor =
      new java.util.concurrent.atomic.AtomicLong();

  private static final java.util.concurrent.atomic.AtomicBoolean unsafeRead =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final android.content.SharedPreferences preferences;
  private final Runnable invalidateScreen;
  private final Runnable invalidateSms;
  private final Runnable invalidateClipboard;
  private final Runnable invalidateAll;

  public DeviceGrants(Context context) {
    Context app = context.getApplicationContext();
    invalidateScreen = () -> com.deepseekharness.app.HttpShellService.screenPolicyChanged(app);
    invalidateSms = () -> com.deepseekharness.app.HttpShellService.smsPolicyChanged(app);
    invalidateClipboard =
        () -> com.deepseekharness.app.HttpShellService.clipboardPolicyChanged(app);
    invalidateAll = () -> com.deepseekharness.app.HttpShellService.globalConsentChanged(app);
    preferences = context.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
  }

  DeviceGrants(android.content.SharedPreferences preferences, Runnable invalidateScreen) {
    this(preferences, invalidateScreen, () -> {});
  }

  DeviceGrants(
      android.content.SharedPreferences preferences,
      Runnable invalidateScreen,
      Runnable invalidateSms) {
    this(preferences, invalidateScreen, invalidateSms, () -> {});
  }

  DeviceGrants(
      android.content.SharedPreferences preferences,
      Runnable invalidateScreen,
      Runnable invalidateSms,
      Runnable invalidateClipboard) {
    this.preferences = preferences;
    this.invalidateScreen = invalidateScreen;
    this.invalidateSms = invalidateSms;
    this.invalidateClipboard = invalidateClipboard;
    this.invalidateAll =
        () -> {
          invalidateScreen.run();
          invalidateSms.run();
          invalidateClipboard.run();
        };
  }

  public boolean smsReadAllowed() {
    return smsReadConfigured();
  }

  public boolean smsReadConfigured() {
    return preferences.getBoolean("sms_read", false);
  }

  /**
   * T130 实体屏特权执行：默认关闭、可撤销、无独立后台常驻授权。
   *
   * <p>只授权"允许使用"；是否能在当前任务里动作仍由任务身份、屏幕确认策略与目标窗口决定。
   * 与短信/剪贴板一样存原生授权，不从环境、插件或备份恢复。
   */
  public boolean privilegedExecutionAllowed() {
    try {
      return preferences.getBoolean("privileged_execution_v1", false);
    } catch (RuntimeException unreadable) {
      return false;
    }
  }

  public boolean setPrivilegedExecutionAllowed(boolean allowed) {
    try {
      boolean saved = preferences.edit().putBoolean("privileged_execution_v1", allowed).commit();
      return saved && privilegedExecutionAllowed() == allowed;
    } catch (RuntimeException failed) {
      return false;
    }
  }

  public NativeConsentPolicy.Snapshot consentSnapshot() {
    // Reads never acquire SCREEN_LOCK beneath the bridge lifecycle lock.
    long readGeneration = mutationGeneration.get();
    if (globalChanging)
      return new NativeConsentPolicy.Snapshot(ReadState.CHANGING, lastSave, observedRevision.get());
    try {
      String value = preferences.getString(GLOBAL_POLICY, null);
      NativeConsentPolicy policy;
      if (value == null && observedRevision.get() > 0) {
        latchUnsafeRead();
        return new NativeConsentPolicy.Snapshot(
            ReadState.MALFORMED, lastSave, observedRevision.get());
      }
      if (value == null) policy = new NativeConsentPolicy(false, 0);
      else {
        try {
          policy = NativeConsentPolicy.decodeStrict(value);
        } catch (IllegalArgumentException malformed) {
          latchUnsafeRead();
          return new NativeConsentPolicy.Snapshot(
              ReadState.MALFORMED, lastSave, observedRevision.get());
        }
      }
      if (policy.revision() < observedRevision.get()) {
        latchUnsafeRead();
        return new NativeConsentPolicy.Snapshot(
            ReadState.RECONFIRM_REQUIRED, lastSave, observedRevision.get());
      }
      observedRevision.accumulateAndGet(policy.revision(), Math::max);
      if (globalChanging || mutationGeneration.get() != readGeneration)
        return new NativeConsentPolicy.Snapshot(
            ReadState.CHANGING, lastSave, observedRevision.get());
      return new NativeConsentPolicy.Snapshot(
          globalUnavailable
              ? ReadState.SAVE_FAILED
              : unsafeRead.get()
                  ? ReadState.RECONFIRM_REQUIRED
                  : value == null
                      ? ReadState.UNCONFIGURED
                      : policy.globalAllow() ? ReadState.ON : ReadState.OFF,
          lastSave,
          policy.revision());
    } catch (ClassCastException malformed) {
      latchUnsafeRead();
      return new NativeConsentPolicy.Snapshot(
          ReadState.MALFORMED, lastSave, observedRevision.get());
    } catch (RuntimeException unavailable) {
      latchUnsafeRead();
      return new NativeConsentPolicy.Snapshot(
          ReadState.READ_UNAVAILABLE, lastSave, observedRevision.get());
    }
  }

  private static void latchUnsafeRead() {
    // Never restore an old revision merely because a later cache read succeeds.
    if (unsafeRead.compareAndSet(false, true)) mutationGeneration.incrementAndGet();
  }

  public NativeConsentPolicy consentPolicy() {
    return consentSnapshot().policy();
  }

  /** Opaque, same-process confirmation candidate; it is never exported or used as authority. */
  public static final class ConsentRecovery {
    private final long generation, revision;
    private final boolean present;
    private final Object original;

    private ConsentRecovery(long generation, long revision, boolean present, Object original) {
      this.generation = generation;
      this.revision = revision;
      this.present = present;
      this.original =
          original instanceof java.util.Set<?> values ? java.util.Set.copyOf(values) : original;
    }
  }

  public ConsentRecovery prepareConsentRecovery() {
    synchronized (SCREEN_LOCK) {
      if (!consentSnapshot().canRebuild()) return null;
      try {
        var values = preferences.getAll();
        Object original = values.get(GLOBAL_POLICY);
        if (values.containsKey(GLOBAL_POLICY) && !supportedPreference(original)) return null;
        return new ConsentRecovery(
            mutationGeneration.get(),
            observedRevision.get(),
            values.containsKey(GLOBAL_POLICY),
            original);
      } catch (RuntimeException unavailable) {
        return null;
      }
    }
  }

  /** Explicit native recovery only: preserve the original, invalidate requests, then publish OFF. */
  public boolean rebuildConsentDisabled(ConsentRecovery candidate) {
    synchronized (SCREEN_LOCK) {
      if (candidate == null
          || globalChanging
          || candidate.generation != mutationGeneration.get()
          || candidate.revision != observedRevision.get()) return false;
      try {
        if (!matchesRecovery(candidate)) return false;
        var next = new NativeConsentPolicy(false, observedRevision.get()).next(false);
        String quarantine = "native_global_consent_quarantine_v1_" + java.util.UUID.randomUUID();
        String evidence = "1\n" + candidate.present + "\n" + candidate.revision;
        Object storedOriginal = storedOriginal(candidate.original);
        var editor = preferences.edit().putString(quarantine + "_record", evidence);
        if (candidate.present) preservePreference(editor, quarantine + "_value", storedOriginal);
        if (!editor.commit()) {
          lastSave = SaveState.COMMIT_FAILED;
          globalUnavailable = true;
          return false;
        }
        var preserved = preferences.getAll();
        if (!evidence.equals(preserved.get(quarantine + "_record"))
            || (candidate.present
                && !storedOriginal.equals(preserved.get(quarantine + "_value")))) {
          lastSave = SaveState.READBACK_FAILED;
          globalUnavailable = true;
          return false;
        }
        globalChanging = true;
        mutationGeneration.incrementAndGet();
        try {
          try {
            invalidateAll.run();
          } catch (RuntimeException invalidationFailed) {
            lastSave = SaveState.INVALIDATION_FAILED;
            globalUnavailable = true;
            return false;
          }
          if (!matchesRecovery(candidate)) {
            lastSave = SaveState.INVALID_RECORD;
            globalUnavailable = true;
            return false;
          }
          observedRevision.accumulateAndGet(next.revision(), Math::max);
          if (!preferences.edit().putString(GLOBAL_POLICY, next.encode()).commit()) {
            lastSave = SaveState.COMMIT_FAILED;
            globalUnavailable = true;
            return false;
          }
          if (!next.equals(
              NativeConsentPolicy.decodeStrict(preferences.getString(GLOBAL_POLICY, null)))) {
            lastSave = SaveState.READBACK_FAILED;
            globalUnavailable = true;
            return false;
          }
          lastSave = SaveState.CACHE_VERIFIED;
          globalUnavailable = false;
          unsafeRead.set(false);
          return true;
        } finally {
          globalChanging = false;
        }
      } catch (RuntimeException failed) {
        lastSave = SaveState.READ_FAILED;
        globalUnavailable = true;
        return false;
      }
    }
  }

  private boolean matchesRecovery(ConsentRecovery candidate) {
    var values = preferences.getAll();
    return values.containsKey(GLOBAL_POLICY) == candidate.present
        && java.util.Objects.equals(values.get(GLOBAL_POLICY), candidate.original);
  }

  private static boolean supportedPreference(Object value) {
    return value instanceof String
        || value instanceof Boolean
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof java.util.Set<?> values
            && values.stream().allMatch(item -> item instanceof String);
  }

  private static Object storedOriginal(Object value) {
    // Base64 prevents XML newline indentation from changing quarantined string contents too.
    if (value instanceof String text)
      return "utf8-base64:"
          + java.util.Base64.getEncoder()
              .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if (value instanceof java.util.Set<?> values) {
      java.util.Set<String> strings = new java.util.HashSet<>();
      for (Object item : values) strings.add((String) storedOriginal(item));
      return strings;
    }
    return value;
  }

  private static void preservePreference(
      android.content.SharedPreferences.Editor editor, String key, Object value) {
    if (value instanceof String text) editor.putString(key, text);
    else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
    else if (value instanceof Integer number) editor.putInt(key, number);
    else if (value instanceof Long number) editor.putLong(key, number);
    else if (value instanceof Float number) editor.putFloat(key, number);
    else if (value instanceof java.util.Set<?> values) {
      java.util.Set<String> strings = new java.util.HashSet<>();
      for (Object item : values) strings.add((String) item);
      editor.putStringSet(key, strings);
    } else throw new IllegalArgumentException("CONSENT_QUARANTINE_TYPE");
  }

  public boolean setGlobalAllowed(boolean allowed) {
    synchronized (SCREEN_LOCK) {
      NativeConsentPolicy current;
      String value;
      try {
        value = preferences.getString(GLOBAL_POLICY, null);
        if (value == null && observedRevision.get() > 0)
          throw new IllegalArgumentException("CONSENT_RECORD_DISAPPEARED");
        current =
            value == null
                ? new NativeConsentPolicy(false, 0)
                : NativeConsentPolicy.decodeStrict(value);
      } catch (IllegalArgumentException invalid) {
        lastSave = SaveState.INVALID_RECORD;
        globalUnavailable = true;
        return false;
      } catch (RuntimeException unavailable) {
        lastSave = SaveState.READ_FAILED;
        globalUnavailable = true;
        return false;
      }
      // A repeated healthy choice is not a new authorization. Failure/reconfirmation retries
      // must still advance the fence, and legacy records still need a deliberate v2 save.
      if (!globalChanging
          && !globalUnavailable
          && !unsafeRead.get()
          && current.revision() >= observedRevision.get()
          && current.globalAllow() == allowed
          && current.encode().equals(value)) {
        observedRevision.accumulateAndGet(current.revision(), Math::max);
        return true;
      }
      final NativeConsentPolicy next;
      try {
        long base = Math.max(current.revision(), observedRevision.get());
        next = new NativeConsentPolicy(current.globalAllow(), base).next(allowed);
      } catch (RuntimeException exhausted) {
        lastSave = SaveState.REVISION_EXHAUSTED;
        globalUnavailable = true;
        return false;
      }
      globalChanging = true;
      mutationGeneration.incrementAndGet();
      try {
        try {
          invalidateAll.run();
        } catch (RuntimeException failed) {
          lastSave = SaveState.INVALIDATION_FAILED;
          globalUnavailable = true;
          return false;
        }
        observedRevision.accumulateAndGet(next.revision(), Math::max);
        try {
          if (!preferences.edit().putString(GLOBAL_POLICY, next.encode()).commit()) {
            lastSave = SaveState.COMMIT_FAILED;
            globalUnavailable = true;
            return false;
          }
        } catch (RuntimeException failed) {
          lastSave = SaveState.COMMIT_FAILED;
          globalUnavailable = true;
          return false;
        }
        // SharedPreferences readback checks the same-process cache, not a new-process disk reload.
        if (!next.equals(
            NativeConsentPolicy.decodeStrict(preferences.getString(GLOBAL_POLICY, null)))) {
          lastSave = SaveState.READBACK_FAILED;
          globalUnavailable = true;
          return false;
        }
        lastSave = SaveState.CACHE_VERIFIED;
        globalUnavailable = false;
        unsafeRead.set(false);
        return true;
      } catch (RuntimeException failed) {
        lastSave = SaveState.READBACK_FAILED;
        globalUnavailable = true;
        return false;
      } finally {
        globalChanging = false;
      }
    }
  }

  public com.deepseekharness.app.util.ScreenConfirmationPolicy effectiveScreenPolicy() {
    var individual = screenPolicy();
    return consentPolicy().globalAllow()
        ? new com.deepseekharness.app.util.ScreenConfirmationPolicy(
            com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.ALLOW_ALL,
            individual.rules,
            individual.revision)
        : individual;
  }

  public boolean setSmsReadAllowed(boolean allowed) {
    invalidateSms.run();
    return preferences.edit().putBoolean("sms_read", allowed).commit();
  }

  public com.deepseekharness.app.util.ClipboardReadPolicy.Mode clipboardReadMode() {
    try {
      return com.deepseekharness.app.util.ClipboardReadPolicy.Mode.decode(
          preferences.getString("clipboard_read_mode_v1", null));
    } catch (RuntimeException malformed) {
      return com.deepseekharness.app.util.ClipboardReadPolicy.Mode.OFF;
    }
  }

  /** Policy revision is process-local and only fences in-flight requests; it is not portable data. */
  private static final java.util.concurrent.atomic.AtomicLong clipboardRevision =
      new java.util.concurrent.atomic.AtomicLong();

  public long clipboardReadRevision() {
    return clipboardRevision.get();
  }

  public boolean setClipboardReadMode(com.deepseekharness.app.util.ClipboardReadPolicy.Mode mode) {
    if (mode == null) return false;
    invalidateClipboard.run();
    try {
      boolean saved = preferences.edit().putString("clipboard_read_mode_v1", mode.id).commit();
      if (saved) clipboardRevision.incrementAndGet();
      return saved && clipboardReadMode() == mode;
    } catch (RuntimeException failed) {
      return false;
    }
  }

  public com.deepseekharness.app.util.ScreenConfirmationPolicy screenPolicy() {
    synchronized (SCREEN_LOCK) {
      try {
        String value = preferences.getString(SCREEN_POLICY, "");
        return com.deepseekharness.app.util.ScreenConfirmationPolicy.decode(value);
      } catch (Exception malformed) {
        // Corrupt or future preference data never silently enables control.
        return com.deepseekharness.app.util.ScreenConfirmationPolicy.manual();
      }
    }
  }

  public boolean setScreenPolicy(
      com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode mode,
      java.util.Map<String, com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule> rules) {
    synchronized (SCREEN_LOCK) {
      try {
        var next =
            new com.deepseekharness.app.util.ScreenConfirmationPolicy(
                mode, rules, Math.addExact(screenPolicy().revision, 1));
        String value = next.encode();
        // Fence queued operations and old notifications before publishing any new policy.
        invalidateScreen.run();
        return preferences.edit().putString(SCREEN_POLICY, value).commit();
      } catch (Exception invalid) {
        return false;
      }
    }
  }

  /**
   * 敏感界面分档策略（T119）。与确认方式正交：密码文本硬阻断不受这里控制；
   * 「全部允许」也不把敏感类别的 ASK 降成静默放行。损坏/未来格式回默认 ASK，绝不回放行。
   */
  public com.deepseekharness.app.util.SensitiveScreenPolicy sensitiveScreenPolicy() {
    synchronized (SCREEN_LOCK) {
      com.deepseekharness.app.util.SensitiveScreenPolicy policy;
      try {
        policy =
            com.deepseekharness.app.util.SensitiveScreenPolicy.decode(
                preferences.getString(SENSITIVE_SCREEN_POLICY, ""));
      } catch (RuntimeException malformed) {
        policy = com.deepseekharness.app.util.SensitiveScreenPolicy.defaults();
      }
      sensitiveRevisionFloor.accumulateAndGet(policy.revision(), Math::max);
      return policy;
    }
  }

  /** 保存单个类别的应对方式；成功才递增修订号，旧确认/旧请求在写入前失效。 */
  public boolean setSensitiveScreenMode(
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category category,
      com.deepseekharness.app.util.SensitiveScreenPolicy.Mode mode) {
    if (category == null || mode == null) return false;
    var next = sensitiveScreenPolicy().withMode(category, mode);
    return saveSensitiveScreenPolicy(next);
  }

  /** 保存应用名单（信任 / 强制交接）；两个名单互斥，默认都为空。 */
  public boolean setSensitiveScreenApps(
      java.util.Set<String> trust, java.util.Set<String> handoff) {
    com.deepseekharness.app.util.SensitiveScreenPolicy next;
    try {
      next = sensitiveScreenPolicy().withApps(trust, handoff);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
    return saveSensitiveScreenPolicy(next);
  }

  private boolean saveSensitiveScreenPolicy(
      com.deepseekharness.app.util.SensitiveScreenPolicy next) {
    if (next == null) return false;
    synchronized (SCREEN_LOCK) {
      try {
        // 进程内修订号下限：即使记录被外部清空/损坏，新保存也不能回到旧修订号。
        long floor = sensitiveRevisionFloor.get();
        if (next.revision() <= floor) next = next.withRevision(floor + 1);
        String value = next.encode();
        // 与确认方式同一撤销入口：先作废旧请求/旧弹窗，再发布新策略。
        invalidateScreen.run();
        if (!preferences.edit().putString(SENSITIVE_SCREEN_POLICY, value).commit()) return false;
        boolean verified = value.equals(preferences.getString(SENSITIVE_SCREEN_POLICY, null));
        if (verified) sensitiveRevisionFloor.accumulateAndGet(next.revision(), Math::max);
        return verified;
      } catch (RuntimeException failed) {
        return false;
      }
    }
  }

  /** 撤销/恢复默认：所有类别回到询问、清空名单；修订号仍严格递增。 */
  public boolean resetSensitiveScreenPolicy() {
    return saveSensitiveScreenPolicy(sensitiveScreenPolicy().reset());
  }

  /**
   * 屏幕授权策略的合并修订号：确认方式与敏感界面策略任一保存都严格递增。
   * 两个修订号都非负且保存时恰好递增一个，因此加法不会与旧值碰撞；溢出时饱和，宁可让旧请求失效。
   */
  public long screenAuthorityRevision() {
    long screen = screenPolicy().revision;
    long sensitive = Math.max(sensitiveScreenPolicy().revision(), sensitiveRevisionFloor.get());
    try {
      return Math.addExact(screen, sensitive);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }
}
