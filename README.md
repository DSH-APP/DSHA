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
  <a href="https://afdian.com/a/dsha_apk"><img src="https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E8%B5%9E%E5%8A%A9-946CE6?logo=afdian&logoColor=white" alt="爱发电"></a>
  <a href="https://qm.qq.com/q/N5aZSlnmgM"><img src="https://img.shields.io/badge/QQ%E7%BE%A4-975836806-1EBAFC?logo=qq&logoColor=white" alt="QQ群"></a>
</p>

<p align="center">
  <a href="README.en.md">English</a> · <b>简体中文</b> · <a href="CHANGELOG.md">更新记录</a> · <a href="docs/security-model.md">安全模型</a> · <a href="AGENTS.md">AGENTS.md（给 AI / 开发者）</a>
</p>

> 🤖 下一个 AI / 开发者请先读 **[AGENTS.md](AGENTS.md)**（项目结构、启动契约、踩过的坑），不要先全库扫描。

---

## DSHA v0.1.7-alpha2 · GitHub 预发布

由贡献者 [@ym2025szz](https://github.com/ym2025szz) 发布。这是实验性预发布：DSH 最近升级到 **0.1.7-alpha.2** 并改动了 Session、Agent 预设、插件管理和部分 Web API，已有用户请先导出重要数据并保存备份密码，再谨慎覆盖更新。旧版自建目录型预设可能需要迁移，详见[预发布说明](docs/releases/v0.1.7-alpha2-pre-release.md)和[上游迁移说明](https://github.com/deepseek-ai/deepseek-harness/discussions/7545)。

本版新增 **Standard 的 Android 11+ 实验性虚拟屏**：支持 ADB/Shizuku/Root、应用选择与启动、应用内/可选悬浮预览、触控、键盘输入、AI 操作和无障碍控件树。虚拟屏仍可能受厂商 ROM 和授权状态影响；**Low 版暂不支持虚拟屏**。同时修复 PiP 流式文字闪烁、整理数据与备份入口、优化 Language 弹窗首帧和插件面板标题避让，并同步内置移动端插件。

- [预发布下载与完整对比](docs/releases/v0.1.7-alpha2-pre-release.md)
- [GitHub Pre-release](https://github.com/DSH-APP/DSHA/releases/tag/v0.1.7-alpha2)

## DSHA v0.1.6-alpha2.1 · 本地交付构建（build 142）

alpha2.1 在 build 141 基础上修复 PiP 中流式文字闪烁，统一数据与备份入口卡片，减少 Language 弹窗首帧布局工作，补齐插件列表标题避让，并加入 Android 11+ 独立虚拟屏：支持 ADB/Shizuku/Root、应用内预览、可选悬浮预览和带 frameSeq 校验的 AI 操作。DSH、Ubuntu、数据迁移和签名规则保持不变。

内置 DSH 仍为 **0.1.6-alpha.2**，Ubuntu 基础环境仍为 **10**，受管运行时身份为 `bb7e40a18e2c38ca3c2aae7fd04a926acb3a7fb8bc0370e80e9a773ba6da0bf4`。DeepSeek Agent Team/压缩/普通工具/错误结果/图片结果、插件、备份、格式化、运行时轮换及维护进程退出回归已经通过；Standard/Low 各 618 项强制全量单测、Release Lint、E7E3 正式签名 APK/ELF 审计，以及 M367FC/API 37 平板和 Xiaomi 2410DPN6CC/API 36 手机的非破坏性覆盖均通过。平板滚动实测 120 Hz、733 帧 0 dropped / 0 jank、平均 114.1 FPS；`release` 中同名 APK 已替换。该结论不扩写到 Android 6/7、全部外部模型服务或所有厂商设备矩阵。

- [alpha2.1 / build 142 修复与验收记录](docs/releases/v0.1.6-alpha2.1-build142.md)
- [build 141 修复与验收记录](docs/releases/v0.1.6-alpha2-build141.md)
- [build 140 proroot 存储兼容、插件修复与真机验收](docs/releases/v0.1.6-alpha2-build140.md)
- [build 137 插件缓存与图标修复](docs/releases/v0.1.6-alpha2-build137.md)
- [build 136 功能与真机验收记录](docs/releases/v0.1.6-alpha2-build136.md)
- [本轮真机界面](ui-preview/native-ui-build135-review.html)
- [build 134 历史记录](docs/releases/v0.1.6-alpha1-build134.md)

网站两个社区插件条目与部署文件已在本地准备；公开网站尚未更新，GitHub Release 未发布。自动备份为本机副本，卸载前应导出并保存解密密码。

## DSHA v0.1.5-rc2.1 · 本地验收交付

版本码 **131**，标准版与兼容版位于工作区 `release`，沿用原发布签名。同版本 APK 验收后使用常规文件名替换，不再并列保存 `-buildNNN` 文件；以后直接用正式版覆盖安装检查。补齐网络桥有界解析与流式代理、插件冻结依赖及审阅/失败恢复、凭据错误状态、保留副本与旧树只读救援管理。#65 / #67、宿主加密备份和受管更新保护继续保留；未重新启用自动备份或新增自动删除。详见[更新与交付](docs/releases/v0.1.5-rc2.1-build131.md)和[逐项证据、设备范围与缺口](docs/stability-acceptance.md)。本轮未上传 GitHub，下方仍为线上正式版说明。

## DSHA v0.1.5-rc2 正式版 · Latest

由贡献者 [@ym2025szz](https://github.com/ym2025szz) 维护并发布，感谢原作者 [@qiannianhuanxiang](https://github.com/qiannianhuanxiang) 和其他贡献者。

**[最新正式版与下载](https://github.com/DSH-APP/DSHA/releases/latest)** · [完整更新与历史版本对比](docs/releases/v0.1.5-rc2-notes.md) · [最终验收记录](docs/releases/v0.1.5-rc2-build129.md)

| 版本 | 适用设备 | 下载 | 大小 |
|---|---|---|---:|
| 标准版 | Android 11+ / arm64，系统 WebView | [APK](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc2/dsha-0.1.5-rc2.apk) · [SHA-256](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc2/dsha-0.1.5-rc2.apk.sha256) | 176.69 MiB |
| 兼容版 | Android 6+ / arm64，内置 Gecko 备用内核 | [APK](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc2/dsha-0.1.5-rc2low.apk) · [SHA-256](https://github.com/DSH-APP/DSHA/releases/download/v0.1.5-rc2/dsha-0.1.5-rc2low.apk.sha256) | 253.23 MiB |

本次以 **GitHub 正式 Release / Latest** 发布，沿用已验收 APK 的 **0.1.5-rc2** 名称、版本码 **129** 和原发布签名。包内 dsh 为 **0.1.5-rc.2**，Ubuntu 基础环境仍为 **10**。两版共享包名和数据，不能并排安装。

### 本版更新

- **界面统一**：卡片、按钮、弹窗、设备权限和日夜主题统一，适配短屏与大字体；完善切页动画。安装检查日志和诊断报告在限定区域内独立滚动，关于弹窗重新安排仓库、QQ群和关闭入口。
- **独立多终端**：PTY 与简易终端分别管理标签，切页、旋转、语言切换保留进程、输出与草稿；显示编号复用空缺，全部关闭后再新建从“终端 1”开始。
- **中英文分离**：原生界面、应用状态和两种网页内核随语言设置切换；切换语言不停止 Web 或终端。用户内容、命令、文件名和第三方原始异常保持原文。
- **插件与预设**：名称升降序、已启用优先、可更新优先排序实际可用，操作按钮有统一边框；内置 dsh-web-mobile 更新至 2.4.1-dsha.2。四种 Agent 预设由服务端确认切换，平板顶栏靠右并提供可用的文件夹入口。
- **网页与文件兼容**：修复多文件选择丢失（#64）、旧 WebView 的 Iterator / PDF Worker 加载错误，提前补齐缺失的 AbortSignal 等接口。
- **启动与恢复**：端口冲突自动选择可用端口，慢鉴权继续等待；保留实际错误记录和独立恢复界面。修复旧平板环境升级时的路径与权限误判，保护并校验个人数据。
- **设备与网络**：集中管理设备能力授权，支持按协议识别 Shizuku 改版管理器并主动恢复连接；针对部分网络的 DNS 故障提供自动、IPv4 和原生模式（#63），不重放 HTTP 请求。

相对上一正式版，升级 dsh 至 rc.2，新增本轮界面、多终端、语言和兼容修复，并汇总至构建 129。此前重构版的离线 Ubuntu、模型选择、文件预览、备份恢复、插件和设备通道继续保留；逐版差异见完整更新说明。

### 升级与验证

使用同签名 APK 覆盖安装，重要数据建议保留手动备份。基础环境为 10 时只更新受管运行时；旧基础环境 9 先保护数据再重建，已在 Android 17 平板完成实际升级。1.1.10 及更早架构的全部直接升级路径未在本轮重新验证。

两版各 **383 项单测：382 通过、1 跳过、无失败**，Lint 无错误；真机覆盖 WebView 116、Gecko、多文件回调、Shizuku 断连恢复、PDF Worker 和手机/平板布局。Android 6/7、真实 16 KiB 页设备与 thedjchi 改版本体未在最终轮次实测，改版兼容依据其公开协议实现。

[问题反馈](https://github.com/DSH-APP/DSHA/issues) · QQ 群 **975836806**。请附机型、系统版本、复现步骤和脱敏诊断报告。

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

<a href="https://github.com/DSH-APP/DSHA/stargazers">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/star-history-dark.svg" />
    <source media="(prefers-color-scheme: light)" srcset="docs/star-history.svg" />
    <img alt="DSHA Star History" src="docs/star-history.svg" width="820" />
  </picture>
</a>

<sub>由 [`tools/gen-star-history.py`](tools/gen-star-history.py) 按周读取 GitHub 星标时间并更新（[workflow](.github/workflows/star-history.yml)）。浅色、深色 SVG 保存在本仓库；曲线按当前仍保留的星标汇总。</sub>
