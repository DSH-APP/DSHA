# DSHA build 131 稳定性验收

基线是 `main` 的 `dca04aed7c1a1468827a953bfd6295fc3ca44170` **加原有 build 130 未提交修改**，不是公开仓库该提交本身。开始时保存了 170 个已有变更文件、二进制补丁及逐文件摘要：`app/build/stability-20260914/baseline*`。build 130 的两份发布 APK 与其交付记录一致，原件继续保留。本轮版本为 `0.1.5-rc2.1` / `0.1.5-rc2.1low`、versionCode 131，dsh `0.1.5-rc.2`、Ubuntu base 10、包名和历史证书不变。

本轮只做本地交付；没有 git 提交、推送、上传、修改 Release，也没有卸载或覆盖手机正式 `com.dsh.client`。审计包不是交付 APK。

## 2–10 逐项证据表

| 编号 | 核实结论与最终状态 | 实际文件 / 符号、处理及证据 |
|---|---|---|
| 2.1 | 已实现并验证 | 原 `HttpShellService.handle` 无界行读取与 LAN 缺少绝对期限是静态确认的风险。`util/HttpProtocol` 在读取字节时限制行/字段/总量/数量，并以单调时钟执行含排队的 5 秒绝对预算。两服务接入；11 项 JVM 解析测试及实际 3090/LAN 超长、慢发、长查询验证通过。 |
| 2.2 | 已实现并验证 | 原 LAN 固定线程池配无界队列及每方向线程已替换为 `SocketDispatch` 的登记前准入、8+16 短请求、8 长流、8 反向 pump，总量最多 32。本地桥保留已有 4+16 有界队列，补齐最多 20 登记。3 项真实 socket JVM 测试、48 连接 LAN / 32 连接本地桥负载、停止/维护回收通过。 |
| 3 | 已实现并验证 | `LanProxyService.LanConnection/Exchange` 与 `HttpProtocol` 区分缺失/零/正长度，拒绝歧义 framing，只有合格 101 才升级。两版实际代理对模拟后端的 12 项矩阵通过：HEAD/204/304/1xx、零长度复用、大分块及 trailer、二进制/压缩、SSE/普通并发、WebSocket 拒绝与成功、断开/半关闭、异常短体及取消。 |
| 4 | 已实现并验证 | `plugin-dependencies.py` 绑定 pnpm 10.34.5 原生锁、清单/归档/实际文件与依赖图摘要；首次解析保存锁，后续冻结，离线缺包或缓存损坏明确失败。完整历史目录回退不重新解析。`plugin-transactions.py` 与 `register_plugin` 把目录、依赖快照、来源、启用配置/停用标记和加载记录纳入持久化提交/恢复；保留旧三文件日志读取。真实 pnpm 6 项、事务 5 项（含 8 个强杀提交边界及恢复中再次强杀）回归；不执行生命周期脚本或 pnpmfile。 |
| 5 | 已实现并验证 | `PluginRepository`、`PluginFragment`、`plugin-lifecycle.py`、`PluginActivationHooks`、`PluginInstallJournals` 接通静态预览→确认安装但停用→再次审阅启用→真实 Cordis 观察→失败停用与诊断保留。进程重启保留未确认记录；输入改变拒绝启用；原生一次性确认文件只在维护屏障内消费。实际坏插件闭环、原生事务恢复、10 项脚本审阅回归通过。静态检查不运行插件；同 UID 插件不是恶意代码沙箱。 |
| 6 | 已实现并验证；部分平台状态为注入 | `CredentialRead`、`KeyVault.read`、`ConfigStore.readApiKey`、`HarnessController`、`ConfigFragment` 区分未配置/可读/暂不可用/需处理；不能解密时保留密文、暂停依赖操作、重试或明确重输/移除。保存先加密读回再提交。3 项纯逻辑与两版各 4 项真实 Keystore/UI 场景通过；永久失效/暂时失败用明确异常注入，未改变用户锁屏或生物识别。 |
| 7.1 | 已实现并验证 | `RetainedCatalogue` / `RetainedDataActivity` 清单和分页、逐项批量检查、取消、导出/预检恢复；坏记录单独保留并报告。`NativeBackupJobs.prepareRestoreCopy/Tree` 复用认证、预检、确认和 `HostDataTransaction`。两版旧树 5 项真实测试通过：无 Bash 导出、错误密码不变、旧树确认恢复、私有副本恢复、`QuarantinedPluginReview` 只读复制并重建相对依赖；原件摘要不变。没有新增删除按钮或自动删除规则。 |
| 7.2 | 已实现待特定验证 | `DataFormatEvidence` 为归档/预检/运行时记录增加独立 schema、未知 formatId/epoch、所观察运行时和实际读写实现摘要；`RuntimeDescriptor.canReadDataWrittenBy` / `ManagedRuntimeTransaction.trimOlder` 更保守。4 项格式证据测试及原轮换保护回归通过。没有上游完整格式声明和全部历史样本，历史格式仍 unknown；同版本号不构成降级证据，发件人声明不变成本机已验证。 |
| 7.3 | 原本已有且已验证；本轮补回归 | 保留宿主 AEAD 完整认证后预检、范围恢复、维护竞争/取消、Bash 不可用救援、实际端口/鉴权/存储与会话写后重开/网页握手/guest 退出。两版现有核心、SAF 和备份工作流重复验证；保留错误密码、只读原件、未知文件与受损前代单测。强杀不是断电，合成旧包不是完整历史样本。 |
| 8.1 | 已实现待特定验证 | 当前真机 Android 13/API 33/4 KiB，独立非调试审计包完成两版更新覆盖、真实 WebView/Gecko、网络、插件、宿主备份、Keystore、双语短屏/字体/重建/旋转。Android 6/7、API 30、反馈 Android 16 平板及 16 KiB 内核没有连接，本轮未执行。新的空数据审计包安装被手机拒绝；本轮未把沿用审计数据称作全新 APK 冷安装。实际 PTY 命令/维护退出及私有/FUSE 附件存储已补测；息屏长期任务与全面多标签/文件选择设备矩阵仍需专门复测。 |
| 8.2 | 仅静态确认与有限测量；性能收益未验证 | 保留现有分包/缓存/省电/锁释放，不启用 R8，不删离线资产。取得当前手机代理负载、宿主与 Gecko 子进程 PSS、一次已有启动分阶段记录及包体分项。USB 充电、电流接口拒绝和未建立同数据冷/热/升级前后矩阵，不能给省电/提速百分比；Node PSS未取得。 |
| 9 | 已实现并验证（本地软件与现有设备范围） | `tools/verify-stability.py` 强制新鲜两版 JUnit、Lint、Release、Node、Python、APK 资产/ELF/签名；省略设备或缺密钥明确报告。最终机器清单/源码变更快照/补丁和 APK 摘要见本报告末尾及 build 131 发布记录。静态 16 KiB 对齐不是真机通过。 |
| 10 | 已实现并验证 | 更新当前 README 中英文、本版 CHANGELOG、BUILD、AGENTS、security-model 中英文及本报告。说明 AES/GCM、位置声明与实际授权、loopback/LAN HTTP、插件同 UID、密码归档与不删除原件边界；历史 build 130/129/1.1.10 记录保持。 |

## HTTP 规范与限额

采用 [RFC 9112 §6.3](https://www.rfc-editor.org/rfc/rfc9112.html#section-6.3) 的消息体判定、§7.1 分块/trailer、§9.3 连接复用，[RFC 9110 §7.6.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-7.6.1) 的逐跳字段及 [RFC 6455 §4](https://www.rfc-editor.org/rfc/rfc6455.html#section-4) 的实际升级握手。只支持项目需要的 HTTP/1.1 原点请求和 chunked；不扩展 CONNECT/任意目的地代理。歧义字段直接拒绝，不拼接猜测。

LAN 总头 64 KiB、请求行与字段各 16 KiB、最多 100 字段。本地设备桥总头 128 KiB、请求行 96 KiB，容纳 `DeviceShellPolicy` 8192 字符命令 UTF-8 百分号编码后最坏约 72 KiB；实际路由测试验证了大查询。16 KiB 字段为现有 43 字符 token 及普通浏览器头留余量，不代表所有第三方巨大 Cookie 均可接受。5 秒只约束未鉴权头；正文沿用流式 8 KiB 缓冲和业务自身策略，SSE/WS 不被头期限截断。流开始后出错关闭连接，不在已有正文中再写 HTTP 错误。

## 插件与原件保护的边界

锁文件证明可复现解析和文件完整性，不证明来源作者可信。审阅静态读取包名、版本、来源、摘要、依赖和缺项；预览保留并以摘要绑定确认。安装脚本、pnpmfile 不执行；`.npmrc`、`.env` 等不进入插件导出，仍保留用户原文件。插件自身代码可能包含秘密，系统不宣称能识别并移除所有自定义秘密。旧记录没有精确依赖快照时明确显示信息不足，但完整旧目录不因此被重新解析或删除。

激活观察与实际 Cordis 事件绑定启动 ID。修复了同一次已授权 proroot→proot 重试误取消新插件审批的问题：同启动 ID 保留审批但重新核对内容，新的未确认启动停用候选。真实加载失败保留诊断和源码，不回滚用户新对话。插件回退只恢复插件组合及相应原生记录。

保留管理没有自动删除。旧树默认只读；导出保持 BEST_EFFORT/PARTIAL 等真实范围；用户选择恢复仍先预检并确认目标。清单记录成功与本次验证不同，重复可读不能证明完整历史格式。容量限制仍生效，不用自动删除或提高限制换验收通过。

连续真机回归使用 `AuditOperationsScope`（只在审计 APK 中）：在原停止屏障内把既有合成作业目录和目录索引移入本轮 UUID 夹具，校验原件摘要，结束后恢复旧目录；本轮结果另保存在 `after-*`，不删除原件。正式 APK 不包含此类或测试 Provider。

## 实际设备与测量

- Redmi M2012K10C，Android 13 / API 33，arm64，4096 字节页；Linux 4.14.186-g7d6e94993-dirty；WebView 116，low 内置 Gecko 143。
- 包名 `com.dsh.client.rc21audit`，历史证书，非 debuggable；与发布代码共享构建，增加审计 Instrumentation/Provider/Activity 与合成测试资产。固定包名、清单和签名在安装前校验。正式 `com.dsh.client` 的版本码 129 和安装时间未改变。
- 首次独立 `com.dsh.client.stabilityaudit` 安装返回 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`（`03dfe1bb-9d92-4e2e-bce5-211cd449ed6f`）。这是手机安装许可，不是沙箱自动审批；没有重试覆盖正式版，也没有把旧环境当全新安装。
- 标准版网络最终代表记录 `f9eca619-3820-415c-9856-fc6776738b64`：48 连接负载、最多 16 排队，峰值登记 25、拒绝 24；慢头 5003 ms；两条 SSE 下 8 次普通往返 63 ms。FD 从 158 到负载 219、夹具全部清理后 143；线程从 26 回到 26。PSS 初始 69465 KiB、SSE 93754、负载 94111、清理后 95086，不能宣称堆内存恢复原值。这是包含客户端和模拟后端的整个审计进程，不是代理独占开销。
- `app/build/stability-measurements/4d9bdf92-d3e2-43f3-a6dc-2a895066515a/measurement.json`：30 秒指定窗口内 5 次串行采样，兼容版试运行，USB 充电，前台 Awake。宿主和 `:tab8`/`:gpu`/`:crashhelper` 分进程 PSS 保留，未累加共享内存；Node 进程未被取得，不以 0 代替。不同 PID 采样时刻不完全同时。电流/电压 sysfs Permission denied；没有应用归因的耗电数值。
- `plugin-startup-inspection.log` 中一次已有环境启动：检查工具 0.3 s、创建进程 0.7 s，proroot glibc 退出后单次 proot 重试，实际官方鉴权行 8.6 s。它不是冷安装、不是最终网页就绪总耗时，也不是与旧版的速度比较。运行时 trial 另外实际验证了网页握手；慢等待未强杀。
- 包体分项和 build 130 对照由最终机器记录提供。没有实施 R8、移除兼容内核或离线内容，不声称速度/耗电提升。

## 复现入口与不能替代的验证

本机准确软件入口（原密钥只通过已有环境变量引用，不写入报告）：

```powershell
$env:GRADLE_USER_HOME='F:/DSHA/_toolchains/gradle-user-home'
# DSHA_KEYSTORE 指向已授权历史签名材料；不要改成另一把密钥。
& 'C:/Users/18768/AppData/Local/Programs/Python/Python311/python.exe' -B tools/verify-stability.py --node 'C:/Program Files/nodejs/node.exe'
```

该入口执行两版 `test*DebugUnitTest`、`lint*Release`、`assemble*Release`，Node `test-startup-diagnostics.mjs` / `test-issue67-startup.mjs`，Python `test-plugin-{discovery,dependencies,transactions,review}.py`，以及两个实际 APK 的 `verify-dsh-upgrade-apk.py`、`audit-standard-apk.py`、apksigner 和 aapt。完整 argv、退出码及日志在机器清单。

实际工具目录虽名为 `jdk-17`，执行返回的是 **Oracle JDK 25.0.4**，Gradle 9.3.1 的 JVM 也指向 `D:/develop/JDK`；代码仍保持 Java 17 source/target，未升级项目语言要求。两项旧进程夹具此前依赖 Windows 标准输出默认编码，重跑出现 GBK/UTF-8 差异；现显式声明夹具 stdout/stderr UTF-8，保留原字节/保密断言。新增报告模板测试发现较短模板抢先匹配，现按完整模板特异性排序，未对任意用户文本做替换。

独立设备复测：先用 `--init-script tools/device-backup-audit.init.gradle '-Pdsha.auditPackage=com.dsh.client.stabilityaudit' :app:assembleStandardDeviceAudit :app:assembleLowDeviceAudit` 构建；在新测试设备允许安装后用 `tools/run-backup-device-audit.py --serial <serial> --package com.dsh.client.stabilityaudit --apk <对应审计APK> --mode <模式>`。现有已授权审计包使用默认 `.rc21audit`，不传正式包名。

最低 API 23/24/30、Android 16 平板与 16 KiB 设备：运行支持 flavor 的 `core,runtime,network,bridge,credential,plugins,plugin_workflow,plugin_recovery,retained,workflow,io,ui,issue67,attachments`；`style --language zh/en` 验证所有原生样式。补测终端标签/维护、真实多文件/图片/流式附件、系统文件回调、息屏后 Web/终端恢复。真机数据必须为合成或明确获准的数据，不提高最低 API 绕过。

新设备冷安装从全新审计包开始，`runtime` 实际准备离线环境；随后在相同受控目录做支持的升级与跨 flavor 覆盖。真实 v1–v4 历史归档需要合法密码及可核对原始数据，在独立审计安装预检、按范围恢复、逐文件核对与读回。真实断电、云盘 Provider、多 profile/来源全组合仍需专门样本；现有强杀和测试 Provider 不替代它们。

息屏空闲、持续任务、LAN 长流的能耗对照需同机、同数据、相同无线与亮度、未充电条件，并用可读取的电池/外部测量设备分时记录。现有 `measure-stability-device.py` 是只读观测入口，不能把 USB 充电期间的设备电量变化称作应用功耗。

## 失败样本与复验

失败报告原样保留：`14fb8288-d08e-4159-b0c6-e56451bd3409` 为模拟后端 IPv6/生产 IPv4 夹具不一致；`52753373-40de-4a71-a226-a4d51497a401` 暴露同启动 ID 审批被兼容重试取消；`97003489-6647-4c19-85a8-3e63b3e3e272` 暴露旧树预览 provenance 操作标识不符合已有策略。实际实现修复后相应回归通过。没有放宽归档操作白名单或鉴权。

`b9236fb3-08d3-4784-9ef0-e5b0539a9f6c` 为 Android `/data/data` 别名夹具未规范化；`c79ca3bd-201d-46a2-85bf-feded1b7605d` 为恢复失败阶段断言过时；`7f76bc2f-cb2e-402f-bcec-03ac91e3b177` 为导出与恢复的失败阶段混淆。恢复保持 FAILED_RETAINED，目标写入失败保持原有 FAILED，均检查具体错误及保留数据。未把测试错误写成产品事故。

`3c8491e4-09a5-4767-9a64-dc2c9c358cbd`、`2947c5d4-8be6-4f8c-ace1-7bf642e3a7ea` 在连续合成验收后作业未获准继续；独立测试命名空间和更具体错误记录补齐后闭环通过。没有删除既有保留目录或放宽生产上限。`b87070d7-63ee-4b8a-98a3-cd33b1b65453` 保存编码/模板失败的全量软件报告，不当作通过。

## 最终结果与交付索引

完整软件报告：`app/build/stability-acceptance/2ff8c8ca-07b7-464e-b561-f2a3066ba723/manifest.json`。两版各 **520 项，519 通过、1 原有跳过、0 失败/错误**；新增 29 项 JVM 测试。原有跳过未增加。Python 宿主 discovery 9 项（4 个原有 Windows 跳过），dependencies 6、transactions 5、review 10（1 个 Windows 真实软链跳过）；四套在 Android 实际执行均无跳过。两版 Lint 通过，仍有既有警告；离线资产、官方模块、arm64 ELF、16 KiB 段及 RELRO、签名通过。

最后软件 PASS 后只更新本文与发布文档；交付前再次比对全部受检源码及资产摘要。产物不包含 audit Instrumentation 或 Provider。

| Flavor | 实际模式 | 结果 | 报告 UUID |
|---|---|---|---|
| low | attachments  | PASS | `2af52882-0b36-402c-b5cb-5a1b7df6dcfc` |
| low | bridge  | PASS | `3cf23ef7-c80d-492c-a13b-b662bd8cbeaf` |
| low | core  | PASS | `89d1d2cd-6914-40f3-bd34-32969c3646de` |
| low | credential  | PASS | `71757cee-e4b4-47db-9b80-d61264f0bff2` |
| low | io  | PASS | `0e14b5ab-2dd4-4a42-9ed0-5b0502364604` |
| low | issue67  | PASS | `70196658-4307-41e9-a028-67ea1cf7a424` |
| low | network  | PASS | `2e3a97c7-6677-45ee-80d9-7417854efc71` |
| low | plugin_recovery  | PASS | `ca97be52-4e7c-49e5-98cf-38251aeded52` |
| low | plugin_workflow  | PASS | `e20f73a2-7562-42b5-9663-10e584836dd0` |
| low | plugins  | PASS | `e7df3576-4ac9-4b5a-9175-4748fca00ed9` |
| low | retained  | PASS | `35898ee9-33d4-46ad-a7c2-08d7d8f0c34d` |
| low | runtime  | PASS | `fcf16ed8-3f2b-4b9a-810d-527e8ba9bf87` |
| low | ui  | PASS | `95806ee1-3c30-4d28-b3ca-2f9985308131` |
| low | workflow  | PASS | `ee2746a0-22bb-4aeb-bb54-38efc03771f0` |
| standard | attachments  | PASS | `17a99ff1-e829-46ee-aa1f-65aca66d0739` |
| standard | bridge  | PASS | `18b46ccc-a23c-43ed-a3d8-e60ed9b35197` |
| standard | core  | PASS | `354b1d86-1814-45e8-9585-f0316b68a058` |
| standard | credential  | PASS | `42fae9ca-fd19-44d1-8ae8-334f414b054c` |
| standard | io  | PASS | `c5e580b5-5487-42b3-a0cf-103c8d813fae` |
| standard | issue67  | PASS | `10b0e8ae-28c9-4b4c-8d6d-34795fef29c3` |
| standard | network  | PASS | `f9eca619-3820-415c-9856-fc6776738b64` |
| standard | plugin_recovery  | PASS | `7411ec76-5a83-41aa-ad1d-ee80b8e9f127` |
| standard | plugin_workflow  | PASS | `fbdb70b2-0d4c-4847-afed-3a5249aaa67b` |
| standard | plugins  | PASS | `6fbbe200-0a1c-4c7e-88f2-57c9977a2bfa` |
| standard | retained  | PASS | `e30b7007-6b9d-4823-ac74-b6c3f19bf220` |
| standard | runtime  | PASS | `0edc7865-eaf5-47b8-a945-c9a9c90f3f20` |
| standard | style en | PASS | `7e304782-6021-47e1-928c-53b5d06a988c` |
| standard | style zh | PASS | `8619b531-4319-4315-b6c0-329323f04178` |
| standard | ui  | PASS | `d93555b2-4209-480c-8d64-b487b37e6ff6` |
| standard | workflow  | PASS | `67348319-d0a0-4ec0-8d6d-1baea544b37e` |

表内报告位于 `app/build/backup-device-validation/<UUID>/result.json`，并有 instrumentation.log；UI 附 View.draw 图片、style 附像素检查报告。每份记录含其实际审计 APK 摘要，不将不同阶段的 APK 冒充同一个文件。最后的实现变更仅为保留副本详情的范围/保护说明，受影响页面另行验收，网络/运行时协议未改变。

`f0be92aa-46a1-4ca4-8aef-104e2da9575f` 的 6 组像素样式无违规，但导航被前一坏插件测试留下的恢复请求打断；仅审计夹具临时保存并清除这个导航请求，结束恢复原值，原失败记录未删除。随后中英文完整导航通过。

生产安装最后只读核对：`versionCode=129 minSdk=30 targetSdk=37`, `versionName=0.1.5-rc2`, `lastUpdateTime=2026-09-12 17:44:24`。测试没有覆盖原安装。

最终清单：`release/dsha-0.1.5-rc2.1-build131-manifest.json`。源码修改快照与补丁在 `app/build/stability-20260914/final-delivery/`，保留 build 130 的 baseline 快照；机器清单记录每份证据的摘要及最终文档摘要。缺设备、历史样本、冷安装许可和能耗条件的项目继续是缺口，不能据此称全面发布验收完成。
