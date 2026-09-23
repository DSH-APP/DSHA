#!/usr/bin/env bash
# DSHA 构建入口：JDK 17 + Gradle Wrapper 9.3.1 + SDK 37.0 + Python 3.9+。
#
# 用法（两种方式都行 —— 脚本本身在部分文件系统上没有可执行位）：
#   bash build.sh :app:testStandardDebugUnitTest
#   bash build.sh :app:assembleLowRelease
#   bash build.sh                      # 默认 :app:assembleStandardDebug
#
# 工具链解析顺序：环境变量 > 原开发机 F:/DSHA/_toolchains > 本机自动探测。
# 为什么要自动探测：这脚本最初只在 Windows 开发机上跑，TOOLS 写死成 F: 盘路径，
# 换到容器/Linux 上第一步就挂在「JAVA_HOME 是无效目录」，而真正的 JDK 17 明明就在
# /usr/lib/jvm 里。探测按「候选里第一个真正可用的」取值，不是第一个存在的。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# 工具链根目录：原开发机在 F 盘，容器/Linux 上一般在 /opt/toolchains。
# 判据是「里面真有东西」，不是目录存在 —— F:/DSHA/_toolchains 在 Linux 上不存在，
# 探测到 /opt/toolchains 后，gradle-user-home 里那份 300+ MB 的依赖缓存就直接复用。
find_tools() {
    local c
    for c in "${TOOLS:-}" "F:/DSHA/_toolchains" /opt/toolchains /usr/local/toolchains; do
        [ -n "$c" ] || continue
        if [ -d "$c/gradle-user-home" ] || [ -d "$c/android-sdk" ] || [ -d "$c/jdk-17" ]; then
            printf '%s
' "$c"; return 0
        fi
    done
    return 1
}
TOOLS="$(find_tools || true)"
TOOLS="${TOOLS:-F:/DSHA/_toolchains}"

# ---------- locale：这一步必须在任何 JVM 启动之前 ----------
# LANG 为空时 JVM 的 sun.jnu.encoding 落到 ANSI_X3.4-1968（纯 ASCII），路径里任何
# 非 ASCII 字符都会被替换成 '?'。实测症状：工作区路径含中文目录名「工作区」时，
# gradlew 报 "An unexpected error occurred while trying to open file
# /sdcard/Download/DSHA/?????????/DSHA/gradle/wrapper/gradle-wrapper.jar" ——
# 从错误信息完全看不出是编码问题。
if [ -z "${LANG:-}" ] || ! locale charmap 2>/dev/null | grep -qi utf; then
    for cand in C.utf8 C.UTF-8 en_US.utf8 en_US.UTF-8 zh_CN.utf8 zh_CN.UTF-8; do
        if locale -a 2>/dev/null | grep -iqx "$cand"; then
            export LANG="$cand" LC_CTYPE="$cand"
            break
        fi
    done
    if ! locale charmap 2>/dev/null | grep -qi utf; then
        echo "ERROR: 当前环境没有 UTF-8 locale（locale -a 无匹配），JVM 无法处理非 ASCII 路径" >&2
        echo "       先装一个 UTF-8 locale，例如: locale-gen en_US.UTF-8" >&2
        exit 1
    fi
fi

# ---------- JDK 17：必须是主版本 17，目录在但版本不对不算数 ----------
find_jdk() {
    local need=17 c real
    for c in "${JAVA_HOME:-}" "${ANDROID_JAVA_HOME:-}" "$TOOLS/jdk-17" \
             /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-amd64 \
             /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-1.17.0-openjdk-arm64 \
             /opt/jdk-17 /usr/local/jdk-17; do
        [ -n "$c" ] || continue
        if [ -x "$c/bin/java" ] && "$c/bin/java" -version 2>&1 | grep -q "version \"$need\."; then
            printf '%s\n' "$c"; return 0
        fi
    done
    # 兜底：PATH 上的 java，解析到真实安装目录再验版本
    if command -v java >/dev/null 2>&1; then
        real="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
        if [ -n "$real" ] && "$real/bin/java" -version 2>&1 | grep -q "version \"$need\."; then
            printf '%s\n' "$real"; return 0
        fi
    fi
    return 1
}

# ---------- Android SDK：认 compileSdk 37 的平台包，而不是只看目录在不在 ----------
find_sdk() {
    local c
    for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$TOOLS/android-sdk" \
             /opt/android-sdk /usr/lib/android-sdk "$HOME/Android/Sdk" \
             "${LOCAL_APP_DATA:-$HOME/AppData}/Local/Android/Sdk"; do
        [ -n "$c" ] || continue
        [ -d "$c" ] || continue
        if ls -d "$c"/platforms/android-37* >/dev/null 2>&1; then
            printf '%s\n' "$c"; return 0
        fi
    done
    return 1
}

JAVA_HOME="$(find_jdk || true)"
if [ -z "${JAVA_HOME:-}" ]; then
    echo "ERROR: 找不到 JDK 17。已试过：\$JAVA_HOME、$TOOLS/jdk-17、/usr/lib/jvm/java-17-openjdk*、PATH 上的 java" >&2
    echo "       装一个 JDK 17，或 export JAVA_HOME=/path/to/jdk17" >&2
    exit 1
fi
export JAVA_HOME

SDK="$(find_sdk || true)"
if [ -z "${SDK:-}" ]; then
    echo "ERROR: 找不到带 android-37 平台包的 Android SDK。已试过：\$ANDROID_SDK_ROOT、\$ANDROID_HOME、$TOOLS/android-sdk、/opt/android-sdk、/usr/lib/android-sdk" >&2
    echo "       或写入 local.properties: sdk.dir=/path/to/sdk" >&2
    exit 1
fi
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"

# local.properties 与探测结果一致就复用，不一致则以探测结果为准并提示。
if [ -f local.properties ]; then
    lp="$(grep -E '^sdk\.dir=' local.properties | tail -1 | cut -d= -f2- || true)"
    lp_real=""
    if [ -n "$lp" ] && [ -d "$lp" ]; then
        lp_real="$(cd "$lp" && pwd -P)"
    fi
    if [ -n "$lp" ] && [ "$lp_real" != "$SDK" ]; then
        echo "NOTE: local.properties 的 sdk.dir=$lp 未使用，实际使用 $SDK"
    fi
fi

# ---------- Python：prepareStandardAssets / prepareUiLanguages 依赖它 ----------
export DSHA_PYTHON="${DSHA_PYTHON:-python3}"
if ! command -v "$DSHA_PYTHON" >/dev/null 2>&1; then
    echo "ERROR: 找不到 $DSHA_PYTHON（tools/prepare-*.py 需要 Python 3.9+）" >&2
    exit 1
fi

# ---------- Gradle 堆：机器内存少时自动降，可用 DSHA_JVM_MB 覆盖 ----------
# gradle.properties 写死 -Xmx4g；在手机上那是把可用内存直接吃光。
if [ -z "${DSHA_JVM_MB:-}" ]; then
    avail="$(awk '/^MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
    want=$(( avail > 0 ? avail / 2 : 2048 ))
    [ "$want" -gt 4096 ] && want=4096
    [ "$want" -lt 1024 ] && want=1024
    DSHA_JVM_MB="$want"
fi

# ---------- Gradle 发行版：官方源在部分网络下只有几十 KB/s ----------
# 用法：GRADLE_DIST_URL=https://镜像/gradle-9.3.1-bin.zip bash build.sh ...
# 完整性不靠信任镜像：wrapper 与这里都按 gradle-wrapper.properties 里锁定的
# distributionSha256Sum 校验，镜像文件被改过就直接拒绝并回退官方源。
# Gradle 用户目录：优先复用工具链自带的缓存（发行版已解包、依赖已就绪），
# 否则落到用户目录 —— 干净机器上那意味着几百 MB 下载。
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOLS/gradle-user-home}"
if [ ! -d "$GRADLE_USER_HOME" ]; then
    GRADLE_USER_HOME="$HOME/.gradle"
fi
export GRADLE_USER_HOME

seed_gradle_dist() {
    [ -n "${GRADLE_DIST_URL:-}" ] || return 0
    local props="$ROOT/gradle/wrapper/gradle-wrapper.properties"
    local url sum zip base dir got
    url="$(grep -E '^distributionUrl=' "$props" | head -1 | cut -d= -f2- | sed 's/\\:/:/g')"
    sum="$(grep -E '^distributionSha256Sum=' "$props" | head -1 | cut -d= -f2- || true)"
    if [ -z "$sum" ]; then
        echo "NOTE: 未设 distributionSha256Sum，无法校验镜像文件，跳过换源" >&2
        return 0
    fi
    zip="$(basename "$url")"; base="${zip%.zip}"
    dir="$(ls -dt "$GRADLE_USER_HOME/wrapper/dists/$base"/*/ 2>/dev/null | head -1 || true)"
    [ -f "$dir$zip" ] && return 0
    if [ -z "$dir" ]; then
        # wrapper 自己的目录名是 distributionUrl 的派生值，不在这里猜：
        # 跑一次让它建目录。gradlew 内部是 exec java，timeout 直接终止该进程。
        echo "==> wrapper 发行版目录尚未创建，先跑一次让它建（官方源超时即弃）"
        timeout 20 bash "$GRADLE_BIN" --version >/dev/null 2>&1 || true
        dir="$(ls -dt "$GRADLE_USER_HOME/wrapper/dists/$base"/*/ 2>/dev/null | head -1 || true)"
        [ -n "$dir" ] || { echo "ERROR: 仍无法定位 wrapper 发行版目录" >&2; return 1; }
    fi
    rm -f "$dir$zip.part"
    echo "==> 从镜像下载 $zip"
    curl -sS -C - --retry 5 --retry-all-errors --connect-timeout 15 --max-time 900 \
         -o "$dir$zip" "$GRADLE_DIST_URL" || { echo "ERROR: 镜像下载失败" >&2; return 1; }
    got="$(sha256sum "$dir$zip" 2>/dev/null | cut -d' ' -f1)"
    if [ "$got" != "$sum" ]; then
        echo "ERROR: 镜像文件 SHA256 不符（$got != $sum），已删除，改走官方源" >&2
        rm -f "$dir$zip"
        return 1
    fi
    echo "==> 镜像文件校验通过"
}
# ---------- AIDL：build-tools 与主机架构不一致时，给 AGP 一个跑得动的 aidl ----------
# Google 仓库里的 Linux build-tools **只有 x86_64**（repository2-3.xml 中 build-tools
# 只有 _linux.zip，无 aarch64 变体；sdkmanager 按主机架构装，装出来仍是 x86_64），
# 在 aarch64 主机上一执行就是：
#     loader: reject .../build-tools/36.0.0/aidl: bad machine
# AGP 9.1.1 又硬性要求 build-tools >= 36.0.0；Debian 包里的 arm64 aidl 太老，解析不了
# 新版 framework.aidl 的 @JavaOnlyStableParcelable。
#
# 为什么不排除 AIDL 任务：下游任务（compileStandardDebugKotlin / Javac）的输入是 AIDL
# 任务输出的**惰性 provider**，`-x` 排除后 provider 永远无法兑现，Gradle 直接报
# "Querying the mapped value ... before task has completed is not supported"。
#
# 所以改成把 build-tools 里的 aidl 换成 shim：任务照常跑完，下游拿到的是
# tools/aidl-stub/ 里人工核对过的等价产物（本项目的 AIDL 源只有一个 IShellService.aidl、
# 两个方法，手写完全合理）。原二进制备份为 aidl.x86_64.disabled。
#
# 已搭好 qemu-user + binfmt 的主机可设 DSHA_FORCE_AIDL=1 走原生 aidl；
# 恢复原状：mv $ANDROID_SDK_ROOT/build-tools/<ver>/aidl.x86_64.disabled .../aidl
probe_aidl_arch() {
    "$DSHA_PYTHON" - "$SDK" <<'PY'
import glob, os, struct, sys
host = os.uname().machine
want = {'aarch64': 183, 'arm64': 183, 'x86_64': 62, 'amd64': 62,
        'i686': 3, 'i386': 3, 'armv7l': 40}.get(host)
if want is None:
    print('unknown-host:%s' % host); sys.exit()
found = None
for d in sorted(glob.glob(os.path.join(sys.argv[1], 'build-tools', '*')), reverse=True):
    b = os.path.join(d, 'aidl')
    if not os.path.isfile(b):
        continue
    try:
        head = open(b, 'rb').read(20)
    except OSError:
        continue
    if head[:4] != b'\x7fELF':
        continue
    found = (d, struct.unpack_from('<H', head, 18)[0])
    break
if found is None:
    print('no-aidl')
elif found[1] != want:
    print('mismatch:%s:%d!=%d' % (found[0], found[1], want))
else:
    print('ok')
PY
}

install_aidl_shim() {
    local stub="$ROOT/tools/aidl-stub" bt bin bak line
    find "$stub" -name '*.java' | grep -q . || { echo "ERROR: $stub 里没有等价产物" >&2; return 1; }
    bt="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
    [ -n "$bt" ] || { echo "ERROR: SDK 里没有 build-tools" >&2; return 1; }
    bin="$bt/aidl"
    bak="$bin.x86_64.disabled"
    [ -e "$bin" ] || { echo "ERROR: 找不到 $bin" >&2; return 1; }
    line="$(head -1 "$bin" 2>/dev/null || true)"
    if printf '%s' "$line" | grep -q '由 build.sh 生成的 aidl 替身'; then
        echo "==> aidl shim 已在位：$bin"
        return 0
    fi
    if [ -x "$bak" ]; then
        echo "==> 先恢复原生 aidl：$bak"
        mv -f "$bak" "$bin"
        chmod +x "$bin" 2>/dev/null || true
    else
        echo "==> 备份原生 aidl（x86_64，本机不可执行）：$bin -> aidl.x86_64.disabled"
        mv -f "$bin" "$bak"
    fi
    cat > "$bin" <<SHIM
#!/usr/bin/env bash
# 由 build.sh 生成的 aidl 替身：主机架构与 build-tools 不符，原生 aidl 跑不起来。
# 原理与恢复方法见 tools/aidl-stub/README.md
set -euo pipefail
OUT=""
while [ "\$#" -gt 0 ]; do
    case "\$1" in
        -o)  [ "\$#" -gt 1 ] && OUT="\$2"; shift 2 ;;
        -o*) OUT="\${1#-o}"; shift ;;
        *)   shift ;;
    esac
done
if [ -z "\$OUT" ]; then
    echo "aidl-stub: 未找到 -o 输出目录" >&2
    exit 2
fi
mkdir -p "\$OUT"
cp -r "$stub"/. "\$OUT"/
SHIM
    chmod +x "$bin"
    echo "==> 已安装 aidl shim：$bin"
}

if [ -z "${DSHA_FORCE_AIDL:-}" ]; then
    AIDL_STATE="$(probe_aidl_arch || echo probe-failed)"
    case "$AIDL_STATE" in
        ok) echo "==> aidl 架构与主机一致（$AIDL_STATE），走原生 AIDL 编译" ;;
        mismatch:*)
            install_aidl_shim || echo "ERROR: aidl shim 安装失败，AIDL 编译将失败" >&2
            echo "==> build-tools 与主机架构不符（${AIDL_STATE#mismatch:}）：已换 shim，AIDL 产物取自 tools/aidl-stub/"
            ;;
        no-aidl)
            echo "NOTE: SDK 里还没有 aidl，AGP 会先自动装 build-tools；装完若是 x86_64，重跑一次即自动换 shim"
            ;;
        *) echo "NOTE: 无法判定 aidl 架构（$AIDL_STATE），按原生路径走" ;;
    esac
fi

GRADLE_BIN="${GRADLE_BIN:-$ROOT/gradlew}"
if [ ! -f "$GRADLE_BIN" ]; then
    echo "ERROR: 找不到 Gradle wrapper：$GRADLE_BIN" >&2
    exit 1
fi
seed_gradle_dist || true
# 不依赖可执行位：手机存储是 FUSE，chmod +x 无效，wrapper 的模式位经常退成 100644。
run_gradle() {
    if [ -x "$GRADLE_BIN" ]; then
        "$GRADLE_BIN" "$@"
    else
        bash "$GRADLE_BIN" "$@"
    fi
}

echo "==> locale=$LANG  (JVM sun.jnu.encoding=$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/sun\.jnu\.encoding/ {print $2}'))"
echo "==> JAVA_HOME=$JAVA_HOME  ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"
echo "==> ANDROID_SDK=$SDK"
echo "==> GRADLE_USER_HOME=$GRADLE_USER_HOME"
echo "==> DSHA_PYTHON=$DSHA_PYTHON  ($("$DSHA_PYTHON" --version 2>&1))"
echo "==> Gradle 堆 -Xmx${DSHA_JVM_MB}m"
echo "==> task: ${*:-:app:assembleStandardDebug}"

if [ "$#" -eq 0 ]; then
    set -- :app:assembleStandardDebug
fi
# 不用 exec：run_gradle 是 shell 函数，bash 的 exec 只接受外部命令。
run_gradle -Dorg.gradle.jvmargs="-Xmx${DSHA_JVM_MB}m -Dfile.encoding=UTF-8" "$@"
