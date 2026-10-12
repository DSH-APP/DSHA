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
}
