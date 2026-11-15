/*
 * 启动主页 · 参考实现（其他页面照此写法）。
 * 来源：res/layout/fragment_launch.xml、ui/LaunchFragment.java（applySchemeALayout / renderStatusChrome）、
 *       ui/LaunchLogPanel.java、values/shell_launch.xml + values-w600dp / values-sw600dp。
 * 布局判据：
 *   - screenWidthDp >= 600（env.w600）：显示插图；主操作与停止/重启同一行；标题 34sp、页边距 36dp。
 *   - 下半区 AdaptiveColumns 按自身宽度 >= 760dp 并排（列距 32，权重 1 : 1.3）。
 *     自身宽度 = fragment 宽（平板减 81dp 左栏）- 2 × 页边距。
 */
(function () {
  const { h } = DSHA;
  const css = `
  .ln{padding:var(--launch-top) var(--launch-pad) var(--launch-bottom)}
  .ln-eyebrow{display:flex;align-items:center;gap:7px;font-size:11px;font-weight:700;letter-spacing:.04em;color:var(--muted)}
  .ln-hero{display:flex;align-items:center;margin-top:20px}
  .ln-hero .acts{flex:1;min-width:0}
  .ln-title{margin:0;font-size:var(--launch-title);font-weight:700;line-height:1.2}
  .ln-sub{margin:10px 0 0;font-size:13px;line-height:1.55;color:var(--text-2)}
  .ln-track{height:16px;margin-top:14px;color:var(--accent)}
  .ln-track svg{width:100%;height:16px;display:block}
  .ln-status{margin-top:8px;font-size:12px;color:var(--text-2)}
  .ln-ctrl{margin-top:20px;display:flex;align-items:center;gap:12px}
  .ln-ctrl.stack{flex-direction:column;align-items:stretch;gap:10px}
  .ln-ctrl.stack .quiet{display:flex;gap:10px}
  .ln-ctrl.stack .quiet .btn{flex:1;border:1px solid var(--line);background:var(--surface);min-height:46px}
  .ln-ctrl .start{min-width:180px;padding:0 24px}
  .ln-ctrl .quiet{display:flex;gap:4px}
  .ln-ctrl .quiet .btn{color:var(--text-2);font-weight:500;font-size:13px}
  .ln-art{flex:none;width:116px;height:140px;margin-left:24px;position:relative;display:grid;place-items:center}
  .ln-art .ring{position:absolute;width:98px;height:93px;border-radius:34px;border:1.5px dashed var(--line-strong);transform:rotate(13deg)}
  .ln-art .core{width:65px;height:65px;border-radius:20px;background:var(--accent-soft);border:1px solid var(--line-strong);display:grid;place-items:center;color:var(--accent);font-size:34px}
  .ln-metrics{display:flex;gap:8px;margin-top:var(--launch-gap)}
  .ln-metric{flex:1;min-width:0;background:var(--surface);border:1px solid var(--line);border-radius:16px;padding:14px}
  .ln-metric .tile{width:31px;height:31px;background:var(--accent-soft);font-size:16px}
  .ln-metric .k{margin-top:10px;font-size:11px;color:var(--muted)}
  .ln-metric .v{margin-top:5px;font-size:14px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .ln-bottom{margin-top:var(--launch-gap)}
  .ln-bottom.split{display:flex;gap:32px;align-items:flex-start}
  .ln-bottom.split>.a{flex:1;min-width:0}.ln-bottom.split>.b{flex:1.3;min-width:0}
  .ln-bottom:not(.split)>.b{margin-top:var(--launch-gap)}
  .ln-sec{font-size:12px;font-weight:700;color:var(--text-2);min-height:44px;display:flex;align-items:center;margin-bottom:6px}
  .ln-row{display:flex;align-items:center;gap:14px;min-height:54px;padding:12px 4px;border-bottom:1px solid var(--line);font-size:14px}
  .ln-row>.i{font-size:20px;color:var(--muted)}
  .ln-row .w{flex:1;min-width:0}.ln-row .s{font-size:11px;color:var(--muted);margin-top:4px}
  .ln-row .chev{font-size:16px}
  .lg-head{display:flex;align-items:center;min-height:40px;gap:8px}
  .lg-head .w{flex:1;min-width:0}
  .lg-head .t{font-size:15px;font-weight:700}
  .lg-head .m{display:flex;align-items:center;gap:6px;margin-top:3px;font-size:12px;color:var(--text-2)}
  /* 40dp = shell_launch_log_ibtn，原生即低于 44dp 下限，data-dense 豁免自检并在 README 记录 */
  .lg-head .ibtn{width:40px;height:40px;border-radius:12px;border:1px solid var(--line);background:var(--surface)}
  .lg-tabs{display:flex;gap:4px;padding:3px;margin-top:10px;border-radius:999px;background:var(--raised)}
  .lg-tabs>span{flex:1;min-height:36px;display:grid;place-items:center;border-radius:999px;font-size:12px;font-weight:700;color:var(--text-2)}
  .lg-tabs>.on{background:var(--surface);color:var(--text)}
  .lg-box{margin-top:8px;border:1px solid var(--line-strong);border-radius:16px;background:var(--surface);overflow:hidden}
  .lg-list{height:var(--launch-log-h);overflow:hidden;padding:4px 0}
  .lg-st{display:flex;align-items:center;gap:10px;padding:9px 12px;font-size:13px}
  .lg-st .i{font-size:18px}
  .lg-st .n{flex:1}
  .lg-st .d{font-family:var(--mono);font-size:12px;color:var(--muted)}
  .lg-st.ok .i{color:var(--good)}.lg-st.run .i{color:var(--accent)}.lg-st.idle .i{color:var(--muted)}
  .lg-empty{display:grid;place-items:center;height:100%;font-size:13px;color:var(--muted)}
  .lg-foot{display:flex;align-items:center;padding:0 4px 0 12px;border-top:1px solid var(--line);min-height:40px;font-size:11px;color:var(--muted)}
  .lg-foot span{flex:1}
  .lg-foot .ibtn{width:40px;height:40px;font-size:16px}
  .ln-sec2{display:flex;gap:10px;margin-top:12px}.ln-sec2 .btn{flex:1;font-size:13px}
  /* 提案 P7（README §8）：短屏横屏隐藏插图、标题 34→24sp、去掉副标题与轨道上方间距，主按钮回到首屏。 */
  .ln.cmp{padding-top:12px}
  .ln.cmp .ln-hero{margin-top:8px}
  .ln.cmp .ln-title{font-size:24px}
  .ln.cmp .ln-sub{display:none}
  .ln.cmp .ln-track{margin-top:8px}
  .ln.cmp .ln-ctrl{margin-top:10px}
  .ln.cmp .ln-metrics{margin-top:16px}
  `;
  const style = document.createElement('style'); style.textContent = css; document.head.appendChild(style);

  // ShellActivityTrack：空闲时一条细线，运行时为波形。
  const track = running => running
    ? `<svg viewBox="0 0 300 16" preserveAspectRatio="none"><path d="M0 8 Q 7 3 14 8 T 28 8 T 42 8 T 56 8 T 70 8 T 84 8 T 98 8 T 112 8" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round"/><path d="M120 8H300" stroke="var(--raised)" stroke-width="4" stroke-linecap="round"/></svg>`
    : `<svg viewBox="0 0 300 16" preserveAspectRatio="none"><path d="M2 8H298" stroke="var(--raised)" stroke-width="4" stroke-linecap="round"/></svg>`;

  DSHA.page({
    id: 'launch', group: '主标签页', title: '启动', shell: 'main', tab: 'launch',
    source: 'fragment_launch.xml · LaunchFragment · LaunchLogPanel',
    states: [{ id: 'stopped', title: '未运行' }, { id: 'ready', title: '已就绪' }],
    context: () => '本机环境',
    render(env) {
      const ready = env.state === 'ready';
      const cmp = env.proposal && env.short480;
      const pad = env.w600 ? 36 : 20;
      const bottomW = env.mainContentW - pad * 2;
      const split = bottomW >= 760;
      const controls = env.w600
        ? `<div class="ln-ctrl">${h.btn(ready ? '进入工作台' : '启动 DSHA', 'pri start', ready ? 'launch_open' : 'launch_start')}
             <div class="quiet">${h.btn('停止', 'quiet', 'launch_stop')}${h.btn('重启', 'quiet', 'plugin_restart')}</div></div>`
        : `<div class="ln-ctrl stack">${h.btn(ready ? '进入工作台' : '启动 DSHA', 'pri block', ready ? 'launch_open' : 'launch_start')}
             <div class="quiet">${h.btn('停止', '', 'launch_stop')}${h.btn('重启', '', 'plugin_restart')}</div></div>`;
      const stages = [
        ['环境检查', '0.4s'], ['运行时与端口', '1.2s'], ['插件加载', '2.1s'], ['Web 鉴权', '0.6s'],
      ];
      const logBody = ready
        ? stages.map(([n, d]) => `<div class="lg-st ok">${h.icon('launch_log_ok')}<span class="n">${n}</span><span class="d">${d}</span></div>`).join('')
        : `<div class="lg-empty">还没有日志。</div>`;
      return `<div class="scroll"><div class="ln${cmp ? ' cmp' : ''}">
        <div class="ln-eyebrow"><span class="dot ${ready ? 'good' : ''}"></span>本机工作台 · ${ready ? '已就绪' : '未运行'}</div>
        <div class="ln-hero"><div class="acts">
          <h1 class="ln-title">${ready ? 'DSH 已就绪，可进入' : 'DSH 未运行'}</h1>
          <p class="ln-sub">${ready ? '下方实时显示启动输出；等待不会自动终止。' : '随时开始你的下一项任务。'}</p>
          <div class="ln-track">${track(ready)}</div>
          ${controls}
        </div>${env.w600 && !cmp ? `<div class="ln-art"><span class="ring"></span><span class="core">${h.icon('terminal')}</span></div>` : ''}</div>
        <div class="ln-metrics">
          <div class="ln-metric"><span class="tile">${h.icon('ui2_box')}</span><div class="k">运行环境</div><div class="v">${ready ? 'Ubuntu 24.04' : '—'}</div></div>
          <div class="ln-metric"><span class="tile">${h.icon('terminal')}</span><div class="k">工作台</div><div class="v">${ready ? '已连接' : '—'}</div></div>
          <div class="ln-metric"><span class="tile">${h.icon('ui_link')}</span><div class="k">实际 Web 端口</div><div class="v mono">${ready ? '41237' : '—'}</div></div>
        </div>
        <div class="ln-bottom${split ? ' split' : ''}">
          <div class="a"><div class="ln-sec">快捷入口</div>
            <div class="ln-row">${h.icon('terminal')}<div class="w">打开本机终端</div>${h.icon('ui2_chevron', 'chev')}</div>
            <div class="ln-row">${h.icon('ui2_box')}<div class="w">运行环境 · 检查与维护</div>${h.icon('ui2_chevron', 'chev')}</div>
            <div class="ln-row">${h.icon('ui_link')}<div class="w">访问地址<div class="s">查看本机与局域网地址</div></div>${h.icon('ui2_chevron', 'chev')}</div>
          </div>
          <div class="b">
            <div class="lg-head"><div class="w"><div class="t">启动日志</div>
              <div class="m"><span class="dot ${ready ? 'good' : ''}"></span>${ready ? '已就绪 · 用时 4.3s' : '尚未启动'}</div></div>
              <button class="ibtn" data-dense aria-label="复制完整启动日志">${h.icon('launch_log_copy')}</button>
              <button class="ibtn" data-dense aria-label="下载启动日志">${h.icon('launch_log_download')}</button></div>
            <div class="lg-tabs"><span class="on">概览 ${ready ? stages.length : 0}</span><span>原始</span></div>
            <div class="lg-box"><div class="lg-list">${logBody}</div>
              <div class="lg-foot"><span>${ready ? '4 个阶段 · 38 行原文' : '0 行原文'}</span><button class="ibtn" data-dense aria-label="展开日志区域">${h.icon('launch_log_expand')}</button></div></div>
            <div class="ln-sec2">${h.btn('安全启动')}${h.btn('恢复选项')}</div>
          </div>
        </div>
      </div></div>`;
    },
  });
})();
