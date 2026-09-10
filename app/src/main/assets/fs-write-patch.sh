#!/bin/bash
# DSHA：让 dsh 在 proot 环境下彻底不用 link() 发布文件。
#
# 背景：Android 私有目录禁止真硬链接（link() 直接 AccessDeniedException），
# 所以 proot 必须开 --link2symlink，把 link() 模拟成
#   目标(symlink) → .l2s.<名>.<hash>.tmp0001(symlink) → ….0001(真实数据)
# 这条链（末 4 位数字就是 proot 模拟的 nlink 计数）。而 dsh 有两处用
# link(临时文件, 目标) 做原子发布：
#   1. dsh-fs-local                  → agent 的 write 工具
#   2. dsh-session-persistence-jsonl → 会话日志
# 由此产生两类故障：
#   · 发布后临时目录被清理 → 目标悬空 → 读取报 ENOENT
#   · .l2s 链残留在 .dsh 里 → tar 遍历时报 ELOOP
#     （Too many levels of symbolic links）→ 备份 100% 失败
#
# 修法：一律 rename 发布。rename 是原子覆盖（POSIX），既不悬空也不产生 symlink。
# 早期版本保留了「目标已存在 → 仍走 link」的分支，想留住 link 的 EEXIST 并发
# 语义 —— 但在 --link2symlink 下 link() 只是 symlink 模拟，那层保护本就不成立，
# 留着只会继续制造 .l2s 链。故 FIX3/FIX4 起彻底去掉该分支。
#
# 幂等：打过新标记就跳过；识别并升级老标记（FIX/FIX2）；语法校验不过自动回滚。
set -u
LOG=/root/.dsh/fs-write-patch.log
mkdir -p /root/.dsh 2>/dev/null || true

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

# ESM 语法校验：index.js 是 ESM，必须以 .mjs 复制后再 --check（否则按 CJS 解析必报错）
check_syntax() {
  cp -f "$1" /tmp/dsha-syn-check.mjs 2>/dev/null || return 1
  node --check /tmp/dsha-syn-check.mjs 2>>"$LOG"
  rc=$?
  rm -f /tmp/dsha-syn-check.mjs
  return $rc
}

# 在常见布局里找包文件（rootfs 的 node_modules 巨大，全目录 find 要几十秒）
locate_pkg() {
  sub="$1"
  for P in \
    "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/$sub/lib/index.js" \
    "/usr/local/lib/node_modules/@deepseek-ai/$sub/lib/index.js" ; do
    if [ -f "$P" ]; then echo "$P"; return 0; fi
  done
  R=$(find /usr/local/lib/node_modules/@deepseek-ai -maxdepth 5 -path "*$sub/lib/index.js" 2>/dev/null | head -1)
  [ -n "$R" ] && { echo "$R"; return 0; }
  R=$(find /root -maxdepth 6 -path "*/$sub/lib/index.js" 2>/dev/null | head -1)
  [ -n "$R" ] && { echo "$R"; return 0; }
  return 1
}

# 0.1.5 的排他发布已在构建时统一适配；旧脚本不能把它降回可覆盖的 rename。
NEW_FS=$(locate_pkg dsh-fs-local)
NEW_SESSION=$(locate_pkg dsh-session-persistence-jsonl)
if grep -q 'DSHA_ATOMIC_PUBLISH_V1' "$NEW_FS" "$NEW_SESSION" 2>/dev/null; then
  if grep -q 'DSHA_ATOMIC_PUBLISH_V1' "$NEW_FS" && grep -q 'DSHA_ATOMIC_PUBLISH_V1' "$NEW_SESSION"; then
    echo FS_PATCH_ALREADY
    echo SESSION_PATCH_ALREADY
    exit 0
  fi
  echo 'FS_PATCH_FAIL：新版运行模块未同步，请通过环境维护重新安装'
  exit 1
fi

# ============ ① agent 的 write 工具（dsh-fs-local） ============
F=$(locate_pkg dsh-fs-local)
if [ -z "${F:-}" ] || [ ! -f "${F:-}" ]; then
  log "未找到 dsh-fs-local/lib/index.js，跳过"
  echo FS_PATCH_SKIP
elif grep -q 'DSHA_L2S_FIX3' "$F"; then
  echo FS_PATCH_ALREADY
else
  cp -f "$F" "$F.dsha-bak" 2>/dev/null || true
  python3 - "$F" <<'PY'
import re
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()
NEW = '/* DSHA_L2S_FIX3 */ await rename(tempPath, absolutePath);'

# a) 从未打过补丁
ORIG = 'await linkFile(tempPath, absolutePath);'
# b) 打过老补丁（FIX）：整段换掉，否则新脚本会因匹配不到原串而永远失败
OLD_PATCH = re.compile(
    r'/\* DSHA_L2S_FIX \*/ await \(async \(\) => \{.*?\}\)\(\);', re.S)

if OLD_PATCH.search(src):
    src = OLD_PATCH.sub(NEW, src, count=1)
    print('UPGRADED')
elif ORIG in src:
    src = src.replace(ORIG, NEW, 1)
    print('PATCHED')
else:
    print('PATTERN_MISS')
    sys.exit(3)
open(path, 'w', encoding='utf-8').write(src)
PY
  rc=$?
  if [ "$rc" -ne 0 ]; then
    log "write 补丁失败 rc=$rc（目标字符串可能随 dsh 版本变化）"
    [ -f "$F.dsha-bak" ] && cp -f "$F.dsha-bak" "$F"
    echo FS_PATCH_FAIL
  elif ! check_syntax "$F"; then
    log "write 补丁语法校验未通过，已回滚"
    [ -f "$F.dsha-bak" ] && cp -f "$F.dsha-bak" "$F"
    echo FS_PATCH_ROLLBACK
  else
    log "已打 write 发布补丁（一律 rename）：$F"
    echo FS_PATCH_OK
  fi
fi

# ============ ② 会话日志发布（dsh-session-persistence-jsonl） ============
S=$(locate_pkg dsh-session-persistence-jsonl)
if [ -z "${S:-}" ] || [ ! -f "${S:-}" ]; then
  log "未找到 dsh-session-persistence-jsonl/lib/index.js，跳过"
  echo SESSION_PATCH_SKIP
  exit 0
fi
if grep -q 'DSHA_L2S_FIX4' "$S"; then
  echo SESSION_PATCH_ALREADY
  exit 0
fi

cp -f "$S" "$S.dsha-bak" 2>/dev/null || true

python3 - "$S" <<'PY'
import re
import sys

path = sys.argv[1]
src = open(path, encoding='utf-8').read()

HELPER = '''
/* DSHA_L2S_FIX4 —— 一律 rename 发布。
   proot --link2symlink 下 link() 只是 symlink 模拟：EEXIST 并发保护不成立，
   而每次 link 都会留下 .l2s.<名>.<hash>.tmp0001 → ….0001 的链条，
   tar 遍历 .dsh 时报 ELOOP（Too many levels of symbolic links），备份必失败；
   临时文件被清理时目标还会悬空（读取报 ENOENT）。rename 是原子覆盖，
   既不悬空也不产生 symlink。 */
async function __dshaPublishLog(tmp, finalPath) {
  try {
    return await rename(tmp, finalPath);
  } catch (e) {
    // 跨文件系统才会 EXDEV：退化成「拷一份再删暂存」，仍然不碰 link
    if (e && e.code === "EXDEV") {
      const { copyFile, rm } = await import("node:fs/promises");
      await copyFile(tmp, finalPath);
      await rm(tmp, { force: true });
      return;
    }
    throw e;
  }
}
'''

CALL_ORIG = 'await link(tmp, finalPath);'
CALL_NEW = 'await __dshaPublishLog(tmp, finalPath);'

已打老补丁 = 'DSHA_L2S_FIX2' in src

# 1) import：确保有 rename（老补丁已加过就跳过）
m2 = re.search(r'^import \{([^}]*)\} from "node:fs/promises";', src, re.M)
if not m2:
    print("NO_FS_PROMISES_IMPORT")
    sys.exit(4)
if 'rename' not in m2.group(1):
    src = src[:m2.start(1)] + " rename," + m2.group(1) + src[m2.end(1):]

if 已打老补丁:
    # 2a) 升级：把 FIX2 的 helper 整段替换成 FIX4 版（调用点已是 __dshaPublishLog）
    OLD_HELPER = re.compile(
        r'\n?/\* DSHA_L2S_FIX2 .*?\nasync function __dshaPublishLog\(tmp, finalPath\) \{.*?\n\}\n',
        re.S)
    if not OLD_HELPER.search(src):
        print("OLD_HELPER_MISS")
        sys.exit(5)
    src = OLD_HELPER.sub(HELPER, src, count=1)
    result = 'UPGRADED'
else:
    # 2b) 首次：注入 helper（放在最后一条 import 之后）并换掉调用点
    if CALL_ORIG not in src:
        print("PATTERN_MISS")
        sys.exit(3)
    lines = src.split('\n')
    last_import = -1
    for i, line in enumerate(lines):
        if line.startswith('import '):
            last_import = i
    if last_import < 0:
        print("NO_IMPORT_BLOCK")
        sys.exit(6)
    lines.insert(last_import + 1, HELPER)
    src = '\n'.join(lines).replace(CALL_ORIG, CALL_NEW, 1)
    result = 'PATCHED'

open(path, 'w', encoding='utf-8').write(src)
print(result)
PY
rc=$?

if [ "$rc" -ne 0 ]; then
  log "会话发布补丁失败 rc=$rc（目标字符串可能随 dsh 版本变化）"
  [ -f "$S.dsha-bak" ] && cp -f "$S.dsha-bak" "$S"
  echo SESSION_PATCH_FAIL
  exit 0
fi
if ! check_syntax "$S"; then
  log "会话发布补丁语法校验未通过，已回滚"
  [ -f "$S.dsha-bak" ] && cp -f "$S.dsha-bak" "$S"
  echo SESSION_PATCH_ROLLBACK
  exit 0
fi

log "已打会话发布补丁（一律 rename）：$S"
echo SESSION_PATCH_OK
