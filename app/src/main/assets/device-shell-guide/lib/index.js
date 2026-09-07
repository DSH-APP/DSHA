/**
 * dsh-device-shell-guide — DSHA builtin server plugin（rc.8 全局 npm 模式版）。
 *
 * 启用后，为每个新对话（亦是每次请求组装）注入「设备操作能力」引导：
 * 让 agent 知道它运行在用户 Android 手机上、并可通过 ADB 无线通道
 * （/root/dsh-bin/adb-shell，uid=2000 免 root）或 Shizuku 桥真实干预实体机。
 *
 * 实现（双通道，确保极简模式也生效）：
 *  1) systemPrompt.section（order 150）：标准模式在系统提示注入
 *  2) agent/pre-step 监听：在用户消息后插入一条 user-role 引导消息
 *     ——极简模式（minimal）不加载 systemPrompt 注入，但 pre-step 是
 *     agent 核心链路必经，注入用户消息前=用户提示词前，绕开限制
 *
 * 挂载方式（rc.8 全局模式）：作为 bundle 注册进 web profile 的
 * dsh.profile.bundles + dependencies（file: 指向本机目录，零网络）。
 */

/** 消息身份：dsh 持久化回放时强校验 message.id（缺 id → 整个会话历史拒绝加载） */
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'

/*
 * 这里**故意不写模块级 inject**。
 *
 * 用户实测报错（1.1.7）：
 *   Error: dsh: plugin tree failed to load: dsh: 1 entry did not activate
 *     dsh-device-shell-guide: pending (waiting for service: systemPrompt)
 *
 * 模块级 `export const inject = ['systemPrompt']` 是**硬依赖**：服务没提供，
 * 插件就永远 pending，dsh 判定 entry 未激活 → 整个 plugin tree 加载失败 →
 * **Web 完全起不来**。而本文件开头的注释自己就写着「极简模式不加载
 * systemPrompt」—— 明知有环境没有这个服务，却把它声明成必需，
 * 等于让插件在那些环境里把整个 Web 拖死。
 *
 * 两条看似可行的路都不通：
 *  · 对象形式 `inject: { required: [], optional: ['systemPrompt'] }`
 *    —— 模块级 inject 必须是数组，对象的键会被当成服务名，
 *    报 `pending (waiting for services: required, optional)`，更糟；
 *  · 运行时判空 `if (ctx.systemPrompt)` —— 读未声明的服务直接抛
 *    `cannot get property "systemPrompt" without inject`，不会返回 undefined。
 *
 * 正解是运行时作用域注入 ctx.inject(deps, cb)：**不阻塞插件激活**，
 * 服务就绪时才跑回调，服务不存在就不跑。官方 dsh-web-app 注入
 * systemPrompt 用的就是这个写法（packages/bundle/web-app/src/index.ts）。
 */

/** 注入到系统提示的引导段（针对 DSHA 手机端环境）。
 *
 * <p><b>这个文件随 APK 的 assets 离线发布。</b>它必须与同一 APK 中的 Java、rootfs 和
 * 插件版本保持兼容；修改提示词后需要随下一版 APK 一起交付，不存在单独的远程脚本通道。
 * 因此这里引用的端点和能力，必须以当前 Java 代码写入的能力清单为准。
 */
const CAPS_FILE = '/root/.dsh/.dsha-caps.json'

/**
 * 读 App 侧写下的能力清单（HarnessController.writeCapsFile 生成）。
 *
 * 读不到就返回 null，调用方按「全开」处理 —— 宁可多教几段，也不能让 agent 突然
 * 不知道自己能操作手机（那比多花几 KB 上下文严重得多）。App 在每次启动 Web 前重写它，
 * 所以用户改完开关重启一次就生效。
 */
function readCaps() {
  try {
    const o = JSON.parse(readFileSync(CAPS_FILE, 'utf8'))
    return (o && typeof o === 'object') ? o : null
  } catch {
    return null
  }
}

/**
 * 按当前真正启用的能力拼提示词。
 *
 * 关着的能力不写进去，省的不只是上下文：原先无条件全量注入时，agent 会去调注定回
 * DISABLED / NO_PERMISSION 的接口，再照提示词里那条「原话告诉用户去哪里开」绕一圈 ——
 * 用户什么都没要求，却先看到一段「你需要先去开位置权限」。
 *
 * @param {Record<string, boolean> | null} caps
 */
function buildPrompt(caps) {
  const on = (k) => caps == null || caps[k] === true
  const L = []
  L.push('【设备操作能力 · DSHA】你正运行在用户 Android 手机的容器里，可以干预这台实体手机。')
  L.push('')
  L.push('■ 通道，按这个顺序选：')
  L.push('  1) 普通工具与文件操作（ls / cat / curl …）—— 能办成的就用它')
  L.push('  2) App 层接口 /app/*（走 127.0.0.1:3090，零配置，不需要 ADB，也不需要 Shizuku）')
  if (on('adb')) {
    L.push('  3) 设备 shell（ADB 无线调试，当前已启用）—— 只有装卸应用、改系统设置、'
      + '抓 logcat/dumpsys 这类事才必须用它')
  }
  L.push('  例：查设备状态用 /app/device 而不是 dumpsys battery；启动应用用 /app/launch 而不是 am start。')
  L.push('')
  L.push('■ 完整端点清单（读屏 / 点按 / 输入 / 截屏 / 通知 / 剪贴板 / 导出文件 …）：')
  L.push('  T=$(cat /root/.dsh/.bridge_token)')
  L.push('  curl -s "http://127.0.0.1:3090/app/help?token=$T"')
  L.push('  → 要用设备能力时查这一次，里面有每个端点的参数和写法。')
  L.push('    清单刻意没写在这里 —— 它有十几 KB，写进提示词就是每一轮都替你付一次上下文。')
  L.push('  ⚠ /app/help 与 /app/version 只有较新的 App 才有（老版本会把未知路径当 shell 命令处理，')
  L.push('    回给你的东西不像清单）。那种情况按下面这份最小清单用，别反复重试 /app/help：')
  L.push('    设备信息 /app/device · 应用列表 /app/apps?q= · 启动应用 /app/launch?pkg=')
  if (on('ui')) {
    L.push('    读屏 /app/ui/dump · 按文字点按 /app/ui/tap?text= · 输入 /app/ui/input?text=')
    L.push('    滑动 /app/ui/swipe · 截屏 /app/ui/screenshot · 按键 /app/ui/key')
  }
  L.push('    通知 /app/notify?title=&text= · 提问 /app/ask?q=&options=a|b · Toast /app/toast?text=')
  L.push('    剪贴板 /app/clip（写入加 ?text=） · 分享 /app/share?text= · 开链接 /app/open?url=')
  L.push('    震动 /app/vibrate?ms= · 导出文件 /app/export?path= · 读 sdcard 文件 /app/readfile?path=')
  if (on('location')) L.push('    位置 /app/location')
  if (on('sensors')) L.push('    传感器 /app/sensors 与 /app/sensor?type= · 手电 /app/torch?on=1')
  L.push('')
  L.push('■ 当前没开、别去试的能力（用户要用会自己去开，你主动调只会拿到 DISABLED）：')
  const off = []
  if (!on('adb')) off.push('设备 shell / ADB（装卸应用、改系统设置、logcat、dumpsys 都做不到）')
  if (!on('ui')) off.push('屏幕操作与截屏（读屏、点按、输入、滑动、按键）')
  if (!on('location')) off.push('位置')
  if (!on('sensors')) off.push('传感器与手电')
  if (!on('root')) off.push('root / --su 提权')
  if (off.length === 0) {
    L.pop()   // 一个都没关就把这个小标题撤掉，别留个空段落
  } else {
    for (const x of off) L.push('  - ' + x)
    L.push('  用户提出要用其中某一项时，照原话告诉他去「配置」页哪一栏开，然后停下等他，'
      + '不要反复重试 —— 重试不会让开关自己变。')
  }
  L.push('')
  L.push('■ 硬约束（几条，都别违）：')
  if (on('confirm')) {
    L.push('  - 危险命令由 App 侧守卫拦下来弹确认框、用户点允许才执行。这道门是机制保证的，'
      + '所以你不必在执行前再口头问一次「可以吗」，把「为什么要跑这条」写进动作说明就够；')
    L.push('  - 不要试图绕过守卫（DSH_NO_CONFIRM、直接调 adb-shell.py 之类）—— 绕过即违规；')
  } else {
    L.push('  - 用户关掉了危险命令确认，也就是说删除、格式化、卸载这类操作会直接执行、'
      + '没有弹窗这道门。你要自己把握：破坏性操作先说清后果、拿到用户明确同意再做；')
  }
  if (on('ui')) {
    L.push('  - 屏幕操作的节奏：每次点按或输入之后先 /app/ui/dump 再决定下一步，别凭记忆连点，'
      + '界面可能已经变了；')
  }
  if (on('root')) {
    L.push('  - 用户已授权 root（--su）。它是最后手段：能用普通权限办成的就别提权，'
      + '用之前说明为什么必须 root；')
  } else {
    L.push('  - 默认权限是 shell 级（uid=2000，非 root）。不要主动用 --su，'
      + '只有用户明确要求 root 操作时才提，并且要他先到「配置」页勾选授权；')
  }
  if (on('adb')) {
    L.push('  - 不要用 /root/dsh-bin/adb 或裸 adb 命令 —— 那是守卫包装脚本，会失败；')
  }
  L.push('  - 与用户交流一律使用中文。')
  return L.join('\n')
}

/**
 * Plugin entry: register the guidance section.
 * @param {import('@deepseek-ai/cordis').Context} ctx
 */
export function apply(ctx) {
  // 通道 1：标准模式 systemPrompt 注入。
  // 用作用域注入而不是模块级 inject —— 极简模式没有这个服务，
  // 硬依赖会让插件永远 pending 并拖垮整个 Web 启动。
  ctx.inject(['systemPrompt'], (promptCtx) => {
    promptCtx.systemPrompt.section({
      name: 'dsh:device-shell-guide',
      order: 150,
      // 每次 apply 时按当前能力现算一次（用户改开关后重启 Web 即生效）
      text: buildPrompt(readCaps()),
    })
  })

  // 通道 2：极简模式（minimal 不加载 systemPrompt）→ 在用户消息后插入引导
  // agent/pre-step 是 agent 核心链路必经事件（官方 dsh-agent-instructions/
  // dsh-compaction-basic 同款用法）。next() 返回 decision，decision.messages
  // 是「本轮」消息（claimed），不含历史 → 不能用 messages 判幂等！
  // 用会话级 WeakSet：同一 session 只注入一次（新对话=新 session 再注入）。
  const guided = /* @__PURE__ */ new WeakSet()
  ctx.on('agent/pre-step', async ({ agent, messages }, next) => {
    const decision = await next()
    if (decision.kind !== 'enter' || !Array.isArray(decision.messages)) return decision
    // 幂等：本会话已注入过 → 跳过（新对话是新 session，WeakSet 自动不含）
    if (agent?.session != null && guided.has(agent.session)) return decision
    // 找到本轮最后一条用户消息的索引（claimed 里 role=user）
    let lastUser = -1
    for (let i = 0; i < messages.length; i++) {
      const m = messages[i]
      if (m && (m.role === 'user' || (m.content && m.source?.kind === 'user'))) lastUser = i
    }
    if (lastUser < 0) return decision
    const guide = {
      // 关键：dsh 持久化会话时强校验每条消息带非空 id
      // （assertMessageEventShape → "lacks an identified message"）。
      // 手搓消息绕过了官方 createUserMessage() 工厂，必须自己补 id，
      // 否则这条引导消息会把整个会话历史写成「加载失败」的损坏状态。
      id: randomUUID(),
      role: 'user',
      content: [{ type: 'text', text: buildPrompt(readCaps()) }],
      source: { kind: 'dsh-device-guide', plugin: 'dsh-device-shell-guide' },
    }
    // 注入到本轮最后用户消息之后（= 用户提示词前的位置语义）
    const claimedCount = messages.length
    const out = decision.messages.toSpliced(lastUser + 1, 0, guide)
    // 标记本会话已注入（防多轮重复）
    if (agent?.session != null) guided.add(agent.session)
    return { ...decision, messages: out }
  })
}
