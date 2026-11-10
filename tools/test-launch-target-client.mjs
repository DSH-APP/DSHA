#!/usr/bin/env node
// dsh-launch-target 网页端：设置导航里有独立「启动目标」面板，插件详情卡片保留，
// 两处共用同一组件、同一路由；文案说明禁用插件不改变启动目标。不连接设备或桥。
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGIN = join(ROOT, 'app/src/main/assets/builtin-plugins/dsh-launch-target');
const source = readFileSync(join(PLUGIN, 'lib/client.js'), 'utf8');
const manifest = JSON.parse(readFileSync(join(PLUGIN, 'package.json'), 'utf8'));

let loaded;
const element = (type, props) => ({ type, props });
const sandbox = {
  window: { __ModuleLoader__: { load: (entry) => { loaded = entry; } } },
  document: undefined
};
vm.runInNewContext(source, sandbox, { filename: 'client.js' });
assert.equal(loaded.id, 'dsh-launch-target');
const mod = loaded.factory((name) => {
  if (name === 'react') return {};
  if (name === 'react/jsx-runtime') return { jsx: element, jsxs: element };
  if (name === '@deepseek-ai/dsh-client-ui-primitives') return { SegmentedControl: 'SegmentedControl' };
  throw new Error('unexpected require ' + name);
});

// 收集 apply 注册的全部 slot。
const registered = [];
const dictionaries = {};
const disposers = [];
const ctx = {
  effect: (fn) => { disposers.push(fn()); },
  locale: {
    register: (ns, dict) => { dictionaries[ns] = dict; return () => {}; },
    bind: (ns) => (key) => dictionaries[ns].zh[key]
  },
  slots: {
    inject: (name, fn) => fn(),
    register: (options, component) => { registered.push({ options, component }); return () => {}; }
  }
};
mod.apply(ctx);

const card = registered.find((r) => r.options.name === 'plugins.bundle.config');
const section = registered.find((r) => r.options.name === 'settings.section');
assert.ok(card, '插件详情页卡片仍然注册');
assert.equal(card.options.key, 'dsh-launch-target');
assert.ok(section, '设置导航注册独立面板');
assert.equal(section.options.id, mod.SECTION_ID);
assert.equal(typeof section.options.order, 'number');
assert.ok(section.options.order > 0, '排在「通用」之后');
assert.equal(section.options.label(), '启动目标');
assert.equal(section.component, mod.LaunchTargetSection);

// 面板内嵌同一张卡片，传入同一翻译函数。
const t = section.options.inject().t;
const tree = mod.LaunchTargetSection({ t });
const children = tree.props.children;
assert.ok(children.some((c) => c && c.type === mod.LaunchTargetCard), '面板复用 LaunchTargetCard');

// 中英文案齐全，说明禁用插件不改变启动目标。
const { zh, en } = dictionaries['dsha.launchTarget'];
assert.deepEqual(Object.keys(zh).sort(), Object.keys(en).sort());
assert.match(zh.pluginNote, /不需要禁用插件/);
assert.match(en.pluginNote, /do not need to disable/);
assert.ok(source.includes("const ROUTE = '/api/dsha-launch-target'"));

// settings.section 由设置底座声明，客户端须注入它。
assert.ok(manifest.dsh.client.inject.includes('@deepseek-ai/dsh-client-ui-settings'));
assert.match(manifest.description, /disabling the plugin keeps the chosen target/);

console.log('launch-target client: ok');
