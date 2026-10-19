"""Generate current third-party identities from checked-in package and tool locks."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
PERMISSIVE = {'MIT', 'ISC', 'BSD-2-Clause', 'BSD-3-Clause', 'Apache-2.0',
              '(MIT OR CC0-1.0)', '(MIT OR Apache-2.0)', '0BSD', 'Unlicense'}
NATIVE_NOTICES = {
    'libtermux.so': ('Apache-2.0 exception', 'tools/termux-jni, v0.118.0 source plus local handshake changes'),
    'libdsha-session.so': ('MIT', 'tools/native-session/session-launcher.c; locked API 23 build'),
    'libproot.so': ('GPL-2.0', 'Termux proot; original Standard binary exact revision remains unverified'),
    'libprootloader.so': ('GPL-2.0', 'Termux proot; original Standard binary exact revision remains unverified'),
    'libprootloader32.so': ('GPL-2.0', 'Termux proot; original Standard binary exact revision remains unverified'),
    'libproot_legacy.so': ('GPL-2.0', 'tools/build-low-proot.py, proot v5.1.107.92 source archive and local changes'),
    'libprootloader_legacy.so': ('GPL-2.0', 'tools/build-low-proot.py, proot v5.1.107.92 source archive'),
    'libtalloc.so': ('LGPL family; exact binary revision unverified', 'Samba talloc; build-low-proot.py locks talloc 2.4.3 headers, not the origin of this binary'),
    'libandroidshmem.so': ('unknown origin/license for this original binary', 'Retained original; no current exact-source reproduction receipt'),
    'libbxroot.so': ('MIT bxroot source; static toolchain runtime has separate terms', 'tools/bxroot/lock.json pins upstream commit, local patches and static relink inputs'),
    'libbxroot-runtime.so': ('MIT', 'tools/bxroot/lock.json pins upstream commit, local patches and glibc shared runtime build'),
    'libbxroot-ulx.so': ('MIT bxroot source; static toolchain runtime has separate terms', 'tools/bxroot/lock.json pins upstream commit, local patches and static relink inputs'),
}
OWNED_PACKAGE_ASSETS = {
    'runtime-fs': ('dsha-runtime-fs', '1.0.0', 'MIT'),
    'session-compat': ('dsha-session-compat', '1.0.0', 'MIT'),
    'client-combo-cache': ('dsha-client-combo-cache', '1.0.0', 'MIT'),
    'destructive-policy': ('dsha-destructive-policy', '1.0.0', 'MIT'),
}


def owned_packages(root=ROOT):
    rows = []
    for asset, identity in OWNED_PACKAGE_ASSETS.items():
        path = root / f'app/src/main/assets/{asset}/package.json'
        row = json.loads(path.read_text(encoding='utf-8'))
        if tuple(row.get(key) for key in ('name', 'version', 'license')) != identity:
            raise ValueError('THIRD_PARTY_OWN_SOURCE_IDENTITY: ' + asset)
        rows.append(row)
    return rows


def gradle_coordinates(root=ROOT):
    build = (root / 'app/build.gradle').read_text(encoding='utf-8')
    return sorted(set(re.findall(r"^\s*(?:implementation|standardImplementation|lowImplementation|coreLibraryDesugaring)\s+'([^']+)'", build, re.M)))


def native_members(root=ROOT):
    paths = {p.relative_to(root).as_posix() for flavor in ('main', 'low')
             for p in (root / f'app/src/{flavor}/jniLibs').rglob('*.so')}
    # Source checkouts can omit generated bxroot binaries; keep their locked license notices.
    lock = root / 'tools/bxroot/lock.json'
    if lock.is_file():
        paths.update('app/src/main/jniLibs/arm64-v8a/' + row['name']
                     for row in json.loads(lock.read_text(encoding='utf-8'))['artifacts'])
    return sorted(paths)


def render():
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf-8'))
    mobile = json.loads((ASSETS / 'builtin-plugins/dsh-web-mobile/package.json').read_text(encoding='utf-8'))
    tools = json.loads((ROOT / 'tools/runtime-tools.lock.json').read_text(encoding='utf-8'))
    packages = lock['packages']
    dsh = packages['node_modules/@deepseek-ai/dsh']['version']
    office = packages['node_modules/@deepseek-ai/libreoffice-kit']['version']
    wasm = packages['node_modules/@deepseek-ai/libreoffice-kit-wasm']['version']
    web = json.loads((ROOT / 'tools/web-compat/package-lock.json').read_text(encoding='utf-8'))
    corejs = web['packages']['node_modules/core-js']
    portable = json.loads((ROOT / 'tools/pc-migration/package-lock.json').read_text(encoding='utf-8'))['packages']['node_modules/yaml']
    bxroot = json.loads((ROOT / 'tools/bxroot/lock.json').read_text(encoding='utf-8'))
    acorn = json.loads((ROOT / 'tools/plugin-capabilities/package-lock.json').read_text(encoding='utf-8'))['packages']['node_modules/acorn']
    restricted = '\n'.join(f'| `{path}` | {row["version"]} | {row.get("license", "unknown")} | {row.get("resolved", "unknown")} |'
                           for path, row in sorted(packages.items()) if path and row.get('license', 'unknown') not in PERMISSIVE)
    android = '\n'.join('- `' + coordinate + '`' for coordinate in gradle_coordinates())
    native = '\n'.join(f'| `{path}` | {NATIVE_NOTICES.get(Path(path).name, ("unknown", "new binary without reviewed source"))[0]} | {NATIVE_NOTICES.get(Path(path).name, ("unknown", "new binary without reviewed source"))[1]} |'
                       for path in native_members())
    owned = '、'.join(f'`{row["name"]}` **{row["version"]}**' for row in owned_packages())
    hashes = '\n'.join(f'- `{name}`：`{hashlib.sha256((ASSETS / "builtin-plugins/dsh-web-mobile/lib" / name).read_bytes()).hexdigest()}`'
                       for name in ('client.js', 'index.js', 'compress.js', 'delete-session.js', 'reasoning-effort.js'))
    return f'''# 第三方组件声明

此文由 `python tools/generate-third-party-notices.py` 从当前锁和实际资产生成。DSHA 自有代码采用 MIT；随包第三方组件分别遵循其许可，不因 APK 顶层许可变成 MIT。

## DSH 与 npm 依赖

- 当前 `@deepseek-ai/dsh` 为 **{dsh}**。完整版本、来源与 integrity 见 `tools/dsh-runtime/package-lock.json`；各包原许可文件保留在运行归档中。
- DSHA 自有运行包 {owned} 采用本仓库 MIT 许可；精确来源为对应 `app/src/main/assets` 源码和 `LICENSE`（Copyright (c) 2026 qiannianhuanxiang），完整原文随 `assets/licenses/DSHA-MIT.txt` 提供并与仓库原件逐字节核对。自有包仅按固定名称、版本和完整 package.json 注册，不扩展到其它同前缀包或 MIT 声明。
- Office 入口 `@deepseek-ai/libreoffice-kit` **{office}**、WASM **{wasm}**：保留上游 NOTICE、licenses、sources 与预构建说明。DSHA 的字体路径适配见 `assets/office-fonts-patch.json`。
- pnpm **{tools['pnpm']['version']}**（MIT）和 certifi **{tools['certifi']['version']}**（MPL-2.0）：版本和原包摘要由 `tools/runtime-tools.lock.json` 固定。CA 未关闭 TLS 核验。
- 许可目录和实际依赖需结合归档成员检查；锁文件内其他平台可选依赖不等于已进入 arm64 APK。
- core-js **{corejs['version']}**（{corejs['license']}），原包来源 `{corejs['resolved']}`，全文为 `assets/web-integration/core-js.LICENSE`。
- 便携迁移 CLI 单独携带 yaml **{portable['version']}**（{portable['license']}）；来源 `{portable['resolved']}`，完整原包按 `tools/pc-migration/package-lock.json` 的 SHA-512 锁定，许可保留在 `assets/pc-migration/node_modules/yaml/LICENSE`。宿主准备不运行安装钩子，手机运行不重新解析依赖。
- 静态插件能力分析使用 acorn **{acorn['version']}**（{acorn['license']}），来源 `{acorn['resolved']}`，原包 integrity 见 `tools/plugin-capabilities/package-lock.json`；解析器和 MIT 原许可分别为 `assets/plugin-acorn.cjs` 与 `assets/plugin-acorn.LICENSE`，精确摘要由 `tools/plugin-capabilities/parser-lock.json` 和 APK 门禁核对。静态分析不执行插件模块。
- sharp **{packages['node_modules/sharp']['version']}** 为 Apache-2.0；arm64 libvips 包 **{packages['node_modules/@img/sharp-libvips-linux-arm64']['version']}** 声明 LGPL-3.0-or-later，来源为锁内 npm 原包和 https://github.com/lovell/sharp-libvips 。包内 README 含嵌入依赖及源码获取说明，应结合实际二进制核对；不把单一 libvips 许可扩展到所有嵌入库。
- `@ubjs/core` / `@ubjs/node` **{packages['node_modules/@ubjs/core']['version']}** 为 MPL-2.0，来源 https://github.com/jhugman/uniffi-bindgen-react-native ；`@trycua/cua-driver` **{packages['node_modules/@trycua/cua-driver']['version']}** 包装层为 MIT，而 Linux arm64 原生包为 **{packages['node_modules/@trycua/cua-driver-linux-arm64-gnu']['license']}**，来源 https://github.com/trycua/cua 。原生包声明与包装层分开核对。
- MPL-2.0、LGPL-3.0 与 GPL-3.0 全文随 `assets/licenses` 提供；文本精确来源与摘要记录在 `docs/audits/build156/third-party-license-sources.json`。通用许可全文不能证明二进制原件的来源或分发权利。

## 移动插件

- `dsh-web-mobile` **{mobile['version']}**，MIT，来自 `{mobile['dshaUpstream']['source']}`，固定提交 `{mobile['dshaUpstream']['commit']}`。
- 上游 client 摘要 `{mobile['dshaUpstream']['clientSha256']}`；本地差异由 `tools/apply-mobile-client-patches.mjs` 与 `tools/mobile-server/` 管理。不是未修改的上游产物。
- 许可全文：`app/src/main/assets/builtin-plugins/dsh-web-mobile/LICENSE`。当前产物摘要：

{hashes}

## 终端与会话启动器

- Termux terminal-emulator 的 `termux.c` 来自 v0.118.0；上游明确的 Apache-2.0 许可例外保存在 `tools/termux-jni/LICENSE.upstream.md`，全文为 `LICENSE-2.0.txt`。不要将该例外扩展到整个 Termux 应用。
- 本地 PTY 身份握手和会话查询差异见 `dsha-pty.c`、`dsha-process.c` 与 `build.ps1`，目标 API 23、LOAD 16 KiB、common-page-size 4096。
- DSHA 独立会话启动器源码为 `tools/native-session/session-launcher.c`；跨平台构建入口 `tools/native-session/build.py`，锁定 NDK 26.3.11579264。

## bxroot 与 proot 回退

- proot（Termux 分支）为 GPL-2.0。标准与兼容资产保留 COPYING；API 23 构建及源码差异见 `tools/build-low-proot.py`。完整声明位于 `assets/licenses/proot-COPYING.txt`。
- bxroot **{bxroot['source']['version']}** 源码为 MIT，固定来源 `{bxroot['source']['repo']}`、提交 `{bxroot['source']['commit']}`；原始许可全文为 `assets/licenses/bxroot-MIT.txt`。本地变更、实际构建工具链、源包/补丁和每个原生文件的摘要见 `tools/bxroot/lock.json`，不是未修改的上游发行二进制。
- bxroot launcher 与 ulx 为静态 ELF；bxroot 自有源码的 MIT 不改变其中静态工具链运行库的许可。实际工具链 `{bxroot['build']['compiler']}`、目标包版本、编译/链接命令、目标对象和 glibc/libgcc 输入摘要见 `tools/bxroot/lock.json` 与 `{bxroot['build']['relinkMaterial']}`；对应 glibc 源获取入口为 `{bxroot['build']['glibcSource']}`。重链接对象、crt、libc.a/libgcc.a/libgcc_eh.a 和链接映射保留；不要仅凭 ELF 无 DT_NEEDED 就称无第三方静态字节。
- glibc 的 LGPL-2.1-or-later 全文和实际 sysroot 包 copyright 分别随 `assets/licenses/bxroot-glibc-COPYING.LIB.txt`、`bxroot-glibc-copyright.txt` 提供。GCC 实际包 copyright 为 `bxroot-gcc-copyright.txt`，GPL-3.0 全文及固定 GCC13.3.0 的 `GCC-RUNTIME-EXCEPTION-3.1.txt` 同时提供。源文件/许可与目标包版本分别核对，旧 copyright 正文中的版本标签不改写为本轮二进制版本。
- 发行配置为 default，SHM claim/copyout 两个 verified gate 均为 0；关闭 reentry 和外部 stub，保留 strict exec。本轮 Android 过滤链和设备执行未验，不能把宿主 ELF/源码门禁当真机成功。
- 生产 APK 移除 proroot，保留原 proot 两 flavor 源文件字节作为明确兼容回退；32 位辅助 loader 继续使用 build171 的既有 strip 映射，`tools/native-packaging.lock.json` 绑定原始摘要、NDK/LLVM 参数和包内摘要，实际 APK 保持既有回退字节。bxroot 是兼容运行时，不是安全沙箱或 Android root；最低内核要求与 APK 的 minSdk 是不同门槛。
- Windows 重编、Linux 重编与手机执行是不同证据；不能从一种宿主成功推导全部平台通过。

## Android 与备份依赖

- Shizuku API/provider：Standard 13.1.5、Low 12.2.0（MIT），公开来源 RikkaApps/Shizuku-API；许可全文随 `assets/licenses/Shizuku-MIT.txt` 保存，原始 Git blob 及 SHA 记录在 build154 审计。
- Termux terminal-view / terminal-emulator 0.118.0 的 Apache-2.0 例外及全文随 `assets/licenses/Termux-terminal-Apache-2.0.txt` 保存；不将其声明扩展到整个上游工程。
- Gson 2.13.1：Apache-2.0；Bouncy Castle bcprov-jdk15to18 1.85.2：上游许可全文随 `assets/licenses` 保存。版本与 JAR 摘要见 `tools/backup-dependencies.lock.json`，构建执行 `verifyBackupDependencies`。
- SnakeYAML 2.4（Apache-2.0）：固定官方 Maven Central JAR/POM 与 `snakeyaml-2.4` 源码标签，使用宿主 data-only SafeConstructor 投影凭据中的本机连接字段。许可原文为 `assets/licenses/snakeyaml-LICENSE.txt`，Java 8 类文件及 Android FIELD 路径见 `docs/audits/build156/snakeyaml-official-artifacts.json`；不由静态兼容声明推导旧设备验收完成。
- Low 的 GeckoView 143.0.20251003115653 为 MPL-2.0，来自 Mozilla Maven；保留相应声明。AAR 原件与其中 12 个 arm64 原生成员的精确摘要见 `tools/native-dependencies.lock.json`，实际 APK 逐成员核对，Standard 不含这些成员。兼容版最低 API 23，不表示所有旧设备已经验收。
- Ubuntu 软件包各自遵循其许可；包内 `/usr/share/doc` 与 common-licenses 保留版权信息。根环境仍为 Ubuntu 24.04 arm64，DSHA 基础环境身份 10。
- node-semver 7.8.1 为 ISC，完整许可随 `assets/plugin-semver.cjs` 提供。

当前 Gradle 直接运行依赖坐标（不包含测试依赖；完整传递依赖许可审查仍需实际解析结果）：

{android}

## Miuix 与 Compose 原生界面

- Miuix `top.yukonga.miuix.kmp:miuix:0.8.8`：Apache-2.0，源码与许可见 https://github.com/compose-miuix-ui/miuix/tree/v0.8.8 。
- AndroidX Compose UI / Foundation 1.10.5 与 AppCompat 1.7.1：Apache-2.0，源码见 https://android.googlesource.com/platform/frameworks/support/ 。
- Compose 的 Android 图形库 `androidx.graphics:graphics-path:1.0.1`：Apache-2.0；两版 APK 均包含 ARM64 `libandroidx.graphics.path.so`，AAR 及成员摘要由 `tools/native-dependencies.lock.json` 与实际 APK 门禁核对。
- Kotlin Compose 编译器 2.3.20：Apache-2.0，仅在构建时使用，源码见 https://github.com/JetBrains/kotlin 。
- Android 依赖由 Maven 获取固定发布版本，其 SHA-256 由 `gradle/verification-metadata.xml` 核验。

## 实际入库 JNI 成员与来源边界

| 资产 | 许可声明 | 来源 / 可核验范围 |
|---|---|---|
{native}

## npm 锁中需另核对的许可声明

此表来自当前锁，包含其它平台可选包。`check-third-party-notices.py --archive` 再核对实际运行归档中的包版本和声明；两者都不代替源码提供、嵌入库或法律来源核验。

| 锁内路径 | 版本 | 上游声明 | 原包来源 |
|---|---|---|---|
{restricted}

GPL、MPL、LGPL 等组件的具体再分发义务应按相应包和修改范围核对。源码入口与许可副本不等于已证明所有上游二进制可逐字节重现。
'''


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    path = ROOT / 'THIRD_PARTY_NOTICES.md'
    content = render()
    if args.check:
        if path.read_text(encoding='utf-8') != content:
            raise SystemExit('THIRD_PARTY_NOTICES_STALE')
    else:
        path.write_text(content, encoding='utf-8', newline='\n')
    print('PASS current locked third-party identities')
