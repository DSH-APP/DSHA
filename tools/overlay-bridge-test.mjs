#!/usr/bin/env node
// 悬浮条插件怎么读桥的返回值 —— 一条断言钉住「桥返回 JSON」这个契约。
//
// 为什么单独给一个测试：HttpShellService 统一把响应包成 {"result":"..."}（注释里
// 专门写过「result 必须包引号，否则是非法 JSON」），而这个插件第一版起就直接拿整段
// body 去和 'DISABLED' 做等值比较 —— 永远不成立。
//
// 后果是静默失效，不会有任何异常：
//   · 冷却机制从来没生效过 —— 用户关掉悬浮条、或者没给悬浮窗权限时，插件照样每轮
//     发一次 HTTP，一路发到会话结束。不崩、不报错、日志里也看不出来，只是白烧电。
//   · reportPluginStates 的 `=== 'OK'` 同理 —— previous 永远设不上，状态没变化也重报。
//
// 跑法：node tools/overlay-bridge-test.mjs
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const PLUGIN = join(ROOT, 'app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/index.js')

const src = readFileSync(PLUGIN, 'utf8')

// 只从源码里取出函数来跑：不给插件加 export，避免动它的模块契约
// （cordis 按 package.json 的 cordis.entry 加载，额外导出没必要冒这个险）。
const m = src.match(/function unwrap\(raw\) \{[\s\S]*?\n\}/)
if (!m) {
  console.error('✗ 找不到 unwrap()：插件被改动过，测试需要跟上')
  process.exit(1)
}
// eslint-disable-next-line no-new-func
const unwrap = new Function(`return (${m[0].replace(/^function unwrap/, 'function')})`)()

const cases = [
  ['{"result":"DISABLED"}', 'DISABLED', '开关被用户关掉'],
  ['{"result":"NO_PERMISSION"}', 'NO_PERMISSION', '没给悬浮窗权限'],
  ['{"result":"SKIP_REASONING"}', 'SKIP_REASONING', '暂时不看思考过程'],
  ['{"result":"OK"}', 'OK', '插件状态上报成功'],
  ['{"result":"TEXT 你好"}', 'TEXT 你好', '中文正常取出'],
  ['{"result":"TEXT 第一行\\n第二行"}', 'TEXT 第一行\n第二行', '换行要还原'],
  ['{"result":"TEXT a\\"b"}', 'TEXT a"b', '引号要还原'],
  ['{"result": 42}', '{"result": 42}', 'result 不是字符串 —— 原样返回，不编造'],
  ['{"other":"DISABLED"}', '{"other":"DISABLED"}', '没有 result 字段 —— 原样返回'],
  ['  {"result":"OK"}  ', 'OK', '首尾空白'],
  ['DISABLED', 'DISABLED', '纯文本兜底（桥将来若改格式）'],
  ['{坏 JSON', '{坏 JSON', '解析失败原样返回，不抛'],
  ['', '', '空响应'],
  [null, '', 'null 兜底'],
]

let bad = 0
for (const [input, want, why] of cases) {
  let got
  try { got = unwrap(input) } catch (e) { got = 'THREW ' + e.message }
  if (got !== want) {
    bad++
    console.error(`✗ ${why}\n   输入 ${JSON.stringify(input)}\n   得到 ${JSON.stringify(got)}\n   期望 ${JSON.stringify(want)}`)
  }
}

// 真正出过事故的形态：包了 JSON 的返回体不能再拿整段做等值比较。
const forbidden = [
  /(await\s+res\.text\(\))\.trim\(\)\s*\n\s*if \(body === 'DISABLED'/,
  /await\s+response\.text\(\)\)\.trim\(\)\s*===\s*'OK'/,
]
for (const re of forbidden) {
  if (re.test(src)) {
    bad++
    console.error(`✗ 桥返回体仍按纯文本比较：${re}`)
  }
}

// 两处判据都必须走 unwrap：冷却与上报去重各一条。
const mustCall = [
  /const body = unwrap\(await res\.text\(\)\)/,
  /unwrap\(await response\.text\(\)\)\s*===\s*'OK'/,
]
for (const re of mustCall) {
  if (!re.test(src)) {
    bad++
    console.error(`✗ 缺少经 unwrap 的桥判据：${re}`)
  }
}

if (bad) { console.error(`✗ ${bad} 条不通过`); process.exit(1) }
console.log(`✓ unwrap ${cases.length} 条断言 + ${forbidden.length + mustCall.length} 条调用点断言通过`)
