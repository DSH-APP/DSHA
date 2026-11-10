# Termux 官方源码评审：终端 HTML 原型的借鉴边界

> 2026-10-25 · shared task `task-1` · reviewer `termux-review`。**本轮只做公开源码获取、固定版本与静态设计评审，不移植上游实现。** 已先 get/claim；关键建议已在成文前发送给 lead。本文不表示原型已落实建议，也不是手机验收结果。

## 1. 结论先行

1. **获取的是官方 Android App 的 Java/XML/终端模拟代码，不是官方 HTML 页面模板。** 上游明确区分 App UI/terminal emulation 与软件包仓库；主布局直接实例化原生 `TerminalView`。[上游范围说明:10–14](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/README.md#L10-L14>) · [主布局:19–34](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/layout/activity_termux.xml#L19-L34>)
2. 最值得借鉴的是 **终端画布优先、会话管理退到 drawer、快捷键与文本输入明确分工、视图生命周期与会话生命周期分离**，不是逐像素复刻或复制 Java/XML。[布局:19–103](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/layout/activity_termux.xml#L19-L103>) · [工具条双页:29–80](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java#L29-L80>) · [Service 归属:55–66](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxService.java#L55-L66>)
3. **GPL 边界是设计输入，不是发布前补一条署名就能解决的事。** Termux App 默认 `GPL-3.0-only`；`terminal-view` / `terminal-emulator` 有 Apache-2.0 例外；`termux-shared` 不是整体 MIT，其 `shared/termux/*`（包括 extra keys）默认仍为 GPL。[根许可:1–6](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md#L1-L6>) · [shared 许可:1–22](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/LICENSE.md#L1-L22>)
4. HTML 应独立实现：可见的会话入口、可取消的关闭流程、明确的输入/执行语义、修饰键状态、焦点恢复和 IME 合成输入保护。下面“HTML 建议”均为**本评审推导**，不是声称 Termux 本身有相同 Web 实现。

## 2. 来源、获取与可复现性

| 项目 | 实际证据 |
|---|---|
| 公开仓库 | [termux/termux-app](<https://github.com/termux/termux-app>) |
| 固定 commit | [`8629e632fcb95da272221be327db653fb24befe9`](<https://github.com/termux/termux-app/commit/8629e632fcb95da272221be327db653fb24befe9>) |
| Git tree | `1567ffcc0e37ec52e20b365521be0a9311ecc353` |
| 获取时默认分支 | `master`；后续引用一律使用完整 commit，不用可漂移分支 |
| commit 时间 | `2026-09-26T10:11:43+05:00`，来自本地 Git 对象，不是发布验收时间 |
| commit 标题 | `Changed: Update dependency-submission to v6 in dependency-submission workflow`（仅记录标题，未运行该 workflow） |
| 获取时间 | `2026-10-25T11:22:22Z` 至 `2026-10-25T11:22:25Z` |
| 获取方式 | 公共 HTTPS、浅 clone、单分支、无 tags；Git `2.43.0` |
| 凭据隔离 | `env -i`、独立空 HOME、忽略全局/系统 Git 配置、空 credential helper/extraHeader、禁交互认证；未读取或复用 lead/其他代理凭据 |
| 本地源码 | `/tmp/dsha-termux-review-20261025-ZE6qaMrO/source`，在生产仓库外；未删除，未将源码拷入生产 |
| 网络结果 | 唯一 clone 退出码 `0`；无网络失败、无重试、无私有 CI 访问。原始 [clone 日志](</tmp/dsha-termux-review-20261025-ZE6qaMrO/git-clone.log>) 和 [退出码](</tmp/dsha-termux-review-20261025-ZE6qaMrO/git-clone.exit-code.txt>) 保留 |
| 完整性 | `git fsck --strict --no-reflogs` 退出码 `0`；上游工作区 `git status --porcelain=v1` 为空；未验证作者签名 |
| 体量 | 346 个 tracked 文件，3,049,101 bytes；不含 `.git`，不是 archive 大小 |
| 全文件索引 | [tracked-file-sha256.txt](</tmp/dsha-termux-review-20261025-ZE6qaMrO/tracked-file-sha256.txt>) SHA-256：`9cb7a774fb108de59708b1176bac5f1bf3056fb863c9c58736a168773fce7908` |
| 机器可读清单 | [termux-source-manifest.json](<<workspace>/DSHA-Sunset-mod-3090-Q/docs/design/terminal-20261025/termux-source-manifest.json>)：25 个证据文件的本地字节数、行数、SHA-256、审阅行段、许可和 URL 构造规则 |

全文件索引算法：按 `git ls-files -z` 得到的路径排序，每行是 `sha256 + 两个空格 + 相对路径 + LF`；摘要覆盖整个 UTF-8 索引。这是**索引摘要，不是源码 ZIP 摘要**。临时目录可能被系统清理，长期复现应使用完整 commit 与清单哈希。

实际匿名获取的核心命令如下；仅 Git 获取数据，不运行上游程序：

```sh
env -i PATH="$PATH" HOME="$review_root/anonymous-home" \
  GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 \
  git -c credential.helper= -c http.extraHeader= \
      -c core.hooksPath=/dev/null -c init.templateDir= \
      clone --depth 1 --single-branch --no-tags \
      https://github.com/termux/termux-app.git "$review_root/source"
```

本次未逐条 HTTP 打开下文 blob/raw URL；源码由上述匿名 Git 获取，引用由同一固定 commit 和已读行段生成。Git 完整性与 SHA-256 不等于作者签名验证、APK 来源验证或法律全审计。此次 `master` 快照也**不是 DSHA 已依赖的 0.118.0 同一版本**；现有依赖边界见 [DSHA 第三方声明:27–42](<<workspace>/DSHA-Sunset-mod-3090-Q/THIRD_PARTY_NOTICES.md#L27-L42>)。

### 本仓库的适用约束

已读 [AGENTS.md](<<workspace>/DSHA-Sunset-mod-3090-Q/AGENTS.md>)、[README.md](<<workspace>/DSHA-Sunset-mod-3090-Q/README.md>)，并核对 [THIRD_PARTY_NOTICES.md](<<workspace>/DSHA-Sunset-mod-3090-Q/THIRD_PARTY_NOTICES.md>) 与 [安全模型](<<workspace>/DSHA-Sunset-mod-3090-Q/docs/security-model.md>)。本轮须保持：

- PTY/简易终端独立会话，跨切页、旋转、语言变化保持进程；关闭单个标签须核验该会话退出；会话 ID 与展示编号分离。[AGENTS:180–187](<<workspace>/DSHA-Sunset-mod-3090-Q/AGENTS.md#L180-L187>)
- UI 更新不替换运行环境，语言变化不停止终端；不因参考上游重写已有启动/维护合同。[AGENTS:153–159](<<workspace>/DSHA-Sunset-mod-3090-Q/AGENTS.md#L153-L159>)
- 未知退出不能当成功，按会话/出生身份验证停止；终端仍非独立内核沙箱。[安全模型:13–16](<<workspace>/DSHA-Sunset-mod-3090-Q/docs/security-model.md#L13-L16>) · [停止边界:50–54](<<workspace>/DSHA-Sunset-mod-3090-Q/docs/security-model.md#L50-L54>)
- DSHA 自有代码 MIT，不把第三方许可抹平；本轮不构建、安装、运行手机或发布。[README:378–398](<<workspace>/DSHA-Sunset-mod-3090-Q/README.md#L378-L398>) · [交付边界:224–230](<<workspace>/DSHA-Sunset-mod-3090-Q/AGENTS.md#L224-L230>)

## 3. App 布局与 Activity：保留画布，减少常驻装饰

### 上游事实

- 根视图填满窗口，主体含 `DrawerLayout` 与 `TerminalView`；终端可触摸聚焦、有垂直滚动条。左 drawer 宽 240dp，设置按钮、单选会话列表和底部“键盘/新会话”分层；工具条置于画布下方，基础高度 37.5dp。[主布局:1–111](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/layout/activity_termux.xml#L1-L111>)
- Activity 加载属性/偏好后装配 terminal clients、工具条、设置、新会话、键盘按钮，再 start/bind service。[TermuxActivity:198–275](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java#L198-L275>)
- Manifest 声明 `singleTask`、可调整大小和若干配置变化由 Activity 处理；这不等于浏览器路由、DOM 重建或 Android 新版本后台政策的通用解法。[AndroidManifest:52–75](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/AndroidManifest.xml#L52-L75>)

### 可借鉴与 HTML 建议

- **P0：保留一个主要终端视口。** 标题、环境、会话和状态尽量压成紧凑信息区；不要把日志包装为多张卡片，也不要让大面积环境概览挤掉正文。
- **P0：会话管理显式可达。** 窄屏使用有名称的“会话”按钮打开 drawer；宽屏可常驻侧栏，已有紧凑标签可保留。drawer 是渐进增强，不是必须模仿的唯一布局。
- **P1：工具条和画布分别布局。** 使用可收缩的 grid/flex 中间区与独立滚动区域，避免整个页面滚动把快捷键带走；安全区和键盘可视高度单独处理。
- 不直接沿用 240dp、37.5dp、40dp 设置按钮作为 Web 像素值或可访问性标准；HTML 建议采用约 44–48 CSS px 的主要触控目标，短屏允许工具条折叠。这是设计建议，不是上游尺寸或已验收结果。

## 4. Drawer / session：身份、显示与终止不是一件事

### 上游事实

- 会话行由展示编号、用户名称、terminal title 组合；运行完毕用删除线，非零退出可用红色；单击切换并关闭 drawer，长按重命名。[会话列表控制器:68–106](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java#L68-L106>)
- 会话切换 attach 已有 session，而非重新启动；当前会话 handle 可保存并重新匹配仍存在的 session；新会话可沿用当前工作目录。[Session client:292–305](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java#L292-L305>) · [新建与恢复:364–430](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java#L364-L430>)
- 退出回调有多种策略：非当前会话 toast、某些退出码自动移除、插件待结果行为不同；移除后选择剩余会话或关闭 Activity。[退出处理:139–180](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java#L139-L180>) · [移除:433–451](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java#L433-L451>)
- 终端对象有独立 handle，视图可能先销毁而会话仍存活；PTY 尺寸变化与 emulator resize 是真实后端操作。[TerminalSession:20–69](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java#L20-L69>) · [尺寸更新:102–109](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java#L102-L109>)

### 可借鉴与 HTML 建议

- **P0：稳定 sessionId，显示序号可变。** 切换只改 activeSessionId，不清空 transcript、草稿或滚动位置；不得用标题/数组位置充当后台会话身份。DSHA 继续使用自己的 ID 合同，不必复制上游 UUID 策略。
- **P0：活动会话与状态同时可见。** 活动标记加文本/图标，不只靠颜色；“运行中/已退出/关闭中/退出待核验”比仅删除线更易理解。
- **P0：显式重命名与关闭。** 保留长按作为辅助，但行内菜单/可聚焦按钮必须提供等价入口；关闭运行会话有确认和取消，关闭失败/未知保留条目。原型仅演示状态时明确标注，不能把删除 DOM 当作真实进程已停。
- **P1：新会话上下文可解释。** 若默认沿用工作目录，创建面板写明来源；不要悄悄在另一个环境或全局目录启动。普通终端与简易终端的切换不可隐式销毁对方。
- **不直接搬用** Termux 对特定退出码自动移除的规则：DSHA 已有更严格的退出证明合同，用户也可能需要保留失败输出。

## 5. Extra keys：不是一排装饰按钮

### 上游事实

- 当前固定 commit 的默认 extra keys 是两行，含 Escape、方向、Home/End、PageUp/PageDown、Tab、Ctrl、Alt 等；行数和 toolbar height scale 参与最终高度计算。[属性默认值:326–333](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java#L326-L333>) · [高度计算:510–538](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java#L510-L538>)
- 配置解析失败回退默认；App 额外处理 `KEYBOARD` / `DRAWER` / `PASTE` / `SCROLL`，其他键交给终端输入分派。[App extra keys:49–105](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/io/TermuxTerminalExtraKeys.java#L49-L105>)
- 修饰键单击激活、长按可锁定，读取时可消费非锁定状态；按住重复键有定时器，`ACTION_UP` / `ACTION_CANCEL` 清理，不是每次触摸事件都发一次字符。[触控取消:422–465](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java#L422-L465>) · [状态与重复:519–583](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java#L519-L583>) · [状态消费:632–654](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java#L632-L654>)
- 输入分派区分宏、KeyEvent 控制键与 Unicode code point；把按钮文字追加进 transcript 不具备这些含义。[TerminalExtraKeys:27–76](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/terminal/io/TerminalExtraKeys.java#L27-L76>)

### 可借鉴与 HTML 建议

- **P0：Ctrl / Alt 具备明确的 armed 状态与 `aria-pressed`。** 默认一次性消费，取消或切换会话后清除，避免发到错误会话；若支持锁定，必须另有清晰的锁定标志和解除入口。锁定不是 P0 必需。
- **P0：动作有真实映射或诚实的演示反馈。** Esc/Tab/方向键/中断使用明确 action payload；未接 PTY 时显示“模拟快捷键”，不能在屏幕写 `^C` 后声称真实命令已中断。
- **P1：第一层常用键，第二层可折叠。** 两行是上游当前默认，不必强制原型始终占两行；根据短屏、横屏和软键盘展开场景选择。
- **P1：长按重复只用于适合重复的方向/删除等键。** `pointerup`、`pointercancel`、窗口失焦、会话切换、面板关闭都结束重复；不让 destructive action 参与通用 repeat。浏览器实现独立编写，不翻译上游 GPL 状态机。

## 6. 输入焦点、IME、选择与发送语义

### 上游事实

- 工具条两个页面分别是 extra keys 和文本输入；切换页明确 requestFocus 给终端或 EditText。文本输入有保存恢复；发送时非空文本原样写入，**仅空文本改成 `\r`**，不是所有非空输入都自动追加 Enter。[工具条:44–80](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java#L44-L80>) · [焦点切换:105–112](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java#L105-L112>)
- 软键盘“显示/隐藏”和“启用/禁用”可有不同含义；即使启动时隐藏/禁用软键盘，仍请求终端焦点以接硬件键盘。重新加载设置不强制抢开软键盘。[View client:530–645](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java#L530-L645>)
- 原生输入连接处理 composition 结束、commitText、deleteSurroundingText、Unicode surrogate pairs 和 CR/LF；这不是简单 `keydown` 等价物。[TerminalView:309–438](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L309-L438>)
- 复制选择模式锁住 drawer，防止选字被侧滑打断。[View client:231–235](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java#L231-L235>)
- 上游专门解释 Gboard 候选栏与扩展布局遮挡，以及 bottom-space/margin 补偿可能抖动；多窗口直接跳过这套补偿，避免错误几何推导。[RootView:23–62](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxActivityRootView.java#L23-L62>) · [多窗口边界:133–145](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxActivityRootView.java#L133-L145>)

### 可借鉴与 HTML 建议

- **P0：两种输入模式有明确标签。** “终端直输”与“编辑后发送”不能暗中混用；可使用“快捷键 / 文本输入”切换，不要求用户猜横滑手势。
- **P0：区分插入、粘贴和执行。** 如原型按钮叫“执行”，明确它是否追加 Enter；若叫“插入”，不偷偷执行。多行粘贴先显示内容/行数并提供取消，避免含换行立即执行。此预览策略是本评审建议，非声称上游已有确认框。
- **P0：焦点是状态，不是每次 render 的副作用。** 打开设置/会话面板保留原焦点来源；关闭恢复到有效控件。快捷键指针操作不无意抢走输入焦点，同时保留按钮的键盘可达性；不能用全局 `preventDefault()` 破坏 Tab、选字、滚动和辅助技术。
- **P0：中文合成输入只提交一次。** 根据 composition/input/beforeinput 的实际平台行为管理 `isComposing`；合成中 Enter 不提交整条命令，避免 compositionend 与 input 双发。禁止把全局 keydown 当全部文字来源。
- **P1：视口调整按浏览器能力实现。** 优先独立可视区域与安全区，必要时结合 `visualViewport` 做有界更新；不能把 Android margin workaround、300/500ms 延迟和固定键盘高度直接搬成 Web 定时器。浏览器模拟键盘不是 Android IME 测试。
- **P1：选择文本不触发会话 drawer 或发送键。** 手势只能渐进增强；Escape 优先关当前 UI 弹层，只有终端拥有输入权时才发送 terminal Escape，且不吞掉输入法组合操作。

## 7. Service 生命周期：离开页面不等于终止会话

### 上游事实

- Service 持有 sessions/background tasks，前台通知与可选 wake/Wi-Fi locks 用于生命周期；Activity 可先结束后重新绑定。[TermuxService:55–106](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxService.java#L55-L106>)
- Activity 停止时撤掉 UI 监听，销毁时清 Activity 引用并解绑，不在该路径直接停止 service；重新连接时恢复现有 session，并防止重建后重复执行“新会话” intent。[TermuxActivity:325–375](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java#L325-L375>) · [恢复与防重复:387–429](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java#L387-L429>)
- Service 返回 `START_NOT_STICKY`，不把被杀后自动重启作为默认；其停止实现有 kill-all 路径，代码也明确非预期 shutdown 的 onDestroy 不保证完成。[Service:163–200](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxService.java#L163-L200>) · [停止及限制:221–259](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxService.java#L221-L259>)
- 上游 README 自己提醒 Android 12+ phantom/excessive-CPU 进程可能被系统终止；“有前台 service”不能证明永不被杀。[上游限制:20](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/README.md#L20>)

### 可借鉴与不直接搬用

- **P0：UI 状态和 session 状态解耦。** 返回主页、drawer 开关、设置开关、主题变化都不等价于发送关闭命令；重建 UI 不重复新建/执行请求。
- **P0：状态文案受证据约束。** 原型显示“已模拟切换/已模拟关闭”；后续真实集成以后台回执区分 requested/closing/exited/unknown，失联不变成成功。
- 不复制 Termux 的 kill-all、wake lock、Wi-Fi lock 或启动服务实现到 DSHA；保留已有受管停止、出生身份、前台服务准入与未知屏障。HTML 本身不提供 Android service 保活，网页 timer 也不是替代。
- 视觉层可停止不可见页面的光标动画、重渲染等，而不停止运行会话；上游在 onStop 只关闭 cursor blinking 的例子可作为职责区分。[光标可见性:112–134](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java#L112-L134>)

## 8. Settings：低频设置独立，关键动作留在终端

### 上游事实

- Settings 是独立 Activity，使用 PreferenceFragment、返回入口；部分插件设置按实际可用偏好显示。[SettingsActivity:29–109](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/activities/SettingsActivity.java#L29-L109>)
- 设置区分 debugging、terminal I/O、terminal view；I/O 有软键盘启用和“仅无硬件键盘时启用”，view 有边距补偿开关。[设置分类:1–18](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/xml/termux_preferences.xml#L1-L18>) · [I/O:3–19](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/xml/termux_terminal_io_preferences.xml#L3-L19>) · [view:3–13](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/xml/termux_terminal_view_preferences.xml#L3-L13>)
- 主题变化可以重建 Activity，但特意保留 extra-keys 文本、sessions 和 transcripts。[主题重建:992–997](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java#L992-L997>)

### HTML 建议

- **P1：终端内只留即时操作。** 新建、会话、键盘/输入、快捷键入口可直接操作；字体、配色、默认键盘、提示与辅助功能进入设置面板。
- **P1：设置变更不清会话。** 字号/主题修改后保留活动会话、输出、草稿；如有“恢复默认”，清楚限定为显示设置，而非结束进程或清历史。
- **P1：无能力项不伪装可用。** 原型可标注“演示”；真实集成未支持的全屏、系统键盘、保活、后台会话恢复不能仅用 switch 表现成功。

## 9. 许可评估与工程取用决策

| 范围 | 固定证据 | 本轮决策 |
|---|---|---|
| Termux App / 仓库默认 | [根许可:1–6](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md#L1-L6>) 明确 GPLv3 **only** | 借鉴行为、信息层级与交互原则；不复制 Activity、Service、drawer XML、App extra-key glue、图标/主题资源，不把 Java 翻译成 JS 当作摆脱许可 |
| terminal-view / terminal-emulator | [根许可:5](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md#L5>) 明确 Apache-2.0 例外 | 将来实际复用时锁版本、核具体文件及依赖、保留许可/适用 NOTICE/修改说明；例外不覆盖同仓库 App 或 shared/termux 代码 |
| termux-shared 一般目录 | [shared 许可:1–22](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/LICENSE.md#L1-L22>)：默认 MIT，含 GPLv3、GPLv2+Classpath、Apache 特例 | 必须逐路径/文件判断，不能用模块名判成全 MIT |
| shared/termux/extrakeys 与 shared/termux/terminal/io | [GPL 子树规则:5–9](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/LICENSE.md#L5-L9>) | `ExtraKeysView`、`TerminalExtraKeys` 不在 terminal-view/emulator 的 Apache 例外内；本轮不搬实现 |
| TermuxPropertyConstants | [明确文件例外:9](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/LICENSE.md#L9>) 与 [SPDX:20–22](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java#L20-L22>) 为 MIT | 默认按键排列可作设计参考；例外仅该明确文件，不能外推整个键盘库 |

“GPL 不直接搬用”是**本轮范围和 DSHA MIT 集成风险控制决策**，不是声称 GPL 禁止所有复用。若未来要实际复制或派生，应按分发方式、衍生范围、依赖和许可证义务单独审查；保留 URL/署名并不会自动消除 GPL 义务。本文是工程评估，不提供法律意见，也不宣称已完成全仓许可审计。

## 10. 交给 lead 的原型对照检查表

本代理未编辑或运行其他成员负责的 HTML/检查器/报告；以下是基于第 3–8 节固定源码的**建议验收项**，不是已发现的当前原型缺陷或已通过的测试。

| 优先级 | 建议验收 | 依据 |
|---|---|---|
| P0 | 新建/切换/重命名不丢已有输出与草稿；display number 变化不改变 sessionId | 第 4 节 session attach / handle 恢复 |
| P0 | 关闭运行会话可取消；确认后明确模拟状态或真实待退出，unknown 不移除 | 第 4、7 节生命周期区别 + DSHA 退出合同 |
| P0 | drawer/设置开关不提交输入；退出弹层正确恢复焦点；按钮可用键盘访问 | 第 3、6、8 节分层与焦点 |
| P0 | 中文组合时 Enter 不误执行；commit 不双发；输入、粘贴、Enter 语义不同 | 第 6 节 IME 与原文发送 |
| P0 | Ctrl/Alt 激活可见且可取消；会话切换不把旧修饰键留给新会话 | 第 5 节修饰键状态；清除策略为本评审建议 |
| P1 | 短屏/横屏/放大字体/软键盘候选栏下可访问命令区和快捷键，不用双重补偿挤空终端 | 第 3、6 节高度与键盘边界 |
| P1 | 长按重复在 pointercancel/blur/面板关闭/切会话终止，不重复提交 destructive action | 第 5 节触控清理与 Web 推导 |
| P1 | 主题、字号、主页往返不重启会话；无真实能力不显示“已保活/已成功中断” | 第 7、8 节视图与进程分离 |
| P1 | 输出按文本呈现；必要 ANSI 功能由明确终端层处理，不把命令/输出直接注入 HTML | 第 6 节 Unicode 输入，以及 [模拟器职责:11–14](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java#L11-L14>)；HTML 注入防护为本评审建议 |

浏览器层检查只证明该原型路径；要验证真实 Android IME、TUI、PTY resize、进程退出、后台返回、辅助技术或 OEM 行为，需后续另行授权与独立设备证据。本轮没有执行这些验证。

## 11. 关键原始文件入口

以下都是同一完整 commit；左侧带行号为本报告证据，右侧为 raw 原文件地址。完整 25 文件路径与哈希见 [机器清单](<<workspace>/DSHA-Sunset-mod-3090-Q/docs/design/terminal-20261025/termux-source-manifest.json>)。

| 固定源码与行号 | 原始字节 URL |
|---|---|
| [根许可:1–6](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md#L1-L6>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/LICENSE.md>) |
| [shared 许可:1–22](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/LICENSE.md#L1-L22>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/termux-shared/LICENSE.md>) |
| [主布局:19–103](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/layout/activity_termux.xml#L19-L103>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/app/src/main/res/layout/activity_termux.xml>) |
| [Activity:198–438](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java#L198-L438>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxActivity.java>) |
| [会话列表:68–106](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java#L68-L106>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java>) |
| [工具条:44–112](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java#L44-L112>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/io/TerminalToolbarViewPager.java>) |
| [ExtraKeysView:519–583](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java#L519-L583>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/termux-shared/src/main/java/com/termux/shared/termux/extrakeys/ExtraKeysView.java>) |
| [输入焦点:568–645](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java#L568-L645>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java>) |
| [Service:55–259](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxService.java#L55-L259>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/TermuxService.java>) |
| [SettingsActivity:29–109](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/activities/SettingsActivity.java#L29-L109>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/app/src/main/java/com/termux/app/activities/SettingsActivity.java>) |
| [TerminalView:309–438](<https://github.com/termux/termux-app/blob/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java#L309-L438>) | [raw](<https://raw.githubusercontent.com/termux/termux-app/8629e632fcb95da272221be327db653fb24befe9/terminal-view/src/main/java/com/termux/view/TerminalView.java>) |

## 12. 完成范围与未做项

**完成：** 匿名获取和保留上游浅检出；记录 commit/tree；Git 完整性检查与全部 tracked 文件摘要；25 个关键证据文件的范围清单；App 布局、Activity、drawer/session、extra keys、输入焦点/IME、service、settings、许可边界静态评估；面向 lead 的优先级建议。

**交付检查：** JSON 可解析；25 个关键文件的字节数、行数、SHA-256 与本地固定检出一致；61 个 commit 固定 blob 引用的路径/行号均在本地源文件范围内；16 个本地文件链接存在；全文件索引和 clone 日志摘要匹配。检查脚本退出码 `0`。这些是本地证据一致性检查，不是逐 URL HTTP 可得性测试。两份文件为新建未提交文件；未将空的 scoped `git diff --check` 输出误称为对 untracked 内容的完整格式验收。

**未做：** 上游构建/测试/脚本执行、APK 下载/安装、手机/虚拟屏控制、私有 CI、生产代码移植、依赖升级、Git 提交/推送/发布、网站操作；未编辑其他成员负责的原型或报告，未宣称这些建议已经实现。未验证所有历史版本、全部版权来源、运行时真实效果或性能。
