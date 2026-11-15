/*
 * 引导与授权 · 首次引导四步、开机加载页、屏幕操作设置、ADB 无线配对。
 * 来源：
 *   ui/OnboardingActivity.java（buildShell / buildSide / sideWidth / contentWidthDp / buildWelcome / buildGoals /
 *       buildPerms / buildFinish / runtimeCard / grantsCard）、ui/OnboardingKit.java（wide / shortScreen / 按钮 /
 *       pill / hint / Grid / FlowRow / segmentBar / stageList）、ui/OnboardingRoles.java（行=按钮、用途=复选框）、
 *       ui/OnboardingEnvironment.java（空间预检）、ui/DeployCapsule.java、ui/DeployCard.java、
 *       ui/PermissionPanel.java、ui/PermissionCard.java、ui/PermissionCards.java、ui/PermissionChannelCard.java、
 *       util/PermissionGoals.java、util/DeployStages.java（INSTALL 八阶段）、util/DeviceSupportReport.java；
 *   ui/BootScreen.java、ui/BootLogoView.java、ui/MainActivity.java（“正在检查运行环境…”）、ui/DirectWebLaunch.java；
 *   ui/AccessibilitySetupActivity.java + ui/CardPage.java + ui/UiNavigation.addHeader；
 *   res/layout/activity_adb_pair.xml + ui/AdbPairActivity.java；
 *   drawable/bg_onb_*.xml、values/dimens.xml（onb_radius_card 18 / tile 14 / capcard 16）、values/strings.xml。
 * 布局判据（只用 env）：
 *   - 引导 OnboardingKit.wide = screenWidthDp >= 600（env.w600）：左侧栏 sideWidth = max(240, 30% × screenW) + 1dp 线；
 *     页边距 28（手机 20）；无手机步骤条、无顶栏胶囊，改由左栏 DeployCard 显示部署进度；页脚按钮横排靠右、每个 >= 168dp。
 *   - OnboardingKit.shortScreen = screenHeightDp <= 480（env.short480）：压缩页头 / 页脚 / 按钮（主 44、次 40）。
 *     七个基准档位的 screenH 都 > 480，所以预览里这一档不会出现（手机横屏才会命中）。
 *   - 正文宽 contentWidthDp：宽屏 screenW - sideW - 1 - 56，手机 screenW - 40，再取 min(680/980)。
 *     功能点三列：正文宽 >= 620；部署前检查 Grid 两列：>= 120×2+8；用途 Grid 两列：>= 290×2+10。
 *   - DeployCapsule：screenW < 370（env.narrow370）不显示“· 阶段”副标签；最大宽 = 屏宽 × 0.62。
 *   - BootScreen：smallestScreenWidthDp >= 600（env.tablet）放大标志（112/88）、字标（20/16）、圆环（26/20），
 *     底部按钮区限宽 480 居中（手机铺满）。
 * 示例数据（推断，均为脱敏样例）：设备 arm64-v8a / Android 14 / 系统 WebView 130.0.6723.102 / 可用 18.4 GB；
 *   空间不足样例 1.2 GB / 需约 2.9 GB；失败原因句为示意，真实文案来自 MaintenanceErrorText.render(detail)。
 */
(function () {
  const { h } = DSHA;
  const { esc, icon } = h;
  const css = `
  .ob-root{flex:1;min-height:0;display:flex}
  .ob-main{flex:1;min-width:0;min-height:0;display:flex;flex-direction:column;background:var(--bg)}
  .ob-rule{flex:none;width:1px;background:var(--line)}
  .ob-side{flex:none;background:var(--surface);overflow-y:auto;scrollbar-width:none;display:flex;flex-direction:column}
  .ob-side::-webkit-scrollbar{display:none}
  .ob-side>.in{flex:1;display:flex;flex-direction:column;padding:22px 18px 18px}
  .ob-side .nm{font-size:22px;font-weight:700;line-height:1.2}
  .ob-side .ct{margin-top:4px;font-size:12.5px;color:var(--muted)}
  .ob-side .sp{flex:1}
  .ob-srow{display:flex;align-items:center;padding:8px 10px;border-radius:12px;margin-top:3px;font-size:14px;color:var(--muted)}
  .ob-srow:first-child{margin-top:0}
  .ob-srow.cur{background:var(--accent-soft);color:var(--text);font-weight:700}
  .ob-srow .mk{flex:none;width:24px;height:24px;border-radius:50%;border:1.5px solid var(--line-strong);display:grid;place-items:center;
    font:700 11px var(--mono);color:var(--muted)}
  .ob-srow.cur .mk{border-color:var(--accent);color:var(--accent)}
  .ob-srow.done .mk{border:0;background:var(--accent);color:var(--on-accent);font-size:13px}
  .ob-srow .lb{margin-left:10px;flex:1;min-width:0}
  .ob-head{flex:none;display:flex;align-items:center}
  .ob-left{flex:none;display:flex;align-items:center}
  .ob-back{width:48px;height:48px;border:0;background:none;border-radius:13px;display:grid;place-items:center;color:var(--text);font-size:24px;padding:0}
  .ob-brand{font-size:18px;font-weight:700;padding-left:10px}
  .ob-mid{flex:1;min-width:0;display:flex;justify-content:center}
  .ob-counter{flex:none;min-width:40px;text-align:end;font:700 11.5px var(--mono);color:var(--muted)}
  .ob-steps{flex:none;display:flex;gap:4px;padding:2px 20px 4px}
  .ob-steps>i{flex:1;height:3px;border-radius:3px;background:var(--raised)}
  .ob-steps>i.on{background:var(--accent)}
  .ob-cap{margin:0 auto;width:100%;display:flex;flex-direction:column}
  .ob-foot{flex:none;background:var(--surface);border-top:1px solid var(--line)}
  /* 部署胶囊 DeployCapsule（34dp，原生小于 44dp） */
  .ob-capsule{display:inline-flex;align-items:center;min-height:34px;min-width:0;padding:0 10px 0 11px;border-radius:999px;
    border:1px solid var(--line-strong);background:var(--surface);font-size:12.5px;color:var(--text)}
  .ob-capsule.ok{border-color:var(--good)}.ob-capsule.bad{border-color:var(--bad);background:var(--bad-soft)}
  .ob-capsule .d{flex:none;width:8px;height:8px;border-radius:50%;background:var(--accent)}
  .ob-capsule.ok .d{background:var(--good)}.ob-capsule.bad .d{background:var(--bad)}
  .ob-capsule .l{flex:none;margin-left:7px;font-weight:700;white-space:nowrap}
  .ob-capsule.ok .l{color:var(--good)}.ob-capsule.bad .l{color:var(--bad)}
  .ob-capsule .s{margin-left:5px;flex:1;min-width:0;color:var(--text-2);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .ob-capsule .n{flex:none;margin-left:6px;font:700 11px var(--mono);color:var(--muted)}
  .ob-capsule .c{flex:none;margin-left:5px;font-size:14px;color:var(--muted)}
  .ob-capsule.ok .c{color:var(--good)}.ob-capsule.bad .c{color:var(--bad)}
  /* 左栏进度卡 DeployCard */
  .ob-dcard{padding:14px;border-radius:16px;background:var(--bg);border:1px solid var(--line-strong)}
  .ob-dcard.idle{border-style:dashed}
  .ob-dcard.ok{border-color:var(--good)}.ob-dcard.bad{border-color:var(--bad)}
  .ob-dcard .cl{margin-top:8px;font-size:12.5px;line-height:1.45;color:var(--text-2)}
  .ob-dcard .more{margin-top:8px;display:flex;align-items:center;gap:4px;font-size:12.5px;font-weight:700;color:var(--accent)}
  .ob-dcard .more .i{font-size:14px}
  .ob-chead{display:flex;align-items:center}
  .ob-chead .t{flex:1;min-width:0;font-size:15.5px;font-weight:700;line-height:1.3}
  .ob-chead .ob-pill{margin-left:10px}
  .ob-pill{display:inline-block;padding:3px 9px;border-radius:999px;font-size:11.5px;font-weight:700;line-height:1.35;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
    background:var(--raised);color:var(--muted);max-width:100%}
  .ob-pill.acc{background:var(--accent-soft);color:var(--accent)}.ob-pill.good{background:var(--good-soft);color:var(--good)}
  .ob-pill.warn{background:var(--warn-soft);color:var(--warn)}.ob-pill.bad{background:var(--bad-soft);color:var(--bad)}
  .ob-seg{display:flex;gap:3px;margin-top:10px}
  .ob-seg>i{flex:1;height:6px;border-radius:3px;background:var(--raised)}
  .ob-seg>i.done{background:var(--accent)}.ob-seg>i.ok{background:var(--good)}.ob-seg>i.bad{background:var(--bad)}
  .ob-seg>i.run{background:linear-gradient(90deg,var(--accent) 0%,var(--accent-tonal) 55%,var(--accent) 100%)}
  /* 正文排版 */
  .ob-eb{font-size:12px;font-weight:700;letter-spacing:.12em;color:var(--accent)}
  .ob-h{margin:0;font-weight:700;line-height:1.28;white-space:pre-line}
  .ob-lead{margin:0;font-size:15px;line-height:1.55;color:var(--text-2);white-space:pre-line}
  .ob-fine{font-size:12px;line-height:1.5;color:var(--muted)}
  .ob-sec{font-size:12.5px;font-weight:700;letter-spacing:.04em;color:var(--muted)}
  .ob-badge{align-self:flex-start;padding:6px 11px;border-radius:999px;background:var(--accent-soft);color:var(--accent);font-size:12px;font-weight:700;white-space:pre-wrap}
  .ob-feats{display:flex;flex-direction:column;gap:12px}
  .ob-feats.cols{flex-direction:row;align-items:flex-start}
  .ob-feat{display:flex;align-items:flex-start;flex:1;min-width:0}
  .ob-feats.cols .ob-feat{flex-direction:column}
  .ob-feat .w{flex:1;min-width:0;margin-left:12px}
  .ob-feats.cols .ob-feat .w{margin:8px 0 0}
  .ob-ibox{flex:none;border-radius:12px;background:var(--accent-soft);color:var(--accent);display:grid;place-items:center}
  .ob-ibox.good{background:var(--good-soft);color:var(--good)}
  .ob-t15{font-size:15px;font-weight:700;line-height:1.35}
  .ob-s125{margin-top:3px;font-size:12.5px;line-height:1.45;color:var(--muted)}
  .ob-grid{display:grid}
  .ob-grid>.full{grid-column:1/-1}
  .ob-tile{padding:11px 13px;border-radius:14px;background:var(--surface);border:1px solid var(--line)}
  .ob-tile.bad{background:var(--bad-soft);border-color:var(--bad)}
  .ob-tile .k{font-size:11px;color:var(--muted)}
  .ob-tile .v{margin-top:4px;font-size:14.5px;font-weight:700;line-height:1.35;overflow-wrap:anywhere}
  .ob-tile.bad .v{color:var(--bad)}
  .ob-link{display:flex;align-items:center;min-height:56px;padding:12px 12px 12px 14px;border-radius:14px;background:var(--surface);border:1px solid var(--line)}
  .ob-link .w{flex:1;min-width:0}
  .ob-link .ttl{font-size:14.5px;font-weight:700;line-height:1.35}
  .ob-link .chev{font-size:18px}
  .ob-link .slot{flex:none;width:38px;height:38px;display:grid;place-items:center;color:var(--accent);font-size:22px}
  .ob-hint{display:flex;align-items:flex-start;gap:8px;padding:10px 12px;border-radius:14px;background:var(--accent-soft);font-size:13px;line-height:1.5;color:var(--text-2)}
  .ob-hint .i{font-size:16px;margin-top:1px;color:var(--accent)}
  .ob-hint.warn{background:var(--warn-soft);color:var(--warn)}.ob-hint.warn .i{color:var(--warn)}
  .ob-hint.bad{background:var(--bad-soft);color:var(--bad)}.ob-hint.bad .i{color:var(--bad)}
  .ob-msg{padding:12px;border-radius:14px;background:var(--bad-soft);color:var(--bad);font-size:13px;line-height:1.5;white-space:pre-line;overflow-wrap:anywhere}
  /* 按钮（OnboardingKit：主 52 / 次 46 / 文字 40 / 小 38 / 行内 34） */
  .ob-btn{display:inline-flex;align-items:center;justify-content:center;min-width:0;padding:6px 18px;border-radius:13px;border:1px solid var(--line);
    background:var(--surface);color:var(--text);font-size:14.5px;font-weight:700;line-height:1.25;text-align:center;min-height:46px}
  .ob-btn.pri{min-height:52px;background:var(--accent);border-color:transparent;color:var(--on-accent)}
  .ob-btn.pri:disabled{background:var(--disabled);color:var(--muted)}
  .ob-btn.ghost{min-height:40px;padding:0 10px;border-color:transparent;background:none;color:var(--accent)}
  .ob-btn.sm{min-height:38px;padding:0 12px;font-size:13px}
  .ob-btn.inline{flex:none;min-height:34px;padding:0 12px;font-size:12.5px;max-width:150px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:inline-block;line-height:34px}
  .ob-bar{display:flex;flex-direction:column;gap:8px}
  .ob-bar>.ob-btn{width:100%}
  .ob-bar.wide{flex-direction:row-reverse;flex-wrap:wrap;justify-content:flex-start;gap:8px}
  .ob-bar.wide>.ob-btn{width:auto;min-width:168px;max-width:100%}
  .ob-acts{display:flex;flex-wrap:wrap;gap:6px}
  /* 用途 */
  .ob-goal{display:flex;align-items:center;min-height:64px;padding:13px 14px;border-radius:18px;background:var(--surface);border:1.5px solid var(--line-strong)}
  .ob-goal.on{background:var(--accent-soft);border-color:var(--accent)}
  .ob-goal.lock{opacity:.78}
  .ob-goal .bx{flex:none;width:22px;height:22px;border-radius:7px;border:2px solid var(--line-strong);display:grid;place-items:center;font-size:14px;color:var(--on-accent)}
  .ob-goal.on .bx{border:0;background:var(--accent)}
  .ob-goal .w{flex:1;min-width:0;margin:0 12px}
  .ob-goal .s{margin-top:2px;font-size:12.5px;line-height:1.45;color:var(--muted)}
  /* 授权列表 */
  .ob-sum .lb{font-size:13px;font-weight:700;color:var(--text-2)}
  .ob-sum .mt{margin-top:6px;height:4px;border-radius:2px;background:var(--line);overflow:hidden}
  .ob-sum .mt>i{display:block;height:100%;background:var(--good)}
  .ob-plist{border-radius:18px;background:var(--surface);border:1px solid var(--line);overflow:hidden}
  .ob-plist>.dv{height:1px;background:var(--line);margin-inline-start:54px}
  .ob-prow{display:flex;align-items:center;min-height:56px;padding:8px 10px 8px 12px}
  .ob-prow .w{flex:1;min-width:0;margin:0 8px 0 10px}
  .ob-prow .tt{font-size:14.5px;font-weight:700;line-height:1.3}
  .ob-prow .psub{margin-top:2px;font-size:12px;line-height:1.4;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .ob-prow .psub.warn{color:var(--warn)}
  .ob-prow .psub.ml{white-space:normal;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical}
  .ob-prow .ob-pill{flex:none;max-width:128px}
  .ob-prow .chev{flex:none;margin-inline-start:6px;font-size:16px;color:var(--muted)}
  .ob-prow .chev.open{transform:rotate(90deg)}
  .ob-pdet{padding:0 14px 12px 54px;font-size:12.5px;line-height:1.5;color:var(--text-2)}
  .ob-pdet .tags{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}
  .ob-pdet .tags>span{padding:2px 8px;border-radius:999px;background:var(--raised);font-size:11.5px}
  .ob-pdet .out{display:flex;align-items:flex-start;gap:6px;margin-top:8px;font-size:12px;color:var(--muted)}
  .ob-pdet .out .i{font-size:13px;margin-top:2px}
  /* 完成页卡片 */
  .ob-card{padding:16px;border-radius:18px;background:var(--surface);border:1px solid var(--line)}
  .ob-stl{margin-top:10px}
  .ob-st{display:flex;align-items:flex-start;padding:6px 0;font-size:13px;line-height:1.45;color:var(--muted)}
  .ob-st .mk{flex:none;width:18px;height:18px;margin-top:1px;border-radius:50%;border:1.5px solid var(--line-strong);display:grid;place-items:center;font-size:12px;color:var(--on-accent)}
  .ob-st .lb{margin-left:10px;flex:1;min-width:0}
  .ob-st.done{color:var(--text-2)}.ob-st.done .mk{border:0;background:var(--accent)}
  .ob-st.good .mk{background:var(--good)}
  .ob-st.cur{color:var(--text);font-weight:700}
  .ob-st.cur .mk{border:2px solid var(--accent-tonal);border-top-color:var(--accent)}
  .ob-st.bad{color:var(--bad);font-weight:700}.ob-st.bad .mk{border:0;background:var(--bad)}
  .ob-chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}
  .ob-gnote{display:flex;align-items:center;margin-top:8px}
  .ob-gnote .ob-fine{flex:1;min-width:0}
  .ob-gnote .ob-btn{font-size:13px;flex:none}
  /* 开机加载页 BootScreen */
  .bt{position:relative;flex:1;min-height:0;display:flex;flex-direction:column;align-items:center;justify-content:center;overflow:hidden;background:var(--bg)}
  .bt-glow{position:absolute;inset:0;opacity:.15;pointer-events:none}
  .bt-card{position:relative;display:flex;flex-direction:column;align-items:center}
  .bt-logo{color:var(--accent)}
  .bt-logo svg{width:100%;height:100%;display:block}
  .bt-word{font-weight:700;letter-spacing:.3em;margin-right:-.3em;color:var(--text);line-height:1}
  .bt-spin{border-radius:50%;border-style:solid;border-color:var(--line-strong);border-top-color:var(--accent);transform:rotate(45deg)}
  .bt-hint{text-align:center;line-height:1.2;color:var(--muted)}
  .bt-acts{position:absolute;bottom:16px;display:flex}
  .bt-acts>button{flex:1;min-height:48px;padding:0 16px;border:0;background:none;color:var(--text);border-radius:8px;font-size:inherit}
  /* ADB 无线配对（activity_adb_pair.xml） */
  .ad-head{flex:none;display:flex;align-items:center;padding:0 18px 0 12px}
  .ad-back{width:48px;height:48px;border:0;background:none;border-radius:50%;display:grid;place-items:center;color:var(--text);font-size:24px;padding:0}
  .ad-pg{padding:18px}
  .ad-title{margin:0;font-size:var(--page-title);font-weight:700;line-height:1.25}
  .ad-desc{margin:8px 0 18px;font-size:var(--page-desc);line-height:1.55;color:var(--text-2)}
  .ad-card{padding:16px;border-radius:18px;background:var(--surface);border:1px solid var(--line);margin-bottom:12px}
  .ad-ct{font-size:13px;font-weight:700}
  .ad-st{margin-top:8px;font-size:13px;line-height:1.45;color:var(--text-2);display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}
  .ad-detail{display:flex;align-items:center;min-height:40px;font-size:12px;color:var(--accent);background:none;border:0;padding:0}
  .ad-act{display:flex;width:100%;align-items:center;justify-content:center;min-height:48px;padding:12px 16px;border-radius:18px;border:1px solid var(--line);
    background:var(--surface);color:var(--text);font-size:14px;font-weight:500;margin-top:8px}
  .ad-act:disabled{background:var(--disabled);color:var(--text-2);border-color:transparent}
  .ad-lbl{margin:10px 0 8px;font-size:11px;color:var(--text)}
  .ad-in{min-height:48px;padding:12px;border-radius:13px;border:1px solid var(--line);background:var(--surface);font-size:14px;color:var(--text)}
  .ad-in.off{background:var(--disabled);color:var(--text-2)}
  .ad-code{margin-top:14px;height:64px;border-radius:22px;border:1px solid var(--line);background:var(--surface);display:grid;place-items:center;
    font-size:24px;letter-spacing:.2em;color:var(--text);font-family:var(--mono)}
  .ad-start{margin-top:16px;width:100%;min-height:52px;border:0;border-radius:13px;background:var(--accent);color:var(--on-accent);font-size:14px;font-weight:700;padding:0 16px}
  .ad-start:disabled{background:var(--disabled);color:var(--muted)}
  .ad-plain{display:flex;width:100%;align-items:center;justify-content:center;min-height:48px;margin-top:10px;padding:12px 16px;border-radius:13px;border:1px solid var(--line);background:var(--surface);color:var(--text);font-size:14px}
  .ad-text{display:inline-flex;align-items:center;min-height:48px;margin-top:6px;padding:8px 16px;border:0;background:none;color:var(--accent);font-size:14px;border-radius:8px}
  /* 屏幕操作设置（CardPage） */
  .ax-txt{font-size:13px;line-height:1.5;color:var(--text-2)}
  .ax-st{font-size:13px;line-height:1.5;color:var(--text)}
  .ax-help{font-size:16px;color:var(--text);padding:4px 0 6px}
  .ax-btn{margin-top:10px}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  // ---------------------------------------------------------------- 部署模型（DeployStages INSTALL 八阶段）
  const STAGES = ['准备部署', '解压 Ubuntu 与 Node', '解压 dsh 与内置依赖', '安装 Python 与 pnpm', '准备应用工具',
    '安装离线 curl、git 与证书', '核对并提交安装文件', '检查启动配置与隔离试运行'];
  const D = {
    idle: { phase: 'idle' },
    run: cur => ({ phase: 'run', cur }),
    ok: { phase: 'ok' },
    fail: cur => ({ phase: 'fail', cur }),
  };
  const stepOf = (d, i) => d.phase === 'ok' ? 'done' : i < d.cur ? 'done' : i === d.cur ? (d.phase === 'fail' ? 'bad' : 'cur') : 'todo';
  const capLabel = d => ({ run: '部署中', ok: '环境已就绪', fail: '部署未完成' })[d.phase];
  const pillText = d => d.phase === 'run' ? `${d.cur + 1} / ${STAGES.length}` : d.phase === 'ok' ? '已就绪' : '未完成';
  const toneOf = d => ({ run: 'acc', ok: 'good', fail: 'bad', idle: '' })[d.phase];
  const FAIL_REASON = '可用空间不足，解压未完成。原环境与个人数据保持原样。'; // 推断：示意 MaintenanceErrorText 输出
  const cardLine = d => d.phase === 'run' ? `第 ${d.cur + 1} / ${STAGES.length} 步 · ${STAGES[d.cur]}`
    : d.phase === 'ok' ? '运行环境已部署并通过检查。' : FAIL_REASON.split('。')[0] + '。';

  const pill = (t, tone) => `<span class="ob-pill ${tone || ''}">${esc(t)}</span>`;
  const hint = (t, tone) => `<div class="ob-hint ${tone || ''}">${icon(tone === 'bad' || tone === 'warn' ? 'onb_alert' : 'onb_info')}<div>${esc(t)}</div></div>`;
  const ibox = (ic, box, sz, good) => `<span class="ob-ibox${good ? ' good' : ''}" style="width:${box}px;height:${box}px;font-size:${sz}px">${icon(ic)}</span>`;
  const gap = (px, html) => `<div style="margin-top:${px}px">${html}</div>`;
  const segBar = d => `<div class="ob-seg" role="img" aria-label="阶段进度">` + STAGES.map((_, i) => {
    const s = stepOf(d, i);
    const cls = d.phase === 'ok' ? 'ok' : s === 'cur' ? 'run' : s === 'done' ? 'done' : s === 'bad' ? 'bad' : '';
    return `<i class="${cls}"></i>`;
  }).join('') + `</div>`;

  // 顶栏胶囊：原生 34dp 高，低于 44dp，加 data-dense。
  function capsule(env, d) {
    if (d.phase === 'idle') return '';
    const cls = d.phase === 'ok' ? 'ok' : d.phase === 'fail' ? 'bad' : '';
    const sub = d.phase === 'run' ? STAGES[d.cur] : d.phase === 'fail' ? '点此查看' : '';
    const max = Math.round(env.screenW * 0.62);
    return `<button class="ob-capsule ${cls}" data-dense aria-label="运行环境部署：${esc(capLabel(d))}，点按查看详情" style="max-width:min(${max}px,100%)">` +
      `<span class="d"></span><span class="l">${esc(capLabel(d))}</span>` +
      (sub && !env.narrow370 ? `<span class="s">· ${esc(sub)}</span>` : '') +
      (d.phase === 'run' ? `<span class="n">${d.cur + 1}/${STAGES.length}</span>` : '') +
      `<span class="c">${icon('ui2_chevron')}</span></button>`;
  }

  function deployCard(d, mt) {
    if (d.phase === 'idle') {
      return `<div class="ob-dcard idle" style="margin-top:${mt}px"><div class="ob-chead"><span class="t">运行环境</span>${pill('未开始')}</div>` +
        `<div class="cl">点「开始部署」后在后台部署，期间可以继续完成引导。</div></div>`;
    }
    const cls = d.phase === 'ok' ? 'ok' : d.phase === 'fail' ? 'bad' : '';
    return `<div class="ob-dcard ${cls}" style="margin-top:${mt}px" data-touch role="button"><div class="ob-chead"><span class="t">运行环境部署</span>${pill(pillText(d), toneOf(d))}</div>` +
      segBar(d) + `<div class="cl">${esc(cardLine(d))}</div>` +
      `<div class="more">阶段与维护记录${icon('ui2_chevron')}</div></div>`;
  }

  const STEP_NAMES = ['欢迎', '用途', '授权', '完成'];
  const sideWidth = env => Math.max(240, Math.round(env.screenW * 0.3));
  /** 正文可用宽（contentWidthDp）。 */
  const contentW = (env, max) => Math.min(max, Math.max(0, env.w600 ? env.screenW - sideWidth(env) - 1 - 56 : env.screenW - 40));

  /** 页脚按钮组 buttonBar：手机纵向铺满；宽屏横排靠右、主操作在最右、每个 >= 168dp、可换行。 */
  const bar = (env, btns) => `<div class="ob-bar${env.w600 ? ' wide' : ''}">${btns.filter(Boolean).join('')}</div>`;
  const pri = (t, dis) => `<button class="ob-btn pri"${dis ? ' disabled' : ''}>${esc(t)}</button>`;
  const sec = t => `<button class="ob-btn">${esc(t)}</button>`;
  // 文字按钮 40dp（OnboardingKit.ghost），低于 44dp，原生如此。
  const ghost = t => `<button class="ob-btn ghost" data-dense>${esc(t)}</button>`;

  /** OnboardingActivity.buildShell。 */
  function shell(env, step, d, content, footer) {
    const wide = env.w600, compact = env.short480;
    const pad = wide ? 28 : 20;
    const max = step === 2 ? 980 : 680;
    let left;
    if (step > 0) left = `<button class="ob-back" aria-label="返回上一步">${icon('ui_back')}</button>`;
    else if (wide) left = `<span style="width:48px;height:48px"></span>`;
    else left = `<span class="ob-brand">DSHA</span>`;
    const head = `<div class="ob-head" style="min-height:${compact ? 40 : 52}px;padding:${compact ? 2 : 4}px ${wide ? 22 : 16}px 2px ${wide ? 12 : 8}px">` +
      `<div class="ob-left" style="min-width:${wide ? 48 : 64}px">${left}</div>` +
      `<div class="ob-mid">${wide ? '' : capsule(env, d)}</div>` +
      `<div class="ob-counter" aria-label="第 ${step + 1} 步，共 4 步">${step + 1} / 4</div></div>`;
    const steps = wide ? '' : `<div class="ob-steps" aria-hidden="true">${STEP_NAMES.map((_, i) => `<i class="${i <= step ? 'on' : ''}"></i>`).join('')}</div>`;
    const body = `<div class="scroll"><div style="padding:${compact ? 2 : wide ? 8 : 10}px ${pad}px ${wide ? 24 : 22}px">` +
      `<div class="ob-cap" style="max-width:${max}px">${content}</div></div></div>`;
    const foot = `<div class="ob-foot" style="padding:${compact ? 7 : wide ? 12 : 10}px ${pad}px ${compact ? 7 : wide ? 14 : 12}px">` +
      `<div class="ob-cap" style="max-width:${max}px">${footer}</div></div>`;
    const main = `<div class="ob-main">${head}${steps}${body}${foot}</div>`;
    if (!wide) return main;
    const sg = compact ? 12 : 18;
    const rows = STEP_NAMES.map((n, i) => {
      const cls = i < step ? 'done' : i === step ? 'cur' : '';
      return `<div class="ob-srow ${cls}"><span class="mk">${i < step ? icon('onb_check') : i + 1}</span><span class="lb">${n}</span></div>`;
    }).join('');
    const side = `<aside class="ob-side" style="width:${sideWidth(env)}px"><div class="in" style="padding-top:${compact ? 14 : 22}px">` +
      `<div class="nm">DSHA</div><div class="ct">首次引导 · ${step + 1} / 4</div>` +
      `<div style="margin-top:${sg}px">${rows}</div>` + deployCard(d, sg) +
      `<div class="sp"></div><div class="ob-fine" style="margin-top:${sg}px">授权全部可选，可随时在 设置 → 设备能力授权 中修改。</div></div></aside>`;
    return `<div class="ob-root">${side}<div class="ob-rule"></div>${main}</div>`;
  }

  const eyebrow = t => `<div class="ob-eb">${esc(t)}</div>`;
  const heading = (env, t, h1) => `<h1 class="ob-h" style="font-size:${h1 ? (env.short480 ? 24 : 29) : 24}px">${esc(t)}</h1>`;
  const lead = t => `<p class="ob-lead">${esc(t)}</p>`;

  /** OnboardingKit.Grid：宽 >= min×2+gap 两列，奇数个时最后一项占满整行。 */
  function grid(items, width, minCol, gp) {
    const cols = width >= minCol * 2 + gp ? 2 : 1;
    return `<div class="ob-grid" style="grid-template-columns:repeat(${cols},minmax(0,1fr));gap:${gp}px">` +
      items.map((it, i) => cols === 2 && i === items.length - 1 && items.length % 2 ? it.replace('class="', 'class="full ') : it).join('') + `</div>`;
  }

  // ---------------------------------------------------------------- 第 1 步：欢迎
  DSHA.page({
    id: 'onboarding-welcome', group: '引导与授权', title: '引导 1 · 欢迎', shell: 'bare',
    source: 'OnboardingActivity.buildWelcome · features / checkGrid / checkSummary / dataRow · DeployCapsule · DeployCard',
    states: [{ id: 'fresh', title: '首次 · 未部署' }, { id: 'lowspace', title: '可用空间不足' }, { id: 'deploying', title: '已开始部署（返回第 1 步）' }],
    render(env) {
      const low = env.state === 'lowspace', dep = env.state === 'deploying';
      const d = dep ? D.run(2) : D.idle;
      const wide = env.w600;
      const cw = contentW(env, 680);
      const featCols = cw >= 620;
      const feats = [['onb_chat', '对话与任务', '让模型理解问题，协作完成工作。'], ['terminal', '完整的终端', '保留熟悉的 Ubuntu 命令与多会话。'],
        ['onb_grid', '插件与设备能力', '按需安装插件，连接手机能力。']];
      const featHtml = `<div class="ob-feats${featCols ? ' cols' : ''}">` + feats.map(([ic, t, s]) =>
        `<div class="ob-feat">${ibox(ic, 38, 19)}<div class="w"><div class="ob-t15">${t}</div><div class="ob-s125">${s}</div></div></div>`).join('') + `</div>`;
      const free = low ? '1.2 GB' : '18.4 GB';
      const tiles = [['设备架构', 'arm64-v8a'], ['系统与版本', 'Android 14'], ['可用空间', free, low], ['安装方式', '随包离线部署'],
        ['网页内核', '系统 WebView 130.0.6723.102'], ['支持结论', '可用']]
        .map(([k, v, bad]) => `<div class="ob-tile${bad ? ' bad' : ''}"><div class="k">${k}</div><div class="v">${esc(v)}</div></div>`);
      // 手机：一行摘要（checkSummary），点开为对话框；只有 blocked 才用红底，空间不足只改副标题。
      const checks = wide ? grid(tiles, cw, 120, 8)
        : `<div class="ob-link" data-touch role="button"><div class="w"><div class="ttl">可用 · Android 14 · 系统 WebView 130.0.6723.102</div>` +
          `<div class="ob-s125">${low ? '可用空间不足，点此查看详情' : '点此查看架构、空间与网页内核'}</div></div>${icon('ui2_chevron', 'chev')}</div>`;
      let c = eyebrow('DeepSeek Harness · 安卓') + gap(env.short480 ? 6 : 12, heading(env, '你的 AI 工作台，\n就在手机里。', true)) +
        gap(10, lead('写代码、处理文件、使用工具。\n把完整的工作环境，随身带走。')) +
        `<div class="ob-badge" style="margin-top:14px">离线部署  ·  无需 ROOT  ·  0.2.1-rc.5</div>` +
        gap(wide ? 22 : 14, featHtml) + gap(wide ? 22 : 16, `<div class="ob-sec">部署前检查</div>`) + gap(8, checks);
      if (low) c += gap(12, hint('可用空间不足：部署约需 2.9 GB，当前可用 1.2 GB。清理空间后点「重新检查」。', 'bad'));
      else if (wide) c += gap(14, hint('内置 Ubuntu 与运行组件将部署到应用私有目录。部署在后台进行，期间可以继续完成引导。'));
      if (wide) c += gap(14, `<div class="ob-link" data-touch role="button"><span class="slot">${icon('ui_shield')}</span>` +
        `<div class="w" style="margin:0 8px 0 12px"><div class="ttl">权限由你决定，数据有处可寻</div><div class="ob-s125">开始使用不需要授予设备控制权限。</div></div>${icon('ui2_chevron', 'chev')}</div>`) +
        gap(14, `<div class="ob-fine">使用在线模型时，消息及你选择的附件会发送到所配置的模型服务。</div>`);
      const primary = low ? pri('重新检查') : dep ? pri('继续') : pri('开始部署');
      return shell(env, 0, d, c, bar(env, [primary, ghost('复制给支持')]));
    },
  });

  // ---------------------------------------------------------------- 第 2 步：用途
  const GOALS = [
    ['base', '对话、写代码、用终端', '无需任何设备授权', 'onb_chat'],
    ['files', '读写手机里的文件', '推荐：存储读写', 'onb_folder'],
    ['screen', '让 AI 操作手机屏幕', '推荐：无障碍 · 悬浮窗 · 通知', 'onb_hand'],
    ['shell', '执行设备命令', '推荐：Stellar（Shizuku 兼容层）', 'terminal'],
    ['bg', '长时间在后台运行', '推荐：电池优化 · 通知', 'onb_battery'],
  ];
  DSHA.page({
    id: 'onboarding-goals', group: '引导与授权', title: '引导 2 · 用途', shell: 'bare',
    source: 'OnboardingActivity.buildGoals / goal / renderGoalsFooter · PermissionGoals · OnboardingRoles.checkbox',
    states: [{ id: 'base', title: '仅基础用途' }, { id: 'picked', title: '已选文件与屏幕' }],
    render(env) {
      const picked = env.state === 'picked';
      const sel = picked ? ['base', 'files', 'screen'] : ['base'];
      const d = D.run(2);
      const rows = GOALS.map(([id, t, s, ic]) => {
        const on = sel.includes(id), lock = id === 'base';
        return `<div class="ob-goal${on ? ' on' : ''}${lock ? ' lock' : ''}" data-touch role="checkbox" aria-checked="${on}">` +
          `<span class="bx">${on ? icon('onb_check') : ''}</span><div class="w"><div class="ob-t15">${t}</div><div class="s">${s}</div></div>${ibox(ic, 34, 18)}</div>`;
      });
      const c = eyebrow('第 2 步 · 用途') + gap(10, heading(env, '你想用 DSHA 做什么？')) +
        gap(8, lead('可多选。只用来推荐下一步的授权，不会自动申请任何权限。')) +
        gap(18, grid(rows, contentW(env, 680), 290, 10)) +
        gap(14, hint(env.w600 ? '运行环境正在后台部署，左侧可查看进度。' : '运行环境正在后台部署，点顶部胶囊可查看进度。'));
      // files → 存储；screen → 无障碍 / 悬浮窗 / 通知：共 4 项推荐
      const f = picked ? bar(env, [pri('下一步：开启 4 项推荐授权'), sec('跳过授权，稍后在设置中开启')])
        : bar(env, [pri('无需授权，下一步'), sec('仍然查看可选授权')]);
      return shell(env, 1, d, c, f);
    },
  });

  // ---------------------------------------------------------------- 第 3 步：授权
  /** PermissionCard.build：一行 + 可选详情。 */
  function permRow(m, expanded) {
    const sub = m.ok ? '' : m.alert || (m.unlocks && m.unlocks.length ? m.unlocks.join(' · ') : m.pill || '');
    // 行内主操作 34dp（PermissionCard.compact），低于 44dp，原生如此。
    const trailing = !m.ok && m.inline ? `<button class="ob-btn pri inline" data-dense aria-label="${esc(m.inlineDesc || m.inline)}">${esc(m.inline)}</button>`
      : m.pill ? pill(m.pill, m.tone) : '';
    let html = `<div class="ob-prow" data-touch role="button" aria-expanded="${!!expanded}">${ibox(m.icon, 32, 17, m.ok)}` +
      `<div class="w"><div class="tt">${esc(m.title)}</div>${sub ? `<div class="psub${m.alert && !m.ok ? ' warn' : ''}${expanded ? ' ml' : ''}">${esc(sub)}</div>` : ''}</div>` +
      trailing + `<span class="chev${expanded ? ' open' : ''}">${icon('ui2_chevron')}</span></div>`;
    if (expanded) {
      html += `<div class="ob-pdet">${m.why ? `<div>${esc(m.why)}</div>` : ''}` +
        (m.ok && m.unlocks ? `<div class="tags">${m.unlocks.map(u => `<span>${esc(u)}</span>`).join('')}</div>` : '') +
        (!m.ok && m.outside ? `<div class="out">${icon('onb_external')}<span>${esc(m.outside)}</span></div>` : '') +
        (m.actions && m.actions.length ? `<div class="ob-acts" style="margin-top:6px">${m.actions.map(a => `<button class="ob-btn ${a.ghost ? 'ghost' : 'sm'}" data-dense>${esc(a.t)}</button>`).join('')}</div>` : '') +
        `</div>`;
    }
    return html;
  }
  const plist = rows => `<div class="ob-plist">${rows.join('<div class="dv"></div>')}</div>`;
  const PM = {
    notifyOff: { icon: 'onb_bell', title: '通知', unlocks: ['部署进度', '任务停止按钮'], pill: '未开启', inline: '允许', inlineDesc: '允许通知' },
    notifyOn: { icon: 'onb_bell', title: '通知', ok: true, pill: '已开启', tone: 'good', unlocks: ['部署进度', '任务停止按钮'] },
    // 无障碍未开启：主操作已在行内，展开区去掉重复的 actions[0]，只剩说明与“离开 DSHA”提示。
    a11yOff: { icon: 'onb_hand', title: '无障碍', unlocks: ['读屏与点按'], pill: '未开启', inline: '去开启', inlineDesc: '去开启屏幕操作',
      why: '让助手读屏、点按和输入，用于手机任务。',
      outside: '在无障碍设置中开启「DSH-A-Su 屏幕控制」。提示“受限设置”时，先在应用详情右上角菜单中允许。' },
    overlayOff: { icon: 'onb_layers', title: '悬浮窗', unlocks: ['悬浮条与状态窗'], pill: '未允许', inline: '去允许', inlineDesc: '去允许悬浮窗' },
    batteryOff: { icon: 'onb_battery', title: '电池优化', unlocks: ['后台更稳'], pill: '后台受限', tone: 'warn', inline: '去设置', inlineDesc: '打开耗电管理',
      alert: '后台受限，可能被系统结束' },
    storageOff: { icon: 'onb_folder', title: '存储读写', unlocks: ['读写 /sdcard'], pill: '未开启', inline: '去开启', inlineDesc: '去开启所有文件访问' },
    channelOff: { icon: 'terminal', title: '设备命令通道', pill: '未安装 Stellar', inline: '安装 Stellar' },
  };
  DSHA.page({
    id: 'onboarding-perms', group: '引导与授权', title: '引导 3 · 授权', shell: 'bare',
    source: 'OnboardingActivity.buildPerms · PermissionPanel · PermissionCard · PermissionCards · PermissionChannelCard',
    states: [{ id: 'recommend', title: '推荐 0 / 3（用途：屏幕）' }, { id: 'expanded', title: '已开 1 项 · 展开无障碍与更多' }],
    render(env) {
      const ex = env.state === 'expanded';
      const d = D.run(4);
      // 用途 base + screen → 推荐 A11Y / OVERLAY / NOTIFY，按 CARDS 顺序 notify、a11y、overlay
      const rec = [permRow(ex ? PM.notifyOn : PM.notifyOff), permRow(PM.a11yOff, ex), permRow(PM.overlayOff)];
      const done = ex ? 1 : 0;
      let panel = `<div class="ob-sum"><div class="lb">推荐 ${done} / 3</div><div class="mt"><i style="width:${done / 3 * 100}%"></i></div></div>` +
        gap(14, plist(rec));
      panel += `<div style="margin-top:6px;display:flex">${ghost(ex ? '收起' : '更多授权 · 3 项')}</div>`;
      if (ex) panel += gap(2, plist([permRow(PM.batteryOff), permRow(PM.storageOff), permRow(PM.channelOff)]));
      const c = eyebrow('第 3 步 · 授权') + gap(10, heading(env, '按需授权')) +
        `<div style="margin-top:8px;max-width:680px">${lead('全部可选，之后可在设置中修改。')}</div>` + gap(16, panel);
      return shell(env, 2, d, c, bar(env, [pri(`下一步 · 已开启 ${done} / 3`)]));
    },
  });

  // ---------------------------------------------------------------- 第 4 步：完成
  function stageList(d) {
    return `<div class="ob-stl">` + STAGES.map((t, i) => {
      const s = stepOf(d, i);
      const cls = s === 'done' ? (d.phase === 'ok' ? 'done good' : 'done') : s;
      const mk = s === 'done' ? icon('onb_check') : s === 'bad' ? icon('onb_x') : '';
      return `<div class="ob-st ${cls}"><span class="mk">${mk}</span><span class="lb">${esc(t)}</span></div>`;
    }).join('') + `</div>`;
  }
  const SUMMARY = ['存储读写', 'Shizuku / Stellar', '无障碍', '通知', '悬浮窗', '电池优化'];
  function grantsCard(on, missing) {
    const n = on.length;
    return `<div class="ob-card"><div class="ob-chead"><span class="t">授权</span>${pill(`已开启 ${n} / 6`, n ? 'good' : '')}</div>` +
      `<div class="ob-chips">${SUMMARY.map(s => on.includes(s) ? pill('✓ ' + s, 'good') : pill('– ' + s)).join('')}</div>` +
      (missing.length ? gap(10, hint(`按你选的用途，还建议开启：${missing.join('、')}。不开也能先进入主界面。`)) : '') +
      `<div class="ob-gnote"><div class="ob-fine">授权可随时在 设置 → 设备能力授权 中修改或撤销。</div>${ghost('返回授权')}</div></div>`;
  }
  DSHA.page({
    id: 'onboarding-finish', group: '引导与授权', title: '引导 4 · 完成', shell: 'bare',
    source: 'OnboardingActivity.buildFinish / runtimeCard / grantsCard / failureText · OnboardingKit.segmentBar / stageList · DeployCard.pillText',
    states: [{ id: 'running', title: '部署进行中' }, { id: 'ready', title: '准备就绪' }, { id: 'failed', title: '部署未完成' }],
    render(env) {
      const st = env.state;
      const d = st === 'ready' ? D.ok : st === 'failed' ? D.fail(1) : D.run(5);
      const limited = '进入受限主界面 · 查看日志与配置';
      const title = st === 'ready' ? '准备就绪' : st === 'failed' ? '部署未完成' : '马上就好';
      const ld = st === 'ready' ? '运行环境已部署并通过检查。进入主界面后会自动启动 Web。'
        : st === 'failed' ? '部署没有完成，本页不会自动覆盖环境。请按下方原因处理后重试。'
          : '运行环境还在部署，完成后即可进入主界面并自动启动 Web。也可以先进入受限主界面查看配置和日志。';
      let rc = `<div class="ob-card"><div class="ob-chead"><span class="t">运行环境</span>${pill(pillText(d), toneOf(d))}</div>` + segBar(d) + stageList(d);
      if (st === 'failed') rc += gap(12, `<div class="ob-msg">未完成的操作：运行环境部署\n最后记录阶段：解压 Ubuntu 与 Node\n\n${FAIL_REASON}</div>`);
      rc += `</div>`;
      const on = st === 'ready' ? ['无障碍', '通知'] : st === 'failed' ? ['通知'] : [];
      // 用途 base + screen：推荐顺序 无障碍、悬浮窗、通知
      const missing = ['无障碍', '悬浮窗', '通知'].filter(x => !on.includes(x));
      const c = eyebrow('第 4 步 · 完成') + gap(10, heading(env, title)) + gap(8, lead(ld)) + gap(18, rc) + gap(12, grantsCard(on, missing));
      const f = st === 'ready' ? bar(env, [pri('进入主界面')])
        : st === 'failed' ? bar(env, [pri('重试部署'), sec('查看本次维护记录'), ghost('启动应急 DSH'), ghost(limited)])
          : bar(env, [pri('等待部署完成…', true), sec(limited)]);
      return shell(env, 3, d, c, f);
    },
  });

  // ---------------------------------------------------------------- 开机加载页
  DSHA.page({
    id: 'onboarding-boot', group: '引导与授权', title: '开机加载页', shell: 'bare',
    source: 'BootScreen · BootLogoView · BootSpinnerView · MainActivity（正在检查运行环境…）· DirectWebLaunch',
    states: [{ id: 'checking', title: '检查运行环境' }, { id: 'stuck', title: '8 秒后出现诊断' }, { id: 'direct', title: '直启 Web（可回主界面）' }],
    render(env) {
      const t = env.tablet;
      const logo = t ? 112 : 88;
      // GradientDrawable 径向半径 = 0.5 × min(宽, 高)；预览按整窗 dp 计。直启 Web 实际用网页背景色推导的调色板，这里沿用 shell 色（推断）。
      const r = Math.round(0.5 * Math.min(env.deviceW, env.deviceH));
      const text = env.state === 'direct' ? '正在进入 Web…' : '正在检查运行环境…';
      const showDiag = env.state === 'stuck', showMain = env.state === 'direct';
      const acts = showDiag || showMain
        ? `<div class="bt-acts" style="${t ? 'width:480px;left:calc(50% - 240px)' : 'left:0;right:0'};font-size:${t ? 14 : 13}px">` +
          (showDiag ? `<button>查看诊断</button>` : '') + (showMain ? `<button>进入主界面</button>` : '') + `</div>` : '';
      const g = t ? 20 : 16, spin = t ? 26 : 20;
      return `<div class="bt" role="status">
        <div class="bt-glow" style="background:radial-gradient(circle at 50% 50%, var(--accent) 0, transparent ${r}px)"></div>
        <div class="bt-card">
          <div class="bt-logo" style="width:${logo}px;height:${logo}px;margin-bottom:${t ? 12 : 8}px"><svg viewBox="0 0 100 100" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"><path d="M30 25L65 50L30 75"/><path d="M55 75H80"/></svg></div>
          <div class="bt-word" style="font-size:${t ? 20 : 16}px">HARNESS</div>
          <div class="bt-spin" style="margin-top:${g}px;width:${spin}px;height:${spin}px;border-width:${t ? 2.5 : 2}px"></div>
          <div class="bt-hint" style="margin-top:${g}px;font-size:${t ? 14 : 12}px;max-width:${t ? 420 : 280}px">${text}</div>
        </div>${acts}</div>`;
    },
  });

  // ---------------------------------------------------------------- 屏幕操作设置
  DSHA.page({
    id: 'onboarding-a11y', group: '引导与授权', title: '屏幕操作设置', shell: 'sub',
    source: 'AccessibilitySetupActivity · CardPage（kv / text / button / entry）· UiNavigation.addHeader',
    states: [{ id: 'off', title: '尚未开启' }, { id: 'connected', title: '已连接' }],
    render(env) {
      const on = env.state === 'connected';
      // CardPage 内容不限宽：平板横屏卡片会拉满（原生如此）。返回行只设无障碍窗格标题，不显示文字。
      return `<div class="scroll"><div class="pg">${h.title('让助手操作屏幕', '开启一次，之后直接在对话里说出要做的事。')}
        <div class="card">${h.kv('第 1 步', '开启系统服务')}
          <div class="ax-txt">点下方按钮 → 已下载的应用 / 服务 →「DSH-A-Su 屏幕控制」→ 开启「使用服务」，确认系统提示。</div>
          <button class="btn pri block ax-btn">${on ? '查看或关闭系统授权' : '去开启屏幕操作'}</button></div>
        <div class="card">${h.kv('第 2 步', '返回这里检查')}
          <div class="ax-st">${on ? '已连接，可以在对话中使用 Computer Use。' : '尚未开启，请完成第 1 步。'}</div>
          <button class="btn block ax-btn">检查连接</button></div>
        <div class="card"><div class="ax-help">怎么使用</div>
          <div class="ax-txt" style="white-space:pre-line">在 DSH 对话中发送：
“请打开系统设置，查看当前的显示设置。”

首次操作按应用提示确认。你可以随时在系统无障碍设置中关闭服务。读屏与点按无需 Root、Shizuku 或 ADB；如果已完成 ADB 配对，后台保活会在部分系统掉线后请求重新绑定。截屏需要 Android 11+。</div>
          <div class="divider"></div>
          ${h.entry({ icon: 'recovery_document', title: '找不到开关或开关是灰色', sub: '查看系统设置提示' })}</div>
      </div></div>`;
    },
  });

  // ---------------------------------------------------------------- ADB 无线配对
  DSHA.page({
    id: 'onboarding-adb', group: '引导与授权', title: 'ADB 无线配对', shell: 'bare',
    source: 'activity_adb_pair.xml · AdbPairActivity（buildUi / 状态观察 / 查看连接详情）',
    states: [{ id: 'idle', title: '等待配对码' }, { id: 'busy', title: '配对进行中' }],
    render(env) {
      const busy = env.state === 'busy';
      const dis = busy ? ' disabled' : '';
      const status = busy ? '正在完成一次配对握手并验证连接，最多约 2 分钟；随后检查自动恢复授权…' : '局域网权限已允许，请输入本次配对码后开始配对';
      // 布局无最大宽度：平板横屏整页拉满（原生如此）。“查看连接详情 ›”为 40dp 文字行（AdbPairActivity 代码添加），低于 44dp。
      // bg_polished_action 按钮的字重 / 内边距取 Widget.DSHA.Button（推断：主题 buttonStyle 映射到 AppCompat Button）。
      return `<div class="ad-head"><button class="ad-back" aria-label="返回上一页">${icon('ui_back')}</button></div>
        <div class="scroll"><div class="ad-pg">
          <h1 class="ad-title">ADB 无线配对</h1>
          <p class="ad-desc">在系统无线调试中取得配对码，再连接当前设备。</p>
          <div class="ad-card"><div class="ad-ct">连接状态</div><div class="ad-st" role="status">${esc(status)}</div>
            <button class="ad-detail" data-dense>查看连接详情 ›</button></div>
          <button class="ad-act">打开无线调试设置</button>
          <div class="ad-card" style="margin-top:12px">
            <div class="ad-lbl">设备地址（留空自动发现）</div><div class="ad-in${busy ? ' off' : ''}" data-touch></div>
            <div class="ad-lbl">配对端口（留空自动发现）</div><div class="ad-in${busy ? ' off' : ''}" data-touch>${busy ? '37135' : ''}</div>
            <div class="ad-lbl">配对码（6 位数字）</div><div class="ad-code" data-touch>${busy ? '604218' : ''}</div>
            <button class="ad-start"${dis}>${busy ? '正在处理，请等待结果…' : '开始配对'}</button></div>
          <div class="ad-card">
            <button class="ad-act"${dis}>自动读取本次配对码</button>
            <button class="ad-act"${dis}>手动地址与端口</button>
            <button class="ad-act"${dis}>验证已有连接</button>
            <button class="ad-plain">断开 ADB 连接</button>
            <button class="ad-text">配对与端口说明</button></div>
        </div></div>`;
    },
  });
})();
