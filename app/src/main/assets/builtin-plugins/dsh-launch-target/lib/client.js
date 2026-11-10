window.__ModuleLoader__.load({ id: 'dsh-launch-target', factory: (require) => {
  'use strict';
  /*
   * DSHA 内置插件「启动目标」网页端：Web 设置导航里的独立面板，插件详情页保留同一张卡片。
   *
   * - 面板注册为 settings.section（id 见 SECTION_ID），在设置导航里有自己的入口；
   *   插件只负责把选择传给原生 App，面板管理参数，关闭直进 Web 不需要禁用插件；
   * - 同一组件另注册到 plugins.bundle.config（DSH 约定的 bundle 自身配置位），键为本插件包名；
   * - 只使用 DSH 原语（SegmentedControl）与 --dsw-alias-* 变量，DSH 主题、主题插件与外部主题壳
   *   改色后自动跟随，不写死任何颜色；
   * - 宽屏（平板 / 桌面）标签与选择器同一行，窄屏（手机）上下排列；粗指针放大触控高度；
   * - 值以原生 App 为准：打开即回读，切换后以宿主回执为准，失败时回到原值并说明原因。
   */
  const React = require('react');
  const jsx = require('react/jsx-runtime');
  const primitives = require('@deepseek-ai/dsh-client-ui-primitives');

  const NS = 'dsha.launchTarget';
  const KEY = 'dsh-launch-target';
  const ROUTE = '/api/dsha-launch-target';
  const SECTION_ID = 'dsha-launch-target';
  // 在「通用」（order 0）之后、插件等官方分区之前。
  const SECTION_ORDER = 5;

  const zh = {
    nav: '启动目标',
    title: '桌面图标启动后进入',
    hint: '只影响下一次从桌面图标启动。选择 Web 后，点击图标先显示加载页，就绪即进入 Web；在 Web 里返回到底会回到桌面。环境需要维护或启动失败时仍进入主界面。',
    pluginNote: '要回到主界面，在这里选「主界面」即可，不需要禁用插件。禁用 dsh-launch-target 只会移除这个面板，已选的启动目标保持不变。',
    main: '主界面',
    web: 'Web 界面',
    loading: '正在读取…',
    saving: '正在保存…',
    saved: '已保存，下次从桌面图标启动时生效。',
    unsupported: '当前 App 版本不支持此设置，请更新 DSHA。',
    denied: '登录状态已失效，请刷新页面后重试。',
    failed: '未能读取或保存，已保留原设置。可稍后重试，或在 App 的「设置 → 运行配置」中修改。',
    retry: '重试'
  };
  const en = {
    nav: 'Launch target',
    title: 'Open from the app icon',
    hint: 'Applies the next time you open DSHA from the app icon. With Web selected, the icon shows a loading screen and opens Web once it is ready; going back from the first Web page returns to the home screen. Maintenance or startup failures still open the main screen.',
    main: 'Main screen',
    web: 'Web',
    loading: 'Loading…',
    saving: 'Saving…',
    saved: 'Saved. Takes effect the next time you open DSHA from the app icon.',
    unsupported: 'This app version does not support this setting. Update DSHA.',
    denied: 'Your session has expired. Refresh the page and try again.',
    pluginNote: 'To go back to the main screen, choose Main screen here. You do not need to disable the plugin. Disabling dsh-launch-target only removes this panel and keeps the selected launch target.',
    failed: 'Could not read or save the setting; the previous value was kept. Try again later, or change it in the app under Settings → Runtime.',
    retry: 'Retry'
  };

  const NARROW = '.dshaLt_row{flex-direction:column;align-items:stretch}'
    + '.dshaLt_label,.dshaLt_control{flex:0 0 auto;min-width:0}'
    + '.dshaLt_control .dshaLt_segments{display:grid;width:100%;box-sizing:border-box}';
  const css = [
    '.dshaLt_root{display:grid;gap:8px;container-type:inline-size}',
    '.dshaLt_section{display:grid;gap:12px}',
    '.dshaLt_sectionTitle{margin:0;color:var(--dsw-alias-label-primary);font-size:var(--dsh-content-font-size-primary,15px);line-height:1.4;font-weight:600}',
    '.dshaLt_row{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px 16px}',
    '.dshaLt_label{color:var(--dsw-alias-label-primary);font-size:var(--dsh-content-font-size-secondary,13px);line-height:1.5;font-weight:500;flex:1 1 180px;min-width:0}',
    '.dshaLt_control{flex:0 1 260px;min-width:200px}',
    '.dshaLt_hint{margin:0;color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:1.5}',
    '.dshaLt_status{margin:0;color:var(--dsw-alias-label-secondary);font-size:12px;line-height:1.5;min-height:18px}',
    '.dshaLt_status[data-tone=error]{color:var(--dsw-alias-danger,var(--dsw-alias-label-secondary))}',
    '.dshaLt_retry{appearance:none;border:0;background:none;padding:0 0 0 6px;color:var(--dsw-alias-link);font:inherit;cursor:pointer}',
    '.dshaLt_retry:focus-visible{outline:2px solid var(--dsw-alias-brand-primary,currentColor);outline-offset:2px;border-radius:4px}',
    // 窄屏（手机竖屏、分屏）上下排列，选择器占满整行。
    // 竖排时主轴变成高度：标签与选择器都必须改回按内容取高，否则标签的 180px 横向基准
    // 会变成 180px 高的空白；分段控件原语是 inline-grid，需要显式撑满整行。
    '@container (max-width:420px){' + NARROW + '}',
    '@supports not (container-type:inline-size){@media (max-width:599px){' + NARROW + '}}',
    // 触屏：放大分段按钮的触控高度，不改变 DSH 原语的配色。
    '@media (pointer:coarse){.dshaLt_control [role=tab]{min-height:40px}}'
  ].join('');
  const STYLE_ID = 'dsh-launch-target/card.css';
  if (typeof document !== 'undefined'
      && document.querySelector('style[data-plugin-css=' + JSON.stringify(STYLE_ID) + ']') === null) {
    const tag = document.createElement('style');
    tag.dataset.plugin = KEY;
    tag.dataset.pluginCss = STYLE_ID;
    tag.textContent = css;
    document.head.appendChild(tag);
  }

  async function call(method, target) {
    const response = await fetch(ROUTE, method === 'GET'
      ? { method: 'GET', credentials: 'same-origin', cache: 'no-store' }
      : { method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ target }) });
    const payload = await response.json().catch(() => null);
    if (response.ok && payload && payload.ok === true && (payload.target === 'main' || payload.target === 'web'))
      return payload.target;
    const code = payload && typeof payload.error === 'string' ? payload.error : 'failed';
    throw Object.assign(new Error(code), { code });
  }

  function message(t, code) {
    if (code === 'unsupported') return t('unsupported');
    if (code === 'access-denied') return t('denied');
    return t('failed');
  }

  function LaunchTargetCard(props) {
    const t = props.t;
    const id = React.useId();
    const [state, setState] = React.useState({ target: undefined, phase: 'loading', error: undefined });
    const request = React.useRef(0);
    const load = React.useCallback(() => {
      const serial = ++request.current;
      setState((previous) => ({ ...previous, phase: 'loading', error: undefined }));
      call('GET').then(
        (target) => { if (serial === request.current) setState({ target, phase: 'idle', error: undefined }); },
        (error) => { if (serial === request.current) setState((previous) => ({ ...previous, phase: 'idle', error: error.code })); });
    }, []);
    React.useEffect(() => { load(); return () => { request.current++; }; }, [load]);
    const choose = (target) => {
      if (state.phase !== 'idle' || target === state.target) return;
      const serial = ++request.current;
      const previous = state.target;
      setState({ target, phase: 'saving', error: undefined });
      call('POST', target).then(
        (saved) => { if (serial === request.current) setState({ target: saved, phase: 'saved', error: undefined }); },
        // 回到宿主原值：界面不能显示一个没有保存成功的选择。
        (error) => { if (serial === request.current) setState({ target: previous, phase: 'idle', error: error.code }); });
    };
    const busy = state.phase === 'loading' || state.phase === 'saving';
    const unknown = state.target === undefined;
    const status = state.error !== undefined ? message(t, state.error)
      : state.phase === 'loading' ? t('loading')
        : state.phase === 'saving' ? t('saving')
          : state.phase === 'saved' ? t('saved') : '';
    return jsx.jsxs('div', {
      className: 'dshaLt_root',
      'data-dsha-launch-target': state.target ?? 'unknown',
      children: [
        jsx.jsxs('div', {
          className: 'dshaLt_row',
          children: [
            jsx.jsx('span', { id: id + '-label', className: 'dshaLt_label', children: t('title') }),
            jsx.jsx('div', {
              className: 'dshaLt_control',
              'aria-labelledby': id + '-label',
              children: jsx.jsx(primitives.SegmentedControl, {
                id: id + '-target',
                className: 'dshaLt_segments',
                label: t('title'),
                value: state.target ?? 'main',
                disabled: busy || unknown,
                options: [
                  { value: 'main', label: t('main') },
                  { value: 'web', label: t('web') }
                ],
                onChange: choose
              })
            })
          ]
        }),
        jsx.jsx('p', { className: 'dshaLt_hint', children: t('hint') }),
        jsx.jsx('p', { className: 'dshaLt_hint', children: t('pluginNote') }),
        jsx.jsxs('p', {
          className: 'dshaLt_status',
          role: 'status',
          'aria-live': 'polite',
          'data-tone': state.error !== undefined ? 'error' : undefined,
          children: [
            status,
            state.error !== undefined && state.error !== 'unsupported'
              ? jsx.jsx('button', { type: 'button', className: 'dshaLt_retry', onClick: load, children: t('retry') })
              : null
          ]
        })
      ]
    });
  }

  /** 设置导航里的独立面板：标题 + 同一张卡片，读写同一路由，值仍以原生 App 为准。 */
  function LaunchTargetSection(props) {
    return jsx.jsxs('section', {
      className: 'dshaLt_section',
      'aria-label': props.t('nav'),
      'data-dsha-launch-target-section': '',
      children: [
        jsx.jsx('h2', { className: 'dshaLt_sectionTitle', children: props.t('nav') }),
        jsx.jsx(LaunchTargetCard, { t: props.t })
      ]
    });
  }

  const inject = ['slots', 'locale'];
  function apply(ctx) {
    const t = ctx.locale.bind(NS);
    ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'dsh-launch-target: dictionaries');
    ctx.effect(() => ctx.slots.inject('plugins.bundle.config', () => ctx.slots.register({
      name: 'plugins.bundle.config',
      key: KEY,
      locale: NS,
      inject: () => ({ t })
    }, LaunchTargetCard)), 'dsh-launch-target: settings card');
    ctx.effect(() => ctx.slots.inject('settings.section', () => ctx.slots.register({
      name: 'settings.section',
      id: SECTION_ID,
      order: SECTION_ORDER,
      label: () => t('nav'),
      locale: NS,
      inject: () => ({ t })
    }, LaunchTargetSection)), 'dsh-launch-target: settings section');
  }
  return { NS, SECTION_ID, inject, apply, LaunchTargetCard, LaunchTargetSection };
}});
