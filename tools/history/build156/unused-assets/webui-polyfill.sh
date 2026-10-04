#!/bin/bash
# WebUI 浏览器兼容补丁（在浏览器端 bundle 头部注入 polyfill，幂等）。
#
# v1：AbortSignal.any / AbortSignal.timeout（Chrome 116+ / Safari 17.4+ 才有）。
#     老版本 Android System WebView 没有这两个 API，dsh 前端在选择工作区等带取消的
#     remote 调用上会抛 "AbortSignal.any is not a function"。
#
# v2：crypto.randomUUID。它**只在 secure context 提供** —— 本机
#     http://127.0.0.1 算 secure，但局域网访问是 http://192.168.x.x:3081，不算。
#     上游连接层自己已经绕过这点（client/random-uuid.ts 用 crypto.getRandomValues
#     手搓 UUID，注释里明写「browsers expose on insecure origins」），但
#     ui-conversation 的附件草稿仍在直接调 crypto.randomUUID() —— 局域网下开附件会炸。
#     getRandomValues 在非安全源是可用的，所以补一个 v4 实现即可。
#     （同类项目 FunnelCakes/deepseek-harness-android 也踩过并修了这一条。）
#
# 幂等判据用下面这个版本标记。已经注过 v1 的文件会被再注一次 —— 两段都是
# `if (!X)` 守卫，重复注入只是文件头多几十行，行为不变；这比去解析已有内容再打补丁靠谱。
POLY=$(cat <<'PLY'
/* DSHA_POLYFILL v2 */
if (typeof AbortSignal !== 'undefined' && !AbortSignal.any) {
  AbortSignal.any = function (signals) {
    var ctrl = new AbortController();
    var done = false;
    var onAbort = function () { if (!done) { done = true; ctrl.abort(ctrl.signal.reason); } };
    signals = signals || [];
    for (var i = 0; i < signals.length; i++) {
      var s = signals[i];
      if (s) { if (s.aborted) { onAbort(); } else { s.addEventListener('abort', onAbort, { once: true }); } }
    }
    return ctrl.signal;
  };
}
if (typeof AbortSignal !== 'undefined' && !AbortSignal.timeout) {
  AbortSignal.timeout = function (ms) {
    var ctrl = new AbortController();
    setTimeout(function () { ctrl.abort(new DOMException('Timeout', 'TimeoutError')); }, ms);
    return ctrl.signal;
  };
}
/* 非 secure context（局域网 http://<手机IP>）没有 crypto.randomUUID，
   但 crypto.getRandomValues 有 —— 按 RFC 4122 拼一个 v4 出来。 */
if (typeof crypto !== 'undefined' && !crypto.randomUUID && crypto.getRandomValues) {
  crypto.randomUUID = function () {
    var b = crypto.getRandomValues(new Uint8Array(16));
    b[6] = (b[6] & 0x0f) | 0x40;   // version 4
    b[8] = (b[8] & 0x3f) | 0x80;   // variant 10xx
    var h = [];
    for (var i = 0; i < 16; i++) h.push((b[i] + 0x100).toString(16).slice(1));
    return h[0] + h[1] + h[2] + h[3] + '-' + h[4] + h[5] + '-' + h[6] + h[7]
      + '-' + h[8] + h[9] + '-' + h[10] + h[11] + h[12] + h[13] + h[14] + h[15];
  };
}
PLY
)

inject() {
  F="$1"
  [ -n "$F" ] && [ -f "$F" ] || return 0
  if grep -q 'DSHA_POLYFILL v2' "$F"; then
    echo "跳过（已注入 v2）: $F"
    return 0
  fi
  { echo "$POLY"; cat "$F"; } > "$F.new" && mv "$F.new" "$F"
  echo "已注入 polyfill v2: $F"
}

# ===== 1) 快速路径（最常见部署形态直接命中，O(1)） =====
for F in \
  $(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-client-connection/lib/client.js' 2>/dev/null | head -1) \
  $(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-api-gateway/lib/client.js' 2>/dev/null | head -1) \
  /root/deepseek-harness/packages/client/connection/lib/client.js \
  /root/deepseek-harness/packages/api/gateway/lib/client.js; do
  inject "$F"
done

# ===== 2) 全量兜底（dsh 版本/路径变化时仍能命中） =====
# 快速路径未命中或未覆盖时，扫描所有 @deepseek-ai 编译产物与源码树里
# 实际调用 AbortSignal.any/timeout 的 JS 文件，逐个幂等注入。
FALLBACK=$(grep -rlE 'AbortSignal\.(any|timeout)|crypto\.randomUUID' \
  /usr/local/lib/node_modules/@deepseek-ai /root/deepseek-harness/packages \
  --include='*.js' 2>/dev/null | head -40)
for F in $FALLBACK; do
  inject "$F"
done

echo "POLYFILL_DONE"
