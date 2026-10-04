#!/usr/bin/env node
import {testRuntime} from './test-runtime-fixture.mjs';
// 只加载当前锁定 DSH 的 React/DOM 与 ui-chat，不连接真实账户，不创建设备会话。
import fs from 'node:fs';
import { browserFixture } from './rc1-browser-fixture.mjs';

const runtime = testRuntime('raw');
const fixture = await browserFixture(runtime);
const errors = [];
fixture.page.on('pageerror', error => errors.push(error.message));
fixture.page.on('console', message => {
  if (message.type() === 'error' && !message.location().url.endsWith('/favicon.ico'))
    errors.push(message.text());
});
try {
  await fixture.load('dsh-client-ui-chat', [], 'module.exports.audit = {ChatNodeList};');
  const result = await fixture.page.evaluate(async () => {
    const React = auditModules.react.default || auditModules.react;
    const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
    const primitives = auditModules['@deepseek-ai/dsh-client-ui-primitives'];
    const ChatNodeList = auditExports['@deepseek-ai/dsh-client-ui-chat'].audit.ChatNodeList;
    const host = document.createElement('div');
    host.style.cssText = 'height:760px;overflow:auto';
    document.body.append(host);
    const nodes = {}, entries = [];
    for (let i = 0; i < 100; i++) {
      const key = `node-${i}`, kind = i % 5 === 0 ? 'user' : 'assistant';
      entries.push({kind: 'node', key});
      nodes[key] = {key, kind, anchorSeq: i + 1,
        data: {text: `${kind} ${i}\n\nCODE BLOCK ${i}\n${'streamed content '.repeat(8)}`},
        location: {kind: 'turn', turn: {turn: i, status: 'closed', data: {}}}};
    }
    const props = {
      entries, pendingInputs: [], nodeStore: {get: key => nodes[key]},
      useChatGroup: () => undefined, useChatNode: key => nodes[key],
      useChatNodeProcess: () => undefined, usePresentation: selector => selector({foldCompletedTurns: false}),
      useStore: selector => selector({}), actions: {setTurnProcessOpen() {}}, cwd: '',
      openFile() {}, openSkill() {}, inspectCall() {}, forkAt() {}, loadImage() {},
      renderMessageImages: () => null, fileMentions() {},
      renderSlot: (_name, owner) => React.createElement(primitives.MarkdownText,
        {text: owner.node.data.text, streaming: false, labels: {}}), t: key => key
    };
    let app = ReactDOM.createRoot(host);
    const samples = [];
    for (let i = 0; i < 6; i++) {
      const started = performance.now();
      app.render(React.createElement(ChatNodeList, props));
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      samples.push(performance.now() - started);
      if (i < 5) { app.unmount(); host.replaceChildren(); app = ReactDOM.createRoot(host); }
    }
    return {samples, flowRows: host.querySelectorAll('[data-chat-flow-key]').length,
      htmlBytes: host.innerHTML.length};
  });
  if (result.flowRows !== 100) throw new Error(`chat rows lost after remount: ${result.flowRows}`);
  if (errors.length) throw new Error(errors.join('\n'));
  const budget = Number(process.env.DSHA_CHAT_RENDER_BUDGET_MS || 3000);
  if (!Number.isFinite(budget) || budget <= 0) throw new Error('Invalid render budget');
  if (Math.max(...result.samples) > budget)
    throw new Error(`100-message mount exceeded ${budget}ms: ${result.samples.join(', ')}`);
  console.log(JSON.stringify({runtime, scenario: '100 messages; code/stream placeholders; 6 mount/unmount cycles', ...result}, null, 2));
} finally { await fixture.close(); }
