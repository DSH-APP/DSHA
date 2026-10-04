package com.deepseekharness.app.util;

/** Guest process work lifetime without a runtime-to-Android coordinator dependency. */
public final class RuntimeWorkPort {
  private RuntimeWorkPort() {}

  public interface Work extends AutoCloseable {
    void retainUntilExit(Process process);

    @Override
    void close();
  }

  public interface Provider {
    Work begin(boolean detached, String kind);

    boolean hasOtherTasks();
  }

  private static volatile Provider provider;

  public static synchronized void install(Provider value) {
    if (value == null) throw new IllegalArgumentException("RUNTIME_WORK_PROVIDER_MISSING");
    if (provider != null && provider != value)
      throw new IllegalStateException("RUNTIME_WORK_PROVIDER_ALREADY_INSTALLED");
    provider = value;
  }

  private static Provider required() {
    Provider value = provider;
    if (value == null) throw new IllegalStateException("RUNTIME_WORK_PROVIDER_UNAVAILABLE");
    return value;
  }

  public static Work begin() {
    return begin("容器命令");
  }

  public static Work begin(String kind) {
    return required().begin(false, kind);
  }

  public static Work beginDetached(String kind) {
    return required().begin(true, kind);
  }

  public static boolean hasOtherTasks() {
    return required().hasOtherTasks();
  }
}
