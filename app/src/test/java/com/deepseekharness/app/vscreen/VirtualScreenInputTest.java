package com.deepseekharness.app.vscreen;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.view.InputEvent;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public final class VirtualScreenInputTest {
  public static final class LegacyApi {
    static final LegacyApi INSTANCE = new LegacyApi();

    public static Object getInstance() {
      return INSTANCE;
    }

    public boolean injectInputEvent(InputEvent event, int mode) {
      return mode == 0;
    }
  }

  public static final class GlobalApi {
    static final GlobalApi INSTANCE = new GlobalApi();

    public static Object getInstance() {
      return INSTANCE;
    }

    public boolean injectInputEvent(InputEvent event, int mode) {
      return mode == 0;
    }
  }

  public static final class LegacyWithoutSingleton {
    public static boolean injectInputEvent(InputEvent event, int mode) {
      return true;
    }
  }

  public static final class BrokenLegacy {
    public static Object getInstance() {
      throw new IllegalStateException("legacy invocation failed");
    }

    public static boolean injectInputEvent(InputEvent event, int mode) {
      return true;
    }
  }

  public static final class LegacyWithoutInjection {
    public static Object getInstance() {
      return new Object();
    }
  }

  @Test
  public void usesLegacyInputManagerWhenItsSingletonExists() throws Exception {
    VirtualScreenInput.InputApi api =
        VirtualScreenInput.resolveInputApi(
            LegacyApi.class,
            () -> {
              throw new AssertionError("旧版系统不应加载 InputManagerGlobal");
            });
    assertSame(LegacyApi.INSTANCE, api.manager);
    assertSame(LegacyApi.class, api.inject.getDeclaringClass());
    assertTrue(Boolean.TRUE.equals(api.inject.invoke(api.manager, null, 0)));
  }

  @Test
  public void fallsBackToInputManagerGlobalWhenLegacySingletonIsMissing() throws Exception {
    VirtualScreenInput.InputApi api =
        VirtualScreenInput.resolveInputApi(LegacyWithoutSingleton.class, () -> GlobalApi.class);
    assertSame(GlobalApi.INSTANCE, api.manager);
    assertSame(GlobalApi.class, api.inject.getDeclaringClass());
    assertTrue(Boolean.TRUE.equals(api.inject.invoke(api.manager, null, 0)));
  }

  @Test
  public void doesNotHideLegacySingletonInvocationFailure() {
    InvocationTargetException error =
        assertThrows(
            InvocationTargetException.class,
            () ->
                VirtualScreenInput.resolveInputApi(
                    BrokenLegacy.class,
                    () -> {
                      throw new AssertionError("调用失败不能回退");
                    }));
    assertSame(IllegalStateException.class, error.getCause().getClass());
  }

  @Test
  public void doesNotHideMissingLegacyInjectionMethod() {
    assertThrows(
        NoSuchMethodException.class,
        () ->
            VirtualScreenInput.resolveInputApi(
                LegacyWithoutInjection.class,
                () -> {
                  throw new AssertionError("注入方法缺失不能回退");
                }));
  }

  @Test
  public void propagatesFailureToLoadGlobalApi() {
    SecurityException denied = new SecurityException("denied");
    assertSame(
        denied,
        assertThrows(
            SecurityException.class,
            () ->
                VirtualScreenInput.resolveInputApi(
                    LegacyWithoutSingleton.class,
                    () -> {
                      throw denied;
                    })));
  }

  @Test
  public void uncertainCommandIsSubmittedOnceAndRetained() {
    java.util.concurrent.atomic.AtomicInteger count =
        new java.util.concurrent.atomic.AtomicInteger();
    VirtualScreenInput input =
        VirtualScreenInput.commandForTest(
            8,
            (argv, timeout) -> {
              count.incrementAndGet();
              org.junit.Assert.assertEquals("8", argv[2]);
              return com.deepseekharness.app.util.VirtualScreenInputResult.UNKNOWN;
            });
    assertSame(
        com.deepseekharness.app.util.VirtualScreenInputResult.UNKNOWN,
        input.commandForTest(new String[] {"/system/bin/input", "-d", "8", "keyevent", "4"}));
    assertSame(com.deepseekharness.app.util.VirtualScreenInputResult.UNKNOWN, input.lastResult());
    org.junit.Assert.assertEquals(1, count.get());
  }

  @Test
  public void commandForTestRunsInjectedRunnerEvenWhenHostProbeIsUnavailable() {
    // CI runner 没有 /system/bin/input：测试缝必须由注入 runner 代表命令可用性，
    // 而不是被真实文件探测提前拒成 REJECTED（T97 合入后的 unit-source 红）。
    java.util.concurrent.atomic.AtomicInteger count =
        new java.util.concurrent.atomic.AtomicInteger();
    VirtualScreenInput input =
        VirtualScreenInput.commandForTest(
            8,
            (argv, timeout) -> {
              count.incrementAndGet();
              org.junit.Assert.assertArrayEquals(
                  new String[] {"/system/bin/input", "-d", "8", "text", "hello%sworld"}, argv);
              return com.deepseekharness.app.util.VirtualScreenInputResult.ACCEPTED;
            });
    assertSame(
        com.deepseekharness.app.util.VirtualScreenInputResult.ACCEPTED, input.text("hello world"));
    org.junit.Assert.assertEquals(1, count.get());
  }

  @Test
  public void missingHostCommandStillRejectsWhenCommandIsNotForced() {
    java.util.concurrent.atomic.AtomicInteger count =
        new java.util.concurrent.atomic.AtomicInteger();
    VirtualScreenInput input =
        VirtualScreenInput.commandForTest(
            8,
            (argv, timeout) -> {
              count.incrementAndGet();
              return com.deepseekharness.app.util.VirtualScreenInputResult.ACCEPTED;
            },
            false);
    assertSame(
        com.deepseekharness.app.util.VirtualScreenInputResult.REJECTED, input.text("123456"));
    org.junit.Assert.assertEquals(0, count.get());
  }

  public static final class CommandChild {
    public static void main(String[] args) throws Exception {
      java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), "executed");
      if (args[1].equals("timeout")) Thread.sleep(30000);
      else if (args[1].equals("output")) System.out.print("x".repeat(5000));
      else if (args[1].equals("failure")) System.exit(7);
    }
  }

  @Test
  public void actualCommandMayExecuteBeforeTimeoutOrBadReceipt() throws Exception {
    java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("dsha-input-result-");
    java.nio.file.Path marker = directory.resolve("submitted.txt");
    try {
      for (String mode : new String[] {"timeout", "output", "failure"}) {
        VirtualScreenInput input = VirtualScreenInput.commandForTest(8, null);
        String javaBinary =
            new java.io.File(
                    System.getProperty("java.home"),
                    "bin/java"
                        + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""))
                .getPath();
        String childClasspath =
            java.nio.file.Path.of(
                    CommandChild.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        String[] argv = {
          javaBinary, "-cp", childClasspath, CommandChild.class.getName(), marker.toString(), mode
        };
        assertSame(
            com.deepseekharness.app.util.VirtualScreenInputResult.UNKNOWN,
            input.commandForTest(argv, 2000));
        org.junit.Assert.assertEquals("executed", java.nio.file.Files.readString(marker));
        java.nio.file.Files.delete(marker);
      }
      assertSame(
          com.deepseekharness.app.util.VirtualScreenInputResult.REJECTED,
          VirtualScreenInput.commandForTest(8, null)
              .commandForTest(new String[] {directory.resolve("does-not-exist").toString()}));
    } finally {
      java.nio.file.Files.deleteIfExists(marker);
      java.nio.file.Files.delete(directory);
    }
  }
}
