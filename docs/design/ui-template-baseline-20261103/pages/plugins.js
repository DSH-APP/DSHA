/*
 * 插件标签页 / 插件安装页 / 技能页。
 * 来源：
 *   插件页  res/layout/fragment_plugins.xml、plugin_list_header.xml、item_plugin.xml、item_plugin_note.xml、
 *           plugin_detail_pane.xml、plugin_list_footer.xml、values/plugin_header.xml；
 *           ui/PluginFragment.java（onCreateView 双栏、render、bindStatusStrip、bindCard*、CardDensity）、
 *           util/PluginHeaderStrip.java、util/PluginCardState.java、ui/PluginCountBadge.java、
 *           ui/PluginWaveProgress.java、ui/PluginTaskProgress.java、ui/PluginFlowRow.java。
 *   安装页  res/layout/activity_plugin_install.xml、ui/PluginInstallActivity.java、ui/PluginNavigation.java。
 *   技能页  ui/SkillsFragment.java（SPLIT_DP=600、smallestScreenWidthDp<600）、ui/CardPage.java、ui/InfoHint.java、
 *           ui/WorkspaceFragment.java（入口）、ui/SettingsFragment.java（双栏 320dp 列表）、MainActivity.updateTitle。
 * 布局判据：
 *   - 插件页双栏 = SettingsPanes.twoPane = screenWidthDp >= 840（env.w840）：左列表 COMPACT 密度 + 1dp 线 +
 *     右侧固定 380dp 详情窗格（DETAIL 密度，复用 item_plugin）。市场页签隐藏详情窗格与竖线，列表占满。
 *     更窄时单栏 LIST 密度，卡片内展开属性表。
 *   - 技能页并排 = 本页实际宽度 >= 600dp：宽度 = fragment 宽（平板减 81dp 左栏）；w840 时 Skills 位于设置双栏
 *     右侧详情窗格，再减 320dp 列表 + 1dp 线。导入按钮仅在并排且 sw>=600（env.tablet）时按内容宽度。
 *   - 安装页是独立 Activity，无宽度分支（原生按钮在平板上铺满整宽）。
 * 触控：卡片内复制钮 36dp、提示条动作/状态条动作/筛选胶囊/社区外链 40dp，均为原生尺寸，标 data-dense。
 * 示例数据均为虚构（example-org / @example/*），不含真实路径隐私或密钥。
 */
(function () {
  const { h } = DSHA;
  const css = `
  .pl{padding:var(--page-top) var(--page-pad) var(--page-bottom)}
  .pl-split{flex:1;min-height:0;display:flex}
  .pl-split>.l{flex:1;min-width:0;display:flex;flex-direction:column}
  .pl-split>.line{flex:none;width:1px;background:var(--line)}
  .pl-split>.r{flex:none;width:380px;display:flex;flex-direction:column}
  .pl-titlerow{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px}
  .pl-titlerow .pg-title{margin:0}
  .pl-seg{display:flex;padding:3px;border-radius:13px;background:var(--raised);gap:2px}
  .pl-seg>button{min-height:44px;padding:0 12px;border:1px solid transparent;border-radius:9px;background:transparent;color:var(--text-2);font-size:13px;font-weight:700;display:inline-flex;align-items:center;gap:5px;white-space:nowrap}
  .pl-seg>.on{background:var(--surface);border-color:var(--line);color:var(--accent)}
  .pl-n{display:inline-grid;place-items:center;min-width:16px;height:16px;padding:0 5px;border-radius:999px;background:var(--accent-soft);color:var(--accent);font-size:10.5px;font-weight:700}
  .pl-hcard{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);padding:12px;margin-top:10px}
  .pl-hhead{display:flex;align-items:center;min-height:32px;padding-left:2px}
  .pl-hhead .t{flex:1;min-width:0;font-size:15px;font-weight:700}
  .pl-web{min-height:40px;margin-right:-6px;padding:0 8px;border:0;background:none;border-radius:12px;color:var(--accent);font-size:13px;font-weight:700;display:inline-flex;align-items:center;gap:4px;white-space:nowrap}
  .pl-web .i{font-size:16px}
  .pl-in{position:relative;margin-top:8px}
  .pl-in .input{min-height:44px;padding-right:48px}
  .pl-in .input>.i{font-size:18px;color:var(--muted)}
  .pl-in .input>span:not(.i){flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-in .ibtn{position:absolute;right:0;top:50%;transform:translateY(-50%);border-radius:12px;font-size:20px}
  .pl-hint{display:flex;gap:6px;align-items:flex-start;margin-top:6px;padding:0 2px;font-size:12px;line-height:1.45;color:var(--muted)}
  .pl-hint .i{font-size:14px;margin-top:1px}
  .pl-hint.good{color:var(--good)}
  .pl-act{display:flex;align-items:center;gap:8px;margin-top:10px}
  .pl-act .btn.pri{flex:1;min-width:96px;min-height:44px;padding:0 16px}
  /* 原生：胶囊 wrap_content(max 200dp) 先量，按钮 weight=1 + minWidth 96dp；内容宽 < 304dp（如 320dp 窄屏）时
     按钮会被挤出卡片右缘裁切。预览让胶囊收缩省略以免溢出，属原生问题。 */
  .pl-act .pl-chip{flex:0 1 auto;min-width:0}
  .pl-chip{display:inline-flex;align-items:center;gap:6px;min-height:40px;max-width:200px;padding:0 10px;border-radius:13px;border:1px solid var(--line);background:var(--surface);color:var(--text);font-size:13px;font-weight:700}
  .pl-chip>span:not(.i){min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-chip .i{font-size:16px;color:var(--text-2)}
  .pl-srow{display:flex;align-items:center;gap:8px}
  .pl-srow .input{flex:1;min-width:0;min-height:44px}
  .pl-srow .input>.i{font-size:18px;color:var(--muted)}
  .pl-srow .input>span:not(.i){min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-srow .btn{min-height:44px;padding:0 12px;font-size:13px}
  .pl-srow .btn .i{color:var(--accent)}
  .pl-flow{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin-top:8px}
  .pl-flow>.end{margin-left:auto}
  .pl-chk{display:inline-flex;align-items:center;gap:10px;min-height:40px;margin-left:-4px;padding:0 6px;font-size:13px;color:var(--text-2)}
  .pl-chk .box{width:18px;height:18px;border-radius:3px;border:2px solid var(--muted)}
  .pl-strip{margin:10px 0 6px;padding:0 4px 2px 12px;border-radius:13px;background:var(--raised)}
  .pl-strip.acc{background:var(--accent-soft)}
  .pl-strip .row{display:flex;align-items:center;min-height:42px}
  .pl-strip .ic{flex:none;width:18px;height:18px;display:grid;place-items:center;font-size:18px;color:var(--muted)}
  .pl-strip.acc .ic{color:var(--accent)}
  .pl-spin{width:16px;height:16px;border-radius:50%;border:2px solid currentColor;border-right-color:transparent}
  .pl-strip .cnt{flex:none;max-width:150px;margin-left:10px;font-size:12px;font-weight:700;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-strip .msg{flex:1;min-width:0;min-height:40px;margin-left:8px;font-size:12.5px;color:var(--text-2);display:flex;align-items:center}
  .pl-strip .msg>span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-strip .act{flex:none;min-height:40px;margin-left:6px;padding:0 12px;border-radius:13px;border:1px solid var(--line);background:var(--surface);color:var(--accent);font-size:13px;font-weight:700}
  .pl-wave{height:12px;margin-right:8px}
  .pl-wave svg{display:block;width:100%;height:12px}
  .pl-card{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);padding:14px 10px 4px 16px;margin-bottom:10px}
  .pl-card.compact{padding-bottom:14px}
  .pl-card.sel{border:2px solid var(--accent);padding:13px 9px 13px 15px}
  .pl-top{display:flex}
  .pl-glyph{flex:none;width:40px;height:40px;border-radius:10px;background:var(--raised);display:grid;place-items:center;font-size:22px;color:var(--accent)}
  .pl-glyph.neutral{color:var(--text-2)}
  .pl-words{flex:1;min-width:0;margin-left:12px;padding-top:1px}
  .pl-name{font-size:14px;font-weight:700;line-height:1.3;overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .pl-meta{display:flex;align-items:center;gap:8px;margin-top:5px;min-width:0}
  .pl-bdg{flex:0 1 auto;min-width:0;padding:3px 8px;border-radius:999px;background:var(--accent-soft);color:var(--accent);font-size:11.5px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-bdg.neutral{background:var(--raised);color:var(--text-2)}
  .pl-ver{flex:1;min-width:0;font-family:var(--mono);font-size:11.5px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-sw{flex:none;width:52px;height:48px;margin-top:-4px;display:grid;place-items:center}
  .pl-sw .t{position:relative;width:52px;height:32px;border-radius:16px;background:var(--raised);border:2px solid var(--muted)}
  .pl-sw .t::after{content:"";position:absolute;left:6px;top:6px;width:16px;height:16px;border-radius:50%;background:var(--muted)}
  .pl-sw.on .t{background:var(--accent);border-color:var(--accent)}
  .pl-sw.on .t::after{left:22px;top:2px;width:24px;height:24px;background:var(--on-accent)}
  .pl-sw.off{opacity:.38}
  .pl-desc{margin:10px 6px 0 0;font-size:13px;line-height:1.55;color:var(--text-2);overflow-wrap:anywhere}
  .pl-desc.c1,.pl-desc.c2{display:-webkit-box;-webkit-box-orient:vertical;overflow:hidden}
  .pl-desc.c1{-webkit-line-clamp:1}.pl-desc.c2{-webkit-line-clamp:2}
  .pl-desc.none{color:var(--muted)}
  .pl-st{display:flex;align-items:center;margin:8px 6px 0 0;min-width:0}
  .pl-st .s{flex:0 1 auto;min-width:0;margin-left:8px;font-size:12px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .pl-st .rt{flex:1;min-width:0;margin-left:10px;text-align:end;font-size:12px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .t-good{color:var(--good)}.t-acc{color:var(--accent)}.t-warn{color:var(--warn)}.t-bad{color:var(--bad)}.t-muted{color:var(--muted)}
  .pl-prog{height:4px;margin:10px 6px 0 0;border-radius:2px;background:var(--raised);overflow:hidden}
  .pl-prog>i{display:block;height:100%;border-radius:2px;background:var(--accent)}
  .pl-note{display:flex;align-items:center;min-height:40px;margin:8px 6px 0 0;padding:4px 4px 4px 10px;border-radius:12px;background:var(--raised)}
  .pl-note.warn{background:var(--warn-soft)}.pl-note.acc{background:var(--accent-soft)}
  .pl-note>.i{font-size:16px;color:var(--muted)}
  .pl-note.warn>.i{color:var(--warn)}.pl-note.acc>.i{color:var(--accent)}
  .pl-note .tx{flex:1;min-width:0;margin-left:8px;padding:6px 0;font-size:12.5px;line-height:1.45;color:var(--text);overflow-wrap:anywhere}
  .pl-note .na{flex:none;min-height:40px;margin-left:8px;padding:0 12px;border-radius:10px;border:1px solid var(--line-strong);background:var(--surface);color:var(--accent);font-size:13px;font-weight:700}
  .pl-props{margin:10px 6px 0 0}
  .pl-tbl{background:var(--bg);border-radius:12px;padding:2px 2px 2px 10px}
  .pl-pr{display:flex;align-items:center;min-height:32px}
  .pl-pr .k{flex:none;width:44px;font-size:12px;color:var(--muted)}
  .pl-pr .v{flex:1;min-width:0;margin-right:8px;font-size:12px;line-height:1.4;color:var(--text);overflow-wrap:anywhere;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .pl-pr .v.mono{font-family:var(--mono)}
  .pl-pr .cp{flex:none;width:36px;height:36px;border:0;border-radius:12px;background:none;color:var(--muted);display:grid;place-items:center;font-size:20px;padding:0}
  .pl-pr .cp.hide{visibility:hidden}
  .pl-sep{height:1px;background:var(--line)}
  .pl-del{display:flex;align-items:center;justify-content:center;width:100%;min-height:46px;margin:10px 0 6px;border-radius:13px;border:1px solid var(--bad);background:var(--bad-soft);color:var(--bad);font-size:14px;font-weight:700}
  .pl-fl{height:1px;background:var(--line);margin-top:10px}
  .pl-foot{display:flex;align-items:center;margin-left:-8px}
  .pl-foot .ex{min-height:44px;padding:0 12px 0 8px;border:0;border-radius:12px;background:none;color:var(--text-2);font-size:13.5px;font-weight:700;display:inline-flex;align-items:center;gap:6px}
  .pl-foot .ex .i{font-size:18px}
  .pl-foot .ex.open .i{transform:rotate(180deg)}
  .pl-foot .more{margin-left:auto;width:44px;height:44px;border:0;border-radius:12px;background:none;color:var(--text-2);display:grid;place-items:center;font-size:22px;padding:0}
  .pl-local{margin-top:20px;margin-bottom:10px;font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--text-2)}
  .pl-dlabel{font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--text-2)}
  .pl-dlabel+.pl-card{margin-top:10px}
  /* 安装页（activity_plugin_install.xml：ScrollView + 18dp 内边距） */
  .pi{padding:18px}
  .pi-head{display:flex;align-items:center;min-height:48px}
  .pi-head .ibtn{width:48px;height:48px;font-size:24px;color:var(--text)}
  .pi-head .pg-title{flex:1;min-width:0;margin:0 0 0 8px}
  .pi-tx{margin-top:16px;font-size:15px;line-height:1.5;color:var(--text);white-space:pre-wrap;overflow-wrap:anywhere}
  .pi-tx:empty{display:none}
  .pi .btn.pri{margin-top:24px}
  .pi .btn.quiet{margin-top:8px}
  /* 技能页 */
  .sk-hint{display:inline-flex;align-items:center;gap:6px;min-height:32px;margin-bottom:14px;font-size:13px;line-height:1.45;color:var(--text-2)}
  .sk-hint .i{font-size:14px;color:var(--accent)}
  .sk-imp{min-height:50px;padding:0 22px}
  .sk-prog{margin-top:10px}
  .sk-prog .hd{display:flex;align-items:center}
  .sk-prog .hd>span{flex:1;min-width:0;font-size:13px;color:var(--text-2)}
  .sk-tb{min-height:44px;padding:0 12px;border:0;border-radius:12px;background:none;color:var(--accent);font-size:13px;font-weight:500;white-space:nowrap}
  .sk-tb:disabled{color:var(--muted)}
  .sk-track{height:16px;color:var(--accent)}
  .sk-track svg{display:block;width:100%;height:16px}
  .sk-res{display:flex;align-items:center;gap:6px;margin-top:10px;padding:10px 12px;border-radius:12px;font-size:13px;color:var(--text);
    background:color-mix(in srgb,var(--good) 12%,transparent);border:1px solid color-mix(in srgb,var(--good) 40%,transparent)}
  .sk-res .i{font-size:14px;color:var(--accent)}
  .sk-head{display:flex;align-items:center;margin-top:18px}
  .sk-head>span{flex:1;font-size:12px;font-weight:700;color:var(--text-2)}
  .sk-split{display:flex;align-items:flex-start}
  .sk-split>.a{flex:1;min-width:0}
  .sk-split>.b{flex:1;min-width:0;margin-left:14px}
  .sk-group{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);padding:4px 0}
  .sk-row{display:flex;align-items:center;min-height:68px;padding:10px 12px;border-radius:12px}
  .sk-row.sel{background:var(--accent-soft)}
  .sk-tile{flex:none;width:40px;height:40px;margin-right:14px;border-radius:10px;background:var(--accent-soft);color:var(--accent);display:grid;place-items:center;font-size:20px}
  .sk-row .w{flex:1;min-width:0}
  .sk-row .n{font-size:15px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .sk-row .d{padding-top:4px;font-size:12px;line-height:1.45;color:var(--text-2);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .sk-row .chev{font-size:18px}
  .sk-pills{display:flex;gap:6px;margin-top:6px;min-width:0}
  .sk-pill{max-width:180px;padding:3px 8px;border-radius:999px;background:var(--line);color:var(--text-2);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .sk-pill.acc{background:var(--accent-soft);color:var(--accent)}
  .sk-line{height:1px;background:var(--line);margin-left:66px}
  .sk-detail{background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card);padding:14px 16px 16px}
  .sk-detail .nm{font-size:18px;font-weight:700;overflow-wrap:anywhere}
  .sk-detail .ds{padding:6px 0 10px;font-size:13px;line-height:1.55;color:var(--text-2)}
  .sk-detail .av{font-size:12px;color:var(--muted)}
  .sk-inv{display:flex;align-items:center;margin-top:10px;padding:4px 4px 4px 12px;border-radius:16px;border:1px solid var(--line);background:var(--surface)}
  .sk-inv>span{flex:1;min-width:0;font-family:var(--mono);font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .sk-path{padding:12px 0 6px;font-family:var(--mono);font-size:11px;color:var(--muted);overflow-wrap:anywhere}
  .sk-src{margin:0;padding:10px 12px;border-radius:16px;border:1px solid var(--line);background:var(--surface);font-family:var(--mono);font-size:12px;line-height:1.55;color:var(--text);white-space:pre-wrap;overflow-wrap:anywhere}
  .sk-empty{display:flex;flex-direction:column;align-items:center;text-align:center;padding:28px 20px 20px;background:var(--surface);border:1px solid var(--line);border-radius:var(--r-card)}
  .sk-empty .sk-tile{width:52px;height:52px;margin:0;font-size:26px}
  .sk-empty .tt{padding:14px 0 6px;font-size:16px;font-weight:700}
  .sk-empty .sb{font-size:13px;line-height:1.6;color:var(--text-2)}
  .sk-empty .sk-tb{margin-top:6px}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  const esc = h.esc;

  // ---------------------------------------------------------------- 插件页示例数据（虚构）
  // 排序「名称 A–Z」：'@' 排在字母前，所以第三方天气插件在首位（双栏默认选中首行）。
  const PLUGINS = [
    {
      key: 'weather', name: '@example/dsh-plugin-weather', kind: '第三方', neutral: true, icon: 'plugins',
      version: '1.4.0', latest: '1.5.0', desc: '在对话中查询天气与空气质量，支持城市名或经纬度。',
      on: true, status: '配置：已启用', tone: 'good', runtime: '当前 Web：已加载',
      notes: [{ tone: 'acc', icon: 'plugin_update', text: '可更新到 1.5.0。', action: '立即更新' }],
      // 位置字段为管理引擎返回的加载路径；具体目录结构为推断，仅作示意。
      location: '/root/.dsh/plugins/node_modules/@example/dsh-plugin-weather', locMono: true,
      source: 'github:example-org/dsh-plugin-weather', deletable: true,
    },
    {
      key: 'notes', name: 'dsh-plugin-notes', kind: '第三方', neutral: true, icon: 'plugins',
      version: '0.3.1', desc: '', on: false, status: '配置：已禁用', tone: 'muted', runtime: '当前 Web：未加载',
      notes: [{ tone: 'warn', icon: 'plugin_restart', text: '重启 Web 后生效。' }],
      location: '/root/.dsh/plugins/node_modules/dsh-plugin-notes', locMono: true,
      source: 'npm:dsh-plugin-notes', deletable: true,
    },
    {
      key: 'mobile', name: 'dsh-web-mobile', kind: 'DSHA 内置', neutral: false, icon: 'ui2_box',
      version: '3.2.2', desc: '移动端网页适配：触控布局、软键盘避让与窄屏导航。（说明文字为推断示例）',
      on: true, status: '配置：已启用', tone: 'good', runtime: '当前 Web：已加载', notes: [],
      location: '/root/.dsh/plugins/dsh-web-mobile', locMono: true, source: '', srcText: '随包内置', deletable: false,
    },
  ];
  const BUSY_MSG = '正在下载插件及依赖：@example/dsh-plugin-weather 1.5.0';

  /** 忙碌态：天气插件正在更新（排队意图未声明 desired → 「操作待完成」）。 */
  function busyItem(p) {
    if (p.key !== 'weather') return p;
    return Object.assign({}, p, {
      pending: true, percent: 42, status: '操作待完成 · 正在执行 · 42%', tone: 'warn',
      // PluginCardState.notes：排队中不提示更新；有操作消息时追加 MESSAGE（MUTED）。
      notes: [{ tone: 'muted', icon: 'plugin_info', text: BUSY_MSG }],
    });
  }

  // PluginWaveProgress 确定态：只画到真实 percent；后段浅色轨道为推断（原生可能不画）。
  function wave(percent) {
    let d = '';
    const end = Math.round(300 * percent / 100);
    for (let x = 2; x <= end; x += 2) d += (d ? 'L' : 'M') + x + ' ' + (6 + 2 * Math.sin(x / 6 * Math.PI)).toFixed(2);
    return `<svg viewBox="0 0 300 12" preserveAspectRatio="none" aria-hidden="true">` +
      `<path d="M${end + 6} 6H298" stroke="var(--line-strong)" stroke-width="4" stroke-linecap="round" vector-effect="non-scaling-stroke" fill="none"/>` +
      `<path d="${d}" stroke="var(--accent)" stroke-width="4" stroke-linecap="round" vector-effect="non-scaling-stroke" fill="none"/></svg>`;
  }

  function note(n) {
    return `<div class="pl-note ${n.tone === 'muted' ? '' : n.tone}">${h.icon(n.icon)}<div class="tx">${esc(n.text)}</div>` +
      (n.action ? `<button class="na" data-dense>${esc(n.action)}</button>` : '') + `</div>`;
  }

  /**
   * item_plugin 三种密度：
   *   LIST    手机/单栏：说明 2 行；展开时显示属性表 + 删除；底栏「展开插件详情 / 收起详情」+ 更多
   *   COMPACT 双栏左列：说明 1 行、只留首条提示、无底栏，选中为 2dp 强调描边
   *   DETAIL  双栏右窗格：说明不限行、属性表常驻、底栏只有「更多」
   */
  function card(p, density, opts) {
    const o = opts || {};
    const open = density === 'detail' || (density === 'list' && o.expanded);
    const foot = density !== 'compact';
    const notes = density === 'compact' ? p.notes.slice(0, 1) : p.notes;
    const cls = ['pl-card', density === 'compact' ? 'compact' : '', o.sel ? 'sel' : ''].join(' ');
    const descCls = open ? '' : density === 'compact' ? 'c1' : 'c2';
    const sw = `<span class="pl-sw${p.on ? ' on' : ''}${p.pending ? ' off' : ''}" data-touch role="switch" aria-checked="${p.on}" aria-label="${p.on ? '禁用 ' : '启用 '}${esc(p.name)}"><span class="t"></span></span>`;
    const props = open ? `<div class="pl-props"><div class="pl-tbl">
        <div class="pl-pr"><span class="k">版本</span><span class="v mono">${esc(p.version + (p.latest ? ' → ' + p.latest : ''))}</span></div>
        <div class="pl-sep"></div>
        <div class="pl-pr"><span class="k">位置</span><span class="v${p.locMono ? ' mono' : ''}">${esc(p.location)}</span>
          <button class="cp" data-dense aria-label="复制插件路径">${h.icon('ui_copy')}</button></div>
        <div class="pl-sep"></div>
        <div class="pl-pr"><span class="k">来源</span><span class="v${p.source ? ' mono' : ''}">${esc(p.source || p.srcText)}</span>
          <button class="cp${p.source ? '' : ' hide'}" data-dense aria-label="复制来源链接">${h.icon('ui_copy')}</button></div>
      </div>${p.deletable ? `<button class="pl-del">删除插件</button>` : ''}</div>` : '';
    return `<div class="${cls}">
      <div class="pl-top"><span class="pl-glyph${p.neutral ? ' neutral' : ''}">${h.icon(p.icon)}</span>
        <div class="pl-words"><div class="pl-name">${esc(p.name)}</div>
          <div class="pl-meta"><span class="pl-bdg${p.neutral ? ' neutral' : ''}">${esc(p.kind)}</span><span class="pl-ver">${esc(p.version)}</span></div></div>
        ${sw}</div>
      <div class="pl-desc ${descCls}${p.desc ? '' : ' none'}">${esc(p.desc || '作者未提供说明')}</div>
      <div class="pl-st"><span class="dot ${p.tone === 'acc' ? 'acc' : p.tone === 'muted' ? '' : p.tone}"></span>
        <span class="s t-${p.tone}">${esc(p.status)}</span><span class="rt">${esc(p.runtime)}</span></div>
      ${p.pending ? `<div class="pl-prog" role="progressbar" aria-valuenow="${p.percent}"><i style="width:${p.percent}%"></i></div>` : ''}
      ${notes.map(note).join('')}
      ${props}
      ${foot ? `<div class="pl-fl"></div><div class="pl-foot">` +
        (density === 'list' ? `<button class="ex${o.expanded ? ' open' : ''}">${h.icon('chevron_down')}${o.expanded ? '收起详情' : '展开插件详情'}</button>` : '') +
        `<button class="more" aria-label="更多操作：${esc(p.name)}">${h.icon('plugin_more')}</button></div>` : ''}
    </div>`;
  }

  function header(market, busy) {
    const seg = `<div class="pl-seg" role="tablist">
        <button class="${market ? 'on' : ''}" role="tab" aria-selected="${market}">插件市场</button>
        <button class="${market ? '' : 'on'}" role="tab" aria-selected="${!market}" aria-label="插件管理 · 共 3 个插件">插件管理<span class="pl-n">3</span></button></div>`;
    const source = (full) => `<button class="pl-chip" data-dense aria-label="下载源：自动选择（推荐）">${h.icon('plugin_source')}<span>${full ? '下载源：自动选择（推荐）' : '源：自动'}</span></button>`;
    const marketCard = `<div class="pl-hcard">
        <div class="pl-hhead"><span class="t">从链接安装</span><button class="pl-web" data-dense>社区插件生态${h.icon('plugin_external')}</button></div>
        <div class="pl-in"><div class="input">${h.icon('ui_link')}<span>https://github.com/example-org/dsh-plugin-weather</span></div>
          <button class="ibtn" aria-label="清空链接">${h.icon('plugin_clear')}</button></div>
        <div class="pl-hint good">${h.icon('plugin_check')}<span>已识别：example-org/dsh-plugin-weather · 默认分支</span></div>
        <div class="pl-act">${source(true)}<button class="btn pri">解析安装</button></div></div>`;
    const manageCard = `<div class="pl-hcard">
        <div class="pl-srow"><div class="input ph">${h.icon('ui_search')}<span>搜索插件名称或描述</span></div>
          <button class="btn">${h.icon('plugin_scan')}检测插件</button></div>
        <div class="pl-flow"><span class="pl-chk" data-dense><span class="box"></span>只看自己装的</span>
          <button class="pl-chip" data-dense><span>名称 A–Z</span>${h.icon('ui_chevron')}</button>
          <span class="end">${source(false)}</span></div></div>`;
    // 状态条：市场页无任务/消息时整条收起；管理页空闲显示计数 +「检查更新」，忙碌显示转圈 + 取消。
    let strip = '';
    if (busy) {
      strip = `<div class="pl-strip acc"><div class="row"><span class="ic"><span class="pl-spin"></span></span>
          <span class="msg"><span>${esc(BUSY_MSG)} · 42%</span></span><button class="act" data-dense aria-label="取消当前操作">取消</button></div>
          <div class="pl-wave" role="progressbar" aria-valuenow="42">${wave(42)}</div></div>`;
    } else if (!market) {
      strip = `<div class="pl-strip"><div class="row"><span class="ic">${h.icon('plugin_info')}</span><span class="cnt">共 3 个插件</span>
          <span class="msg"><span></span></span><button class="act" data-dense>检查更新</button></div><div class="pl-wave"></div></div>`;
    }
    return `<div class="pl-titlerow"><h1 class="pg-title">插件</h1>${seg}</div>` + (market ? marketCard : manageCard) + strip;
  }

  const localFooter = () => `<div class="pl-local">本地插件</div>` + h.entries([
    { icon: 'ui_import', title: '导入插件包', sub: '从设备选择 ZIP 插件包' },
    { icon: 'ui_export', title: '导出插件', sub: '保存已安装插件的副本' },
  ], true);

  DSHA.page({
    id: 'plugins', group: '主标签页', title: '插件', shell: 'main', tab: 'plugins',
    source: 'fragment_plugins.xml · plugin_list_header.xml · item_plugin.xml · plugin_detail_pane.xml · plugin_list_footer.xml · PluginFragment',
    states: [{ id: 'installed', title: '插件管理（已安装）' }, { id: 'market', title: '插件市场' }, { id: 'busy', title: '安装进行中' }],
    context: () => '扩展你的工作方式',
    render(env) {
      const market = env.state === 'market';
      const busy = env.state === 'busy';
      const twoPane = env.w840; // SettingsPanes.twoPane：screenWidthDp >= 840
      const items = busy ? PLUGINS.map(busyItem) : PLUGINS;
      const list = market ? '' : twoPane
        ? items.map((p, i) => card(p, 'compact', { sel: i === 0 })).join('')
        : items.map((p, i) => card(p, 'list', { expanded: !busy && i === 0 })).join('');
      const left = `<div class="scroll"><div class="pl">${header(market, busy)}${list}${market ? localFooter() : ''}</div></div>`;
      if (!twoPane || market) return left;
      return `<div class="pl-split"><div class="l">${left}</div><div class="line"></div>
        <div class="r"><div class="scroll"><div class="pl"><div class="pl-dlabel">插件详情</div>${card(items[0], 'detail')}</div></div></div></div>`;
    },
  });

  // ---------------------------------------------------------------- 插件安装页（网站唤起）
  const LINK = 'https://github.com/example-org/dsh-plugin-weather/releases/download/v1.5.0/dsh-plugin-weather-1.5.0.tgz';
  DSHA.page({
    id: 'plugins-install', group: '主标签页', title: '插件安装（链接唤起）', shell: 'bare',
    source: 'activity_plugin_install.xml · PluginInstallActivity · PluginNavigation',
    states: [{ id: 'running', title: '正在下载解析' }, { id: 'done', title: '安装完成' }, { id: 'setup', title: '环境未初始化' }],
    render(env) {
      let status, details, btn, disabled;
      if (env.state === 'setup') {
        // 环境未就绪分支不调用 renderOperation，详情保持空白。
        status = '请先完成 DSHA 首次初始化，完成后会继续安装插件';
        details = ''; btn = '初始化 DSHA'; disabled = false;
      } else if (env.state === 'done') {
        status = '已安装 @example/dsh-plugin-weather；重启 Web 后生效。';
        // Preview.description()：名称 · 版本 / 作者 / 说明 / 兼容消息 / 实际依赖。兼容消息文案为推断。
        details = '@example/dsh-plugin-weather · 1.5.0\n作者：example-org\n在对话中查询天气与空气质量，支持城市名或经纬度。\n兼容当前 DSH 版本\n\n实际依赖数量：2\n  undici · 6.21.0\n  zod · 3.23.8';
        btn = '安装完成'; disabled = true;
      } else {
        status = '正在下载并解析：@example/dsh-plugin-weather';
        details = '下载并解析插件包后自动安装、启用。\n\n' + LINK;
        btn = '正在安装'; disabled = true;
      }
      return `<div class="scroll"><div class="pi">
        <div class="pi-head"><button class="ibtn" aria-label="返回上一页">${h.icon('ui_back')}</button><h1 class="pg-title">安装插件</h1></div>
        <div class="pi-tx">${esc(status)}</div>
        <div class="pi-tx">${esc(details)}</div>
        <button class="btn pri block"${disabled ? ' disabled' : ''}>${esc(btn)}</button>
        <button class="btn quiet block">打开插件管理</button>
      </div></div>`;
    },
  });

  // ---------------------------------------------------------------- 技能页（设置 → 数据与备份 → 技能）
  // 目录顺序为推断（SkillImports.list 的排序未在此核对）。
  const SKILLS = [
    { name: 'weekly-report', desc: '按团队模板整理本周进展、风险与下周计划。', user: true, model: true,
      text: '---\nname: weekly-report\ndescription: 按团队模板整理本周进展、风险与下周计划。\n---\n\n# 周报整理\n1. 汇总本周完成事项与相关链接。\n2. 列出风险与需要协助的事项。\n3. 给出下周计划，按优先级排序。' },
    { name: 'pdf-review', desc: '审阅 PDF 文档，输出要点摘要与待确认问题清单。', user: true, model: false },
    { name: 'commit-message', desc: '根据暂存区改动生成中文提交说明，附带 type: 前缀。', user: false, model: true },
  ];
  const skTrack = `<svg viewBox="0 0 300 16" preserveAspectRatio="none"><path d="M0 8 Q 7 3 14 8 T 28 8 T 42 8 T 56 8 T 70 8 T 84 8 T 98 8 T 112 8" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round"/><path d="M120 8H300" stroke="var(--raised)" stroke-width="4" stroke-linecap="round"/></svg>`;

  function skRow(s, wide, sel) {
    const pills = (s.user ? `<span class="sk-pill acc">/${esc(s.name)}</span>` : '') + (s.model ? `<span class="sk-pill">模型可调用</span>` : '');
    return `<div class="sk-row${sel ? ' sel' : ''}" role="button" tabindex="0"><span class="sk-tile">${h.icon('ui_tasks')}</span>
      <div class="w"><div class="n">${esc(s.name)}</div><div class="d">${esc(s.desc)}</div>${pills ? `<div class="sk-pills">${pills}</div>` : ''}</div>
      ${wide ? '' : h.icon('ui2_chevron', 'chev')}</div>`;
  }
  function skDetail(s) {
    return `<div class="sk-detail"><div class="nm">${esc(s.name)}</div><div class="ds">${esc(s.desc)}</div>
      <div class="av">${s.model ? '模型可按需调用 skill 工具读取。' : '此技能禁止模型自主调用。'}</div>
      ${s.user ? `<div class="sk-inv"><span>/${esc(s.name)}</span><button class="sk-tb">复制调用命令</button></div>` : `<div class="av" style="padding-top:4px">此技能禁止用户直接调用。</div>`}
      <div class="sk-path">/root/.dsh/skills/${esc(s.name)}/SKILL.md</div>
      <pre class="sk-src">${esc(s.text || '')}</pre></div>`;
  }
  function skillsBody(env) {
    // 本页根宽度：w840 时位于设置双栏右侧（减 320 列表 + 1 线）；否则为 fragment 宽。
    const width = env.w840 ? env.mainContentW - 321 : env.mainContentW;
    const wide = width >= 600;               // SkillsFragment.SPLIT_DP
    const wrapImport = wide && env.tablet;   // smallestScreenWidthDp >= 600
    const busy = env.state === 'importing';
    const empty = env.state === 'empty';
    const listHtml = empty
      ? `<div class="sk-empty"><span class="sk-tile">${h.icon('ui_tasks')}</span><div class="tt">还没有技能</div>
          <div class="sb">导入 SKILL.md 后会出现在这里，DSH 会按需读取。</div><button class="sk-tb">格式要求</button></div>`
      : `<div class="sk-group">${SKILLS.map((s, i) => (i ? '<div class="sk-line"></div>' : '') + skRow(s, wide, wide && i === 0)).join('')}</div>`;
    return `<div class="scroll"><div class="pg">
      ${h.title('技能')}
      <div class="sk-hint" role="button">导入 SKILL.md，让 DSH 按需读取可复用的任务说明。${h.icon('onb_info')}</div>
      <div><button class="btn pri sk-imp${wrapImport ? '' : ' block'}"${busy ? ' disabled' : ''}>导入 SKILL.md</button></div>
      ${busy ? `<div class="sk-prog"><div class="hd"><span>正在读取并检查 SKILL.md…</span><button class="sk-tb">取消</button></div><div class="sk-track">${skTrack}</div></div>` : ''}
      ${env.state === 'list' ? `<div class="sk-res" role="button">已导入技能：weekly-report${h.icon('onb_info')}</div>` : ''}
      <div class="sk-head"><span>已安装 · ${empty ? 0 : SKILLS.length}</span><button class="sk-tb"${busy ? ' disabled' : ''}>刷新</button></div>
      ${wide && !empty ? `<div class="sk-split"><div class="a">${listHtml}</div><div class="b">${skDetail(SKILLS[0])}</div></div>` : listHtml}
    </div></div>`;
  }

  DSHA.page({
    id: 'skills', group: '主标签页', title: '技能', shell: 'main', tab: 'settings',
    source: 'SkillsFragment · CardPage · InfoHint · WorkspaceFragment 入口 · SettingsFragment 双栏',
    states: [{ id: 'list', title: '已安装（刚导入）' }, { id: 'importing', title: '导入进行中' }, { id: 'empty', title: '空状态' }],
    back: () => true,
    context: () => '偏好与管理',
    render(env) {
      const body = skillsBody(env);
      if (!env.w840) return body;
      // 设置双栏：左侧 320dp 设置列表（此处只示意「运行环境」分组，完整列表见 settings.js），右侧为当前子页。
      const left = `<div class="scroll"><div class="pg">${h.title('设置', '让工作台按你的习惯运行。')}${h.label('运行环境')}
        <div class="card flush">${[
          { icon: 'ui2_box', title: '安装', sub: '安装与修复运行环境' },
          { icon: 'settings', title: '配置', sub: '接口、显示与运行' },
          { icon: 'ui2_folder', title: '数据与备份', sub: '备份恢复 · 文件共享', sel: true },
          { icon: 'ui_shield', title: '设备能力授权', sub: 'Root · Shizuku · ADB · 权限' },
          { icon: 'ui2_box', title: '任务状态上岛', sub: '状态栏胶囊 · 灵动岛 · 实时活动' },
        ].map(h.entry).join('')}</div></div></div>`;
      return `<div class="two-pane"><div class="list">${left}</div><div class="detail">${body}</div></div>`;
    },
    // MainActivity.updateTitle：嵌套子页显示返回键，顶栏标题为「技能」（kit 外壳固定写 DSHA，这里改写本页顶栏）。
    after(dev) { const t = dev.querySelector('.appbar .t'); if (t) t.textContent = '技能'; },
  });
})();
