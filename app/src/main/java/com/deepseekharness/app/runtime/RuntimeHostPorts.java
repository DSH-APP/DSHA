package com.deepseekharness.app.runtime;

/** App-owned configuration snapshot and cold-install diagnostics boundary. */
public final class RuntimeHostPorts {
  private final ThreadLocal<Settings> invocation = new ThreadLocal<>();
  private final ThreadLocal<PendingColdSelection> pendingCold = new ThreadLocal<>();
  private volatile Provider provider;
  private final com.deepseekharness.app.util.DiagnosticFailures diagnosticFailures =
      new com.deepseekharness.app.util.DiagnosticFailures();

  /** The Android composition root provides one instance; runtime code owns no global registry. */
  public interface Owner {
    RuntimeHostPorts runtimeHostPorts();
  }

  public static RuntimeHostPorts fromOwner(Object owner) {
    if (!(owner instanceof Owner))
      throw new IllegalStateException("RUNTIME_HOST_OWNER_UNAVAILABLE");
    RuntimeHostPorts ports = ((Owner) owner).runtimeHostPorts();
    if (ports == null) throw new IllegalStateException("RUNTIME_HOST_PORTS_UNAVAILABLE");
    return ports;
  }

  public static final class Settings {
    public final String dnsMode;
    public final boolean proroot, staticLoader, disableProotSeccomp;

    public Settings(
        String dnsMode, boolean proroot, boolean staticLoader, boolean disableProotSeccomp) {
      if (dnsMode == null || !dnsMode.matches("auto|ipv4|native"))
        throw new IllegalArgumentException("RUNTIME_DNS_MODE");
      this.dnsMode = dnsMode;
      this.proroot = proroot;
      this.staticLoader = staticLoader;
      this.disableProotSeccomp = disableProotSeccomp;
    }
  }

  public interface Provider {
    Settings snapshot();

    /** 仅允许尚未选择运行方式的首次空环境试用快速候选；不改变日常运行默认值。 */
    default boolean preferFastColdMode() {
      return false;
    }

    void stage(String value);

    void record(String kind, String detail);

    void failure(Throwable error);

    /** Legacy/testing owners may keep diagnostic IDs; the production application supplies text. */
    default String describeWebStop(com.deepseekharness.app.util.WebStopDiagnostic diagnostic) {
      return diagnostic.debug();
    }

    default java.util.Map<String, Object> snapshotColdSelection() throws java.io.IOException {
      throw new java.io.IOException("COLD_SELECTION_AUTHORITY_UNAVAILABLE");
    }

    default void restoreColdSelection(java.util.Map<String, Object> before, String expectedRoot)
        throws java.io.IOException {
      throw new java.io.IOException("COLD_SELECTION_AUTHORITY_UNAVAILABLE");
    }

    default RuntimeTrial.BrowserProbe browserProbe() throws java.io.IOException {
      throw new java.io.IOException("TRIAL_BROWSER_PROBE_UNAVAILABLE");
    }

    /** 宿主核对发布后的私有根，返回路径、设备和 inode 身份；不能只返回目录名。 */
    default String coldRuntimeIdentity(java.io.File rootfs) throws java.io.IOException {
      throw new java.io.IOException("COLD_RUNTIME_IDENTITY_UNAVAILABLE");
    }

    default Settings successfulColdRuntime(
        java.io.File rootfs,
        com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
        String packageSlotSha)
        throws java.io.IOException {
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_UNAVAILABLE");
    }
  }

  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  public synchronized void install(Provider value) {
    if (value == null) throw new IllegalArgumentException("RUNTIME_PROVIDER_MISSING");
    if (provider != null && provider != value)
      throw new IllegalStateException("RUNTIME_PROVIDER_ALREADY_INSTALLED");
    provider = value;
  }

  private Provider required() {
    Provider value = provider;
    if (value == null) throw new IllegalStateException("RUNTIME_PROVIDER_UNAVAILABLE");
    return value;
  }

  public Settings settings() {
    Settings current = invocation.get();
    if (current != null) return current;
    Settings value = required().snapshot();
    if (value == null) throw new IllegalStateException("RUNTIME_SETTINGS_UNAVAILABLE");
    return value;
  }

  public boolean preferFastColdMode() {
    return required().preferFastColdMode();
  }

  public Scope open() {
    if (invocation.get() != null) return () -> {};
    Settings value = settings();
    invocation.set(value);
    return () -> {
      pendingCold.remove();
      invocation.remove();
    };
  }

  public String diagnosticFailure() {
    return diagnosticFailures.summary();
  }

  public java.util.List<com.deepseekharness.app.util.DiagnosticFailures.Entry>
      diagnosticFailures() {
    return diagnosticFailures.snapshot();
  }

  private void emit(String operation, java.util.function.Consumer<Provider> action) {
    Provider value = required();
    try {
      action.accept(value);
    } catch (RuntimeException error) {
      diagnosticFailures.add(operation, error);
    }
  }

  public void stage(String stage) {
    emit("stage", value -> value.stage(stage));
  }

  public void record(String kind, String detail) {
    emit("record:" + kind, value -> value.record(kind, detail));
  }

  public void failure(Throwable error) {
    emit("failure", value -> value.failure(error));
  }

  public String describeWebStop(com.deepseekharness.app.util.WebStopDiagnostic diagnostic) {
    return required().describeWebStop(java.util.Objects.requireNonNull(diagnostic));
  }

  public RuntimeTrial.BrowserProbe browserProbe() throws java.io.IOException {
    RuntimeTrial.BrowserProbe value = required().browserProbe();
    if (value == null) throw new java.io.IOException("TRIAL_BROWSER_PROBE_UNAVAILABLE");
    return value;
  }

  public java.util.Map<String, Object> snapshotColdSelection() throws java.io.IOException {
    return required().snapshotColdSelection();
  }

  public void restoreColdSelection(java.util.Map<String, Object> before, String expectedRoot)
      throws java.io.IOException {
    Provider value = required();
    // Publication may fail before any preference mutation. An exact unchanged projection needs no
    // rollback.
    if (!before.equals(value.snapshotColdSelection()))
      value.restoreColdSelection(before, expectedRoot);
    Settings restored = value.snapshot();
    if (restored == null) throw new java.io.IOException("RUNTIME_SETTINGS_UNAVAILABLE");
    pendingCold.remove();
    if (invocation.get() != null) invocation.set(restored);
  }

  private static final class PendingColdSelection {
    final java.io.File root;
    final String identity, packages, runtimeId;
    final com.deepseekharness.app.util.ColdInstallPlan.Mode mode;
    final Settings settings, beforeSettings;
    final java.util.Map<String, Object> before;

    PendingColdSelection(
        java.io.File root,
        String identity,
        String packages,
        String runtimeId,
        com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
        Settings settings,
        Settings beforeSettings,
        java.util.Map<String, Object> before) {
      this.root = root;
      this.identity = identity;
      this.packages = packages;
      this.runtimeId = runtimeId;
      this.mode = mode;
      this.settings = settings;
      this.beforeSettings = beforeSettings;
      this.before = java.util.Map.copyOf(before);
    }
  }

  /** 冷工具探针只给本次完整试运行选择方式；此处不写偏好或健康回执。 */
  public void prepareColdRuntime(
      java.io.File publishedRootfs,
      com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
      String packageSlotSha,
      String expectedRuntimeId)
      throws java.io.IOException {
    Settings frozen = invocation.get();
    if (frozen == null) throw new java.io.IOException("COLD_RUNTIME_REQUIRES_INVOCATION");
    if (pendingCold.get() != null) throw new java.io.IOException("COLD_RUNTIME_PREPARATION_EXISTS");
    if (publishedRootfs == null
        || mode == null
        || packageSlotSha == null
        || !packageSlotSha.matches("[a-f0-9]{64}")
        || expectedRuntimeId == null
        || !expectedRuntimeId.matches("[a-f0-9]{64}"))
      throw new java.io.IOException("COLD_RUNTIME_PREPARATION");
    Provider provider = required();
    Settings stored = provider.snapshot();
    if (stored == null) throw new java.io.IOException("RUNTIME_SETTINGS_UNAVAILABLE");
    if ("proroot".equals(mode.runtime)
        && (!stored.proroot && !provider.preferFastColdMode()
            || stored.staticLoader != frozen.staticLoader))
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_CHANGED");
    if (mode == com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT_DYNAMIC
        && (!provider.preferFastColdMode() || !frozen.staticLoader))
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_CHANGED");
    java.io.File root = publishedRootfs.getCanonicalFile();
    String identity = provider.coldRuntimeIdentity(root);
    if (identity == null || identity.isEmpty())
      throw new java.io.IOException("COLD_RUNTIME_IDENTITY_UNAVAILABLE");
    java.util.Map<String, Object> before = provider.snapshotColdSelection();
    if (before == null) throw new java.io.IOException("COLD_SELECTION_AUTHORITY_UNAVAILABLE");
    Settings candidate =
        new Settings(
            frozen.dnsMode,
            "proroot".equals(mode.runtime),
            mode.staticLoader(frozen.staticLoader),
            "proroot".equals(mode.runtime) ? frozen.disableProotSeccomp : mode.noSeccomp);
    pendingCold.set(
        new PendingColdSelection(
            root, identity, packageSlotSha, expectedRuntimeId, mode, candidate, frozen, before));
    invocation.set(candidate);
  }

  /** 只接受本次完整真实试运行且 guest 已关闭的证明，再持久保存实际成功方式。 */
  public void confirmColdRuntime(java.io.File publishedRootfs, java.util.Map<String, Object> proof)
      throws java.io.IOException {
    PendingColdSelection pending = pendingCold.get();
    if (invocation.get() == null || pending == null)
      throw new java.io.IOException("COLD_RUNTIME_PREPARATION_MISSING");
    if (invocation.get() != pending.settings)
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_CHANGED");
    if (publishedRootfs == null
        || !pending.root.equals(publishedRootfs.getCanonicalFile())
        || !pending.identity.equals(required().coldRuntimeIdentity(pending.root)))
      throw new java.io.IOException("COLD_RUNTIME_ROOT_CHANGED");
    if (!com.deepseekharness.app.backup.RuntimeDescriptor.healthy(proof, pending.runtimeId))
      throw new java.io.IOException("COLD_RUNTIME_TRIAL_UNCONFIRMED");
    Object actual = proof.get("runtimeMode");
    boolean sameMode = pending.mode.runtime.equals(actual);
    boolean compatibilityFallback =
        "proroot".equals(pending.mode.runtime)
            && "proot".equals(actual)
            && "proroot".equals(proof.get("fallbackFrom"))
            && (proof.get("fallbackExitCode") instanceof Integer
                || proof.get("fallbackExitCode") instanceof Long)
            && ((Number) proof.get("fallbackExitCode")).longValue() >= Integer.MIN_VALUE
            && ((Number) proof.get("fallbackExitCode")).longValue() <= Integer.MAX_VALUE;
    if (!sameMode && !compatibilityFallback
        || sameMode && (proof.containsKey("fallbackFrom") || proof.containsKey("fallbackExitCode")))
      throw new java.io.IOException("COLD_RUNTIME_TRIAL_MODE_MISMATCH");
    Provider provider = required();
    if (!pending.before.equals(provider.snapshotColdSelection()))
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_CHANGED");
    Settings stored = provider.snapshot();
    if (stored == null
        || "proroot".equals(actual) && stored.staticLoader != pending.beforeSettings.staticLoader)
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_CHANGED");
    com.deepseekharness.app.util.ColdInstallPlan.Mode actualMode =
        sameMode
            ? pending.mode
            : pending.settings.disableProotSeccomp
                ? com.deepseekharness.app.util.ColdInstallPlan.Mode.PROOT_COMPAT
                : com.deepseekharness.app.util.ColdInstallPlan.Mode.PROOT;
    successfulColdRuntime(pending.root, actualMode, pending.packages);
    pendingCold.remove();
  }

  /** The successful installation explicitly changes this invocation's selected runtime too. */
  public void successfulColdRuntime(
      java.io.File rootfs,
      com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
      String packageSlotSha)
      throws java.io.IOException {
    Settings proven = settings();
    Settings selected = required().successfulColdRuntime(rootfs, mode, packageSlotSha);
    if (selected == null
        || selected.proroot != "proroot".equals(mode.runtime)
        || "proroot".equals(mode.runtime)
            && selected.staticLoader != mode.staticLoader(proven.staticLoader)
        || !"proroot".equals(mode.runtime) && selected.disableProotSeccomp != mode.noSeccomp)
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_MISMATCH");
    if (invocation.get() != null) invocation.set(selected);
  }
}
