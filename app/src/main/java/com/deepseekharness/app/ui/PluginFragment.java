package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.util.PluginSort;
import com.deepseekharness.app.util.PluginSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 插件市场入口与已装插件管理；页面只投影应用级管理引擎的快照。 */
public class PluginFragment extends Fragment {
  private PluginManagementEngine repository;
  private PluginRepository.State current;
  private View root, header, footer;
  private EditText linkInput, search;
  private TextView linkHint;
  private CheckBox hideBuiltin;
  private boolean market = true;
  private Runnable releaseWatch;
  private final java.util.Set<String> expandedPlugins = new java.util.HashSet<>();
  private PluginSort.Mode sortOrder = PluginSort.Mode.NAME_ASC;
  private final List<PluginRepository.Item> visibleItems = new ArrayList<>();
  private final PluginListPresenter.Projection<PluginRepository.Item> listProjection =
      new PluginListPresenter.Projection<>(
          item -> item.stableKey,
          item -> item.contentKey,
          item ->
              new PluginListPresenter.Entry<>(
                  item,
                  item.stableKey,
                  item.contentKey,
                  item.name,
                  item.description,
                  item.builtin || item.official || item.runtimeProvided,
                  item.enabled,
                  item.available,
                  item.dynamic,
                  item.updateAvailable));
  private long renderedSelectionVersion = -1;
  private final Adapter adapter = new Adapter();
  private ArrayList<String> pendingExports = new ArrayList<>();
  private android.net.Uri pendingImport;
  private PageState pageState;
  private boolean restoreScroll, createdView;
  private boolean listEnterPlayed;
  private boolean taskWasBusy;
  private String lastResultMessage = "";
  private int taskMaxPercent = -1;

  /** Activity 范围只保存页面选择；不拥有插件任务或目录。Bundle 负责进程重建。 */
  public static final class PageState extends ViewModel {
    boolean initialized, market = true, hideBuiltin;
    String query = "", link = "";
    PluginSort.Mode sort = PluginSort.Mode.NAME_ASC;
    final java.util.Set<String> expanded = new java.util.HashSet<>();
    PluginListPresenter.Anchor marketAnchor, installedAnchor;
    ArrayList<String> exports = new ArrayList<>();
    String imported;

    /** 方案 A 双栏：右侧详情当前显示的插件 stableKey。 */
    String selected = "";
  }

  // 调试自测只覆写这些内存边界，不连接或修改生产仓储。
  PluginManagementEngine managementEngine() {
    return PluginManagementEngine.get(requireContext());
  }

  LiveData<PluginRepository.State> installedState() {
    return repository.state();
  }

  boolean safeModeActive() {
    return current != null && current.catalog != null && current.catalog.safeMode;
  }

  void syncInstalledState() {
    repository.ensureLoaded();
  }

  private void ensureInstalledState() {
    if (root != null && isResumed() && !market) syncInstalledState();
  }

  private final ActivityResultLauncher<android.content.Intent> importPicker =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> {
            if (repository == null) repository = PluginManagementEngine.get(requireContext());
            android.content.Intent data = result.getData();
            android.net.Uri uri = data == null ? null : data.getData();
            if (uri == null
                && data != null
                && data.getClipData() != null
                && data.getClipData().getItemCount() > 0)
              uri = data.getClipData().getItemAt(0).getUri();
            if (result.getResultCode() != android.app.Activity.RESULT_OK || uri == null) {
              repository.selectionMessage(
                  com.deepseekharness.app.util.UiText.text("未选择文件。可再次点击导入插件包。"));
              return;
            }
            if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) {
              repository.selectionMessage(com.deepseekharness.app.util.UiText.text("地址无法读取，请改选文件"));
              return;
            }
            if ("file".equals(uri.getScheme())
                && android.os.Build.VERSION.SDK_INT < 30
                && requireContext()
                        .checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
              pendingImport = uri;
              PluginFragment.this.readPermission.launch(
                  android.Manifest.permission.READ_EXTERNAL_STORAGE);
              return;
            }
            repository.importArchive(uri);
          });
  private final ActivityResultLauncher<String> readPermission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          allowed -> {
            android.net.Uri selected = pendingImport;
            pendingImport = null;
            if (allowed && selected != null) repository.importArchive(selected);
            else
              repository.selectionMessage(
                  com.deepseekharness.app.util.UiText.text("无文件权限，请改用系统选择器"));
          });
  private final ActivityResultLauncher<String> exportPicker =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("application/gzip"),
          uri -> {
            if (uri != null && !pendingExports.isEmpty())
              repository.exportArchives(new ArrayList<>(pendingExports), uri);
            pendingExports.clear();
          });

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
    View list = inflater.inflate(R.layout.fragment_plugins, container, false);
    PressFeedback.attachButtons(list);
    twoPane = SettingsPanes.twoPane(requireContext());
    detailPane = null;
    if (!twoPane) return list;
    // 方案 A：左侧沿用原插件列表（含页头与页脚），右侧固定宽度详情窗格。
    LinearLayout shell = new LinearLayout(requireContext());
    shell.setOrientation(LinearLayout.HORIZONTAL);
    shell.setBackgroundColor(requireContext().getColor(R.color.shell_bg));
    shell.addView(list, new LinearLayout.LayoutParams(0, -1, 1f));
    View line = new View(requireContext());
    line.setId(R.id.plugin_detail_divider);
    line.setBackgroundColor(requireContext().getColor(R.color.shell_line));
    float density = getResources().getDisplayMetrics().density;
    shell.addView(line, new LinearLayout.LayoutParams(Math.max(1, Math.round(density)), -1));
    detailPane = inflater.inflate(R.layout.plugin_detail_pane, shell, false);
    detailPane.setId(R.id.plugin_detail_pane);
    shell.addView(detailPane, new LinearLayout.LayoutParams(Math.round(380 * density), -1));
    return shell;
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
    root = view;
    RecyclerView list = view.findViewById(R.id.pluginList);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
    // Header/footer 是 RecyclerView 条目，必须在设置 LayoutManager 后 inflate。
    // RecyclerView.generateLayoutParams() 在父级尚未有 LayoutManager 时会直接抛出
    // InflateException，导致点击“插件”导航后整个 Activity 崩溃。
    header = getLayoutInflater().inflate(R.layout.plugin_list_header, list, false);
    footer = getLayoutInflater().inflate(R.layout.plugin_list_footer, list, false);
    adapter.reset();
    repository = managementEngine();
    pageState =
        new ViewModelProvider(requireActivity())
            .get("plugin-page:" + getClass().getName(), PageState.class);
    if (!pageState.initialized) {
      pageState.sort =
          new com.deepseekharness.app.core.ConfigStore(requireContext()).getPluginSort();
      pageState.market =
          getArguments() == null || !getArguments().getBoolean("show_installed", false);
      restorePageState(saved);
      pageState.initialized = true;
    }
    market = pageState.market;
    if (!createdView
        && saved == null
        && getArguments() != null
        && getArguments().getBoolean("show_installed", false)) market = false;
    createdView = true;
    sortOrder = pageState.sort;
    expandedPlugins.clear();
    expandedPlugins.addAll(pageState.expanded);
    selectedPlugin = pageState.selected == null ? "" : pageState.selected;
    pendingExports = new ArrayList<>(pageState.exports);
    pendingImport = pageState.imported == null ? null : android.net.Uri.parse(pageState.imported);
    restoreScroll = true;
    linkInput = find(R.id.appbar_github_input);
    linkHint = find(R.id.pluginLinkHint);
    search = find(R.id.pluginSearch);
    hideBuiltin = find(R.id.chkHideBuiltin);
    // 这些控件位于可回收 header；页面状态自行保存，避免系统按旧层级覆盖更新的值。
    list.setSaveEnabled(false);
    search.setSaveEnabled(false);
    linkInput.setSaveEnabled(false);
    hideBuiltin.setSaveEnabled(false);
    search.setText(pageState.query);
    linkInput.setText(pageState.link);
    hideBuiltin.setChecked(pageState.hideBuiltin);
    // 单一回收列表负责整页滚动，标题和操作区也是列表条目。
    list.setItemAnimator(null);
    list.setAdapter(adapter);
    list.addOnScrollListener(
        new RecyclerView.OnScrollListener() {
          @Override
          public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy) {
            if (root != null && !restoreScroll) rememberAnchor(captureAnchor());
          }
        });
    find(R.id.btnMarket).setOnClickListener(v -> selectTab(true));
    find(R.id.btnPluginWebsite).setOnClickListener(v -> openPluginWebsite());
    find(R.id.btnInstalled).setOnClickListener(v -> selectTab(false));
    find(R.id.btnRefresh).setOnClickListener(v -> repository.refresh());
    find(R.id.btnPluginUpdates).setOnClickListener(v -> repository.checkUpdates(null));
    find(R.id.btnPluginDownloadSource).setOnClickListener(v -> showDownloadSources());
    updateDownloadSourceLabel();
    // 状态条与警示条空间有限：可见文字用短词，读屏仍读完整说明（XML contentDescription）。
    ((TextView) find(R.id.btnCancelPluginTask))
        .setText(com.deepseekharness.app.util.UiText.text("取消"));
    ((TextView) find(R.id.btnPluginRestore))
        .setText(com.deepseekharness.app.util.UiText.text("恢复"));
    ((TextView) find(R.id.pluginSafeText))
        .setText(
            com.deepseekharness.app.util.UiText.choose(
                "安全模式：第三方插件已暂停", "Safe mode: third-party plugins paused"));
    find(R.id.btnCancelPluginTask).setOnClickListener(v -> repository.cancelTask());
    find(R.id.btnPluginRestore)
        .setOnClickListener(
            v ->
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                    .setTitle(com.deepseekharness.app.util.UiText.text("恢复第三方插件？"))
                    .setMessage(
                        com.deepseekharness.app.util.UiText.text(
                            "恢复安全启动前已启用的插件；之后手动禁用的插件保持禁用。恢复后重启 Web 生效。"))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                    .setPositiveButton(
                        com.deepseekharness.app.util.UiText.text("恢复"),
                        (d, which) -> repository.safeMode(false, null))
                    .show());
    find(R.id.btnPluginInstall).setOnClickListener(v -> installLink());
    // 输入框尾部同一个按钮：空时粘贴，有内容时清空。
    find(R.id.btnPluginPaste)
        .setOnClickListener(
            v -> {
              if (linkInput.getText().length() == 0) pasteLink();
              else {
                linkInput.setText("");
                linkInput.requestFocus();
              }
            });
    find(R.id.btnImport).setOnClickListener(v -> chooseImport(false));
    find(R.id.btnExport).setOnClickListener(v -> chooseExport());
    find(R.id.btnSort).setOnClickListener(v -> showSortOptions());
    hideBuiltin.setOnCheckedChangeListener((v, checked) -> render());
    search.addTextChangedListener(watcher(this::render));
    search.setOnEditorActionListener(
        (v, action, event) -> {
          if (action != EditorInfo.IME_ACTION_DONE
              && (event == null
                  || event.getKeyCode() != KeyEvent.KEYCODE_ENTER
                  || event.getAction() != KeyEvent.ACTION_UP)) return false;
          search.clearFocus();
          android.view.inputmethod.InputMethodManager keyboard =
              (android.view.inputmethod.InputMethodManager)
                  requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
          if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
          return true;
        });
    linkInput.addTextChangedListener(watcher(this::recognizeLink));
    linkInput.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_GO
              || action == EditorInfo.IME_ACTION_DONE
              || (event != null
                  && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                  && event.getAction() == KeyEvent.ACTION_UP)) {
            installLink();
            return true;
          }
          return false;
        });
    TextView status = find(R.id.statusText);
    status.setOnClickListener(v -> showPluginStatus());
    installedState()
        .observe(
            getViewLifecycleOwner(),
            state -> {
              current = state;
              render();
            });
    recognizeLink();
  }

  @Override
  public void onResume() {
    super.onResume();
    // 插件页可见期间才允许后台周期核对；对话页等其它界面不再定时跑 guest 目录查询。
    PluginManagementEngine engine = managementEngine();
    if (engine != null && releaseWatch == null) releaseWatch = engine.watch();
    ensureInstalledState();
  }

  @Override
  public void onPause() {
    if (releaseWatch != null) releaseWatch.run();
    releaseWatch = null;
    capturePageState();
    super.onPause();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle state) {
    capturePageState();
    super.onSaveInstanceState(state);
    if (pageState == null) return;
    state.putBoolean("market", pageState.market);
    state.putString("sortOrder", pageState.sort.name());
    state.putString("query", pageState.query);
    state.putString("link", pageState.link);
    state.putBoolean("hideBuiltin", pageState.hideBuiltin);
    state.putStringArrayList("expandedPlugins", new ArrayList<>(pageState.expanded));
    state.putStringArrayList("pendingExports", new ArrayList<>(pageState.exports));
    state.putString("pendingImport", pageState.imported);
    saveAnchor(state, "marketAnchor", pageState.marketAnchor);
    saveAnchor(state, "installedAnchor", pageState.installedAnchor);
  }

  private void restorePageState(Bundle state) {
    if (state == null) return;
    pageState.market = state.getBoolean("market", pageState.market);
    if (state.containsKey("sortOrder"))
      pageState.sort = PluginSort.Mode.parse(state.getString("sortOrder"));
    else if (state.getBoolean("enabledFirst")) pageState.sort = PluginSort.Mode.ENABLED_FIRST;
    pageState.query = state.getString("query", "");
    pageState.link = state.getString("link", "");
    pageState.hideBuiltin = state.getBoolean("hideBuiltin");
    ArrayList<String> expanded = state.getStringArrayList("expandedPlugins");
    if (expanded != null) pageState.expanded.addAll(expanded);
    ArrayList<String> exports = state.getStringArrayList("pendingExports");
    if (exports != null) pageState.exports = exports;
    pageState.imported = state.getString("pendingImport");
    pageState.marketAnchor = restoreAnchor(state, "marketAnchor");
    pageState.installedAnchor = restoreAnchor(state, "installedAnchor");
  }

  private static void saveAnchor(Bundle bundle, String prefix, PluginListPresenter.Anchor anchor) {
    if (anchor == null) return;
    bundle.putString(prefix + "Key", anchor.key);
    bundle.putInt(prefix + "Position", anchor.position);
    bundle.putInt(prefix + "Offset", anchor.offset);
  }

  private static PluginListPresenter.Anchor restoreAnchor(Bundle bundle, String prefix) {
    if (!bundle.containsKey(prefix + "Position")) return null;
    return new PluginListPresenter.Anchor(
        bundle.getString(prefix + "Key", ""),
        bundle.getInt(prefix + "Position"),
        bundle.getInt(prefix + "Offset"));
  }

  private void capturePageState() {
    if (pageState == null || root == null) return;
    pageState.market = market;
    pageState.sort = sortOrder;
    pageState.query = search.getText().toString();
    pageState.link = linkInput.getText().toString();
    pageState.hideBuiltin = hideBuiltin.isChecked();
    pageState.expanded.clear();
    pageState.expanded.addAll(expandedPlugins);
    pageState.exports = new ArrayList<>(pendingExports);
    pageState.imported = pendingImport == null ? null : pendingImport.toString();
    if (!restoreScroll) rememberAnchor(captureAnchor());
  }

  private void rememberAnchor(PluginListPresenter.Anchor anchor) {
    if (anchor == null || pageState == null) return;
    if (market) pageState.marketAnchor = anchor;
    else pageState.installedAnchor = anchor;
  }

  private PluginListPresenter.Anchor captureAnchor() {
    RecyclerView list = find(R.id.pluginList);
    if (list.hasPendingAdapterUpdates()) return null;
    LinearLayoutManager layout = (LinearLayoutManager) list.getLayoutManager();
    int position = layout.findFirstVisibleItemPosition();
    if (position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()) return null;
    View first = layout.findViewByPosition(position);
    return new PluginListPresenter.Anchor(
        adapter.keyAt(position),
        position,
        first == null ? 0 : layout.getDecoratedTop(first) - list.getPaddingTop());
  }

  private void restoreAnchor(PluginListPresenter.Anchor anchor) {
    if (anchor == null) return;
    rememberAnchor(anchor);
    RecyclerView list = find(R.id.pluginList);
    ((LinearLayoutManager) list.getLayoutManager())
        .scrollToPositionWithOffset(
            PluginListPresenter.anchorPosition(anchor, adapter.rows), anchor.offset);
  }

  @Override
  public void onDestroyView() {
    capturePageState();
    ((RecyclerView) find(R.id.pluginList)).setAdapter(null);
    adapter.reset();
    root = null;
    detailPane = null;
    header = null;
    footer = null;
    linkInput = null;
    search = null;
    linkHint = null;
    hideBuiltin = null;
    listEnterPlayed = false;
    super.onDestroyView();
  }

  <T extends View> T find(int id) {
    T result = root.findViewById(id);
    if (result == null && header != null) result = header.findViewById(id);
    if (result == null && footer != null) result = footer.findViewById(id);
    return result;
  }

  private static TextWatcher watcher(Runnable callback) {
    return new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        callback.run();
      }

      @Override
      public void afterTextChanged(Editable e) {}
    };
  }

  /** 市场卡片显示完整下载源；管理页与筛选胶囊同行，只显示短词。读屏始终读完整名称。 */
  private void updateDownloadSourceLabel() {
    com.deepseekharness.app.util.PluginDownloadSource source =
        new com.deepseekharness.app.core.ConfigStore(requireContext()).getPluginDownloadSource();
    String full =
        com.deepseekharness.app.util.UiText.format(
            "下载源：%s", downloadSourceLabels()[source.ordinal()]);
    String[] compact = {
      com.deepseekharness.app.util.UiText.choose("源：自动", "Source: auto"),
      com.deepseekharness.app.util.UiText.choose("源：官方", "Source: npm"),
      com.deepseekharness.app.util.UiText.choose("源：镜像", "Source: mirror")
    };
    TextView chip = find(R.id.btnPluginDownloadSource);
    chip.setText(market ? full : compact[source.ordinal()]);
    chip.setContentDescription(full);
  }

  /** 下载源胶囊只有一个实例：切换页签时移到当前卡片的槽位，点击与状态不分叉。 */
  private void placeDownloadSource() {
    View chip = find(R.id.btnPluginDownloadSource);
    ViewGroup target = find(market ? R.id.pluginMarketSourceSlot : R.id.pluginManageSourceSlot);
    if (chip == null || target == null || chip.getParent() == target) return;
    ((ViewGroup) chip.getParent()).removeView(chip);
    target.addView(chip);
    updateDownloadSourceLabel();
  }

  private String[] downloadSourceLabels() {
    return new String[] {
      com.deepseekharness.app.util.UiText.text("自动选择（推荐）"),
      com.deepseekharness.app.util.UiText.text("npm 官方源"),
      com.deepseekharness.app.util.UiText.text("npmmirror 国内镜像")
    };
  }

  /** 下载源说明与最近一次任务结果：原文案不再常驻页面，只在用户主动点开时出现。 */
  private void showDownloadSourceInfo() {
    String hint =
        com.deepseekharness.app.util.UiText.text("自动选择可用的 npm 源；镜像失败时回退官方源。GitHub 使用官方直连。");
    String result =
        lastResultMessage.isEmpty()
            ? ""
            : com.deepseekharness.app.util.UiStateText.render(lastResultMessage);
    new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
        .setTitle(com.deepseekharness.app.util.UiText.text("使用说明"))
        .setMessage(result.isEmpty() ? hint : hint + "\n\n" + result)
        .setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null)
        .show();
  }

  private void showDownloadSources() {
    if (repository.isBusy()) return;
    com.deepseekharness.app.core.ConfigStore config =
        new com.deepseekharness.app.core.ConfigStore(requireContext());
    new DshaDialogBuilder(requireContext())
        .setTitle(com.deepseekharness.app.util.UiText.text("插件下载源"))
        .setSingleChoiceItems(
            downloadSourceLabels(),
            config.getPluginDownloadSource().ordinal(),
            (dialog, which) -> {
              if (!repository.isBusy()) {
                config.setPluginDownloadSource(
                    com.deepseekharness.app.util.PluginDownloadSource.values()[which]);
                updateDownloadSourceLabel();
              }
              dialog.dismiss();
            })
        // 原页面上的圆形感叹号并入这里：说明与最近一次任务结果从选择弹窗进入。
        .setNeutralButton(
            com.deepseekharness.app.util.UiText.text("使用说明"),
            (dialog, which) -> showDownloadSourceInfo())
        .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
        .show();
  }

  private void showPluginStatus() {
    if (current == null || current.message.isEmpty()) return;
    var dialog =
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
            .setTitle(com.deepseekharness.app.util.UiText.text("插件操作结果"))
            .setMessage(com.deepseekharness.app.util.UiStateText.render(current.message));
    if (!repository.isBusy() && repository.preview().getValue() != null) {
      dialog
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.choose("重试安装", "Retry installation"),
              (d, w) -> repository.confirmPreview())
          .setNeutralButton(
              com.deepseekharness.app.util.UiText.choose("清理临时包", "Remove temporary package"),
              (d, w) -> repository.discardPreview())
          .setNegativeButton(com.deepseekharness.app.util.UiText.text("关闭"), null);
    } else dialog.setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null);
    dialog.show();
  }

  private void recognizeLink() {
    if (root == null) return;
    String input = linkInput.getText().toString();
    boolean empty = input.trim().isEmpty();
    boolean valid = false;
    try {
      PluginSource source = PluginSource.parse(input);
      linkHint.setText(com.deepseekharness.app.util.UiText.format("已识别：%s", source.description()));
      valid = true;
    } catch (IllegalArgumentException error) {
      linkHint.setText(
          empty ? com.deepseekharness.app.util.UiText.text("支持仓库、分支与压缩包") : error.getMessage());
    }
    // 识别结果着色：空为说明，可识别为好色，不可识别为提醒色；只表达解析结论，不代表已下载。
    int tone = empty ? R.color.shell_text_muted : valid ? R.color.shell_good : R.color.shell_warn;
    int color = requireContext().getColor(tone);
    linkHint.setTextColor(color);
    android.graphics.drawable.Drawable mark =
        empty
            ? null
            : requireContext()
                .getDrawable(valid ? R.drawable.ic_plugin_check : R.drawable.ic_plugin_alert);
    if (mark != null) {
      mark = mark.mutate();
      mark.setTint(color);
      int size = dp(14);
      mark.setBounds(0, 0, size, size);
    }
    linkHint.setCompoundDrawablesRelative(mark, null, null, null);
    android.widget.ImageButton paste = find(R.id.btnPluginPaste);
    boolean hasText = input.length() > 0;
    paste.setImageResource(hasText ? R.drawable.ic_plugin_clear : R.drawable.ic_ui_copy);
    paste.setContentDescription(
        hasText
            ? com.deepseekharness.app.util.UiText.choose("清空链接", "Clear link")
            : getString(R.string.ui_m0179));
    find(R.id.btnPluginInstall).setEnabled(valid);
  }

  private void selectTab(boolean showMarket) {
    if (market == showMarket) return;
    capturePageState();
    market = showMarket;
    restoreScroll = true;
    linkInput.clearFocus();
    search.clearFocus();
    android.view.inputmethod.InputMethodManager keyboard =
        (android.view.inputmethod.InputMethodManager)
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
    render();
    animateTabSelection();
    ensureInstalledState();
  }

  /** 分段切换反馈：选中段 260ms 轻微回弹；系统关闭动画时保持静态，不新增循环动画。 */
  private void animateTabSelection() {
    if (root == null) return;
    TextView marketTab = find(R.id.btnMarket);
    TextView installedTab = find(R.id.btnInstalled);
    if (marketTab == null || installedTab == null) return;
    for (TextView tab : new TextView[] {marketTab, installedTab}) {
      tab.animate().cancel();
      tab.setScaleX(1f);
      tab.setScaleY(1f);
    }
    if (!UiMotion.animationsEnabled(requireContext())) return;
    TextView selected = market ? marketTab : installedTab;
    selected.setScaleX(0.96f);
    selected.setScaleY(0.96f);
    selected
        .animate()
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(260L)
        .setInterpolator(UiMotion.standard())
        .start();
  }

  /** 首次出现插件行时逐项淡入；只播一次，滚动复用与局部刷新不重放。 */
  private void playListEnter() {
    if (listEnterPlayed || root == null || market) return;
    listEnterPlayed = true;
    View list = find(R.id.pluginList);
    if (!(list instanceof ViewGroup group)) return;
    group.post(
        () -> {
          if (root == null) return;
          // F1：插件列表首次出现前 8 项错峰；滚动 / 切页重建都不重放。
          StaggerList.reveal(group, "plugin-installed");
        });
  }

  private void openPluginWebsite() {
    try {
      startActivity(new android.content.Intent(requireContext(), CommunityActivity.class));
    } catch (RuntimeException error) {
      toast(
          com.deepseekharness.app.util.UiText.format(
              "无法打开浏览器，请在浏览器中访问 %s", com.deepseekharness.app.util.Constants.SITE_URL));
    }
  }

  private void installLink() {
    try {
      PluginSource source = PluginSource.parse(linkInput.getText().toString());
      android.view.inputmethod.InputMethodManager keyboard =
          (android.view.inputmethod.InputMethodManager)
              requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
      if (keyboard != null) keyboard.hideSoftInputFromWindow(linkInput.getWindowToken(), 0);
      linkInput.clearFocus();
      repository.install(source);
    } catch (IllegalArgumentException error) {
      toast(error.getMessage());
    }
  }

  private void chooseImport(boolean alternative) {
    View focus = requireActivity().getCurrentFocus();
    if (focus != null) {
      android.view.inputmethod.InputMethodManager keyboard =
          (android.view.inputmethod.InputMethodManager)
              requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
      if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
      focus.clearFocus();
    }
    repository.selectionMessage(com.deepseekharness.app.util.UiText.text("请选择插件包"));
    try {
      RecentsPrivacyGuard.launch(
          importPicker, PluginFilePicker.intent(requireContext(), alternative));
    } catch (android.content.ActivityNotFoundException error) {
      if (!alternative) {
        chooseImport(true);
        return;
      }
      repository.selectionMessage(com.deepseekharness.app.util.UiText.text("请启用系统文件应用后重试"));
      toast(com.deepseekharness.app.util.UiText.text("没有可用的文件选择器"));
    } catch (RuntimeException error) {
      repository.selectionMessage(
          com.deepseekharness.app.util.UiText.format(
              "无法打开文件选择器，请检查系统文件应用：%s", error.getClass().getSimpleName()));
    }
  }

  private void pasteLink() {
    ClipboardManager clipboard =
        (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
    if (clip == null || clip.getItemCount() == 0) {
      toast(com.deepseekharness.app.util.UiText.text("剪贴板没有链接"));
      return;
    }
    CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
    if (text != null) linkInput.setText(text);
  }

  private void render() {
    if (root == null || current == null) return;
    boolean catalogKnown = current.catalog != null && current.catalog.known;
    boolean catalogLoading = current.catalog != null && current.catalog.loading;
    boolean catalogStale = current.catalog != null && current.catalog.stale;
    String catalogError = current.catalog == null ? "" : current.catalog.error;
    find(R.id.pluginMarketCard).setVisibility(market ? View.VISIBLE : View.GONE);
    find(R.id.pluginLocalTitle).setVisibility(market ? View.VISIBLE : View.GONE);
    find(R.id.pluginLocalCard).setVisibility(market ? View.VISIBLE : View.GONE);
    find(R.id.pluginWebsiteSection).setVisibility(market ? View.VISIBLE : View.GONE);
    find(R.id.pluginLinkSection).setVisibility(market ? View.VISIBLE : View.GONE);
    boolean safe = safeModeActive();
    find(R.id.btnPluginRestore).setVisibility(safe ? View.VISIBLE : View.GONE);
    find(R.id.pluginSafeBanner).setVisibility(safe ? View.VISIBLE : View.GONE);
    find(R.id.installedControls).setVisibility(market ? View.GONE : View.VISIBLE);
    placeDownloadSource();
    find(R.id.btnMarket).setSelected(market);
    find(R.id.btnInstalled).setSelected(!market);
    ((TextView) find(R.id.btnMarket))
        .setTextColor(
            requireContext()
                .getColor(market ? R.color.shell_accent : R.color.shell_text_secondary));
    ((TextView) find(R.id.btnInstalled))
        .setTextColor(
            requireContext()
                .getColor(market ? R.color.shell_text_secondary : R.color.shell_accent));
    bindStatusStrip(catalogLoading, !catalogError.isEmpty());
    PluginWaveProgress progress = find(R.id.pluginBusy);
    if (current.busy) {
      if (!taskWasBusy) {
        lastResultMessage = "";
        taskMaxPercent = -1;
      }
      if (current.percent >= 0) taskMaxPercent = Math.max(taskMaxPercent, current.percent);
      progress.setTask(
          current.percent, com.deepseekharness.app.util.UiStateText.render(current.message));
      taskWasBusy = true;
    } else {
      if (current.result != PluginRepository.State.RESULT_NONE && !current.message.isEmpty())
        lastResultMessage = current.message;
      if (progress.finishing()) {
        // 成功 / 失败收束自己会隐藏；后续状态更新不能把它重新拉回或打断。
      } else if (taskWasBusy && current.result != PluginRepository.State.RESULT_NONE) {
        progress.finish(current.result == PluginRepository.State.RESULT_SUCCESS, taskMaxPercent);
      } else {
        progress.hide();
      }
      taskWasBusy = false;
    }
    // busy 表示队列在工作，不阻止继续提交其他插件意图。
    find(R.id.btnPluginDownloadSource).setEnabled(!current.busy);
    TextView sort = find(R.id.btnSort);
    sort.setText(sortLabels()[sortOrder.ordinal()]);
    sort.setContentDescription(getString(R.string.plugin_sort_title) + " · " + sort.getText());
    List<PluginListPresenter.Operation> operations = new ArrayList<>();
    for (var operation : current.operations)
      operations.add(
          new PluginListPresenter.Operation(
              operation.operationId,
              operation.targetKey,
              operation.desired,
              String.valueOf(operation.phase),
              operation.message,
              operation.percent,
              operation.pending()));
    List<PluginListPresenter.Row<PluginRepository.Item>> rows =
        listProjection.project(
            current.items,
            operations,
            search.getText().toString(),
            hideBuiltin.isChecked(),
            sortOrder,
            expandedPlugins);
    if (renderedSelectionVersion != listProjection.selectionVersion()) {
      visibleItems.clear();
      for (var row : rows) visibleItems.add(row.entry.value);
      renderedSelectionVersion = listProjection.selectionVersion();
    }
    List<PluginListPresenter.Row<PluginRepository.Item>> shown =
        market ? java.util.Collections.emptyList() : rows;
    PluginListPresenter.Anchor before =
        !restoreScroll && !market && shown != adapter.rows ? captureAnchor() : null;
    boolean structural = adapter.submit(shown);
    if (!market && !shown.isEmpty()) playListEnter();
    if (restoreScroll) {
      PluginListPresenter.Anchor anchor =
          market ? pageState.marketAnchor : pageState.installedAnchor;
      if (anchor == null
          || market
          || catalogKnown
          || PluginListPresenter.HEADER_KEY.equals(anchor.key)) {
        restoreAnchor(
            anchor == null
                ? new PluginListPresenter.Anchor(PluginListPresenter.HEADER_KEY, 0, 0)
                : anchor);
        restoreScroll = false;
      }
    } else if (structural) restoreAnchor(before);
    PluginListPresenter.EmptyState emptyState =
        PluginListPresenter.emptyState(
            catalogKnown, catalogLoading, catalogError, current.items.size(), visibleItems.size());
    String count =
        catalogKnown
            ? getResources()
                .getQuantityString(R.plurals.plugin_count, visibleItems.size(), visibleItems.size())
            : com.deepseekharness.app.util.UiText.choose(
                catalogLoading ? "正在检测插件…" : "插件列表尚未确认",
                catalogLoading ? "Checking plugins…" : "Plugin list unconfirmed");
    if (catalogKnown && !catalogError.isEmpty())
      count +=
          com.deepseekharness.app.util.UiText.choose(
              " · 刷新失败，保留上次列表", " · Refresh failed; showing the last verified list");
    else if (catalogKnown && catalogStale)
      count += com.deepseekharness.app.util.UiText.choose(" · 等待同步", " · Awaiting refresh");
    ((TextView) find(R.id.pluginCount)).setText(count);
    bindInstalledBadge(
        catalogKnown, catalogLoading, !catalogError.isEmpty() || catalogStale, count);
    TextView empty = find(R.id.pluginEmpty);
    empty.setVisibility(!market && visibleItems.isEmpty() ? View.VISIBLE : View.GONE);
    empty.setText(
        switch (emptyState) {
          case LOADING -> com.deepseekharness.app.util.UiText.text("正在读取插件…");
          case INITIAL ->
              com.deepseekharness.app.util.UiText.choose(
                  "插件列表尚未确认，请刷新检测", "The plugin list is unconfirmed. Refresh to check.");
          case FAILED ->
              com.deepseekharness.app.util.UiText.choose(
                  "读取插件失败，请查看状态并刷新重试",
                  "Could not read plugins. Check the status and refresh to retry.");
          case EMPTY ->
              com.deepseekharness.app.util.UiText.choose("尚未安装插件", "No plugins installed");
          default -> com.deepseekharness.app.util.UiText.text("没有符合条件的插件");
        });
    recognizeLink();
    renderDetail();
  }

  /**
   * 统一状态条：图标 / 计数 / 状态文字 / 唯一动作，形态由 {@link com.deepseekharness.app.util.PluginHeaderStrip}
   * 推导。文字仍是管理引擎原消息，不在这里改写。
   */
  private void bindStatusStrip(boolean catalogLoading, boolean catalogError) {
    var input = new com.deepseekharness.app.util.PluginHeaderStrip.Input();
    input.market = market;
    input.busy = current.busy;
    input.cancellable = current.cancellable;
    input.result = current.result;
    input.hasMessage = !current.message.isEmpty();
    input.catalogLoading = catalogLoading;
    input.catalogError = catalogError;
    var view = com.deepseekharness.app.util.PluginHeaderStrip.project(input);
    View strip = find(R.id.pluginStatusStrip);
    boolean stripWasHidden = strip.getVisibility() != View.VISIBLE;
    strip.setVisibility(view.visible ? View.VISIBLE : View.GONE);
    // 状态条出现淡入一次；条内文字只在真正变化时淡入（下载进度回调很密，审计 A-04）。
    if (view.visible && stripWasHidden) UiMotion.reveal(strip);
    int tone =
        switch (view.tone) {
          case ACCENT -> R.color.shell_accent;
          case GOOD -> R.color.shell_good;
          case WARN -> R.color.shell_warn;
          case BAD -> R.color.shell_bad;
          case MUTED -> R.color.shell_text_muted;
        };
    int soft =
        switch (view.tone) {
          case ACCENT -> R.color.shell_accent_soft;
          case GOOD -> R.color.shell_good_soft;
          case WARN -> R.color.shell_warn_soft;
          case BAD -> R.color.shell_bad_soft;
          case MUTED -> R.color.shell_raised;
        };
    // 同一语气重复渲染不重建 ColorStateList；下载进度每次回调都会走到这里。
    if (!view.tone.equals(strip.getTag())) {
      strip.setTag(view.tone);
      strip.setBackgroundTintList(
          android.content.res.ColorStateList.valueOf(requireContext().getColor(soft)));
      ((android.widget.ImageView) find(R.id.pluginStatusIcon))
          .setImageTintList(
              android.content.res.ColorStateList.valueOf(requireContext().getColor(tone)));
      ((android.widget.ProgressBar) find(R.id.pluginStatusSpinner))
          .setIndeterminateTintList(
              android.content.res.ColorStateList.valueOf(requireContext().getColor(tone)));
    }
    boolean spinner = view.icon == com.deepseekharness.app.util.PluginHeaderStrip.Icon.SPINNER;
    find(R.id.pluginStatusSpinner).setVisibility(spinner ? View.VISIBLE : View.GONE);
    android.widget.ImageView icon = find(R.id.pluginStatusIcon);
    icon.setVisibility(spinner ? View.INVISIBLE : View.VISIBLE);
    if (!spinner)
      icon.setImageResource(
          switch (view.icon) {
            case CHECK -> R.drawable.ic_plugin_check;
            case ERROR -> R.drawable.ic_plugin_error;
            case WARN -> R.drawable.ic_plugin_alert;
            default -> R.drawable.ic_plugin_info;
          });
    find(R.id.pluginCount).setVisibility(view.showCount ? View.VISIBLE : View.GONE);
    TextView status = find(R.id.statusText);
    UiMotion.crossFadeText(
        status, com.deepseekharness.app.util.UiStateText.render(current.message));
    status.setClickable(input.hasMessage);
    android.graphics.drawable.Drawable chevron = null;
    if (view.chevron) {
      chevron = requireContext().getDrawable(R.drawable.ic_ui2_chevron);
      if (chevron != null) {
        chevron = chevron.mutate();
        chevron.setTint(requireContext().getColor(R.color.shell_text_muted));
        chevron.setBounds(0, 0, dp(14), dp(14));
      }
    }
    status.setCompoundDrawablesRelative(null, null, chevron, null);
    var action = view.action;
    find(R.id.btnCancelPluginTask)
        .setVisibility(
            action == com.deepseekharness.app.util.PluginHeaderStrip.Action.CANCEL
                ? View.VISIBLE
                : View.GONE);
    find(R.id.btnCancelPluginTask).setEnabled(current.cancellable);
    // 检查更新在忙碌时只是让位给取消，按钮本身保持可用（队列允许继续提交）。
    find(R.id.btnPluginUpdates)
        .setVisibility(
            action == com.deepseekharness.app.util.PluginHeaderStrip.Action.CHECK_UPDATES
                ? View.VISIBLE
                : View.GONE);
  }

  /** 「插件管理」分段上的数量徽标；读屏读完整计数，不读徽标字符。 */
  private void bindInstalledBadge(
      boolean known, boolean loading, boolean warn, String countDescription) {
    TextView tab = find(R.id.btnInstalled);
    PluginCountBadge.Kind kind =
        !known
            ? (loading ? PluginCountBadge.Kind.LOADING : PluginCountBadge.Kind.UNKNOWN)
            : warn ? PluginCountBadge.Kind.WARN : PluginCountBadge.Kind.COUNT;
    int count = current.items.size();
    String signature = kind + ":" + count + ":" + com.deepseekharness.app.util.UiText.language();
    if (!signature.equals(tab.getTag())) {
      tab.setTag(signature);
      tab.setText(
          PluginCountBadge.append(
              requireContext(), getString(R.string.ui_m0135), kind, count, tab.getTextSize()));
    }
    // 徽标是全部已装数量（不受搜索 / 筛选影响），读屏描述与之一致。
    String description =
        known
            ? getResources().getQuantityString(R.plurals.plugin_count, count, count)
            : countDescription;
    tab.setContentDescription(getString(R.string.ui_m0135) + " · " + description);
  }

  private String[] sortLabels() {
    return new String[] {
      getString(R.string.plugin_sort_az),
      getString(R.string.plugin_sort_za),
      getString(R.string.plugin_sort_enabled),
      getString(R.string.plugin_sort_updates)
    };
  }

  /** G1 筛选入口：排序方式放进底部抽屉，选中即生效；遮罩 / 返回只关闭，不改排序。 */
  private void showSortOptions() {
    CardPage page = new CardPage(requireContext(), getString(R.string.plugin_sort_title), "", true);
    final BottomSheetCard.Sheet[] drawer = new BottomSheetCard.Sheet[1];
    String[] labels = sortLabels();
    for (int index = 0; index < labels.length; index++) {
      final int selected = index;
      View row =
          page.entry(
              page.content,
              labels[index],
              "",
              index == sortOrder.ordinal() ? R.drawable.ic_plugin_check : R.drawable.ic_ui2_box,
              () -> {
                if (drawer[0] != null) drawer[0].dismiss();
                sortOrder = PluginSort.Mode.values()[selected];
                new com.deepseekharness.app.core.ConfigStore(requireContext())
                    .setPluginSort(sortOrder);
                render();
              });
      if (index == sortOrder.ordinal())
        row.setStateDescription(com.deepseekharness.app.util.UiText.choose("当前排序", "Current sort"));
    }
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.text("取消"),
        false,
        () -> {
          if (drawer[0] != null) drawer[0].dismiss();
        });
    drawer[0] = BottomSheetCard.show(requireContext(), page);
  }

  private void chooseExport() {
    if (current == null) return;
    List<String> names = new ArrayList<>();
    for (PluginRepository.Item item : current.items) if (item.exportable) names.add(item.name);
    if (names.isEmpty()) {
      toast(com.deepseekharness.app.util.UiText.text("没有可导出的插件"));
      return;
    }
    boolean[] checked = new boolean[names.size()];
    AlertDialog dialog =
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
            .setTitle(com.deepseekharness.app.util.UiText.text("选择要导出的插件"))
            .setMultiChoiceItems(
                names.toArray(new String[0]),
                checked,
                (d, which, value) -> {
                  checked[which] = value;
                  boolean any = false;
                  for (boolean selected : checked) any |= selected;
                  ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
                })
            .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
            .setPositiveButton(
                com.deepseekharness.app.util.UiText.text("选择保存位置"),
                (d, which) -> {
                  ArrayList<String> selected = new ArrayList<>();
                  for (int i = 0; i < checked.length; i++)
                    if (checked[i]) selected.add(names.get(i));
                  beginExport(selected);
                })
            .create();
    dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
    dialog.show();
  }

  private void beginExport(ArrayList<String> names) {
    if (names.isEmpty()) return;
    pendingExports = names;
    String name =
        names.size() == 1 ? names.get(0).replaceAll("[^A-Za-z0-9._-]", "_") : "DSHA-plugins";
    String stamp =
        new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
    try {
      RecentsPrivacyGuard.launch(exportPicker, name + "-" + stamp + ".tar.gz");
    } catch (Exception error) {
      pendingExports.clear();
      toast(com.deepseekharness.app.util.UiText.text("无法打开保存位置选择器"));
    }
  }

  private void toggle(PluginRepository.Item item, boolean enabled) {
    if (item.official && !enabled) {
      new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext(), true)
          .setTitle(com.deepseekharness.app.util.UiText.text("禁用官方核心？"))
          .setMessage(
              item.name + com.deepseekharness.app.util.UiText.text(" 是 Web 运行所需的核心，禁用后页面可能无法启动。"))
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.text("禁用"),
              (d, which) -> repository.setEnabled(item, false))
          .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
          .setOnDismissListener(d -> adapter.rebindTarget(item.stableKey))
          .show();
    } else repository.setEnabled(item, enabled);
  }

  /** G1：「更多」改为底部抽屉（拖拽手柄 / 返回键先收 / 遮罩只关闭），动作仍走原确认弹窗。 */
  private void itemActions(PluginRepository.Item item) {
    List<PluginAction> actions = pluginActions(item);
    var kind =
        com.deepseekharness.app.util.PluginCardState.kind(
            item.dynamic, item.official, item.runtimeProvided, item.builtin, item.runtimeLocated);
    CardPage page = new CardPage(requireContext(), item.name, kindLabel(kind), true);
    final BottomSheetCard.Sheet[] drawer = new BottomSheetCard.Sheet[1];
    android.widget.LinearLayout group = page.card();
    ((android.widget.LinearLayout.LayoutParams) group.getLayoutParams()).bottomMargin = page.dp(4);
    page.content.setPadding(page.dp(16), page.dp(12), page.dp(16), page.dp(4));
    page.footer.setPadding(page.dp(16), 0, page.dp(16), page.dp(8));
    for (PluginAction action : actions) {
      page.entry(
          group,
          actionLabel(action, item),
          actionHint(action, item),
          actionIcon(action),
          () -> {
            if (drawer[0] != null) drawer[0].dismiss();
            runPluginAction(action, item);
          });
    }
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
        false,
        () -> {
          if (drawer[0] != null) drawer[0].dismiss();
        });
    drawer[0] = BottomSheetCard.show(requireContext(), page);
  }

  private static List<PluginAction> pluginActions(PluginRepository.Item item) {
    List<PluginAction> actions = new ArrayList<>();
    actions.add(PluginAction.DETAIL);
    actions.add(PluginAction.COPY_NAME);
    if (!item.source.isEmpty()) actions.add(PluginAction.COPY_SOURCE);
    if (item.exportable) actions.add(PluginAction.EXPORT);
    if (item.updatable) actions.add(PluginAction.CHECK);
    if (item.updateAvailable) actions.add(PluginAction.UPDATE);
    if (!item.rollbackVersion.isEmpty()) actions.add(PluginAction.ROLLBACK);
    if (item.deletable && !item.dynamic) actions.add(PluginAction.DELETE);
    return actions;
  }

  private void runPluginAction(PluginAction action, PluginRepository.Item item) {
    if (action == PluginAction.DETAIL) {
      new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
          .setTitle(item.name)
          .setMessage(
              com.deepseekharness.app.util.UiText.format(
                  "%s\n\n版本：%s%s%s%s%s%s",
                  descriptionText(item),
                  item.version,
                  (item.location.isEmpty()
                      ? ""
                      : com.deepseekharness.app.util.UiText.format("\n位置：%s", item.location)),
                  (item.source.isEmpty()
                      ? ""
                      : com.deepseekharness.app.util.UiText.format("\n来源：%s", item.source)),
                  (item.latestVersion.isEmpty()
                      ? ""
                      : com.deepseekharness.app.util.UiText.format(
                          "\n上次检查版本：%s", item.latestVersion)),
                  (item.updateMessage.isEmpty()
                      ? ""
                      : "\n" + com.deepseekharness.app.util.UiStateText.render(item.updateMessage)),
                  (item.rollbackVersion.isEmpty()
                      ? ""
                      : com.deepseekharness.app.util.UiText.format(
                          "\n可回退：%s", item.rollbackVersion))))
          .setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null)
          .setNeutralButton(
              com.deepseekharness.app.util.UiText.choose("能力声明", "Capabilities"),
              (dialog, button) ->
                  repository.inspectCapabilities(
                      item,
                      details -> {
                        if (!isAdded()) return;
                        new DshaDialogBuilder(requireContext())
                            .setTitle(item.name)
                            .setMessage(details)
                            .setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null)
                            .show();
                      }))
          .show();
    } else if (action == PluginAction.CHECK) {
      repository.checkUpdates(item);
    } else if (action == PluginAction.UPDATE) {
      repository.prepareUpdate(item);
    } else if (action == PluginAction.ROLLBACK) {
      new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext(), true)
          .setTitle(com.deepseekharness.app.util.UiText.text("回退插件？"))
          .setMessage(
              item.name
                  + com.deepseekharness.app.util.UiText.text("：")
                  + item.version
                  + " → "
                  + item.rollbackVersion
                  + com.deepseekharness.app.util.UiText.text("\n只恢复插件文件，当前启用状态和对话数据保留；重启 Web 生效。"))
          .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.text("回退"),
              (confirm, button) -> repository.rollback(item))
          .show();
    } else if (action == PluginAction.EXPORT) {
      ArrayList<String> names = new ArrayList<>();
      names.add(item.name);
      beginExport(names);
    } else if (action == PluginAction.DELETE) {
      confirmDelete(item);
    } else {
      copyText(action == PluginAction.COPY_NAME ? item.name : item.source);
    }
  }

  private enum PluginAction {
    DETAIL,
    COPY_NAME,
    COPY_SOURCE,
    EXPORT,
    CHECK,
    UPDATE,
    ROLLBACK,
    DELETE
  }

  private String actionLabel(PluginAction action, PluginRepository.Item item) {
    return switch (action) {
      case DETAIL -> com.deepseekharness.app.util.UiText.text("查看详情");
      case COPY_NAME -> com.deepseekharness.app.util.UiText.text("复制插件名称");
      case COPY_SOURCE -> com.deepseekharness.app.util.UiText.text("复制来源链接");
      case EXPORT -> com.deepseekharness.app.util.UiText.text("导出插件");
      case CHECK -> com.deepseekharness.app.util.UiText.text("检查插件更新");
      case UPDATE -> com.deepseekharness.app.util.UiText.format("更新至 %s", item.latestVersion);
      case ROLLBACK -> com.deepseekharness.app.util.UiText.format("回退至 %s", item.rollbackVersion);
      case DELETE -> com.deepseekharness.app.util.UiText.text("删除插件");
    };
  }

  private static int actionIcon(PluginAction action) {
    return switch (action) {
      case DETAIL -> R.drawable.ic_plugin_info;
      case COPY_NAME, COPY_SOURCE -> R.drawable.ic_ui_copy;
      case EXPORT -> R.drawable.ic_ui_export;
      case CHECK -> R.drawable.ic_recovery_refresh;
      case UPDATE -> R.drawable.ic_plugin_update;
      case ROLLBACK -> R.drawable.ic_plugin_restart;
      case DELETE -> R.drawable.ic_onb_x;
    };
  }

  private String actionHint(PluginAction action, PluginRepository.Item item) {
    return switch (action) {
      case DETAIL ->
          com.deepseekharness.app.util.UiText.choose(
              "版本、位置、来源与能力声明", "Version, path, source and capabilities");
      case COPY_NAME -> com.deepseekharness.app.util.UiText.raw(item.name);
      case COPY_SOURCE -> com.deepseekharness.app.util.UiText.raw(item.source);
      case EXPORT ->
          com.deepseekharness.app.util.UiText.choose("导出为插件包文件", "Export as a plugin archive");
      case CHECK ->
          com.deepseekharness.app.util.UiText.choose(
              "向当前下载源查询新版本", "Ask the current download source for a newer version");
      case UPDATE ->
          item.latestVersion.isEmpty()
              ? ""
              : com.deepseekharness.app.util.UiText.format(
                  "%s → %s", item.version, item.latestVersion);
      case ROLLBACK ->
          item.rollbackVersion.isEmpty()
              ? ""
              : com.deepseekharness.app.util.UiText.format(
                  "%s → %s", item.version, item.rollbackVersion);
      case DELETE ->
          com.deepseekharness.app.util.UiText.choose(
              "从加载路径移除，原件保留在数据页", "Remove from the load path; the original stays in Data");
    };
  }

  private void confirmDelete(PluginRepository.Item item) {
    if (!item.deletable) return;
    new DshaDialogBuilder(requireContext(), true)
        .setTitle(com.deepseekharness.app.util.UiText.choose("删除插件？", "Delete plugin?"))
        .setMessage(
            com.deepseekharness.app.util.UiText.format(
                "%s 将从加载路径移除；原件保留在数据页的保留区。对话、其他插件和外部源码目录保留。", item.name))
        .setNegativeButton(com.deepseekharness.app.util.UiText.choose("取消", "Cancel"), null)
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.choose("删除", "Delete"),
            (dialog, which) -> {
              repository.delete(item);
            })
        .show();
  }

  private void toast(String message) {
    if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
  }

  /** 当前 Web 胶囊的短文案：加载状态压成一个词，完整记录放 contentDescription。 */
  static String runtimeBadgeText(PluginRepository.Item item) {
    if (item.runtimeStale) return com.deepseekharness.app.util.UiText.choose("待核验", "Unverified");
    return switch (item.runtimeState) {
      case "loaded", "running", "active" ->
          com.deepseekharness.app.util.UiText.choose("已加载", "Loaded");
      case "failed" -> com.deepseekharness.app.util.UiText.choose("加载失败", "Failed");
      case "pending", "waiting" -> com.deepseekharness.app.util.UiText.choose("等待服务", "Waiting");
      case "loading" -> com.deepseekharness.app.util.UiText.choose("加载中", "Loading");
      case "stopped", "unloaded", "disabled", "inactive" ->
          com.deepseekharness.app.util.UiText.choose("未加载", "Not loaded");
      default -> com.deepseekharness.app.util.UiText.choose("待核验", "Unverified");
    };
  }

  static String runtimeText(PluginRepository.Item item) {
    String state =
        switch (item.runtimeState) {
          case "loaded", "running", "active" ->
              com.deepseekharness.app.util.UiText.choose("已加载", "loaded");
          case "failed" -> com.deepseekharness.app.util.UiText.choose("加载失败", "load failed");
          case "pending", "waiting" ->
              com.deepseekharness.app.util.UiText.choose("等待服务", "waiting for services");
          case "loading" -> com.deepseekharness.app.util.UiText.choose("正在加载", "loading");
          case "stopped", "unloaded", "disabled", "inactive" ->
              com.deepseekharness.app.util.UiText.choose("未加载", "not loaded");
          default -> "";
        };
    if (state.isEmpty()) {
      String unknown =
          com.deepseekharness.app.util.UiText.choose(
              "当前 Web：加载状态未确认", "Current Web: load unconfirmed");
      String history =
          switch (item.loadState) {
            case "loaded" -> com.deepseekharness.app.util.UiText.choose("已加载", "loaded");
            case "failed" -> com.deepseekharness.app.util.UiText.choose("加载失败", "load failed");
            case "queued" -> com.deepseekharness.app.util.UiText.choose("等待生效", "queued");
            case "attempted" ->
                com.deepseekharness.app.util.UiText.choose("已尝试加载", "load attempted");
            default -> "";
          };
      return history.isEmpty()
          ? unknown
          : String.format(
              java.util.Locale.ROOT,
              com.deepseekharness.app.util.UiText.choose(
                  "%s\n上次激活记录：%s", "%s\nLast activation record: %s"),
              unknown,
              history);
    }
    if (item.runtimeStale)
      return String.format(
          java.util.Locale.ROOT,
          com.deepseekharness.app.util.UiText.choose(
              "当前 Web：待核验；上次状态：%s", "Current Web: awaiting verification; last state: %s"),
          state);
    return String.format(
        java.util.Locale.ROOT,
        com.deepseekharness.app.util.UiText.choose("当前 Web：%s", "Current Web: %s"),
        state);
  }

  private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
    private List<PluginListPresenter.Row<PluginRepository.Item>> rows =
        java.util.Collections.emptyList();
    private final java.util.Map<String, Long> ids = new java.util.HashMap<>();
    private long nextId = 1;

    Adapter() {
      setHasStableIds(true);
    }

    void reset() {
      rows = java.util.Collections.emptyList();
    }

    /** 只刷新新旧两张卡片的选中外观（走既有 EXPANSION 局部绑定），不重建列表。 */
    void notifySelection(String previous, String next) {
      for (int i = 0; i < rows.size(); i++) {
        String key = rows.get(i).entry.stableKey;
        if (key.equals(previous) || key.equals(next))
          notifyItemChanged(i + PluginListPresenter.HEADER_COUNT, PluginListPresenter.EXPANSION);
      }
    }

    String keyAt(int position) {
      if (position == 0) return PluginListPresenter.HEADER_KEY;
      if (position == getItemCount() - 1) return PluginListPresenter.FOOTER_KEY;
      return rows.get(position - PluginListPresenter.HEADER_COUNT).entry.stableKey;
    }

    @Override
    public long getItemId(int position) {
      if (position == 0) return -2;
      if (position == getItemCount() - 1) return -3;
      return ids.computeIfAbsent(keyAt(position), ignored -> nextId++);
    }

    boolean submit(List<PluginListPresenter.Row<PluginRepository.Item>> next) {
      if (rows == next) return false;
      List<PluginListPresenter.Row<PluginRepository.Item>> previous = rows;
      boolean structural = previous.size() != next.size();
      if (!structural)
        for (int i = 0; i < next.size(); i++)
          if (!PluginListPresenter.sameItem(previous.get(i), next.get(i))) {
            structural = true;
            break;
          }
      DiffUtil.DiffResult diff =
          DiffUtil.calculateDiff(
              new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                  return previous.size();
                }

                @Override
                public int getNewListSize() {
                  return next.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPosition, int newPosition) {
                  return PluginListPresenter.sameItem(
                      previous.get(oldPosition), next.get(newPosition));
                }

                @Override
                public boolean areContentsTheSame(int oldPosition, int newPosition) {
                  return PluginListPresenter.changes(
                          previous.get(oldPosition), next.get(newPosition))
                      == 0;
                }

                @Override
                public Object getChangePayload(int oldPosition, int newPosition) {
                  return PluginListPresenter.changes(
                      previous.get(oldPosition), next.get(newPosition));
                }
              });
      rows = next;
      // DiffUtil 只比较插件行；四种通知均显式跳过 header，footer 由插删自然移动。
      diff.dispatchUpdatesTo(
          new ListUpdateCallback() {
            @Override
            public void onInserted(int position, int count) {
              notifyItemRangeInserted(PluginListPresenter.adapterPosition(position), count);
            }

            @Override
            public void onRemoved(int position, int count) {
              notifyItemRangeRemoved(PluginListPresenter.adapterPosition(position), count);
            }

            @Override
            public void onMoved(int from, int to) {
              notifyItemMoved(
                  PluginListPresenter.adapterPosition(from),
                  PluginListPresenter.adapterPosition(to));
            }

            @Override
            public void onChanged(int position, int count, Object payload) {
              notifyItemRangeChanged(PluginListPresenter.adapterPosition(position), count, payload);
            }
          });
      return structural;
    }

    void rebindTarget(String stableKey) {
      for (int i = 0; i < rows.size(); i++)
        if (rows.get(i).entry.stableKey.equals(stableKey)) {
          notifyItemChanged(PluginListPresenter.adapterPosition(i), PluginListPresenter.OPERATION);
          return;
        }
    }

    class Holder extends RecyclerView.ViewHolder {
      Holder(View view) {
        super(view);
      }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
      if (type != 1) {
        View content = type == 0 ? header : footer;
        if (content.getParent() instanceof ViewGroup)
          ((ViewGroup) content.getParent()).removeView(content);
        return new Holder(content);
      }
      SwipeAction.Host host = SwipeAction.host(parent.getContext());
      View card =
          LayoutInflater.from(parent.getContext()).inflate(R.layout.item_plugin, host, false);
      if (twoPane) card.setBackgroundResource(R.drawable.bg_plugin_card_selectable);
      host.setRow(card);
      host.setLayoutParams(
          new RecyclerView.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      return new Holder(host);
    }

    @Override
    public void onBindViewHolder(
        @NonNull Holder holder, int position, @NonNull List<Object> payloads) {
      if (getItemViewType(position) != 1) return;
      int changed = 0;
      for (Object payload : payloads) {
        if (!(payload instanceof Integer)) {
          changed |= PluginListPresenter.CONTENT;
          break;
        }
        changed |= (Integer) payload;
      }
      if (payloads.isEmpty() || (changed & PluginListPresenter.CONTENT) != 0) {
        onBindViewHolder(holder, position);
        return;
      }
      var row = rows.get(position - PluginListPresenter.HEADER_COUNT);
      CardDensity density = listDensity();
      if ((changed & PluginListPresenter.OPERATION) != 0)
        bindCardStatus(cardOf(holder.itemView), row, density);
      if ((changed & PluginListPresenter.EXPANSION) != 0)
        bindCardExpansion(cardOf(holder.itemView), row, density);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
      if (getItemViewType(position) != 1) return;
      PluginListPresenter.Row<PluginRepository.Item> row =
          rows.get(position - PluginListPresenter.HEADER_COUNT);
      bindCard(cardOf(holder.itemView), row, listDensity());
      bindSwipe(holder.itemView, row.entry.value);
    }

    @Override
    public int getItemViewType(int position) {
      return position == 0 ? 0 : position == getItemCount() - 1 ? 2 : 1;
    }

    @Override
    public int getItemCount() {
      return 2 + rows.size();
    }
  }

  // ---------------------------------------------------------------- 插件卡片（列表 / 平板左栏 / 详情窗格共用）

  /** 列表行承载在可滑动容器里；绑定、状态刷新与展开都作用在容器内的卡片上。 */
  private static View cardOf(View itemView) {
    return itemView instanceof SwipeAction.Host host ? host.row() : itemView;
  }

  /** F2：插件列表左滑露出「删除」，破坏性动作只弹既有确认弹窗；对话 / 终端等横向内容列表不挂滑动。 平板双栏用点选切换详情，不启用左滑；不可删除与临时插件也没有可滑动作。 */
  private void bindSwipe(View itemView, PluginRepository.Item item) {
    if (!(itemView instanceof SwipeAction.Host host)) return;
    SwipeAction.Model model = new SwipeAction.Model();
    model.enabled = !twoPane && item.deletable && !item.dynamic;
    if (model.enabled) {
      model.add(
          SwipeAction.Action.destructive(
              com.deepseekharness.app.util.UiText.choose("删除插件", "Delete plugin"),
              com.deepseekharness.app.util.UiText.choose("再次确认后删除", "Confirm before delete"),
              R.drawable.ic_onb_x,
              () -> confirmDelete(item)));
    }
    host.apply(model);
  }

  /** 同一张 item_plugin 卡片的三种密度；原型见 docs/design/plugin-card-20261101/preview.html。 */
  enum CardDensity {
    /** 手机单栏：卡片内展开属性表与删除。 */
    LIST,
    /** 平板左栏：只留摘要、状态与首条提示，点卡片选中。 */
    COMPACT,
    /** 平板右栏：完整说明与属性表常驻，不显示展开按钮。 */
    DETAIL
  }

  private CardDensity listDensity() {
    return twoPane ? CardDensity.COMPACT : CardDensity.LIST;
  }

  /** 完整绑定：标题行、说明、属性表文案与所有点击回调；状态与展开态分别走局部绑定。 */
  private void bindCard(
      View card, PluginListPresenter.Row<PluginRepository.Item> row, CardDensity density) {
    PluginRepository.Item item = row.entry.value;
    var kind =
        com.deepseekharness.app.util.PluginCardState.kind(
            item.dynamic, item.official, item.runtimeProvided, item.builtin, item.runtimeLocated);
    boolean neutral =
        kind == com.deepseekharness.app.util.PluginCardState.Kind.THIRD
            || kind == com.deepseekharness.app.util.PluginCardState.Kind.LOCATED;
    android.widget.ImageView glyph = card.findViewById(R.id.pluginGlyph);
    glyph.setImageResource(kindIcon(kind));
    glyph.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            requireContext()
                .getColor(neutral ? R.color.shell_text_secondary : R.color.shell_accent)));
    ((TextView) card.findViewById(R.id.pluginName)).setText(item.name);
    TextView badge = card.findViewById(R.id.pluginBadge);
    badge.setText(kindLabel(kind));
    // 来源徽章统一中性色；语义区分留给配置与当前 Web 两枚状态徽章。
    applyBadge(badge, com.deepseekharness.app.util.PluginCardState.Tone.MUTED);
    TextView version = card.findViewById(R.id.pluginVersion);
    version.setText(com.deepseekharness.app.util.UiText.raw(item.version));
    // 版本徽章同样用中性色，只保留等宽字与胶囊外形。
    applyBadge(version, com.deepseekharness.app.util.PluginCardState.Tone.MUTED);
    version.setVisibility(item.version.isEmpty() ? View.GONE : View.VISIBLE);
    TextView description = card.findViewById(R.id.pluginDesc);
    String text = descriptionText(item);
    description.setText(
        text.isEmpty()
            ? com.deepseekharness.app.util.UiText.choose("作者未提供说明", "No description provided")
            : text);
    description.setTextColor(
        requireContext()
            .getColor(text.isEmpty() ? R.color.shell_text_muted : R.color.shell_text_secondary));
    bindCardDetails(card, item);

    View actions = card.findViewById(R.id.pluginActions);
    actions.setOnClickListener(v -> itemActions(item));
    actions.setContentDescription(com.deepseekharness.app.util.UiText.format("更多操作：%s", item.name));
    android.widget.Button delete = card.findViewById(R.id.pluginDelete);
    delete.setVisibility(item.deletable && !item.dynamic ? View.VISIBLE : View.GONE);
    delete.setText(com.deepseekharness.app.util.UiText.choose("删除插件", "Delete plugin"));
    delete.setOnClickListener(v -> confirmDelete(item));
    card.findViewById(R.id.pluginExpand)
        .setOnClickListener(
            v -> {
              if (!expandedPlugins.add(item.stableKey)) expandedPlugins.remove(item.stableKey);
              render();
            });
    if (density == CardDensity.DETAIL) {
      card.setOnClickListener(null);
      card.setOnLongClickListener(null);
      card.setClickable(false);
      card.setLongClickable(false);
    } else {
      // 方案 A 双栏：点卡片在右侧详情窗格查看，不在卡片内展开。
      if (density == CardDensity.COMPACT)
        card.setOnClickListener(v -> selectDetail(item.stableKey));
      else {
        card.setOnClickListener(null);
        card.setClickable(false);
      }
      card.setOnLongClickListener(
          v -> {
            itemActions(item);
            return true;
          });
    }
    bindCardExpansion(card, row, density);
    bindCardStatus(card, row, density);
  }

  /** 展开态与选中态：手机按 expandedPlugins 展开属性表；平板左栏只显示选中描边；详情窗格常驻展开。 */
  private void bindCardExpansion(
      View card, PluginListPresenter.Row<PluginRepository.Item> row, CardDensity density) {
    boolean open = density == CardDensity.DETAIL || (density == CardDensity.LIST && row.expanded);
    card.findViewById(R.id.pluginDetails).setVisibility(open ? View.VISIBLE : View.GONE);
    boolean foot = density != CardDensity.COMPACT;
    card.findViewById(R.id.pluginFoot).setVisibility(foot ? View.VISIBLE : View.GONE);
    card.findViewById(R.id.pluginFootLine).setVisibility(foot ? View.VISIBLE : View.GONE);
    card.setPaddingRelative(
        card.getPaddingStart(), card.getPaddingTop(), card.getPaddingEnd(), foot ? dp(4) : dp(14));
    TextView expand = card.findViewById(R.id.pluginExpand);
    expand.setVisibility(density == CardDensity.LIST ? View.VISIBLE : View.GONE);
    expand.setText(
        row.expanded
            ? com.deepseekharness.app.util.UiText.choose("收起详情", "Collapse details")
            : com.deepseekharness.app.util.UiText.choose("展开插件详情", "Plugin details"));
    android.graphics.drawable.Drawable chevron = expand.getCompoundDrawablesRelative()[0];
    if (chevron != null) chevron.mutate().setLevel(row.expanded ? 1 : 0);
    TextView description = card.findViewById(R.id.pluginDesc);
    description.setMaxLines(open ? Integer.MAX_VALUE : density == CardDensity.COMPACT ? 1 : 2);
    card.setSelected(density == CardDensity.COMPACT && row.entry.stableKey.equals(selectedPlugin));
  }

  /** 主状态、语气点、卡片内进度、提示条与开关；操作进度只触发这一段。 */
  private void bindCardStatus(
      View card, PluginListPresenter.Row<PluginRepository.Item> row, CardDensity density) {
    PluginRepository.Item item = row.entry.value;
    // 首屏只放短结论（≤4 字）；完整状态经 contentDescription 给无障碍，明细不裸露在胶囊里。
    String configured = configuredText(row);
    String runtime = runtimeText(item);
    TextView state = card.findViewById(R.id.pluginStatus);
    state.setText(configuredBadgeText(row));
    state.setContentDescription(configured);
    applyBadge(state, configBadgeTone(row));
    TextView runtimeView = card.findViewById(R.id.pluginRuntime);
    String runtimeBadge = runtimeBadgeText(item);
    runtimeView.setText(runtimeBadge);
    runtimeView.setContentDescription(runtime);
    runtimeView.setVisibility(runtimeBadge.isEmpty() ? View.GONE : View.VISIBLE);
    applyBadge(
        runtimeView,
        com.deepseekharness.app.util.PluginCardState.runtimeTone(
            item.runtimeState, item.runtimeStale));
    PluginTaskProgress progress = card.findViewById(R.id.pluginProgress);
    if (row.pending()) progress.setTask(row.operation.percent, configured + "\n" + runtime);
    else progress.hide();
    bindCardNotes(card, row, density);
    bindToggle(card.findViewById(R.id.pluginSwitch), row);
    card.findViewById(R.id.pluginDelete).setEnabled(!row.pending());
  }

  /** 配置徽章的语义语气；是否已加载由独立的当前 Web 徽章表达。 */
  private com.deepseekharness.app.util.PluginCardState.Tone configBadgeTone(
      PluginListPresenter.Row<PluginRepository.Item> row) {
    PluginRepository.Item item = row.entry.value;
    return com.deepseekharness.app.util.PluginCardState.configTone(
        row.pending(),
        item.available,
        item.enabled,
        item.detected,
        item.dynamic,
        !item.metadataError.isEmpty());
  }

  /** 小胶囊的底色透明度：8% 的 shell_*_soft 在白卡上几乎不可见，看着就像只改了文字颜色。 */
  private static final int BADGE_FILL_ALPHA = 0x2E;

  /**
   * 徽章统一着色入口：底色用同语义色 18% 填充（中性用 raised），文字取对应语义色。
   *
   * <p>配置徽章的圆点是 TextView 的 compound drawable，随文字色一起 tint，不会单独进入无障碍焦点。
   */
  private void applyBadge(TextView view, com.deepseekharness.app.util.PluginCardState.Tone tone) {
    int text = requireContext().getColor(badgeTextColor(tone));
    int fill =
        tone == com.deepseekharness.app.util.PluginCardState.Tone.MUTED
            ? requireContext().getColor(R.color.shell_raised)
            : badgeFill(toneColor(tone));
    view.setTextColor(text);
    view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(fill));
    view.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(text));
  }

  private int badgeFill(int toneColor) {
    return (requireContext().getColor(toneColor) & 0x00FFFFFF) | (BADGE_FILL_ALPHA << 24);
  }

  private static int toneSoftColor(com.deepseekharness.app.util.PluginCardState.Tone tone) {
    return switch (tone) {
      case GOOD -> R.color.shell_good_soft;
      case ACCENT -> R.color.shell_accent_soft;
      case WARN -> R.color.shell_warn_soft;
      case BAD -> R.color.shell_bad_soft;
      case MUTED -> R.color.shell_raised;
    };
  }

  /** 中性徽章文字用 secondary 而不是 muted：raised 底上 muted 的对比度偏弱。 */
  private static int badgeTextColor(com.deepseekharness.app.util.PluginCardState.Tone tone) {
    return tone == com.deepseekharness.app.util.PluginCardState.Tone.MUTED
        ? R.color.shell_text_secondary
        : toneColor(tone);
  }

  private static int toneColor(com.deepseekharness.app.util.PluginCardState.Tone tone) {
    return switch (tone) {
      case GOOD -> R.color.shell_good;
      case ACCENT -> R.color.shell_accent;
      case WARN -> R.color.shell_warn;
      case BAD -> R.color.shell_bad;
      case MUTED -> R.color.shell_text_muted;
    };
  }

  /** 提示条：重启生效 → 可更新（带更新动作）→ 最近一次操作消息；平板左栏只显示首条。 */
  private void bindCardNotes(
      View card, PluginListPresenter.Row<PluginRepository.Item> row, CardDensity density) {
    PluginRepository.Item item = row.entry.value;
    String message =
        row.operation == null || row.operation.message.isEmpty()
            ? ""
            : com.deepseekharness.app.util.UiStateText.render(row.operation.message);
    List<com.deepseekharness.app.util.PluginCardState.Note> notes =
        com.deepseekharness.app.util.PluginCardState.notes(
            item.restartRequired, item.updateAvailable, row.pending(), !message.isEmpty());
    if (density == CardDensity.COMPACT && notes.size() > 1) notes = notes.subList(0, 1);
    LinearLayout container = card.findViewById(R.id.pluginNotes);
    // 进度每次刷新都会走到这里：提示内容不变时不重建子视图。
    // 不再把 identityHashCode 放进签名：列表每次刷新都会产生新 Item，旧签名会重建提示条造成闪烁。
    String signature =
        notes
            + "\u0000"
            + message
            + "\u0000"
            + item.latestVersion
            + "\u0000"
            + com.deepseekharness.app.util.UiText.language();
    if (signature.equals(container.getTag())) return;
    container.setTag(signature);
    container.removeAllViews();
    container.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
    for (var note : notes) {
      switch (note) {
        case RESTART ->
            addRestartChip(
                container,
                com.deepseekharness.app.util.UiText.choose("重启 Web 后生效。", "Restart Web to apply."));
        case UPDATE ->
            addNote(
                container,
                com.deepseekharness.app.util.PluginCardState.Tone.ACCENT,
                R.drawable.ic_plugin_update,
                com.deepseekharness.app.util.UiText.format("可更新到 %s。", item.latestVersion),
                com.deepseekharness.app.util.UiText.choose("立即更新", "Update now"),
                v -> repository.prepareUpdate(item));
        case MESSAGE ->
            addNote(
                container,
                com.deepseekharness.app.util.PluginCardState.Tone.MUTED,
                R.drawable.ic_plugin_info,
                message,
                null,
                null);
      }
    }
  }

  private void addNote(
      LinearLayout container,
      com.deepseekharness.app.util.PluginCardState.Tone tone,
      int icon,
      String text,
      String action,
      View.OnClickListener listener) {
    View note = getLayoutInflater().inflate(R.layout.item_plugin_note, container, false);
    note.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(requireContext().getColor(toneSoftColor(tone))));
    android.widget.ImageView image = note.findViewById(R.id.pluginNoteIcon);
    image.setImageResource(icon);
    image.setImageTintList(
        android.content.res.ColorStateList.valueOf(requireContext().getColor(toneColor(tone))));
    ((TextView) note.findViewById(R.id.pluginNoteText)).setText(text);
    TextView button = note.findViewById(R.id.pluginNoteAction);
    if (action != null) {
      button.setText(action);
      button.setTextColor(requireContext().getColor(toneColor(tone)));
      button.setOnClickListener(listener);
      button.setVisibility(View.VISIBLE);
    }
    container.addView(note);
  }

  /** 重启待办单独渲染为警示胶囊：与「可更新 / 最近操作消息」两类提示条区分，只占内容宽度。 */
  private void addRestartChip(LinearLayout container, String text) {
    View chip = getLayoutInflater().inflate(R.layout.item_plugin_state_chip, container, false);
    int textColor = requireContext().getColor(R.color.shell_warn);
    TextView label = chip.findViewById(R.id.pluginChipText);
    label.setText(text);
    label.setTextColor(textColor);
    android.widget.ImageView icon = chip.findViewById(R.id.pluginChipIcon);
    icon.setImageResource(R.drawable.ic_plugin_restart);
    icon.setImageTintList(android.content.res.ColorStateList.valueOf(textColor));
    chip.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(badgeFill(R.color.shell_warn)));
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(8);
    container.addView(chip, params);
  }

  /** 展开后的属性表：版本 / 位置 / 来源，路径与来源可复制（复用「已复制」提示）。 */
  private void bindCardDetails(View card, PluginRepository.Item item) {
    ((TextView) card.findViewById(R.id.pluginVersionLabel))
        .setText(com.deepseekharness.app.util.UiText.choose("版本", "Version"));
    ((TextView) card.findViewById(R.id.pluginLocationLabel))
        .setText(com.deepseekharness.app.util.UiText.choose("位置", "Location"));
    ((TextView) card.findViewById(R.id.pluginSourceLabel))
        .setText(com.deepseekharness.app.util.UiText.choose("来源", "Source"));
    String latest =
        item.updateAvailable && !item.latestVersion.isEmpty()
            ? com.deepseekharness.app.util.UiText.raw(" → " + item.latestVersion)
            : "";
    ((TextView) card.findViewById(R.id.pluginDetailVersion))
        .setText(
            com.deepseekharness.app.util.UiText.raw(
                (item.version.isEmpty() ? "—" : item.version) + latest));
    boolean path = !item.runtimeProvided && !item.location.isEmpty();
    TextView location = card.findViewById(R.id.pluginDetailLocation);
    location.setText(locationText(item));
    location.setTypeface(
        path ? android.graphics.Typeface.MONOSPACE : android.graphics.Typeface.DEFAULT);
    View copyLocation = card.findViewById(R.id.pluginCopyLocation);
    copyLocation.setVisibility(path ? View.VISIBLE : View.INVISIBLE);
    copyLocation.setContentDescription(
        com.deepseekharness.app.util.UiText.choose("复制插件路径", "Copy plugin path"));
    copyLocation.setOnClickListener(v -> copyText(item.location));
    boolean source = !item.source.isEmpty();
    TextView sourceView = card.findViewById(R.id.pluginDetailSource);
    sourceView.setText(sourceText(item));
    sourceView.setTypeface(
        source ? android.graphics.Typeface.MONOSPACE : android.graphics.Typeface.DEFAULT);
    View copySource = card.findViewById(R.id.pluginCopySource);
    copySource.setVisibility(source ? View.VISIBLE : View.INVISIBLE);
    copySource.setContentDescription(com.deepseekharness.app.util.UiText.text("复制来源链接"));
    copySource.setOnClickListener(v -> copyText(item.source));
  }

  private void copyText(String value) {
    ClipboardManager clipboard =
        (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null) return;
    clipboard.setPrimaryClip(
        ClipData.newPlainText(com.deepseekharness.app.util.UiText.text("插件"), value));
    toast(com.deepseekharness.app.util.UiText.text("已复制"));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private static int kindIcon(com.deepseekharness.app.util.PluginCardState.Kind kind) {
    return switch (kind) {
      case DYNAMIC -> R.drawable.ic_plugin_bolt;
      case OFFICIAL, RUNTIME -> R.drawable.ic_ui_shield;
      case BUILTIN -> R.drawable.ic_ui2_box;
      case LOCATED -> R.drawable.ic_ui2_folder;
      case THIRD -> R.drawable.ic_plugins;
    };
  }

  private static String kindLabel(com.deepseekharness.app.util.PluginCardState.Kind kind) {
    return switch (kind) {
      case DYNAMIC -> com.deepseekharness.app.util.UiText.choose("临时插件", "Temporary plugin");
      case OFFICIAL -> com.deepseekharness.app.util.UiText.text("官方核心");
      case RUNTIME -> com.deepseekharness.app.util.UiText.choose("随 DSH 安装提供", "Provided by DSH");
      case BUILTIN -> com.deepseekharness.app.util.UiText.choose("DSHA 内置", "DSHA built-in");
      case LOCATED ->
          com.deepseekharness.app.util.UiText.choose("安装目录 · 未验证", "Install dir · unverified");
      case THIRD -> com.deepseekharness.app.util.UiText.choose("第三方", "Third-party");
    };
  }

  /** 插件自述及可恢复状态；坏包与会话临时插件仍给出明确的管理入口。 */
  private String descriptionText(PluginRepository.Item item) {
    if (item.dynamic)
      return item.description.isEmpty()
          ? com.deepseekharness.app.util.UiText.text("临时插件请在对应 Web 会话的 Cordis 插件管理中删除。")
          : com.deepseekharness.app.util.UiText.format(
              "%s\n\n临时插件请在对应 Web 会话的 Cordis 插件管理中删除。", item.description);
    if (!item.metadataError.isEmpty())
      return com.deepseekharness.app.util.UiText.text("元数据读取失败；可删除入口");
    if (item.description.isEmpty()) return "";
    return item.builtin
        ? com.deepseekharness.app.util.UiStateText.render(item.description)
        : com.deepseekharness.app.util.UiText.raw(item.description);
  }

  private String locationText(PluginRepository.Item item) {
    return item.runtimeProvided
        ? com.deepseekharness.app.util.UiText.choose("随 DSH 安装提供", "Provided by DSH")
        : item.location.isEmpty()
            ? com.deepseekharness.app.util.UiText.choose("未提供路径", "Path not provided")
            : com.deepseekharness.app.util.UiText.raw(item.location);
  }

  private String sourceText(PluginRepository.Item item) {
    if (!item.source.isEmpty()) return com.deepseekharness.app.util.UiText.raw(item.source);
    return item.builtin
        ? com.deepseekharness.app.util.UiText.choose("随包内置", "Bundled")
        : item.runtimeProvided
            ? com.deepseekharness.app.util.UiText.choose(
                "已核对当前 APK 受管内容", "Verified against the current APK")
            : item.official
                ? com.deepseekharness.app.util.UiText.choose("DSH 官方组件", "Official DSH component")
                : com.deepseekharness.app.util.UiText.choose("来源未记录", "Source not recorded");
  }

  private void bindToggle(
      android.widget.CompoundButton toggle, PluginListPresenter.Row<PluginRepository.Item> row) {
    PluginRepository.Item item = row.entry.value;
    toggle.setVisibility(item.dynamic ? View.GONE : View.VISIBLE);
    toggle.setOnCheckedChangeListener(null);
    toggle.setChecked(row.checked());
    toggle.setContentDescription(
        com.deepseekharness.app.util.UiText.format(row.checked() ? "禁用 %s" : "启用 %s", item.name));
    toggle.jumpDrawablesToCurrentState();
    toggle.setEnabled(row.toggleEnabled());
    toggle.setOnCheckedChangeListener((v, checked) -> toggle(item, checked));
  }

  /** 左侧主状态：排队中显示意图与百分比，平时显示配置。当前 Web 单独写在右侧。 */
  /** 配置胶囊的短文案：首屏只留一个结论词，完整状态放 contentDescription。 */
  private String configuredBadgeText(PluginListPresenter.Row<PluginRepository.Item> row) {
    PluginRepository.Item item = row.entry.value;
    if (row.pending()) {
      String working = com.deepseekharness.app.util.UiText.choose("处理中", "Working");
      return row.operation.percent >= 0 ? working + " " + row.operation.percent + "%" : working;
    }
    return item.dynamic
        ? com.deepseekharness.app.util.UiText.choose("临时", "Temporary")
        : !item.metadataError.isEmpty()
            ? com.deepseekharness.app.util.UiText.choose("异常", "Error")
            : !item.available
                ? com.deepseekharness.app.util.UiText.choose("缺失", "Missing")
                : item.enabled
                    ? com.deepseekharness.app.util.UiText.choose("已启用", "Enabled")
                    : item.detected
                        ? com.deepseekharness.app.util.UiText.choose("可开启", "Enable")
                        : com.deepseekharness.app.util.UiText.choose("已禁用", "Disabled");
  }

  private String configuredText(PluginListPresenter.Row<PluginRepository.Item> row) {
    PluginRepository.Item item = row.entry.value;
    if (row.pending()) {
      String intent =
          (row.operation.desired == null
                  ? com.deepseekharness.app.util.UiText.choose("\n操作待完成", "\nOperation pending")
                  : row.operation.desired
                      ? com.deepseekharness.app.util.UiText.choose("\n等待启用", "\nEnable pending")
                      : com.deepseekharness.app.util.UiText.choose("\n等待禁用", "\nDisable pending"))
              .substring(1);
      String phase =
          switch (row.operation.phase) {
            case "RUNNING" -> com.deepseekharness.app.util.UiText.choose("正在执行", "Running");
            case "COMMITTING" ->
                com.deepseekharness.app.util.UiText.choose("正在确认提交", "Confirming commit");
            default -> com.deepseekharness.app.util.UiText.choose("排队中", "Queued");
          };
      String progress = intent + " · " + phase;
      if (row.operation.percent >= 0) progress += " · " + row.operation.percent + "%";
      return progress;
    }
    return item.dynamic
        ? com.deepseekharness.app.util.UiText.choose("临时插件", "Temporary plugin")
        : !item.metadataError.isEmpty()
            ? com.deepseekharness.app.util.UiText.text("插件元数据不可用")
            : !item.available
                ? com.deepseekharness.app.util.UiText.text("实体缺失，请重新导入")
                : item.enabled
                    ? com.deepseekharness.app.util.UiText.choose("配置：已启用", "Configuration: enabled")
                    : item.detected
                        ? com.deepseekharness.app.util.UiText.text("已检测，可开启以加入 Web")
                        : com.deepseekharness.app.util.UiText.choose(
                            "配置：已禁用", "Configuration: disabled");
  }

  // ---------------------------------------------------------------- 方案 A 双栏详情窗格

  /** 窗口宽度 ≥ 840dp 时插件列表右侧常驻详情窗格；更窄时保持卡片内展开。 */
  private boolean twoPane;

  private String selectedPlugin = "";
  private View detailPane;

  private void selectDetail(String stableKey) {
    if (stableKey.equals(selectedPlugin)) return;
    String previous = selectedPlugin;
    selectedPlugin = stableKey;
    if (pageState != null) pageState.selected = stableKey;
    adapter.notifySelection(previous, stableKey);
    renderDetail();
  }

  /** 右侧窗格：同一张 item_plugin 卡片以 DETAIL 密度绑定，开关、更多与删除走与列表相同的回调。 */
  private void renderDetail() {
    if (!twoPane || detailPane == null) return;
    // 插件市场页没有可选条目：隐藏详情窗格，列表占满宽度。
    detailPane.setVisibility(market ? View.GONE : View.VISIBLE);
    View divider = root.findViewById(R.id.plugin_detail_divider);
    if (divider != null) divider.setVisibility(market ? View.GONE : View.VISIBLE);
    if (market) return;
    PluginListPresenter.Row<PluginRepository.Item> row = null;
    for (var candidate : adapter.rows)
      if (candidate.entry.stableKey.equals(selectedPlugin)) row = candidate;
    if (row == null && !adapter.rows.isEmpty()) {
      row = adapter.rows.get(0);
      selectedPlugin = row.entry.stableKey;
      if (pageState != null) pageState.selected = selectedPlugin;
      adapter.notifySelection("", selectedPlugin);
    }
    View empty = detailPane.findViewById(R.id.plugin_detail_empty);
    View content = detailPane.findViewById(R.id.plugin_detail_content);
    empty.setVisibility(row == null ? View.VISIBLE : View.GONE);
    content.setVisibility(row == null ? View.GONE : View.VISIBLE);
    ((TextView) empty)
        .setText(
            com.deepseekharness.app.util.UiText.choose(
                "选择左侧插件查看详情", "Select a plugin to see its details"));
    if (row == null) return;
    ((TextView) detailPane.findViewById(R.id.plugin_detail_label))
        .setText(com.deepseekharness.app.util.UiText.choose("插件详情", "Plugin details"));
    bindCard(detailPane.findViewById(R.id.plugin_detail_card), row, CardDensity.DETAIL);
  }
}
