/** DSHA 设备能力引导：使用 dsh 0.1.5 的消息工厂与已提交事件判重。 */
import bridgePort from './bridge-port.cjs';
import * as fs from 'node:fs';
import { createUserMessage } from '@deepseek-ai/dsh-llm';
export const name = 'dsh-device-shell-guide';
const sectionName = 'dsh:device-shell-guide';
const guideVersion = '3';
const languages = Object.freeze({ zh: 'Simplified Chinese', 'zh-Hant': 'Traditional Chinese', en: 'English',
  ru: 'Russian', ja: 'Japanese', ko: 'Korean', ar: 'Arabic', pt: 'Portuguese', la: 'Latin', fr: 'French' });
const supported = value => typeof value === 'string' && Object.hasOwn(languages, value);
const fallbackLanguage = () => supported(process.env.DSHA_UI_LANGUAGE) ? process.env.DSHA_UI_LANGUAGE : undefined;

/** Current native preference, queried anew for each assembly; no installation/profile/user files are rewritten. */
export async function selectedLanguage(signal) {
  let fd;
  try {
    if (signal?.aborted) return fallbackLanguage();
    fd = fs.openSync('/root/.dsh/.bridge_token', fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    const info = fs.fstatSync(fd);
    if (!info.isFile() || info.size < 1 || info.size > 129 || (info.mode & 0o077) !== 0) throw Error('LANGUAGE_TOKEN_FILE');
    const bytes = Buffer.alloc(130), size = fs.readSync(fd, bytes, 0, bytes.length, 0);
    const token = bytes.subarray(0, size).toString('latin1').trim();
    if (size !== info.size || !/^[A-Za-z0-9_-]{1,128}$/.test(token)) throw Error('LANGUAGE_TOKEN_INVALID');
    fs.closeSync(fd); fd = undefined;
    const port = bridgePort();
    const timeout = AbortSignal.timeout(1500);
    const response = await fetch(`http://127.0.0.1:${port}/app/version`, {
      headers: { 'X-Token': token }, redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    });
    if (!response.ok || bridgePort() !== port) throw Error('LANGUAGE_BRIDGE_UNAVAILABLE');
    const reader = response.body?.getReader();
    if (!reader) throw Error('LANGUAGE_BRIDGE_RESPONSE');
    const parts = []; let length = 0;
    try {
      for (;;) {
        const part = await reader.read();
        if (part.done) break;
        length += part.value.byteLength;
        if (length > 8192) throw Error('LANGUAGE_BRIDGE_LIMIT');
        parts.push(Buffer.from(part.value));
      }
    } finally { await reader.cancel().catch(() => {}); }
    const reply = JSON.parse(Buffer.concat(parts, length).toString('utf8'));
    const value = typeof reply.result === 'string' ? /^UI_LANGUAGE=([^\r\n]+)$/m.exec(reply.result)?.[1] : undefined;
    if (supported(value)) return value;
  } catch { /* Old/offline bridges retain the captured launch preference; user instructions still take priority. */ }
  finally { if (fd !== undefined) try { fs.closeSync(fd); } catch {} }
  return fallbackLanguage();
}

export function languageInstruction(language) {
  return (supported(language)
    ? `DSHA current response language: ${language} (${languages[language]}). Use this language for replies and explanations by default.`
    : 'Use the language requested by the user, otherwise follow the language of the current conversation.')
    + ' An explicit user request, custom prompt or user-owned AGENTS.md language instruction takes priority. '
    + 'This current DSHA default supersedes earlier DSHA built-in language defaults, including old Chinese-only guide snapshots; '
    + 'it does not replace user instructions. Keep quoted text, code, paths and tool identifiers in their original form.';
}

/**
 * 端口必须在每次组装提示时重读：Web 可能先于设备桥启动，桥重启也可能换端口。
 * 在模块加载时缓存会把双实例场景永久写成另一个安装的端口。端口未发布时不猜固定值：
 * 3090–3099 可能属于同机另一个 DSHA 安装，写进提示词会让模型把本安装 token 发过去。
 */
export function promptText(language = fallbackLanguage()) {
  let known;
  try { known = bridgePort(); } catch { known = undefined; }
  // 非数字占位让 curl 直接报 URL 错误，而不是连到默认端口。
  const port = known === undefined ? '<PORT>' : String(known);
  // 本段只放「选屏、通道、跨屏硬约束」；实体屏 / 虚拟屏操作细则各自在对应 MCP 的 instructions 与工具描述里，
  // 不在这里重复。约束清单由 tools/test-prompt-language.mjs 逐条锁定，去重时不得删掉任何一条。
  return [
  '【设备操作能力 · DSHA】你在用户 Android 手机的容器里，可以按用户要求操作这台手机。',
  languageInstruction(language),

  '■ 通道（按顺序选）：1) 容器内开发与文件用普通工具；2) App 接口 /app/*（127.0.0.1:' + port + '，不需要 ADB/Shizuku），' +
  '如查设备状态用 /app/device 而非 dumpsys，启动应用用 /app/launch 而非 am start；' +
  '3) 设备 shell（adb-shell 命令，查日志、设备文件操作、结束用户应用；写 Android 设备文件只能走它）。' +
  '设备 shell 依次使用用户已开启并经 root 管理器授权的 su、已授权的 Shizuku、已启用的 ADB；用 id 核验实际身份，' +
  '不假设 uid=2000 或 root；--su 只在明确需要 root 时用，仍须原生授权。不要用 /root/dsh-bin/adb 或裸 adb（守卫包装，会失败）。',

  '■ 跨应用文件（SAF）：用户授权过的目录用 /app/saf/* 读写（trees 拿 id；写完回读；writable=false 时 READ_ONLY）。',
  '■ 两块屏（用户在 设置 → 设备能力授权 → 识屏 选：关闭 / 实体 / 虚拟，互斥），按用户要求选，工具互不转发：',
  '  | 屏 | 工具 | begin/finish | 依赖 |',
  '  | 实体主屏 display 0 | mcp__dsha-android__* 或 /app/ui/* | 需要 | 无障碍；坐标触控另需截屏授权 |',
  '  | 独立虚拟屏 | mcp__dsha-vscreen__* | 不调用 | Root/Shizuku/ADB 通道；无障碍只加控件树与中文输入 |',
  '  SCREEN_MODE_OFF / SCREEN_MODE_PHYSICAL_OFF / SCREEN_MODE_VIRTUAL_OFF 表示没有这块屏的授权：照原话告诉用户去哪里切换，' +
  '不改用另一块屏、不重试。不从实体主屏截图推断虚拟屏内容；一块屏失败不用另一块屏兜底。',

  '■ 实体主屏任务：只有用户在当前对话明确要求操控手机才开始；普通讨论、引用、翻译或未明确要求执行时不要调用，' +
  '不能靠关键词判断意图。先 android_control({action:"begin"})（即使只启动应用），完成或放弃时在收尾调用一次 ' +
  'android_control({action:"finish"})：关闭本轮控制，不取消模型对话，本轮不再重新 begin。会话与轮次由传输层绑定，' +
  '不新建聊天，不只回复教程或让用户去工作区开助手。观察与动作用 android_get_state / android_screenshot / android_node，细则见该 MCP 说明。' +
  '只有 curl 时用 /app/ui/task-begin 与 /app/ui/task-end，它们和所有 /app/ui/* 都从工具环境传 ' +
  'taskSessionId=$DSH_PHONE_SESSION_ID、turnId=$DSH_PHONE_TURN_ID；begin 不替代权限或观察；用户在本机暂停时控制窗保留，等用户明确继续或停止。',

  ...(known === undefined ? ['  ⚠ 设备桥端口尚未发布（/root/.dsh/.bridge_port 不存在或无效）：设备桥未就绪。' +
    '不要猜 3090 或其他端口，那可能是同机另一个 DSHA 安装；请用户打开 DSHA 等设备桥就绪后再试，' +
    '就绪后从 /root/.dsh/.bridge_port 读取端口替换下面的 <PORT>。'] : []),
  '■ 端点清单要用时查一次（凭据走头文件，不放进 URL 或 argv）：',
  '  curl -s -H @/root/.dsh/.bridge_headers "http://127.0.0.1:' + port + '/app/help"',
  '  老版本 App 没有 /app/help（回复不像清单）时不要反复重试，/app/ 下可用：device · apps · launch?pkg= · ui/dump · ui/tap · ' +
  'ui/input · ui/swipe · ui/screenshot · ui/key · notify · ask · toast · clip · share · open · vibrate · export · readfile · location · sensors · sensor · torch',

  '■ 硬约束（所有通道、两块屏都适用）：',
  '  - 每次点按或输入后先重新观察（实体屏读屏，虚拟屏 see 或 tree）再决定下一步，不凭记忆连点；',
  '  - UNKNOWN / PARTIAL / [EXECUTION_UNKNOWN] 表示可能已执行：先核对实际状态，不自动重放，不换通道或换屏兜底；收尾不代表未知动作已成功；',
  '  - DISABLED / NO_PERMISSION、无障碍或悬浮显示未授权、ADB 连不上：照工具原话告诉用户去哪里开，不反复重试；' +
  '对话、原生执行状态、确认策略和系统授权各自生效，不伪造授权或自动批准；',
  '  - [POLICY_BLOCKED] 表示不执行，不能靠 Root、旧确认开关、环境变量放行，也不能用普通 shell、Python/Node、裸 ADB 或 UI 操作绕过；',
  '  - 设备命令保护始终开启：可读任何目录（仍受 Android 权限限制）；根目录、系统目录、DCIM、Pictures、Android/data、' +
  'Android/obb 及子目录只读，普通 Download 可操作；禁止块设备/分区、SELinux 修改、settings put/setprop、挂载、刷机、' +
  '卸载/清应用数据，未知命令直接拦截；',
  '  - 结束应用前自动刷新并分用户/系统两组：只结束普通用户应用，系统应用与关键进程禁止结束；用完整包名或明确正数 PID，禁止模糊匹配与整组杀进程；',
  '  - 短信是独立敏感能力：只用 adb-shell 执行 content query --uri content://sms，默认逐次确认；持续自动化需用户在 ' +
  '设置 → 设备能力授权 开启短信读取，可随时撤销；仅当前 Android 用户；不发送、修改或删除短信；DSHA 授权不替代 Android 权限，被拒时不绕过。',
  ].join('\n');
}

export function apply(ctx) {
  let systemActive = false;
  const guided = new WeakMap();
  const inspected = new WeakSet();
  const renderedGuides = new WeakMap();
  const isGuide = event => event?.type === 'user/message'
    && event.data?.source?.kind === 'plugin' && event.data.source.plugin === name;
  // 作用域注入不会阻塞极简配置的启动；标准配置只走系统提示，避免双份引导。
  ctx.inject(['systemPrompt'], promptCtx => {
    promptCtx.systemPrompt.section({ name: sectionName, order: 150, text: context => {
      const text = promptText();
      if (context && typeof context === 'object') renderedGuides.set(context, text);
      return text;
    } });
    promptCtx.effect(() => { systemActive = true; return () => { systemActive = false; }; });
  });
  // The rc3 section text callback is synchronous. Its official assembly waterfall is async,
  // so only this owned section is refreshed there; complete/custom prompts remain authoritative.
  ctx.on('system-prompt/assemble', async (assembly, context, next) => {
    const ownedText = context && typeof context === 'object' ? renderedGuides.get(context) : undefined;
    if (context && typeof context === 'object') renderedGuides.delete(context);
    const value = await next();
    if (ownedText === undefined || !value.sections?.some(section => section.name === sectionName && section.text === ownedText)) return value;
    const language = await selectedLanguage(context?.signal);
    return { ...value, sections: value.sections.map(section => section.name === sectionName && section.text === ownedText
      ? { ...section, text: promptText(language) } : section) };
  });
  const eventKey = event => isGuide(event) ? event.data.source.sections?.find(section =>
    section.name?.startsWith(`${name}:v${guideVersion}:`))?.name : undefined;
  ctx.on('session/event', (session, event) => { const key = eventKey(event); if (key) guided.set(session, key); });
  ctx.on('agent/pre-step', async ({ agent, step, signal }, next) => {
    const decision = await next();
    if (systemActive || decision.kind !== 'enter' || signal?.aborted || step !== 1) return decision;
    const session = agent?.session;
    if (!session || !Array.isArray(decision.messages) || decision.messages.length === 0) return decision;
    if (!inspected.has(session)) {
      inspected.add(session);
      // 恢复/分叉的历史也参与判重；不修改已有 V3 事件或消息身份。
      for (const event of session.snapshotEvents?.() ?? []) {
        const key = eventKey(event); if (key) guided.set(session, key);
      }
    }
    const language = await selectedLanguage(signal);
    if (signal?.aborted) return decision;
    const key = `${name}:v${guideVersion}:${language ?? 'conversation'}`;
    if (guided.get(session) === key) return decision;
    const text = promptText(language);
    const guide = createUserMessage({
      content: [{ type: 'text', text }],
      source: { kind: 'plugin', plugin: name, form: 'snapshot', sections: [{ name: key, text }] },
    });
    // 后续插件仍可能拒绝步骤，因此仅在 session/event 确认写入后标记已注入。
    return { ...decision, messages: [guide, ...decision.messages] };
  }, { prepend: true });
}
