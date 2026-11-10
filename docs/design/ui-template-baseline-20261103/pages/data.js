/*
 * 数据与存储 · NativeData / WorkspaceFiles / Storage / StorageHelp / RetainedData /
 *            AutomaticBackup / ColdSetupSpeed / BrowserOriginRestore。
 * 来源：ui/CardPage.java（内页骨架：页边 20、顶 18、标题 26sp、说明 13sp、卡片 16/12 内边距 + 14 间距、
 *       footer 20/8/20/16 竖排整宽按钮、每个按钮上边距 10、主 52 / 次 46）、ui/UiNavigation.addHeader（返回行 52dp），
 *       以及各 Activity 的 Java 代码（文案为 UiText.choose / text 的第一个参数，逐字照抄）。
 * 布局判据：这 8 个页面都没有任何宽度 / 高度判据（无 layout-* 资源、无 screenWidthDp 分支、无最大内容宽），
 *   所以三个档位上都是同一单栏、内容随屏宽铺满；唯一与档位相关的是 shell_page_bottom（手机 90 / sw600 30，kit 写入）。
 * CardPage 的 footer 在 ScrollView 之外、无分隔线（不是 kit 的 .footer），这里用 .dt-foot 照画。
 * 示例数据均为脱敏样例：路径用默认工作目录 /root/deepseek-harness（Constants.DEFAULT_WORKDIR），ID 为随机十六进制。
 */
(function () {
  const { h } = DSHA;
  const { esc, icon } = h;
  const GROUP = '数据与存储';
  const css = `
  .dt-foot{flex:none;padding:8px 20px 16px}
  .dt-foot>.btn{margin-top:10px}
  .dt-mt10{margin-top:10px}
  .dt-text{white-space:pre-line;line-height:1.45}
  .dt-status{font-size:13px;color:var(--text-2);line-height:1.5;white-space:pre-line}
  .dt-s12{font-size:12px;color:var(--text-2);line-height:1.4;white-space:pre-line}
  .dt-s11{font-size:11px;color:var(--muted);line-height:1.4;padding-top:10px}
  /* 原生 RadioButton / CheckBox：32dp 按钮区 + 文字 */
  .dt-opt{display:flex;align-items:center;min-height:52px;padding:6px 0;gap:6px;font-size:14px;color:var(--text);line-height:1.35}
  .dt-opt.h48{min-height:48px;padding:0}.dt-opt.h44{min-height:44px;padding:0}
  .dt-opt .w{flex:1;min-width:0}
  .dt-opt .sub{display:block;font-size:12px;color:var(--muted)}
  .dt-opt.off{color:var(--muted);opacity:.6}
  .dt-rb,.dt-cb{flex:none;width:32px;height:32px;display:grid;place-items:center}
  .dt-rb i{width:20px;height:20px;border-radius:50%;border:2px solid var(--text-2);display:grid;place-items:center}
  .dt-rb.on i{border-color:var(--accent)}
  .dt-rb.on i::after{content:"";width:10px;height:10px;border-radius:50%;background:var(--accent)}
  .dt-cb i{width:18px;height:18px;border-radius:2px;border:2px solid var(--text-2);display:grid;place-items:center;color:var(--on-accent)}
  .dt-cb.on i{background:var(--accent);border-color:var(--accent)}
  .dt-cb i svg{width:14px;height:14px}
  /* NativeData 恢复向导步骤条 */
  .dt-steps{display:flex;gap:6px;margin-bottom:14px}
  .dt-steps>div{flex:1;min-width:0}
  .dt-steps .bar{height:4px;border-radius:16px;background:var(--raised)}
  .dt-steps .on .bar{background:var(--accent)}
  .dt-steps .t{padding-top:6px;font-size:12px;color:var(--muted);line-height:1.3}
  .dt-steps .cur .t{color:var(--accent);font-weight:700}
  .dt-sublab{font-size:12px;font-weight:700;color:var(--text-2);padding:6px 0 2px}
  /* StorageActivity 总量 + 分布条 */
  .dt-total{font-size:22px;font-weight:700;padding-top:6px;line-height:1.2}
  .dt-bar{display:flex;height:14px;margin:12px 0 6px;border-radius:16px;overflow:hidden;background:var(--raised)}
  .dt-bar>i{display:block;height:100%}
  .dt-kd{display:flex;align-items:center;gap:8px}
  .dt-kd>b{flex:none;width:10px;height:10px;border-radius:50%}
  /* StorageHelpActivity 问答卡 */
  .dt-qh{display:flex;align-items:center;min-height:48px;gap:8px}
  .dt-qh .t{flex:1;min-width:0;font-size:16px;font-weight:700;line-height:1.3}
  .dt-qh .i{font-size:20px;color:var(--muted)}
  .dt-qh.open .i{transform:rotate(180deg)}
  .dt-p{font-size:14px;color:var(--text-2);line-height:1.6;padding-top:10px}
  /* RetainedDataActivity */
  .dt-chips{display:flex;gap:8px;overflow-x:auto;scrollbar-width:none;margin-bottom:10px}
  .dt-chips::-webkit-scrollbar{display:none}
  .dt-chip{flex:none;min-height:40px;padding:0 14px;border-radius:999px;border:1px solid var(--line);background:var(--surface);
    font-size:13px;color:var(--text-2);display:grid;place-items:center;white-space:nowrap}
  .dt-chip.on{color:var(--accent);font-weight:700}
  .dt-rp{font-size:14px;color:var(--text-2);white-space:pre-line;line-height:1.45;overflow-wrap:anywhere}
  .dt-ps{font-size:14px;color:var(--muted);padding:4px 0 8px}
  .dt-rc{background:var(--surface);border:1px solid var(--line);border-radius:18px;padding:12px;margin-bottom:12px}
  .dt-rc .dt-opt{font-size:16px;min-height:48px;align-items:flex-start;padding:0;line-height:1.4}
  .dt-rc .dt-opt .dt-cb{margin-top:4px}
  .dt-rc .dt-opt .w{padding-top:8px}
  .dt-rc .btn{min-height:48px;margin-top:8px;font-weight:500}
  /* AutomaticBackupActivity 开关行 */
  .dt-swrow{display:flex;align-items:center;min-height:48px;font-size:14px;gap:12px}
  .dt-swrow .w{flex:1}
  /* ColdSetupSpeedActivity */
  .dt-fact{display:flex;align-items:flex-start;gap:10px;padding:7px 0;font-size:13px;color:var(--text-2);line-height:1.5}
  .dt-fact .i{font-size:16px;color:var(--accent);margin-top:2px}
  .dt-mono{font-family:var(--mono);font-size:13px;line-height:1.55;padding:4px 0;white-space:pre-wrap;overflow-wrap:anywhere}
  /* WorkspaceFilesActivity（非 CardPage 旧式页） */
  .dt-wf{flex:1;min-height:0;display:flex;flex-direction:column;padding:6px 12px 12px;background:var(--bg)}
  .dt-wf .hd{flex:none;display:flex;align-items:center;height:52px;padding:0 18px 0 6px}
  .dt-wf .hd .ibtn{width:48px;height:52px;border-radius:24px;font-size:20px}
  .dt-wf .tx{flex:none;font-size:13px;color:var(--text-2);padding:5px 6px;line-height:1.45;overflow-wrap:anywhere}
  .dt-wf .nav4{flex:none;display:flex}
  .dt-wf .nav4>.btn{flex:1;min-width:0;margin:6px 2px;padding:0 4px;min-height:48px;font-size:13px;font-weight:500;color:var(--text)}
  .dt-wf .cancel{flex:none;min-height:48px;font-size:13px;font-weight:500}
  .dt-list{flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;scrollbar-width:none}
  .dt-list::-webkit-scrollbar{display:none}
  .dt-row{min-height:64px;padding:10px 12px;font-size:15px;color:var(--text);line-height:1.4;border-bottom:1px solid var(--line);white-space:pre-line;overflow-wrap:anywhere}
  /* BrowserOriginRestoreActivity（裸 LinearLayout + TextView + WebView） */
  .dt-or{flex:1;min-height:0;display:flex;flex-direction:column;background:var(--bg)}
  .dt-or .st{flex:none;padding:8px;font-size:14px;color:var(--text-2);line-height:1.45;white-space:pre-line}
  .dt-or .web{flex:1;min-height:0;border-top:1px dashed var(--line-strong);display:grid;place-items:center;
    color:var(--muted);font-size:12px;text-align:center;padding:16px;line-height:1.6}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  // ---------- 小件 ----------
  const TICK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.2 4.2L19 7" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const radio = (on, title, sub, cls) => `<div class="dt-opt ${cls || ''}" data-touch role="radio" aria-checked="${!!on}">` +
    `<span class="dt-rb${on ? ' on' : ''}"><i></i></span><span class="w">${esc(title)}${sub ? `<span class="sub">${esc(sub)}</span>` : ''}</span></div>`;
  const check = (on, text, cls) => `<div class="dt-opt ${cls || ''}" data-touch role="checkbox" aria-checked="${!!on}">` +
    `<span class="dt-cb${on ? ' on' : ''}"><i>${on ? TICK : ''}</i></span><span class="w dt-text">${esc(text)}</span></div>`;
  // CardPage.footer：竖排整宽按钮；空 footer 仍保留 8 + 16 内边距。
  const foot = btns => `<div class="dt-foot">${btns.join('')}</div>`;
  const pri = (t, off) => h.btn(t, 'pri block' + (off ? ' off' : ''));
  const sec = (t, off) => h.btn(t, 'block' + (off ? ' off' : ''));
  const page = (title, desc, body) => `<div class="scroll"><div class="pg">${title ? h.title(title, desc) : (desc ? `<p class="pg-desc">${esc(desc)}</p>` : '')}${body}</div></div>`;

  // ================================================================ NativeDataActivity
  // 默认 data_mode（无 extra）=「应用数据与救援」工具页；restore =「从备份恢复」常驻向导（StorageHelp「选择备份做恢复预检」进入）。
  // 「取消当前作业」footer 按钮默认 GONE，状态卡在文字为空时 GONE。
  const SCOPES = [
    ['应用数据', '对话、附件、配置和用户插件，不含 Ubuntu'],
    ['对话与附件', '只导出会话记录和附件'],
    ['设置', 'profile 设置与工作目录配置'],
    ['插件', '用户插件；系统插件按当前 APK 重建'],
    ['所选项目文件', '只导出下方选择的项目目录'],
  ];
  const scopeGroup = () => SCOPES.map(([t, s], i) => radio(i === 0, t, s)).join('');
  function nativeTools() {
    const actions = h.entries([
      { title: '导出应用数据', sub: '将停止 Web、终端和写入任务后建立快照，导出无需密码的 tar.gz 备份。', icon: 'ui_export' },
      { title: '只读救援导出', sub: '救援不会停止当前写入，结果将标明尽力救援或部分数据。不能据此删除原件。导出为无需密码的 tar.gz。', icon: 'recovery_shield' },
      { title: '导入备份', sub: '先验证备份内容，再确认恢复范围。', icon: 'ui_import' },
      { title: '重新导出已验证副本', icon: 'recovery_document' },
    ]);
    const options = `<div class="card"><div class="dt-status" style="padding-top:6px">项目文件需单独选择目录；不会扫描整部手机。</div>` +
      `<div class="dt-mt10">${sec('选择项目目录')}</div>` +
      `<div style="margin-top:8px">${check(false, '包含原生 API Key（默认不包含）', 'h48')}</div></div>`;
    return page('应用数据与救援', '运行环境不可用时，也能检查并保护可读取的数据。',
      actions + h.label('导出选项') + `<div class="card">${scopeGroup()}</div>` + options +
      h.label('保留数据') + h.entries([{ title: '保留副本与旧树', sub: '查看保留副本、旧环境和插件原件；检查与恢复分别处理。', icon: 'recovery_database' }]) +
      h.label('恢复与维护') + h.entries([
        { title: '恢复中断的数据提交', sub: '将先停止写任务，再根据宿主日志恢复一致状态。无需旧 Python 或备份密码，原件将保留。', icon: 'recovery_refresh' },
        { title: '选择已有数据目录', icon: 'recovery_database' },
        { title: '回退兼容运行时', icon: 'recovery_wrench' },
      ])) + foot([]);
  }
  function nativeRestore() {
    const names = ['选择文件', '范围与选项', '预检核验', '确认恢复'];
    const cur = 1;
    const steps = `<div class="dt-steps" role="img" aria-label="第 2 步，共 4 步：范围与选项">` + names.map((n, i) =>
      `<div class="${i <= cur ? 'on' : ''}${i === cur ? ' cur' : ''}"><div class="bar"></div><div class="t">${n}</div></div>`).join('') + `</div>`;
    // optionsStep：kv(备份来源) + 说明 + 恢复范围 RadioGroup + API Key 复选 + 项目位置 RadioGroup + 两个卡内按钮。
    // 备份来源 kv 值最宽 210dp，长文件名会换行（示例已截短，真实为完整显示名）。
    const wizard = `<div class="card">${h.kv('备份来源', 'DSHA-data-v5-3f9c….tar.gz')}
      <div class="dt-status" style="padding:4px 0 8px">此备份无需输入密码。先在私有目录验证内容和实际范围，确认前不会覆盖当前数据。</div>
      <div class="dt-sublab">恢复范围</div>${scopeGroup()}
      ${check(false, '恢复原生 API Key（默认保留本机凭据）', 'h48')}
      <div class="dt-sublab" style="padding-top:8px">项目恢复位置（创建新的独立目录）</div>
      ${radio(true, '应用私有项目区', '', 'h44')}${radio(false, '容器主目录 /root', '', 'h44')}
      <div class="dt-mt10">${pri('开始预检')}</div><div class="dt-mt10">${sec('重新选择')}</div></div>`;
    return page('从备份恢复', '先验证备份内容，再确认恢复范围。', steps + wizard) + foot([]);
  }
  DSHA.page({
    id: 'data', group: GROUP, title: '应用数据与救援', shell: 'sub',
    source: 'NativeDataActivity（actionsPage / scopeGroup / optionsCard / renderStepper / optionsStep）· CardPage',
    states: [{ id: 'tools', title: '救援工具（默认）' }, { id: 'restore', title: '从备份恢复 · 范围与选项' }],
    render(env) { return env.state === 'restore' ? nativeRestore() : nativeTools(); },
  });

  // ================================================================ WorkspaceFilesActivity
  // 非 CardPage：LinearLayout 背景 surface（= shell_bg），内边距 12/6/12/12；返回行由 addHeader 插入在内边距之内，
  // 所以返回钮比 CardPage 页再右移 12dp、且没有页面标题（只有说明文字）。四个导航钮 weight 1、各 2/6 外边距、48dp 高；
  // ListView 占满剩余高度，自身滚动；分页行仅在 offset>0 或还有下一页时出现（示例不出现）。
  // 按钮 setTextColor 用固定颜色（不是状态列表），禁用时只换底色、文字仍为正文色：照实画。
  // 行文字为「名称\n大小」15sp 同色两行（Formatter.formatFileSize，SI 单位）。
  const WF_ROWS = [
    ['assets', '目录'], ['docs', '目录'], ['output', '目录'],
    ['chart-weekly.png', '286 kB'], ['README.md', '4.2 kB'], ['report.pdf', '1.3 MB'],
  ];
  DSHA.page({
    id: 'data-workspace-files', group: GROUP, title: '作品文件导出', shell: 'bare',
    source: 'WorkspaceFilesActivity（onCreate / render / FileRows）',
    states: [{ id: 'list', title: '工作目录列表' }, { id: 'saving', title: '正在导出' }],
    render(env) {
      const saving = env.state === 'saving';
      const nb = t => h.btn(t, saving ? 'off' : '');
      const msg = saving ? '正在核对并导出：report.pdf' : '点目录继续浏览，点文件选择保存或分享。';
      return `<div class="dt-wf">
        <div class="hd"><button class="ibtn" aria-label="返回">${icon('ui_back')}</button></div>
        <div class="tx">选择工作目录里的作品文件，保存到手机或分享给其他应用。无需 Root；这是普通文件导出，完整数据备份另有入口。</div>
        <div class="nav4">${nb('工作目录')}${nb('DSHA')}${nb('上一级')}${nb('刷新')}</div>
        <div class="tx">位置：/root/deepseek-harness</div>
        <div class="tx">${esc(msg)}</div>
        ${saving ? h.btn('取消导出', 'block cancel') : ''}
        <div class="dt-list">${WF_ROWS.map(([n, d]) => `<div class="dt-row" data-touch>${esc(n)}\n${esc(d)}</div>`).join('')}</div>
      </div>`;
    },
  });

  // ================================================================ StorageActivity
  // 第一张卡：kv(工作目录) + entry(文件共享)；第二张卡：总量 22sp + 14dp 分布条 + 每个类别一行 kv（带 10dp 色点）。
  // 分布条色板顺序 shell_accent / good / warn / bad / primary_dark / text_muted（StorageActivity.PALETTE），
  // 段按大小降序（StorageUsage.segments）；0 字节类别的色点为 shell_raised。
  // 「historical-audit-fixtures」不在 StorageCategoryLabels 里，原生直接显示原键名：照实画。
  // kv 用 CardPage.kv（kv 之后的 entry 由 CardPage.entry 自动加 44dp 缩进分隔线）。
  const SIZES = [
    ['linux', '当前 Linux 与 DSH', 1202590842], ['host-runtime-operations', '运行时回退副本', 405170995],
    ['host-environment-operations', '环境重建保护副本', 0], ['runtime-updates', '旧版更新记录', 2411724],
    ['host-backup-operations', '备份副本', 64801996], ['mozilla', '兼容浏览器数据', 0],
    ['user-data-v5', '宿主个人数据', 155818803], ['cache', '应用缓存', 24641536],
    ['historical-audit-fixtures', 'historical-audit-fixtures', 0],
  ];
  const fmtBytes = b => {
    if (b < 1024) return b + ' B';
    const kb = b / 1024; if (kb < 1024) return kb.toFixed(1) + ' KB';
    const mb = kb / 1024; if (mb < 1024) return mb.toFixed(1) + ' MB';
    return (mb / 1024).toFixed(2) + ' GB';
  };
  DSHA.page({
    id: 'data-storage', group: GROUP, title: '存储与文件', shell: 'sub',
    source: 'StorageActivity（renderUsage）· StorageMaintenance.inspect · StorageUsage · StorageCategoryLabels',
    states: [{ id: 'sized', title: '已统计' }, { id: 'loading', title: '正在统计' }],
    render(env) {
      const loading = env.state === 'loading';
      // primary_dark 不在 kit token 里：取 pal_standard_primary_dark（浅 #1A46B8 / 深 #3A75DA）。
      const palette = ['var(--accent)', 'var(--good)', 'var(--warn)', 'var(--bad)', env.theme === 'dark' ? '#3A75DA' : '#1A46B8', 'var(--muted)'];
      const total = SIZES.reduce((s, x) => s + x[2], 0);
      const sorted = SIZES.filter(x => x[2] > 0).sort((a, b) => b[2] - a[2] || a[0].localeCompare(b[0]));
      const overflow = sorted.length > palette.length;
      const named = overflow ? palette.length - 1 : sorted.length;
      const color = {};
      const segs = sorted.slice(0, named).map((x, i) => { color[x[0]] = palette[i]; return [x[2] / total, palette[i]]; });
      if (overflow) segs.push([sorted.slice(named).reduce((s, x) => s + x[2], 0) / total, palette[named]]);
      const other = overflow ? palette[named] : null;
      const rows = loading ? '' : SIZES.map(([k, label, b]) => {
        const c = color[k] || (b > 0 && other) || 'var(--raised)';
        return `<div class="kv"><span class="k dt-kd"><b style="background:${c}"></b>${esc(label)}</span><span class="vv">${fmtBytes(b)}</span></div>`;
      }).join('');
      const bar = loading ? '' : segs.map(([f, c]) => `<i style="flex:${Math.max(f, 0.004)};background:${c}"></i>`).join('');
      return page('存储与文件', '完整 Linux 环境约占 1 GB。历史环境和回退副本另行保留；它们不等于聊天数据。以下为文件大小估算。',
        `<div class="card">${h.kv('工作目录', '/root/deepseek-harness')}<div class="divider"></div>` +
        h.entry({ title: '文件共享', sub: '通过系统文件选择器或 MT 管理器访问 DSHA', icon: 'ui2_box' }) + `</div>` +
        `<div class="card"><div class="dt-total">${loading ? '&nbsp;' : fmtBytes(total)}</div><div class="dt-bar">${bar}</div>${rows}</div>` +
        `<div class="dt-s12">${loading ? '正在处理…' : '已完成统计。清理只移除可再生缓存和已核验的多余运行时副本，个人数据与备份保留。'}</div>`) +
        foot([pri('清理可再生缓存与多余运行时副本', loading), sec('重新统计', loading)]);
    },
  });

  // ================================================================ StorageHelpActivity
  // CardPage 去掉了标题 TextView（removeViewAt(0)）和 footer：返回行下直接是说明文字，随后 7 张问答卡。
  // 默认只展开第一张；卡头 48dp、16sp 粗体 + 20dp chevron_down（展开时旋转 180°）。段落 14sp、行距 +3、上边距 10。
  const HELP = [
    { t: '只想腾空间，从这里开始', p: [
      '先到「存储与文件」查看占用。完整 Ubuntu、Node 和插件依赖属于应用数据，通常比 Android 缓存大得多。Android「清缓存」可能只有几十 MB，清完不会移除这套运行环境。',
      '应用内清理只移除可再生缓存和已核验的多余运行时副本。对话、附件、设置、插件、手动备份和无法核验的原件保留。清理前仍会在原页面确认，并安全停止 DSH 和终端。'],
      after: [{ entry: { title: '查看占用与清理选项', sub: '打开存储页后才统计详细大小；清理仍需原页面确认。', icon: 'ui2_box' } }] },
    { t: '重要数据，先导出再维护', p: [
      '导出选择「应用数据」可保存对话、附件、配置和用户插件，不会复制完整 Ubuntu。个人项目要另选目录；原生 API Key 默认不包含，需要时在导出页主动勾选。',
      '新导出文件为 DSHA-data-v5-….tar.gz，无需备份密码。请用系统保存窗口存到 App 外的文档、下载或云盘，并另留一份。导出完成后可先做恢复预检，确认范围和文件可读；预检不会立即覆盖当前数据。',
      '本机自动备份和重建保护副本仍在 App 内，不保证清除全部存储或卸载后可用。历史加密备份仍需保存原密码。'],
      after: [{ button: '去导出重要数据' }, { entry: { title: '选择备份做恢复预检', sub: '先检查文件，再由原页面确认是否恢复。', icon: 'ui2_box' } }] },
    { t: '备份并重建环境' }, { t: '重置配置（保留对话）' }, { t: 'Android 系统「清缓存」' },
    { t: 'Android 系统「清除全部存储／清除数据」' }, { t: '为什么占用会增长' },
  ];
  DSHA.page({
    id: 'data-storage-help', group: GROUP, title: '存储、备份与重建说明', shell: 'sub',
    source: 'StorageHelpActivity（section / paragraph / setExpanded）· CardPage',
    states: [{ id: 'first', title: '默认（首组展开）' }, { id: 'export', title: '展开「先导出」' }],
    render(env) {
      const open = i => i === 0 || (env.state === 'export' && i === 1);
      const cards = HELP.map((s, i) => {
        const o = open(i) && s.p;
        let body = '';
        if (o) {
          body = s.p.map(p => `<div class="dt-p">${esc(p)}</div>`).join('');
          // 卡头算作卡内第一个子视图，所以 entry 前总有 44dp 缩进分隔线（CardPage.entry 规则）。
          body += s.after.map(a => a.button ? `<div class="dt-mt10">${pri(a.button)}</div>` : `<div class="divider"></div>${h.entry(a.entry)}`).join('');
        }
        return `<div class="card"><div class="dt-qh${o ? ' open' : ''}" data-touch role="button" aria-expanded="${!!o}"><span class="t">${esc(s.t)}</span>${icon('chevron_down')}</div>${body}</div>`;
      }).join('');
      return page('', '先看保留范围，再选择操作。打开本页不会修改文件或停止 DSH。', cards);
    },
  });

  // ================================================================ RetainedDataActivity
  // 参考 docs/design/retained-data-20261102（示例数据取自该原型的样例）；这里画的是当前原生实现，不是 A/B/C 方案。
  // 筛选 chip：bg_chip_clickable（无选中态底色），选中只把文字换成 shell_accent + 粗体；40dp 高（<44，data-dense）。
  //   注：该原型 orig-phone 截图里选中 chip 有浅色底，当前 bg_chip_clickable 没有 selected 状态，这里按代码画。
  // 每条记录：bg_card（18dp 圆角）+ 12dp 内边距 + 12dp 下间距；CheckBox 16sp 两行 + 「查看与操作」48dp 次按钮。
  // footer 三个按钮竖排（CardPage.footer 是 VERTICAL），约占 196dp。
  // report / pageStatus 是未设字号的 TextView（推断为默认 14sp）。
  const RT = [
    ['运行时原件 · a480fa8e · previous', '完成历史 · 原操作已提交 · 不自动删除', true],
    ['运行时原件 · 77044d28 · record', '完成历史 · 原操作已提交 · 不自动删除', true],
    ['加密副本 · 5c19e0b2 · DSHA-auto-20261101-0300', '完成历史 · 已记录验证，操作前复核 · 不自动删除', false],
    ['隔离插件 · e41a77c0 · dsh-web-mobile', '活动目录 · 需要恢复中断操作 · 不自动删除', false],
    ['待恢复 profile 设置 · 9f02c6aa · default', '活动目录 · 迁移待重试或待处理 · 不自动删除', false],
    ['插件原件 · e41a77c0 · dsh-web-mobile (2)', '活动目录 · 同 ID 重复原件；操作拒绝 · 不自动删除', false, 'dup'],
  ];
  DSHA.page({
    id: 'data-retained', group: GROUP, title: '保留副本与旧环境', shell: 'sub',
    source: 'RetainedDataActivity（renderChips / label / render / inspect）· CardPage · 参考 docs/design/retained-data-20261102',
    states: [{ id: 'list', title: '清单（已勾选 2 条）' }, { id: 'inspected', title: '检查完成' }],
    render(env) {
      const done = env.state === 'inspected';
      const chips = `<div class="dt-chips" data-hscroll data-dense role="radiogroup">` +
        ['全部', '备份副本', '旧环境', '插件', '其他'].map((n, i) => `<span class="dt-chip${i === 0 ? ' on' : ''}" role="radio" aria-checked="${i === 0}">${n}</span>`).join('') + `</div>`;
      // 检查报告：每条「key · 结果」+ 末行合计；错误码原样保留（推断：RECORD_ONLY 码无本地化映射）。
      const report = done ? `<div class="dt-rp">RUNTIME:a480fa8e5d2c41b7:tree · 本次读取一致；没有据此确认历史格式或完整性
RUNTIME:77044d28c9a1e305:record · RECORD_ONLY_NOT_A_VERIFIED_COPY
检查通过：1；未通过或未完整读取：1</div>` : '';
      const rows = RT.map(([a, b, sel, dup]) => `<div class="dt-rc">${check(sel, a + '\n' + b, dup ? 'off' : '')}${h.btn('查看与操作', 'block')}</div>`).join('');
      return page('保留副本与旧环境', '原件保持只读。记录中的成功状态不是本次重新验证；检查、导出与恢复分别处理。未知或受损原件继续保留。',
        chips + report + `<div class="dt-ps">已显示 1–6 / 6 条 · 第 1 页</div>` + rows) +
        foot([pri('检查所选记录'), sec('刷新清单'), sec('取消检查')]);
    },
  });

  // ================================================================ AutomaticBackupActivity
  // 设置卡：MaterialSwitch（带文字，48dp）+ entry(备份时机) + entry(每天时间 / 间隔小时) + 12sp 说明 + 11sp 卸载提示。
  // 两个 entry 都用 ic_ui2_box（原生如此）。状态文字直接挂在 content 上（无卡片），随后「本机副本」分组。
  // MaterialSwitch 本身不是 CardPage.entry，所以第一个 entry 前也有分隔线（card 已有子视图）。
  DSHA.page({
    id: 'data-auto-backup', group: GROUP, title: '自动备份', shell: 'sub',
    source: 'AutomaticBackupActivity（render / refresh）· CardPage · CardSheet.choices',
    states: [{ id: 'copies', title: '每天 · 有 3 份副本' }, { id: 'empty', title: '按间隔 · 暂无副本' }],
    render(env) {
      const empty = env.state === 'empty';
      const timing = empty
        ? [{ title: '备份时机', sub: '每隔一段时间', icon: 'ui2_box' }, { title: '间隔小时', sub: '24', icon: 'ui2_box' }]
        : [{ title: '备份时机', sub: '每天指定时间', icon: 'ui2_box' }, { title: '每天时间', sub: '03:00', icon: 'ui2_box' }];
      const settings = `<div class="card"><div class="dt-swrow" data-touch role="switch" aria-checked="true"><span class="w">自动备份</span>${h.sw(true)}</div>` +
        timing.map(o => `<div class="divider"></div>${h.entry(o)}`).join('') +
        `<div class="dt-s12">到期时仍在运行则延后，不会停止对话或终端。时间由系统调度，可能略有延迟。保留最近 3 份已验证自动副本。关闭后取消后续任务，已有副本保留。</div>` +
        `<div class="dt-s11">自动副本与本机密钥在卸载时会清除。换机或卸载前，请导出无需密码的 tar.gz 并保存到应用外。</div></div>`;
      const status = empty ? '已开启 · 等待到期且 DSH 停止' : '已开启 · 等待到期且 DSH 停止\n上次完成：2026-11-02 03:01';
      const copies = empty
        ? `<div class="card"><div class="dt-s12" style="color:var(--muted)">还没有自动备份。到达所选时间后，在 DSH 停止且没有终端任务时创建第一份。</div></div>`
        : h.entries([
          { title: '11-02 03:01', sub: '38.6 MB', icon: 'recovery_document' },
          { title: '11-01 03:00', sub: '38.2 MB · 插件依赖待核对', icon: 'recovery_document' },
          { title: '10-31 03:04', sub: '37.9 MB', icon: 'recovery_document' },
        ]);
      return page('自动备份', '默认开启。备份对话、附件、配置和插件；不重复备份 Ubuntu。个人项目请使用手动备份。',
        settings + `<div class="dt-s12">${esc(status)}</div>` + h.label('本机副本 · 保留最近 3 份') + copies) +
        foot([pri('现在创建自动备份'), sec('刷新备份记录')]);
    },
  });

  // ================================================================ ColdSetupSpeedActivity
  // CardPage 但没有调用 UiNavigation.addHeader：页面顶部没有返回行，返回只靠 footer「返回」或系统返回（shell: bare 照实画）。
  // 结果卡为等宽 13sp 可选文字。onCreate 读的是 report(this, false)（latest-failure.json），所以成功后再次打开页面
  // 显示的是最近一次失败或「—」，不是最近一次成功（推断，依据 ColdSetupTiming.report 的文件选择）。
  // 「done」状态为本次运行刚结束时的文字（成功文案 + report(true)）；阶段名与毫秒数为示意（推断）。
  DSHA.page({
    id: 'data-cold-setup', group: GROUP, title: '首次安装测速', shell: 'bare',
    source: 'ColdSetupSpeedActivity · CardPage · core/ColdSetupTiming.report',
    states: [{ id: 'idle', title: '未测速' }, { id: 'done', title: '测速完成' }],
    render(env) {
      const done = env.state === 'done';
      const result = done ? `完整首次安装完成：186.42 秒；临时环境已清理，原数据保留。
186.42 s
runtime: proot disableSeccomp=false
0 ms: 正在准备运行环境
4210 ms: 正在解压离线环境
121870 ms: 正在核对运行组件
163550 ms: 正在启动 Web 试运行

hostStageMillis
extract: 117660 ms
verify: 41680 ms` : '—';
      const facts = [
        ['ui2_box', '在独立目录完整执行首次安装，当前环境、数据和设置保持不变'],
        ['onb_info', '开始时会正常停止 DSH 和终端'],
        ['recovery_clock', '测速期间请保持应用在前台，结束后自动清理临时环境'],
      ].map(([ic, t]) => `<div class="dt-fact">${icon(ic)}<span>${t}</span></div>`).join('');
      return page('首次安装测速', '', `<div class="card">${facts}</div>` + h.label('最近一次结果') +
        `<div class="card"><div class="dt-mono">${esc(result)}</div></div>`) + foot([pri('开始测速'), sec('返回')]);
    },
  });

  // ================================================================ BrowserOriginRestoreActivity
  // 没有 CardPage、返回行或标题：一个竖排 LinearLayout，顶部 TextView（setPadding(20,…) 是 px 不是 dp，
  // 在 2.5–2.75 密度上约 8dp，推断）+ 下方 WebView/Gecko 加载专用迁移文档（内容由该文档自绘，本预览不画）。
  // 窗口底色为主题 windowBackground（dsha_pal_surface = shell_bg），文字为默认 TextView 样式（推断 14sp / 次要色）。
  // 页面 title 为 Activity 内的用户入口文案「恢复旧端口草稿」（取自绑定失败提示中的引用）。
  DSHA.page({
    id: 'data-origin-restore', group: GROUP, title: '恢复旧端口草稿', shell: 'bare',
    source: 'BrowserOriginRestoreActivity（onCreate 状态文字）· BrowserOriginRestoreFactory',
    states: [{ id: 'reading', title: '读取旧端口候选' }, { id: 'result', title: '恢复结果' }],
    render(env) {
      const text = env.state === 'result'
        ? '恢复结果：写入 12，冲突 1，未知 0。候选与原数据已保留，请重新进入对话页读取。'
        : '正在读取旧端口的数据候选；原数据不会删除。';
      return `<div class="dt-or"><div class="st">${esc(text)}</div>
        <div class="web">专用迁移文档（WebView / Gecko）<br>由 /__dsha/browser-origin-restore 自绘，本预览不画其内容</div></div>`;
    },
  });
})();
