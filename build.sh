#!/usr/bin/env bash
# deepseekharness 一键构建脚本：构建 debug APK，并复制为带版本号的命名产物。
# 用法：./build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# 可通过环境变量覆盖（默认适配本工作区：/workspace 下的 gradle 与 android-sdk）
GRADLE_BIN="${GRADLE_BIN:-/workspace/gradle/bin/gradle}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/workspace/android-sdk}"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"

if [ ! -x "$GRADLE_BIN" ]; then
    echo "找不到 gradle：$GRADLE_BIN（可用 GRADLE_BIN 环境变量指定）" >&2
    exit 1
fi

VERSION_NAME=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)
VERSION_NAME=${VERSION_NAME:-0.0}
echo "==> 版本: ${VERSION_NAME}"

# ---- APK 签名：必须与线上包一致，否则装不上 ----
# 线上包（v1.1.7 起）是用一份 debug keystore 签的 —— secret 当初传错了文件，详见
# 工作区 DSHA-签名现状与风险.md。Android 只允许同签名覆盖安装，所以本地出的包想装到
# 已经装过正式版的机器上，就必须用同一把钥匙；用 DSHA-release.keystore 签出来的包会
# 因为签名冲突直接装不上（之前那个 1.1.7-fix 测试包就是这样）。
#
# 线上包使用历史发布密钥签名；脚本与 APK 一起离线发布，不再生成远程脚本清单。
DSHA_PUBLISH_KEYSTORE="${DSHA_PUBLISH_KEYSTORE:-/workspace/DSHA-ACTUAL-PUBLISH-KEY-debug.keystore}"
if [ -f "$DSHA_PUBLISH_KEYSTORE" ]; then
  export DSHA_KEYSTORE="$DSHA_PUBLISH_KEYSTORE"
  export DSHA_KEYSTORE_PASSWORD="${DSHA_KEYSTORE_PASSWORD:-android}"
  export DSHA_KEY_ALIAS="${DSHA_KEY_ALIAS:-androiddebugkey}"
  export DSHA_KEY_PASSWORD="${DSHA_KEY_PASSWORD:-android}"
  echo "==> APK 用线上发布密钥签名（可直接覆盖安装）"
else
  echo "==> 注意：找不到线上发布密钥 $DSHA_PUBLISH_KEYSTORE"
  echo "    本地包将用 gradle.properties 里的密钥签名，装不到已装正式版的机器上"
fi

"$GRADLE_BIN" :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "构建失败：未找到 $APK" >&2
    exit 1
fi

OUT="deepseekharness-arm64-v${VERSION_NAME}.apk"
cp "$APK" "$OUT"
echo "==> 原始产物: $ROOT/$APK"
echo "==> 版本命名: $ROOT/$OUT"
