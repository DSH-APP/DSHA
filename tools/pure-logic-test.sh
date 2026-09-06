#!/bin/bash
# 跑那几个「无 Android 依赖」的纯逻辑类的断言集：
#   LanAuth      —— 局域网桥/3090 桥的凭据判定与请求行改写
#   AssetPath    —— 增量更新清单里 asset 名当路径用之前的校验
#   RuntimeHealth —— 容器运行时兼容性探针的输出解析（判据全是字符串匹配）
#   OfflineVersion —— 离线包版本标记比大小（比错了会把用户环境降级）
#   OverlayLines  —— 悬浮条分行与滚动（切错了文字会不停滚走，读不了）
#   UserDataPolicy —— 「什么算用户数据」的唯一定义。断言重点不是单个路径对不对，而是
#                     同一份定义派生出的两个列表（tar 排除项 / 还原后清理项）必须一一对应 ——
#                     这两处判断分裂过两次，各造成一次静默故障
#   ShellQuote   —— 拼进 bash -c 之前的 shell 转义。插件名与仓库地址有一部分来自插件市场
#                   索引（外部数据），转义写错一次就等于让市场条目能在容器里跑任意命令。
#                   断言做 round-trip（按单引号规则反解回来对比），不比字符串长相
#
# 为什么单独一个脚本而不是接进 gradle：这些类刻意不碰 Android API，用 javac 编几个
# 文件就能跑完，秒级、离线、不占 SDK 与 gradle 缓存。手机上的工作区跑一次完整 gradle
# 要几分钟且常把会话拖死，改一行字符串处理不该付那个代价。
#
# 用法：bash tools/pure-logic-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_DIR="$REPO_ROOT/app/src/main/java/com/deepseekharness/app"
SRCS=("$JAVA_DIR/SensitiveData.java" "$JAVA_DIR/LanAuth.java" "$JAVA_DIR/AssetPath.java" "$JAVA_DIR/SafeFiles.java" "$JAVA_DIR/BridgeLimits.java" "$JAVA_DIR/BackupInspector.java" "$JAVA_DIR/PluginErrorHint.java" "$JAVA_DIR/RuntimeHealth.java" "$JAVA_DIR/OfflineVersion.java" "$JAVA_DIR/OverlayLines.java" "$JAVA_DIR/UserDataPolicy.java" "$JAVA_DIR/ShellQuote.java" "$JAVA_DIR/Query.java" "$JAVA_DIR/BackupScope.java" "$JAVA_DIR/PublicDirs.java" "$JAVA_DIR/ArchiveProbe.java" "$JAVA_DIR/GitHubRef.java" "$JAVA_DIR/PluginSpec.java" "$JAVA_DIR/PatchToggle.java" "$JAVA_DIR/PatchYaml.java" "$JAVA_DIR/DshVersion.java" "$JAVA_DIR/MarketCol.java" "$JAVA_DIR/MarketSearch.java" "$JAVA_DIR/WebProcSel.java" "$JAVA_DIR/AssetBatch.java" "$JAVA_DIR/WatchdogScript.java" "$JAVA_DIR/DangerShellGuard.java")
TEST="$REPO_ROOT/tools/pure-logic-test/PureLogicTest.java"

for f in "${SRCS[@]}" "$TEST"; do
  if [ ! -f "$f" ]; then
    echo "找不到 $f" >&2
    exit 1
  fi
done

if ! command -v javac >/dev/null 2>&1; then
  echo "没有 javac（需要 JDK 17+）" >&2
  exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -nowarn -d "$OUT" "${SRCS[@]}" "$TEST"
java -cp "$OUT" com.deepseekharness.app.PureLogicTest
