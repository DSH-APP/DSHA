# 第三方组件声明

此文由 `python tools/generate-third-party-notices.py` 从当前锁和实际资产生成。DSHA 自有代码采用 MIT；随包第三方组件分别遵循其许可，不因 APK 顶层许可变成 MIT。

## DSH 与 npm 依赖

- 当前 `@deepseek-ai/dsh` 为 **0.2.0-rc.2**。完整版本、来源与 integrity 见 `tools/dsh-runtime/package-lock.json`；各包原许可文件保留在运行归档中。
- Office 入口 `@deepseek-ai/libreoffice-kit` **0.1.2**、WASM **0.1.1**：保留上游 NOTICE、licenses、sources 与预构建说明。DSHA 的字体路径适配见 `assets/office-fonts-patch.json`。
- pnpm **10.34.5**（MIT）和 certifi **2026.7.22**（MPL-2.0）：版本和原包摘要由 `tools/runtime-tools.lock.json` 固定。CA 未关闭 TLS 核验。
- 许可目录和实际依赖需结合归档成员检查；锁文件内其他平台可选依赖不等于已进入 arm64 APK。
- core-js **3.50.0**（MIT），原包来源 `https://registry.npmjs.org/core-js/-/core-js-3.50.0.tgz`，全文为 `assets/web-integration/core-js.LICENSE`。
- sharp **0.35.5** 为 Apache-2.0；arm64 libvips 包 **1.3.4** 声明 LGPL-3.0-or-later，来源为锁内 npm 原包和 https://github.com/lovell/sharp-libvips 。包内 README 含嵌入依赖及源码获取说明，应结合实际二进制核对；不把单一 libvips 许可扩展到所有嵌入库。
- `@ubjs/core` / `@ubjs/node` **0.31.0-3** 为 MPL-2.0，来源 https://github.com/jhugman/uniffi-bindgen-react-native ；`@trycua/cua-driver` **0.28.0** 包装层为 MIT，而 Linux arm64 原生包为 **MIT AND MPL-2.0**，来源 https://github.com/trycua/cua 。原生包声明与包装层分开核对。
- MPL-2.0、LGPL-3.0 与 GPL-3.0 全文随 `assets/licenses` 提供；文本精确来源与摘要记录在 `docs/audits/build156/third-party-license-sources.json`。通用许可全文不能证明二进制原件的来源或分发权利。

## 移动插件

- `dsh-web-mobile` **3.0.3**，MIT，来自 `https://github.com/mexiaosqwq/dsh-web-mobile`，固定提交 `a094288883b343e848d7f9cf302d73ad8ed4794b`。
- 上游 client 摘要 `88bfc7b315249cbe8a4fcbcaf41854cce3a8480a5b98a7bdb063ba78b1ae9a19`；本地差异由 `tools/apply-mobile-client-patches.mjs` 与 `tools/mobile-server/` 管理。不是未修改的上游产物。
- 许可全文：`app/src/main/assets/builtin-plugins/dsh-web-mobile/LICENSE`。当前产物摘要：

- `client.js`：`3e5568a4c41730df9b3a01261b7781678cb74866e1c96f45164204e5e9a6c5e7`
- `index.js`：`1ab8021dec1453ed7e48a43269efcc46598a27005de2d51b6644579544c90445`
- `compress.js`：`f5902f4f036d0f7a67d2ce6e4c71b01a98227cb4365abde7a4bf8f1bf5e6de0c`
- `delete-session.js`：`f4f8caf0fd54ab49ad264908296b12d9d46868542b082136927afe3c21d32b19`

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

- `androidx.appcompat:appcompat:1.6.1`
- `androidx.webkit:webkit:1.15.0`
- `com.android.tools:desugar_jdk_libs:2.0.4`
- `com.google.android.material:material:1.11.0`
- `com.google.code.gson:gson:2.13.1`
- `com.termux.termux-app:terminal-view:0.118.0`
- `dev.rikka.shizuku:api:12.2.0`
- `dev.rikka.shizuku:api:13.1.5`
- `dev.rikka.shizuku:provider:12.2.0`
- `dev.rikka.shizuku:provider:13.1.5`
- `org.bouncycastle:bcprov-jdk15to18:1.85.2`
- `org.mozilla.geckoview:geckoview-arm64-v8a:143.0.20251003115653`
- `org.yaml:snakeyaml:2.4`

## 实际入库 JNI 成员与来源边界

| 资产 | 许可声明 | 来源 / 可核验范围 |
|---|---|---|
| `app/src/low/jniLibs/arm64-v8a/libproot_legacy.so` | GPL-2.0 | tools/build-low-proot.py, proot v5.1.107.92 source archive and local changes |
| `app/src/low/jniLibs/arm64-v8a/libprootloader_legacy.so` | GPL-2.0 | tools/build-low-proot.py, proot v5.1.107.92 source archive |
| `app/src/main/jniLibs/arm64-v8a/libandroidshmem.so` | unknown origin/license for this original binary | Retained original; no current exact-source reproduction receipt |
| `app/src/main/jniLibs/arm64-v8a/libdsha-session.so` | MIT | tools/native-session/session-launcher.c; locked API 23 build |
| `app/src/main/jniLibs/arm64-v8a/libproot.so` | GPL-2.0 | Termux proot; original Standard binary exact revision remains unverified |
| `app/src/main/jniLibs/arm64-v8a/libprootloader.so` | GPL-2.0 | Termux proot; original Standard binary exact revision remains unverified |
| `app/src/main/jniLibs/arm64-v8a/libprootloader32.so` | GPL-2.0 | Termux proot; original Standard binary exact revision remains unverified |
| `app/src/main/jniLibs/arm64-v8a/libproroot-bridge.so` | upstream proprietary | proroot v1.2.8 original binary |
| `app/src/main/jniLibs/arm64-v8a/libproroot-linker.so` | upstream proprietary | proroot v1.2.8 original binary |
| `app/src/main/jniLibs/arm64-v8a/libproroot-runtime.so` | upstream proprietary | proroot v1.2.8 original binary |
| `app/src/main/jniLibs/arm64-v8a/libproroot-stub-loader.so` | upstream proprietary | proroot v1.2.8 original binary |
| `app/src/main/jniLibs/arm64-v8a/libproroot.so` | upstream proprietary | proroot v1.2.8 original binary; source reproducibility not asserted |
| `app/src/main/jniLibs/arm64-v8a/libtalloc.so` | LGPL family; exact binary revision unverified | Samba talloc; build-low-proot.py locks talloc 2.4.3 headers, not the origin of this binary |
| `app/src/main/jniLibs/arm64-v8a/libtermux.so` | Apache-2.0 exception | tools/termux-jni, v0.118.0 source plus local handshake changes |

## npm 锁中需另核对的许可声明

此表来自当前锁，包含其它平台可选包。`check-third-party-notices.py --archive` 再核对实际运行归档中的包版本和声明；两者都不代替源码提供、嵌入库或法律来源核验。

| 锁内路径 | 版本 | 上游声明 | 原包来源 |
|---|---|---|---|
| `node_modules/@deepseek-ai/libreoffice-kit` | 0.1.2 | MPL-2.0 | https://registry.npmjs.org/@deepseek-ai/libreoffice-kit/-/libreoffice-kit-0.1.2.tgz |
| `node_modules/@deepseek-ai/libreoffice-kit-darwin-arm64` | 0.1.1 | MPL-2.0 | https://registry.npmjs.org/@deepseek-ai/libreoffice-kit-darwin-arm64/-/libreoffice-kit-darwin-arm64-0.1.1.tgz |
| `node_modules/@deepseek-ai/libreoffice-kit-darwin-x64` | 0.1.1 | MPL-2.0 | https://registry.npmjs.org/@deepseek-ai/libreoffice-kit-darwin-x64/-/libreoffice-kit-darwin-x64-0.1.1.tgz |
| `node_modules/@deepseek-ai/libreoffice-kit-wasm` | 0.1.1 | MPL-2.0 | https://registry.npmjs.org/@deepseek-ai/libreoffice-kit-wasm/-/libreoffice-kit-wasm-0.1.1.tgz |
| `node_modules/@deepseek-ai/libreoffice-kit-win32-arm64` | 0.1.2 | MPL-2.0 | https://registry.npmjs.org/@deepseek-ai/libreoffice-kit-win32-arm64/-/libreoffice-kit-win32-arm64-0.1.2.tgz |
| `node_modules/@deepseek-ai/libreoffice-kit-win32-x64` | 0.1.2 | MPL-2.0 | https://registry.npmjs.org/@deepseek-ai/libreoffice-kit-win32-x64/-/libreoffice-kit-win32-x64-0.1.2.tgz |
| `node_modules/@img/sharp-libvips-darwin-arm64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-darwin-arm64/-/sharp-libvips-darwin-arm64-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-darwin-x64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-darwin-x64/-/sharp-libvips-darwin-x64-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linux-arm` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linux-arm/-/sharp-libvips-linux-arm-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linux-arm64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linux-arm64/-/sharp-libvips-linux-arm64-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linux-ppc64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linux-ppc64/-/sharp-libvips-linux-ppc64-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linux-riscv64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linux-riscv64/-/sharp-libvips-linux-riscv64-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linux-s390x` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linux-s390x/-/sharp-libvips-linux-s390x-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linux-x64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linux-x64/-/sharp-libvips-linux-x64-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linuxmusl-arm64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linuxmusl-arm64/-/sharp-libvips-linuxmusl-arm64-1.3.4.tgz |
| `node_modules/@img/sharp-libvips-linuxmusl-x64` | 1.3.4 | LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-libvips-linuxmusl-x64/-/sharp-libvips-linuxmusl-x64-1.3.4.tgz |
| `node_modules/@img/sharp-wasm32` | 0.35.5 | Apache-2.0 AND LGPL-3.0-or-later AND MIT | https://registry.npmjs.org/@img/sharp-wasm32/-/sharp-wasm32-0.35.5.tgz |
| `node_modules/@img/sharp-win32-arm64` | 0.35.5 | Apache-2.0 AND LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-win32-arm64/-/sharp-win32-arm64-0.35.5.tgz |
| `node_modules/@img/sharp-win32-ia32` | 0.35.5 | Apache-2.0 AND LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-win32-ia32/-/sharp-win32-ia32-0.35.5.tgz |
| `node_modules/@img/sharp-win32-x64` | 0.35.5 | Apache-2.0 AND LGPL-3.0-or-later | https://registry.npmjs.org/@img/sharp-win32-x64/-/sharp-win32-x64-0.35.5.tgz |
| `node_modules/@trycua/cua-driver-darwin-arm64` | 0.28.0 | MIT AND MPL-2.0 | https://registry.npmjs.org/@trycua/cua-driver-darwin-arm64/-/cua-driver-darwin-arm64-0.28.0.tgz |
| `node_modules/@trycua/cua-driver-darwin-x64` | 0.28.0 | MIT AND MPL-2.0 | https://registry.npmjs.org/@trycua/cua-driver-darwin-x64/-/cua-driver-darwin-x64-0.28.0.tgz |
| `node_modules/@trycua/cua-driver-linux-arm64-gnu` | 0.28.0 | MIT AND MPL-2.0 | https://registry.npmjs.org/@trycua/cua-driver-linux-arm64-gnu/-/cua-driver-linux-arm64-gnu-0.28.0.tgz |
| `node_modules/@trycua/cua-driver-linux-x64-gnu` | 0.28.0 | MIT AND MPL-2.0 | https://registry.npmjs.org/@trycua/cua-driver-linux-x64-gnu/-/cua-driver-linux-x64-gnu-0.28.0.tgz |
| `node_modules/@trycua/cua-driver-win32-arm64-msvc` | 0.28.0 | MIT AND MPL-2.0 | https://registry.npmjs.org/@trycua/cua-driver-win32-arm64-msvc/-/cua-driver-win32-arm64-msvc-0.28.0.tgz |
| `node_modules/@trycua/cua-driver-win32-x64-msvc` | 0.28.0 | MIT AND MPL-2.0 | https://registry.npmjs.org/@trycua/cua-driver-win32-x64-msvc/-/cua-driver-win32-x64-msvc-0.28.0.tgz |
| `node_modules/@ubjs/core` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/core/-/core-0.31.0-3.tgz |
| `node_modules/@ubjs/node` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node/-/node-0.31.0-3.tgz |
| `node_modules/@ubjs/node-darwin-arm64` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-darwin-arm64/-/node-darwin-arm64-0.31.0-3.tgz |
| `node_modules/@ubjs/node-darwin-x64` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-darwin-x64/-/node-darwin-x64-0.31.0-3.tgz |
| `node_modules/@ubjs/node-linux-arm64-gnu` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-linux-arm64-gnu/-/node-linux-arm64-gnu-0.31.0-3.tgz |
| `node_modules/@ubjs/node-linux-arm64-musl` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-linux-arm64-musl/-/node-linux-arm64-musl-0.31.0-3.tgz |
| `node_modules/@ubjs/node-linux-x64-gnu` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-linux-x64-gnu/-/node-linux-x64-gnu-0.31.0-3.tgz |
| `node_modules/@ubjs/node-linux-x64-musl` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-linux-x64-musl/-/node-linux-x64-musl-0.31.0-3.tgz |
| `node_modules/@ubjs/node-win32-arm64-msvc` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-win32-arm64-msvc/-/node-win32-arm64-msvc-0.31.0-3.tgz |
| `node_modules/@ubjs/node-win32-x64-msvc` | 0.31.0-3 | MPL-2.0 | https://registry.npmjs.org/@ubjs/node-win32-x64-msvc/-/node-win32-x64-msvc-0.31.0-3.tgz |
| `node_modules/argparse` | 2.0.1 | Python-2.0 | https://registry.npmjs.org/argparse/-/argparse-2.0.1.tgz |

GPL、MPL、LGPL 等组件的具体再分发义务应按相应包和修改范围核对。源码入口与许可副本不等于已证明所有上游二进制可逐字节重现。
