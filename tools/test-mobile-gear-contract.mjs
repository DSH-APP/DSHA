// DSHA_GEAR_FIT_V1（T61）：移动壳侧栏 footer 的齿轮让位契约。
// 目标：**宿主 ConnectionIndicator 不渲染时，契约仍然成立**——齿轮可见性只由实测行宽决定，
// 不再读宿主节点的身份；行内空间不足时齿轮让位、账户菜单里的设置项保留（始终只有一条设置入口）。
// 用最小 DOM 语义跑真实产物（app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js），
// 不启动浏览器、不连接设备。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';

const root = path.resolve(import.meta.dirname, '..');
const asset = path.join(root, 'app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js');
const source = fs.readFileSync(asset, 'utf8');

class FakeElement {
  constructor(tag) {
    this.tagName = tag;
    this.children = [];
    this.attributes = new Map();
    this.dataset = {};
    this.listeners = new Map();
    this.parentElement = null;
    this.width = 0;
    this.computed = {
      display: 'inline-flex',
      visibility: 'visible',
      paddingLeft: '0px',
      paddingRight: '0px',
      marginLeft: '0px',
      marginRight: '0px',
    };
  }
  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }
  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }
  hasAttribute(name) {
    return this.attributes.has(name);
  }
  removeAttribute(name) {
    this.attributes.delete(name);
  }
  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }
  remove() {
    if (this.parentElement) {
      const at = this.parentElement.children.indexOf(this);
      if (at >= 0) this.parentElement.children.splice(at, 1);
      this.parentElement = null;
    }
  }
  addEventListener(type, handler) {
    this.listeners.set(type, handler);
  }
  getBoundingClientRect() {
    return {width: this.width, height: 20};
  }
  set innerHTML(value) {
    this.markup = value;
  }
  get innerHTML() {
    return this.markup ?? '';
  }
  querySelector() {
    return null;
  }
  querySelectorAll() {
    return [];
  }
  get isConnected() {
    return true;
  }
}

function createHarness() {
  const frame = new FakeElement('div');
  frame.setAttribute('data-mobile-nav', 'frame');
  const row = new FakeElement('div');
  frame.appendChild(row);
  frame.querySelector = (selector) => (selector === ROW_SELECTOR ? row : null);
  const document = {
    documentElement: new FakeElement('html'),
    querySelector: (selector) => (selector === '[data-mobile-nav="frame"]' ? frame : null),
    querySelectorAll: () => [],
    createElement: (tag) => new FakeElement(tag),
    // 3.3.1/3.3.5 的 drawer-quick-settings 首次 ensure 会挂行内按下态监听（bindRowHook）；
    // 本契约只关心几何让位，监听本身用 no-op 桩即可。
    addEventListener: () => {},
    removeEventListener: () => {},
  };
  const sandbox = {console, document, getComputedStyle: (element) => element.computed};
  sandbox.window = {__ModuleLoader__: {load: ({factory}) => { sandbox.loaded = factory(() => ({})); }}};
  sandbox.globalThis = sandbox;
  sandbox.setTimeout = setTimeout;
  sandbox.clearTimeout = clearTimeout;
  sandbox.requestAnimationFrame = (run) => setTimeout(run, 0);
  sandbox.cancelAnimationFrame = clearTimeout;
  vm.createContext(sandbox);
  // 暴露内部 require，才能直接取到 effects/drawer-quick-settings.js 的导出。
  vm.runInContext(
    source.replace('var __cache = {};', 'globalThis.__mobileRequire = __localRequire; var __cache = {};'),
    sandbox,
    {filename: asset},
  );
  const mod = sandbox.__mobileRequire('./effects/drawer-quick-settings.js');
  const locale = {
    bind: () => (key) => key,
    subscribe: () => () => {},
  };
  const task = mod.createDrawerQuickSettingsTask({locale});
  return {row, task, document, mod, sandbox};
}

const ROW_SELECTOR = '[class*="sidebarCol"] [class*="triggerRow"]';

function launcher(width) {
  const element = new FakeElement('button');
  element.setAttribute('aria-haspopup', 'menu');
  element.width = width;
  return element;
}

function pill(width) {
  const element = new FakeElement('div');
  element.setAttribute('role', 'status');
  element.width = width;
  return element;
}

function gearWrap(row) {
  return row.children.find((child) => child.dataset.mobileNav === 'quick-settings-wrap') ?? null;
}

test('产物不再按宿主指示灯是否存在决定齿轮让位', () => {
  assert.ok(!source.includes("child.getAttribute('data-phase')"), '旧的指示灯存在判据仍在产物里');
  assert.ok(!source.includes('[class*="triggerRow"]:has('), '旧的 :has() 连接条守卫仍在产物里');
  assert.match(source, /data-mobile-nav-quick-fit="0"/);
  assert.match(source, /exports\.dshaGearFits = dshaGearFits;/);
  assert.match(source, /const keep = trigger !== null && trigger\.getAttribute\('aria-expanded'\) === 'true' && gearVisible;/);
});

test('纯判据：只按剩余宽度决定，宽度不可用时保守让位', () => {
  const {dshaGearFits, GEAR_SLOT_PX} = createHarness().mod;
  assert.equal(GEAR_SLOT_PX, 40);
  assert.equal(dshaGearFits(260, 110), true, '没有连接条时应放得下');
  assert.equal(dshaGearFits(260, 240), false, '连接条占用 140px 时放不下');
  assert.equal(dshaGearFits(260, 220), true, '余量正好 40px 视为放得下');
  assert.equal(dshaGearFits(260, 221), false, '余量 39px 不够');
  assert.equal(dshaGearFits(0, 0), false, '布局未就绪按放不下处理');
  assert.equal(dshaGearFits(Number.NaN, 0), false);
  assert.equal(dshaGearFits(260, -1), false);
});

test('没有指示灯时 ensure 正常注入齿轮并标记放得下', () => {
  const {row, task} = createHarness();
  row.width = 260;
  row.appendChild(launcher(110));
  task.ensure();
  const wrap = gearWrap(row);
  assert.ok(wrap !== null, '齿轮没有注入');
  assert.equal(wrap.parentElement, row);
  assert.equal(wrap.dataset.mobileNavQuickFit, '1');
  assert.equal(row.children.length, 2);
});

test('指示灯出现后按实测宽度让位，消失后恢复', () => {
  const {row, task} = createHarness();
  row.width = 260;
  row.appendChild(launcher(110));
  task.ensure();
  assert.equal(gearWrap(row).dataset.mobileNavQuickFit, '1');

  const indicator = pill(140);
  row.appendChild(indicator);
  task.ensure();
  assert.equal(gearWrap(row).dataset.mobileNavQuickFit, '0', '指示灯占用行宽后齿轮应让位');

  indicator.remove();
  task.ensure();
  assert.equal(gearWrap(row).dataset.mobileNavQuickFit, '1', '指示灯消失后齿轮应恢复');
});

test('行宽不足以容纳齿轮时保守让位（保留账户菜单入口）', () => {
  const {row, task} = createHarness();
  row.width = 240;
  row.appendChild(launcher(210));
  task.ensure();
  assert.equal(gearWrap(row).dataset.mobileNavQuickFit, '0');
});

test('重复 ensure 幂等：同一结论不重写属性', () => {
  const {row, task} = createHarness();
  row.width = 260;
  row.appendChild(launcher(110));
  task.ensure();
  const wrap = gearWrap(row);
  const before = Object.keys(wrap.dataset).length;
  task.ensure();
  assert.equal(row.children.filter((child) => child === wrap).length, 1, '齿轮被重复注入');
  assert.equal(Object.keys(wrap.dataset).length, before);
});
