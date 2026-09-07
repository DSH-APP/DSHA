# rc1.4 更新流程修复与验证

## 最终集成验收 · 2026-09-08

主流程已完成两版 Release、Lint、JUnit 与 LowDebug AndroidTest 编译。最终 debug 自插桩 **135 条 Android 断言通过**，包括新增的旧来源迁移和实际进度控件阶段展示。原始结果见[功能验收](functional-audit-rc1.4.md)。测试使用可控清单及安装校验执行器；系统安装器、未知来源权限往返和厂商后台策略仍按下文保留实机覆盖限制。

以下保留协作者阶段记录；“未运行 Gradle”“新增断言尚未执行”描述当时分工，不是最终集成状态。

日期：2026-09-07，补充修复于 2026-09-08。范围为更新审查问题、阶段进度展示和旧任务来源迁移，未修改应用版本、环境版本或发布文件，未运行 Gradle。工作区原有及其他协作者的变更保留。

一次独立测试包安装被手机系统拒绝，故未作为通过证据；后续使用 debug self 入口由主流程测试。

## 修改文件与行为

| 文件 | 本次修改 |
| --- | --- |
| `app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java` | 启动后台检查与提示去重；候选查询通道的保存/恢复；跨通道隐藏候选与 APK 并拒绝下载、续传和安装；安装校验前后核对候选身份。清单也使用可注入 Transport，便于确定性离线测试。 |
| `app/src/main/java/com/deepseekharness/app/core/UpdateRepository.java` | ViewModel 持有安装校验请求、结果与错误；新页面接手；结果一次性领取；离开页面时使回调失效；进程恢复给出显式重试状态。 |
| `app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java` | 观察并展示保留的安装状态；校验期间禁用冲突操作；只在页面恢复且未保存状态时打开系统安装器；保存安装中断标记。 |
| `app/src/main/java/com/deepseekharness/app/ui/UpdateUi.java` | 按显式检查/下载/核验阶段展示进度，安装校验显示加载动画；完成后等待领取的结果不再转圈。 |
| `app/src/main/java/com/deepseekharness/app/util/UpdatePolicy.java`、`app/src/test/java/com/deepseekharness/app/util/UpdatePolicyTest.java` | 依据旧选择契约恢复可确定的来源，稳定包来源不作猜测；单测锁定迁移及未知标记规则。 |
| `app/src/main/java/com/deepseekharness/app/ui/MainActivity.java` | 仅追加启动检查订阅与 Snackbar 入口、焦点/生命周期处理；没有修改导航选择、Fragment 事务、返回栈或标题逻辑。 |
| `app/src/androidTest/java/com/deepseekharness/app/core/UpdateFlowInstrumentation.java` | 独立目录、虚拟清单和可控执行器的更新流程断言；不调用真实下载、联网或系统安装器。 |
| `app/src/debug/java/com/deepseekharness/app/core/UpdateFlowAuditInstrumentation.java` | 上述断言的 debug 包内 self-instrumentation 副本，仅更改类名及入口注释；不依赖额外 test APK。manifest 由主线程注册，本次未改。 |
| `app/src/androidTest/java/com/deepseekharness/app/core/UpdateProcessInstrumentation.java` | 反射夹具补 `candidateChannel`、清除旧 `verifiedApk`，断言持久来源；打开测试页面时跳过启动自动检查。 |
| `app/src/androidTest/java/com/deepseekharness/app/core/OptimizationInstrumentation.java` | 独立偏好明确选择预览通道，反射夹具补来源及校验状态；重建断言来源并跳过启动自动检查。 |
| `app/src/androidTest/java/com/deepseekharness/app/Rc13Instrumentation.java` | 仅为下载夹具补来源、清除旧校验文件，并明确要求预览通道，不修改用户通道设置。 |
| `tools/test-update-flow.ps1` | 现在仅执行本地 javac，包含 debug 副本和旧 androidTest 夹具；已移除设备参数、APK 打包/安装/卸载与设备命令。 |

启动开关通过 `ConfigStore.isCheckUpdate()` 读取。自动检查使用已保存通道，每个应用进程至多发起一次；已有检查/下载或待恢复下载时让已有任务继续。只有发现新版本才产生提示。主界面处于 RESUMED 且有窗口焦点时显示 Snackbar，用户点击后打开更新页，不自动下载。提示在 Snackbar 的 `onShown` 后才消费，旋转或进入 Web 打断显示时仍保留待提示状态。

候选另存 `checkedChannel`，因为预览查询可以返回稳定版本，不能只看 `Release.channel` 判断查询来源。切通道立即发布失配状态；即使清单失败，旧候选及已下载文件仍留存，但不会出现在新通道的下载/安装入口。切回明确的来源通道后，网络失败仍允许使用原候选、分段进度或已下载包。成功取得不同候选时清除旧 APK 的就绪资格；成功取得空清单则清除旧候选。

旧契约核对：提交 `9e55945` 中的 rc1.3 仅保存当前所选通道；本地旧 rc1.4 Release 编译产物的 `UpdateEngine.save()` 把 `Release.channel` 写入任务，`setChannel()` 又会在联网前单独保存所选通道，二者不能建立可靠的查询来源关联。旧选择规则明确禁止稳定通道接收预览包，因此缺字段的预览包可确定来源为预览；稳定包可能来自两种查询，必须视为未知。未知或无效来源保留文件、提示联网重新检查，禁止下载/续传/安装；用空字符串持久保存“未知”，避免下次恢复再次推断。联网成功后重新建立当前通道候选，旧 APK 不直接继承就绪资格。

更新状态增加 `IDLE / CHECKING / DOWNLOADING / VERIFYING` 阶段。缓存字节数继续保留用于失败后的续传入口，但检查清单及下载后核验不展示该字节进度，只显示 indeterminate 动画；安装前校验使用独立的 `InstallState.verifying` 显示动画。等待页面领取已完成校验的结果时隐藏动画；真实下载仍显示字节数与百分比。

校验工作不捕获 Activity。旋转后使用同一个 ViewModel 接手，完成结果留存到更新页恢复；领取时再检查候选、通道与文件身份，避免校验期间被其他入口改变。页面结束后不再交付结果；进程被回收则显示“安装前校验已因进程重建中断”，由用户重新点击安装。文件摘要、包名、版本、变体和签名的原有校验仍保留。

## 已执行验证

- 标准版与兼容版：四个生产文件及新增 instrumentation 的 Java 17 独立编译均通过。使用本机 Android 37 SDK 和已有 Debug 编译产物/缓存依赖；这不等同于完整 APK 构建或 Lint。
- 通过独立 javac/JUnitCore 运行 `UpdatePolicyTest`、`ResumableDownloadTest`、`FileIntegrityTest`，结果 **OK (12 tests)**。覆盖稳定/预览选择、续传范围、取消重试、损坏前缀拒绝、已完成包免重下、摘要/大小及中断。
- 修改文件的 `git diff --check` 通过。
- debug 副本与原断言逐字比对，仅类名/注释不同；标准/兼容两版的更新核心、两个 Activity、debug 入口及四个 androidTest 入口均独立编译通过。
- 2026-09-08：`UpdatePolicyTest` 独立 javac/JUnitCore **6 项通过**，其中 3 项新增用例覆盖旧稳定来源歧义、旧预览唯一来源、显式来源及未知标记。包含 `UpdateUi` 和迁移策略的标准/兼容独立编译通过；新增设备端迁移与实际进度控件断言仅完成编译，未在本任务执行。

本地证据（均位于忽略的构建目录，不是发布文件）：

- 标准版编译：`build/update-flow-selftest/963f692095b84e9f89c15d626a539218/compile.args`
- 兼容版编译：`build/update-flow-selftest/b2b2cc6dfea54563bf1594134d196d9c/compile.args`
- JUnit：`build/update-flow-junit/result.txt`
- debug 入口与旧夹具标准版编译：`build/update-flow-selftest/7d9d70e4b05e4b939b98c6338b85faeb/compile.args`
- debug 入口与旧夹具兼容版编译：`build/update-flow-selftest/0265c6e5068240a990196aefafff54db/compile.args`
- 旧保存契约字节码：`build/update-flow-legacy-contract.txt`
- 迁移单测：`build/update-policy-migration-junit/result.txt`
- 阶段/迁移最终编译：标准 `build/update-flow-selftest/4ab46828a01c4e429f6bc46a520509df/compile.args`；兼容 `build/update-flow-selftest/0527edf7aa4c47a99ad1f6c5a4dcad40/compile.args`

## 新增自测与后续设备验收

脚本现在只做本地编译，可用参数指定 SDK、缓存与 JDK 路径，不再接受设备参数。debug self-instrumentation 类名为 `com.deepseekharness.app.core.UpdateFlowAuditInstrumentation`；由主线程添加 manifest 注册并在其设备验收空档运行，本任务不操作手机。

```powershell
./tools/test-update-flow.ps1
./tools/test-update-flow.ps1 -Flavor low
```

新增设备断言覆盖：启动开关关闭、所选通道、重复启动检查、提示显示确认、网络失败无新版本提示；两种发布通道候选的来源隔离、安装/下载拒绝、进程恢复、切回原通道及分段保留；成功切通道/空清单；ViewModel 与 LifecycleOwner 交接、重复点击、后台保留、一次性领取、清理后的迟到结果、进程中断重试、过期候选拒绝及校验失败重试。

阶段/迁移补充断言：旧任务两种所选通道 × 两种发布通道；缺失、空白、损坏和 JSON null 来源；未知来源离线重试/切通道/重建不解锁，联网确认后恢复候选。另在主线程只创建未附着窗口的实际更新页控件，验证缓存 100% 的自动/手动重查、部分下载后的检查、真实 50% 下载、下载后核验、安装校验动画与完成后等待领取的显隐和按钮状态；不启动 Activity 或安装器。

待完整应用构建后补充真实 UI 验收：

1. 分别关闭/开启“启动时检查更新”，冷启动主界面；对稳定与预览通道确认结果。检查过程中连续旋转、切换日夜模式及打开 Web，回到主界面确认提示只在可见时出现，点击提示才进入更新页。
2. 原通道下载完成后断网切另一通道，确认明确标注原来源且没有可安装入口；重建页面仍失配；切回来源通道确认保留的包和重试入口可用。
3. 对真实 APK 点击安装并立即连续旋转/切换日夜模式，校验完成后只启动一次安装器；校验期间按 Home，返回后继续；退出更新页后不得迟到启动；进程恢复显示重试提示。
4. 核对实际 APK 的签名、摘要和系统未知来源权限往返。本次确定性 ViewModel 测试替换校验执行器来控制完成时机，不代表这些系统路径已经实测。

已修复 `Rc13Instrumentation`、`UpdateProcessInstrumentation`、`OptimizationInstrumentation` 中的旧反射夹具：其预览候选同时声明来源为预览查询，注入新候选时不沿用另一 APK 的校验资格。UpdateProcess/Optimization 打开主界面时屏蔽自动检查，避免其预先准备的候选被后台真实清单替换；重建时检查持久来源。没有放宽生产引擎的通道或 APK 校验规则，也没有修改这些测试的其他验收流程。
