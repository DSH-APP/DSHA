#!/usr/bin/env bash
# 本地分阶段打包：日志落盘、断点续跑。
#
# **必须前台跑**：这个工作区是 proot 容器（--kill-on-exit），shell 调用一结束，
# proot 就把该次调用下的所有进程连根杀掉 —— nohup 和 setsid 都逃不掉。实测过：
# 后台起的 gradle 与 tar 都在调用返回后立刻消失，日志停在半截、也不会写失败记录。
# 所以长任务要么在单条调用里跑完，要么靠 gradle 自己的增量（中断后重跑会跳过
# 已 UP-TO-DATE 的任务），本脚本的 stamp 机制就是给这种续跑用的。
#
# 为什么不用 build.sh：build.sh 是一条直线，跑到一半失败要从头再来；而本工作区里
# 一次 assembleDebug 要把 300MB 的离线 rootfs 打进 APK，中断重来的代价太高。
# 这里把流程切成六段，每段单独落日志、成功后立 stamp，再跑时自动跳过已完成的段。
#
# 用法：
#   bash tools/pack-local.sh                  # 从上次中断处继续
#   bash tools/pack-local.sh --from 3         # 从第 3 段起重跑（含 3）
#   bash tools/pack-local.sh --from 4 --to 4  # 只跑第 4 段
#   bash tools/pack-local.sh --only 5         # 同上，单段写法
#   bash tools/pack-local.sh --force          # 全部重跑
#   bash tools/pack-local.sh --status         # 只看进度，不动手
#
# 段：1 预检 / 2 离线 rootfs 就位 / 3 编译 java+资源 /
#     4 assembleDebug / 5 核对 APK 签名指纹 / 6 命名产物 + sha256
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_BIN="${GRADLE_BIN:-/workspace/gradle/bin/gradle}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/workspace/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
ROOTFS_SRC="${ROOTFS_SRC:-/workspace/dl/offline-rootfs.tar.gz}"
PUBLISH_KEYSTORE="${DSHA_PUBLISH_KEYSTORE:-/workspace/DSHA-ACTUAL-PUBLISH-KEY-debug.keystore}"
# gradle 参数：本工作区是手机上的 proot 容器，daemon 与并行都会把内存拖爆。
# GRADLE_OPTS 必须和 -Dorg.gradle.jvmargs 写成同一份值 —— 两者不一致时 gradle 会
# 另 fork 一个 single-use daemon（等于同时吃两份堆），而仓库 gradle.properties 里
# 写的是 -Xmx4g，在这台机器上（可用内存常只剩 2G）fork 出来就是被 OOM 杀掉。
GRADLE_JVM="-Xmx1280m -Dfile.encoding=UTF-8"
export GRADLE_OPTS="$GRADLE_JVM"
GRADLE_ARGS=(--no-daemon --no-parallel "-Dorg.gradle.jvmargs=$GRADLE_JVM")
# 默认离线解析依赖。这台机器的网络时通时不通 —— 实测同一分钟内 curl 拿得到 HTTP 200、
# gradle 却报 "Temporary failure in name resolution"，而失败一次要白跑七分半。所有依赖
# 都已经在 gradle cache 或 mavenLocal 里（见 tools/fetch-offline-deps.sh）。
# 新加了依赖、确实需要联网解析时：PACK_ONLINE=1 bash tools/pack-local.sh
[ -n "${PACK_ONLINE:-}" ] || GRADLE_ARGS+=(--offline)

VERSION_NAME="$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)"
VERSION_CODE="$(sed -n 's/.*versionCode \([0-9]\+\).*/\1/p' app/build.gradle | head -1)"
LOGDIR="${LOGDIR:-/workspace/build-logs/v${VERSION_NAME:-unknown}}"
mkdir -p "$LOGDIR"

FROM=1; TO=6; ONLY=""; FORCE=0; STATUS_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="${2:-1}"; shift 2 ;;
    --to) TO="${2:-6}"; shift 2 ;;
    --only) ONLY="${2:-}"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --status) STATUS_ONLY=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

STAGE_NAMES=(x 预检 rootfs就位 编译 assemble 验签 命名产物)

say() { echo "[$(date '+%H:%M:%S')] $*"; }
# 前缀隔离旧的七阶段流程，避免清单签名阶段的 stamp 误命中新流程。
stamp() { echo "$LOGDIR/offline-stamp-$1.ok"; }

done_p() { [ -f "$(stamp "$1")" ]; }

skip_p() {
  local n="$1"
  [ -n "$ONLY" ] && { [ "$n" = "$ONLY" ] && return 1 || return 0; }
  [ "$n" -lt "$FROM" ] && return 0
  [ "$n" -gt "$TO" ] && return 0
  [ "$FORCE" = 1 ] && return 1
  done_p "$n" && return 0
  return 1
}

# 跑一段：$1=序号 $2=函数名。日志同时落盘与上屏（tail 便于后台观察）。
run_stage() {
  local n="$1" fn="$2" log
  log="$LOGDIR/offline-$n-${STAGE_NAMES[$n]}.log"
  if skip_p "$n"; then
    if done_p "$n"; then
      say "段 $n ${STAGE_NAMES[$n]}：跳过（已完成）"
    else
      say "段 $n ${STAGE_NAMES[$n]}：跳过（不在本次范围）"
    fi
    return 0
  fi
  say "段 $n ${STAGE_NAMES[$n]}：开始 → $log"
  rm -f "$(stamp "$n")"
  if "$fn" >"$log" 2>&1; then
    touch "$(stamp "$n")"
    say "段 $n ${STAGE_NAMES[$n]}：完成"
    return 0
  fi
  say "段 $n ${STAGE_NAMES[$n]}：失败，日志尾部 ↓"
  tail -25 "$log" || true
  return 1
}

# ---------------- 各段实现 ----------------

s1_precheck() {
  echo "版本：$VERSION_NAME / versionCode $VERSION_CODE"
  [ -n "$VERSION_NAME" ] || { echo "读不到 versionName"; return 1; }
  # VERSION 文件与 build.gradle 必须一致：不一致会打出版本号自相矛盾的包
  if [ -f VERSION ]; then
    local v; v="$(tr -d ' \n\r' < VERSION)"
    if [ "$v" != "$VERSION_NAME" ]; then
      echo "版本不一致：VERSION=$v，build.gradle=$VERSION_NAME"
      return 1
    fi
    echo "VERSION 文件一致：$v"
  fi
  [ -x "$GRADLE_BIN" ] || { echo "找不到 gradle：$GRADLE_BIN"; return 1; }
  [ -d "$ANDROID_SDK_ROOT" ] || { echo "找不到 SDK：$ANDROID_SDK_ROOT"; return 1; }
  [ -f "$ROOTFS_SRC" ] || { echo "找不到离线 rootfs：$ROOTFS_SRC"; return 1; }
  [ -f "$PUBLISH_KEYSTORE" ] || echo "警告：找不到线上发布密钥，出的包装不到已装正式版的机器"
  free -m | head -2
  df -h "$ROOT" | tail -1
  echo OK
}

s2_asset() {
  local dst=app/src/main/assets/offline-rootfs.tar.gz
  if [ -f "$dst" ] && [ "$(stat -c %s "$dst")" = "$(stat -c %s "$ROOTFS_SRC")" ]; then
    echo "已就位且大小一致，跳过拷贝"
  else
    # 同一文件系统内优先硬链接：省 300MB 空间和一次整盘拷贝
    ln -f "$ROOTFS_SRC" "$dst" 2>/dev/null && echo "硬链接就位" \
      || { cp -f "$ROOTFS_SRC" "$dst" && echo "复制就位"; }
  fi
  ls -la "$dst"

  # 版本标记：**离线包内容的版本号，由 CI 从仓库根的 OFFLINE_VERSION 取**，不是 dsh 的
  # 版本号。本地包一律写 0 —— 这是原设计（见 android-build.yml 那段注释），因为本地用的
  # 离线包内容不受 OFFLINE_VERSION 管，写任何非 0 值都可能骗过 App 的比较：
  # v1.1.9-rc1 就踩过 —— 仓库里当时跟踪着一份写着 dsh-0.1.1-rc.2 的标记，而包里烤的
  # 是 dsh 0.1.0-rc.6，用户装上后被提示「发现新版内置环境」，点了升级反而把 dsh 降级了。
  # 包里实际的 dsh 版本只打进日志，不参与判断 —— 它是给人看的，用来确认包对不对。
  local vfile=app/src/main/assets/offline-rootfs.version
  local pkgrel=./usr/local/lib/node_modules/@deepseek-ai/dsh/package.json
  local tmpd
  tmpd="$(mktemp -d)"
  local dshver=""
  if tar -xzf "$ROOTFS_SRC" -C "$tmpd" "$pkgrel" 2>/dev/null; then
    dshver="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' \
      "$tmpd/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json" 2>/dev/null || true)"
  fi
  rm -rf "$tmpd"
  printf '0\n' > "$vfile"
  if [ -n "$dshver" ]; then
    echo "包内 dsh 版本：$dshver（仅记录；标记写 0，App 不会拿本地包去提示升级）"
  else
    echo "警告：离线包里读不出 @deepseek-ai/dsh 版本 —— 包可能不完整，标记已写 0"
  fi
  echo OK
}

s3_compile() {
  # 先编 java 与资源：语法/资源错误在这一段就暴露，不用等十几分钟的 assemble
  "$GRADLE_BIN" :app:compileDebugJavaWithJavac :app:processDebugResources "${GRADLE_ARGS[@]}" || return 1
  echo OK
}

s4_assemble() {
  if [ -f "$PUBLISH_KEYSTORE" ]; then
    export DSHA_KEYSTORE="$PUBLISH_KEYSTORE"
    export DSHA_KEYSTORE_PASSWORD="${DSHA_KEYSTORE_PASSWORD:-android}"
    export DSHA_KEY_ALIAS="${DSHA_KEY_ALIAS:-androiddebugkey}"
    export DSHA_KEY_PASSWORD="${DSHA_KEY_PASSWORD:-android}"
    echo "用线上发布密钥签名（可覆盖安装）"
  fi
  "$GRADLE_BIN" :app:assembleDebug "${GRADLE_ARGS[@]}" || return 1
  ls -la app/build/outputs/apk/debug/app-debug.apk
  echo OK
}

s5_verify() {
  local apk=app/build/outputs/apk/debug/app-debug.apk
  [ -f "$apk" ] || { echo "没有产物 $apk"; return 1; }
  local signer; signer="$(ls -d "$ANDROID_SDK_ROOT"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
  [ -n "$signer" ] || { echo "找不到 apksigner，跳过核对"; return 0; }
  local apk_fp
  apk_fp="$("$signer" verify --print-certs "$apk" 2>/dev/null \
    | grep -i 'SHA-256 digest' | head -1 | tr -d ' ' | sed 's/.*://')"
  echo "APK 证书 SHA-256: $apk_fp"
  # 期望值不硬编码：直接从密钥文件现算，避免抄错（release.yml 以前就填错过期望值）
  if [ -f "$PUBLISH_KEYSTORE" ] && command -v keytool >/dev/null 2>&1; then
    local key_fp
    key_fp="$(keytool -list -v -keystore "$PUBLISH_KEYSTORE" \
      -storepass "${DSHA_KEYSTORE_PASSWORD:-android}" 2>/dev/null \
      | grep -i 'SHA256:' | head -1 | tr -d ' ' | tr -d ':' | sed 's/.*SHA256//I')"
    echo "密钥 SHA-256:    $key_fp"
    if [ -n "$key_fp" ] && [ "$(echo "$apk_fp" | tr 'A-F' 'a-f')" != "$(echo "$key_fp" | tr 'A-F' 'a-f')" ]; then
      echo "指纹不一致 —— 这个包装不到已装正式版的机器上"
      return 1
    fi
    echo "指纹一致"
  fi
  echo OK
}

s6_name() {
  local apk=app/build/outputs/apk/debug/app-debug.apk
  local out="deepseekharness-arm64-v${VERSION_NAME}.apk"
  cp -f "$apk" "$out"
  sha256sum "$out" | tee "$out.sha256"
  # 再出一份名字里带内容指纹的副本：同一个版本号本地会打很多次包，文件名一模一样，
  # 手机上很容易点到之前下载的那一份，然后以为「新改的东西没生效」。真机上刚发生过。
  local sha6
  sha6="$(cut -c1-6 < "$out.sha256")"
  local tagged="deepseekharness-arm64-v${VERSION_NAME}-${sha6}.apk"
  rm -f "deepseekharness-arm64-v${VERSION_NAME}-"??????".apk"
  ln -f "$out" "$tagged" 2>/dev/null || cp -f "$out" "$tagged"
  ls -la "$out" "$tagged"
  echo "产物：$ROOT/$tagged（内容同 $out，名字带指纹便于区分）"
  echo OK
}

# ---------------- 主流程 ----------------

if [ "$STATUS_ONLY" = 1 ]; then
  echo "版本 $VERSION_NAME（code $VERSION_CODE）  日志目录 $LOGDIR"
  for i in 1 2 3 4 5 6; do
    if done_p "$i"; then st="完成"; else st="未完成"; fi
    printf "  段 %d %-12s %s\n" "$i" "${STAGE_NAMES[$i]}" "$st"
  done
  exit 0
fi

say "打包开始：v$VERSION_NAME（code $VERSION_CODE）日志 → $LOGDIR"
run_stage 1 s1_precheck  || exit 1
run_stage 2 s2_asset     || exit 1
run_stage 3 s3_compile   || exit 1
run_stage 4 s4_assemble  || exit 1
run_stage 5 s5_verify    || exit 1
run_stage 6 s6_name      || exit 1
say "全部完成：deepseekharness-arm64-v${VERSION_NAME}.apk"
