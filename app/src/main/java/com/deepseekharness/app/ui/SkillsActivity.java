package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.skills.SkillImports;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.SkillDocument;
import com.deepseekharness.app.util.UiText;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 原生用户技能入口；使用官方 rc2 全局技能目录，不执行导入正文。 */
public final class SkillsActivity extends AppCompatActivity {
  private static final class Result {
    final String code, name, path;

    Result(String code, String name, String path) {
      this.code = code;
      this.name = name;
      this.path = path;
    }
  }

  public static final class Model extends ViewModel {
    final MutableLiveData<SkillImports.Catalogue> catalogue = new MutableLiveData<>();
    final MutableLiveData<Result> result = new MutableLiveData<>(new Result("READY", "", ""));
    final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    final AtomicBoolean working = new AtomicBoolean();
    BackupControl control;
    boolean committing;

    synchronized void cancel() {
      if (control != null && !committing) control.cancel();
    }

    @Override
    protected void onCleared() {
      cancel();
    }
  }

  private Model model;
  private LinearLayout rows;
  private TextView status, listStatus;
  private Button importButton, refreshButton, cancelButton;
  private final ActivityResultLauncher<String[]> picker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri == null) return;
            start(uri);
          });

  private static String t(String chinese, String english) {
    return UiText.choose(chinese, english);
  }

  private static String f(String chinese, String english, Object... arguments) {
    return String.format(Locale.ROOT, t(chinese, english), arguments);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    model = new ViewModelProvider(this).get(Model.class);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(getColor(R.color.surface));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int padding = dp(20);
    body.setPadding(padding, padding, padding, padding);
    scroll.addView(body);
    setContentView(scroll);
    button(body, t("返回", "Back"), this::finish);
    TextView title = text(body, t("技能（Skills）", "Skills"), 22, R.color.text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    text(
        body,
        t(
            "从文件导入 SKILL.md，让 DSH 按需读取可复用的任务说明。无需 Root。",
            "Import SKILL.md files so DSH can load reusable task instructions as needed. Root is not required."),
        14,
        R.color.text_secondary);
    text(
        body,
        t(
            "这里管理用户全局技能。导入仅复制 SKILL.md，正文中的命令不会自动执行；引用的脚本和资料需用文件入口另行放入同一技能目录。",
            "This page manages global user skills. Import copies only SKILL.md and does not run commands in its body. Add referenced scripts and resources to the same skill folder through Files."),
        14,
        R.color.text_secondary);
    text(body, UiText.raw(SkillImports.GUEST_ROOT), 13, R.color.text_secondary);
    importButton =
        button(
            body, t("导入 SKILL.md", "Import SKILL.md"), () -> picker.launch(new String[] {"*/*"}));
    refreshButton = button(body, t("刷新技能", "Refresh skills"), () -> start(null));
    cancelButton = button(body, t("取消读取", "Cancel reading"), model::cancel);
    status = text(body, "", 14, R.color.text);
    status.setTextIsSelectable(true);
    listStatus = text(body, "", 14, R.color.text_secondary);
    rows = new LinearLayout(this);
    rows.setOrientation(LinearLayout.VERTICAL);
    body.addView(rows);
    model.catalogue.observe(this, this::render);
    model.result.observe(this, result -> status.setText(resultText(result)));
    model.busy.observe(
        this,
        busy -> {
          boolean active = Boolean.TRUE.equals(busy);
          importButton.setEnabled(!active);
          refreshButton.setEnabled(!active);
          cancelButton.setEnabled(active && !model.committing);
        });
    if (model.catalogue.getValue() == null && !model.working.get()) start(null);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private TextView text(LinearLayout parent, CharSequence value, int size, int color) {
    TextView text = new TextView(this);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(getColor(color));
    text.setPadding(0, dp(12), 0, dp(4));
    parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
    return text;
  }

  private Button button(LinearLayout parent, String label, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setIncludeFontPadding(false);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(dp(48));
    button.setBackgroundResource(R.drawable.bg_btn);
    button.setTextColor(getColor(R.color.text));
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
    layout.topMargin = dp(8);
    parent.addView(button, layout);
    button.setOnClickListener(view -> action.run());
    return button;
  }

  private void render(SkillImports.Catalogue catalogue) {
    rows.removeAllViews();
    if (catalogue == null) return;
    listStatus.setText(
        catalogue.entries.isEmpty()
            ? t("尚无可读取的用户全局技能。", "No readable global user skills yet.")
            : f("可读取的用户全局技能：%s", "Readable global user skills: %s", catalogue.entries.size()));
    if (catalogue.unreadable > 0)
      text(
          rows,
          f(
              "另有 %s 个技能文件无法通过导入格式检查，原件已保留。",
              "%s additional skill files did not pass the import format checks. Their originals were retained.",
              catalogue.unreadable),
          14,
          R.color.text_secondary);
    for (SkillImports.Entry entry : catalogue.entries) {
      TextView name = text(rows, UiText.raw(entry.document.name), 18, R.color.text);
      name.setTypeface(null, android.graphics.Typeface.BOLD);
      text(rows, UiText.raw(entry.document.description), 14, R.color.text_secondary);
      text(rows, UiText.raw(entry.guestPath()), 12, R.color.text_secondary);
      button(rows, t("查看 SKILL.md", "View SKILL.md"), () -> show(entry));
    }
  }

  private void show(SkillImports.Entry entry) {
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(16), 0, dp(16), dp(16));
    scroll.addView(body);
    String availability =
        entry.document.modelInvocable
            ? t("模型可按需调用 skill 工具读取。", "The model can load this with the skill tool as needed.")
            : t("此技能禁止模型自主调用。", "This skill disables autonomous model invocation.");
    text(body, availability, 14, R.color.text_secondary);
    if (entry.document.userInvocable) {
      text(
          body,
          f("在聊天中输入 /%s 可明确调用。", "Enter /%s in chat to invoke it explicitly.", entry.document.name),
          14,
          R.color.text_secondary);
      button(
          body,
          t("复制调用命令", "Copy invocation"),
          () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null)
              clipboard.setPrimaryClip(ClipData.newPlainText("skill", "/" + entry.document.name));
          });
    } else
      text(
          body,
          t("此技能禁止用户直接调用。", "This skill disables direct user invocation."),
          14,
          R.color.text_secondary);
    TextView source = text(body, UiText.raw(entry.document.text), 14, R.color.text);
    source.setTextIsSelectable(true);
    new DshaDialogBuilder(this)
        .setTitle(UiText.raw(entry.document.name))
        .setView(scroll)
        .setPositiveButton(t("关闭", "Close"), null)
        .show();
  }

  private void start(Uri uri) {
    if (!model.working.compareAndSet(false, true)) return;
    if (uri != null && !"content".equals(uri.getScheme())) {
      model.working.set(false);
      model.result.setValue(new Result("SKILL_URI", "", ""));
      return;
    }
    Context app = getApplicationContext();
    Model owner = model;
    BackupControl control = new BackupControl(null);
    synchronized (owner) {
      owner.control = control;
      owner.committing = false;
    }
    owner.busy.setValue(true);
    owner.result.setValue(new Result(uri == null ? "READING" : "IMPORTING", "", ""));
    new Thread(
            () -> {
              try {
                if (uri == null) {
                  try (RuntimeTasks task = RuntimeTasks.begin("技能读取")) {
                    owner.catalogue.postValue(repository(app).list(control));
                  }
                  owner.result.postValue(new Result("READY", "", ""));
                } else {
                  byte[] bytes = read(app, uri, control);
                  SkillDocument.parse(bytes);
                  synchronized (owner) {
                    control.check();
                    owner.committing = true;
                  }
                  owner.busy.postValue(true);
                  EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("技能导入");
                  if (lease == null) throw new IOException("SKILL_BUSY");
                  SkillImports.Entry installed;
                  try (lease) {
                    installed =
                        lease.run(
                            () -> {
                              if (MaintenanceCoordinator.pending(app.getFilesDir()))
                                throw new IOException("SKILL_BUSY");
                              synchronized (MaintenanceCoordinator.archiveLock()) {
                                try (RuntimeTasks task = RuntimeTasks.begin("技能导入")) {
                                  return repository(app)
                                      .install("SKILL.md", bytes, new BackupControl(null));
                                }
                              }
                            });
                  }
                  owner.result.postValue(
                      new Result("IMPORTED", installed.document.name, installed.guestPath()));
                  try (RuntimeTasks task = RuntimeTasks.begin("技能读取")) {
                    owner.catalogue.postValue(repository(app).list(new BackupControl(null)));
                  } catch (IOException | RuntimeException refreshFailed) {
                    // 发布已完成；只读刷新失败不能把成功导入报告成未完成或重复执行导入。
                  }
                }
              } catch (Exception failure) {
                String code =
                    failure instanceof java.io.InterruptedIOException || control.isCancelled()
                        ? "CANCELLED"
                        : failure.getMessage();
                owner.result.postValue(new Result(code == null ? "SKILL_IO" : code, "", ""));
              } finally {
                synchronized (owner) {
                  owner.control = null;
                  owner.committing = false;
                }
                owner.working.set(false);
                owner.busy.postValue(false);
              }
            },
            "dsha-skills")
        .start();
  }

  private static SkillImports repository(Context context) throws IOException {
    return new SkillImports(
        new AndroidBackupFileSystem(), context.getFilesDir().getCanonicalFile());
  }

  private static byte[] read(Context context, Uri uri, BackupControl control) throws IOException {
    String filename;
    try (Cursor cursor =
        DocumentStreams.query(
            context.getContentResolver(),
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
            control)) {
      if (!cursor.moveToFirst()) throw new IOException("SKILL_FILENAME");
      int name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
      if (name < 0 || cursor.isNull(name)) throw new IOException("SKILL_FILENAME");
      filename = cursor.getString(name);
      SkillDocument.filename(filename);
      int size = cursor.getColumnIndex(OpenableColumns.SIZE);
      if (size >= 0 && !cursor.isNull(size) && cursor.getLong(size) > SkillDocument.MAX_BYTES)
        throw new IOException("SKILL_SIZE");
    }
    try (InputStream input = DocumentStreams.input(context.getContentResolver(), uri, control);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        control.check();
        if (output.size() + count > SkillDocument.MAX_BYTES) throw new IOException("SKILL_SIZE");
        output.write(buffer, 0, count);
      }
      control.check();
      return output.toByteArray();
    }
  }

  private String resultText(Result result) {
    return switch (result.code) {
      case "READY" ->
          t("选择技能文件后可查看内容并复制调用命令。", "Select a skill to view its contents and copy its invocation.");
      case "READING" -> t("正在读取用户技能…", "Reading user skills…");
      case "IMPORTING" -> t("正在读取并检查 SKILL.md…", "Reading and checking SKILL.md…");
      case "IMPORTED" ->
          f(
              "已导入技能：%s\n%s\nDSH 会在后续请求中发现此技能；若目录尚未刷新，可重启 Web。",
              "Imported skill: %s\n%s\nDSH discovers this skill on subsequent requests. Restart Web if its catalog has not refreshed.",
              result.name,
              result.path);
      case "CANCELLED" -> t("已取消读取，未导入技能。", "Reading cancelled. No skill was imported.");
      case "SKILL_FILENAME", "SKILL_URI" ->
          t(
              "请选择名为 SKILL.md 的文件，不能导入目录或压缩包。",
              "Choose a file named SKILL.md. Folders and archives cannot be imported.");
      case "SKILL_SIZE" ->
          t("SKILL.md 必须为非空文件，且不超过 1 MiB。", "SKILL.md must be non-empty and at most 1 MiB.");
      case "SKILL_ENCODING" ->
          t("SKILL.md 必须是有效 UTF-8 文本。", "SKILL.md must contain valid UTF-8 text.");
      case "SKILL_FRONTMATTER", "SKILL_REQUIRED_FIELDS", "SKILL_NAME", "SKILL_INVOCATION" ->
          t(
              "技能格式无效。文件需以 --- YAML 开头，包含小写短横线 name 和非空 description；调用开关使用 disable-model-invocation / user-invocable。未导入文件。",
              "Invalid skill format. Start with --- YAML frontmatter, include a lowercase kebab-case name and non-empty description, and use disable-model-invocation / user-invocable for invocation controls. No file was imported.");
      case "SKILL_EXISTS" ->
          t(
              "已有同名技能或目录，未覆盖原件。请先在源文件中使用不同的 name。",
              "A skill or folder with this name already exists. The original was retained. Use a different name in the source file first.");
      case "SKILL_BUSY" ->
          t(
              "有安装、备份、恢复或维护任务正在进行，请完成后重试。",
              "An install, backup, restore or maintenance operation is active. Retry after it finishes.");
      case "SKILL_HOME_UNAVAILABLE", "PARENT_LINK_OR_MISSING", "PARENT_LINK" ->
          t(
              "当前 DSH 用户目录不可安全读取，请先完成环境安装或修复。未创建替代目录。",
              "The current DSH user folder cannot be read safely. Complete environment setup or repair first. No replacement folder was created.");
      case "SKILL_DIRECTORY", "SKILL_ENTRY_LIMIT" ->
          t(
              "技能目录不是可安全读取的普通目录，或条目超过 512 项。原件已保留。",
              "The skills folder is not a safely readable regular directory, or it has more than 512 entries. Originals were retained.");
      default ->
          t(
              "技能操作未完成。请检查文件授权、可用空间和目录状态；已有技能未覆盖。",
              "The skill operation did not finish. Check file access, free space and the folder state. Existing skills were retained.");
    };
  }
}
