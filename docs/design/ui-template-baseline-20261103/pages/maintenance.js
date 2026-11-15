/*
 * 维护与恢复：运行环境维护 / 启动恢复 / 应急 DSH / 版本更新 / 自检与诊断。
 * 来源（origin/main build279）：
 *   - ExtractActivity：res/layout/activity_extract.xml、ui/ExtractActivity.java、ui/ExtractFooter.java、
 *     util/MaintenanceFooterPolicy.java、util/DeployStages.java、ui/DeployCard.java、ui/DeployTaskSheet.java、
 *     ui/OnboardingKit.java（segmentBar / stageList）、ui/AdaptiveColumns.java、values/extract_footer.xml。
 *   - StartupRecoveryActivity：ui/StartupRecoveryActivity.java、ui/StartupRecoveryLayout.java、util/StartupRecoveryState.java。
 *   - RecoveryActivity：ui/RecoveryActivity.java（CardPage 骨架，没有 UiNavigation 返回行，返回在页脚）、
 *     util/RecoveryStatusText.java。RecoveryWebActivity 为 WebView 外壳，这里不画。
 *   - UpdateActivity：res/layout/activity_update.xml、layout-w840dp/activity_update.xml、update_hero / update_candidate /
 *     update_manage.xml、ui/UpdateActivity.java、ui/UpdateUi.java、ui/UpdateProgressButton.java、util/UpdateActionPolicy.java。
 *   - DiagnosticActivity：res/layout/activity_diagnostics.xml、layout-sw600dp/activity_diagnostics.xml、diagnostic_hero /
 *     diagnostic_tools / diagnostic_feedback.xml、ui/DiagnosticActivity.java、ui/DiagnosticResultList.java、util/DiagnosticSummary.java。
 * 布局判据（只用源码阈值）：
 *   - 维护页：OnboardingKit.wide（screenW >= 600）时正文收进居中列，列宽 w840 ? 1040 : 640，侧距 max(20,(screenW-列宽)/2)；
 *     底栏按钮固定 640 列。AdaptiveColumns 按自身宽度（screenW - 2×侧距）>= 720 且有说明时两列（列距 28，权重 1 : 1.15）。
 *     MaintenanceFooterPolicy.sideBySide = screenW >= 600 || screenH <= 480；stackedLinks = fontScale >= 1.25 || screenW < 340
 *     （预览字体倍率按 1.0）。
 *   - 启动恢复：StartupRecoveryLayout.wide = 窗口宽 >= 700dp（这里用 screenW），两栏权重 1.1 : 1、列距 24、页边 24（否则 16）。
 *   - 版本更新：layout-w840dp 两栏（左 420dp + 1dp 线 + 右栏）；否则单栏，centerColumn 把内容收成 640dp 居中列。
 *   - 自检：layout-sw600dp 两栏（左 340dp，右栏 = screenW - 64 - 340 - 24）；正常项展开后按 DiagnosticSummary.okColumns
 *     （结果卡宽 >= 560 且字体 < 1.15）排两列。
 * 示例数据为脱敏虚构值（插件名、设备型号、候选构建号、更新记录正文），推断处在代码旁注“推断”。
 */
(function () {
  const { h } = DSHA;
  const css = `
  /* ---------- 运行环境维护（ExtractActivity） ---------- */
  .mx-c{margin:0 auto}
  .mx-head{display:flex;align-items:center}
  .mx-spin{flex:none;width:20px;height:20px;margin-right:10px;border-radius:50%;border:2.5px solid var(--accent-tonal);border-top-color:var(--accent)}
  .mx-title{flex:1;min-width:0;margin:0;font-size:22px;font-weight:700;line-height:1.2}
  .mx-pill{flex:none;margin-left:10px;padding:3px 9px;border-radius:999px;font-size:11.5px;font-weight:700;line-height:1.3;white-space:nowrap}
  .mx-pill.acc{background:var(--accent-soft);color:var(--accent)}.mx-pill.bad{background:var(--bad-soft);color:var(--bad)}
  .mx-status{margin-top:10px;font-size:15px;font-weight:700;line-height:1.45}
  .mx-lead{margin-top:8px;font-size:13.5px;line-height:1.6;color:var(--text-2)}
  .mx-cols{margin-top:16px}
  .mx-cols.split{display:flex;gap:28px;align-items:flex-start}
  .mx-cols.split>.a{flex:1;min-width:0}.mx-cols.split>.b{flex:1.15;min-width:0}
  .mx-cols.split .mx-rescue{margin-top:0}
  .mx-card{background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:16px}
  .mx-line{font-size:14px;font-weight:700;line-height:1.45}
  .mx-seg{display:flex;gap:3px;margin-top:10px}
  .mx-seg>i{flex:1;height:6px;border-radius:3px;background:var(--raised)}
  .mx-seg>i.done{background:var(--accent)}.mx-seg>i.bad{background:var(--bad)}
  .mx-seg>i.run{background:linear-gradient(90deg,var(--accent) 0 55%,var(--accent-tonal) 55% 100%)}
  .mx-stages{margin-top:8px}
  .mx-st{display:flex;align-items:flex-start;padding:6px 0;font-size:13px;line-height:1.45;color:var(--muted)}
  .mx-st .mk{flex:none;width:18px;height:18px;margin-top:1px;border-radius:50%;display:grid;place-items:center;border:1.5px solid var(--line-strong);font-size:12px;color:var(--on-accent)}
  .mx-st .n{flex:1;min-width:0;margin-left:10px}
  .mx-st.done{color:var(--text-2)}.mx-st.done .mk{background:var(--accent);border:0}
  .mx-st.cur{color:var(--text);font-weight:700}.mx-st.cur .mk{border:2px solid var(--accent-tonal);border-top-color:var(--accent)}
  .mx-st.bad{color:var(--bad);font-weight:700}.mx-st.bad .mk{background:var(--bad);border:0}
  .mx-rescue{margin-top:12px;min-height:44px}
  .mx-err{margin-top:12px;padding:12px;border-radius:14px;background:var(--bad-soft);color:var(--bad);font-size:13px;line-height:1.55;white-space:pre-wrap;overflow-wrap:anywhere}
  .mx-foot{flex:none;background:var(--surface);border-top:1px solid var(--line);padding-top:12px;padding-bottom:10px}
  .mx-acts{display:flex;flex-direction:column;gap:8px}
  .mx-acts.side{flex-direction:row;align-items:center}
  .mx-acts.side>*{flex:1;min-width:0}
  .mx-acts .btn{min-height:44px;white-space:normal;text-align:center;padding:10px 16px}
  .mx-acts .retry{min-height:48px;border:0}
  .mx-acts.side .enter{min-height:48px}
  .mx-acts .retry.danger{background:var(--bad);color:var(--on-accent)}
  .mx-busy{position:relative;overflow:hidden;min-height:48px;border-radius:13px;background:var(--raised);border:1px solid var(--line-strong);display:flex;align-items:center;justify-content:center;padding:0 14px}
  .mx-busy .fill{position:absolute;left:0;top:0;bottom:0;background:var(--accent-tonal)}
  .mx-busy>span{position:relative}
  .mx-busy .sp{width:16px;height:16px;border-radius:50%;border:2px solid var(--accent-tonal);border-top-color:var(--accent)}
  .mx-busy .l{margin-left:8px;font-size:14px;font-weight:700}
  .mx-busy .k{margin-left:8px;font-size:12.5px;font-weight:700;color:var(--text-2)}
  .mx-links{display:flex;gap:8px;margin-top:8px}
  .mx-link{flex:1;min-width:0;min-height:44px;padding:6px 4px;border-radius:12px;border:1px solid var(--line-strong);background:var(--surface);color:var(--accent);
    font-size:12.5px;font-weight:700;line-height:1.2;display:flex;align-items:center;justify-content:center;gap:5px;text-align:center}
  .mx-link .i{font-size:16px}
  .mx-link.stack{flex-direction:column;gap:2px}
  .mx-link.hot-bad{border:1.5px solid var(--bad);background:var(--bad-soft);color:var(--bad)}

  /* ---------- 启动恢复（StartupRecoveryLayout） ---------- */
  .sr{padding:8px 16px 24px}
  .sr.wide{padding:8px 24px 24px}
  .sr-bar{display:flex;align-items:center}
  .sr-bar .ibtn{border-radius:10px;color:var(--text);font-size:22px}
  .sr-bar h1{flex:1;min-width:0;margin:0 0 0 4px;font-size:20px;font-weight:700}
  .sr-body.wide{display:flex;align-items:flex-start;gap:24px}
  .sr-body.wide>.l{flex:1.1;min-width:0}.sr-body.wide>.r{flex:1;min-width:0}
  .sr-card{margin-top:8px;border-radius:18px;border:1px solid var(--line);padding:14px 16px}
  .sr-card.good{background:var(--good-soft)}.sr-card.bad{background:var(--bad-soft)}
  .sr-card .hd{display:flex;align-items:center}
  .sr-card .hd .dot{width:10px;height:10px;margin-right:10px}
  .sr-card .t{flex:1;min-width:0;font-size:16px;font-weight:700;line-height:1.4;padding:2px 0}
  .sr-card.good .t,.sr-card.good .code{color:var(--good)}.sr-card.bad .t,.sr-card.bad .code{color:var(--bad)}
  .sr-card .code{flex:none;padding-left:8px;font-family:var(--mono);font-size:11px}
  .sr-card .d{margin-top:4px;font-size:13px;line-height:1.55;color:var(--text-2)}
  .sr-card .m{margin-top:4px;font-size:12px;line-height:1.5;color:var(--muted);overflow-wrap:anywhere}
  .sr-card .sr-acts{display:flex;gap:8px;margin-top:12px}
  .sr-card .sr-acts>.btn{flex:1;min-width:0;padding:0 10px}
  .sr-sec{margin-top:16px}
  .sr-sh{display:flex;align-items:center;padding:0 0 6px 4px}
  .sr-sh.sub{padding-bottom:0}
  .sr-sh .lb{font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--text-2);padding:2px 0}
  .sr-sh .sum{flex:1;min-width:0;padding-left:8px;font-size:12px;color:var(--muted)}
  .sr-sh .tg{min-height:36px;padding:0 10px;border:0;background:none;border-radius:10px;color:var(--accent);font-size:13px;font-weight:700}
  .sr-note{padding:0 4px 6px;font-size:11px;line-height:1.5;color:var(--muted)}
  .sr-grp{border:1px solid var(--line);border-radius:14px;overflow:hidden}
  .sr-row{display:flex;align-items:center;min-height:48px;padding:8px 10px 8px 12px}
  .sr-row.off{opacity:.6}
  .sr-row .bx{flex:none;width:30px;height:30px;margin-right:12px;border-radius:12px;background:var(--accent-soft);color:var(--accent);display:grid;place-items:center;font-size:17px}
  .sr-row .dot{width:8px;height:8px;margin-right:12px}
  .sr-row .w{flex:1;min-width:0}
  .sr-row .a{font-size:14px;line-height:1.35}.sr-row .a.b{font-weight:700}
  .sr-row .s{margin-top:3px;font-size:12px;line-height:1.4;color:var(--muted)}
  .sr-row .s.warn{color:var(--warn)}
  .sr-row .chev{margin-left:8px}
  .sr-row .tg{display:inline-block;padding:2px 8px;border-radius:999px;font-size:12px;font-weight:700}
  .sr-row .tg.good{background:var(--good-soft);color:var(--good)}.sr-row .tg.bad{background:var(--bad-soft);color:var(--bad)}.sr-row .tg.info{background:var(--accent-soft);color:var(--accent)}
  .sr-row .sr-tm{flex:none;margin-left:8px;font-size:13px;color:var(--text-2)}.sr-row .sr-el{flex:none;margin-left:8px;font-size:12px;color:var(--muted)}
  .sr-dv{height:1px;background:var(--line)}

  /* ---------- 应急 DSH（RecoveryActivity / CardPage） ---------- */
  .em-t{margin:0;padding-bottom:18px;font-size:26px;font-weight:700;line-height:1.3}
  .em-p{font-size:14px;line-height:1.5;color:var(--text-2)}
  .em-s{font-size:15px;line-height:1.5}
  .em-k{font-size:14px;line-height:1.5}
  .em-edit{display:flex;align-items:center;min-height:48px;border-bottom:1.5px solid var(--line-strong);font-size:15px;color:var(--muted)}
  .em-edit.off{opacity:.5}
  .em .btn.block{margin-top:10px}
  .em .btn.two{white-space:pre-line;line-height:1.35;padding:8px 16px}
  .em-op{min-height:0}
  .em-h{font-size:17px;font-weight:700}
  .em-foot{flex:none;padding:8px 20px 16px}
  .em-foot .btn{margin-top:10px}

  /* ---------- 版本更新（UpdateActivity） ---------- */
  .up-bar{flex:none;display:flex;align-items:center;min-height:60px;padding:0 16px 0 8px}
  .up-bar.w840{padding:0 24px 0 16px}
  .up-bar .ibtn{width:48px;height:48px;color:var(--text);font-size:22px}
  .up-bar h1{flex:1;min-width:0;margin:0 0 0 8px;font-size:16px;font-weight:700}
  .up-bar.w840 h1{margin-left:10px}
  .up-c{padding:4px 20px var(--page-bottom)}
  .up-two{flex:1;min-height:0;display:flex}
  .up-two>.scroll.l{flex:none;width:420px}
  .up-two>.vl{flex:none;width:1px;background:var(--line-strong)}
  .up-two .up-c.l{padding:4px 20px var(--page-bottom) 32px}
  .up-two .up-c.r{padding:4px 32px var(--page-bottom)}
  .up-card{background:var(--surface);border:1px solid var(--line);border-radius:18px}
  .up-hero{padding:16px}
  .up-top{display:flex;align-items:center}
  .up-logo{flex:none;width:40px;height:40px;border-radius:12px;background:var(--accent-soft);border:1px solid var(--line-strong);display:grid;place-items:center;color:var(--accent);font-size:20px}
  .up-top .w{flex:1;min-width:0;margin-left:12px}
  .up-name{font-size:16px;font-weight:700}
  .up-ver{display:inline-flex;align-items:center;min-height:44px;margin-left:-6px;padding:0 6px;border:0;background:none;font-size:13px;color:var(--text-2)}
  .up-ver .i{margin-left:4px;font-size:16px;color:var(--muted)}
  .up-ed{flex:none;margin-left:8px;padding:4px 10px;border-radius:999px;background:var(--accent-soft);color:var(--accent);font-size:12px;font-weight:700}
  .up-hr{height:1px;background:var(--line);margin:12px 0 8px}
  .up-ch{display:flex;align-items:center;min-height:44px}
  .up-ch .lb{flex:1;min-width:0;margin-right:12px;font-size:13px;color:var(--text-2)}
  .up-seg{display:flex;gap:2px;padding:3px;border-radius:13px;background:var(--raised)}
  .up-seg button{min-width:76px;min-height:44px;padding:0 14px;border:1px solid transparent;border-radius:9px;background:none;font-size:13px;font-weight:700;color:var(--text-2)}
  .up-seg button.on{background:var(--surface);border-color:var(--line);color:var(--text)}
  .up-seg.off button{color:var(--muted)}
  .up-seg.pane{width:420px;max-width:100%}
  .up-seg.pane button{flex:1;font-size:14px}
  .up-st{display:flex;align-items:flex-start;margin-top:6px}
  .up-st .dot{width:8px;height:8px;margin-top:6px}
  .up-st .tx{flex:1;min-width:0;margin-left:10px;font-size:13px;line-height:1.55}
  .up-st .re{flex:none;min-height:44px;margin:-12px -8px 0 8px;padding:0 8px;border:0;background:none;color:var(--accent);font-size:13px;font-weight:700}
  .up-act{position:relative;overflow:hidden;display:flex;align-items:center;justify-content:center;width:100%;min-height:52px;margin-top:12px;padding:12px 16px;
    border-radius:13px;border:0;background:var(--accent);color:var(--on-accent);font-size:14px;font-weight:700}
  .up-act.fill{background:var(--accent-soft);color:var(--accent);box-shadow:inset 0 0 0 1px var(--line-strong)}
  .up-act .lite{position:absolute;left:0;top:0;bottom:0;overflow:hidden;background:var(--accent)}
  .up-act .lite>span{position:absolute;left:0;top:0;bottom:0;display:flex;align-items:center;justify-content:center;color:var(--on-accent)}
  .up-act>.base{position:relative}
  .up-cand{margin-top:12px;padding:14px 8px 4px 16px}
  .up-cand .top{display:flex;align-items:center;margin-right:8px}
  .up-cand .top .w{flex:1;min-width:0;margin-left:12px}
  .up-cand .tt{font-size:15px;font-weight:700}
  .up-meta{margin-top:4px;font-size:12px;color:var(--muted);font-variant-numeric:tabular-nums}
  .up-notes{margin:10px 8px 0 0;font-size:13px;line-height:1.55;color:var(--text-2);white-space:pre-line;display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden}
  .up-more{display:flex;justify-content:flex-end}
  .up-more button{display:inline-flex;align-items:center;min-height:44px;padding:0 8px 0 10px;border:0;background:none;color:var(--accent);font-size:13px;font-weight:700}
  .up-more .i{margin-left:4px;font-size:14px}
  .up-lbl{margin:24px 0 10px;font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--text-2)}
  .up-card .entry{padding:12px 16px}
  .up-card .entry.act{background:var(--accent-soft)}
  .up-dv{height:1px;background:var(--line);margin-left:60px}
  .up-pt{margin-top:20px;font-size:21px;font-weight:700;line-height:1.35;overflow-wrap:anywhere}
  .up-pn{margin-top:16px;max-width:640px;font-size:14px;line-height:1.6;white-space:pre-line;overflow-wrap:anywhere}

  /* ---------- 自检与诊断（DiagnosticActivity） ---------- */
  .dg-bar{flex:none;display:flex;align-items:center;padding:0 12px 0 8px}
  .dg-bar.tab{padding:6px 24px 6px 16px}
  .dg-bar .ibtn{width:48px;height:48px;color:var(--text);font-size:22px}
  .dg-bar h1{flex:1;min-width:0;margin:0 0 0 10px;font-size:16px;font-weight:700}
  .dg-c{padding:8px 20px var(--page-bottom)}
  .dg-two{display:flex;align-items:flex-start;padding:8px 32px var(--page-bottom)}
  .dg-two>.l{flex:none;width:340px}.dg-two>.r{flex:1;min-width:0;margin-left:24px}
  .dg-lbl{margin:24px 0 10px;font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--text-2)}
  .dg-lbl.first{margin-top:0}
  .dg-card{background:var(--surface);border:1px solid var(--line);border-radius:18px;overflow:hidden}
  .dg-hero{padding:16px}
  .dg-hero .top{display:flex;align-items:center}
  .dg-badge{flex:none;width:48px;height:48px;border-radius:14px;background:var(--raised);color:var(--muted);display:grid;place-items:center;font-size:24px}
  .dg-badge.warn{background:var(--warn-soft);color:var(--warn)}
  .dg-hero .w{flex:1;min-width:0;margin-left:14px}
  .dg-hl{font-size:17px;font-weight:700}
  .dg-sub{margin-top:5px;font-size:13px;line-height:1.55;color:var(--text-2)}
  .dg-hero .btn{margin-top:14px}
  .dg-empty{padding:20px;text-align:center;font-size:13px;line-height:1.55;color:var(--text-2)}
  .dg-empty.tab{padding:24px}
  .dg-row{display:flex;align-items:center;min-height:56px;padding:10px 16px 10px 18px}
  .dg-row .dot{width:8px;height:8px}
  .dg-row .w{flex:1;min-width:0;margin:0 8px 0 12px}
  .dg-row .t{font-size:14px;font-weight:700;line-height:1.35}
  .dg-row .s{margin-top:3px;font-size:12px;color:var(--muted)}
  .dg-row .s.warn{color:var(--warn)}
  .dg-row .chev.rot{transform:rotate(90deg)}
  .dg-dv{height:1px;background:var(--line);margin-left:36px}
  .dg-grid{padding-bottom:6px;border-top:1px solid var(--line)}
  .dg-gl{display:flex;position:relative}
  .dg-gl+.dg-gl::before{content:"";position:absolute;left:40px;right:16px;top:0;height:1px;background:var(--line)}
  .dg-cell{flex:1;min-width:0;display:flex;align-items:center;min-height:44px;padding:0 16px 0 20px}
  .dg-cell .d6{flex:none;width:6px;height:6px;border-radius:50%;background:var(--good);opacity:.7}
  .dg-cell .n{flex:1;min-width:0;margin:0 10px 0 14px;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .dg-cell .v{flex:none;max-width:48%;font-size:12px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;text-align:end}
  .dg-card .entry{padding:12px 16px}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  const esc = h.esc;
  const chev = () => `<span class="chev">${h.icon('ui2_chevron')}</span>`;
  /** ShellRow：32dp 图标块 + 标题 / 说明 + 可选箭头（update_manage / diagnostic_* 同款，行最小高 56）。 */
  const shellRow = (icon, title, sub, arrow, cls) =>
    `<div class="entry${cls ? ' ' + cls : ''}"><span class="tile">${h.icon(icon)}</span>` +
    `<div class="w"><div class="h">${esc(title)}</div>${sub ? `<div class="s">${esc(sub)}</div>` : ''}</div>` +
    (arrow === false ? '' : chev()) + `</div>`;
  const rows = (list, dv) => list.join(`<div class="${dv}"></div>`);

  // =====================================================================
  // 1. 运行环境维护（ExtractActivity）
  // =====================================================================
  // UPDATE 模式的五个显示阶段（DeployStages.UPDATE）。
  const UPDATE_STAGES = ['停止 Web 与终端任务', '解压新版 dsh', '准备内置插件与界面适配', '保留并切换受管文件', '隔离试运行与就绪检查'];
  // 设备摘要为示例（DeviceSupportReport.copyText 格式；gate=OK 时只附“本机环境：…”）。
  const DEVICE = 'DSHA 0.2.1-rc.3-20261102.1430-dsh0.2.1-rc.5 / 279\nAndroid 14 (API 34)\nExample Phone X1\nABI arm64-v8a\nWebView 130.0 (com.google.android.webview)';
  // MaintenanceErrorText：TRIAL_RENDERER_FAILED 的提示 + 错误代码。
  const FAIL_REASON = '新运行时试运行未通过；原运行环境和个人数据已保留。请查看日志后重试或回退。';

  DSHA.page({
    id: 'maintenance', group: '维护与恢复', title: '运行环境维护', shell: 'bare',
    source: 'activity_extract.xml · ExtractActivity · ExtractFooter · MaintenanceFooterPolicy · AdaptiveColumns',
    states: [{ id: 'running', title: '进行中' }, { id: 'failed', title: '失败' }],
    render(env) {
      const failed = env.state === 'failed';
      // ---- 正文列（ExtractActivity.bindViews）----
      const wide = env.w600;
      const column = env.w840 ? 1040 : 640;
      const side = wide ? Math.max(20, Math.floor((env.screenW - column) / 2)) : 20;
      const footerSide = wide ? Math.max(20, Math.floor((env.screenW - 640) / 2)) : 20;
      const colsW = env.screenW - side * 2;
      // 进行中没有说明文字 → extract_focus 整列隐藏，阶段卡占满（AdaptiveColumns 只按可见列排布）。
      const focus = failed;
      const split = focus && colsW >= 720;
      // ---- 阶段（DeployStages.View）：进行中停在第 2 步；失败停在第 5 步 ----
      const current = failed ? 4 : 1;
      const steps = UPDATE_STAGES.map((_, i) => i < current ? 'done' : i === current ? (failed ? 'bad' : 'cur') : 'todo');
      const seg = steps.map(s => `<i class="${s === 'done' ? 'done' : s === 'bad' ? 'bad' : s === 'cur' ? 'run' : ''}"></i>`).join('');
      const list = UPDATE_STAGES.map((t, i) => {
        const s = steps[i];
        const mk = s === 'done' ? h.icon('onb_check') : s === 'bad' ? h.icon('onb_x') : '';
        return `<div class="mx-st ${s}"><span class="mk">${mk}</span><span class="n">${t}</span></div>`;
      }).join('');
      // DeployCard.line：运行中“第 n / m 步 · 阶段”；失败取原因首行。
      const stageLine = failed ? FAIL_REASON : '第 2 / 5 步 · 解压新版 dsh';
      const message =
        '未完成的操作：更新运行环境\n最后记录阶段：正在核验原生模块…\n\n' + FAIL_REASON + '\n错误代码：TRIAL_RENDERER_FAILED' +
        '\n\n原件状态：尚未确认恢复完成。请查看记录后使用“恢复中断维护”；也可先点“导出可读副本”保存数据。' +
        '\n\n任务未完成。请按上方原因处理后重试；本页不会自动覆盖环境。\n\n本机环境：' + DEVICE;
      const stageCard = `<div class="mx-card"><div class="mx-line">${esc(stageLine)}</div>
        <div class="mx-seg" role="img" aria-label="已完成 ${current} / 5 个阶段">${seg}</div>
        <div class="mx-stages">${list}</div></div>`;
      // 救援导出放在说明上方（xml 注释：失败说明较长，放下方手机首屏看不到）。
      const focusCol = focus ? `${h.btn('导出可读副本', 'block mx-rescue')}<div class="mx-err">${esc(message)}</div>` : '';
      // ---- 底栏（ExtractFooter）----
      const sideBySide = env.w600 || env.short480;
      const stacked = env.narrow340; // fontScale 视为 1.0
      const enterLabel = sideBySide ? '进入受限主界面' : '进入受限主界面 · 查看日志与配置';
      const enter = `<button class="btn enter" aria-label="进入受限主界面 · 查看日志与配置">${enterLabel}</button>`;
      // 失败：DANGER 主按钮；进行中：不可点进度面板（level = (完成数 + 0.5) / 总数 = 30%）。
      const primary = failed
        ? `<button class="btn retry danger">重试更新运行时</button>`
        : `<div class="mx-busy" role="progressbar" aria-label="更新中 2 / 5"><i class="fill" style="width:30%"></i>` +
          `<span class="sp"></span><span class="l">更新中</span><span class="k">2 / 5</span></div>`;
      // 竖排：主操作在上、受限入口在下；并排：受限入口在左、主操作在右。
      const acts = sideBySide ? enter + primary : primary + enter;
      const link = (icon, label, aria, hot) =>
        `<button class="mx-link${stacked ? ' stack' : ''}${hot ? ' ' + hot : ''}" aria-label="${aria}">${h.icon(icon)}<span>${label}</span></button>`;
      const shortPad = sideBySide && env.short480 ? 'padding-top:8px;padding-bottom:6px;' : '';
      return `<div class="scroll"><div class="mx-c" style="padding:${wide ? 28 : 22}px ${side}px ${wide ? 24 : 20}px">
          <div class="mx-head">${failed ? '' : '<span class="mx-spin" aria-hidden="true"></span>'}
            <h1 class="mx-title">运行环境维护</h1>
            <span class="mx-pill ${failed ? 'bad' : 'acc'}">${failed ? '未完成' : '2 / 5'}</span></div>
          <div class="mx-status">${failed ? '更新未完成' : '更新运行环境'}</div>
          <div class="mx-lead">相同基础环境只更新 dsh 与内置插件，个人目录、会话和配置保持原位；验证失败回切原运行时。</div>
          <div class="mx-cols${split ? ' split' : ''}"><div class="a">${stageCard}</div>${focus ? `<div class="b">${focusCol}</div>` : ''}</div>
        </div></div>
        <div class="mx-foot" style="${shortPad}padding-left:${footerSide}px;padding-right:${footerSide}px">
          <div class="mx-acts${sideBySide ? ' side' : ''}">${acts}</div>
          <div class="mx-links">${link('recovery_document', '维护记录', '查看本次维护记录')}${link('ui_copy', '复制给支持', '复制本机环境摘要给支持')}${link('recovery_shield', '应急 DSH', '启动应急 DSH', failed ? 'hot-bad' : '')}</div>
        </div>`;
    },
  });

  // =====================================================================
  // 2. 启动恢复（StartupRecoveryActivity / StartupRecoveryLayout）
  // =====================================================================
  const PLUGIN = 'notes-sync'; // 示例插件名
  const ATTEMPTS = [ // 示例记录（StartupRecoveryState.outcomeLabel / elapsedLabel 文案）
    ['bad', '失败 · 插件出错', '今天 09:32', '第 6 秒失败'],
    ['good', '成功', '今天 08:10', '5 秒就绪'],
    ['good', '成功 · 安全模式', '11-01 22:47', '4 秒就绪'],
    ['good', '成功', '11-01 18:05', '5 秒就绪'],
    ['good', '成功', '10-31 21:12', '6 秒就绪'],
  ];
  const ATTEMPTS_OK = [['good', '成功', '今天 09:35', '5 秒就绪']].concat(ATTEMPTS.slice(1));

  DSHA.page({
    id: 'maintenance-startup', group: '维护与恢复', title: '启动恢复', shell: 'bare',
    source: 'StartupRecoveryActivity · StartupRecoveryLayout · StartupRecoveryState',
    states: [{ id: 'plugin', title: '插件加载失败' }, { id: 'running', title: '正常运行' }],
    render(env) {
      const fail = env.state === 'plugin';
      const wide = env.screenW >= 700; // StartupRecoveryLayout：widthPixels / density >= 700
      // 操作行：图标块 + 标题 + 效果说明；blocked 时说明换成「不可用：原因」并整行 0.6 透明。
      const act = (icon, label, sub, blocked) => ({
        html: `<div class="sr-row${blocked ? ' off' : ''}" role="button"${blocked ? ' aria-disabled="true"' : ''}>` +
          (icon ? `<span class="bx">${h.icon(icon)}</span>` : '') +
          `<div class="w"><div class="a${sub ? ' b' : ''}">${esc(label)}</div>${sub ? `<div class="s${blocked ? ' warn' : ''}">${esc(blocked ? '不可用：' + sub : sub)}</div>` : ''}</div>${chev()}</div>`,
        inset: icon ? 54 : 12,
      });
      const stat = (tone, tag, time, el) => ({
        html: `<div class="sr-row" role="button"><span class="dot ${tone === 'info' ? 'acc' : tone}"></span>` +
          `<div class="w"><span class="tg ${tone}">${esc(tag)}</span></div><span class="sr-tm">${esc(time)}</span>${el ? `<span class="sr-el">${esc(el)}</span>` : ''}${chev()}</div>`,
        inset: 32,
      });
      const grp = items => `<div class="sr-grp">${items.map((r, i) => (i ? `<div class="sr-dv" style="margin-left:${r.inset}px"></div>` : '') + r.html).join('')}</div>`;
      const sec = (title, body, extra, note) => `<section class="sr-sec"><div class="sr-sh${note ? ' sub' : ''}"><span class="lb">${title}</span>${extra || ''}</div>` +
        (note ? `<div class="sr-note">${note}</div>` : '') + body + `</section>`;

      // 状态卡：宽屏把错误码放标题右侧，窄屏并入元信息行。
      const code = fail ? 'PLUGIN_LOAD_FAILED' : '';
      // 推断：失败阶段文字取自启动记录 entry.stage，此处以“插件加载”示意。
      const metaParts = fail ? ['启动失败后自动打开', '失败阶段：插件加载'] : ['最近成功：今天 09:35 · 5 秒就绪'];
      if (code && !wide) metaParts.push(code);
      const card = `<div class="sr-card ${fail ? 'bad' : 'good'}" aria-live="polite">
        <div class="hd"><span class="dot ${fail ? 'bad' : 'good'}"></span><span class="t">${fail ? `启动失败：插件 ${PLUGIN} 加载出错` : 'DSH 正在正常运行'}</span>${code && wide ? `<span class="code">${code}</span>` : ''}</div>
        <div class="d">${fail ? '只有这个插件报错。卸载它，或先安全启动再到插件页处理。' : '网页已就绪，最近一次启动没有错误。这里的修复操作会先停止 Web。'}</div>
        <div class="m">${esc(metaParts.join(' · '))}</div>
        <div class="sr-acts">${fail ? h.btn('卸载出错插件', 'pri') + h.btn('安全启动') : h.btn('返回 Web', 'pri')}</div></div>`;

      // 插件：可卸载的用户插件逐行列出（行文字“名称 · 卸载”，StartupRecoveryAudit 按此查找）。
      const plugins = sec('插件', grp([act('', `${PLUGIN} · 卸载`, fail ? '本次启动报错' : '', false)]));
      // 其它恢复操作：状态卡里已有的主操作不重复；运行中 START 类守卫给出“已在运行”。
      const running = !fail;
      const recovery = sec('其它恢复操作', grp([
        act('recovery_refresh', '重试启动', running ? '已在运行' : '按原配置重新启动一次。', running),
        ...(running ? [act('recovery_shield', '安全启动', '已在运行', true)] : []),
        act('recovery_new_file', '新建配置文件', '以默认内容替换所选配置，原文件先存为修复前快照。'),
        act('recovery_clock', '恢复最近健康快照', '只覆盖配置文件；会话、工作区和插件文件不动。'),
        act('recovery_shield', '启动独立应急 DSH', '独立运行根，不读取正式环境，可让 AI 协助排查。'),
      ]));
      const tools = sec('数据与诊断', grp([
        act('recovery_document', '查看并下载日志'), act('recovery_database', '宿主备份与只读救援'),
        act('recovery_clock', '回退兼容运行时'), act('recovery_gear', '环境安装与修复'),
      ]));
      const all = fail ? ATTEMPTS : ATTEMPTS_OK;
      const shown = wide ? all : all.slice(0, 3);
      // 「全部 5 条」只在窄屏出现；原生 minHeight 36dp（StartupRecoveryLayout.attemptsToggle），低于 44dp，保持原尺寸。
      const toggle = !wide && all.length > 3 ? `<button class="tg" data-dense>全部 ${all.length} 条</button>` : '';
      const attempts = sec('最近启动', grp(shown.map(a => stat(...a))),
        `<span class="sum">${fail ? '1 次未成功' : '全部成功'}</span>${toggle}`);
      const snaps = sec('配置快照', grp([stat('good', '健康启动', '今天 08:10'), stat('good', '健康启动', '11-01 22:47'), stat('info', '修复之前', '10-30 19:20')]),
        '', '保留三次健康启动与三次修复前快照；仅保存配置，不复制会话、工作区或依赖目录。');
      const body = wide
        ? `<div class="sr-body wide"><div class="l">${card}${plugins}${recovery}${tools}</div><div class="r">${attempts}${snaps}</div></div>`
        : `<div class="sr-body">${card}${plugins}${recovery}${attempts}${snaps}${tools}</div>`;
      return `<div class="scroll"><div class="sr${wide ? ' wide' : ''}">
        <div class="sr-bar"><button class="ibtn" aria-label="返回">${h.icon('ui_back')}</button><h1>启动恢复</h1>
          <button class="ibtn" aria-label="刷新恢复记录">${h.icon('recovery_refresh')}</button></div>${body}</div></div>`;
    },
  });

  // =====================================================================
  // 3. 应急 DSH（RecoveryActivity；CardPage 无返回行，返回在页脚）
  // =====================================================================
  DSHA.page({
    id: 'maintenance-emergency', group: '维护与恢复', title: '应急 DSH', shell: 'bare',
    source: 'RecoveryActivity · CardPage · RecoveryStatusText（RecoveryWebActivity 为 WebView 外壳，未画）',
    states: [{ id: 'idle', title: '未启动' }, { id: 'ready', title: '已就绪' }],
    render(env) {
      const ready = env.state === 'ready';
      const b = (t, pri, on) => `<button class="btn block${pri ? ' pri' : ''}"${on ? '' : ' disabled'}>${t}</button>`;
      // 推断：提案行 kind / targetId / reason 取 broker.plans() 原值，此处为示例。
      const plans = ready
        ? `<button class="btn block two">repair-runtime · managed-runtime\n待确认</button>
           <div class="em-p">受管运行时缺少原生模块，提议按包内副本重新准备；原件先留存。</div>`
        : `<div class="em-p">应急 DSH 提出方案后在此查看和确认。尚未确认的提案不会修改正式数据。</div>`;
      return `<div class="scroll em"><div class="pg">
        <h1 class="em-t">应急 DSH</h1>
        <div class="card"><div class="em-p">正式环境维护失败时，可在这里启动独立的空白 DSH。它可以读取相关故障文件并向当前模型服务发送内容，提出修复方案；每次正式数据写入都需在此确认。</div>
          <div class="em-s">${ready ? '独立应急 DSH 已就绪；正式环境保持原状。' : '应急 DSH 尚未启动'}</div></div>
        <div class="card"><div class="em-k">临时 API Key（可选，仅本次应急会话使用）</div>
          <div class="em-edit${ready ? ' off' : ''}" role="textbox" aria-label="临时 API Key">留空时尝试已有可读凭据</div>
          ${b('启动应急 DSH', true, !ready)}${b('进入空白应急 Web', true, ready)}${b('停止应急 DSH', false, ready)}
          <div class="em-p" style="margin-top:10px">需要更换临时密钥时，请先停止应急 DSH，再填写并重新启动。</div>
          ${b('查看原生诊断', false, true)}${b('查看无需模型的修复选项', false, true)}</div>
        <div class="em-op"></div>
        <div class="card"><div class="em-h">修复提案</div>${plans}</div>
        <div class="card">${b('查看并下载错误日志', false, true)}${b('备份与保留数据', false, true)}${b('返回正式环境恢复页', false, true)}</div>
      </div></div>
      <div class="em-foot em">${b('返回', false, true)}</div>`;
    },
  });

  // =====================================================================
  // 4. 版本更新（UpdateActivity）
  // =====================================================================
  // 候选版本为示例（预览通道 build281）；候选更新记录正文为示例；本版本记录取 res/raw/changelog.md。
  const REL = { title: '预览版 0.2.1-rc.4', meta: 'build281 · 98.6 MiB',
    notes: '- 修复：后台任务列表切换语言后时长显示成错误的译文。\n- 调整：启动恢复页状态卡在窄屏下的按钮排布。\n- 验证范围：仅本机定向单测与源码门禁；未上真机。' };
  const CUR = { title: 'DSHA 0.2.1-rc.3-20261102.1430-dsh0.2.1-rc.5 / build279',
    notes: '- 实验性：启动恢复页重做，顶部状态卡说明当前结论，失败按原因分类并给出对应的下一步。\n- build278 未单独发布：其中后台任务列表的时长在切换语言时会显示成错误的译文，本版已修复，build278 的其余改动随本版一起发布。\n- 验证范围：仅本机定向单测、Lint 与源码门禁；未上真机。版本码提升为 279（基于 build278）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。' };

  DSHA.page({
    id: 'maintenance-update', group: '维护与恢复', title: '版本更新', shell: 'bare',
    source: 'activity_update.xml · layout-w840dp/activity_update.xml · update_hero/candidate/manage · UpdateUi · UpdateProgressButton',
    states: [{ id: 'idle', title: '空闲' }, { id: 'downloading', title: '下载中' }, { id: 'ready', title: '可安装' }],
    render(env) {
      const st = env.state;
      const rel = st !== 'idle';
      // UpdateActionPolicy.resolve：空闲无候选 → 检查（MUTED）；下载中 FILL（ACCENT，锁定通道）；已下载 → 安装（GOOD）。
      const status = st === 'idle' ? '暂无可用更新' : st === 'downloading' ? '正在下载，离开页面后继续' : '下载及校验完成，点击「安装更新」继续';
      const dot = st === 'idle' ? '' : st === 'downloading' ? 'acc' : 'good';
      const recheck = st === 'ready'; // idle && hasRelease
      const chOn = st !== 'downloading';
      let action;
      if (st === 'downloading') {
        // 按钮即进度条：底层主色字 + 上层白字按填充宽度裁切。
        const pct = 42, label = '42% · 41.4 / 98.6 MiB';
        action = `<button class="up-act fill" disabled role="progressbar" aria-valuenow="${pct}" aria-label="${label}"><span class="base">${label}</span>` +
          `<span class="lite" style="width:${pct}%"><span style="width:${(10000 / pct).toFixed(2)}%">${label}</span></span></button>`;
      } else action = `<button class="up-act">${st === 'ready' ? '安装更新' : '检查更新'}</button>`;
      // 品牌图 dsha_brand 无对应图标资源，以 terminal 图标块示意。
      const hero = `<div class="up-card up-hero">
        <div class="up-top"><span class="up-logo" aria-hidden="true">${h.icon('terminal')}</span>
          <div class="w"><div class="up-name">DSH-A-Su</div><button class="up-ver" aria-expanded="false">0.2.1-rc.3 · build279${h.icon('chevron_down')}</button></div>
          <span class="up-ed">标准版</span></div>
        <div class="up-hr"></div>
        <div class="up-ch"><span class="lb">更新通道</span><div class="up-seg${chOn ? '' : ' off'}" role="radiogroup">
          <button class="${rel ? '' : 'on'}"${chOn ? '' : ' disabled'}>稳定版</button><button class="${rel ? 'on' : ''}"${chOn ? '' : ' disabled'}>预览版</button></div></div>
        <div class="up-st"><span class="dot ${dot}"></span><div class="tx">${status}</div>${recheck ? '<button class="re">重新检查</button>' : ''}</div>
        ${action}</div>`;
      const manage = act => `<div class="up-card" style="overflow:hidden">${rows([
        shellRow('ui2_box', '更新受管运行时', '只更新随包组件，保留个人数据'),
        shellRow('recovery_document', '兼容运行时回退', '通过隔离试运行后再切换'),
        shellRow('recovery_document', '本版本更新记录', '0.2.1-rc.3 · build279', true, act ? 'act' : ''),
        shellRow('onb_external', '在浏览器查看发布页', '查看所有版本与安装包'),
      ], 'up-dv')}</div>`;
      const bar = `<div class="up-bar${env.w840 ? ' w840' : ''}"><button class="ibtn" aria-label="返回上一页">${h.icon('ui_back')}</button><h1>版本更新</h1></div>`;

      if (env.w840) {
        // 平板横屏两栏：有候选时右栏默认“候选版本”，否则“本版本”；左栏对应行以 activated 软底标出。
        const pane = rel ? 'cand' : 'cur';
        const left = hero +
          (rel ? `<div class="up-lbl">候选版本</div><div class="up-card" style="overflow:hidden">${shellRow('ui2_box', REL.title, REL.meta, true, 'act')}</div>` : '') +
          `<div class="up-lbl">管理</div>${manage(pane === 'cur')}`;
        const right = `<div class="up-seg pane" role="tablist"><button class="${pane === 'cand' ? 'on' : ''}">候选版本</button><button class="${pane === 'cur' ? 'on' : ''}">本版本</button></div>` +
          (pane === 'cand'
            ? `<div class="up-pt">${REL.title}</div><div class="up-meta" style="margin-top:6px">${REL.meta}</div><div class="up-pn">${esc(REL.notes)}</div>`
            : `<div class="up-pt">${esc(CUR.title)}</div><div class="up-pn">${esc(CUR.notes)}</div>`);
        return bar + `<div class="up-two"><div class="scroll l"><div class="up-c l">${left}</div></div><div class="vl"></div>
          <div class="scroll"><div class="up-c r">${right}</div></div></div>`;
      }
      // 手机 / 平板竖屏：centerColumn 把内容收成 640dp 居中列（侧距不小于 20）。
      const side = Math.max(20, Math.floor((env.screenW - 640) / 2));
      const cand = rel ? `<div class="up-card up-cand"><div class="top"><span class="tile">${h.icon('ui2_box')}</span>
          <div class="w"><div class="tt">${REL.title}</div><div class="up-meta">${REL.meta}</div></div></div>
          <div class="up-notes">${esc(REL.notes)}</div>
          <div class="up-more"><button>查看完整记录${h.icon('ui2_chevron')}</button></div></div>` : '';
      return bar + `<div class="scroll"><div class="up-c" style="padding-left:${side}px;padding-right:${side}px">${hero}${cand}
        <div class="up-lbl">管理</div>${manage(false)}</div></div>`;
    },
  });

  // =====================================================================
  // 5. 自检与诊断（DiagnosticActivity）
  // =====================================================================
  // 结果为示例：标题取 DiagnosticRepository 原文，状态文字取对应分支文案；WebView 版本为示例值。
  const PROBLEMS = [
    ['warn', '插件加载', '存在加载错误'],
    ['warn', '最近隔离运行试验', '失败证据已保留'],
    ['', 'Web 鉴权与桥接', 'DSH 未运行'],
  ];
  const OK = [
    ['应用与设备', '本次只读采样'], ['网页内核', 'WebView 130.0'], ['Ubuntu 与 Bash 加载器', '就绪检查通过'],
    ['运行工具文件', '文件存在'], ['所选运行方式', 'proot · SMOKE_OK'], ['运行时版本与身份', '与包内版本一致'],
    ['存储与数据目录', '私有目录可用'], ['设备通道', '按需授权'], ['宿主进程资源', '本次只读采样'],
  ];

  DSHA.page({
    id: 'maintenance-diagnostics', group: '维护与恢复', title: '自检与诊断', shell: 'bare',
    source: 'activity_diagnostics.xml · layout-sw600dp/activity_diagnostics.xml · diagnostic_hero/feedback/tools · DiagnosticResultList',
    states: [{ id: 'idle', title: '尚未检查' }, { id: 'attention', title: '需要处理' }, { id: 'expanded', title: '展开正常项' }],
    render(env) {
      const idle = env.state === 'idle';
      const expanded = env.state === 'expanded';
      const two = env.tablet; // layout-sw600dp
      const hero = `<div class="dg-card dg-hero"><div class="top"><span class="dg-badge${idle ? '' : ' warn'}">${h.icon(idle ? 'onb_info' : 'onb_alert')}</span>
        <div class="w"><div class="dg-hl" aria-live="polite">${idle ? '尚未检查' : '2 项需要处理'}</div>
        <div class="dg-sub">${idle ? '检查组件、存储与服务，生成脱敏报告' : '问题已排在结果顶部，点开查看原因'}</div></div></div>
        ${h.btn(idle ? '开始检查' : '重新检查', 'pri block')}</div>`;
      const feedback = `<div class="dg-card">${rows([
        shellRow('ui_copy', '复制诊断报告', '脱敏文本，可粘贴到反馈', false),
        shellRow('ui_export', '导出故障包', '结构化日志包，可附复现步骤'),
      ], 'up-dv')}</div>`;
      const tools = `<div class="dg-card">${rows([
        shellRow('recovery_document', '最近五次启动', '阶段、实际错误与退出码'),
        shellRow('recovery_shield', '启动故障处理', '查看问题与恢复方式'),
        shellRow('recovery_wrench', '修复工具', '重新准备证书、Python、npm 与 pnpm', false),
        shellRow('plugins', '插件管理', '排查插件导致的启动问题'),
      ], 'up-dv')}</div>`;
      // 结果卡宽：手机 = screenW - 40；平板右栏 = screenW - 64 - 340 - 24。
      const cardW = two ? env.screenW - 64 - 340 - 24 : env.screenW - 40;
      const cols = cardW >= 560 ? 2 : 1; // DiagnosticSummary.okColumns（字体 1.0）
      let results;
      if (idle) results = `<div class="dg-empty${two ? ' tab' : ''}">还没有结果。检查只读取本机状态，不修改环境。</div>`;
      else {
        const prob = PROBLEMS.map(([tone, t, s]) => `<div class="dg-row" role="button"><span class="dot ${tone}"></span>` +
          `<div class="w"><div class="t">${t}</div><div class="s${tone ? ' warn' : ''}">${s}</div></div>${chev()}</div>`);
        prob.push(`<div class="dg-row" role="button" aria-expanded="${expanded}"><span class="dot good"></span><div class="w"><div class="t">${OK.length} 项正常</div>` +
          `<div class="s">${expanded ? '点按收起' : '点按展开'}</div></div><span class="chev${expanded ? ' rot' : ''}">${h.icon('ui2_chevron')}</span></div>`);
        let grid = '';
        if (expanded) {
          const lines = [];
          for (let i = 0; i < OK.length; i += cols) {
            const cells = [];
            for (let c = 0; c < cols; c++) {
              const it = OK[i + c];
              cells.push(it ? `<div class="dg-cell" data-touch><span class="d6"></span><span class="n">${it[0]}</span><span class="v">${it[1]}</span></div>` : '<div class="dg-cell"></div>');
            }
            lines.push(`<div class="dg-gl">${cells.join('')}</div>`);
          }
          grid = `<div class="dg-grid">${lines.join('')}</div>`;
        }
        results = rows(prob, 'dg-dv') + grid;
      }
      const resultCard = `<div class="dg-card">${results}</div>`;
      const bar = `<div class="dg-bar${two ? ' tab' : ''}"><button class="ibtn" aria-label="返回上一页">${h.icon('ui_back')}</button><h1>自检与诊断</h1></div>`;
      if (two) {
        return bar + `<div class="scroll"><div class="dg-two">
          <div class="l"><div class="dg-lbl first">状态</div>${hero}<div class="dg-lbl">反馈</div>${feedback}<div class="dg-lbl">工具</div>${tools}</div>
          <div class="r"><div class="dg-lbl first">检查结果</div>${resultCard}</div></div></div>`;
      }
      return bar + `<div class="scroll"><div class="dg-c">${hero}<div class="dg-lbl">检查结果</div>${resultCard}
        <div class="dg-lbl">反馈</div>${feedback}<div class="dg-lbl">工具</div>${tools}</div></div>`;
    },
  });
})();
