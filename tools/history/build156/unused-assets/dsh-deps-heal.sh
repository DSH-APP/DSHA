#!/bin/bash
# dsh-deps-heal.sh — 校验并补装 @deepseek-ai/dsh 全局安装缺失的 @deepseek-ai/* 子包依赖。
#
# 背景（见 dsh-issue-report）：npm 全局安装 rc.8 时，npmmirror 镜像元数据缓存不一致
# 导致部分子包（dsh-client-ui-slots / dsh-client-ui-primitives 等）在 package.json 里
# 声明了依赖、但安装时没有解析进 node_modules → 服务端 require 时 Cannot find module。
#
# 做法：读主包 package.json 的 dependencies 里所有 @deepseek-ai/* 声明，逐个检查
# 全局包的 node_modules/@deepseek-ai/ 下是否存在；缺失的用「主包精确版本」补装
# （镜像缓存坏时精确版本最稳），失败回退声明范围，再失败跳过（不阻塞）。
#
# 幂等：全部就绪输出 HEAL_OK；补装完成输出 HEAL_DONE；部分失败 HEAL_PARTIAL。
# 由 App 在安装⑤末尾 + 每次启动时调用；输出写 /root/dsh-deps-heal.log。
set -u
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
LOG=/root/dsh-deps-heal.log
exec > >(tee "$LOG") 2>&1

DSH_DIR="$(npm root -g 2>/dev/null)/@deepseek-ai/dsh"
[ -d "$DSH_DIR" ] || DSH_DIR=/usr/local/lib/node_modules/@deepseek-ai/dsh
[ -d "$DSH_DIR" ] || { echo "NO_DSH: 未找到全局 dsh 包（环境可能未装⑤）"; exit 0; }

NM="$DSH_DIR/node_modules/@deepseek-ai"
# 注意：python 读的是 package.json 文件（不是目录！）——传 $DSH_DIR/package.json，
# 否则 json.load(open(目录)) 抛 IsADirectoryError，被 2>/dev/null 吞掉 →
# 永远输出 HEAL_OK 假成功、从不补装缺失子包。
DSH_PKG="$DSH_DIR/package.json"
MAIN_VER=$(python3 - "$DSH_PKG" "$NM" <<'PY' 2>/dev/null | tail -1
import json, os, sys
pkg = json.load(open(sys.argv[1]))
print(pkg.get('version', ''))
PY
)
echo "dsh 主包版本: ${MAIN_VER:-unknown}"

# 1) 收集缺失的 @deepseek-ai/* 依赖（声明了但没装上）
MISSING_SPEC=""
MISSING_LIST=""
while IFS= read -r line; do
  name="${line%% *}"
  spec="${line#* }"
  [ -n "$name" ] || continue
  sub="${name#@deepseek-ai/}"
  if [ ! -e "$NM/$sub" ]; then
    MISSING_SPEC="$MISSING_SPEC $name@$spec"
    MISSING_LIST="$MISSING_LIST $name"
  fi
done < <(python3 - "$DSH_PKG" "$NM" <<'PY' 2>/dev/null
import json, os, sys
pkg = json.load(open(sys.argv[1]))
nm = sys.argv[2]
deps = pkg.get('dependencies', {})
for name, spec in deps.items():
    if name.startswith('@deepseek-ai/'):
        sub = name.split('/')[1]
        if not os.path.exists(os.path.join(nm, sub)):
            print(name + ' ' + spec)
PY
)

if [ -z "$MISSING_LIST" ]; then
  echo "HEAL_OK: 全部 @deepseek-ai/* 子包依赖已就绪"
  exit 0
fi

echo "HEAL_MISSING:$MISSING_LIST"
echo "HEAL_SPEC:$MISSING_SPEC"
cd "$DSH_DIR" || exit 0

# 2) 逐个补装：优先主包精确版本（镜像缓存坏时最稳），失败回退声明范围。
#    --no-save + --no-package-lock：只补缺失实体，不改主包 package.json / lockfile，
#    避免 reify 重排现有依赖树（弄坏其他子包）。
#    注意 scoped 包名以 @ 开头：用「最后一个 @」切分（%%@* 会把开头的 @ 也切掉，错！）
for line in $MISSING_SPEC; do
  name="${line%@*}"
  spec="${line##*@}"
  echo ">> 补装 $name（精确 $MAIN_VER，回退 $spec）"
  ok=0
  if [ -n "$MAIN_VER" ]; then
    timeout 180 npm install --no-save --no-package-lock --ignore-scripts \
      --registry=https://registry.npmmirror.com "$name@$MAIN_VER" >/tmp/dsh-heal.log 2>&1 && ok=1
    [ "$ok" = "0" ] && timeout 180 npm install --no-save --no-package-lock --ignore-scripts \
      --registry=https://registry.npmjs.org "$name@$MAIN_VER" >>/tmp/dsh-heal.log 2>&1 && ok=1
  fi
  if [ "$ok" = "0" ]; then
    timeout 180 npm install --no-save --no-package-lock --ignore-scripts \
      --registry=https://registry.npmmirror.com "$name@$spec" >>/tmp/dsh-heal.log 2>&1 && ok=1
    [ "$ok" = "0" ] && timeout 180 npm install --no-save --no-package-lock --ignore-scripts \
      --registry=https://registry.npmjs.org "$name@$spec" >>/tmp/dsh-heal.log 2>&1 && ok=1
  fi
  if [ "$ok" = "1" ]; then
    echo "   ✓ $name 补装成功"
  else
    echo "   !! $name 补装失败（可能镜像未同步/版本不存在，跳过——构建期依赖不影响运行）"
    tail -5 /tmp/dsh-heal.log 2>/dev/null
  fi
done

# 3) 复查
STILL=""
for line in $MISSING_SPEC; do
  name="${line%@*}"
  sub="${name#@deepseek-ai/}"
  if [ ! -e "$NM/$sub" ]; then STILL="$STILL $name"; fi
done
if [ -z "$STILL" ]; then
  echo "HEAL_DONE: 缺失子包已全部补装"
else
  echo "HEAL_PARTIAL: 仍缺失:$STILL（不影响服务端运行/预构建前端，可忽略）"
fi
exit 0
