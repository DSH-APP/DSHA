package com.deepseekharness.app.util;

import java.util.HashMap;
import java.util.Map;

/** 仅登记本进程实际启动并完成受控工具检查的应急 Web；归档和 profile 名不能自行取得豁免。 */
public final class RuntimeInstanceRegistry {
  private static final RuntimeInstanceRegistry SHARED = new RuntimeInstanceRegistry();
  private final Map<String, Entry> entries = new HashMap<>();

  public static RuntimeInstanceRegistry shared() {
    return SHARED;
  }

  private static final class Entry {
    final long generation;
    final WebPidIdentity identity;
    final String profile;

    Entry(long generation, WebPidIdentity identity, String profile) {
      this.generation = generation;
      this.identity = identity;
      this.profile = profile;
    }
  }

  public synchronized void registerRecovery(
      String id, long generation, WebPidIdentity identity, String command, boolean toolsVerified) {
    if (!validId(id)
        || generation < 1
        || identity == null
        || identity.exited()
        || !toolsVerified
        || !matchesCommand(command, "dsha-emergency-" + id))
      throw new IllegalArgumentException("RECOVERY_IDENTITY_UNVERIFIED");
    Entry previous = entries.get(id);
    if (previous != null && previous.generation >= generation)
      throw new IllegalStateException("RECOVERY_GENERATION_REUSED");
    entries.put(id, new Entry(generation, identity, "dsha-emergency-" + id));
  }

  /** DENIED/null、PID 重用及同名伪装绝不因此获得维护豁免。 */
  public synchronized boolean isIndependentRecovery(WebPidIdentity current, String command) {
    if (current == null || current.exited()) return false;
    for (Entry entry : entries.values())
      if (entry.identity.sameProcess(current) && matchesCommand(command, entry.profile))
        return true;
    return false;
  }

  public synchronized void remove(String id, long generation) {
    Entry entry = entries.get(id);
    if (entry != null && entry.generation == generation) entries.remove(id);
  }

  public static boolean validId(String id) {
    return id != null && id.matches("[a-f0-9]{32}");
  }

  public static boolean matchesCommand(String command, String profile) {
    if (command == null || profile == null || !profile.matches("dsha-emergency-[a-f0-9]{32}"))
      return false;
    String[] args =
        command.indexOf('\0') >= 0 ? command.split("\u0000") : command.trim().split("\\s+");
    if (args.length < 4 || !(args[0].equals("node") || args[0].endsWith("/node"))) return false;
    int entry = args[1].equals("--expose-internals") ? 2 : 1;
    if (entry + 2 >= args.length
        || !args[entry].endsWith("/node_modules/@deepseek-ai/dsh/lib/bin.js")) return false;
    return args[entry + 1].equals("--profile") && args[entry + 2].equals(profile);
  }
}
