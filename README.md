# DSHA

<p align="center">
  <b>DeepSeek Harness 安卓启动器</b><br>
  在手机上跑完整的 <a href="https://github.com/deepseek-ai/deepseek-harness">deepseek-harness</a> —— 免 ROOT，免 Termux，装完即用
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT"></a>
  <a href="https://github.com/DSH-APP/DSHA/releases/latest"><img src="https://img.shields.io/github/v/release/DSH-APP/DSHA?sort=date&color=blue" alt="release"></a>
  <a href="https://github.com/DSH-APP/DSHA/stargazers"><img src="https://img.shields.io/github/stars/DSH-APP/DSHA?style=flat" alt="stars"></a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white" alt="android">
  <img src="https://img.shields.io/badge/arch-arm64--v8a-lightgrey" alt="arch">
</p>

<p align="center">
  <a href="README.en.md">English</a> · <b>简体中文</b> · <a href="CHANGELOG.md">更新记录</a> · <a href="docs/security-model.md">安全模型</a> · <a href="AGENTS.md">AGENTS.md（给 AI / 开发者）</a>
</p>

> 🤖 下一个 AI / 开发者请先读 **[AGENTS.md](AGENTS.md)**（项目结构、启动契约、踩过的坑），不要先全库扫描。

> 正式版 **[v0.2.2 · build357](https://github.com/DSH-APP/DSHA/releases/tag/v0.2.2)**（`0.2.2-20261111.0200-dsh0.2.2-rc.1`）：内容与 build356 一致，按官方格式交付 APK 与完整源码包。完整改动见 [CHANGELOG.md](CHANGELOG.md)。

## 当前状态

| 项目 | 值 |
|---|---|
| 应用名 | **DSHA**（测试变体为 `DSHA Test`） |
| 版本 | `0.2.2-20261111.0200-dsh0.2.2-rc.1` |
| versionCode | 357 |
| 包名 | `dsh.android.client`（测试变体 `dsh.android.client.SunsetRNE`） |
| 签名 | 历史发布证书，SHA-256 `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5` |
| 系统 | Android 11+（API 30）/ arm64-v8a；**Android 10 及以下不再支持** |
| 内置环境 | Ubuntu 24.04 arm64 · Node.js 24 · pnpm 10.34.5 · DSH 0.2.2-rc.1 · 移动 UI 3.3.5 |
| 运行时 | proot 默认，proroot 可选（依机型） |
| 界面语言 | 10 种：zh / zh-Hant / en / ru / ja / ko / ar / pt / la / fr |
| 主线分支 | `main` |
| 许可 | MIT（沿用原版） |

## 这是什么

DeepSeek Harness（`@deepseek-ai/dsh`）是 DeepSeek 官方的 agent harness，为 glibc Linux 编写。直接跑在安卓上会撞上一堆事：原生模块编译不过、`link(2)` 被 SELinux 挡住、沙箱起不来、前端按桌面布局排版。

**DSHA 把这些全部封在一个 APK 里。** 首次启动解压并校验内置的离线环境，完成后进入主页、Web 在后台启动；打开 Web UI 再配置提供商、模型与 API Key。不需要 Termux、ROOT，也不需要敲一行命令。里面是完整的 Ubuntu 环境：`apt` 能用、交互式 PTY 能用、需要编译的原生模块能装，跟你在服务器上用是同一套东西。

本仓库是 DSHA 的主仓库（官方发行线）：源自 [@qiannianhuanxiang](https://github.com/qiannianhuanxiang) 的原版 DSHA，并合入了社区贡献的终端、设备能力与移动端界面改造；发布身份为官方 `dsh.android.client` 与历史 E7E3 发布证书，旧 Mod 包 `io.github.sunsetrne.dsha` 保留为并存的历史身份。感谢原作者与所有贡献者。

## 身份与共存

| 身份 | 包名 | 说明 |
|---|---|---|
| 正式（默认） | `dsh.android.client` | 当前 Release；E7E3 证书，可覆盖升级历史正式包 |
| 测试变体 | `dsh.android.client.SunsetRNE` | 与正式包并存，数据与授权独立 |
| 旧 Mod | `io.github.sunsetrne.dsha` | 历史 Mod 身份（A4B0 证书），仅用于历史包与共存验证 |
| 更早 | `com.dsh.client` | 更早包名，旧数据仍可经导出/恢复迁移 |

同一台手机上多个身份可以并存，但**数据、系统授权、无障碍开关互不继承**；迁移走应用内「数据与备份」导出 → 新包导入。同时运行两套 Web 时仍可能遇到端口冲突。

## 下载与安装

当前正式版 **[v0.2.2 · build357](https://github.com/DSH-APP/DSHA/releases/tag/v0.2.2)**，Standard 标准版，Android 11+ / arm64，APK 约 **302 MiB**（内置完整 Ubuntu 环境）。

[下载 APK](https://github.com/DSH-APP/DSHA/releases/latest) · SHA-256 与完整源码包见该 Release 的资产列表 · [下载校验](docs/download-verification.md)

### 开始使用

1. 安装对应签名的 APK，首次启动解压并校验内置离线环境（耗时取决于设备 I/O）。
2. 环境就绪后进入主页，Web 在后台启动；主页显示进度与结果。
3. Web 就绪后打开 Web UI，在网页设置中配置提供商、模型与 API Key。

### 迁移

- **从正式版升级**：同包名、同签名直接覆盖；升级前先正常停止 DSH，升级后数据保留。
- **从旧 Mod 包迁移**：先在旧包「数据与备份」导出，再在正式包导入；设备授权与无障碍开关需重新开启。
- **从更早的 `com.dsh.client` 迁移**：同样走导出/导入，不从另一包名自动继承授权。

## 为什么是 DSHA

|  | 说明 |
|---|---|
| 🚫 **零命令行门槛** | 内置离线 Ubuntu rootfs，APK 装完就能用。不装 Termux、不配 pkg、不敲命令 |
| 🐧 **完整 glibc 环境** | 不是裁剪版：`apt` / PTY / 原生模块 / Python / git 都在。上游插件不用改就能跑 |
| ⚙️ **双运行时选择** | 默认 proot、可选 proroot；实际性能取决于设备、I/O、缓存和插件，不以历史样本给引擎排名 |
| 🔌 **手机就是 agent 的手** | 无障碍 + ADB 免 Shizuku 直连 + 独立虚拟屏：读屏、点按、装应用、跑自动化，全部内置 |
| 💾 **导出后迁移数据** | 「数据与备份」导出无需密码的 `.tar.gz`；数据位置可在存储指南查看，本机副本不保证卸载后存续 |
| 🩺 **坏了能自己说清哪坏了** | 组件级自检 + 按需修补 + 启动诊断 + 独立应急 DSH；Web 起不来时直接点名是哪个插件 |

## 核心模块

### ① 装机与环境

| 能力 | 说明 |
|---|---|
| 内置离线 rootfs | Ubuntu 24.04（noble）arm64 打进 APK，无网也能完成环境部署 |
| 六步安装流水线 | 解压 → 基础工具 → Node.js → pnpm → dsh → 补丁，每步先探测再执行、装完即校验；只修失败项，不从头重来 |
| 分步重装 / 更新 | rootfs、工具、Node、harness 相互独立，可单独重装，不重复下载 |
| 双装机路径 | 预构建包与源码构建都支持，源码路径自动处理 node-pty 等原生模块编译 |
| 离线工具链 | curl / git / Python / 证书随包锁定版本（`packages.lock.json`），不依赖用户先跑 `apt` |
| 镜像与网络 | apt 源官方 / 中科大镜像自动回退；DNS 三模式（auto / 强制 IPv4 / 系统原生），AAAA 被拒时仅重试解析、不重放 HTTP 请求 |
| 基础环境版本化 | Ubuntu 基础环境独立编号；界面更新不重建环境，普通 dsh 更新不递增基础版本 |
| 固定构建输入 | 离线 rootfs、DSH runtime、Ubuntu tools 作为公开 Release 资产分发，`ci/sunset-inputs.lock.json` 锁定 SHA-256，clone 后可还原完整构建 |

### ② 运行时

| 能力 | 说明 |
|---|---|
| proroot / proot 双运行时 | 默认稳定的 proot；可在「配置」选择 proroot，实际兼容结果依机型 |
| 有限兼容回退 | proroot 在鉴权前明确退出且无插件故障时，确认 guest 退出后仅重试一次 proot；慢启动不触发回退 |
| Node.js 24 + pnpm 10.34.5 | 与上游一致的运行环境，随包锁版本 |
| 分包更新 | Ubuntu 与 dsh 运行时在 APK 内分包；冷安装两份都解压，局部更新只读 dsh 包 |
| 受管更新试运行 | 新环境先在隔离区真实启动、验证端口 / 鉴权 / 会话读写 / 插件加载，全过后才切换提交，失败自动回切 |
| 前台服务 + 看门狗 | 通知栏可见运行状态；Web 掉了自动拉起，不用手动重启 |
| 慢启动保护 | 等待鉴权不设强杀时限（60 秒仅提示），看门狗不会把慢启动当故障误杀 |

### ③ 数据与备份

| 能力 | 说明 |
|---|---|
| 数据位置与迁移 | 「数据与备份 → 存储指南」查看实际位置；升级保留当前数据，卸载前请导出需要保留的内容 |
| v5 备份与导出 | 用户导出为**无需密码**的 `.tar.gz` 完整性归档；内部自动副本仍加密，历史加密原件读取仍需原密码 |
| 自动备份计划 | 默认开启：每天指定时间 / 每隔 1–168 小时 / 停止 DSH 后，三种模式；运行中自动延后不打断工作；保留最近 3 份已验证副本 |
| 分范围备份 | 全量 / 只对话 / 只插件 / 只设置任选，恢复时只覆盖对应内容 |
| 换机恢复 | 导出实际对话、附件、设置及用户插件，先校验摘要和范围再预检恢复，保留当前内容与冲突原件 |
| 凭据不进备份 | 桥 token 整文件排除；`.credentials.yaml` 字段级剔除本机密钥、保留用户 API key |
| 兼容旧备份 | 先完整认证/摘要核验再预检；未知或依赖不完整时如实提示。系统插件由当前 APK 重建，用户原件继续保留 |
| 会话损坏隔离 | 坏掉的会话文件挪到 `corrupt-backup` 可取回，不让一个坏文件卡住整个 Web |
| 覆盖更新自动清理 | 升级后闲时清理可再生缓存与多余旧副本；保留至少两份健康前代，改过的原件不动 |
| 外置工作区保护 | 完整格式化已移除；维护只断开外置引用，保留其文件和备份 |

### ④ 设备能力（让 agent 真正操作这台手机）

agent 通过本机 `127.0.0.1:3090` 桥调用以下能力（token 门控，`/app/help` 有完整端点清单）：

| 能力 | 说明 |
|---|---|
| 屏幕操作 | **无障碍服务实现，不需要 ADB**：读屏结构化输出（带可点击区域坐标）、按文字 / 坐标点按、输入、按键、滑动、截屏存 PNG |
| 独立虚拟屏 | Standard / Android 11+，先授权 ADB、Shizuku 或 Root 任一通道，再到虚拟屏页面创建屏幕；仅开启无障碍不会创建虚拟屏，详见[架构说明](docs/virtual-screen-architecture.md) |
| 实体屏特权执行（实验） | Phase A：复用受管 `app_process` 入口操作实体主屏，默认关闭、可撤销、仅当前手机任务期间有效；需在「设备能力授权 → 特权执行」原生开启，详见[核心说明](docs/privileged-screen-core.md) |
| ADB 无线直连 | 内置无线配对（TLS 1.3-PSK + SPAKE2）与保活，**不需要 Shizuku**，重启手机自动恢复 |
| Shizuku / Root 通道 | 备用设备命令通道，与 ADB 同受命令白名单约束 |
| Root 命令三态拦截 | 严格（默认白名单）、放宽（拦截不可逆操作）、完全放宽；仅作用于 Root 通道，Shizuku / ADB 仍按严格档 |
| 跨应用文件（SAF） | 用户经系统文档选择器逐个目录授权，助手按授权读写；写 / 删需二次确认，只给写权限的树直接拒绝 |
| 与用户交互 | 系统通知、App 内提示、震动（长任务叫醒用户）、弹窗三选项征询、分享 / 打开链接 |
| 文件交换 | 读目录 / 文本文件、导出产物到 `Download/DSHA`；凭据区（`.dsh` / `.ssh` / `.android`）读写均被拒绝 |
| 传感器与位置 | 光线 / 加速度 / 陀螺仪 / 磁力计 / 气压 / 步数 / 定位 / 手电筒 —— 默认关闭，逐项授权 |
| 剪贴板 | 读写剪贴板（受系统前台限制） |
| 危险命令守门人 | 设备命令始终走白名单解析：格式化 / 分区 / SELinux / 系统属性等直接拒绝；不认识的命令不放行；拦截返回 `[POLICY_BLOCKED]` 而不是弹「仍然允许」 |
| 短信读取 | 独立敏感能力，仅限严格只读查询，默认每条确认，授权可撤销、不随备份迁移 |

### ⑤ 终端

| 能力 | 说明 |
|---|---|
| 真 PTY 终端 | Termux terminal-emulator JNI 实现：vim / htop / tmux 等全屏 TUI 可用 |
| 手机键盘补偿 | 扩展键行（Ctrl / Alt / Esc / 方向键 / Tab），双指缩放调字号 |
| 多标签 | 多会话并存，跨切页 / 旋转 / 语言切换保持进程与状态 |
| 会话侧栏 | 三向态与横屏压缩布局，切换会话不丢输出 |
| 输出面板 | 「更多…」面板、预览 / 复制 / 分享 / 保存，长输出不刷屏 |
| 简易终端兜底 | PTY 在个别机型异常时可切回简易终端，会话保留在后台 |

### ⑥ Web UI 与访问

| 能力 | 说明 |
|---|---|
| 唯一 Standard 线 | 系统 WebView 内核；`low` 兼容线与 GeckoView 已整体删除 |
| 移动端适配 | 内置 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) **3.3.5**（MIT），保留手机快捷键和窄屏适配 |
| 画中画 | 默认关闭，用户主动开启后使用 PiP 小窗；正常返回与后台恢复保留同代网页实例 |
| 高刷与省电 | 按用户上限请求刷新率，空闲交还系统；实际帧率依设备和系统，不把请求成功当作性能证明 |
| 深浅色与多语言 | 深色 / 浅色 / 跟随系统；10 种界面语言，默认跟随系统语言 |
| 局域网访问 | 电脑 / 平板浏览器直接用手机上的 dsh。token 鉴权 fail-closed，命中后回设 `SameSite=Strict` Cookie，不让 token 随外链泄漏 |
| 老浏览器兼容 | 自动注入 `AbortSignal.any/timeout` 与 `crypto.randomUUID` polyfill（局域网 HTTP 下必需） |
| 流式悬浮条 | AI 输出像歌词一样贴在屏幕顶部，工具调用翻译成人话；底色 / 透明度 / 行数可调；危险命令可直接在悬浮条上批准 |
| 上岛 | 任务状态上岛（实时活动 / 灵动岛）与通知内多选、自定义回答直接内置 |
| 端口可配 | Web 端口自定义，冲突自动回退并说明 |

### ⑦ 插件生态

| 能力 | 说明 |
|---|---|
| 插件管理 | 安装 / 更新 / 启停 / 删除 / 搜索 / 排序，全在 App 内；配合官网 [dsha.cc](https://dsha.cc) 插件目录与社区插件 |
| 多样安装方式 | GitHub 简写 / tree / blob / archive / Release 链接、HTTPS 压缩包直链、本地导入（按内容识别格式）、终端 `dsha-plugin install` |
| 多下载源 | 自动择源，支持 npm 官方源与 npmmirror，更新检查多路并发、锁定版本复用缓存、失败有限回退 |
| 依赖与事务 | 随包 pnpm 10.34.5，允许未锁定依赖解析、生命周期脚本和 pnpmfile；记录实际锁与目录摘要，拒绝归档越界并保留回切原件 |
| 安装后自动启用 | 包格式、路径、依赖与摘要检查后自动提交并启用；用户主动禁用和安全模式意图保持，详见[插件说明](docs/plugins.md) |
| 硬依赖自动改造 | 插件写死的服务依赖就地改成运行时注入 —— 一个插件不拖挂整棵插件树 |
| 内置插件保护 | 内置插件不可删除；用户禁用过的，升级后保持禁用；配置可导入导出 |

主要内置插件包括：`dsh-web-mobile`（移动端适配）、`dsh-status-overlay`（悬浮条）、
`dsh-task-notifier`（回合完成通知）、`dsh-device-shell-guide`（设备能力引导）、
`dsh-computer-use-android`（Android Computer Use）、`dsh-tool-vscreen`（虚拟屏工具）、
`dsh-launch-target`（启动目标选择）、`dsh-fs-read-hint` / `dsh-fs-search-scope`（读取提示与搜索范围）、
`dsh-auto-review`（官方实验性 Auto review 入口）。

### ⑧ 可靠性与应急

| 能力 | 说明 |
|---|---|
| 安装与修复页 | 检查全部六项组件并按需修复（证书 / npm 异常、DNS、会话写入补丁等），只处理失败项 |
| 诊断报告 | 环境检查 + 失败日志可导出；启动诊断保存阶段、实际插件错误与退出码，最近五次脱敏留存 |
| 启动恢复 | 明确的启动 / 插件故障自动进入恢复页；安全启动用独立 profile 只加载官方基础组件，原配置与插件开关保留 |
| 配置快照 | 配置变更前自动留快照（三份健康 + 三份修复前），坏了能回滚 |
| 插件故障人话诊断 | Web 起不来时直接说「是插件 X、它要的服务不存在、点这里修」，而不是甩一屏 Node 堆栈 |
| 独立应急 DSH | 正式环境损坏时进入独立应急环境：独立运行根、只读诊断先行，写入修复需原生逐步确认 |
| 脚本增量热更新 | 关键脚本可从 GitHub 增量更新并**离线验签**（公钥内置，签名不符整批拒绝），不必等新 APK |

### ⑨ 安全（默认最小权限）

| 层面 | 做法 |
|---|---|
| 什么权限都不给也能用 | 最小配置（不给 ADB、不给文件权限、不开局域网）下 DSHA 照常跑 dsh；每项能力默认关闭、随时撤销 |
| API key | Android Keystore + AES/GCM，密钥不出 Keystore；失败不清空旧密文 |
| 设备命令 | 白名单始终开启，Root / Shizuku / ADB 三通道同一套策略；Root 另有 strict / relaxed / full 三档，短信闸门三档都保留 |
| 桥凭据护栏 | `/app/export`、`/app/readfile` 拒绝凭据区路径，有规范化与 canonical path 检查；已知 Root 递归 READ 子链接缺口见安全文档，不视为完整隔离 |
| 无遥测 | 不上传任何日志或使用数据；更新、插件下载、模型调用之外不发起网络请求 |
| 诚实披露 | 已知弱点（无内核级沙箱、历史明文备份、签名密钥待轮换等）在文档里直接列出 |

👉 每项权限到底暴露了什么、agent 碰得到手机的哪些部分，见 **[安全模型](docs/security-model.md)**。

## 与同类方案的关系

安卓上跑 dsh 目前有两条路，各有代价，说清楚比互相贴标签有用：

| | **容器派**（DSHA 走这条） | **Termux bootstrap 派** |
|---|---|---|
| 做法 | proot/proroot + 完整 glibc rootfs | 用 Termux 的包在 Android bionic 上裸跑 |
| 装机 | 装 APK 就完事 | 装 Termux → 敲命令 → 装工具链 |
| 环境 | 完整 Ubuntu，`apt` 与原生模块随便用 | 需要为 bionic 逐个打补丁 / 重编 |
| 开销 | 存在系统调用与文件 I/O 转译开销，实际耗时依设备与场景 | 无容器层，理论最快 |
| 沙箱 | 两边都受限 | 两边都受限 |

## 已知限制

| 项目 | 状态 | 说明 |
|---|---|---|
| 架构 | ⚠️ 仅 arm64-v8a | 32 位与 x86 设备不支持 |
| 系统 | ⚠️ Android 11+（API 30） | Android 10 及以下不再支持；不同 ROM 的权限、后台与省电策略差异较大 |
| 包体 | ⚠️ 本版约 302 MiB | 内置完整 Ubuntu 环境的代价，换来的是免下载、免命令行 |
| bash 工具 | ✅ 可用 | 完整 Ubuntu 的 bash，agent 跑 shell 命令没有限制 |
| bash 的**沙箱隔离** | ⚠️ 不可用 | bubblewrap 要 unprivileged user namespace，Android sepolicy 不给 —— 容器派和 Termux 派都一样绕不过。约束靠 dsh 的权限档位：默认 `danger-full-access`，可在配置页改成 `workspace-write` 或 `read-only`。请自行判断风险 |
| 悬浮条 | ⚠️ 需要授权 | 用 `TYPE_APPLICATION_OVERLAY` 自绘；免 ROOT 拿不到真正的「状态栏歌词」接口 |
| 数据位置 | ⚠️ 需要文件权限 | 「所有文件访问」被拒时数据留在私有目录，卸载即丢（自检会明确告知当前状态） |
| 虚拟屏 / 特权执行 | ⚠️ 需要授权 | 依赖具体系统版本与原生授权状态，按实际设备取证声明，不承诺所有机型可用 |
| 电脑端连接 | ⚠️ 已撤下 | 远程工作区能力不再提供；手机控制与本机 / 局域网能力保留 |
| 部分 vendor 原生依赖 | ⚠️ 不保证 | 部分 vendor 原生依赖不保证完全从源码复现；首次解压离线环境耗时较长 |
| 卓易通 / 鸿蒙 anco | ❓ 未验证 | 理论可行，尚无真机回归 |

## 架构

```
┌──────────────────────── APK ────────────────────────┐
│ 原生 Android（Java 17 · Material3 · 单模块 :app）    │
│  ├ 启动 / 安装修复 / 配置 / 工作区 / 插件 / 终端     │
│  ├ 网页内核：系统 WebView（唯一 Standard 线）        │
│  ├ 前台服务 + 看门狗 + 通知 + 画中画                 │
│  ├ App 桥 :3090（无障碍 / 虚拟屏 / 交互 / SAF / 文件）│
│  ├ LAN 代理 :3081 · ADB / Shizuku / Root 设备通道    │
│  └ 悬浮条（TYPE_APPLICATION_OVERLAY）                │
├─────────────────────────────────────────────────────┤
│ proot（默认）/ proroot（可选，依机型核验）           │
├─────────────────────────────────────────────────────┤
│ Ubuntu 24.04 arm64 · Node.js 24 · pnpm 10.34.5      │
│  └ @deepseek-ai/dsh 0.2.2-rc.1 → Web UI :auto        │
└─────────────────────────────────────────────────────┘
```

数据：实际位置以「数据与备份 → 存储指南」为准；旧 `Documents/dshdata` 数据继续兼容读取。
API key 由 Android Keystore 保护，本机密钥与自动副本不保证卸载后存续。端口与鉴权细节见[端口专页](docs/ports-and-networking.md)。

## ADB 无线配对（设备 Shell 能力）

配好之后 agent 就能直接操作这台手机，**不需要 Shizuku**。完整通道说明见 [docs/device-channels.md](docs/device-channels.md)。

**首次配对（约 1 分钟）**

1. 系统设置 →「关于手机」→ 连点「版本号」7 次开启开发者选项
2. 开发者选项 → 打开「无线调试」
3. 进入「无线调试」→「使用配对码配对设备」，记下 **IP:端口** 与 **6 位配对码**
4. 回到 DSHA →「设备能力授权」页 → 填入 → 配对

> 无线调试的配对码需要 Android 11+；老系统可走 Shizuku / Root 通道，或直接用无障碍能力（读屏、点按、输入不需要任何配对）。

无线调试尚未开启时，「验证已有连接」可以先准备内置离线依赖；出现 `ADB_OFFLINE_READY` 仅表示依赖就绪。之后仍需打开无线调试并完成配对或连接验证。

**配对之后**：DSHA 自己维护连接（保活 + 重连），重启手机后也会自动恢复，不用再操作。

**验证**：内置终端里跑 `adb shell id`，输出 `uid=2000(shell)` 即成功。

## 从源码构建

需要 JDK 17、Node 24、Python 3.9+、Android SDK 37 与 NDK 26。固定输入用公开预发布资产还原，工具链与签名配置见 [BUILD.md](BUILD.md) 与 [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md)。

```bash
python3 tools/ci-assets.py --url "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["url"])')" \
  --sha256 "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["sha256"])')"
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
./gradlew :app:testStandardDebugUnitTest :app:lintStandardRelease
./gradlew :app:assembleStandardRelease
```

Windows 使用 `gradlew.bat`；也可用 `bash build.sh` 包装命令。测试任务名中的 Debug 只表示测试变体，不要求生成或安装调试 APK。签名私钥、用户数据、设备截图与日志不进入仓库。稳定性门禁见 `python3 tools/verify-stability.py`。

## 文档

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 项目结构、启动契约、技术约束与踩坑记录（AI / 新贡献者入口） |
| [docs/documentation-index.md](docs/documentation-index.md) | 完整文档索引 |
| [docs/security-model.md](docs/security-model.md) | 安全模型：每项权限的边界与已知弱点 |
| [docs/device-permissions-architecture.md](docs/device-permissions-architecture.md) | 权限架构：通道、原生授权与执行侧护栏 |
| [docs/plugins.md](docs/plugins.md) | 插件安装与打包要求 |
| [docs/virtual-screen-architecture.md](docs/virtual-screen-architecture.md) | 虚拟屏架构、前提与输入约束 |
| [docs/privileged-screen-core.md](docs/privileged-screen-core.md) | 实体屏特权执行核心（T130 Phase A） |
| [docs/runtime-engines.md](docs/runtime-engines.md) | proot / proroot 运行时与已知限制 |
| [docs/ports-and-networking.md](docs/ports-and-networking.md) | 端口、鉴权与局域网 |
| [docs/backup-and-restore.md](docs/backup-and-restore.md) | 备份、恢复与数据位置 |
| [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md) | 固定构建输入的公开资产与还原/升级流程 |
| [docs/i18n-length-policy.md](docs/i18n-length-policy.md) | 界面文案长度预算与多语言精简口径 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 路线图与「明确不做」清单 |
| [BUILD.md](BUILD.md) / [CONTRIBUTING.md](CONTRIBUTING.md) | 构建、签名与贡献流程 |
| [CHANGELOG.md](CHANGELOG.md) / [docs/releases/](docs/releases/) | 完整更新记录与各版本验收回执 |

## 致谢

- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) —— 本体
- [proroot](https://github.com/coderredlab/proroot) / [proot](https://github.com/termux/proot) —— 免 ROOT 容器（见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）
- [Termux terminal-view / terminal-emulator](https://github.com/termux/termux-app)（Apache-2.0）—— PTY 终端
- [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) —— 内置移动端适配
- [Shizuku](https://shizuku.rikka.app/) —— 备用设备命令通道

## 交流与反馈

- 问题与建议优先提 [GitHub Issues](https://github.com/DSH-APP/DSHA/issues)，附机型、Android 版本、APK 版本与复现步骤。
- 社区交流：QQ 群 **975836806**（应用内「关于」也提供入口）。
- 提交代码前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)；外部 PR 需逐行审查后才合并。

## 许可

[MIT](LICENSE)。第三方组件许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
