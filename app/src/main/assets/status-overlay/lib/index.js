/**
 * dsh-status-overlay —— 把 agent 正在生成的内容实时送到手机屏幕顶部的悬浮条上，
 * 像音乐软件的歌词那样流式滚动；工具调用则显示成「正在执行命令」这类人话。
 *
 * 数据流：
 *   session/event → 这里节流合并 → GET 127.0.0.1:3090/app/overlay → App 侧 OverlayController
 *
 * 几个刻意的设计：
 *
 * 1) **不写模块级 `inject`**。模块级 inject 是硬依赖，缺服务就让 entry 永远 pending，
 *    dsh 会判定「1 entry did not activate」→ **整棵 plugin tree 加载失败**，Web 起不来。
 *    内置的 device-shell-guide 0.1.9 栽过这一次，三个用户的 Web 直接打不开。
 *    这个插件只用 ctx.on，不需要任何服务，那就一个都不声明。
 *
 * 2) **发「当前应显示的整行」而不是增量**。悬浮条只有一行、只显示尾部若干字，
 *    发整行是幂等的：丢一次包下一次就自动对上；发增量则一旦丢包就永久错位。
 *
 * 3) **节流 + 冷却**。流式 token 一秒几十个，每个都发一次 HTTP 纯属烧电，
 *    所以合并到 FLUSH_MS 一次；而用户没开这个功能（DISABLED）或没给悬浮窗权限
 *    （NO_PERMISSION）时进入长冷却，别对着一个关着的门一直敲。
 *
 * 4) **多实例**：dsh 可以同时跑多个会话，各自都在吐字。按 session 分桶、各带一个短标识
 *    交给 App 侧渲染，否则两路输出会交织成乱码。
 *
 * 5) **一个异常都不能冒出去**。这是个「顺带好看」的功能，绝不能影响 agent 干活 ——
 *    所有 IO 与解析都包在 try/catch 里，失败就静默降级。
 */
import { readFileSync } from 'node:fs'

/** 桥地址（App 侧只监听回环，容器与宿主共享网络命名空间，所以直接连得上）。 */
const BRIDGE = 'http://127.0.0.1:3090/app/overlay'
const TOKEN_PATH = '/root/.dsh/.bridge_token'
/** 合并窗口：120ms 一次，肉眼看起来仍是连续流动的。 */
const FLUSH_MS = 120
/** 插件侧只做粗截断（防 URL 编码后过长），真正的行数由 App 侧按用户配置决定 ——
 *  插件不知道用户把「最多显示几行」调成了几。上限给得比显示所需大得多是故意的：
 *  App 那边按显示宽度切行，切分点只取决于文本开头，开头一变所有行就重排。 */
const MAX_CHARS = 1200
/** 超限时一次砍到这个长度，而不是逐字砍 —— 见 tail() 的注释。 */
const KEEP_CHARS = 800
/** 功能关闭 / 没权限时的冷却时长。 */
const COOLDOWN_MS = 60_000
/** 思考过程被 App 拒掉后隔多久再试一次 —— 那个开关随时可能被打开，不能一拒就永久闭嘴。 */
const REASONING_RETRY_MS = 60_000
/** 单次请求超时：桥就在本机，1.5 秒都不该等。 */
const TIMEOUT_MS = 1500

/** 工具名 → 人话。命中越具体的放前面；没命中的兜底成「正在使用 X」。 */
const TOOL_LABELS = [
  [/bash|shell|command|exec|terminal/i, '⚙ 正在执行命令'],
  [/write|create.*file|edit|patch|apply/i, '⚙ 正在修改文件'],
  [/read|cat|view|open.*file/i, '⚙ 正在读取文件'],
  [/glob|grep|search|find/i, '⚙ 正在搜索'],
  [/fetch|web|http|browse|url/i, '⚙ 正在联网查资料'],
  [/todo|plan/i, '⚙ 正在整理任务清单'],
  [/task|agent|subagent|dispatch/i, '⚙ 正在派子任务'],
  [/image|screenshot|vision/i, '⚙ 正在看图'],
  [/notify|toast|share|clip/i, '⚙ 正在调用手机功能'],
]

function toolLabel(name) {
  const n = String(name || '').trim()
  if (!n) return '⚙ 正在使用工具'
  for (const [re, label] of TOOL_LABELS) {
    if (re.test(n)) return label
  }
  return '⚙ 正在使用 ' + n
}

/** 工具参数里挑一个「值得显示的那个」——命令原文最有用，其次是路径/模式/URL。
 *  arguments 是 JSON 字符串；模型也可能吐出半截的非法 JSON，所以解析失败就放弃。 */
const ARG_KEYS = ['command', 'cmd', 'script', 'path', 'file_path', 'filePath',
  'pattern', 'query', 'url', 'prompt', 'description']

function toolDetail(argsJson) {
  try {
    if (!argsJson) return ''
    const o = typeof argsJson === 'string' ? JSON.parse(argsJson) : argsJson
    if (!o || typeof o !== 'object') return ''
    for (const k of ARG_KEYS) {
      const v = o[k]
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
    // 没命中已知键名 → 退而取第一个非空字符串值（新工具也能显示点东西）
    for (const v of Object.values(o)) {
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
    return ''
  } catch {
    return ''
  }
}

let token
function bridgeToken() {
  if (token !== undefined) return token
  try {
    token = readFileSync(TOKEN_PATH, 'utf8').trim()
  } catch {
    token = ''    // 桥没起来 / 桌面环境：整个功能静默停用
  }
  return token
}

/** sessionKey → { line, timer, dirty } */
const state = new Map()
let cooldownUntil = 0
/** App 侧回了 SKIP_REASONING（用户当时不想看思考过程）→ 暂停这一类到这个时间点。
 *  **带时效而不是永久**：开关是随时可改的，写成永久的话用户之后在配置页打开
 *  「显示思考过程」，插件仍然一条都不发，非得重启 dsh 才恢复 —— 真机上报的
 *  「思考内容无法显示」就是这个。 */
let skipReasoningUntil = 0

function sessionKey(session) {
  try {
    const raw = session?.id ?? session?.sessionId ?? session?.key ?? ''
    const s = String(raw)
    return s ? s.slice(-8) : '-'
  } catch {
    return '-'
  }
}

/** 压成单行并限长。
 *
 *  **不能逐字截**：App 侧按显示宽度把这段文本切成固定的行、再取最后几行，切分点只取决于
 *  文本开头。每来一个字就把开头砍掉一个字的话，所有行都会重排 —— 那正是用户报的
 *  「最前端文字不停滚走、可读性很差」。所以超限时一次砍到 KEEP_CHARS，并尽量从词边界
 *  开始，把重排频率从「每个字一次」降到「几百个字一次」。 */
function tail(s) {
  const one = String(s || '').replace(/\s+/g, ' ').trim()
  if (one.length <= MAX_CHARS) return one
  let from = one.length - KEEP_CHARS
  const sp = one.indexOf(' ', from)
  if (sp > 0 && sp - from < 40) from = sp + 1
  return one.slice(from)
}

async function send(key, kind, text) {
  const tok = bridgeToken()
  if (!tok) return
  if (Date.now() < cooldownUntil) return
  const url = `${BRIDGE}?kind=${encodeURIComponent(kind)}`
    + `&session=${encodeURIComponent(key)}`
    + `&text=${encodeURIComponent(text || '')}`
  try {
    const res = await fetch(url, {
      headers: { 'X-Token': tok },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    const body = (await res.text()).trim()
    if (body === 'DISABLED' || body === 'NO_PERMISSION') {
      // 用户没开这个功能或没授权 —— 进冷却，别一直敲一扇关着的门
      cooldownUntil = Date.now() + COOLDOWN_MS
    } else if (body === 'SKIP_REASONING') {
      // 功能开着，只是这会儿不看思考过程：别整段冷却，只停这一类，而且**要带时效**
      skipReasoningUntil = Date.now() + REASONING_RETRY_MS
    }
    return body
  } catch {
    // 桥没起、超时、被拒：这功能不重要，静默降级
    cooldownUntil = Date.now() + 5000
  }
  return ''
}

// ================= 就地回话 =================
// agent 说完一轮后，用户可以直接在悬浮条上打一句话回过来（不必切回 App）。
// App 侧把用户按下发送的文本存进队列，这里轮询取走再喂回 session。
//
// **为什么轮询而不是让 App 主动推**：反向连接要在 dsh 进程里开一个监听端口，
// 那是新的攻击面，也要处理端口冲突与 rootfs 重启后的注册；而这里等的是人打字，
// 秒级延迟完全无感。轮询只在「刚说完一轮」这段窗口里跑，平时一个请求都不发。

const REPLY_URL = 'http://127.0.0.1:3090/app/overlay/reply'
/** 取件间隔。人打字以秒计，1.2 秒足够灵敏又不至于烧电。 */
const REPLY_POLL_MS = 1200
/** 一轮说完后最多守 3 分钟。用户这会儿不回，那就是不打算回了 —— 下一轮 done 会再开窗口。 */
const REPLY_WINDOW_MS = 180_000
/** 每个 session 一个取件循环，避免多会话时互相重复取。 */
const replyPollers = new Map()

/**
 * 把用户的话喂回 session。
 *
 * **这里刻意排了四条路依次试。** dsh 的会话 API 在不同版本里叫法不一样，而这个插件要
 * 跟着一整条 1.1.x 的用户群跑；写死一个方法名等于把功能绑在某个小版本上。
 * 四条都不通时把原因回显到悬浮条 —— 用户至少知道话没送出去，而不是干等一个永远不来的回复。
 * 首次成功后记住是哪条（successfulRoute），后面直接走它，不再每次都从头试。
 */
let successfulRoute = ''

async function deliverReply(ctx, session, text) {
  const routes = [
    ['session.prompt', () => session?.prompt?.(text)],
    ['session.send', () => session?.send?.(text)],
    ['api.sessions.prompt', () => {
      const api = ctx?.get?.('api') ?? ctx?.api
      const id = session?.id ?? session?.key ?? session?.sessionId
      return api?.sessions?.prompt?.({ session: id, content: text })
    }],
    ['session.append', () => {
      // 最后的兜底：直接往事件日志里追一条 user/message。
      // 语义上这是「注入一条用户消息」，能不能立刻触发一轮回复取决于 dsh 的驱动器 ——
      // 但至少内容进了会话，用户切回 App 就能看到并接着说。
      const r = session?.append?.('user/message', {
        message: { role: 'user', content: [{ type: 'text', text }] },
      }, { surfaceOp: 'append' })
      return r ?? (session?.run?.() ?? session?.continue?.())
    }],
  ]
  const ordered = successfulRoute
    ? routes.filter(([n]) => n === successfulRoute).concat(routes.filter(([n]) => n !== successfulRoute))
    : routes
  let lastErr = ''
  for (const [name, fn] of ordered) {
    try {
      const r = fn()
      if (r === undefined) continue          // 这条路上的方法不存在，换下一条
      await r
      successfulRoute = name
      return name
    } catch (e) {
      lastErr = `${name}: ${e?.message || e}`
    }
  }
  return lastErr ? `FAILED ${lastErr}` : 'FAILED 会话没有可用的发送入口'
}

/** 在 done 之后开一段取件窗口。同一 session 只开一个。 */
function startReplyPoll(ctx, key, session) {
  if (replyPollers.has(key)) return
  const tok = bridgeToken()
  if (!tok) return
  const deadline = Date.now() + REPLY_WINDOW_MS
  const timer = setInterval(async () => {
    if (Date.now() > deadline) {
      stopReplyPoll(key)
      return
    }
    try {
      const res = await fetch(
        `${REPLY_URL}?token=${encodeURIComponent(tok)}&session=${encodeURIComponent(key)}`,
        { signal: AbortSignal.timeout(TIMEOUT_MS) })
      const body = (await res.text()).trim()
      if (body === 'DISABLED' || body === 'CLOSED') {
        // DISABLED=开关关了；CLOSED=输入栏已经收起（用户按了发送，或 45 秒没动）。
        // 两种都没有继续轮询的意义 —— 下一轮 done 会重新开窗口。
        stopReplyPoll(key)
        return
      }
      if (!body.startsWith('TEXT ')) return   // EMPTY：还没打完
      const text = body.slice(5)
      if (!text) return
      // 取到就停：这一轮的窗口用掉了，下一次 done 会重新开
      stopReplyPoll(key)
      const r = await deliverReply(ctx, session, text)
      if (r.startsWith('FAILED')) {
        void send(key, 'text', '⚠ 这句没送出去：' + r.slice(7))
      } else {
        void send(key, 'text', '↩ 已发送：' + text)
      }
    } catch {
      // 桥没起或超时：下个周期再试，不必特殊处理
    }
  }, REPLY_POLL_MS)
  replyPollers.set(key, timer)
}

function stopReplyPoll(key) {
  const t = replyPollers.get(key)
  if (t) clearInterval(t)
  replyPollers.delete(key)
}

function bucket(key) {
  let b = state.get(key)
  if (!b) {
    b = { line: '', think: '', timer: undefined, pending: false }
    state.set(key, b)
    // 会话数量不该无限涨（异常情况下也就几十个）
    if (state.size > 16) {
      const oldest = state.keys().next().value
      if (oldest !== key) state.delete(oldest)
    }
  }
  return b
}

/** 合并窗口内只保留最后一次内容，窗口结束时发一次。
 *  getter 而不是直接传字符串：窗口这 120ms 里还会继续追加，要发的是**那一刻**的最新值。 */
function schedule(key, kind, getter) {
  const b = bucket(key)
  if (b.timer) {
    b.pending = true
    return
  }
  b.timer = setTimeout(() => {
    b.timer = undefined
    b.pending = false
    void send(key, kind, getter ? getter() : b.line)
  }, FLUSH_MS)
  // 让 dsh 能正常退出：这个定时器不该拖住事件循环
  if (typeof b.timer?.unref === 'function') b.timer.unref()
}

/** @param {import('@deepseek-ai/cordis').Context} ctx */
export function apply(ctx) {
  // 顺带把插件真实加载状态报给 App（见 reportPluginStates 的注释）
  reportPluginStates(ctx)
  ctx.on('session/event', (session, event) => {
    try {
      if (!bridgeToken()) return
      const key = sessionKey(session)
      const type = event?.type
      const data = event?.data

      if (type === 'assistant/chunk') {
        const chunk = data?.chunk
        const isText = chunk?.type === 'text-delta'
        const isThink = chunk?.type === 'reasoning-delta'
        if (!isText && !isThink) return
        if (isThink && Date.now() < skipReasoningUntil) return   // App 侧刚说过不看这个
        const piece = chunk.text
        if (!piece) return
        const b = bucket(key)
        // 正文与思考各留一条缓冲：思考结束转正文时，两段不该被拼成一句
        if (isThink) {
          b.think = tail((b.think || '') + piece)
          schedule(key, 'reasoning', () => b.think)
        } else {
          b.line = tail(b.line + piece)
          schedule(key, 'text', () => b.line)
        }
        return
      }

      if (type === 'tool/call') {
        const b = bucket(key)
        // 「正在执行命令」看不出到底要跑什么 —— 把命令原文（或路径/模式）带上，
        // 这也是悬浮条上就地批准危险命令时唯一的判断依据。
        const label = toolLabel(data?.name)
        const detail = toolDetail(data?.arguments)
        b.line = detail ? label + ': ' + detail : label
        b.think = ''
        // 工具状态要立刻可见（它替代整行，且不像正文那样高频）
        if (b.timer) {
          clearTimeout(b.timer)
          b.timer = undefined
        }
        void send(key, 'tool', b.line)
        return
      }

      if (type === 'turn/start') {
        const b = bucket(key)
        b.line = ''
        b.think = ''
        return
      }

      if (type === 'turn/end') {
        const b = bucket(key)
        if (b.timer) {
          clearTimeout(b.timer)
          b.timer = undefined
        }
        // 留着最后一句让它自然淡出（App 侧几秒后自己收起来）。
        // 返回 'OK REPLY_ON' 说明用户开了就地回话、App 已经把输入栏摆出来了 ——
        // 那就开一段取件窗口等他打字。没开这个开关时一个轮询请求都不会发。
        void send(key, 'done', b.line).then((body) => {
          if (typeof body === 'string' && body.includes('REPLY_ON')) {
            startReplyPoll(ctx, key, session)
          }
        }).catch(() => {})
        state.delete(key)
      }
    } catch {
      // 事件回调里抛异常会影响 agent 主链路，一律吞掉
    }
  })

  ctx.on('dispose', () => {
    // 取件循环是 setInterval，不清就会在插件卸载后继续敲桥
    for (const k of Array.from(replyPollers.keys())) stopReplyPoll(k)
    try {
      for (const [key, b] of state) {
        if (b.timer) clearTimeout(b.timer)
        void send(key, 'clear', '')
      }
      state.clear()
    } catch {
      // 卸载路径上的清理，失败无所谓
    }
  })
}


/**
 * 把「插件真实加载状态」报给 App 的 3090 桥。
 *
 * 为什么需要它：**注册进 profile 不等于加载成功**。cordis 的 PENDING 是最隐蔽的一种状态 ——
 * 插件 inject 的服务没有提供者时它就停在那儿，而且**不报错**（那是合法状态，服务可能稍后
 * 才挂载），于是插件静静地什么都不做。官方教程把「插件什么都没干也没报错 → 去看 fiber
 * 状态」当作标准诊断法，而 App 侧只能读 profile 的 package.json，永远看不到这一层。
 * 「插件装了没反应」缺的就是这份证据。
 *
 * 全程宽容：registry 的形状随 cordis 版本可能变，取不到就少报一点，绝不影响悬浮条本身。
 */
function collectPluginStates(ctx) {
  const loaded = []
  const failed = []
  try {
    const reg = ctx && ctx.registry
    const iter = reg && typeof reg.entries === 'function' ? reg.entries() : reg
    for (const entry of iter || []) {
      const runtime = Array.isArray(entry) ? entry[1] : entry
      if (!runtime) continue
      const nm = runtime.name || (runtime.callback && runtime.callback.name)
      if (!nm) continue
      let fibers = []
      try {
        fibers = runtime.fibers ? Array.from(runtime.fibers) : []
      } catch (e) {
        fibers = []
      }
      if (!fibers.length) {
        failed.push(nm + ':无活动实例')
        continue
      }
      let ok = false
      let why = ''
      for (const f of fibers) {
        const raw = f && f.state !== undefined ? f.state : ''
        const st = String(raw).toLowerCase()
        // 状态可能是字符串也可能是枚举数字，两种都认；认不出的一律当"没起来"上报，
        // 宁可多报一条让用户去查，也不要漏掉真正卡住的插件
        if (st === 'active' || st === '2' || st === '3') {
          ok = true
          break
        }
        why = st || '未知'
      }
      if (ok) loaded.push(nm)
      else failed.push(nm + ':' + why)
    }
  } catch (e) {
    // registry 结构对不上就别报了：少一份诊断信息可以接受，插件本身出问题不行
  }
  return { loaded, failed }
}

function reportPluginStates(ctx) {
  const T = bridgeToken()
  if (!T) return
  // 延后再报：apply 阶段别的插件可能还在 LOADING，太早报会把它们全算成没加载起来
  setTimeout(() => {
    try {
      const st = collectPluginStates(ctx)
      if (!st.loaded.length && !st.failed.length) return
      const url = 'http://127.0.0.1:3090/app/plugins'
        + '?loaded=' + encodeURIComponent(st.loaded.join(','))
        + '&failed=' + encodeURIComponent(st.failed.join(','))
        + '&token=' + encodeURIComponent(T)
      fetch(url).catch(() => {})
    } catch (e) {
      // 上报失败不影响任何既有功能
    }
  }, 8000)
}
