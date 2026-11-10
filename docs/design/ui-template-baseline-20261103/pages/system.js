/*
 * 任务与系统 · 8 个独立 Activity（离线预览，不连接设备或网络）。
 * 来源（origin/main build279）：
 *   ui/BackgroundTasksActivity.java · ui/AgentTasksActivity.java · AgentTaskNotifications.phase
 *   ui/FileTransfersActivity.java · ui/TransferDetailView.java · ui/TransferStepsView.java · ui/TransferUi.java
 *   ui/TransferText.java · util/TransferListModel.java
 *   ui/CommunityActivity.java · ui/ThemeActivity.java · ui/CardPage.java · util/UiThemePalette.java
 *   vscreen/VirtualScreenActivity.java · vscreen/VirtualScreenPreviewView.java
 *   assistant/AssistantActivity.java · assistant/AssistantWindow.java · assistant/AssistantService.taskGuidance
 *   ui/WebPreviewActivity.java · res/layout/activity_web_preview.xml · values/ui_strings.xml
 * 布局判据（只用源码里的阈值）：
 *   - BackgroundTasks：screenWidthDp < 600 为手机；内容边距 max(手机 16 / 其他 32, (w-720)/2)；非手机「相关页面」两列。
 *   - FileTransfers：w >= 840（SettingsPanes.TWO_PANE_DP）双栏，列表固定 420dp；600 <= w < 840 平板竖屏；否则手机。
 *     列表边距 centeredPad(760, 手机 12 / 平板 32)，详情 centeredPad(720, 手机 16 / 平板 40)；
 *     detailWide：非手机且 (双栏 w-420-64 | min(w-80,720)) >= 600 时保存位置与文件信息并排。
 *   - VirtualScreen：预览高 = clamp(屏高 × 0.62, 280, 720)。其余页面无宽度分支（原生如此）。
 * 图标：ic_section_maintenance 只是 ic_recovery_wrench 的 layer-list 包装，这里直接用 recovery_wrench。
 * 示例数据均为虚构、已脱敏。
 */
(function () {
  const { h } = DSHA;
  const esc = h.esc, icon = h.icon;
  const css = `
  /* ---------- 共用：TransferUi 风格的自绘顶栏与小件 ---------- */
  .sy-col{flex:1;min-height:0;display:flex;flex-direction:column}
  .sy-head{flex:none;display:flex;align-items:center;min-height:60px;padding:2px 6px 2px 4px}
  .sy-head .tt{flex:1;min-width:0;margin-left:2px}
  .sy-head .t{font-size:19px;font-weight:700;line-height:1.25;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .sy-head .s{margin-top:3px;font-size:12.5px;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .sy-ib{flex:none;width:48px;height:48px;border:0;background:none;border-radius:12px;display:grid;place-items:center;color:var(--text-2);font-size:24px;padding:0}
  .sy-ib.back{color:var(--text);border-radius:24px}
  .sy-sec{font-size:12.5px;font-weight:700;letter-spacing:.02em;color:var(--muted);line-height:1.3}
  .sy-card{background:var(--surface);border:1px solid var(--line);border-radius:18px;overflow:hidden}
  .sy-tile{flex:none;width:36px;height:36px;border-radius:12px;display:grid;place-items:center;font-size:20px;background:var(--raised);color:var(--text-2)}
  .sy-tile.acc{background:var(--accent-tonal);color:var(--accent)}
  .sy-chev{flex:none;font-size:18px;color:var(--muted);margin-left:8px;transform:rotate(-90deg)}
  .sy-spin{flex:none;width:18px;height:18px;border-radius:50%;border:2px solid var(--accent-tonal);border-top-color:var(--accent)}
  .sy-div{height:1px;background:var(--line)}

  /* ---------- BackgroundTasksActivity ---------- */
  .bk{padding:4px var(--bt-pad) 32px}
  .bk .sy-sec{margin-left:4px}
  .bk-task{display:flex;align-items:center;min-height:68px;padding:12px 14px}
  .bk-task .w,.bk-link .w{flex:1;min-width:0;margin:0 8px 0 14px}
  .bk-task .n{font-size:15px;font-weight:700;line-height:1.3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .bk-task .m{margin-top:4px;font-size:12.5px;line-height:1.45;color:var(--text-2);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .bk-empty{display:flex;flex-direction:column;align-items:center;padding:28px 24px;text-align:center}
  .bk-empty .ring{width:52px;height:52px;border-radius:50%;background:var(--good-soft);color:var(--good);display:grid;place-items:center;font-size:24px}
  .bk-empty .hd{margin-top:14px;font-size:15px;font-weight:700}
  .bk-empty .bd{margin-top:6px;font-size:13px;line-height:1.5;color:var(--text-2)}
  .bk-link{display:flex;align-items:center;min-height:60px;padding:10px 10px 10px 14px}
  .bk-link .w{margin:0 0 0 12px}
  .bk-link .n{font-size:14.5px;font-weight:500;line-height:1.3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .bk-link .m{margin-top:3px;font-size:12px;line-height:1.45;color:var(--muted)}
  .bk-pair{display:flex;gap:12px;align-items:stretch}
  .bk-pair>.sy-card{flex:1;min-width:0}
  .bk-pair+.bk-pair{margin-top:12px}
  .bk-note{margin:16px 4px 0;font-size:12.5px;line-height:1.6;color:var(--muted)}

  /* ---------- AgentTasksActivity（CardPage，文字之间原生无间距） ---------- */
  .at .card{padding:12px 16px}
  .at-t{font-size:13px;color:var(--muted);line-height:1.3}
  .at-p{font-size:16px;color:var(--text);line-height:1.3}
  .at-d{font-size:13px;color:var(--muted);line-height:1.3}
  .at-e{font-size:12px;color:var(--text);line-height:1.3}
  .at .btn.block{margin-top:10px}
  .at-empty{font-size:14px;color:var(--text)}

  /* ---------- FileTransfersActivity ---------- */
  .ft-body{flex:1;min-height:0;display:flex}
  .ft-list{flex:none;width:420px;border-right:1px solid var(--line)}
  .ft-detail{flex:1;min-width:0}
  .ft-lc{padding:4px var(--ft-lpad) 32px}
  .ft-two .ft-lc{padding:8px 16px 28px}
  .ft-dc{padding:4px var(--ft-dpad) 40px}
  .ft-two .ft-dc{padding:20px 32px 40px}
  .ft-seg{display:flex;gap:2px;padding:3px;border-radius:13px;background:var(--raised);margin-top:2px}
  .ft-seg>span{flex:1;min-width:0;min-height:40px;display:flex;align-items:center;justify-content:center;gap:.2em;padding:0 4px;border-radius:9px;font-size:13px;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .ft-seg>span b{font-weight:700;color:var(--muted)}
  .ft-seg>.on{background:var(--surface);box-shadow:inset 0 0 0 1px var(--line);color:var(--text);font-weight:700}
  .ft-seg>.on b{color:var(--accent)}
  .ft-seg b.bad{color:var(--bad)}
  .ft-lab{margin-top:18px;padding:0 8px}
  .ft-grp{margin-top:8px}
  .ft-row{display:flex;align-items:center;min-height:68px;padding-right:6px}
  .ft-row.sel{background:var(--accent-soft)}
  .ft-hit{flex:1;min-width:0;display:flex;align-items:center;padding:12px 4px 12px 12px;border-radius:12px}
  .ft-tile{flex:none;display:grid;place-items:center;border-radius:12px}
  .ft-tx{flex:1;min-width:0;margin-left:12px}
  .ft-name{font-size:15px;font-weight:700;line-height:1.3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .ft-meta{margin-top:4px;font-size:12.5px;line-height:1.45;color:var(--text-2);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .ft-meta b{font-weight:700}
  .ft-bar{margin-top:8px;height:4px;border-radius:2px;background:var(--raised);overflow:hidden}
  .ft-bar>i{display:block;height:100%;border-radius:2px;background:var(--accent)}
  .ft-bar.big{height:6px;border-radius:3px;margin-top:12px}
  .ft-acts{flex:none;display:flex;align-items:center}
  .ft-tb{flex:none;display:inline-flex;align-items:center;justify-content:center;gap:6px;height:44px;min-width:44px;border:0;border-radius:22px;background:var(--accent-tonal);color:var(--accent);font-size:20px;padding:0;margin-left:4px}
  .ft-tb.txt{font-size:13.5px;font-weight:700;padding:0 14px;white-space:nowrap}
  .ft-tb.txt .i{font-size:18px}
  .ft-tb.plain{background:none}
  .ft-more{flex:none;width:44px;height:48px;border:0;background:none;border-radius:12px;display:grid;place-items:center;color:var(--text-2);font-size:24px;padding:0}
  .ft-foot{padding:18px 10px 0;font-size:12.5px;color:var(--muted);line-height:1.5}
  .ft-learn{display:inline-flex;align-items:center;height:40px;border:0;background:none;padding:0;color:var(--accent);font-size:13px;font-weight:700}
  .ft-empty{display:flex;flex-direction:column;align-items:center;padding:72px 24px 24px;text-align:center}
  .ft-empty .ring{width:72px;height:72px;border-radius:50%;background:var(--raised);color:var(--muted);display:grid;place-items:center;font-size:32px}
  .ft-empty .hd{margin-top:16px;font-size:16px;font-weight:700}
  .ft-empty .bd{margin-top:8px;font-size:13.5px;color:var(--text-2)}
  .ft-hint{padding-top:160px;text-align:center;font-size:14px;color:var(--muted)}
  .ft-hero{display:flex;align-items:center;padding:4px 0 0 4px}
  .ft-hero .tx{flex:1;min-width:0;margin-left:14px}
  .ft-hero .nm{font-size:19px;font-weight:700;line-height:1.3;overflow-wrap:anywhere}
  .ft-hero .sub{display:flex;align-items:center;margin-top:8px;min-width:0}
  .ft-chip{flex:none;padding:5px 10px;border-radius:999px;font-size:12px;font-weight:700;line-height:1.2}
  .ft-hero .inf{flex:1;min-width:0;margin-left:8px;font-size:13px;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .ft-pc{padding:16px;margin-top:14px}
  .ft-big{display:flex;align-items:flex-end}
  .ft-big .pct{font-size:34px;font-weight:700;letter-spacing:-.02em;line-height:1.1}
  .ft-big .by{flex:1;min-width:0;margin:0 0 4px 10px;font-size:13.5px;color:var(--text-2)}
  .ft-nums{display:flex;margin-top:8px;font-size:12.5px;color:var(--text-2)}
  .ft-nums>span{flex:1}.ft-nums>span+span{text-align:end}
  .ft-sh{display:flex;align-items:center}
  .ft-sh .rg{flex:none;width:32px;height:32px;border-radius:50%;display:grid;place-items:center;font-size:18px}
  .ft-sh .lb{flex:1;min-width:0;margin-left:10px;font-size:15.5px;font-weight:700}
  .ft-bd{margin-top:8px;font-size:13.5px;line-height:1.6;color:var(--text-2)}
  .ft-steps{display:flex;margin-top:14px;padding-top:16px;border-top:1px solid var(--line)}
  .ft-step{flex:1;min-width:0;position:relative;display:flex;flex-direction:column;align-items:center}
  .ft-step+.ft-step::before{content:"";position:absolute;top:10px;height:2px;border-radius:1px;left:calc(-50% + 15px);right:calc(50% + 15px);background:var(--line-strong)}
  .ft-step+.ft-step.r::before{background:var(--accent)}
  .ft-step .nd{width:22px;height:22px;border-radius:50%;display:grid;place-items:center;background:var(--surface);box-shadow:inset 0 0 0 2px var(--line-strong)}
  .ft-step.done .nd{background:var(--accent);box-shadow:none;color:var(--on-accent)}
  .ft-step.failed .nd{background:var(--bad);box-shadow:none;color:var(--surface)}
  .ft-step.cur .nd{box-shadow:inset 0 0 0 2px var(--accent)}
  .ft-step.cur .nd::after{content:"";width:8.4px;height:8.4px;border-radius:50%;background:var(--accent)}
  .ft-step.paused .nd{box-shadow:inset 0 0 0 2px var(--warn)}
  .ft-step.paused .nd::after{content:"";width:8.4px;height:8.4px;border-radius:50%;background:var(--warn)}
  .ft-step .nd svg{width:22px;height:22px}
  .ft-step .sl{margin-top:8px;font-size:12px;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:calc(100% - 4px)}
  .ft-step.todo .sl{color:var(--muted)}
  .ft-step.cur .sl,.ft-step.paused .sl{font-weight:700}
  .ft-step.failed .sl{font-weight:700;color:var(--bad)}
  .ft-pa{display:flex;gap:10px;margin-top:12px}
  .ft-pa>button{flex:1;min-width:0;display:inline-flex;align-items:center;justify-content:center;gap:6px;height:52px;border:0;border-radius:26px;font-size:14.5px;font-weight:700;padding:0 16px;background:var(--accent);color:var(--on-accent)}
  .ft-pa>button .i{font-size:20px}
  .ft-cols{display:flex;gap:12px;align-items:flex-start;margin-top:12px}
  .ft-cols>*{flex:1;min-width:0;margin-top:0!important}
  .ft-c{padding:16px;margin-top:12px}
  .ft-loc{display:flex;align-items:center;margin-top:12px}
  .ft-loc .fd{flex:none;width:44px;height:44px;border-radius:12px;background:var(--raised);color:var(--text-2);display:grid;place-items:center;font-size:22px}
  .ft-loc .tx{flex:1;min-width:0;margin-left:12px}
  .ft-loc .p{font-size:15px;font-weight:700;overflow-wrap:anywhere}
  .ft-loc .n{margin-top:4px;font-size:12.5px;line-height:1.45;color:var(--text-2)}
  .ft-flow{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}
  .ft-ob{display:inline-flex;align-items:center;gap:6px;height:40px;padding:0 16px;border-radius:20px;border:1px solid var(--line-strong);background:var(--surface);color:var(--text);font-size:13px;font-weight:700}
  .ft-ob .i{font-size:18px}
  .ft-kv{display:flex;align-items:flex-start;padding:11px 0;font-size:14px}
  .ft-kv+.ft-kv{border-top:1px solid var(--line)}
  .ft-kv .k{flex:none;color:var(--text-2)}
  .ft-kv .v{flex:1;min-width:0;margin-left:16px;text-align:end;font-weight:700;overflow-wrap:anywhere}
  .ft-mg{margin-top:12px;padding:14px 0 6px}
  .ft-mg>.sy-sec{padding:0 16px 4px}
  .ft-mrow{display:flex;align-items:center;min-height:60px;padding:10px 16px}
  .ft-mrow>.i{font-size:22px;color:var(--bad)}
  .ft-mrow .tx{flex:1;min-width:0;margin-left:14px}
  .ft-mrow .l{font-size:14.5px;font-weight:700;color:var(--bad)}
  .ft-mrow .n{margin-top:3px;font-size:12.5px;line-height:1.45;color:var(--text-2)}

  /* ---------- CommunityActivity（旧式按钮与 R.color.text/primary） ---------- */
  .cm-bar{flex:none;display:flex;align-items:center}
  .cm-tb{flex:none;min-height:48px;padding:12px 16px;border:0;border-radius:10px;background:none;color:var(--accent);font-size:14px;font-weight:500}
  .cm-title{flex:1;min-width:0;font-size:19px;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .cm-status{flex:none;padding:3px 6px;font-size:14px;line-height:1.4;color:var(--muted)}
  .cm-web{flex:1;min-height:0;background:var(--bg);position:relative}
  .cm-err{position:absolute;inset:0;background:#FFFFFF;color:#5F6368;display:flex;flex-direction:column;justify-content:center;padding:0 28px;font-size:14px;line-height:1.5}
  .cm-err b{font-size:18px;color:#202124;margin-bottom:8px}

  /* ---------- ThemeActivity ---------- */
  .th-modes{display:flex;gap:10px;margin-bottom:14px}
  .th-tile{flex:1;min-width:0;display:flex;flex-direction:column;align-items:center;padding:6px 6px 10px;border-radius:16px;background:var(--surface);box-shadow:inset 0 0 0 1px var(--line)}
  .th-tile.on{box-shadow:inset 0 0 0 2px var(--accent)}
  .th-pv{position:relative;width:100%;height:96px;border-radius:10px;overflow:hidden}
  .th-pv .half{position:absolute;inset:0}
  .th-pv .half.r{clip-path:inset(0 0 0 50%)}
  .th-pv .half.l{clip-path:inset(0 50% 0 0)}
  .th-pv i{position:absolute;display:block}
  .th-pv .ol{position:absolute;inset:0;border-radius:10px}
  .th-tile .nm{margin-top:10px;font-size:13px;color:var(--text);text-align:center;line-height:1.3}
  .th-tile.on .nm{color:var(--accent);font-weight:700}
  .th-sws{display:flex;padding:6px 0 2px}
  .th-sw{flex:1;min-width:0;display:flex;flex-direction:column;align-items:center;padding:8px 2px;border-radius:10px}
  .th-ring{width:40px;height:40px;border-radius:50%;padding:2px;box-shadow:inset 0 0 0 1px var(--line)}
  .th-sw.on .th-ring{box-shadow:inset 0 0 0 2px var(--text)}
  .th-ring>i{display:block;width:100%;height:100%;border-radius:50%}
  .th-sw.on .th-ring>i{box-shadow:inset 0 0 0 3px var(--surface)}
  .th-sw .nm{margin-top:8px;font-size:12px;color:var(--text-2);text-align:center;line-height:1.3}
  .th-sw.on .nm{color:var(--text);font-weight:700}
  .th-note{padding:10px 0 4px;font-size:12px;line-height:1.55;color:var(--muted)}
  .th-row{display:flex;align-items:center;min-height:56px;padding:10px 0;border-radius:10px}
  .th-row .w{flex:1;min-width:0;margin-right:12px}
  .th-row .t{font-size:14px;font-weight:700;line-height:1.3}
  .th-row .s{margin-top:4px;font-size:12px;line-height:1.5;color:var(--muted)}
  .th-row .ck{font-size:20px;color:var(--accent)}
  .th-row .ck.off{visibility:hidden}
  .th-row.sync{padding:8px 0}
  .th-m3{flex:none;position:relative;width:52px;height:32px;border-radius:16px;background:var(--accent)}
  .th-m3::after{content:"";position:absolute;top:4px;right:4px;width:24px;height:24px;border-radius:50%;background:var(--on-accent)}
  .th-foot{flex:none;height:24px}

  /* ---------- VirtualScreenActivity（未套用 shell 样式的旧式控件） ---------- */
  .vs{padding:10px 12px 12px}
  .vs-row{display:flex}
  .vs-row>button{flex:1;min-width:0;height:40px;padding:0 5px;border-radius:13px;border:1px solid var(--line);background:var(--surface);color:var(--text);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .vs-l{padding:3px 0;color:var(--text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;line-height:1.35}
  .vs-l.s14{font-size:14px;min-height:25px}.vs-l.s12{font-size:12px;min-height:22px}.vs-l.s11{font-size:11px;min-height:21px}
  .vs-frame{padding:4px 0}
  .vs-pv{background:#101218;display:grid;place-items:center;overflow:hidden}
  .vs-seek{position:relative;height:32px;margin:0 16px}
  .vs-seek::before{content:"";position:absolute;left:0;right:0;top:15px;height:2px;background:var(--line-strong)}
  .vs-seek>s{position:absolute;left:0;top:15px;height:2px;background:var(--accent)}
  .vs-seek>i{position:absolute;top:9px;width:14px;height:14px;margin-left:-7px;border-radius:50%;background:var(--accent)}
  .vs-edit{flex:1;min-width:0;height:44px;padding:0 8px;display:flex;align-items:center;font-size:12px;color:var(--text);border-bottom:2px solid var(--accent);white-space:nowrap;overflow:hidden}
  .vs-row>button.w76{flex:none;width:76px;height:44px}
  /* 被控应用画面：示意的系统设置页，颜色固定，不随 DSHA 主题 */
  .vs-mock{background:#F6F8FC;color:#1B1C1F;display:flex;flex-direction:column;font-family:var(--sans)}
  .vs-mock .sbar{height:6%;display:flex;align-items:center;justify-content:space-between;padding:0 6%;font-size:9px;color:#444}
  .vs-mock .ttl{padding:6% 6% 4%;font-size:19px}
  .vs-mock .srch{margin:0 6% 5%;height:6%;border-radius:99px;background:#E7EBF2}
  .vs-mock .it{display:flex;align-items:center;gap:9px;padding:0 6%;height:9%}
  .vs-mock .it i{flex:none;width:20px;height:20px;border-radius:50%}
  .vs-mock .it span{font-size:11px}
  .vs-mock .it em{display:block;font-style:normal;font-size:9px;color:#5F6368;margin-top:2px}

  /* ---------- AssistantActivity（平台默认 TextView / Button） ---------- */
  .as{padding:16px}
  .as-st{font-size:20px;color:var(--text);line-height:1.3}
  .as-tx{font-size:14px;color:var(--text);line-height:1.45}
  .as-btn{display:block;width:100%;min-height:48px;padding:12px 16px;border-radius:13px;border:1px solid var(--line);background:var(--surface);color:var(--text);font-size:14px;font-weight:500;line-height:1.3;text-align:center}
  .as-bound{flex:1;min-height:0;display:flex;flex-direction:column;padding:16px}
  .as-bound .as-st{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;white-space:pre-line}
  .as-msgs{font-size:15px;line-height:1.45;white-space:pre-line;color:var(--text)}
  .as-in{flex:none;min-height:64px;padding:10px 4px;font-size:16px;line-height:1.4;color:var(--muted);border-bottom:2px solid var(--line-strong)}
  .as-acts{flex:none;display:flex;overflow-x:auto;scrollbar-width:none}
  .as-acts::-webkit-scrollbar{display:none}
  .as-acts>.as-btn{flex:none;width:auto;white-space:nowrap}
  .as-acts>.as-btn.off{color:var(--text-2);background:var(--disabled);border-color:transparent}

  /* ---------- WebPreviewActivity ---------- */
  .wp{flex:1;min-height:0;position:relative;display:flex;flex-direction:column}
  .wp-web{flex:1;background:#FFFFFF}
  .wp-prog{position:absolute;left:0;right:0;top:0;height:3px;background:var(--raised);z-index:2}
  .wp-prog>i{display:block;height:100%;background:var(--accent)}
  .wp-err{position:absolute;inset:0;background:var(--bg);display:flex;flex-direction:column;justify-content:center;padding:24px;overflow-y:auto}
  .wp-err .t{font-size:20px;font-weight:700;line-height:1.35}
  .wp-err .d{margin-top:16px;font-size:15px;line-height:1.55;white-space:pre-line;overflow-wrap:anywhere}
  .wp-err button{display:block;width:100%;min-height:48px;padding:12px 16px;border:0;background:none;font-size:14px;font-weight:500;color:var(--accent);border-radius:10px}
  .wp-err button.wp-retry{margin-top:24px;border:1px solid var(--line);border-radius:13px;background:var(--surface);color:var(--text)}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  const GROUP = '任务与系统';

  // ================================================================ 共用小件
  const chev = () => `<span class="sy-chev">${icon('transfer_chevron')}</span>`;
  const head = (title, sub, trailing) =>
    `<div class="sy-head"><button class="sy-ib back" aria-label="返回">${icon('ui_back')}</button>` +
    `<div class="tt"><div class="t">${esc(title)}</div>${sub ? `<div class="s">${esc(sub)}</div>` : ''}</div>${trailing || ''}</div>`;

  // ================================================================ BackgroundTasksActivity
  // 任务示例：RuntimeTasks.kind 的真实分类文案；「后台容器进程」归 CONTAINER，目的地 NONE（无箭头、不可点）。
  const BT_TASKS = [
    { ic: 'recovery_database', title: '数据与备份', meta: '保存并核验文件 · 1284 项', opens: true },
    { ic: 'plugins', title: '插件管理', meta: '1 分 12 秒 · 正在安装依赖（npm install）', opens: true },
    { ic: 'ui2_box', title: '后台容器进程', meta: '8 分 3 秒 · 正在执行', opens: false },
  ];
  const BT_LINKS = [
    ['ui_chat', 'Agent 任务', '正在运行的对话回合'],
    ['plugins', '插件管理', '安装、更新与检测'],
    ['terminal', '终端', '会话与运行中的命令'],
    ['recovery_database', '数据与备份', '导出、恢复与副本'],
    ['recovery_clock', '自动备份', '计划与最近结果'],
    ['recovery_wrench', '安装与环境', '运行环境状态与维护'],
  ];
  const btLink = ([ic, t, s]) => `<div class="bk-link" data-touch><span class="sy-tile">${icon(ic)}</span>` +
    `<div class="w"><div class="n">${esc(t)}</div><div class="m">${esc(s)}</div></div>${chev()}</div>`;

  DSHA.page({
    id: 'system-bgtasks', group: GROUP, title: '后台任务', shell: 'bare',
    source: 'BackgroundTasksActivity · TransferUi · BackgroundTaskModel',
    states: [{ id: 'running', title: '有任务' }, { id: 'idle', title: '空闲' }],
    render(env) {
      const running = env.state === 'running';
      const phone = env.screenW < 600;                                   // 源码 width < 600
      const pad = Math.max(phone ? 16 : 32, (env.screenW - 720) / 2);     // 源码内容边距
      const tasks = BT_TASKS.map((t, i) => (i ? '<div class="sy-div" style="margin-left:68px"></div>' : '') +
        `<div class="bk-task"${t.opens ? ' data-touch' : ''}><span class="sy-tile acc">${icon(t.ic)}</span>` +
        `<div class="w"><div class="n">${esc(t.title)}</div><div class="m">${esc(t.meta)}</div></div>` +
        `<span class="sy-spin" aria-hidden="true"></span>${t.opens ? chev() : ''}</div>`).join('');
      const empty = `<div class="sy-card bk-empty"><span class="ring">${icon('launch_log_ok')}</span>` +
        `<div class="hd">没有正在执行的任务</div><div class="bd">终端、插件、备份与环境维护开始后会显示在这里。</div></div>`;
      let links;
      if (phone) links = `<div class="sy-card">` + BT_LINKS.map((l, i) => (i ? '<div class="sy-div" style="margin-left:62px"></div>' : '') + btLink(l)).join('') + `</div>`;
      else {
        links = '';
        for (let i = 0; i < BT_LINKS.length; i += 2)
          links += `<div class="bk-pair">${BT_LINKS.slice(i, i + 2).map(l => `<div class="sy-card">${btLink(l)}</div>`).join('')}</div>`;
      }
      return `<div class="sy-col">` +
        head('后台任务', running ? `${BT_TASKS.length} 项任务正在执行` : '当前空闲，没有正在执行的原生任务') +
        `<div class="scroll"><div class="bk" style="--bt-pad:${pad}px">
          <div class="sy-sec" style="margin-top:4px">进行中</div>
          <div style="margin-top:8px">${running ? `<div class="sy-card">${tasks}</div>` : empty}</div>
          <div class="sy-sec" style="margin-top:24px">相关页面</div>
          <div style="margin-top:8px">${links}</div>
          <p class="bk-note">这里只显示进度。需要取消或处理时，请进入对应页面操作。</p>
        </div></div></div>`;
    },
  });

  // ================================================================ AgentTasksActivity
  // 原生 bug：onCreate 删掉标题后，render() 对 page.content 调 removeAllViews，副标题也被清掉；
  // 所以实际页面只有返回行 + 卡片，没有页面标题。此处照实画。卡片内文字之间没有间距（照实）。
  // 会话 ID 为脱敏示例；真实值为 DSH 会话 id。
  const AT_ROWS = [
    { title: '整理下载目录里的截图', phase: '等你确认', detail: '是否把 42 个文件移动到 Pictures/归档？', time: 's-7c1e · 第 3 轮 · 阶段 48s · 总计 2分16秒', waiting: true, active: true },
    { title: '周报草稿', phase: '正在执行工具', detail: 'read_file', time: 's-29ad · 第 1 轮 · 阶段 6s · 总计 31s', active: true },
    { title: '检查插件更新', phase: '任务完成', detail: '', time: 's-b04f · 第 2 轮 · 阶段 0s · 总计 1分5秒', active: false },
  ];
  DSHA.page({
    id: 'system-agent-tasks', group: GROUP, title: 'Agent 任务', shell: 'sub',
    source: 'AgentTasksActivity · CardPage · AgentTaskNotifications.phase',
    states: [{ id: 'active', title: '有任务' }, { id: 'empty', title: '无记录' }],
    render(env) {
      if (env.state === 'empty') return `<div class="scroll"><div class="pg at"><div class="at-empty">暂无任务记录</div></div></div>`;
      const cards = AT_ROWS.map(r => `<div class="card">` +
        `<div class="at-t">${esc(r.title)}</div><div class="at-p">${esc(r.phase)}</div>` +
        (r.detail ? `<div class="at-d">${esc(r.detail)}</div>` : '') + `<div class="at-e">${esc(r.time)}</div>` +
        (r.waiting ? h.btn('回答', 'pri block') : '') +
        (r.active ? h.btn('停止', 'block') : '') + h.btn('回聊天', 'block') + `</div>`).join('');
      return `<div class="scroll"><div class="pg at">${cards}</div></div>`;
    },
  });

  // ================================================================ FileTransfersActivity
  const KIND = {
    zip: ['transfer_archive', 'warn'], pdf: ['transfer_document', 'bad'], png: ['transfer_image', 'good'],
    py: ['transfer_code', 'acc'], txt: ['transfer_text', 'muted'],
  };
  const TONE = { acc: 'var(--accent)', warn: 'var(--warn)', bad: 'var(--bad)', good: 'var(--good)', muted: 'var(--muted)' };
  const TONE_SOFT = { acc: 'var(--accent-tonal)', warn: 'var(--warn-soft)', bad: 'var(--bad-soft)', good: 'var(--good-soft)', muted: 'var(--raised)' };
  // TransferUi.tile：类型色浅底 + 类型色图标（TEXT 用 text_secondary），内边距 25%。
  const ftTile = (name, size) => {
    const [ic, tone] = KIND[name.split('.').pop()] || KIND.txt;
    const fg = tone === 'muted' ? 'var(--text-2)' : TONE[tone];
    return `<span class="ft-tile" style="width:${size}px;height:${size}px;background:${TONE_SOFT[tone]};color:${fg};font-size:${size / 2}px">${icon(ic)}</span>`;
  };
  const st = (text, tone) => `<b style="color:${TONE[tone]}">${esc(text)}</b>`;
  const ACT = {
    RETRY: ['plugin_restart', '重试'], CHOOSE: ['onb_folder', '选择保存位置'], PAUSE: ['transfer_pause', '暂停'],
    OPEN: ['onb_external', '打开'], SHARE: ['transfer_share', '分享'],
  };
  // 示例记录（TransferListModel.sections：需要处理 → 正在传输 → 按天）。meta：c = 手机/双栏紧凑，p = 平板竖屏。
  const FT = [
    { id: 'f1', sec: '需要处理', name: 'workspace-export.zip', primary: ['RETRY'],
      c: `${st('失败', 'bad')} · 传输中断`, p: `${st('失败', 'bad')} · 传输中断` },
    { id: 'f2', sec: '需要处理', name: 'quarterly-report.pdf', primary: ['CHOOSE'],
      c: st('已校验，请选择保存位置', 'acc'), p: st('已校验，请选择保存位置', 'acc') },
    { id: 'f3', sec: '正在传输', name: 'design-assets.zip', primary: ['PAUSE'], bar: 63,
      c: `${st('正在下载', 'acc')} · 63% · 4.2 MB/秒`, p: `${st('正在下载', 'acc')} · 79.4 MB / 126 MB · 4.2 MB/秒` },
    { id: 'f4', sec: '今天', name: 'chart-weekly.png', primary: ['OPEN', 'SHARE'],
      c: '1.8 MB · 11:32', p: '1.8 MB · 11:32 · Pictures/DSHA' },
    { id: 'f5', sec: '今天', name: 'main (1).py', primary: ['OPEN', 'SHARE'],
      c: `4.6 kB · 09:15 · <b style="color:var(--accent);font-weight:400">已自动改名</b>`, p: `4.6 kB · 09:15 · Download/DSHA · <b style="color:var(--accent);font-weight:400">已自动改名</b>` },
    { id: 'f6', sec: '昨天', name: 'notes.txt', primary: [],
      c: `${st('已取消', 'muted')} · 21:40`, p: `${st('已取消', 'muted')} · 21:40` },
  ];
  const ftLayout = env => env.screenW >= 840 ? 'two' : env.screenW >= 600 ? 'portrait' : 'phone';
  const centeredPad = (env, max, min) => Math.max(min, (env.screenW - max) / 2);
  function ftDetailWide(env, layout) {
    const content = layout === 'two' ? env.screenW - 420 - 64 : Math.min(env.screenW - 80, 720);
    return layout !== 'phone' && content >= 600;
  }
  function ftRow(r, layout, selected) {
    const portrait = layout === 'portrait';
    let acts = '';
    // 平板竖屏：第二主操作为 PLAIN 文字钮，第一主操作为 TONAL 文字钮；手机与双栏只留 44dp 图标钮。
    if (portrait && r.primary.length > 1) {
      const [ic, lb] = ACT[r.primary[1]];
      acts += `<button class="ft-tb txt plain">${icon(ic)}${lb}</button>`;
    }
    if (r.primary.length) {
      const [ic, lb] = ACT[r.primary[0]];
      acts += portrait ? `<button class="ft-tb txt">${icon(ic)}${lb}</button>` : `<button class="ft-tb" aria-label="${lb}：${esc(r.name)}">${icon(ic)}</button>`;
    }
    acts += `<button class="ft-more" aria-label="更多操作：${esc(r.name)}">${icon('transfer_more')}</button>`;
    return `<div class="ft-row${selected ? ' sel' : ''}"><div class="ft-hit" data-touch>${ftTile(r.name, 40)}` +
      `<div class="ft-tx"><div class="ft-name">${esc(r.name)}</div><div class="ft-meta">${portrait ? r.p : r.c}</div>` +
      (r.bar != null ? `<div class="ft-bar"><i style="width:${r.bar}%"></i></div>` : '') +
      `</div></div><div class="ft-acts">${acts}</div></div>`;
  }
  function ftList(layout, selectedId) {
    // 分段 tab 原生 minHeight 40dp（bg_plugin_segment_tab），低于 44dp：data-dense。
    let out = `<div class="ft-seg" role="tablist" data-dense>` +
      `<span class="on" data-touch>全部 <b>6</b></span><span data-touch>进行中 <b>2</b></span>` +
      `<span data-touch>失败 <b class="bad">1</b></span><span data-touch>已结束 <b>3</b></span></div>`;
    const secs = [...new Set(FT.map(r => r.sec))];
    for (const s of secs) {
      const rows = FT.filter(r => r.sec === s);
      out += `<div class="sy-sec ft-lab">${s}</div><div class="sy-card ft-grp">` +
        rows.map((r, i) => (i ? '<div class="sy-div" style="margin-left:64px"></div>' : '') + ftRow(r, layout, r.id === selectedId)).join('') + `</div>`;
    }
    // 「了解下载记录」原生为 40dp 高 PLAIN 按钮（TransferUi.button(..., 40)），低于 44dp：data-dense。
    out += `<div class="ft-foot">移除记录不会删除已完成的文件。<br><button class="ft-learn" data-dense>了解下载记录</button></div>`;
    return out;
  }
  const doneMark = '<svg viewBox="0 0 22 22"><path d="M6.38 11.22L9.9 14.74 15.84 7.7" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const failMark = '<svg viewBox="0 0 22 22"><path d="M7.26 7.26L14.74 14.74M14.74 7.26L7.26 14.74" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/></svg>';
  // TransferStepsView：节点半径 11dp、节点与连线间隙 8dp、线宽 2dp；标签 12sp，进行中/暂停/失败加粗。
  function ftSteps(steps) {
    const labels = ['下载文件', '校验', '保存'];
    return `<div class="ft-steps" role="img" aria-label="三步进度">` + steps.map((s, i) =>
      `<div class="ft-step ${s}${s !== 'todo' ? ' r' : ''}"><span class="nd">${s === 'done' ? doneMark : s === 'failed' ? failMark : ''}</span><span class="sl">${labels[i]}</span></div>`).join('') + `</div>`;
  }
  // 详情：downloading = design-assets.zip（正在下载）；failed = workspace-export.zip（传输中断，已下载部分可续传）。
  function ftDetail(env, layout, which) {
    const wide = ftDetailWide(env, layout);
    const dl = which === 'downloading';
    const name = dl ? 'design-assets.zip' : 'workspace-export.zip';
    const chip = dl ? ['正在下载', 'acc'] : ['失败', 'bad'];
    const hero = `<div class="ft-hero">${ftTile(name, 60)}<div class="tx"><div class="nm">${esc(name)}</div>` +
      `<div class="sub"><span class="ft-chip" style="color:${TONE[chip[1]]};background:${TONE_SOFT[chip[1]]}">${chip[0]}</span>` +
      `<span class="inf">zip · ${dl ? '126 MB' : '52.4 MB'}</span></div></div></div>`;
    const stateCard = dl
      ? `<div class="sy-card ft-pc"><div class="ft-big"><span class="pct">63%</span><span class="by">79.4 MB / 126 MB</span></div>` +
        `<div class="ft-bar big"><i style="width:63%"></i></div>` +
        `<div class="ft-nums"><span>4.2 MB/秒</span><span>剩余约 11 秒</span></div>${ftSteps(['cur', 'todo', 'todo'])}</div>`
      : `<div class="sy-card ft-pc"><div class="ft-sh"><span class="rg" style="background:var(--bad-soft);color:var(--bad)">${icon('onb_alert')}</span><span class="lb">传输中断</span></div>` +
        `<div class="ft-bd">传输中断或文件服务暂不可用。已下载进度保留，请检查运行环境后重试。</div>` +
        `<div class="ft-bd" style="margin-top:6px">已下载 18.6 MB，重试时从断点继续。</div>${ftSteps(['failed', 'todo', 'todo'])}</div>`;
    const primary = dl
      ? `<div class="ft-pa"><button>${icon('transfer_pause')}暂停</button></div>`
      : `<div class="ft-pa"><button>${icon('plugin_restart')}重试</button></div>`;
    // 进行中 / 失败的记录没有文档 URI，所以没有「技术信息」折叠区；只有「复制路径」（40dp OUTLINE 按钮，原生尺寸：data-dense）。
    const loc = `<div class="sy-card ft-c"><div class="sy-sec">保存位置</div>` +
      `<div class="ft-loc"><span class="fd">${icon('onb_folder')}</span><div class="tx"><div class="p">Download/DSHA</div>` +
      `<div class="n">系统「下载」目录中的 DSHA 文件夹</div></div></div>` +
      `<div class="ft-flow" data-dense><button class="ft-ob">${icon('ui_copy')}复制路径</button></div></div>`;
    const file = `<div class="sy-card ft-c"><div class="sy-sec">文件信息</div><div style="margin-top:6px">` +
      `<div class="ft-kv"><span class="k">类型</span><span class="v">application/zip</span></div>` +
      `<div class="ft-kv"><span class="k">大小</span><span class="v">${dl ? '126 MB' : '52.4 MB'}</span></div>` +
      `<div class="ft-kv"><span class="k">创建时间</span><span class="v">2026年11月2日 ${dl ? '14:02' : '14:20'}</span></div></div></div>`;
    const manage = `<div class="sy-card ft-mg"><div class="sy-sec">管理</div><div class="ft-mrow" data-touch>` +
      (dl ? `${icon('onb_x')}<div class="tx"><div class="l">取消下载</div><div class="n">丢弃已下载的 79.4 MB，之后需从网页重新发起。</div></div>`
          : `${icon('transfer_remove')}<div class="tx"><div class="l">移除记录</div><div class="n">只删除这条记录，不影响其他文件。</div></div>`) + `</div></div>`;
    return hero + stateCard + primary + (wide ? `<div class="ft-cols">${loc}${file}</div>` : loc + file) + manage;
  }
  const ftEmpty = () => `<div class="ft-empty"><span class="ring">${icon('transfer_inbox')}</span>` +
    `<div class="hd">暂无下载记录</div><div class="bd">网页文件菜单中的下载会显示在这里。</div></div>`;

  DSHA.page({
    id: 'system-transfers', group: GROUP, title: '下载记录', shell: 'bare',
    source: 'FileTransfersActivity · TransferDetailView · TransferStepsView · TransferListModel',
    states: [{ id: 'list', title: '记录列表' }, { id: 'detail', title: '记录详情' }, { id: 'empty', title: '无记录' }],
    render(env) {
      const layout = ftLayout(env);
      const two = layout === 'two';
      const state = env.state;
      const pageOpen = state === 'detail' && !two;           // 手机 / 平板竖屏：整页详情
      const more = `<button class="sy-ib" aria-label="更多选项">${icon('transfer_more')}</button>`;
      const summary = state === 'empty' || pageOpen ? '' : '6 条记录 · 1 个正在传输 · 1 个失败';
      const header = head(pageOpen ? '记录详情' : '下载记录', summary, more) + (two ? '<div class="sy-div"></div>' : '');
      const lpad = centeredPad(env, 760, layout === 'phone' ? 12 : 32);
      const dpad = centeredPad(env, 720, layout === 'phone' ? 16 : 40);
      const listInner = state === 'empty' ? ftEmpty() : ftList(layout, two ? (state === 'detail' ? 'f3' : 'f1') : null);
      if (two) {
        // 双栏：列表态默认选中第一条可见记录（需要处理组的失败记录）；详情态选中正在下载的记录。
        const det = state === 'empty' ? `<div class="ft-hint">没有可查看的记录</div>` : ftDetail(env, layout, state === 'detail' ? 'downloading' : 'failed');
        return `<div class="sy-col ft-two">${header}<div class="ft-body">` +
          `<div class="scroll ft-list"><div class="ft-lc">${listInner}</div></div>` +
          `<div class="scroll ft-detail"><div class="ft-dc">${det}</div></div></div></div>`;
      }
      const inner = pageOpen
        ? `<div class="scroll"><div class="ft-dc" style="--ft-dpad:${dpad}px">${ftDetail(env, layout, 'downloading')}</div></div>`
        : `<div class="scroll"><div class="ft-lc" style="--ft-lpad:${lpad}px">${listInner}</div></div>`;
      return `<div class="sy-col">${header}${inner}</div>`;
    },
  });

  // ================================================================ CommunityActivity
  // 旧式实现：Button(bg_action_plain) + R.color.text/primary/text_muted；状态行 padding 写的是像素 16/8
  // （未换算 dp，2.625x 屏约 6/3dp，此处按此推断）。网页内容来自 dsha.cc，预览不加载，只画占位。
  DSHA.page({
    id: 'system-community', group: GROUP, title: '社区插件生态', shell: 'bare',
    source: 'CommunityActivity（WebView，无本机桥）',
    states: [{ id: 'loading', title: '连接中' }, { id: 'error', title: '连接失败' }],
    render(env) {
      const err = env.state === 'error';
      return `<div class="sy-col"><div class="cm-bar"><button class="cm-tb">返回</button><div class="cm-title">社区插件生态</div><button class="cm-tb">重试</button></div>` +
        `<div class="cm-status">${err ? '社区暂时无法连接，请检查网络后重试。' : '正在连接社区…'}</div>` +
        `<div class="cm-web">${err ? `<div class="cm-err"><b>网页无法打开</b>（推断）主框架加载失败后，原生只改状态行，WebView 仍显示系统默认错误页。</div>` : ''}</div></div>`;
    },
  });

  // ================================================================ ThemeActivity
  // CardPage 骨架但未调用 UiNavigation.addHeader：页面没有返回行，只靠系统返回。平板无限宽，三块卡等分拉宽（原生如此）。
  // 预览卡 {bg, surface, accent, muted, line} 取对应明暗的 shell_* 与当前调色板。
  const PAL = {
    standard: { light: ['#F3F6FB', '#FCFDFF', '#1E50D0', '#5A6B86', 'rgba(29,58,96,.075)'], dark: ['#0B0F16', '#131923', '#6AA4FF', '#8895AA', 'rgba(203,220,255,.071)'] },
    sakura: { light: ['#FBF4F7', '#FFFCFE', '#AB1C58', '#5A6B86', 'rgba(29,58,96,.075)'], dark: ['#170C10', '#24141B', '#EA7EAB', '#8895AA', 'rgba(203,220,255,.071)'] },
  };
  const SWATCH = {
    light: [['standard', '经典蓝', '#1E50D0'], ['aurora', '极光', '#0D6573'], ['sakura', '樱花', '#AB1C58'], ['cyber', '赛博', '#7826D7'], ['amber', '琥珀', '#864D0B']],
    dark: [['standard', '经典蓝', '#6AA4FF'], ['aurora', '极光', '#16B0C8'], ['sakura', '樱花', '#EA7EAB'], ['cyber', '赛博', '#BA8EEB'], ['amber', '琥珀', '#EC8713']],
  };
  // ThemeActivity.Preview.drawMock：画布 + 强调色圆点 + 标题线 + 两张卡（卡高 = (96-24-8-6)/2 = 29dp）。
  const mock = c => `<i style="inset:0;background:${c[0]}"></i>` +
    `<i style="left:8px;top:8px;width:8px;height:8px;border-radius:50%;background:${c[2]}"></i>` +
    `<i style="left:20px;top:10.5px;width:calc(60% - 20px);height:3px;border-radius:2px;background:${c[3]}"></i>` +
    [0, 1].map(k => `<i style="left:8px;right:8px;top:${24 + k * 35}px;height:29px;border-radius:6px;background:${c[1]}"></i>` +
      `<i style="left:14px;top:${24 + k * 35 + 13}px;width:calc(55% - 14px);height:3px;border-radius:2px;background:${k ? c[3] : c[2]}"></i>`).join('');
  const PALETTE_VARS = {
    light: { '--bg': '#FBF4F7', '--surface': '#FFFCFE', '--raised': '#F7E9EE', '--accent': '#AB1C58', '--accent-soft': 'rgba(171,28,88,.078)', '--accent-tonal': 'rgba(171,28,88,.122)', '--on-accent': '#FFFFFF', '--logo-top': '#FFE9F2', '--nav-bg': '#FFFCFE', '--disabled': '#F7E9EE' },
    dark: { '--bg': '#170C10', '--surface': '#24141B', '--raised': '#351C26', '--accent': '#EA7EAB', '--accent-soft': 'rgba(234,126,171,.078)', '--accent-tonal': 'rgba(234,126,171,.141)', '--on-accent': '#210812', '--logo-top': '#3E1426', '--nav-bg': '#24141B', '--disabled': '#351C26' },
  };
  DSHA.page({
    id: 'system-theme', group: GROUP, title: '主题与外观', shell: 'bare',
    source: 'ThemeActivity · CardPage · UiThemePalette · theme_palettes.xml',
    states: [{ id: 'standard', title: '经典蓝 · 跟随系统' }, { id: 'sakura', title: '樱花配色' }],
    // 樱花态：按 values{,-night}/theme_palettes.xml 的 pal_sakura_shell_* 覆盖本屏 token。
    after(dev, env) {
      if (env.state !== 'sakura') return;
      const vars = PALETTE_VARS[env.theme === 'dark' ? 'dark' : 'light'];
      for (const k in vars) dev.style.setProperty(k, vars[k]);
    },
    render(env) {
      const palId = env.state === 'sakura' ? 'sakura' : 'standard';
      const p = PAL[palId], night = env.theme === 'dark';
      const tile = (mode, name, on) => {
        const c = mode === 'dark' ? p.dark : p.light;
        const body = mode === 'system'
          ? `<span class="half l">${mock(p.light)}</span><span class="half r">${mock(p.dark)}</span>`
          : `<span class="half">${mock(c)}</span>`;
        return `<div class="th-tile${on ? ' on' : ''}" data-touch role="radio" aria-checked="${on}"><span class="th-pv">${body}` +
          `<span class="ol" style="box-shadow:inset 0 0 0 1px ${c[4]}"></span></span><span class="nm">${name}</span></div>`;
      };
      const sw = SWATCH[night ? 'dark' : 'light'].map(([id, nm, col]) =>
        `<div class="th-sw${id === palId ? ' on' : ''}" data-touch role="radio" aria-checked="${id === palId}"><span class="th-ring"><i style="background:${col}"></i></span><span class="nm">${nm}</span></div>`).join('');
      const styles = [['圆形扩散', '从点按处向外铺开，不回缩', true], ['对角擦除', '从左上角柔和地斜扫到右下角'], ['淡入淡出', '整屏新旧主题交叉淡化'], ['直接切换', '不做过渡，立即换成新外观']]
        .map(([t, s, on]) => `<div class="th-row" data-touch role="radio" aria-checked="${!!on}"><div class="w"><div class="t">${t}</div><div class="s">${s}</div></div><span class="ck${on ? '' : ' off'}">${icon('plugin_check')}</span></div>`).join('');
      return `<div class="scroll"><div class="pg">${h.title('主题与外观', '选择应用的明暗外观，切换会立即生效。')}
        ${h.label('外观模式')}<div class="th-modes">${tile('light', '浅色', false)}${tile('dark', '深色', false)}${tile('system', '跟随系统', true)}</div>
        ${h.label('配色')}<div class="card"><div class="th-sws">${sw}</div><div class="th-note">配色只改原生界面；内嵌 Web 只同步浅色和深色。</div></div>
        ${h.label('切换动画')}<div class="card">${styles}</div>
        ${h.label('同步')}<div class="card"><div class="th-row sync" data-touch><div class="w"><div class="t">Web 界面跟随</div>
          <div class="s">切换时把浅色、深色或跟随系统同步到内嵌 Web；之后在 Web 里改的外观保留到下次切换。</div></div><span class="th-m3" role="switch" aria-checked="true"></span></div></div>
      </div></div><div class="th-foot"></div>`;
    },
  });

  // ================================================================ VirtualScreenActivity
  // 未改造的旧式页：12dp 页边、bg_btn 12sp 按钮 40dp 高（低于 44dp，按钮之间无间距；整行 data-dense，原生尺寸），
  // 三行状态文字 setLines(1) 单行截断。预览高 = clamp(屏高 × 0.62, 280, 720)，屏高用 screenH 近似 heightPixels/density（推断）。
  // 虚拟屏尺寸：竖屏 1008×1792（VirtualScreenManager.start）。被控应用画面为示意，不随 DSHA 主题。
  DSHA.page({
    id: 'system-vscreen', group: GROUP, title: '虚拟屏', shell: 'bare',
    source: 'vscreen/VirtualScreenActivity · VirtualScreenPreviewView',
    states: [{ id: 'idle', title: '未创建' }, { id: 'running', title: '运行中 · 应用面板' }],
    render(env) {
      const on = env.state === 'running';
      const initial = Math.max(280, Math.min(720, Math.round(env.screenH * 0.62)));
      const pw = env.screenW - 24;
      const scale = Math.min(pw / 1008, initial / 1792);
      const mw = Math.round(1008 * scale), mh = Math.round(1792 * scale);
      const rows = [['#1A73E8', '网络和互联网', 'WLAN、移动网络'], ['#188038', '已连接的设备', '蓝牙、投射'], ['#E37400', '应用', '最近使用的应用'], ['#9334E6', '通知', '通知历史记录'], ['#D93025', '电池', '76%'], ['#5F6368', '存储', '已使用 41%']];
      const shot = on ? `<div class="vs-mock" style="width:${mw}px;height:${mh}px"><div class="sbar"><span>09:41</span><span>●●</span></div>` +
        `<div class="ttl">设置</div><div class="srch"></div>` +
        rows.map(([c, t, s]) => `<div class="it"><i style="background:${c}"></i><span>${t}<em>${s}</em></span></div>`).join('') + `</div>` : '';
      const seek = Math.round((initial - 280) * 100 / 440);
      return `<div class="scroll"><div class="vs">
        <div class="vs-row" data-dense><button>返回</button><button>使用说明</button></div>
        <div class="vs-l s14">${on ? '虚拟屏已开启' : '先连接设备通道，再点“创建竖屏”或“创建横屏”。'}</div>
        <div class="vs-l s12">${on ? '触摸已提交，请确认画面变化' : ''}</div>
        <div class="vs-l s11">${on ? 'com.android.settings · 1008×1792 · shizuku' : ''}</div>
        <div class="vs-frame"><div class="vs-pv" style="height:${initial}px" aria-label="虚拟屏预览，双指缩放">${shot}</div></div>
        <div class="vs-seek" aria-label="调整预览大小"><s style="width:${seek}%"></s><i style="left:${seek}%"></i></div>
        <div class="vs-row" data-dense><button>创建竖屏</button><button>创建横屏</button></div>
        <div class="vs-row" data-dense><button>应用</button><button>键盘</button><button>节点操作</button><button>关闭</button></div>
        ${on ? `<div class="vs-row"><div class="vs-edit">com.android.settings</div><button class="w76">选择</button><button class="w76">启动</button></div>` : ''}
      </div></div>`;
    },
  });

  // ================================================================ AssistantActivity
  // 未套用 shell 样式：ScrollView + 16dp 内边距，平台默认 TextView（20sp 状态）与 Button（bg_btn，48dp，
  // 竖向相互贴合无间距）。绑定态改为整页 LinearLayout：状态两行 + 只滚动的记录 + 输入框 + 横向滚动按钮条。
  // 记录拼接格式（state.text()）为推断示例；会话 ID 脱敏。默认文字色/字号按 Material 平台默认推断。
  DSHA.page({
    id: 'system-assistant', group: GROUP, title: '悬浮助手', shell: 'bare',
    source: 'assistant/AssistantActivity · AssistantWindow.label/detail · AssistantService.taskGuidance',
    states: [{ id: 'sessions', title: '选择会话' }, { id: 'bound', title: '已绑定会话' }, { id: 'permission', title: '悬浮窗权限' }],
    render(env) {
      const b = t => `<button class="as-btn">${esc(t)}</button>`;
      if (env.state === 'bound') {
        const transcript = '你：帮我整理 Download 目录里的截图\n\nDSH：好的，我先查看目录中的文件，再按月份归类。\n· 已读取 42 个文件\n· 正在移动 2026-10 的截图…\n\n下一次手机操作前会重新观察屏幕。';
        const acts = ['发送', '语音输入', '暂停', '继续', '停止当前任务', '打开完整 DSH 会话 / 模型设置', '选择其他会话', '收起并返回其他应用', '结束悬浮助手'];
        return `<div class="as-bound"><div class="as-st">本机 · 执行中\ns-7c1e</div>` +
          `<div class="scroll"><div class="as-msgs">${esc(transcript)}</div></div>` +
          `<div class="as-in">向当前绑定会话发送消息</div>` +
          `<div class="as-acts" data-hscroll>${acts.map(t => `<button class="as-btn${t === '继续' ? ' off' : ''}">${esc(t)}</button>`).join('')}</div></div>`;
      }
      if (env.state === 'permission') {
        return `<div class="scroll"><div class="as"><div class="as-st">手机任务需要悬浮窗权限</div>` +
          `<div class="as-tx">本次手机工具尚未执行。请打开 DSHA 设置中的悬浮窗权限，或系统设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → DSHA。允许后回到原对话明确继续；不会自动重放。</div>` +
          b('打开系统悬浮窗权限') + b('返回原对话继续') + `</div></div>`;
      }
      return `<div class="scroll"><div class="as"><div class="as-st">本机 DSH 会话</div>` +
        b('允许悬浮窗（离开应用后仍可聊天和停止）') + b('新建会话') + b('整理下载目录 · 执行中') + b('周报草稿 · 就绪') + b('检查插件更新 · 就绪') + b('刷新会话') + `</div></div>`;
    },
  });

  // ================================================================ WebPreviewActivity
  // 边到边（WebFullscreenUi.install）：内容区在系统栏之间，由网页自己处理安全区。加载时只有顶部 3dp 横向进度条
  // 覆盖在网页上；WebView 未设背景色，首帧前按系统默认白底推断（深色主题下可能闪白，需真机核对）。
  // 错误面板：colorBackground 底、24dp 内边距、纵向居中；重试（Widget.DSHA.Button）+ 两个 TextButton + borderless（颜色推断为强调色）。
  DSHA.page({
    id: 'system-webpreview', group: GROUP, title: 'Web 内嵌预览', shell: 'bare',
    source: 'WebPreviewActivity · activity_web_preview.xml · ui_strings.xml',
    states: [{ id: 'loading', title: '加载中' }, { id: 'error', title: '连接失败' }],
    render(env) {
      if (env.state === 'error') {
        return `<div class="wp"><div class="wp-err"><div class="t">暂时无法连接对话服务</div>` +
          `<div class="d">服务可能仍在启动或已退出。请稍后重试，持续失败时返回启动页查看日志。\n错误代码：-6\n\ncom.google.android.webview 131.0.6778.135</div>` +
          `<button class="wp-retry">重试</button><button>返回启动页处理插件 / 安全启动</button><button>下载错误日志</button><button>在系统浏览器中打开</button></div></div>`;
      }
      return `<div class="wp"><div class="wp-prog" role="progressbar" aria-valuenow="35"><i style="width:35%"></i></div><div class="wp-web"></div></div>`;
    },
  });
})();
