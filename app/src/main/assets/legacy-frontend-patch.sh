#!/bin/sh
# 老 WebView 兼容：把 dsh 前端的现代产物降级成非 module 的 IIFE bundle。
#
# 为什么需要：dsh 前端是 Vite 打出来的现代产物 —— <script type="module"> 加上
# AbortSignal.any 这类新 API。Android 的系统 WebView 版本跟着系统走，Chrome < 118
# 的机器上前端直接白屏。原先唯一的兜底是切 GeckoView（APK 内置的浏览器内核），
# 但那要多一份引擎的内存，而 2~3GB 内存的老机器恰恰最吃紧这个。
# 这条路把前端降到老 WebView 能懂的语法，不需要额外内核。
#
# 思路参考 woaiys3/deepseek-harness-android-app 的 legacy-patch（MIT）。差异：
# 它在 PC 上用 esbuild 预构建、产物打进 APK；我们的前端产物在 rootfs 里、跟着 dsh
# 版本走，构建期固定不下来，所以在设备上跑容器内的 esbuild。
#
# 幂等：已经降级过就直接退出（认 index.html 里的 index.legacy.js）。
# 可回退：原始 index.html 备份成 index.html.modern，任何一步失败都还原它。
# 退出码：0 成功或已是降级态；2 环境不满足（缺 esbuild / 找不到 dist）；1 构建失败已回退。
set -e

log() { echo "[legacy] $*"; }

# polyfill 内联在这里，不做成伴生文件：assets 脚本的复制机制一次搬一个脚本，
# 多一个伴生文件就多一处会漏的地方 —— 内置插件那次就是漏了一个 lib 文件让 dsh 起不来。
POLYFILL="$(mktemp)"
trap 'rm -f "$POLYFILL"' EXIT
cat > "$POLYFILL" <<'POLYFILL_EOF'
// 老 WebView 兼容 polyfill：Chromium < 118 缺的运行时 API。
// 由 legacy-frontend-patch.sh 通过 esbuild --banner 注入到降级 bundle 头部。
//
// 只补前端实际用到的，不做通用 polyfill —— 每一条都对应一个真实报错。
// 写法刻意用 ES5（var、function），因为这段代码本身不经过转译。
(function () {
  'use strict';

  if (!window.globalThis) window.globalThis = window;

  // Chrome 73 起有。Vite 产物里 Object.fromEntries 用得很多。
  if (!Object.fromEntries) {
    Object.fromEntries = function (entries) {
      var o = {};
      for (var i = 0; i < entries.length; i++) {
        var e = entries[i];
        o[e[0]] = e[1];
      }
      return o;
    };
  }

  // Chrome 76 起有。
  if (typeof Promise !== 'undefined' && !Promise.allSettled) {
    Promise.allSettled = function (ps) {
      return Promise.all(Array.prototype.map.call(ps, function (p) {
        return Promise.resolve(p).then(
          function (v) { return { status: 'fulfilled', value: v }; },
          function (r) { return { status: 'rejected', reason: r }; }
        );
      }));
    };
  }

  // Chrome 71 起有。
  if (!window.queueMicrotask) {
    window.queueMicrotask = function (fn) { Promise.resolve().then(fn); };
  }

  // Chrome 92 起有。
  if (!Array.prototype.at) {
    Array.prototype.at = function (i) {
      var n = Math.trunc(+i) || 0;
      var len = this.length >>> 0;
      var k = n < 0 ? len + n : n;
      return (k < 0 || k >= len) ? undefined : this[k];
    };
  }
  if (!String.prototype.at) {
    String.prototype.at = function (i) {
      var n = Math.trunc(+i) || 0;
      var len = this.length >>> 0;
      var k = n < 0 ? len + n : n;
      return (k < 0 || k >= len) ? undefined : this.charAt(k);
    };
  }

  // Chrome 93 起有。structuredClone 在部分组件里用于深拷贝。
  if (!window.structuredClone) {
    window.structuredClone = function (v) {
      return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
    };
  }

  // AbortSignal.timeout（Chrome 103）与 AbortSignal.any（Chrome 116）——
  // **这两条是我们把系统 WebView 阈值定在 118 的直接原因**：dsh 前端在带取消的
  // remote 调用上直接用它们，老 WebView 上会抛
  //   "AbortSignal.any is not a function"
  // 工作区选择这类操作就此失效。
  if (typeof AbortSignal !== 'undefined' && typeof AbortController !== 'undefined') {
    if (!AbortSignal.timeout) {
      AbortSignal.timeout = function (ms) {
        var c = new AbortController();
        setTimeout(function () {
          try {
            c.abort(new DOMException('signal timed out', 'TimeoutError'));
          } catch (e) {
            c.abort();      // 老 WebView 的 abort() 不接受 reason 参数
          }
        }, ms);
        return c.signal;
      };
    }
    if (!AbortSignal.any) {
      AbortSignal.any = function (signals) {
        var c = new AbortController();
        var list = Array.prototype.slice.call(signals);
        for (var i = 0; i < list.length; i++) {
          var s = list[i];
          if (!s) continue;
          if (s.aborted) {
            try { c.abort(s.reason); } catch (e) { c.abort(); }
            return c.signal;
          }
          /* eslint-disable no-loop-func */
          (function (sig) {
            sig.addEventListener('abort', function () {
              try { c.abort(sig.reason); } catch (e) { c.abort(); }
            });
          })(s);
        }
        return c.signal;
      };
    }
  }
})();
POLYFILL_EOF

# ── 1. 找前端 dist ──────────────────────────────────────────────
# 路径跟着 dsh 版本走，所以按候选依次试，最后用 find 兜底（限深度，rootfs 里目录很多）。
DIST=""
for c in \
  /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist \
  /usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist
do
  if [ -f "$c/index.html" ]; then DIST="$c"; break; fi
done
if [ -z "$DIST" ]; then
  DIST=$(find /usr/local/lib/node_modules /usr/lib/node_modules -maxdepth 6 \
           -type d -name dist -path '*dsh-web-frontend*' 2>/dev/null | head -1)
fi
[ -n "$DIST" ] && [ -f "$DIST/index.html" ] || { log "找不到前端 dist，跳过"; exit 2; }
log "dist: $DIST"

# ── 2. 幂等检查 ────────────────────────────────────────────────
if grep -q 'index\.legacy\.js' "$DIST/index.html" 2>/dev/null; then
  log "已经是降级态，无需处理"
  exit 0
fi

# ── 3. 环境检查 ────────────────────────────────────────────────
ESBUILD=""
if command -v esbuild >/dev/null 2>&1; then
  ESBUILD="esbuild"
elif [ -x /usr/local/lib/node_modules/esbuild/bin/esbuild ]; then
  ESBUILD="/usr/local/lib/node_modules/esbuild/bin/esbuild"
fi
if [ -z "$ESBUILD" ]; then
  # 没有就现装一份。老 rootfs 升级上来的用户会走到这里，需要网络。
  log "没有 esbuild，尝试安装"
  npm install -g esbuild --no-audit --no-fund >/dev/null 2>&1 || true
  command -v esbuild >/dev/null 2>&1 && ESBUILD="esbuild"
fi
[ -n "$ESBUILD" ] || { log "esbuild 不可用（装不上也没内置），跳过"; exit 2; }

# ── 4. 找 module 入口 ──────────────────────────────────────────
# Vite 产物形如 <script type="module" crossorigin src="/assets/index-XXXX.js"></script>
ENTRY=$(grep -oE 'src="[^"]*assets/index-[A-Za-z0-9_-]+\.js"' "$DIST/index.html" \
        | head -1 | sed -E 's/^src="(.*)"$/\1/')
[ -n "$ENTRY" ] || { log "index.html 里找不到 module 入口，跳过"; exit 2; }
ENTRY_ABS="$DIST/${ENTRY#/}"
[ -f "$ENTRY_ABS" ] || { log "入口文件不存在：$ENTRY_ABS"; exit 2; }
log "入口: $ENTRY"

# ── 5. 备份原始 index.html ─────────────────────────────────────
# 只在第一次备份：后面重跑时 index.html 已经是改写过的，再备份就把原始版覆盖没了。
[ -f "$DIST/index.html.modern" ] || cp "$DIST/index.html" "$DIST/index.html.modern"

restore() {
  log "失败，还原 index.html"
  [ -f "$DIST/index.html.modern" ] && cp "$DIST/index.html.modern" "$DIST/index.html"
  rm -f "$DIST/assets/index.legacy.js"
}

# ── 6. 降级构建 ────────────────────────────────────────────────
# **入口必须用相对路径**：传绝对路径时 esbuild 会把它当模块说明符去解析，然后报
# `Could not resolve "<那个绝对路径>"`（esbuild 0.28.2 实测，容器里的 Ubuntu 24.04）。
# 所以先 cd 进 dist，再用 ./assets/xxx.js。输出也用相对路径 —— 写 /tmp 会 permission denied，
# 而 dist/assets 我们本来就有写权限。
# --format=iife：老 WebView 不认 <script type="module">
# --target=chrome61：可选链、空值合并、async/await、类字段都会被转译掉
#   （比参考实现的 chrome51 略高：Android 8.0 自带的 WebView 就是 Chrome 61，
#     再往下压会让产物更大而我们的 minSdk 是 26，跑不到那些机器上）
# --banner:js：polyfill 必须在 bundle 之前执行
cd "$DIST" || { log "进不去 dist：$DIST"; exit 2; }
if ! "$ESBUILD" ".$ENTRY" \
      --bundle \
      --format=iife \
      --target=chrome61 \
      --minify \
      --banner:js="$(cat "$POLYFILL")" \
      --outfile="assets/index.legacy.js" \
      --log-level=warning
then
  restore
  exit 1
fi

SIZE=$(wc -c < "$DIST/assets/index.legacy.js" 2>/dev/null || echo 0)
[ "$SIZE" -gt 10000 ] || { log "产物太小（$SIZE 字节），不可信"; restore; exit 1; }
log "产物: assets/index.legacy.js（$SIZE 字节）"

# ── 7. 改写 index.html ─────────────────────────────────────────
# module script → 普通 script；modulepreload 全删（老 WebView 不认，留着只是噪声）。
# CSS、favicon、以及我们自己注入的 mobile client 引用都要原样保留。
awk '
  /<script[^>]*type="module"/ { print "    <script src=\"/assets/index.legacy.js\"></script>"; next }
  /<link[^>]*rel="modulepreload"/ { next }
  { print }
' "$DIST/index.html.modern" > "$DIST/index.html.tmp"

if ! grep -q 'index\.legacy\.js' "$DIST/index.html.tmp"; then
  log "改写后没有 legacy 引用，说明 index.html 结构和预期不同"
  rm -f "$DIST/index.html.tmp"
  restore
  exit 1
fi
mv "$DIST/index.html.tmp" "$DIST/index.html"

# 成功标记给 App 侧看：它据此决定还要不要退回 GeckoView。
# 写文件而不是靠退出码，是因为 App 侧读文件比解析脚本输出可靠，
# 而且重启之后这个事实还在。
mkdir -p /root/.dsh 2>/dev/null || true
date '+%Y-%m-%d %H:%M:%S' > /root/.dsh/.legacy-frontend-ok 2>/dev/null || true

log "完成：老 WebView 现在可以直接用系统内核打开 WebUI"
exit 0
