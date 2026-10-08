// 冷启动页面侧回归：注入脚本必须
//  1. 在外壳出现后、插件仍逐个 apply 的窗口里给出不挡操作的进度；
//  2. 合并启动期的 DOM 抖动，少做全场扫描；
//  3. 在任何注入失败时静默放弃，绝不能拖垮页面启动。
// 纯源码夹具：用确定性的假 DOM/假定时器执行真正的 app/src/main/assets/web-integration/startup.js。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';

const root = path.resolve(import.meta.dirname, '..');
const source = fs.readFileSync(
    path.join(root, 'app/src/main/assets/web-integration/startup.js'), 'utf8');

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
  Object.defineProperty(node.style, 'cssText', { get() { return this._t || ''; }, set(value) { this._t = value; } });
  return node;
}

function environment(options = {}) {
  const timers = new Map();
  let nextTimer = 1;
  let now = 0;
  const setTimeout = (fn, delay) => {
    const id = nextTimer++;
    timers.set(id, { at: now + (Number(delay) || 0), fn });
    return id;
  };
  const clearTimeout = (id) => { timers.delete(id); };
  // 只推进到期计时器；同一次推进里新排的计时器按需继续展开。
  const advance = (ms) => {
    const target = now + ms;
    for (;;) {
      let pick = null;
      for (const [id, timer] of timers) if (timer.at <= target && (pick === null || timer.at < pick[1].at)) pick = [id, timer];
      if (pick === null) break;
      timers.delete(pick[0]);
      now = pick[1].at;
      pick[1].fn();
    }
    now = target;
  };

  const body = element('body');
  const rootNode = element('div');
  const head = element('head');
  const boot = options.bootScreen === undefined
      ? null
      : Object.assign(element('div'), { textContent: options.bootScreen });
  const composer = { current: options.composer === undefined ? null : element('textarea') };
  let scans = 0;

  const document = {
    body,
    head,
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
    __DSH_BOOT__: { entries: new Array(options.plugins === undefined ? 4 : options.plugins) },
    __ModuleLoader__: { load(definition) { loaders.push(definition); } },
    addEventListener() {},
    dispatchEvent(event) { events.push(event); return true; },
  };
  window.top = window;

  const context = {
    window,
    document,
    MutationObserver,
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init && init.detail; } },
    console: { info: (line) => info.push(line) },
    performance: { now: () => now },
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
  context.globalThis = context;
  context.location = { origin: 'http://127.0.0.1:3080', pathname: '/' };
  context.window.location = context.location;
  vm.runInNewContext(source, context, { filename: 'startup.js' });

  return {
    window, document, body, root: rootNode, observers, info, events, advance, loaders,
    now: () => now,
    scans: () => scans,
    setComposer: () => { composer.current = element('textarea'); },
    // 复现 dsh-client 的注册与 apply 序列。
    apply(id) {
      const definition = { id, factory: () => ({ apply() {} }) };
      window.__ModuleLoader__.load(definition);
      const transformed = loaders[loaders.length - 1];
      const exports = transformed.factory(() => { throw new Error('unexpected external'); });
      exports.apply();
    },
    // 复现「插件 apply 挂住」：报了 loading，永远不报 active。
    applyPending(id) {
      const definition = { id, factory: () => ({ apply: () => new Promise(() => {}) }) };
      window.__ModuleLoader__.load(definition);
      loaders[loaders.length - 1].factory(() => { throw new Error('unexpected external'); }).apply();
    },
  };
}

function pageEvents(info) {
  return info.filter((line) => line.startsWith('[DSHA_PAGE] '))
      .map((line) => JSON.parse(line.slice(12)));
}

// 1) 首次 loading 后 250ms 才出现进度条：短启动不会被闪一下。
{
  const env = environment();
  assert.equal(env.body.children.length, 0, '页面加载时不得凭空插入节点');
  env.apply('plugin-a');
  assert.equal(env.body.children.length, 0, '250ms 之前不该出现进度条');
  env.advance(250);
  assert.equal(env.body.children.length, 1);
  const strip = env.body.children[0];
  assert.equal(strip.getAttribute('data-dsha-boot-progress'), '1');
  assert.match(strip.style.cssText, /pointer-events:none/, '进度条不得拦截任何点按');
  assert.match(strip.style.cssText, /position:fixed/);
  assert.match(strip.children[0].textContent, /1\/4/, '应显示已应用/总数');

  const quietFrom = env.now();
  env.apply('plugin-b');
  env.advance(250);
  assert.equal(env.body.children.length, 1, '进度条只应存在一份');
  assert.match(env.body.children[0].children[0].textContent, /2\/4/);

  // 2) 最后一次 active 之后静默 800ms 自行消失，不需要用户操作。
  const idleAt = quietFrom + 800;
  env.advance(idleAt - 1 - env.now());
  assert.equal(env.body.children.length, 1);
  env.advance(1);
  assert.equal(env.body.children.length, 0, '插件不再启动后进度条必须消失');
}

// 3) 插件卡住时硬上限兜底：绝不允许永久遮挡。
{
  const env = environment({ plugins: 10 });
  env.applyPending('plugin-a');
  env.advance(250);
  assert.equal(env.body.children.length, 1, '挂住的插件仍应显示进度');
  env.advance(29999);
  assert.equal(env.body.children.length, 1, '30s 之内不该提前收起');
  env.advance(1);
  assert.equal(env.body.children.length, 0, '30s 后必须无条件收起');
}

// 4) ready（外壳可见）与进度条解耦：composer 常年先于插件出现，
//    这正是本 issue 的症状，ready 绝不能顺手关掉进度。
{
  const env = environment({ composer: true });
  assert.equal(pageEvents(env.info).filter((event) => event.type === 'ready').length, 1,
      'composer 已在时必须立刻报 ready');
  env.apply('plugin-a');
  env.advance(250);
  assert.equal(env.body.children.length, 1, 'ready 先到不得永久关掉进度条');
}

// 4b) composer 后到：ready 只报一次，且不影响进度条的正常收起。
{
  const env = environment();
  assert.equal(pageEvents(env.info).filter((event) => event.type === 'ready').length, 0);
  env.apply('plugin-a');
  const eventAt = env.now();
  env.advance(250);
  assert.equal(env.body.children.length, 1);
  const observer = env.observers[env.observers.length - 1];
  env.setComposer();
  observer.callback([]);
  env.advance(1);
  assert.equal(pageEvents(env.info).filter((event) => event.type === 'ready').length, 1, 'ready 只报一次');
  assert.equal(env.body.children.length, 1, 'ready 不得顺手收起进度条');
  env.advance(eventAt + 800 - 1 - env.now());
  assert.equal(env.body.children.length, 1);
  env.advance(1);
  assert.equal(env.body.children.length, 0, '无待启动插件 800ms 后收起');
}

// 5) 合并启动期抖动：一个宏任务里只做一次全场扫描，判据不变。
{
  const env = environment({ bootScreen: 'Failed to load plugins: fixture' });
  const observer = env.observers[env.observers.length - 1];
  const baseline = env.scans();
  for (let i = 0; i < 200; i++) observer.callback([]);
  assert.equal(env.scans(), baseline, '回调本身不得直接扫描');
  const queued = env.scans();
  env.advance(1);
  assert.equal(env.scans(), queued + 1, '一个批次只扫描一次');
  const failure = pageEvents(env.info).find((event) => event.type === 'issue' && event.fatal);
  assert.ok(failure && /Failed to load plugins/.test(failure.message), '启动失败屏仍必须被识别');
}

// 6) 每条事件带本机时间戳，客户端启动时间线才可量化。
{
  const env = environment();
  env.apply('plugin-a');
  const events = pageEvents(env.info);
  assert.deepEqual(events.map((event) => event.type), ['loading', 'active']);
  for (const event of events) assert.equal(typeof event.at, 'number');
  assert.ok(events[1].at >= events[0].at);
}

// 7) 页面重渲染重复 apply：不得重复计数，也不得重复报告。
{
  const env = environment({ plugins: 2 });
  env.apply('plugin-a');
  env.apply('plugin-a');
  env.advance(250);
  const types = pageEvents(env.info).map((event) => event.type);
  assert.deepEqual(types, ['loading', 'active'], '同一插件的重复 apply 只记首次');
  assert.match(env.body.children[0].children[0].textContent, /1\/2/);
}

// 8) 注入失败必须静默：DOM 拒绝创建节点时页面启动照常。
{
  const env = environment();
  env.document.createElement = () => { throw new Error('dom unavailable'); };
  env.apply('plugin-a');
  env.advance(250);
  assert.equal(env.body.children.length, 0);
  assert.deepEqual(pageEvents(env.info).map((event) => event.type), ['loading', 'active'],
      '进度条失败不能吞掉启动事件');
  env.apply('plugin-b');
  assert.ok(pageEvents(env.info).some((event) => event.id === 'plugin-b'), '后续插件仍必须被报告');
}

console.log('startup boot progress: ok');
