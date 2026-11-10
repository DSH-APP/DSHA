import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
// 生产不再回退 3090（build280）：CI 主机没有 /root/.dsh/.bridge_port，显式发布一个夹具端口；本用例不断言端口。
process.env.DSHA_BRIDGE_PORT ??= '3090';
const server = require('../../../app/src/main/assets/builtin-plugins/dsh-computer-use-android/lib/server.cjs');

test('explicit phone task begin/finish transport supplies only the actual caller identity', async () => {
  const requests = [];
  const dependencies = { fs: { async readFile() { return 'local-fixture-token'; } }, fetch: async (url, options) => {
    requests.push({ url, options });
    return Response.json({ result: JSON.stringify({ ok: true, state: 'COMPLETED', needsObservation: true }) });
  } };
  const task = { sessionId: 'actual-full-session', turnId: '17', completedTurnId: '16' };
  const begin = await server.call('android_control', { action: 'begin' }, new AbortController().signal, dependencies, task);
  assert.equal(begin.isError, false);
  assert.equal(requests[0].url.pathname, '/app/ui/task-begin');
  assert.equal(requests[0].url.searchParams.get('taskSessionId'), task.sessionId);
  assert.equal(requests[0].url.searchParams.get('turnId'), task.turnId);
  assert.equal(requests[0].url.searchParams.get('completedTurnId'), task.completedTurnId);
  assert.equal(requests[0].url.searchParams.has('action'), false);
  assert.equal(requests[0].options.headers['X-Token'], 'local-fixture-token');
  await server.call('android_control', { action: 'finish' }, new AbortController().signal, dependencies, task);
  assert.equal(requests[1].url.pathname, '/app/ui/task-end');
  assert.equal(requests.length, 2);
  const schema = server.tools.find(tool => tool.name === 'android_control').inputSchema;
  assert.deepEqual(Object.keys(schema.properties), ['action']);
  assert.deepEqual(schema.properties.action.enum, ['begin', 'finish']);
});

test('phone task control rejects absent caller metadata and model-supplied identities before any native request', async () => {
  let native = 0;
  const dependencies = { fs: { async readFile() { native++; return 'token'; } }, fetch: async () => { native++; } };
  await assert.rejects(server.call('android_control', { action: 'begin' }, undefined, dependencies), /SCREEN_TASK_IDENTITY_REQUIRED/);
  await assert.rejects(server.call('android_control', { action: 'begin', taskSessionId: 'invented' }, undefined, dependencies,
    { sessionId: 'actual', turnId: '7' }), /UNKNOWN_ARGUMENT/);
  assert.equal(native, 0);
});
