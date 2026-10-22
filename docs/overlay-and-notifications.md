# 悬浮条、请求确认与任务通知

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`，并对照本轮工作树源码。本文集中说明三类不同窗口；没有新增系统权限或设备验收。

## 流式悬浮条

“设置 → 悬浮条”由 [OverlayFragment](../app/src/main/java/com/deepseekharness/app/ui/OverlayFragment.java) 编辑。控件先改草稿，点“保存样式”才写入实际偏好；“预览悬浮条”可临时展示真实系统窗口，离开页面时撤销预览。开启需要系统悬浮窗许可；页面会引导到设置，许可返回后重新检查。许可、应用内开启状态与实际窗口成功创建是不同结果。

| 项目 | 默认 / 范围 | 生效和边界 |
|---|---|---|
| 显示悬浮条 | 关闭 | 保存后启用；关闭会拆除窗口，运行事件也逐次检查开关与许可 |
| 行数 / 字号 | 3行 / 12sp | 行数1–6；保存后刷新样式 |
| 停留 / 不透明度 | 6秒 / 85% | 停留2–60秒，不透明度20–100% |
| 显示思考过程 | 关闭 | 只控制悬浮展示；不改变模型配置或消息数据 |
| 显示执行命令 | 开启 | 允许显示命令原文，使用其他应用时注意可见信息 |
| 在悬浮条确认命令 | 开启 | 还需悬浮条启用、系统许可及有效挂起请求；不是独立授权来源 |

[OverlayController](../app/src/main/java/com/deepseekharness/app/OverlayController.java) 处理流式事件、样式与窗口资源；[OverlayFrameBuffer](../app/src/main/java/com/deepseekharness/app/util/OverlayFrameBuffer.java) 防止已失效的帧更新重建新窗口。确认期间普通流文本不会覆盖确认 UI；显示超时和隐藏不代表业务已经停止。撤销悬浮窗许可后事件不会继续创建有效窗口，通知或原生入口仍各自受系统可用性约束。

## 允许/拒绝与助手提问

[HttpShellService](../app/src/main/java/com/deepseekharness/app/HttpShellService.java) 为需要本应用确认的请求建立单次身份与桥运行代次，提供通知按钮、前台原生弹窗和可用时的悬浮条入口。三者解决同一个挂起请求，不产生三份独立许可。[ConfirmReceiver](../app/src/main/java/com/deepseekharness/app/ConfirmReceiver.java) 只接收指定 action 与完整请求身份；旧通知、旧 dialog 或旧运行代次不能批准后来的请求。

确认等待上限60秒。拒绝、超时无答案、窗口不可用与运行已失效是不同结果，均不能伪造“允许”。屏幕操作还需在提示窗口关闭后重新核对真实目标、帧/节点与当前身份；不能点“允许”后立刻操作仍被确认窗遮挡的页面。停止、撤销或策略变化会使相关排队请求失效。屏幕规则详见[设备权限](device-permissions-architecture.md)，虚拟屏另见[虚拟屏架构](virtual-screen-architecture.md)。

本应用确认不覆盖设备命令白名单，`POLICY_BLOCKED` 没有“仍然允许”按钮；DSH 工具审批、插件自身权限与任意原生终端代码也不是同一门禁。不能把悬浮确认描述为系统强沙箱。

`/app/ask` 的助手提问由 [BridgeAskDialog](../app/src/main/java/com/deepseekharness/app/BridgeAskDialog.java) 和 [BridgeQuestions](../app/src/main/java/com/deepseekharness/app/util/BridgeQuestions.java) 管理，最多展示三个选项；只在可用前台 Activity 打开。页面后台、销毁、返回或窗口消失时取消本次提问，不把消失当成默认答案。它与命令允许/拒绝分别管理。

## Agent任务通知

[AgentTaskNotifications](../app/src/main/java/com/deepseekharness/app/AgentTaskNotifications.java) 使用普通任务进度通知频道 `dsh_task_progress`，独立于 Harness 前台服务常驻通知，不请求 promoted 胶囊。当前桥注册绑定实际端口、generation、instance、client 与 nonce；任务再绑定 session、turn、owner及递增 sequence，旧事件不能控制新任务。

原生“任务”页始终可以查看状态。应用在后台时发布通知，前台时取消对应系统通知；通知权限被拒绝时捕获失败并保留原生任务页。通知仅展示阶段与耗时，不复制聊天正文，锁屏公开版本保留较少内容。进度显示通常按1Hz刷新，终态事件可立即刷新。

“停止”发送绑定该次任务的请求，先显示“已请求停止，等待结束”；它不是已退出证明，也不是停止整个 DSH。生产者确认/终态才推进状态，超时或失联保守显示“状态暂不可确认”。“回聊天”核对当前运行身份后路由到相应会话；旧代次不应被重新启动以满足旧通知。“划掉”仅改变通知展示，不隐式停止任务。状态机见 [AgentTaskState](../app/src/main/java/com/deepseekharness/app/util/AgentTaskState.java)。

## 软件核验入口与限制

回归入口：[OverlayFrameBufferTest](../app/src/test/java/com/deepseekharness/app/util/OverlayFrameBufferTest.java)、[BridgeConfirmationsTest](../app/src/test/java/com/deepseekharness/app/util/BridgeConfirmationsTest.java)、[BridgeQuestionsTest](../app/src/test/java/com/deepseekharness/app/util/BridgeQuestionsTest.java)、[AgentTaskRegistrationTest](../app/src/test/java/com/deepseekharness/app/util/AgentTaskRegistrationTest.java)、[AgentTaskStateTest](../app/src/test/java/com/deepseekharness/app/util/AgentTaskStateTest.java)。本轮没有执行测试或点系统窗口；通知权限、系统后台限制、悬浮窗口层级及目标二次核验仍需正式包设备记录。
