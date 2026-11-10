# 任务状态上岛（Android 16 实时活动）

> build209 起上岛能力已并入正式包，不再有独立上岛包（`dsha.islandEdition`、`.island` 包名与 `BuildConfig.ISLAND_EDITION` 均已删除）。本文其余内容保留 build205 历史实现记录。

核验日期：2026-10-27。参考基线：build205 源码（`0.2.1-rc.3-20261027.1312`）。2026-11-08 起补记支持矩阵与回答链路，补记不改变原核验范围。

本页说明 DSHA 如何把「任务正在执行」的阶段性状态显示在系统状态栏胶囊 / 灵动岛 / 流体云上，
以及它为什么不需要 root、不需要白名单，什么情况下会静默退回普通通知。

## 1. 能力与边界

| 项 | 事实 |
|---|---|
| 依赖的系统接口 | Android 16（API 36）起的 Live Updates（promoted ongoing） |
| 需要 root / LSPosed | **不需要** |
| 需要厂商白名单 | **不需要**（小米官方大岛 `miui.focus.param` 才需要，本功能不依赖它） |
| 需要的权限 | `POST_PROMOTED_NOTIFICATIONS`（`normal\|appops`，装机即授予，用户可在系统设置关闭） |
| 胶囊显示内容 | 图标 + 一行短文本（约 7 字符），工具阶段带工具名，**不含进度条** |
| 完成态 | 任务结束后 8 秒内保持 ongoing 并显示「完成 / 已停止 / 已达上限 / 已断开」，随后主动取消 |
| 展开态详情 | 阶段、工具与目标、轮次（由 subText 展示，只出现一次）、结束后总耗时；运行中不写秒数，避免心跳重建通知吞掉按钮点击；锁屏公开版本只显示阶段，不含工具与标题 |
| 失败行为 | 任何异常都返回原通知，功能退化为普通通知，不影响任务本身 |

代码覆盖的平台：原生 Android 16+、小米澎湃 OS 3.0.300+/4、ColorOS 16、OriginOS 6/7，以及后续跟进同一接口的厂商；实际验证状态见下表。

| 平台 | 系统基线 | 提升写法 | 验证状态 |
|---|---|---|---|
| 原生 Android 16（36.0） | Android 16 | `android.colorized=true` | 支持（按平台分支） |
| 原生 Android 16 QPR1+ / 17 | 36.1+ | `android.requestPromotedOngoing=true` + `colorized=false` | 代码支持，未上机 |
| 小米澎湃 OS 3.0.300+ / 4 | Android 16 / 17 | 按平台分支 | 贡献者真机观察（build332 / Android 17），未入库 |
| ColorOS 16 | Android 16 | 旧模型 | 公开资料，未上机 |
| OriginOS 6 | Android 16 | 按平台分支 | 公开资料，未上机 |
| **OriginOS 7** | **Android 17** | **新模型** | **预期支持；仓库尚无上机记录，实测后更新** |

OriginOS 7 基于 Android 17，仍保留 Origin Island（原子岛）。只要系统继续渲染 AOSP
promoted ongoing 通知，该路径即可显示原子岛，不需要 vivo 私有 SDK 或白名单；若 vivo 只认自家接入，
则退化为普通通知，任务本身不受影响。
更老系统与鸿蒙只显示普通通知 —— 这是系统能力差异，不是开关失效。

## 2. 两代 ROM 的判据是相反的

| 版本 | 提升判据 |
|---|---|
| Android 16 GA（36.0，如 ColorOS 16） | 旧模型：需要 `android.colorized=true` |
| Android 16 QPR1+（36.1 / Android 17，如 HyperOS 4） | 新模型：需要请求位 `android.requestPromotedOngoing=true`，且 **colorized 必须为 false** |

因此实现按「平台是否存在 `setRequestPromotedOngoing`」分成两支，不能只写一支；
权限查询（`canPostPromotedNotifications`）与模型判定必须分开 —— 用户关掉实时活动时
权限查询返回 false，不能据此把 36.1 误判成旧模型。
API 引入版本：`canPostPromotedNotifications()` 自 **API 36** 起就有；`setRequestPromotedOngoing`
与 `POST_PROMOTED_NOTIFICATIONS` 自 **API 36.1** 起才有。
查询只反映新模型（请求位）的许可：36.0 旧模型 ROM 按 colorized 提升，查询返回 false 时设置页仍显示
「查不到」（`STATE_UNKNOWN`），不显示成「已关闭」；判定见 `util/IslandPage.supportState`。
平台侧的 `setRequestPromotedOngoing` 是 36.1 才有的方法，在 36.0 上调用会抛
`NoSuchMethodError`，所以这里统一走 **extra**（官方文档明确允许用它替代 setter）。

## 3. 改动清单

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/deepseekharness/app/IslandSupport.java` | 新增。能力查询、两代 ROM 分支、通知增强、系统设置入口；纯展示层 |
| `app/src/main/java/com/deepseekharness/app/util/IslandText.java` | 新增。纯逻辑：短文本裁剪、岛位选举（无 Android import，配单测） |
| `app/src/main/java/com/deepseekharness/app/util/AgentTaskState.java` | 改。任务行增加工具详情/标题；断连 60 秒收敛为终态，心跳可恢复瞬时断连 |
| `app/src/main/java/com/deepseekharness/app/AgentTaskNotifications.java` | 改。渲染时接入选举与增强；完成态保留 8 秒；内容去重；展开态详情 |
| `app/src/main/java/com/deepseekharness/app/ui/AgentTasksActivity.java` | 改。详情页显示标题、工具与阶段耗时；等待中显示「回答」 |
| `app/src/main/java/com/deepseekharness/app/AgentTaskAnswerReceiver.java` | 新增。通知选项按钮 → 本机一次性回答端点 |
| `app/src/main/java/com/deepseekharness/app/ui/WebPageScripts.java` | 改。进入会话后滚动并聚焦提问卡 |
| `app/src/main/java/com/deepseekharness/app/ui/BrowserConversationRoute.java` | 改。会话入口支持“带问题定位” |
| `app/src/main/assets/builtin-plugins/dsh-task-notifier/lib/index.js` | 改。工具详情、会话标题、每秒心跳、丢失 turn/end 时 3 秒补终态 |
| `app/src/main/java/com/deepseekharness/app/ui/IslandFragment.java` | 新增。设置页「任务状态上岛」；2026-11-02 按[方案 B](design/island-status-20261102/README.md) 重绘（`layout/fragment_island.xml`、`util/IslandPage` + 单测） |
| `app/src/main/java/com/deepseekharness/app/ui/SettingsFragment.java` | 入口标签 |
| `app/src/main/java/com/deepseekharness/app/core/ConfigStore.java` | 两个偏好读写 |
| `app/src/main/java/com/deepseekharness/app/util/Constants.java` | 两个偏好键名 |
| `app/src/main/AndroidManifest.xml` | `POST_PROMOTED_NOTIFICATIONS` |
| `app/build.gradle` | build209 起无独立上岛包开关；上岛能力并入正式包 |
| `app/src/test/java/com/deepseekharness/app/util/IslandTextTest.java` | 新增。选举与裁剪的单测 |

## 4. 岛位选举

系统按包名只显示一个胶囊（SystemUI `filterByPackage`），多个活跃任务同时请求提升会互相顶掉，
表现为胶囊文字来回跳。因此每次渲染先选一个代表：

```
等待确认 (5) > 正在停止 (4) > 执行工具 (3) > 生成回复 (2) > 其他运行中 (1) > 不参与 (0)
```

不参与的条件：非活跃、状态 `unknown`、用户已划掉。同优先级取更早开始的任务，保证文字稳定。
只有被选中的那条通知会被增强，其余保持普通通知。

没有活跃任务时，最近 8 秒内结束且未被划掉的任务会短暂占用岛位，显示完成结果；
这也是“任务完成后胶囊直接消失、看不到结果”的修复点。

## 5. 偏好字段

| 键 | 默认 | 含义 |
|---|---|---|
| `task_island` | `true` | 任务状态上岛总开关 |
| `task_island_chronometer` | `false` | 开：系统计时器显示已用时间；关：显示开始时间。两种情况都不再每秒重绘 |

系统胶囊最多约 7 个字符：`shortCriticalText` 优先，放不下时只显示图标或部分文字。
运行中通知内容保持稳定（秒数交给系统 `when`/计时器），心跳不再触发 `notify`，
避免每秒重绘导致通知按钮点击被替换吞掉。

### 设置页（2026-11-02 方案 B）

| 区块 | 行为 |
|---|---|
| 回到桌面后（示意） | 页面可见时每秒读 `AgentTaskState` 快照，代表与保留期用 `IslandText.pickForDisplay`，胶囊文字用 `islandShortText`，与真实通知同源；只读，不发通知 |
| 系统状态行 | `IslandSupport.state()` 每次 `onResume` 重读。36.0 旧接口的 `STATE_UNKNOWN` 显示“系统支持，开关状态查不到”并附系统设置入口，胶囊示意为虚线；36.1+ 关闭时按钮为“去打开”；低于 API 36 不显示入口 |
| 行为开关 | 键与默认值不变；计时器开关在总开关关闭时置灰但保留已存值（与 `islandEnabled && chronometer` 一致） |
| 上岛顺序 | 五级只读列表，高亮当前活跃代表所在级；完成态不属于任何一级。顺序不可配置 |

DSHA 原生页面在前台时任务通知被撤下（`AgentTaskNotifications` 的 `foreground → cancel`），所以页面只做示意，不在本页内发布胶囊。

## 6. 状态更新、收敛与详情

| 机制 | 行为 |
|---|---|
| 每秒心跳 | 上报端在任务仍存活时每秒发一条同阶段事实，原生刷新“最后活动时间”；长时间思考不会因 15 秒静默被判成未知 |
| 15 秒静默 | 先显示「状态暂不可确认」，不再宣称正在执行 |
| 60 秒静默 | 按「连接中断」结束并释放 ongoing/岛位；之后同一轮的新心跳可以恢复，真实终态（完成/停止/超限）不可复活 |
| 丢失 turn/end | 上报端看到 Agent 已 idle 或已被替换时，3 秒后补发终态：停止过 → `aborted`，正常 idle → `completed`，被替换 → `interrupted` |
| 瞬时页面不可用 | 页面身份暂时读不到不再判死已有任务；只有确认 Web 已停止才终结旧票 |
| 内容去重 | 通知内容未变化时不重复 `notify`；配合计时器模式可避免每秒重绘 |
| 详情来源 | 工具名 + 目标（路径/URL/查询词；命令只留 argv[0]）、会话标题、轮次、阶段耗时、总耗时 |
| 隐私 | 锁屏公开版本只显示阶段，不含工具目标、命令或会话标题 |

工具详情只在通知与详情页显示，胶囊受 7 字限制，只取工具名（如「执行·read」）。

## 7. 点按进入与岛内回答

| 入口 | 行为 |
|---|---|
| 点按通知/胶囊（等你确认） | 直接打开该会话；页面就绪后把提问卡滚进可视区，**没有选项时**才聚焦回答框（有选项时保持键盘收起，避免把「提交」挤出可视区）；不自动全屏 |
| 通知卡片上的选项按钮 | 单问题、1–3 个选项时显示；单选点一下立即回答，多选先切换（✓）再「提交」，**不需要先打开 App** |
| 通知「自己写」 | 直接输入自定义答案（RemoteInput）；单/多选都支持，多选时自定义答案与已选选项一起提交 |
| 多问题 / 超过 3 个选项 / 自定义过长 | 显示「打开回答」，进入会话由提问卡回答（网页卡片本身一直支持自定义答案） |
| 「Agent 任务」页 | 等待中的任务显示「回答」按钮，进入同一会话 |

> 聚焦判据：提问卡内存在实际选项行（`Mbwy4a_optionRow`）时不抢焦点；选项容器
> `Mbwy4a_options` 对每张卡片都会渲染，不能用来判断“有没有选项”。

回答回传链路：

```text
web 事件里带 port/path/token/callId/questions
  → 原生通知 addAction（AgentTaskAnswerReceiver）
  → POST http://127.0.0.1:<webPort>/__dsha_question_answer
  → 插件按“当前真正待答的问题”校验 token/callId/选项
  → 唤醒 user-questions/waterfall，把结构化答案交回 ask_user_question
```

- 令牌每个进程随机生成，只通过已认证的任务 bridge 下发给原生；端点只接受本机回环请求。
- 网页端先答、问题已结束、选项不匹配或令牌过期都会被端点拒绝（返回 409）；不会重复回答。
- 通知动作在后台执行，失败必须可见：HTTP 非 200、空提交或入口失效都会弹一条 Toast
  （含 HTTP 状态与服务端错误码，如 `HTTP 409 NO_WAITING`），并提示点通知正文进入对话回答；
  应用日志（`DSHA`）记录 `TASK_ANSWER_REJECTED <status> <body>`。
- 展开态只保留一处轮次（通知 subText）：正文不再重复「第 N 轮」，运行中也不写秒数，
  内容签名因此保持稳定，心跳不会每秒重建通知。
- 通知上的选项只覆盖安全子集（单问题、≤3 选项）；多选用「切换 + 提交」，自定义答案用 RemoteInput；
  多问题或超过 3 个选项时回到会话里回答。
- 多选状态只保存在本机通知进程内，按任务 action 隔离；问题结束/切换任务时自动丢弃。
- 锁屏公开版本仍只显示阶段，不含问题文本与选项。

通知动作受系统 3 个槽位限制：单选最多显示 2 个选项 +「自己写」；多选最多显示 2 个选项 +「提交」，
未选择时显示「自己写」。选项超过 2 个或需要自由文本时，点按通知正文进入会话回答，
展开态会提示「更多选项请点按通知进入对话回答」。槽位规划由 `util/IslandText.quickSlots` 统一给出并有单测；
多选只有 1 个选项且已选时，第三个槽位仍放「自己写」；没有可点选项时为「自己写」+「打开回答」。

## 8. 独立版包名（历史）

build209 起独立上岛包已并入正式包，`dsha.islandEdition` 开关、`.island` 包名与 `ISLAND_EDITION` 构建字段全部删除，不再有可选的独立包。以下为历史记录：
`-Pdsha.islandEdition=true` 曾把包名变为 `dsh.android.client.island`，使应用私有目录、偏好、插件与运行环境隔离。

## 9. 验证范围

已做（2026-10-28 本轮）：

- 宿主单测：`AgentTaskStateTest`、`IslandTextTest`（含详情限长、断连收敛与恢复、完成态选举、耗时格式）。
- `:app:compileStandardDebugJavaWithJavac` 编译通过。
- 源码整洁、UI i18n、文档链接门禁通过；`runtime-descriptor.json` 按新插件字节重算。
- 正式 `tools/test-task-progress.mjs` 实跑通过：所有权隔离、一次性停止、问题载荷与原生回答端点、
  详情/心跳/终态收敛、HMR 连续性。
- 本机 util+core 1221 项测试 1220 通过；唯一失败是 `BoundedProcessRunnerTest` 的洪泛夹具在负载下
  输出长度少一截，单独重跑通过（环境/时序抖动，非本次改动）。

未做：真机安装与胶囊显示验收；8 秒完成态是否按系统节奏消失、锁屏公开版本的实际显示、
长时间 Doze / 进程冻结下的心跳恢复，以及**通知选项按钮的真实点击回传**均未在真机验证。

2026-11-08 补记：OriginOS 7（Android 17）按 36.1+ 新模型列入预期支持，尚无上机记录；
OriginOS 6 也只有公开资料，两者都需要实测后才能改为「已验证」。