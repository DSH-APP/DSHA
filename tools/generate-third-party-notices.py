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
    'libproroot.so': ('upstream proprietary', 'proroot v1.2.8 original binary; source reproducibility not asserted'),
    'libproroot-runtime.so': ('upstream proprietary', 'proroot v1.2.8 original binary'),
    'libproroot-linker.so': ('upstream proprietary', 'proroot v1.2.8 original binary'),
    'libproroot-bridge.so': ('upstream proprietary', 'proroot v1.2.8 original binary'),
    'libproroot-stub-loader.so': ('upstream proprietary', 'proroot v1.2.8 original binary'),
}


def gradle_coordinates(root=ROOT):
    build = (root / 'app/build.gradle').read_text(encoding='utf-8')
    return sorted(set(re.findall(r"^\s*(?:implementation|standardImplementation|lowImplementation|coreLibraryDesugaring)\s+'([^']+)'", build, re.M)))


def native_members(root=ROOT):
    return sorted(p.relative_to(root).as_posix() for flavor in ('main', 'low')
                  for p in (root / f'app/src/{flavor}/jniLibs').rglob('*.so'))


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
    restricted = '\n'.join(f'| `{path}` | {row["version"]} | {row.get("license", "unknown")} | {row.get("resolved", "unknown")} |'
                           for path, row in sorted(packages.items()) if path and row.get('license', 'unknown') not in PERMISSIVE)
    android = '\n'.join('- `' + coordinate + '`' for coordinate in gradle_coordinates())
    native = '\n'.join(f'| `{path}` | {NATIVE_NOTICES.get(Path(path).name, ("unknown", "new binary without reviewed source"))[0]} | {NATIVE_NOTICES.get(Path(path).name, ("unknown", "new binary without reviewed source"))[1]} |'
                       for path in native_members())
    hashes = '\n'.join(f'- `{name}`：`{hashlib.sha256((ASSETS / "builtin-plugins/dsh-web-mobile/lib" / name).read_bytes()).hexdigest()}`'
                       for name in ('client.js', 'index.js', 'compress.js', 'delete-session.js'))
    return f'''# 第三方组件声明

此文由 `python tools/generate-third-party-notices.py` 从当前锁和实际资产生成。DSHA 自有代码采用 MIT；随包第三方组件分别遵循其许可，不因 APK 顶层许可变成 MIT。

## DSH 与 npm 依赖

- 当前 `@deepseek-ai/dsh` 为 **{dsh}**。完整版本、来源与 integrity 见 `tools/dsh-runtime/package-lock.json`；各包原许可文件保留在运行归档中。
- Office 入口 `@deepseek-ai/libreoffice-kit` **{office}**、WASM **{wasm}**：保留上游 NOTICE、licenses、sources 与预构建说明。DSHA 的字体路径适配见 `assets/office-fonts-patch.json`。
- pnpm **{tools['pnpm']['version']}**（MIT）和 certifi **{tools['certifi']['version']}**（MPL-2.0）：版本和原包摘要由 `tools/runtime-tools.lock.json` 固定。CA 未关闭 TLS 核验。
- 许可目录和实际依赖需结合归档成员检查；锁文件内其他平台可选依赖不等于已进入 arm64 APK。
- core-js **{corejs['version']}**（{corejs['license']}），原包来源 `{corejs['resolved']}`，全文为 `assets/web-integration/core-js.LICENSE`。
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

## proot / proroot

- proot（Termux 分支）为 GPL-2.0。标准与兼容资产保留 COPYING；API 23 构建及源码差异见 `tools/build-low-proot.py`。完整声明位于 `assets/licenses/proot-COPYING.txt`。
- proroot v1.2.8 为上游专有许可。DSHA 分发上游原始二进制，不声称可重编、审计源码或任意修改后再分发；许可证与来源见随包声明。不能用容器 root 身份推断 Android root 权限。
- Windows 重编、Linux 重编与手机执行是不同证据；不能从一种宿主成功推导全部平台通过。

## Android 与备份依赖

- Shizuku API/provider：Standard 13.1.5、Low 12.2.0（MIT），公开来源 RikkaApps/Shizuku-API；许可全文随 `assets/licenses/Shizuku-MIT.txt` 保存，原始 Git blob 及 SHA 记录在 build154 审计。
- Termux terminal-view / terminal-emulator 0.118.0 的 Apache-2.0 例外及全文随 `assets/licenses/Termux-terminal-Apache-2.0.txt` 保存；不将其声明扩展到整个上游工程。
- Gson 2.13.1：Apache-2.0；Bouncy Castle bcprov-jdk15to18 1.85.2：上游许可全文随 `assets/licenses` 保存。版本与 JAR 摘要见 `tools/backup-dependencies.lock.json`，构建执行 `verifyBackupDependencies`。
- SnakeYAML 2.4（Apache-2.0）：固定官方 Maven Central JAR/POM 与 `snakeyaml-2.4` 源码标签，使用宿主 data-only SafeConstructor 投影凭据中的本机连接字段。许可原文为 `assets/licenses/snakeyaml-LICENSE.txt`，Java 8 类文件及 Android FIELD 路径见 `docs/audits/build156/snakeyaml-official-artifacts.json`；不由静态兼容声明推导旧设备验收完成。
- Low 的 GeckoView 143.0.20251003115653 为 MPL-2.0，来自 Mozilla Maven；保留相应声明。兼容版最低 API 23，不表示所有旧设备已经验收。
- Ubuntu 软件包各自遵循其许可；包内 `/usr/share/doc` 与 common-licenses 保留版权信息。根环境仍为 Ubuntu 24.04 arm64，DSHA 基础环境身份 10。
- node-semver 7.8.1 为 ISC，完整许可随 `assets/plugin-semver.cjs` 提供。

当前 Gradle 直接运行依赖坐标（不包含测试依赖；完整传递依赖许可审查仍需实际解析结果）：

{android}

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
