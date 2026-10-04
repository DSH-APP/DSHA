#!/bin/bash
# webui-degrade-patch.sh — 前端「插件失败降级」热补丁（对编译产物打，RC6/源码通用）。
# 目标：web client bundle 里
#   if (failures.length > 0) throw new Error(`web boot: ...did not activate...`);
# 改为 console.warn(...) —— 某个插件加载失败不再卡死整个 HARNESS（fail-loud → continue），
# 其余插件照常加载；失败插件仅缺那一块并打印警告。
# 幂等：已打过（含 dsha-degrade 标记）跳过；无命中输出 DEGRADE_UNSUPPORTED（不视为失败）。
set -u

PATCHED=0
FOUND=0
FILES=""
# RC6 / npm 全局包 + 源码构建（client-web bundle 所在）
FILES="$(grep -rl 'did not activate' \
  /usr/local/lib/node_modules/@deepseek-ai /root/deepseek-harness /root/*/packages \
  --include='*.js' 2>/dev/null | head -10)"

for F in $FILES; do
  [ -n "$F" ] && [ -f "$F" ] || continue
  FOUND=1
  grep -q 'dsha-degrade' "$F" && continue
  # 关键替换：throw new Error(`web boot:  →  console.warn("[dsha-degrade]", `web boot:
  sed -i 's|if (failures.length > 0) throw new Error(`web boot:|if (failures.length > 0) console.warn("[dsha-degrade] web boot degraded, continuing:", `web boot:|g' "$F"
  grep -q 'dsha-degrade' "$F" && PATCHED=$((PATCHED + 1)) && echo "DEGRADE_PATCHED:$F"
done

if [ "$PATCHED" -gt 0 ]; then
  echo "DEGRADE_OK:${PATCHED}"
  exit 0
fi
if [ "$FOUND" -gt 0 ]; then
  echo "DEGRADE_NO_MATCH"
  exit 0
fi
echo "DEGRADE_UNSUPPORTED"
exit 0
