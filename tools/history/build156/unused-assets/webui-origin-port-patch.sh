#!/bin/bash
# webui-origin-port-patch.sh — 修复外部浏览器（Chrome 150+）访问 127.0.0.1:3080 时
# 所有 /api 请求 403 的问题。
#
# 根因：dsh-client-connection 的 isTrustedApiRequest 在校验 Origin 时
#   用 new URL(origin).host === hostUrl.host 比较。Chrome 150+ 对 loopback
#   同源请求发送的 Origin 会省略端口（http://127.0.0.1），而 Host 是
#   127.0.0.1:3080，导致 host 比较失败 -> /api 全部 403 -> 前端表现为
#   “无法连接网络”。App 内置 WebView（旧版 Chromium）不受影响。
#
# 修复：改为比较 hostname（端口不是 CSRF / DNS-rebinding 边界）。
# 幂等：已改则跳过；LAN 补丁已把函数改为 return true 时同样跳过。
set -u

PATCHED=0
ALREADY=0

patch_file() {
  F="$1"
  [ -n "$F" ] && [ -f "$F" ] || return 0
  if grep -q 'new URL(origin)\.hostname === hostUrl\.hostname' "$F"; then
    ALREADY=$((ALREADY + 1))
    echo "ORIGIN_PORT_ALREADY: $F"
    return 0
  fi
  if grep -q 'new URL(origin)\.host === hostUrl\.host' "$F"; then
    sed -i 's|new URL(origin)\.host === hostUrl\.host|new URL(origin).hostname === hostUrl.hostname|' "$F"
    if grep -q 'new URL(origin)\.hostname === hostUrl\.hostname' "$F"; then
      PATCHED=$((PATCHED + 1))
      echo "ORIGIN_PORT_PATCHED: $F"
    else
      echo "ORIGIN_PORT_PATCH_FAILED: $F"
    fi
  else
    # 找不到原字符串：可能是 LAN 补丁已把 isTrustedApiRequest 改为 return true，
    # 或 dsh 新版本已修复/改写法。不算失败。
    echo "ORIGIN_PORT_SKIP: $F"
  fi
}

# ===== 1) 快速路径：RC6 npm 全局安装（扁平/嵌套两种布局都覆盖） + 源码树 =====
for F in \
  $(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-client-connection/lib/index.js' 2>/dev/null | head -5) \
  /root/deepseek-harness/packages/client/connection/lib/index.js; do
  patch_file "$F"
done

# ===== 2) 全量兜底：dsh 版本/路径变化时仍能命中 =====
FALLBACK=$(grep -rl 'new URL(origin)\.host === hostUrl\.host' \
  /usr/local/lib/node_modules/@deepseek-ai /root/deepseek-harness/packages \
  --include='*.js' 2>/dev/null | head -20)
for F in $FALLBACK; do
  patch_file "$F"
done

if [ "$PATCHED" -gt 0 ]; then
  echo "ORIGIN_PORT_OK:${PATCHED}"
  exit 0
fi
if [ "$ALREADY" -gt 0 ]; then
  echo "ORIGIN_PORT_ALREADY"
  exit 0
fi
echo "ORIGIN_PORT_DONE"
exit 0
