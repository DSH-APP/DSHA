# Agent 接手时的领域与事实来源

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；本页为F11新建维护说明，不是找回的旧 `docs/agents/domain.md` 原件，也不重建旧会话决定。先读[README](../../README.md)、[AGENTS](../../AGENTS.md)和[总手册](../project-architecture-handbook.md)。

## 先认清状态归属

| 领域 | 唯一事实源/所有者 | 不可替代的证据 |
|---|---|---|
| APK与固定输入 | Gradle、锁、受管descriptor、实际APK | README版本文案不能证明包内容/签名 |
| Web运行 | HarnessController及生命周期协作者 | UI“停止”不能代替guest/launcher退出证明 |
| 数据与插件事务 | HostDataTransaction、ManagedRuntimeTransaction及对应日志 | 目录存在、旧健康回执不能替代本次目标/摘要复核 |
| 设备许可 | Android系统、DeviceGrants、CapabilityBroker和运行身份 | 插件工具审批不等于系统权限 |
| 用户/外部内容 | 用户原件与固定候选 | 归档自述健康不取得当前执行权，外置引用不等于外置内容已备份 |

详细修改地图由[模块图](../module-map.md)、[权限架构](../device-permissions-architecture.md)、[环境与数据](../runtime-data-plugin-architecture.md)提供。新增职责放入所属协作者，不用总控制器积累无关逻辑。

## 文档、代码和决定分别核对

用户当前决定约束本轮范围；代码描述真实行为；规范/ADR说明设计边界；某次发布或设备回执只证明所列源码/APK和时点。两者不符时记录具体调用链并裁定，不能自动以代码覆盖用户决定，也不能为迎合旧报告恢复已退休擦除或旧审批门槛。

已有决策从[ADR0001](../adr/0001-build154-boundaries.md)、[ADR0002](../adr/0002-current-threat-model.md)、[ADR0003](../adr/0003-virtual-screen-lifecycle-and-input.md)进入；新决策要说明背景、后果和证据，不把旧文件缺失当作推测历史的许可。`GLOSSARY.md`按实际共享术语需求建立；目前没有该文件不阻止维护，也不虚造空词表。新增的具体引用必须存在并进入源码分发。

## 协作与验证

以用户授权范围拆互斥任务。并行修改时注明文件所有者、禁止区域、基线和完成证据；冻结后由单一负责人生成受管证明、签名/交付和最终清单。读别的任务输出不自动授权向该用户聊天、邮件或外部服务发送消息。

先选最小关联测试，再按变动扩大检查；记录失败、跳过、平台与缓存。源码/Host/浏览器/实际APK/真机证据分列，不把静态`@Test`计数当执行数。设备、ADB、发布及长时验收遵守当前授权，文档补齐本身不授权运行它们。[构建与发布](../build-and-release.md)说明绑定方式。
