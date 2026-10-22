# 安装、组件检查与环境维护

> 核验日期：2026-10-21。行为按本工作树 build190/F12 源码核对，F10 `087724c` 及 F11 保留参考历史归属。本页更新未执行构建、软件测试、设备或长时验收；最终身份与正式执行数量见 [README](../README.md) 和 [F12记录](releases/v0.2.1-rc.2-build190-fix12-20261021.md)。历史结果不随本文转记。

安装解决“离线环境是否可运行”，组件检查解决“某个工具是否响应”，环境维护解决“受管文件或环境是否需要事务修复”。三者的成功条件不同。入口总览见[环境、数据与插件机制](runtime-data-plugin-architecture.md)，启动后的状态见[启动与诊断](startup-and-diagnostics.md)。

## 首次准备

首次用户经欢迎页进入准备页，解压并校验 APK 内的完整 Ubuntu 与 DSH 资产；当前 Ubuntu 基础编号为 base10，DSH 为 `0.2.1-rc.2`。缺少固定离线归档的源码检出不能作为完整正式安装包交付。运行引擎默认 proot，可显式选择 proroot，详见[运行引擎](runtime-engines.md)。

首次空环境走冷安装事务：准备环境、执行运行核验、确认实际运行方式和环境身份。已有环境不会因 Bash 或标记损坏就被组件页自动清除、重新解压。完成后先进入 Main，再提交一次受代次围栏保护的后台 Web 启动请求；模型与 API Key 在 Web 设置中填写。[环境维护实现](../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java)与[首次 Web 请求](../app/src/main/java/com/deepseekharness/app/ui/FirstRunWebStart.java)是这两段的入口。

## 六步组件页

“安装与环境”中的“检查全部组件”先进行只读探测；“按需修复”只修选中且探测失败、当前允许修复的项目，随后重新探测。单项检查与全量检查共用[InstallPipeline](../app/src/main/java/com/deepseekharness/app/runtime/InstallPipeline.java)与[InstallProbe](../app/src/main/java/com/deepseekharness/app/util/InstallProbe.java)。

| 步骤 | 当前检查内容 | 按需修复的边界 |
|---|---|---|
| 1 Linux 环境 | 环境身份、解压标记、实际 Bash/加载器就绪 | 仅确认为首次空目录且有离线包时解压；已有文件保留 |
| 2 基础工具 | curl、git、Python 及 ssl/sqlite3/readline/tarfile/zipfile | 补齐失败的离线工具，完成后复验 |
| 3 Node.js | 实际 `node --version` 退出状态 | 本组件页不覆盖用户升级过的 Node；提示转入保数据环境维护 |
| 4 pnpm | 实际 `pnpm --version` | 核对并补齐随包 pnpm，再复验 |
| 5 deepseek-harness | 可执行入口与受管包中的有效版本 | 本组件页不直接覆盖用户升级的 DSH；先备份并审阅维护计划 |
| 6 运行补丁 | DNS、会话写入兼容、LAN 设置及 Android 组 | 只修失败的补丁/组并复验，不据一个版本字符串判全部通过 |

检查结果必须有本次已知键的退出状态。缺失、重复、格式错误或探测进程提前退出均不能算成功；Linux 未就绪时，其余组件明确标为无法验证。检查通过仅证明列出的探针通过，不等于所有插件、网络服务、模型请求或 Android 能力都正常。

| 操作 | 何时生效 | 取消与失败 |
|---|---|---|
| 检查全部/单项 | 成功取得环境任务票据后在后台开始 | 竞争任务或 pending 维护会拒绝；不先发布虚假的运行状态 |
| 按需修复 | 用户点击后，仅对失败项写入 | 不执行尚未开始的后续修复；已经完成的修复保留 |
| 停止检查 | 探针阶段请求结束本次进程；写阶段等待安全收尾 | 页面销毁不释放任务锁；进程退出未确认时继续回收并阻止新环境任务 |
| 离开页面/旋转 | 仅改变 UI 观察者 | 应用进程内的任务与结果由仓库持有；不保证系统结束进程后自动续跑 |

[InstallRepository](../app/src/main/java/com/deepseekharness/app/core/InstallRepository.java)持有任务生命周期，[InstallTask](../app/src/main/java/com/deepseekharness/app/util/InstallTask.java)维护线程安全快照与取消状态。检查日志保留最近约 48 Ki 字符预算；跨行私钥块隐藏，超过 4096 字符的单行整体隐藏，并移除控制序列、脱敏常见凭据。日志不是完整审计追踪，也不保证任意秘密格式都能被识别。

组件探测时限仍由`InstallPipeline/InstallProcess`按所选检查计算。插件列表与短启停脚本另有[GuestScriptBudget](../app/src/main/java/com/deepseekharness/app/runtime/GuestScriptBudget.java)的30秒协作总预算，涵盖排队、准备边界和执行，不能套用成所有安装、诊断或维护的硬时限；单次系统I/O、monitor或Binder等待不可由该预算抢占。任何超时都不能替代guest与launcher的退出证明，未知退出仍保留任务锁、日志和维护屏障。

## 维护计划、更新与保数据重建

“保护数据并修复运行环境”“检查更新计划”等入口先展示当前差异和恢复情况。Ubuntu 基础相同且受管更新可用时，优先准备 APK 受管文件候选；不是每次 UI 更新都重新建立 Ubuntu。[ExtractActivity](../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java)决定页面流程，[EnvironmentMaintenance](../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java)执行维护顺序。

实际提交使用[MaintenanceCoordinator](../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java)和[MaintenanceGate](../app/src/main/java/com/deepseekharness/app/util/MaintenanceGate.java)：取得环境执行权，正常停止 Web 与终端，确认运行任务/受管进程退出，再切换数据或运行时。guest 或 launcher 退出未确认时保留原环境及日志，不能把超时、取消或 UNKNOWN 当作“什么也没有执行”。

受管更新由[ManagedRuntimeTransaction](../app/src/main/java/com/deepseekharness/app/backup/ManagedRuntimeTransaction.java)保留前代并提交；[RuntimeTrial](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java)检查候选的真实启动、端口/鉴权、存储/会话、网页握手和退出。兼容回退使用当前 APK 启动器重新试运行前代，不恢复旧对话快照。更新失败保留原运行时和个人数据；回切失败也必须保留现场，不能只清日志解除阻塞。

保数据环境重建另有数据快照和新旧树事务，不等同于清数据。维护成功后仍重新检查环境就绪；未通过时停在原生页，允许查看原因或明确进入受限界面，不自动跳转循环。已确认的旧运行时轮换还要复核逐根摘要与成员；修改过、不可读、含额外文件或未知的原件保留。

完整格式化、彻底重装与擦除后端已退出生产能力。外置工作区维护只能断开应用私有引用，不能遍历、删除、覆盖或改名外部文件；旧恢复日志也不得借恢复重放外置写删。合法私有临时清理仍须核对受控目标及 NOFOLLOW，不能从旧格式化逻辑继承任意擦除能力。

## 中断时的处理顺序

1. 保留错误信息和现场，在“恢复中断维护”查看具体事务类型；不要删除 pending 文件、换裸 PID 强杀或卸载来绕过门禁。
2. 当前恢复入口区分冷安装、隔离试运行、插件事务、环境重建、配置事务、受管运行时、宿主数据和旧维护日志；先检查唯一性与进程退出，再按所属契约恢复。
3. 多个可恢复事务、对象身份变化或仍有运行任务时停止并报告，原件继续保留。恢复后再运行对应组件检查/就绪检查。
4. Web 不能启动时仍可用宿主备份、只读救援和[独立应急 DSH](emergency-recovery.md)；普通恢复不应要求先运行已经损坏的 guest 工具。

## 修改与验证

| 修改点 | 关联测试入口 |
|---|---|
| 六步只读检查、按需修复和复验 | [InstallPipelineTest](../app/src/test/java/com/deepseekharness/app/runtime/InstallPipelineTest.java) |
| 任务所有权、竞争、取消与收尾 | [InstallRepositoryTest](../app/src/test/java/com/deepseekharness/app/core/InstallRepositoryTest.java)、[InstallTaskTest](../app/src/test/java/com/deepseekharness/app/util/InstallTaskTest.java) |
| 停止顺序与维护围栏 | [MaintenanceGateTest](../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java) |
| 首次完成后的唯一启动请求 | [FirstRunWebStartTest](../app/src/test/java/com/deepseekharness/app/ui/FirstRunWebStartTest.java) |

这些链接是回归选取依据，本文没有重新运行它们。两 flavor 软件结果、实际 APK、签名与手机证据应分别绑定精确版本和摘要；工具检查通过不提升为完整设备矩阵或数据逐项比对通过。
