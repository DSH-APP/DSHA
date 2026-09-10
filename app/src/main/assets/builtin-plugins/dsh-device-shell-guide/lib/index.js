/** DSHA 设备能力引导：使用 dsh 0.1.5 的消息工厂与已提交事件判重。 */
import { createUserMessage } from '@deepseek-ai/dsh-llm';
export const name = 'dsh-device-shell-guide';

const PROMPT = [
  '【设备操作能力 · DSHA】你正运行在用户 Android 手机的容器里，可以干预这台实体手机。',

  '■ 三条通道，按这个顺序选：',
  '  1) Ubuntu 工作区内的开发与文件操作使用普通工具；Android 设备文件写入必须走受保护的设备 shell。',
  '  2) App 层接口 /app/*（走 127.0.0.1:3090，零配置，不需要 ADB，也不需要 Shizuku）',
  '  3) 设备 shell（ADB 无线调试，用户可能没开）—— 查询设备日志、普通设备文件操作和结束用户应用。',
  '  例：查设备状态用 /app/device 而不是 dumpsys battery；启动应用用 /app/launch 而不是 am start。',

  '■ 完整端点清单（读屏 / 点按 / 输入 / 截屏 / 通知 / 剪贴板 / 传感器 / 导出文件 …）：',
  '  T=$(cat /root/.dsh/.bridge_token)',
  '  curl -s "http://127.0.0.1:3090/app/help?token=$T"',
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
  '  - 默认权限是 shell 级（uid=2000，非 root）。不要主动用 --su，' +
  '    只有用户明确要求 root 操作时才提，并且要他先到「配置」页勾选授权；',
  '  - 不要用 /root/dsh-bin/adb 或裸 adb 命令 —— 那是守卫包装脚本，会失败；',
  '  - 与用户交流一律使用中文。',
].join('\n')

export function apply(ctx) {
  let systemActive = false;
  const guided = new WeakSet();
  const inspected = new WeakSet();
  const isGuide = event => event?.type === 'user/message'
    && event.data?.source?.kind === 'plugin' && event.data.source.plugin === name;
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
