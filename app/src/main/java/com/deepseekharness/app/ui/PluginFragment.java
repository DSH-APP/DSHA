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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.util.PluginSource;

import java.util.ArrayList;
import com.deepseekharness.app.util.PluginSort;
import java.util.List;
import java.util.Locale;

/** 插件市场入口与已装插件管理；耗时任务由 Activity 范围的 Repository 承接。 */
public class PluginFragment extends Fragment {
    private PluginRepository repository;
    private PluginRepository.State current;
    private View root;
    private EditText linkInput, search;
    private TextView linkHint;
    private CheckBox hideBuiltin;
    private boolean market = true;
    private PluginSort.Mode sortOrder=PluginSort.Mode.NAME_ASC;
    private final List<PluginRepository.Item> visibleItems = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private ArrayList<String> pendingExports = new ArrayList<>();
    private android.net.Uri pendingImport;
    private AlertDialog previewDialog;
    /** Repository 随 Activity 留存；失效记录不能只挂在被替换的 Fragment 上。仅主线程访问。 */
    private static long installedRevision;
    private static final java.util.WeakHashMap<PluginRepository, Long> refreshedRevisions = new java.util.WeakHashMap<>();
    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshInvalidated = this::refreshInstalledIfNeeded;
    private String environmentNotice;

    static void invalidateInstalledState() { installedRevision++; }

    /** 只在首次读取或安全启动失效后同步一次；等待中的轮询只读内存状态。 */
    private void refreshInstalledIfNeeded() {
        refreshHandler.removeCallbacks(refreshInvalidated);
        if (root == null || !isResumed()) return;
        if (pluginRefreshBlocked()) {
            refreshHandler.postDelayed(refreshInvalidated, 500);
            return;
        }
        Long refreshed = refreshedRevisions.get(repository);
        if (refreshed != null && refreshed == installedRevision) return;
        // 失败由操作结果明确显示，用户可手动重试；不在失败后无限全量刷新。
        refreshedRevisions.put(repository, installedRevision);
        syncInstalledState();
    }

    // 调试自测替换这两个边界，验证刷新时机，不触发真实插件注册或容器任务。
    boolean pluginRefreshBlocked() {
        String blocked = repository.environmentBlockMessage();
        if (!blocked.isEmpty()) {
            PluginRepository.State shown = repository.state().getValue();
            if (!repository.isBusy()) {
                environmentNotice = blocked;
                if (shown == null || !blocked.equals(shown.message)) repository.selectionMessage(blocked);
            }
            return true;
        }
        if (environmentNotice != null) {
            PluginRepository.State shown = repository.state().getValue();
            if (!repository.isBusy() && shown != null && environmentNotice.equals(shown.message))
                repository.selectionMessage(com.deepseekharness.app.util.UiText.text("环境任务已结束；当前显示缓存列表，可点「刷新」同步插件状态"));
            environmentNotice = null;
        }
        com.deepseekharness.app.core.HarnessController controller =
                com.deepseekharness.app.core.HarnessController.get(requireContext());
        return repository.isBusy() || controller.isStarting() || controller.isStopping();
    }
    void syncInstalledState() { repository.refresh(); }

    private final ActivityResultLauncher<android.content.Intent> importPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (repository == null) repository = new ViewModelProvider(requireActivity()).get(PluginRepository.class);
                android.content.Intent data = result.getData();
                android.net.Uri uri = data == null ? null : data.getData();
                if (uri == null && data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0)
                    uri = data.getClipData().getItemAt(0).getUri();
                if (result.getResultCode() != android.app.Activity.RESULT_OK || uri == null) {
                    repository.selectionMessage(com.deepseekharness.app.util.UiText.text("未选择文件或文件管理器未返回文件。可点「其他文件选择器」重试，选择 ZIP / TAR.GZ 插件包。"));
                    return;
                }
                if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) {
                    repository.selectionMessage(com.deepseekharness.app.util.UiText.text("文件管理器返回的地址无法读取，请改用系统文件选择器。"));
                    return;
                }
                if ("file".equals(uri.getScheme()) && android.os.Build.VERSION.SDK_INT < 30
                        && requireContext().checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    pendingImport = uri;
                    PluginFragment.this.readPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                    return;
                }
                repository.importArchive(uri);
            });
    private final ActivityResultLauncher<String> readPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), allowed -> {
                android.net.Uri selected = pendingImport;
                pendingImport = null;
                if (allowed && selected != null) repository.importArchive(selected);
                else repository.selectionMessage(com.deepseekharness.app.util.UiText.text("未获得文件读取权限，请改用系统文件选择器导入。"));
            });
    private final ActivityResultLauncher<String> exportPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/gzip"), uri -> {
                if (uri != null && !pendingExports.isEmpty())
                    repository.exportArchives(new ArrayList<>(pendingExports), uri);
                pendingExports.clear();
            });

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_plugins, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        root = view;
        sortOrder=new com.deepseekharness.app.core.ConfigStore(requireContext()).getPluginSort();
        repository = new ViewModelProvider(requireActivity()).get(PluginRepository.class);
        if (getArguments() != null && getArguments().getBoolean("show_installed", false)) market = false;
        if (saved != null) {
            market = saved.getBoolean("market", true);
            if(saved.containsKey("sortOrder"))sortOrder=PluginSort.Mode.parse(saved.getString("sortOrder"));
            else if(saved.getBoolean("enabledFirst"))sortOrder=PluginSort.Mode.ENABLED_FIRST;
            ArrayList<String> names = saved.getStringArrayList("pendingExports");
            if (names != null) pendingExports = names;
            String imported = saved.getString("pendingImport");
            if (imported != null) pendingImport = android.net.Uri.parse(imported);
        }
        linkInput = view.findViewById(R.id.appbar_github_input);
        linkHint = view.findViewById(R.id.pluginLinkHint);
        search = view.findViewById(R.id.pluginSearch);
        hideBuiltin = view.findViewById(R.id.chkHideBuiltin);
        RecyclerView list = view.findViewById(R.id.pluginList);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setNestedScrollingEnabled(false);
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        view.findViewById(R.id.btnMarket).setOnClickListener(v -> selectTab(true));
        view.findViewById(R.id.btnPluginWebsite).setOnClickListener(v -> openPluginWebsite());
        view.findViewById(R.id.btnInstalled).setOnClickListener(v -> selectTab(false));
        view.findViewById(R.id.btnRefresh).setOnClickListener(v -> repository.refresh());
        view.findViewById(R.id.btnPluginUpdates).setOnClickListener(v -> repository.checkUpdates(null));
        view.findViewById(R.id.btnCancelPluginTask).setOnClickListener(v -> repository.cancelTask());
        view.findViewById(R.id.btnPluginRestore).setOnClickListener(v -> new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(com.deepseekharness.app.util.UiText.text("恢复第三方插件？")).setMessage(com.deepseekharness.app.util.UiText.text("恢复安全启动前已启用的插件；之后手动禁用的插件保持禁用。恢复后重启 Web 生效。"))
                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null).setPositiveButton(com.deepseekharness.app.util.UiText.text("恢复"), (d, which) -> repository.safeMode(false, null)).show());
        view.findViewById(R.id.btnPluginInstall).setOnClickListener(v -> installLink());
        view.findViewById(R.id.btnPluginPaste).setOnClickListener(v -> pasteLink());
        view.findViewById(R.id.btnImport).setOnClickListener(v -> chooseImport(false));
        view.findViewById(R.id.btnImportFallback).setOnClickListener(v -> chooseImport(true));
        view.findViewById(R.id.btnExport).setOnClickListener(v -> chooseExport());
        view.findViewById(R.id.btnSort).setOnClickListener(v -> showSortOptions());
        hideBuiltin.setOnCheckedChangeListener((v, checked) -> render());
        search.addTextChangedListener(watcher(this::render));
        linkInput.addTextChangedListener(watcher(this::recognizeLink));
        linkInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP)) {
                installLink();
                return true;
            }
            return false;
        });
        TextView status = view.findViewById(R.id.statusText);
        status.setOnClickListener(v -> {
            if (current != null && !current.message.isEmpty())
                new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("插件操作结果"))
                        .setMessage(com.deepseekharness.app.util.UiStateText.render(current.message)).setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null).show();
        });
        repository.state().observe(getViewLifecycleOwner(), state -> { current = state; render(); });
        repository.preview().observe(getViewLifecycleOwner(), ignored -> showInstallPreview());
        recognizeLink();
    }

    @Override public void onResume() {
        super.onResume();
        refreshInstalledIfNeeded();
    }

    @Override public void onPause() {
        refreshHandler.removeCallbacks(refreshInvalidated);
        super.onPause();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("market", market);
        state.putString("sortOrder", sortOrder.name());
        state.putStringArrayList("pendingExports", pendingExports);
        if (pendingImport != null) state.putString("pendingImport", pendingImport.toString());
    }

    @Override public void onDestroyView() {
        refreshHandler.removeCallbacks(refreshInvalidated);
        if (previewDialog != null) { previewDialog.dismiss(); previewDialog = null; }
        ((RecyclerView) root.findViewById(R.id.pluginList)).setAdapter(null);
        root = null;
        linkInput = null;
        search = null;
        linkHint = null;
        hideBuiltin = null;
        super.onDestroyView();
    }

    private static TextWatcher watcher(Runnable callback) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { callback.run(); }
            @Override public void afterTextChanged(Editable e) { }
        };
    }

    private void showInstallPreview() {
        if (root == null || repository.isBusy() || previewDialog != null) return;
        PluginRepository.Preview preview = repository.preview().getValue();
        if (preview == null) return;
        previewDialog = new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("确认安装插件"))
                .setMessage(preview.description())
                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), (d, w) -> repository.discardPreview())
                .setPositiveButton(com.deepseekharness.app.util.UiText.text("确认安装"), (d, w) -> repository.confirmPreview())
                .setOnCancelListener(d -> repository.discardPreview()).create();
        previewDialog.setOnDismissListener(d -> previewDialog = null);
        previewDialog.show();
    }

    private void recognizeLink() {
        if (root == null) return;
        String input = linkInput.getText().toString();
        boolean valid = false;
        try {
            PluginSource source = PluginSource.parse(input);
            linkHint.setText(com.deepseekharness.app.util.UiText.text("已识别：") + source.description());
            valid = true;
        } catch (IllegalArgumentException error) {
            linkHint.setText(input.trim().isEmpty() ? com.deepseekharness.app.util.UiText.text("支持仓库、分支/子目录、Release 下载和压缩包直链")
                    : error.getMessage());
        }
        root.findViewById(R.id.btnPluginInstall).setEnabled(valid && !repository.isBusy());
    }

    private void selectTab(boolean showMarket) {
        market = showMarket;
        linkInput.clearFocus();
        search.clearFocus();
        android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        render();
        androidx.core.widget.NestedScrollView scroll = root.findViewById(R.id.pluginScroll);
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void openPluginWebsite() {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://dsha.cc/"))
                    .addCategory(android.content.Intent.CATEGORY_BROWSABLE));
        } catch (RuntimeException error) {
            toast(com.deepseekharness.app.util.UiText.text("无法打开浏览器，请在浏览器中访问 https://dsha.cc/"));
        }
    }

    private void installLink() {
        if (repository.isBusy()) return;
        try {
            PluginSource source = PluginSource.parse(linkInput.getText().toString());
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(linkInput.getWindowToken(),0);
            linkInput.clearFocus();
            repository.install(source);
        }
        catch (IllegalArgumentException error) { toast(error.getMessage()); }
    }

    private void chooseImport(boolean alternative) {
        if (repository.isBusy()) { toast(com.deepseekharness.app.util.UiText.text("请等待当前插件操作完成后再导入")); return; }
        View focus = requireActivity().getCurrentFocus();
        if (focus != null) {
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
        repository.selectionMessage(com.deepseekharness.app.util.UiText.text("请选择插件压缩包；文件选择器无法返回时，可使用「其他文件选择器」。"));
        try { importPicker.launch(PluginFilePicker.intent(requireContext(), alternative)); }
        catch (android.content.ActivityNotFoundException error) {
            if (!alternative) { chooseImport(true); return; }
            repository.selectionMessage(com.deepseekharness.app.util.UiText.text("未找到可用的文件选择器，请启用系统「文件」应用后重试。"));
            toast(com.deepseekharness.app.util.UiText.text("没有可用的文件选择器"));
        } catch (RuntimeException error) {
            repository.selectionMessage(com.deepseekharness.app.util.UiText.text("无法打开文件选择器，请使用备用入口：") + error.getClass().getSimpleName());
        }
    }

    private void pasteLink() {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) { toast(com.deepseekharness.app.util.UiText.text("剪贴板没有链接")); return; }
        CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
        if (text != null) linkInput.setText(text);
    }

    private void render() {
        if (root == null || current == null) return;
        root.findViewById(R.id.pluginMarketCard).setVisibility(market ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.marketHelp).setVisibility(market ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.pluginWebsiteSection).setVisibility(market ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.pluginLinkSection).setVisibility(market ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.btnPluginRestore).setVisibility(repository.isSafeMode() ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.installedControls).setVisibility(market ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.pluginList).setVisibility(market ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.btnMarket).setSelected(market);
        root.findViewById(R.id.btnInstalled).setSelected(!market);
        ((TextView) root.findViewById(R.id.btnMarket)).setTextColor(requireContext().getColor(
                market ? R.color.primary : R.color.text_secondary));
        ((TextView) root.findViewById(R.id.btnInstalled)).setTextColor(requireContext().getColor(
                market ? R.color.text_secondary : R.color.primary));
        root.findViewById(R.id.pluginBusy).setVisibility(current.busy ? View.VISIBLE : View.GONE);
        android.widget.ProgressBar progress = root.findViewById(R.id.pluginBusy);
        progress.setIndeterminate(current.percent < 0);
        if (current.percent >= 0) progress.setProgress(current.percent);
        root.findViewById(R.id.btnCancelPluginTask).setVisibility(current.busy ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.btnCancelPluginTask).setEnabled(current.cancellable);
        ((TextView) root.findViewById(R.id.statusText)).setText(com.deepseekharness.app.util.UiStateText.render(current.message));
        for (int id : new int[]{R.id.btnImport, R.id.btnImportFallback, R.id.btnExport, R.id.btnRefresh, R.id.btnPluginUpdates, R.id.btnPluginRestore})
            root.findViewById(id).setEnabled(!current.busy);
        TextView sort=root.findViewById(R.id.btnSort);
        sort.setText(sortLabels()[sortOrder.ordinal()]);
        sort.setContentDescription(getString(R.string.plugin_sort_title)+" · "+sort.getText());
        visibleItems.clear();
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (PluginRepository.Item item : current.items) {
            if (hideBuiltin.isChecked() && (item.builtin || item.official)) continue;
            if (!(item.name + " " + item.description).toLowerCase(Locale.ROOT).contains(query)) continue;
            visibleItems.add(item);
        }
        visibleItems.sort(PluginSort.comparator(sortOrder,it->it.name,it->it.enabled,it->it.updateAvailable));
        ((TextView) root.findViewById(R.id.pluginCount)).setText(com.deepseekharness.app.util.UiText.text("共 ") + visibleItems.size() + com.deepseekharness.app.util.UiText.text(" 个插件"));
        TextView empty = root.findViewById(R.id.pluginEmpty);
        empty.setVisibility(!market && visibleItems.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(current.busy ? com.deepseekharness.app.util.UiText.text("正在读取插件…") : com.deepseekharness.app.util.UiText.text("没有符合条件的插件"));
        adapter.notifyDataSetChanged();
        recognizeLink();
        showInstallPreview();
    }

    private String[] sortLabels() {
        return new String[]{getString(R.string.plugin_sort_az),getString(R.string.plugin_sort_za),
                getString(R.string.plugin_sort_enabled),getString(R.string.plugin_sort_updates)};
    }
    private void showSortOptions() {
        new DshaDialogBuilder(requireContext()).setTitle(R.string.plugin_sort_title)
                .setSingleChoiceItems(sortLabels(),sortOrder.ordinal(),(dialog,index)->{
                    sortOrder=PluginSort.Mode.values()[index];
                    new com.deepseekharness.app.core.ConfigStore(requireContext()).setPluginSort(sortOrder);
                    dialog.dismiss();render();
                }).setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"),null).show();
    }

    private void chooseExport() {
        if (current == null || repository.isBusy()) return;
        List<String> names = new ArrayList<>();
        for (PluginRepository.Item item : current.items) if (item.exportable) names.add(item.name);
        if (names.isEmpty()) { toast(com.deepseekharness.app.util.UiText.text("没有可导出的插件")); return; }
        boolean[] checked = new boolean[names.size()];
        AlertDialog dialog = new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                .setTitle(com.deepseekharness.app.util.UiText.text("选择要导出的插件"))
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, which, value) -> {
                    checked[which] = value;
                    boolean any = false;
                    for (boolean selected : checked) any |= selected;
                    ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
                })
                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                .setPositiveButton(com.deepseekharness.app.util.UiText.text("选择保存位置"), (d, which) -> {
                    ArrayList<String> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(names.get(i));
                    beginExport(selected);
                }).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false));
        dialog.show();
    }

    private void beginExport(ArrayList<String> names) {
        if (names.isEmpty() || repository.isBusy()) return;
        pendingExports = names;
        String name = names.size() == 1 ? names.get(0).replaceAll("[^A-Za-z0-9._-]", "_") : "DSHA-plugins";
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        try { exportPicker.launch(name + "-" + stamp + ".tar.gz"); }
        catch (Exception error) { pendingExports.clear(); toast(com.deepseekharness.app.util.UiText.text("无法打开保存位置选择器")); }
    }

    private void toggle(PluginRepository.Item item, boolean enabled) {
        if (repository.isBusy()) { adapter.notifyDataSetChanged(); return; }
        if (item.official && !enabled) {
            new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("禁用官方核心？"))
                    .setMessage(item.name + com.deepseekharness.app.util.UiText.text(" 是 Web 运行所需的核心，禁用后页面可能无法启动。"))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("禁用"), (d, which) -> repository.setEnabled(item, false))
                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                    .setOnDismissListener(d -> adapter.notifyDataSetChanged()).show();
        } else repository.setEnabled(item, enabled);
    }

    private void itemActions(PluginRepository.Item item) {
        List<String> actions = new ArrayList<>();
        actions.add("查看详情");
        actions.add("复制插件名称");
        if (!item.source.isEmpty()) actions.add("复制来源链接");
        if (item.exportable) actions.add("导出插件包");
        if (item.deletable) actions.add("检查插件更新");
        if (item.updateAvailable) actions.add("更新至 " + item.latestVersion);
        if (!item.rollbackVersion.isEmpty()) actions.add("回退至 " + item.rollbackVersion);
        if (item.deletable) actions.add("删除插件");
        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(item.name)
                .setItems(actions.stream().map(action -> action.startsWith("更新至 ")
                        ? com.deepseekharness.app.util.UiText.text("更新至 ") + action.substring(4)
                        : action.startsWith("回退至 ") ? com.deepseekharness.app.util.UiText.text("回退至 ") + action.substring(4)
                        : com.deepseekharness.app.util.UiText.text(action)).toArray(String[]::new), (d, which) -> {
                    String action = actions.get(which);
                    if (action.equals("查看详情")) {
                        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(item.name)
                                .setMessage(item.description + com.deepseekharness.app.util.UiText.text("\n\n版本：") + item.version
                                        + (item.location.isEmpty() ? "" : com.deepseekharness.app.util.UiText.text("\n位置：") + item.location)
                                        + (item.source.isEmpty() ? "" : com.deepseekharness.app.util.UiText.text("\n来源：") + item.source)
                                        + (item.latestVersion.isEmpty() ? "" : com.deepseekharness.app.util.UiText.text("\n上次检查版本：") + item.latestVersion)
                                        + (item.updateMessage.isEmpty() ? "" : "\n" + com.deepseekharness.app.util.UiStateText.render(item.updateMessage))
                                        + (item.rollbackVersion.isEmpty() ? "" : com.deepseekharness.app.util.UiText.text("\n可回退：") + item.rollbackVersion))
                                .setPositiveButton(com.deepseekharness.app.util.UiText.text("关闭"), null).show();
                    } else if (action.equals("检查插件更新")) {
                        repository.checkUpdates(item);
                    } else if (action.startsWith("更新至 ")) {
                        repository.prepareUpdate(item);
                    } else if (action.startsWith("回退至 ")) {
                        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("回退插件？"))
                                .setMessage(item.name + com.deepseekharness.app.util.UiText.text("：") + item.version + " → " + item.rollbackVersion
                                        + com.deepseekharness.app.util.UiText.text("\n只恢复插件文件，当前启用状态和对话数据保留；重启 Web 生效。"))
                                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null).setPositiveButton(com.deepseekharness.app.util.UiText.text("回退"), (confirm, button) -> repository.rollback(item)).show();
                    } else if (action.equals("导出插件包")) {
                        ArrayList<String> names = new ArrayList<>();
                        names.add(item.name);
                        beginExport(names);
                    } else if (action.equals("删除插件")) {
                        if (repository.isBusy()) { toast(com.deepseekharness.app.util.UiText.text("请等待当前插件操作完成")); return; }
                        new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext()).setTitle(com.deepseekharness.app.util.UiText.text("删除插件？"))
                                .setMessage(com.deepseekharness.app.util.UiText.text("将删除 ") + item.name + com.deepseekharness.app.util.UiText.text(" 的安装文件和启用记录。")
                                        + com.deepseekharness.app.util.UiText.text("\n对话、其他插件及外部源码目录会保留。需要留存时可先导出。"))
                                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
                                .setPositiveButton(com.deepseekharness.app.util.UiText.text("删除"), (confirm, button) -> repository.delete(item)).show();
                    } else {
                        ClipboardManager clipboard = (ClipboardManager) requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(com.deepseekharness.app.util.UiText.text("插件"),
                                action.equals("复制插件名称") ? item.name : item.source));
                        toast(com.deepseekharness.app.util.UiText.text("已复制"));
                    }
                }).show();
    }

    private void toast(String message) {
        if (isAdded()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            final TextView name, state, description;
            final android.widget.CompoundButton toggle;
            Holder(View view) {
                super(view);
                name = view.findViewById(R.id.pluginName);
                state = view.findViewById(R.id.pluginStatus);
                description = view.findViewById(R.id.pluginDesc);
                toggle = view.findViewById(R.id.pluginSwitch);
            }
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_plugin, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            PluginRepository.Item item = visibleItems.get(position);
            holder.name.setText(item.name);
            holder.state.setText((item.dynamic ? (item.enabled ? com.deepseekharness.app.util.UiText.text("临时插件 · 已运行") : com.deepseekharness.app.util.UiText.text("临时插件 · 未运行"))
                    : item.available ? (item.enabled ? com.deepseekharness.app.util.UiText.text("已启用") : item.detected ? com.deepseekharness.app.util.UiText.text("已检测，可开启以加入 Web") : com.deepseekharness.app.util.UiText.text("已禁用")) : com.deepseekharness.app.util.UiText.text("实体缺失，请重新导入"))
                    + (item.version.isEmpty() ? "" : " · " + item.version)
                    + (item.updateAvailable ? com.deepseekharness.app.util.UiText.text("\n可更新：") + item.latestVersion : ""));
            holder.state.setTextColor(requireContext().getColor(
                    !item.available ? R.color.warn : item.enabled ? R.color.primary : R.color.text_muted));
            holder.description.setText(item.description.isEmpty()
                    ? (item.official ? com.deepseekharness.app.util.UiText.text("官方核心") : item.builtin ? com.deepseekharness.app.util.UiText.text("DSHA 内置插件") : com.deepseekharness.app.util.UiText.text("第三方插件")) : item.builtin || item.official || item.dynamic ? com.deepseekharness.app.util.UiStateText.render(item.description) : item.description);
            holder.itemView.findViewById(R.id.pluginActions).setOnClickListener(v -> itemActions(item));
            holder.itemView.findViewById(R.id.pluginActions).setContentDescription(com.deepseekharness.app.util.UiText.text("更多操作：") + item.name);
            holder.toggle.setVisibility(item.dynamic ? View.GONE : View.VISIBLE);
            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setChecked(item.enabled);
            holder.toggle.setContentDescription((item.enabled ? com.deepseekharness.app.util.UiText.text("禁用 ") : com.deepseekharness.app.util.UiText.text("启用 ")) + item.name);
            holder.toggle.jumpDrawablesToCurrentState();
            holder.toggle.setEnabled(!repository.isBusy() && (item.available || item.enabled));
            holder.toggle.setOnCheckedChangeListener((v, checked) -> {
                if (checked != item.enabled) toggle(item, checked);
            });
            holder.itemView.setOnLongClickListener(v -> { itemActions(item); return true; });
        }
        @Override public int getItemCount() { return visibleItems.size(); }
    }
}
