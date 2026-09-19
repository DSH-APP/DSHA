#!/usr/bin/env python3
"""不经 Gradle 直接跑纯逻辑单测（javac + JUnitCore）。

为什么需要它：AGP 9.1.1 硬性要求 build-tools >= 36.0.0，而 Google 仓库的 Linux
build-tools 只有 x86_64（`repository2-3.xml` 里 build-tools 只有 `_linux.zip`，
无 aarch64 变体）。aarch64 主机上 `processStandardDebugResources` 会卡在
"AAPT2 ... Daemon startup failed"，整个 `:app:testStandardDebugUnitTest` 连带跑不了。

但 `app/src/test/` 下的测试本来就不碰 Android 运行时（AGENTS.md：`util/` 下的类
不得 import Android API），所以完全可以用 `javac` + `JUnitCore` 直接跑，绕开
aapt2 / d8 / 打包这一整条链。本脚本就是那条路，用来验证纯逻辑改动。

跑法：
    python3 tools/run-unit-tests.py
    DSHA_ONLY=QueryTest,ShellQuoteTest python3 tools/run-unit-tests.py   # 只跑几个

不做的事：不跑仪器测试（androidTest 需要真实设备）、不替代 CI 上的完整
Gradle 构建。它的定位是「本机没有可运行的 aapt2 时，仍然能验证 Java 改动」。
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'


def die(msg: str) -> None:
    print(f'ERROR: {msg}', file=sys.stderr)
    sys.exit(1)


def ensure_utf8_locale() -> None:
    """必须在任何 JVM 启动之前，且判据是「当前 locale 真是 UTF-8」。

    坑一：LANG 为空时 JVM 的 sun.jnu.encoding 落到 ANSI_X3.4-1968，工作区路径里的
    中文目录名会被替换成 '?'，javac 报一堆莫名其妙的 cannot find symbol —— 从错误
    信息完全看不出是编码问题。

    坑二（真踩过）：不能只看「有没有设 locale 变量就跳过」。本机 LC_CTYPE=POSIX 是
    设着的，按那个判据会直接返回，UTF-8 从没生效。所以这里查 locale charmap 的实际值。
    """
    def is_utf8() -> bool:
        try:
            r = subprocess.run(['locale', 'charmap'], capture_output=True, text=True, timeout=15)
            return 'utf' in r.stdout.lower()
        except Exception:
            return any('utf' in os.environ.get(v, '').lower()
                       for v in ('LC_ALL', 'LC_CTYPE', 'LANG'))

    if is_utf8():
        return
    try:
        avail = subprocess.run(['locale', '-a'], capture_output=True,
                               text=True, timeout=15).stdout.lower()
    except Exception:
        avail = ''
    for cand in ('C.utf8', 'C.UTF-8', 'en_US.utf8', 'en_US.UTF-8'):
        if cand.lower() in avail:
            os.environ['LANG'] = cand
            os.environ['LC_CTYPE'] = cand
            os.environ.pop('LC_ALL', None)
            return
    die('当前环境没有 UTF-8 locale，JVM 无法处理工作区路径里的非 ASCII 目录名；'
        '先装一个，例如 locale-gen en_US.UTF-8')


def gradle_user_home() -> Path:
    for cand in (os.environ.get('GRADLE_USER_HOME'), '/opt/toolchains/gradle-user-home',
                 str(Path.home() / '.gradle')):
        if cand and Path(cand).is_dir():
            return Path(cand)
    die('找不到 Gradle 缓存目录，设 GRADLE_USER_HOME 指过去')


def android_jar() -> Path:
    roots = [os.environ.get('ANDROID_SDK_ROOT'), os.environ.get('ANDROID_HOME'),
             '/opt/android-sdk', '/usr/lib/android-sdk']
    for r in roots:
        if not r:
            continue
        for p in sorted(Path(r).glob('platforms/android-3*'), reverse=True):
            jar = p / 'android.jar'
            if jar.is_file():
                return jar
    die('找不到 android.jar，设 ANDROID_SDK_ROOT 或装 platforms;android-37')


def find_jar(gh: Path, *needles: str) -> Path:
    for needle in needles:
        hits = [p for p in gh.glob(f'caches/modules-2/files-2.1/**/{needle}*.jar')
                if p.is_file() and 'sources' not in p.name and 'javadoc' not in p.name]
        if hits:
            return sorted(hits)[0]
    die(f'Gradle 缓存里找不到 {" 或 ".join(needles)}，先跑一次 Gradle 让它下载依赖')


def r_classpath_entry(gh: Path, gen: Path) -> str:
    """R 类：优先用 Gradle 生成好的 R.jar，没有就从 R.txt 生成 R.java。"""
    for p in sorted(APP.glob('build/intermediates/compile_r_class_jar/*/*/R.jar')):
        return str(p)
    txt = next(iter(sorted(APP.glob('build/intermediates/compile_symbol_list/*/*/R.txt'))), None)
    if txt is None:
        die('既没有 R.jar 也没有 R.txt —— 先成功跑一次资源处理（需要可用的 aapt2）')
    src = gen / 'R.java'
    src.parent.mkdir(parents=True, exist_ok=True)
    by_type: dict = {}
    for line in txt.read_text(encoding='utf-8').splitlines():
        parts = line.split()
        if len(parts) == 3:
            by_type.setdefault(parts[0], []).append((parts[1], parts[2]))
    body = ['package com.deepseekharness.app;', '', 'public final class R {']
    for typ, items in sorted(by_type.items()):
        body.append(f'  public static final class {typ} {{')
        for name, value in items:
            body.append(f'    public static final int {name} = {value};')
        body.append('  }')
    body.append('}')
    src.write_text('\n'.join(body) + '\n', encoding='utf-8')
    out = gen / 'r-classes'
    _javac([str(src)], out, [str(android_jar())])
    return str(out)


def _javac(sources, out: Path, cp) -> None:
    """把 -cp 和源文件一起写进 argfile。

    为什么 -cp 不能走命令行：这个容器经 proot/proroot 转一层 exec，把 ~36 KB 的
    classpath 当作单个 argv 元素传过去会被截断，javac 只认到前一小段，于是报一堆
    「package androidx.lifecycle does not exist」—— 而 android.jar 里的类又都能解析，
    看起来像依赖没下全。实测：同样的变量，bash 走 shell 传参正常、Python 直接
    execve 必挂；把 -cp 挪进 argfile 后两边都稳定。这类静默截断最耗人。
    """
    out.mkdir(parents=True, exist_ok=True)
    argfile = out.parent / f'{out.name}-args.txt'
    lines = ['-cp', os.pathsep.join(cp)] + list(sources)
    argfile.write_text('\n'.join(lines), encoding='utf-8')
    cmd = ['javac', '-nowarn', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
           '-d', str(out), f'@{argfile}']
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        errs = [l for l in (r.stdout + r.stderr).splitlines() if 'error:' in l]
        for line in errs[:40]:
            print(line, file=sys.stderr)
        die(f'javac 失败（{len(errs)} 条 error）')


def main() -> int:
    ensure_utf8_locale()
    gh = gradle_user_home()
    aj = android_jar()
    junit = find_jar(gh, 'junit-4.13.2', 'junit-4.13', 'junit-4.12')
    hamcrest = find_jar(gh, 'hamcrest-core-1.3', 'hamcrest-core')

    work = Path(tempfile.mkdtemp(prefix='dsha-unjunit-'))
    gen = work / 'gen'

    # UiMessages 是 prepare-ui-languages.py 的产物，缺了就现场生成。
    if not any(APP.glob('build/generated/uiLanguage/**/UiMessages.java')):
        script = ROOT / 'tools/prepare-ui-languages.py'
        r = subprocess.run([sys.executable, '-B', str(script), '--output',
                            str(APP / 'build/generated/uiLanguage')], capture_output=True, text=True)
        if r.returncode != 0:
            die(f'生成 UiMessages 失败：{r.stdout}{r.stderr}')

    r_entry = r_classpath_entry(gh, gen)

    cp = [str(aj), r_entry, str(junit), str(hamcrest)]
    for jar in list(gh.glob('caches/9.3.1/transforms/*/transformed/*/jars/classes.jar')) \
            + [p for p in gh.glob('caches/modules-2/files-2.1/**/*.jar')
               if p.is_file() and 'sources' not in p.name and 'javadoc' not in p.name]:
        cp.append(str(jar))

    roots = ['src/main/java', 'src/standard/java', 'src/debug/java']
    sources = [str(p) for root in roots for p in (APP / root).rglob('*.java')]
    # AIDL 产物：本机没有能跑的 aidl 时用人工等价产物（见 tools/aidl-stub/README.md）
    sources += [str(p) for p in (ROOT / 'tools/aidl-stub').rglob('*.java')]
    for pat in ('build/generated/source/buildConfig/standard/debug/**/*.java',
                'build/generated/uiLanguage/**/*.java'):
        sources += [str(p) for p in APP.glob(pat)]
    if not sources:
        die('没找到任何源码')

    main_out = work / 'main'
    _javac(sorted(set(sources)), main_out, cp)

    tests = sorted(str(p) for p in (APP / 'src/test/java').rglob('*Test.java'))
    if not tests:
        die('没找到测试源码')
    only = [s for s in os.environ.get('DSHA_ONLY', '').split(',') if s]
    if only:
        tests = [t for t in tests if any(f'{o}.java' in t for o in only)]

    test_out = work / 'test'
    _javac(tests, test_out, cp + [str(main_out)])

    # 必须是全限定名：JUnitCore 按 FQN 加载，裸类名会全部 ClassNotFound。
    classes = sorted(str(p.relative_to(test_out).with_suffix('')).replace(os.sep, '.')
                     for p in test_out.rglob('*Test.class'))
    run_cp = os.pathsep.join([str(test_out), str(main_out)] + cp)
    print(f'==> 纯 Java 单测：{len(classes)} 个测试类，{len(sources)} 个源文件，'
          f'android.jar={aj.name}')
    # 和 javac 同理：run 的 classpath 也不能走 argv（会被 proot 那层截断）。
    run_argfile = work / 'junit-args.txt'
    run_argfile.write_text('\n'.join(['-cp', run_cp, 'org.junit.runner.JUnitCore'] + classes),
                           encoding='utf-8')
    r = subprocess.run(['java', f'@{run_argfile}'], capture_output=True, text=True)
    tail = (r.stdout + r.stderr).strip().splitlines()
    for line in tail[-25:]:
        print(line)
    shutil.rmtree(work, ignore_errors=True)
    return r.returncode


if __name__ == '__main__':
    sys.exit(main())
