/*
 * 设置标签页与设置子页（照 pages/launch.js 写法）。
 * 来源：
 *   - 设置根页：res/layout/fragment_settings.xml、ui/SettingsFragment.java（TAB_OPTIONS / buildRow / twoPaneShell）、
 *     ui/SettingsPanes.java（TWO_PANE_DP = 840）、res/drawable/bg_settings_row.xml / bg_settings_group.xml、values/shell_page.xml。
 *   - 顶栏：ui/MainActivity.updateToolbar（子页显示返回 + 页面标题；w840 双栏且详情未嵌套时保持 DSHA + 品牌图标）、
 *     renderPageContext（语境「偏好与管理」）。
 *   - 子页：ConfigFragment + fragment_config.xml + values/config_page.xml；WorkspaceFragment + fragment_workspace.xml +
 *     item_data_entry.xml；InstallFragment + fragment_install.xml + AdaptiveColumns；DeviceGrantsFragment +
 *     fragment_device_grants.xml + FullPermissionControls + util/GrantSummary；OverlayFragment + CardPage；
 *     IslandFragment + fragment_island.xml + item_island_level.xml + values/island_page.xml；AboutFragment + CardPage。
 * 布局判据（只用 env 与组件自身宽度）：
 *   - env.w840：设置为 twoPaneShell，左 320dp 列表 + 1dp 线 + 右详情；子页在右侧详情内切换，不显示返回。
 *     否则子页整页替换 fragment_container，顶栏显示返回与页面标题，手机仍保留悬浮胶囊。
 *   - 详情宽 detailW = w840 ? mainContentW - 321 : mainContentW。各子页按自身宽度判断：
 *     运行配置 detailW >= 720 两列（config_two_column_dp，内容上限 1080，两侧至少 28dp）；
 *     上岛 detailW >= 700 两列（island_two_column_dp，上限 880）；
 *     安装 AdaptiveColumns：detailW - 2×20 >= 680 并排，列距 24；
 *     授权 GrantSummary：detailW - 2×14 >= 640 两栏（单栏上限 640、双栏上限 1040，居中）；
 *     数据与备份：screenWidthDp < 600（!env.w600）时三格统计纵向堆叠（按屏宽，不按详情宽）。
 *   - 页底：shell_page_bottom 手机 90 / sw600 30；运行配置保存栏 shell_nav_clearance 手机 74 / sw600 0。
 * 说明：
 *   - 分组标题图标为原生 ic_section_*（layer-list 包装，icons.js 生成时按引用解析）。
 *   - 顶栏右侧「下载记录」与「关于」两个 44dp 图标由 kit 外壳统一绘制（MainActivity.showMain）。
 *   - 示例数据（版本、时间、端口）均为脱敏示意。
 */
(function () {
  const { h } = DSHA;
  const esc = h.esc, icon = h.icon;
  const css = `
  .st-pane{flex:1;min-height:0;display:flex;flex-direction:column;background:var(--bg)}
  .st-pg{padding:var(--page-top) var(--page-pad) var(--page-bottom)}
  .st-title{margin:0;font-size:var(--page-title);font-weight:700;line-height:1.25}
  .st-desc{margin:8px 0 0;font-size:var(--page-desc);line-height:1.55;color:var(--text-2)}
  .st-sec{margin:24px 0 10px;font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--text-2);line-height:1.2}
  .st-sec.ic{display:flex;align-items:center;gap:8px}.st-sec.ic .i{font-size:16px;color:var(--accent)}
  .st-group{border:1px solid var(--line);border-radius:14px;overflow:hidden}
  .st-row{display:flex;align-items:center;min-height:56px;padding:12px 16px}
  .st-row .tile{width:32px;height:32px;font-size:18px}
  .st-row .w{flex:1;min-width:0;margin:0 8px 0 12px}
  .st-row .t{font-size:14px;font-weight:700;line-height:1.3}
  .st-row .s{font-size:12px;color:var(--muted);margin-top:4px;line-height:1.4;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .st-row .v{flex:none;font-size:13px;color:var(--text-2);margin-left:8px;max-width:120px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .st-row .chev{font-size:16px;margin-left:8px}
  .st-row.bad .t{color:var(--bad)}
  .st-two .st-row.tab{min-height:52px}
  .st-two .st-row.tab .s{-webkit-line-clamp:1}
  .st-row.sel{background:var(--accent-soft);border-radius:12px}
  .st-div{height:1px;background:var(--line);margin-left:44px}
  .st-div.full{margin-left:0}
  .st-foot{margin-top:22px;text-align:center;font-size:12px;color:var(--muted);overflow-wrap:anywhere}
  .st-hint{display:inline-flex;align-items:center;gap:6px;min-height:32px;font-size:12px;color:var(--muted);line-height:1.5}
  .st-hint .i{font-size:14px;color:var(--accent)}
  /* 双栏 */
  .st-two{flex:1;min-height:0;display:flex}
  .st-two>.list{flex:none;width:320px;display:flex;flex-direction:column}
  .st-two>.line{flex:none;width:1px;background:var(--line)}
  .st-two>.detail{flex:1;min-width:0;display:flex;flex-direction:column}
  /* 运行配置 */
  .cf-cols{padding:4px var(--cf-side,16px) 16px}
  .cf-cols.two{display:flex;align-items:flex-start;gap:20px}.cf-cols.two>*{flex:1;min-width:0}
  .cf-lab{display:flex;align-items:center;gap:8px;margin:10px 0 4px;padding:0 4px;font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--muted)}
  .cf-lab .x{flex:1;min-width:0;display:flex;justify-content:flex-end}
  .cf-lab .st-hint{font-weight:400;letter-spacing:0;text-align:start;min-height:32px}
  .cf-lab .st-hint span{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .cf-card{border:1px solid var(--line);border-radius:14px;overflow:hidden}
  .cf-row{display:flex;align-items:center;min-height:48px;padding:5px 12px 5px 14px;gap:0}
  .cf-row .w{flex:1;min-width:0}
  .cf-row .t{font-size:14px;line-height:1.3;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .cf-row .s{font-size:12px;color:var(--text-2);margin-top:2px;line-height:1.35;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .cf-row .switch{margin:9px 0 9px 8px}
  .cf-row .chev{font-size:16px;margin-left:8px}
  .cf-row.sub{padding-left:24px;background:var(--bg)}
  .cf-hint{padding:0 12px 8px 14px;font-size:12px;color:var(--text-2);line-height:1.4}
  .cf-chip{flex:none;min-height:36px;padding:0 10px;margin-left:8px;border-radius:10px;border:1px solid var(--line-strong);background:var(--surface);color:var(--accent);font-size:13px;display:inline-flex;align-items:center;white-space:nowrap}
  .cf-chip.link{color:var(--text-2)}.cf-chip.ghost{border-color:transparent;background:none;color:var(--muted)}
  .cf-sel{flex:none;min-height:36px;padding:0 8px 0 12px;border-radius:10px;border:1px solid var(--line-strong);display:inline-flex;align-items:center;gap:4px;font-size:13px;color:var(--text);white-space:nowrap}
  .cf-sel .i{font-size:14px;color:var(--muted)}
  .cf-seg{flex:none;display:flex;padding:2px;margin-left:8px;border-radius:11px;background:var(--raised)}
  .cf-seg>span{min-height:36px;min-width:44px;padding:0 9px;display:grid;place-items:center;border-radius:9px;font-size:13px;color:var(--text-2)}
  .cf-seg>.on{background:var(--surface);color:var(--accent);font-weight:700}
  .cf-badge{margin-left:6px;padding:2px 7px;border-radius:7px;background:var(--warn-soft);color:var(--warn);font-size:11px;font-weight:700}
  .cf-links{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.cf-links .cf-chip{margin-left:0}
  .cf-save{flex:none;display:flex;align-items:center;gap:4px;padding:8px var(--cf-side,16px) 10px;margin-bottom:var(--cf-clear);border-top:1px solid var(--line);background:var(--surface)}
  .cf-save .msg{flex:1;min-width:0;font-size:12px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .cf-save .btn.pri{min-height:44px;padding:0 20px}
  .cf-save .btn.quiet{padding:0 10px}
  /* 数据与备份 */
  .ws-head{display:flex;align-items:center}
  .ws-head .tl{flex:none;width:44px;height:44px;margin-right:14px;border-radius:13px;background:var(--accent-soft);color:var(--accent);display:grid;place-items:center;font-size:22px}
  .ws-head h1{margin:0;font-size:26px;font-weight:700;line-height:1.2;flex:1;min-width:0}
  .ws-sub{margin:6px 0 0;font-size:13px;color:var(--text-2)}
  .ws-hero{margin-top:18px;background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:18px}
  .ws-hero .top{display:flex;align-items:flex-start}
  .ws-hero .orb{flex:none;width:48px;height:48px;border-radius:14px;display:grid;place-items:center;font-size:24px}
  .ws-hero .orb.good{background:var(--good-soft);color:var(--good)}.ws-hero .orb.warn{background:var(--warn-soft);color:var(--warn)}
  .ws-hero .w{flex:1;min-width:0;margin-left:14px}
  .ws-hero .t{font-size:18px;font-weight:700;line-height:1.3}
  .ws-hero .d{margin-top:6px;font-size:13px;line-height:1.55;color:var(--text-2)}
  .ws-stats{display:flex;gap:10px;margin-top:14px}
  .ws-stats.stack{flex-direction:column;gap:8px}
  .ws-stat{flex:1;min-width:0;min-height:44px;padding:12px;border-radius:12px;background:var(--raised)}
  .ws-stat .k{font-size:12px;color:var(--muted)}
  .ws-stat .vv{margin-top:4px;font-size:14px;font-weight:700;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .ws-acts{display:flex;gap:10px;margin-top:14px}.ws-acts .btn{flex:1;min-width:0;min-height:52px}
  .ws-group .st-row{min-height:64px;padding:13px}
  .ws-sec{margin:22px 0 8px}
  /* 安装与环境 */
  .in-head{display:flex;align-items:center;margin-bottom:14px}
  .in-head .tl{flex:none;width:40px;height:40px;margin-right:12px;border-radius:12px;background:var(--accent-soft);color:var(--accent);display:grid;place-items:center;font-size:20px}
  .in-head h1{margin:0;font-size:24px;font-weight:700;flex:1;min-width:0}
  .in-cols.split{display:flex;gap:24px;align-items:flex-start}.in-cols.split>*{flex:1;min-width:0}
  .in-cols.split>.b>.in-sec:first-child{margin-top:0}
  .in-hero{background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:14px}
  .in-hero .top{display:flex;align-items:center}
  .in-orb{flex:none;width:40px;height:40px;border-radius:12px;display:grid;place-items:center;font-size:20px}
  .in-orb.good{background:var(--good-soft);color:var(--good)}.in-orb.bad{background:var(--bad-soft);color:var(--bad)}
  .in-hero .w{flex:1;min-width:0;margin-left:12px}
  .in-hero .t{font-size:16px;font-weight:700}
  .in-hero .s{margin-top:4px;font-size:12.5px;color:var(--text-2);line-height:1.45}
  .in-hero .p{margin-top:4px;font-size:11.5px;color:var(--muted)}
  .in-chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}
  .in-chip{max-width:100%;padding:5px 9px;border-radius:9px;background:var(--raised);font-size:11.5px;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .in-chip b{color:var(--text)}
  .in-meter{display:flex;gap:4px;height:5px;margin-top:12px}.in-meter i{flex:1;border-radius:3px;background:var(--raised)}
  .in-meter i.ok{background:var(--good)}.in-meter i.bad{background:var(--bad)}.in-meter i.warn{background:var(--warn)}
  .in-acts{display:flex;gap:8px;margin-top:12px}.in-acts .btn{flex:1;min-width:0;padding:0 8px}
  .in-acts .btn:not(.pri){min-height:52px}
  .in-err{margin-top:12px;padding:12px;border-radius:12px;background:var(--bad-soft);color:var(--bad);font-size:12.5px;line-height:1.5}
  .in-sec{margin:18px 0 8px;display:flex;align-items:center}
  .in-sec .st-sec{margin:0;flex:1}
  .in-sec .n{font-family:var(--mono);font-size:12px;color:var(--muted)}
  .in-step{display:flex;align-items:center;min-height:52px;padding:8px 10px 8px 12px}
  .in-num{flex:none;width:28px;height:28px;border-radius:14px;display:grid;place-items:center;font:700 12px var(--mono);background:var(--raised);color:var(--text-2)}
  .in-step .w{flex:1;min-width:0;margin:0 8px 0 12px}
  .in-step .t{font-size:14px;font-weight:700}
  .in-step .s{margin-top:3px;font-size:11.5px;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .in-pill{flex:none;max-width:104px;padding:3px 8px;border-radius:9px;font-size:11px;font-weight:700;background:var(--raised);color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .good>.in-num,.in-pill.good{background:var(--good-soft);color:var(--good)}
  .bad>.in-num,.in-pill.bad{background:var(--bad-soft);color:var(--bad)}
  .warn>.in-num,.in-pill.warn{background:var(--warn-soft);color:var(--warn)}
  .in-step .chev,.in-tool .chev{font-size:16px;margin-left:6px}
  .in-div{height:1px;background:var(--line);margin-left:52px}
  .in-div.t54{margin-left:54px}
  .in-tool{display:flex;align-items:center;min-height:56px;padding:8px 10px 8px 12px}
  .in-tool .tl{flex:none;width:30px;height:30px;border-radius:9px;background:var(--accent-soft);color:var(--accent);display:grid;place-items:center;font-size:17px}
  .in-tool .tl.bad{background:var(--bad-soft);color:var(--bad)}
  .in-tool .w{flex:1;min-width:0;margin:0 8px 0 12px}
  .in-tool .t{font-size:14px;font-weight:700}.in-tool.bad .t{color:var(--bad)}
  .in-tool .s{margin-top:3px;font-size:11.5px;color:var(--text-2);line-height:1.4;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .in-tool .last{margin-top:3px;font:11px var(--mono);color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .in-tool .ibtn{width:44px;height:44px;font-size:18px}
  .in-log{height:220px;margin:0 10px 10px;padding:12px;border-radius:13px;border:1px solid var(--line-strong);background:var(--surface);font:11.5px/1.6 var(--mono);color:var(--text-2);overflow:hidden;white-space:pre-wrap;overflow-wrap:anywhere}
  /* 设备能力授权 */
  .gr-head{flex:none;display:flex;align-items:center;gap:6px;padding:4px 6px 10px var(--gr-side,14px);background:var(--bg);border-bottom:1px solid var(--line);box-shadow:0 2px 3px rgba(0,0,0,.04)}
  .gr-pill{flex:1;min-width:0;min-height:44px;padding:5px 6px 5px 8px;border-radius:12px;border:1px solid var(--line);background:var(--surface);display:flex;flex-direction:column;justify-content:center}
  .gr-pill .a{display:flex;align-items:center;gap:5px;font-size:12px;font-weight:700;white-space:nowrap;overflow:hidden}
  .gr-pill .a span:last-child{overflow:hidden;text-overflow:ellipsis}
  .gr-pill .b{margin:3px 0 0 12px;font-size:11px;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .gr-head .ibtn{color:var(--accent);font-size:20px;margin-right:0}
  .gr-body{padding:10px var(--gr-side,14px) var(--page-bottom)}
  .gr-cols{display:flex;gap:12px;align-items:flex-start}.gr-cols>*{flex:1;min-width:0}
  .gr-sec+.gr-sec{margin-top:14px}
  .gr-sec>.st-sec{margin:0 0 6px}
  .gr-lab{display:flex;align-items:center;margin-bottom:6px}.gr-lab .st-sec{flex:1;margin:0}
  .gr-card{border:1px solid var(--line);border-radius:14px;padding:4px 14px 10px}
  .gr-sw{display:flex;align-items:center;min-height:48px;font-size:14px;gap:10px}.gr-sw>span:first-child{flex:1;min-width:0}
  .gr-st{display:flex;align-items:center;gap:6px}
  .gr-st .m{flex:1;min-width:0;padding:4px 0;font-size:12px;line-height:1.5;color:var(--muted);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .gr-tb{flex:none;min-height:44px;padding:0 12px;border:0;background:none;border-radius:12px;color:var(--accent);font-size:13px;font-weight:600}
  .gr-tb.bad{color:var(--bad);min-height:36px;padding:0 8px;font-size:12px}
  .gr-seg{display:flex;padding:3px;margin-top:4px;border-radius:12px;background:var(--raised)}
  .gr-seg>span{flex:1;min-height:40px;display:flex;align-items:center;justify-content:center;gap:6px;border-radius:9px;font-size:13px;color:var(--text-2)}
  .gr-seg>.on{background:var(--surface);color:var(--text);font-weight:700}
  .gr-seg .dot{width:6px;height:6px}
  .gr-div{height:1px;background:var(--line);margin-top:10px}
  .gr-title{display:flex;align-items:center;min-height:48px}.gr-title>span{flex:1;font-size:14px;font-weight:700}
  .gr-note{margin-top:8px;padding:8px 12px;border-radius:12px;background:var(--raised);font-size:12px;color:var(--text-2);line-height:1.5}
  .gr-a11y{display:flex;align-items:center;min-height:44px;font-size:13px;font-weight:700}
  .gr-rules{display:flex;align-items:center;min-height:40px;padding:0 8px 0 4px;font-size:13px;color:var(--accent)}
  .gr-group{border:1px solid var(--line);border-radius:14px;overflow:hidden}
  .gr-row{display:flex;align-items:center;min-height:52px;padding:8px 14px}
  .gr-row .w{flex:1;min-width:0}.gr-row .t{font-size:14px;font-weight:700}
  .gr-row .s{margin-top:3px;font-size:12px;color:var(--muted);line-height:1.4;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
  .gr-row .chev{font-size:16px;margin-left:8px}
  .gr-rdiv{height:1px;background:var(--line);margin-left:14px}
  /* 悬浮条 */
  .ov-sw{display:flex;align-items:center;min-height:48px;font-size:14px;gap:10px}.ov-sw>span:first-child{flex:1;min-width:0}
  .ov-perm{font-size:12px;color:var(--muted);padding-bottom:4px}
  .ov-perm.need{color:var(--accent);min-height:44px;display:flex;align-items:center}
  .ov-ph{display:flex;align-items:center}.ov-ph>span{flex:1;font-size:13px;color:var(--text-2)}
  .ov-ph .btn{min-height:44px;padding:0 12px;font-size:13px;font-weight:500}
  .ov-sample{margin:4px 0;padding:12px 14px;border-radius:12px;color:#fff;font-size:12px;line-height:1.45;white-space:pre-line;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
  .ov-slide{display:flex;align-items:center;min-height:52px;gap:0}
  .ov-slide .n{min-width:72px;font-size:14px}
  .ov-slide .bar{flex:1;height:48px;position:relative}
  .ov-slide .bar::before{content:"";position:absolute;left:8px;right:8px;top:22px;height:4px;border-radius:2px;background:var(--raised)}
  .ov-slide .bar i{position:absolute;left:8px;top:22px;height:4px;border-radius:2px;background:var(--accent)}
  .ov-slide .bar b{position:absolute;top:14px;width:20px;height:20px;margin-left:-10px;border-radius:50%;background:var(--accent)}
  .ov-slide .v{min-width:48px;text-align:end;font-size:13px;color:var(--text-2)}
  .ov-ct{padding:12px 0 6px;font-size:14px;display:flex;align-items:center}.ov-ct>span:first-child{flex:1}.ov-ct .v{font-size:13px;color:var(--text-2)}
  .ov-chips{display:flex;flex-wrap:wrap;gap:2px 6px;margin-bottom:8px}
  .ov-chips.eq{flex-wrap:nowrap}.ov-chips.eq .chip{flex:1;min-width:0;justify-content:center;min-height:40px;padding:0}
  .ov-chips .chip{min-height:32px}
  .ov-sws{display:flex;gap:8px;margin-bottom:8px}
  .ov-sw-ring{width:44px;height:44px;border-radius:50%;display:grid;place-items:center;border:2px solid transparent}
  .ov-sw-ring.on{border-color:var(--accent)}
  .ov-sw-ring i{width:30px;height:30px;border-radius:50%;border:1px solid var(--line-strong)}
  .ov-line{height:1px;background:var(--line)}
  .ov-auto{padding:2px 4px 8px;font-size:12px;color:var(--muted)}
  /* 上岛 */
  .il-wrap{padding:4px 0 20px}
  .il-intro{margin:6px var(--il-side,16px) 0}
  .il-cols{padding:0 var(--il-side,16px)}
  .il-cols.two{display:flex;gap:20px;align-items:flex-start}.il-cols.two>*{flex:1;min-width:0}
  .il-prev{padding:12px}
  .il-cap{font-size:12px;color:var(--muted)}
  .il-strip{height:32px;margin-top:6px;padding:0 10px;border-radius:10px;background:var(--raised);display:flex;align-items:center;gap:8px}
  .il-strip .clk{font-size:12px;font-weight:700}
  .il-capsule{height:22px;padding:0 9px;border-radius:11px;background:var(--accent);color:var(--on-accent);font-size:12px;font-weight:700;display:grid;place-items:center;white-space:nowrap}
  .il-notice{margin-top:8px;padding:8px 12px 9px;border-radius:12px;background:var(--surface);border:1px solid var(--line)}
  .il-notice .t{font-size:14px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .il-notice .s{margin-top:2px;font-size:12px;color:var(--text-2)}
  .il-note{margin-top:6px;font-size:12px;color:var(--muted);line-height:1.4}
  .il-state{display:flex;align-items:center;min-height:48px;padding:8px 12px 8px 14px}
  .il-state .dot{width:10px;height:10px;margin-right:12px}
  .il-state .w{flex:1;min-width:0}
  .il-state .t{font-size:14px}.il-state .s{margin-top:2px;font-size:12px;color:var(--text-2);line-height:1.4}
  .il-state .api{margin-top:2px;font:11px var(--mono);color:var(--muted)}
  .il-lv{display:flex;align-items:center;min-height:48px;padding:5px 12px 5px 14px}
  .il-lv.on{background:var(--accent-soft)}
  .il-lv .no{flex:none;width:24px;height:24px;border-radius:12px;background:var(--raised);color:var(--text-2);font:700 12px var(--mono);display:grid;place-items:center}
  .il-lv.on .no{background:var(--accent);color:var(--on-accent)}
  .il-lv .w{flex:1;min-width:0;margin-left:12px}
  .il-lv .a{display:flex;align-items:center;gap:6px;font-size:14px;white-space:nowrap}
  .il-lv .smp{height:20px;padding:0 8px;border-radius:10px;background:var(--raised);font-size:11px;color:var(--text-2);display:grid;place-items:center}
  .il-lv .why{margin-top:2px;font-size:12px;color:var(--muted)}
  .il-lv .c{flex:none;margin-left:8px;font-size:12px;color:var(--muted)}.il-lv.on .c{color:var(--accent)}
  .il-lv+.il-lv{border-top:1px solid var(--line)}
  .il-lab{display:flex;align-items:center;gap:8px;margin:10px 0 4px}
  .il-lab .cf-lab{margin:0}
  .il-lab .h{flex:1;min-width:0;text-align:end;font-size:12px;color:var(--muted)}
  /* 关于 */
  .ab-mark{height:84px;display:grid;place-items:center}
  .ab-mark span{width:64px;height:64px;border-radius:20px;background:var(--accent-soft);border:1px solid var(--line-strong);color:var(--accent);display:grid;place-items:center;font-size:36px}
  .ab-brand{margin:0;padding:16px 0 10px;text-align:center;font-size:30px;font-weight:700}
  .ab-ver{padding-bottom:24px;text-align:center;font-size:12px;line-height:1.5;color:var(--text-2);overflow-wrap:anywhere}
  .ab-links .entry{padding:10px 0}
  .ab-links .divider{margin-left:44px}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  // 分组标题图标直接取原生 ic_section_*（icons.js 已解析其 layer-list 别名）。
  const secLabel = (t, ic, cls) => `<div class="st-sec${ic ? ' ic' : ''} ${cls || ''}">${ic ? icon(window.ICONS['section_' + ic] ? 'section_' + ic : ic) : ''}<span>${esc(t)}</span></div>`;
  const chev = () => `<span class="chev">${icon('ui2_chevron')}</span>`;

  // ---------------------------------------------------------------- 设置列表（fragment_settings.xml）
  const ROWS = {
    install: { title: '安装与环境', sub: '安装与修复运行环境', icon: 'ui2_box' },
    config: { title: '运行配置', sub: '接口、显示与运行', icon: 'settings' },
    workspace: { title: '数据与备份', sub: '备份恢复 · 文件共享', icon: 'ui2_folder' },
    grants: { title: '设备能力授权', sub: 'Root · Shizuku · ADB · 权限', icon: 'ui_shield' },
    island: { title: '任务状态上岛', sub: '状态栏胶囊 · 灵动岛 · 实时活动', icon: 'ui2_box' },
  };
  const row = (o, sel) => `<div class="st-row${o.cls ? ' ' + o.cls : ''}${sel ? ' sel' : ''}" data-touch${sel ? ' aria-selected="true"' : ''}>` +
    `<span class="tile">${icon(o.icon)}</span><div class="w"><div class="t">${esc(o.title)}</div>${o.sub ? `<div class="s">${esc(o.sub)}</div>` : ''}</div>` +
    (o.trail != null ? o.trail : chev()) + `</div>`;
  const group = (rows, cls) => `<div class="st-group ${cls || ''}">` + rows.join('<div class="st-div"></div>') + `</div>`;

  function settingsList(env, selected) {
    const two = env.w840;
    const dark = env.theme === 'dark';
    const tabs = ['install', 'config', 'workspace', 'grants', 'island'].map(k => row(Object.assign({ cls: 'tab' }, ROWS[k]), two && selected === k));
    return `<div class="scroll"><div class="st-pg">
      <h1 class="st-title">设置</h1>
      <p class="st-desc">让工作台按你的习惯运行。</p>
      ${secLabel('运行环境')}
      ${group(tabs)}
      ${secLabel('使用体验')}
      ${group([
        row({ title: '主题与外观', sub: `跟随系统 · ${dark ? '深色' : '浅色'}`, icon: 'ui2_auto' }),
        row({ title: '语言 / Language', sub: '跟随系统 · 简体中文', icon: 'ui_language' }),
        row({ title: '悬浮条', sub: '显示内容、透明度与停留时间', icon: 'ui_chat' }, two && selected === 'overlay'),
        row({ title: '省电模式', sub: '持续保持运行，适合长时间任务。', icon: 'ui2_moon', trail: h.sw(false) }),
      ])}
      ${secLabel('支持')}
      ${group([
        row({ title: '版本更新', sub: '稳定版与预览版更新', icon: 'recovery_refresh' }),
        row({ title: '自检与诊断', sub: '运行状态、错误记录与日志导出', icon: 'ui_shield' }),
        row({ title: '关于 DSHA', sub: '版本、开源许可与反馈', icon: 'about' }, two && selected === 'about'),
      ])}
      <div class="st-foot">DSHA v0.2.1-rc.3-20261102.1430-dsh0.2.1-rc.5 · MIT 许可</div>
    </div></div>`;
  }

  /** w840：twoPaneShell（列表 320 + 1dp 线 + 详情）；否则单页。 */
  function frame(env, key, detail) {
    if (!env.w840) return `<div class="st-pane">${detail}</div>`;
    return `<div class="st-two"><div class="list">${settingsList(env, key)}</div><div class="line"></div><div class="detail st-pane">${detail}</div></div>`;
  }
  const detailW = env => env.w840 ? env.mainContentW - 321 : env.mainContentW;
  const hintI = t => `<span class="st-hint"><span>${esc(t)}</span>${icon('onb_info')}</span>`;

  // ---------------------------------------------------------------- 运行配置
  function configDetail(env) {
    const w = detailW(env);
    const two = w >= 720;
    const side = two ? Math.max(28, Math.floor((w - 1080) / 2)) : 16;
    const dirty = env.state === 'dirty';
    const sw = on => h.sw(on);
    const div = '<div class="st-div full"></div>';
    // ConfigChip / 分段 36dp（config_chip_height），低于 44dp：原生如此，行本身 48dp。
    const chip = (t, cls) => `<button class="cf-chip ${cls || ''}" data-dense>${esc(t)}</button>`;
    const colA = `<div>
      <div class="cf-lab"><span>运行与网络</span><span class="x">${hintI('模型和 API Key 在 Web UI 中配置。')}</span></div>
      <div class="cf-card">
        <div class="cf-row" data-touch><div class="w"><div class="t">运行方式</div></div><span class="cf-sel">${dirty ? 'proroot（可选，实验）' : 'proot（默认，稳定）'}${icon('chevron_down')}</span></div>
        ${div}
        <div class="cf-row"><div class="w"><div class="t">自动分配 Web 端口（推荐）</div><div class="s">首选 3080，占用时自动换用</div></div>${sw(true)}</div>
        ${div}
        <div class="cf-row"><div class="w"><div class="t">DNS 解析策略</div></div><span class="cf-seg" data-dense><span class="on">自动</span><span>IPv4</span><span>原生</span></span></div>
        ${div}
        <div class="cf-row"><div class="w"><div class="t">允许局域网访问</div><div class="s">未设密码 · 用户名 dsha</div></div>${chip('设密码')}${sw(false)}</div>
        ${div}
        <div class="cf-row" data-touch><div class="w"><div class="t" style="display:flex;align-items:center">运行时兼容选项${dirty ? '<span class="cf-badge">已改</span>' : ''}</div>
          <div class="s">静态加载器 开 · seccomp 加速 ${dirty ? '已禁用' : '开'}</div></div>${chev()}</div>
      </div></div>`;
    const colB = `<div>
      <div class="cf-lab"><span>显示与启动</span></div>
      <div class="cf-card">
        <div class="cf-row"><div class="w"><div class="t">从桌面图标直接进入 Web</div></div>${sw(false)}</div>
        <div class="cf-hint">开启后点击桌面图标先显示加载页，Web 就绪即进入；在 Web 里返回到底会回到桌面。环境需要维护或启动失败时仍进入主界面。也可在 Web 的插件页「启动目标」中修改。</div>
        ${div}
        <div class="cf-row"><div class="w"><div class="t">使用桌面版网页布局</div><div class="s">网页内核 System WebView</div></div>${sw(false)}</div>
        ${div}
        <div class="cf-row"><div class="w"><div class="t">开启对话画中画</div><div class="s">系统已允许</div></div>${chip('系统设置')}${sw(true)}</div>
        ${div}
        <div class="cf-row"><div class="w"><div class="t">启动时检查更新</div></div>${sw(true)}</div>
        ${div}
        <div class="cf-row"><div class="w"><div class="t">悬浮显示 AI 输出</div></div>${chip('外观与行为')}${sw(false)}</div>
      </div>
      <div class="cf-links">${chip('数据与备份', 'link')}${chip('项目主页', 'ghost')}</div>
    </div>`;
    // 保存栏固定页底；手机用 shell_nav_clearance 74dp 让出悬浮胶囊。
    return `<div class="scroll" style="--cf-side:${side}px"><div class="cf-cols${two ? ' two' : ''}">${colA}${colB}</div></div>
      <div class="cf-save" style="--cf-side:${side}px;--cf-clear:${env.tablet || (env.proposal && env.short480) ? 0 : 74}px">
        <span class="msg">${dirty ? '2 项未保存 · 端口与运行时需重启 Web' : '配置已是最新'}</span>
        ${dirty ? h.btn('撤销改动', 'quiet') : ''}${h.btn('保存配置', 'pri' + (dirty ? '' : ' off'))}
      </div>`;
  }

  // ---------------------------------------------------------------- 数据与备份
  function workspaceDetail(env) {
    const never = env.state === 'never';
    const e = (ic, t, s, v, cls) => row({ icon: ic, title: t, sub: s, cls, trail: (v ? `<span class="v">${esc(v)}</span>` : '') + chev() });
    return `<div class="scroll"><div class="st-pg">
      <div class="ws-head"><span class="tl">${icon('ui2_folder')}</span><h1>数据与备份</h1></div>
      <p class="ws-sub">导出、恢复与文件共享。</p>
      <div class="ws-hero">
        <div class="top"><span class="orb ${never ? 'warn' : 'good'}">${icon('recovery_shield')}</span><div class="w">
          <div class="t">${never ? '还没有导出过备份' : '数据已保护'}</div>
          <div class="d">${never ? '对话、配置和插件目前只存在应用内。建议先做一次一键备份。' : '最近 7 天内有手动导出的备份；自动副本另存于本机私有目录。'}</div></div></div>
        <div class="ws-stats${env.w600 ? '' : ' stack'}">
          <div class="ws-stat" data-touch><div class="k">最近手动备份</div><div class="vv">${never ? '从未' : '2026/11/1 21:30'}</div></div>
          <div class="ws-stat" data-touch><div class="k">自动备份</div><div class="vv">每天 03:00</div></div>
          <div class="ws-stat"><div class="k">保存位置</div><div class="vv">下载/DSHA</div></div>
        </div>
        <div class="ws-acts">${h.btn('立即备份', 'pri')}${h.btn('从备份恢复')}</div>
      </div>
      ${secLabel('备份选项', 'data', 'ic ws-sec')}
      ${group([
        e('recovery_clock', '自动备份', '备份时机 · 本机保留最近 3 份已验证副本', '已开启'),
        e('ui_export', '自定义导出', '选择范围 · 项目目录 · 是否包含 API Key'),
        e('recovery_document', '备份与维护记录', '手动备份、自动副本和维护结果'),
      ], 'ws-group')}
      ${secLabel('存储与文件', 'ui2_folder', 'ic ws-sec')}
      ${group([
        e('recovery_database', '存储与文件', '空间占用、缓存清理和文件共享'),
        e('ui2_folder', '作品与文件导出', '浏览工作区 · 保存到手机 · 分享'),
        e('entry_book', '技能（Skills）', '查看技能 · 导入 SKILL.md'),
      ], 'ws-group')}
      ${secLabel('维护与恢复', 'maintenance', 'ic ws-sec')}
      ${group([
        e('entry_book', '储存指南', '数据位置 · 导出备份 · 恢复与清理'),
        e('recovery_document', '保留副本与旧环境', '旧环境、插件原件和隔离项，不自动删除'),
        e('recovery_wrench', '环境修复与数据救援', '只读救援导出 · 恢复中断提交 · 回退运行时'),
        e('recovery_refresh', '重置配置', '保留原件，插件、对话和凭据不受影响'),
      ], 'ws-group')}
    </div></div>`;
  }

  // ---------------------------------------------------------------- 安装与环境
  const STEPS = ['Ubuntu 环境', '离线基础工具', 'Node.js 24', 'pnpm', 'DeepSeek Harness', '启动与环境检查'];
  function installDetail(env) {
    const failed = env.state === 'failed';
    const split = detailW(env) - 40 >= 680;   // AdaptiveColumns：自身宽 = 详情宽 − 2×shell_page_pad
    // 失败示例：第 4 步失败，其后未执行（推断的典型序列）。
    const st = failed ? ['ok', 'ok', 'ok', 'bad', 'warn', 'warn'] : ['', '', '', '', '', ''];
    const label = { ok: '成功', bad: '失败', warn: '未完成', '': '未检查' };
    const tone = { ok: 'good', bad: 'bad', warn: 'warn', '': '' };
    const subs = failed ? ['基础版本 24.04 · 完整', 'curl / git / 证书 已就绪', 'v24 · 可执行', 'pnpm 链接缺失', '', ''] : [];
    const steps = STEPS.map((t, i) => `<div class="in-step ${tone[st[i]]}" data-touch>
        <span class="in-num">${st[i] === 'ok' ? '✓' : st[i] === 'bad' ? '✕' : i + 1}</span>
        <div class="w"><div class="t">${esc(t)}</div>${subs[i] ? `<div class="s">${esc(subs[i])}</div>` : ''}</div>
        <span class="in-pill ${tone[st[i]]}">${label[st[i]]}</span>${chev()}</div>`).join('<div class="in-div"></div>');
    const tool = (ic, t, s, bad) => `<div class="in-tool${bad ? ' bad' : ''}" data-touch><span class="tl${bad ? ' bad' : ''}">${icon(ic)}</span>
        <div class="w"><div class="t">${esc(t)}</div><div class="s">${esc(s)}</div></div>${chev()}</div>`;
    const log = failed
      ? '[1/6] Ubuntu 环境 … 成功\n[2/6] 离线基础工具 … 成功\n[3/6] Node.js 24 … 成功\n[4/6] pnpm … 失败：pnpm 链接缺失\n已停止后续步骤；原环境保持不变。'
      : '';
    const a = `<div class="a">
      <div class="in-hero">
        <div class="top"><span class="in-orb ${failed ? 'bad' : 'good'}">${icon(failed ? 'onb_x' : 'onb_check')}</span><div class="w">
          <div class="t">${failed ? '1 个组件需要处理' : '环境可用'}</div>
          <div class="s">${failed ? 'pnpm 检查未通过' : '检查环境，或按需修复缺项'}</div>
          ${failed ? '<div class="p">仅检查 · 总耗时 9 秒</div>' : ''}</div></div>
        <div class="in-chips"><span class="in-chip">Ubuntu <b>24.04</b></span><span class="in-chip">dsh <b>0.2.1-rc.5</b></span><span class="in-chip">App <b>0.2.1-rc.3 · 279</b></span></div>
        ${failed ? `<div class="in-meter">${st.map(s => `<i class="${s}"></i>`).join('')}</div>` : ''}
        <div class="in-acts">${h.btn('检查全部', 'pri')}${h.btn('按需修复')}</div>
        ${failed ? '<div class="in-err">pnpm：未找到可执行的 pnpm 链接。可点「按需修复」只重装此组件。</div>' : ''}
      </div>
      <div class="in-sec">${secLabel('六个安装步骤', 'steps', 'ic')}<span class="n">${failed ? '3/6' : ''}</span></div>
      <div class="st-group">${steps}</div>
    </div>`;
    const b = `<div class="b">
      <div class="in-sec">${secLabel('维护与恢复', 'maintenance', 'ic')}</div>
      <div class="st-group">
        ${tool('recovery_refresh', '更新受管运行时', '只更新随包组件，保留个人数据')}<div class="in-div t54"></div>
        ${tool('ui_shield', '环境修复与恢复', '查看启动依赖与中断维护')}<div class="in-div t54"></div>
        ${tool('recovery_clock', '首次安装测速', '独立目录完整计时，不影响当前环境')}<div class="in-div t54"></div>
        ${tool('recovery_wrench', '备份并重建环境', '先完整备份再重建，失败自动回切', true)}
      </div>
      <div class="in-sec">${secLabel('检查与安装日志', 'log', 'ic')}</div>
      <div class="st-group">
        <div class="in-tool" data-touch style="min-height:52px;padding-top:6px;padding-bottom:6px"><span class="tl">${icon('section_log')}</span>
          <div class="w"><div class="t">${failed ? '收起日志' : '查看日志'}</div><div class="last">${failed ? '已停止后续步骤；原环境保持不变。' : '执行后将在此逐行显示脱敏输出。'}</div></div>
          ${failed ? `<button class="ibtn" aria-label="复制日志">${icon('ui_copy')}</button>` : ''}<span class="chev" style="${failed ? 'transform:rotate(90deg)' : ''}">${icon('ui2_chevron')}</span></div>
        ${failed ? `<div class="in-log">${esc(log)}</div>` : ''}
      </div>
    </div>`;
    return `<div class="scroll"><div class="st-pg">
      <div class="in-head"><span class="tl">${icon('ui2_box')}</span><h1>安装与环境</h1></div>
      <div class="in-cols${split ? ' split' : ''}">${a}${b}</div>
    </div></div>`;
  }

  // ---------------------------------------------------------------- 设备能力授权
  function grantsDetail(env) {
    const w = detailW(env);
    const base = 14;
    const two = w - 2 * base >= 640;                              // GrantSummary.columns
    const side = Math.max(base, Math.floor((w - (two ? 1040 : 640)) / 2)); // GrantSummary.sidePadding
    const pill = (t, v, tone) => `<div class="gr-pill" data-touch><div class="a"><span class="dot ${tone}"></span><span>${t}</span></div><div class="b">${v}</div></div>`;
    const seg = (items, on) => `<div class="gr-seg">${items.map(([t, tone], i) => `<span class="${i === on ? 'on' : ''}"><span class="dot ${tone}"></span>${t}</span>`).join('')}</div>`;
    const global = `<div class="gr-sec" id="g-global">${secLabel('全局权限', 'global', 'ic')}
      <div class="gr-card"><div class="gr-sw"><span>不再询问 DSHA 安全确认</span>${h.sw(false)}</div>
        <div class="gr-st"><span class="m">已关闭：按各项 DSHA 确认规则</span></div></div></div>`;
    const channels = `<div class="gr-sec">${secLabel('设备执行通道', 'channels', 'ic')}
      <div class="gr-card">
        <div class="gr-sw"><span>允许 Root Shell</span>${h.sw(false)}</div>
        <div class="gr-st"><span class="m">Root 未启用。开启后才会查找 su 并请求授权。</span><button class="gr-tb">验证</button></div>
        ${seg([['严格', 'good'], ['放宽', 'warn'], ['完全放宽', 'bad']], 0)}
        <div class="gr-div"></div>
        <div class="gr-title"><span>Shizuku</span><button class="gr-tb">授权</button><button class="gr-tb">打开</button></div>
        <div class="gr-st"><span class="m">Shizuku 未授权</span></div>
        <div class="gr-div"></div>
        <div class="gr-sw"><span>ADB 设备通道</span>${h.sw(false)}</div>
        <div class="gr-st"><span class="m">ADB 已关闭。不用无线调试就保持关闭。</span><button class="gr-tb">配对</button></div>
      </div></div>`;
    // 撤销预授权：原生 minHeight 36dp（fragment_device_grants.xml screen_revoke）；规则行 40dp。
    const screen = `<div class="gr-sec">
      <div class="gr-lab">${secLabel('屏幕操作', 'screen', 'ic')}<button class="gr-tb bad" data-dense>撤销预授权</button></div>
      <div class="gr-card" style="padding-top:8px">
        <div class="gr-st"><span class="m">读屏与点按通过 DSHA 无障碍服务执行，每次操作前按下方确认方式询问。</span></div>
        <div class="gr-a11y">未开启</div>
        ${h.btn('设置屏幕操作 · 两步完成', 'pri block')}
        <div class="gr-sw" style="margin-top:4px"><span>暂停助手屏幕操作</span>${h.sw(false)}</div>
        <div class="gr-div" style="margin-top:2px"></div>
        <div style="margin-top:10px;font-size:13px;font-weight:700">确认方式</div>
        <div style="margin-top:6px">${seg([['全部手动', 'good'], ['按应用', 'good'], ['全部允许', 'warn']], 0)}</div>
        <div class="gr-st"><span class="m">每次读屏和操作前都询问你 · 推荐</span></div>
      </div></div>`;
    const system = `<div class="gr-sec">${secLabel('系统权限', 'system', 'ic')}
      <div class="gr-group">
        <div class="gr-row" data-touch><div class="w"><div class="t">管理文件访问权限</div><div class="s">未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件</div></div>${chev()}</div>
        <div class="gr-rdiv"></div>
        <div class="gr-row" data-touch><div class="w"><div class="t">设置电池优化</div><div class="s">后台断连时，可允许 DSHA 后台运行并减少电池限制。厂商仍可能暂停后台；恢复前先确认无线调试已开启，再点「验证已有连接」。</div></div>${chev()}</div>
      </div></div>`;
    const sensitive = `<div class="gr-sec">${secLabel('独立敏感能力', 'sensitive', 'ic')}
      <div class="gr-card">
        <div class="gr-sw"><span>短信读取</span>${h.sw(false)}</div>
        <div class="gr-st" style="padding-bottom:8px"><span class="m">已关闭 · 每次查询都需确认</span></div>
        <div class="gr-div" style="margin-top:0"></div>
        <div class="gr-sw"><span>允许读取位置</span>${h.sw(false)}</div>
        <div class="gr-div" style="margin-top:0"></div>
        <div class="gr-sw"><span>传感器与手电</span>${h.sw(false)}</div>
      </div></div>`;
    const body = two
      ? `<div class="gr-cols"><div>${global}${channels}${system}</div><div>${screen}${sensitive}</div></div>`
      : `${global}${channels}${screen}${system}${sensitive}`;
    return `<div class="gr-head" style="--gr-side:${side}px">
        ${pill('全局', '关闭', 'good')}${pill('Root', '未启用', '')}${pill('Shizuku', '未授权', 'warn')}${pill('屏幕', '未设置', 'warn')}
        <button class="ibtn" aria-label="页面说明">${icon('onb_info')}</button></div>
      <div class="scroll"><div class="gr-body" style="--gr-side:${side}px">${body}</div></div>`;
  }

  // ---------------------------------------------------------------- 悬浮条
  function overlayDetail(env) {
    const need = env.state === 'permission';
    const chips = (list, on, eq) => `<div class="ov-chips${eq ? ' eq' : ''}">${list.map((t, i) => `<span class="chip${i === on ? ' on' : ''}">${t}</span>`).join('')}</div>`;
    const slide = (n, pct, v) => `<div class="ov-slide"><span class="n">${n}</span><span class="bar" data-touch><i style="width:calc((100% - 16px) * ${pct})"></i><b style="left:calc(8px + (100% - 16px) * ${pct})"></b></span><span class="v">${v}</span></div>`;
    const sws = ['#11141A', '#000000', '#0D1B2A', '#102A17', '#1E1030'];
    return `<div class="scroll"><div class="st-pg">
      ${h.title('悬浮条', '在其他应用中，轻量查看任务进展。')}
      <div class="card">
        <div class="ov-sw"><span>显示悬浮条</span>${h.sw(!need)}</div>
        <div class="ov-perm${need ? ' need' : ''}"${need ? ' data-touch' : ''}>${need ? '需要悬浮窗权限 · 去开启 ›' : '悬浮窗权限已开启'}</div>
      </div>
      <div class="card">
        <div class="ov-ph"><span>样式预览</span>${h.btn('在屏幕上预览', 'quiet')}</div>
        <div class="ov-sample" style="background:rgba(17,20,26,.85)">DSHA · 样式预览\n正在执行命令：ls -la\n文件已整理完成，可以继续下一步。</div>
      </div>
      ${h.label('外观')}
      <div class="card">
        ${slide('不透明度', 0.8125, '85%')}<div class="ov-line"></div>
        ${slide('文字大小', 0.4286, '12sp')}<div class="ov-line"></div>
        <div class="ov-ct"><span>最多显示行数</span></div>${chips(['1', '2', '3', '4', '5', '6'], 2, true)}<div class="ov-line"></div>
        <div class="ov-ct"><span>停留时间</span></div>${chips(['2 秒', '5 秒', '6 秒', '10 秒', '15 秒', '30 秒', '60 秒'], 2)}<div class="ov-line"></div>
        <div class="ov-ct"><span>背景样式</span><span class="v">深灰蓝</span></div>
        <div class="ov-sws">${sws.map((c, i) => `<span class="ov-sw-ring${i === 0 ? ' on' : ''}" data-touch><i style="background:${c}"></i></span>`).join('')}</div>
      </div>
      ${h.label('显示内容')}
      <div class="card">
        <div class="ov-sw"><span>显示思考过程</span>${h.sw(false)}</div><div class="ov-line"></div>
        <div class="ov-sw"><span>显示执行命令</span>${h.sw(true)}</div><div class="ov-line"></div>
        <div class="ov-sw"><span>在悬浮条上确认命令</span>${h.sw(true)}</div>
        <div class="st-hint" style="padding-bottom:6px"><span>需要确认的命令可直接在悬浮条上批准或拒绝。</span>${icon('onb_info')}</div>
      </div>
      <div class="ov-auto">改动会立即保存。</div>
    </div></div>`;
  }

  // ---------------------------------------------------------------- 任务状态上岛
  const LEVELS = [[5, '等你确认', '等你确认', '需要你回答，排最前'], [4, '正在停止', '正在停止', '你已点停止，先显示结果'],
    [3, '执行工具', '执行·read', '胶囊带工具名'], [2, '生成回复', '思考中', ''], [1, '其他运行中', '运行中', '']];
  function islandDetail(env) {
    const w = detailW(env);
    const two = w >= 700;
    const side = two ? Math.max(28, Math.floor((w - 880) / 2)) : 16;
    const running = env.state !== 'idle';
    const prev = running
      ? `<div class="il-strip"><span class="clk">09:41</span><span class="il-capsule">执行·read</span></div>
         <div class="il-notice"><div class="t">整理项目文档</div><div class="s">正在执行工具 · read · file</div></div>`
      : `<div class="il-strip"><span class="clk">09:41</span></div>
         <div class="il-note">现在没有运行中的任务。任务开始后，这里显示回到桌面时的样子。</div>`;
    const levels = LEVELS.map(([n, t, s, why]) => {
      const on = running && n === 3;
      return `<div class="il-lv${on ? ' on' : ''}"><span class="no">${n}</span><div class="w"><div class="a"><span>${t}</span><span class="smp">${s}</span></div>${why ? `<div class="why">${why}</div>` : ''}</div><span class="c">${on ? '在岛上' : ''}</span></div>`;
    }).join('');
    // ConfigChip 36dp（系统设置 / 支持的设备），原生如此。
    return `<div class="scroll"><div class="il-wrap" style="--il-side:${side}px">
      <div class="il-intro">${hintI('任务运行时把当前阶段显示在状态栏胶囊上；在 DSHA 页面里时不显示。')}</div>
      <div class="il-cols${two ? ' two' : ''}">
        <div>
          <div class="cf-lab"><span>当前状态</span></div>
          <div class="cf-card">
            <div class="il-prev"><div class="il-cap">回到桌面后（示意）</div>${prev}</div>
            <div class="st-div full"></div>
            <div class="il-state"><span class="dot good"></span><div class="w"><div class="t">已允许</div><div class="s">回到桌面后，运行中的任务会显示在状态栏胶囊上。</div><div class="api">API 36</div></div>
              <button class="cf-chip" data-dense>系统设置</button></div>
          </div>
          <div class="cf-lab"><span>行为</span></div>
          <div class="cf-card">
            <div class="cf-row"><div class="w"><div class="t">任务状态上岛</div><div class="s">多个任务同时运行时，只显示优先级最高的一个</div></div>${h.sw(true)}</div>
            <div class="st-div full"></div>
            <div class="cf-row"><div class="w"><div class="t">用系统计时器显示耗时</div><div class="s">通知显示开始时间</div></div>${h.sw(false)}</div>
          </div>
        </div>
        <div>
          <div class="il-lab"><span class="cf-lab">上岛顺序</span><span class="h">同级先开始的优先 · 结束后保留 8 秒</span></div>
          <div class="cf-card">${levels}</div>
          <div class="cf-links"><button class="cf-chip link" data-dense>支持的设备</button></div>
        </div>
      </div>
    </div></div>`;
  }

  // ---------------------------------------------------------------- 关于
  function aboutDetail() {
    return `<div class="scroll"><div class="st-pg">
      ${h.title('关于 DSHA')}
      <div class="ab-mark"><span>${icon('ui2_prompt')}</span></div>
      <h2 class="ab-brand">DSHA</h2>
      <div class="ab-ver">DeepSeek Harness for Android<br>0.2.1-rc.3-20261102.1430-dsh0.2.1-rc.5 · 279</div>
      <div class="card">${h.kv('包名', 'io.github.sunsetrne.dsha', true)}${h.kv('当前版本', '标准版 · Android 11+')}${h.kv('DSH', '0.2.1-rc.5', true)}${h.kv('架构', 'arm64-v8a', true)}${h.kv('开源许可', 'MIT')}</div>
      ${h.label('了解更多')}
      <div class="card ab-links">
        ${h.entry({ icon: 'ui_link', title: '项目仓库', sub: 'github.com/DSH-APP/DSHA' })}<div class="divider"></div>
        ${h.entry({ icon: 'ui_chat', title: '交流与反馈', sub: 'QQ群 975836806 · 问题反馈' })}<div class="divider"></div>
        ${h.entry({ icon: 'recovery_document', title: '开源许可', sub: 'DSHA 与随包第三方组件' })}
      </div>
    </div></div>`;
  }

  // ---------------------------------------------------------------- 注册
  const ctx = () => '偏好与管理';
  DSHA.page({
    id: 'settings', group: '设置', title: '设置', shell: 'main', tab: 'settings',
    source: 'fragment_settings.xml · SettingsFragment · SettingsPanes',
    states: [{ id: 'default', title: '默认' }],
    context: ctx,
    // w840 首次进入默认选中第一项（DEFAULT_KEY = tab0「安装与环境」）。
    render: env => env.w840 ? frame(env, 'install', installDetail(env)) : `<div class="st-pane">${settingsList(env, null)}</div>`,
  });
  const SUBS = [
    { id: 'settings-config', key: 'config', title: '运行配置', source: 'fragment_config.xml · ConfigFragment · config_page.xml',
      states: [{ id: 'saved', title: '已保存' }, { id: 'dirty', title: '有未保存改动' }], body: configDetail },
    { id: 'settings-workspace', key: 'workspace', title: '数据与备份', source: 'fragment_workspace.xml · WorkspaceFragment · item_data_entry.xml',
      states: [{ id: 'protected', title: '数据已保护' }, { id: 'never', title: '从未备份' }], body: workspaceDetail },
    { id: 'settings-install', key: 'install', title: '安装与环境', source: 'fragment_install.xml · InstallFragment · AdaptiveColumns',
      states: [{ id: 'idle', title: '未检查' }, { id: 'failed', title: '检查失败' }], body: installDetail },
    { id: 'settings-grants', key: 'grants', title: '设备能力授权', source: 'fragment_device_grants.xml · DeviceGrantsFragment · FullPermissionControls · GrantSummary',
      states: [{ id: 'default', title: '默认（未授权）' }], body: grantsDetail },
    { id: 'settings-overlay', key: 'overlay', title: '悬浮条', source: 'OverlayFragment · CardPage · OverlayController',
      states: [{ id: 'granted', title: '已授权' }, { id: 'permission', title: '缺悬浮窗权限' }], body: overlayDetail },
    { id: 'settings-island', key: 'island', title: '任务状态上岛', source: 'fragment_island.xml · IslandFragment · item_island_level.xml · island_page.xml',
      states: [{ id: 'running', title: '任务在岛上' }, { id: 'idle', title: '无任务' }], body: islandDetail },
    { id: 'settings-about', key: 'about', title: '关于 DSHA', source: 'AboutFragment · CardPage',
      states: [{ id: 'default', title: '默认' }], body: aboutDetail },
  ];
  SUBS.forEach(s => DSHA.page({
    id: s.id, group: '设置', title: s.title, shell: 'main', tab: 'settings', source: s.source, states: s.states,
    context: ctx,
    // MainActivity.updateToolbar：单栏子页 = 返回 + 页面标题；双栏详情（未嵌套）= 品牌图标 + DSHA。
    back: env => !env.w840,
    appbarTitle: env => env.w840 ? 'DSHA' : s.title,
    render: env => frame(env, s.key, s.body(env)),
  }));
})();
