package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import com.deepseekharness.app.R;
import com.deepseekharness.app.recovery.RecoveryController;
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
    LinearLayout toolbar = new LinearLayout(this);
    toolbar.setOrientation(LinearLayout.HORIZONTAL);
    root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
    Button repairs = new androidx.appcompat.widget.AppCompatButton(this);
    repairs.setText(UiText.choose("应急 DSH · 修复提案", "Emergency DSH · Repairs"));
    repairs.setTextSize(14);
    repairs.setAllCaps(false);
    repairs.setBackgroundResource(R.drawable.bg_btn);
    repairs.setTextColor(getColorStateList(R.color.button_text));
    repairs.setOnClickListener(v -> startActivity(new Intent(this, RecoveryActivity.class)));
    toolbar.addView(repairs, new LinearLayout.LayoutParams(0, -2, 3));
    Button retry = new androidx.appcompat.widget.AppCompatButton(this);
    retry.setText(UiText.choose("重载", "Reload"));
    retry.setContentDescription(UiText.choose("重新加载应急页面", "Reload emergency page"));
    retry.setTextSize(14);
    retry.setAllCaps(false);
    retry.setBackgroundResource(R.drawable.bg_action_plain);
    retry.setTextColor(getColorStateList(R.color.button_text));
    retry.setOnClickListener(
        v -> {
          if (retained.browser != null) {
            retained.browser.close();
            retained.browser = null;
          }
          recreate();
        });
    toolbar.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
    status = new TextView(this);
    status.setTextSize(14);
    status.setTextColor(getColor(R.color.err));
    status.setPadding(16, 8, 16, 8);
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
      LinearLayout notice = new LinearLayout(this);
      notice.setOrientation(LinearLayout.VERTICAL);
      notice.setPadding(16, 8, 16, 8);
      TextView hint = new TextView(this);
      hint.setTextSize(14);
      hint.setTextColor(getColor(R.color.text));
      hint.setText(
          UiText.choose(
              "本次应急启动未取得模型密钥。诊断页仍可用；发送对话前，请返回应急页停止服务并填写临时 API Key 后重新启动。",
              "No model key was available at recovery startup. Diagnostics remain available. To chat, return to Recovery, stop the service, enter a temporary API key, and restart."));
      notice.addView(hint);
      Button configure = new androidx.appcompat.widget.AppCompatButton(this);
      configure.setText(UiText.choose("返回应急页配置", "Open recovery settings"));
      configure.setOnClickListener(v -> startActivity(new Intent(this, RecoveryActivity.class)));
      notice.addView(configure, new LinearLayout.LayoutParams(-1, -2));
      root.addView(notice, 2, new LinearLayout.LayoutParams(-1, -2));
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
      showError(
          UiText.choose("应急网页无法加载：", "Cannot load the emergency page: ")
              + error.getClass().getSimpleName());
    }
  }

  private void showError(String text) {
    runOnUiThread(
        () -> {
          status.setText(com.deepseekharness.app.util.SensitiveData.redact(text));
          status.setVisibility(View.VISIBLE);
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
