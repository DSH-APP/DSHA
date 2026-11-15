package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.recovery.RecoveryController;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;

/** 应急网页保留自己的页面，旋转和正式实例的状态变化均不替换其 URL。 */
public final class RecoveryWebActivity extends AppCompatActivity {
  public static final class Retained extends androidx.lifecycle.ViewModel {
    RecoveryBrowserSurface browser;
    String identity = "";

    @Override
    protected void onCleared() {
      if (browser != null) browser.close();
    }
  }

  private Retained retained;
  private TextView status;
  private FrameLayout container;
  private BrowserMicrophone microphone;

  public BrowserMicrophone microphone() {
    return microphone;
  }

  public boolean microphoneSessionCurrent() {
    var snapshot = RecoveryController.get(this).snapshot();
    return !isFinishing()
        && !isDestroyed()
        && retained != null
        && snapshot.ready
        && retained.identity.equals(snapshot.instanceId + ":" + snapshot.generation);
  }

  private final Handler main = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    microphone = new BrowserMicrophone(this);
    retained = new androidx.lifecycle.ViewModelProvider(this).get(Retained.class);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(getColor(R.color.surface));
    setContentView(root);
    // 头部只留一行：标题 + 短动作 + 图标重载；不再用两个大按钮占满一条。
    root.addView(toolbar(), new LinearLayout.LayoutParams(-1, -2));
    View rule = new View(this);
    rule.setBackgroundColor(getColor(R.color.shell_line));
    root.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    status = new TextView(this);
    status.setTextSize(13);
    status.setTextColor(getColor(R.color.err));
    status.setPadding(dp(16), dp(6), dp(16), dp(6));
    root.addView(status, new LinearLayout.LayoutParams(-1, -2));
    status.setVisibility(View.GONE);
    container = new FrameLayout(this);
    root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              public void handleOnBackPressed() {
                if (retained.browser == null || !retained.browser.back()) finish();
              }
            });
    var snapshot = RecoveryController.get(this).snapshot();
    if (!snapshot.ready) {
      showError(
          UiText.choose(
              "应急服务尚未就绪，请返回查看启动状态。",
              "The emergency service is not ready. Return to review its status."));
      return;
    }
    if (!snapshot.modelCredentialAvailable) {
      // 44 字说明收成一行结论 + ⓘ；设置入口仍是明确动作，不再占一整块。
      LinearLayout notice = new LinearLayout(this);
      notice.setOrientation(LinearLayout.VERTICAL);
      notice.setPadding(dp(16), dp(2), dp(16), dp(6));
      TextView hint = new TextView(this);
      hint.setTextSize(13);
      hint.setTextColor(getColor(R.color.text_secondary));
      hint.setText(
          UiText.choose(
              "本次应急启动未取得模型密钥。诊断页仍可用；发送对话前，请返回应急页停止服务并填写临时 API Key 后重新启动。",
              "No model key was available at recovery startup. Diagnostics remain available. To chat, return to Recovery, stop the service, enter a temporary API key, and restart."));
      InfoHint.attach(hint, UiText.choose("临时 API Key", "Temporary API key"));
      notice.addView(hint);
      Button configure = toolbarButton(UiText.choose("配置密钥", "Set key"));
      configure.setOnClickListener(v -> startActivity(new Intent(this, RecoveryActivity.class)));
      LinearLayout.LayoutParams configureParams = new LinearLayout.LayoutParams(-2, dp(40));
      configureParams.topMargin = dp(4);
      notice.addView(configure, configureParams);
      root.addView(notice, root.indexOfChild(container));
      reveal(notice);
    }
    String identity = snapshot.instanceId + ":" + snapshot.generation;
    if (!identity.equals(retained.identity)) {
      if (retained.browser != null) retained.browser.close();
      retained.browser = null;
      retained.identity = identity;
    }
    try {
      if (retained.browser == null) retained.browser = RecoveryBrowserFactory.create(this);
      container.addView(
          retained.browser.attach(this, this::showError), new FrameLayout.LayoutParams(-1, -1));
      retained.browser.load(snapshot.authUrl, snapshot.baseUrl, snapshot.cookie);
      retained.browser.syncLanguage(
          new com.deepseekharness.app.core.ConfigStore(this).getUiLanguage());
    } catch (RuntimeException | LinkageError error) {
      showError(UiText.format("应急网页无法加载：%s", error.getClass().getSimpleName()));
    }
  }

  private LinearLayout toolbar() {
    LinearLayout toolbar = new LinearLayout(this);
    toolbar.setOrientation(LinearLayout.HORIZONTAL);
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setPadding(dp(16), dp(2), dp(8), dp(2));
    TextView title = new TextView(this);
    title.setText(UiText.choose("应急 DSH", "Emergency DSH"));
    title.setTextSize(15);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setTextColor(getColor(R.color.text));
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setAccessibilityHeading(true);
    toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Button repairs = toolbarButton(UiText.choose("修复提案", "Repairs"));
    repairs.setOnClickListener(v -> startActivity(new Intent(this, RecoveryActivity.class)));
    toolbar.addView(repairs, new LinearLayout.LayoutParams(-2, dp(40)));
    ImageButton reload = new ImageButton(this);
    reload.setImageResource(R.drawable.ic_recovery_refresh);
    reload.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
    reload.setBackgroundResource(R.drawable.bg_action_plain);
    reload.setContentDescription(UiText.choose("重新加载应急页面", "Reload emergency page"));
    reload.setOnClickListener(
        v -> {
          if (retained.browser != null) {
            retained.browser.close();
            retained.browser = null;
          }
          recreate();
        });
    toolbar.addView(reload, new LinearLayout.LayoutParams(dp(44), dp(40)));
    return toolbar;
  }

  private Button toolbarButton(String text) {
    var button = new androidx.appcompat.widget.AppCompatButton(this);
    button.setText(text);
    button.setTextSize(14);
    button.setAllCaps(false);
    button.setBackgroundResource(R.drawable.bg_action_plain);
    button.setTextColor(getColorStateList(R.color.button_text));
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setIncludeFontPadding(false);
    button.setPadding(dp(12), 0, dp(12), 0);
    return button;
  }

  private void reveal(View view) {
    if (view == null) return;
    if (!UiMotion.animationsEnabled(this)) {
      view.setAlpha(1f);
      return;
    }
    view.setAlpha(0f);
    view.animate().alpha(1f).setDuration(160L).setInterpolator(UiMotion.standard()).start();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void showError(String text) {
    runOnUiThread(
        () -> {
          if (isFinishing() || isDestroyed()) return;
          if (status.getVisibility() != View.VISIBLE) status.setVisibility(View.VISIBLE);
          UiMotion.crossFadeText(status, SensitiveData.redact(text));
        });
  }

  private final Runnable monitor =
      new Runnable() {
        public void run() {
          var state = RecoveryController.get(RecoveryWebActivity.this).snapshot();
          if (!retained.identity.equals(state.instanceId + ":" + state.generation)
              || !state.ready) {
            if (retained.browser != null) {
              retained.browser.close();
              retained.browser = null;
            }
            showError(
                UiText.choose(
                    "应急服务已停止或代次已变化，请返回恢复页。",
                    "The emergency service stopped or changed. Return to Recovery."));
          } else main.postDelayed(this, 1000);
        }
      };

  @Override
  protected void onResume() {
    super.onResume();
    if (retained != null && retained.browser != null)
      retained.browser.syncLanguage(
          new com.deepseekharness.app.core.ConfigStore(this).getUiLanguage());
    main.post(monitor);
  }

  @Override
  protected void onPause() {
    main.removeCallbacks(monitor);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (retained != null && retained.browser != null) retained.browser.detach();
    if (microphone != null) microphone.close();
    super.onDestroy();
  }
}
