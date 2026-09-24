#!/usr/bin/env node
// 只加载锁定 rc1 的 React/DOM 与 ui-chat，不连接真实账户，不创建设备会话。
import fs from 'node:fs';
import { browserFixture } from './rc1-browser-fixture.mjs';

const runtime = process.env.DSHA_TEST_RUNTIME || JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json', 'utf8')).raw;
const fixture = await browserFixture(runtime);
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
      htmlBytes: host.innerHTML.length, errors: []};
  });
  if (result.flowRows !== 100) throw new Error(`chat rows lost after remount: ${result.flowRows}`);
  if (result.errors.length) throw new Error(result.errors.join('\n'));
  console.log(JSON.stringify({runtime, scenario: '100 messages; code/stream placeholders; 6 mount/unmount cycles', ...result}, null, 2));
} finally { await fixture.close(); }
