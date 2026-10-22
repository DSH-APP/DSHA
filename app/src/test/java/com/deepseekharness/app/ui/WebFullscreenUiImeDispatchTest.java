package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.deepseekharness.app.R;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/** 通过公开安装入口验证平台事件顺序，也可运行于未修改的 2054 源码。 */
@RunWith(WebFullscreenUiImeDispatchTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WebFullscreenUiImeDispatchTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  public static final class Host extends Activity {
    Content content;
    Resources colors;

    @Override
    public Resources getResources() {
      Resources base = super.getResources();
      if (colors == null)
        colors =
            new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
              @Override
              public int getColor(int id, Theme theme) {
                if (id == R.color.surface || id == R.color.terminal_surface)
                  return android.graphics.Color.BLACK;
                return super.getColor(id, theme);
              }
            };
      return colors;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends View> T findViewById(int id) {
      return id == android.R.id.content && content != null ? (T) content : super.findViewById(id);
    }
  }

  private static final class Content extends FrameLayout {
    boolean focused = true;
    int paddingWrites;

    Content(Context context) {
      super(context);
    }

    @Override
    public boolean hasWindowFocus() {
      return focused;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
      super.setPadding(left, top, right, bottom);
      paddingWrites++;
    }
  }

  private WindowInsetsCompat insets(int keyboard) {
    return new WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 14))
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
        .build();
  }

  private Content install(Host host) {
    host.content = new Content(host);
    host.setContentView(host.content);
    host.content.layout(0, 0, 400, 800);
    WebFullscreenUi.install(host);
    return host.content;
  }

  @Test
  public void applyEndStateBeforeStartMustWaitForTheActualAnimationFrames() {
    var controller = Robolectric.buildActivity(Host.class).setup().visible();
    try {
      Content content = install(controller.get());
      ViewCompat.dispatchApplyWindowInsets(content, insets(0));
      WindowInsetsAnimation animation =
          new WindowInsetsAnimation(WindowInsets.Type.ime(), null, 250);
      content.dispatchWindowInsetsAnimationPrepare(animation);
      ViewCompat.dispatchApplyWindowInsets(content, insets(300));
      assertEquals(
          "onApply is an end target, not an animation frame", 14, content.getPaddingBottom());
      content.dispatchWindowInsetsAnimationProgress(
          insets(80).toWindowInsets(), List.of(animation));
      assertEquals(14, content.getPaddingBottom());
      content.dispatchWindowInsetsAnimationEnd(animation);
    } finally {
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void realFocusObserverMustReleaseImeBeforeAnotherWindowsInsetsArrive() {
    var controller = Robolectric.buildActivity(Host.class).setup().visible();
    try {
      Content content = install(controller.get());
      ViewCompat.dispatchApplyWindowInsets(content, insets(300));
      assertEquals(300, content.getPaddingBottom());
      content.focused = false;
      ReflectionHelpers.callInstanceMethod(
          content.getViewTreeObserver(),
          "dispatchOnWindowFocusChange",
          ReflectionHelpers.ClassParameter.from(boolean.class, false));
      assertEquals(
          "focus loss must release the old keyboard without waiting for insets",
          14,
          content.getPaddingBottom());
      ViewCompat.dispatchApplyWindowInsets(content, insets(280));
      assertEquals(14, content.getPaddingBottom());
    } finally {
      controller.pause().stop().destroy();
    }
  }

  @Test
  public void platformProgressMustNotRelayoutTheBrowserEveryFrame() {
    var controller = Robolectric.buildActivity(Host.class).setup().visible();
    try {
      Content content = install(controller.get());
      ViewCompat.dispatchApplyWindowInsets(content, insets(0));
      WindowInsetsAnimation animation =
          new WindowInsetsAnimation(WindowInsets.Type.ime(), null, 250);
      content.dispatchWindowInsetsAnimationPrepare(animation);
      ViewCompat.dispatchApplyWindowInsets(content, insets(300));
      int before = content.paddingWrites;
      for (int height = 10; height <= 300; height += 10)
        content.dispatchWindowInsetsAnimationProgress(
            insets(height).toWindowInsets(), List.of(animation));
      assertEquals("真实 progress 只更新几何桥，不逐帧修改原生 padding", before, content.paddingWrites);
      content.dispatchWindowInsetsAnimationEnd(animation);
      assertEquals(300, content.getPaddingBottom());
    } finally {
      controller.pause().stop().destroy();
    }
  }
}
