#!/bin/bash
# session-home.sh 的行为断言：在沙箱里真建目录、真跑脚本、真查文件。
#
# 这条测试存在的理由：它动的是**用户已有的会话数据**（从公开目录搬回私有），
# 而这个仓库在这类事上栽过 —— 备份里其实没有对话（软链没解引用）、
# 恢复时把刚恢复的数据当旧副本删掉。所以每条分支都要有断言，尤其是失败路径。
set -u

S="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/session-home.sh"
B="${TMPDIR:-/tmp}/dsha-session-home-test.$$"
pass=0; fail=0

ok()   { pass=$((pass+1)); printf '  ok   %s\n' "$1"; }
bad()  { fail=$((fail+1)); printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; }
chk()  { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "期望 [$3] 实际 [$2]"; fi; }

fresh() {
  rm -rf "$B"
  mkdir -p "$B/home" "$B/pub"
  export DSH_HOME_DIR="$B/home" DSH_PUB_DIR="$B/pub"
}
run() { bash "$S" "$@" 2>&1; }

echo "=== session-home.sh ==="

# ---- 1 首次：两边都没有 ----
fresh
out=$(run ensure)
case "$out" in *SESSIONS_NEW*) ok "首次：建私有目录";; *) bad "首次：建私有目录" "$out";; esac
[ -d "$B/home/sessions" ] && [ ! -L "$B/home/sessions" ] \
  && ok "首次：是实体目录不是软链" || bad "首次：是实体目录不是软链"

# ---- 2 现状升级路径（最关键）：私有是软链 → 公开有数据 ----
fresh
mkdir -p "$B/pub/sessions/sess-a"
echo '{"header":1}' > "$B/pub/sessions/sess-a/session.jsonl"
echo 'stale' > "$B/pub/sessions/session.lock"
ln -s "$B/pub/sessions" "$B/home/sessions"
out=$(run ensure)
case "$out" in *SESSIONS_MIGRATED*) ok "升级：报告已搬回";; *) bad "升级：报告已搬回" "$out";; esac
[ -d "$B/home/sessions" ] && [ ! -L "$B/home/sessions" ] \
  && ok "升级：私有侧变成实体目录（flock 可用的前提）" \
  || bad "升级：私有侧变成实体目录" "还是软链或不存在"
chk "升级：会话内容跟着搬过来了" \
  "$(cat "$B/home/sessions/sess-a/session.jsonl" 2>/dev/null)" '{"header":1}'
[ -e "$B/home/sessions/session.lock" ] \
  && bad "升级：陈旧锁文件不该搬过来" || ok "升级：陈旧锁文件没搬过来"
[ -f "$B/pub/sessions/sess-a/session.jsonl" ] \
  && ok "升级：公开侧原数据保留（成为镜像）" || bad "升级：公开侧原数据保留"

# ---- 3 幂等：再跑一次不应改变什么 ----
out=$(run ensure)
case "$out" in *SESSIONS_OK*) ok "幂等：第二次报告已就位";; *) bad "幂等：第二次报告已就位" "$out";; esac
chk "幂等：内容没被动过" \
  "$(cat "$B/home/sessions/sess-a/session.jsonl" 2>/dev/null)" '{"header":1}'

# ---- 4 卸载重装：私有整个没了，公开镜像还在 ----
fresh
mkdir -p "$B/pub/sessions/sess-b"
echo 'kept' > "$B/pub/sessions/sess-b/session.jsonl"
out=$(run ensure)
case "$out" in *SESSIONS_RESTORED*) ok "重装：从镜像恢复";; *) bad "重装：从镜像恢复" "$out";; esac
chk "重装：数据回到私有目录" "$(cat "$B/home/sessions/sess-b/session.jsonl" 2>/dev/null)" 'kept'
[ -L "$B/home/sessions" ] && bad "重装：不该恢复成软链" || ok "重装：恢复成实体目录"

# ---- 5 悬空软链 ----
fresh
ln -s "$B/nonexistent-target" "$B/home/sessions"
out=$(run ensure)
case "$out" in *SESSIONS_NEW*) ok "悬空软链：换成空目录";; *) bad "悬空软链：换成空目录" "$out";; esac
[ -d "$B/home/sessions" ] && [ ! -L "$B/home/sessions" ] \
  && ok "悬空软链：结果是实体目录" || bad "悬空软链：结果是实体目录"

# ---- 6 私有位置被一个普通文件占着 ----
fresh
echo 'junk' > "$B/home/sessions"
out=$(run ensure)
[ -d "$B/home/sessions" ] && ok "占位文件：已挪开并建目录" || bad "占位文件：已挪开并建目录" "$out"
ls "$B/home"/sessions.notadir.* >/dev/null 2>&1 \
  && ok "占位文件：原文件留了副本没删" || bad "占位文件：原文件留了副本没删"

# ---- 7 sync：私有 → 镜像 ----
fresh
run ensure >/dev/null
mkdir -p "$B/home/sessions/sess-c"
echo 'new-turn' > "$B/home/sessions/sess-c/session.jsonl"
echo 'lock' > "$B/home/sessions/session.lock"
out=$(run sync)
case "$out" in *SYNC_OK*) ok "同步：报告成功";; *) bad "同步：报告成功" "$out";; esac
chk "同步：新会话进了镜像" "$(cat "$B/pub/sessions/sess-c/session.jsonl" 2>/dev/null)" 'new-turn'
[ -e "$B/pub/sessions/session.lock" ] \
  && bad "同步：锁文件不该进镜像" || ok "同步：锁文件没进镜像"

# ---- 8 sync 不删镜像里多出来的东西（删除不可逆，宁可多留）----
mkdir -p "$B/pub/sessions/sess-old"
echo 'old' > "$B/pub/sessions/sess-old/session.jsonl"
run sync >/dev/null
[ -f "$B/pub/sessions/sess-old/session.jsonl" ] \
  && ok "同步：不删镜像里多出来的会话" || bad "同步：不删镜像里多出来的会话"

# ---- 9 公开目录不可写：ensure 仍要成功，sync 要明确跳过 ----
fresh
run ensure >/dev/null
chmod 500 "$B/pub" 2>/dev/null
out=$(run sync)
case "$out" in *SYNC_SKIP*) ok "只读公开目录：同步明确跳过而不是假装成功";;
                *SYNC_OK*)  ok "只读公开目录：本环境仍可写（跳过该断言）";;
                *) bad "只读公开目录：同步应跳过" "$out";; esac
chmod 700 "$B/pub" 2>/dev/null
out=$(run ensure)
case "$out" in *SESSIONS_OK*) ok "只读公开目录：会话本身照常可用";; *) bad "只读公开目录：会话本身照常可用" "$out";; esac

# ---- 10 status 的形状（自检与 UI 要解析它）----
fresh
run ensure >/dev/null
out=$(run status)
case "$out" in *"kind=dir"*) ok "status：报告 kind=dir";; *) bad "status：报告 kind=dir" "$out";; esac
case "$out" in *"pub="*) ok "status：报告公开目录可写性";; *) bad "status：报告公开目录可写性" "$out";; esac

rm -rf "$B"
echo "----------------------------------------------"
if [ "$fail" -gt 0 ]; then
  echo "失败 $fail 条，通过 $pass 条"
  exit 1
fi
echo "全部通过：$pass 条"
