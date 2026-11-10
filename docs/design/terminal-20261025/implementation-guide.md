# DSHA 全屏终端改造实施指引

对应[整合报告](<report.md>)与[HTML 交互预览](<preview.html>)。基线：`Sunset-zhongdua-G` / `02f80abeee37f3b107544714c8544ada5a1c40b9`。

**本文是下一阶段的指令性方案，不表示已经执行。** 本轮只新增设计文档、预览和验证材料，不修改 Android 生产实现。所有拟新增类名均为建议名称。

## 第二轮补充：官方源码参考与主壳原型的落地边界

截至 2026-10-25，已获取 Termux 固定 commit `8629e632fcb95da272221be327db653fb24befe9`，并补齐 HTML 的启动、插件、设置主 Tab。详见[本轮整合报告](<report.md>)、[Termux 静态评审](<termux-source-review.md>)、[三个主 Tab 的源码映射](<main-tabs-source-map.md>)。本指南下文仍是**未实施的 Android 改造计划**，不能因为 HTML 已可操作就跳过原生合同与验收。

### 先利用原型冻结信息架构，不直接移植 HTML

1. 四个入口顺序保持启动 / 插件 / 设置 / 终端。启动操作管理 Web，终端入口管理页面；普通重启恢复普通模式，安全启动是显式分支。
2. 插件的“市场”是来源与安装入口，不是已联网推荐列表。已安装信息从真实管理接口取得；未知列表不是 0 条。名称/版本来自 manifest，启用/加载/更新来自运行回执，不混成一个绿色状态。
3. 配置保持现有 proot/proroot、自动端口、DNS/LAN、显示/PiP、兼容参数合同；手动端口使用现有 `ConfigInput.port()`，不是网页自创规则。运行实例的实际端口与下次启动的配置分开。
4. 一键备份保持应用数据+当前应用内工作区的固定流程；范围选择属于更多备份选项。生产写入/恢复继续经原来安全停止、完整性核验、预检和事务，不以原型确认替代。
5. 配置与插件筛选跨根 Tab 保留是设计增强，实施时使用明确 UI 状态模型；不让 Fragment 重建/滚动恢复意外触发保存、安装、重复启动。区分已保存偏好、未保存草稿与运行中的快照。
6. 主壳中的系统字体/平板双列是 HTML 设计建议；生产应复用主题与文案资源，不能硬编码一套网页颜色或把每个 CSS 像素当 dp。终端等宽输出仍交 TerminalView。
7. 原型测试台允许未准备/维护时浏览静态页面，只为评审；Android 实施必须保留原来的首次准备和环境恢复门禁，不按原型放宽。主页面的终端快捷卡和终端显示捷径需单独确定是否纳入生产。
8. HTML 只演示语言选择摘要、更新/授权/配对等边界，不提供实际功能；不要把这些说明面板当作已完成的 Android 子页实现。

### Termux 取用规则与下一阶段优先项

- 固定上游 commit、逐文件检查许可。App 和 shared/termux 额外键默认 GPL；不能将其 Java/XML 翻成 JS/Java 并仅加署名当作许可问题已解决。当前 Apache 终端依赖维持原版本，本轮不升级。
- 借鉴内容优先、按需会话目录、输入模式与焦点分工、Activity 解绑而不销毁会话的职责。保留 DSHA 的出生身份/guest退出核验/维护屏障，不复制上游 kill-all、退出码自动移除、singleTask 或固定 IME margin workaround。
- 下一阶段优先做：Main 销毁解耦 + terminal-only 保护 → 明确 pending/closing/unconfirmed → 输入焦点与 IME → 重命名/额外键体验。此顺序先保障正确性再扩充交互，不反过来。
- 本轮主壳演示经[验证记录](<validation.md>)验收；它不证明原生 IME、TUI、后台保活、真实插件/备份或系统授权。

---

## 1. 先冻结六条不可破坏的合同

1. `返回页面 / onPause / onDestroyView / 宿主 onDestroy` 不得调用 PTY finish、shell shutdown、Ctrl+D、`exit` 或 `cancelAndRestart()`。
2. 页面必须绑定 `TerminalSessionOwner.shared()`；不能用 Activity/Fragment 生命周期重新创建 owner。
3. 结束单个会话必须绑定 `(PTY|SIMPLE, id)`，调用现有 owner 关闭核验；只在确认退出后移除条目。
4. `isRunning()==false` 只说明启动器/当前会话观察，**不自动等于 guest 清理已确认**；未知状态继续保留保护。
5. 环境维护仍先关闭终端、关闭 Web、取得维护屏障；不以界面关闭绕开任何一步。
6. UI 更新不改变终端内核、proot/proroot 选择、运行资产摘要、生产数据格式、现有偏好键或设备授权策略。

参考当前合同：[终端说明](<../../terminal.md>)；生命周期事实：[owner](<../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L10-L23>)、[PTY 退出核验](<../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L168-L203>)、[维护屏障](<../../../app/src/main/java/com/deepseekharness/app/util/MaintenanceGate.java#L79-L118>)。

## 2. 分层与文件级改动清单

| 范围 | 当前文件或拟新增模块 | 指令 |
|---|---|---|
| 一级入口 | [MainActivity](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L350-L413>) | 抽统一 `openTerminal()`；点击终端为 action，不替换主容器；保留此前 nav 选中 |
| 外部意图 | [MainActivity:625–640](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L625-L640>) | `open_terminal` 经同一个路由和门禁，只消费一次，不能返回后循环弹出 |
| 旧页恢复 | [MainActivity:89–158](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L89-L158>) | 处理旧 saved state 恢复在主容器中的终端 Fragment；先解绑/移除旧视图后导航，不能双宿主订阅或重复新建 |
| 主宿主退出 | [MainActivity:956–969](<../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L956-L969>) | 将终端 shutdown 从 UI 销毁迁出；先具备明确关闭入口/运行保护，不能仅删代码即宣称完成 |
| 新宿主 | 拟新增 `ui/TerminalActivity.java` | 普通同 task 非导出 Activity，专属 Insets、Back、已保存工作台状态；不拥有进程生死 |
| 注册 | [AndroidManifest](<../../../app/src/main/AndroidManifest.xml#L87-L97>) | 仅注册新宿主，`exported=false`、`adjustResize`；不加 launcher、独立进程、affinity 或 NEW_TASK |
| 页面展示 | [PtyTerminalFragment](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L119-L249>)、[TerminalFragment](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java#L67-L171>) | 迁出重复导航控件，保留绑定、输入、脱敏、节流和空态；把私有操作按窄接口交给工作台 |
| 外壳布局 | [PTY 布局](<../../../app/src/main/res/layout/fragment_pty_terminal.xml>)、[简易布局](<../../../app/src/main/res/layout/fragment_terminal.xml>)、[标签条](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalTabBar.java>) | 重做黑底工作台、手机抽屉和平板侧栏；旧组件稳定后才删除，不删内核组件 |
| 显示状态 | 拟新增 `TerminalPresentationState` / `TerminalWorkspaceController` | 投影 mode/id/number/title/process/cleanup；View不拿到可任意删标签的集合 |
| 关闭状态 | [TerminalSessionOwner](<../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L94-L184>) | 适量增加持久到进程内的关闭结果快照；统一异步关闭通知，不改变底层退出核验算法 |
| 运行保护 | 拟新增 `TerminalRuntimeProtection` 协作者，必要时接 [HarnessService](<../../../app/src/main/java/com/deepseekharness/app/HarnessService.java#L120-L154>) | 单独终端原因/租约；可见启动、失败状态、通知、最终退出退休；不要塞进 HarnessController 巨类 |
| 偏好与语言 | [PTY 偏好](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L46-L52>)、[文案目录](<../../../tools/i18n/messages.json>) | 保留 `term_pty`、`term_font_px` 等旧键；后者名字虽 px，当前实际按 sp 配置，不能擅改单位 |
| 内核/运行资产 | [PtySession](<../../../app/src/main/java/com/deepseekharness/app/PtySession.java>)、[TerminalRuntime](<../../../app/src/main/java/com/deepseekharness/app/runtime/TerminalRuntime.java>) | 本期不替换，必要的状态观察只读接口单独审查；不改 JNI 与 rootfs |

## 3. 导航实现步骤

### 3.1 统一路由

以下是**伪代码**，不是可以直接复制编译的补丁：

```java
onMainItemSelected(item) {
    if (item.id == NAV_TERMINAL) {
        terminalNavigator.openFromVisibleMain(); // 必须复用现有准备/恢复门禁
        return false;                           // 保留来源 nav 选中及内容
    }
    return navigateExistingMainPage(item);
}

consumeOpenTerminal(intent) {
    if (!intent.hasOpenTerminal()) return;
    intent.consumeOpenTerminal();
    terminalNavigator.openWhenMainReady();       // 一次意图；不在后台强行拉起
}
```

要求：

- 同一入口同时被点击/通知触发时只打开一个宿主；不能只在启动前查一次顶层 Activity 就假定无竞态。
- 不调用新建 session 作为“打开页面”的通用副作用。仅保留既有“该类型从未初始化过时默认新建首个”的策略，所有标签手动关闭后保持空态。
- 原生宿主 route 的 in-flight 状态在成功 resume、明确拒绝/失败时释放；配置重建、进程死亡不自动重复创建用户 shell。
- 旧 Main 恢复出 PTY/simple Fragment 时明确兼容迁移；会话 owner 不变，先解除旧观察者，避免旧 view 覆盖新回调。
- `BrowserTasks` 当前有 Main/Web 返回协调；先复用主任务策略，不把 Web/PiP 的专属 `taskAffinity` 和 singleTask 模式复制过来。

### 3.2 返回和状态保存

`TerminalActivity` 保存的是：模式、选中的复合会话键、抽屉开合、字号/配色偏好、用户回看位置等 UI 状态；保存 Bundle 不等于保存进程。

建议为当前进程内 owner 增加 `ownerEpoch`（仅用于宿主/协作者身份，不替代 PTY 出生身份）。保存状态和回调绑定 `(ownerEpoch, kind, id)`，创建/关闭票另带 operation generation。

同进程重建时，只有 epoch 一致且 owner 仍包含该 ID 才绑定；不存在则展示当前有效选择/空态。新进程中 next 会从 1 开始，即使先建了同类型同 ID 的标签，也不能让旧 Bundle 认领它。epoch 不匹配时显示旧会话不可恢复，禁止自动输入或重放历史命令。

返回应委托临时层/选择态/IME 后才关闭工作台。硬键盘 Esc 仍送给终端。收键盘与 finish 不能在同一 Back 事件被重复执行；覆盖 Android 预测返回手势完成/取消。

## 4. 生命周期与运行保护实现步骤

### 4.1 先把状态建对

当前 [TerminalTabs](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java#L48-L62>) 只有 `closing`；[owner 的关闭异常](<../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L106-L146>) 会把它复位，Fragment 主要 Toast。不要把下列新增状态描述为已存在。

建议分开两条状态轴，而不是用一颗绿点：

```text
ProcessState: NOT_STARTED | STARTING | RUNNING | EXITED | UNKNOWN
CleanupState: NOT_REQUESTED | CLOSING | VERIFIED | UNCONFIRMED
ProtectionState: PREPARING | ACTIVE | LOST | RELEASED
SimpleBackendState: 原有 STOPPED / STARTING / READY / BUSY / STOPPING / FAILED 投影

SessionKey: (ownerEpoch, kind, id)
OperationTicket: (SessionKey或创建票, operationGeneration)
展示补充: number、脱敏标题、退出码（若已知）、启动/关闭错误摘要、可允许的操作
```

- FGS 拒绝或明确 fork 前失败：`NOT_STARTED` + 错误摘要，可释放精确准备票；fork 结果未知则 `UNKNOWN + UNCONFIRMED`，不能标成 VERIFIED。简易 FAILED 不能直接折算成进程已退出。
- `EXITED` 只表示已观察/核验的 launcher 或 shell 退出，不代表整个 guest 会话已空。启动器退出但 guest 未确认：`EXITED + UNCONFIRMED`，可查看输出/诊断，不释放维护保护，不显示“已安全关闭”。
- 普通关闭失败：保留同 ID、编号和错误；只有用户重新明确选择核验/关闭才再次操作，不自动重发业务输入。
- 新建未成功发布标签但已 fork 的 PTY 也受保护；运行租约不能仅用可见标签计数。现有底层 [pending 集合](<../../../app/src/main/java/com/deepseekharness/app/PtySession.java#L58-L66>) 和 [维护覆盖](<../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L156-L160>) 提醒了此边界。
- 关闭操作移到专门协作者/工作线程，结果经 owner 的状态投影通知页面；宿主离开不取消已获用户许可的关闭，也不能把旧结果更新到新会话。

### 4.2 引入终端运行保护而非偷用 Web 状态

建议实现顺序：

1. 可见页面请求新建前，获得终端运行准入票据；Android 权限/前台服务失败返回可理解错误。
2. 真实 promotion 成功后才开始创建流程，创建期间持有准备租约；取消或明确未创建时释放，未知保留核验路径。
3. 将成功或未确认的创建转为运行/待清理租约；onPause/onDestroy 不释放。
4. 现有 RuntimeTasks 工作屏障仍保留，不把前台通知当退出证明。
5. 最后一个真实受保护终端已核验退出后释放终端原因；若 Web 仍运行则保持 Web 服务原因，反之亦然。
6. `START_STICKY` 只能恢复服务协调/状态核验，不能自动回放历史 shell 命令或“复活”已结束会话。

可选择独立前台服务或共享服务中的多原因租约。共享时必须同时覆盖 [服务退休](<../../../app/src/main/java/com/deepseekharness/app/HarnessService.java#L280-L337>)，并测试“停 Web 但终端仍运行”。**不得修改 stopWeb 的业务作用范围以图省事。**

**promotion 后保护丢失必须有单独策略：** `serviceDestroyed`、系统撤销或租约失效时，逻辑 cleanup lease 保留不等于物理 FGS 仍有效，投影应变为 `ProtectionState.LOST` 并告知风险。创建前丢失保护应拒绝创建；fork 过程中或已经运行的会话只允许精确票据/代次接收结果，不偷偷重建、不暗中扩权调用 stopWeb。已 fork 后是继续运行但提示降级、还是经明确用户授权关闭，需要产品先冻结；未确认清理仍保留逻辑屏障。补迟到 promotion、取消后回调、准备/fork/运行/关闭未确认四阶段保护丢失的测试。

CPU 锁、电量策略是否按空闲 shell 整段持有，建议另立能耗评估；本轮不声称已测耗电，也不因 UI 不可见就释放保护。Android/ROM 对后台运行的限制应面向用户如实提示。

### 4.3 三种关闭必须分别验收

| 操作 | 生产调用语义 | 验收点 |
|---|---|---|
| 结束单终端 | 指定 owner closePty/closeSimple | 核验成功才移除；别影响其他会话 |
| 停止 Web | 既有 controller.stopWeb | 不升级为全部终端关闭；终端保护不随 Web 退休 |
| 环境维护 | [MaintenanceCoordinator:118–132](<../../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java#L118-L132>) | closeAllAndConfirm→stopWeb→fence；失败不移动或覆盖环境 |

拟新增“结束全部终端”必须清楚列出影响数量并确认，复用 owner，不自行遍历裸 PID。**当前 closeAll 是 fail-fast：首个异常就中止，PTY pending 失败也会阻止后续 simple 处理。** 界面展示“已关闭 / 未确认 / 尚未尝试”，维护仍遇错即止；若要尽力关闭其余会话，应单列宿主协调行为变更，不吞底层异常、不虚报全部已结束。不要新造模糊的“退出应用”同时暗停所有业务。

## 5. 响应式、输入与可访问性规范

### 5.1 布局

- Android 按**当前窗口 dp** 判断断点；原型 `840 CSS px` 只代表宽屏常驻侧栏的设计基点，不作为最终设备像素阈值。
- 根据用户追加 Termux 实图，**默认极简模式**在所有窗口均收起目录、无常驻应用标题栏；入口使用可拖动吸边的页面内悬浮球，保留系统返回。**可选工作台模式**：compact `<600dp` 抽屉，medium `600–839dp` 仍抽屉，expanded `>=840dp` 在满足内容最小宽度时显示侧栏。窗口高度不足时收起非关键说明。
- 侧栏约 240–280dp，终端区域 `minWidth=0` 可缩放，不用固定 80 列硬裁；TerminalView 按最终可用像素和字号重算 cols/rows。
- 附加键 48dp 可点击，窄屏允许横滚；空态、菜单、大字体对话框可滚动。
- 默认应用内容满屏，系统栏安全区照常保留。沉浸模式为可选设置，不作为进入页面必做系统权限动作。
- 平板切手机、横竖切换、分屏拖拽时，保留 SessionKey 和后端对象，只调尺寸。

### 5.2 悬浮球入口（后续讨论修订）

- 用终端宿主布局里的普通 View/FAB 实现，不用 `WindowManager.TYPE_APPLICATION_OVERLAY`，不申请 `SYSTEM_ALERT_WINDOW`，不复用 AI 助手跨应用悬浮窗。离开页面解绑球的观察和定时器，不影响终端 owner。
- 建议52dp触控圆球（最小48dp），默认右侧约60%高度；原型是52 CSS px。保留整颗球，不半藏到屏幕外，避免挤占系统返回手势区。
- 点击打开会话/返回菜单；拖动超过 touch slop 后仅移动，抬手吸附最近左右边缘，吞掉拖动附带的 click。`ACTION_CANCEL`、失去捕获、旋转时收束到合法位置，不误触菜单或终端输入。
- 拖动监听只挂球，不能覆盖整个 TerminalView 去拦选字/滚动/鼠标报告。点击菜单仅导航，关闭会话仍必须明确选择并走原 owner 核验。
- 用窗口 bounds、system bar/cutout、IME 和附加键位置计算安全矩形；转屏/分屏/字体变化重约束。在输入行附近优先上移或下移避让；无法保证不遮挡任意输出，应允许手动拖走或切工作台，而不是改变 PTY 行列来为悬浮球强留空。
- 闲置约2.5秒降低透明度，聚焦/交互恢复，不完全隐藏；减弱动画跟随系统偏好。不将降低透明度解释为会话已结束或任务状态。
- TalkBack描述“终端菜单”，提供不依赖拖动的移动操作或位置重置；键盘聚焦球后方向键移动、Enter/空格开菜单，球之外键盘仍交终端。关闭菜单回球焦点；回到输入时显式恢复终端焦点，避免按键被菜单吞掉。
- 原型仅当前文档保存 `停靠侧 + 归一化高度`，刷新归零；生产是否记住偏好可单独确定，不能使用固定像素导致横竖屏越界。布局状态不得携带终端命令或冒充会话恢复。
- 验收补鼠标/触摸拖动、取消、点击阈值、吸边、低高度+键盘、横竖切换、左/右系统返回手势、TalkBack与菜单焦点、回主页面球消失、原会话不关闭。

### 5.3 输入

- 原生 PTY 继续 `TerminalView` 接 IME；不要再叠一个 EditText 充当第二输入源。
- 组合字符未提交前不得逐字拆散送入；验证现有 `shouldEnforceCharBasedInput()` 与目标输入法，不凭注释认定全部兼容。
- Ctrl/Alt 默认一次性或明确锁定；视觉态与实际发送必须一致。会话切换/失焦清除，硬键盘抬键正确释放。
- 后续若重构扩展键，用终端当前模式映射方向键/功能键，不把普通光标序列无条件用于 application-cursor 模式。
- 粘贴前显式展示来源与多行提示，不默认附回车；遵守 bracketed paste 与敏感数据策略。
- 文本选择、长按菜单、回滚历史、滚到底部按钮保留。不要将每条输出置为 TalkBack live region 导致持续抢读。
- 非中文文案从统一十语目录生成；终端输入/输出不自动翻译。RTL 只作用外壳，终端等宽文本仍保持适当 LTR 语义。

## 6. 实施顺序与退出条件

### 阶段 A：合同与测试先行

- 确认报告中的四个产品选择；加入返回/结束状态表。
- 复核旧版 UI 各路径：首次进入、所有会话关闭、环境未就绪、主题重建、维护失败。
- 建立导航纯逻辑测试、终端保护租约测试、关闭结果投影测试。
- **退出条件：** 可以用测试区别 page-dismiss、session-close、web-stop、maintenance-stop。

### 阶段 B：新宿主与入口

- 同 task `TerminalActivity` 接原 Fragment，先不换样式。
- Main 点击和 `open_terminal` 统一路由；兼容旧 saved state。
- 将 Main 销毁 shutdown 解耦，接明确关闭入口与运行保护。
- **退出条件：** 返回、重进、旋转、关闭 Main 的非配置销毁不误杀；单独终端运行保护成立。

### 阶段 C：UI 工作台

- 默认极简、可选工作台；原生工具条/按需会话目录、两排附加键与简易模式统一外壳。入口改为可拖动吸边的页面内悬浮球；原型中常驻“DSHA·仅模拟”标记不属于生产日志内容，不要求系统悬浮窗权限。
- 加空态、启动错误、关闭未确认/诊断操作。
- 搬迁而不是丢失历史绑定和脱敏逻辑。
- **退出条件：** 手机/平板布局、触控、大字体、IME 下全部关键操作可达。

### 阶段 D：验证与收尾

- 跑定向单测、Standard Lint/构建及精确 APK 检查；不跑 Low、不发布。
- 经用户另外批准后再做真机验收；不把本指引视为设备授权。
- 保留实际失败与修正补跑记录；按精确 APK SHA 归属证据。
- **退出条件：** P0/P1场景通过或明确阻断；未验设备范围如实标明。

## 7. 测试矩阵

### 7.1 当前已有测试（存在不代表本轮执行）

- [TerminalTabsTest](<../../../app/src/test/java/com/deepseekharness/app/util/TerminalTabsTest.java>)：递增身份、空缺编号、选择、关闭占号。
- [TerminalSessionOwnerTest](<../../../app/src/test/java/com/deepseekharness/app/core/TerminalSessionOwnerTest.java>)：失败关闭保留、旧 observer 不拆新 observer。
- [TerminalSessionTest](<../../../app/src/test/java/com/deepseekharness/app/util/TerminalSessionTest.java>)：简易队列/部分写入未知/取消和关闭。
- [TerminalRuntimeTest](<../../../app/src/test/java/com/deepseekharness/app/runtime/TerminalRuntimeTest.java>)：运行参数合同。
- [TerminalReadyTest](<../../../app/src/test/java/com/deepseekharness/app/util/TerminalReadyTest.java>)：启动就绪握手。

在具有项目规定 JDK/SDK/受管资产的构建环境中建议执行（**本轮未执行**）：

```bash
bash build.sh :app:testStandardDebugUnitTest \
  --tests 'com.deepseekharness.app.util.TerminalTabsTest' \
  --tests 'com.deepseekharness.app.util.TerminalSessionTest' \
  --tests 'com.deepseekharness.app.core.TerminalSessionOwnerTest' \
  --tests 'com.deepseekharness.app.runtime.TerminalRuntimeTest' \
  --tests 'com.deepseekharness.app.util.TerminalReadyTest'

# 加入并执行新增导航/状态/租约测试；随后执行标准版 Lint 和项目精确APK门禁。
bash build.sh :app:lintStandardRelease
```

构建依赖/运行资产缺失时应报告缺项，不从设备已安装 APK 反向推断完整源码输入。

### 7.2 本次改造必须新增的用例

| 编号 | 场景 | 断言 |
|---|---|---|
| NAV-01 | 设置/插件子页→终端→返回 | 返回原入口约定位置；原输入/滚动不被重建覆盖；无 shutdown |
| NAV-02 | 连点终端 + 同时 open_terminal | 仅一个宿主，无重复 shell，无重复 observer |
| NAV-03 | 旧 saved state 恢复终端根页 | 旧视图先解绑；不在 Main 和新宿主同时绑定 |
| NAV-04 | 抽屉、选择、IME、Back 手势取消/完成 | 按优先级退出，无 Esc/exit 注入，无一次返回退两层 |
| LIFE-01 | 旋转、语言、主题、Main 非配置销毁 | 同进程 owner 对象、SessionKey 不变，不发送关闭信号 |
| LIFE-02 | Web 未启动，仅终端，返回/Home/息屏 | 有真实运行保护与通知；拒绝路径明确；不伪称永久常驻 |
| LIFE-03 | Web+终端并存→停止 Web | Web 停止；终端租约和会话不因服务退休丢失 |
| LIFE-04 | 已 fork 未发布、关闭失败、guest 未确认 | 保留保护和状态；不依赖可见标签数退休服务 |
| LIFE-05 | 进程死亡后重新进入 | 不伪装恢复、不自动重放、不自动复用旧ID发送输入 |
| CLOSE-01 | 关闭 A 后切 B，新建复用 A 的显示号 | A 的异步结果不影响 B/新会话；真实 ID不复用 |
| CLOSE-02 | 全部关完→返回→再进 | 空态不自动新建；按钮仍可新建 |
| CLOSE-03 | 维护时终端退出失败 | 环境路径未移动、数据未覆盖、屏障保留 |
| INPUT-01 | 拼音/日文/Emoji/中英文混排 | 一次提交一次输入，组合态不丢，宽字符显示合理 |
| INPUT-02 | 硬键盘Enter、Ctrl+C/D/Z、Alt、Esc、方向键 | 不重复发送，不粘修饰键；导航和ESC独立 |
| INPUT-03 | 多行/带尾换行/控制符粘贴 | 不静默执行；正确 bracketed paste 与确认边界 |
| TUI-01 | 已安装的 vim/tmux/top，旋转/键盘/字体变化 | cols/rows随可用区改变；无错位、卡旧尺寸 |
| UI-01 | 320/360dp短屏、840/1024dp平板、分屏拖动 | 无关键按钮被裁、无固定机型判断、会话不复制 |
| A11Y-01 | TalkBack、1.3/2倍字体、RTL、日夜 | 触控可达、文案不截关键动作、非颜色状态可识别 |
| PRIV-01 | 敏感标题/输出、退出日志、导出/粘贴 | 保留当前脱敏；无新增命令正文采集或隐式网络调用 |

### 7.3 审查发现的现有调试夹具风险

- [TerminalSessionSelfTest 的 OwnedBackend](<../../../app/src/debug/java/com/deepseekharness/app/ui/TerminalSessionSelfTest.java#L178-L260>) 未转发生产 backend 的 `onReady`，而实际后端借此登记出生身份；应先修测试代理的委托完整性，再把它用作取消/关闭链证据。
- [MultiTerminalLanguageAudit:355–360](<../../../app/src/debug/java/com/deepseekharness/app/ui/MultiTerminalLanguageAudit.java#L355-L360>) 含旧包名启动组件；改为当前上下文/包名获取，避免测错应用。
- 以上为静态审查提示，未执行这些调试入口，不能直接宣称运行失败或修复完成。

## 8. 给后续实现者的可复用任务指令

> 在 `Sunset-zhongdua-G` 指定基线或其经确认的新基线上，实现原生 Termux-inspired 全屏终端工作台。先复核现有终端合同及工作树，按本指引分层实施。保留 Termux TerminalView、PTY/JNI、运行引擎、身份核验、维护屏障和生产数据。
>
> 将主一级终端入口和 open_terminal 意图统一到同 task、同进程、non-exported 的宿主；返回只关闭页面。修正 Main.onDestroy 的终端 shutdown 耦合，同时完成明确关闭范围与 terminal-only 运行保护；不得将 stopWeb 偷换成全局停止。PTY/简易用复合会话键，关闭未确认必须可见并保留保护。
>
> 手机抽屉、平板侧栏按当前窗口宽度响应，保留原生输入及安全边界。先写和跑导航/状态/租约测试，再迁移外壳，稳定后才删旧渲染布局。不运行 Low、不改运行资产、不清数据、不提交/发布/操作设备，除非用户另行明确要求。产出变更清单、真实测试结果、未验范围和回滚办法。

验收交付不能只附截图；必须同时有导航与关闭语义测试、实际构建记录以及经授权的设备行为证据。

## 9. 2026-10-26 增量：三向态与快捷按键

### 9.1 三向态侧栏（HTML 已定稿，原生待确认）

- 颜色合同与 token 见 [native-terminal-theme-plan.md](<native-terminal-theme-plan.md>)；浅色 `terminal_panel` 用白/浅蓝灰，深色用近黑，`terminal_surface` 与 `terminal_foreground` 保持固定黑底。
- 必须先拆出 `terminal_panel_text`，不能把 `terminal_foreground` 直接改成浅色；否则工具栏/会话行白底白字。
- 底部动作**保留现有 6 个**（返回、键盘、显示设置、粘贴、回到输入、说明），不因 HTML 预览的 2×2 删除粘贴/回到输入；短窗按 `denseFooter` 退回三列。
- 主题切换在终端内需禁用/保护 `closeAllRunning` 的瞬态 UI；Activity 重建后再刷新视图颜色。

### 9.2 快捷按键（已实现）

文件：

| 文件 | 职责 |
|---|---|
| [TerminalExtraKeys.java](<../../../app/src/main/java/com/deepseekharness/app/util/TerminalExtraKeys.java>) | 纯 JVM：手机/平板 profile、默认 7+7/8+8、27 个动作、编解码/上限/去重、中英文 label/description |
| [ExtraKeysDialog.java](<../../../app/src/main/java/com/deepseekharness/app/ui/ExtraKeysDialog.java>) | CardSheet 编辑面板：双 profile、两行 Chip、增删替换排序、恢复默认、保存/取消 |
| [PtyTerminalFragment.java](<../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java>) | 单行等宽网格、自动缩字、48dp 行高、横向滚动、动作映射、保存后刷新、长按入口 |
| [bg_terminal_key.xml](<../../../app/src/main/res/drawable/bg_terminal_key.xml>) | 按键底色/描边/涟漪/修饰键选中态 |
| [TerminalActivity.java](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java>) | 显示设置第五项“快捷按键布局” |
| [TerminalPane.java](<../../../app/src/main/java/com/deepseekharness/app/ui/TerminalPane.java>) | `refreshExtraKeys()` 默认空实现，PTY 覆盖 |

合同：

- 设备分类用 `smallestScreenWidthDp >= 600`，手机/平板分别保存，不按机型名判断。
- 持久化沿用 `deepseekharness`：`term_extra_keys_phone`、`term_extra_keys_tablet`；格式 `ACTION,ACTION|ACTION,...`，只接受 catalog 内动作；损坏/空值回默认。
- 渲染固定 48dp 行高、单行、8–13sp 自动缩字；每行键数超过可用宽度时横向滚动；不折行、不裁字。
- 保存只刷新 PTY 附加键，不重建/切换会话；编辑面板本身不拥有会话或进程。
- 简易模式没有附加键；`refreshExtraKeys()` 默认 no-op。

### 9.3 必须补的验证

| 范围 | 断言 |
|---|---|
| 纯模型 | 19 项 JUnit（已本地通过）：边界、默认、往返、非法回退、上限/去重、catalog 文案 |
| 编译/资源 | **已通过**：CI run 40282161255 的 source contracts、Lint、assemble、全量 2545 项与 APK 校验；本机无 Android SDK，未本地重编 |
| 布局 | 320/360/390/600/840dp；1.3×/2× 字体；不折行、不横溢出、每键 ≥44dp、可点 |
| 交互 | 长按/点按打开面板；双 profile 草稿不串；增删替换排序；每行满 10；两个 profile 各至少 1 键；保存即时刷新 |
| 进程 | 保存只刷新视图，不新建/切换/结束会话；无输入注入 |
| 设备 | 手机/平板横竖屏、分屏、IME、RTL、TalkBack、浅/深色；未验前不得写成设备通过 |
