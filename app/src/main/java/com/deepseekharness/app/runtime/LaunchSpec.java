package com.deepseekharness.app.runtime;

import java.io.File;
import java.util.Map;

/** Immutable launch choices; diagnostics deliberately omit command and environment values. */
public final class LaunchSpec {
  final ContainerRuntime runtime;
  final boolean hardlinks, isolated, coldTrace, pipedInput;
  final File dataDomain;
  final BoundedGuestSessions.Operation record;
  final com.deepseekharness.app.util.ColdInstallPlan.Mode coldMode;
  final Map<String, String> environment;
  final java.util.List<ContainerRuntime.Bind> binds;

  private LaunchSpec(Builder b) {
    if (b.runtime == null) throw new IllegalArgumentException("LAUNCH_RUNTIME_MISSING");
    if (b.record != null && !b.isolated)
      throw new IllegalArgumentException("LAUNCH_RECORD_REQUIRES_ISOLATION");
    runtime = b.runtime;
    hardlinks = b.hardlinks;
    isolated = b.isolated;
    coldTrace = b.coldTrace;
    pipedInput = b.pipedInput;
    dataDomain = b.dataDomain;
    record = b.record;
    coldMode = b.coldMode;
    environment = Map.copyOf(b.environment);
    binds = java.util.List.copyOf(b.binds);
    for (var bind : binds)
      if (bind.host() == null
          || bind.guest() == null
          || !bind.guest().startsWith("/")
          || bind.guest().contains("/../")
          || bind.guest().endsWith("/..")
          || bind.host().indexOf('\0') >= 0
          || bind.guest().indexOf('\0') >= 0)
        throw new IllegalArgumentException("LAUNCH_BIND_FORMAT");
    for (var e : environment.entrySet())
      if (!e.getKey().matches("[A-Z_][A-Z0-9_]{0,99}") || e.getValue().indexOf('\0') >= 0)
        throw new IllegalArgumentException("LAUNCH_ENVIRONMENT_FORMAT");
  }

  public static Builder runtime(ContainerRuntime runtime) {
    return new Builder(runtime);
  }

  public static final class Builder {
    final ContainerRuntime runtime;
    boolean hardlinks, isolated, coldTrace, pipedInput;
    File dataDomain;
    BoundedGuestSessions.Operation record;
    com.deepseekharness.app.util.ColdInstallPlan.Mode coldMode;
    Map<String, String> environment = Map.of();
    final java.util.List<ContainerRuntime.Bind> binds = new java.util.ArrayList<>();

    private Builder(ContainerRuntime runtime) {
      this.runtime = runtime;
    }

    public Builder hardlinks(boolean value) {
      hardlinks = value;
      return this;
    }

    public Builder isolated(boolean value) {
      isolated = value;
      return this;
    }

    public Builder coldTrace(boolean value) {
      coldTrace = value;
      return this;
    }

    public Builder pipedInput(boolean value) {
      pipedInput = value;
      return this;
    }

    public Builder dataDomain(File value) {
      dataDomain = value;
      return this;
    }

    Builder record(BoundedGuestSessions.Operation value) {
      record = value;
      return this;
    }

    public Builder coldMode(com.deepseekharness.app.util.ColdInstallPlan.Mode value) {
      coldMode = value;
      return this;
    }

    public Builder environment(Map<String, String> value) {
      environment = value;
      return this;
    }

    public Builder bind(String host, String guest) {
      binds.add(new ContainerRuntime.Bind(host, guest));
      return this;
    }

    public LaunchSpec build() {
      return new LaunchSpec(this);
    }
  }

  @Override
  public String toString() {
    return "LaunchSpec[runtime="
        + runtime.id()
        + ", isolated="
        + isolated
        + ", pipedInput="
        + pipedInput
        + ", environmentKeys="
        + environment.keySet()
        + "]";
  }
}
