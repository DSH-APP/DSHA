#!/bin/bash
# DSHA：给 dsh 的 Web 服务加 token 鉴权。
#
# 为什么必须做：上游 dsh 的 webserver 只做了「绑定 127.0.0.1」，请求处理里没有
# 任何 token / Origin / Referer 校验（packages/host/webserver：createServer 直接
# 交给 handle）。这在桌面上还算合理，在 Android 上完全不够 ——
# **任何应用访问 localhost 都不需要声明权限**，于是手机里随便一个 App 都能：
#   · 读走全部会话历史（含 API key 与私密对话）
#   · 新建会话让 agent 执行任意 bash（等于完全控制这台设备的容器）
#   · 调用 agent 的所有工具
# 同类项目（wowokcode/dsh-mobile）已经给 3080 加了 token 鉴权，我们反而只锁了
# 自己的 3090，dsh 本体是裸的。这个补丁补上这一层。
#
# 放行方式（三者任一）：
#   · URL 带 ?dsha_t=<token>   —— WebView 首帧用，命中后回设 Cookie
#   · Cookie: dsha_t=<token>   —— 后续请求与 WebSocket upgrade 自动带
#   · X-Dsha-Token: <token>    —— 脚本/curl 用
# token 就是 App 已经在用的 /root/.dsh/.bridge_token。
#
# 故意留的后门：token 文件不存在时**放行**。否则桌面版 dsh、或桥还没生成 token
# 的首启瞬间会被自己锁死在外面，代价是一个白屏没法自查的死局。
set -u
LOG=/root/.dsh/webserver-auth-patch.log
mkdir -p /root/.dsh 2>/dev/null || true
log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

W=$(find /usr/local/lib/node_modules/@deepseek-ai -path "*dsh-host-webserver/lib/index.js" 2>/dev/null | head -1)
if [ -z "$W" ]; then
  W=$(find /root -maxdepth 7 -path "*dsh-host-webserver/lib/index.js" 2>/dev/null | head -1)
fi
if [ -z "$W" ] || [ ! -f "$W" ]; then
  log "未找到 dsh-host-webserver/lib/index.js，跳过"
  echo WEBAUTH_SKIP
  exit 0
fi
if grep -q 'DSHA_WEB_AUTH' "$W"; then
  echo WEBAUTH_ALREADY
  exit 0
fi

cp -f "$W" "$W.dsha-bak" 2>/dev/null || true

python3 - "$W" <<'PY'
import re
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()

# ① 顶部注入：读 token + 三种放行方式的校验函数。
#    这是 ESM，不能用 require —— 顺着已有的 import 区加一条 node:fs 具名导入。
FN = r'''
/* DSHA_WEB_AUTH —— 回环≠安全：Android 上任何 App 都能访问 127.0.0.1 且无需权限，
   上游 webserver 又没有任何鉴权。没这层校验，随便一个应用就能读走全部会话、
   建会话让 agent 执行 bash。token 复用 App 的 /root/.dsh/.bridge_token。 */
const __DSHA_TOKEN_PATH = "/root/.dsh/.bridge_token";
let __dshaTokenCache;
const __dshaToken = () => {
  if (__dshaTokenCache !== undefined) return __dshaTokenCache;
  try {
    __dshaTokenCache = __dshaReadFileSync(__DSHA_TOKEN_PATH, "utf8").trim();
  } catch {
    __dshaTokenCache = "";   // 没有 token 文件：桌面版或桥未就绪 → 放行，别把自己锁死
  }
  return __dshaTokenCache;
};
const __dshaAuthOk = (req, res) => {
  const tok = __dshaToken();
  if (!tok) return true;
  const url = req.url || "/";
  const qi = url.indexOf("dsha_t=");
  if (qi >= 0) {
    const got = decodeURIComponent(url.slice(qi + 7).split("&")[0].split("#")[0]);
    if (got === tok) {
      // 回设 Cookie：之后的静态资源、XHR 和 WebSocket upgrade 都会自动带上，
      // 页面里不必到处拼 token（也就不会把 token 泄进前端代码或历史记录）
      try {
        res?.setHeader?.("Set-Cookie",
          "dsha_t=" + tok + "; Path=/; SameSite=Strict; Max-Age=31536000");
      } catch { /* upgrade 时没有 res，忽略 */ }
      return true;
    }
  }
  const cookie = req.headers?.cookie || "";
  for (const part of cookie.split(";")) {
    if (part.trim() === "dsha_t=" + tok) return true;
  }
  if (req.headers?.["x-dsha-token"] === tok) return true;
  return false;
};
'''

m = re.search(r'^import .*?;$', src, re.M)
if m is None:
    print("NO_IMPORT_BLOCK")
    sys.exit(4)
imports_end = 0
for mm in re.finditer(r'^import .*?;$', src, re.M):
    imports_end = mm.end()
src = (src[:imports_end]
       + '\nimport { readFileSync as __dshaReadFileSync } from "node:fs";\n'
       + FN + src[imports_end:])

# ② 普通请求：handle 的第一行就拦
HANDLE = 'const handle = async (req, res) => {'
if HANDLE not in src:
    print("PATTERN_MISS_HANDLE")
    sys.exit(3)
src = src.replace(
    HANDLE,
    HANDLE + '''
\t\t\tif (!__dshaAuthOk(req, res)) {
\t\t\t\tres.writeHead(403, { "Content-Type": "text/plain; charset=utf-8" });
\t\t\t\tres.end("DSHA: 需要 token。请在 DSHA 应用内打开，或在 URL 后加 ?dsha_t=<token>");
\t\t\t\treturn;
\t\t\t}''', 1)

# ③ WebSocket upgrade：漏了这条等于白做（会话流是走 WS 的）
UP = "this.server.on('upgrade', (req, socket, head) => {"
if UP not in src:
    UP = 'this.server.on("upgrade", (req, socket, head) => {'
if UP not in src:
    print("PATTERN_MISS_UPGRADE")
    sys.exit(5)
src = src.replace(
    UP,
    UP + '''
\t\t\tif (!__dshaAuthOk(req, undefined)) {
\t\t\t\tsocket.destroy();
\t\t\t\treturn;
\t\t\t}''', 1)

open(path, 'w', encoding='utf-8').write(src)
print("PATCHED")
PY
rc=$?

if [ "$rc" -ne 0 ]; then
  log "鉴权补丁失败 rc=$rc（dsh 版本可能改了 webserver 结构）"
  [ -f "$W.dsha-bak" ] && cp -f "$W.dsha-bak" "$W"
  echo WEBAUTH_FAIL
  exit 0
fi

cp -f "$W" /tmp/dsha-web-syn.mjs 2>/dev/null
if ! node --check /tmp/dsha-web-syn.mjs 2>>"$LOG"; then
  log "鉴权补丁语法校验未通过，已回滚"
  cp -f "$W.dsha-bak" "$W"
  rm -f /tmp/dsha-web-syn.mjs
  echo WEBAUTH_ROLLBACK
  exit 0
fi
rm -f /tmp/dsha-web-syn.mjs

log "已给 dsh Web 服务加 token 鉴权：$W"
echo WEBAUTH_OK
