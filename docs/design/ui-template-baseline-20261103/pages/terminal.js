/*
 * 终端 · 全屏终端工作台（TerminalActivity）。
 * 入口：MainActivity 底栏 / 左栏的 nav_terminal 是「导航动作」——监听器调用 openTerminal() 拉起
 *       TerminalActivity 并 return false，不切根页、不替换 fragment_container（旧的已保存
 *       TerminalFragment / PtyTerminalFragment 只在恢复时被移除并转交给该 Activity）。所以这里不画
 *       shell:'main' 的终端标签页，而是整屏自绘（shell:'bare'）。
 * 来源：ui/TerminalActivity.java（buildWorkspace / buildSessionsPanel / applyPresentation /
 *       applyCompactChrome / addSessionRow / applySystemBars）、util/TerminalWorkspacePolicy.java、
 *       ui/PtyTerminalFragment.java（buildExtraKeys / styleExtraKey / showTerminalActions）、
 *       util/TerminalExtraKeys.java（defaults / label / style）、ui/TerminalFloatingMenu.java、
 *       ui/TerminalSessionViews.java（dot）、ui/ExtraKeysDialog.java、ui/TerminalActionsDialog.java、
 *       ui/CardSheet.java、ui/CardPage.java、layout/fragment_pty_terminal.xml（终端画布 + pty_keys）、
 *       drawable/bg_terminal_*.xml、values{,-night}/colors.xml terminal_*、theme_palettes.xml
 *       pal_standard_terminal_*、values/dimens.xml terminal_sidebar_*。
 *       fragment_terminal.xml（简易终端）与 view_terminal_tabs.xml / TerminalTabBar 在生产中隐藏
 *       （"仅保留旧夹具ID"），会话切换全部在侧栏，本预览不画旧标签条。
 * 布局判据（全部来自 TerminalWorkspacePolicy，宽高 = 根视图扣系统栏 = env.screenW / env.screenH）：
 *   - 内嵌侧栏 canInline：screenW >= 840（env.w840）且 screenW - inlineWidth >= 480；
 *     inlineWidth = clamp(round(screenW × .25), 240, 296)。否则为抽屉。
 *   - 抽屉上限 drawerMaxWidth：手机类（env.phone，sw<600）且 screenW<600 → 224，否则 336；
 *     drawerWidth = min(上限, screenW - min(32, screenW/4))。
 *   - 侧栏自身判据（按侧栏宽 / 高）：narrowActions 宽<240；stackSessionDetails 宽<320（收起类型图标块）；
 *     compactHeight 高<560、denseFooter 高<480；shortLandscape 宽>高且高<1000；
 *     footerColumns = clamp(floor((宽-16)/102), 1, 紧凑?3:2)。
 *   - 附加键 TerminalExtraKeys.profileFor：env.tablet → 8+8，否则 7+7；每行等分，48dp 高。
 *   - CardSheet：screenW >= 600（env.w600）居中限宽 540，否则贴底并抬高 74dp（shell_nav_height 64 + 10）。
 * 两种模式（「显示设置 → 切换工作台/极简模式」，偏好 terminal_workbench 默认 false）：
 *   - 极简（默认）：无工具栏，黑色画布 + 可拖动 >_ 菜单球；侧栏只在打开时出现。
 *   - 工作台：面板色工具栏（返回 / 标题 / 设置 / 说明 / DSH CLI / 菜单）；能内嵌时侧栏常驻。
 * 推断（源码未直接给出，预览近似）：终端输出示例文本与 ANSI 配色（Termux 默认调色板近似）、
 *   行高；对话框遮罩颜色（系统 dim，借用 kit .scrim 色）；Material Chip / RadioButton 的外观；
 *   贴底弹层全宽（setLayout(-1,-2)）；菜单球按 2.5s 后变淡的静止态（alpha .45）绘制。
 * 原生现状（预览保持一致，自检会报）：
 *   - 侧栏在 stackSessionDetails（宽<320：手机 224 抽屉、平板横屏 296 内嵌）时会话行主体只剩
 *     6+6dp 内边距 + 单行名称 ≈32dp 高，选择会话的触控区低于 44dp（行本身因 48dp 图标钮撑到 48/50dp，
 *     但点击区是 body）。这里加 data-dense 保留原尺寸。
 *   - CardSheet 在终端里也按 MainActivity 胶囊抬高 74dp，底部露出一条被遮罩的终端。
 */
(function () {
  const { h } = DSHA;
  const P = '.dev[data-page="terminal"],.dev[data-page="terminal-workbench"]';
  const sel = s => P.split(',').map(p => p + ' ' + s).join(',');
  const css = `
  ${P}{--tm-panel:#FFFFFF;--tm-card:#F7F9FD;--tm-raised:#EAEFF7;--tm-tile:#F1F4F9;
    --tm-text:#152033;--tm-strong:#0D1727;--tm-muted:#59657A;--tm-faint:#7A879C;--tm-line:#DFE6F0;--tm-line2:#C6D2E4;
    --tm-acc:#1E50D0;--tm-acc-bg:#E8EFFF;--tm-acc-soft2:#DBE7FF;--tm-acc-line:#B9CCF4;--tm-on-acc:#FFFFFF;--tm-chipbg:rgba(255,255,255,.18);
    --tm-run:#2FA36B;--tm-idle:#1E50D0;--tm-pend:#C98A12;--tm-danger:#A93B43;--tm-warn:#7A5300;--tm-scrim:rgba(9,16,30,.55)}
  ${P.split(',').map(p => p + '[data-theme="dark"]').join(',')}{--tm-panel:#0B0F16;--tm-card:#10151E;--tm-raised:#151C27;--tm-tile:#141B25;
    --tm-text:#E4E8F0;--tm-strong:#F2F5FA;--tm-muted:#9DA8BB;--tm-faint:#7C8AA0;--tm-line:#1E2735;--tm-line2:#2E3B4F;
    --tm-acc:#8AB4FF;--tm-acc-bg:#13233C;--tm-acc-soft2:#1B3050;--tm-acc-line:#2C4C7C;--tm-on-acc:#081428;--tm-chipbg:rgba(8,20,40,.12);
    --tm-run:#5CC98F;--tm-idle:#8AB4FF;--tm-pend:#E5C07B;--tm-danger:#E08A8A;--tm-warn:#E5C07B;--tm-scrim:rgba(0,0,0,.70)}
  /* 系统栏：极简模式状态栏透明叠在黑底上、浅色图标；工作台为 terminal_panel；导航栏恒 terminal_surface。 */
  ${sel('.sb')}{background:var(--term-surface);color:#E4E8F0}
  .dev[data-page="terminal-workbench"] .sb{background:var(--tm-panel);color:var(--tm-text)}
  ${sel('.gb')}{background:var(--term-surface)}
  ${sel('.gb::after')}{background:#E4E8F0}
  ${sel('.app')}{background:var(--term-surface)}

  .tm{position:relative;flex:1;min-height:0;display:flex;flex-direction:column;background:var(--term-surface);overflow:hidden}
  .tm-bar{flex:none;display:flex;align-items:center;background:var(--tm-panel);border-bottom:1px solid var(--tm-line)}
  .tm-bar .tt{flex:1;min-width:0;padding:4px 8px;font-size:16px;font-weight:700;color:var(--tm-text);line-height:1.25;
    display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .tm-ib{flex:none;width:48px;height:48px;border:0;padding:12px;background:none;border-radius:0;display:grid;place-items:center;color:var(--tm-text);font-size:24px}
  .tm-ib .i{width:24px;height:24px}
  .tm-body{flex:1;min-height:0;display:flex}
  .tm-pane{flex:1;min-width:0;display:flex;flex-direction:column;direction:ltr}
  .tm-out{flex:1;min-height:0;overflow:hidden;padding:2px 2px 0;font-family:var(--mono);font-size:13px;line-height:1.22;
    color:var(--term-fg);white-space:pre-wrap;word-break:break-all}
  .tm-out .g{color:#5FD75F;font-weight:700}.tm-out .b{color:#6FA8FF;font-weight:700}.tm-out .r{color:#E06C75}.tm-out .y{color:#E5C07B}
  .tm-out .cur{display:inline-block;width:.6em;height:1.1em;vertical-align:-.2em;background:var(--term-fg)}
  /* pty_keys：托盘 1dp 顶线 + #0A0D13，上 4 下 2；每行 48dp、左右 2dp，键等分；键帽四边内缩 2dp、8dp 圆角。 */
  .tm-keys{flex:none;background:var(--term-tray);border-top:1px solid var(--term-tray-line);padding:3px 0 2px}
  .tm-krow{display:flex;height:48px;padding:0 2px}
  .tm-key{position:relative;flex:1 1 0;min-width:44px;height:48px;border:0;background:none;padding:0 4px 1px;color:var(--term-key-text);
    font:500 15px var(--sans);display:grid;place-items:center;white-space:nowrap;overflow:hidden}
  .tm-key::before{content:"";position:absolute;inset:2px;border-radius:8px;background:var(--term-key);box-shadow:inset 0 -1px 0 #04060A,inset 0 1px 0 #262E3A}
  .tm-key.fn::before,.tm-key.mod::before{background:var(--term-key-fn)}
  .tm-key>span{position:relative}
  .tm-key.mod{font-weight:700}
  .tm-key.mod::after{content:"";position:absolute;left:50%;bottom:8px;width:14px;height:3px;margin-left:-7px;border-radius:2px;background:#354152}
  .tm-key.arrow{font:400 18px var(--sans)}
  .tm-key.ctl{font:400 15px var(--mono);color:var(--term-key-ctrl)}
  .tm-key.sym{font:400 17px var(--mono);color:#C9D2DF}
  /* 菜单球：48dp 触控格内 40dp 面板色圆 + 强描边，>_ 16dp 等宽；静止 2.5s 后 alpha .45。 */
  .tm-ball{position:absolute;right:12px;width:48px;height:48px;border:0;padding:0;background:none;z-index:4;opacity:.45;display:grid;place-items:center}
  .tm-ball::before{content:"";position:absolute;inset:4px;border-radius:50%;background:var(--tm-panel);border:1px solid var(--tm-line2);box-shadow:0 3px 8px rgba(0,0,0,.35)}
  .tm-ball>span{position:relative;font:400 16px/1 var(--mono);color:var(--tm-text)}
  /* 会话侧栏 */
  .tm-drawer{position:absolute;inset:0;z-index:10}
  .tm-dscrim{position:absolute;inset:0;background:var(--tm-scrim)}
  .tm-drawer>.tm-panel{position:absolute;left:0;top:0;bottom:0;box-shadow:0 8px 24px rgba(0,0,0,.35)}
  .tm-panel{flex:none;display:flex;flex-direction:column;background:var(--tm-panel);color:var(--tm-text);min-height:0;direction:ltr}
  .tm-body>.tm-panel{border-right:1px solid var(--tm-line)}
  .tm-head{flex:none;display:flex;align-items:center}
  .tm-id{flex:none;width:40px;height:40px;margin-right:12px;border-radius:10px;background:var(--tm-acc-bg);border:1px solid var(--tm-acc-line);color:var(--tm-acc);display:grid;place-items:center}
  .tm-id .i{width:22px;height:22px}
  .tm-head .hh{flex:1;min-width:0;font-size:19px;font-weight:700;color:var(--tm-strong);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .tm-head .tm-ib{color:var(--tm-muted)}
  .tm-head .tm-ib.danger{color:var(--tm-danger)}
  .tm-create{flex:none;display:flex;align-items:center;min-height:54px;margin:0 14px 12px;padding:6px 16px 6px 11px;border:0;border-radius:13px;background:var(--accent);color:var(--on-accent);text-align:start}
  .tm-create .cl,.tm-create .cx{color:var(--on-accent)}  /* bg_btn_primary = primary + button_primary_text(dsha_pal_accent_on) */
  .tm-create .cx{flex:none;width:30px;height:30px;padding:6px;border-radius:9px;background:var(--tm-chipbg);display:grid;place-items:center}
  .tm-create .cx .i{width:18px;height:18px}
  .tm-create .cl{flex:1;min-width:0;margin-left:12px;font-size:15px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .tm-list{flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;scrollbar-width:none}
  .tm-row{position:relative;display:flex;align-items:center;min-height:50px;margin-bottom:4px;border-radius:12px}
  .tm-row.on{background:var(--tm-acc-bg)}
  .tm-row.on::before{content:"";position:absolute;left:0;top:14px;bottom:14px;width:3px;border-radius:2px;background:var(--tm-acc)}
  .tm-rb{flex:1;min-width:0;display:flex;align-items:center;padding:6px 4px 6px 10px;border-radius:11px}
  .tm-rb.st{padding-left:12px}
  .tm-glyph{flex:none;width:32px;height:32px;margin-right:11px;padding:7px;border-radius:8px;background:var(--tm-raised);color:var(--tm-muted);display:grid;place-items:center}
  .tm-row.on .tm-glyph{background:var(--surface);color:var(--tm-acc)}
  .tm-rb .nm{flex:0 1 auto;min-width:0;font-size:15px;font-weight:700;line-height:20px;color:var(--tm-text);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .tm-row.on .nm{color:var(--tm-acc)}
  .tm-sd{flex:none;width:7px;height:7px;margin:0 4px 0 8px;border-radius:50%}
  .tm-sd.run{background:var(--tm-run)}.tm-sd.idle{background:var(--tm-idle)}.tm-sd.pend{background:var(--tm-pend)}.tm-sd.exit{border:1px solid var(--tm-faint)}
  .tm-row .tm-ib{padding:14px;color:var(--tm-muted);position:relative}
  .tm-row .tm-ib .i{width:20px;height:20px;position:relative}
  .tm-row.on .tm-ib::before{content:"";position:absolute;inset:7px;border-radius:50%;background:var(--tm-acc-soft2)}
  .tm-foot{flex:none;border-top:1px solid var(--tm-line);background:var(--tm-panel)}
  .tm-frow{display:flex}
  .tm-fa{flex:1 1 0;min-width:48px;min-height:48px;margin:3px;border:0;border-radius:12px;background:var(--tm-tile);color:var(--tm-text);
    display:flex;align-items:center;gap:10px;padding:6px 8px 6px 12px;font-size:13.5px;font-weight:400;text-align:start;line-height:1.25}
  .tm-fa .i{width:19px;height:19px;color:var(--tm-acc)}
  .tm-fa .t{min-width:0;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .tm-fa.d{font-size:11px;gap:5px;padding:6px 5px 6px 8px}
  .tm-fa.n{height:48px;font-size:11px;gap:5px;padding:3px;margin:3px 0}
  .tm-fa.n+.tm-fa.n{margin-left:6px}
  .tm-fa.n .t{white-space:nowrap;text-overflow:ellipsis;display:block}
  /* CardSheet：手机贴底（抬高 74dp），w600 居中卡片限宽 540；正文滚动、页脚常驻。 */
  .tm-dlg{position:absolute;inset:0;z-index:30;background:rgba(9,16,30,.45);display:flex;align-items:flex-end;justify-content:center;padding-bottom:74px}
  .tm-dlg.wide{align-items:center;padding:0}
  .tm-sheet{width:100%;display:flex;flex-direction:column;overflow:hidden;background:var(--surface);color:var(--text);border:1px solid var(--line);border-bottom:0;border-radius:22px 22px 0 0}
  .tm-dlg.wide .tm-sheet{border-bottom:1px solid var(--line);border-radius:18px}
  .tm-sheet>.scroll{flex:1 1 auto}
  .tm-sc{padding:16px}
  .tm-sc>h3{margin:0;font-size:20px;font-weight:700;line-height:1.3}
  .tm-sc>.sub{margin:0;padding:8px 0 14px;font-size:12px;line-height:1.55;color:var(--text-2)}
  .tm-sf{flex:none;padding:8px 16px 12px;display:flex;flex-direction:column}
  .tm-sf>.btn,.tm-blk{margin-top:10px}
  .tm-cap{font-size:11px;color:var(--muted)}
  .tm-radios{display:flex}
  .tm-radio{flex:1;min-width:0;min-height:48px;display:flex;align-items:center;gap:12px;font-size:14px}
  .tm-radio>i{flex:none;width:20px;height:20px;border-radius:50%;border:2px solid var(--muted);position:relative}
  .tm-radio.on>i{border-color:var(--accent)}
  .tm-radio.on>i::after{content:"";position:absolute;inset:3px;border-radius:50%;background:var(--accent)}
  .tm-kcard{padding:14px 16px}
  .tm-chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px;direction:ltr}
  .tm-chips .chip{gap:4px;padding:0 8px 0 12px;font-size:13px;color:var(--text)}
  .tm-chips .chip .i{font-size:16px;color:var(--muted)}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  // ---------- 示例数据（脱敏；推断） ----------
  const SESSIONS = [
    { pty: true, name: 'PTY 1', dot: 'run', on: true, full: 'PTY · 终端 1' },
    { pty: true, name: 'DSH CLI', dot: 'run', full: 'PTY · DSH CLI' },
    { pty: false, name: '简易 1', dot: 'idle', full: '简易 · 终端 1' },
    { pty: true, name: 'PTY 2', dot: 'exit', full: 'PTY · 终端 2' },
  ];
  const OUT = [
    '<span class="g">root@localhost</span>:<span class="b">~</span># cd projects/demo',
    '<span class="g">root@localhost</span>:<span class="b">~/projects/demo</span># git status -sb',
    '## <span class="g">main</span>...<span class="r">origin/main</span>',
    ' <span class="r">M</span> src/app.ts',
    '<span class="r">??</span> notes.md',
    '<span class="g">root@localhost</span>:<span class="b">~/projects/demo</span># ls',
    'README.md  notes.md  package.json  <span class="b">src</span>',
    '<span class="g">root@localhost</span>:<span class="b">~/projects/demo</span># node -v',
    'v22.11.0',
    '<span class="g">root@localhost</span>:<span class="b">~/projects/demo</span># <span class="cur"></span>',
  ].join('\n');

  // ---------- TerminalExtraKeys ----------
  const LABEL = { ESC: 'Esc', TAB: 'Tab', CTRL: 'Ctrl', ALT: 'Alt', LEFT: '←', RIGHT: '→', UP: '↑', DOWN: '↓', HOME: 'Home', END: 'End',
    PGUP: 'PgUp', PGDN: 'PgDn', CC: '^C', CD: '^D', CZ: '^Z', PIPE: '|', SLASH: '/', DASH: '-' };
  const DESC = { ESC: '发送 Esc 键', TAB: '发送 Tab 键，用于补全或缩进', CTRL: 'Ctrl，一次性修饰键，未启用', ALT: 'Alt，一次性修饰键，未启用',
    LEFT: '方向键：光标左移', RIGHT: '方向键：光标右移', UP: '方向键：上移历史或光标行', DOWN: '方向键：下移历史或光标行',
    HOME: '跳到行首', END: '跳到行尾', PGUP: '向上翻页', PGDN: '向下翻页', CC: '控制字符 ^C：中断当前命令',
    CD: '控制字符 ^D：结束输入或退出会话', CZ: '控制字符 ^Z：挂起当前命令', PIPE: '输入管道符号 |',
    SLASH: '输入斜杠 /，用于路径分隔', DASH: '输入连字符 -，用于命令选项' };
  const KIND = a => (a === 'CTRL' || a === 'ALT') ? 'mod' : /^C[CDZL]$/.test(a) ? 'ctl'
    : ['ESC', 'TAB', 'HOME', 'END', 'PGUP', 'PGDN'].includes(a) ? 'fn' : ['LEFT', 'RIGHT', 'UP', 'DOWN'].includes(a) ? 'arrow' : 'sym';
  const keyRows = tablet => tablet
    ? [['ESC', 'TAB', 'CTRL', 'ALT', 'LEFT', 'DOWN', 'UP', 'RIGHT'], ['CC', 'CD', 'CZ', 'HOME', 'END', 'PIPE', 'SLASH', 'DASH']]
    : [['ESC', 'SLASH', 'DASH', 'HOME', 'UP', 'END', 'PGUP'], ['TAB', 'CTRL', 'ALT', 'LEFT', 'DOWN', 'RIGHT', 'PGDN']];
  const keys = env => `<div class="tm-keys" aria-label="终端快捷按键，长按可自定义">` + keyRows(env.tablet).map(r =>
    `<div class="tm-krow">${r.map(a => `<button class="tm-key ${KIND(a)}" aria-label="${h.esc(DESC[a])}"><span>${h.esc(LABEL[a])}</span></button>`).join('')}</div>`).join('') + `</div>`;

  // ---------- TerminalWorkspacePolicy ----------
  function geometry(env) {
    const W = env.screenW, H = env.screenH;
    const inlineW = Math.max(240, Math.min(296, Math.round(W * .25)));
    const canInline = env.w840 && W - inlineW >= 480;
    const drawerMax = env.phone && !env.w600 ? 224 : 336;
    const drawerW = Math.min(drawerMax, Math.max(1, W - Math.min(32, Math.floor(W / 4))));
    return { inlineW, canInline, drawerW, landscapeCompact: W > H && H < 1000 };
  }

  /** 会话侧栏：抽屉 / 内嵌复用同一份视图。width/height 为侧栏自身 dp。 */
  function panel(env, g, o) {
    const narrow = o.width < 240;
    const lc = g.landscapeCompact;
    const tight = o.height < 560 || lc;
    const dense = narrow || o.height < 480 || lc;
    const cols = Math.max(1, Math.min(dense ? 3 : 2, Math.floor(Math.max(0, o.width - 16) / 102)));
    const stack = o.width < 320;
    const head = `padding:${tight ? 8 : 14}px ${narrow ? 12 : 6}px ${tight ? 6 : 12}px ${narrow ? 12 : 16}px`;
    const fs = narrow ? 12 : (lc ? 8 : 12);
    const foot = `padding:${tight ? 6 : 10}px ${fs}px ${tight ? 6 : 12}px`;
    const list = `padding:${tight ? 0 : 2}px ${narrow ? 12 : 10}px ${tight ? 6 : 10}px`;
    const rows = SESSIONS.map(s => {
      const label = (s.on ? '当前会话 · ' : '') + s.full + ' · ' + (s.dot === 'exit' ? '已核验退出 · 历史保留' : s.pty ? '运行中' : '就绪');
      return `<div class="tm-row${s.on ? ' on' : ''}">` +
        // stack 时只剩单行名称：选择区约 32dp 高，原生如此（见文件头）。
        `<div class="tm-rb${stack ? ' st' : ''}" data-touch${stack ? ' data-dense' : ''} role="button" aria-label="${h.esc(label)}">` +
        (stack ? '' : `<span class="tm-glyph">${h.icon(s.pty ? 'terminal_pty' : 'terminal_simple')}</span>`) +
        `<span class="nm">${h.esc(s.name)}</span><i class="tm-sd ${s.dot}"></i></div>` +
        `<button class="tm-ib" aria-label="重命名 ${h.esc(s.full)}">${h.icon('terminal_rename')}</button>` +
        `<button class="tm-ib" aria-label="结束 ${h.esc(s.full)} 的进程">${h.icon('terminal_close')}</button></div>`;
    }).join('');
    const acts = dense
      ? [['键盘', 'terminal_keyboard'], ['粘贴', 'ui_copy'], ['聚焦输入', 'ui2_prompt'], ['显示', 'terminal_display'], ['主界面', 'terminal_home'], ['说明', 'terminal_help']]
      : [['显示键盘', 'terminal_keyboard'], ['粘贴文本', 'ui_copy'], ['回到输入', 'ui2_prompt'], ['显示设置', 'terminal_display'], ['返回主界面', 'terminal_home'], ['说明', 'terminal_help']];
    const cls = 'tm-fa' + (dense ? (narrow ? ' n' : ' d') : '');
    let grid = '';
    for (let i = 0; i < acts.length; i += cols)
      grid += `<div class="tm-frow">${acts.slice(i, i + cols).map(([t, ic]) => `<button class="${cls}">${h.icon(ic)}<span class="t">${t}</span></button>`).join('')}</div>`;
    return `<div class="tm-panel" style="width:${o.width}px" aria-label="会话侧栏">
      <div class="tm-head" style="${head}"><span class="tm-id">${h.icon('terminal')}</span><span class="hh">会话</span>
        <button class="tm-ib danger" aria-label="结束全部终端会话并核验">${h.icon('terminal_end_all')}</button>
        ${o.showClose ? `<button class="tm-ib" aria-label="收起会话侧栏">${h.icon('terminal_close')}</button>` : ''}</div>
      <button class="tm-create"><span class="cx">${h.icon('terminal_new')}</span><span class="cl">新建终端会话</span></button>
      <div class="tm-list" style="${list}">${rows}</div>
      <div class="tm-foot" style="${foot}">${grid}</div>
    </div>`;
  }

  // TerminalFloatingMenu.constrain：右吸边 12dp；y = 12 + (H - 48 - 12 - 预留(底部安全区 + 100) - 12) × .6。
  function ball(env) {
    const H = env.screenH + 20;  // 根视图高 - 顶部 padding（含手势条 20）
    const high = Math.max(12, H - 48 - 12 - (20 + 100));
    return `<button class="tm-ball" style="top:${Math.round(12 + (high - 12) * .6)}px" aria-label="打开会话侧栏"><span>&gt;_</span></button>`;
  }

  // 弹层几何统一走 kit popup（原样 = CardSheet 现状；?variant=proposal = README §9 自适应规则）。
  function extraKeysSheet(env) {
    const rows = keyRows(env.tablet);
    const body = `<div class="card"><div class="tm-cap">布局</div><div class="tm-radios">
        <div class="tm-radio${env.tablet ? '' : ' on'}" role="radio"><i></i>手机布局</div>
        <div class="tm-radio${env.tablet ? ' on' : ''}" role="radio"><i></i>平板布局</div></div></div>
      ${rows.map((r, i) => `<div class="card tm-kcard"><div class="tm-cap">${i ? '第二行' : '第一行'} · ${r.length}/10</div>
        <div class="tm-chips">${r.map(a => `<span class="chip">${h.esc(LABEL[a])}${h.icon('onb_x')}</span>`).join('')}</div>
        <button class="btn block tm-blk">+ 添加按键</button></div>`).join('')}
      <button class="btn block tm-blk">恢复当前布局默认</button>`;
    return DSHA.popup(env, { kind: 'sheet', size: 'l', title: '快捷按键', sub: '应用于 PTY 会话；手机与平板布局分别保存。长按或点按按键可操作。', body,
      actions: [{ t: '取消' }, { t: '保存', pri: true }] });
  }

  function actionsSheet(env) {
    const body = h.entries([
      { title: '粘贴文本…', icon: 'ui_copy' }, { title: '查看终端输出…', icon: 'terminal' },
      { title: '显示设置…', icon: 'terminal_display' }, { title: '快捷按键…', icon: 'terminal_keyboard' },
      { title: '会话管理', icon: 'terminal_menu' }]);
    return DSHA.popup(env, { kind: 'sheet', size: 'm', title: '终端操作', sub: 'PTY 1', body, actions: [{ t: '关闭' }] });
  }

  const pane = env => `<div class="tm-pane"><div class="tm-out">${OUT}</div>${keys(env)}</div>`;

  DSHA.page({
    id: 'terminal', group: '终端', title: '全屏终端 · 极简模式（默认）', shell: 'bare',
    source: 'nav_terminal → TerminalActivity · PtyTerminalFragment · TerminalWorkspacePolicy · TerminalExtraKeys · TerminalFloatingMenu · ExtraKeysDialog',
    states: [{ id: 'running', title: '会话运行中' }, { id: 'sessions', title: '会话侧栏 / 抽屉展开' }, { id: 'keys', title: '快捷按键弹层' }],
    render(env) {
      const g = geometry(env);
      const open = env.state === 'sessions';
      const inline = open && g.canInline;   // 极简内嵌：面板自带关闭钮，菜单球隐藏
      const inlinePanel = inline ? panel(env, g, { width: g.inlineW, height: env.screenH, showClose: true }) : '';
      const drawer = open && !inline
        ? `<div class="tm-drawer"><div class="tm-dscrim"></div>${panel(env, g, { width: g.drawerW, height: env.screenH, showClose: true })}</div>` : '';
      return `<div class="tm"><div class="tm-body">${inlinePanel}${pane(env)}</div>
        ${inline ? '' : ball(env)}${drawer}${env.state === 'keys' ? extraKeysSheet(env) : ''}</div>`;
    },
  });

  DSHA.page({
    id: 'terminal-workbench', group: '终端', title: '全屏终端 · 工作台模式', shell: 'bare',
    source: 'TerminalActivity（workbench=true）· TerminalWorkspacePolicy · TerminalActionsDialog · CardSheet',
    states: [{ id: 'running', title: '会话运行中（宽屏侧栏常驻）' }, { id: 'actions', title: '终端操作弹层' }],
    render(env) {
      const g = geometry(env);
      const expanded = g.canInline;           // 工作台：能内嵌就常驻，不可收起
      const bar = `<div class="tm-bar"><button class="tm-ib" aria-label="返回">${h.icon('ui_back')}</button><div class="tt">PTY 1</div>` +
        (expanded ? `<button class="tm-ib" aria-label="终端显示设置">${h.icon('settings')}</button><button class="tm-ib" aria-label="终端与会话说明">${h.icon('about')}</button>` : '') +
        `<button class="tm-ib" aria-label="打开 DSH CLI">${h.icon('terminal')}</button>` +
        (expanded ? '' : `<button class="tm-ib" aria-label="打开会话侧栏">${h.icon('terminal_menu')}</button>`) + `</div>`;
      const inlinePanel = expanded ? panel(env, g, { width: g.inlineW, height: env.screenH - 49, showClose: false }) : '';
      return `<div class="tm">${bar}<div class="tm-body">${inlinePanel}${pane(env)}</div>
        ${env.state === 'actions' ? actionsSheet(env) : ''}</div>`;
    },
  });
})();
