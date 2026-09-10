# DSHA

<p align="center">
  <b>DeepSeek Harness 安卓启动器</b><br>
  在手机上跑完整的 <a href="https://github.com/deepseek-ai/deepseek-harness">deepseek-harness</a> —— 免 ROOT，免 Termux，装完即用
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT"></a>
  <a href="https://github.com/DSH-APP/DSHA/releases/latest"><img src="https://img.shields.io/github/v/release/DSH-APP/DSHA?sort=date&color=blue" alt="release"></a>
  <a href="https://github.com/DSH-APP/DSHA/stargazers"><img src="https://img.shields.io/github/stars/DSH-APP/DSHA?style=flat" alt="stars"></a>
  <img src="https://img.shields.io/badge/Android-6%2B%20%2F%2011%2B-3DDC84?logo=android&logoColor=white" alt="android">
  <img src="https://img.shields.io/badge/arch-arm64--v8a-lightgrey" alt="arch">
</p>

<p align="center">
  <a href="README.en.md">English</a> · <b>简体中文</b> · <a href="CHANGELOG.md">更新记录</a> · <a href="docs/security-model.md">安全模型</a> · <a href="AGENTS.md">AGENTS.md（给 AI / 开发者）</a>
</p>

> 🤖 下一个 AI / 开发者请先读 **[AGENTS.md](AGENTS.md)**（项目结构、启动契约、踩过的坑），不要先全库扫描。

---

## DSHA v0.1.5-rc1 正式版 · Latest

本版由贡献者 [@ym2025szz](https://github.com/ym2025szz) 维护并发布，感谢原作者 [@qiannianhuanxiang](https://github.com/qiannianhuanxiang) 和其他贡献者。此前的重构预览更新归并到本正式版介绍；1.1.10 及更早版本的历史发布与下方原始正文保留。

**[最新正式版与下载](https://github.com/DSH-APP/DSHA/releases/latest)** · [完整更新与预览版对比](docs/releases/v0.1.5-rc1-notes.md) · [真机验收记录](docs/releases/v0.1.5-rc1.md)

| 版本 | 适用设备 | 下载 | 大小 |
|---|---|---|---:|
| 标准版 | Android 11+ / arm64，系统 WebView | [APK](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc1/dsha-0.1.5-rc1.apk) · [SHA-256](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc1/dsha-0.1.5-rc1.apk.sha256) | 176.06 MiB |
| 兼容版 | Android 6+ / arm64，内置 Gecko 备用内核 | [APK](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc1/dsha-0.1.5-rc1low.apk) · [SHA-256](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc1/dsha-0.1.5-rc1low.apk.sha256) | 252.82 MiB |

正式 Release 沿用已验收的版本号 **0.1.5-rc1**、版本码 **116** 与历史签名；包内 dsh 为 **0.1.5-rc.1**，Ubuntu 基础环境版本为 **10**。两版共享包名和数据，不能并排安装。

### 正式版带来了什么

- **新版模型与文件预览**：DeepSeek-V41-Flash，保留 V4 模型选项；Markdown、HTML、PDF、图片、代码和长文本预览、标签滚动与代码复制。关闭“×”在标签右侧垂直居中。
- **更快的启动与更新**：独立 dsh 离线包、未变化网页脚本复用；修复首次原生模块加载失败。Android 16 同机启动至鉴权 **3.43～3.66 秒**，完整冷安装验证约 **19.3 秒**。
- **数据保留与维护**：相同 Ubuntu 基础环境局部更新，个人目录、会话、配置和第三方插件保持原位；基础环境变化先保护数据再重建。移除按启动次数自动备份和启动时全目录扫描，保留手动备份、预检、回退和恢复。
- **启动错误能定位、能恢复**：实际阶段、插件错误、退出码和可下载日志；60 秒后继续等待；独立安全启动；统一 PID 身份核验，维护前关闭终端及后台任务。
- **汇总此前预览版能力**：标准／兼容双版本、插件安装／导入／导出／启停／更新／回退、后台 APK 下载与校验、无线 ADB、Shizuku 备用入口、局域网访问和 PTY；完善设备命令白名单与系统进程保护。
- **移动交互修订**：键盘和系统栏避让、回车换行、提示清理、图片草稿和阅读位置恢复，以及更清晰的配置、插件和诊断界面。

相对上一已发布预览版 **v1.2.0-rc1.4**，dsh 从 `0.1.2-rc.1` 升至 `0.1.5-rc.1`；标准 APK 约缩小 **17.3%**，兼容 APK 约缩小 **12.7%**。完整说明按 alpha.2、rc1、rc1.2、rc1.3、rc1.4 汇总差异，历史 Release 继续保留。

### 安装与验证

升级前保留重要数据的手动备份，再覆盖安装对应 APK。此前已发布 v1.2 预览版的基础环境为 9，升到本版 10 时需要保护数据并重建；相同基础环境 10 的本地 alpha 包可局部更新，alpha.2 → 本版实测约 **7.45 秒**。不要把该耗时当成所有旧版的升级耗时。

本轮 Android 16 实测新模型的图片、附件与工具调用，WebView／Gecko、文件预览、覆盖更新、冷安装、连续启动、终端回收、插件和备份恢复。两版各 **330 项单测：329 通过、1 跳过、无失败**，Lint 无错误，签名、摘要、离线包与 ELF 检查通过。Android 6/7、真实 16 KB 页内核等仍需实机补充验证，具体边界见验收记录。

反馈：[GitHub Issues](https://github.com/DSH-APP/DSHA/issues) / QQ 群 **975836806**，请附机型、系统版本、复现步骤及脱敏诊断报告。

---

以下原样保留 **1.1.10 及更早版本**的项目介绍；历史能力、构建步骤和兼容范围描述对应当时版本，当前正式版请以上方介绍与发布说明为准。

## DSHA目前正在进行大规模重构，更新较慢👀


## 这是什么

DeepSeek Harness（`@deepseek-ai/dsh`）是 DeepSeek 官方的 agent harness，类 Claude Code。
它是为 glibc Linux 写的，直接在安卓上跑会撞上一堆事：原生模块编译不过、`link(2)` 被
SELinux 挡住、沙箱起不来、前端按桌面布局排版。

**DSHA 把这些全部封在一个 APK 里。** 装 APK、填 API key(或跳过)、点启动 —— 不需要 Termux、
不需要 ROOT、不需要敲一行命令。里面是一个完整的 Ubuntu 24.04 环境：`apt` 能用、
交互式 PTY 能用、需要编译的原生模块能装，跟你在服务器上用是同一套东西。

---

## 为什么是 DSHA

|  | 说明 |
|---|---|
| 🚫 **零命令行门槛** | 内置离线 Ubuntu rootfs，APK 装完就能用。不装 Termux、不配 pkg、不敲命令 |
| 🐧 **完整 glibc 环境** | 不是裁剪版：`apt` / PTY / 原生模块 / Python / git 都在。上游插件不用改就能跑 |
| ⚡ **proroot 零 ptrace 开销** | 传统 proot 每个系统调用两次上下文切换；proroot 走 LD_PRELOAD + 二进制补丁做进程内路径翻译。真机实测关键项合计 **+58%** |
| 🔌 **ADB 免 Shizuku 直连** | 内置无线配对与保活，agent 可以直接操作这台手机（点击、截屏、装应用） |
| 💾 **卸载重装数据不丢** | 对话与设置放在 `Documents/dshdata`，文件管理器里可见可备份；API key 走 Android Keystore 加密 |
| 🩺 **坏了能自己说清哪坏了** | 23 项自检 + 一键修补 + 15 个自愈脚本；Web 起不来时直接点名是哪个插件 |

---

## 30 秒上手

1. 到 [Releases](https://github.com/qiannianhuanxiang/DSHA/releases/latest) 下最新 APK 装上（仅 arm64）
2. 首次启动解压内置环境（几分钟，只有一次）
3. 「配置」页填 DeepSeek API key →「启动」页点启动 → 自动打开 Web UI

就这样。想跑得更细可以走「分步安装」，每步都能单独重装、单独更新。

---

## 能力全景

DSHA 不只是「能跑起来」。下面每一项都是实装的功能。

<details open>
<summary><b>① 环境与装机</b></summary>

| 能力 | 说明 |
|---|---|
| 内置离线 rootfs | Ubuntu 24.04 arm64 打进 APK，无网也能完成环境部署 |
| 分步安装 | rootfs / 基础工具 / Node.js / harness 四步独立，可单独重装与更新，不重复下载 |
| 多源并行测速 | 清华、阿里云、华为云、腾讯云、南大、哈工大、npmmirror… 测完弹窗自选 |
| 双装机路径 | 预构建包与源码构建都支持，源码路径会自动处理 node-pty 等原生模块的编译问题 |
| 安装即校验 | 每步装完立刻验产物，不把「装了一半」当成功 |
| 断点续装 | 中途失败或退出后回到该步继续，不从头再来 |

</details>

<details open>
<summary><b>② 运行时与性能</b></summary>

| 能力 | 说明 |
|---|---|
| proroot / proot 双运行时 | 默认 proroot（零 ptrace 开销），「配置」页一键切回 proot |
| 实测提升 | vivo V2352A / Android 14：关键项合计 +58%，tar 打包 +94%（备份走这条），stat 密集 +82%（node 模块解析） |
| 三层兜底 | 运行时文件缺失自动降回 proot；连续 3 次启动失败强制切回并告知；装机路径始终用 proot |
| Node.js 24 + pnpm | 与上游一致的运行环境 |
| 前台服务常驻 | 通知栏可见运行状态，系统不会随手回收 |
| 看门狗 | Web 掉了自动拉起，不用手动重启 |

</details>

<details open>
<summary><b>③ 数据安全与迁移</b></summary>

| 能力 | 说明 |
|---|---|
| 数据不随卸载消失 | 会话 / 设置 / 附件放 `内部存储/Documents/dshdata`，原位留私有软链 |
| API key 加密存储 | Android Keystore（AES/CBC），密钥不出 Keystore；备份里那份也加密 |
| 全量备份 | 手动备份保留 10 份轮换，自动备份**双槽交替**（永远留着上一份完整的） |
| 分范围备份 | 备份时可选**全量 / 只对话 / 只插件**。部分备份恢复时只覆盖对应内容 —— 拿只含对话的包恢复，配置与插件保持现状 |
| 换机不丢对话 | 对话等热数据在手机上是指向公开目录的符号链接，`tar` 默认只存链接、换设备恢复就是空的；备份会额外把它们解引用快照一份 |
| 备份自带说明 | 包里放 `DSHA-README.txt`：里面有什么、怎么手动取数据、哪些东西换设备后用不了 |
| 恢复前体检 | 只读走一遍整个包，靠 gzip 的 CRC 发现截断与损坏，并预览「多少会话 / 多大 / 来自哪个版本」 |
| 恢复极宽容 | 老备份一律放行；缺失插件后台自动补装；跨设备的 `link:` 路径自动重写；本机路径插件的源码随包内联 |
| 恢复后自动适配 | 跑一遍版本迁移（下线已换掉的内置插件、补回新的）并重新对齐桥 token |
| 凭据不进备份 | 本机桥 token 排除在外 —— 备份落在公共目录，不该带走这台机器的凭据 |
| 会话损坏隔离 | 坏掉的会话文件挪到 `corrupt-backup`，随时可取回，不让一个坏文件卡住整个 Web |

</details>

<details open>
<summary><b>④ 设备能力（让 agent 真正操作这台手机）</b></summary>

| 能力 | 说明 |
|---|---|
| ADB 无线直连 | 内置配对与保活，**不需要 Shizuku**。agent 可以点击、滑动、截屏、装应用、读日志 |
| Shizuku 通道 | 作为备用路径保留，已授权的用户可继续用 |
| App 桥（127.0.0.1:3090） | agent 可以发系统通知、读设备信息、请求用户确认 |
| 危险命令守门人 | 覆盖关键路径、递归删除等命令会拦下来问你，**三条渠道**（通知 / 前台弹窗 / 悬浮条）任选其一批准 |
| 流式悬浮条 | AI 输出像歌词一样实时贴在屏幕顶部；显示正在执行的命令原文，思考过程可选；底色 / 透明度 / 行数 / 停留时间都能调，带预览 |
| 内置终端 | 直接进 Ubuntu shell，`apt install` 什么都行 |
| 免 ROOT 文件访问 | 注入 MT 管理器文件提供器，在文件管理器里直接浏览、编辑 App 私有目录 |

</details>

<details open>
<summary><b>⑤ 可靠性：坏了能自己说清哪坏了</b></summary>

| 能力 | 说明 |
|---|---|
| 23 项自检 + 一键修补 | 桥 / ADB / 插件 / 会话 / 备份 / 运行时 / 公开数据 / 守卫补丁 / Web 鉴权… 逐项体检，能自动修的当场修 |
| 插件故障人话诊断 | Web 起不来时直接说「是插件 X，它要的服务不存在，点这里修」，而不是甩一屏 Node 堆栈 |
| 15 个自愈与补丁脚本 | pnpm 空壳还原、bundle 解析修复、profile 引导修复、`.l2s` 链摊平、会话修复、依赖修复、写文件补丁… |
| 脚本增量热更新 | 关键脚本可从 GitHub 增量更新并**离线验签**（公钥内置，签名不符整批拒绝），不必等新 APK |
| 失败原因落盘 | 备份、安装、启动的失败原因写进文件，自检直接读 —— 不让「没反应」变成无从排查 |
| CI 守门人 | 每次推送跑 Fast checks：清单一致性 + 离线验签 + 300 条纯逻辑断言 + assets 脚本真编译；发布时证书指纹不符直接中止 |

</details>

<details open>
<summary><b>⑥ 网络与访问</b></summary>

| 能力 | 说明 |
|---|---|
| 内嵌 WebView | GeckoView，不受系统 WebView 版本拖累 |
| 局域网访问 | 手机开着 dsh，电脑 / 平板直接在浏览器里用。token 鉴权 fail-closed，命中后回设 `SameSite=Strict` Cookie，不让 token 随外链泄漏 |
| 一键取地址 | 启动页可复制本机地址与局域网地址（带 token），随心跳刷新 |
| 老浏览器兼容 | 自动注入 `AbortSignal.any/timeout` 与 `crypto.randomUUID` polyfill —— 后者在局域网 HTTP（非 secure context）下是必需的 |
| 端口可配 | Web 端口自定义，冲突自动回退并说明 |

</details>

<details open>
<summary><b>⑦ 插件生态</b></summary>

| 能力 | 说明 |
|---|---|
| 插件市场 | 浏览、安装、更新、启用/禁用、删除，全部在 App 内完成 |
| 内置移动端适配 | 集成 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT）：窄屏单栏 + 目录抽屉、设置改底部 sheet、状态栏安全区、表格与气泡排版 |
| 内置设备技能引导 | 让 agent 知道这台手机上有哪些能力可用 |
| 硬依赖自动改造 | 插件写死的服务依赖会被就地改成运行时注入 —— 一个插件不该把整棵插件树拖挂 |
| 内置插件保护 | 内置插件不会被误删；用户手动禁用过的，升级后依然保持禁用 |
| 导入导出 | 插件配置可导出备份、可导入还原 |

</details>

<details open>
<summary><b>⑧ 开发者 / Agent 友好</b></summary>

| 能力 | 说明 |
|---|---|
| [AGENTS.md](AGENTS.md) | 给 AI 与新贡献者的入口文档：结构、契约、踩过的坑，省掉全库扫描 |
| Agent Skills | [`agent-skills/`](agent-skills/) 提供 `device-shell`（ADB / Shizuku 桥）与 `screen-ocr-operator`（OCR + 批量操作屏幕） |
| 纯逻辑测试集 | 300 条断言，不依赖 Android API，`bash tools/pure-logic-test.sh` 秒级跑完；另有两套端到端测试：解压往返（真代码解真 tar.gz，逐个比 sha256）与停止判据（造真进程与 pid 文件跑一遍 shell 片段） |
| 活动日志 | 关键动作与失败原因留痕，用户报问题时有据可查 |
| 全 CI 构建 | 不需要电脑：推 tag 即出签名 APK，arm64 runner 现场造 rootfs |

</details>

---

## 与同类方案的关系

安卓上跑 dsh 目前有两条路，各有代价，说清楚比互相贴标签有用：

| | **容器派**（DSHA 走这条） | **Termux bootstrap 派** |
|---|---|---|
| 做法 | proot/proroot + 完整 glibc rootfs | 用 Termux 的包在 Android bionic 上裸跑 |
| 装机 | 装 APK 就完事 | 装 Termux → 敲命令 → 装工具链 |
| 环境 | 完整 Ubuntu，`apt` 与原生模块随便用 | 需要为 bionic 逐个打补丁 / 重编 |
| 开销 | proroot 已无 ptrace 开销 | 无容器层，理论最快 |
| 沙箱 | 两边都受限 | 两边都受限 |

---

## 已知限制

诚实列出来，省得你装完才发现：

| 项目 | 状态 | 说明 |
|---|---|---|
| 架构 | ⚠️ 仅 arm64-v8a | 32 位与 x86 设备不支持 |
| 系统 | ✅ Android 8.0+ | 更老的版本没测过 |
| 包体 | ⚠️ 约 370 MB | 内置完整 Ubuntu 环境的代价，换来的是免下载、免命令行 |
| bash 工具 | ✅ 可用 | 完整 Ubuntu 的 bash，agent 跑 shell 命令没有限制 |
| bash 的**沙箱隔离** | ⚠️ 不可用 | bubblewrap 要 unprivileged user namespace，Android sepolicy 不给 —— 容器派和 Termux 派都一样绕不过。所以没有内核级边界，约束靠 dsh 的权限档位：默认 `danger-full-access`，可在配置页改成 `workspace-write` 或 `read-only`。请自行判断风险 |
| 卓易通 / 鸿蒙 anco | ❓ 未验证 | 理论可行，尚无真机回归 |
| 悬浮条 | ⚠️ 需要授权 | 用 `TYPE_APPLICATION_OVERLAY` 自绘；免 ROOT 拿不到真正的「状态栏歌词」接口 |
| 数据位置 | ⚠️ 需要文件权限 | 「所有文件访问」被拒时数据留在私有目录，卸载即丢（自检会明确告知当前状态） |

👉 **每项权限到底暴露了什么、agent 碰得到你手机的哪些部分**，见 [安全模型](docs/security-model.md) —— 包括我们自己列出的已知弱点。

---

## 架构

```
┌──────────────────────── APK ────────────────────────┐
│ 原生 Android（Java 17）· Material3 · GeckoView       │
│  ├ 启动 / 安装 / 配置 / 工作区 / 插件 / 终端 / 设置  │
│  ├ 前台服务 + 看门狗 + 通知                          │
│  ├ App 桥 :3090   局域网桥 :3081                     │
│  └ 悬浮条（TYPE_APPLICATION_OVERLAY）                │
├─────────────────────────────────────────────────────┤
│ proroot（默认，零 ptrace 开销）/ proot（兜底）        │
├─────────────────────────────────────────────────────┤
│ Ubuntu 24.04 arm64 · Node.js 24 · pnpm              │
│  └ @deepseek-ai/dsh  →  Web UI :3080                │
└─────────────────────────────────────────────────────┘
```

数据：会话 / 设置 / 附件在 `Documents/dshdata`（公开可见可备份）；
`DSH_HOME` 本体与 `.credentials.yaml` 刻意留在私有目录。

---

## ADB 无线配对（设备 Shell 能力）

配好之后 agent 就能直接操作这台手机，**不需要 Shizuku**。

**首次配对（约 1 分钟）**

1. 系统设置 →「关于手机」→ 连点「版本号」7 次开启开发者选项
2. 开发者选项 → 打开「无线调试」
3. 进入「无线调试」→「使用配对码配对设备」，记下 **IP:端口** 与 **6 位配对码**
4. 回到 DSHA →「工作区」页 → ADB 区域 → 填入 → 配对

**配对之后**：DSHA 自己维护连接（保活 + 重连），重启手机后也会自动恢复，不用再操作。

**验证**：内置终端里跑 `adb shell id`，输出 `uid=2000(shell)` 即成功。

**让 agent 用起来**：把技能包复制到 agent 的技能目录：

```bash
cp -r agent-skills/device-shell ~/.agents/skills/
cp -r agent-skills/screen-ocr-operator ~/.agents/skills/
```

---

## 构建

**云端（推荐，不需要电脑）**

```bash
git tag v1.2.3 && git push origin v1.2.3   # 触发 release 流水线，自动出签名 APK
```

流水线分两段：`ubuntu-24.04-arm` 原生 arm64 现场造 rootfs（带 cache），
`ubuntu-latest` 把离线包打进 APK 并核对证书指纹。
推 `main` 也会跑一次 debug 构建与 Fast checks。

**本地**（需要 Gradle 8.5 + Android SDK + JDK 17）

```bash
./build.sh                      # 需要先有 app/src/main/assets/offline-rootfs.tar.gz
bash tools/pure-logic-test.sh   # 300 条纯逻辑断言，不需要设备
```

---

## 致谢

- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) —— 本体
- [proot](https://github.com/termux/proot) / [proroot](https://github.com/coderredlab/proroot) —— 免 ROOT 容器（见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）
- [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) —— 内置移动端适配
- [Shizuku](https://shizuku.rikka.app/) —— 备用设备命令通道

## 交流

QQ 群 **975836806** —— 测试版、问题反馈、插件交流。

⚠️一群当前已满请进二群**975836806**

## 许可

[MIT](LICENSE)。第三方组件许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## Star History

<a href="https://github.com/qiannianhuanxiang/DSHA/stargazers">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/star-history-dark.svg" />
    <source media="(prefers-color-scheme: light)" srcset="docs/star-history.svg" />
    <img alt="DSHA Star History" src="docs/star-history.svg" width="820" />
  </picture>
</a>

<sub>曲线由 [`tools/gen-star-history.py`](tools/gen-star-history.py) 每周自动生成（[workflow](.github/workflows/star-history.yml)）。
GitHub 从 2026-06-30 起把 stargazers API 限制成只有仓库 admin/collaborator 能读，
第三方实时嵌入图因此普遍失效，所以数据自己拉、图自己画。</sub>

