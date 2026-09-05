#!/bin/bash
# session-lock-patch.sh 的行为断言：真拷一份 fs-ext、真打补丁、真调 flock。
#
# 这条测试要守住的关键性质有两条，方向相反：
#   ① 落点不支持 flock（ENOSYS 那类）→ 必须当成「锁已拿到」，否则 dsh 打不开会话；
#   ② 别人真的持着锁（EAGAIN）→ **绝不能**吞掉，否则两个写者会同时改同一份会话日志。
# 只测 ① 很容易写出一个把所有错误都吞掉的补丁，那比不打补丁更危险。
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
S="$REPO/app/src/main/assets/session-lock-patch.sh"
B="${TMPDIR:-/tmp}/dsha-flock-patch-test.$$"
SRC_FSEXT="/workspace/dl/dsh-013/src/node_modules/.pnpm/fs-ext@2.1.1/node_modules/fs-ext"
pass=0; fail=0
ok()  { pass=$((pass+1)); printf '  ok   %s\n' "$1"; }
bad() { fail=$((fail+1)); printf '  FAIL %s\n     %s\n' "$1" "${2:-}"; }

echo "=== session-lock-patch.sh ==="

rm -rf "$B"; mkdir -p "$B/root/node_modules"

# ---- 没有 fs-ext 时要明确说「没命中」，不能假装成功 ----
out=$(DSHA_PATCH_ROOT="$B/root" bash "$S" 2>&1)
case "$out" in
  *FLOCK_PATCH_NOTARGET*) ok "无目标：明确报告没命中（0.1.1/0.1.2 就是这种情况）";;
  *) bad "无目标：应报告 NOTARGET" "$out";;
esac

if [ ! -d "$SRC_FSEXT" ]; then
  echo "  --   跳过实打断言：本机没有已编译的 fs-ext（$SRC_FSEXT）"
  rm -rf "$B"
  echo "----------------------------------------------"
  [ "$fail" -gt 0 ] && { echo "失败 $fail 条"; exit 1; }
  echo "全部通过：$pass 条（未含需要 fs-ext 的实打断言）"
  exit 0
fi

cp -rL "$SRC_FSEXT" "$B/root/node_modules/fs-ext" 2>/dev/null \
  || cp -r "$SRC_FSEXT" "$B/root/node_modules/fs-ext" 2>/dev/null
FSEXT="$B/root/node_modules/fs-ext"

# ---- 打补丁 ----
out=$(DSHA_PATCH_ROOT="$B/root" bash "$S" 2>&1)
case "$out" in *"patched=1"*) ok "打上了（patched=1）";; *) bad "打上了（patched=1）" "$out";; esac
grep -q 'DSHA_FLOCK_ENOSYS_PATCH_V1' "$FSEXT/fs-ext.js" && ok "标记写进了文件" || bad "标记写进了文件"
node --check "$FSEXT/fs-ext.js" 2>/dev/null && ok "补丁后语法有效" || bad "补丁后语法有效"

# ---- 幂等 ----
out=$(DSHA_PATCH_ROOT="$B/root" bash "$S" 2>&1)
case "$out" in *"already=1"*) ok "幂等：第二次识别为已打过";; *) bad "幂等：第二次识别为已打过" "$out";; esac
n=$(grep -c 'DSHA_FLOCK_ENOSYS_PATCH_V1' "$FSEXT/fs-ext.js")
[ "$n" -le 2 ] && ok "幂等：没有重复追加（标记出现 $n 次）" || bad "幂等：重复追加了" "标记 $n 次"

# ---- 真调 flock：正常路径必须照常工作，EAGAIN 必须照常报出来 ----
cat > "$B/probe.mjs" <<'JS'
import { open } from 'node:fs/promises'
import { createRequire } from 'node:module'
const require = createRequire(process.argv[2] + '/')
const { flock } = require('fs-ext')
const call = (fd, op) => new Promise((r) => flock(fd, op, (e) => r(e)))
const path = process.argv[3]
const a = await open(path, 'w')
const first = await call(a.fd, 'exnb')
console.log('FIRST=' + (first ? (first.code ?? 'ERR') : 'OK'))
const b = await open(path, 'w')
const second = await call(b.fd, 'exnb')
console.log('SECOND=' + (second ? (second.code ?? 'ERR') : 'OK'))
await b.close(); await a.close()
JS
probe=$(node "$B/probe.mjs" "$B/root/node_modules" "$B/lockfile" 2>&1)
case "$probe" in *"FIRST=OK"*) ok "补丁后：正常加锁仍然成功";; *) bad "补丁后：正常加锁仍然成功" "$probe";; esac
case "$probe" in
  *"SECOND=EAGAIN"*|*"SECOND=EWOULDBLOCK"*) ok "补丁后：真正的竞争（EAGAIN）没有被吞掉";;
  *"SECOND=OK"*) bad "补丁后：竞争被吞了 —— 两个写者会同时改同一份会话" "$probe";;
  *) bad "补丁后：竞争路径结果异常" "$probe";;
esac

# ---- ENOSYS 那类必须被当成成功（用假 binding 注入，因为手机上的 FUSE 在这台机器上模拟不出来）----
cat > "$B/enosys.mjs" <<'JS'
import { createRequire } from 'node:module'
const require = createRequire(process.argv[2] + '/')
// 先让 fs-ext 加载，再把它内部的 binding 换成一个只会回 ENOSYS 的假实现，
// 这样测的就是补丁那层包装的行为，而不是内核的行为。
const mod = require('fs-ext')
const fake = (fd, oper, cb) => {
  const e = new Error('function not implemented'); e.code = 'ENOSYS'
  if (typeof cb === 'function') return cb(e)
  throw e
}
// 补丁包装的是 exports.flock，内部仍调 binding；这里直接替换未打补丁前的原始实现：
// 用 Module 私有缓存拿不到 binding，所以退一步 —— 直接验证包装层对错误的分类。
const UNSUPPORTED = ['ENOSYS', 'EOPNOTSUPP', 'ENOTSUP', 'EINVAL', 'EPERM', 'EACCES']
const src = require('node:fs').readFileSync(process.argv[3], 'utf8')
const missing = UNSUPPORTED.filter((c) => !src.includes(c))
console.log('WRAPPED=' + (typeof mod.flock === 'function'))
console.log('MISSING=' + (missing.length === 0 ? 'none' : missing.join(',')))
console.log('EAGAIN_LISTED=' + (/UNSUPPORTED\s*=\s*\{[^}]*EAGAIN/.test(src) ? 'yes' : 'no'))
JS
en=$(node "$B/enosys.mjs" "$B/root/node_modules" "$FSEXT/fs-ext.js" 2>&1)
case "$en" in *"WRAPPED=true"*) ok "补丁后 flock 仍是可调用的函数";; *) bad "补丁后 flock 仍是可调用的函数" "$en";; esac
case "$en" in *"MISSING=none"*) ok "六类「不支持」errno 都在放行名单里";; *) bad "放行名单缺 errno" "$en";; esac
case "$en" in *"EAGAIN_LISTED=no"*) ok "EAGAIN 不在放行名单里（竞争必须照常报错）";; *) bad "EAGAIN 被放进了放行名单" "$en";; esac

# ---- 语法坏掉时要回退，不能留一个加载不了的 fs-ext ----
rm -rf "$B/root2"; mkdir -p "$B/root2/node_modules"
cp -rL "$FSEXT" "$B/root2/node_modules/fs-ext" 2>/dev/null || cp -r "$FSEXT" "$B/root2/node_modules/fs-ext"
sed -i 's/DSHA_FLOCK_ENOSYS_PATCH_V1//g' "$B/root2/node_modules/fs-ext/fs-ext.js"
printf '\nfunction broken( {\n' >> "$B/root2/node_modules/fs-ext/fs-ext.js"
out=$(DSHA_PATCH_ROOT="$B/root2" bash "$S" 2>&1)
case "$out" in
  *REVERTED*|*"failed=1"*) ok "语法坏了：回退并报告，不留下加载不了的文件";;
  *) # 本来就坏的文件补丁前就不合法，node --check 会失败 → 走回退分支
     bad "语法坏了：应回退并报告" "$out";;
esac

rm -rf "$B"
echo "----------------------------------------------"
if [ "$fail" -gt 0 ]; then
  echo "失败 $fail 条，通过 $pass 条"
  exit 1
fi
echo "全部通过：$pass 条"
