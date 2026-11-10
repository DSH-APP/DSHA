package com.deepseekharness.app.ui;

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.transfer.DownloadEngine;
import com.deepseekharness.app.transfer.DownloadTask;
import com.deepseekharness.app.transfer.PendingFileAction;
import com.deepseekharness.app.util.TransferListModel;
import com.deepseekharness.app.util.TransferListModel.Action;
import com.deepseekharness.app.util.TransferListModel.Filter;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户可控的下载记录。移除记录绝不删除已完成的目标文件。
 *
 * <p>布局（原型 docs/design/downloads-20261102/preview.html）：手机与平板竖屏是列表 + 整页详情，平板横屏
 * （≥ {@link SettingsPanes#TWO_PANE_DP}dp）是左列表右详情。分组、筛选与步骤由 {@link TransferListModel} 决定。
 */
public final class FileTransfersActivity extends AppCompatActivity
    implements TransferDetailView.Host {
  private static final String PENDING_SAVE = "transfer_save_task";
  private static final String AUTO_TASK = "transfer_explicit_task",
      AUTO_ACTION = "transfer_explicit_action";
  private static final String AUTO_OWNER = "transfer_explicit_document";
  private static final String STATUS_TASK = "transfer_status_task";
  private static final String STATUS_DESTINATION = "transfer_status_destination";
  private static final String STATE_FILTER = "transfer_filter",
      STATE_PAGE = "transfer_page",
      STATE_SELECTED = "transfer_selected",
      STATE_TECH = "transfer_tech",
      STATE_LIST_Y = "transfer_list_y",
      STATE_DETAIL_Y = "transfer_detail_y";
  private static final int MENU_DETAILS = 100, MENU_COPY_NAME = 101, MENU_SYSTEM = 102;

  private enum Layout {
    PHONE,
    TABLET_PORTRAIT,
    TWO_PANE
  }

  private DownloadEngine engine;
  private Layout layout;
  private String pageTitle;
  private TextView title, summary, notice;
  private View noticeBox;
  private ImageView noticeIcon;
  private ImageButton headerMore;
  private ScrollView listScroll, detailScroll;
  private LinearLayout listContent, detailContent;
  private OnBackPressedCallback closePage;
  private final Map<String, RowHolder> rows = new HashMap<>();
  private String structureKey, detailKey;
  private TransferDetailView detail;
  private Filter filter = Filter.ALL;
  private String pageId, selectedId;
  private boolean techExpanded;
  private int restoreListY = -1, restoreDetailY = -1;
  private String pendingSaveId;
  private PendingFileAction pendingAction;
  private String actionOwner;
  private String statusTaskId;
  private String statusDestination;
  private DownloadTask.Status displayedTaskStatus;
  private boolean resumed;

  public static Intent intent(Context context) {
    return new Intent(context, FileTransfersActivity.class);
  }

  /** Called only after an admitted, current top-level page explicitly chose this destination. */
  static Intent destinationIntent(
      Context context, String taskId, String destination, String owner) {
    return intent(context)
        .putExtra(AUTO_TASK, taskId)
        .putExtra(AUTO_ACTION, destination)
        .putExtra(AUTO_OWNER, owner);
  }

  public static void open(Context context) {
    Intent target = intent(context);
    if (!(context instanceof android.app.Activity)) target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(target);
  }

  private static String t(String zh, String en) {
    return UiText.choose(zh, en);
  }

  private final ActivityResultLauncher<Intent> savePicker =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            String id = pendingSaveId;
            pendingSaveId = null;
            Intent data = result.getData();
            Uri uri = result.getResultCode() == RESULT_OK && data != null ? data.getData() : null;
            if (uri == null) {
              showNotice(
                  t(
                      "已取消另存为，已校验文件仍保留在下载记录中，可稍后继续保存。",
                      "Save as was cancelled. The verified file remains in history to save later."));
              return;
            }
            DownloadTask current = id == null ? null : find(id);
            if (current == null || current.status != DownloadTask.Status.WAITING_DESTINATION) {
              showNotice(
                  t(
                      "原下载记录已不可用，请重新选择记录。",
                      "The original transfer is unavailable. Select the record again."));
              return;
            }
            if (!"content".equals(uri.getScheme())
                || uri.getAuthority() == null
                || uri.getAuthority().isEmpty()) {
              showNotice(
                  t(
                      "文件提供方没有返回可用的文档位置。",
                      "The provider did not return a usable document location."));
              return;
            }
            // Only persist flags actually granted by a provider that supports persistence.
            int flags =
                data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                && flags != 0) {
              try {
                getContentResolver().takePersistableUriPermission(uri, flags);
              } catch (SecurityException | IllegalArgumentException unsupported) {
                // A transient grant still permits the foreground save; the engine reports failures.
              }
            }
            perform(
                () -> {
                  engine.provideSaveTarget(id, uri);
                  showNotice(t("正在保存文件…", "Saving file…"));
                });
          });

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (saved != null) pendingSaveId = saved.getString(PENDING_SAVE);
    pendingAction =
        new PendingFileAction(
            saved == null ? getIntent().getStringExtra(AUTO_TASK) : saved.getString(AUTO_TASK),
            saved == null ? getIntent().getStringExtra(AUTO_ACTION) : saved.getString(AUTO_ACTION));
    actionOwner =
        saved == null ? getIntent().getStringExtra(AUTO_OWNER) : saved.getString(AUTO_OWNER);
    statusTaskId =
        saved == null ? getIntent().getStringExtra(AUTO_TASK) : saved.getString(STATUS_TASK);
    statusDestination =
        saved == null ? pendingAction.action() : saved.getString(STATUS_DESTINATION);
    int width = getResources().getConfiguration().screenWidthDp;
    layout =
        width >= SettingsPanes.TWO_PANE_DP
            ? Layout.TWO_PANE
            : width >= 600 ? Layout.TABLET_PORTRAIT : Layout.PHONE;
    if (saved != null) {
      try {
        filter = Filter.valueOf(saved.getString(STATE_FILTER, Filter.ALL.name()));
      } catch (IllegalArgumentException unknown) {
        filter = Filter.ALL;
      }
      pageId = saved.getString(STATE_PAGE);
      selectedId = saved.getString(STATE_SELECTED);
      techExpanded = saved.getBoolean(STATE_TECH);
      restoreListY = saved.getInt(STATE_LIST_Y, -1);
      restoreDetailY = saved.getInt(STATE_DETAIL_Y, -1);
      // 旋转到双栏时，原二级页成为右侧选中项；双栏转单栏时不自动推入二级页。
      if (layout == Layout.TWO_PANE) {
        if (pageId != null) selectedId = pageId;
        pageId = null;
      }
    }
    engine = DownloadEngine.get(this);
    pageTitle =
        pendingAction.action().equals("share")
            ? t("分享文件", "Share file")
            : pendingAction.action().equals("saveas")
                ? t("另存为", "Save as")
                : t("下载记录", "Downloads");
    buildLayout();
    closePage =
        new OnBackPressedCallback(pageId != null) {
          @Override
          public void handleOnBackPressed() {
            closeDetailPage();
          }
        };
    getOnBackPressedDispatcher().addCallback(this, closePage);
    if (!pendingAction.id().isEmpty() && pendingAction.action().equals("share"))
      showNotice(
          t(
              "正在准备分享，校验完成后将打开系统分享选择器。",
              "Preparing to share. The system share picker will open after verification."));
    else if (!pendingAction.id().isEmpty() && pendingAction.action().equals("saveas"))
      showNotice(t("正在准备另存为，完成后请选择保存位置。", "Preparing Save as. Choose a destination when ready."));
    engine.state().observe(this, this::render);
  }

  // ---------------------------------------------------------------- 布局骨架

  private void buildLayout() {
    LinearLayout root = TransferUi.vertical(this);
    root.setBackgroundColor(getColor(R.color.shell_bg));
    setContentView(root);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    LinearLayout header = TransferUi.horizontal(this);
    header.setMinimumHeight(dp(60));
    header.setPadding(dp(4), dp(2), dp(6), dp(2));
    ImageButton back = UiNavigation.back(this, () -> getOnBackPressedDispatcher().onBackPressed());
    back.setImageTintList(ColorStateList.valueOf(getColor(R.color.shell_text)));
    header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout titles = TransferUi.vertical(this);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
    titleParams.setMarginStart(dp(2));
    header.addView(titles, titleParams);
    title = TransferUi.text(this, pageTitle, 19, R.color.shell_text, true);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setAccessibilityHeading(true);
    titles.addView(title);
    summary = TransferUi.text(this, "", 12.5f, R.color.shell_text_secondary, false);
    summary.setSingleLine(true);
    summary.setEllipsize(TextUtils.TruncateAt.END);
    titles.addView(summary, TransferUi.match(this, 3));
    headerMore = iconButton(R.drawable.ic_transfer_more, t("更多选项", "More options"));
    headerMore.setOnClickListener(v -> (pageId != null ? pageMenu() : topMenu(v)).show());
    header.addView(headerMore);
    androidx.core.view.ViewCompat.setAccessibilityPaneTitle(root, pageTitle);
    root.addView(header, new LinearLayout.LayoutParams(-1, -2));
    if (layout == Layout.TWO_PANE) root.addView(TransferUi.divider(this, 0));

    noticeBox = buildNotice();
    root.addView(noticeBox);

    LinearLayout body = TransferUi.horizontal(this);
    body.setGravity(Gravity.TOP);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    listScroll = new ScrollView(this);
    listScroll.setFillViewport(true);
    listScroll.setClipToPadding(false);
    listScroll.setSaveEnabled(false);
    listContent = TransferUi.vertical(this);
    listScroll.addView(listContent, new FrameLayout.LayoutParams(-1, -2));
    detailScroll = new ScrollView(this);
    detailScroll.setFillViewport(true);
    detailScroll.setClipToPadding(false);
    detailScroll.setSaveEnabled(false);
    detailContent = TransferUi.vertical(this);
    detailScroll.addView(detailContent, new FrameLayout.LayoutParams(-1, -2));
    if (layout == Layout.TWO_PANE) {
      body.addView(listScroll, new LinearLayout.LayoutParams(dp(420), -1));
      View line = new View(this);
      line.setBackgroundColor(getColor(R.color.shell_line));
      body.addView(line, new LinearLayout.LayoutParams(Math.max(1, dp(1)), -1));
      body.addView(detailScroll, new LinearLayout.LayoutParams(0, -1, 1));
      listContent.setPadding(dp(16), dp(8), dp(16), dp(28));
      detailContent.setPadding(dp(32), dp(20), dp(32), dp(40));
    } else {
      FrameLayout stack = new FrameLayout(this);
      body.addView(stack, new LinearLayout.LayoutParams(0, -1, 1));
      stack.addView(listScroll, new FrameLayout.LayoutParams(-1, -1));
      stack.addView(detailScroll, new FrameLayout.LayoutParams(-1, -1));
      int pad = centeredPad(760, layout == Layout.PHONE ? 12 : 32);
      listContent.setPadding(pad, dp(4), pad, dp(32));
      int detailPad = centeredPad(720, layout == Layout.PHONE ? 16 : 40);
      detailContent.setPadding(detailPad, dp(4), detailPad, dp(40));
      detailScroll.setVisibility(pageId != null ? View.VISIBLE : View.GONE);
      listScroll.setVisibility(pageId != null ? View.GONE : View.VISIBLE);
    }
  }

  /** 内容最大宽度 maxDp，窄屏时退回最小边距 minDp。 */
  private int centeredPad(int maxDp, int minDp) {
    int width = getResources().getConfiguration().screenWidthDp;
    return dp(Math.max(minDp, (width - maxDp) / 2f));
  }

  /** 详情内容区能否并排放下保存位置与文件信息两张卡。 */
  private boolean detailWide() {
    int width = getResources().getConfiguration().screenWidthDp;
    int content = layout == Layout.TWO_PANE ? width - 420 - 64 : Math.min(width - 80, 720);
    return layout != Layout.PHONE && content >= 600;
  }

  private View buildNotice() {
    LinearLayout box = TransferUi.horizontal(this);
    box.setGravity(Gravity.TOP);
    box.setBackgroundResource(R.drawable.bg_transfer_tile);
    box.setPadding(dp(14), dp(12), dp(4), dp(12));
    noticeIcon = TransferUi.icon(this, R.drawable.ic_onb_info, R.color.shell_accent, 20);
    box.addView(noticeIcon);
    notice = TransferUi.text(this, "", 13.5f, R.color.shell_text, false);
    notice.setTextIsSelectable(true);
    notice.setLineSpacing(dp(4), 1f);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMarginStart(dp(10));
    params.topMargin = dp(1);
    box.addView(notice, params);
    ImageButton close = iconButton(R.drawable.ic_onb_x, t("关闭提示", "Dismiss"));
    LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(40), dp(40));
    closeParams.topMargin = -dp(9);
    closeParams.bottomMargin = -dp(9);
    close.setOnClickListener(v -> box.setVisibility(View.GONE));
    box.addView(close, closeParams);
    LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, -2);
    int pad =
        layout == Layout.TWO_PANE ? dp(16) : layout == Layout.PHONE ? dp(12) : centeredPad(760, 32);
    boxParams.setMargins(pad, dp(4), pad, dp(8));
    box.setLayoutParams(boxParams);
    box.setVisibility(View.GONE);
    return box;
  }

  private void tintNotice(boolean bad) {
    noticeBox.setBackgroundTintList(
        ColorStateList.valueOf(
            getColor(bad ? R.color.shell_bad_soft : R.color.shell_accent_tonal)));
    noticeIcon.setImageResource(bad ? R.drawable.ic_onb_alert : R.drawable.ic_onb_info);
    noticeIcon.setImageTintList(
        ColorStateList.valueOf(getColor(bad ? R.color.shell_bad : R.color.shell_accent)));
  }

  private ImageButton iconButton(int drawable, String description) {
    ImageButton button = new androidx.appcompat.widget.AppCompatImageButton(this);
    button.setImageResource(drawable);
    button.setImageTintList(ColorStateList.valueOf(getColor(R.color.shell_text_secondary)));
    button.setBackgroundResource(R.drawable.bg_plugin_icon_button);
    button.setScaleType(ImageView.ScaleType.CENTER);
    button.setContentDescription(description);
    button.setTooltipText(description);
    button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
    return button;
  }

  // ---------------------------------------------------------------- 生命周期

  @Override
  protected void onPostResume() {
    super.onPostResume();
    resumed = true;
    launchExplicitDestination(engine.state().getValue());
  }

  @Override
  protected void onPause() {
    // A backgrounded request cannot surprise the user with a later picker.
    if (resumed && !isChangingConfigurations()) retireExplicitDestination();
    resumed = false;
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (!isChangingConfigurations()) retireExplicitDestination();
    super.onDestroy();
  }

  private void retireExplicitDestination() {
    boolean pending = pendingAction != null && !pendingAction.id().isEmpty();
    if (pendingAction != null) pendingAction.retire();
    WebFileActions.retireDestination(actionOwner);
    if (pending && notice != null && !isFinishing())
      showNotice(
          t(
              "自动打开已取消，文件仍保留在记录中，可手动继续分享或保存。",
              "Automatic opening was cancelled. The file remains in history to share or save manually."));
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (pendingSaveId != null) state.putString(PENDING_SAVE, pendingSaveId);
    state.putString(AUTO_TASK, pendingAction.id());
    state.putString(AUTO_ACTION, pendingAction.action());
    state.putString(AUTO_OWNER, actionOwner);
    state.putString(STATUS_TASK, statusTaskId);
    state.putString(STATUS_DESTINATION, statusDestination);
    state.putString(STATE_FILTER, filter.name());
    state.putString(STATE_PAGE, pageId);
    state.putString(STATE_SELECTED, selectedId);
    state.putBoolean(STATE_TECH, techExpanded);
    if (listScroll != null) state.putInt(STATE_LIST_Y, listScroll.getScrollY());
    if (detailScroll != null) state.putInt(STATE_DETAIL_Y, detailScroll.getScrollY());
  }

  // ---------------------------------------------------------------- 渲染

  private void render(DownloadEngine.State state) {
    if (state == null) return;
    View focused = getCurrentFocus();
    Object focusTag = focused == null ? null : focused.getTag(R.id.transfer_focus_key);
    renderHeader(state);
    renderList(state);
    renderDetail(state);
    if (focusTag != null && !focused.isAttachedToWindow()) {
      View again = findTagged(getWindow().getDecorView(), focusTag);
      if (again != null) again.requestFocus();
    }
    if (restoreListY >= 0) {
      int y = restoreListY;
      restoreListY = -1;
      listScroll.post(() -> listScroll.scrollTo(0, y));
    }
    if (restoreDetailY >= 0) {
      int y = restoreDetailY;
      restoreDetailY = -1;
      detailScroll.post(() -> detailScroll.scrollTo(0, y));
    }
    for (DownloadTask task : state.tasks) {
      if (statusTaskId == null
          || !statusTaskId.equals(task.id)
          || !task.destination.equals(statusDestination)) continue;
      if (task.status != displayedTaskStatus) {
        displayedTaskStatus = task.status;
        String message =
            requestStatusNotice(
                statusTaskId,
                statusDestination,
                task.id,
                task.destination,
                task.status,
                TransferText.name(task));
        if (!message.isEmpty()) showNotice(message);
      }
      break;
    }
    launchExplicitDestination(state);
  }

  /** 重建后把焦点还给同一语义控件（同一记录的同一按钮），避免 TalkBack 跳回页首。 */
  private static View findTagged(View view, Object tag) {
    if (tag.equals(view.getTag(R.id.transfer_focus_key))) return view;
    if (view instanceof android.view.ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findTagged(group.getChildAt(i), tag);
        if (found != null) return found;
      }
    return null;
  }

  private void renderHeader(DownloadEngine.State state) {
    boolean page = pageId != null;
    title.setText(page ? t("记录详情", "Record details") : pageTitle);
    int total = state == null ? 0 : state.tasks.size(), active = 0, failed = 0;
    if (state != null)
      for (DownloadTask task : state.tasks) {
        if (TransferListModel.active(task.status.name())) active++;
        if (task.status == DownloadTask.Status.FAILED) failed++;
      }
    String text;
    if (active > 0 && failed > 0)
      text =
          TransferText.f(
              "%d 条记录 · %d 个正在传输 · %d 个失败",
              "%d records · %d transferring · %d failed", total, active, failed);
    else if (active > 0)
      text = TransferText.f("%d 条记录 · %d 个正在传输", "%d records · %d transferring", total, active);
    else if (failed > 0)
      text = TransferText.f("%d 条记录 · %d 个失败", "%d records · %d failed", total, failed);
    else text = TransferText.f("%d 条记录", "%d records", total);
    summary.setText(text);
    summary.setVisibility(page || total == 0 ? View.GONE : View.VISIBLE);
  }

  private static List<TransferListModel.Entry> entries(List<DownloadTask> tasks) {
    List<TransferListModel.Entry> result = new ArrayList<>();
    for (DownloadTask task : tasks)
      result.add(new TransferListModel.Entry(task.id, task.status.name(), task.createdAt));
    return result;
  }

  private void renderList(DownloadEngine.State state) {
    List<TransferListModel.Entry> entries = entries(state.tasks);
    Map<Filter, Integer> counts = TransferListModel.counts(entries);
    filter = TransferListModel.effective(filter, counts);
    boolean showFilter = TransferListModel.showFilter(counts);
    List<TransferListModel.Section> sections =
        TransferListModel.sections(entries, filter, ZoneId.systemDefault());
    StringBuilder key = new StringBuilder(layout.name()).append('|').append(filter);
    key.append('|').append(showFilter).append('|').append(counts);
    for (TransferListModel.Section section : sections) {
      key.append('|').append(section.kind).append(section.epochDay);
      for (TransferListModel.Entry entry : section.entries) key.append(',').append(entry.id);
    }
    Map<String, DownloadTask> byId = new HashMap<>();
    for (DownloadTask task : state.tasks) byId.put(task.id, task);
    if (!key.toString().equals(structureKey)) {
      structureKey = key.toString();
      rebuildList(sections, counts, showFilter, byId, state.tasks.isEmpty());
    } else {
      for (DownloadTask task : state.tasks) {
        RowHolder holder = rows.get(task.id);
        if (holder != null) holder.bind(task);
      }
    }
  }

  private void rebuildList(
      List<TransferListModel.Section> sections,
      Map<Filter, Integer> counts,
      boolean showFilter,
      Map<String, DownloadTask> byId,
      boolean empty) {
    listContent.removeAllViews();
    rows.clear();
    if (empty) {
      listContent.addView(emptyState(), new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    if (showFilter) listContent.addView(segments(counts), TransferUi.match(this, 2));
    ZoneId zone = ZoneId.systemDefault();
    for (TransferListModel.Section section : sections) {
      String label =
          switch (section.kind) {
            case ATTENTION -> t("需要处理", "Needs attention");
            case TRANSFERRING -> t("正在传输", "Transferring");
            case DAY -> TransferText.day(this, section.epochDay, zone);
          };
      TextView heading = TransferUi.sectionLabel(this, label);
      heading.setPadding(dp(8), 0, dp(8), 0);
      listContent.addView(heading, TransferUi.match(this, 18));
      LinearLayout group = TransferUi.card(this, 0);
      for (TransferListModel.Entry entry : section.entries) {
        DownloadTask task = byId.get(entry.id);
        if (task == null) continue;
        if (group.getChildCount() > 0) group.addView(TransferUi.divider(this, 64));
        RowHolder holder = new RowHolder();
        holder.bind(task);
        rows.put(task.id, holder);
        group.addView(holder.root);
      }
      listContent.addView(group, TransferUi.match(this, 8));
    }
    LinearLayout foot = TransferUi.vertical(this);
    foot.setPadding(dp(10), dp(18), dp(10), 0);
    foot.addView(
        TransferUi.text(
            this,
            t("移除记录不会删除已完成的文件。", "Removing a record keeps completed files."),
            12.5f,
            R.color.shell_text_muted,
            false));
    MaterialButton learn =
        TransferUi.button(
            this, TransferUi.ButtonStyle.PLAIN, t("了解下载记录", "About download history"), 0, null, 40);
    learn.setTextSize(13);
    learn.setPadding(0, 0, 0, 0);
    learn.setOnClickListener(v -> info());
    foot.addView(learn, new LinearLayout.LayoutParams(-2, -2));
    listContent.addView(foot);
  }

  private View emptyState() {
    LinearLayout box = TransferUi.vertical(this);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    box.setPadding(dp(24), dp(72), dp(24), dp(24));
    ImageView ring =
        TransferUi.icon(this, R.drawable.ic_transfer_inbox, R.color.shell_text_muted, 72);
    ring.setBackgroundResource(R.drawable.bg_transfer_ring);
    ring.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.shell_raised)));
    int pad = dp(20);
    ring.setPadding(pad, pad, pad, pad);
    box.addView(ring);
    TextView titleView =
        TransferUi.text(this, t("暂无下载记录", "No downloads yet"), 16, R.color.shell_text, true);
    titleView.setGravity(Gravity.CENTER);
    box.addView(titleView, TransferUi.match(this, 16));
    TextView body =
        TransferUi.text(
            this,
            t("网页文件菜单中的下载会显示在这里。", "Downloads from the web file menu appear here."),
            13.5f,
            R.color.shell_text_secondary,
            false);
    body.setGravity(Gravity.CENTER);
    box.addView(body, TransferUi.match(this, 8));
    return box;
  }

  private View segments(Map<Filter, Integer> counts) {
    LinearLayout track = TransferUi.horizontal(this);
    track.setBackgroundResource(R.drawable.bg_plugin_segment_track);
    track.setPadding(dp(3), dp(3), dp(3), dp(3));
    for (Filter value : Filter.values()) {
      int count = counts.getOrDefault(value, 0);
      String label =
          switch (value) {
            case ALL -> t("全部", "All");
            case ACTIVE -> t("进行中", "Active");
            case FAILED -> t("失败", "Failed");
            case DONE -> t("已结束", "Finished");
          };
      boolean selected = filter == value;
      SpannableStringBuilder text = new SpannableStringBuilder(label);
      text.append("\u2009");
      int start = text.length();
      text.append(String.valueOf(count));
      int countColor =
          value == Filter.FAILED && count > 0
              ? getColor(R.color.shell_bad)
              : getColor(selected ? R.color.shell_accent : R.color.shell_text_muted);
      text.setSpan(
          new ForegroundColorSpan(countColor),
          start,
          text.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.setSpan(
          new StyleSpan(android.graphics.Typeface.BOLD),
          start,
          text.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      TextView tab =
          TransferUi.text(
              this,
              text,
              13,
              selected ? R.color.shell_text : R.color.shell_text_secondary,
              selected);
      tab.setGravity(Gravity.CENTER);
      tab.setSingleLine(true);
      tab.setEllipsize(TextUtils.TruncateAt.END);
      tab.setMinHeight(dp(40));
      tab.setPadding(dp(4), 0, dp(4), 0);
      tab.setBackgroundResource(R.drawable.bg_plugin_segment_tab);
      tab.setSelected(selected);
      tab.setEnabled(count > 0);
      tab.setAlpha(count > 0 ? 1f : 0.42f);
      tab.setClickable(true);
      tab.setFocusable(true);
      tab.setContentDescription(TransferText.f("%s，%d 条", "%s, %d", label, count));
      tab.setTag(R.id.transfer_focus_key, "filter:" + value);
      tab.setAccessibilityDelegate(
          new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
              super.onInitializeAccessibilityNodeInfo(host, info);
              info.setClassName(android.widget.Button.class.getName());
            }
          });
      tab.setOnClickListener(
          v -> {
            if (filter == value) return;
            filter = value;
            structureKey = null;
            render(engine.state().getValue());
            listScroll.scrollTo(0, 0);
          });
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      if (value != Filter.ALL) params.setMarginStart(dp(2));
      track.addView(tab, params);
    }
    return track;
  }

  private void renderDetail(DownloadEngine.State state) {
    String id = layout == Layout.TWO_PANE ? selectedId : pageId;
    if (layout == Layout.TWO_PANE && (id == null || find(state, id) == null)) {
      String first = firstVisible(state);
      if (!Objects.equals(first, selectedId)) {
        selectedId = first;
        for (RowHolder holder : rows.values()) holder.select();
      }
      id = selectedId;
    }
    DownloadTask task = id == null ? null : find(state, id);
    if (task == null) {
      detail = null;
      if (pageId != null) {
        closeDetailPage();
        return;
      }
      if (layout == Layout.TWO_PANE && !"empty".equals(detailKey)) {
        detailKey = "empty";
        detailContent.removeAllViews();
        TextView hint =
            TransferUi.text(
                this,
                state.tasks.isEmpty()
                    ? t("没有可查看的记录", "No records to show")
                    : t("选择一条记录查看详情", "Select a record to see details"),
                14,
                R.color.shell_text_muted,
                false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(160), 0, 0);
        detailContent.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        UiMotion.reveal(detailContent);
      }
      return;
    }
    String key =
        String.join(
            "|",
            task.id,
            task.status.name(),
            String.valueOf(task.uri),
            String.valueOf(task.location),
            TransferText.name(task),
            String.valueOf(task.message),
            String.valueOf(detailWide()));
    if (!key.equals(detailKey) || detail == null) {
      boolean sameTask = detailKey != null && detailKey.startsWith(task.id + "|");
      detailKey = key;
      detailContent.removeAllViews();
      detail = new TransferDetailView(this, this, task, detailWide());
      detailContent.addView(detail.root, new LinearLayout.LayoutParams(-1, -2));
      if (!sameTask) {
        detailScroll.scrollTo(0, 0);
        // 双栏首次进入某条详情：整块 150ms 淡入；同任务进度刷新走 refresh，不重建也不闪（审计 E-08）。
        UiMotion.reveal(detailContent);
      }
    } else detail.refresh(task);
  }

  private String firstVisible(DownloadEngine.State state) {
    List<TransferListModel.Section> sections =
        TransferListModel.sections(entries(state.tasks), filter, ZoneId.systemDefault());
    for (TransferListModel.Section section : sections)
      if (!section.entries.isEmpty()) return section.entries.get(0).id;
    return null;
  }

  private static DownloadTask find(DownloadEngine.State state, String id) {
    if (state != null && id != null)
      for (DownloadTask task : state.tasks) if (task.id.equals(id)) return task;
    return null;
  }

  private DownloadTask find(String id) {
    return find(engine.state().getValue(), id);
  }

  // ---------------------------------------------------------------- 详情导航

  private void openDetail(String id) {
    if (layout == Layout.TWO_PANE) {
      if (id.equals(selectedId)) return;
      selectedId = id;
      for (RowHolder holder : rows.values()) holder.select();
      render(engine.state().getValue());
      return;
    }
    pageId = id;
    techExpanded = false;
    detailKey = null;
    closePage.setEnabled(true);
    render(engine.state().getValue());
    listScroll.setVisibility(View.GONE);
    detailScroll.setVisibility(View.VISIBLE);
    detailScroll.scrollTo(0, 0);
    if (UiMotion.animationsEnabled(this)) {
      boolean rtl = detailScroll.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
      detailScroll.setAlpha(0f);
      detailScroll.setTranslationX(dp(rtl ? -28 : 28));
      detailScroll
          .animate()
          .alpha(1f)
          .translationX(0)
          .setDuration(240)
          .setInterpolator(UiMotion.standard())
          .start();
    }
    headerMore.requestFocus();
    title.announceForAccessibility(title.getText());
  }

  /** 关闭二级页回到列表；列表视图一直存在，滚动位置自然保留。返回用 160ms 反向滑出（审计 E-08）。 */
  private void closeDetailPage() {
    String id = pageId;
    pageId = null;
    detail = null;
    detailKey = null;
    closePage.setEnabled(false);
    detailScroll.animate().cancel();
    if (UiMotion.animationsEnabled(this)) {
      boolean rtl = detailScroll.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
      detailScroll
          .animate()
          .alpha(0f)
          .translationX(dp(rtl ? -24 : 24))
          .setDuration(160)
          .setInterpolator(UiMotion.standard())
          .withEndAction(
              () -> {
                detailScroll.setVisibility(View.GONE);
                detailScroll.setAlpha(1f);
                detailScroll.setTranslationX(0);
                detailContent.removeAllViews();
              })
          .start();
    } else {
      detailScroll.setAlpha(1f);
      detailScroll.setTranslationX(0);
      detailScroll.setVisibility(View.GONE);
      detailContent.removeAllViews();
    }
    listScroll.setVisibility(View.VISIBLE);
    renderHeader(engine.state().getValue());
    RowHolder holder = id == null ? null : rows.get(id);
    if (holder != null) holder.hit.requestFocus();
  }

  // ---------------------------------------------------------------- 行

  private final class RowHolder {
    final LinearLayout root = TransferUi.horizontal(FileTransfersActivity.this);
    final LinearLayout hit = TransferUi.horizontal(FileTransfersActivity.this);
    final FrameLayout tileBox = new FrameLayout(FileTransfersActivity.this);
    final TextView name =
        TransferUi.text(FileTransfersActivity.this, "", 15, R.color.shell_text, true);
    final TextView meta =
        TransferUi.text(FileTransfersActivity.this, "", 12.5f, R.color.shell_text_secondary, false);
    final LinearLayout texts = TransferUi.vertical(FileTransfersActivity.this);
    final LinearLayout actions = TransferUi.horizontal(FileTransfersActivity.this);
    final ImageButton more;
    LinearProgressIndicator bar;
    String id, boundName, actionKey;

    RowHolder() {
      root.setMinimumHeight(dp(68));
      root.setPadding(0, 0, dp(6), 0);
      root.setBackgroundResource(R.drawable.bg_transfer_row);
      hit.setPadding(dp(12), dp(12), dp(4), dp(12));
      hit.setBackgroundResource(R.drawable.bg_plugin_icon_button);
      hit.setClickable(true);
      hit.setFocusable(true);
      root.addView(hit, new LinearLayout.LayoutParams(0, -2, 1));
      hit.addView(tileBox, new LinearLayout.LayoutParams(dp(40), dp(40)));
      LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
      textParams.setMarginStart(dp(12));
      hit.addView(texts, textParams);
      name.setSingleLine(true);
      name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
      texts.addView(name);
      meta.setLineSpacing(dp(2), 1f);
      meta.setMaxLines(2);
      meta.setEllipsize(TextUtils.TruncateAt.END);
      texts.addView(meta, TransferUi.match(FileTransfersActivity.this, 4));
      root.addView(actions, new LinearLayout.LayoutParams(-2, -2));
      more = iconButton(R.drawable.ic_transfer_more, "");
      more.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48)));
      hit.setAccessibilityDelegate(
          new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
              super.onInitializeAccessibilityNodeInfo(host, info);
              info.addAction(
                  new AccessibilityNodeInfo.AccessibilityAction(
                      AccessibilityNodeInfo.ACTION_CLICK, t("查看详情", "View details")));
            }
          });
    }

    void bind(DownloadTask task) {
      id = task.id;
      String label = TransferText.name(task);
      if (!label.equals(boundName)) {
        boundName = label;
        tileBox.removeAllViews();
        tileBox.addView(TransferUi.tile(FileTransfersActivity.this, label, 40));
        name.setText(label);
      }
      meta.setText(meta(task));
      hit.setTag(R.id.transfer_focus_key, "row:" + task.id);
      hit.setOnClickListener(v -> openDetail(task.id));
      more.setTag(R.id.transfer_focus_key, "more:" + task.id);
      more.setContentDescription(TransferText.f("更多操作：%s", "More actions: %s", label));
      more.setTooltipText(t("更多操作", "More actions"));
      more.setOnClickListener(v -> rowMenu(v, task.id).show());
      bindProgress(task);
      String key = task.status + "|" + layout;
      if (!key.equals(actionKey)) {
        actionKey = key;
        actions.removeAllViews();
        List<Action> primary = TransferListModel.primary(task.status.name());
        if (layout == Layout.TABLET_PORTRAIT && primary.size() > 1)
          actions.addView(rowButton(primary.get(1), task, TransferUi.ButtonStyle.PLAIN, false));
        if (!primary.isEmpty()) {
          MaterialButton main =
              rowButton(
                  primary.get(0),
                  task,
                  TransferUi.ButtonStyle.TONAL,
                  layout != Layout.TABLET_PORTRAIT);
          LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) main.getLayoutParams();
          params.setMarginStart(dp(4));
          actions.addView(main, params);
        }
        actions.addView(more);
      }
      select();
    }

    void select() {
      boolean selected = layout == Layout.TWO_PANE && id != null && id.equals(selectedId);
      root.setSelected(selected);
      hit.setStateDescription(selected ? t("已选中", "Selected") : null);
    }

    private MaterialButton rowButton(
        Action action, DownloadTask task, TransferUi.ButtonStyle style, boolean iconOnly) {
      String label = TransferText.action(action, task.status);
      String description = TransferText.f("%s：%s", "%s: %s", label, TransferText.name(task));
      MaterialButton button =
          TransferUi.button(
              FileTransfersActivity.this,
              style,
              iconOnly ? null : label,
              TransferUi.iconFor(action),
              description,
              44);
      if (!iconOnly) {
        button.setTextSize(13.5f);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(44)));
      }
      String id = task.id;
      button.setTag(R.id.transfer_focus_key, "act:" + action + ":" + id);
      // 行按钮只在状态变化时重建；点击时按 id 取最新记录，不持有旧快照。
      button.setOnClickListener(
          v -> {
            DownloadTask current = find(id);
            if (current != null) act(action, current, v);
          });
      return button;
    }

    /** 进行中 / 暂停显示细进度条；确定与不确定模式切换时替换视图，不在可见状态改模式。 */
    private void bindProgress(DownloadTask task) {
      boolean show =
          TransferListModel.active(task.status.name()) || task.status == DownloadTask.Status.PAUSED;
      boolean indeterminate =
          task.total <= 0
              || task.status == DownloadTask.Status.VERIFYING
              || task.status == DownloadTask.Status.PUBLISHING
              || task.status == DownloadTask.Status.QUEUED && task.downloaded <= 0;
      if (!show) {
        if (bar != null) texts.removeView(bar);
        bar = null;
        return;
      }
      if (bar == null || bar.isIndeterminate() != indeterminate) {
        if (bar != null) texts.removeView(bar);
        bar = new LinearProgressIndicator(FileTransfersActivity.this);
        bar.setTrackThickness(dp(4));
        bar.setTrackCornerRadius(dp(2));
        bar.setTrackColor(getColor(R.color.shell_raised));
        bar.setMax(100);
        bar.setIndeterminate(indeterminate);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        texts.addView(bar, TransferUi.match(FileTransfersActivity.this, 8));
      }
      bar.setIndicatorColor(
          getColor(
              task.status == DownloadTask.Status.PAUSED
                  ? R.color.shell_warn
                  : R.color.shell_accent));
      if (!indeterminate)
        bar.setProgressCompat(
            Math.max(0, TransferListModel.percent(task.downloaded, task.total)), true);
    }
  }

  /** 行内元数据：状态（加粗着色）· 进度或大小 · 时间 / 位置 · 已自动改名。 */
  private CharSequence meta(DownloadTask task) {
    boolean compact = layout != Layout.TABLET_PORTRAIT;
    SpannableStringBuilder text = new SpannableStringBuilder();
    TransferText.Tone tone = TransferText.tone(task.status);
    int percent = TransferListModel.percent(task.downloaded, task.total);
    String progress =
        compact
            ? percent < 0 ? TransferText.size(this, Math.max(0, task.downloaded)) : percent + "%"
            : TransferText.pair(
                TransferText.size(this, Math.max(0, task.downloaded)),
                TransferText.size(this, task.total));
    switch (task.status) {
      case COMPLETE -> {
        part(text, TransferText.size(this, task.total));
        part(text, TransferText.clock(this, task.createdAt));
        if (!compact) part(text, TransferText.location(task));
        if (TransferListModel.renamed(task.displayName, task.artifact.name)) {
          int start = text.length() + (text.length() > 0 ? 3 : 0);
          part(text, t("已自动改名", "Renamed"));
          text.setSpan(
              new ForegroundColorSpan(getColor(R.color.shell_accent)),
              start,
              text.length(),
              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
      }
      case CANCELLED -> {
        status(text, TransferText.status(task.status), tone);
        part(text, TransferText.clock(this, task.createdAt));
      }
      case DOWNLOADING -> {
        status(text, TransferText.status(task.status), tone);
        part(text, progress);
        if (task.speedBytesPerSecond > 0)
          part(
              text,
              TransferText.f("%s/秒", "%s/s", TransferText.size(this, task.speedBytesPerSecond)));
      }
      case PAUSED -> {
        status(text, TransferText.status(task.status), tone);
        part(text, progress);
      }
      case QUEUED, VERIFYING -> {
        status(text, TransferText.status(task.status), tone);
        part(text, TransferText.size(this, task.total));
      }
      case PUBLISHING -> {
        status(text, TransferText.status(task.status), tone);
        part(text, TransferText.location(task));
      }
      case WAITING_DESTINATION ->
          status(text, t("已校验，请选择保存位置", "Verified · choose where to save"), tone);
      case FAILED -> {
        status(text, TransferText.status(task.status), tone);
        part(text, TransferText.failureTitle(TransferListModel.failure(task.message)));
      }
    }
    return text;
  }

  private void status(SpannableStringBuilder text, String value, TransferText.Tone tone) {
    int start = text.length() + (text.length() > 0 ? 3 : 0);
    part(text, value);
    text.setSpan(
        new ForegroundColorSpan(TransferUi.toneColor(this, tone)),
        start,
        text.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    text.setSpan(
        new StyleSpan(android.graphics.Typeface.BOLD),
        start,
        text.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static void part(SpannableStringBuilder text, String value) {
    if (value == null || value.isEmpty()) return;
    if (text.length() > 0) text.append(" · ");
    text.append(value);
  }

  // ---------------------------------------------------------------- 菜单

  private PopupMenu popup(View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
    menu.setForceShowIcon(true);
    return menu;
  }

  private void item(Menu menu, int id, String label, int icon, int colorId) {
    MenuItem item = menu.add(Menu.NONE, id, menu.size(), label);
    android.graphics.drawable.Drawable drawable = getDrawable(icon);
    if (drawable != null) {
      drawable = drawable.mutate();
      drawable.setTint(getColor(colorId));
      item.setIcon(drawable);
    }
  }

  private void actionItem(Menu menu, Action action, DownloadTask task) {
    boolean danger = action == Action.REMOVE || action == Action.CANCEL;
    item(
        menu,
        action.ordinal(),
        TransferText.action(action, task.status),
        TransferUi.iconFor(action),
        danger ? R.color.shell_bad : R.color.shell_text_secondary);
  }

  private boolean onActionItem(MenuItem item, String id, View anchor) {
    DownloadTask current = find(id);
    if (current == null) return true;
    if (item.getItemId() == MENU_DETAILS) openDetail(id);
    else if (item.getItemId() == MENU_COPY_NAME)
      copy(t("文件名", "File name"), TransferText.name(current));
    else if (item.getItemId() == MENU_SYSTEM) systemDownloads();
    else if (item.getItemId() >= 0 && item.getItemId() < Action.values().length)
      act(Action.values()[item.getItemId()], current, anchor);
    return true;
  }

  private PopupMenu rowMenu(View anchor, String id) {
    PopupMenu popup = popup(anchor);
    DownloadTask task = find(id);
    if (task == null) return popup;
    Menu menu = popup.getMenu();
    if (layout != Layout.TWO_PANE)
      item(
          menu,
          MENU_DETAILS,
          t("详细信息与位置", "Details and location"),
          R.drawable.ic_onb_info,
          R.color.shell_text_secondary);
    if (task.status == DownloadTask.Status.COMPLETE) actionItem(menu, Action.SHARE, task);
    actionItem(menu, TransferListModel.manage(task.status.name()), task);
    popup.setOnMenuItemClickListener(item -> onActionItem(item, id, anchor));
    return popup;
  }

  private PopupMenu pageMenu() {
    PopupMenu popup = popup(headerMore);
    DownloadTask task = find(pageId);
    if (task == null) return popup;
    Menu menu = popup.getMenu();
    item(
        menu,
        MENU_COPY_NAME,
        t("复制文件名", "Copy file name"),
        R.drawable.ic_ui_copy,
        R.color.shell_text_secondary);
    if (task.status == DownloadTask.Status.COMPLETE && "downloads".equals(task.destination))
      item(
          menu,
          MENU_SYSTEM,
          t("系统下载列表", "System Downloads"),
          R.drawable.ic_transfer_download,
          R.color.shell_text_secondary);
    actionItem(menu, TransferListModel.manage(task.status.name()), task);
    String id = task.id;
    popup.setOnMenuItemClickListener(item -> onActionItem(item, id, headerMore));
    return popup;
  }

  private PopupMenu topMenu(View anchor) {
    PopupMenu popup = popup(anchor);
    Menu menu = popup.getMenu();
    DownloadEngine.State state = engine.state().getValue();
    int finished = 0;
    if (state != null)
      for (DownloadTask task : state.tasks)
        if (TransferListModel.finished(task.status.name())) finished++;
    item(
        menu,
        1,
        t("移除已结束记录", "Remove finished records"),
        R.drawable.ic_transfer_remove,
        R.color.shell_text_secondary);
    menu.findItem(1).setEnabled(finished > 0);
    item(
        menu,
        2,
        t("系统下载列表", "System Downloads"),
        R.drawable.ic_transfer_download,
        R.color.shell_text_secondary);
    item(
        menu,
        3,
        t("了解下载记录", "About download history"),
        R.drawable.ic_onb_info,
        R.color.shell_text_secondary);
    int count = finished;
    popup.setOnMenuItemClickListener(
        item -> {
          if (item.getItemId() == 1) clearFinished(count);
          else if (item.getItemId() == 2) systemDownloads();
          else info();
          return true;
        });
    return popup;
  }

  // ---------------------------------------------------------------- 操作

  @Override
  public void act(Action action, DownloadTask task, View source) {
    DownloadTask current = find(task.id);
    if (current == null) return;
    switch (action) {
      case OPEN -> openFile(current);
      case SHARE -> share(current);
      case PAUSE -> perform(() -> engine.pause(current.id));
      case RESUME, RETRY -> perform(() -> engine.resume(current.id));
      case CHOOSE -> chooseDestination(current);
      case CANCEL -> cancel(current);
      case REMOVE -> remove(current);
    }
  }

  @Override
  public boolean techExpanded() {
    return techExpanded;
  }

  @Override
  public void setTechExpanded(boolean expanded) {
    techExpanded = expanded;
  }

  private void perform(ThrowingAction action) {
    try {
      action.run();
    } catch (Exception failed) {
      showNotice(TransferText.error(failed.getMessage()), true);
    }
  }

  private interface ThrowingAction {
    void run() throws Exception;
  }

  private void showNotice(String message) {
    showNotice(message, false);
  }

  private void showNotice(String message, boolean bad) {
    // 结果条出现用 150ms 淡入，避免直接盖住列表顶部（审计 E-09）。
    boolean wasHidden = noticeBox.getVisibility() != View.VISIBLE;
    boolean changed = !message.contentEquals(notice.getText());
    noticeBox.setVisibility(View.VISIBLE);
    if (changed) UiMotion.crossFadeText(notice, message);
    tintNotice(bad);
    if (wasHidden) UiMotion.reveal(noticeBox);
    notice.announceForAccessibility(message);
  }

  private void snack(String message) {
    Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
  }

  /** 危险确认的确定按钮用错误色。 */
  private void danger(AlertDialog dialog) {
    android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    if (positive != null) positive.setTextColor(getColor(R.color.shell_bad));
  }

  private void cancel(DownloadTask task) {
    Runnable stop =
        () -> {
          engine.cancel(task.id);
          snack(TransferText.cancelled());
        };
    if (!TransferListModel.cancelDiscards(task.status.name(), task.downloaded)) {
      stop.run();
      return;
    }
    AlertDialog dialog =
        new DshaDialogBuilder(this)
            .setTitle(t("取消这次下载？", "Cancel this download?"))
            .setMessage(
                TransferText.f(
                    "已下载的 %s 会被丢弃，之后需要从网页重新发起。",
                    "The %s already downloaded will be discarded. Start again from the web app.",
                    TransferText.size(this, task.downloaded)))
            .setPositiveButton(t("取消下载", "Cancel download"), (d, which) -> stop.run())
            .setNegativeButton(t("继续保留", "Keep"), null)
            .show();
    danger(dialog);
  }

  private void remove(DownloadTask task) {
    boolean complete = task.status == DownloadTask.Status.COMPLETE;
    String where = TransferText.location(task);
    AlertDialog dialog =
        new DshaDialogBuilder(this)
            .setTitle(t("移除下载记录？", "Remove this record?"))
            .setMessage(
                complete
                    ? TransferText.f(
                        "只移除这条记录，%s 仍保留在 %s。",
                        "Only the record is removed. %s stays in %s.",
                        TransferText.name(task),
                        where)
                    : t(
                        "只移除此条下载记录，不影响其他文件。",
                        "Only this download record is removed. Other files are not affected."))
            .setPositiveButton(
                t("移除记录", "Remove record"),
                (d, which) ->
                    perform(
                        () -> {
                          engine.remove(task.id);
                          snack(
                              complete
                                  ? TransferText.f(
                                      "已移除记录，文件仍在 %s",
                                      "Record removed. The file is still in %s", where)
                                  : t("已移除记录", "Record removed"));
                        }))
            .setNegativeButton(t("取消", "Cancel"), null)
            .show();
    danger(dialog);
  }

  private void clearFinished(int count) {
    if (count <= 0) return;
    AlertDialog dialog =
        new DshaDialogBuilder(this)
            .setTitle(TransferText.f("移除 %d 条已结束记录？", "Remove %d finished records?", count))
            .setMessage(
                t(
                    "包括已完成和已取消的记录。已完成文件仍保留在原保存位置；失败、暂停和进行中的记录不受影响。",
                    "Includes completed and cancelled records. Completed files stay where they were saved. Failed, paused and active records are not affected."))
            .setPositiveButton(
                t("移除记录", "Remove record"),
                (d, which) -> {
                  DownloadEngine.State state = engine.state().getValue();
                  if (state == null) return;
                  int removed = 0, failed = 0;
                  for (DownloadTask task : new ArrayList<>(state.tasks)) {
                    if (!TransferListModel.finished(task.status.name())) continue;
                    try {
                      engine.remove(task.id);
                      removed++;
                    } catch (Exception busy) {
                      failed++;
                    }
                  }
                  snack(
                      failed == 0
                          ? TransferText.f(
                              "已移除 %d 条记录，文件均已保留", "Removed %d records. Files were kept.", removed)
                          : TransferText.f(
                              "已移除 %d 条，%d 条正在收尾，请稍后再试",
                              "Removed %d. %d are still finishing; try again shortly.",
                              removed,
                              failed));
                })
            .setNegativeButton(t("取消", "Cancel"), null)
            .show();
    danger(dialog);
  }

  private void info() {
    String message =
        String.join(
            "\n\n",
            t(
                "移除记录只删除这里的条目，已完成的文件仍在原保存位置。",
                "Removing a record only deletes the entry here. Completed files stay where they were saved."),
            t(
                "保存位置已有同名文件时，会自动使用新名称，原文件不会被覆盖。",
                "If a file with the same name exists, a new name is used and the original is not overwritten."),
            t(
                "打开分享选择器不代表对方已收到，请在接收应用中确认。",
                "Opening the share picker does not confirm delivery. Check in the receiving app."),
            t(
                "失败或暂停的下载会保留已下载进度，可以重试或继续。",
                "Failed or paused downloads keep their progress, so you can retry or resume."));
    new DshaDialogBuilder(this)
        .setTitle(t("关于下载记录", "About download history"))
        .setMessage(message)
        .setPositiveButton(t("知道了", "Got it"), null)
        .show();
  }

  @Override
  public void systemDownloads() {
    try {
      startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
    } catch (android.content.ActivityNotFoundException unavailable) {
      showNotice(
          t(
              "此设备没有提供系统下载列表，请按详情中的保存位置在文件管理器中查找。",
              "This device has no system Downloads list. Use the saved location in the details to find the file."));
    }
  }

  @Override
  public void copy(String label, String value) {
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    if (clipboard == null) return;
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
    // Android 13 起系统自带复制确认，避免重复提示。
    if (android.os.Build.VERSION.SDK_INT < 33) snack(t("已复制", "Copied"));
  }

  /** A consumed picker-launch ticket does not retire its exact request's status text. */
  static String requestStatusNotice(
      String requestId,
      String destination,
      String taskId,
      String taskDestination,
      DownloadTask.Status status,
      String displayName) {
    if (requestId == null
        || requestId.isEmpty()
        || !requestId.equals(taskId)
        || !("saveas".equals(destination) || "share".equals(destination))
        || !destination.equals(taskDestination)
        || status == null) return "";
    return switch (status) {
      case COMPLETE ->
          destination.equals("saveas")
              ? UiText.format("已保存文件：%s", displayName)
              : t(
                  "分享文件已就绪；打开分享选择器不代表对方已收到。",
                  "The file is ready to share. Opening the share picker does not confirm delivery.");
      case CANCELLED -> t("文件操作已取消。", "File operation cancelled.");
      case FAILED ->
          t(
              "文件操作失败，请在当前记录中查看原因并重试。",
              "File operation failed. Check this record for the reason and retry.");
      case PAUSED -> t("文件操作已暂停，可在当前记录中继续。", "File operation paused. Resume it from this record.");
      default -> "";
    };
  }

  private void launchExplicitDestination(DownloadEngine.State state) {
    if (state == null || pendingAction == null || isFinishing()) return;
    if (!WebFileActions.destinationCurrent(actionOwner)) {
      retireExplicitDestination();
      return;
    }
    for (DownloadTask task : state.tasks) {
      boolean pending = !pendingAction.id().isEmpty();
      String action = pendingAction.consume(task.id, task.destination, task.status, resumed);
      if (pendingAction.id().isEmpty()) WebFileActions.retireDestination(actionOwner);
      if (action.equals("share")) share(task);
      else if (action.equals("saveas")) chooseDestination(task);
      else if (pending && pendingAction.id().isEmpty())
        showNotice(
            t(
                "文件准备已停止，请在记录中查看原因并手动继续。",
                "File preparation stopped. Check the record and continue manually."));
    }
  }

  private void chooseDestination(DownloadTask task) {
    if (pendingSaveId != null) {
      showNotice(t("请先完成当前保存位置选择。", "Complete the current destination picker first."));
      return;
    }
    pendingSaveId = task.id;
    statusTaskId = task.id;
    statusDestination = task.destination;
    displayedTaskStatus = null;
    Intent picker =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime(task, null))
            .putExtra(Intent.EXTRA_TITLE, TransferText.name(task))
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try {
      RecentsPrivacyGuard.launch(savePicker, picker);
      showNotice(t("请选择保存位置。", "Choose a save destination."));
    } catch (RuntimeException unavailable) {
      pendingSaveId = null;
      showNotice(
          t("系统文件保存界面不可用，请稍后重试。", "The system save picker is unavailable. Try again later."), true);
    }
  }

  private Uri completedUri(DownloadTask task) {
    if (task.status != DownloadTask.Status.COMPLETE || task.uri == null || task.uri.isEmpty())
      throw new IllegalStateException("FILE_NOT_COMPLETE");
    Uri uri = Uri.parse(task.uri);
    if (!"content".equals(uri.getScheme())) throw new IllegalStateException("FILE_URI_UNAVAILABLE");
    return uri;
  }

  private String mime(DownloadTask task, Uri uri) {
    String actual = null;
    if (uri != null) {
      try {
        actual = getContentResolver().getType(uri);
      } catch (RuntimeException unavailable) {
        // The recorded final MIME is retained when a provider is temporarily unavailable.
      }
    }
    if (actual == null || actual.isEmpty()) actual = task.mime;
    if (actual == null || actual.isEmpty()) actual = task.artifact.mime;
    return actual == null || !actual.contains("/") ? "application/octet-stream" : actual;
  }

  private void openFile(DownloadTask task) {
    perform(
        () -> {
          Uri uri = completedUri(task);
          Intent view =
              new Intent(Intent.ACTION_VIEW)
                  .setDataAndType(uri, mime(task, uri))
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          view.setClipData(ClipData.newRawUri(TransferText.name(task), uri));
          try {
            startActivity(view);
          } catch (android.content.ActivityNotFoundException noViewer) {
            showNotice(
                t(
                    "没有可打开此类文件的应用，可使用分享选择其他应用。",
                    "No app can open this file type. Try sharing it to another app."));
          }
        });
  }

  private void share(DownloadTask task) {
    perform(
        () -> {
          Uri uri = completedUri(task);
          String actualMime = mime(task, uri);
          startActivity(
              NativeFileShare.chooser(
                  uri, TransferText.name(task), actualMime, t("分享文件", "Share file")));
          showNotice(
              t(
                  "已打开分享选择器，请在接收应用中完成分享。",
                  "The share picker is open. Complete sharing in the receiving app."));
        });
  }

  private int dp(float value) {
    return TransferUi.dp(this, value);
  }
}
