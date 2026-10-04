package com.deepseekharness.app.runtime;

/** App-owned configuration snapshot and cold-install diagnostics boundary. */
public final class RuntimeHostPorts {
  private final ThreadLocal<Settings> invocation = new ThreadLocal<>();
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

  public Scope open() {
    if (invocation.get() != null) return () -> {};
    Settings value = settings();
    invocation.set(value);
    return invocation::remove;
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
    if (invocation.get() != null) invocation.set(restored);
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
        || selected.proroot != (mode == com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT)
        || mode == com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT
            && selected.staticLoader != proven.staticLoader
        || mode != com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT
            && selected.disableProotSeccomp != mode.noSeccomp)
      throw new java.io.IOException("COLD_RUNTIME_SELECTION_MISMATCH");
    if (invocation.get() != null) invocation.set(selected);
  }
}
