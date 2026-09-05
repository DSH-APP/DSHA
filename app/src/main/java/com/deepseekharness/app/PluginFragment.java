package com.deepseekharness.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 插件控制器：插件市场（awesome-dsh-plugins 快照，支持 star/名称/分类/兼容性排序 + 一键安装）
 * + 已装插件管理（启用/禁用/导入/导出）
 */
public class PluginFragment extends Fragment {
    /** 市场缓存年龄提示（" · 缓存于 N 分钟前"）；无缓存返回空串 */
    private String cacheHint() {
        long age = c.getMarketCacheAgeMs();
        if (age < 0) return "";
        return String.format(java.util.Locale.US, " · 缓存于 %d 分钟前", age / 60000);
    }

    private enum Mode { MARKET, INSTALLED }

    private Mode mode = Mode.MARKET;
    private final List<String[]> items = new ArrayList<>();
    private final List<String[]> installed = new ArrayList<>();
    private PluginAdapter adapter;
    private HarnessController c;
    private TextView status;
    /** 底部细进度条：文案含「正在」时自动亮起，操作结束自动收起（见 {@link #say}） */
    private android.widget.ProgressBar busyBar;

    /** 统一设置状态文案：市场拉取/插件安装/卸载动辄几十秒，只有静态文字容易让人以为卡死，
     *  这里按文案自动联动底部细进度条，所有调用点无需各自管理可见性。 */
    private void say(String s) {
        if (status != null) status.setText(s);
        if (busyBar != null) {
            boolean busy = s != null && (s.contains("正在") || s.contains("加载中"));
            busyBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
    }
    /** 当前排序：0 star / 1 名称 */
    private int sortMode = 0;
    /** 仅显示兼容插件（过滤 ❌不兼容） */
    private boolean filterIncompat = false;
    private boolean hideInstalled = false;
    /** 当前分类筛选（空 = 全部）。市场源本来就带分类（it[4]），
     *  之前只在详情弹窗里显示，白白浪费了一个天然的筛选维度。 */
    private String categoryFilter = "";
    /** 已装插件名（供市场页「隐藏已安装」比对；加载已装列表时填充） */
    private final java.util.List<String> installedNames = new java.util.ArrayList<>();
    /** 当前搜索词（供过滤/排序后刷新视图复用） */
    private String searchQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_plugins, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        c = HarnessController.get(requireContext());
        adapter = new PluginAdapter();
        RecyclerView rv = view.findViewById(R.id.pluginList);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        status = view.findViewById(R.id.statusText);
        busyBar = view.findViewById(R.id.pluginBusy);

        TextView btnMarket = view.findViewById(R.id.btnMarket);
        TextView btnInstalled = view.findViewById(R.id.btnInstalled);
        TextView btnSort = view.findViewById(R.id.btnSort);
        android.widget.EditText searchBox = view.findViewById(R.id.pluginSearch);
        // ===== GitHub 仓库链接解析（市场顶部）：输入链接 → 列表切换为解析结果 =====
        // 输入框已移到 Activity 的 app_bar（嵌在标题与「关于」按钮之间），
        // 所以从 activity 取而不是 fragment 的 view。
        // 取不到就跳过整段解析逻辑 —— 将来布局再变也不至于 NPE。
        final android.widget.EditText githubInput =
                requireActivity().findViewById(R.id.appbar_github_input);
        // 不在这里 return —— 下面的 if (githubInput != null) 已经做了保护，
        // 而 btnMarket/btnSort/btnFilter/btnRefresh 的绑定都在本方法后半段：
        // 中途 return 会让整个插件页的按钮全部失去监听。
        // 没有「解析」按钮：输入停顿 600ms 自动解析，回车也能立刻解析，按钮纯属多余
        if (githubInput != null) {
            // 当前解析结果缓存（null=非解析模式）
            final java.util.concurrent.atomic.AtomicReference<String[]> parsedRef =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
            // 防抖 handler（输入停顿 600ms 才解析）
            final android.os.Handler debounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            final Runnable[] debounceTask = new Runnable[1];
            java.util.function.Consumer<String> doParse = (link) -> {
                String u = link == null ? "" : link.trim();
                if (u.isEmpty()) {
                    // 清空 → 按**当前模式**恢复列表。
                    //
                    // 这里原来是无条件 setData(空) 之后只 `if (mode == MARKET) showMarket()` ——
                    // 停在「插件管理」时列表被清空却没人重填，卡片就永远不见了。
                    // 而触发它的不是用户：MainActivity#onTabShown 切走插件页时会
                    // ghIn.setText("") 清空那个 GitHub 输入框，TextWatcher 照样响，
                    // 防抖 600ms 之后（用户已经切到别的页了）跑到这里把列表清空。
                    // 于是「从其他页返回插件管理，卡片全不见、搜索框和 tab 还在」——
                    // 就是主人看到的那个提示「已恢复插件市场」露的马脚：
                    // 明明停在插件管理，恢复的却是市场。
                    parsedRef.set(null);
                    if (mode == Mode.MARKET) {
                        adapter.setData(new ArrayList<>(), true);
                        showMarket();
                        say("已恢复插件市场");
                    } else {
                        showInstalled();
                    }
                    return;
                }
                String[] info = c.parseGithubUrl(u);
                if (info == null) {
                    // 不是 GitHub 链接 —— 但 pnpm 认的来源远不止 GitHub：jsr:、gh:、gitlab:、
                    // bitbucket:、git+ssh、远程 tarball、owner/repo#commit、#path: 子目录、
                    // 本地目录与 .tgz。识别得出来就摆成一条可直接安装的条目，
                    // 别再一句「无法解析链接」把用户挡回去。
                    int kind = PluginSpec.classify(u);
                    if (kind == PluginSpec.UNKNOWN) {
                        say("不认识这个来源：" + u + "\n支持 npm 包名（可带 @版本）、jsr:、"
                                + "owner/repo（可带 #分支 / #commit / #semver: / #path:）、"
                                + "github:/gitlab:/bitbucket:、完整 git URL、远程 tarball、"
                                + "本地目录或 .tgz");
                        return;
                    }
                    parsedRef.set(null);
                    java.util.List<String[]> one = new java.util.ArrayList<>();
                    // it[6] 放原始 spec —— installMarketItem 认不出 GitHub 链接时直接拿它装
                    String note = "来源类型：" + PluginSpec.describe(kind)
                            + (PluginSpec.shipsSourceOnly(kind)
                            ? "\n\n这类来源装的是源码。缺构建产物时会自动在容器里 clone 并构建，"
                              + "要几分钟。" : "");
                    String sub = PluginSpec.subPathOf(u);
                    if (!sub.isEmpty()) note += "\n仓库子目录：" + sub;
                    one.add(new String[]{u, "0", "", "⏳待定",
                            PluginSpec.describe(kind), note, u});
                    adapter.setData(one, true);
                    say("识别为「" + PluginSpec.describe(kind) + "」，点「安装」直接装");
                    return;
                }
                final String owner2 = info[1].substring(0, info[1].indexOf('/'));
                final String repo2 = info[1].substring(info[1].indexOf('/') + 1);
                // 先把仓库本身显示出来 —— 查 npm 包名要走网络（多源回退，最坏几十秒），
                // 以前等它跑完才 setData，用户看到的就是「点了没反应」。
                parsedRef.set(info);
                java.util.List<String[]> one0 = new java.util.ArrayList<>();
                one0.add(new String[]{info[1], "0", owner2, "⏳待定",
                        "查询中…", "来自仓库链接：\n" + info[2], info[2]});
                adapter.setData(one0, true);
                say("已解析 " + info[1] + "，正在查 npm 包名…（可直接点「安装」按仓库方式装）");
                new Thread(() -> {
                    String npmName = c.fetchNpmName(owner2, repo2);
                    if (npmName != null) info[0] = npmName;
                    runOnUiThreadSafely(() -> {
                        if (githubInput.getText().toString().trim().isEmpty()) return; // 已被清空
                        parsedRef.set(info);
                        // 列表显示解析结果（单条）。it[2]=owner（startAutoInstall 用它），
                        // it[6]=完整仓库 URL（详情/复制用）
                        java.util.List<String[]> one = new java.util.ArrayList<>();
                        one.add(new String[]{info[1], "0", owner2, "⏳待定",
                                npmName != null ? npmName : "仅GitHub仓库", "来自仓库链接：\n" + info[2], info[2]});
                        adapter.setData(one, true);
                        say(npmName != null
                                ? "✅ 解析成功：" + npmName + "（点「安装」装到已装插件）"
                                : "⚠️ 没查到 npm 包名（仓库未发布 npm，或网络/代理不通）——"
                                        + "仍可点「安装」按 GitHub 仓库方式装");
                    });
                }).start();
            };
            // 输入监听（防抖）
            githubInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (debounceTask[0] != null) debounceHandler.removeCallbacks(debounceTask[0]);
                    debounceTask[0] = () -> doParse.accept(githubInput.getText().toString());
                    debounceHandler.postDelayed(debounceTask[0], 600);
                }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            });
            // 回车 = 立即解析
            githubInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    doParse.accept(githubInput.getText().toString());
                    return true;
                }
                return false;
            });
        }
        view.findViewById(R.id.actionBar).setVisibility(View.GONE);

        // 搜索：按名称过滤（忽略大小写）
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                searchQuery = s.toString();
                if (mode == Mode.MARKET) {
                    refreshMarketView();
                } else if (mode == Mode.INSTALLED) {
                    String q = searchQuery.trim().toLowerCase();
                    java.util.List<String[]> filtered = new java.util.ArrayList<>();
                    for (String[] it : installed) {
                        if (q.isEmpty() || it[0].toLowerCase().contains(q)) filtered.add(it);
                    }
                    adapter.setData(filtered, false);
                    say("已装 " + filtered.size() + " 个插件 · 开关启用/禁用" + (q.isEmpty() ? "" : "（搜索：" + q + "）"));
                }
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        btnMarket.setOnClickListener(v -> {
            mode = Mode.MARKET;
            exitMulti();   // 多选只属于插件管理页，切走就收起来
            styleTab(btnMarket, true);
            styleTab(btnInstalled, false);
            view.findViewById(R.id.actionBar).setVisibility(View.GONE);
            view.findViewById(R.id.chkHideBuiltin).setVisibility(View.GONE);
            showMarket();
        });
        btnInstalled.setOnClickListener(v -> {
            mode = Mode.INSTALLED;
            styleTab(btnMarket, false);
            styleTab(btnInstalled, true);
            view.findViewById(R.id.actionBar).setVisibility(View.VISIBLE);
            view.findViewById(R.id.chkHideBuiltin).setVisibility(View.VISIBLE);
            showInstalled();
        });
        btnSort.setOnClickListener(v -> showSortMenu(btnSort));
        // 「筛选」按钮布局里一直存在，但从未绑定过监听 —— 用户点了毫无反应。
        // 筛选功能原先塞在排序菜单里，两个按钮职责混淆。现在拆开。
        TextView btnFilter = view.findViewById(R.id.btnFilter);
        if (btnFilter != null) btnFilter.setOnClickListener(v -> showFilterMenu(btnFilter));

        // 强制刷新市场缓存（清缓存 → 重新拉网络）
        TextView btnRefresh = view.findViewById(R.id.btnRefresh);
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> {
                say("已清除缓存，正在重新拉取…");
                c.refreshMarketIndex();
                items.clear();
                showMarket();
            });
        }

        view.findViewById(R.id.btnExport).setOnClickListener(v -> exportPlugins());
        view.findViewById(R.id.btnImport).setOnClickListener(v -> importPlugins());
        // 多选操作条（长按插件卡片才出现）
        multiBar = view.findViewById(R.id.multiBar);
        multiCount = view.findViewById(R.id.multiCount);
        view.findViewById(R.id.multiSelectAll).setOnClickListener(v -> selectAllInstalled());
        view.findViewById(R.id.multiCancel).setOnClickListener(v -> exitMulti());
        view.findViewById(R.id.multiEnable).setOnClickListener(v -> batchToggle(true));
        view.findViewById(R.id.multiDisable).setOnClickListener(v -> batchToggle(false));
        view.findViewById(R.id.multiExport).setOnClickListener(v -> batchExport());
        view.findViewById(R.id.multiDelete).setOnClickListener(v -> batchDelete());
        // 隐藏自带插件开关：记住选择，切换时刷新已装列表
        final android.widget.CheckBox hideCb = view.findViewById(R.id.chkHideBuiltin);
        hideCb.setChecked(requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("hide_builtin", false));
        hideCb.setOnCheckedChangeListener((b, isChecked) -> {
            requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("hide_builtin", isChecked).apply();
            showInstalled();
        });

        showMarket();
    }

    private void styleTab(TextView tab, boolean on) {
        tab.setBackgroundResource(on ? R.drawable.bg_tab_on : R.drawable.bg_tab);
        tab.setTextColor(requireContext().getColor(on ? R.color.primary : R.color.text_muted));
    }

    /** 排序下拉菜单：点一下展开选择，不用一直点循环。
     *  菜单里同时提供「仅显示兼容」勾选项（过滤 ❌不兼容，⏳待定/未测保留）。 */
    private void showSortMenu(android.view.View anchor) {
        final String[] options = {"⭐ Star 数", "🔤 名称 A-Z"};
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        for (int i = 0; i < options.length; i++) {
            pm.getMenu().add(0, i, 0, options[i]);
        }
        pm.setOnMenuItemClickListener(item -> {
            sortMode = item.getItemId();
            ((android.widget.TextView) anchor).setText(options[sortMode].replace("排序：", ""));
            if (mode == Mode.MARKET) refreshMarketView();
            return true;
        });
        pm.show();
    }

    /** 筛选菜单：只管「显示哪些」，与排序分开。
     *
     *  之前这些选项塞在排序菜单里，而布局上另有一个「筛选」按钮没绑监听 ——
     *  用户看到按钮却点不动，功能藏在另一个按钮后面。 */
    private void showFilterMenu(android.view.View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        // 当前索引到底带不带兼容性标注？plugins.json 那份就不带（只有 stars / downloads /
        // category / npm），全是「⏳待定」。这时候「仅显示兼容」一点也筛不掉东西 ——
        // 与其让用户点了没反应，不如把原因写在菜单上并把它灰掉。
        boolean hasCompat = false;
        for (String[] it : items) {
            if (it.length > 3 && it[3] != null) {
                String c3 = it[3].trim();
                if (!c3.isEmpty() && !c3.contains("待定") && !c3.contains("未测")) {
                    hasCompat = true;
                    break;
                }
            }
        }
        pm.getMenu().add(0, 1, 0, hasCompat ? "仅显示兼容" : "仅显示兼容（本索引未标注兼容性）")
                .setCheckable(true).setChecked(filterIncompat && hasCompat).setEnabled(hasCompat);
        pm.getMenu().add(0, 2, 0, "隐藏已安装").setCheckable(true).setChecked(hideInstalled);
        // 分类来自市场源本身（it[4]），按实际出现的值动态列出 ——
        // 硬编码分类名会在上游改标题时静默失效
        java.util.List<String> cats = collectCategories();
        if (!cats.isEmpty()) {
            android.view.SubMenu sub = pm.getMenu().addSubMenu(0, 3, 0,
                    categoryFilter.isEmpty() ? "分类：全部" : "分类：" + categoryFilter);
            sub.add(0, 100, 0, "全部").setCheckable(true).setChecked(categoryFilter.isEmpty());
            for (int i = 0; i < cats.size(); i++) {
                String cat = cats.get(i);
                sub.add(0, 101 + i, 0, cat)
                        .setCheckable(true).setChecked(cat.equals(categoryFilter));
            }
        }
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 100) {
                categoryFilter = "";
            } else if (id >= 101) {
                categoryFilter = String.valueOf(item.getTitle());
            } else if (id == 3) {
                return false;                 // 子菜单标题本身：交给系统展开
            } else if (id == 1) {
                filterIncompat = !filterIncompat;
            } else if (id == 2) {
                hideInstalled = !hideInstalled;
            }
            if (id == 1 || id == 2) item.setChecked(id == 1 ? filterIncompat : hideInstalled);
            // 有筛选生效时按钮加个点，让用户知道列表被过滤过 ——
            // 「明明搜到了却看不到」是最容易被当成 bug 的体验
            if (anchor instanceof TextView) {
                ((TextView) anchor).setText(
                        (filterIncompat || hideInstalled || !categoryFilter.isEmpty())
                                ? "筛选 •" : "筛选");
            }
            if (mode == Mode.MARKET) refreshMarketView();
            return true;
        });
        pm.show();
    }

    /** 收集市场里实际出现的分类（去重、保持源顺序）。
     *  源顺序就是上游文档的编排顺序，比字母序更符合用户在网页上看到的样子。 */
    private java.util.List<String> collectCategories() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String[] it : items) {
            if (it.length > 4 && it[MarketCol.CATEGORY] != null && !it[MarketCol.CATEGORY].trim().isEmpty()
                    && !out.contains(it[MarketCol.CATEGORY].trim())) {
                out.add(it[MarketCol.CATEGORY].trim());
            }
        }
        return out;
    }

    /** 市场条目是否已经装过。市场里的名字是 owner/repo，已装列表里是 npm 包名，
     *  两者对不上号，所以做宽松比对（去掉 scope 和 owner 前缀后比尾段）。 */
    private boolean isAlreadyInstalled(String marketName) {
        if (marketName == null || installedNames.isEmpty()) return false;
        String tail = marketName.contains("/")
                ? marketName.substring(marketName.lastIndexOf('/') + 1) : marketName;
        tail = tail.toLowerCase(java.util.Locale.ROOT);
        for (String ins : installedNames) {
            String it = ins.toLowerCase(java.util.Locale.ROOT);
            String insTail = it.contains("/") ? it.substring(it.lastIndexOf('/') + 1) : it;
            if (insTail.equals(tail) || it.equals(tail) || insTail.contains(tail)
                    || tail.contains(insTail)) {
                return true;
            }
        }
        return false;
    }

    /** 安全解析 star 数（外部数据源格式变化不崩溃） */
    private static int safeStar(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void applySort() {
        final int sm = sortMode;
        Collections.sort(items, (a, b) -> {
            switch (sm) {
                case 0: // star 降序
                    int sa = safeStar(a[1]);
                    int sb = safeStar(b[1]);
                    return sb - sa;
                default: // 名称
                    return a[0].toLowerCase().compareTo(b[0].toLowerCase());
            }
        });
    }

    /** 判断某条目是否"不兼容"（兼容新旧索引格式的多种表述）：
     *  ❌不兼容 / ❌ 不兼容 / 不兼容 / 运行级不兼容 等一律命中；
     *  但"可用"类表述（✅可用/✅ 可用/可用）绝不误判。 */
    private static boolean isIncompat(String compat) {
        if (compat == null) return false;
        String c = compat.trim();
        if (c.startsWith("❌")) return true;
        return c.contains("不兼容") && !c.contains("可用");
    }

    /** 刷新市场视图：按 搜索词 + 仅兼容开关 过滤，再排序，更新列表与状态栏。
     * 基于全量 items 每次重新计算，保证各条件可叠加。 */
    @Override
    public void onResume() {
        super.onResume();
        // 重新可见时让列表重走一遍 onBindViewHolder。
        // 三条修法里最外层的一道保险：如果卡片消失是因为 adapter 的 view 状态坏了
        // （而不是 BlurView 的快照或尺寸问题），重 bind 能把它们拉回来；
        // 数据本身在内存里，这一步不查容器目录，成本只是几次 setText。
        if (adapter != null) {
            try {
                adapter.notifyDataSetChanged();
            } catch (Throwable ignored) {
            }
        }
        // 内置插件的注册可能被 dsh 的 initProfile 覆盖掉（首次启动时最常见）。
        // 打开插件页正是用户「发现插件不见了」的那一刻，在这里静默修掉最直接 ——
        // 否则用户只能看着自检报错，然后去终端手改 package.json，那不叫修好。
        final HarnessController hc = c;
        if (hc == null) return;
        new Thread(() -> {
            int fixed = hc.repairBuiltinPlugins();
            if (fixed <= 0) return;
            try {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    Toast.makeText(requireContext(),
                            "已修回 " + fixed + " 个内置插件的注册，点「重启 Web」生效",
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable ignored) {
            }
        }, "dsha-repair-builtin").start();
    }

    private void refreshMarketView() {
        if (mode != Mode.MARKET) return;
        applySort();
        java.util.List<String[]> filtered = new java.util.ArrayList<>();
        String q = searchQuery.trim();
        // 搜索跨「名称/描述/作者/分类/npm 包名」多词 AND 匹配 —— 判据在 MarketSearch
        // （原来只比名称一列：插件名多是 dsh-xxx 缩写，搜「主题」「语音」一条都出不来）
        String[] terms = MarketSearch.terms(q);
        int skipped = 0;
        int hidden = 0;
        for (String[] it : items) {
            if (!MarketSearch.matches(it, terms)) continue;
            // 仅兼容开关：滤掉不兼容条目（⏳待定/未测 保留，未知兼容性不误杀）
            if (filterIncompat && isIncompat(it[3])) {
                skipped++;
                continue;
            }
            // 隐藏已安装：市场里翻找新插件时，装过的会挤占视野
            if (hideInstalled && isAlreadyInstalled(it[0])) {
                hidden++;
                continue;
            }
            // 分类筛选
            if (!categoryFilter.isEmpty()
                    && (it.length <= 4 || !categoryFilter.equals(
                            it[4] == null ? "" : it[4].trim()))) {
                continue;
            }
            filtered.add(it);
        }
        adapter.setData(filtered, true);
        String hint = "共 " + filtered.size() + " 个插件";
        if (!q.isEmpty()) hint += "（搜索：\"" + q + "\"，含描述/作者）";
        if (filterIncompat) hint += " · 仅显示兼容（已滤 " + skipped + " 条不兼容）";
        if (hideInstalled) hint += " · 已隐藏 " + hidden + " 个装过的";
        if (!categoryFilter.isEmpty()) hint += " · 分类：" + categoryFilter;
        hint += " · 点击查看详情/安装" + cacheHint();
        say(hint);
    }

    /** 线程回调安全切主线程（Fragment detach 后不再崩溃）：未 attach 则丢弃 */
    private void runOnUiThreadSafely(java.lang.Runnable r) {
        if (!isAdded()) return;
        android.app.Activity a = getActivity();
        if (a == null) return;
        a.runOnUiThread(r);
    }

    /** 用 application context 在主线程弹 Toast：不依赖 Fragment 是否还 attached。
     *  用于「结论必须让用户看到」的场合（导入/导出结果），
     *  {@link #runOnUiThreadSafely} 在 detach 后会静默丢弃，不适合这种场合。 */
    private static void toastOnMain(android.content.Context appCtx, String msg) {
        if (appCtx == null || msg == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show());
    }

    private void showMarket() {
        if (!items.isEmpty()) {
            refreshMarketView();
            return;
        }
        say("正在拉取插件市场…");
        new Thread(() -> {
            // 首选 plugins.json（带 npm 包名映射，装的时候不用再去仓库猜），
            // 拉不到才退回 Markdown 表格 —— 退回逻辑在 fetchMarketRows 里
            List<String[]> list = c.fetchMarketRows();
            runOnUiThreadSafely(() -> {
                if (list.isEmpty()) {
                    say("市场拉取失败（网络不通？）");
                    return;
                }
                items.clear();
                items.addAll(list);
                refreshMarketView();
                fetchStars(items); // 异步批量拉真实 star 数
            });
        }).start();
    }

    /** 「注册了但没加载起来」的插件 → 原因。来自 3090 桥的 /app/plugins 上报
     *  （dsh 进程内的插件遍历 cordis registry 报的），只在本次 Web 运行期间有效。
     *  这是 App 侧唯一能拿到的「真的生效了吗」的证据 —— package.json 只说明注册了。 */
    private final java.util.Map<String, String> loadFailures = new java.util.HashMap<>();

    private void showInstalled() {
        final boolean hide = requireContext().getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("hide_builtin", false);
        new Thread(() -> {
            String[][] pl = c.listPlugins(hide);
            final java.util.Map<String, String> fails = c.pluginLoadFailures();
            // 记下已装插件名，供市场页「隐藏已安装」比对。
            // 放在这里是因为这是唯一真实拿到已装列表的地方 ——
            // 单独再查一次会和这里的结果漂移。
            installedNames.clear();
            for (String[] row : pl) {
                if (row.length > 0 && row[0] != null && !row[0].isEmpty()) {
                    installedNames.add(row[0]);
                }
            }
            runOnUiThreadSafely(() -> {
                loadFailures.clear();
                loadFailures.putAll(fails);
                installed.clear();
                if (pl == null || pl.length == 0) {
                    say("未发现已装插件（目录 " + String.join("/", HarnessController.PLUGIN_DIRS) + "）");
                    adapter.setData(new ArrayList<>(), false);
                    return;
                }
                for (String[] p : pl) installed.add(p);
                // 刷新后把已经不在列表里的选中项剔掉（比如刚被批量删掉的、或被
                // 「隐藏自带插件」筛掉的）——否则批量操作会作用在看不见的插件上
                if (multiMode) {
                    selected.retainAll(installedNames);
                    if (selected.isEmpty()) exitMulti();
                    else updateMultiBar();
                }
                adapter.setData(installed, false);
                say("已装 " + installed.size() + " 个插件 · 开关启用/禁用");
            });
        }).start();
    }

    private void exportPlugins() {
        say("正在导出插件…");
        final android.content.Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            String path = c.exportPlugins();
            // 导出路径是用户接下来要用的信息，不能因为切走页面就丢（Toast 走
            // application context），而 say() 是更新本页文本，留在 attached 分支。
            if (path == null) {
                toastOnMain(appCtx, "导出失败：打包出错");
            } else if ("NO_PLUGINS".equals(path)) {
                toastOnMain(appCtx, "没有可导出的插件");
            } else {
                toastOnMain(appCtx, "插件包已导出到 " + path);
            }
            runOnUiThreadSafely(() -> {
                if (path == null) {
                    say("导出失败（打包出错）");
                } else if ("NO_PLUGINS".equals(path)) {
                    say("没有已启用的插件可导出（先去市场安装或确认插件已启用）");
                } else {
                    say("已导出：" + path);
                }
            });
        }).start();
    }

    // ===== 多选（只在已装插件页生效）=====
    // 入口是**长按卡片**，不加常驻按钮：一年用几次的操作不该在页面上占位置。
    // 长按原来是「卸载这一个」——那件事现在由多选里的删除承担，语义更统一。
    private View multiBar;
    private TextView multiCount;
    private boolean multiMode = false;
    private final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();

    private void enterMulti(String first) {
        if (mode != Mode.INSTALLED) return;   // 市场页不参与多选
        multiMode = true;
        if (first != null) selected.add(first);
        if (multiBar != null) multiBar.setVisibility(View.VISIBLE);
        updateMultiBar();
        adapter.notifyDataSetChanged();
    }

    private void exitMulti() {
        multiMode = false;
        selected.clear();
        if (multiBar != null) multiBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void toggleSelect(String name) {
        if (!selected.remove(name)) selected.add(name);
        if (selected.isEmpty()) {
            exitMulti();       // 一个都不选就自动退出，省一次「退出」点击
            return;
        }
        updateMultiBar();
        adapter.notifyDataSetChanged();
    }

    private void selectAllInstalled() {
        for (String[] row : installed) {
            if (row.length > 0 && row[0] != null && !row[0].isEmpty()) selected.add(row[0]);
        }
        updateMultiBar();
        adapter.notifyDataSetChanged();
    }

    private void updateMultiBar() {
        if (multiCount != null) multiCount.setText("已选 " + selected.size() + " 个");
    }

    /** 选中的名字快照 —— 后台线程里不能直接读 selected（用户可能同时在改）。 */
    private java.util.List<String> selectedSnapshot() {
        return new java.util.ArrayList<>(selected);
    }

    private void batchToggle(boolean enable) {
        final java.util.List<String> names = selectedSnapshot();
        if (names.isEmpty()) return;
        say((enable ? "正在启用 " : "正在禁用 ") + names.size() + " 个插件…");
        final android.content.Context appCtx = requireContext().getApplicationContext();
        exitMulti();
        new Thread(() -> {
            String r = c.togglePlugins(names, enable);
            toastOnMain(appCtx, r);
            runOnUiThreadSafely(() -> {
                say(r);
                showInstalled();
            });
        }).start();
    }

    private void batchExport() {
        final java.util.List<String> names = selectedSnapshot();
        if (names.isEmpty()) return;
        say("正在导出 " + names.size() + " 个插件…");
        final android.content.Context appCtx = requireContext().getApplicationContext();
        exitMulti();
        new Thread(() -> {
            String r = c.exportSelectedPlugins(names);
            final String msg;
            if ("NO_SELECTION".equals(r)) msg = "没有选中插件";
            else if ("NOT_FOUND".equals(r)) msg = "选中的插件都找不到实体（可能已被卸载）";
            else if (r == null) msg = "导出失败：打包或写出出错";
            else msg = "已导出 " + names.size() + " 个插件到 " + r;
            toastOnMain(appCtx, msg);
            runOnUiThreadSafely(() -> say(msg));
        }).start();
    }

    private void batchDelete() {
        final java.util.List<String> names = selectedSnapshot();
        if (names.isEmpty()) return;
        // 卸载不可逆，二次确认里把名字全列出来 —— 多选最容易误点的就是它
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("卸载 " + names.size() + " 个插件")
                .setMessage(String.join("\n", names) + "\n\n卸载后需要重新安装才能恢复，确定？")
                .setPositiveButton("卸载", (d, w) -> {
                    say("正在卸载 " + names.size() + " 个插件…");
                    final android.content.Context appCtx = requireContext().getApplicationContext();
                    exitMulti();
                    new Thread(() -> {
                        String r = c.removePlugins(names);
                        toastOnMain(appCtx, r);
                        runOnUiThreadSafely(() -> {
                            say(r);
                            showInstalled();
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出单个插件到 Download/DSHA/插件/（单文件）。
     *  结论 Toast + 状态行双报：导出路径是用户接下来要用的信息，不能因为切走页面就丢。 */
    private void exportOne(String name) {
        say("正在导出 " + name + " …");
        final android.content.Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            String r = c.exportOnePlugin(name);
            final String msg;
            if ("BAD_NAME".equals(r)) {
                msg = "插件名不合法，没法当文件名用";
            } else if ("NOT_FOUND".equals(r)) {
                msg = "没找到 " + name + " 的实体（可能已被卸载）";
            } else if (r == null) {
                msg = "导出失败：打包或写出出错";
            } else {
                msg = "已导出到 " + r;
            }
            toastOnMain(appCtx, msg);
            runOnUiThreadSafely(() -> say(msg));
        }).start();
    }

    private void importPlugins() {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        // 类型放开：格式由文件头判定（见 ArchiveProbe），选择器的作用只是别把文件挡住。
        // 原来写死 application/gzip —— zip、未压缩 tar、以及被各种转存工具改过 MIME 的
        // 文件在选择器里全是灰的，用户根本选不中，看起来像「导入按钮没反应」。
        intent.setType("*/*");
        intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{
                "application/gzip", "application/x-gzip", "application/x-tar",
                "application/x-compressed-tar", "application/zip",
                "application/x-zip-compressed", "application/octet-stream",
        });
        startActivityForResult(intent, 1001);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == android.app.Activity.RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri == null) return;
            say("正在读取并识别插件包…");
            // 拷贝 + 解包可能要几十秒，用户很容易在这期间离开插件页。原来后台线程里
            // 直接用 requireContext()：detach 后抛 IllegalStateException，被外层
            // catch (Exception) 吞掉，结果是导入既没做成、也没有任何提示。
            // 先在 UI 线程取好 application context。
            final android.content.Context appCtx = requireContext().getApplicationContext();
            new Thread(() -> {
                java.io.File tmp = new File(appCtx.getCacheDir(), "plugin-import-upload.bin");
                String[] r;
                try {
                    try (java.io.InputStream in = appCtx.getContentResolver().openInputStream(uri);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while (in != null && (n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    r = c.importArchive(tmp);
                } catch (Exception e) {
                    r = new String[]{"ERR", "读取所选文件失败：" + e.getMessage()};
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                final boolean ok = "OK".equals(r[0]);
                final String msg = r[1] == null ? "（无结果）" : r[1];
                // Toast 先发（走 application context + 主 Looper，一定送达）：
                // 结论不该因为用户切走页面就消失，这是「静默失败」的老毛病
                toastOnMain(appCtx, ok ? "导入完成" : "导入失败");
                runOnUiThreadSafely(() -> {
                    say(msg.replace('\n', ' '));
                    if (ok) showInstalled();
                    // 结果信息量大（装了哪些、跳过哪些、为什么），Toast 会截断 → 用对话框
                    showCopyableResult(ok ? "导入完成" : "导入失败", msg);
                });
            }).start();
        }
    }

    /** 详情弹窗：star/作者/更新日期 + 完整描述 + README + 安装按钮。 */
    private void showDetail(String[] it) {
        String owner = it[MarketCol.OWNER];
        String repo = it[MarketCol.URL].endsWith("/") ? "" : it[MarketCol.URL].substring(it[MarketCol.URL].lastIndexOf('/') + 1);

        // 三路异步都会回来改这个弹窗（仓库信息、描述翻译、README），所以正文统一由
        // detailMessage 组装、各自只改自己那一格 —— 否则谁后回来谁把对方的成果覆盖掉。
        final String[] star = {it[MarketCol.STARS]};
        final String[] who = {owner};
        final String[] date = {""};
        final String[] readme = {null};
        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];

        // 正文自己放进 ScrollView，不用 setMessage —— 那个在长文本下会把按钮挤出屏幕、
        // 内容也看不全（用户反馈「详情显示不完整」）。现在描述之后还要接 README，更得能滑。
        final TextView body = new TextView(requireContext());
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        body.setTextIsSelectable(true);          // 长按可选可复制
        body.setLineSpacing(0, 1.15f);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(body);
        body.setText(detailMessage(it, star[0], who[0], date[0], readme[0]));

        final Runnable render = () -> {
            android.app.AlertDialog d = holder[0];
            if (d != null && d.isShowing()) {
                body.setText(detailMessage(it, star[0], who[0], date[0], readme[0]));
            }
        };

        holder[0] = new android.app.AlertDialog.Builder(requireContext())
                .setTitle(it[MarketCol.NAME])
                .setView(scroll)
                .setPositiveButton("安装", (d, w) -> startAutoInstall(it, owner, repo))
                .setNeutralButton("复制仓库链接", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", it[MarketCol.URL]));
                    DshaSnack.show(PluginFragment.this, "链接已复制");
                })
                .setNegativeButton("关闭", null)
                .show();

        // 异步拉取更新日期/作者/star 刷新弹窗
        if (!owner.isEmpty() && !repo.isEmpty()) {
            new Thread(() -> {
                String[] info = c.fetchRepoInfo(owner, repo);
                if (info == null) return;
                runOnUiThreadSafely(() -> {
                    star[0] = info[1];
                    who[0] = info[2].isEmpty() ? owner : info[2];
                    date[0] = info[0].isEmpty() ? "未知" : info[0];
                    render.run();
                });
            }).start();
        }

        // 异步拉 README：市场索引里只有一句话描述，真正想知道「这插件怎么用」得看它
        if (!owner.isEmpty() && !repo.isEmpty()) {
            new Thread(() -> {
                String md = fetchReadme(owner, repo);
                if (md == null) return;
                runOnUiThreadSafely(() -> {
                    readme[0] = md;
                    render.run();
                });
            }, "dsha-readme").start();
        }

    }

    /** README 最多显示这么多字符：再长弹窗里也读不完，还白占内存。 */
    private static final int README_MAX = 8000;

    /** 拉仓库 README。先试 raw（不限流），大小写两种常见写法都试一遍。 */
    private static String fetchReadme(String owner, String repo) {
        // HEAD 指向默认分支，不必猜 main 还是 master
        String[] candidates = {
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/README.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/readme.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/README.MD",
        };
        for (String u : candidates) {
            String s = httpGetText(u, README_MAX);
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return null;
    }

    private static String httpGetText(String url, int maxChars) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "DSHA");
            if (conn.getResponseCode() != 200) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                // 按字节上限收：README 里中文多，maxChars*3 足够覆盖，也不会无限吃内存
                while ((n = in.read(buf)) > 0 && bos.size() < maxChars * 3) {
                    bos.write(buf, 0, n);
                }
            }
            String s = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            return s.length() > maxChars ? s.substring(0, maxChars) + "\n\n…（README 太长，"
                    + "余下内容见仓库）" : s;
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 详情弹窗的正文。三路异步共用一处组装，避免各自 setText 相互覆盖。 */
    private String detailMessage(String[] it, String star, String owner, String date,
                                String readme) {
        StringBuilder sb = new StringBuilder();
        sb.append("⭐ ").append(star).append(" · 👤 ").append(owner.isEmpty() ? "?" : owner)
                .append("\n兼容性：").append(it[3])
                .append("\n分类：").append(it[4])
                .append("\n\n");
        sb.append(it[5]);
        sb.append("\n\n🔗 ").append(it[6])
                .append("\n\n📅 最近更新：")
                .append(date == null || date.isEmpty() ? "查询中…" : date);
        sb.append("\n\n──────────\n");
        if (readme == null) {
            sb.append("README 加载中…");
        } else {
            sb.append(readme);
        }
        return sb.toString();
    }

    /** 批量异步拉取市场列表 star 数（GitHub search API，每批 ~80 仓库）。
     *  注意匿名 API 限流 10 次/分钟 + 1700+ 条全拉需要 22 批 × 6s ≈ 2 分钟，且几乎必然 403。
     *  → 只刷新【前 1 批】（当前页可见的 80 条，1 个请求，限流内轻松完成）；
     *    其余条目保留索引自带 star。遇 403/失败立即停止（不浪费配额）。 */
    private void fetchStars(java.util.List<String[]> items) {
        if (items == null || items.isEmpty()) return;
        new Thread(() -> {
            StringBuilder q = new StringBuilder("q=");
            int n = 0;
            java.util.List<Integer> idxs = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(items.size(), 80); i++) {
                String u = items.get(i)[6].replace("https://github.com/", "").replace("http://github.com/", "");
                if (u.contains("/") && !u.startsWith("http")) {
                    if (n > 0) q.append("+");
                    q.append("repo:").append(u);
                    idxs.add(i);
                    n++;
                }
            }
            if (n == 0) return;
            String uApi = "https://api.github.com/search/repositories?" + q + "&per_page=100";
            String[] urls = {
                    HarnessController.gitHubProxy(uApi),
                    uApi,
                    "https://ghfast.top/" + uApi
            };
            for (String u : urls) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    conn.setRequestProperty("User-Agent", "DSHA/" + c.getVersionNameForUa());
                    if (conn.getResponseCode() != 200) {
                        conn.disconnect();
                        continue; // 403 = 限流 → 直接放弃，不再重试其他源
                    }
                    StringBuilder sb = new StringBuilder();
                    String l;
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                            conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    while ((l = br.readLine()) != null) {
                        sb.append(l);
                        if (sb.length() > 400000) break;
                    }
                    conn.disconnect();
                    org.json.JSONObject j = new org.json.JSONObject(sb.toString());
                    org.json.JSONArray arr = j.optJSONArray("items");
                    if (arr != null) {
                        for (int k = 0; k < arr.length(); k++) {
                            org.json.JSONObject o = arr.optJSONObject(k);
                            String full = o.optString("full_name", "");
                            long star = o.optLong("stargazers_count", 0);
                            for (int idx : idxs) {
                                String fu = items.get(idx)[6].replace("https://github.com/", "").replace("http://github.com/", "");
                                if (full.equalsIgnoreCase(fu)) {
                                    items.get(idx)[1] = String.valueOf(star);
                                    break;
                                }
                            }
                        }
                    }
                    break; // 成功即止
                } catch (Exception ignored) {
                }
            }
            runOnUiThreadSafely(() -> {
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        }).start();
    }

    /** 一键安装：先**预检**再装。
     *
     *  为什么加预检：社区标准（dsh-community-standard v0.15）把这条列为生态三大裂缝
     *  之一 ——「装上才知道炸：装之前没人能回答这个插件能不能跑，
     *  唯一的报错方式是崩溃」。安装一个插件可能要等几分钟，
     *  让用户等完再看一大段 pnpm 堆栈是最差的体验。
     *
     *  预检只发一两个 HTTP 请求（读仓库 package.json + 查 registry），很快。
     *  结论明确「装不上」时给出原因并让用户自己决定要不要硬试，而不是替他放弃 ——
     *  规范 §3 也要求「展示但禁用，不要隐藏」。 */
    /** 后台装一个插件并把结果显示出来（市场里几条路都用它，别再各写一份线程）。
     *  必须在 UI 线程调用 —— say 与 showInstallResult 都只能在 UI 线程跑。 */
    private void installInBackground(final String pkg, final String display, final String spec) {
        say("正在安装 " + pkg + " …");
        new Thread(() -> {
            String out = c.installPlugin(pkg, spec);
            runOnUiThreadSafely(() -> showInstallResult(pkg, display, out));
        }, "dsha-install").start();
    }

    /**
     * 市场条目的安装入口：链接先用 {@link GitHubRef} 正确解析。
     *
     * <p>原来这里是 {@code it[6].substring(it[6].lastIndexOf('/') + 1)} —— 拿 URL 的
     * <b>最后一段</b>当仓库名。对普通条目（{@code github.com/o/r}）没问题，但市场索引里有
     * 不少 monorepo 条目（{@code github.com/o/r/tree/main/plugins/x}），它们会被解析成
     * 仓库 {@code o/x} —— 那个仓库根本不存在，于是必然装不上。这是「市场里很多东西装不了」
     * 的另一半原因。
     */
    private void installMarketItem(String[] it) {
        GitHubRef gr = GitHubRef.parse(it[MarketCol.URL]);
        if (gr == null) {
            // 不是 GitHub 链接，但可能是别的 pnpm 来源（jsr: / gitlab: / 远程 tarball /
            // 本地目录或 .tgz / owner/repo#ref …）——直接把 spec 交给 dsh
            if (PluginSpec.isUsable(it[MarketCol.URL])) {
                installBySpec(it[MarketCol.NAME], it[MarketCol.URL]);
                return;
            }
            say("这条市场记录的链接解析不了：" + it[MarketCol.URL]);
            DshaSnack.showLong(PluginFragment.this, "链接格式不认识，装不了");
            return;
        }
        if (!gr.hasSubdir()) {
            startAutoInstall(it, gr.owner, gr.repo);
            return;
        }
        // monorepo 子目录：整条链接交给 installFromGithubUrl —— 它认子目录，
        // 并会在容器里 clone + 装依赖 + 构建（几分钟级，所以先把话说清）
        say("正在安装 " + it[MarketCol.NAME] + "（仓库子目录插件，要在容器里构建，请耐心等几分钟）…");
        final android.content.Context appCtx = requireContext().getApplicationContext();
        final String url = it[MarketCol.URL], display = it[MarketCol.NAME];
        new Thread(() -> {
            String r = c.installFromGithubUrl(url);
            final String msg = r == null || r.isEmpty() ? "无输出" : r;
            toastOnMain(appCtx, "安装流程结束：" + display);
            runOnUiThreadSafely(() -> {
                say(msg.replace('\n', ' '));
                showInstalled();
                showCopyableResult("安装结果：" + display, msg);
            });
        }).start();
    }

    /** 按任意 pnpm 来源直接安装（npm / jsr: / gitlab: / 远程 tarball / 本地路径 …）。 */
    private void installBySpec(String display, String spec) {
        say("正在安装 " + display + "（" + PluginSpec.describe(PluginSpec.classify(spec)) + "）…");
        final android.content.Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            String r = c.installPlugin(spec);
            final String msg = r == null || r.isEmpty() ? "无输出" : r;
            toastOnMain(appCtx, "安装流程结束：" + display);
            runOnUiThreadSafely(() -> {
                say(msg.replace('\n', ' '));
                showInstalled();
                showCopyableResult("安装结果：" + display, msg);
            });
        }).start();
    }

    private void startAutoInstall(String[] it, String owner, String repo) {
        final String display = it[MarketCol.NAME];
        say("正在预检 " + display + " …");
        new Thread(() -> {
            final String spec = "github:" + owner + "/" + repo;
            // 索引里现成的 npm 包名优先（第 8 列，只有 plugins.json 那条路有）——
            // 省掉一次多源网络探测。fetchNpmName 要去仓库读 package.json、多镜像回退，
            // 常常查不到，「没查到 npm 包名」那条提示就是它；plugins.json 的映射每天由 CI 刷新。
            //
            // 刻意不复用 it[MarketCol.CATEGORY]：Markdown 那条路的 it[MarketCol.CATEGORY] 是**分类**（"ui"、"tools"），
            // 而分类名恰好也是合法的包名形态 —— 拿它当包名会去装一个叫 "ui" 的包。
            String fromIndex = (it.length > 7 && it[MarketCol.NPM] != null
                    && PluginSpec.isPackageName(it[MarketCol.NPM].trim())) ? it[MarketCol.NPM].trim() : null;
            String hint = fromIndex != null ? fromIndex : c.fetchNpmName(owner, repo);
            final String[] pre = c.precheckForMarket(spec, hint);
            final String verdict = pre[0], why = pre[1];
            final String pkg = pre[2] != null ? pre[2] : (hint != null ? hint : spec);

            // 已弃用的插件不拦着不让装 —— 那是用户自己的选择；但要先把冲突说清楚。
            // 清单是 DeprecatedPlugins，跟恢复流程的静默补装共用一份（那边直接不装：
            // 程序替用户做决定和用户自己点，是两回事）。
            // 包名和仓库名都比一遍：清单里存的是包名，而这个项目栽过「仓库名≠包名」的坑。
            final String deprecated = DeprecatedPlugins.isDeprecated(pkg) ? pkg
                    : DeprecatedPlugins.isDeprecated(repo) ? repo : null;
            if (deprecated != null) {
                runOnUiThreadSafely(() -> {
                    say("⚠️ 已弃用：" + display);
                    new android.app.AlertDialog.Builder(requireContext())
                            .setTitle("⚠️ 已被内置功能接替：" + display)
                            .setMessage(DeprecatedPlugins.reason(deprecated)
                                    + "\n\n两边改造同一批界面元素，同时启用的表现是抽屉和浮层出两份、"
                                    + "点一下响应两次。"
                                    + "\n\n仓库：\n" + it[MarketCol.URL])
                            .setPositiveButton("仍然安装",
                                    (d, w) -> installInBackground(pkg, display, spec))
                            .setNegativeButton("算了", null)
                            .show();
                });
                return;
            }

            // 一路顺畅：不打扰用户，直接装
            if ("ok".equals(verdict)) {
                runOnUiThreadSafely(() -> say("✅ " + display + " 可安装，正在装…"));
                String out = c.installPlugin(pkg, spec);
                runOnUiThreadSafely(() -> showInstallResult(pkg, display, out));
                return;
            }
            // 其余三态：把结论摆出来，让用户决定
            runOnUiThreadSafely(() -> {
                String badge = "blocked".equals(verdict) ? "🔴 大概装不上"
                        : "build".equals(verdict) ? "🟡 需要现场构建" : "⚪ 情况不明";
                say(badge + "：" + display);
                android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(requireContext())
                        .setTitle(badge + "：" + display)
                        .setMessage(why + "\n\n仓库：\n" + it[MarketCol.URL])
                        // 即使预检说装不上也保留「仍然试试」—— 预检是启发式的，
                        // 不该替用户做最终决定（万一作者刚发布、或仓库结构特殊）
                        .setPositiveButton("仍然安装",
                                (d, w) -> installInBackground(pkg, display, spec))
                        .setNeutralButton("复制仓库链接", (d, w) -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    requireContext().getSystemService(
                                            android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("url", it[MarketCol.URL]));
                                DshaSnack.show(PluginFragment.this, "链接已复制");
                            }
                        })
                        .setNegativeButton("算了", null);
                b.show();
            });
        }, "dsha-precheck").start();
    }

    /** 安装结果（成功/失败）弹窗 + 重启 WebUI 按钮 */
    /**
     * 可滚动 + 可选中的消息视图。
     *
     * <p>{@code setMessage} 的文本既不能滚动也不能选中 —— 而安装失败的输出动辄几十行
     * pnpm 堆栈，用户看不全，更没法复制去提 issue。那份输出是他手上唯一的线索。
     */
    private View buildSelectableMessage(String text) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        TextView tv = new TextView(requireContext());
        tv.setText(text == null ? "无输出" : text);
        tv.setTextIsSelectable(true);
        tv.setTextSize(12);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        android.widget.ScrollView sv = new android.widget.ScrollView(requireContext());
        sv.addView(tv);
        return sv;
    }

    /** 把文本放进剪贴板。 */
    private void copyToClipboard(String text, String what) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DSHA", text == null ? "" : text));
            Toast.makeText(requireContext(), "已复制" + (what == null ? "" : what),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            DshaSnack.show(PluginFragment.this, "复制失败：" + t);
        }
    }

    /** 结果对话框：内容可滚动、可选中，还带一个「复制」按钮。 */
    private void showCopyableResult(String title, String msg) {
        final String text = msg == null || msg.isEmpty() ? "无输出" : msg;
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(buildSelectableMessage(text))
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制", (d, w) -> copyToClipboard(text, "全部输出"))
                .show();
    }

    private void showInstallResult(String pkg, String display, String out) {
        boolean ok = out != null && out.contains("INSTALL_EXIT=0");
        say((ok ? "✅ 安装成功 " : "❌ 安装失败 ") + display + (ok ? "，刷新页面即可生效" : ""));
        final String text = out == null ? "无输出" : out;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(requireContext())
                .setTitle((ok ? "✅ 安装成功：" : "❌ 安装失败：") + display)
                // 失败时那几十行输出是用户唯一的线索：要能滚动、能选中、能一键复制
                .setView(buildSelectableMessage(text))
                .setNeutralButton("复制", (d, w) -> copyToClipboard(text, "安装输出"))
                .setNegativeButton("关闭", null);
        if (ok) {
            b.setPositiveButton("重启 WebUI", (d, w) -> {
                    // 1.5s 延迟回调期间用户可能已离开本页：全程用 applicationContext，
                    // 不能在回调里再 requireContext()（fragment detach 后必抛异常闪退）
                    final android.content.Context app = requireContext().getApplicationContext();
                    android.content.Intent stop = new android.content.Intent(app, HarnessService.class)
                            .setAction(HarnessService.ACTION_STOP);
                    app.startService(stop);
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        android.content.Intent i = new android.content.Intent(app, HarnessService.class);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            app.startForegroundService(i);
                        } else {
                            app.startService(i);
                        }
                        if (isAdded()) say("WebUI 已重启");
                    }, 1500);
                });
        } else {
            // 装失败时「重启 WebUI」没有意义 —— 用户真正需要的是把 profile 退回装之前。
            // 今天这批故障（自指依赖 ELOOP、装成 monorepo 管理包、缺构建产物成空壳）
            // 全都是「装完之后 profile 被写坏」，退回去比继续在坏状态上试下一个插件有用。
            b.setPositiveButton("撤销这次安装", (d, w) -> undoInstall());
        }
        b.show();
    }

    /** 撤销上一次插件安装：还原存档点，然后刷新列表。 */
    private void undoInstall() {
        say("正在退回到安装之前…");
        final android.app.Activity act = getActivity();
        new Thread(() -> {
            final String r = c.undoLastPluginInstall();
            if (act == null) return;
            act.runOnUiThread(() -> {
                // 1.5s~几十秒的后台操作期间用户可能已经离开本页，
                // detach 之后再 requireContext() 必抛异常闪退
                if (!isAdded()) return;
                showCopyableResult("撤销结果", r);
                showInstalled();
            });
        }, "dsha-undo").start();
    }



    private class PluginAdapter extends RecyclerView.Adapter<PluginAdapter.VH> {

        private List<String[]> data = new ArrayList<>();
        private boolean isMarket = true;

        void setData(List<String[]> d, boolean market) {
            data = d;
            isMarket = market;
            notifyDataSetChanged();
        }

        @NonNull
        /** 回滚开关时抑制监听器，避免一次点击被放大成两次相反的操作。 */
        private boolean suppressToggle = false;

        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_plugin, parent, false);
            // 列表项要自己套一次玻璃。
            // DshaGlass.apply 是在 onFragmentViewCreated 时对整棵树跑的，那一刻 RecyclerView
            // 里一个 item 都还没有 —— item 是滚到才 inflate 的，所以插件卡片一直是纯色，
            // 而别处布局里写死的卡片都有玻璃（反馈：「插件市场的卡片为什么没有玻璃材质」）。
            // 这里只走一张卡这么小的子树，成本可以忽略；底图晚到的情况由
            // ensureBackdrop 完成后那次 apply(decorView) 兜住（那时 item 已经 attach）。
            DshaGlass.apply(v);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            String[] it = data.get(pos);
            if (isMarket) {
                h.name.setText(it[MarketCol.NAME]);
                h.desc.setText(MarketCol.at(it, MarketCol.DESC));
                h.status.setText("⭐ " + MarketCol.at(it, MarketCol.STARS)
                        + " · 👤 " + (MarketCol.at(it, MarketCol.OWNER).isEmpty()
                                ? "?" : MarketCol.at(it, MarketCol.OWNER))
                        + " · " + MarketCol.at(it, MarketCol.COMPAT)
                        + " · " + MarketCol.at(it, MarketCol.CATEGORY));
                h.installBtn.setVisibility(View.VISIBLE);
                h.installBtn.setText("安装");
                h.switchView.setVisibility(View.GONE);
                h.itemView.setOnClickListener(v -> showDetail(it));
                h.installBtn.setOnClickListener(v -> installMarketItem(it));
            } else {
                h.name.setText(it[MarketCol.Installed.NAME]);
                // 简介：内置插件用我们写的中文，其余读它 package.json 的 description。
                // 以前这里写死空串 —— 列表上只有一串包名，用户根本不知道每个插件干什么。
                h.desc.setText(MarketCol.at(it, MarketCol.Installed.DESC));
                boolean enabled = "启用".equals(it[MarketCol.Installed.STATE]);
                // 注册了不等于加载成功：PENDING（inject 的服务没提供者）不报错，插件就静静地
                // 什么都不做。这一行是用户唯一能看到真实原因的地方，别让它退化成"已启用"。
                String fail = loadFailures.get(it[0]);
                if (enabled && fail != null && !fail.isEmpty()) {
                    h.status.setText("⚠ 已启用但没加载起来（" + fail + "）");
                } else {
                    h.status.setText(enabled ? "已启用" : "已禁用");
                }
                // 已装卡片复用同一个按钮做「导出」——不额外加控件，卡片布局一行未改。
                // 导出的是单个插件、单个文件，落在 Download/DSHA/插件/。
                h.installBtn.setVisibility(View.VISIBLE);
                h.installBtn.setText("导出");
                h.installBtn.setOnClickListener(v -> exportOne(it[0]));
                h.switchView.setVisibility(View.VISIBLE);
                h.itemView.setOnClickListener(null); // 防止 RecyclerView 复用到市场的点击监听
                // 长按进入多选（原来长按是「卸载这一个」——那件事现在归多选里的删除，
                // 语义更统一，也省掉一个只为单个插件存在的对话框）
                h.itemView.setOnLongClickListener(v -> {
                    enterMulti(it[0]);
                    return true;
                });
                if (multiMode) {
                    // 多选态：点卡片切换选中；用透明度标示未选中（不新增 drawable、
                    // 也不动卡片布局）；开关与导出按钮暂时锁住，避免误触
                    boolean sel = selected.contains(it[0]);
                    h.itemView.setOnClickListener(v -> toggleSelect(it[0]));
                    h.itemView.setAlpha(sel ? 1f : 0.45f);
                    h.status.setText(sel ? "✓ 已选中" : (enabled ? "已启用" : "已禁用"));
                    h.switchView.setEnabled(false);
                    h.installBtn.setEnabled(false);
                } else {
                    h.itemView.setOnClickListener(null); // 防止 RecyclerView 复用到市场的点击监听
                    h.itemView.setAlpha(1f);
                    h.switchView.setEnabled(true);
                    h.installBtn.setEnabled(true);
                }
                h.switchView.setOnCheckedChangeListener(null);
                h.switchView.setChecked(enabled);
                h.switchView.setOnCheckedChangeListener((btn, checked) -> {
                    // suppressToggle：回滚时 setChecked 会再次触发这个监听器，
                    // 那会把用户的一次点击变成两次相反的操作（原来就有这个隐患，
                    // 只是同步执行时不容易看出来）。
                    if (suppressToggle) return;
                    // 切换要读写 profile 的 cordis.patch.yml，还要读插件自己的 patch 抠
                    // loader 行 id —— 好几次文件 IO，放在 UI 线程上是 ANR 的料。
                    // 开关先停在用户点的位置（乐观），失败再回滚。
                    btn.setEnabled(false);
                    final android.content.Context appCtx = requireContext().getApplicationContext();
                    new Thread(() -> {
                        final boolean ok = c.togglePlugin(it[0], checked);
                        final String why = ok ? "" : c.getLastToggleError();
                        runOnUiThreadSafely(() -> {
                            btn.setEnabled(true);
                            if (ok) {
                                it[1] = checked ? "启用" : "禁用";
                                h.status.setText(checked ? "已启用" : "已禁用");
                                Toast.makeText(appCtx, it[0]
                                                + (checked ? " 已启用，刷新页面即可生效" : " 已禁用"),
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                suppressToggle = true;
                                btn.setChecked(!checked);
                                suppressToggle = false;
                                // 把真实原因摊开：以前一律显示「操作失败」，用户无从下手
                                Toast.makeText(appCtx, why.isEmpty() ? "操作失败" : why,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }).start();
                });
            }
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView name, desc, status;
            android.widget.Switch switchView;
            TextView installBtn;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.pluginName);
                desc = v.findViewById(R.id.pluginDesc);
                status = v.findViewById(R.id.pluginStatus);
                switchView = v.findViewById(R.id.pluginSwitch);
                installBtn = v.findViewById(R.id.pluginInstall);
            }
        }
    }
}
