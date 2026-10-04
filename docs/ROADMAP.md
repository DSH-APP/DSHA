# DSHA 路线图（2026-08-26 起）

> 历史记录：本文只描述原日期、版本和已执行范围；不作为现行构建、验收或发版入口。当前流程见[接手指南](接手指南.md)，原字节和摘要见[历史文档清单](audits/build154/history-document-sources.json)。


> 状态：v1.1.9-rc1 已作为预发布发出（Release 里有 APK + sha256，离线包由 CI 现建，
> dsh 0.1.1-rc.2 / 标记 2）。下面的阶段划分是 2026-08-26 一轮竞品调研之后重排的，
> 每一阶段都写了「怎么验证」和「不通了退哪条路」——没有退路的计划不算计划。

## 先修正一条对外表述

调研发现 [AAswordman/Operit](https://github.com/AAswordman/Operit)（7044★，LGPL-3.0）
也内置 Ubuntu 24.04 + PRoot（条件允许时上 chroot），终端还支持 vim / tmux / SSH。
所以「完整 glibc 环境」**不再是 DSHA 独有的卖点**，README 与对外介绍里不该再这么讲。

DSHA 真正剩下的差异：

- 专为 dsh（DeepSeek 官方 agent 框架）做的一体化，不是自己实现 agent loop；
- proroot（零 ptrace 开销）而非 PRoot；
- ADB 直连，不强制 Shizuku；
- 卸载重装数据不丢 + 一整套备份/自愈体系。

---

## 阶段 0 · v1.1.9 收尾

阻塞点是真机验证，代码侧已经就绪。

| # | 事项 | 谁做 | 验收 |
|---|---|---|---|
| 0.1 | rc1 真机复验（见下表） | 用户 | 五项全过 |
| 0.2 | 127 根因实证 | 用户跑一条命令 | 看到 `/usr/local/lib/node_modules` 的实际状态 |
| 0.3 | GitHub token revoke | 用户 | 换 fine-grained + contents:write + 30 天 |
| 0.4 | debug keystore 离线备份 | 用户 | 拷到外部存储 + 云盘 |
| 0.5 | 关闭议题 #36 | 用户 | — |
| 0.6 | 打 `v1.1.9` 正式版 | 验证通过后 | Release 出包，UpdateChecker 能推 |

rc1 要验的五项：

1. 老备份恢复后旧 UI 插件**不再复活**，报告里出现「未补装 dsh-client-ui-mobile-adapt：…」；
2. 「发现新版内置环境 v2」**会弹一次**（线上标记 2、老 rootfs 是 1），点升级后 dsh 仍是
   0.1.1-rc.2 —— 退回 0.1.0-rc.6 就说明防降级没生效；
3. 悬浮条按行滚动，默认 3 行，已显示的行不再抖动；
4. 设置页「重新解压内置环境」（在「自检 & 修补」下面）能进解压页；
5. 插件市场装旧 UI 插件时弹冲突说明，且仍可「仍然安装」。

`v1.1.9` 正式版发出后，**所有老用户会收到一次「发现新版内置环境 v2」**（离线包内容确实
变过好几轮）。这是预期行为，弹窗可忽略、可稍后，重解压会保留配置 / API Key / 对话记录。

---

## 阶段 1 · 真 PTY 终端

**为什么排最前**：`TerminalFragment` 现在 246 行，靠 `execRootfsInteractive()` 起 bash、
把 stdout 塞进 TextView。没有 PTY 就没有 TERM、没有行编辑、没有光标控制 ——
vim / htop / tmux / top / ncdu 全跑不了。这是用户一上手就会撞到的硬伤。

**技术路线换了**：原计划自己搭 xterm.js + WebView + `script -qfc`。调研发现 Termux 把终端
拆成了独立库发在 JitPack 上，而且 `terminal-view` 与 `terminal-emulator` 这两个子模块是
**Apache 2.0**（termux-app 整体 GPLv3，但这两个不是），与 DSHA 的 MIT 兼容。它们自带
JNI 的 PTY 层（openpty + fork/exec）、完整 xterm 模拟和现成的 `TerminalView` 控件。

```gradle
maven { url "https://jitpack.io" }
implementation "com.termux:terminal-view:0.118.0"     // 自动带 terminal-emulator
implementation "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava"
```

### 1.0 先做 go/no-go 验证（不要先动 UI）

三个假设必须先证实，任何一条不成立就退回 xterm.js 方案：

1. **JNI 的 `.so` 是否随 aar 打包**，能否在没装 Termux 的机器上跑起来
   （aar 里要有 `jni/arm64-v8a/*.so`）；
2. **能否把任意 argv + env 交给它的 `createSubprocess`** —— 我们要传的是 proroot 的启动
   参数（`libproot_exec.so` + 一串 `-b` 挂载 + 环境变量），不是普通可执行文件；
3. **Android 14 的 W^X 是否拦**。DSHA 现在靠 `linker64` 启动 proot 绕过，JNI fork 出来的
   子进程要单独确认。

前两条能在工作区静态验证（解 aar、读 JNI 方法签名）；第三条只能真机。

### 1.1 改动点

| 文件 | 改什么 |
|---|---|
| 根 `build.gradle` / `settings.gradle` | 加 JitPack 仓库 |
| `app/build.gradle` | 两个依赖（少了 guava 占位包会 `Duplicate class`） |
| `ProotBootstrap.java` | 现在 argv 与 env 是内部构造后直接塞 ProcessBuilder；要新增 `ptyArgv()` / `ptyEnv()` 把它们暴露出来供 JNI 用 |
| 新建 `PtySession.java` | 包装 `TerminalSession`：启动命令 = proroot argv + `/bin/bash -l`；处理退出、标题变更、bell |
| 重写 `TerminalFragment.java` | `TerminalView` 取代 TextView+EditText；加扩展键行（Ctrl / Esc / Tab / 方向键 —— TUI 必需）、字号调节、长按选择复制 |
| 兜底 | 保留旧实现作为「简易终端」开关。新终端在个别机型崩掉时用户还有退路 |

### 1.2 验证

真机跑 `vim`、`htop`、`tmux`、`top`、`ncdu`，以及 dsh 自己的交互界面。UI 功能没法写断言，
必须真机过一遍。

---

## 阶段 2 · snapshot / act 屏幕操作

**底子比预想的厚**：`DshaAccessibilityService` 已有 573 行，`uiDump` / `uiTapText` /
`uiTap` / `uiInput` / `uiKey` / `uiScreenshot` / `uiSwipe` 全都在，而且已经挂在 3090 桥的
`/app/device` 下。差的只是「ref」这一层 —— 抄
[HermesApp](https://github.com/SelectXn00b/HermesApp)（MIT）的 Playwright 模式。

| 文件 | 改什么 |
|---|---|
| `DshaAccessibilityService.java` | `uiSnapshot()`：遍历树、给每个可交互节点编号（`e1`/`e2`…），输出精简结构（ref + 类型 + 文本 + 可点击/可编辑 + bounds），内存里存 `ref → node` 并带 epoch；`uiAct(ref, action, value)`：按 ref 执行 click / setText / scroll / longClick，epoch 不匹配返回 `STALE` 让 agent 重新 snapshot |
| `HttpShellService.java` | `/app/device` 加 `action=snapshot` 与 `action=act` |
| 新建 `UiSnapshot.java` | 「树 → 精简文本」与「ref 解析」抽成无 Android 依赖的纯逻辑，进 pure-logic 断言 |
| `agent-skills/` | 新增 `screen-operator`（Playwright 风格）；把 `screen-ocr-operator` 降级成兜底，SKILL.md 写明「先 snapshot，WebView / Flutter / 游戏 / 画布里拿不到内容再上视觉模型」 |

**收益**：现在每一步都要截图 + 视觉模型 + 猜坐标。换成 ref 之后一次 snapshot 能规划多步，
不花视觉模型的钱，坐标也不会错。

---

## 阶段 3 · 低成本高感知（适合穿插在等编译、等真机反馈的间隙）

| # | 做什么 | 成本 | 说明 |
|---|---|---|---|
| 3.1 | 默认助理入口 | 极低 | `AndroidManifest` 加 `ASSIST` + `VOICE_COMMAND` intent-filter，透明 Activity 转发到对话页。长按 home 或「Hey Google, ask DSHA」直接进 |
| 3.2 | 工具权限三档 | 低 | 自动允许 / 每次询问 / 禁止（默认询问）。现在只有危险命令二元确认；`DangerShellGuard` 与 `/app/ask` 读同一个配置 |
| 3.3 | 局域网二维码 | 低 | 现在要手输 token；生成 `http://<ip>:3081/?token=…` 的二维码贴在工作区页 |
| 3.4 | 构建证明验证指引 | 极低 | 我们已经有 attestation，但 README 没写怎么验：`gh attestation verify --deny-self-hosted-runners` |

---

## 阶段 4 · 定位性功能（做之前先确认有人要）

- **4.1 MCP Server**：在 3081 桥上加 MCP Streamable HTTP 端点，暴露 exec /
  device.snapshot / device.act / sensors / location / torch / clip / notify，认证复用现有
  Bearer token。做完 Claude Desktop 与 Cursor 就能直接调用手机能力 —— 这是别家没有的定位，
  而且与「手机自给自足」不冲突（是叠加）。
- **4.2 语音**：STT 用 Android SpeechRecognizer，TTS 先用系统的（本地 ONNX VITS 是后话）。
  与 3.1 串起来就是「按住 home 说话 → agent 干活」。
- **4.3 WebDAV 备份上传**：HttpURLConnection + Basic Auth + MKCOL，密码走 Keystore，
  默认只在 Wi-Fi 上传。

---

## 阶段 5 · 技术债（穿插，别单独排期）

- `HarnessController` 已 42.9 万字节：每次改都要等全量编译，而且「同一份判断散落两处」的
  坑基本都出在它内部。按职责切：安装 / 备份恢复 / 插件 / Web 生命周期 / 设备桥，
  一次只搬一块 + 编译验证。
- 备份恢复进度可视化；自动备份改「距上次多久」；备份前磁盘预检（中转包在 rootfs 内，
  峰值要两倍空间）；`.credentials.yaml` 排除开关。

---

## 执行顺序

```
阶段 0（等真机验证）
  ↓
阶段 1.0  Termux 库 go/no-go        ← 决定后面走哪条路
  ↓                              ↘ 不通 → 退回 xterm.js + script -qfc
阶段 1.1/1.2 实现 ──┐
                    ├── 期间穿插阶段 3
阶段 2 ─────────────┘
  ↓
阶段 4（先验证需求）
```

阶段 1 排最前，因为「跑不了 TUI」是唯一一个用户一上手就撞到的硬伤，而 Termux 库把成本从
「自己编 pty-bridge」降到「加两行依赖」。阶段 2 价值高但要 agent 侧配合改 skill，可以并行
准备。阶段 4 全是叠加能力，需求没验证过，别提前投入。

---

## 明确不做

| 不做 | 原因 |
|---|---|
| 抄 Operit 的代码 | LGPL-3.0 会传染到 MIT，只看思路 |
| OpenClaw 架构 | Gateway 跑电脑、手机当 companion node，与「手机自给自足」相反；Wear OS 伴侣同理 |
| HermesApp 的 agent 内核 | dsh 本身就是内核，重写没意义 |
| `libtermux/libtermux-android` | 12★，自己 README 写着 experimental / not production-ready，而且是 Termux 派 bionic 环境，与 glibc 容器路线冲突 |
| R8 混淆压包 | 370MB 里 Java 占比极小，省不到 1% |
| AVF（Android Virtualization Framework） | 现在只有 Pixel 6+ / Android 16+，设备覆盖太窄。但要留意：AVF 铺开到主流机型后，proot 层会从资产变负债，届时形态应是「检测到 AVF → 走 VM，否则回落容器」 |
