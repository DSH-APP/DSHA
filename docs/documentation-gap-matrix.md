# 文档缺口报告核对与补齐矩阵

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；本页核对当前 build190/F11工作树与《权威性文档缺失与整理查询》源材料。报告审计基线是 `d2f883315d75420ab83b5d121f0e14eab9769e0b`，早于F10；源材料SHA-256为 `5f85f7f64225029f6d42f3585d7d722bc9feaa7893b524e2ce75e8945c04a541`。报告是待核对材料，不是产品指令或当前交付证明。

报告的33篇/29篇、79处断链、56目标、旧版本及统计只属于原审计快照。本轮从F10已有114份Markdown的发布范围出发核对，不按旧计数重复补写已经存在的手册、ADR和历史说明。当前源文件存在、进入Git/完整源码ZIP、能获得本机历史证据，是三项不同检查；最终发布可见性由统一交付清单与实际远端结果确认。

## 31个主题的实际入口

“既有”表示F10已提供对应核心说明；“补齐”表示本轮新增专题或纠正当前正文，不表示增加产品功能、重做历史测试或完成设备矩阵。

| 报告主题 | 当前实际文档 | 本轮处理与限制 |
|---|---|---|
| 1 虚拟屏 | [架构](virtual-screen-architecture.md)、[指南](virtual-screen-guide.md)、[ADR0003](adr/0003-virtual-screen-lifecycle-and-input.md) | 既有帧/节点/anchor、代次、关闭和失败说明；旧10文件/1测试不是现行计数 |
| 2 ADB/Root/Shizuku | [权限架构](device-permissions-architecture.md)、[通道操作](device-channels.md) | 补集中操作、124/125/126、许可和执行身份；不做新配对/设备实测 |
| 3 proot/proroot | [引擎](runtime-engines.md)、[选择指南](runtime-and-network.md) | 既有默认proot与有限回退，补设置选择；旧proroot默认按日期保留 |
| 4 端口 | [端口](ports-and-networking.md) | 既有动态/手动/保留号/备用记忆/旧origin；绑定竞态辅助函数不写成已接入重试 |
| 5 DNS | [DNS](dns-configuration.md) | 既有三模式作用层与窄解析重试；不等于全局关闭IPv6 |
| 6 proroot静态加载器 | [引擎](runtime-engines.md)、[设置选择](runtime-and-network.md) | 说明实际可达分支与vendor限制；不以存在动态分支推断UI可达 |
| 7 proot seccomp | [引擎](runtime-engines.md)、[设置选择](runtime-and-network.md) | 下次启动参数生效；无当前同设备数据不编性能损失 |
| 8 桌面网页布局 | [浏览器](browser-engines.md) | 补UA读取时机、实例重建；UA不等于viewport或响应式布局改变 |
| 9 System WebView/Low Gecko | [浏览器](browser-engines.md) | 补真实检测/失败路径；Standard无Gecko备用，不以UA版本推断自动切换 |
| 10 PiP | [画中画](picture-in-picture.md) | 补默认关、系统许可、同代实例、新Task与恢复规则；不迁用旧包设备结论 |
| 11 检查更新 | [更新](update.md)、[下载校验](download-verification.md) | 补每进程一次、清单/通道/校验；当前ACTION_VIEW不具旧173会话围栏保证 |
| 12 终端 | [终端](terminal.md) | 补PTY/简易、标签/退出身份与状态；UI离开不证明进程停止 |
| 13 六步安装/环境维护 | [安装维护](install-and-maintenance.md) | 补探测/按需修复/复验与日志边界；Node/DSH不在组件页盲目覆盖 |
| 14 插件管理 | [插件操作](plugins.md)、[运行与权限](plugin-runtime-and-permissions.md) | 来源、候选、事务、删除和真实加载分开；不新增审阅门 |
| 15 备份恢复 | [备份恢复](backup-and-restore.md)、[v5格式](backup-format-v5.md) | 补用户流程，纠正旧重导出密码叙述；新导出无密码，旧件读取仍认证 |
| 16 自动备份/保留 | [备份恢复](backup-and-restore.md) | 三计划、闲时延后、三份正常副本；与手动一键工作区范围分开 |
| 17 数据维护/格式化/重建 | [安装维护](install-and-maintenance.md)、[数据架构](runtime-data-plugin-architecture.md) | 擦除能力退休；外置仅断私有引用，未知原件保留 |
| 18 LAN | [LAN](lan.md)、[端口](ports-and-networking.md) | 补HTTP明文、3081、本轮token/cookie及失败分类；主机回归不作真实Wi-Fi证明 |
| 19 3090桥 | [桥和文件](bridge-and-files.md) | 补协议3、33精确路由+2前缀、X-Token、请求预算和错误；运行时help仍是能力入口 |
| 20 文件共享/DocumentsProvider | [桥和文件](bridge-and-files.md)、[Web文件](web-file-sharing.md) | 补稳定ID、anchor/链接/FD、32MiB/2GiB与选择器完成边界 |
| 21 独立应急DSH | [应急](emergency-recovery.md) | 补独立根/五工具/原生写入确认/只读就绪和退出；不借正式rootfs救援 |
| 22 启动门禁/恢复 | [启动诊断](startup-and-diagnostics.md)、[安装维护](install-and-maintenance.md) | 补首次请求、三层就绪、恢复阶梯；UNKNOWN不跳清空环境 |
| 23 自检/诊断/日志 | [启动诊断](startup-and-diagnostics.md) | 补采集限额、脱敏/导出边界；不声称自动上传或任何秘密均能识别 |
| 24 悬浮条/确认 | [悬浮与通知](overlay-and-notifications.md)、[权限架构](device-permissions-architecture.md) | 三渠道和失败回退、系统悬浮许可/业务批准分开 |
| 25 任务通知 | [悬浮与通知](overlay-and-notifications.md) | 补身份/点击/通知权限；标准进度通知，不承诺promoted胶囊 |
| 26 i18n/语言锁存 | [语言](i18n.md) | 补进程锁存与Activity重建范围，不承诺后台进程重启或全语言矩阵 |
| 27 配置快照 | [配置快照](config-snapshots.md)、[配置恢复](configuration-recovery-host.md) | 六文件快照、宿主白名单、显式重置各自有边界，不混为整机设置镜像 |
| 28 构建/签名/发布 | [构建发布](build-and-release.md)、[BUILD](../BUILD.md)、[贡献](../CONTRIBUTING.md) | 补身份、阶段、固定输入、E7E3和源码ZIP；发布身份JSON不替代实际APK签名 |
| 29 测试/证据口径 | [构建发布](build-and-release.md)、[总手册](project-architecture-handbook.md) | 选择集/XML/跳过/缓存与静态计数分开；511/45/785各属原时点 |
| 30 Agent/issue/triage/域 | [domain](agents/domain.md)、[issue](agents/issue-tracker.md)、[triage](agents/triage-labels.md) | F11明确新建；未找到旧原件，不伪称恢复或远端标签已设置；GLOSSARY按需 |
| 31 内置/协议/热加载 | [插件运行权限](plugin-runtime-and-permissions.md) | 补7/8/2/3集合、策略源与协议分层；HMR/动态runner实际效果仍按匹配输入和运行证据确认 |

## 原24条与新增21条差异如何落地

以下编号分别对应报告6.1和6.5，避免把原已撤回的指控重新称作缺陷。历史文件保留其原时间和身份，导航与当前事实可另补。

| 报告编号 | 核对结果/处理 |
|---|---|
| 6.1 #1–4、#11–12 | 下载校验、fix1、接手、ADR与可取得历史说明F10已补入；当前索引链接到实际文件。遗失原始回执不凭空重建 |
| 6.1 #5 | GLOSSARY按需，非必备缺口；domain说明当前选择 |
| 6.1 #6 | `agent-skills`目录与发布可见性由最终源码stage检查；目录存在与进入附件分别核对，不据旧浅克隆断言当前仍缺 |
| 6.1 #7–8、#9 | Standard/Low旧页和v1.1.6默认值均限定历史；当前浏览器/引擎专页负责现行规则 |
| 6.1 #10、#14–15、#18、#20 | 当前AGENTS/插件页由维护负责人同步；目录数、旧冻结依赖、旧mobile和3080固定说法不作为现行事实 |
| 6.1 #13 | 组件页实际探针、日志预算与完整性边界见安装维护，不承诺未实现的证书/npm全检或完整日志 |
| 6.1 #16、#21 | “按提示重启”与多候选需明确目标是交互澄清；不推断原文承诺自动重启或应用内附件选择器 |
| 6.1 #17 | 官方核心禁用可能使页面无法启动；恢复应由原生插件页/明确维护入口，不因“不可删除”推断不可禁用 |
| 6.1 #19 | 保留原撤回裁定；端口辅助函数不等于生产承诺/已复现故障 |
| 6.1 #22–23 | 包patch必需与可选入口声明校验分别说；普通失败临时候选与UNKNOWN保留现场分别说，不能笼统永久保留所有候选 |
| 6.1 #24 | 导出依赖/失败状态需要实际链和样本证明；文档不将缺独立字段推导为功能必坏或完全通过 |
| 6.5 #1、#3–5、#9 | 报告F9/2330并非现状；F10 `087724c`与其APK `922b325a…`是本轮参考。当前发布状态由README关联Release/最终回执决定，历史表不改写 |
| 6.5 #2、#6–8、#13–16、#19 | 同步现行计数口径、依赖/密码/格式化/端口/桥/自动启用，不恢复旧生产入口或审阅规则 |
| 6.5 #10 | 具体ADR已经可达；三篇Agent页本轮新建，当前gitignore不具有旧报告所述规则，不凭空添加“取消忽略”历史 |
| 6.5 #11 | 小型派生资产、精确bytepin与大生成归档分列，见BUILD/CONTRIBUTING；未被ignore不等于已提交/违规 |
| 6.5 #12 | Star图/工作流按最终stage实际目标核对，旧快照缺失不能直接迁用；不编新star数或假工作流结果 |
| 6.5 #17–18 | DocumentsProvider可读路径编码与调用方不透明ID兼容；Web文件接入3.0.5是历史，本轮另列3.2.1 |
| 6.5 #20–21 | 内置数量是用途分层；`patchReload`写入与消费分开，保留待确认，不宣布HMR失效或已在设备工作 |

## 检查方法与分发边界

[check-document-links.py](../tools/check-document-links.py)默认检查维护入口/当前专页；`--paths-file`可针对最终源码stage的精确Markdown清单运行。现行缺失目标和缺失Markdown（含历史说明）均失败；仅下文两份精确固定上游原文的8条已登记目标分类为非本地上游引用。历史非文档本机证据单独报告为不可取得，不冒称已随源码分发。`--strict-history`对历史非文档缺失及这8条上游引用仍失败。工具不联网，不验证URL在线状态、锚点语义或正文事实。

```sh
python tools/test-document-links.py
python tools/check-document-links.py --output app/build/build190-fix11-docs/current-links.json
# 在最终stage另用 --root STAGE --paths-file 精确文档清单，再核所有新增MD进入Git和完整ZIP
```

关联工具回归使用隔离目录和固定上游原文验证：现行断链失败、引用链接/HTML图片/编码路径、代码块排除、历史缺MD失败、本机证据分类、越界路径、缺源文件、未登记新页默认仍为现行，以及精确上游路径/摘要/目标、严格模式、字节变化和复制改路径不能复用例外。结果在本机证据目录保存；它们不代替全库事实审阅、实际APK验证或真机验收。

## F11实际分发stage的物理闭合

初次开发机全树可读检查不能代表源码分发闭合。F11实际135MD候选stage另查出40条错误，归一化为19个目标；本轮按真实原件和实际点击路径处理，不把此前C盘检查的0错误迁作该stage结果。

本次补入6篇真实历史说明（build154 POLICY、rc2全局总结、build155/156架构及其剩余项）、3项来源/保存JSON，以及F盘找到且精确符合旧保存清单SHA的Termux历史README。build155剩余项是递归新增的文档依赖。JSON范围由原6项显式扩大到9项；不是整个audit目录的通行证。真实历史正文保持原时间，内嵌的旧数据策略/能力和测试结果不升为当前承诺。

既有原字节build156发布页的相对链接及其递归目标需要5份物理导航副本：`tools/history/documents/docs/audits/build156/architecture-assessment.md`、`tools/history/documents/docs/releases/build156-remaining-work-20261003.md`、`tools/history/documents/docs/releases/build155-remaining-work-20261003.md`、`tools/history/documents/docs/audits/build155/architecture-assessment.md`、`tools/history/documents/docs/releases/v0.2.6-build155-20261003.md`。初次4副本检查还发现最后一份发布页缺失，原失败结果保留后补齐。它们逐字节复制对应正常文档路径的新导航位置，明确为F11复制件，绝不改写旧原件或伪称找回历史文件。具体可点击入口见[总索引](documentation-index.md)。

剩余8条属于固定上游mobile README与Termux许可证的相对引用，分别由源文件完整SHA、精确source path和literal target绑定固定commitURL，输出`upstream-source-relative`警告且明确非APK本地目标；严格模式继续拒绝。变更源原文、源路径或目标不享此分类，不泛化忽略vendored或历史目录。上游原文/许可证保持原字节，不为文档缺口改APK资产。

最终交付负责人将这15项新增分发路径及完整SHA/复制来源关系纳入冻结清单，重新运行实际stage的链接、隐私及ZIP成员核验并重算总数。递归引用的历史大JSON台账、设备原图/日志和私有取证不为凑0上传；它们继续单列不可取得证据，不冒充已分发文件。最终stage结果不能由本段预先宣布。

本轮不为补文档修改历史设备原日志/回执，不上传缺失的私有证据、不操作ADB/手机、不生成额外审计APK、不恢复格式化、不将官方primary链接视为官方认证。源码/软件与发布的最终动作及状态由负责人统一完成。
