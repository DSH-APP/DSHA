/*
 * DSHA UI 模板基准 · 档位、外壳与页面注册（离线预览，不连接设备或网络）。
 *
 * 用法：
 *   index.html?page=launch&profile=tablet-landscape&theme=dark   单屏（导出用，视口 = 档位 dp）
 *   index.html                                                    设计台（全部页面缩略）
 *
 * 页面模块调用 DSHA.page({...}) 注册，render(env) 返回 HTML 字符串。env 里的布尔量
 * 与原生判据一一对应（见 README「断点」），页面只能用这些判据切换布局，不能自造阈值。
 */
(function () {
  'use strict';

  // ---------- 档位：dp 尺寸（整窗，含系统栏） ----------
  const PROFILES = {
    'phone':            { w: 360,  h: 780,  label: '手机竖屏', main: true },
    // 手机横屏：sw<600 仍是手机外壳（悬浮胶囊），但 w≥600 会命中 values-w600dp，且 screenH≤480 命中短屏。
    'phone-landscape':  { w: 780,  h: 360,  label: '手机横屏', main: true },
    'tablet-portrait':  { w: 800,  h: 1280, label: '平板竖屏', main: true },
    'tablet-landscape': { w: 1280, h: 800,  label: '平板横屏', main: true },
    'phone-narrow':     { w: 320,  h: 640,  label: '窄手机（边界）' },
    'phone-large':      { w: 412,  h: 892,  label: '大屏手机（边界）' },
    'y700-portrait':    { w: 640,  h: 1024, label: 'TB321FU 竖屏（边界）' },
    'y700-landscape':   { w: 1024, h: 640,  label: 'TB321FU 横屏（边界）' },
    'phone-landscape-small': { w: 640, h: 320, label: '小手机横屏（边界）' },
  };
  const MAIN_PROFILES = Object.keys(PROFILES).filter(k => PROFILES[k].main);
  const EDGE_PROFILES = Object.keys(PROFILES).filter(k => !PROFILES[k].main);
  // 系统栏示意：状态栏 24dp + 手势条 20dp（真机按 OEM 不同，见 README）。
  const SB_H = 24, GB_H = 20;

  /** 由档位推导与原生 Configuration 等价的判据。 */
  function envFor(id, theme, state, variant) {
    const p = PROFILES[id];
    const screenW = p.w, screenH = p.h - SB_H - GB_H;  // screenWidthDp / screenHeightDp（不含系统栏）
    const sw = Math.min(p.w, p.h);                       // smallestScreenWidthDp（近似）
    const tablet = sw >= 600;                            // layout-sw600dp / values-sw600dp
    const railW = tablet ? 81 : 0;                       // 80dp 左栏 + 1dp 竖线
    return {
      profile: id, label: p.label, theme, state: state || 'default',
      // 打磨提案层（?variant=proposal）：只改 kit 共享外壳，不改原样基准；见 README §8。
      proposal: variant === 'proposal',
      deviceW: p.w, deviceH: p.h, screenW, screenH, sw,
      tablet, phone: !tablet,
      landscape: p.w > p.h,
      w600: screenW >= 600,          // values-w600dp、各 Activity 的 screenWidthDp >= 600
      w840: screenW >= 840,          // SettingsPanes.TWO_PANE_DP、layout-w840dp、终端内嵌侧栏
      short480: screenH <= 480,      // OnboardingKit.shortScreen、MaintenanceFooterPolicy
      narrow370: screenW < 370,      // DeployCapsule
      narrow340: screenW < 340,      // MaintenanceFooterPolicy.stackedLinks
      railW,
      // 主外壳内容宽（fragment_container）：平板减左栏
      mainContentW: screenW - railW,
      // 独立 Activity 内容宽 = screenW
    };
  }

  // ---------- 小工具 ----------
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const icon = (name, cls) => {
    const svg = (window.ICONS || {})[name];
    if (!svg) { console.warn('missing icon', name); return `<span class="i ${cls || ''}" data-missing="${esc(name)}"></span>`; }
    return `<span class="i ${cls || ''}">${svg}</span>`;
  };
  const h = {
    esc, icon,
    /** CardPage.entry：图标块 + 标题/说明 + 右值 + 箭头 */
    entry: (o) => `<div class="entry${o.sel ? ' sel' : ''}">` +
      (o.icon ? `<span class="tile ${o.tone || ''}">${icon(o.icon)}</span>` : '') +
      `<div class="w"><div class="h">${esc(o.title)}</div>${o.sub ? `<div class="s">${esc(o.sub)}</div>` : ''}</div>` +
      (o.value ? `<span class="v">${esc(o.value)}</span>` : '') +
      (o.trail != null ? o.trail : (o.chevron === false ? '' : `<span class="chev">${icon('ui2_chevron')}</span>`)) + `</div>`,
    entries: (list, flush) => `<div class="card${flush ? ' flush' : ''}">` +
      list.map((o, i) => (i ? '<div class="divider"></div>' : '') + h.entry(o)).join('') + '</div>',
    kv: (k, v, mono) => `<div class="kv"><span class="k">${esc(k)}</span><span class="vv${mono ? ' mono' : ''}">${esc(v)}</span></div>`,
    label: t => `<div class="label">${esc(t)}</div>`,
    title: (t, d) => `<h1 class="pg-title${d ? '' : ' only'}">${esc(t)}</h1>` + (d ? `<p class="pg-desc">${esc(d)}</p>` : ''),
    btn: (t, kind, ic) => `<button class="btn ${kind || ''}">${ic ? icon(ic) : ''}${esc(t)}</button>`,
    note: (t, tone, ic) => `<div class="note ${tone || ''}">${icon(ic || 'onb_info')}<div>${t}</div></div>`,
    sw: on => `<span class="switch${on ? ' on' : ''}" role="switch" aria-checked="${!!on}"></span>`,
  };

  // ---------- 外壳 ----------
  const TABS = [
    { id: 'launch', title: '启动', icon: 'launch' },
    { id: 'plugins', title: '插件', icon: 'plugins' },
    { id: 'settings', title: '设置', icon: 'settings' },
    { id: 'terminal', title: '终端', icon: 'terminal' },
  ];
  function statusBar() {
    return `<div class="sb"><span>09:41</span><span class="sys-icons">` +
      `<i style="width:14px;height:9px;clip-path:polygon(0 100%,100% 0,100% 100%)"></i>` +
      `<i style="width:13px;height:9px;border-radius:9px 9px 2px 2px"></i>` +
      `<i style="width:20px;height:9px;border-radius:2.5px"></i></span></div>`;
  }
  function appBar(o) {
    return `<div class="appbar">` +
      (o.back ? `<button class="ibtn lg" aria-label="返回">${icon('ui_back')}</button>` : `<span class="brand">${icon('terminal')}</span>`) +
      `<div class="words"><div class="t">${esc(o.title || 'DSHA')}</div>${o.context ? `<div class="c">${esc(o.context)}</div>` : ''}</div>` +
      (o.trailing || '') + `</div>`;
  }
  function rail(active, compact) {
    return `<nav class="rail${compact ? ' compact' : ''}" aria-label="主导航"><div class="items">` +
      TABS.map(t => `<div class="rail-item${t.id === active ? ' on' : ''}">${icon(t.icon)}<span class="lbl">${t.title}</span></div>`).join('') +
      `</div>${compact ? '' : '<div class="rail-foot">LOCAL FIRST</div>'}</nav>`;
  }
  function bottomNav(active) {
    return `<nav class="nav" aria-label="主导航">` +
      TABS.map(t => `<div class="nav-item${t.id === active ? ' on' : ''}"><span class="pill">${icon(t.icon)}</span><span class="lbl">${t.title}</span></div>`).join('') + `</nav>`;
  }

  /**
   * 页面外壳类型：
   *  main     MainActivity：顶栏 + (平板左栏 | 手机悬浮胶囊) + fragment
   *  sub      独立 Activity：UiNavigation.addHeader 返回行 + CardPage 内容
   *  bare     整屏自绘（引导、维护、终端、启动恢复等），页面自己画全部
   */
  function shell(page, env, inner) {
    if (page.shell === 'main') {
      const bar = appBar({ title: page.appbarTitle ? page.appbarTitle(env) : 'DSHA', context: page.context ? page.context(env) : '', back: page.back && page.back(env), trailing: page.appbarTrailing ? page.appbarTrailing(env) : APPBAR_TRAILING });
      if (env.tablet) {
        return bar + `<div class="body">${rail(page.tab)}<div class="main">${inner}</div></div>`;
      }
      // 提案 P1：手机横屏（短屏）把悬浮胶囊换成 56dp 紧凑侧栏，竖向空间还给内容。
      if (env.proposal && env.short480) {
        return bar + `<div class="body">${rail(page.tab, true)}<div class="main">${inner}</div></div>`;
      }
      return bar + `<div class="body"><div class="main">${inner}</div></div>` + (page.hideNav && page.hideNav(env) ? '' : bottomNav(page.tab));
    }
    if (page.shell === 'sub') {
      return `<div class="subhead"><button class="ibtn" aria-label="返回">${icon('ui_back')}</button>` +
        (page.subheadTitle ? `<span class="st">${esc(page.subheadTitle)}</span>` : '') + `</div>` + inner;
    }
    return inner;
  }

  // ---------- 弹层（CardSheet / DshaDialogBuilder）----------
  /**
   * 弹层几何。返回 { mode, w, maxH, lift, row, sideW, title, pad, rule }。
   *  原样（native）：
   *   - sheet（CardSheet）：screenW ≥ 600 → 居中卡片 min(540, W−32)，高 ≤ min(0.84·整屏, H−48)；
   *     否则贴底整宽，高 ≤ min(0.86·整屏, H−74)，并抬高 74dp 让出胶囊。页脚按钮恒为竖排整宽。
   *   - dialog（DshaDialogBuilder / AlertDialog）：宽 min(560, W) 减系统内边距 24×2（推断），高 ≤ H−24，文字按钮右对齐。
   *   只看宽度，不看高度：手机横屏（780×316）拿到 540 宽的居中卡，竖排页脚吃掉大半高度。
   *  提案（adaptive，见 README §9）：宽高一起决定形态，正文可视高 ≥ 120dp 为硬约束。
   *   A 贴底   W<600 且 H>480          整宽，高 ≤ 0.9H，不再抬高（弹层是模态，盖住胶囊即可）
   *   B 居中   W≥600 且 H>480          宽按内容档：s 400 / m 480 / l 640（W≥840 时 720）/ dialog 400；高 ≤ min(H−80, 800)
   *   C 紧凑   H≤480，s / m 档或 dialog  宽 ≤ min(W−32, 400 / 480 / 400)，高 ≤ H−24，标题 18sp，页脚一行
   *   D 分栏   H≤480，l 档（长表单）且 W≥600   宽 ≤ min(W−48, 680)，左栏标题 + 操作（≈36%），右栏正文滚动
   *  尺寸档由内容决定：s 选择列表 ≤4 项；m 说明 / 短列表；l 多段表单（快捷按键、显示设置）。
   *  页脚：A 档 ≤2 个按钮横排、≥3 竖排；B/C 横排（主按钮在右）；D 在左栏底部竖排。
   */
  function popupGeom(env, o) {
    const W = env.screenW, H = env.screenH, n = (o.actions || []).length;
    const dlg = o.kind === 'dialog', size = o.size || 'm';
    if (!env.proposal) {
      if (dlg) return { mode: 'center', w: Math.min(560, W) - 48, maxH: H - 24, lift: 0, row: true, title: 20, pad: 24, rule: 'native-dialog' };
      if (W >= 600) return { mode: 'center', w: Math.min(540, W - 32), maxH: Math.min(Math.round(env.deviceH * .84), H - 48), lift: 0, row: false, title: 20, pad: 16, rule: 'native-wide' };
      return { mode: 'bottom', w: W, maxH: Math.min(Math.round(env.deviceH * .86), H - 74), lift: 74, row: false, title: 20, pad: 16, rule: 'native-bottom' };
    }
    if (!env.short480 && W < 600) return { mode: 'bottom', w: W, maxH: Math.round(H * .9), lift: 0, row: n <= 2, title: 20, pad: 16, rule: 'A' };
    if (!env.short480) {
      const want = dlg || size === 's' ? 400 : size === 'l' ? (W >= 840 ? 720 : 640) : 480;
      return { mode: 'center', w: Math.min(want, W - 48), maxH: Math.min(H - 80, 800), lift: 0, row: true, title: 20, pad: dlg ? 24 : 20, rule: 'B' };
    }
    if (!dlg && size === 'l' && W >= 600) {
      const w = Math.min(W - 48, 680);
      return { mode: 'side', w, maxH: H - 24, lift: 0, row: false, sideW: Math.round(w * .36), title: 18, pad: 16, rule: 'D' };
    }
    return { mode: 'compact', w: Math.min(W - 32, size === 'm' && !dlg ? 480 : 400), maxH: H - 24, lift: 0, row: true, title: 18, pad: 16, rule: 'C' };
  }
  /**
   * 弹层：o = { kind:'sheet'|'dialog', size:'s'|'m'|'l', title, sub, body(HTML),
   *             actions:[{ t, pri, danger }] }（按原生顺序：次要在前，主操作最后）
   */
  function popup(env, o) {
    const g = popupGeom(env, o);
    const dlg = o.kind === 'dialog';
    const btn = a => dlg && !env.proposal
      ? `<button class="btn quiet${a.danger ? ' danger' : ''}">${esc(a.t)}</button>`
      : `<button class="btn${a.pri ? ' pri' : ''}${a.danger ? ' danger' : ''}">${esc(a.t)}</button>`;
    const acts = (o.actions || []).map(btn).join('');
    const head = `<h3 style="font-size:${g.title}px">${esc(o.title || '')}</h3>` + (o.sub ? `<p class="pp-sub">${esc(o.sub)}</p>` : '');
    const style = `width:${g.w}px;max-height:${g.maxH}px;--pp-pad:${g.pad}px`;
    let inner;
    if (g.mode === 'side') {
      inner = `<div class="pp-side" style="width:${g.sideW}px">${head}<div class="pp-acts col">${acts}</div></div>` +
        `<div class="scroll pp-body">${o.body || ''}</div>`;
    } else {
      inner = `<div class="scroll pp-body">${head}${o.body || ''}</div>` +
        (acts ? `<div class="pp-acts ${g.row ? 'row' : 'col'}${dlg && !env.proposal ? ' text' : ''}">${acts}</div>` : '');
    }
    return `<div class="pp-scrim m-${g.mode}" style="padding-bottom:${g.lift}px">` +
      `<div class="pp ${dlg ? 'dialog' : 'sheet'} m-${g.mode}" role="dialog" data-rule="${g.rule}" style="${style}">${inner}</div></div>`;
  }

  // MainActivity.showMain：下载记录（44dp，内边距 11）+ 关于（btn_about，18dp 图标）常驻顶栏右侧。
  const APPBAR_TRAILING = `<button class="ibtn" aria-label="下载记录">${icon('ui_downloads')}</button>` +
    `<button class="ibtn" aria-label="关于 DSHA">${icon('about')}</button>`;
  const PAGES = [];
  const DSHA = {
    PROFILES, MAIN_PROFILES, EDGE_PROFILES, TABS, h, envFor, popup, popupGeom,
    /**
     * 注册页面：
     *  id, group, title, source(原生来源，字符串), shell('main'|'sub'|'bare'), tab(main 时),
     *  states: [{id,title}]（可选，默认只有 default），render(env)->HTML
     */
    page(def) {
      if (!def.id || !def.render) throw new Error('page 需要 id 与 render');
      if (PAGES.some(p => p.id === def.id)) throw new Error('重复的 page id ' + def.id);
      def.states = def.states && def.states.length ? def.states : [{ id: 'default', title: '默认' }];
      PAGES.push(def);
    },
    pages: () => PAGES.slice(),
    /** 渲染一块设备屏，返回元素。 */
    mount(target, pageId, profileId, theme, stateId, variantArg) {
      const page = PAGES.find(p => p.id === pageId);
      if (!page) throw new Error('未知页面 ' + pageId);
      const p = PROFILES[profileId];
      if (!p) throw new Error('未知档位 ' + profileId);
      const variant = variantArg || new URLSearchParams(location.search).get('variant') || '';
      const env = envFor(profileId, theme, stateId || page.states[0].id, variant);
      const dev = document.createElement('div');
      dev.className = 'dev';
      dev.dataset.theme = theme;
      dev.dataset.page = pageId;
      dev.style.setProperty('--dev-w', p.w + 'px');
      dev.style.setProperty('--dev-h', p.h + 'px');
      dev.style.setProperty('--sb-h', SB_H + 'px');
      dev.style.setProperty('--gb-h', GB_H + 'px');
      // 资源限定符覆盖
      // shell_page_bottom：手机 90dp（为胶囊留位；CardPage 在独立 Activity 也沿用），sw600 为 30dp。
      dev.style.setProperty('--page-bottom', env.tablet ? '30px' : '90px');
      if (env.proposal) {
        dev.dataset.variant = 'proposal';
        if (env.short480) dev.dataset.short = '1';
        if (env.w840) dev.dataset.wide = '1';
        // 提案 P4：独立 Activity 没有胶囊，底部 90dp 留白收回到 24dp；手机横屏主外壳改侧栏后同理。
        if (page.shell !== 'main' || env.short480) dev.style.setProperty('--page-bottom', env.tablet ? '30px' : '24px');
      }
      if (env.w600) { dev.style.setProperty('--launch-title', '34px'); dev.style.setProperty('--launch-pad', '36px'); dev.style.setProperty('--launch-top', '26px'); }
      dev.style.setProperty('--launch-bottom', env.tablet || (env.proposal && env.short480) ? '30px' : '90px');
      const inner = page.render(env, h);
      dev.innerHTML = statusBar() + `<div class="app">${shell(page, env, inner)}</div><div class="gb"></div>`;
      target.appendChild(dev);
      if (page.after) page.after(dev, env);
      return dev;
    },
  };
  window.DSHA = DSHA;
})();
