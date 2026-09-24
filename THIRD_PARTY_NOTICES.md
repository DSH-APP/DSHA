# 第三方组件声明

DSHA 的 APK 内包含以下第三方二进制组件。

## DSH 0.1.6-alpha.2 Office 预览

- 随锁定的 DSH 包包含 `@deepseek-ai/libreoffice-kit@0.0.1` 与 Linux 使用的 `@deepseek-ai/libreoffice-kit-wasm@0.0.1`，入口包声明 MPL-2.0；上游引擎、集成代码及第三方组件的声明分别保留在包内 `NOTICE`、`licenses`、`sources` 与 `prebuilds.json` 中。
- DSHA 仅补充 Android `/system/fonts` 到默认字体查找路径；使用已有系统字体，不额外复制系统字体。修改由 `assets/office-fonts-patch.json` 记录，用户明确配置的字体目录仍按上游规则优先使用。

## rc2.1 宿主数据保护依赖

- Gson **2.13.1**：仅使用受限 JSON 流式读写，不使用任意类型反序列化。许可为 Apache-2.0，随包全文位于 `assets/licenses/gson-LICENSE.txt`。[上游版本与许可](https://github.com/google/gson/tree/gson-parent-2.13.1)。
- Bouncy Castle **bcprov-jdk15to18 1.85.2**：使用轻量 PBKDF2-HMAC-SHA256 和 AES-GCM API，不注册或替换系统全局 Provider，也没有明文降级路径。许可全文从该 JAR 的 `org.bouncycastle.LICENSE` 导出，位于 `assets/licenses/bouncycastle-LICENSE.txt`。[上游许可](https://www.bouncycastle.org/licence.html)。
- 两个 flavor 均由 `tools/backup-dependencies.lock.json` 固定版本与 JAR SHA-256；Java 编译前执行 `verifyBackupDependencies`，不匹配即停止构建。JVM 测试覆盖 Node/OpenSSL 固定向量及 JCE 互操作；API 23 上的实际运行尚待用户安排的设备验证。

## Termux 终端 JNI（标准版）

- 来源：`termux/termux-app` 的 `v0.118.0`，`terminal-emulator/src/main/jni/termux.c`。
- 许可：Apache-2.0（上游对 terminal-emulator 的许可例外，说明与许可全文保存在 `tools/termux-jni/`）。
- 在包内的位置：`lib/arm64-v8a/libtermux.so`。
- 标准版使用 NDK r26d 从同版本原始源码重新编译，保持原有 JNI 接口，设置 16 KB ELF 页对齐。
- 源码及复现命令：`tools/termux-jni/termux.c`、`tools/termux-jni/build.ps1`。

## proot（Termux 分支）

- 来源：https://github.com/termux/proot
- 许可：GPL-2.0
- 在包内的位置：`lib/arm64-v8a/libproot.so`、`libprootloader.so`、
  `libprootloader32.so`、`libtalloc.so`、`libandroidshmem.so`
- 用途：默认的容器运行时，用 ptrace 实现免 root 的 chroot 环境
- 说明：文件名带 `lib` 前缀、`.so` 后缀是 Android 打包要求
  （只有这样系统才会把它提取到可执行的 `nativeLibraryDir`），
  内容未作修改

## proroot

- 来源：https://github.com/coderredlab/proroot（v1.2.8）
- 许可：Proprietary。README 原文：*"Free to use in your projects.
  Redistribution of modified binaries is not permitted."*
  —— 允许在项目中使用，禁止分发**修改过**的二进制
- 在包内的位置：`lib/arm64-v8a/libproroot.so`、`libproroot-runtime.so`、
  `libproroot-linker.so`、`libproroot-stub-loader.so`、`libproroot-bridge.so`
- 用途：**默认**的容器运行时（v1.1.6 起）。用 LD_PRELOAD + 二进制补丁做进程内
  路径翻译，没有 ptrace 的上下文切换开销，真机实测启动快 5~6 倍
- 分发的是官方 release 的**原始二进制**，未作任何修改，sha256 与
  上游 release notes 一致：

```
a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01  libproroot.so
8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34  libproroot-runtime.so
1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f  libproroot-bridge.so
51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee  libproroot-linker.so
06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0  libproroot-stub-loader.so
```

### 为什么随包分发而不是按需下载

Android 10+ 的 W^X 策略不允许从应用可写目录（`filesDir`）执行代码。
下载到 `filesDir` 的 `.so` 无法执行，只有放进 APK 的 `jniLibs`、
由系统提取到 `nativeLibraryDir` 才能跑。现有的 `libproot.so` 同理。

### 用户可控性

- **默认启用**（v1.1.6 起），可在「配置」页取消勾选改用传统 proot
- 不参与装机路径（解压、安装六步一律用 proot），只影响「执行命令」这一层
- 运行时文件缺失时自动降回 proot
- 连续 3 次启动失败会强制切回 proot 并告知用户
- 因此最坏情况是这一层退回 proot，不会导致环境不可用 ——
  这是敢把闭源组件设为默认的前提：**它不可用时系统自动绕过它**

### 已知限制

- 上游未公开源码，无法审计，出问题只能等作者修
- 作者已将开发重心转向另一个项目（proroom），更新频率会下降
- 因闭源，Termux 官方仓库拒绝收录（见 proroot issue #21）

## 内置移动端适配插件（dsh-mobile-nav）

- 上游：[mexiaosqwq/dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)
- 包名：`@dsh-external/dsh-mobile-nav`
- 版本：`v2.1.1`（按 tag 固定，不跟 main 漂移）
- 许可：**MIT** —— 许可证全文随文件一并分发于 `app/src/main/assets/mobile-nav/LICENSE`

随 APK 分发的是上游仓库里的构建产物，未作任何修改：

| 文件 | sha256 |
| --- | --- |
| `lib/client.js` | `6c6ee969b3de2d7f04eafd4b70319c8f9c8891a72a21090fb5878636be6b2e04` |
| `lib/index.js` | `855d07192c12ac831830e87246216dbc74ad8c83a6d67ae00ee7e89a378591ef` |
| `package.json` | `b80273b3cb53a7c2aac643a6838c7d4d39f98374c22ee467692f977d41fc61ab` |
| `cordis.patch.yml` | `427367650ec107cf5fd35cc6496398c629680f57cd6f7b980a3622fd70e082ef` |
| `LICENSE` | `0d50650e8ee0e00996facf70e6d246dddb836e27c4ce7027cdcf800ac5758f4b` |

### 为什么随包分发

装机要离线可用，这是相对同类项目的主要优势；而移动端适配是「手机上能不能正常用」
的前提，不该依赖首启联网。插件是单文件构建产物（~132KB），纯前端 DOM/CSS 改造 ——
零网络请求、无 `eval`/`new Function`，外部依赖只有官方浏览器侧共享的 `react` 与
`@deepseek-ai/dsh-client-ui-primitives`，随包带上代价很小。

### 关系说明

我们只负责把上游产物打进 APK 并做安置/注册，插件的功能与 UI 行为归上游维护。
界面细节问题建议直接反馈到上游仓库。

### 替换历史

这次更换之前内置的是 `dsh-client-ui-mobile-adapt`
（[Hotsteel2901](https://github.com/Hotsteel2901/dsh-client-ui-mobile-adapt)，MIT），
因作者长期停更而换掉。升级时 App 会自动把旧插件从 profile 的 `bundles` /
`dependencies` 摘掉并删除实体（`migrateLegacyMobileAdapt`）—— 两个插件改造同一批
DOM 元素，同时激活会互相打架（抽屉/浮层出两份、事件绑定两遍）。如果你此前手动
禁用过旧插件，新插件会沿用「已禁用」状态，不会被悄悄打开。

## 其他

- 随包 CA 证书来自 certifi 2026.7.22 / Mozilla 根证书集合（MPL-2.0），许可证随 assets/licenses/certifi-LICENSE.txt 提供。
  构建脚本 tools/build-standard-runtime.py 固定官方下载地址与 SHA-256；没有关闭 TLS 验证。
- low 兼容版内核：GeckoView 143.0.20251003115653（MPL-2.0），来自 Mozilla Maven；
  [对应源码](https://hg.mozilla.org/releases/mozilla-release/rev/08388fb6b18c61dbb3d0baa9bee4e7440b85f671)。
  后续 Firefox Android 提高了最低系统要求，因此保留兼容 Android 6/7 的这个版本，仅用于本机 dsh 预览。
- low 兼容版容器：Termux proot v5.1.107.92（GPL-2.0），
  [上游源码](https://github.com/termux/proot/tree/v5.1.107.92)；API 23 编译配置、兼容函数和 fd 断言补丁完整保存在
  `tools/build-low-proot.py`，脚本校验上游归档 SHA-256 后可重现构建。COPYING 随兼容包资产分发。
- 标准版补充资产 `python-support.bin`：Ubuntu 24.04 arm64 的 `libsqlite3-0` 3.45.1-1ubuntu2.7、
  `libreadline8t64` 8.2-4build1，未修改二进制。版权文件随包保存在容器 `usr/share/doc`。
  对应源码：[sqlite3](https://launchpad.net/ubuntu/+source/sqlite3/3.45.1-1ubuntu2.7)、
  [readline](https://launchpad.net/ubuntu/+source/readline/8.2-4build1)。
- `pnpm-runtime.bin`：pnpm 10.34.5（MIT），来自 npm 官方发布包，保留许可证，省略其他平台的可执行文件。
  [源码](https://github.com/pnpm/pnpm/tree/v10.34.5)；可用 `tools/build-standard-runtime.py` 按固定校验值复现资产。
- Ubuntu arm64 rootfs（`assets/offline-rootfs.bin`）：各软件包遵循各自许可
- GeckoView（`libxul.so` 等）：MPL-2.0
- `@deepseek-ai/dsh`：见其 npm 包内的许可声明

## npm node-semver 7.8.1

插件版本兼容性使用 [npm/node-semver](https://github.com/npm/node-semver) 7.8.1，遵循 ISC 许可。完整许可随 `app/src/main/assets/plugin-semver.cjs` 一并分发；生成方式见 `tools/vendor-plugin-semver.cjs`。

## 0.1.6-alpha1 运行时扩展

完整依赖和摘要锁定在 `tools/dsh-runtime/package-lock.json`，原 npm 包的许可文件保留在离线运行时中。新增主要组件按发布包声明：

- DSH 0.1.6-alpha.1 及其 Browser Use、Computer Use、Auto review、Team、SSH 等官方组件：见各包随附许可。
- `@trycua/cua-driver` 0.28.0：MIT；Linux arm64 原生可选依赖一并保留。
- `@browserbasehq/stagehand` 4.1.0：MIT。
- `@playwright/mcp` 0.0.80：Apache-2.0。
- `chrome-devtools-mcp` 1.9.0：Apache-2.0。

DSHA 的 Android Computer Use 适配层和独立会话启动器为本仓库实现，沿用本仓库 MIT 许可；未替换上游 Cua Driver 的实现或将其标为 Android 原生驱动。
