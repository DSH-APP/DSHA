/**
 * DSHA 设备能力引导：使用 dsh 0.1.5 的消息工厂与已提交事件判重。
 *
 * 本文件同时是「DSHA 容器级常驻约定」的唯一注入点（systemPrompt 分区），因此临时文件目录的约定
 * 也写在这里：AGENTS.md 走 workspace 指令链（按目录、受 64 KiB 预算淘汰），不适合承载每个会话
 * 都必须读到的容器约定。目录字面量以 App 侧 util/ScratchPaths.java 为准，本文件只做兜底。
 */
import fs from 'node:fs';
import { createUserMessage } from '@deepseek-ai/dsh-llm';
export const name = 'dsh-device-shell-guide';

/** 与 app/src/main/java/com/deepseekharness/app/util/ScratchPaths.java 的 GUEST_DIRECTORY 一致。 */
const CACHE_DIRECTORY = process.env.DSHA_CACHE_DIR || '/root/.cache/dsha';

const PROMPT = [
  '【设备操作能力 · DSHA】你正运行在用户 Android 手机的容器里，可以干预这台实体手机。',

  '■ 临时文件与缓存目录（唯一约定，别到处乱放）：',
  `  - 目录：${CACHE_DIRECTORY}（已创建；脚本用 $DSHA_CACHE_DIR 取同一个路径）。`,
  '  - 放这里：探针脚本、中间产物、下载/解压副本、日志、草稿 —— 一切可再生的临时文件。',
  '  - 不要放 /tmp、/root、/root/.dsh 或 git 工作区：那些地方属于运行中的进程、用户数据或仓库。',
  '  - 这个目录按约定可再生：App 的「清理存储空间」会清空它，且不另行确认；',
  '    需要保留的成果写到用户工作区或 /root/Documents 下。',

  '■ 三条通道，按这个顺序选：',
  '  1) Ubuntu 工作区内的开发与文件操作使用普通工具；Android 设备文件写入必须走受保护的设备 shell。',
  '  2) App 层接口 /app/*（走 127.0.0.1:3090，零配置，不需要 ADB，也不需要 Shizuku）',
  '  3) 设备 shell（adb-shell 命令自动选择已授权的 root、Shizuku 或 ADB）—— 查询设备日志、普通设备文件操作和结束用户应用。',
  '  例：查设备状态用 /app/device 而不是 dumpsys battery；启动应用用 /app/launch 而不是 am start。',

  '■ 完整端点清单（读屏 / 点按 / 输入 / 截屏 / 通知 / 剪贴板 / 传感器 / 导出文件 …）：',
  '  # Credentials are sent by header file; do not place them in URLs or argv.',
  '  curl -s -H @/root/.dsh/.bridge_headers "http://127.0.0.1:3090/app/help"',
  '  → 要用设备能力时查这一次，里面有每个端点的参数和写法。',
  '    清单刻意没写在这里 —— 它有十几 KB，写进提示词就是每一轮都替你付一次上下文。',
  '  ⚠ /app/help 与 /app/version 只有较新的 App 才有（老版本会把未知路径当 shell 命令处理，',
  '    回给你的东西不像清单）。那种情况按下面这份最小清单用，别反复重试 /app/help：',
  '    设备信息 /app/device · 应用列表 /app/apps?q= · 启动应用 /app/launch?pkg=',
  '    读屏 /app/ui/dump · 按文字点按 /app/ui/tap?text= · 输入 /app/ui/input?text=',
  '    滑动 /app/ui/swipe · 截屏 /app/ui/screenshot · 按键 /app/ui/key',
  '    通知 /app/notify?title=&text= · 提问 /app/ask?q=&options=a|b · Toast /app/toast?text=',
  '    剪贴板 /app/clip（写入加 ?text=） · 分享 /app/share?text= · 开链接 /app/open?url=',
  '    震动 /app/vibrate?ms= · 导出文件 /app/export?path= · 读 sdcard 文件 /app/readfile?path=',
  '    位置 /app/location · 传感器 /app/sensors 与 /app/sensor?type= · 手电 /app/torch?on=1',

  '■ 硬约束（几条，都别违）：',
  '  - 短信是独立敏感能力：用 adb-shell 执行 content query --uri content://sms，默认逐次确认。' +
  '    持续自动化需用户在设置 → 设备能力授权中开启短信读取，可随时撤销；仅当前 Android 用户，' +
  '    不允许发送、修改或删除短信。DSHA 授权不能替代 Android 权限，拒绝时不要绕过；',
  '  - 设备命令保护始终开启：允许读取任何目录（仍受 Android 权限限制）；根目录和系统目录、' +
  '    DCIM、Pictures、Android/data、Android/obb 及其子目录只读。普通 Download 文件操作允许；',
  '  - 禁止块设备/分区、SELinux 修改、settings put/setprop、挂载、刷机、卸载/清应用数据，未知命令直接拦截；',
  '  - 结束应用前自动刷新全部应用并分用户/系统两组；普通用户应用直接结束，系统应用与关键进程禁止结束。' +
  '    使用完整包名或明确正数 PID，禁止模糊匹配与整组杀进程；',
  '  - [POLICY_BLOCKED] 表示不执行，不能靠 Root、旧确认开关、环境变量放行。' +
  '    不可用普通 shell、Python/Node、裸 ADB 或 UI 操作绕过设备策略；',
  '  - 接口回 DISABLED / NO_PERMISSION、或 ADB 连不上时，照原话告诉用户去哪里开，' +
  '    不要反复重试同一条 —— 重试不会让开关自己变；',
  '  - 屏幕操作的节奏：每次点按或输入之后先 /app/ui/dump 再决定下一步，别凭记忆连点，' +
  '    界面可能已经变了；',
  '  - 设备 shell 优先使用用户已在「设置 → 设备能力授权」开启且经 root 管理器授权的 su，可直接执行，无需 ADB 配对；' +
  '    否则使用已授权的 Shizuku，再尝试已启用的 ADB。用 id 核验实际身份，不假设一定是 uid=2000 或 root。' +
  '    --su 只在明确需要 root 时使用，仍须原生授权，不能绕过任何设备保护；',
  '  - [EXECUTION_UNKNOWN] 表示命令可能已执行。先检查实际状态，不得切换通道或自动重放；',
  '  - 不要用 /root/dsh-bin/adb 或裸 adb 命令 —— 那是守卫包装脚本，会失败；',
  '  - 与用户交流一律使用中文。',
].join('\n')

export function apply(ctx) {
  let systemActive = false;
  const guided = new WeakSet();
  const inspected = new WeakSet();
  const isGuide = event => event?.type === 'user/message'
    && event.data?.source?.kind === 'plugin' && event.data.source.plugin === name;
  // 约定目录在每次启动时由插件创建：AI 与脚本都不必先探测目录是否存在。
  // 创建失败不能挡住插件加载，提示词里的约定路径仍然有效（脚本用 mkdir -p 兜底）。
  try { fs.mkdirSync(CACHE_DIRECTORY, { recursive: true, mode: 0o700 }); }
  catch { /* 目录不可用时保持插件可用，不掩盖真实启动错误。 */ }
  // 作用域注入不会阻塞极简配置的启动；标准配置只走系统提示，避免双份引导。
  ctx.inject(['systemPrompt'], promptCtx => {
    promptCtx.systemPrompt.section({ name: 'dsh:device-shell-guide', order: 150, text: PROMPT });
    promptCtx.effect(() => { systemActive = true; return () => { systemActive = false; }; });
  });
  ctx.on('session/event', (session, event) => { if (isGuide(event)) guided.add(session); });
  ctx.on('agent/pre-step', async ({ agent, step, signal }, next) => {
    const decision = await next();
    if (systemActive || decision.kind !== 'enter' || signal?.aborted || step !== 1) return decision;
    const session = agent?.session;
    if (!session || !Array.isArray(decision.messages) || decision.messages.length === 0) return decision;
    if (!inspected.has(session)) {
      inspected.add(session);
      // 恢复/分叉的历史也参与判重；不修改已有 V3 事件或消息身份。
      if (session.snapshotEvents?.().some(isGuide)) guided.add(session);
    }
    if (guided.has(session)) return decision;
    const guide = createUserMessage({
      content: [{ type: 'text', text: PROMPT }],
      source: { kind: 'plugin', plugin: name, form: 'snapshot', sections: [{ name, text: PROMPT }] },
    });
    // 后续插件仍可能拒绝步骤，因此仅在 session/event 确认写入后标记已注入。
    return { ...decision, messages: [guide, ...decision.messages] };
  }, { prepend: true });
}
