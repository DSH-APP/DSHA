package com.deepseekharness.app.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One-time PackageManager context for Root/Shizuku app_process launchers. */
public final class PrivilegedPackageContext {
  private static final Object LOCK = new Object();
  private static final long INIT_TIMEOUT_MS = 10_000;
  private static volatile Context systemContext;
  private static volatile IOException initializationFailure;
  private static boolean initializationAttempted;

  private PrivilegedPackageContext() {}

  /** Root/app_process binds identity to the exact APK used to load this invocation. */
  public static com.deepseekharness.app.util.SelfPackageIdentity launchIdentity(String packageName)
      throws IOException {
    return launchIdentity(packageName, -1);
  }

  public static com.deepseekharness.app.util.SelfPackageIdentity launchIdentity(
      String packageName, int expectedUid) throws IOException {
    try {
      Context system = systemContext();
      if (expectedUid >= 0 && expectedUid < 10000)
        throw new IOException("SELF_PACKAGE_CALLER_UNVERIFIED");
      Context owner =
          expectedUid >= 0
              ? packageContextForUser(system, packageName, expectedUid / 100000)
              : system;
      android.content.pm.ApplicationInfo info =
          owner.getPackageManager().getApplicationInfo(packageName, 0);
      if (expectedUid >= 0 && info.uid != expectedUid)
        throw new IOException("SELF_PACKAGE_CALLER_UNVERIFIED");
      return com.deepseekharness.app.util.SelfPackageIdentity.launch(
          packageName,
          info.uid,
          checkedSource(info.sourceDir),
          System.getProperty("java.class.path", ""));
    } catch (IOException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException("SELF_PACKAGE_UNAVAILABLE", error);
    }
  }

  /** Shizuku 13 supplies its package Context; 12 uses the actual Binder UID and loaded dex origin. */
  public static com.deepseekharness.app.util.SelfPackageIdentity callerIdentity(
      Class<?> anchor, Context supplied) throws IOException {
    int caller = android.os.Binder.getCallingUid();
    try {
      Context system = supplied != null ? supplied : systemContext();
      String[] packages = system.getPackageManager().getPackagesForUid(caller);
      if (packages == null || packages.length == 0)
        throw new IOException("SELF_PACKAGE_CALLER_UNVERIFIED");
      if (supplied != null) {
        if (supplied.getClassLoader() != anchor.getClassLoader())
          throw new IOException("SELF_PACKAGE_LOADER_UNVERIFIED");
        var info = supplied.getApplicationInfo();
        String source = checkedSource(info.sourceDir);
        return com.deepseekharness.app.util.SelfPackageIdentity.caller(
            supplied.getPackageName(), info.uid, source, source, caller, packages);
      }
      java.util.Set<String> loaded = loadedApks(anchor.getClassLoader());
      com.deepseekharness.app.util.SelfPackageIdentity result = null;
      for (String name : packages) {
        Context app = packageContextForUser(system, name, caller / 100000);
        var info = app.getApplicationInfo();
        String source = checkedSource(info.sourceDir);
        if (!loaded.contains(source)) continue;
        if (result != null) throw new IOException("SELF_PACKAGE_AMBIGUOUS");
        result =
            com.deepseekharness.app.util.SelfPackageIdentity.caller(
                name, info.uid, source, source, caller, packages);
      }
      if (result == null) throw new IOException("SELF_PACKAGE_LOADER_UNVERIFIED");
      return result;
    } catch (IOException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException("SELF_PACKAGE_UNAVAILABLE", error);
    }
  }

  private static Context packageContextForUser(Context system, String name, int user)
      throws Exception {
    if (user == android.os.Process.myUid() / 100000)
      return system.createPackageContext(
          name, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
    java.lang.reflect.Constructor<android.os.UserHandle> constructor =
        android.os.UserHandle.class.getDeclaredConstructor(int.class);
    constructor.setAccessible(true);
    return (Context)
        Context.class
            .getMethod(
                "createPackageContextAsUser", String.class, int.class, android.os.UserHandle.class)
            .invoke(
                system,
                name,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY,
                constructor.newInstance(user));
  }

  private static java.lang.reflect.Field field(Class<?> type, String name)
      throws NoSuchFieldException {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        var found = current.getDeclaredField(name);
        found.setAccessible(true);
        return found;
      } catch (NoSuchFieldException absent) {
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static java.util.Set<String> loadedApks(ClassLoader loader) throws Exception {
    java.util.Set<String> sources = new java.util.HashSet<>();
    // Inspect actual DexFile origins, never ClassLoader.toString or caller-supplied package text.
    Object paths = field(loader.getClass(), "pathList").get(loader);
    Object[] elements = (Object[]) field(paths.getClass(), "dexElements").get(paths);
    for (Object element : elements) {
      Object dex = field(element.getClass(), "dexFile").get(element);
      if (dex == null) continue;
      String name = (String) dex.getClass().getMethod("getName").invoke(dex);
      if (name != null && com.deepseekharness.app.util.DeviceShellPolicy.canonicalApkPath(name))
        sources.add(checkedSource(name));
    }
    return sources;
  }

  private static String checkedSource(String source) throws IOException {
    java.io.File file = new java.io.File(source);
    String canonical = file.getCanonicalPath();
    if (!file.isFile()
        || !source.equals(canonical)
        || !com.deepseekharness.app.util.DeviceShellPolicy.canonicalApkPath(canonical))
      throw new IOException("SELF_PACKAGE_SOURCE_UNVERIFIED");
    return canonical;
  }

  public static Context systemContext() throws IOException {
    try {
      return systemContextInternal();
    } catch (IOException error) {
      throw new IOException(
          com.deepseekharness.app.util.PrivilegedContextFailure.describe(
              android.os.Build.VERSION.SDK_INT, error),
          error);
    }
  }

  private static Context systemContextInternal() throws IOException {
    Context cached = systemContext;
    if (cached != null) return cached;
    Looper main = Looper.getMainLooper();
    if (main == null) {
      // Bare app_process entry points have no Android main Looper. Prepare it before
      // ActivityThread.systemMain(), which constructs handlers during attach().
      if (Looper.myLooper() != null) throw new IOException("PRIVILEGED_MAIN_LOOPER_UNAVAILABLE");
      try {
        Looper.prepareMainLooper();
      } catch (RuntimeException error) {
        throw new IOException("PRIVILEGED_MAIN_LOOPER_UNAVAILABLE", error);
      }
      main = Looper.getMainLooper();
    }
    if (Looper.myLooper() == main) return initializeOnMain();

    CountDownLatch ready = new CountDownLatch(1);
    AtomicReference<Context> result = new AtomicReference<>();
    AtomicReference<IOException> failure = new AtomicReference<>();
    if (!new Handler(main)
        .post(
            () -> {
              try {
                result.set(initializeOnMain());
              } catch (IOException error) {
                failure.set(error);
              } finally {
                ready.countDown();
              }
            })) throw new IOException("PRIVILEGED_PACKAGE_CONTEXT_POST_FAILED");
    try {
      if (!ready.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        throw new IOException("PRIVILEGED_PACKAGE_CONTEXT_TIMEOUT");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException("PRIVILEGED_PACKAGE_CONTEXT_INTERRUPTED", error);
    }
    if (failure.get() != null) throw failure.get();
    Context initialized = result.get();
    if (initialized == null) throw new IOException("PRIVILEGED_PACKAGE_CONTEXT_MISSING");
    return initialized;
  }

  private static Context initializeOnMain() throws IOException {
    Context cached = systemContext;
    if (cached != null) return cached;
    synchronized (LOCK) {
      cached = systemContext;
      if (cached != null) return cached;
      if (initializationFailure != null) throw initializationFailure;
      if (initializationAttempted)
        throw new IOException("PRIVILEGED_PACKAGE_CONTEXT_ALREADY_ATTEMPTED");
      if (Looper.getMainLooper() == null || Looper.myLooper() != Looper.getMainLooper())
        throw new IOException("PRIVILEGED_MAIN_LOOPER_MISMATCH");
      initializationAttempted = true;
      try {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        if (context == null || context.getPackageManager() == null)
          throw new IOException("PRIVILEGED_PACKAGE_MANAGER_MISSING");
        systemContext = context;
        return context;
      } catch (IOException error) {
        initializationFailure = error;
        throw error;
      } catch (Throwable error) {
        initializationFailure = new IOException("PRIVILEGED_PACKAGE_CONTEXT_UNAVAILABLE", error);
        throw initializationFailure;
      }
    }
  }
}
