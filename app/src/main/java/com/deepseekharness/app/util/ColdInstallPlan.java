package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Probe private disposable paths before choosing one runtime for the package write. */
public final class ColdInstallPlan {
  public static final String PROBE_READY = "DSHA_COLD_RUNTIME_READY";
  public static final String INSTALL_READY = "DSHA_UBUNTU_TOOLS_READY";

  private ColdInstallPlan() {}

  public enum Mode {
    PROROOT("proroot", false),
    PROOT("proot", false),
    PROOT_COMPAT("proot", true);
    public final String runtime;
    public final boolean noSeccomp;

    Mode(String runtime, boolean noSeccomp) {
      this.runtime = runtime;
      this.noSeccomp = noSeccomp;
    }
  }

  public static final class Observation {
    public final int exitCode;
    public final boolean timedOut, exited;
    public final String output, diagnostic;

    public Observation(
        int exitCode, boolean timedOut, boolean exited, String output, String diagnostic) {
      this.exitCode = exitCode;
      this.timedOut = timedOut;
      this.exited = exited;
      this.output = output;
      this.diagnostic = diagnostic;
    }

    public boolean ready(String marker) {
      return exited
          && !timedOut
          && exitCode == 0
          && output != null
          && ("\n" + output + "\n").contains("\n" + marker + "\n");
    }
  }

  public interface Runner {
    Observation run(Mode mode, boolean probe) throws IOException, InterruptedException;
  }

  public interface Recorder {
    void record(Mode mode, boolean probe, Observation observation);
  }

  public interface CheckedAction {
    void run() throws IOException;
  }

  /** An unavailable durable selection must never publish a new environment-ready marker. */
  public static void publishReady(CheckedAction selectRuntime, CheckedAction publishMarker)
      throws IOException {
    selectRuntime.run();
    publishMarker.run();
  }

  public static List<Mode> modes(
      boolean preferProroot, boolean prorootAvailable, boolean isolated, boolean noSeccomp) {
    List<Mode> modes = new ArrayList<>();
    if (preferProroot && prorootAvailable && isolated) modes.add(Mode.PROROOT);
    if (!noSeccomp) modes.add(Mode.PROOT);
    modes.add(Mode.PROOT_COMPAT);
    return List.copyOf(modes);
  }

  /** At most three probes and one package installation; no retry after package writes. */
  public static Mode run(List<Mode> modes, Runner runner, Recorder recorder)
      throws IOException, InterruptedException {
    if (modes.isEmpty() || modes.size() > 3 || modes.stream().distinct().count() != modes.size())
      throw new IllegalArgumentException("COLD_RUNTIME_PLAN");
    for (Mode mode : modes) {
      Observation probe = runner.run(mode, true);
      recorder.record(mode, true, probe);
      if (!probe.exited) throw new IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
      if (!probe.ready(PROBE_READY)) continue;
      Observation installed = runner.run(mode, false);
      recorder.record(mode, false, installed);
      if (!installed.exited) throw new IOException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
      if (!installed.ready(INSTALL_READY))
        throw new IOException(
            "COLD_INSTALL_POSTCHECK_FAILED: " + mode + "\n" + installed.diagnostic);
      return mode;
    }
    throw new IOException("COLD_RUNTIME_PROBE_FAILED");
  }

  public static void applyEnvironment(Map<String, String> environment, Mode mode) {
    if (mode.noSeccomp) environment.put("PROOT_NO_SECCOMP", "1");
    else environment.remove("PROOT_NO_SECCOMP");
  }

  public static String probeScript() {
    return "set -eu\nprobe=\"$1\"\n"
        + "[ ! -e \"$probe\" ] && [ ! -L \"$probe\" ] || exit 64\n"
        + "/bin/mkdir -- \"$probe\"\n"
        + "trap '/bin/rm -f -- \"$probe/plain\"; /bin/rmdir -- \"$probe\"' EXIT\n"
        + "/bin/bash -c 'printf DSHA_COLD_PROBE' > \"$probe/plain\"\n"
        + "/usr/bin/python3 -I -S -c "
        + ShellQuote.arg(
            "import hashlib, os, sqlite3, ssl, sys; "
                + "p=sys.argv[1]; assert open(p,'rb').read()==b'DSHA_COLD_PROBE'; "
                + "f=open(p,'wb'); f.write(b'DSHA_COLD_VERIFIED'); f.flush(); os.fsync(f.fileno()); f.close(); "
                + "assert open(p,'rb').read()==b'DSHA_COLD_VERIFIED'; assert hashlib.sha256(b'probe').digest(); "
                + "assert sqlite3.sqlite_version; assert ssl.OPENSSL_VERSION")
        + " \"$probe/plain\"\n"
        + "printf '\\n"
        + PROBE_READY
        + "\\n'\n";
  }

  public static String probeCommand(String guest) {
    if (guest == null || !guest.matches("/root/\\.dsha-cold-probe-[a-f0-9-]{36}"))
      throw new IllegalArgumentException("COLD_PROBE_PATH");
    return "/bin/bash -c "
        + ShellQuote.arg(probeScript())
        + " dsha-cold-probe "
        + ShellQuote.arg(guest);
  }

  public static String installCommand(String guest) {
    if (guest == null || !guest.matches("/root/\\.dsha-bundled-tools-[a-f0-9-]{36}"))
      throw new IllegalArgumentException("COLD_PACKAGE_PATH");
    return "/bin/bash /root/dsh-bin/install-ubuntu-tools " + ShellQuote.arg(guest);
  }
}
