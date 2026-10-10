// 冷启动页面侧回归：注入脚本必须
//  1. 在外壳出现后、插件仍逐个 apply 的窗口里给出不挡操作的进度；
//  2. 合并启动期的 DOM 抖动，少做全场扫描；
//  3. 在任何注入失败时静默放弃，绝不能拖垮页面启动，也不能污染启动诊断。
// 纯源码夹具：用确定性的假 DOM / 假定时器执行真正的
// app/src/main/assets/web-integration/startup.js，不依赖浏览器或真机。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const root = path.resolve(import.meta.dirname, '..');
const source = fs.readFileSync(
    path.join(root, 'app/src/main/assets/web-integration/startup.js'), 'utf8');

const flush = () => new Promise((resolve) => setImmediate(resolve));

function element(tag) {
  const node = {
    tagName: tag,
    children: [],
    attributes: {},
    textContent: '',
    parentNode: null,
    style: {},
    setAttribute(name, value) { this.attributes[name] = String(value); },
    getAttribute(name) { return name in this.attributes ? this.attributes[name] : null; },
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; },
    removeChild(child) {
      const at = this.children.indexOf(child);
      if (at >= 0) this.children.splice(at, 1);
      child.parentNode = null;
      return child;
    },
    dispatchEvent() { return true; },
    getClientRects() { return []; },
  };
  Object.defineProperty(node.style, 'cssText',
      { get() { return this._t || ''; }, set(value) { this._t = value; } });
  return node;
}

function environment(options = {}) {
  const timers = new Map();
  let nextTimer = 1;
  let now = 0;
  const listeners = Object.create(null);
  const windowErrors = [];

  const body = element('body');
  const rootNode = element('div');
  const boot = options.bootScreen === undefined
      ? null
      : Object.assign(element('div'), { textContent: options.bootScreen });
  const composer = { current: options.composer === undefined ? null : element('textarea') };
  let scans = 0;

  // 真实浏览器里计时器回调抛出的异常会走 window.onerror；夹具照做，这样
  // 「进度条失败必须静默」才是被真正验证的，而不是被夹具吞掉。
  const setTimeout = (fn, delay) => {
    const id = nextTimer++;
    timers.set(id, { at: now + (Number(delay) || 0), fn });
    return id;
  };
  const clearTimeout = (id) => { timers.delete(id); };
  const advance = (ms) => {
    const target = now + ms;
    for (;;) {
      let pick = null;
      for (const [id, timer] of timers) {
        if (timer.at <= target && (pick === null || timer.at < pick[1].at)) pick = [id, timer];
      }
      if (pick === null) break;
      timers.delete(pick[0]);
      now = pick[1].at;
      try {
        pick[1].fn();
      } catch (error) {
        windowErrors.push(error);
        for (const listener of listeners.error || []) listener({ error, message: String(error.message) });
      }
    }
    now = target;
  };

  const document = {
    body,
    head: element('head'),
    documentElement: element('html'),
    createElement: (tag) => element(tag),
    getElementById: (id) => (id === 'root' ? (rootNode.children.length ? rootNode : null) : null),
    querySelector(selector) {
      if (selector === '[data-dsh-boot]') { scans++; return boot; }
      if (selector === '[data-composer-input]') return composer.current;
      return null;
    },
    querySelectorAll: () => [],
    addEventListener() {},
  };

  const observers = [];
  class MutationObserver {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe() {}
    disconnect() { this.disconnected = true; }
  }

  const info = [];
  const events = [];
  const loaders = [];
  const window = {
    __DSHA_PAGE_BINDING__: { nonce: 'fixture' },
    __DSHA_LANGUAGE__: options.language || 'zh',
    __ModuleLoader__: { load(definition) { loaders.push(definition); } },
    addEventListener(type, listener) { (listeners[type] ||= []).push(listener); },
    dispatchEvent(event) { events.push(event); return true; },
  };
  if (options.boot === undefined || options.boot === true) {
    window.__DSH_BOOT__ = { rev: 'fixture', entries: new Array(options.plugins === undefined ? 4 : options.plugins), batches: [] };
  }
  window.top = window;

  const context = {
    window,
    document,
    MutationObserver,
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init && init.detail; } },
    console: { info: (line) => info.push(line) },
    setTimeout,
    clearTimeout,
    JSON,
    Object,
    String,
    Math,
    Date,
    Array,
    Boolean,
  };
  if (options.performance !== false) context.performance = { now: () => now };
  context.globalThis = context;
  context.location = { origin: 'http://127.0.0.1:3080', pathname: '/' };
  context.window.location = context.location;
  vm.runInNewContext(source, context, { filename: 'startup.js' });

  const wrap = (definition) => {
    window.__ModuleLoader__.load(definition);
    return loaders[loaders.length - 1].factory(() => { throw new Error('unexpected external'); });
  };

  return {
    window, document, body, root: rootNode, observers, info, events, advance, flush, loaders,
    now: () => now,
    scans: () => scans,
    windowErrors,
    setComposer() { composer.current = element('textarea'); },
    // 复现 dsh-client 的注册与 apply 序列（apply 同步返回）。
    apply(id) { wrap({ id, factory: () => ({ apply() {} }) }).apply(); },
    // 复现「插件 apply 挂住」：报了 loading，永远不报 active。
    applyPending(id) { wrap({ id, factory: () => ({ apply: () => new Promise(() => {}) }) }).apply(); },
    // 可手动结算的 apply，用来验证静默计时与收起时机。
    applyDeferred(id) {
      let settle;
      const promise = new Promise((resolve) => { settle = resolve; });
      wrap({ id, factory: () => ({ apply: () => promise }) }).apply();
      return { settle: async () => { settle(); await flush(); } };
    },
    // 插件初始化抛错：走 issue 分支，pending 归零。
    applyThrowing(id) {
      wrap({ id, factory: () => ({ apply() { throw new Error('plugin failed'); } }) });
      assert.throws(() => loaders[loaders.length - 1].factory(() => { throw new Error('unexpected external'); }).apply());
    },
  };
}

function pageEvents(info) {
  return info.filter((line) => line.startsWith('[DSHA_PAGE] '))
      .map((line) => JSON.parse(line.slice(12)));
}

function bar(env) {
  const strip = env.body.children.find((node) => node.getAttribute('data-dsha-boot-progress') === '1');
  return strip === undefined ? null : strip;
}

function barText(env) {
  const strip = bar(env);
  return strip === null ? null : strip.children[0].textContent;
}

// 1) 250ms 之前不得出现任何节点；插件都在 250ms 内应用完时也不能闪一下再收。
{
  const env = environment();
  assert.equal(env.body.children.length, 0, '页面加载时不得凭空插入节点');
  env.apply('plugin-a');
  assert.equal(bar(env), null, '250ms 之前不该出现进度条');
  env.advance(249);
  assert.equal(bar(env), null);
  env.advance(1);
  assert.equal(bar(env), null, '短启动不得出现进度条');
  env.advance(60000);
  assert.equal(bar(env), null, '短启动之后也不得补闪一次');
}

// 2) 有插件挂住时：250ms 出现、不挡点按、显示已应用/总数。
{
  const env = environment();
  env.applyPending('plugin-a');
  env.advance(249);
  assert.equal(bar(env), null);
  env.advance(1);
  const strip = bar(env);
  assert.ok(strip, '插件仍待应用时必须出现进度条');
  assert.match(strip.style.cssText, /pointer-events:none/, '进度条不得拦截任何点按');
  assert.match(strip.style.cssText, /position:fixed/);
  assert.match(barText(env), /正在启动网页功能 0\/4/, '应显示已应用/总数');
  // 后续插件继续启动时只保留一条，并且计数前进。
  env.apply('plugin-b');
  env.advance(250);
  assert.equal(env.body.children.filter((node) => node.getAttribute('data-dsha-boot-progress') === '1').length, 1);
  assert.match(barText(env), /1\/4/);
}

// 3) 英文界面文案与无 __DSH_BOOT__ 时的降级（只报已应用数）。
{
  const env = environment({ language: 'en', plugins: 2 });
  env.applyPending('plugin-a');
  env.advance(250);
  assert.match(barText(env), /Starting web features 0\/2/);

  const bare = environment({ boot: false });
  bare.applyPending('plugin-a');
  bare.advance(250);
  assert.match(barText(bare), /正在启动网页功能 0$/, '没有引导图时只报已应用数');
}

// 4) 全部插件结算后静默 800ms 自行收起，不需要用户操作。
{
  const env = environment({ plugins: 2 });
  const pending = env.applyDeferred('plugin-a');
  env.advance(250);
  assert.ok(bar(env));
  await pending.settle();
  assert.ok(bar(env), '刚结算时进度条仍在，让用户看到完成');
  env.advance(799);
  assert.ok(bar(env), '800ms 之内不得提前收起');
  env.advance(1);
  assert.equal(bar(env), null, '静默 800ms 后必须自行收起');
}

// 5) 新的插件在静默窗口里启动时，收起计时必须重新开始，不能半路消失。
{
  const env = environment({ plugins: 3 });
  const pending = env.applyDeferred('plugin-a');
  env.advance(250);
  assert.ok(bar(env));
  env.advance(700);
  env.applyPending('plugin-b');
  env.advance(700);
  assert.ok(bar(env), '还有插件待应用时不得收起');
  await pending.settle();
  assert.match(barText(env), /1\/3/);
  env.advance(700);
  assert.ok(bar(env), '仍有插件挂住时进度条必须留着');
  env.advance(30000);
  assert.equal(bar(env), null, '硬上限兜底后不得永久占屏');
}

// 6) 插件卡死时的硬上限：30s 之内不许提前收，到了必须无条件收。
{
  const env = environment({ plugins: 10 });
  env.applyPending('plugin-a');
  env.advance(250);
  assert.ok(bar(env), '挂住的插件仍应显示进度');
  env.advance(29999);
  assert.ok(bar(env), '30s 之内不该提前收起');
  env.advance(1);
  assert.equal(bar(env), null, '30s 后必须无条件收起');
}

// 7) ready（外壳可见）与进度解耦：composer 常年先于插件出现，这正是本 issue 的
//    症状，ready 绝不能顺手关掉进度条。
{
  const env = environment({ composer: true });
  assert.equal(pageEvents(env.info).filter((event) => event.type === 'ready').length, 1,
      'composer 已在时必须立刻报 ready');
  env.applyPending('plugin-a');
  env.advance(250);
  assert.ok(bar(env), 'ready 先到不得关掉进度条');
  env.advance(29999);
  assert.ok(bar(env), 'ready 不得改变硬上限之前的收留行为');
  env.advance(1);
  assert.equal(bar(env), null, '进度条仍按自己的硬上限收起');
}
{
  const env = environment();
  assert.equal(pageEvents(env.info).filter((event) => event.type === 'ready').length, 0);
  env.applyPending('plugin-a');
  env.advance(250);
  const observer = env.observers[env.observers.length - 1];
  env.setComposer();
  observer.callback([]);
  env.advance(1);
  assert.equal(pageEvents(env.info).filter((event) => event.type === 'ready').length, 1, 'ready 只报一次');
  assert.ok(bar(env), 'ready 不得顺手收起进度条');
  env.advance(29998);
  assert.ok(bar(env), '仍在等待插件时不得收起');
  env.advance(1);
  assert.equal(bar(env), null, '硬上限到时收起');
}

// 8) 合并启动期抖动：一个宏任务里只做一次全场扫描，判据不变。
{
  const env = environment({ bootScreen: 'Failed to load plugins: fixture' });
  const observer = env.observers[env.observers.length - 1];
  const baseline = env.scans();
  for (let i = 0; i < 200; i++) observer.callback([]);
  assert.equal(env.scans(), baseline, 'mutation 回调本身不得直接扫描');
  env.advance(1);
  assert.equal(env.scans(), baseline + 1, '一个批次只扫描一次');
  env.advance(1000);
  assert.equal(env.scans(), baseline + 1, '没有新批次就不得再扫描');
  const failures = pageEvents(env.info).filter((event) => event.type === 'issue' && event.fatal);
  assert.equal(failures.length, 1, '启动失败屏仍必须被识别，且只报一次');
  assert.match(failures[0].message, /Failed to load plugins/);
}

// 9) 每条事件带本机时间戳，客户端启动时间线才可量化。
{
  const env = environment();
  env.apply('plugin-a');
  const events = pageEvents(env.info);
  assert.deepEqual(events.map((event) => event.type), ['loading', 'active']);
  for (const event of events) assert.equal(typeof event.at, 'number');
  assert.ok(events[1].at >= events[0].at);
}
{
  // 没有 performance 的老内核：时间戳退化为 0，但绝不抛异常。
  const env = environment({ performance: false });
  env.apply('plugin-a');
  const events = pageEvents(env.info);
  assert.deepEqual(events.map((event) => event.at), [0, 0]);
  assert.deepEqual(env.windowErrors, []);
}

// 10) 页面重渲染重复 apply：不得重复计数，也不得重复报告。
{
  const env = environment({ plugins: 2 });
  env.applyPending('plugin-a');
  env.applyPending('plugin-a');
  env.advance(250);
  assert.deepEqual(pageEvents(env.info).map((event) => event.type), ['loading']);
  assert.match(barText(env), /0\/2/, '同一插件的重复 apply 只算一次');
}

// 11) 插件初始化失败：pending 归零，进度条照常按静默计时收起。
{
  const env = environment({ plugins: 2 });
  env.applyThrowing('plugin-a');
  env.advance(250);
  assert.equal(bar(env), null, '已经结算完的插件不该留下进度条');
  assert.deepEqual(pageEvents(env.info).map((event) => event.type), ['loading', 'issue']);
}

// 12) 注入失败必须静默：DOM 拒绝创建节点时页面启动照常，也不能冒出一条
//     污染启动诊断的 issue 事件。
{
  const env = environment();
  env.document.createElement = () => { throw new Error('dom unavailable'); };
  env.applyPending('plugin-a');
  env.advance(250);
  assert.equal(bar(env), null);
  assert.deepEqual(env.windowErrors, [], '进度条失败不得把异常抛给页面');
  assert.deepEqual(pageEvents(env.info).map((event) => event.type), ['loading'],
      '进度条失败不能吞掉启动事件，也不能自己报错');
  env.applyPending('plugin-b');
  env.advance(250);
  assert.ok(pageEvents(env.info).some((event) => event.id === 'plugin-b'), '后续插件仍必须被报告');
}

// 13) 注入失败的另一条路径：appendChild 失败（旧内核的只读 body）。
{
  const env = environment();
  env.body.appendChild = () => { throw new Error('read-only body'); };
  env.applyPending('plugin-a');
  env.advance(250);
  assert.deepEqual(env.windowErrors, []);
  assert.equal(bar(env), null);
  assert.deepEqual(pageEvents(env.info).map((event) => event.type), ['loading']);
}

console.log('startup boot progress: ok');
