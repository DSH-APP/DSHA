# 文档总索引与维护规则


当前源码：`0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1` / build356 / `io.github.sunsetrne.dsha` / DSH-A-Su（`main`）。本版是 build355 之后的布局 hotfix：build355 正式包内实际是 T143 首版布局（单卡片并排、设置页旧间距），本版改成用户在 353 `.debug` 预览包上确认的 v3——设置页去掉页内「设置」大标题后顶部空白同样收紧（容器 `paddingTop` 改为 `shell_section_margin` 10dp，首组不再叠 24dp），插件页「导入插件包 / 导出插件」改为两个独立圆角包裹框（各自 `ShellCard` 背景与 `selectableItemBackground` 按压前景，间隔 12dp），不再是单卡片并排；副标题与箭头保持移除，点击行为不变。改动只涉及 `fragment_settings.xml` 与 `plugin_list_footer.xml` 两个布局。受管运行时不变，`runtimeId` 仍为 **`8e54085c`**（205 个输入），fixed-inputs 冻结沿用锁定 20261105e，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。**build356 尚未真机验收**：v3 形态已在 353 `.debug` 预览包由用户确认，平板横竖屏、短屏与 1.3× 字体仍待核对；验收项是 354→356 覆盖安装成功、数据完好、READY。宿主 contracts 整组、全量单测、lint 与全树格式 / 文档检查由本轮发布准备执行（结果见工作树声明）。改动说明见 [build356 更新说明](releases/build356-changes.zh.md)。

历史版本的构建与验收记录保留在 [releases/](releases/)；各版本结论只在其对应提交与时点成立，不替代当前版本。


## 当前功能与架构

| 文档 | 解决的问题 |
|---|---|
| [项目维护总手册](project-architecture-handbook.md) | 全部主要功能、框架分层、入口类与修改/测试地图 |
| [ADB / Root / Shizuku 权限架构](device-permissions-architecture.md) | 系统权限、特权通道、无障碍、自有审批、服务与撤销、故障排查 |
| [环境、数据与插件机制](runtime-data-plugin-architecture.md) | 解压启动、正常/安全/应急、受管更新、pnpm/插件事务、凭据、备份与恢复 |
| [运行引擎与运行态](runtime-engines.md) | 当前proot/proroot、DSHA v1.1.6历史、vendor版本、loader/shim、退出监督与兼容边界 |
| [端口与网络](ports-and-networking.md) | 自动/手动Web端口、备用本机记忆、3090桥/ADB/LAN分域、当前重试接入边界 |
| [DNS配置](dns-configuration.md) | auto/ipv4/native的真实差异、Node窄lookup重试、glibc no-aaaa与用户选择 |
| [切网后回收失效的外网连接（T37，实验性）](network-switch-connection-recycle.md) | 切网/切后台后模型请求卡满 300 秒的原因、网络代次与 net-epoch.cjs 回收规则、开关与范围；未真机验证 |
| [虚拟屏架构](virtual-screen-architecture.md) | Core/Manager/工具路由、帧/节点/anchor证据、采集输入、关闭恢复与修改回归 |
| [实体屏特权执行核心（T130 Phase A，实验）](privileged-screen-core.md) | display 0 镜像抓帧 + 固定动词注入、一次性帧/动作凭据、能力/任务/确认三道门、低延迟 benchmark、无无障碍依赖与未真机验收边界 |
| [任务状态上岛](island-status.md) | 实时活动/胶囊两代判据、岛位选举、完成态、心跳收敛与验证范围 |
| [ADR0001](adr/0001-build154-boundaries.md) / [ADR0002](adr/0002-current-threat-model.md) / [ADR0003](adr/0003-virtual-screen-lifecycle-and-input.md) | 架构取舍、权限与数据边界、虚拟屏生命周期及后果 |
| [资料与来源依据](references-and-provenance.md) | 官方primary入口、工程上游关系、固定版本/摘要、许可与来源限制 |
| [固定构建输入](fixed-build-inputs.md) | 公开仓库预发布资产、12 项输入、SHA-256 锁定与还原流程 |
| [CI 构建与发布](ci-release.md) | 公开仓库 Actions 正式成品：触发参数、Secrets、门禁、签名核验与 Release 产物 |
| [CI 节点图](ci-nodes.md) | Actions 可复用节点、入口工作流、交接与耗时依据 |
| [Mod 身份与签名](mod-identity-and-signing.md) | DSH-A-Su 包名、证书、verifyPublishCertificate 与覆盖升级边界 |
| [文案长度政策](i18n-length-policy.md) | 十种语言的短标签与长句处理边界 |
| [首次引导重构设计](design/onboarding-20261031/README.md) | 4 步引导、部署胶囊、受限主界面、授权卡片、自适应布局与落地差异 |
| [DSH Web 独立进程设计（待确认）](design/web-process-isolation-20261102/README.md) | `:web` 进程划分、IWebHost 接口、维护屏障跨进程协议、故障场景与分阶段计划；未实现 |
| [proot 本体开销调查（T34）](design/proot-overhead-20261103/README.md) | 随包 libproot.so 对应 termux/proot v5.1.107.91、上游新修订无提速、停顿来源与 readlink 入口直答方案；未实现 |
| [跨应用文件访问（SAF）](design/cross-app-files-20261104/README.md) | 设备能力授权里的 SAF 授权簿、`/app/saf/*` 九个桥接端点、取消与超时、TOCTOU 固定身份、内置提示插件；已随本 PR 实现 |
| [物理屏动作提速（T105）](design/physical-screen-speed-20261107/README.md) | 事件驱动 settle、动作即观察（`observe=1`，含 MCP 默认开启）、紧凑识屏 `mode=delta/same`；动作凭据规则不变，未真机测量 |
| [外接设备页面变化观察（T134）](design/extdev-observation-20261110.md) | 外接 USB-ADB 目标的 logcat events / `dumpsys window` / 画面通道真机测量，`/app/extdev/watch` 事件采样 + `operate observe=1` settle，摘要绑定目标代次、只读不落盘 |
| [build352 覆盖升级维护页死锁（T139）](design/build352-maintenance-register-deadlock-20261110.md) | 维护事务内注册与 runtime-health 写入顺序冲突导致 ENV_NOT_READY 回滚的真机证据与 build353 修复（注册移到事务完成后、失败不回滚运行时）；含教训与验证清单 |
| [ADB 通道原生化设计（T42）](design/adb-native-20261103/README.md) | ADB 配对、私钥与客户端移到安卓壳，容器只经桥提交命令；含用户 Q1–Q9 决定与 E1–E4 取证，未实现 |
| [GitHub 凭据原生化设计（T50）](design/github-credential-native.md) | 壳内 OAuth Device Flow 登录、token 存 Keystore、容器经桥按需取 access token；待确认，未实现 |
| [物理屏两套观察路径设计（T107）](design/physical-screen-observation-20261107.md) | 无障碍动作 + 特权镜像读屏 + evdev 用户操作检测；A/B/C 对比、接口/授权/兼容/回退与 M1–M3 计划；未实现 |
| [build351 覆盖升级首启失败排查（T133）](design/build351-first-start-registration-20261110.md) | 插件注册 90s 超时 + 残留 profile 锁 + 「原因未分类」的源码级归因（③ 机制为主、① 锁生命周期与首启重复覆盖层加重、② 语义不足），含最小修复与复现/取证清单；只排查未改代码 |

## 用户操作与开发入口

- [README](../README.md)、[开发约束](../AGENTS.md)、[构建](../BUILD.md)、[贡献流程](../CONTRIBUTING.md)、[接手指南](接手指南.md)。
- [Standard](android-standard.md)、[Low](android-low.md)、[插件](plugins.md)、[虚拟屏开启](virtual-screen-guide.md)、[网页文件交互](web-file-sharing.md)、[v5备份格式](backup-format-v5.md)、[配置恢复](configuration-recovery-host.md)。
- [工程规范](engineering-standard.md)、[模块地图](module-map.md)、[安全模型](security-model.md)、[源码来源](source-provenance.md)、[下载校验](download-verification.md)。

## 当前操作与专题

| 场景 | 文档 |
|---|---|
| 首次安装、组件探测、按需修复、维护与中断恢复 | [安装与维护](install-and-maintenance.md)、[启动与诊断](startup-and-diagnostics.md)、[应用及受管更新](update.md) |
| 覆盖安装后白屏 / 不触发环境维护 / 内置插件依赖宿主控件 | [覆盖升级、运行时维护与插件依赖契约](overwrite-upgrade-and-maintenance.md) |
| 外部 PR / 外部合并的强制逐行审查 | [外部 PR 与外部合并审查流程](external-pr-review.md) |
| 引擎/静态加载器/seccomp、DNS、端口排查 | [运行与网络选择](runtime-and-network.md)、[引擎](runtime-engines.md)、[DNS](dns-configuration.md)、[端口](ports-and-networking.md) |
| 设备通道、3090协议、LAN、文件与系统文档入口 | [设备通道](device-channels.md)、[桥与文件](bridge-and-files.md)、[LAN](lan.md)、[Web文件](web-file-sharing.md) |
| 一键/手动/自动备份、恢复、快照与独立应急 | [备份与恢复](backup-and-restore.md)、[配置快照](config-snapshots.md)、[独立应急](emergency-recovery.md)、[v5格式](backup-format-v5.md) |
| 插件安装、系统/用户分层、协议、权限与重启 | [插件操作](plugins.md)、[插件运行与权限](plugin-runtime-and-permissions.md) |
| 临时内置修复：`file has not been read` 读取提示（上游 #11977）与撤除条件 | [dsh-fs-read-hint](fs-read-hint-plugin.md) |
| 临时内置修复：glob 跳过嵌套 git 工作树（T45）与撤除条件 | [dsh-fs-search-scope](fs-search-scope-plugin.md) |
| 内置移动 Web 适配、dsh-web-mobile 上游 3.3.5 与宿主集成最小集（服务端鉴权 / 安全区 / 齿轮几何让位 / 文件传输 / 多语言）；宽屏触屏会话行挤压（上游 3.3.1 已接管） | [移动 Web 适配补丁](web-mobile-patches.md)、[平板会话行挤压（上游 #255）](web-mobile-tablet-rows-20261031.md) |
| 首次连接提示补丁（T58）：为什么归档、不合并；切页返回黄标的真实行为与再修边界 | [T58 首次连接提示归档](design/first-connect-indicator-20261106/README.md) |
| PTY/简易终端、WebView/Gecko、桌面UA、PiP | [终端](terminal.md)、[浏览器内核](browser-engines.md)、[画中画](picture-in-picture.md) |
| 语言锁存、悬浮条、审批和任务通知 | [语言](i18n.md)、[悬浮与通知](overlay-and-notifications.md) |
| 源码/固定输入/签名/发布与测试证据 | [构建与发布](build-and-release.md)、[领域边界](agents/domain.md)、[议题流程](agents/issue-tracker.md)、[分诊用语](agents/triage-labels.md) |

每篇新专题注明核验日期、参考基线、真实生效时机及失败/验证边界。三篇Agent流程是F11新写维护说明，不伪称恢复旧原件，也不证明远端标签、权限或工作流配置。GLOSSARY按实际术语需求创建。

当前默认与术语：proot默认、proroot显式可选；默认Web端口为自动，备用只在本机保存；DNS auto并非全局IPv4优先。原生模型配置生产入口已移除，模型设置在Web；画中画默认关闭；一键备份先安全停止DSH/任务并保存无密码归档，外置工作区内容另备。精确定义以对应专页和当前代码为准。

## 历史材料如何阅读

CHANGELOG保留旧版本的默认值和性能记录，同时追加build190迁移说明。DSHA v1.1.6不是proroot上游版本；当前vendor锁v1.2.8。旧构建的回退、安装、签名、包名或测试数量不能用作当前交付结论。

当前源码发布补齐普通维护Markdown及其需要的历史发布说明，包括真实fix1、build156与旧接手原件；它们保持原归属。历史文内的`app/build`回执、旧本机APK、原始设备日志、截图和私有审计附件没有为修复导航而上传。它们是当时本机材料引用，不能当成当前仓库下载入口或本轮复测证明。当前维护请从本索引进入，不按旧脚本生成额外审计APK或清理用户数据。

历史报告原文保持；发布可见性检查区分当前指南链接、可用历史说明和未分发的本机证据。找到旧报告的真实原件会核对摘要后纳入，不凭空重建遗失回执。下表用于定位报告本身，不声称其所有原始现场附件都在源码包内。

## 文档更新与可复核性

F11以实际分发stage检查引用，而非用开发机全树存在替代交付闭合。以下历史材料按真实原件纳入精确白名单；它们仅证明原时点，不恢复旧格式化、审批、版本或验收要求。

| 真实历史文件 | 分发用途 |
|---|---|
| [build154 POLICY](audits/build154/POLICY.md) | 旧审计正文的当时约束说明；当前约束仍看AGENTS/现行专题 |
| [rc2全局审计总结](audits/2026-09-27-rc2-full-architecture/final-assessment.md) | build147当时的架构/交付范围；不是本轮设备回执 |
| [build155架构](audits/build155/architecture-assessment.md)、[剩余项](releases/build155-remaining-work-20261003.md) | 对应历史发布说明及build156评估的递归文档依赖 |
| [build156架构](audits/build156/architecture-assessment.md)、[剩余项](releases/build156-remaining-work-20261003.md) | 对应历史原件与当时未验范围 |
| [历史文档保存清单](audits/build154/history-document-sources.json)、[ShellService来源记录](audits/build156/shellservice-source-provenance.json)、[BUILD原件记录](audits/build156/source-docs-retained.json) | 3项新增显式JSON元数据；连同原6项共9项，不开放其他audit JSON |
| [Termux历史README](../tools/history/documents/tools/termux-jni/README.md) | F盘找到的3547字节原件，SHA `c29a880a9d7014d58b125bd57e6f7a52a4bae4bda64e76dd22a2819382f521ca`精确符合保存清单 |

保存清单记录的全部历史对象不因此全部随本包分发。特别是历史执行台账、原始设备图/日志等仍按各自保密与分发范围处理；来源JSON中的未知授权也不会因文件可下载而变成已证实授权。

为使原字节历史发布页的真实相对链接在GitHub/源码ZIP中可点击，F11新增以下5份**导航副本**。每份与所列源文件原字节相同；这是本轮导航复制，不是声称新追回了五份旧保存原件。既有历史发布原件不改写，也不按新位置重新解释其链接。第5份发布页是导航副本内继续引用的必要递归目标。

| 本轮导航副本 | 原字节复制来源 |
|---|---|
| [build156架构副本](../tools/history/documents/docs/audits/build156/architecture-assessment.md) | [原文](audits/build156/architecture-assessment.md) |
| [build156剩余项副本](../tools/history/documents/docs/releases/build156-remaining-work-20261003.md) | [原文](releases/build156-remaining-work-20261003.md) |
| [build155剩余项副本](../tools/history/documents/docs/releases/build155-remaining-work-20261003.md) | [原文](releases/build155-remaining-work-20261003.md) |
| [build155架构副本](../tools/history/documents/docs/audits/build155/architecture-assessment.md) | [原文](audits/build155/architecture-assessment.md) |
| [build155发布页副本](../tools/history/documents/docs/releases/v0.2.6-build155-20261003.md) | [原文](releases/v0.2.6-build155-20261003.md) |

两份固定上游原文另有8条**上游相对引用**，不代表这些目标存在于APK本地：mobile README（SHA `f6b8cf532640b096e07ceee33061b3ee63eff9d228369d36de5e98a278c14b93`）的4图及AGENTS映射到上游固定commit `646fe837440f9939228bdba0024d407eaac01128`；Termux原许可证（SHA `a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304`）的2目录及共享许可证映射到commit `6e2689f55295fa444be8ac8592c527c2c5ef3253`。门禁输出精确上游URL并标`upstream-source-relative`警告；`--strict-history`仍失败。源路径、源摘要或目标任一变化都不能沿用例外，其他缺失目标继续失败。没有改动这两份上游原文或APK资产。

1. 改默认值、设置项、环境变量、授权或端口时，先核ConfigStore/配置入口与实际调用链，补用户可见作用层、下次生效时机及迁移规则。
2. 同步专页、总手册、README和CHANGELOG；历史条目保留时间归属，不用新状态重写旧结果。
3. 文档引用文件存在与该文件进入Git/完整源码ZIP是两项检查；新维护页必须进入最终发布清单。
4. 原生API文档只说明平台合同；隐藏API、厂商能力和实际权限按当前代码与明确的设备证据声明。引用官方资料不代表上游认证本分支。
5. 记录测试对象、失败/跳过、增量或缓存复用及未运行范围。源码CI、Host、浏览器、实际APK与手机验收分开；本轮暂停设备验收。
6. 运行源码检查、锁/描述符与许可检查；包/签名与附件摘要由统一交付负责人核对。私钥、用户凭据、设备原日志和截图不作为文档附件上传。

## 随源码提供的历史说明

- [ROADMAP.md](ROADMAP.md)
- [adb-flow-audit.md](adb-flow-audit.md)
- [backup-upgrade-acceptance.md](backup-upgrade-acceptance.md)
- [backup-upgrade-baseline.md](backup-upgrade-baseline.md)
- [device-acceptance-2026-09-05.md](device-acceptance-2026-09-05.md)
- [extract-startup-speed-2026-09-10.md](extract-startup-speed-2026-09-10.md)
- [functional-audit-rc1.4.md](functional-audit-rc1.4.md)
- [issue67-recovery.md](issue67-recovery.md)
- [maintenance-rc1.4-verification.md](maintenance-rc1.4-verification.md)
- [optimization-backup-verification.md](optimization-backup-verification.md)
- [optimization-implementation.md](optimization-implementation.md)
- [optimization-runtime-verification.md](optimization-runtime-verification.md)
- [optimization-site-verification.md](optimization-site-verification.md)
- [optimization-transfer-verification.md](optimization-transfer-verification.md)
- [optimization-web-verification.md](optimization-web-verification.md)
- [pid-stop-fix-2026-09-10.md](pid-stop-fix-2026-09-10.md)
- [plugin-download-npm-fix-2026-09-05.md](plugin-download-npm-fix-2026-09-05.md)
- [plugin-layout-install-fix-2026-09-05.md](plugin-layout-install-fix-2026-09-05.md)
- [release-rc1.2-2026-09-06.md](release-rc1.2-2026-09-06.md)
- [release-rc1.4-2026-09-07.md](release-rc1.4-2026-09-07.md)
- [releases/v0.1.5-rc1-notes.md](releases/v0.1.5-rc1-notes.md)
- [releases/v0.1.5-rc1.md](releases/v0.1.5-rc1.md)
- [releases/v0.1.5-rc2-build129.md](releases/v0.1.5-rc2-build129.md)
- [releases/v0.1.5-rc2-notes.md](releases/v0.1.5-rc2-notes.md)
- [releases/v0.1.5-rc2.1-build130.md](releases/v0.1.5-rc2.1-build130.md)
- [releases/v0.1.5-rc2.1-build131.md](releases/v0.1.5-rc2.1-build131.md)
- [releases/v0.1.6-alpha1-build134.md](releases/v0.1.6-alpha1-build134.md)
- [releases/v0.1.6-alpha1-build135.md](releases/v0.1.6-alpha1-build135.md)
- [releases/v0.1.6-alpha2-build136.md](releases/v0.1.6-alpha2-build136.md)
- [releases/v0.1.6-alpha2-build137.md](releases/v0.1.6-alpha2-build137.md)
- [releases/v0.1.6-alpha2-build140.md](releases/v0.1.6-alpha2-build140.md)
- [releases/v0.1.6-alpha2-build141.md](releases/v0.1.6-alpha2-build141.md)
- [releases/v0.1.7-rc1-build146.md](releases/v0.1.7-rc1-build146.md)
- [releases/v0.1.7-rc2-apk-size.md](releases/v0.1.7-rc2-apk-size.md)
- [releases/v0.1.7-rc2-build147.md](releases/v0.1.7-rc2-build147.md)
- [releases/v0.1.7-rc2-emergency-recovery.md](releases/v0.1.7-rc2-emergency-recovery.md)
- [releases/v0.1.7-rc2-microphone.md](releases/v0.1.7-rc2-microphone.md)
- [releases/v0.1.7-rc2-notes.md](releases/v0.1.7-rc2-notes.md)
- [releases/v0.1.7-rc2-plugin-network-issues79-80.md](releases/v0.1.7-rc2-plugin-network-issues79-80.md)
- [releases/v0.1.7-rc2-web-identity-recovery.md](releases/v0.1.7-rc2-web-identity-recovery.md)
- [releases/v0.1.7-rc2-web-mobile-3.0.3.md](releases/v0.1.7-rc2-web-mobile-3.0.3.md)
- [releases/v0.1.7-rc2.1-build148.md](releases/v0.1.7-rc2.1-build148.md)
- [releases/v0.1.7-rc2.1-maintenance-20260930.md](releases/v0.1.7-rc2.1-maintenance-20260930.md)
- [releases/v0.2.0-rc2-20260930.md](releases/v0.2.0-rc2-20260930.md)
- [releases/v0.2.0-rc2-notes.md](releases/v0.2.0-rc2-notes.md)
- [releases/v0.2.1-rc.2-build190-20261019.md](releases/v0.2.1-rc.2-build190-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix1-20261019.md](releases/v0.2.1-rc.2-build190-fix1-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix2-20261020.md](releases/v0.2.1-rc.2-build190-fix2-20261020.md)
- [releases/v0.2.1-rc.2-build190-fix3-20261020.md](releases/v0.2.1-rc.2-build190-fix3-20261020.md)
- [releases/v0.2.21-build170-20261009.md](releases/v0.2.21-build170-20261009.md)
- [releases/v0.2.5-build154-20261003.md](releases/v0.2.5-build154-20261003.md)
- [releases/v0.2.6-build155-20261003.md](releases/v0.2.6-build155-20261003.md)
- [releases/v0.2.7-build156-20261003.md](releases/v0.2.7-build156-20261003.md)
- [releases/v1.2.0-rc1.2.md](releases/v1.2.0-rc1.2.md)
- [releases/v1.2.0-rc1.4.md](releases/v1.2.0-rc1.4.md)
- [releases/v1.5-alpha.2.md](releases/v1.5-alpha.2.md)
- [source-updates/20261019-build190-vscreen-port.md](source-updates/20261019-build190-vscreen-port.md)
- [stability-acceptance.md](stability-acceptance.md)
- [terminal-process-reaping-handoff.md](terminal-process-reaping-handoff.md)
- [ui-layout-plan-rc1.4.md](ui-layout-plan-rc1.4.md)
- [ui-layout-rc1.4-verification.md](ui-layout-rc1.4-verification.md)
- [update-flow-rc1.4-verification.md](update-flow-rc1.4-verification.md)
- [upgrade-dsh-0.1.5-alpha.2.md](upgrade-dsh-0.1.5-alpha.2.md)

## 历史原字节与可读视图

下列历史文件原本使用CRLF。为符合当前源码的LF规范，其常用文档路径提供仅换行规范化的可读视图；完整原字节另存tools/history，既有Git属性保护原字节，退休材料不参与部署或测试发现。没有补写原验收事实，视图和原件摘要分别记录。

| 可读视图 | 原字节 | 原件SHA-256 |
|---|---|---|
| [releases/v0.2.1-rc.2-build190-20261019.md](releases/v0.2.1-rc.2-build190-20261019.md) | [原件](../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-20261019.md) | `f172ce28b9cbc8933e8ae3bc7ad0f01f39441e3c0e9eecbbcf955b06ea432d37` |
| [releases/v0.2.1-rc.2-build190-fix1-20261019.md](releases/v0.2.1-rc.2-build190-fix1-20261019.md) | [原件](../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-fix1-20261019.md) | `d34ca245134ce842f404c52fe52060c9255bd31d4ecee2bc7c5966a0017c3c0c` |
| [releases/v0.2.7-build156-20261003.md](releases/v0.2.7-build156-20261003.md) | [原件](../tools/history/documents/docs/releases/v0.2.7-build156-20261003.md) | `8cc70cb1720736e0f9c7427b6b5399155b1983d4bfe51d755ece02dc0196cd55` |
| [source-updates/20261019-build190-vscreen-port.md](source-updates/20261019-build190-vscreen-port.md) | [原件](../tools/history/documents/docs/source-updates/20261019-build190-vscreen-port.md) | `7a37fa7c1155553d0fa767e629a5a437ccf33fcabb9d0e41b976fe7841890bc4` |
