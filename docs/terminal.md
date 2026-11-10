# 终端：PTY、简易模式与会话关闭

源码更新：2026-10-30。终端工作台基于 build197 `02f80ab` 落地，build198 补上悬浮球/菜单的会话抽屉；同日后续把侧栏改为跟随应用三向态、图标化与 2×2 填充底栏。2026-10-30 起手机竖屏/窄窗抽屉上限收窄到 224dp，平板（含分屏窄窗）与宽窗仍为 336dp；同版加入 DSH CLI 入口（复用 PTY 固定 guest command）。同日又收尾 224dp 窄抽屉的视觉对齐：宽度 < 240dp 时改用 dense 短标签动作组、缩短摘要与副标题并统一 12dp 横向网格，完整语义保留在 `contentDescription`；宽窗/平板布局不变。本页说明源码合同，不是设备验收报告；build214 的 224dp 窄抽屉真机实测见[build214 真机验收记录](<releases/v0.2.1-rc.3-build214-acceptance-20261030.md>)。构建、测试与交付状态见[终端回归说明](<terminal-regression-20261025.md>)和[CI 合同](<terminal-ci-20261025.md>)；本次原生三向态、图标化与 2×2 底栏的 CI 测试包见[build198 主题报告](<releases/v0.2.1-rc.3-build198-native-terminal-theme-20261026.md>)，旧审计 APK 不作为操作入口。

## 入口与选择

Main 的“终端”是打开同 task、非导出的 [TerminalActivity](<../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java>) 的动作，不替换原根页或子页；通知可复用该终端宿主。默认极简黑底，页面内菜单球可拖动吸边；无需系统悬浮窗权限。点击悬浮球或工作台顶栏“菜单”打开会话侧栏：侧栏提供 PTY/简易分组、新建、切换、重命名、结束核验，以及返回主界面、显示设置、键盘、粘贴、回到输入和说明。

侧栏形态按**当前窗口 dp**判定，不按物理像素或机型名称猜测：

| 窗口 | 极简模式 | 工作台模式 |
|---|---|---|
| 宽度 < 840dp | 会话抽屉（手机竖屏/窄窗 224dp 上限；平板含分屏窄窗与宽窗 336dp；至少保留 32dp 遮罩/返回手势区） | 顶栏“菜单”打开同一抽屉 |
| 宽度 ≥ 840dp，内联后终端宽度仍 ≥ 480dp | 用户打开会话时以约窗口 25%（240–296dp）的内联面板显示，终端同时保留并仍可输入；再次点击球或关闭按钮回到全宽 | 同一面板常驻内联；关闭按钮隐藏，顶栏显示“设置/说明” |

宽度跨断点、旋转、分屏拖拽或折叠形态变化都会重新判定。抽屉最大宽度按设备类与当前窗口宽度双重判定：`smallestScreenWidthDp < 600` 的手机类在窗口 < 600dp 时取 224dp；平板（含分屏窄窗）、手机宽窗与宽屏取 336dp，不按机型名猜测；32dp 遮罩/返回手势区不收缩，内联侧栏的 240–296dp 不受抽屉上限影响。极简模式的“已打开”意图随 Activity 保存：从抽屉转内联或反向时继续显示同一侧栏，不重建会话；工作台的内联常驻不依赖该意图。可用高度（扣除系统栏和输入法）< 560dp 时隐藏副标题和次要说明并收紧边距；< 480dp 或**横屏且高度 < 1000dp** 时底部动作改为三列两行，避免固定三行挤掉会话列表；横屏同时把动作压到 44dp/10.5sp、使用短标签（完整名称在 `contentDescription`），隐藏副标题与脚注，竖屏/超高窗口恢复两列三行与完整标签。极简内联时悬浮球隐藏，用侧栏关闭按钮或返回键收起；抽屉仍由球/菜单打开并被遮罩覆盖。侧栏与悬浮球跟随应用「系统 / 白天 / 黑夜」三向态：浅色为白/浅蓝、夜间为近黑/浅蓝，终端输出区在两套外观下都固定黑底；侧栏不再内置主题分段开关，外观统一由应用顶栏的主题按钮或系统设置驱动，切换由 AppCompat 重建 Activity，会话仍由进程级 owner 保持，不新建、不关闭也不重放命令。新建会话、结束会话与“回到输入”均按一次生效：收起侧栏后再在下一帧恢复终端输入焦点，避免第一次点按只关闭侧栏不回到输入。会话行常规为单行：类型图标 + 名称 + 短状态胶囊 + 重命名/结束图标，完整状态保留在无障碍描述；< 320dp（含 224dp 抽屉）隐藏类型图标并把状态堆到名称下方。

**窄于 240dp 的抽屉**（手机 224dp 抽屉，以及更窄的分屏窗口）另有专用布局；宽度回到 ≥ 240dp 的内联侧栏或抽屉时全部还原：

- 底部动作组改用 dense 短标签（主界面/显示/键盘/粘贴/聚焦输入/说明），默认字体下 2 列 3 行、11sp、强制单行、固定 48dp 等高；完整动作名保留在 `contentDescription`。判据为纯逻辑 `TerminalWorkspacePolicy.narrowActions(widthDp) = widthDp < 240`，与手机抽屉 224dp 上限和内联侧栏 240dp 下限一致；大字体仍按实际宽度减列（最窄为 1 列 6 行），但始终单行等高。
- 摘要缩为“N 个会话”，完整 “N 个会话 · PTY n / 简易 n” 写入摘要 View 的 `contentDescription`；头部副标题缩为“独立会话”（en `Independent sessions`），完整 “独立会话 · 返回不结束进程” 写入副标题 View 的 `contentDescription`。宽侧栏继续显示完整文本。
- 横向统一 12dp 网格：头部、会话列表、页脚左右各 12dp，分组行水平内边距 0、空态水平外边距 0（12dp 由列表提供），页脚按钮外缘外边距 0、相邻按钮各留 3dp、上下各 3dp、内边距 3dp，使会话卡、新建按钮、分组标题、摘要与六个动作的左右边缘对齐且按钮之间不粘连。窄态切换会重渲染会话列表，宽态恢复原 14/8、6/10 与 8dp 分组内边距。

宽窗底部为 2×2 主操作加粘贴/回到输入一行，六个动作在宽窗的短窗/横屏仍以三列两行保留；窄抽屉在默认字体下保持 2 列（大字体按实际宽度继续减列），短标签与 12dp 网格只在窄态生效。

“新建会话”先选择 PTY 交互会话或简易按行会话；两种模式共用上方同一套会话侧栏。底部的“结束全部”是宿主尽力关闭入口：逐项复用 owner 的关闭与核验，确认框列出 PTY/简易数量并说明这不是返回页面；结果按“已关闭/正在结束/未确认”显示，未确认会话保留在列表中，不会调用维护用的 fail-fast 全量关闭，也不停止 Web 或其他业务。关闭请求在 owner 的单线程关闭队列中串行执行，每个会话最长等待 5 秒，因此 N 个会话最坏约 N×5 秒；界面持续显示进度。运行保护、单会话结束和“核验并结束”语义不变。

返回键顺序为对话框 → 可收起的侧栏（抽屉或极简内联）→ 终端选择/IME → 关闭页面；工作台常驻内联侧栏不占用返回层级。

选择保存在本机 `term_pty`；切换模式保留另一种模式的既有会话，并非把同一 shell 转换成另一种协议。两组标签由进程级 [TerminalSessionOwner](<../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java>) 持有，Fragment 只附着观察者；切页、旋转、语言重建和 Main 普通销毁均不会请求结束终端。应用进程被系统结束后不承诺恢复原 shell；旧 Bundle 的 ownerEpoch 不能认领新进程中的同号标签，不重放输入。

首次进入每种模式最多发起一次默认创建；取消、失败或所有标签已结束后再次进入保持空态，需用户明确新建。侧栏的“返回主界面”和返回键仅关闭页面，与“结束进程”分开。侧栏的打开意图随 Activity 保存并按新窗口形态重投影，但不会持久化会话或命令；关闭工作台常驻侧栏不通过返回键完成。

| 模式 | 适合的操作 | 实际行为与限制 |
|---|---|---|
| PTY | vim、htop、tmux、交互提示、逐键输入 | [PtySession](../app/src/main/java/com/deepseekharness/app/PtySession.java) 接 Termux terminal-emulator/JNI；附加键提供 Ctrl、Alt、Esc、Tab、方向键、翻页与控制字符，支持字号调整与缩放 |
| 简易 | 按行提交命令、查看文字结果 | 管道 shell，命令排队并串行发送；不模拟完整终端，需交互输入/TUI 时切 PTY |

### DSH CLI 入口

工作台顶栏有 48dp 的「DSH CLI」按钮（仅工作台显示）；「新建会话」选择器也有第三项 `DSH CLI`（极简模式经侧栏“新建会话”可达；不占用侧栏第 7 个 footer 动作，避免挤压 224dp 抽屉）。入口只创建一个普通 PTY 会话，但把 guest command 固定为 `/bin/bash -c <脚本>`：脚本先 `stty sane`，用 `dsh --version` 预检（失败只提示“dsh 不可用”，当前 shell 仍可用，不伪造成功），打印中/英 banner 后 `exec /bin/bash -l`。标签创建成功后重命名为 `DSH CLI`。

边界：内置 DSH 没有交互式 TUI，入口不自动运行 `dsh headless`，也不启动第二个 Web。banner 明确提示 `headless` 是一次性任务且默认不读取 GUI 保存的凭据（需自行提供环境变量或 `.env`），不要在终端启动 `dsh web`，也不要对 Web 正在使用的 profile 执行 `dsh plugin --profile web ...`。CLI 会话仍是 PTY：保护票、出生身份、关闭核验、全量结束与维护屏障完全复用，不新增 Kind、权限或 Manifest；环境未就绪时沿用 `terminalBlockMessage`，不创建半截会话。

固定 argv 由纯逻辑 [DshaCliPreset](../app/src/main/java/com/deepseekharness/app/util/DshaCliPreset.java) 生成，不插值用户输入、不读写凭据；[DshaCliPresetTest](../app/src/test/java/com/deepseekharness/app/util/DshaCliPresetTest.java)、[TerminalRuntimeTest](../app/src/test/java/com/deepseekharness/app/runtime/TerminalRuntimeTest.java)、[TerminalSessionOwnerTest](../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java) 覆盖 argv 形状、只替换命令尾部与保护票时序。真实 PTY/IME/关闭核验/维护屏障仍须正式 APK 设备记录；320dp 窄窗顶栏 4 个按钮与大字体选择器也需目视验收。

### 附加键（PTY）

底部附加键按设备宽度选择一套布局：`smallestScreenWidthDp < 600` 为手机，`>= 600` 为平板；两套布局分别保存在 `deepseekharness` 偏好的 `term_extra_keys_phone` / `term_extra_keys_tablet`。首次使用手机默认是 Termux 风格的 7+7（Esc / 斜杠 / 连字符 / Home / 上 / End / PgUp；Tab / Ctrl / Alt / 左 / 下 / 右 / PgDn），平板保留 8+8 的既有组合。

显示设置里的“快捷按键布局”打开编辑面板，也可以长按附加键区域进入。编辑器按手机/平板分别保存，支持从动作目录增删、替换、左移/右移；每行最多 10 个，空行在终端里隐藏，两个布局各自至少保留一个按键。动作目录包括 Esc、Tab、Ctrl、Alt、方向键、Home/End、PgUp/PgDn、`^C/^D/^Z/^L` 和常用符号；`Ctrl/Alt` 仍是一次性修饰键，选中后下一次按键生效并清除。

按键文本在窄屏或大字体下自动缩字、单行不折行；单行宽度超过可用区域时横向滚动，行高固定 48dp。保存后只重建当前 PTY 视图，不新建、不切换会话，也不改变进程；简易模式没有附加键，编辑结果只作用于 PTY。设备宽度类别变化（旋转、分屏、外接屏）会按新形态重选布局。模型与单测见 [TerminalExtraKeys](../app/src/main/java/com/deepseekharness/app/util/TerminalExtraKeys.java)、[TerminalExtraKeysTest](../app/src/test/java/com/deepseekharness/app/util/TerminalExtraKeysTest.java)；编辑面板的触摸、RTL、TalkBack 与 IME 避让仍需真机验收。

入口实现为 [PtyTerminalFragment](../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java) 与 [TerminalFragment](../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java)。新标签使用当前环境准备与运行方式；已有标签继续持有出生时的进程身份。运行方式的选择与兼容边界见[运行引擎](runtime-engines.md)。

## 会话、编号与命令状态

[TerminalTabs](../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java) 的内部 ID 永久递增，显示编号复用已成功关闭的空缺。关闭中或关闭失败的标签继续占号；不能根据显示编号向新会话投递旧回调。PTY 与简易模式各自保存标签与当前选择。

简易终端的 [TerminalSession](../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java) 状态为 `STOPPED → STARTING → READY/BUSY → STOPPING`，失败进入 `FAILED`。启动握手先核验固定 bootstrap 的 PID、会话与出生身份，才允许发送用户命令。尚未发送的命令可等待就绪；初始化超时不表示执行过用户命令。

“中止”会终止本次简易 shell 的进程组，确认退出后创建新 shell；`cd`、`export` 等 shell 内状态随之清除。尚未发送的命令保留，已经发送的命令不会自动重放。部分写入失败属于结果未知，界面提示先核查结果，不能以“重试”自动重复可能有副作用的操作。关闭标签会取消未发送队列。

简易输出经过 ANSI 清理和敏感文本脱敏；缓冲超过 300000 字符时保留后 100000 字符。这只是界面输出上限，不是完整命令审计归档。单条简易命令按 Java 字符串长度限制为 16384，空值及 NUL 被拒绝。

## 关闭、维护与失败

“结束进程”先明确确认目标类型与 ID，标记 `CLOSING`，再在工作线程等待退出。只有 [TerminalSessionOwner](<../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java>) 核验成功才移除标签；失败持久保存 `UNCONFIRMED`、脱敏错误及原 ID，停止接受新输入，不自动重复结束请求。用户可显式选择“核验并结束”。PTY 自然退出后经真实清理核验可释放保护但保留历史；仅启动器退出仍展示清理待核验，不能画成成功。环境维护仍先关闭 PTY（含未发布 pending）及简易会话，再停止 Web 并取得维护屏障；遇错即止。

### 独立后台保护

[TerminalRuntimeService](<../app/src/main/java/com/deepseekharness/app/TerminalRuntimeService.java>) 与 Web 前台服务独立。可见页面请求新建，只有真实 `startForeground` 成功、精确 `(ownerEpoch, kind, ticket)` 被一次性消费后才允许创建。准备、fork、运行及未确认清理均持有逻辑票；视图离开不释放。服务丢失展示 `LOST`、拒绝新输入，不暗中重新提升、不重放 shell；显式结束仍可核验。通知不是退出证明，也不保证 ROM 永不回收。

PTY 在底层核验 guest 为空后释放保护。简易标签的 `STOPPED/FAILED` 不能证明整个生命周期已清理，保守保留至用户显式结束核验成功；该状态与“页面已返回”不同。原有 RuntimeTasks 维护与 CPU 锁策略保留，本次没有新增整段 CPU 锁或宣称功耗改善。停止 Web 不改变终端服务原因。

[TerminalProcessCloser](../app/src/main/java/com/deepseekharness/app/runtime/TerminalProcessCloser.java) 使用保存的进程身份与会话关系检查退出。不能把 `/proc` 暂时不可读、超时或启动器退出等同于整个 guest 已退出；核验失败继续保留环境占用，不按裸 PID、端口或名称强杀。现用环境不能在该屏障未解除时移动或重建。

终端、插件和 DSH 处于应用的 Android UID 运行域。原生终端允许执行 shell；设备桥的命令白名单不构成终端的强沙箱。输入命令前应按真实工作目录和权限判断影响，详见[安全模型](security-model.md)。

## 核验入口

修改后至少选择与变更相关的 [TerminalTabsTest](../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java)、[TerminalSessionTest](../app/src/test/java/com/deepseekharness/app/util/TerminalSessionTest.java)、[TerminalSessionOwnerTest](../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java)、[TerminalRuntimeTest](../app/src/test/java/com/deepseekharness/app/runtime/TerminalRuntimeTest.java)、[TerminalExtraKeysTest](../app/src/test/java/com/deepseekharness/app/util/TerminalExtraKeysTest.java)、[TerminalWorkspacePolicyTest](../app/src/test/java/com/deepseekharness/app/util/TerminalWorkspacePolicyTest.java)、[DshaCliPresetTest](../app/src/test/java/com/deepseekharness/app/util/DshaCliPresetTest.java)。它们分别覆盖编号/关闭、队列与未知结果、所有权和启动输入、抽屉宽度与侧栏形态、CLI 固定 argv；测试源码存在不表示本轮已重新执行。真实 PTY、IME、ROM `/proc` 行为仍须精确正式 APK 的设备记录。
