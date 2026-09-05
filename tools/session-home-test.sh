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

# ---- 11 悬空软链（真机故障回归：dsh 因此起不来）----
# storages/attachments/settings.yaml 仍然软链到公开目录。软链一旦悬空，
# dsh 的 storage 后端对它 mkdir(recursive) 得到 ENOENT（不是 EEXIST），
# 插件树加载失败、整个 dsh 起不来。旧实现「是软链就跳过」正好把这条漏掉。
mkdirok() {  # 模拟 dsh 实际做的事：fs.mkdir(path, { recursive: true })
  node -e "
    const fs=require('fs');
    try { fs.mkdirSync(process.argv[1],{recursive:true}); console.log('OK'); }
    catch(e){ console.log(e.code); }
  " "$1" 2>/dev/null
}

fresh
run ensure >/dev/null
ln -s "$B/pub/nonexistent/storages" "$B/home/storages"
chk "悬空软链：先确认它真的会让 dsh 的 mkdir 失败" "$(mkdirok "$B/home/storages")" "ENOENT"
out=$(run links)
case "$out" in *LINK_CLEARED*) ok "悬空软链：目标不可达时被清除";; *) bad "悬空软链：应被清除" "$out";; esac
chk "悬空软链：清除后 dsh 能建目录了" "$(mkdirok "$B/home/storages")" "OK"

# ---- 12 悬空但公开侧有数据 → 重新指过去，不能丢数据 ----
fresh
run ensure >/dev/null
mkdir -p "$B/pub/storages"
echo 'keep-me' > "$B/pub/storages/unit.json"
ln -s "$B/pub/wrong-path/storages" "$B/home/storages"
out=$(run links)
case "$out" in *LINK_REPAIRED*) ok "悬空软链：公开侧有数据时重新指过去";; *) bad "悬空软链：应重新指向" "$out";; esac
chk "悬空软链：公开侧数据没丢" "$(cat "$B/home/storages/unit.json" 2>/dev/null)" 'keep-me'

# ---- 13 有效软链与实体目录都不该被动 ----
fresh
run ensure >/dev/null
mkdir -p "$B/pub/attachments"; echo 'a' > "$B/pub/attachments/x"
ln -s "$B/pub/attachments" "$B/home/attachments"
mkdir -p "$B/home/storages"; echo 'b' > "$B/home/storages/y"
run links >/dev/null
[ -L "$B/home/attachments" ] && [ -e "$B/home/attachments/x" ] \
  && ok "有效软链：原样保留" || bad "有效软链：原样保留"
[ -d "$B/home/storages" ] && [ ! -L "$B/home/storages" ] && [ -f "$B/home/storages/y" ] \
  && ok "实体目录：原样保留" || bad "实体目录：原样保留"

# ---- 14 ensure 顺带做软链体检（启动路径上只调一次）----
fresh
ln -s "$B/pub/gone/settings.yaml" "$B/home/settings.yaml"
out=$(run ensure)
case "$out" in *LINKS_OK*) ok "ensure：顺带跑了软链体检";; *) bad "ensure：应顺带跑软链体检" "$out";; esac
[ ! -L "$B/home/settings.yaml" ] \
  && ok "ensure：悬空的 settings.yaml 已清除" || bad "ensure：悬空的 settings.yaml 已清除"

rm -rf "$B"
echo "----------------------------------------------"
if [ "$fail" -gt 0 ]; then
  echo "失败 $fail 条，通过 $pass 条"
  exit 1
fi
echo "全部通过：$pass 条"
