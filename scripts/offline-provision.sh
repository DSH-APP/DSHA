#!/usr/bin/env bash
# ============================================================
# offline-provision.sh — 在 arm64 rootfs（chroot / proot）内
# 预装 deepseek-harness 运行环境，产出「解压即用」的 rootfs。
#
# 默认装 rc.2（@deepseek-ai/dsh@0.1.1-rc.2，2026-08-21 发布），
# 与 App 在线安装（@rc 跟随最新）对齐；rc.8 失败再回退源码构建。
# 升级 rc 版本时同步改 DSH_VERSION 常量即可。
#
# 环境变量：
#   GITHUB_ACTIONS=true  → 官方源优先（GitHub runner 在海外）
#   DSHA_KEEP_CA=1       → 保留 ca-certificates（真 chroot 需要）
# ============================================================
set -euo pipefail

# dsh 版本（pin 到具体 rc，保证离线包可复现；与 App 在线 @rc 策略解耦）
DSH_VERSION="${DSH_VERSION:-0.1.1-rc.2}"
WORKDIR="${WORKDIR:-deepseek-harness}"
IN_CI="${GITHUB_ACTIONS:-}"
KEEP_CA="${DSHA_KEEP_CA:-}"
export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"

echo "==> [1/8] 配置 apt 源"
if [ -n "$IN_CI" ]; then
  echo "    CI：保留官方源（ports.ubuntu.com / archive.ubuntu.com）"
else
  sed -i 's|ports.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; s|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' \
    /etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true
fi

echo "==> [2/8] apt 更新 + 安装基础工具"
apt-get update -y
apt-get install -y --no-install-recommends \
    curl git python3 make gcc g++ xz-utils ca-certificates
# proot 下 ca-certificates postinst 常失败，会把 dpkg 卡成 broken。
# 真 chroot / CI 必须保留证书，否则 https 全挂。
if [ -z "$KEEP_CA" ] && [ -z "$IN_CI" ]; then
  apt-get install -y --no-install-recommends ca-certificates 2>/dev/null || true
  dpkg --remove --force-remove-reinstreq ca-certificates 2>/dev/null || true
  dpkg --configure -a 2>/dev/null || true
fi
command -v python >/dev/null 2>&1 || ln -sf /usr/bin/python3 /usr/bin/python || true

echo "==> [3/8] 安装 Node.js v24.19.0"
if [ ! -x /usr/local/bin/node ]; then
  mkdir -p /tmp 2>/dev/null || true
  rm -f /tmp/node.tar.xz
  if [ -n "$IN_CI" ]; then
    curl -fSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz \
      || curl -fSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz
  else
    curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz \
      || curl -kfsSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz
  fi
  tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
  rm -f /tmp/node.tar.xz
fi
node -v && npm -v

echo "==> [4/8] 安装 pnpm / node-gyp"
if [ -n "$IN_CI" ]; then
  export npm_config_registry=https://registry.npmjs.org
  printf 'registry=https://registry.npmjs.org\n' > /root/.npmrc
else
  export npm_config_registry=https://registry.npmmirror.com
  printf 'registry=https://registry.npmmirror.com\n' > /root/.npmrc
fi
command -v pnpm >/dev/null 2>&1 || npm install -g pnpm@11.7.0
command -v node-gyp >/dev/null 2>&1 || npm install -g node-gyp
# esbuild：老 WebView 上把 dsh 前端降级成非 module 的 IIFE bundle
# （assets/legacy-frontend-patch.sh 用它）。装进离线包是为了那条路不依赖网络 ——
# 需要它的恰恰是老设备，而老设备的网络往往也差。约 10MB，rootfs 本来就 300MB+。
command -v esbuild >/dev/null 2>&1 || npm install -g esbuild --no-audit --no-fund
# 会话修复自愈需要 zstandard（解压 session.jsonl.zstd）：
# 有 pip 就直接装，装不上不阻塞（自愈时会再尝试）
if command -v pip >/dev/null 2>&1 || python3 -m pip --version >/dev/null 2>&1; then
  python3 -m pip install --break-system-packages -q zstandard 2>/dev/null || python3 -m pip install -q zstandard 2>/dev/null || true
fi
pnpm -v
node-gyp --version || true

install_headers() {
  if [ -f /root/.cache/node-gyp/24.19.0/include/node/node.h ]; then
    echo "Node headers 已缓存"
    return 0
  fi
  mkdir -p /root/.cache/node-gyp/24.19.0
  cd /root/.cache/node-gyp/24.19.0
  if [ -n "$IN_CI" ]; then
    curl -fSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz \
      || curl -fSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz
  else
    curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz \
      || curl -kfsSL --retry 3 https://nodejs.org/dist/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz
  fi
  tar -xzf headers.tar.gz --strip-components=1
  rm -f headers.tar.gz
  touch .install-stamp
}

build_pty() {
  local dir="$1"
  [ -n "$dir" ] && [ -d "$dir" ] || return 1
  if [ -f "$dir/build/Release/pty.node" ] || [ -f "$dir/prebuilds/linux-arm64/pty.node" ]; then
    echo "pty.node 已就绪: $dir"
    return 0
  fi
  cd "$dir"
  GYP=/usr/local/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js
  [ -f "$GYP" ] || GYP=$(find /usr/local/lib -maxdepth 8 -path '*/node-gyp/bin/node-gyp.js' 2>/dev/null | head -1)
  [ -n "$GYP" ] || GYP=$(command -v node-gyp)
  export npm_config_disturl=https://npmmirror.com/mirrors/node
  node "$GYP" rebuild
  [ -f "$dir/build/Release/pty.node" ] || [ -f "$dir/prebuilds/linux-arm64/pty.node" ]
}

echo "==> [5/8] 安装 @deepseek-ai/dsh@${DSH_VERSION}（App 默认 dsh 版本，失败即停不 clone）"
install_headers
RC_OK=0
npm config set allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs --location=user 2>/dev/null || true
if npm install -g "@deepseek-ai/dsh@${DSH_VERSION}" --force; then
  NP=$(find /usr/local/lib/node_modules -maxdepth 8 -path '*/node-pty' -type d 2>/dev/null | head -1)
  if build_pty "$NP"; then
    RC_OK=1
    echo "dsh ${DSH_VERSION} + node-pty 就绪"
  else
    # node-pty 编译失败不再 fallback clone（手机/clone 易留空源码）：
    # 尝试 prebuilds 或直接失败
    echo "ERROR: dsh ${DSH_VERSION} 已装但 node-pty 编译失败，请检查工具链后重试"
    exit 1
  fi
else
  echo "ERROR: npm 安装 dsh ${DSH_VERSION} 失败（网络/镜像），请检查后重试"
  exit 1
fi

# （源码回退已移除：npm 失败即 exit 1，避免手机 clone 失败留下空源码目录。
#   如需源码模式请直接 clone 仓库自行构建。）
echo "==> [6/8] 应用补丁"
cd /root
if [ -d /root/"${WORKDIR}"/.git ]; then
  if [ -f /root/patches/webui-sidebar.patch ]; then
    (cd /root/"${WORKDIR}" && git apply --check /root/patches/webui-sidebar.patch \
      && git apply /root/patches/webui-sidebar.patch && echo 'sidebar 补丁已应用') \
      || echo 'sidebar 补丁跳过'
  fi
  if [ -f /root/patches/bash-guard.patch ]; then
    (cd /root/"${WORKDIR}" && git apply --check /root/patches/bash-guard.patch \
      && git apply /root/patches/bash-guard.patch && echo 'bash-guard 补丁已应用') \
      || echo 'bash-guard 补丁跳过'
  fi
fi
if [ -f /root/patches/webui-polyfill.sh ]; then
  bash /root/patches/webui-polyfill.sh || true
fi
if [ -f /root/patches/webui-origin-port-patch.sh ]; then
  bash /root/patches/webui-origin-port-patch.sh || true
fi

echo "==> [7/8] 安装危险命令确认包装器"
if [ -f /root/patches/rootfs-confirm-install.sh ]; then
  bash /root/patches/rootfs-confirm-install.sh
fi
# 空的内置插件快照，避免 App 误把后续用户插件当自带
touch /root/dsha-builtin.txt

echo "==> [7.5/8] 预置 DSHA 内置插件（dsh-web-mobile / device-shell-guide / task-notifier，解压即用）"
if [ -f /root/patches/provision-builtin-plugins.sh ]; then
  bash /root/patches/provision-builtin-plugins.sh
else
  echo "  WARN: 未找到 provision-builtin-plugins.sh（旧构建器？跳过预置，App 启动时会运行时注入）"
fi

echo "==> [8/8] 校验"
node -v
command -v pnpm
command -v node-gyp
command -v dsh && dsh --version 2>/dev/null | head -1 || echo '(dsh --version 无输出属正常)'
test -f /root/dsh-guard.sh
test -d /root/dsh-bin
test -f /root/dsh-bin/.version
if [ -x /usr/local/bin/dsh ] || command -v dsh >/dev/null 2>&1; then
  echo "✅ dsh 命令就绪"
else
  echo "❌ dsh 命令缺失" >&2
  exit 1
fi
echo "==> offline-provision 完成"
