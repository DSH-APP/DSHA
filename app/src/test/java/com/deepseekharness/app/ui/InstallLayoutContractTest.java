package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 安装页压缩重构的布局契约：设备审计（InstallAuditInstrumentation / LogPanelAudit /
 * LayoutPreviewActivity）直接按 id 读取 fragment_install，且预览不经过 InstallFragment。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = InstallLayoutContractTest.App.class)
public final class InstallLayoutContractTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private View inflate() {
    var context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    return LayoutInflater.from(context).inflate(R.layout.fragment_install, null, false);
  }

  @Test
  public void auditIdsKeepTheirViewTypes() {
    View root = inflate();
    assertTrue(root.findViewById(R.id.install_scroll) instanceof ScrollView);
    assertTrue(root.findViewById(R.id.install_btn) instanceof Button);
    assertTrue(root.findViewById(R.id.install_error) instanceof TextView);
    assertTrue(root.findViewById(R.id.install_log) instanceof TextView);
    assertTrue(root.findViewById(R.id.install_log_scroll) instanceof LogScrollView);
    for (int id :
        new int[] {
          R.id.install_state1,
          R.id.install_state2,
          R.id.install_state3,
          R.id.install_state4,
          R.id.install_state5,
          R.id.install_state6
        }) assertTrue(root.findViewById(id) instanceof TextView);
  }

  @Test
  public void staticLogPanelStaysVisibleAndBounded() {
    // 预览直接 inflate XML；折叠只能由 Fragment 运行时决定，否则 LogPanelAudit 量不到高度。
    View log = inflate().findViewById(R.id.install_log_scroll);
    assertEquals(View.VISIBLE, log.getVisibility());
    float density = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density;
    int height = log.getLayoutParams().height;
    assertTrue(height >= Math.round(144 * density) && height <= Math.round(220 * density));
  }

  @Test
  public void stepDetailIsOneSharedCollapsedPanelInsideTheStepGroup() {
    View root = inflate();
    ViewGroup steps = root.findViewById(R.id.install_steps);
    View detail = root.findViewById(R.id.install_step_detail);
    assertSame(steps, detail.getParent());
    assertEquals(View.GONE, detail.getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.install_recover).getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.install_cancel).getVisibility());
  }
}
