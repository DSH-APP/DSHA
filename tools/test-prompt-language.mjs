// Locked current prompt assembly + real loopback HTTP; no model/device or user profile writes.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire, syncBuiltinESMExports } from 'node:module';
import { pathToFileURL } from 'node:url';

const root = path.resolve(import.meta.dirname, '..');
const runtime = process.env.DSHA_TEST_RUNTIME
  || JSON.parse(fs.readFileSync(path.join(root, 'app/build/test-runtimes/current.json'), 'utf8')).raw;
// The raw fixture follows the formal runtime lock, not the independently pinned
// recovery runtime (which stays on rc3 for rescue and is not the module tree here).
const expectedVersion = JSON.parse(fs.readFileSync(path.join(root, 'tools/dsh-runtime/package.json'), 'utf8'))
  .dependencies['@deepseek-ai/dsh'];
assert.equal(JSON.parse(fs.readFileSync(path.join(runtime, 'node_modules/@deepseek-ai/dsh/package.json'), 'utf8')).version, expectedVersion);
const requireRuntime = createRequire(path.join(runtime, 'package.json'));
const load = name => import(pathToFileURL(requireRuntime.resolve(name)).href);
const { Context } = await load('@deepseek-ai/cordis');
const { default: SystemPrompt } = await load('@deepseek-ai/dsh-system-prompt');
const { createScope } = await load('@deepseek-ai/dsh-scope');
const sourcePath = path.join(root, 'app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js');
const source = fs.readFileSync(sourcePath, 'utf8')
  .replace("'@deepseek-ai/dsh-llm'", JSON.stringify(pathToFileURL(requireRuntime.resolve('@deepseek-ai/dsh-llm')).href))
  .replace("'./bridge-port.cjs'", JSON.stringify(pathToFileURL(path.join(path.dirname(sourcePath), 'bridge-port.cjs')).href));
const guide = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const recoverySource = fs.readFileSync(path.join(root, 'app/src/main/assets/recovery-agent.js'), 'utf8')
  .replace("'@deepseek-ai/dsh-tools'", JSON.stringify(pathToFileURL(requireRuntime.resolve('@deepseek-ai/dsh-tools')).href));
const recovery = await import(`data:text/javascript;base64,${Buffer.from(recoverySource).toString('base64')}`);

/**
 * T36 约束清单：设备指引与两个屏幕 MCP 的说明去重后，每条安全约束必须仍在且只放在一处归属位置。
 * 位置：guide=常驻设备指引；android/vscreen=对应 MCP instructions；android:<tool>/vscreen:<tool>=该工具描述。
 * 改写措辞时同步更新片段，但不得删除条目；总长度上限防止各段重新膨胀。
 */
const SCREEN_CONSTRAINTS = [
  // 通用 / 跨屏（常驻指引）
  ['explicit-intent', 'guide', '只有用户在当前对话明确要求操控手机才开始'],
  ['no-casual-trigger', 'guide', '普通讨论、引用、翻译或未明确要求执行时不要调用'],
  ['no-keyword-intent', 'guide', '不能靠关键词判断意图'],
  ['begin-even-launch', 'guide', 'android_control({action:"begin"})（即使只启动应用）'],
  ['finish-once', 'guide', '完成或放弃时在收尾调用一次'],
  ['finish-keeps-chat', 'guide', '不取消模型对话，本轮不再重新 begin'],
  ['transport-identity', 'guide', '会话与轮次由传输层绑定'],
  ['no-new-chat', 'guide', '不新建聊天，不只回复教程或让用户去工作区开助手'],
  ['curl-task-begin-end', 'guide', '/app/ui/task-begin 与 /app/ui/task-end'],
  ['curl-identity-env', 'guide', 'taskSessionId=$DSH_PHONE_SESSION_ID、turnId=$DSH_PHONE_TURN_ID'],
  ['begin-not-permission', 'guide', 'begin 不替代权限或观察'],
  ['pause-keeps-window', 'guide', '用户在本机暂停时控制窗保留，等用户明确继续或停止'],
  ['channel-order', 'guide', '■ 通道（按顺序选）'],
  ['device-write-shell', 'guide', '写 Android 设备文件只能走它'],
  ['prefer-app-api', 'guide', '启动应用用 /app/launch 而非 am start'],
  ['shell-channel-order', 'guide', '经 root 管理器授权的 su、已授权的 Shizuku、已启用的 ADB'],
  ['shell-verify-id', 'guide', '用 id 核验实际身份'],
  ['su-only-when-needed', 'guide', '--su 只在明确需要 root 时用，仍须原生授权'],
  ['no-raw-adb', 'guide', '不要用 /root/dsh-bin/adb 或裸 adb'],
  ['screen-mode-exclusive', 'guide', '关闭 / 实体 / 虚拟，互斥'],
  ['screen-table-scope', 'guide', '| 独立虚拟屏 | mcp__dsha-vscreen__* | 不调用 |'],
  ['screen-mode-off', 'guide', '照原话告诉用户去哪里切换，不改用另一块屏、不重试'],
  ['no-cross-infer', 'guide', '不从实体主屏截图推断虚拟屏内容'],
  ['no-cross-fallback', 'guide', '一块屏失败不用另一块屏兜底'],
  ['help-via-headers', 'guide', 'curl -s -H @/root/.dsh/.bridge_headers'],
  ['header-credentials', 'guide', '凭据走头文件，不放进 URL 或 argv'],
  ['old-help-no-retry', 'guide', '老版本 App 没有 /app/help（回复不像清单）时不要反复重试'],
  ['observe-after-action', 'guide', '每次点按或输入后先重新观察'],
  ['unknown-no-replay', 'guide', 'UNKNOWN / PARTIAL / [EXECUTION_UNKNOWN] 表示可能已执行：先核对实际状态，不自动重放，不换通道或换屏兜底'],
  ['finish-not-success', 'guide', '收尾不代表未知动作已成功'],
  ['disabled-tell-user', 'guide', '照工具原话告诉用户去哪里开，不反复重试'],
  ['no-forged-consent', 'guide', '不伪造授权或自动批准'],
  ['policy-blocked', 'guide', '[POLICY_BLOCKED] 表示不执行，不能靠 Root、旧确认开关、环境变量放行'],
  ['policy-no-bypass', 'guide', '不能用普通 shell、Python/Node、裸 ADB 或 UI 操作绕过'],
  ['readonly-dirs', 'guide', '根目录、系统目录、DCIM、Pictures、Android/data、Android/obb 及子目录只读'],
  ['forbidden-commands', 'guide', '禁止块设备/分区、SELinux 修改、settings put/setprop、挂载、刷机、卸载/清应用数据，未知命令直接拦截'],
  ['kill-user-apps-only', 'guide', '系统应用与关键进程禁止结束'],
  ['kill-exact-target', 'guide', '用完整包名或明确正数 PID，禁止模糊匹配与整组杀进程'],
  ['sms-read-only', 'guide', '只用 adb-shell 执行 content query --uri content://sms，默认逐次确认'],
  ['sms-grant-revocable', 'guide', '开启短信读取，可随时撤销；仅当前 Android 用户'],
  ['sms-no-write', 'guide', '不发送、修改或删除短信'],
  ['sms-no-bypass', 'guide', 'DSHA 授权不替代 Android 权限，被拒时不绕过'],
  // 实体主屏 MCP
  ['android-begin-finish', 'android', '先调用 android_control begin，完成或放弃时在收尾调用一次 android_control finish'],
  ['android-display0-only', 'android', '本服务只操作实体主屏（display 0），存在独立虚拟屏时也不会转过去'],
  ['android-permissions-remain', 'android', '必要的 Android 权限与原生确认仍然生效'],
  ['android-overlay-hidden', 'android', '原生任务窗口在首次读屏前隐藏，直到任务结束或用户暂停'],
  ['android-overlay-change', 'android', '输入前若该窗口可见状态变了，先取新观察再明确决定，不暗中重试'],
  ['android-node-credential', 'android', '凭据绑定任务、目标与代次，30 秒内一次有效'],
  ['android-coordinate-credential', 'android', '只认最新 android_screenshot 中 coordinatesAvailable:true 的 observationId'],
  ['android-tree-not-coordinates', 'android', '读屏树不能授权坐标'],
  ['android-native-checks', 'android', '像素、目标、代次、遮挡与边界检查照常执行'],
  ['android-no-multitouch', 'android', '单指滑动，不提供多指'],
  ['android-node-no-fallback', 'android', '节点动作被拒或 UNKNOWN 时不自动改用坐标手势、不重放'],
  ['android-explicit-switch', 'android', '须重新截图并明确选择'],
  ['android-webview-verify', 'android', 'effectVerified:false 时要重新观察'],
  ['android-control-scope', 'android:android_control', '仅实体主屏'],
  ['android-control-vscreen', 'android:android_control', '虚拟屏工具不需要也不调用它'],
  ['android-control-identity', 'android:android_control', '不由模型传入、不回退到全局活动任务、不新建聊天'],
  ['android-control-no-grant', 'android:android_control', '不授予系统权限或自动同意确认'],
  ['android-control-finish', 'android:android_control', 'finish后本轮不能重新begin，也不能用于重试UNKNOWN动作'],
  ['android-node-tool', 'android:android_node', '失败或UNKNOWN不得自动转坐标手势或重放'],
  // 虚拟屏 MCP
  ['vscreen-no-begin', 'vscreen', '不需要也不调用 android_control begin/finish（那只用于实体主屏）'],
  ['vscreen-no-physical-fallback', 'vscreen', '失败时不改用实体主屏兜底'],
  ['vscreen-status-first', 'vscreen', '先 status（没有 Core 时才 create）'],
  ['vscreen-core-explicit', 'vscreen', '显式传 status 返回的 coreInstance'],
  ['vscreen-empty-launch', 'vscreen', '新建或旋转后的屏可能是空屏（see 回复 nextAction=LAUNCH_APP_FIRST），先 launch 应用'],
  ['vscreen-observe-first', 'vscreen', '输入前先 see 或 tree 观察，动作后重新观察再决定下一步'],
  ['vscreen-capability-pixel', 'vscreen', '不需要无障碍——status、create、close、launch、see'],
  ['vscreen-capability-a11y', 'vscreen', '需要无障碍——tree、node、editor、anchor/anchored_click 与中文等非 ASCII 的 type'],
  ['vscreen-write-needs-app', 'vscreen', '写动作要求屏上已有前台应用，空屏会返回未执行'],
  ['vscreen-modes-independent', 'vscreen', '三种动作各有独立身份与目标复核，不互相静默回退'],
  ['vscreen-editable-first', 'vscreen', '中文和动态页面输入优先用已观察的 editable 节点或 editor，不靠旧像素坐标'],
  ['vscreen-same-observation', 'vscreen', 'coreInstance、generation、frameSeq、observationId、nodeId 必须来自同一次观察'],
  ['vscreen-no-upgrade', 'vscreen', '不用更新的 Core 身份或当前 status 升级旧观察'],
  ['vscreen-cancel-unknown', 'vscreen', '可能丢失回执，不证明动作或 launch 未执行'],
  ['vscreen-markers-no-replay', 'vscreen', 'executionUnknown、noReplay、targetChangedAfterExecution、runChangedAfterExecution 一律不自动重放'],
  ['vscreen-no-auto-recreate', 'vscreen', 'cleanupPending、CLOSE_UNKNOWN、START_UNKNOWN、DISCONNECTED 不是自动重建的许可'],
  // T35 新增：观察失败的 nextAction、待清理 Core 与旋转（在 T35 分支之上合并）
  ['vscreen-follow-next-action', 'vscreen', '只读观察失败按回复的 nextAction 处理（WAIT_AND_SEE_AGAIN、REPORT_TO_USER、LAUNCH_APP_OR_WAIT、ENABLE_ACCESSIBILITY）'],
  ['vscreen-observe-failure-safe', 'vscreen', '重新观察安全，也不代表任何动作已执行'],
  ['vscreen-check-status', 'vscreen', 'CHECK_STATUS 表示先读 status 再决定'],
  ['vscreen-ask-user-close', 'vscreen', '经用户同意后才 close 该 coreInstance，绝不自动新建替代'],
  ['vscreen-see-next-actions', 'vscreen:android_vscreen_see', 'WAIT_AND_SEE_AGAIN（FRAME_PENDING）时稍等再 see'],
  ['vscreen-tree-reasons', 'vscreen:android_vscreen_tree', 'ACCESSIBILITY_NOT_ENABLED→请用户在系统设置开启 DSHA 无障碍'],
  ['vscreen-rotate-relaunch', 'vscreen:android_vscreen_create', '原应用窗口不会跟过去'],
  ['vscreen-launch-generation', 'vscreen:android_vscreen_launch', '原生 launch 不校验 generation'],
  ['vscreen-self-launch-refused', 'vscreen:android_vscreen_launch', '不接受把 DSHA 自身启动到虚拟屏'],
  ['vscreen-see-anchor-first', 'vscreen:android_vscreen_see', '优先显式 anchor→anchored_click'],
  ['vscreen-anchor-full-res', 'vscreen:android_vscreen_anchor', '原图全分辨率整数坐标'],
  ['vscreen-anchor-no-reselect', 'vscreen:android_vscreen_anchor', 'ANCHOR_ALREADY_ISSUED 不会返回旧 ID；它或丢失回复都不允许自动重选或补截图'],
  ['vscreen-anchor-ambiguous', 'vscreen:android_vscreen_anchor', '锚定不可用、Canvas 或有歧义时，明确改用 tree/node 或新观察，不自动重试'],
  ['vscreen-anchored-once', 'vscreen:android_vscreen_anchored_click', 'UNKNOWN/noReplay 不恢复锚点、不重放，不自动换成 tap'],
  ['vscreen-anchored-frames', 'vscreen:android_vscreen_anchored_click', 'sourceFrameSeq 保持原帧，validatedCurrentSeq 是实际复核帧'],
  ['vscreen-tap-latest-frame', 'vscreen:android_vscreen_tap', '须带最新已观察帧的 frameSeq'],
  ['vscreen-preview-scale', 'vscreen:android_vscreen_tap', '先按 width/previewWidth、height/previewHeight 换算'],
  ['vscreen-touch-phases', 'vscreen:android_vscreen_touch', '0 按下、2 移动、1 抬起、3 取消'],
  ['vscreen-type-ascii', 'vscreen:android_vscreen_type', '只含可打印 ASCII 时走输入通道，不需要无障碍'],
  ['vscreen-node-once', 'vscreen:android_vscreen_node', '不需要 frameSeq、不受像素帧率影响，nodeId 用一次即失效'],
  ['vscreen-close-identity', 'vscreen:android_vscreen_close', '仍有存活或待清理的 Core 时须传其 coreInstance'],
];
// vscreen 含 T35 的 nextAction / 待清理 / 旋转规则（约 1135 字符），上限留少量余量。
const SCREEN_LIMITS = { guide: 3000, android: 800, vscreen: 1200 };

function mcpSurface(relative) {
  const file = path.join(root, 'app/src/main/assets/builtin-plugins', relative);
  const reply = spawnSync(process.execPath, [file], { encoding: 'utf8', timeout: 5000,
    input: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }) + '\n' });
  assert.equal(reply.status, 0, relative);
  const descriptions = Object.fromEntries(createRequire(import.meta.url)(file).tools.map(tool => [tool.name, tool.description]));
  return { instructions: JSON.parse(reply.stdout.trim().split('\n')[0]).result.instructions, descriptions };
}

function lockScreenConstraints(guideText) {
  const android = mcpSurface('dsh-computer-use-android/lib/server.cjs');
  const vscreen = mcpSurface('dsh-tool-vscreen/lib/server.cjs');
  const where = { guide: guideText, android: android.instructions, vscreen: vscreen.instructions };
  const ids = new Set();
  for (const [id, place, phrase] of SCREEN_CONSTRAINTS) {
    assert(!ids.has(id), 'duplicate constraint id ' + id); ids.add(id);
    const [surface, tool] = place.split(':');
    const text = tool ? (surface === 'android' ? android : vscreen).descriptions[tool] : where[surface];
    assert(typeof text === 'string', `${id}: missing surface ${place}`);
    assert(text.includes(phrase), `${id}: constraint missing from ${place}: ${phrase}`);
  }
  // 规则正文统一中文：说明里不再保留一份英文平行规则。
  for (const surface of ['android', 'vscreen']) assert(!/\bnever\b|\bmust\b|\bdo not\b/i.test(where[surface]), surface + ' instructions must not keep an English rule copy');
  for (const [surface, limit] of Object.entries(SCREEN_LIMITS))
    assert(where[surface].length <= limit, `${surface} prompt grew to ${where[surface].length} chars (limit ${limit})`);
}

const original = Object.fromEntries(['openSync', 'fstatSync', 'readSync', 'closeSync', 'lstatSync'].map(key => [key, fs[key]]));
const previousEnv = { ...process.env };
const token = 'f'.repeat(32), fakeFd = 987651;
let language = 'en', requests = 0, status = 200, excessive = false, tokenMode = 0o100600;
const server = http.createServer(async (request, response) => {
  assert.equal(request.headers['x-token'], request.url === '/app/version' ? token : undefined);
  if (request.url === '/app/version') {
    requests++;
    response.writeHead(status, { 'Content-Type': 'application/json' });
    response.end(excessive ? 'x'.repeat(8193) : JSON.stringify({ result: `BRIDGE_PROTOCOL=3\nUI_LANGUAGE=${language}\n` }));
  } else {
    assert.equal(request.url, '/v1/diagnostics');
    assert.equal(request.headers.authorization, `Bearer ${'a'.repeat(64)}`);
    response.end(JSON.stringify({ status: 'ok', uiLanguage: language }));
  }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const port = String(server.address().port);
const contexts = [];
let recoveryDispose;
try {
  process.env.DSHA_BRIDGE_PORT = port;
  process.env.DSHA_UI_LANGUAGE = 'zh'; // captured launch language deliberately becomes stale
  fs.openSync = function(file, flags, ...args) {
    if (file === '/root/.dsh/.bridge_token') {
      assert.equal(flags, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
      return fakeFd;
    }
    return original.openSync.call(this, file, flags, ...args);
  };
  fs.fstatSync = function(fd, ...args) {
    return fd === fakeFd ? { isFile: () => true, size: token.length, mode: tokenMode } : original.fstatSync.call(this, fd, ...args);
  };
  fs.readSync = function(fd, buffer, offset, length, position, ...args) {
    if (fd === fakeFd) { assert.equal(length, 130); return buffer.write(token, offset); }
    return original.readSync.call(this, fd, buffer, offset, length, position, ...args);
  };
  fs.closeSync = function(fd, ...args) { if (fd !== fakeFd) return original.closeSync.call(this, fd, ...args); };
  fs.lstatSync = function(file, ...args) {
    if (file === '/root/.dsh/.bridge_port') throw Object.assign(Error('isolated fixture'), { code: 'ENOENT' });
    return original.lstatSync.call(this, file, ...args);
  };
  syncBuiltinESMExports();
  const ctx = new Context(); contexts.push(ctx);
  await ctx.plugin(SystemPrompt, {});
  await ctx.plugin(guide);
  const userText = 'User custom instructions: preserve this exact value.';
  ctx.systemPrompt.section({ name: 'user-custom', order: 160, text: userText });
  for (const selected of ['zh', 'zh-Hant', 'en', 'ru', 'ja', 'ko', 'ar', 'pt', 'la', 'fr']) {
    language = selected;
    const assembly = await ctx.systemPrompt.assemble();
    const text = assembly.sections.find(section => section.name === 'dsh:device-shell-guide').text;
    assert(text.includes(`DSHA current response language: ${selected} (`), selected);
    assert(text.includes('custom prompt or user-owned AGENTS.md language instruction takes priority'));
    assert(text.includes('android_get_state') && text.includes('android_screenshot') && text.includes('android_node'));
    assert(text.includes('android_control({action:"begin"})') && text.includes('android_control({action:"finish"})'));
    assert(text.includes('普通讨论、引用、翻译') && text.includes('不取消模型对话'));
    assert(!text.includes('与用户交流一律使用中文'));
    assert.equal(assembly.sections.find(section => section.name === 'user-custom').text, userText);
  }
  assert.equal(requests, 10, 'same live environment must reread every assembly without restart');
  lockScreenConstraints(guide.promptText('zh'));
  const complete = 'User complete system prompt';
  const custom = ctx.systemPrompt.section({ name: 'user-complete', order: 0, text: complete, complete: true });
  assert.deepEqual((await ctx.systemPrompt.assemble()).sections.map(row => row.text), [complete]);
  custom();
  const scopeKey = {}, userScope = createScope(ctx, scopeKey);
  await userScope.ctx.plugin({ inject: ['systemPrompt'], apply(scope) {
    scope.systemPrompt.section({ name: 'dsh:device-shell-guide', order: 150, text: userText });
  } });
  const beforeOverride = requests;
  assert.equal((await ctx.systemPrompt.assemble({ scope: scopeKey })).sections.find(row => row.name === 'dsh:device-shell-guide').text, userText);
  assert.equal(requests, beforeOverride, 'an explicit replacement of this section must remain owned by its author');
  await userScope.dispose();

  // Minimal profiles retain old snapshots byte-for-byte, append a current one, and dedup only committed same-language guides.
  const handlers = new Map(), historical = { type: 'user/message', data: { id: 'old-guide', source: { kind: 'plugin', plugin: guide.name }, content: [{ type: 'text', text: '与用户交流一律使用中文。' }] } };
  const frozenHistory = JSON.stringify(historical);
  guide.apply({ inject() {}, on(event, handler) { handlers.set(event, handler); } });
  const session = { snapshotEvents: () => [historical] }, decision = { kind: 'enter', messages: [{ id: 'current-user', text: 'custom prompt' }] };
  const step = () => handlers.get('agent/pre-step')({ agent: { session }, step: 1, signal: new AbortController().signal }, async () => decision);
  language = 'ja';
  const first = await step();
  assert.equal(first.messages.length, 2); assert(first.messages[0].content[0].text.includes('language: ja ('));
  assert.equal(JSON.stringify(historical), frozenHistory);
  assert.equal(first.messages[1], decision.messages[0]);
  assert.equal((await step()).messages.length, 2, 'uncommitted guide cannot suppress retry after rejected admission');
  handlers.get('session/event')(session, { type: 'user/message', data: first.messages[0] });
  assert.equal(await step(), decision);
  language = 'ar';
  const changed = await step();
  assert.equal(changed.messages.length, 2); assert(changed.messages[0].content[0].text.includes('language: ar ('));

  status = 401; assert.equal(await guide.selectedLanguage(), 'zh');
  status = 200; language = 'system'; assert.equal(await guide.selectedLanguage(), 'zh');
  excessive = true; assert.equal(await guide.selectedLanguage(), 'zh'); excessive = false;
  tokenMode = 0o100644;
  const beforeUnsafe = requests; assert.equal(await guide.selectedLanguage(), 'zh'); assert.equal(requests, beforeUnsafe); tokenMode = 0o100600;
  const cancelled = new AbortController(); cancelled.abort();
  const beforeCancel = requests; assert.equal(await guide.selectedLanguage(cancelled.signal), 'zh'); assert.equal(requests, beforeCancel);

  // Recovery uses its already-authenticated independent broker, never the formal runtime token.
  process.env.DSHA_RECOVERY_INSTANCE_ID = 'a'.repeat(32);
  process.env.DSHA_RECOVERY_GENERATION = '1';
  process.env.DSHA_RECOVERY_BROKER_PORT = port;
  process.env.DSHA_RECOVERY_BROKER_TOKEN = 'a'.repeat(64);
  const recoveryHandlers = new Map(), sections = [];
  recovery.apply({ tools: { guard() {}, register() {} }, systemPrompt: { section(value) { sections.push(value); } },
    on(name, handler) { recoveryHandlers.set(name, handler); if (name === 'dispose') recoveryDispose = handler; },
    root: { loader: { await: () => new Promise(() => {}) } } });
  for (const selected of ['zh-Hant', 'fr', 'la']) {
    language = selected;
    const initial = { sections: sections.map(row => ({ name: row.name, text: typeof row.text === 'function' ? row.text({}) : row.text })) };
    const assembly = await recoveryHandlers.get('system-prompt/assemble')(initial, {}, async () => initial);
    assert(assembly.sections.find(row => row.name === 'dsha-recovery-language').text.includes(`language: ${selected} (`));
    assert.equal(assembly.sections.find(row => row.name === 'dsha-recovery').text, initial.sections.find(row => row.name === 'dsha-recovery').text);
  }
  console.log('PASS locked rc3 dynamic prompt assembly, 10 current languages, user complete/custom prompt preservation, historical snapshot refresh, cancellation/401/bounds and independent recovery language');
} finally {
  recoveryDispose?.();
  for (const ctx of contexts.reverse()) await ctx.fiber.dispose();
  Object.assign(fs, original); syncBuiltinESMExports();
  for (const key of Object.keys(process.env)) if (!(key in previousEnv)) delete process.env[key];
  Object.assign(process.env, previousEnv);
  await new Promise(resolve => server.close(resolve));
}
