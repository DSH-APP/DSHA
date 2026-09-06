#!/bin/bash
# ============================================================
# 从「本地已经装好的 dsh 目录树」重建离线 rootfs 包。
#
# 与 tools/rebuild-offline-dsh.sh 的分工：
#   · rebuild-offline-dsh.sh   从 npm 装（`npm i -g @deepseek-ai/dsh@X`）——
#     要求那个版本已经发布到 npm 上；
#   · 这个脚本（from-tree）      从一棵已经装好的树搬 —— alpha / 自建 / 从源码
#     pack 出来的版本用这个，因为它们根本不在 npm 上。
#
# ⚠️ **必须在归档层面做，不能解包成目录再重新打包**（这条是踩出来的）：
# 这个工作区容器带 proot --link2symlink，tar 解包时会把硬链接换成
# 「符号链接 + .l2s.<名字>NNNN 替身」，重新打包时 tar 跟不动替身 ——
# ./usr/bin/gunzip、perl 这类硬链接条目会变成指向包外的悬空软链，
# 真机上解压出来那些命令就是坏的，而且只在 stderr 留几行。
# 所以流程是：gunzip 出 .tar → tar --delete 摘子树 → tar -r 追加 → pigz 压回。
#
# 用法：
#   tools/rebuild-offline-from-tree.sh <源 tar.gz> <树根目录> <输出 tar.gz>
# 其中「树根目录」下要有 usr/local/lib/node_modules/@deepseek-ai/dsh。
# ============================================================
set -eu

SRC="${1:?第一个参数：源离线包 tar.gz}"
TREE="${2:?第二个参数：树根目录}"
OUT="${3:?第三个参数：输出 tar.gz}"
SUBTREE="usr/local/lib/node_modules/@deepseek-ai"

say() { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\n✗ %s\n' "$*" >&2; exit 1; }

[ -f "$SRC" ] || die "源包不存在：$SRC"
[ -d "$TREE/$SUBTREE/dsh" ] || die "树里没有 $SUBTREE/dsh：$TREE"

# 硬链接会在 tar 往返里被 proot 弄坏，进包之前必须为 0
hl=$(find "$TREE" -type f -links +1 2>/dev/null | wc -l)
[ "$hl" -eq 0 ] || die "树里还有 $hl 个硬链接，先用 cat 重建成实体文件再来"
l2s=$(find "$TREE" -name '.l2s.*' 2>/dev/null | wc -l)
[ "$l2s" -eq 0 ] || die "树里还有 $l2s 个 .l2s 替身"

WORK="$(dirname "$OUT")/.offline-rebuild.$$"
mkdir -p "$WORK"
TAR="$WORK/rootfs.tar"
trap 'rm -rf "$WORK"' EXIT

say "1/5 解压源包 → $TAR"
if command -v pigz >/dev/null 2>&1; then
  pigz -d -c "$SRC" > "$TAR"
else
  gzip -d -c "$SRC" > "$TAR"
fi
ls -la "$TAR" | awk '{print "    " $5 " 字节"}'

say "2/5 确认归档里的路径前缀"
# 有的包是 ./usr/...，有的是 usr/...，摘子树时必须用对，否则 --delete 静默什么都不删
if tar -tf "$TAR" "./$SUBTREE" >/dev/null 2>&1; then
  PREFIX="./"
elif tar -tf "$TAR" "$SUBTREE" >/dev/null 2>&1; then
  PREFIX=""
else
  die "归档里找不到 $SUBTREE —— 源包结构不对？"
fi
echo "    前缀 = '${PREFIX}'"
old_ver=$(tar -xOf "$TAR" "${PREFIX}${SUBTREE}/dsh/package.json" 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("version",""))' 2>/dev/null || echo '?')
echo "    包里原来的 dsh 版本 = $old_ver"

say "3/5 摘掉旧的 $SUBTREE"
tar --delete --wildcards -f "$TAR" "${PREFIX}${SUBTREE}" 2>/dev/null || true
tar --delete --wildcards -f "$TAR" "${PREFIX}${SUBTREE}/*" 2>/dev/null || true
if tar -tf "$TAR" "${PREFIX}${SUBTREE}/dsh/package.json" >/dev/null 2>&1; then
  die "旧子树没删干净"
fi
echo "    已摘除"

say "4/5 追加新树"
# -C 到树根，用与源包一致的前缀追加；不加 -h（树里已经没有硬链接，
# 而 .bin/* 那些相对软链应当原样保留）
if [ "$PREFIX" = "./" ]; then
  tar -rf "$TAR" -C "$TREE" "./$SUBTREE"
else
  tar -rf "$TAR" -C "$TREE" "$SUBTREE"
fi
new_ver=$(tar -xOf "$TAR" "${PREFIX}${SUBTREE}/dsh/package.json" 2>/dev/null \
  | python3 -c 'import json,sys;print(json.load(sys.stdin).get("version",""))' 2>/dev/null || echo '?')
echo "    包里现在的 dsh 版本 = $new_ver"
[ -n "$new_ver" ] && [ "$new_ver" != '?' ] || die "追加后读不出版本"

say "5/5 验收 + 压回"
fails=0
chk() {
  if tar -tf "$TAR" "$1" >/dev/null 2>&1; then
    echo "    ✓ $1"
  else
    echo "    ✗ 缺 $1"
    fails=$((fails + 1))
  fi
}
# 入口软链（路径没变，所以源包里那条应当还在）
chk "${PREFIX}usr/local/bin/dsh"
# 0.1.3 的 session 锁靠它，缺了会话根本打不开
chk "${PREFIX}${SUBTREE}/dsh/node_modules/fs-ext/build/Release/fs_ext.node"
# 终端靠它
chk "${PREFIX}${SUBTREE}/dsh/node_modules/node-pty/package.json"
# 图片附件靠它（少了插件树加载就失败，web 起不来）
chk "${PREFIX}${SUBTREE}/dsh/node_modules/@img/sharp-linux-arm64/package.json"
# 那两个硬链接来源的原生模块必须是实体文件
chk "${PREFIX}${SUBTREE}/dsh/node_modules/esbuild/bin/esbuild"
n_l2s=$(tar -tf "$TAR" | grep -c '\.l2s\.' || true)
echo "    .l2s 条目数 = $n_l2s（必须是 0）"
[ "$n_l2s" -eq 0 ] || fails=$((fails + 1))
[ "$fails" -eq 0 ] || die "验收有 $fails 项不通过，没有生成输出"

if command -v pigz >/dev/null 2>&1; then
  pigz -p 4 -c "$TAR" > "$OUT"
else
  gzip -c "$TAR" > "$OUT"
fi
say "完成：$OUT"
ls -la "$OUT" | awk '{print "    " $5 " 字节"}'
echo "    dsh $old_ver → $new_ver"
