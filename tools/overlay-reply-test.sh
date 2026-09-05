#!/usr/bin/env bash
# 就地回话链路的咽喉：插件怎么读桥的返回值。
#
# 为什么单独给它一个测试：桥返回的是 {"result":"..."}，而插件原来直接拿整段 body
# 去和 'DISABLED' 做等值比较 —— 永远不成立。这个错误不会报任何异常、不会崩、
# 日志里也看不出来，表现只是「功能没反应」和「关了开关照样每 120ms 发一次 HTTP」。
# 这类静默失效正是断言该守的地方。
set -euo pipefail
cd "$(dirname "$0")/.."
PLUGIN=app/src/main/assets/status-overlay/lib/index.js

node --check "$PLUGIN"

node - "$PLUGIN" <<'JS'
const fs = require('node:fs')
const src = fs.readFileSync(process.argv[2], 'utf8')
const m = src.match(/function unwrap\(raw\) \{[\s\S]*?\n\}/)
if (!m) { console.error('✗ 找不到 unwrap()：插件被改动过，测试需要跟上'); process.exit(1) }
eval(m[0])

const cases = [
  ['{"result":"DISABLED"}', 'DISABLED', '桥的 JSON 包装'],
  ['{"result":"NO_PERMISSION"}', 'NO_PERMISSION', '没给悬浮窗权限'],
  ['{"result":"CLOSED"}', 'CLOSED', '输入栏已收起'],
  ['{"result":"EMPTY"}', 'EMPTY', '还没打完'],
  ['{"result":"TEXT 你好"}', 'TEXT 你好', '中文正常取出'],
  ['{"result":"TEXT 第一行\\n第二行"}', 'TEXT 第一行\n第二行', '换行要还原（用户会打多行）'],
  ['{"result":"TEXT a\\"b"}', 'TEXT a"b', '引号要还原'],
  ['  {"result":"OK"}  ', 'OK', '首尾空白'],
  ['DISABLED', 'DISABLED', '纯文本兜底（桥将来若改格式）'],
  ['{坏 JSON', '{坏 JSON', '解析失败原样返回，不抛'],
  ['', '', '空响应'],
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
if (bad) { console.error(`✗ unwrap ${bad}/${cases.length} 条不通过`); process.exit(1) }
console.log(`✓ unwrap ${cases.length} 条断言通过`)
JS
