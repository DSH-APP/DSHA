package com.deepseekharness.app.assistant;

import com.deepseekharness.app.util.UiText;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/** System recognizer only after the user explicitly taps the microphone. Text remains available. */
public final class AssistantVoiceActivity extends AppCompatActivity implements RecognitionListener {
  private SpeechRecognizer recognizer;
  private AssistantSessionController session;
  private TextView status;
  private boolean started;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    session = AssistantSessionController.active();
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    status = new TextView(this);
    status.setText(
        UiText.choose(
            "语音输入：识别结果将作为草稿，不会自动发送。", "Voice input adds a draft. It is not sent automatically."));
    layout.addView(status);
    Button cancel = new Button(this);
    cancel.setText(UiText.choose("返回文字输入", "Return to text input"));
    layout.addView(cancel);
    cancel.setOnClickListener(v -> finish());
    setContentView(layout);
    if (session == null) {
      finish();
      return;
    }
    // 语音输入：手机动作原地等待，不暂停、不取消当前对话。
    session.beginComposing("voice");
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      status.setText(
          UiText.choose(
              "系统语音识别不可用，请返回文字输入。", "System speech recognition is unavailable. Use text input."));
      return;
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 195);
    else start();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code == 195 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
      start();
    else
      status.setText(
          UiText.choose(
              "麦克风权限未授予，请返回文字输入。", "Microphone permission was not granted. Use text input."));
  }

  private void start() {
    if (started || isFinishing() || session != AssistantSessionController.active()) return;
    started = true;
    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
    recognizer.setRecognitionListener(this);
    Intent request =
        new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
    try {
      recognizer.startListening(request);
      status.setText(UiText.choose("请说话…", "Speak now\u2026"));
    } catch (RuntimeException failure) {
      status.setText(
          UiText.choose(
              "无法启动系统语音识别，请返回文字输入。", "Unable to start speech recognition. Use text input."));
    }
  }

  @Override
  public void onResults(Bundle results) {
    var texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if (session == AssistantSessionController.active() && texts != null && !texts.isEmpty())
      session.setDraft(texts.get(0));
    finish();
  }

  @Override
  public void onError(int error) {
    status.setText(
        String.format(
            UiText.choose(
                "语音识别未完成（%s），请返回文字输入。", "Speech recognition did not finish (%s). Use text input."),
            error));
  }

  @Override
  public void onReadyForSpeech(Bundle value) {}

  @Override
  public void onBeginningOfSpeech() {}

  @Override
  public void onRmsChanged(float value) {}

  @Override
  public void onBufferReceived(byte[] value) {}

  @Override
  public void onEndOfSpeech() {
    status.setText(UiText.choose("正在识别…", "Recognizing\u2026"));
  }

  @Override
  public void onPartialResults(Bundle value) {}

  @Override
  public void onEvent(int event, Bundle value) {}

  @Override
  protected void onStop() {
    // Leaving recognition is an immediate microphone stop, even if Android retains the Activity.
    if (recognizer != null) {
      recognizer.cancel();
      recognizer.destroy();
      recognizer = null;
    }
    // 未经编辑器接管就离开语音时放开等待；编辑器接管后来源已不同，这里不生效。
    if (session != null && session == AssistantSessionController.active())
      session.endComposing("voice");
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    if (recognizer != null) {
      recognizer.cancel();
      recognizer.destroy();
    }
    super.onDestroy();
  }
}
