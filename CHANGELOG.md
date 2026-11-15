## 0.2.2-20261111.0200-dsh0.2.2-rc.1 / build357（0.2.2 正式版：内容与 build356 一致，发布资产按官方格式交付）

- 正式发布 0.2.2：源码与 build356 相同（身份 upstream / 包名 dsh.android.client / E7E3 证书），仅提升 versionCode 到 357 并去掉预发布标记。
- 发布资产对齐官方旧格式：`dsha-0.2.2.apk` + `dsha-0.2.2.apk.sha256` + `DSHA-0.2.2-<时间戳>-source.zip` + `.sha256`；构建证据留在 Actions artifact，不再作为 284MB 资产随包分发。
- Release 说明分为「更新内容」与折叠的「构建与校验」两段；校验段保留 commit / run / 证书 / 固定输入摘要，便于下载后核对。
- 未改变：受管运行时（runtimeId `8e54085c`）、固定输入 `fixed-inputs-20261105e`、内置 DSH 0.2.2-rc.1。

## 0.2.2-rc.1-20261111.0100-dsh0.2.2-rc.1 / build356（官方发行身份正式接续：切回 dsh.android.client / 历史 E7E3 证书，版本号由 0.2.1-rc.5 提升为 0.2.2-rc.1；内容与 build356 源码一致）

- 发布身份切回官方：`-Pdsha.identity` 默认值由 `mod` 改为 `upstream`，构建产物回到包名 `dsh.android.client`、显示名 `DSHA`、历史 E7E3 发布证书（SHA-256 `e7e3a31a…b22a53f5`）；旧 Mod 身份 `io.github.sunsetrne.dsha` / A4B0 证书保留为非默认身份，仅用于历史包与共存验证（`app/build.gradle`、`ci/release-identity.upstream.json`）。
- 版本号：DSHA 修订号由 `0.2.1-rc.5` 提升为 `0.2.2-rc.1`（versionCode 保持 356，源码内容与 build356 相同，未重新计入新构建号）；`ci/release-identity.json`、README、AGENTS 与十语构建说明同步。
- 发布身份文件：`ci/release-identity.upstream.json` 的允许发布仓库由 `ym2025szz/DSHA-prerelase`（私有）改为 `DSH-APP/DSHA` 的 `main`，公开仓库。
- 未包含：本次不改变任何 app 行为、运行时内容与固定输入（`fixed-inputs-20261105e` / runtimeId `8e54085c` 均不变）；签名正式包仍只能由持有 E7E3 私钥的 CI 生成，本地只能产出测试签名包。
- **未验证 / 未验收**：官方身份包尚未真机验收；覆盖升级（0.2.1-rc.2 → 本版）必须用正式签名包验证，测试包不能代替。

## 0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1 / build356（build355 布局修正为已确认的 T143 v3：设置页顶部空白收紧 + 本地插件两个独立卡片；纯 app 层，受管运行时不变 runtimeId 8e54085c）

- build355 布局修正（T143 v3，`d3c07b4f8`）：build355 正式包内实际是 T143 首版布局（单卡片并排、设置页旧间距），本版改成用户在 build353 `.debug` 预览包上确认的 v3。只改 `app/src/main/res/layout/fragment_settings.xml` 与 `plugin_list_footer.xml` 两个布局文件，无 Java、无文案键、无点击行为变化。
- 设置页顶部空白收紧：页内「设置」大标题已移除后，容器 `paddingTop` 由 `shell_page_top` 改为 `shell_section_margin`（10dp），第一组 `ShellSectionLabel` 去掉 `layout_marginTop="@dimen/shell_section_gap"`（24dp），首组不再叠加两段留白，直接接外壳页头。
- 插件页本地插件入口改为两个独立卡片：外层 `pluginLocalCard` 不再套 `ShellCard`，只作横向容器；「导入插件包」与「导出插件」（`btnImport` / `btnExport`）各自使用 `ShellCard` 圆角包裹、`?attr/selectableItemBackground` 按压前景、`clickable` / `focusable` 与 `shell_row_min_height` 内边距，左右各 6dp 外边距形成 12dp 间隔。副标题与右侧箭头保持移除，id、文案键与点击语义不变。
- 受管运行时不变：改动只在两个 app 层布局文件内，不在 `runtime-descriptor` 的输入内；合并树用 `tools/prepare-runtime-descriptor.py --check` 校验 **`8e54085c`** 不变（205 个输入）。fixed-inputs 冻结沿用锁定的 `fixed-inputs-20261105e`，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。
- 版本与范围：版本码提升为 356（基于已发布的 build355 / `584390e75`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。这是一次只修正已发布布局形态的 hotfix；设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。build355 已作为正式包发布（tag `dsha-a-su-build355-20261110-150000` @ `584390e75`）；目标 tag `dsha-a-su-build356-20261110-170000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：v3 形态已在 build353 `.debug` 预览包由用户确认真机效果；build356 整体尚未真机验收，平板横竖屏、短屏与 1.3× 字体下的顶部留白与两个卡片间距仍待核对。验收项是 354→356 覆盖安装成功、数据完好、READY，以及设置页与插件页 v3 形态在真机上的表现；build355 已发布但整体尚未真机验收，T145 / T146 核对点按 build355 的记录继续跟踪。
- 验证范围：发布身份与前置检查（versionCode 356 / versionName 与 `ci/release-identity.json` 一致；`check-release-prerequisites.py` PASS）、固定输入按锁 `fixed-inputs-20261105e` 从 prep/build353-release 复制并记录逐文件 sha256（/root/.cache/prep-build356/fixed-inputs-copied.sha256，与 build355 回执逐字节一致；恢复归档另记 recovery-archives-copied.sha256）、宿主 contracts 整组 12 项（固定输入运行时工具 / descriptor 源 205 assets + 481 launchers / 第三方声明 / 内置插件 / 凭据路径 / semver / host manifest 251 / 源卫生 / UI i18n newFindings 0 / changelog / 文档链接 55 文档 1253 链接 0 error）、`prepare-runtime-descriptor.py --check`（`8e54085c`）、全树 Java 格式（1692 文件 / 0 待格式化）、`git diff --check`（54 文件 / 8 分片 0 错）、`UpdateVersionPageTest`；全量 `:app:testStandardDebugUnitTest`（4427 例，10 例 aarch64 Robolectric 环境基线失败如实记录，0 error / 0 跳过）与 `:app:lintStandardRelease`（0 error / 599 warning，任务通过）；host 测试 fast 90/95、runtime 72/128——失败均为本机环境 / CI 夹具缺失（与本次布局改动无关，不预写通过）；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build356 更新说明](docs/releases/build356-changes.zh.md)。

## 0.2.1-rc.5-20261110.1500-dsh0.2.2-rc.1 / build355（T145 启动页快捷入口收拢为「+」气泡 + T146 设备能力授权页精简；纯 app 层，受管运行时不变 runtimeId 8e54085c）

- 启动页快捷入口收拢为顶栏「+」气泡（T145，`3d03cb12a`）：启动页不再显示整段快捷入口卡片（终端 / 检查与维护 / 访问地址），顶栏右侧新增「+」按钮，点按后从锚点展开气泡（`ui/QuickActionsBubble`）；启动页气泡含四项——下载记录、关于 DSHA、运行环境 · 检查与维护、访问地址（带「查看本机与局域网地址」副标题），其它根页只保留下载记录与关于。气泡是 `PopupWindow`（点外部 / 返回键 / 再点「+」关闭，`INPUT_METHOD_NOT_NEEDED`），每行 `contentDescription=` 标签、可聚焦并复用 `selectableItemBackground`，可见项与顺序由纯逻辑 `util/QuickActions` 决定；四项之间三条 `shell_line` 分隔线，非启动页只有两项时只留第一条。启动日志、安全启动与恢复按钮及其 id 不变，终端入口继续由底部导航承担。
- 设备能力授权页精简（T146，`e2d8006fc`）：首页移除顶部四个概览胶囊与固定头部；设备执行通道 / 屏幕操作改为两行入口，状态摘要来自 `GrantSummary` 的只读判据（通道 = Root 档位 + Shizuku 状态；屏幕 = 无障碍 + 暂停 + 确认方式），长译文最多三行换行，不使用省略号截断。原卡片内容分别迁到独立页 `DeviceChannelsFragment`（Root / Shizuku·Stellar / ADB / 外接设备与 root 命令拦截档位）和 `ScreenOperationFragment`（无障碍设置、屏幕识别模式、暂停、确认方式与敏感界面交接、虚拟识屏）；开关、验证、配对与二次确认流程不变。`SettingsPanes.open` 同时兼容单栏（推进返回栈）与双栏（≥840dp 在右栏推进并可返回）；`MainActivity` 顶栏补「设备执行通道」「屏幕操作」子页标题；抽出 `GrantsUi` 共用分段与折叠构件。
- 受管运行时不变：T145 / T146 都是 app 层界面改动，不在 `runtime-descriptor` 的输入内；合并树用 `tools/prepare-runtime-descriptor.py --check` 校验 **`8e54085c`** 不变（205 个输入）。fixed-inputs 冻结沿用锁定的 `fixed-inputs-20261105e`，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。
- 版本与范围：版本码提升为 355（基于已发布的 build354 / `cdb2ea51a`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批含 T145 / T146 两条线，属于 app 层界面精简；设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。build354 已作为正式包发布（tag `dsha-a-su-build354-20261110-130000` @ `cdb2ea51a`）；目标 tag `dsha-a-su-build355-20261110-150000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：T145 只在 TB321FU（Android 16 / 1600×2560 竖屏 / sw600dp）核对展开态与三项分隔线；平板横屏、手机窄屏（sw<600，底部胶囊导航）、四个气泡行的真实点击、TalkBack 与 D-pad / Tab 焦点遍历、1.3 倍字体 / 日夜 / 短屏的 style 仪器化均未真机核对。T146 尚未真机核对：两行入口摘要在 320dp / 1.3 倍字体下不截断、手机返回键从两个子页回授权首页、≥840dp 双栏设置中通道 / 屏幕子页在右栏推进且顶栏标题正确、Root 档位红色确认与屏幕「全部允许 / 撤销」二次确认流程不变。**build355 尚未真机验收**，验收项是 354→355 覆盖安装成功、数据完好、READY，以及上述两条线在真机上的表现；build344–build354 正式包覆盖安装验收按各自记录。
- 验证范围：发布身份与前置检查（versionCode 355 / versionName 与 `ci/release-identity.json` 一致；`check-release-prerequisites.py` PASS）、固定输入按锁 `fixed-inputs-20261105e` 复制并记录逐文件 sha256（/root/.cache/prep-build355/fixed-inputs-copied.sha256，与 build353 回执逐字节一致）、宿主 contracts 整组 12 项（固定输入运行时工具 / descriptor 源 205 assets + 481 launchers / 第三方声明 / 内置插件 / 凭据路径 / semver / host manifest 251 / 源卫生 / UI i18n newFindings 0 / changelog / 文档链接 55 文档 1253 链接 0 error）、`prepare-runtime-descriptor.py --check`（`8e54085c`）、全树 Java 格式（1692 文件 / 0 待格式化）、`git diff --check`（54 文件 / 8 分片 0 错）、`UpdateVersionPageTest`；全量 `:app:testStandardDebugUnitTest`（4427 例，10 例 aarch64 Robolectric 环境基线失败如实记录，0 error / 0 跳过）与 `:app:lintStandardRelease`（0 error / 600 warning，任务通过；较基线 583 条的 +17 均为新调整布局与新增控件的资源 / 布局类警告，无错误）；host 测试 fast 90/95、runtime 72/128——fast 5 条与 runtime 56 条失败均为本机环境 / CI 夹具缺失（Playwright、pnpm CLI、previous runtime APK 与本地 SAF / vscreen 协议 / 布局夹具等），与本次改动无关，不预写通过；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build355 更新说明](docs/releases/build355-changes.zh.md)。

## 0.2.1-rc.5-20261110.1300-dsh0.2.2-rc.1 / build354（T142 测试包反馈导出 PARENT_LINK 修复 + T143 设置页/插件页精简；纯 app 层，受管运行时不变 runtimeId 8e54085c）

- 测试包反馈导出系统别名修复（T142，`17b914f9d`）：Android 16 上 `Context.getCacheDir()` / `getFilesDir()` 返回 `/data/user/0` 系统别名，`AndroidBackupFileSystem` 的逐段 NOFOLLOW 打开把合法别名判成 `PARENT_LINK`，测试包「导出反馈日志（测试包）」因此失败、`dsh-home` 采集降级。现在只对本进程刚生成的缓存归档与 `getFilesDir()` 调用 `getCanonicalFile()` 规范化后再读取（`core/TestPackageFeedbackExporter`），用户选择的 SAF 目标与 guest 路径的防逃逸检查保持不变。真机 TB321FU（Android 16）装 353 `.debug` 补丁包后连续三次导出成功（9 条记录，含 `dsh-home`，无 `PARENT_LINK`）。
- 设置页与插件页精简（T143，`5e6e92aeb`）：设置页隐藏页内「设置」大标题，内容整体上移并去掉重复的标题留白；插件页「本地插件」卡片从上下两行改为两个并排入口（导入插件 / 导出插件），去掉副标题与右侧箭头，只保留图标与名称，继续沿用 `ShellCard` / `ShellRow` 样式与主题尺寸，横竖屏与平板按现有布局自适应。只改呈现，不改点击行为与文案资源键；未使用文案按 `check-ui-i18n` 规则处理（`newFindings=0`）。
- 受管运行时不变：T142 只改 `core/TestPackageFeedbackExporter`，T143 只改 `fragment_settings.xml` / `plugin_list_footer.xml`，都不在 `runtime-descriptor` 的输入内；合并树用 `tools/prepare-runtime-descriptor.py --check` 校验 **`8e54085c`** 不变（205 个输入）。fixed-inputs 冻结沿用锁定的 `fixed-inputs-20261105e`，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。
- 版本与范围：版本码提升为 354（基于已发布的 build353 / `0f5ed4a3a`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批含 T142 / T143 两条线，属于 app 层修复与界面精简；设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。build353 已作为正式包发布（tag `dsha-a-su-build353-20261110-090000` @ `0f5ed4a3a`）；目标 tag `dsha-a-su-build354-20261110-130000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：T142 的修复已在 TB321FU 的 353 `.debug` 补丁包上验证；build354 正式包与 `.debug` / `.dev` 测试包的完整导出（含 `dsh-home`、SAF 另存为回读、系统分享）仍待 PJD110 真机验收。T143 的两处效果已在 TB321FU 真机确认，平板横竖屏、短屏与 1.3 倍字体下的间距与触控区仍待真机核对。**build354 尚未真机验收**，验收项是 353→354 覆盖安装成功、数据完好、READY，以及上述两条线在真机上的表现；build344–build353 正式包覆盖安装验收按各自记录。
- 验证范围：发布身份与前置检查（versionCode 354 / versionName 与 `ci/release-identity.json` 一致；`check-release-prerequisites.py` PASS）、固定输入按锁 `fixed-inputs-20261105e` 复制并记录逐文件 sha256（/root/.cache/prep-build354/fixed-inputs-copied.sha256，与 build353 回执逐字节一致）、宿主 contracts 整组 12 项（固定输入运行时工具 / descriptor 源 205 assets + 481 launchers / 第三方声明 / 内置插件 / 凭据路径 / semver / host manifest / 源卫生 / UI i18n newFindings 0 / changelog / 文档链接 55 文档 1253 链接 0 error）、`prepare-runtime-descriptor.py --check`（`8e54085c`）、全树 Java 格式（1685 文件 / 0 待格式化）、`git diff --check`（54 文件 / 8 分片 0 错）、`UpdateVersionPageTest`；全量 `:app:testStandardDebugUnitTest`（4422 例，10 例 aarch64 Robolectric 环境基线失败如实记录，0 error / 0 跳过）与 `:app:lintStandardRelease`（0 error / 583 warning，任务通过；较基线 577 条的 +6 来自 T143 去掉标题与副标题后 `ui2_settings` / `ui2_import_sub` / `ui2_export_sub` 的 `UnusedResources`，无错误）；host 测试 fast 90/95、runtime 72/128——fast 5 条与 runtime 56 条失败均为本机环境 / CI 夹具缺失（Playwright、pnpm CLI、previous APK 等），与本次改动无关，不预写通过；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build354 更新说明](docs/releases/build354-changes.zh.md)。

## 0.2.1-rc.5-20261110.0900-dsh0.2.2-rc.1 / build353（T139 维护期插件注册死锁 P0 修复 + T138 测试包「反馈与诊断」一键导出 + T137 正式 Release 附带 .debug/.dev 共存测试包与 dry_run；含受管运行时更新 runtimeId 8e54085c）

- 维护期插件注册死锁修复（P0，T139，`5f204c311`）：build352 的跨 runtime 覆盖升级在维护页必然失败——`DSHA_UPDATE_RUNTIME_V1` 期间维护事务已把 `.runtime-descriptor.json` / `managed-assets-v2` 切到新 runtimeId，而 `runtime-health/<id>.json` 要等 `validation.verify()` 返回后才写入，注册在事务校验内执行时 `environmentReady()` 必然为 false，抛 `ENV_NOT_READY` 并回滚（PJD110 真机 07:17:06 / 07:19:15 / 07:26:27 连续三次，一直停在维护页；回滚后 live 运行时仍为 build351 的运行时）。现在维护事务的静态校验恢复为只核验运行时补丁，注册移到事务提交、健康回执写入之后（`EnvironmentMaintenance.registerBuiltinAfterCommit`）；注册失败不再回滚已健康的运行时，只记录诊断事件 `MAINTENANCE_REGISTRATION=MAINTENANCE_REGISTER_DEFERRED:<原因>`（原因有界、无换行），由启动路径继续按 T135 的分级预算 / 超时清锁重试。维护完成文案区分「dsh 与内置插件更新完成」与「dsh 更新完成；内置插件注册未完成，将在下次启动按宽预算重试」。新增纯逻辑 `util/MaintenanceRegistration`（`MAINTENANCE_REGISTER_OK` / `DEFERRED`）与单测，覆盖成功、普通失败、超长原因、中断、无消息异常；设计文档 `docs/design/build352-maintenance-register-deadlock-20261110.md` 记录真机证据链、根因、mock 单测与 CI 未挡住的原因、验证清单与教训。**build352 的跨 runtime 升级路径是坏的，不要把它作为升级目标**；build353 的验收路径是 351→353 覆盖升级。
- 测试包「反馈与诊断」一键导出（T138，`d6ef0e845`）：只有显式测试变体（`.test` / `.debug` / `.dev`）显示入口，正式包 `BuildConfig.TEST_PACKAGE=false` 保持隐藏，不按包名或运行时状态猜测；导出的 `DSHA-feedback-…tar.gz` 收集本进程 logcat（`-d --pid=<自身 pid> -v threadtime`，只读本进程）、DSH / 宿主 / 修复日志、`last-startup-failure.log`、`diagnostic-events*`、`startup-history`（最近 5 条）、`maintenance`（最近 3 条）、`runtime-health`（最近 3 条）、插件事务与完成证明，以及设备与环境信息（机型 / 品牌 / ABI / 系统版本 / 安全补丁 / locale / 包名与版本 / `TEST_PACKAGE` / runtimeId / 运行方式 / uptime 与启动、维护、应急状态）；不采集 IMEI、序列号、账号或 API key，全部文本经 `SensitiveData` 脱敏。新增 `util/BoundedByteTail` / `util/FeedbackCapture` / `core/TestPackageFeedbackExporter`；`DiagnosticExportArchive` 增加参数化上限，测试包导出最多 48 项、单项 ≤256 KiB、总量 ≤8 MiB，正式包诊断导出仍是 32 项 / 256 KiB / 1 MiB；来源不存在、越界、软链或读取失败只写入 manifest 的 `capture` 段，不阻断整包。保存走「另存为…」（SAF，写后回读字节校验）或「分享…」（只交给系统分享目标，不经过网络、不自动上传）。
- 正式 Release 附带共存测试包与 dry_run（T137，`393fdd362`）：新增共享节点 `_node-test-packages.yml`，按 matrix 构建 `.debug`（debuggable）与 `.dev`（release、非 debuggable）两个共存测试包，用独立测试证书签名，并逐项核对包名后缀、版本、debuggable、证书与正式 / 上游证书不同，复跑 verify-dsh-upgrade / recovery / third-party / managed-assets。`release-production.yml` 新增 `test_packages`（`debug+dev` 默认 / `debug` / `none`）与 `dry_run` 输入；`test-packages` 与 `build-signed` 并行，`publish` 下载测试包证据并用随证据下发、按 build-info 摘要核对的 `tools/release-test-packages.py` 合并 `testPackages` 段、`SHA256SUMS` 与发布说明；`dry_run=true` 时跳过 publish，由 `dry-run-summary` 走同一套合并 / 校验并打印将创建的 tag 与资产摘要。测试证书 Secrets 只进入该节点、只落在 `RUNNER_TEMP` 并在 `if: always()` 删除；`test-package-variants.yml` 复用同一节点，正式 Release 强制固定测试证书，一次性证书会被拒绝。新增 `tools/release-test-packages.py` 与 16 例单测（同 run / 同 commit、APK 摘要、证书 PEM、`SHA256SUMS` 自洽、目录穿越与二次合并等反例）。
- 受管运行时更新：T138 的 `DiagnosticExportArchive` 属 launcher 输入，合并树由 `tools/prepare-runtime-descriptor.py` 重算 `runtimeId` 为 **`8e54085c`**（205 个输入）；T139 只改 `EnvironmentMaintenance` 与新增 `util/MaintenanceRegistration`，不在 descriptor 输入内。fixed-inputs 冻结沿用锁定的 `fixed-inputs-20261105e`，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。
- 版本与范围：版本码提升为 353（基于已发布的 build352 / `ba8c37ff0`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批含 T139 / T138 / T137 三条线（T139 是 P0 hotfix）；设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。**build352 已发布（tag `dsha-a-su-build352-20261110-070000` @ `ba8c37ff0`）但跨 runtime 升级路径坏的，不要用于覆盖升级**；目标 tag `dsha-a-su-build353-20261110-090000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：T139 需要 PJD110 真机做 351→353 覆盖升级，走完维护 5/5 并完成注册，核对新 runtimeId 的 `runtime-health/<id>.json`、`register-managed-asset-id` 与 `MAINTENANCE_REGISTRATION` 事件，再重启一次确认首启不重复注册；反向验证故意让注册失败时维护仍应完成、启动可按宽预算重试。T138 的测试包导出入口、SAF 另存为回读、系统分享、上限与脱敏、正式包不显示入口都需设备验收。T137 的 `.debug` / `.dev` 共存包构建与并排安装、`SHA256SUMS` / `build-info` 合并、`dry_run` 实际行为需一次真实 release run 验证（本机只做 YAML 解析、脚本单测与文档检查）。**build353 尚未真机验收**，验收项是 351→353 覆盖安装成功、维护页走完、数据完好、READY、首启不再回滚，以及上述三条线在真机上的表现；build344–build351 正式包覆盖安装验收仍待用户，build352 的升级验收由本版 351→353 重做，build338–build351 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查（`check-release-prerequisites.py` PASS，versionCode 353 / versionName 与 `ci/release-identity.json` 一致）、固定输入按锁 `fixed-inputs-20261105e` 从 prep/build352-release 工作树复制并记录逐文件 sha256（/root/.cache/prep-build353/fixed-inputs-copied.sha256）、宿主 contracts 整组 12 项（固定输入运行时工具 / descriptor 源 205 assets + 481 launchers / 第三方声明 / 内置插件 / 凭据路径 / semver / host manifest / 源卫生 / UI i18n newFindings 0（T139 格式修正后按同一已声明 nonFinding 刷新 sourceSha256，无新增判定） / changelog / 文档链接）、`prepare-runtime-descriptor.py --check`（`8e54085c`）、全树 Java 格式（1685 文件 / 0 待格式化；T139 的 `EnvironmentMaintenance` 与 `MaintenanceRegistrationTest` 只做格式修正）、`git diff --check`（57 文件 / 9 分片 0 错）、`UpdateVersionPageTest`（10 例 0 失败）、T137 `tools/test-release-test-packages.py`（16 例 0 失败）；全量 `:app:testStandardDebugUnitTest` 两轮：首轮 4422 例 12 失败（10 例 aarch64 Robolectric 环境基线 + 2 例本版说明断言待同步），修正后第二轮 4422 例 10 失败且全部为本机 aarch64 基线（`DocumentRowsSnapshotTest` 5 / `DshaDocumentsProviderTest` 4 / `BridgeImageDecoderTest` 1），无新增失败；`:app:lintStandardRelease` 0 error / 577 warning（任务通过）；host 测试 fast 90/95、runtime 72/128——fast 5 条与 runtime 56 条失败均为本机环境 / CI 夹具缺失（Playwright 27、pnpm CLI 1、previous APK 1、gradle 缓存夹具、root 权限语义、DNS 解析与宿主 SIGSEGV 等，与本次改动无关，不预写通过）；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build353 更新说明](docs/releases/build353-changes.zh.md)。

## 0.2.1-rc.5-20261110.0700-dsh0.2.2-rc.1 / build352（T135 覆盖升级首启插件注册修复 + T134 外接设备页面观察通道 watch / operate observe=1 + T133 首启失败根因报告；含受管运行时更新 runtimeId b8c59df9）

- 覆盖升级首启插件注册修复（T135，`092b8848d`）：注册预算按受管资产身份分级——常规 90s、首启 240s；宿主读 `runtime-descriptor` 的 `runtimeId`，guest 读 `managed-assets-v2`，注册成功后原子写入 `register-managed-asset-id`，超时被杀则不写，下次仍按宽预算重试。注册脚本新增 `--clear-stale-profile-lock`（不经 `operation_lock`，只删除已确认退出持有者的锁），宿主在注册超时后以 10s 短预算清理一次；`plugin-manager list` 对死持有者报 `PLUGIN_PROFILE_LOCK_STALE`（可重试），不再笼统报「配置正在修改」。`StartupRecoveryState` 新增 `Kind.REGISTER`，识别 `BUILTIN_REGISTER_TIMEOUT` / `EXIT_` / `RECEIPT_MISSING` 与 `PLUGIN_PROFILE_LOCK_TIMEOUT` / `STALE`，标题与说明给出「插件注册超时 / 未完成 / 待确认的锁，可直接重试」；`StartupText` 只保留可行动结论，去掉原始 `exit=` / `timedOut=` / guest JSON（原文仍留在启动历史与日志）。宿主分段计时：注册命令走 Runner 行回调，失败消息追加 `hostElapsedMs` 与最后一条 `REGISTER_TIMING` 阶段，guest 自报时间超过宿主墙钟时显式标 `GUEST_TIMING_MISMATCH`。首启不重复装覆盖层：`RuntimeTools` 写跨进程 `.runtime-tools-prepared` 完成戳（相对受管文件集合 + `RuntimeAssetStamp`），仅 APK 身份、已登记 descriptor 身份与元数据戳全部一致时复用，任何不一致退回完整准备。注册并入维护就绪检查：update 的 `RuntimeTrial` 静态校验改为 `register=true`、`requireCompleted` + `BUILTIN_REGISTER_OK`，注册在 Web 试运行前完成，失败随事务回滚。新增 `RegisterBudget` / `GuestTimingEvidence` / `RuntimePreparedProof` 单测与注册预算、时序证据、超时清锁、`REGISTER` 分类、`StartupText` 折叠用例，`tools/` 下补锁状态 / 接管 / 清理、身份标记与 stale 锁列表测试。**不把 479409ms 归因任何单一子阶段**，只把宿客两侧时间与最后阶段写入诊断；覆盖升级后的首次启动仍需真机验证。
- 外接设备页面观察通道（T134，`24130f244`）：新增 `/app/extdev/watch`（`mode=events` / `window`），在同一个专属 ADB 会话内连续采样目标 events 缓冲区，避免每次检查重启 server；返回前台包 / Activity、绑定目标代次的 digest、`sequence` 与 `settle`，`since` 游标可把多次调用串成「等待页面变化」。`operate` 支持 `observe=1`：动作已派发（非 UNKNOWN）时附 4 样本观察；观察失败只写 `observationUnavailable`，不改写动作状态；`NOT_SENT` / `POLICY_BLOCKED` / `UNKNOWN` 语义不变。抽出纯逻辑 `ExternalFocusEvents` / `ExternalWatchPolicy` / `ExternalRateLimit` 并配单测；采样沿用只读命令白名单（不带 `logcat -t`：真机实测它先截原始尾部再过滤，会得到空结果）。真机测量（OnePlus PJD110 / Android 16）：logcat events 采样检测 p50 123 ms、`dumpsys window` 128 ms、`screencap` 922 ms；`screenrecord` h264 首帧 37–43 ms 但需解码 / 帧差，留 Phase C。同步更新 `/app/help` 中英文、文档索引与 `tools/java-format-files.json`，并重算 runtime descriptor（`HttpShellService` / `BridgeRoutes` 属 launcher 输入）。**`/app/extdev/watch` 是新增接口，接口级真机验收未做，需测试包在 PJD110 上逐项验收。**
- build351 覆盖升级首次启动失败根因排查报告（T133，`bf59e8864`，仅文档）：归类为 ③ 机制缺失（注册 90s 固定预算、无分阶段 / 续期、超时无锁清理）为主，① 设计链路（首启重复受管覆盖层、残留 profile 锁被恢复页再报一次）加重，② 语义不足（`BUILTIN_REGISTER_TIMEOUT` 未分类 / 落到 `STARTUP_FAILED`、原始 `exit=` / JSON 直出）。证据为恢复页 / 维护页时线与本机 build351 正常启动基线（register 870ms、stamp 65ms、101 项）；记录 guest 479409ms 与宿主 90s 预算不自洽，列为需要现场证据的缺口，**不据此定子阶段**。报告给出上述最小修复与测试包复现 / 取证清单，不改生产代码。
- 受管运行时更新：T135 的注册脚本与 launcher 输入、T134 的 `HttpShellService` / `BridgeRoutes` 都进入 `runtime-descriptor` 输入，合并树由 `tools/prepare-runtime-descriptor.py` 重算 `runtimeId` 为 **`b8c59df9`**（205 个输入）；fixed-inputs 冻结沿用锁定的 `fixed-inputs-20261105e`，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。
- 版本与范围：版本码提升为 352（基于已发布的 build351 / `bc9bce87d`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批含 T135 / T134 / T133 三条线（T133 仅文档）；设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。build351 已作为正式包发布（tag `dsha-a-su-build351-20261110-040000` @ `bc9bce87d`）；目标 tag `dsha-a-su-build352-20261110-070000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：T135 覆盖升级后的首次启动仍需 PJD110 真机验证——受管资产身份是否命中首启宽预算、超时后是否只清理已退出持有者的锁、恢复页分类与「可直接重试」是否可读、`hostElapsedMs` / `GUEST_TIMING_MISMATCH` 是否按预期出现、`.runtime-tools-prepared` 跨进程复用是否命中、维护 5/5 注册校验是否通过；**479409ms 的归因与「首启问题是否完全解决」仍待现场证据，本版不预写通过**。T134 的 `/app/extdev/watch` 与 `operate observe=1` 是新增接口 / 参数，接口级真机验收未做（p50 采样数据来自设备测量），Phase C（`screenrecord` 首帧）未实现。**build352 尚未真机验收**，验收项是 351→352 覆盖安装成功、数据完好、READY、首次启动注册与上述功能在真机上的表现；build350 的 T98 / T119、build351 的 T130 / T131 与 build344–build351 正式包覆盖安装验收仍待用户，build338–build349 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查、宿主 contracts 整组（固定输入运行时工具 / descriptor 源 / 第三方声明 / 内置插件 / 凭据路径 / semver / host manifest / 源卫生 / UI i18n / changelog / 文档链接）、descriptor `b8c59df9` / 受管运行时源（205 assets / 481 launchers）、全树 Java 格式（1677 文件 / 0 待格式化）、`git diff --check`（54 文件 / 8 分片）、T135 / T134 定向单测与 `UpdateVersionPageTest`（12 类 89 例，0 失败）；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，4395 例，10 例 aarch64 Robolectric 环境基线失败——`DocumentRowsSnapshotTest` 5 / `DshaDocumentsProviderTest` 4 / `BridgeImageDecoderTest` 1，如实记录且无新增失败）与 `:app:lintStandardRelease`（0 error / 577 warning，任务通过）；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build352 更新说明](docs/releases/build352-changes.zh.md)。

## 0.2.1-rc.5-20261110.0400-dsh0.2.2-rc.1 / build351（T130 实体屏特权执行 Phase A（实验、默认关）+ T131 内置 dsh-web-mobile 同步上游 3.3.5；含受管运行时更新 runtimeId 6f31f111）

- 实体屏特权执行 Phase A（T130，`4e8c80e0e`，实验、默认关）：复用受管 `app_process` 特权 Core，在 display 0 上以 `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` 镜像抓帧（`maxWidth` 320–1920 / `jpegQuality` 30–90 / `maxFps` 1–10，默认 1280 / 60 / 6；近黑帧标 `SECURE_OR_BLACK`；帧不落盘、不进日志），并用固定动词 tap / swipe / key（3 / 4 / 66 / 67）注入，**整条路径不依赖无障碍**。新增 `/app/priv/{info,frame,config,tap,swipe,key,benchmark,close}` 与 `PhysicalScreenManager` / `PhysicalScreenCore` / `PhysicalScreenDisplaySession` / `PhysicalScreenLauncher`；动作回执一次往返带回新帧与新观察，凭据 30 秒、一次性、绑定 generation 与目标包，`EXECUTION_UNKNOWN` 不重放，失败 fail-closed 且不静默回退无障碍。四道门：设置 → 设备能力授权 → 特权执行（`privileged_execution_v1`，原生授权、默认关、可撤销、不从备份 / 环境恢复，关闭即丢弃 Core 连接）、当前手机任务身份（`taskSessionId` / `turnId`，先 `/app/ui/task-begin`）、目标包可核验（`PRIV_TARGET_UNVERIFIABLE`）、敏感应用拒绝（`PRIV_SENSITIVE_TARGET_UNSUPPORTED`）与 `ScreenConfirmationPolicy.allows`；Phase A 不发逐动作原生确认窗，ASK 一律 `NOT_SENT`（`SCREEN_CONFIRMATION_REQUIRED`），只放行「全部允许」或按应用 `ALLOW`。`/app/priv/benchmark` 跑 N 次完整动作链，输出 App 侧 `roundTripMs` 与 Core 侧 `inputMs` / `frameWaitMs` 的 p50 / p95 / max（最近秩、不插值）并标 `accessibilityRequired:false`；`waitMs` 0–500、`n` 1–20 且 `n × (waitMs + 400) ≤ 8000`。旋转 / 分屏 / 分辨率变化会重建镜像并更换 `generation`，旧凭据全部失效；黑帧判定是 48 像素采样启发式，只用于提示，不是内容识别。文档见 `docs/privileged-screen-core.md`；新增单测 27 例（策略 / 路由 / 启动器 / 管理器）。
- 内置 `dsh-web-mobile` 同步上游 3.3.5（T131，`acf30243b`）：从 3.3.1（`4e71eb67`，2026-11-06）前进到上游 main `646fe837440f9939228bdba0024d407eaac01128`（2026-11-08，`4e71eb67..646fe83` 共 37 个提交）；发布标签 v3.3.5 = `14ecfccf`，标签后的 README / 插件页排版修复 3 个提交一并取用。锁为 `version=3.3.5` / `ref=main` / `commit=646fe83`：241 个上游成员、243 个部署成员（+ `lib/file-transfer.js` / `.map`）、14 个退役成员（本次新增 3 个：改名 `drawer-layer` 前 `overlay-backdrop-fab` 的 `.d.ts` / `.d.ts.map` / `.ts`）。新增 `assets/icon.png` 与 `locale/{en,zh}.json` 并加入受管安装表，宿主插件卡片按 `package.json#icon` 与 `locale/<lang>.json` 显示图标和中英文标题描述；用户可见变化还有手机档「更多视图」入口、子代理 chip 让位、抽屉暗层收敛到 frame 伪元素、全屏手势按方向定族（左右 45% 起点区退役）、hero 工作树行、插件页交还宿主排版。补丁策略不变：**完全追随上游**，`tools/apply-mobile-client-patches.mjs` 只保留宿主集成必需的最小集（服务端鉴权 / 安全区 / `DSHA_GEAR_FIT_V1`）与文件传输、多语言，已删除的 UI / 行为补丁未重放；build336 起默认启用保持。同步记录见 `docs/releases/v0.2.1-rc.5-build351-web-mobile-3.3.5-20261109.md`。
- 受管运行时更新：T130 新增 / 改动的 `vscreen/` Java 与 T131 的插件成员都进入 `runtime-descriptor` 输入，合并树由 `tools/prepare-runtime-descriptor.py` 重算 `runtimeId` 为 **`6f31f111`**（205 个输入）；fixed-inputs 冻结沿用锁定的 `fixed-inputs-20261105e`，内置 `dsh-web-mobile` 3.3.5 与默认启用不变。
- 版本与范围：版本码提升为 351（基于已发布的 build350 / `1c8a79815`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批含 T130 / T131 两条线；T130 默认关闭，设备 shell 白名单与执行期护栏不放宽，授权 fail-closed 语义、密码文本硬阻断与敏感界面策略不变。build350 已作为正式包发布（tag `dsha-a-su-build350-20261109-230000`）；目标 tag `dsha-a-su-build351-20261110-040000` 由 release 工作流在用户确认后创建。
- **未验证 / 未验收**：T130 真机验收未做：抓帧与注入、关闭无障碍服务后仍可动作、`FLAG_SECURE` 页面标 `SECURE_OR_BLACK`、`/app/priv/benchmark` 的 p50 / p95 / max、关闭能力开关后所有端点 `NOT_SENT`、旋转 / 分屏后 `generation` 与旧凭据失效；Phase A 只覆盖 root 通道，Shizuku / ADB 通道未验收，`/app/priv/*` 尚未接入内置工具。T131 覆盖升级验收未做：351 覆盖安装后不白屏、侧栏与设置入口不重复、插件卡片图标与本地化标题、默认启用保持、抽屉 / 文件面板全屏手势。**build351 尚未真机验收**，验收项是 350→351 首次覆盖安装成功、数据完好、READY 与上述功能在真机上的表现（用户计划用 ADB 装到另一台手机）；build350 的 T98 / T119 与 build344–build350 正式包覆盖安装验收仍待用户，build338–build349 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查、宿主 contracts 整组（固定输入运行时工具 / descriptor 源 / 第三方声明 / 内置插件 / 凭据路径 / semver / host manifest / 源卫生 / UI i18n / changelog / 文档链接）、descriptor `6f31f111` / 受管运行时源（205 assets / 481 launchers）、全树 Java 格式（1662 文件 / 0 待格式化）、`git diff --check`（54 文件 / 8 分片）、T130 定向单测与 `UpdateVersionPageTest`（7 类 48 例，0 失败）；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，4351 例，10 例 aarch64 Robolectric 环境基线失败——`DocumentRowsSnapshotTest` 5 / `DshaDocumentsProviderTest` 4 / `BridgeImageDecoderTest` 1，如实记录且无新增失败）与 `:app:lintStandardRelease`（0 error / 577 warning，任务通过）；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build351 更新说明](docs/releases/build351-changes.zh.md)。

## 0.2.1-rc.5-20261109.2300-dsh0.2.2-rc.1 / build350（T98 虚拟屏窗口证据最小暴露与多窗口可行动提示 + T119 敏感界面分档与交接 + 上岛 PR #12 修复；含受管运行时更新 runtimeId 5ff08d58）

- 虚拟屏窗口证据最小暴露与多窗口可行动提示（T98，`33ceab2c7`）：`see` 的 `FRAME_NOT_READY` 细分与 tree / node / editor / anchor 的窗口不可用回复，在无障碍可读时附最小只读窗口证据（`util/VirtualScreenWindowEvidence`）：应用 / 系统 / 输入法窗口计数、`focusedWindowId` / `activeWindowId`、`windowAmbiguous`，窗口唯一时再附目标窗口的 displayId / id / type / layer / bounds / focused / active / 标题 / 包名；标题去掉控制字符、折叠空白并截断到 64 码点，包名按包名格式校验后才保留，其余不合格值直接省略，不含节点文本、控件内容、截图或完整窗口清单。`NO_UNIQUE_APPLICATION_WINDOW` 用计数区分两种原因：多个应用窗口并列（分屏 / 悬浮窗）→ `reason=VSCREEN_WINDOW_AMBIGUOUS`、`nextAction=ASK_USER_TO_CLOSE_SPLIT_SCREEN`；只有系统 / 输入法窗口、没有应用窗口 → `reason=VSCREEN_NO_WINDOW`、`nextAction=LAUNCH_APP_FIRST`。证据只用于解释失败与给出下一步，**不参与授权、选窗或重试**：语义路径仍只认 `VirtualScreenWindowPolicy.selectDetailed` 的唯一结果，锚点路径仍只认原观察携带的 windowId（同 id 重复仍按 `ANCHOR_WINDOW_AMBIGUOUS` 拒绝），不会因为「多窗口」自动换一个窗口。
- 虚拟屏敏感界面分档与交接（T119，`dc6f9642e` / `c3a4e3c30` / `241e06159`）：新增 `util/SensitiveScreenClassifier` 与 `util/SensitiveScreenPolicy`（纯逻辑），在 6.1 的密码焦点硬守卫之外对整屏做一次保守分类（密码 / 验证码 / 支付 / 系统安全 / 敏感但树读不出 `SECURE_UNKNOWN`），默认全部 `ASK`；可逐类配置 `HANDOFF`——在派发任何输入前暂停助手、撤销在途请求并丢弃观察，返回 `ok:false` / `state=NOT_SENT` / `error=SENSITIVE_SCREEN_HANDOFF` / `sensitivity=<类别>` / `readOnly:true` / `retryable:false` / `nextAction=HANDOFF_TO_USER`，用户恢复后必须重新 `see`，不自动重放；`OFF` 只关闭整屏分档、回到普通确认策略。密码文本读写仍是输入层硬阻断，不因任何档位或确认解除。`ALLOW_ALL` 与按应用 `ALLOW` 只省普通确认，不能跳过敏感类别 `ASK`；敏感确认一次一授权，**不写入「本轮允许此应用」的 5 次记忆**（普通应用仍每 5 次再问）。设置 → 设备能力授权 → 识屏新增「敏感界面交接」独立一行，逐类别选择询问 / 交接，并维护强制交接与信任应用两个精确包名名单（默认空、各上限 64、两名单互斥、损坏回默认 ASK）。屏幕授权 `policyRevision` 合并 `screen_confirmation_v1` 与 `sensitive_screen_v1` 两个修订号，任一保存都让旧确认与在途请求失效，进程内保留单调下限；会注入输入的端点在确认后、派发前再读一次分类，读不出返回 `SENSITIVE_SCREEN_UNVERIFIED`、类别变化返回 `SENSITIVE_SCREEN_CHANGED`，两者都是未发送、可重新观察。
- 上岛（通知胶囊）修复（PR #12，`ccd4b81d7`，已入 main）：展开态只保留一处轮次（通知 subText），正文不再重复「第 N 轮」，运行中也不再每秒写入秒数，内容签名保持稳定，心跳重建通知不再吞掉选项按钮点击；回答动作在后台失败必须可见——HTTP 非 200、空提交、选项失效或入口失效都会弹 Toast（含 HTTP 状态与服务端错误码，如 `HTTP 409 NO_WAITING`）并提示点通知进入对话回答，应用日志记录 `TASK_ANSWER_REJECTED <status> <body>`；从通知进入会话时判据改为提问卡里是否存在实际选项行 `Mbwy4a_optionRow`（选项容器 `Mbwy4a_options` 每张卡都会渲染，用它判断会恒真），有选项时不抢焦点、键盘保持收起，避免把卡片底部「提交」挤出可视区。`docs/island-status.md` 补平台验证状态表：原生 Android 16 / 16 QPR1+、HyperOS、ColorOS 16、OriginOS 6/7 分别标注「代码覆盖 / 公开资料 / 未上机」，OriginOS 7（Android 17）列为预期支持、仓库尚无上机记录。
- 受管运行时更新：T98 / T119 改动落在 `runtime-descriptor` 的 launcherSources，合并树由脚本重算 `runtimeId` 为 **`5ff08d58`**（`b6220b227` 登记 T98 新增 Java 文件，`084b692f2` 重算）；fixed-inputs 冻结沿用锁定的 20261105e，内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- 版本与范围：版本码提升为 350（基于已发布的 build349 / `3264f7868`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批含 T98 / T119 两条虚拟屏策略线与已合入 main 的 PR #12，不放宽设备 shell 白名单与执行期护栏，密码文本硬阻断与授权 fail-closed 语义不变。build349 已作为正式包发布（tag `dsha-a-su-build349-20261109-140000`）。
- **未验证 / 未验收**：真机验收未做：T98 多窗口 / 分屏应提示收起分屏而非自动选窗、IME 窗口只进 `inputMethodWindowCount`、`FLAG_SECURE` 页面截图失败时窗口证据仍只含元数据；T119 的 ASK / HANDOFF / OFF 三档、强制交接与信任名单两向生效、敏感确认不写入 5 次记忆、HANDOFF 后暂停与恢复后必须重看、短屏 / 1.3 倍字体下设置页可滚动、无障碍关闭时分类降级（保护降级，不宣称安全）；PR #12 的展开态单轮次 / 失败可见 / 有选项时不抢焦点在真实 ROM 上的表现，以及 OriginOS 7 原子岛（尚无上机记录）。**build350 尚未真机验收**，验收项是 349→350 首次覆盖安装成功、数据完好、READY、受管运行时更新与上述功能在真机上的表现；build344–build350 正式包真机覆盖安装验收仍待用户；build338–build349 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `5ff08d58` / 受管运行时源、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build350 更新说明](docs/releases/build350-changes.zh.md)。

## 0.2.1-rc.5-20261109.1400-dsh0.2.2-rc.1 / build349（插件卡片状态胶囊底色可见性修复 8%→18% + 文案精简到 ≤4 字 + 提示条防闪烁；纯 UI）

- 插件卡片「配置 / 当前 Web」状态胶囊底色可见性修复（`93af9944d`）：小胶囊底色原先取 `shell_*_soft`（8% 透明），在白卡上几乎看不出胶囊、像只换了文字颜色；现在改为同色相 18% 填充（`BADGE_FILL_ALPHA = 0x2E`，由 `toneColor` 合成 alpha），中性仍用 `shell_raised`，重启待办警示胶囊同步加深。只改展示，不动胶囊的语气映射与状态判定。
- 胶囊文案精简到首屏 ≤4 字：配置侧只留「已启用 / 已禁用 / 可开启 / 缺失 / 异常 / 临时 / 处理中」等结论词，当前 Web 侧只留「已加载 / 加载中 / 加载失败 / 等待服务 / 未加载 / 待核验」；完整状态与加载历史改放进 `contentDescription` 交给无障碍，不再铺进胶囊。
- 提示条防闪烁：提示条重建签名去掉 `System.identityHashCode`。列表每次刷新都会产生新的 Item，旧签名随之变化会重建提示条，造成「可更新 / 最近操作消息」并排提示闪烁；现在只按提示内容、语言、版本与操作记录签名，内容不变就不重建。
- 受管运行时描述不变（仍为 `5fec6de5`）：本批只改 `PluginFragment` 的展示层与 `PluginFragmentRenderingTest`，`runtime-descriptor` 的 launcherSources 未变；fixed-inputs 冻结沿用锁定的 20261105e，内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- 版本与范围：版本码提升为 349（基于已发布的 build348 / `2045b61d6`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批只含上述一条 UI 修复线，不改插件状态判定、设备 shell 白名单，不放宽执行期护栏。build348 已作为正式包发布（tag `dsha-a-su-build348-20261109-110000`）。
- **未验证 / 未验收**：真机验收未做：18% 底色在日夜 / 窄屏 / 1.3 倍字体 / RTL 下的可见性与对比度；≤4 字短文案与「处理中 42%」等进度文案是否被截断；紧凑（COMPACT）密度下短文案的换行；`contentDescription` 的读屏播报；列表刷新时提示条是否确实不再闪烁。**build349 尚未真机验收**，验收项是 348→349 首次覆盖安装成功、数据完整、READY、受管运行时描述不变与上述四项在真机上的表现；build344–build349 正式包真机覆盖安装验收仍待用户；build338–build348 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `5fec6de5` / 受管运行时源、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build349 更新说明](docs/releases/build349-changes.zh.md)。

# 更新记录

## 0.2.1-rc.5-20261109.1100-dsh0.2.2-rc.1 / build348（BOTTOM 动作抽屉首屏上限 0.72× + Auto review 兼容壳运行证据修复 + 插件卡片语义色徽章；含受管运行时更新 runtimeId 5fec6de5）

- 贴底动作抽屉首屏高度上限收紧为可用高的 0.72×（`450952c1e`）：插件「更多操作」等 `BottomSheetCard` 入口列表按内容自适应时会被撑到屏幕上半区；新增 `SheetGeometry.bottomDrawerMaxHeight(heightDp, liftDp)`（可用高 = 屏高 − 悬浮胶囊抬高量），`BottomSheetCard` 改用它，超出在抽屉内滚动、页脚常驻。`CardSheet` 的 BOTTOM 档保持 0.9，不共用常量：说明 / 选择 / 长表单调用面广，长表单也需要正文高度。`SheetGeometryTest` / `BottomSheetCardLayoutTest` / `CardSheetGeometryTest` 同步比例上限与短屏（<480dp）行为不变；设计文档 UI 基准线 §9 记录取舍。
- 插件运行证据对 Auto review 兼容壳跳过版本相等检查（`4a25d2562`）：profile 默认选择签名兼容壳 `dsh-auto-review@0.1.0`，壳的 patch 插入官方包 `@deepseek-ai/dsh-experimental-auto-review@0.2.2-rc.1`，运行快照只记录 profile 条目（壳 0.1.0），而卡片版本取官方包 0.2.2-rc.1；`PluginIdentity.legacyShim(cardName, observedName)` 只认「canonical 卡片 + alias 运行行」这一条历史包装路径，`PluginRuntimeProjection.apply()` 对兼容壳跳过版本相等、按加载状态确认，不再长期误报「加载状态未确认 / 重启 Web 后生效」。非 alias 的版本不等仍判 unknown + 重启待办，「旧模块冒充新版」保护不变；`PluginRuntimeProjectionTest` 补真实方向且版本不等的用例并锁定 canonical 行保护。
- 插件卡片状态改语义色徽章（`df4fe653d`）：来源 / 版本 / 配置 / 当前 Web 各用独立胶囊，底色与文字统一取 `shell_*` 语义 token；配置与当前 Web 的语气映射抽到 `PluginCardState.configTone` / `runtimeTone` 纯逻辑并补单测，只改展示，不动状态判定。重启待办从整条提示改为警示胶囊（`item_plugin_state_chip`），与「可更新 / 最近操作消息」两类提示条区分；徽章放进 `PluginFlowRow`，窄屏与 1.3 倍字体整枚换行、不省略关键状态词；状态圆点合入配置徽章作为 compound drawable，不单独暴露给 TalkBack。
- 受管运行时更新：`PluginIdentity` / `PluginRuntimeProjection` 属 `runtime-descriptor` 的 launcherSources，重算 `runtimeId` 为 `5fec6de5`；fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- 版本与范围：版本码提升为 348（基于已发布的 build347 / `2121f27c0`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批只含上述三条线，不改设备 shell 白名单、不放宽执行期护栏。build347 已作为正式包发布（tag `dsha-a-su-build347-20261109-090000`）。
- **未验证 / 未验收**：真机验收未做：插件「更多操作」抽屉在普通屏 / 短屏 / 1.3 倍字体下首屏高度、抽屉内滚动与页脚常驻；Auto review 卡片在真实 Web 重启前后的配置 / 当前 Web 徽章与重启待办是否消失；插件卡片四枚徽章在日夜 / 窄屏 / 1.3 倍字体 / RTL 下的换行与配色（`LayoutAuditInstrumentation` 测试包）。**build348 尚未真机验收**，验收项是 347→348 首次覆盖安装成功、数据完好、READY、受管运行时更新与上述三项功能的表现；build344–build348 正式包真机覆盖安装验收仍待用户；build338–build347 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `5fec6de5` / 受管运行时源、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。
- 十语种更新说明见 [build348 更新说明](docs/releases/build348-changes.zh.md)。

## 0.2.1-rc.5-20261109.0900-dsh0.2.2-rc.1 / build347（SAF 目录选择指向系统文件选择器 + 设备通道状态胶囊让位 + 隐藏后台内容守卫/严格模式；含受管运行时更新 runtimeId 28dc573a）

- SAF 目录选择明确指向系统 DocumentsUI（`479302313`）：新增 `ui/SystemDocumentPicker`，树选择意图显式指向 `com.android.documentsui` / `com.google.android.documentsui`，未导出、禁用或本机缺失时退回默认解析；默认处理程序被 OEM 改写时先说明「可能看不到 SD 卡 / U 盘等存储挂载点」再给三个入口——系统文件选择器 / 接管者应用信息（清「默认打开」）/ 继续用默认，没有可用处理程序时 toast 而不是崩溃。授权页、工作区备份目录与原生数据项目备份源三处 tree 选择统一；清单 `<queries>` 增加两个系统包与 `OPEN_DOCUMENT_TREE`；`SystemDocumentPickerTest` 7 项覆盖强制系统入口、OEM 接管识别、缺失 / 未导出回退与 tree URI / 起始目录转发。授权仍由系统逐目录发放，`/app/saf/*` 的授权簿、路径护栏与写入开关不变。
- 设备通道状态胶囊不再挤出配对按钮与开关（`cfad29009`）：Root / Shizuku / ADB / 外接设备四行改为「左列标题 + 状态胶囊」按权重让位，行内验证、配对按钮与开关先按自身宽度测量，状态胶囊只在剩余宽度内换行；新增 `ShellChannelStatusPill`（最多 6 行、不省略、行距 2dp），外接设备状态并入标题左列；`DeviceGrantsLayoutTest` 补长状态 / 中英 / 1.3 倍字体下动作保持宽度且留在页内的回归，通道高度预算放宽到 500（实测 462）。
- 隐藏后台补内容守卫与严格模式（`76b3a44a6`，一加 C16 报障）：Android 13+ 的 `RecentTasks#isInVisibleRange` 会保留「最近可见任务」——从应用内直接滑到最近任务时当前卡片仍由系统保留，公开 API 不能只移除卡片而不结束 UI；本版按用户决定做内容守卫 + 实验性严格模式。新增 `ui/RecentsPrivacyGuard`（Application 级生命周期）：开关开启时对全部界面关闭最近任务截图内容（API 33+），严格模式（默认关）在应用整体退到后台 700ms 后结束 UI 任务；SAF 等结果型外部选择器经 `RecentsPrivacyGuard.launch` 启动、在途时不结束，画中画 / 多窗口 / 旋转不触发，失败写 `DiagnosticLog` 且不崩溃、不重放。`BrowserTasks.applyRecentsPreference` 刷完回读任务列表与 root Intent 标志，任务为空或标志不符时写 `RECENTS_HIDE_APPLY` / `RECENTS_HIDE_VERIFY` 并返回失败，不再假成功。设置页主开关补副标题并新增「严格模式（实验）」子行（父开关关闭时隐藏）；十语文案 `m9500`–`m9502`；设计文档记录平台限制与 C16 待验证项。**如实说明**：Android 13+ 最近任务会保留当前可见任务卡片，公开 API 无法只移除这一张；内容守卫只让卡片不显示页面内容，严格模式通过结束 UI 任务让卡片消失（dsh 进程、终端会话、ADB 保活与常驻通知不受影响）。
- 受管运行时更新：`DshaApp` / `ConfigStore` 属 `runtime-descriptor` 的 launcherSources，重算 `runtimeId` 为 `28dc573a`；fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- 版本与范围：版本码提升为 347（基于已发布的 build346 / `86f91ba0a`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批只含上述三条线，不改设备 shell 白名单、不放宽执行期护栏。build346 已作为正式包发布（tag `dsha-a-su-build346-20261109-070000`）。
- **未验证 / 未验收**：真机验收未做：一加 C16（ColorOS 16）上从应用内直接滑到最近任务看当前卡片内容是否空白、按 Home 后卡片是否消失、严格模式开启后卡片是否消失且 dsh Web / 终端 / ADB 保活仍在；系统文件选择器在 OEM 接管场景的实际入口与返回的 tree 授权；设备通道四行在日夜 / 短屏 / 1.3 倍字体 / RTL 下的视觉验收（`LayoutAuditInstrumentation` 测试包）；`BrowserTasks` 回读在新 ROM 上的真实结果。**build347 尚未真机验收**，验收项是 346→347 首次覆盖安装成功、数据完好、READY、受管运行时更新与上述三项功能的表现；build344–build347 正式包真机覆盖安装验收仍待用户；build338–build346 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `28dc573a` / 受管运行时源、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build347 更新说明](docs/releases/build347-changes.zh.md)。

## 0.2.1-rc.5-20261109.0700-dsh0.2.2-rc.1 / build346（屏幕常亮与省电模式硬互斥 + 实体主屏活动历史 M1；含受管运行时更新 runtimeId 7b1ca97d）

- 屏幕常亮与省电模式硬互斥（`d540dfe9d`）：新增 `util/PowerModePolicy` 纯逻辑；屏幕常亮与省电模式默认关闭，开启任一边强制关闭另一边并同步持久化、服务与窗口常亮，关闭一边与重复开启不误伤另一边；设置页用同步护栏阻断编程式 `setChecked` 递归，强制关闭另一边时给中英 toast；启动时把历史「双开」归一为保留屏幕常亮、关闭省电模式并写回持久化，不提示。`PowerModePolicyTest` / `ConfigStorePowerModeTest` 覆盖默认值、互不影响与归一分支。
- 实体主屏活动历史 M1（`19d928f3c`）：新增 `ScreenActivityFeed`（约 120 秒 / 64 条内存环形）、`ScreenActivityEvent` / `ScreenActivityPolicy` / `ScreenActivityQuery` 与 `ScreenActivityRecorder`；只在手机任务期间记录目标切换、用户点按 / 滚动、内容变化与助手动作；task-end、暂停、无障碍服务断开或重连立即清空；不落盘、不进备份 / 诊断日志；敏感应用只留目标切换，密码框只留「已输入」，输入法与自身浮层事件丢弃。
- 回执与工具：`/app/ui/*` 的 JSON 回执统一附带 `recentActivity`（上次回执之后至多 3 条）；新增 `GET /app/ui/history?since=<序号>&limit=20`（默认 20、最大 100，`since` 用 `nextSince` 续读；M1 不返回图片，`frames` 为空数组）；内置 `dsh-computer-use-android` 新增 `android_history` 工具并与 `/app/help` 同步。无障碍配置增加 `typeViewClicked|typeViewScrolled`，动作执行器显式记录 `ASSISTANT_ACTION` 并以 1.5 秒派发窗口区分 user / assistant；目标比对、凭据 TTL 与确认策略等执行期护栏语义不变。
- 受管运行时更新：`DshaAccessibilityService` / `HttpShellService` / `screen/ScreenActionExecutor` / 新增 `screen/ScreenActivityRecorder` / 无障碍配置与内置插件 `server.cjs` 变化，`runtime-descriptor` 由脚本重算为 `7b1ca97d`；fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- 版本与范围：版本码提升为 346（基于已发布的 build345 / `917295a5a`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本批只含上述两条线，不改设备 shell 白名单、不放宽执行期护栏。build345 已作为正式包发布（tag `dsha-a-su-build345-20261109-030000`）。
- **未验证 / 未验收**：真机验收未做：两个开关的强制关闭与 toast、历史「双开」启动归一、活动历史在真实手机任务中的记录 / 清空、`recentActivity` 与 `/app/ui/history` / `android_history` 的实际回执、敏感应用与密码框脱敏均未在真机逐项执行；M1 不提供图片帧。**build346 尚未真机验收**，验收项是 345→346 首次覆盖安装成功、数据完好、READY、受管运行时更新与上述两个功能的表现；build344 / build345 / build346 正式包真机覆盖安装验收仍待用户；build338–345 已发布版本的真机验收按各自记录。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `7b1ca97d` / 受管运行时源、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build346 更新说明](docs/releases/build346-changes.zh.md)。

## 0.2.1-rc.5-20261109.0300-dsh0.2.2-rc.1 / build345（设置 / 设备授权文案精简与状态胶囊 + 管理文件行直开系统页 + 折叠披露二次展开崩溃修复 + 开关关闭态白底 + 数据与备份 / GitHub 登录瘦身与可复制弹窗；纯 UI）

- 设置与设备能力授权精简（`0d0b9d359`）：设置页删除页顶标语与 6 个入口副标题，悬浮条 / 隐藏后台 / 版本更新 / 自检 / 关于的常显说明收起，主题与语言状态改行尾值；省电模式去掉随开关出现的动态副标题，行高恒定。设备能力授权页：设备执行通道与全局确认长说明默认折叠，重复的 Root 说明合并，删除页顶说明与多处常显解释；通道 / 系统权限 / 敏感能力 / 虚拟识屏状态统一为同行 `ShellStatusPill` 胶囊，外接设备状态短化为已关闭 / 未绑定 / 已绑定 / 未验证。开关保持 Material 胶囊形状，关闭态轨道先改近白底 + 细边（随后由 `90609c2c4` 换成 `trackTint` 方案）。该基线已经用户真机确认，作为后续迭代基线。
- 管理文件访问权限修复（`ad9b93849`）：上一提交把状态改为同行胶囊后，`DisclosureCard` 只能把详情插到竖排父容器摘要之后，而胶囊父容器已成横向行，导致「管理文件访问权限」点击无法展开（真机与单测均复现）。布局新增 `config_all_files_line` 横向行、详情容器改插到该行之后，`DeviceGrantsLayoutTest` / `GrantsAccordionTest` 同步新契约并修复 CI Source checks；回归 `DeviceGrantsLayoutTest` / `DeviceGrantsScreenModeTest` / `GrantsAccordionTest` 全绿。
- 折叠披露二次展开崩溃（`f94a95a14`）：收起时只清空详情容器，动作按钮仍挂在旧 `FlowRow`，再次展开时新 `FlowRow` 复用同一按钮触发 `IllegalStateException: The specified child already has a parent`。现在收起与释放都先摘下动作视图，渲染前再兜底 detach 一次；收起动画期间又点开时以新状态为准。补「展开→收起→再展开」「展开中改说明」两条回归用例。
- 管理文件行直开系统页与开关轨道（`90609c2c4`）：管理文件访问权限改回点整行或状态胶囊直接拉起系统「所有文件访问」页（与其它系统权限行一致），不再做折叠披露，行尾箭头恢复可见。开关上一批用 `android:track` 自绘白底轨道被 `MaterialSwitch` 忽略，默认 tint 把轨道染成与卡片同色、关闭态只剩圆点；改为样式 `trackTint=@color/switch_track_fill`（关=`shell_surface` 近白，开=主题主色），描边沿用 Material `trackDecoration`，删除失效 drawable，并补轨道染色断言防止退回同色。
- 数据与备份 / GitHub 登录瘦身（`f6274ab55`）：hero 展开区原来直接塞完整 detail、摘要又取首句，NEVER 默认展开时整段重复；改展开区只放首句之后的内容，NEVER 不再默认展开，也不叠加旧的一键备份回执（与「还没有导出过备份」矛盾）。GitHub 登录作用域 / 凭据 / 来源 / 在线校验长值改短词（未记录 / 未保存 / hosts.yml·未写入 / 已登录），换行由展开说明承载，登录状态里重复的「记录未保存」收进凭据行。
- GitHub 登录引导弹窗（`aec0ad5e9`）：弹窗改为自定义内容，命令用等宽字体框展示并可长按选择，命令与授权链接各带复制按钮；底部保留「打开终端」一键执行，另加中性按钮「复制命令」。文案中英双语（`UiText.choose`），TLS/CA 失败提示单独一行，不再拼接长段落。
- 版本与范围：版本码提升为 345（基于已发布的 build344 / `e49f3b337`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本版是纯 UI 修复与瘦身：不修改管输入、不改根启动脚本；runtimeId 保持 `f65796a2`，fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。build344 已作为正式包发布（tag `dsha-a-su-build344-20261108-130000`）。
- **未验证 / 未验收**：本批已用 SunsetTest 测试包在真机验证管理文件行直开系统页、开关关闭态白底、GitHub 登录弹窗复制（测试包证据，不是正式包验收）；其余页面在日夜、短屏、1.3 倍字体、RTL、TalkBack 下未逐项执行。**build345 尚未真机验收**，验收项是 344→345 首次覆盖安装成功、数据完好、READY 与上述页面表现；build344 / build345 正式包真机覆盖安装验收仍待用户；build338–build344 真机安装验收仍待用户。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `f65796a2` / 受管运行时源、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build345 更新说明](docs/releases/build345-changes.zh.md)。

## 0.2.1-rc.5-20261108.1300-dsh0.2.2-rc.1 / build344（T123 proot 运行根自镜像绑定（受管运行时更新，runtimeId f65796a2）+ T122 Wave D 左滑动作 / 底部抽屉；24 项卡效果最后一波）

- proot 运行根自镜像绑定（T123，`424b4a015`）：`baseArgv()` 增加 `-b <canonical rootfs>:<canonical rootfs>`，修 `/proc/self/maps` 宿主路径回读（运行根以 `/data/user/0` 别名传入、maps 却是 `/data/data` 时 guest 会误读 `<rootfs>/data/data/...` 并 ENOENT）；canonical 由 `getCanonicalFile()` 解析、失败回退传入路径；proroot v1.2.8 vendor 已绑，App 不重复绑定（升级 proroot 需复核）。落在 launcher 源，受管运行时 descriptor 由 `2ae3f266` 重算为 **`f65796a2`**；固定输入未重建、未新增受管运行时。新增 `ContainerRuntimeSelfMirrorTest`。**proot 真机核对未做**（切换 proot 会重启 Web 并中断当前会话）。
- 左滑动作 `SwipeAction`（T122 / F2，`df9d77263`）：行尾动作条 45% 阈值、超过动作条宽度 30% 阻尼；LTR 向左、RTL 向右由 `SwipeGesture` 纯逻辑镜像。滑动只露动作，破坏性动作只调用宿主既有确认弹窗（二次确认），无回呼不可点；与横向滚动冲突的列表不挂滑动；同屏只开一行；读屏提供「显示操作」。底部抽屉 `BottomSheetCard`（T122 / G1）：贴底整宽、拖拽手柄下拖 96dp 或点击关闭、返回键先收、短屏 <480dp 全高正文滚动页脚常驻、遮罩只关闭；系统关闭动画直接落终态。
- 接入与文案（T122）：自动备份记录左滑「预检恢复 / 导出」、点按开详情 / 动作抽屉；插件列表左滑「删除插件」（仍走原确认弹窗，平板双栏与临时插件禁用），插件「更多」动作与排序筛选改底部抽屉；已有确认弹窗不迁移。新增十语文案 11 条与 `ids.swipe_action_open`，刷新 hygiene 字节钉；三个新类与四个测试类登记格式化清单。
- 版本与范围：版本码提升为 344（基于已发布的 build343 / `4ba75ddd`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本版含一处受管运行时更新（proot launcher 自镜像绑定，runtimeId `f65796a2…`），不改其余代码行为、不新增受管运行时；fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- **未验证 / 未验收**：真机手势、短屏、RTL 与 TalkBack 验收未做；proot 真机核对未做（切换 proot 会重启 Web 并中断当前会话），需用户安排。**build344 尚未真机验收**，验收项是 343→344 首次覆盖安装成功、数据完好、READY、proot 运行根自镜像在真机的行为，以及 `SwipeAction` / `BottomSheetCard` 在真机短屏、RTL、TalkBack 下的表现；build338–build343 真机安装验收仍待用户。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `f65796a2` / 受管运行时源、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build344 更新说明](docs/releases/build344-changes.zh.md)。

## 0.2.1-rc.5-20261108.1100-dsh0.2.2-rc.1 / build343（T121 Wave C：确定性进度 / 有限骨架 / 错峰列表 / 卡内页签 / 吸顶折叠 / 单开手风琴 / 页尾技术说明；纯 UI）

- 进度与骨架（E1 / E4）：新 `DeterminateProgress` 支持条 / 环两种确定性进度，真实百分比只增不减、`100` 只由完成态写入；取消只发一次且取消后不再接受进度，完成 / 失败 / 取消终态停动画并收起取消；总量未知走不确定态，不伪造进度。`FiniteSkeleton` 有限骨架上限 1–2 秒，数据到达即停，到上限隐藏并回调静态提示，无无限 shimmer。
- 列表与页签（F1 / F3 / F4）：`StaggerList` 前 8 项仅首次错峰，按稳定键去重，滚动 / 重绘 / 翻页不重播；`CardTabs` 卡内页签横向可滚动、未选面板 `GONE`、选中键随旋转恢复；`GroupedList` 分组标题吸顶并可折叠，只改 `translationY`，正文收起后不进读屏。
- 手风琴与技术说明（B2 / B3）：`Accordion` 建在 `DisclosureCard` 上、单开，收起仍保留后果行与读屏语义；`TechNote` 页尾技术说明沿用 `DisclosureCard`，空明细不折叠。
- 应用面：恢复向导进度改为 `DeterminateProgress`（总量取预检的真实条目 / 字节，按后端累计已复制量算百分比，成功 / 失败 / 取消在结果步收口）；备份记录首次读取用有限骨架、前 8 条首次错峰；插件列表前 8 项错峰；诊断页工具卡内「启动与恢复 / 网络与插件」两个页签；设置页环境 / 体验 / 支持三组吸顶可折叠；设备能力授权页多段说明改单开手风琴（屏幕操作说明改插到整行之后）；启动 / 设置 / 配置页页尾统一 `TechNote`，配置页新增运行方式 / 端口 / DNS 与配置存放位置的事实说明，隐私与安全后果不藏也不新增 ⓘ。
- 版本与范围：版本码提升为 343（基于已发布的 build342 / `f5a295a3e`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本版不改代码行为、不改受管输入、不新增受管运行时；runtimeId 保持 `2ae3f266…`，fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- **未验证 / 未验收**：真机视觉、TalkBack 朗读与真实作业时延未验证；确定性进度、有限骨架、错峰、页签、吸顶折叠、手风琴与技术说明在日夜、短屏、1.3 倍字体与 RTL 下的表现未逐项执行。**build343 尚未真机验收**，验收项是 342→343 首次覆盖安装成功、数据完好、READY 与上述交互在真实设备上的表现；build338–build342 真机安装验收仍待用户。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `2ae3f266` / 202 assets / 475 launchers、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build343 更新说明](docs/releases/build343-changes.zh.md)。

## 0.2.1-rc.5-20261108.0900-dsh0.2.2-rc.1 / build342（T120 Wave B：按压反馈 / 内联加载 / 数值滚动 / 卡片层级与徽标 / 单选卡与多选操作栏；纯 UI，33 项）

- 按压反馈三变体（A1 / C1 / C2，`99c971d6c`）：可点行只补状态层（不改内容与布局），主按钮按下 0.985 缩放，大卡片圆角 18→22dp 形变；系统关闭动画时全部静态处理，禁用按钮不进入缩放手势。`UiMotion` 新增数值滚动、圆角形变与区域淡换基元。25 个页面接入：`OnboardingKit` / `CardPage` 共享按钮与行、`CardSheet` 页脚统一接入，25 个页面根视图 `attachButtons`（终端自定义触摸区与 WebView 排除）。
- 内联加载按钮 `LoadingAction`（A2，`2d261ff4f`）：左侧 16dp 真实转圈、忙碌期锁定已测宽度、禁点并写 `stateDescription`，结束恢复原文案与宽度；重复 `start` 被拒绝、同步 `run()` 异常也恢复、系统关闭动画显示静态弧。一键备份在真实任务开始时内联转圈并锁宽；部署 / 维护忙碌态仍用独立进度区，不叠加假加载态。
- 数值滚动与状态淡换（E5 / G4，`3bbae9e68`）：`RollingNumber` 记住上一值滚动到新值、单位随 formatter、关动画直接落值；`FadeSwap` 文字先写值再淡入、区域内容 90ms 淡出→重建→150ms 淡入、连续刷新只保留最后一次。存储页总量、工作台 / 自动备份 / 维护页头状态接入。
- 卡片层级与状态徽标（H1 / H2，`055b400c4`）：elevated 主卡 / filled 分组 / outlined 次级三级卡片；分组统一 filled，`CardPage.card(Tier)` 显式选级；徽标（>99 显示 99+）与状态点只用 `shell_*` token，可在同一 View 换色复用。
- 单选卡与多选操作栏（D1 / D2 / H3，`708206da3`）：`SelectableCard` 单选=2dp 描边+色调+勾选、多选=勾选框，紧凑格隐藏勾选防截断，读屏报 `radio` / `checkbox` checked；`SelectionActionBar` 已选数量徽标+动作、0 项收起计数但动作栏不跳位。接入语言选择格、虚拟屏预览档位、导出范围；保留副本与旧环境改多选底部操作栏。
- 本版是 T120 Wave B（输入反馈与视觉基元）：纯 UI 改动，不改代码行为、不改受管输入、不新增受管运行时；runtimeId 保持 `2ae3f266…`，fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- 版本：版本码提升为 342（基于已发布的 build341 / `04f2ace84`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。
- **未验证 / 未验收**：Wave B 以定向组件单测与全量单测验证；真机视觉与朗读（TalkBack）、按压手感、内联加载、卡片层级、单选 / 多选在日夜、短屏、1.3 倍字体与 RTL 下的表现均未逐项执行。**build342 尚未真机验收**，验收项是 341→342 首次覆盖安装成功、数据完好、READY 与上述交互在真实设备上的表现；build338–build341 真机安装验收仍待用户。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `2ae3f266` / 202 assets / 475 launchers、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build342 更新说明](docs/releases/build342-changes.zh.md)。

## 0.2.1-rc.5-20261108.0700-dsh0.2.2-rc.1 / build341（UI 文案第二波（T109，155 处十语）+ 折叠披露 DisclosureCard 替代 ⓘ（T120，四屏与数据/配置页 14 个披露点））

- UI 文案第二波（T109，`bb4a5a0ef` 可见批 / `caa2cbec4` 弹窗批）：155 处表面与弹窗文案（可见 134 + 弹窗 19 + 2 条并入既有条目）改为短文案与状态词（≤5 字），十语同步；`hosts.yml`、web profile、`.env`、Cordis、proot、出生身份、容器启动器、Computer Use 等内部术语移出界面或改成用户语言（登录记录 / 进程 / 网页内核 / 已连接）。安全、权限与隐私文案只改术语并保留完整后果；6 条原列入「待布局承接」，其中 5 条在本版落位，`x1970412`（应急 DSH）留 build343；`x296plugin001` 位于 launcherInputs 钉住的 `core/PluginRepository.java`，本波不改。6 个历史字节钉文件的 CR 行尾规范化为 LF 并重算。
- 新增折叠披露 `DisclosureCard` 替代 ⓘ 弹层（T120，`1dae32177`）：摘要保留在原 TextView，行尾箭头展开；收起不创建隐藏子视图，展开才创建说明 / 动作；展开状态由宿主持有并存入 Bundle，旋转 / 刷新 / 语言切换不丢；异常与放宽档位可默认展开；`UiMotion` 增加收起淡出与箭头旋转，统一曲线并保留系统关闭动画的静态降级；三处入口（普通摘要、水平行后的竖向容器、指定兄弟视图之后）；单测覆盖懒创建、默认展开、状态恢复、后果常显、动作延迟创建与 1.3 倍字体布局。
- 高频四屏与数据 / 配置页披露迁移（T120，`72daa4429` / `1f8a6002d`）：启动页恢复核对 / 未确认原因收成一句摘要、异常默认展开，运行时与 dsh 版本移入页尾「技术信息」折叠区；设置页清掉最后 1 处 InfoHint，页脚版本行补「技术信息」折叠区；设备授权页移除页头 ⓘ、分组说明改标签内折叠（Root 放宽默认展开）、全部文件访问折叠卡承载 m0546 / x1970284 / m0393、通道展开区承载 x1970572、全局确认卡以展开区承载 uiv5a010（开启后默认展开）、屏幕确认方式改折叠（「全部允许」默认展开）；数据与备份页工作台状态卡与保留副本 / 导出结果 / 配置页共 4 处长说明迁移。四屏 + 数据 / 配置页共 14 个披露点迁移、移除 13 处 InfoHint 站点，四屏内 InfoHint 清零。
- 5 条无法无损缩短的权限 / 安全文案 `m0546` / `x1970284` / `m0393` / `x1970572` / `uiv5a010` 落折叠展开区，收起态保留结论；root 放宽与全局确认异常默认展开。`x1970412`（应急 DSH）不在本批，留 build343。
- 版本与范围：版本码提升为 341（基于已发布的 build340 / `026e9444`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本版不改代码行为、不改受管输入、不新增受管运行时；runtimeId 保持 `2ae3f266…`，fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- **未验证 / 未验收**：文案、折叠卡与披露迁移以定向单测与本地编译验证；真机像素级验收（日夜、短屏、1.3 倍字体、RTL、折叠动画与状态恢复）未逐项执行。**build341 尚未真机验收**，验收项是 340→341 首次覆盖安装成功、数据完好、READY，以及披露卡在四屏与数据 / 配置页的展开 / 折叠表现；build338–build340 真机安装验收仍待用户。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、`test-vscreen-help`、`test-diagnostic-feedback`、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check` 与本期定向单测（`DisclosureCard` 相关、布局契约与版本页）；全量单测与 lint 由主会话在合并树执行；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build341 更新说明](docs/releases/build341-changes.zh.md)。

## 0.2.1-rc.5-20261108.0400-dsh0.2.2-rc.1 / build340（UI 第三批（T114，最后一批）：向导步骤容器复用、结果卡三字段、错误状态面板、P2 过渡与 A-06/D-06 十语文案）

- UI 第三批（T114）Slice A（`49e59797a`）：E-01 恢复向导的选择 / 选项步骤容器只建一次，返回上一步保留已填内容；进度 / 确认 / 结果只更新各自容器，不再整块 `removeAllViews` 重置滚动与焦点，流程清空时丢弃缓存。E-02 结果卡拆成结论 / 计数 / 错误三个字段（新增纯逻辑 `NativeResultParts` 与单测）：计数数字直接落值、结论淡入，错误默认只显示短结论，原因 / 下一步 / 错误码收进 ⓘ（UI 基准 ⑬）。F-01 错误状态面板的标题 / 说明 / 按钮 / 技术详情交叉淡入，状态行按内容键去重、相同内容不重建，图标档位变化高亮。E-08 传输页双栏首次进入详情与空态提示 150ms 淡入，同任务进度刷新不重建。
- UI 第三批 Slice B 第一批（`dd30db62c`）：A-03 环境横幅淡入；A-04 插件状态条出现淡入、状态文字仅变化时淡入（下载进度高频回调不闪）；A-05 省电模式副标题 150ms 淡入；B-08 启动加载 hint 切换 150ms 淡入；C-07 / C-08 ADB 配对忙碌时按钮保留动作文案只禁用、等待信息留状态行、状态行淡入、六控件 `enabled` 即时；D-03 配置保存条文本淡入 + 文字颜色 120ms 过渡（`UiMotion.crossFadeTextColor` + 单测）；E-09 传输页结果条出现 / 文本变化淡入，不再硬盖列表顶部。
- UI 第三批 Slice B 第二批与 Slice C（`ec62ca58a`）：B-09 部署胶囊标签 / 说明 / 计数淡入、胶囊出现淡入；E-10 后台任务计数淡入、新行 staggerIn、行标题 / 时长过渡；F-04 终端会话标题与警告行淡入；F-08 插件安装状态与按钮标签淡入、进程重建提示淡入；E-05 保留副本分页状态行与列表重建淡入；E-06 存储总量与占用条重建淡入；F-07 技能结果条出现淡入，失败结果首屏一句结论、完整原因 / 下一步进 ⓘ（⑬）。Slice C：A-06 引导第 4 步首屏 4 个 ≤5 字状态词（部署中 / 已完成 / 部署失败 / 待修复），原因与下一步进 ⓘ；D-06 四个二级页摘要 8–12 字，完整说明继续可点开 ⓘ；新增 7 条文案（「已完成」复用既有 `x1970669`）进 messages-extra 与 8 语言译文，刷新字节钉。
- 本版是 UI 第三批（最后一批）：纯 UI 改动，不改代码行为、不改受管输入、不新增受管运行时；runtimeId 保持 `2ae3f266…`，fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- 版本：版本码提升为 340（基于已发布的 build339 / `a1340483`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。
- **未验证 / 未验收**：上述过渡、状态去重与文案均以定向单测与本地编译验证；真机像素级验收（日夜、短屏、1.3 倍字体、RTL、低端机掉帧）未逐项执行。**build340 尚未真机验收**，验收项是 339→340 首次覆盖安装成功、数据完好、READY，以及本批 UI 过渡 / 文案在实际设备上的表现；build338 / build339 真机安装验收仍待用户。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、`test-vscreen-help`、`test-diagnostic-feedback`、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check` 与本期定向单测（`NativeResultPartsTest`、`UiMotion` / `InfoHint` 等 UI 用例、版本页）；全量单测与 lint 由主会话在合并树执行；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build340 更新说明](docs/releases/build340-changes.zh.md)。

## 0.2.1-rc.5-20261108.0300-dsh0.2.2-rc.1 / build339（T113 识屏档位二次确认 + T92 应急 DSH 启动页 + T97 密码焦点守卫 + T99 显示规格白名单 + T105 物理屏动作即观察；含 T93 / T107 文档）

- 识屏档位切换二次确认（T113，`29c5028b`）：设置 → 设备能力授权 → 识屏选择实体 / 虚拟时，先弹 `DshaDialogBuilder` 二次确认（说明读屏范围与可撤销），确认后才切换；成功、失败、待清理与重复选择都有行内状态和 toast，确认不会因「全部允许」确认方式被跳过。新增 `ScreenModeFeedback` 作为确认判据、模式名与结果文案的唯一来源；切换中忽略重复点按；320dp 短屏与 1.3 倍字体下中英文确认弹窗可滚动。
- 应急 DSH 启动页精简与确认流单层化（T92，`da0b9b71`）：首屏说明收成一行结论 + ⓘ；状态与启动 / 进入 / 停止三个主操作合并到一张卡；临时 API Key 默认收起，诊断 / 日志 / 备份 / 返回恢复页收进「更多」；状态与操作结果改用 UiMotion 淡入。确认流新增 `RecoveryRepairSheet`：选择与预览在同一对话框内切换，返回只退一层，等待预览时禁用重复点击，执行 / 拒绝仍在滚动内容里（短屏与大字体可滚动），提案 ID 为空时不出现执行入口；应急 Web 头部改为单行标题 + 短动作「修复提案」+ 图标重载 + 分隔线。
- 虚拟屏助手写文本的密码焦点守卫（T97 P1，`8b82e4f7`）：`/app/vscreen/type` 的纯 ASCII 快路径原由 Core 直接执行 `input text`，绕过无障碍密码检查。新增 `VirtualScreenTextInputPolicy`（密码焦点硬拒绝、安全焦点放行、有语义通道但焦点不可读时 fail-closed、无障碍未连接时保留文档化的 ASCII 能力但回复标注 `passwordFocusChecked=false`）与只读 `VirtualScreenPasswordProbe`（按 ACTION_AUTHORITY 复核当前显示唯一应用窗口并读取 `FOCUS_INPUT`，窗口变化按 `SCREEN_TARGET_CHANGED` 未发送）。ASCII 派发前预检：密码返回 `PASSWORD_INPUT_UNAVAILABLE`（`state=NOT_SENT`、`nextAction=REPORT_TO_USER`），焦点不可读返回 `PASSWORD_FOCUS_UNVERIFIED`（`retryable`、`SEE_AGAIN`）；同步堵非 ASCII type 与 node `set_text` 的密码节点写入；原生面板走独立租约不受影响，不提供「仍然允许」。
- T97 CI 测试缝修复（`d16b0c14`，测试基础设施）：`VirtualScreenInput` 增加实例级 forced-available 标记，测试实例不再被宿主 `/system/bin/input` 探测劫持（GitHub runner 无该文件导致 T97 单测红）；生产实例不设该标记，真实 missing-command 仍照旧拒绝，`systemCommandExists` 未放宽。
- 虚拟屏显示规格白名单预设（T99，`c33f358d`）：默认仍为 1008×1792@320（16:9 / 504×896 dp），新增兼容基线 compat-720 = 720×1600@320（20:9 / 360×800 dp）；未知 / 空 / 超预算规格回退默认，不接受任意尺寸或 dpi。Manager 创建与旋转只向 Core 传 preset id 与方向，Core 按白名单解析并应用 dpi、回读 preset / density / logical 尺寸；`VirtualScreenDisplaySettings` 持久化选择，虚拟屏页新增「显示规格」单选，仅影响下一次创建或旋转重建，不重建当前画面、不改帧身份与输入门。观察预算仍为 2 份 / 24 MiB，1440×2560 两份约 28.1 MiB 不进白名单；compat-720 真机验收未进行。
- 物理屏动作即观察与事件驱动 settle（T105，`c6a2ea84` / `c94df74d` / `00dd7a90`）：`/app/ui` 动作端点新增可选 `observe=1`——动作 `COMPLETED` 后在同一请求 settle（首个无障碍事件 ≤800ms、静默 250ms、总上限 2.5s；可被任务撤销与请求截止打断，不忙轮询）并重新读树，回执附新的 `treeId` / `nodeId`、文字树与 settle 元数据；坐标动作仍不返回截图，坐标凭据仍必须单独截图。旧调用方不传时行为不变；观察失败只写 `observationUnavailable`，不改写动作状态；UNKNOWN 不得重放；凭据失效类拒绝会附当前屏新树，便于用新凭据重试。内置 `dsh-computer-use-android` 的 7 个动作工具新增可选 `observe`（省略时 MCP 服务端注入 `observe=1`，`observe:false` 关闭；输入 schema 只加可选字段，严格参数校验不放宽）。`/app/sense/state` 连续调用默认回增量：变化超过三分之一、窗口 / 尺寸 / 焦点 / 来源 / 截断状态变化、代次或目标变化、`full=1` 时回全量，无变化回 `mode=same` 且只回一行；响应新增 `mode` / `deltaReason` / `previousSid` / `changedElements`，`format=v4-preview-3`。`k***` 仍只是阅读顺序位置编号，动作继续使用一次性 `treeId` / `nodeId` 租约。`/app/help` 中英已同步。
- 运行时升级后用户插件版本不匹配指引（T93，`bebc01b4`，文档）：补充 APK / DSH 覆盖更新不重写用户 profile 的第三方插件清单或 `node_modules`；DSH 兼容性预检按 `engines.dsh` / `@deepseek-ai/dsh*` peer 范围跳过不兼容 bundle 并逐条禁用（fail-safe，不自动删除或改写用户插件）。处理走插件管理重装与 `compatibility.json` 精确版本豁免，不手改 profile；同时说明启动日志同名 bundle 多 cordis entry 的重复行不等于模块重复导入（另有 StartupTrace 相邻相同行去重）。
- 物理屏两套观察路径设计评估（T107，`ee5adfde`，文档）：推荐方向 C（实体屏特权镜像读屏 + evdev 用户物理输入检测），用户操作检测不依赖画面、镜像帧永不签发坐标 / 动作凭据，A 轻量方案保留为无特权降级并标 `userActivity=UNKNOWN`；授权默认关、按任务激活、可撤销、失败 fail-closed。本期只做设计评估，不实现产品代码，未在真机验证抓帧 / evdev / 各通道权限。
- 版本与受管输入：版本码提升为 339（基于 build338，合入 main 63805377 后 6 个提交与 build339 合并树 T99 / T105 / T97 CI 修复）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。合并树统一重算 runtime-descriptor（runtimeId `2ae3f266…`，475 launchers / 202 assets）；版本与文档改动不改变它；固定输入保持冻结（本版使用锁定的 fixed-inputs 20261105e），不新增受管运行时；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- **未验证 / 未验收**：T113 二次确认、T92 应急启动页与确认流、T97 密码焦点守卫、T99 compat-720、T105 观察 / settle / 增量均以单测与本地编译验证，真机与截图验收未执行；T107 为设计评估，未实现、未验证；build338 真机安装验收仍待用户，**build339 亦未真机验收**，验收项是 338→339 首次覆盖安装成功、数据完好、READY，以及上述 UI / 虚拟屏 / 识屏行为的真机核对。
- 验证范围：发布身份与前置检查、宿主 contracts 整组（descriptor `2ae3f266` / 202 assets / 475 launchers、第三方声明、内置插件、凭据路径、semver、host-tests manifest、源卫生、UI i18n、CHANGELOG、文档链接）、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback` 与本期定向单测；全量单测与 lint 由主会话在合并树执行；正式签名 assemble 不在本机执行（无发布 keystore）。结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build339 更新说明](docs/releases/build339-changes.zh.md)。

## 0.2.1-rc.5-20261108.0030-dsh0.2.2-rc.1 / build338（T110 页面保活 K0–K2 + 应用内往返不重连 + UI 第二批 T114 余量与错误 ⑬）

- 页面保活 K0–K2（T110，`ba13ed5f` / `02e187e0`）：Web 宿主页在同一任务内切到原生设置 / 终端 / 插件页时不再销毁 WebView 文档；返回时以同一 generation / instance 接管原页面，不重新 `loadUrl`。新的 `BrowserPageKeeper` 把页面所有权提升到应用作用域，`onDestroy` 按配置变更 / 正常结束 / 系统回收三分支处理；停止、维护、Web 进程换代、renderer 退出、origin-restore、任务移除与低内存 trim 走显式退役，真实停止不会被保活掩盖。K0 新增脱敏诊断 `WEB_ENGINE` / `WEB_PAGE_DESTROY` / `WEB_KEEPALIVE_ATTACH|DETACH|RETIRE`（原因码白名单，不记录 URL、cookie 或页面标题）。
- 应用内往返不再重连、不整页重渲染（T110 / K5 场景 1，`b80d8225` / `7f13f06f`）：回到 Web 时宿主派发 `dsha-browser-resume`，受管 `app-integration/client.js` 先用一条只读 `session.control` 流探测当前 carrier；有帧返回即保留 Connection，不重建 Remote 基线，长页面不再整页重渲染或长时间卡加载；探测超时但 Connection 仍为 `connected` 时也不重置（真死 carrier 交给服务端心跳 / socket close）；Connection 已断开或探测能力缺失时退回原行为。新增脱敏 `WEB_PAGE_RESUME` 诊断（impl / trigger / state / probe / reset / hiddenMs / inApp），便于后续回归定位。
- K5 真机回执：场景 1（流式长页面应用内往返）已在 LENOVO TB321FU / Android 16 / WebView 140 由用户实测通过——黄→绿重连消失、流式输出不再整页重载、不再长时间卡加载，草稿保留；验证包为二轮诊断测试包 `3d3866f7`（与本次合入的修复同一内容，包名 `io.github.sunsetrne.dsha.test`）。其余场景（2–13）与第二台设备 / 第二种 WebView 未执行，按用户决定声明为实验性、不阻塞合并。
- 实体屏遮挡判定修复（`bfe82527`）：`MainScreenWindowPolicy` 先收集 display 0 上层级高于目标且相交的 cover，再按每条边统一取最大裁切量计算，结果与窗口列表顺序 / 层级无关；系统装饰与输入法的贴边判定加 2px 容差与「沿边覆盖 ≥80%」规则，覆盖不足但向内延伸 ≤ 目标垂直方向 1/4 的贴边窄条 / 小浮层按该边裁切；内部覆盖仍整窗拒绝坐标，未引入部分遮挡降级；零面积 / 反向 / null bounds 按几何不覆盖跳过（P2）。ZUI 真机形状（居中 taskbar + 边缘游戏浮层）由整窗拒绝改为裁切；浮动 IME / 分屏 / PiP 与 ZUI / vivo 实机复测未做。
- 虚拟屏 status 补下一步（T88，`850875bf`）：独立 `/app/vscreen/status` 无 Core 时除 `ok:false` + `VSCREEN_NOT_RUNNING` 外补机器可读 `nextAction=CREATE_FIRST` 与 `retryable`（待清理仍 `ASK_USER_TO_CLOSE`），原有 error / state / lifecycle 字段不变；`HttpShellService` 与 `VirtualScreenManager` 属受管 launcher 源，descriptor 按合并树重算（runtimeId `a77aa93f…`）；定向单测覆盖无 Core 与待清理两种 status 形状。
- UI 第二批（T114 余量 + 错误与复合状态 ⑬，`61245640`）：启动恢复界面动作行只留动作标题，效果说明收进行尾 ⓘ，不可用原因留在行内，分组说明改为标题旁 ⓘ，列表重建恢复滚动位置并淡入，结果横幅出现 / 换文过渡；安装页结论 / 状态 / 进度淡入、秒表只改数字不闪烁、六段段位变化高亮，维护错误按 UI 基准 ⑬ 收成短结论 + ⓘ 并保留错误语义色；诊断页结论 / 状态 / 按钮交叉淡入、图标档位高亮；维护页阶段条只在阶段形态变化时重建；设备授权 root 放宽警告与概览胶囊淡入、色点高亮；数据页状态卡结论淡入、路径与原生任务明细进 ⓘ；传输页返回列表 160ms 反向滑出；WebPreview / WebCrashPanel 顶部进度条淡入淡出、错误状态层 180ms 淡入。`UiMotion` 新增 `crossFadeTimerText` / `flash` / `reveal`，`InfoHint` 新增 `bindOnSurface`；新增 `StartupRecoveryLayoutTest` 并扩充 UiMotion / InfoHint 用例；新文案「查看说明：」进入 messages-extra 与 8 语言译文。
- 内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变；本版不更换插件，不改 Ubuntu 基础环境。
- 版本与受管输入：版本码提升为 338（基于 build337）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。受管输入随 T110 / T88（含遮挡修复）合入按合并树重算 runtime-descriptor（runtimeId `a77aa93f…`），版本与文档改动不改变它；固定输入保持冻结（本版使用锁定的 fixed-inputs 20261105e），不新增受管运行时。
- **未验证 / 未验收**：T110 仅 K5 场景 1 单设备真机通过，其余场景按用户决定为实验性；UI 第二批以 Robolectric 布局 / 动效单测与本地 lint 验证，真机像素级动效（低端机掉帧、深色模式、1.3 倍字体、横屏 / 平板）未逐项验收。**build338 尚未真机验收**，验收项是 337→338 首次覆盖安装成功、应用内往返不重连 / 不整页重渲染、流式输出继续、停止 / 维护 / 换代仍正确退役、数据完好、READY。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、全量 `:app:testStandardDebugUnitTest`、`:app:lintStandardRelease`、`:app:assembleStandardRelease`、全树 Java 格式、`git diff --check`、`generate-changelog --check`、文档链接检查、`test-diagnostic-feedback` 与遮挡 / 虚拟屏定向单测；结果以本机发布准备回执与本次 CI run 为准。
- 十语种更新说明见 [build338 更新说明](docs/releases/build338-changes.zh.md)。

## 0.2.1-rc.5-20261107.1900-dsh0.2.2-rc.1 / build337（手机任务接口 409 修复 + Web 终端 pty.fork 预加载 + UI 长文本第一批 + 隐藏后台最近任务重放；不含 T110）

- 手机任务接口 409 修复（T115，`a9c718e2`）：build336 正式包的实体屏手机任务窗无法建立——内置 `dsh-status-overlay` 的 `sessions.status` / `sessions.cancel` 仍在调用 0.2.2-rc.1 已移除的 `SessionController.inspect`，`sessions.status` 抛 TypeError，桥统一回 409，Java 侧只见 `ASSISTANT_TASK_CHANGED`。现改为 `controller.state` + `controller.events` 的有界读取（最近 16 条消息事件裁剪为最近 8 条可见对话；turnId / turnActive 优先用 Host 的 turnBoundary 投影），响应字段与 Java 读取保持一致；host 测试 11/11 通过。正式包真机待用户安装 build337 后验证。
- Web 终端 `pty.fork` 修复（T116，`adab1698`）：同一 Node 进程里 `sharp` 先于 `node-pty` 加载会把 `pty.node` 导出注册到 sharp/libvips 键，之后打开 Web 终端报 `pty.fork is not a function`（重试无效）。启动预加载 `startup-observer.cjs` 在插件加载前 require node-pty，失败静默、不影响启动；根因与复现见 [T116 审计](docs/design/t116-pty-fork-20261107.md)。
- UI 长文本整改第一批（T114，`c4c79941`）：审计报告 §2.3 的 12 处 P1 默认可见长文改为页内 1–2 行摘要，完整说明复用既有 InfoHint / InfoSurface 弹层（不新增翻译键，安全/隐私文案语义不变）；`UiMotion` 新增 `crossFadeText` 与 `animateCounter`，试接维护页状态、自动备份状态与启动页大标题；新增 UiMotionTest（9 项）与 InfoHintTest（6 项），ui 包 53 个测试类 449 项通过。
- 隐藏后台最近任务重放（T111，`e809f72c`）：`MainActivity.onCreate`、`WebPreviewActivity.onCreate` 与 `onResume` 重放 `BrowserTasks.applyRecentsPreference`，覆盖进程重启 / 覆盖更新 / 系统重建任务后「新任务默认可见」的缺口；清单只保留任务根 Activity 的 `excludeFromRecents`。定向单测 51 项通过；ColorOS 16 与 Lenovo 真机行为未验证，不据此宣称现场已修复。
- 文案与 CI/文档修复：T109 切片 1 合并 15 处原生 UI 同义/近重复并统一术语、修正唯一一处俄语超长（`44e44119`）；启动状态条补十语文案（`c4fd2828`）；补齐 Java 格式清单（`ec532d9a`）与六份翻译字节钉（`6b573957`），恢复 contracts / java-format 门禁。UI 基准线 v2–v5 与 [T112 审计](docs/design/ui-baseline-audit-20261107.md)（51 项，P1 22）为 docs-only 记录，不含代码改动。
- 内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变；本版不更换插件，也不改 Ubuntu 基础环境。
- 版本与受管输入：版本码提升为 337（基于 build336）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。受管输入随 T115/T116 重算 runtime-descriptor（runtimeId `da08b4cb…`）；版本与文档改动不改变它；固定输入未重建，不新增受管运行时。
- **未包含 / 未验收**：**T110 页面保活不在本版**——从网页返回原生主页面再回网页时，侧栏连接状态重走「黄→绿→隐藏」与原生加载遮罩的已知问题仍在；**K5 未验收**（自动化被 build336 正式包 409 阻塞，需按测试包流程手动执行，或等 build337 正式包后再自动化）；T111 / T115 / T116 的真机行为待用户安装正式包验证。build337 尚未真机验收，验收项是 336→337 首次覆盖成功、手机任务工具可 begin、Web 终端可打开、隐藏后台按开关生效、数据完好、READY。
- 验证范围：发布身份与前置检查、宿主 contracts 整组 12 项、host-tests fast 与 runtime、全量 `:app:testStandardDebugUnitTest`、全树 Java 格式、`git diff --check`、`generate-changelog --check` 与文档链接检查；结果以本机发布准备回执与本次 CI run 为准。Lint 由 release-production 的 CI lint 节点执行。
- 十语种更新说明见 [build337 更新说明](docs/releases/build337-changes.zh.md)。

## 0.2.1-rc.5-20261107.0330-dsh0.2.2-rc.1 / build336（内置 dsh-web-mobile 同步上游 3.3.1：完全追随上游 + 宿主集成最小集 + 文件传输/多语言，恢复默认启用）

- 内置 `dsh-web-mobile` 同步上游 3.3.1 并恢复默认启用（T101，`517078d2`）：按用户选定的 C 方案，以上游 3.3.1（`4e71eb67`，适配宿主 0.2.2）为基座、**完全追随上游**，只保留宿主集成必需的最小集。上游修整页白屏、顶栏开关被裁、文件键消失、页签叠字与文件面板标签条被顶栏盖住；固定 HEAD 而非 v3.3.0 / v3.4.0 标签（后两者 react 仍为必选 peer）。`lib/client.js` 摘要 `7f82fac2…`；内置注册表不再有 `defaultDisabled` 条目，全新安装与覆盖更新都默认启用，用户显式禁用仍然优先。
- C 方案只比纯上游多五类宿主集成：齿轮几何让位 `DSHA_GEAR_FIT_V1`（上游按连接指示灯存在性判断，0.2.2 首连可能误判，改为实测几何）；安全区变量包装 `--dsha-safe-area-inset-*`（Android WebView 不报告系统栏 inset，跳过上游已包好的一处，不嵌套）；服务端三模块替换（删除会话走宿主鉴权 `connection.requestRejection`、压缩只作用当前 webServer 端口、live 会话经公开 AgentHandle 删除）；原生文件传输（`lib/file-transfer.js` + `/api/dsha/files/{prepare,release}`，保存到 / 分享 / 下载记录 / 图片保存，32 MiB 分享限制与失败回退）；多语言与 live-locale（8 种附加语言，移动壳标签不再回退英文）。
- 删除陈旧本地补丁：`DSHA_SESSION_INPUT_V4`（会话行触摸单击 / 抽屉关闭改写）、手机快捷键弹层搜索行与可见带 CSS、`DSHA_FONT_FAMILY_ROW_V1` 字体族行换行、`DSHA_BACKDROP_PRESS_V1` 遮罩按压染色、宽屏触屏档收起规则（上游 PR #257 已接管）；`test-mobile-backdrop-press.mjs` 与 `test-mobile-modal-behavior.mjs` 随之退役，host-tests manifest 252→250 项。上游已接管项不重放。
- 已知风险与接受项（真机未验证前不写通过）：335→336 首次覆盖安装待验证；验收项为主界面不白屏、侧栏与 footer 只有一个设置入口、`dsh-web-mobile` 默认启用、文件面板原生保存/分享/下载记录可用、10 语言标签正确、个人数据完好。按上游行为接受的回归：手机快捷键弹层无搜索、设置字体族行可能被压窄、深色主题按压遮罩会亮闪；会话行单击与抽屉关闭回到上游实现。
- 版本与受管输入：版本码提升为 336（基于 build335）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。合并插件替换后按 `tools/prepare-runtime-descriptor.py --write` 重算 runtime-descriptor（runtimeId `5b502f71…`）；版本与文档改动不改变它；固定输入未重建，不新增受管运行时或插件。
- 验证范围：`:app:testStandardDebugUnitTest`、`:app:lintStandardRelease`、宿主 contracts 整组 12 项、全树格式检查、`git diff --check`、文档链接、`check-ui-i18n`、`generate-changelog --check`、`test-terminal-home-i18n.py`、`test-ui-language-catalog.py`、`verify-runtime-descriptor-source`、`prepare-runtime-descriptor --check`、host-tests manifest、`generate-builtin-plugins --verify` 与 `test-mobile-*` 整组；结果以本机发布准备回执为准。未 push、未合并、未发布、未安装。
- 十语种更新说明见 [build336 更新说明](docs/releases/build336-changes.zh.md)；详细补丁审计、五类宿主集成与回退见 [build336 内置插件同步记录](docs/releases/v0.2.1-rc.5-build336-web-mobile-3.3.1-20261106.md)。

## 0.2.1-rc.5-20261107.0140-dsh0.2.2-rc.1 / build335（P0：build334 首次覆盖失败修复 + 恢复身份证据兜底 + 虚拟屏预览呼吸条 + 覆盖更新对话框 i18n）

- trial 收尾对未退 guest 有界强杀（T103，`a5168f99`）：`manager.stop()` 返回非空时不再立即判 `TRIAL_PROCESS_UNCONFIRMED`。先有界等待本轮启动器 3 秒，再由 `WebProcessManager.forceStopTrialGuest` 按本轮记录、唯一 profile 与保存的出生身份连读复核后，只对本轮 guest 发 SIGKILL，随后重跑记录停止与全局无 Web 核验；仍无法确认才失败，并把脱敏详情写入可导出操作记录与记录目录（成功试用的收尾失败写 `failure.json`，恢复路径写 `recovery-stop.json`，已有失败详情不覆盖）。`TrialRecovery` 走同一顺序，一次 `recover-maintenance` 就能关闭停在 guest 未退的旧 trial；强制路径缺出生身份、PID 记录对不上或两次观察变化时 fail-closed，不按裸 PID / 名称扫描。维护页 `pendingTrial` 去掉 2 秒缓存，`onResume` 丢弃 `BackupTask` 的 UI 缓存，外部恢复完成后立即回到「上一版运行时待更新」。
- 恢复按出生身份复核并补写身份证据（T100，`903974db`）：启动恢复与前后台复核共用 `observeRecoveredSession`；严格 PID / 身份文件核验为 UNKNOWN 时降级到完整出生证明（boot / PID / 出生时间 / 命令行 / UID / generation+instance+nonce），严格核验已给出 LIVE / EXITED 则不降级，证明版读不到仍 fail-closed。接回成功后补写 `/root/.dsha-web.pid` 与 `.dsha-web.identity`（pid 记录指向其它编号时原样保留），修掉身份文件陈旧 / 被退休后每次启动都落回 `IDENTITY_UNKNOWN` 的代码缺口；终局 UNKNOWN 写入可导出诊断 `WEB_SESSION_RESTORE_FAILURE`（stage / reason / attempts），`IDENTITY_UNKNOWN` 文案给出「停止 → 普通停止无法确认时按身份核验强制结束 → 重启再核对」的下一步。
- 虚拟屏预览改版（T96，`71010565`）：默认不再自动展开 172dp 页内小窗，改为宿主页顶部蓝色呼吸条（3dp 呼吸线 + 目标应用文字「应用名 · 包名」，视觉 27dp、热区 40dp）；点击打开虚拟识屏页，× 收起。新增三档「提示条（默认）/ 完整预览（旧页内小窗）/ 关闭」（设置 → 设备能力授权 → 虚拟屏预览，选择档位即清除收起意图）；收起持久化在应用私有偏好，跨虚拟屏代次与 App 重启保持，不再按 generation 记忆；虚拟屏停止 / 撤销、宿主离开前台、虚拟屏页自身、系统悬浮预览显示时移除。目标只来自 Manager 只读 `targetStatus`，拿不到显示「虚拟屏操作中」，不按启动记录猜测、不取帧、不签发输入凭据；呼吸动画遵循 `UiMotion`。
- 覆盖更新对话框文案补全并纳入 release 门禁（T102，`0331a00b`）：补 T91 的「放弃上一版 Web 并强制结束？」与其说明正文，zh / en 入 `tools/i18n/messages.json`，其余 8 个语言补同一 id 的完整译文，不留空、不用占位；同步更新 `check-source-hygiene` 中 6 个已审阅语言文件的字节钉住哈希，并把 `test-terminal-home-i18n.py` 加入 release-production / release-upstream 的 host-regression 子集，避免再次出现 push 红、release 绿。
- build334 真机结论（P0，未通过）：333→334 首次覆盖失败，两台设备都在 trial 收尾停在 `TRIAL_PROCESS_UNCONFIRMED`（事务如 `c4299668…`），运行时更新事务半提交、启动被维护屏障拦住；随后在维护页再执行一次维护即 334/READY、数据完好。build334 记为「首次失败、二次可用」，不写通过。**build335 尚未真机验收**；验收项是单台 334→335 首次覆盖安装即成功、维护完成进入网页、数据完好、READY，未验证前不写「已通过」。
- 验证范围：`:app:testStandardDebugUnitTest`、`:app:lintStandardRelease`、宿主 contracts 整组 12 项、全树格式检查、`git diff --check`、文档链接、`check-ui-i18n`、`generate-changelog --check`、`test-terminal-home-i18n.py`（必须绿）、`test-ui-language-catalog.py`、`verify-runtime-descriptor-source`、`prepare-runtime-descriptor --check` 与 host-tests manifest；结果以本机发布准备回执为准。版本码提升为 335（基于 build334）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1；受管输入随 T103 / T100 / T96 重算 runtime-descriptor（runtimeId `2d456af3`），版本与文档改动不再改变它；固定输入未重建，不新增受管运行时或插件。
- 十语种更新说明见 [build335 更新说明](docs/releases/build335-changes.zh.md)。

## 0.2.1-rc.5-20261106.2228-dsh0.2.2-rc.1 / build334（P0：覆盖更新后 trial 收尾误判半提交，修复弃旧版与恢复出口）

- P0 覆盖更新修复（T91，`a70232fc`）：build333 真机覆盖更新后，proroot 启动器在试用 guest 退出后残留（OnePlus PJD110 / Android 16 / WebView 156 实测，后台无其它 Web 也复现），trial 收尾误判 `TRIAL_PROCESS_UNCONFIRMED`，运行时更新事务停在半提交（`files-committed` / `settings-committed`）、启动被维护屏障拦住、重启无效。trial 收尾改为只按本记录 guest 的退出证据确认，启动器残留不再改变结论；`RuntimeTrial` 只对本次持有的 `Process` 句柄做一次有界清理（超时 / 失败仅写 `TRIAL_LAUNCHER_RETENTION` 诊断）；`launched` 但缺 `.dsha-web.pid` / `.stale` 的 trial 恢复改按记录 id 推导的试运行 profile 确认无此进程（同 UID、两次一致；仍存在 / 拒读 / 身份变化继续 fail-closed）。
- 维护失败可诊断、恢复后清旧失败：维护任务开始 / 失败写入可导出的操作记录（`MAINTENANCE_TASK_START` / `MAINTENANCE_TASK_FAILED`），失败态保留应急 DSH、维护记录、导出可读副本与「进入受限主界面」；外部恢复完成后，环境可运行且无维护进行 / 待恢复时丢弃旧失败快照（`RuntimeUpgradeState.clearStaleFailure`），回到「上一版运行时待更新」并给出更新入口，不再要求再点重试；启动记录把「已拿到鉴权链接但未写最终 ready / failed」标为「已就绪 · 等待进入」。
- 覆盖更新弃旧版（I10）：只有「已装 APK 身份 ≠ 记录中的会话身份」时，维护 / 强制停止动作内才按记录出生身份（boot、PID、出生时间、命令行、UID、代次/实例/nonce）双次核验，先结束记录中的 guest，再结束对应的旧 proot 启动器（SIGTERM → 短等待 → 复核 → SIGKILL）；guest 未确认退出不碰启动器，任一核验失败 / 读不了 / PID 复用 / 他人 UID / 两次观察变化都不发信号；普通停止、当前实例、终端、虚拟屏与隔离试运行路径不变（I9）。
- build333 真机事故：2026-11-06 在 OnePlus PJD110（Android 16，WebView 156）从 build332 覆盖安装 build333 后触发上述半提交（事务 `69ebb4f7…`），启动被拦且重启无效；经应急 DSH `recover-maintenance` + 重试后恢复 READY / 42117，数据完好。**build334 尚未真机验收**，验收项为从 build333 覆盖安装后不再卡在 `TRIAL_PROCESS_UNCONFIRMED`、维护能完成并进入网页，不预写通过。
- 验证范围：全量单测（`:app:testStandardDebugUnitTest`）、`:app:lintStandardRelease`、宿主 contracts 整组 12 项、全树格式检查（JDK 17）、`generate-changelog --check`、文档链接与源码整洁检查；结果以本机发布准备回执为准。版本码提升为 334（基于 build333）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1；runtimeId 随 T91 的 `launcherInputs` 变更重算为 `28d51d65…`，版本与文档改动不再改变它；固定输入未重建，不新增受管运行时或插件。
- 十语种更新说明见 [build334 更新说明](docs/releases/build334-changes.zh.md)。

## 0.2.1-rc.5-20261106.1830-dsh0.2.2-rc.1 / build333（跨应用文件访问 SAF + 隐藏后台开关 + 设备授权页 UI 一致性）

- 跨应用文件访问（SAF，PR #3，review `5797594537` APPROVE，merge `9cc91f5e`）：设置 → 设备能力授权 → 系统权限新增「跨应用文件访问」；用户经系统文档选择器授权目录后持久化读/写（最多 8 棵树），授权页管理与只读浏览。新增 `/app/saf/*` 桥端点（read 256 KiB、write 16 KiB、push/pull 32 MiB、列表 500 项、`rm` 两段式一次性 token），协议版本保持 3（纯加法）；只认授权短 id、写/删要求可写，容器路径复用 `BridgePathPolicy`，本地文件用 `O_NOFOLLOW` + dev/ino + `/proc/self/fd` 真实路径复核并拒绝硬链接与 `.l2s`；内置 `dsh-device-shell-guide` 提示词与 `/app/help` 各加一条入口说明。受管输入随该 PR 重算 runtime-descriptor（runtimeId `04b24571`），固定输入不重建。
- 设置 → 体验新增「隐藏后台」开关（`73896284`，ADB 保活辅助）：默认关闭、本机偏好、不进备份白名单；开启后把本应用全部有效任务移出最近任务，关闭后恢复并按「只留一张主卡」重新分类；重启后重新读取偏好并应用，失败写 `DiagnosticLog` 并下次重试。只影响最近任务可见性，不改 `DeviceBridgeService`、常驻通知、前台服务与停止语义，不承诺保活。
- 设备能力授权页 UI 一致性修复（`8509ae0a`）：状态胶囊在 `PluginFlowRow` 内自适应（`wrap_content` + 最小 56dp 宽，放不下换行），状态去掉 12dp 缩进、允许两行、字号收进资源（12sp）；其余 9 个语种「关闭」状态词改用「已关闭」译条（中文保留「关闭 / 關閉」），覆盖概览状态、识屏与剪贴板档位三处；概览条压平（`shell_bg` + 1dp 底线，去下圆角/强描边/阴影），短屏并入滚动区。`DeviceGrantsLayoutTest` 同步新增十语种词义、320dp 长译文、1.3 倍字体与短屏用例。
- 验证范围：宿主 contracts 整组、全树格式检查（JDK17）、`generate-changelog --check`、文档链接与源码整洁检查、`test-release-*` 相关门禁，以及定向单测（更新页、SAF 五类 + 路由、`TaskNavigationPolicy`、`BrowserTasks`、`ConfigStore` 隐藏后台、设备授权页布局）。**两项新功能未真机验收**：`androidTest` 源集存在既有编译错误（`RecoveryInstrumentation` 调 `HarnessService.refreshPowerMode()` 少传 `Context`），隐藏后台的最近任务行为与授权页像素级截断（1.3 倍字体、短屏、RTL）待真机。版本码提升为 333（基于 build332）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1；runtimeId 保持 `04b24571`。
- 十语种更新说明见 [build333 更新说明](docs/releases/build333-changes.zh.md)。

## 0.2.1-rc.5-20261106.0700-dsh0.2.2-rc.1 / build332（docs-only：更正 T58 归档文档事实口径）

- 本版只更正 T58 归档文档的事实口径：首次连接提示补丁自 build324（`a54f9854`）起已并入并随包，build324–build332 都携带；归档 tag `archive/feat/first-connect-indicator-20261106`（`5918af63`）只是同一补丁的独立分支快照，不另行合并，也不表示补丁不在包内。`docs/design/first-connect-indicator-20261106/README.md`、文档索引与 build331 更新说明已按此更正。
- 无新功能、无行为变化、不改运行时：不重建 fixed-inputs，不改 managed-runtime-inputs 与 runtime-descriptor（runtimeId 仍 `35e3f499`），不新增受管运行时或插件。
- 验证范围：宿主 contracts 整组、全树格式检查（JDK17）、host-tests manifest 与定向单测（更新页）；未真机验收。版本码提升为 332（基于 build331）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build332 更新说明](docs/releases/build332-changes.zh.md)。

## 0.2.1-rc.5-20261106.0400-dsh0.2.2-rc.1 / build331（proroot git 大 diff 崩溃 fail-loud + 首次连接提示补丁归档）

- proroot 下 git 大 diff 崩溃改为 fail-loud（T82）：build330（proroot v1.2.8）本机实测 `git show --stat`、`git diff --stat` / `--numstat` 对某些提交或 ≥8 路径组合 exit 139、stdout 0 字节、stderr 仅 `[proroot] SIGSEGV pc=… lr=…`；命令接 `| head` / `;` 时最终 rc 被吞成 0，审查与合入可能静默拿到空数据。新增 `tools/git_guard.py`（`GitSigsegvError` / `GIT_PROROOT_SIGSEGV`，不重试、不返回部分输出、给出安全替代），`check-source-hygiene` / `ci-handoff` / `format-java` / `verify-stability` / `verify-ci-package` 统一改用 `git_output`；新增 `tools/test-git-guard.py` 并登记 host-tests manifest；AGENTS 与运行引擎文档中英记录本机陷阱与审查/合入安全用法。未改 proroot 本体、固定输入、受管运行时输入与 runtime-descriptor（runtimeId 仍 `35e3f499`）。
- 首次连接提示补丁归档（T58）：`docs/design/first-connect-indicator-20261106/README.md` 记录补丁的结论、难复现原因、维护/风险代价与后续任务前置条件，并加入文档索引；该补丁自 build324（`a54f9854`）起已并入并随包（build324–build331 都携带）；归档 tag `archive/feat/first-connect-indicator-20261106`（`5918af63`）是同一补丁的独立分支快照，不另行合并；本归档不新增或扩展补丁，也不回退已并入补丁；本版不改运行时。
- 验证范围：宿主 contracts 整组、全树格式检查（JDK17）、host-tests manifest（251 项，含 git guard 回归）与定向单测（更新页）；未真机验收。版本码提升为 331（基于 build330）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build331 更新说明](docs/releases/build331-changes.zh.md)。

## 0.2.1-rc.5-20261106.0230-dsh0.2.2-rc.1 / build330（虚拟屏拒绝宿主自身启动 + APK 受管 gh 资产改名修复）

- 虚拟屏 self-launch 拒绝（T76）：`/app/vscreen/launch` 不再接受把 DSHA 自身启动到虚拟屏。系统不会为副屏另起实例，而是复用正在运行的同一进程；本进程里已绑定默认 display 的 WebView 在副屏会以 `libmonochrome` 空指针崩溃（relay 真机证据：display 4、主线程 Looper 进入 WebView、复用已运行的进程 14715）。bridge 在授权询问前、Manager 在 claim 前、Core 在 `am start` 前三层用同一条判定拒绝，返回 `VSCREEN_SELF_LAUNCH_REFUSED`（state=NOT_SENT，不发出启动命令、不再崩溃）；内置插件把该码归 pre-dispatch，原生选择器不再列出宿主，结果页给中英原因。这是安全拒绝，不是支持副屏 self-launch；以后若要支持须 createDisplayContext + 独立 WebView。runtime-descriptor 重生成，固定输入不重建。
- APK 受管 gh 资产修复（T79，T78 结论 B）：官方 build328/329 的 APK 里只有 `assets/gh-cli.tar`（38,727,680 B，STORED），没有 `gh-cli.tar.gz`——AGP 9.1.1 资产合并对 `.gz` 自动 gunzip 并去掉扩展名，受管安装按 `gh-cli.tar.gz` 打开必定失败（wrapper 自举可兜底）。源固定输入名与 `runtime-descriptor.json` pin 不变、固定输入不重建；APK 打包名改为 `gh-cli.bin`（`tools/asset-deployment.json` 的 sourceTransforms 与 `tools/prepare-standard-assets.py`），`RuntimeTools` 按打包名打开并回退源名；新增 `tools/verify-apk-managed-assets.py` 发布门禁，在正式包（release-production build-signed）与测试包构建链上核对实际成员名、逐字节摘要与 gh 固定成员，防止再次静默改名。runtime-descriptor 随受管生成器改动重生成（runtimeId `35e3f499`），固定输入不重建。
- 验证范围：宿主 contracts 整组、全树格式检查（JDK17）与定向单测（VirtualScreenPolicy / Manager ownership / 更新页）；未真机验收。版本码提升为 330（基于 build329）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build330 更新说明](docs/releases/build330-changes.zh.md)。

## 0.2.1-rc.5-20261106.0130-dsh0.2.2-rc.1 / build329（GitHub 登录原生页 + gh CA 双视图修复 + 引导底栏统一）

- GitHub 登录原生页（T71）：显示容器 `hosts.yml` 的账号、作用域与来源，标明受管 `gh` 是否可用，并用只读 `gh api -i /user` 区分 CA/TLS 失败、token 401、未登录、网络与超时；提供登录、重新授权与退出。页面不显示 token；已知缺口是本机 token 仍存于容器 `hosts.yml`，同 UID 的网页与插件可读（隔离另立 T50，本批不处理）。
- gh 的 CA 路径修复（T73）：`RuntimeTools` 改用 canonical 宿主根设置 `DSHA_GH_HOST_ROOT`；`gh` 包装脚本在 `/data/user/0` 与 `/data/data` 两个视图查找受管 CA，两处都缺或 `DSHA_GH_CA` 指向缺失文件时明确报错退出，不再静默回退到缺新根证书的旧系统 CA。固定输入不重建，runtime-descriptor 重生成（runtimeId 10e8595e）。
- 引导第 4 步底栏统一（T72）：欢迎流程完成页改用与运行环境维护页相同的 `ExtractFooter` 紧凑两行布局，更新中显示 x/5、恢复主操作，就绪后进入主界面。
- 验证范围：宿主 contracts 整组、全树格式检查（JDK17）与定向单测（更新页、GitHub 登录、gh 宿主根、引导页）；未真机验收。版本码提升为 329（基于 build328）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build329 更新说明](docs/releases/build329-changes.zh.md)。

## 0.2.1-rc.5-20261106.0010-dsh0.2.2-rc.1 / build328（gh 纳入受管资产 + 自举兜底 + /opt 跨重建契约）

- gh 不可用根因修复（T69）：官方 GitHub CLI v2.103.3 作为第 12 项固定输入 `gh-cli.tar.gz` 进入受管资产，安装时只提取固定成员、二次校验并原子发布到 `/opt/gh/bin/gh`；整树重建按受管注入。包装脚本在缺失/损坏时按固定 URL + 摘要自举，失败 fail-closed 并打印恢复命令；删除「bundled」误导文案。
- 新增 `util/MaintenanceToolPaths` 明文契约与重建前有界预检：`/opt` 保留为用户工具，`/usr/local/bin` 不保留，`usr/local/share/dsha` 受管注入；`/opt` 超过 10 万条在移动旧树前拒绝重建。
- 固定输入 11→12 项，bundle 716,787,167 B / sha256 be97d9f0…f08f5（fixed-inputs-20261105e，已发布 prerelease）；APK 增大约 13.0 MiB。宿主测试、contracts 整组与源码/描述符/第三方声明门禁通过；未真机验证。版本码提升为 328（基于 build327）。
- 十语种更新说明见 [build328 更新说明](docs/releases/build328-changes.zh.md)；验证记录见 [build328 gh 固定输入与 /opt 契约记录](docs/releases/v0.2.1-rc.5-build328-gh-provision-20261105.md)。

## 0.2.1-rc.5-20261105.2310-dsh0.2.2-rc.1 / build327（维护页一步完成覆盖更新；内置竖屏适配插件默认禁用并覆盖更新收敛）

- 维护页自行停止（T67）：点「更新运行时」会先按出生身份核验（boot/pid/启动时间/命令行/nonce）把旧网页进程完全停止，再取维护屏障执行更新；不必先进受限主界面点「停止」再回维护页；核验不过不杀，给出提示与重试入口，自动开始路径仍不强杀。
- 竖屏插件默认禁用（T68）：内置 `dsh-web-mobile` 默认禁用，覆盖更新同样收敛为禁用；只有当前版本内从原生插件页显式启用才生效，下一次 APK 覆盖更新再次收敛。原因：大量代码未适配 build324 起的新页面与新逻辑（曾造成白屏与侧栏重复）。恢复默认启用须满足 `docs/plugins.md` 的硬门槛并附真机证据。
- 验证范围：宿主测试、contracts 整组与源码/描述符门禁；真机结论按当次实际确认记录填写，未预写通过。版本码提升为 327（基于 build326）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build327 更新说明](docs/releases/build327-changes.zh.md)；验证记录见 [build327 维护自停记录](docs/releases/v0.2.1-rc.5-build327-maintenance-self-stop-20261105.md) 与 [build327 竖屏插件默认禁用记录](docs/releases/v0.2.1-rc.5-build327-web-mobile-default-disabled-20261105.md)。

## 0.2.1-rc.5-20261105.2200-dsh0.2.2-rc.1 / build326（覆盖安装按补丁级判定是否维护：同一 DSH 版本换受管补丁也进维护页，更新回到主操作位）

- 维护判定（T60）：同一 DSH 版本、只更换内置受管补丁的覆盖安装也会进入运行环境维护页；「更新运行时」回到主操作位，不再被「进入主界面」压过。新增 `util/RuntimeUpgradeState`（`upToDate` / `maintenanceRequired` / `limitedEntry` / `openMaintenancePage`），`ExtractActivity`、`EnvironmentUiStatus`、`MainActivity.checkEntry` 接入。
- 入口放开（T60 第二笔）：未确认的旧网页会话只拦「自动开始更新」（`EnvironmentIdentity.shouldAutoStart` 未改），维护页与一键手点更新保留；未知实例不会自动重放。
- 验证范围：宿主测试、contracts 整组、本版记录的 11 个定向测试类与源码/描述符门禁；真机覆盖安装结论待用户按记录逐项核对后补写，未预写通过。版本码提升为 326（基于 build325）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 契约文档 [覆盖升级与维护门禁](docs/overwrite-upgrade-and-maintenance.md)（I1–I8）已随本版落地；十语种更新说明见 [build326 更新说明](docs/releases/build326-changes.zh.md)，验证记录见 [build326 记录](docs/releases/v0.2.1-rc.5-build326-overwrite-maintenance-20261105.md)。

## 0.2.1-rc.5-20261105.2135-dsh0.2.2-rc.1 / build325（修复 build324 覆盖安装后的白屏：移动壳齿轮让位不再依赖连接指示灯）

- 白屏修复（T61）：移动壳侧栏 footer 齿轮改按实测行宽让位（`DSHA_GEAR_FIT_V1`），不再依赖宿主是否渲染「连接状态」指示；侧栏不再叠加，工作台不再白屏。重新生成 `dsh-web-mobile` 的 `client.js` 与第三方声明摘要，`dsh-runtime.bin` 与固定输入包不变。
- 覆盖升级：本版测试包在真机上能正常触发运行环境维护与重建，不再把「进入主界面」当成主操作。
- 验证范围：宿主测试、源码与运行时描述符门禁（含移动壳齿轮契约回归）；用户已在测试包真机确认白屏消失、覆盖升级能正常触发环境重建；其余项（齿轮可见可点、首连提示、技能审批弹窗、平板档）待用户补确认，未写为通过。版本码提升为 325（基于 build324）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build325 更新说明](docs/releases/build325-changes.zh.md)，验证记录见 [build325 记录](docs/releases/v0.2.1-rc.5-build325-mobile-gear-fit-20261105.md)。

## 0.2.1-rc.5-20261105.2020-dsh0.2.2-rc.1 / build324（user-only 技能调用一次性审批；首次进入不再闪烁连接提示）

- 技能调用确认（T54）：模型调用标记为「仅用户可批准」的技能时弹窗询问允许或拒绝；本次允许只对这次调用生效，拒绝则不放行。
- 首次进入不再闪烁提示（T58）：打开页面不再先闪「重新连接中」再闪「已连接」；真实断开后恢复仍给出提示。首次连接提示改由受管补丁 `first-connect-indicator-patch.json`（`DSHA_FIRST_CONNECT_SILENT_V1`）静默，真实断开红色提示与恢复黄 → 绿保持。
- 验证范围：宿主测试、运行时描述符与源码门禁，另有 T54/T58 的专项软件回归（见 build324 记录）；真机结论按当次实际确认记录，不扩写为全机型矩阵。版本码提升为 324（基于 build323）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build324 更新说明](docs/releases/build324-changes.zh.md)，验证记录见 [build324 记录](docs/releases/v0.2.1-rc.5-build324-first-connect-indicator-20261105.md)。

## 0.2.1-rc.5-20261105.1930-dsh0.2.2-rc.1 / build323（DSH 0.2.2 升级第一步：内置与应急 DSH 切换到 0.2.2-rc.1）

- DSH 0.2.2 升级第一步（T55 S1）：内置 DSH 与独立应急 DSH 同时切换到 0.2.2-rc.1；宿主执行器与配置迁移保持兼容，受管运行时输入按 0.2.2 重建并锁回固定摘要，插件、会话与个人数据保持原位。
- 已知显示问题：首次进入页面会先闪一下「重新连接中」再闪「已连接」；已确认为上游首次连接提示的显示行为，不影响连接与对话，修复排在下一版。
- 验证范围：宿主测试、运行时描述符校验与源码门禁；真机结论按当次实际确认记录，不扩写为全机型矩阵。版本码提升为 323（基于 build322）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
- 十语种更新说明见 [build323 更新说明](docs/releases/build323-changes.zh.md)，验证记录见 [build323 记录](docs/releases/v0.2.1-rc.5-build323-dsh022-s1-connection-display-20261105.md)。

## 0.2.1-rc.5-20261105.0830-dsh0.2.1-rc.5 / build322（修复 proroot 下第三方插件无法卸载）

- 插件卸载（T56）：修复 proroot 引擎下第三方插件一律卸不掉、报「No such file or directory」的问题；卸载仍按原有事务执行，失败时完整回滚。
- 实现（fa5b03ea）：proroot v1.2.8 预加载的 libc `renameat2` 忽略目录描述符、按当前目录解析名称；`plugin-transactions.py` 的保留移动改为直接发 `renameat2` 系统调用（RENAME_NOREPLACE），保留按目录描述符遍历与 NOFOLLOW 防护，不支持时仍拒绝执行；新增回归测试。
- 验证范围：发布会话在 build322 上复跑 主机测试 `test-plugin-delete-nofollow.py`、`test-plugin-transactions.py`；`*UpdateVersionPageTest` / `*RuntimeToolsRegistryAndroidTest` / `*WebLifecycleTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 322（基于 build321）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build322 更新说明](docs/releases/build322-changes.zh.md)，验证记录见 [build322 记录](docs/releases/v0.2.1-rc.5-build322-proroot-plugin-delete-20261105.md)。

## 0.2.1-rc.5-20261105.0730-dsh0.2.1-rc.5 / build321（DSH 0.2.2 升级预备：宿主与内置插件同时兼容 0.2.1-rc.5 与 0.2.2，发现数据被更高版本写过时只提示不自动修复）

- DSH 0.2.2 升级预备（S0，T51）：宿主与内置插件同时兼容当前 0.2.1-rc.5 与即将切换的 0.2.2；内置 DSH 仍是 0.2.1-rc.5，本版不换运行时。
- 实现（1503a647、a04e77ae）：内置插件 peer 版本范围放宽到 0.2.2；网页兼容层补 `URL.canParse`；LAN 契约识别 rc5 / 0.2.2 两族；`dsh-fs-search-scope` 同时适配新旧工具 API；新增 `DshVersionPolicy` 检测 DSH 数据代次：仅当数据由更高版本写过（INSTALLED_NEWER）时维护页提示并拦截自动覆盖，版本未知或缺失不再阻断启动与维护。
- 验证范围：发布会话在 build321 上复跑 `*DshVersionPolicyTest` / `*RuntimeToolsRegistryAndroidTest` / `*UpdateVersionPageTest` / `*WebLifecycleTest` / `*WebLifecycleStartProtectionTest` / `*WebLifecycleResponsivenessTest`；主机测试 `test-fs-search-scope.mjs`、`test-runtime-input-contract.py`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 321（基于 build320）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build321 更新说明](docs/releases/build321-changes.zh.md)，验证记录见 [build321 记录](docs/releases/v0.2.1-rc.5-build321-dsh022-s0-prep-20261105.md)。

## 0.2.1-rc.5-20261105.0630-dsh0.2.1-rc.5 / build320（设置页新增屏幕常亮开关（默认关闭），网页与终端页面不自动息屏）

- 设置（T53，来自 PR #4）：新增「屏幕常亮」开关，默认关闭；开启后应用内各页面（含网页预览与终端）保持亮屏，关闭即恢复系统息屏；该开关是本机设置，不随备份恢复。
- 实现（874822e9）：`ConfigStore.isKeepScreenOn` / `setKeepScreenOn`；`ModernAndroidUi` 在 Activity 创建与恢复时按设置同步 `FLAG_KEEP_SCREEN_ON`，读取失败保持系统默认；设置页新增开关与图标及十种语言文案。
- 验证范围：发布会话在 build320 上复跑 `*ConfigStoreKeepScreenOnTest` / `*ModernAndroidUiKeepScreenOnTest` / `*ConfigStoreRuntimeSettingsTest` / `*WebLifecycleTest` / `*WebLifecycleStartProtectionTest` / `*WebLifecycleResponsivenessTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 320（基于 build319）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build320 更新说明](docs/releases/build320-changes.zh.md)，验证记录见 [build320 记录](docs/releases/v0.2.1-rc.5-build320-keep-screen-on-20261105.md)。

## 0.2.1-rc.5-20261105.0530-dsh0.2.1-rc.5 / build319（剪贴板读取改为关闭 / 每次询问 / 预授权三态（默认关闭），打开外部链接按类型确认）

- 设备能力授权（T43）：剪贴板读取新增关闭 / 每次询问 / 预授权三态，默认关闭；写入剪贴板不受影响。`/app/open` 只直接放行 http(s) 与 geo，tel / mailto / market 先原生确认，其余 scheme 一律拒绝。
- 实现（c2d8341a、21822a5a）：新增纯逻辑 `ClipboardReadPolicy`、`ExternalOpenPolicy`、`TurnCapabilityGrants`；询问模式经能力票据与前台复核后原生确认；设备能力页增加三态控件；剪贴板 / 短信 / 设备授权偏好排除出可迁移偏好与备份范围（`PortablePreferenceProjection`、`BackupScope`），授权不随备份恢复。
- 验证范围：发布会话在 build319 上复跑 `*ClipboardReadPolicyTest` / `*ExternalOpenPolicyTest` / `*TurnCapabilityGrantsTest` / `*BackupScopeTest` / `*PortablePreferenceProjectionTest` / `*BridgeRoutesTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 319（基于 build318）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build319 更新说明](docs/releases/build319-changes.zh.md)，验证记录见 [build319 记录](docs/releases/v0.2.1-rc.5-build319-clipboard-open-grant-20261105.md)。

## 0.2.1-rc.5-20261105.0430-dsh0.2.1-rc.5 / build318（技能页：新增禁用开关、最近删除与彻底删除，按内容宽度分三档布局）

- 技能管理：每个技能可单独禁用（`SKILL.md` 原地改名为 `SKILL.md.disabled`，DSH 不再发现它，重新启用即恢复）；删除先移到「最近删除」，默认保留 30 天（1–365 天可调），到期在打开技能页时清理，也可随时恢复或彻底删除（T52）。
- 实现（439d3e45、2aa52f61）：新增 `SkillLibrary`（禁用 / 移入最近删除 / 恢复 / 彻底删除，经受控文件系统 NOFOLLOW 操作）与纯逻辑 `SkillLifecycle`（槽位命名、元数据、过期判定）；`SkillsFragment` 按内容宽度分窄屏 / 宽行 / 并排三档布局。
- 验证范围：发布会话在 build318 上复跑 `*SkillLibraryTest` / `*SkillLifecycleTest` / `*SkillCatalogueFixture` 相关 / `*CompactLayoutTest` / `*UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 318（基于 build317）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build318 更新说明](docs/releases/build318-changes.zh.md)，验证记录见 [build318 记录](docs/releases/v0.2.1-rc.5-build318-skill-disable-trash-20261105.md)。

## 0.2.1-rc.5-20261105.0330-dsh0.2.1-rc.5 / build317（gh 适配纳入受管运行时：包装脚本与 GitHub 转发代理随包写入，转发只放行 GitHub 域名）

- gh 适配：`gh` 包装脚本与 CONNECT 转发代理改为受管资产，由 RuntimeTools 随包写入并纳入运行时描述符；代理只监听 127.0.0.1，只放行 github.com / api.github.com / codeload.github.com / uploads.github.com 与 *.githubusercontent.com 的 443 端口（T49）。
- 实现（fed4f46b）：新增 `gh-wrapper.sh` / `gh-proxy.mjs`，同步 `managed-runtime-inputs.json`、`asset-deployment.json`、`ManagedRuntimeLayout` 与 `RuntimeTools` 的主机路径、CA 与代理环境；端口被占用时自动换临时端口，代理退出后自恢复。
- 验证范围：发布会话在 build317 上复跑 `*RuntimePackageCommandsAndroidTest`；主机测试 `tools/test-gh-managed-shim.mjs`（4 项）、`test-runtime-descriptor-inputs.py`、`test-runtime-input-contract.py`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 317（基于 build316）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build317 更新说明](docs/releases/build317-changes.zh.md)，验证记录见 [build317 记录](docs/releases/v0.2.1-rc.5-build317-gh-managed-shim-20261105.md)。

## 0.2.1-rc.5-20261105.0230-dsh0.2.1-rc.5 / build316（插件依赖核验：CAS 扫描快路径复核目录项类型与 inode；文件快照在 SQLite 初始化出错时释放许可）

- 插件依赖核验与文件快照：CAS 扫描快路径复核目录项类型与 inode，堵住同一时间刻度内的替换漏检（T48）；`DocumentRowsSnapshot` 在 SQLite 初始化抛出 Error 时也释放许可（T47）。
- CAS 扫描（c8010c9c）：`plugin-transactions.py` 的 scandir 快路径保存初始目录项类型与 inode，子项处理完后重新读取并比对，同一 ctime 刻度内把普通文件换成符号链接并复原修改时间不再漏检；仍不对每个普通文件额外 stat。
- 文件快照（T47）：`DocumentRowsSnapshot` 构造阶段捕获 Throwable，先清理再原样重抛，SQLite 原生库加载失败等 Error 不再泄漏 ACTIVE 许可；新增可注入 opener 的回归测试。
- 验证范围：发布会话在 build316 上复跑 `*DocumentRowsSnapshotTest` / `*UpdateVersionPageTest`；主机测试 `test-plugin-transactions` / `test-plugin-cas-scan-review` / `test-plugin-cas-materialize`（依赖 aarch64 `renameat2` 与 Robolectric 原生 SQLite 的既有用例以 CI 为准）、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 316（基于 build315）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build316 更新说明](docs/releases/build316-changes.zh.md)，验证记录见 [build316 记录](docs/releases/v0.2.1-rc.5-build316-cas-scandir-document-rows-20261105.md)。

## 0.2.1-rc.5-20261105.0130-dsh0.2.1-rc.5 / build315（实验性：运行时准备缓存与分阶段计时；更新试运行记录渲染卡点；阅读位置空闲保存与原图 Blob 生命周期；诊断报告恢复数据与备份行（修复挑自协作者 ym2025szz））

- 实验性：运行时准备缓存记录未命中原因并按 APK 身份缓存预载与证明摘要；更新试运行记录渲染器卡在哪一步；阅读位置改为空闲时保存、原图 Blob 随页面卸载中止；诊断报告恢复「数据与备份」只读行（修复挑自协作者 ym2025szz efe03f81 快照）。
- 运行时准备（76c267b1）：`RuntimeTools` 记录缓存未命中原因；准备戳抽为 `RuntimeAssetStamp`，一次 lstat 判定；`RuntimeApkAssets` 预载与包证明摘要按不可变 APK 身份缓存；慢准备由 `RuntimeToolsTiming` 分阶段计时。
- 试运行诊断（7aea87a9）：新增 `util/TrialRendererDiagnostic`，`RuntimeBrowserProbe` / `RuntimeTrial` / `runtime-trial-page.js` 记录页面加载、Cookie、视图可见、WebSocket 就绪帧与回执 POST 的进度，只作诊断，不改变试运行接受条件。
- 页面脚本（42e3ddd0、c2680232）：阅读位置在空闲、`scrollend`、切换会话 / 路由与页面隐藏时保存，不再逐帧序列化；原图保存直接用 Blob 并随页面卸载中止。内置移动插件只按既有补丁重建，未带入 mobile 3.2.3。
- 诊断（19784e74）：补齐 build311 剥掉的 `BackupOperationDiagnostics`，诊断报告与环境忙时的只读采样都列出备份任务、失败阶段与 errno。
- 运行时描述符随源码重算。
- 验证范围：发布会话在 build315 上复跑 `*RuntimeTools*` / `*RuntimeAsset*` / `*RuntimeApk*` / `*TrialRenderer*` / `*RuntimeBrowserProbe*` / `*BackupOperation*` / `*RuntimeStateDiagnostics*` / `*UpdateVersionPageTest`；主机测试 `test-trial-renderer-diagnostic` / `test-reading-position-checkpoint`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 315（基于 build314）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build315 更新说明](docs/releases/build315-changes.zh.md)，验证记录见 [build315 记录](docs/releases/v0.2.1-rc.5-build315-runtime-cache-trial-diagnostics-reading-backup-20261105.md)。

## 0.2.1-rc.5-20261105.0030-dsh0.2.1-rc.5 / build314（实验性：插件卡片不对临时插件显示删除、元数据损坏仍可删除；应急确认按实际包名；插件启动路径减少 import 与 I/O（修复挑自协作者 ym2025szz））

- 实验性：插件卡片不对临时插件显示删除，元数据损坏的插件仍可删除；应急原生确认与旧源恢复广播按实际安装包名；插件管理启动路径减少 import 与元数据 I/O（修复挑自协作者 ym2025szz efe03f81 快照）。
- 插件卡片（eaef0bcd）：临时插件不再显示删除，改为提示到对应 Web 会话的 Cordis 插件管理中移除；`package.json` 缺失或损坏时卡片仍显示并标注元数据错误，仍可删除；带保留注册的已禁用插件不再从列表消失。`plugin-manager.py` 新增 `plugin_card_metadata`。
- 应急确认包名（1281c88b）：`recovery-native-consent.cjs` 读取 `DSHA_BRIDGE_APPLICATION_ID`，缺省为 `io.github.sunsetrne.dsha`；`RecoveryRuntime` 注入实际包名；`BrowserOriginRestore.QUIESCE` 改用 `AppIdentity.applicationId()`。快照中把缺省改为原版包名的部分未挑入。
- 插件启动（f96f200b）：`plugin-manager.py` / `register-builtin-plugins.py` / `plugin-transactions.py` 延迟导入 tar / zip / ssl / urllib，`ensure_relative_link` 走快路径，CAS 链接扫描改用 `scandir` 类型，新增 `REGISTER_TIMING` 阶段计时；`startup-observer.cjs` 复用 require 与 YAML schema。插件锁相关改动未挑入。
- 运行时描述符随源码重算；六份补充翻译的字节钉同步更新。
- 验证范围：发布会话在 build314 上复跑 `*Plugin*` / `*Recovery*` / `*UpdateVersionPageTest`；主机测试 `test-plugin-startup-imports` / `test-plugin-runtime-links` / `test-plugin-cas-scan-review` / `test-recovery-native-consent` / `test-native-consent-transport` / `test-runtime-proof`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 314（基于 build313）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build314 更新说明](docs/releases/build314-changes.zh.md)，验证记录见 [build314 记录](docs/releases/v0.2.1-rc.5-build314-plugin-cards-recovery-identity-startup-20261105.md)。

## 0.2.1-rc.5-20261104.2330-dsh0.2.1-rc.5 / build313（实验性：WebView 渲染进程退出后回收上传、下载与麦克风；输入法、画中画与颜色探针去重（修复挑自协作者 ym2025szz））

- 实验性：WebView 渲染进程退出后回收上传、下载与麦克风，旋转期间旧页面仍有清理方；相同输入法帧、画中画恢复布局与颜色探针不再重复（修复挑自协作者 ym2025szz efe03f81 快照）。
- 渲染进程退出（6215ae02）：`WebPreviewActivity` 在 renderer 退出时清理页面状态并回收文件选择、Blob 下载与麦克风授权；旋转改用保留的 renderer 客户端，旧页面仍有清理方；`BrowserTasks.browserUnavailable`、`WebBlobDownload.close`、`BrowserOriginRestore*`、`CommunityActivity` 同步。快照中的 `UiFrameRate` 帧率改造未挑入，保留 `WebFrameRate.apply/clear`。
- 去重（f9731b0e）：`WebFullscreenUi` 相同 IME 帧不重复发送；`PictureInPictureFrame.resumeViewport` 关闭画中画时不重复布局；`web-integration/page.js` 颜色探针去重。
- 运行时描述符随源码重算（受管启动器输入）。
- 验证范围：发布会话在 build313 上复跑 `*RendererSurfaceLifecycle*` / `*WebBackgroundPageLifecycle*` / `*WebFullscreenUi*` / `*PictureInPictureFrame*` / `*WebPreview*` / `*BrowserOriginRestore*` / `*WebBlobDownload*` / `*Community*` / `*UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 313（基于 build312）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build313 更新说明](docs/releases/build313-changes.zh.md)，验证记录见 [build313 记录](docs/releases/v0.2.1-rc.5-build313-renderer-exit-ime-dedupe-20261104.md)。

## 0.2.1-rc.5-20261104.2230-dsh0.2.1-rc.5 / build312（实验性：文件接口大目录流式列出并可取消；通知进入 Web 就绪即进；启动页按钮改为重启 / 停止（修复挑自协作者 ym2025szz））

- 实验性：文件接口（DocumentsProvider）大目录流式列出并可取消；通知点击进入 Web 改为就绪即进；启动页次要按钮顺序改为重启 / 停止（修复挑自协作者 ym2025szz efe03f81 快照）。
- 文件接口（13e1fd49）：`queryChildDocuments` 改用 `DirectoryStream` 流式遍历，行先写入有界的 `DocumentRowsSnapshot`（缓存目录内 SQLite，限制并发打开数）再返回，避免大目录整体排序与内存峰值；FIFO 与拒读条目跳过不阻塞；同进程调用经 `DocumentStreams` 传递取消信号（外部 SAF 客户端仍受框架限制）；目录过多 / 暂存失败给出明确提示，并设置子目录通知 URI。
- 启动页（1a235ab6）：通知点击进入 Web 由就绪事件立即触发，每秒刷新只作兜底，页面不可见时停止并注销监听，不再叠加刷新任务；次要按钮顺序由「停止 / 重启」改为「重启 / 停止」（用户确认的 UI 调整）。
- 运行时描述符随源码重算（受管启动器输入）。
- 验证范围：发布会话在 build312 上复跑 `*DshaDocumentsProvider*` / `*DocumentRowsSnapshot*` / `*DocumentStreams*` / `*DocumentPaths*` / `*LaunchAccessAddresses*` / `*LaunchWebEntry*` / `*MainEntryLayout*` / `*UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 312（基于 build311）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build312 更新说明](docs/releases/build312-changes.zh.md)，验证记录见 [build312 记录](docs/releases/v0.2.1-rc.5-build312-documents-listing-ready-enter-20261104.md)。

## 0.2.1-rc.5-20261104.2130-dsh0.2.1-rc.5 / build311（实验性：Web 停止已证实退出后不再被旧 PID 翻回未知；环境忙时诊断改为只读宿主状态（修复挑自协作者 ym2025szz））

- 实验性：Web 停止已证实退出后不再被旧 PID 翻回未知，读不到进程时阻断维护而非误判仍在运行；环境忙时诊断改为只读宿主状态采样（修复挑自协作者 ym2025szz efe03f81 快照）。
- Web 停止证据（aa2f32f0）：`WebProcessSession.confirmStopped` 证实 guest 与启动器全部退出后，清除此前代次的旧 PID 证明，避免 PID 复用或权限变化把已完成的停止翻回 UNKNOWN；`WebProcessManager` 在环境变量不可读时只按保存的出生身份确认退出、绝不接管替换进程，扫描遇到拒读条目报 `OWNED_WEB_SCAN_UNCONFIRMED` 阻断维护；`WebSessionRecheck` 记录恢复原因与鉴权次数，新增 `LAUNCHER_UNKNOWN` 提示文案并写 `WEB_SESSION_RECHECK_STATE` 诊断。
- 诊断（112dec64）：Web 会话未定、启动或停止中、运行任务忙、用户停止未确认或维护未完成时，普通诊断不再启动 guest 或直接失败，改为只读采样 `util/RuntimeStateDiagnostics`（固定字段、白名单取值，不能授权重启或清理记录）；诊断修复入口不变。快照中的备份操作诊断行依赖尚未挑入的 `BackupOperationDiagnostics`，本版未含。
- 运行时描述符随 Web 生命周期源码重算（受管启动器输入）。
- 验证范围：发布会话在 build311 上复跑 `*WebSessionExitEvidence*` / `*WebSessionRecheck*` / `*WebSessionRecovery*` / `*WebSessionRestoreText*` / `*WebSessionStorage*` / `*WebProcessSession*` / `*WebProcess*` / `*WebStop*` / `*RuntimeStateDiagnostics*` / `*UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 311（基于 build310）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build311 更新说明](docs/releases/build311-changes.zh.md)，验证记录见 [build311 记录](docs/releases/v0.2.1-rc.5-build311-web-stop-evidence-host-diagnostics-20261104.md)。

## 0.2.1-rc.5-20261104.2030-dsh0.2.1-rc.5 / build310（实验性：后台进程退出无法确认时保留环境保护；配置快照备用目录只读；桥接 APK 安装源沿用读取入口的域边界（修复挑自协作者 ym2025szz））

- 实验性：后台进程退出无法确认时保留环境保护并记录诊断；配置快照的备用目录只读；桥接 APK 安装源沿用读取入口的域边界（修复挑自协作者 ym2025szz efe03f81 快照）。
- 环境保护（c76fa239）：新增 `util/RetainedProcessObserver`，`RuntimeTasks.waitForRetainedExit` 改用它轮询保留进程；`exitValue` 之外的探测失败报 UNKNOWN，保护继续保持、任务说明改为 `EXIT_PROBE_UNAVAILABLE`，并只记一次 `RUNTIME_EXIT_OBSERVATION` 诊断，不提前释放。`EnvironmentTaskGate` 同步。
- 配置快照（54ee5c2f）：`ConfigurationSnapshots.writableCatalog` 只认主目录 `catalog.json`；备用 `catalog.json.previous` 仍可读来恢复，但不再作为写入或整理的依据。
- 桥接安装（70c9d262）：`/app/install` 改用 `BridgePathPolicy.deniedReadRaw` / `resolveReadFile`，canonical 只解析一次并绑定到后续快照；新增 `BridgeApkInstall.sourceAllowed`，共享存储只认当前用户根，guest 链接不能跨到共享或宿主域。测试中的示例路径改为 Mod 包名。
- 运行时描述符随 `RuntimeTasks` 与 `HttpShellService` 源码重算（受管启动器输入）。
- 验证范围：发布会话在 build310 上复跑 `*RetainedProcessObserver*` / `*EnvironmentRetainedExit*` / `*EnvironmentTaskGate*` / `*ConfigurationSnapshots*` / `*BridgeApkInstall*` / `*BridgePathPolicy*` / `*RuntimeTasks*` / `*UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 310（基于 build309）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build310 更新说明](docs/releases/build310-changes.zh.md)，验证记录见 [build310 记录](docs/releases/v0.2.1-rc.5-build310-retained-exit-install-source-20261104.md)。

## 0.2.1-rc.5-20261104.1930-dsh0.2.1-rc.5 / build309（实验性：发布身份可切换（默认 Mod 身份，上游身份流程仅在协作者私有仓库运行）；外接设备确认时固定目标设备、只读调试命令校验参数；备份读取非阻塞打开并修正 arm64 目录标志，失败时记录阶段（部分修复挑自协作者 ym2025szz））

- 实验性：发布身份改为可切换（默认 Mod 身份，另有仅在协作者私有仓库运行的上游身份流程）；外接设备确认时固定目标设备、只读调试命令校验参数；备份读取改为非阻塞打开并修正 arm64 目录标志，失败时记录阶段（部分修复挑自协作者 ym2025szz）。
- 发布身份（c12bf11f，SunsetRNE，为协作者 ym2025szz 的上游发布准备）：新增 `ci/release-identity.mod.json` 与 `ci/release-identity.upstream.json`，`app/build.gradle` 加 `-Pdsha.identity=mod|upstream`（默认 mod，未知值配置失败、不回退），包名、显示名、测试后缀、默认 key alias 与 `verifyPublishCertificate` 核对的证书都从身份文件读取，versionCode / versionName 两种身份共用；新增只读 `printReleaseIdentity`。`ci/release-identity.json` 保留为 Mod 兼容入口，`tools/release-identity.py --check` 强制一致并拒绝两套身份共享包名、证书、alias、测试后缀、tag 前缀或仓库。`release-production.yml` 显式传 mod；新增 `release-upstream.yml` 只在 `ym2025szz/DSHA-prerelase` 私有仓库 `codex/prerelease-190` 运行（tag `dsha-build…`），在本仓库 bind 即跳过。本仓库正式包仍为 `io.github.sunsetrne.dsha` 与 Mod 证书，不保存、不引用 E7E3 密钥。
- 外接设备（挑自 ym2025szz efe03f81，9956ac80）：每个外接桥请求在确认框出现前捕获目标序列号、产品、指纹与绑定 / 桥 / Web 代次，换绑、忘记设备、重开外接、请求失效或保存失败后未派发的步骤以 `EXTERNAL_AUTHORITY_CHANGED_BEFORE_DISPATCH` 拒绝，RootShell 写给 root helper 前再核对一次；确认框显示固定后的目标。`wm` 只接受 size / density 查询，`logcat` 只接受 -d/-g/-s、-t N、已知缓冲区与格式及标签过滤；设备授权页外接开关保存失败时提示并回滚。
- 备份（挑自 ym2025szz efe03f81，cd39ecc4 / d726ecb3）：`AndroidBackupFileSystem` 的 read / currentSnapshot 以 O_NONBLOCK 打开，stat 后被换成 FIFO 时不再等待写端，fstat 身份核验立即拒绝；list / syncDirectory 加 O_DIRECTORY，常量按 NDK aarch64 头文件取 040000（原 0x10000 在 arm64 上是 O_DIRECT）。新增 `BackupFailureDiagnostic`，按阶段写任务目录 failure.json（只含固定阶段、异常类型 / errno 与项目栈位置，不含消息和路径）；`BackupTemporaryFiles` 让临时文件清理失败作为 suppressed 挂到原始错误上，不再覆盖真实原因。
- 运行时描述符随外接设备与备份源码重算（受管启动器输入）；双身份文档与 build308 当前身份表合并，保留共用版本行。
- 验证范围：发布会话在 build309 上复跑 `*External*` / `*AndroidBackupFileSystemRead*` / `*BackupFailureDiagnostic*` / `*BackupTemporaryFiles*` / `*HostSnapshot*` / `*OneClickBackup*` / `*IslandText*` / `*DeviceShellPolicy*` / `*UpdateVersionPageTest`、`tools/test-release-identity.py`、`tools/test-ci-workflows.py`、`tools/test-backup-read-nonblock.py`、`printReleaseIdentity`（mod 与 upstream）、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 309（基于 build308）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build309 更新说明](docs/releases/build309-changes.zh.md)，验证记录见 [build309 记录](docs/releases/v0.2.1-rc.5-build309-dual-identity-external-backup-20261104.md)。

## 0.2.1-rc.5-20261104.1830-dsh0.2.1-rc.5 / build308（实验性：上岛通知修复多选提交无反应与动作按钮超过系统上限（贡献者 Ralsei1337）；Root 读取守卫补上 grep -f/-nfFILE 模式文件路径校验（上游 #215）；诊断页早期失败时结果到达后正确启用复制（上游 #213）；启动目标插件面板窄屏竖排时去掉标签下大段空白，分段控件改为撑满整行（实验性，未真机））

- 实验性：上岛通知修复多选提交无反应与动作按钮超过系统上限（贡献者 Ralsei1337）；Root 读取守卫补上 grep -f/-nfFILE 模式文件路径校验（上游 #215）；诊断页早期失败时结果到达后正确启用复制（上游 #213）；启动目标插件面板窄屏竖排时去掉标签下大段空白，分段控件改为撑满整行（实验性，未真机）。
- 上岛（外部贡献 e4019f56，基于 build245，挑入 build307 之上无冲突）：多选「提交」补带任务标识，接收器能读到已选集合并回传；等待作答通知按系统 3 个动作槽位最多显示 2 个选项，其余由点按通知进入对话承接；运行中切换计时器立即重绘；新模型显式 colorized=false；释放已结束任务的多选集合；多选切换按选项序号区分。发布会话按当前 main 调整：36.0 旧模型 ROM 上 `canPostPromotedNotifications()` 为 false 时设置页仍显示「查不到」而不是「已关闭」；动作槽位规划抽为 `IslandText.quickSlots` 并补单测。
- Root 读取守卫（上游 DSH-APP/DSHA#215，lzh2034855417-beep）：`grep -f FILE`、`-nfFILE` 等组合短选项中的模式文件此前被当作选项跳过，现与输入文件一并做短信库路径与规范路径核验，原生与 guest 两侧同改；运行时描述符随 `device-shell-policy.py` 重算。诊断页（上游 #213，按 build252 后的页面等效移植）：早期失败不经忙碌态时，结果卡片到达后重算反馈按钮。上游 #200 的压缩重入问题已在 build194 等效修复，未重复挑入。
- 启动目标插件（`dsh-launch-target`，d80e2bd9，基于 build307）：容器宽度 ≤420px 竖排时标签的 180px flex 基准落到高度方向造成大段空白，改为按内容取高；分段控件显式撑满整行；宽屏横排不变。实验性，未真机。
- 身份：DSHA 修订号由 0.2.1-rc.3 同步为 0.2.1-rc.5（versionName 前缀与 `ext.dshaApkNameVersion`，APK 下载名随之为 `dsha-0.2.1-rc.5.apk`）；修订号由 35d69fa8（build193）引入，覆盖升级按 versionCode 判定，恢复配方与数据兼容按内置 DSH 版本选择，不受影响。
- 验证范围：发布会话在 build308 上复跑 `*IslandText*` / `*Island*` / `*DeviceShellPolicy*` / `*UpdateVersionPageTest`、`tools/test-device-shell-policy.py`、`tools/test-diagnostic-feedback.py`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 308（基于 build307）；DSHA 修订号由 0.2.1-rc.3 同步为 0.2.1-rc.5，与内置 DSH 0.2.1-rc.5 一致。
- 十语种更新说明见 [build308 更新说明](docs/releases/build308-changes.zh.md)，验证记录见 [build308 记录](docs/releases/v0.2.1-rc.5-build308-island-grep-diagnostics-20261104.md)。

## 0.2.1-rc.3-20261104.0130-dsh0.2.1-rc.5 / build307（实验性：设备能力授权页「设备执行通道」「屏幕操作」改为紧凑两行条目）

- 实验性：设备能力授权页的「设备执行通道」与「屏幕操作」两个板块改为紧凑两行条目，减少纵向占用；授权逻辑与开关行为不变（分支 `ui/compact-device-sections`，90a0f4b4，基于 build304，挑入 build306 之上无冲突；用户已在真机竖屏与横屏确认）。
- 验证范围：发布会话在 build307 上复跑 `*DeviceGrantsLayoutTest` / `*UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 307（基于 build306）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build307 更新说明](docs/releases/build307-changes.zh.md)，验证记录见 [build307 记录](docs/releases/v0.2.1-rc.3-build307-compact-device-sections-20261104.md)。

## 0.2.1-rc.3-20261104.0100-dsh0.2.1-rc.5 / build306（实验性：手机竖屏底部导航只显示图标，胶囊压缩到 52dp）

- 实验性：手机竖屏底部胶囊导航只显示图标，高度由 64dp 压缩到 52dp，页底让位随之收紧（90→78dp、74→62dp）；菜单标题保留，无障碍朗读与长按提示不变；横屏保留带文字胶囊，sw600dp 平板左栏不受影响（分支 `ui/compact-nav-portrait`，6db8c400，基于 build305；用户已在平板真机验收）。
- 验证范围：发布会话在 build306 上复跑 `*ShellNavCompactPortraitTest` / `*UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 306（基于 build305）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build306 更新说明](docs/releases/build306-changes.zh.md)，验证记录见 [build306 记录](docs/releases/v0.2.1-rc.3-build306-compact-nav-portrait-20261104.md)。

## 0.2.1-rc.3-20261104.0030-dsh0.2.1-rc.5 / build305（实验性：主 tab 切换按方向横移并淡入淡出）

- 实验性：启动/插件/设置三个主 tab 互切时按底栏顺序横向滑入并淡入淡出，帮助感知页面方位；子页返回、门禁页恢复、重复点选仍用原页面动效，系统关闭动画时一并关闭（分支 `ui/tab-switch-motion`，4f657a02，基于 build303；测试包经用户真机确认观感）。
- 验证范围：发布会话在 build305 上复跑 `*UpdateVersionPageTest` / `*MainActivity*` / `*UiMotion*`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 305（基于 build304）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build305 更新说明](docs/releases/build305-changes.zh.md)，验证记录见 [build305 记录](docs/releases/v0.2.1-rc.3-build305-tab-switch-motion-20261104.md)。

## 0.2.1-rc.3-20261103.2330-dsh0.2.1-rc.5 / build304（实验性：ADB/root 通道进程治理与备份凭据排除；设备命令与虚拟屏共用通道顺序；Web 页不再成第二个窗口；glob 跳过嵌套工作树）

- 实验性：T38 对 root 通道增加并发上限 3、超时与 App 退出自动回收，避免 helper 进程堆积；ADB 探活不再被 Root/Shizuku 通道冒充；覆盖安装后旧 helper 退出并自动记录 root-busy、package-replaced 快照（分支 `fix/adb-channel-pileup`，37812c45、a137fb74，基于 build303；`.test` 真机已核对）。
- 安全修复：T42 §7a 让备份与导出排除 `.dsh/adbkeys`，恢复 build303 及以前的旧归档时不落地其中的配对密钥；本机环境重建保留当前设备配对（分支 `fix/backup-exclude-adbkeys`，74632bdb，基于 build303）。
- 实验性：T41 设备命令与虚拟屏共用一份原生选通道顺序（Root → Shizuku → ADB；Android 17 上 ADB 已验证连接时先于 Shizuku），设备命令先按严格判据、再按已授权判据各选一次，执行失败不换通道重放；ADB 路径新增局域网授权前提（分支 `fix/channel-order-align`，6066f5f3，基于 build303）。
- 修复：T46 Web 页并入 Main 同一任务，桌面/自由窗口模式下不再出现两个窗口；已有存活宿主时转交请求而不另建（分支 `fix/single-window-task`，d642b1f9，基于 build303；用户真机确认不再复现，画中画展开未单独验收）。
- 实验性：T45 新增签名内置临时修复插件 `dsh-fs-search-scope`，DSH 的 glob 默认跳过 `.agents/worktrees` 与已登记的链接工作树，避免多工作树仓库检索膨胀；停用插件即恢复上游原样。为签名名单重建 `dsh-runtime.bin`，固定构建输入切到 `fixed-inputs-20261103c`（分支 `feat/fs-search-override`，b3f77cdd、bd0f5895、7dfc3aac，基于 build303；未真机）。
- 文档：补充 T42 ADB 通道原生化设计稿的 Q1–Q9 决定与 E1–E4 只读取证结论，并加入文档总索引；原生 ADB 客户端实现仍未开始。
- 验证范围：发布会话在 build304 上复跑 `*RootHelper*` / `*DeviceChannel*` / `AdbResultTest` / `ScreenCapabilityGateTest` / `*BrowserTask*` / `*BuiltinPlugin*` / `DataRootPolicyTest` / `NativeEnvironmentRebuildTest` / `tools/test-backup-engine.py` / `tools/test-fs-search-scope.mjs`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 304（基于 build303）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build304 更新说明](docs/releases/build304-changes.zh.md)，验证记录见 [build304 记录](docs/releases/v0.2.1-rc.3-build304-channels-window-search-20261103.md)。

## 0.2.1-rc.3-20261103.2130-dsh0.2.1-rc.5 / build303（实验性：弹层按窗口宽、高与内容自适应；收录 ADB 通道原生化设计稿，补充识屏守卫与设备命令确认设计稿）

- 实验性：`CardSheet` / `CardPage` / `DshaDialogBuilder` 弹层按窗口宽、高与内容档自适应，几何计算抽到纯逻辑 `util/SheetGeometry`（分支 `feat/sheet-geometry` 8a207c13，基于 build296；**未上真机**）。
- 文档：T42 ADB 通道原生化设计稿（`docs/design/adb-native-20261103/`，c68cbb5a、b8295fc5、02335afa）；T39 识屏守卫设计稿补权限审计与 Q8 定案（424c933f、4d0127aa）；T40 设备命令确认设计稿补 T42 依赖、T43 拆分与 §4.5 强确认（6da09a79）。均未实现。
- 验证范围：发布会话在 build303 上复跑 `*SheetGeometryTest` / `*CardSheetGeometryTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 303（基于 build302）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build303 更新说明](docs/releases/build303-changes.zh.md)，验证记录见 [build303 记录](docs/releases/v0.2.1-rc.3-build303-sheet-geometry-20261103.md)。

## 0.2.1-rc.3-20261103.2100-dsh0.2.1-rc.5 / build302（实验性：切网后回收失效的外网连接，模型请求不再卡满 300 秒；收录识屏守卫与设备命令确认设计稿）

- 实验性：网络切换（Wi-Fi / 移动数据）或切后台后，按网络代次回收失效的外网连接，模型请求不再卡满 300 秒；宿主发布网络代次 `NetworkEpochPublisher`，容器内 `net-epoch.cjs` 预加载后丢弃旧代次连接（分支 `fix/stream-stall-network` 0607535a、b457c17e，T37，基于 build299；**未上真机切网验证**，排查见 `docs/network-switch-connection-recycle.md` 与日志 `[dsha-net]`）。
- 文档：收录 T39 识屏单一守卫设计稿（`docs/design/screen-guard-20261103/`，1012cd6c、4a8e9f27）与 T40 原生设备命令确认设计稿（`docs/design/device-exec-confirm-20261103/`，e6bada84），均已由用户确认，尚未实现。
- 验证范围：发布会话在 build302 上复跑 `*NetworkEpochPolicyTest` / `*RuntimeTools*` / `*ManagedRuntimeLayout*` / `RuntimeToolsRegistryAndroidTest` / `UpdateVersionPageTest` / `UiStateTextTest`、`tools/test-net-epoch.mjs`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 302（基于 build301）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build302 更新说明](docs/releases/build302-changes.zh.md)，验证记录见 [build302 记录](docs/releases/v0.2.1-rc.3-build302-network-recycle-20261103.md)。

## 0.2.1-rc.3-20261103.2030-dsh0.2.1-rc.5 / build301（虚拟屏观察失败细分并给出下一步，修复助手旋转后代次失步；设备/屏幕提示词去重）

- 虚拟屏助手侧观察失败细分为空屏、首帧等待、画面停滞并给出下一步；待清理 Core 下 see/tree 不再互相指向重看；助手旋转虚拟屏不再判成「目标已变」导致管理器与 Core 代次失步，旋转回复写明显示已重建、原应用需重新 launch（分支 `fix/vscreen-error-detail` 4a17f5c2..b2f033f7，T35，基于 build299；真机四种情况已由原会话核对）。
- 设备 / 屏幕常驻提示词去重为中文单份，写明虚拟屏不需 android_control begin、无障碍关闭时像素路径仍可用，97 条约束由测试逐条锁定（分支 `docs/screen-prompt-consolidation` 2378a0e3，T36；真机三项已由原会话核对）。
- 验证范围：发布会话在 build301 上复跑 `com.deepseekharness.app.vscreen.*` / `*VirtualScreenObservationHintTest` / `*HttpShellService*` / `*DshaAccessibility*` / `RuntimeToolsRegistryAndroidTest` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 301（基于 build300）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build301 更新说明](docs/releases/build301-changes.zh.md)，验证记录见 [build301 记录](docs/releases/v0.2.1-rc.3-build301-vscreen-hints-prompts-20261103.md)。

## 0.2.1-rc.3-20261103.2000-dsh0.2.1-rc.5 / build300（新增临时修复插件 dsh-fs-read-hint：未读即改被拒时提示已读的同名路径）

- 新增签名内置临时修复插件 `dsh-fs-read-hint`：助手未读即改被 `fs-observation-policy` 拒绝时，在报错里追加本会话已读过的同名路径，帮助改用正确路径；`dsh-runtime.bin` 按签名名单重建（只有 `dsh-plugin-manager/lib/index.js` 一个成员变化），应急锁同步到同一字节保持去重，固定构建输入切到 `fixed-inputs-20261103b`；发布会话补登记 `tools/test-fs-read-hint.mjs` 到 host-tests 清单（分支 `fix/fs-read-hint-rebuild` 71be0f94..d5f96f58，基于 build299；未上真机）。
- 验证范围：发布会话在 build300 上复跑 `RuntimeToolsRegistryAndroidTest` / `*PluginManager*` / `*BuiltinPlugin*` / `*RecoveryRuntime*` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 300（基于 build299）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build300 更新说明](docs/releases/build300-changes.zh.md)，验证记录见 [build300 记录](docs/releases/v0.2.1-rc.3-build300-fs-read-hint-20261103.md)。

## 0.2.1-rc.3-20261103.1630-dsh0.2.1-rc.5 / build299（虚拟屏 MCP 插件声明版本协商，启动时少起一次 server.cjs）

- 虚拟屏 MCP 插件声明 `versionNegotiation: legacy`，`dsh-mcp-client` 运行时补丁增加可选版本协商（默认仍为 auto），启动时 `server.cjs` 从 2 次降为 1 次；`dsh-runtime.bin` 按补丁重建（只有 `mcp-client/index.js` 一个成员变化），应急锁同步到同一字节保持去重，固定构建输入切到 `fixed-inputs-20261103`（分支 `fix/vscreen-mcp-negotiation` 8d2c76b3..70fd2ac4，T31，基于 build297；未上真机）。
- 验证范围：发布会话在 build299 上复跑 `RuntimeToolsRegistryAndroidTest` / `com.deepseekharness.app.vscreen.*` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 299（基于 build298）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build299 更新说明](docs/releases/build299-changes.zh.md)，验证记录见 [build299 记录](docs/releases/v0.2.1-rc.3-build299-vscreen-mcp-negotiation-20261103.md)。

## 0.2.1-rc.3-20261103.1600-dsh0.2.1-rc.5 / build298（文档：proot 开销调查、UI 模板基准与协作约定）

- 仅文档，应用行为不变：新增 proot 本体开销调查（`docs/design/proot-overhead-20261103`，T34：随包 libproot.so 核实为 termux/proot v5.1.107.91，首选 readlink 入口直答方案，实施另行决定）；新增 UI 模板基准（`docs/design/ui-template-baseline-20261103`，四档位整套导出、适配度审计与弹层自适应提案）；AGENTS 增加 DSH 文件工具先 read 再 edit 的约定（分支 `research/proot-overhead` 34981e28、`design/ui-template-baseline` 67724f5e、`docs/agents-fs-read-rule` 3a62dd40）。
- 验证范围：发布会话在 build298 上复跑 `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 298（基于 build297）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build298 更新说明](docs/releases/build298-changes.zh.md)，验证记录见 [build298 记录](docs/releases/v0.2.1-rc.3-build298-docs-20261103.md)。

## 0.2.1-rc.3-20261103.1530-dsh0.2.1-rc.5 / build297（实验性：启动日志与诊断写明 proot 是否带 seccomp 过滤）

- **实验性。** 启动日志「本次实际运行方式」改为 proot（seccomp 过滤）/ proot（兼容模式：无 seccomp）/ proroot，seccomp 状态由 `ContainerRuntime.Proot.seccompDisabled()` 读取，与 applyEnv 同一配置快照；诊断报告「所选运行方式」增加 `runtimeDetail=proroot|proot-seccomp|proot-no-seccomp`；新增 `util/RuntimeModeLabel` 及单测，不改运行方式选择逻辑（分支 `feat/proot-mode-log` 00a59814、f1b93538，T33，基于 build296；未上真机）。
- 验证范围：发布会话在 build297 上复跑 `RuntimeModeLabelTest` / `ContainerRuntimeEnvironmentTest` / `DiagnosticReportTest` / `StartupDiagnosticsTest` / `WebLifecycle*` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 297（基于 build296）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build297 更新说明](docs/releases/build297-changes.zh.md)，验证记录见 [build297 记录](docs/releases/v0.2.1-rc.3-build297-proot-mode-log-20261103.md)。

## 0.2.1-rc.3-20261103.1500-dsh0.2.1-rc.5 / build296（实验性：识屏模式收束，助手侧与操作者面板分开）

- **实验性。** 识屏模式收束：实体识屏与虚拟识屏互斥，桥与 MCP 按识屏模式放行（模式不符返回 `SCREEN_MODE_*`），能力撤销时同步降级；授权页新增识屏模式行。识屏模式只约束助手：虚拟识屏页的创建、旋转、输入、关闭只看设备能力与生命周期，不读也不改识屏模式，页内显示助手能否使用本屏并给出授权页入口；ADR0003 决定六相应改写（分支 `feat/screen-mode-gate-b` 4dee3c7f、9fa148f7、5860efb2，T29，原基于 build294；用户已用测试包真机核对：虚拟识屏下 Shizuku 虚拟屏 READY，启动应用后 see 与 tree 正常）。
- 验证范围：发布会话在 build296 上复跑 `com.deepseekharness.app.vscreen.*` / `ScreenMode*` / `ScreenCapabilityGate*` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 296（基于 build295）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build296 更新说明](docs/releases/build296-changes.zh.md)，验证记录见 [build296 记录](docs/releases/v0.2.1-rc.3-build296-screen-mode-gate-20261103.md)。

## 0.2.1-rc.3-20261103.1430-dsh0.2.1-rc.5 / build295（覆盖安装后 Web 启动不再卡在确认旧启动器退出）

- 修复覆盖安装后 Web 启动卡在「已知 Web 启动器未能确认退出」（`retire-prior-launchers`），只能杀进程才恢复：停止证据读取失败不再整进程锁存，之后重读成功即按原记录判定；记录里的启动器 PID 已被其它 UID 复用时，判定原进程已退出（只用于退出证明，不据此发送任何信号）（分支 `fix/retire-prior-launchers` 9707bbcb，T32，477a5781 发现；未真机复现）。
- 验证范围：发布会话在 build295 上复跑 `RecoveredWebStopBarrierTest` / `WebSessionKernelEvidenceTest` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 295（基于 build294）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build295 更新说明](docs/releases/build295-changes.zh.md)，验证记录见 [build295 记录](docs/releases/v0.2.1-rc.3-build295-retire-prior-launchers-20261103.md)。

## 0.2.1-rc.3-20261103.0830-dsh0.2.1-rc.5 / build294（实验性：虚拟识屏页按状态重做）

- **实验性。** 虚拟识屏页按状态重做：未开启 / 创建中 / 运行中 / 待清理四态各有独立布局，横屏改为左右布局、操作贴底；旋转后自动重新打开原应用；接回系统悬浮预览；启动结果未知时引导查看虚拟屏启动记录，不再重复文案；首帧等待不报错，节点面板不留空白（分支 `ui/vscreen-page` cd9f414b..2f1c0c7d 共 6 个提交，原基于 build287，用户已真机核对；逻辑层遗留问题记录在 T28 交接，不在本版）。
- 验证范围：发布会话在 build294 上复跑 `com.deepseekharness.app.vscreen.*` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 294（基于 build293）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build294 更新说明](docs/releases/build294-changes.zh.md)，验证记录见 [build294 记录](docs/releases/v0.2.1-rc.3-build294-vscreen-page-20261103.md)。

## 0.2.1-rc.3-20261103.0800-dsh0.2.1-rc.5 / build293（启动日志框滑动归属一次性锁定）

- 启动日志框与外层页面的嵌套滑动改为越过 touchSlop 后按累计位移一次性锁定归属：旧实现逐帧按单帧位移判断，贴边时一帧抖动就放开外层，外层随即抢走手势，概览日志难以滑动（新增 `util/NestedScrollArbiter` 与单测；分支 `fix/launch-log-nested-scroll` 37725ccb，原基于 build284，用户已真机确认手感）。
- 验证范围：发布会话在 build293 上复跑 `NestedScrollArbiterTest` / `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 293（基于 build292）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build293 更新说明](docs/releases/build293-changes.zh.md)，验证记录见 [build293 记录](docs/releases/v0.2.1-rc.3-build293-launch-log-nested-scroll-20261103.md)。

## 0.2.1-rc.3-20261103.0730-dsh0.2.1-rc.5 / build292（上岛页「支持的设备」避开悬浮胶囊）

- 上岛页「支持的设备」不再被手机悬浮胶囊遮挡：页底余量改用 `shell_page_bottom`（手机 90dp、平板 30dp）；入口从页底孤立按钮移入「当前状态」卡末行；说明弹窗改用 CardSheet，手机贴底并抬到胶囊上方，宽屏居中限宽。文案未改（分支 `ui/island-devices-inset` 7965b7ac，原基于 build282，用户已在测试包真机确认平板横屏页面与弹窗）。
- 验证范围：发布会话在 build292 上复跑 `UpdateVersionPageTest` / `UiStateTextTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 292（基于 build291）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build292 更新说明](docs/releases/build292-changes.zh.md)，验证记录见 [build292 记录](docs/releases/v0.2.1-rc.3-build292-island-devices-inset-20261103.md)。

## 0.2.1-rc.3-20261103.0700-dsh0.2.1-rc.5 / build291（一键备份位置证明改在停写之后记录）

- 修复一键备份每次以 `DATA_LOCATION_CHANGED` 失败（一加 12 报告）：位置证明原先在停 Web 前记录，停 Web 会删除或原子重写 `.dsh` 顶层机器状态文件（`.bridge_port` / `.bridge_status`），导致同一链路必然失败。现在手动、自动与救援三条路径都在停写之后再定位；整项排除的机器状态不做位置核对，用户数据在定位后被替换仍失败关闭（分支 `fix/backup-location-proof`，原会话 session-42f01268 未提交，主会话按用户要求提交；新增 `NativeDataLocationsProofTest`）。
- 宿主回归修正两处过期断言：`test-plugin-management-patch-chain.py` 的前序模块与 V1 模块摘要按内置 DSH 0.2.1-rc.5 重算（配方夹具摘要不变）；`test-dsh-builtins.mjs` 实体屏工具数由 10 改为 11（新增 `android_read_image`）。两项均不在发布门禁内，本机复跑通过。
- 验证范围：发布会话在 build291 上复跑 `com.deepseekharness.app.backup.*` / `UpdateVersionPageTest`、宿主回归 `test-plugin-management-patch-chain.py` / `test-dsh-builtins.mjs`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 291（基于 build290）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build291 更新说明](docs/releases/build291-changes.zh.md)，验证记录见 [build291 记录](docs/releases/v0.2.1-rc.3-build291-backup-location-proof-20261103.md)。

## 0.2.1-rc.3-20261103.0630-dsh0.2.1-rc.5 / build290（实验性：网络健康检测一期）

- **实验性。** 网络健康检测一期（只检测不修复）：诊断页新增网络健康面板，检测系统拦截本 App 网络、Web 在跑但前台服务缺失、容器 DNS 过期、有网络但出不去、进程曾被冻结五类问题，只记录并提示，不重启 Web、不改系统设置、不重放请求（分支 `design/network-health-20261102`，原会话未提交，主会话按用户要求提交为 4cd38fde；原基于 build274，无冲突）。
- 验证范围：发布会话在 build290 上复跑 `NetworkHealthPolicyTest` / `UiStateTextTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 290（基于 build289）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build290 更新说明](docs/releases/build290-changes.zh.md)，验证记录见 [build290 记录](docs/releases/v0.2.1-rc.3-build290-network-health-20261103.md)。

## 0.2.1-rc.3-20261103.0600-dsh0.2.1-rc.5 / build289（阶段分段进度条动画）

- 维护页与部署进度的阶段分段条加动画：完成段从左向右填充、当前段流光、结束时颜色淡变；系统关闭动画时画静态终态（分支 `ui/maintenance-progress-motion` 613e7b96，原基于 build274，用户已在测试包真机核对节奏与亮度）。与 build276 的当前段流光（`DeployRunSegment`）同改 `OnboardingKit.segmentBar`，按用户选择合并：画法用 `StageSegmentBar`，“正在执行”判定沿用 build276 的 `OnboardingKit.running`，并承接窗口不可见时停止重绘与 RTL 镜像；删除 `DeployRunSegment`。
- 验证范围：发布会话在 build289 上复跑 `DeployCapsuleTest` / `StageSegmentMotionTest` / `OnboardingActivityTest` / `OnboardingMotionPlanTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 289（基于 build288）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build289 更新说明](docs/releases/build289-changes.zh.md)，验证记录见 [build289 记录](docs/releases/v0.2.1-rc.3-build289-stage-segment-motion-20261103.md)。

## 0.2.1-rc.3-20261103.0530-dsh0.2.1-rc.5 / build288（实验性：Web 设置导航新增「启动目标」面板）

- **实验性。** Web 设置导航新增独立的「启动目标」面板（settings.section），插件只负责传递设置；禁用 `dsh-launch-target` 插件不再改变启动目标（分支 `fix/launch-target-settings` 99716b5b，原基于 build277）。运行配置页说明文字与 build287 同改：合并为「桌面图标或常驻通知…系统回收后回到 Web…设置 → 启动目标」；运行时描述符重算。
- 验证范围：发布会话在 build288 上复跑 宿主 `test-launch-target-client.mjs`、`UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 288（基于 build287）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build288 更新说明](docs/releases/build288-changes.zh.md)，验证记录见 [build288 记录](docs/releases/v0.2.1-rc.3-build288-launch-target-settings-20261103.md)。

## 0.2.1-rc.3-20261103.0500-dsh0.2.1-rc.5 / build287（实验性：直进 Web 修复闪屏并补齐入口）

- **实验性。** 直进 Web 不再闪出主界面：Web 打开后加载页转为主界面上的静态遮罩；并补齐通知、最近任务与系统回收后恢复入口——通知经透明中转页回到存活的原网页或直接打开已就绪的 Web，偏好为主界面时行为不变（分支 `fix/direct-web-entries` ae4e9a15 + a9e34d60，原基于 build278；`BootScreen` 与 build283 在同一处各新增方法，两边保留；运行时描述符重算）。
- 验证范围：发布会话在 build287 上复跑 `DirectWebEntryTest` / `EntryTargetTest` / `WebPageBootOverlayTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 287（基于 build286）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build287 更新说明](docs/releases/build287-changes.zh.md)，验证记录见 [build287 记录](docs/releases/v0.2.1-rc.3-build287-direct-web-entries-20261103.md)。

## 0.2.1-rc.3-20261103.0430-dsh0.2.1-rc.5 / build286（实验性：桥新增 /app/install）

- **实验性。** 设备桥新增 `/app/install`：容器推送单个 APK，DSHA 在前台或有后台启动豁免时直接拉起系统安装器，否则发通知由用户点按打开；未允许「安装未知应用」时引导到设置页（分支 `feat/bridge-apk-install` 1d574da4，原基于 build274）。合入时把三处 `UiText.choose` 参数拼接改为整句选择与 `%s` 模板（4fd3b598），否则 `check-ui-i18n` 契约失败；文字不变。
- 验证范围：发布会话在 build286 上复跑 `BridgeApkInstallTest` / `BridgeRoutesTest` / `NotificationIdsTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 286（基于 build285）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build286 更新说明](docs/releases/build286-changes.zh.md)，验证记录见 [build286 记录](docs/releases/v0.2.1-rc.3-build286-bridge-apk-install-20261103.md)。

## 0.2.1-rc.3-20261103.0400-dsh0.2.1-rc.5 / build285（实验性：ⓘ 说明弹窗按窗口形态适配）

- **实验性 UI 渲染更新，待统一视觉验收。** ⓘ 说明弹窗按窗口形态适配：平板贴 ⓘ 显示气泡，手机横屏用紧凑弹窗，竖屏用底部说明面板（T23，分支 `design/info-dialog-20261102` 5eb7074d + 831e27a2，基于 build272，无冲突）。
- 验证范围：发布会话在 build285 上复跑 `InfoSurfacePolicyTest` / `TerminalInputContractTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 285（基于 build284）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build285 更新说明](docs/releases/build285-changes.zh.md)，验证记录见 [build285 记录](docs/releases/v0.2.1-rc.3-build285-info-dialog-20261103.md)。

## 0.2.1-rc.3-20261103.0330-dsh0.2.1-rc.5 / build284（实验性：对话页错误面板重绘）

- **实验性 UI 渲染更新，待统一视觉验收。** 对话页错误面板重绘：崩溃结论与逐层状态，服务结束后面板停留并定期复核，重启就绪即接回（分支 `design/web-crash-20261102` 809a99ba，原基于 build272）。与 build283 同改 `WebPreviewActivity.showError`：保留本版面板并在其前收起 build283 的原生加载页；i18n 条目级三方合并、语言字节钉重算。
- 验证范围：发布会话在 build284 上复跑 `WebCrashReportTest` / `WebCrashPanelTest` / `WebPageBootOverlayTest` / `WebBackgroundPageLifecycleTest` / `UiStateTextTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 284（基于 build283）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build284 更新说明](docs/releases/build284-changes.zh.md)，验证记录见 [build284 记录](docs/releases/v0.2.1-rc.3-build284-web-crash-panel-20261103.md)。

## 0.2.1-rc.3-20261103.0300-dsh0.2.1-rc.5 / build283（实验性：进入对话先显示原生加载页）

- **实验性尽力修复。** 从容器进入对话时先显示原生加载页，WebView 首帧不再白底：首帧底色取最近一次已核验的网页背景色，对话页首次加载盖与网页启动画面同款的原生加载页，8 秒后与诊断入口一起出现「返回启动页」（分支 `fix/web-entry-blank` 39583ae0，原基于 build274，无冲突；用户未复现原问题）。
- 验证范围：发布会话在 build283 上复跑 `WebPageBootOverlayTest` / `WebBackgroundPageLifecycleTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 283（基于 build282）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build283 更新说明](docs/releases/build283-changes.zh.md)，验证记录见 [build283 记录](docs/releases/v0.2.1-rc.3-build283-web-entry-blank-20261103.md)。

## 0.2.1-rc.3-20261103.0230-dsh0.2.1-rc.5 / build282（实验性：插件后台检测只在插件页可见时进行）

- **实验性。** 插件自动查询只在插件页可见时周期进行；对话页在前台时不再每约 2 分钟进容器跑一次 `plugin-manager.py refresh`（分支 `fix/plugin-auto-query-scope` ecdec5b9，原基于 build272，无冲突；运行时描述符重算）。
- 验证范围：发布会话在 build282 上复跑 `PluginManagementEngineTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 282（基于 build281）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build282 更新说明](docs/releases/build282-changes.zh.md)，验证记录见 [build282 记录](docs/releases/v0.2.1-rc.3-build282-plugin-auto-query-scope-20261103.md)。

## 0.2.1-rc.3-20261103.0200-dsh0.2.1-rc.5 / build281（实验性：ADB 开启时重新打开不再卡在环境检查）

- **实验性尽力修复。** 开启 ADB 时，解锁后重新打开 DSHA 不再与 ADB 后台探活争抢环境，减少长时间停在「正在检查运行环境」（T25，分支 `fix/adb-reopen-env-check` 18af5dc9，原基于 build272；未能复现原问题）。与 build278 的 `MainActivity` 字段在同一处新增，两边保留；运行时描述符重算。
- 验证范围：发布会话在 build281 上复跑 `AdbProbeDeferralTest` / `MainRecreatePolicyTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 281（基于 build280）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build281 更新说明](docs/releases/build281-changes.zh.md)，验证记录见 [build281 记录](docs/releases/v0.2.1-rc.3-build281-adb-reopen-env-check-20261103.md)。

## 0.2.1-rc.3-20261103.0130-dsh0.2.1-rc.5 / build280（设备桥端口发现失败时不再回退 3090）

- **安全修复。** 设备桥端口未发布或读取失败时 fail-closed，不再回退默认 3090 并携带本安装 token；多个 DSHA 共存时凭据不会发给另一个安装（分支 `fix/bridge-port-fail-closed` 3be984d6，基于 build279，无冲突）。涉及 `bridge-port.cjs`、`adb-shell.py`、`native-consent.cjs`、`dns-compat.cjs` 与 6 个内置插件，运行时描述符随之更新。CI 节点化后的首个正式发布。
- 验证范围：发布会话在 build280 上复跑 `UpdateVersionPageTest`、宿主回归 `test-bridge-port.py` / `test-bridge-port.mjs` / `test-native-consent-transport.mjs` / `test-dsh-builtins.mjs` / `test-destructive-global-consent.mjs`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 280（基于 build279）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build280 更新说明](docs/releases/build280-changes.zh.md)，验证记录见 [build280 记录](docs/releases/v0.2.1-rc.3-build280-bridge-port-fail-closed-20261103.md)。

## 0.2.1-rc.3-20261102.1430-dsh0.2.1-rc.5 / build279（实验性：启动恢复页重做）

- **build278 未单独发布**：其正式发布单测 `UiStateTextTest` 失败——后台任务列表新增的时长模板「%d 秒 / %d 分 %d 秒 / %d 小时 %d 分」与已有模板渲染结果相同，切换语言时选错译文。本版改为复用 `Fmt` 的时长模板并删除三条新模板，日文「%d 件」重名改为「%d 項目」（dc6155a7）；build278 的其余改动随本版发布。
- **实验性 UI 渲染更新，待统一视觉验收。** 启动恢复页按方案 A 实现：状态卡语义、失败分类与多语文案（分支 `design/startup-recovery-20261102`，原会话停在实现中、未提交，主会话按用户要求提交为 b7538465；临时渲染测试未入库；i18n 条目级三方合并、语言字节钉重算）。
- 验证范围：发布会话在 build279 上复跑 `StartupRecoveryStateTest` / `StartupRecoveryLifecycleTest` / `UiStateTextTest` / `BackgroundTaskModelTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 279（基于 build278）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build279 更新说明](docs/releases/build279-changes.zh.md)，验证记录见 [build279 记录](docs/releases/v0.2.1-rc.3-build279-startup-recovery-20261102.md)。

## 0.2.1-rc.3-20261102.1400-dsh0.2.1-rc.5 / build278（实验性：后台任务页与环境提示重绘）

- **实验性 UI 渲染更新，待统一视觉验收。** 后台任务页与环境提示横幅重绘；同进程重建界面不再显示冷启动加载页；主界面门禁区分本进程进行中的事务与崩溃残留（分支 `ui/background-tasks-redraw`，原会话未提交，主会话按用户要求提交为 68b6c294；i18n 条目级三方合并、语言字节钉重算）。
- 验证范围：发布会话在 build278 上复跑 `BackgroundTaskModelTest` / `MainRecreatePolicyTest` / `MaintenanceOwnershipTest` / `RecoveryBannerStateTest` / `StartupRecoveryLifecycleTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 278（基于 build277）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build278 更新说明](docs/releases/build278-changes.zh.md)，验证记录见 [build278 记录](docs/releases/v0.2.1-rc.3-build278-background-tasks-redraw-20261102.md)。

## 0.2.1-rc.3-20261102.1330-dsh0.2.1-rc.5 / build277（受限状态导出可读副本入口）

- 维护失败或待恢复时，维护页与数据与备份页直接提供「导出可读副本」入口；受限主界面的数据救援入口不再置灰（分支 `fix/limited-rescue-export` 15072402，原基于 build271；原会话未结束，按用户要求由主会话合并，语言字节钉重算）。
- 验证范围：发布会话在 build277 上复跑 `MaintenanceFooterPolicyTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 277（基于 build276）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build277 更新说明](docs/releases/build277-changes.zh.md)，验证记录见 [build277 记录](docs/releases/v0.2.1-rc.3-build277-limited-rescue-export-20261102.md)。

## 0.2.1-rc.3-20261102.1300-dsh0.2.1-rc.5 / build276（首次引导动效与部署加载动画）

- 首次引导步骤切换动效；部署进度当前阶段增加加载动画（分段流光 + 阶段圆环），引导、左栏进度卡、阶段面板与维护页共用（分支 `ui/onboarding-motion` 5de52819，原基于 build272，无冲突）。
- 验证范围：原会话在 build272 测试包上真机视觉核验通过；发布会话在 build275 上复跑 `OnboardingMotionPlanTest` / `OnboardingActivityTest` / `DeployCapsuleTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未跑 LayoutAudit。版本码提升为 276（基于 build275）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build276 更新说明](docs/releases/build276-changes.zh.md)，验证记录见 [build276 记录](docs/releases/v0.2.1-rc.3-build276-onboarding-motion-20261102.md)。

## 0.2.1-rc.3-20261102.1230-dsh0.2.1-rc.5 / build275（实验性：启动日志就绪用时修复）

- **实验性 UI 修复，待统一视觉验收。** 启动日志「已就绪 · 用时」不再随刷新增长（T22，分支 `fix/launch-log-ready-duration` 9cd7b3af）：build272 引入，`LaunchLogPanel.renderMeta()` 的 READY / FAILED 直接用 `snapshot.elapsedMs`，进入网页前该值随面板每秒刷新增长。改用 `StartupLogDigest.endMs`：已就绪取 browserReadyMs，其次 backendReadyMs，与概览占比条同口径；RUNNING 不变。
- 验证范围：发布会话在 build274 上复跑 `LaunchLogPanelTest` / `StartupLogDigestTest` / `StartupTraceTest` / `UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。未上真机。版本码提升为 275（基于 build274）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build275 更新说明](docs/releases/build275-changes.zh.md)，验证记录见 [build275 记录](docs/releases/v0.2.1-rc.3-build275-launch-ready-duration-20261102.md)。

## 0.2.1-rc.3-20261102.1200-dsh0.2.1-rc.5 / build274（实验性：主题与外观）

- **实验性 UI 更新，待统一视觉验收。** 主题与外观（分支 `feat/theme-motion` e935ec96）：设置 › 体验新增独立「主题与外观」页（浅色 / 深色 / 跟随系统预览卡），顶栏主题按钮移除；切换动画四选一（圆形扩散、对角擦除、淡入淡出、直接切换），截图先盖住旧窗口再开始，修掉回闪；冷启动开机加载页加描线与字标淡入。
- 五套配色（经典蓝 / 极光 / 樱花 / 赛博 / 琥珀）与日夜正交：强调色与底色改为 `?attr/dsha_pal_*` 间接引用，`tools/generate-theme-palettes.py` 生成并校验 WCAG ≥ 4.5，经典蓝逐字保留原值。原生亮暗经集成插件同步到 Web（可关），配色不同步到 Web。
- 合入 build273 时：build270 下载记录页新增的 `shell_accent_tonal` 并入调色板强调色族（按各配色主色换色相、保持原透明度，经典蓝保持 `#1F1E50D0` / `#246AA4FF`，五套配色主色文字在其上对比度均 ≥ 4.5）；i18n 按条目三方合并、字节钉重算，`runtime-descriptor` 重新生成。
- 验证范围：分支内定向单测 29 项与测试包真机浏览；发布会话在 build273 上复跑主题相关单测、`UpdateVersionPageTest`、`test-ui-colors.py`、`lintStandardRelease` 与源码门禁。未跑全量单测与 LayoutAuditInstrumentation。版本码提升为 274（基于 build273）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build274 更新说明](docs/releases/build274-changes.zh.md)，验证记录见 [build274 记录](docs/releases/v0.2.1-rc.3-build274-theme-motion-20261102.md)。

## 0.2.1-rc.3-20261102.1130-dsh0.2.1-rc.5 / build273（运行配置页保存栏避让导航胶囊）

- 运行配置页保存栏让出手机底部悬浮导航胶囊（分支 `fix/config-save-bar-clearance` 52e151a7）：手机上胶囊（64dp 高 + 10dp 底边距）浮在内容之上，固定页底的保存栏整条被盖住，改了配置无法保存。新增 `shell_nav_clearance`（手机 74dp，平板 sw600dp 左侧导航为 0dp），保存栏加底边距；位置与保存逻辑不变。
- 验证范围：发布会话在 build272 上复跑 `ConfigSaveBarClearanceTest`、`UpdateVersionPageTest`、`lintStandardRelease` 与源码门禁。LayoutAudit 单独渲染配置页时没有胶囊，量不到这处重叠；未上真机。版本码提升为 273（基于 build272）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build273 更新说明](docs/releases/build273-changes.zh.md)，验证记录见 [build273 记录](docs/releases/v0.2.1-rc.3-build273-config-save-bar-20261102.md)。

## 0.2.1-rc.3-20261102.1100-dsh0.2.1-rc.5 / build272（实验性：启动日志区原生重做）

- **实验性 UI 更新，待统一视觉验收。** 启动页「启动日志」区按 `docs/design/launch-log-20261101/preview.html` 原型原生实现（分支 `ui/launch-log-native` 241d1541）：概览按阶段分组显示开始 / 完成 / 耗时，问题行上浮并自动展开；原始视图保留完整日志，支持「仅问题」「隐藏计时」「换行」切换与从概览跳转高亮。
- 阶段分组由 `util/StartupLogDigest` 按 `StartupTrace.Entry.kind` 计算，不解析中文文案；整体结果只取 controller 真实状态，不从日志推测。上翻后显示「n 条新日志」浮标，点按回到末尾；复制与下载始终是完整原文，与筛选无关。`StartupTrace.Snapshot.log` 与诊断归档字节不变。
- 验证范围：分支内定向单测 71 项与源码 / 文案门禁；合入 build271 时 i18n 按条目三方合并、字节钉重算，发布会话复跑启动页与启动诊断相关单测、`UpdateVersionPageTest`、`lintStandardRelease`（0 错误）及源码门禁。未跑全量单测与 LayoutAuditInstrumentation，未上真机。版本码提升为 272（基于 build271）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build272 更新说明](docs/releases/build272-changes.zh.md)，验证记录见 [build272 记录](docs/releases/v0.2.1-rc.3-build272-launch-log-20261102.md)。

## 0.2.1-rc.3-20261102.1030-dsh0.2.1-rc.5 / build271（实验性：外接设备（默认关闭））

- **实验性功能，默认关闭，未经真机验收。** 新增「外接设备」能力（`extdev/**`，接手 T3 `feat/external-device-debug` 骨架并重做）：经 USB 或无线调试（IPv4:端口）控制另一台手机，独立授权、不继承本机 ADB / 全局授权；首次开启需原生确认。
- USB 模式识别：adb / fastboot 按接口三元组，9008 / BROM / Preloader / 展锐 / Odin 按 VID/PID 只识别并提示。SuperADB 式安装 APK / APKS / XAPK(+OBB) / 模块 ZIP，root 下走 pm 会话，每次原生确认，确认后按 SHA-256 与绑定代次复核。刷机只做只读预检与 dry-run，不执行刷写；fastboot、预检、刷写只认 USB；无线目标排除本机网卡地址与同指纹设备。
- 同一专属 loopback server 内多步执行，nonce 切分步骤，UNKNOWN 不重放；执行守卫只放行计划生成的 install / push / shell 形状，adb / fastboot reboot 受限。adb / fastboot / 加载器纳入固定锁 `tools/external-tools/lock.json`，构建核对证明，运行时字节不符即拒绝。附 ADB 遥控 / 刷机指令参考文档。
- 合入时与 build268 设备能力授权页压缩冲突：外接设备卡原先按 ScrollView 首个子视图插入，新布局根节点已不是 ScrollView，会在打开页面时抛出类型转换异常；改为追加到「通道」分组末尾，随分组在单栏 / 双栏间移动；字段两边保留。
- 验证范围：分支内定向单测通过；合入 build270 后重算运行时描述符，发布会话复跑外接设备 / 设备命令守卫 / 桥路由与 `ui.*` 共 414 项、`test-external-tools.py`（11 项篡改用例被拒）与源码门禁。未经真机验收，未在真机上连接任何外接设备。版本码提升为 271（基于 build270）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build271 更新说明](docs/releases/build271-changes.zh.md)，验证记录见 [build271 记录](docs/releases/v0.2.1-rc.3-build271-external-device-20261102.md)。

## 0.2.1-rc.3-20261102.1000-dsh0.2.1-rc.5 / build270（实验性：任务状态上岛页重绘）

- **实验性 UI 更新，待统一视觉验收。** 设置 › 任务状态上岛页（`IslandFragment`）按方案 B 重绘。原页面在 API 36.0 上只显示「无法确认系统状态」，用户不知道该做什么；顶栏落到 app_name；计时器开关与总开关的依赖未体现。
- 顶部示意卡展示回到桌面后的胶囊与通知（每秒读任务快照，选举与文案沿用 `IslandText`，只读不发通知）；系统状态行：36.0 旧接口改为「系统支持，开关状态查不到」并给出系统设置入口，36.1+ 关闭时提供「去打开」；行为开关标题列可点按，总开关关闭时计时器置灰但保留已存值，偏好键与默认值不变；上岛顺序五级只读列表高亮当前占岛级别；窗格 ≥700dp 两列。纯逻辑 `util/IslandPage`（+单测）；新增 42 条文案及译文；MainActivity 顶栏标题一行。
- 验证范围：分支内格式 / i18n / hygiene / 文档链接 / 资源门禁与 4 个定向单测类（35 项）；合入 build269 时 i18n 按条目三方合并、字节钉重算，发布会话复跑 `ui.*` 与 `IslandPageTest` 共 342 项及源码门禁。未装机，未跑 LayoutAuditInstrumentation，需统一视觉验收。版本码提升为 270（基于 build269）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- build269（bc41c40c）的 Source checks 通过，但正式发布在 `lintStandardRelease` 报 3 个 WrongConstant 失败（`TransferDetailView` 用了 `Layout.BREAK_STRATEGY_SIMPLE`），未生成 Release、不补发；c9edb6af 改用 `LineBreaker` 常量（本机 lint 0 errors），269 的改动随本版发布。
- 十语种更新说明见 [build270 更新说明](docs/releases/build270-changes.zh.md)，验证记录见 [build270 记录](docs/releases/v0.2.1-rc.3-build270-island-status-20261102.md)。

## 0.2.1-rc.3-20261102.0930-dsh0.2.1-rc.5 / build269（实验性：下载记录页重写（分段筛选、分组列表、详情页））

- **实验性 UI 更新，待统一视觉验收。** 下载记录页（`FileTransfersActivity`）按 `docs/design/downloads-20261102` 原型重写界面渲染，传输引擎语义不变。原页四个等权按钮易误触、同名记录无法区分、标题不可见。
- 列表：标题与实时摘要、四段等宽筛选、需要处理 / 正在传输 / 按日期分组，每行一个主操作加更多菜单，新增「移除已结束记录」。详情：手机与平板竖屏整页推入，≥840dp 左列表右详情；状态卡带下载 / 校验 / 保存三步进度，危险操作集中在管理区，取消已有进度需确认。纯逻辑抽到 `util/TransferListModel`（+单测）；新增 105 条十语文案。
- 验证范围：分支内定向单测 16 项与界面语言门禁；合入 build268 时 i18n 按条目三方合并、字节钉重算，发布会话复跑 `ui.*` 与 `TransferListModelTest` 共 341 项及源码门禁。未经真机渲染，需统一视觉验收（清单见 `docs/design/downloads-20261102/implementation.md`）。版本码提升为 269（基于 build268）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build269 更新说明](docs/releases/build269-changes.zh.md)，验证记录见 [build269 记录](docs/releases/v0.2.1-rc.3-build269-downloads-20261102.md)。

## 0.2.1-rc.3-20261102.0900-dsh0.2.1-rc.5 / build268（实验性：设备能力授权页压缩）

- **实验性 UI 更新，待统一视觉验收。** 设备能力授权页（`ui/DeviceGrantsFragment`）按 v3 预览压缩：页面大标题交给顶栏，新增不随内容滚动的概览头部（全局 / Root / Shizuku·Stellar / 屏幕四个胶囊，点按跳到对应分组），状态与风险色映射抽到 `util/GrantSummary`（+单测）。
- Root、Shizuku、ADB 合为一张通道卡，长说明收进分组标签 ⓘ；Root 拦截档位与屏幕确认方式改为三段选择，放宽档仍进入原有红色确认（三项勾选 + 10 秒），确认前分段保持已保存值，Root 未启用时不保存；风险确认按钮顺序与点空白不关闭保持不变。内容宽 ≥640dp（含平板设置分栏）时屏幕操作与敏感能力移到右栏。新增 24 条文案十语登记。
- 同版合入不占号的设计稿：`docs/design/retained-data-20261102/` 保留副本与旧环境页重设计多方案原型（实验性，未改原生）。
- 验证范围：分支内定向单测 35/35 与格式 / i18n / hygiene 门禁；合入 build267 后发布会话复跑 `ui.*` 与 `GrantSummaryTest` 共 341 项及源码门禁。未跑 Lint、LayoutAudit，未经真机渲染，需统一视觉验收（日夜、短屏、1.3 倍字体、中英文、平板竖横屏）。版本码提升为 268（基于 build267）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build268 更新说明](docs/releases/build268-changes.zh.md)，验证记录见 [build268 记录](docs/releases/v0.2.1-rc.3-build268-device-grants-compact-20261102.md)。

## 0.2.1-rc.3-20261102.0830-dsh0.2.1-rc.5 / build267（实验性：切到移动数据后容器无外网的争取性修复）

- **实验性更新。** 根因：容器 `/etc/resolv.conf` 只在拉起进程时写入当时网络的设备 DNS（常为 WiFi 私网 DNS），切网后不更新，新网络下该地址不可达，解析全部超时。
- `device/NetworkStateMonitor`：全 App 唯一默认网络回调，ADB 重连改为订阅它，过滤切网时旧网络迟到的 onLost。`runtime/ResolverRefresher`：设备 DNS 变为新的非空列表后按原规则重写 DSHA 托管段；用户 nameserver 与软链接不动，断网瞬间不写，维护独占期间推迟；`resolv.conf` 不再计入受管资产戳。
- `dns-compat.cjs`：EAI_AGAIN / EAI_FAIL 时经 `/app/net/resolve` 让 Android 系统解析器解析一次，只解析不重放 HTTP，旧 App 返回 [NO_CMD] 时保持原错误，不注入公共 DNS。`/app/net` 只读诊断宿主网络与容器 resolv.conf 来源及是否过期；两个端点均受 X-Token 门控（见安全模型）。
- 验证范围：分支内定向单测 27 项通过；合入 build266 后发布会话复跑网络 / RuntimeTools / 桥路由单测 36 项、`test-dns-compat.mjs` 13/14（原生 LD_PRELOAD 夹具项在合入前同样失败，属 proot 环境限制）与源码门禁。未做切网真机验证，不保证所有机型、ROM、运营商、VPN 或私有 DNS 组合下可靠。版本码提升为 267（基于 build266）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build267 更新说明](docs/releases/build267-changes.zh.md)，验证记录见 [build267 记录](docs/releases/v0.2.1-rc.3-build267-mobile-data-network-20261102.md)。

## 0.2.1-rc.3-20261102.0800-dsh0.2.1-rc.5 / build266（悬浮助手重构：可拖动胶囊与对话卡片，展开收起不再取消对话）

- 悬浮助手（`assistant/**`、`control/OwnedScreenOverlay`）重构为可拖动胶囊加对话卡片：展开 / 收起 / 切换会话不再取消对话；状态行为拖动把手，限制在屏幕内并记住位置（`OverlayPlacementTest`）；加 ✕、防误触与停止二次确认（`OverlayTouchGuardTest`）；✕ 隐藏后转通知回复。
- 窗内直接输入：用户输入期间 AI 的手机动作原地等待（`util/ComposeGate`，读屏 dump/tree/screenshot/senseState 不受影响），发送后以 USER_MESSAGE_ARRIVED 插进当前轮次接着做，不自动重放动作。
- 手机操作确认增加「本轮允许此应用」，敏感应用每 5 次再确认（`TurnScreenGrantsTest`）；带当前手机任务身份的 `/app/launch` 改走悬浮助手确认窗；启动入口偶发为空时按 MAIN/LAUNCHER 解析同包导出入口，NOT_FOUND 附带是否已安装。
- 验证范围：分支内全量单测 3157 项仅 8 项本机环境基线失败；真机核对展开不取消对话、新卡片日夜、本轮授权、启动确认绑定与设置页启动。合入 build265 时 `HttpShellService` 与 build262 新增的 senseState 读屏操作冲突，两边保留；重算运行时描述符，发布会话复跑助手 / 控制 / 桥 / 识屏等单测与源码门禁。窗内输入键盘与 steer 衔接、通知回复、上岛回复未真机核对。版本码提升为 266（基于 build265）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build266 更新说明](docs/releases/build266-changes.zh.md)，验证记录见 [build266 记录](docs/releases/v0.2.1-rc.3-build266-assistant-overlay-20261102.md)。

## 0.2.1-rc.3-20261102.0730-dsh0.2.1-rc.5 / build265（维护页底栏图标跟随文字居中）

- 运行环境维护页（`ExtractActivity`）底部「维护记录 / 复制给支持 / 应急 DSH」三个按钮，build255 起用 compound drawable 挂图标，图标贴在按钮最左边、文字在中间。`ui/ExtractFooter` 改为图标紧贴文字居中，竖排分支保持图标在上（`ExtractFooterIconTest`）。
- 随本版合入两项不占号的改动：`docs/design/web-process-isolation-20261102/` DSH Web 独立进程设计稿（看板 T15，D1–D4 未决，暂不实现）；识屏真机探针不再自行 task-begin / task-end、保留完整启动回执首行（仅测试工具，`tools/screen-eval/device_probe.py`）。
- 验证范围：用户在测试包上真机确认底栏图标居中；合入 build264 后发布会话复跑维护页相关单测 45 项与源码门禁。版本码提升为 265（基于 build264）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build265 更新说明](docs/releases/build265-changes.zh.md)，验证记录见 [build265 记录](docs/releases/v0.2.1-rc.3-build265-extract-footer-icons-20261102.md)。

## 0.2.1-rc.3-20261102.0700-dsh0.2.1-rc.5 / build264（版本更新页压缩：主卡合并、进度按钮原位切换、平板横屏两栏）

- 版本、通道、状态与主操作合并为一张主卡，去掉重复大标题与介绍，长版本名折叠可展开复制。主按钮 `ui/UpdateProgressButton` 本身就是进度条，检查 / 下载 / 核验 / 安装原位切换，宽高位置不变；页面不放取消（下载通知栏保留）。显示规则抽到 `util/UpdateActionPolicy` 并配单测。
- 平板横屏（≥840dp，`layout-w840dp/activity_update.xml`）左栏主卡与管理、右栏候选 / 本版本更新记录；平板竖屏单栏居中约 640dp。
- 检查更新进行中允许切换更新通道：切换时断开当前请求并作废其结果，收尾后按新通道立即重查，候选写入与作废标记同锁复核，旧通道结果不会落入新通道；下载、校验与安装交接仍锁定，显式取消 / 暂停优先（`core/UpdateEngine` + 测试）。
- 验证范围：用户在测试包上真机确认检查中切换通道（日夜）与进度按钮动画，并核对平板竖屏；短屏、1.3 倍字体与平板横屏两栏未单独取证。合入 build263 后 i18n 按条目三方合并、字节钉重算，发布会话复跑 `ui.*` 与更新相关单测 403 项及源码门禁。版本码提升为 264（基于 build263）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build264 更新说明](docs/releases/build264-changes.zh.md)，验证记录见 [build264 记录](docs/releases/v0.2.1-rc.3-build264-update-compact-20261102.md)。

## 0.2.1-rc.3-20261102.0630-dsh0.2.1-rc.5 / build263（关闭终端标签不再卡死，DSH 不会被连带结束）

- `PtySession.finishAndWait` 持有会话监视器最长数秒等待 proroot 会话退出；主线程关闭 / 切换时调用的 `attachListener`、`inputProblem` 排在同一把锁后，FocusEvent 超时触发 ANR，用户选择「关闭应用」后 App 进程连同子进程 DSH Web 一起结束。真机进程树记录显示 PTY 会话 2 秒内已正常退出，DSH 是随 ANR 结束的 App 进程被带走。
- 监听与输入问题状态改用独立锁，监听回调移到锁外执行（`PtySessionLockContractTest`）。`PtySession` 属受管启动器输入，运行时描述符随之更新。
- 验证范围：用户在 Android 16 平板合并测试包上真机确认关闭唯一 PTY 标签不再 ANR、DSH 不掉线；合入 build262 后发布会话复跑终端 / PtySession 单测 209 项与源码门禁，按固定格式化器补空行并重算描述符。版本码提升为 263（基于 build262）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build263 更新说明](docs/releases/build263-changes.zh.md)，验证记录见 [build263 记录](docs/releases/v0.2.1-rc.3-build263-pty-close-isolation-20261102.md)。

## 0.2.1-rc.3-20261102.0600-dsh0.2.1-rc.5 / build262（新增只读紧凑识屏预览 /app/sense/state）

- 新增 `/app/sense/state`（及 `/app/ui/sense/state`）只读全量紧凑屏幕状态，复用手机任务、授权、窗口代次与 owner 围栏；`k***` 只是本次预览标识，不是动作凭据，动作仍走原 treeId/nodeId 租约。节点 action list 每次只读一次并转位图，租约复核同时比对 action-set；原 dump/tree 输出不变。
- P0a：新增 `tools/screen-eval` 固定种子合成样本评估，compact 编码逐字节镜像 `ScreenSenseCodec`，与 Java `ScreenSenseBudgetTest` 共用 SHA-256 golden；去掉逐行占位符后 60/120/200 元素为 963/1869/3090 字符，60 元素仍未达 ≤900 目标。回复增加 source、quality（ok/partial/empty）、partial、readMs、ageMs，format 升为 v4-preview-2；`/app/help` 识屏说明改为中英双语。
- 测试包专用真机核对探针只记录计数与拒绝码；原生设置页 tree 与 sense 31/31 一致，负控 STALE_NODE 通过。
- 验证范围：分支内定向单测 30/30 与真机原生页只读核对通过；合入 build261 后重算运行时描述符，发布会话复跑识屏 / 桥路由单测 377 项、`test-screen-eval.py` 与源码门禁。版本码提升为 262（基于 build261）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- build261（990bd326）的 Source checks 与正式发布均因 `tools/java-format-files.json` 漏登记 T12 新增的 `TerminalReadiness` / `ReadinessCache` / `ReadinessCacheTest` 失败（FORMAT_MANIFEST_INCOMPLETE），未生成 Release、不补发；a86282da 补登记，261 的改动随本版发布。
- 十语种更新说明见 [build262 更新说明](docs/releases/build262-changes.zh.md)，验证记录见 [build262 记录](docs/releases/v0.2.1-rc.3-build262-screen-sense-20261102.md)。

## 0.2.1-rc.3-20261102.0530-dsh0.2.1-rc.5 / build261（终端粘贴长文本不再无响应）

- `PtyTerminalFragment.inputAllowed()` 每个码点、`attachSelected()` 每次切换都在主线程全量计算运行时描述符（BackupJson 序列化 + 哈希），粘贴长文本时累计超过 5 秒触发 ANR，build253 起正式包同样受影响。
- 新增纯逻辑 `util/ReadinessCache` 与 `ui/TerminalReadiness`：终端输入与切换只读缓存的就绪结论；维护进行中一律全量检查，维护任务代次 / 状态、就绪依赖文件 mtime/size 变化或超过 10 秒即重算。安装、维护与启动门禁仍走全量检查，门禁本身不放宽。
- 验证范围：用户在 Android 16 平板测试包上真机确认粘贴长文本不再无响应；合入 build260 后发布会话复跑 `ReadinessCacheTest`、`TerminalInputContractTest` 等终端单测 213 项与源码门禁，并按固定格式化器补齐空行。版本码提升为 261（基于 build260）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build261 更新说明](docs/releases/build261-changes.zh.md)，验证记录见 [build261 记录](docs/releases/v0.2.1-rc.3-build261-pty-input-ready-20261102.md)。

## 0.2.1-rc.3-20261102.0500-dsh0.2.1-rc.5 / build260（运行配置页压缩为两张卡片与单行保存条）

- 运行配置页原来五组卡片、整宽下拉与常驻提示占高过多，手机一屏只见 4 项。合并为「运行与网络」「显示与启动」两张卡片，行高 48dp；兼容选项默认折叠并标记已改；DNS 改用分段选择，网页内核改为副文案；局域网关闭时隐藏 HTTP 提示。
- 底部保存条显示未保存项数并可撤销（`util/ConfigDraft` + 单测）；窗格宽 ≥720dp 两列，>1080dp 居中留白。保存流程、端口校验与环境任务门禁不变。build258 新增的「从桌面图标直接进入 Web」开关放在「显示与启动」卡片首行，仍即时保存、不计入未保存项。
- 新增 25 条文案及译文（与 build256 诊断页重复的两条源文改为复用），hygiene 字节钉按合并结果重算。
- 验证范围：分支内定向单测通过；合入 build259 后发布会话复跑 `ui.*` 与 `ConfigDraftTest`、源码门禁。未在真机安装，未跑 LayoutAuditInstrumentation。版本码提升为 260（基于 build259）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build260 更新说明](docs/releases/build260-changes.zh.md)，验证记录见 [build260 记录](docs/releases/v0.2.1-rc.3-build260-config-compact-20261102.md)。

## 0.2.1-rc.3-20261102.0430-dsh0.2.1-rc.5 / build259（启动页启动 / 停止 / 重启按钮改用矢量图标）

- 启动页按钮原先把 ▶ ↻ □ 写进文案，字体回退不一致且读屏会念出符号。改为 MaterialButton 矢量图标：启动用实心三角，已运行时主操作「进入工作台」用右箭头，停止用实心圆角方块，重启复用插件页回环图标；文案去掉符号，十语种译文与哈希钉同步。
- 宽窗口下任一按钮文字折行时单向切换为窄屏排列（主操作独占一行，停止 / 重启分在下方），避免平板竖屏带插图时「重启」被挤成竖排（`ui/LaunchFragment`）。
- 验证范围：分支内 `ui.*` 单测 319 项通过，用户真机核对平板横屏未运行 / 已运行、日夜与竖屏上下排列；1.3 倍字体与手机短屏未单独真机核对。合入 build258 后发布会话复跑 `ui.*` 单测与源码门禁。版本码提升为 259（基于 build258）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build259 更新说明](docs/releases/build259-changes.zh.md)，验证记录见 [build259 记录](docs/releases/v0.2.1-rc.3-build259-launch-icons-20261102.md)。

## 0.2.1-rc.3-20261102.0400-dsh0.2.1-rc.5 / build258（开机加载页、桌面图标直进 Web、内置插件 dsh-launch-target）

- 新增桌面图标启动目标：原生「设置 → 运行配置」与新内置系统插件 `dsh-launch-target`（Web 插件详情页）可选「主界面 / Web 界面」，默认主界面。值只保存在原生 App（3090 桥 `/app/entry-target`，仅接受 `main` / `web`），不进入备份；插件路由经 DSH `connection` 鉴权与同源检查，浏览器不接触桥 token。
- 选择 Web 时，桌面图标先显示开机加载页（`BootScreen` / `BootSpinnerView` / `util/BootPalette`），入口门禁放行且鉴权就绪后进入 Web；等待鉴权不设强制时限，60 秒后只补提示。维护、启动恢复、原实例待确认、待处理的插件链接，以及通知 / 任务卡 / 旋转恢复都仍进入主界面（`util/EntryTarget`、`ui/DirectWebLaunch`）。
- 签名名单写在 `dsh-runtime.bin` 补丁内，运行时归档按 npm 锁重建（SHA-256 2316d6d9…）；独立应急锁同步到同一字节以恢复包内共用，应急 runtimeId 随之变化，已装应急舱会按新身份重建。固定输入切到 `fixed-inputs-20261101`。
- 验证范围：分支内定向单测与源码门禁通过，合入 build257 后重算运行时描述符并复跑定向单测；应急 DSH 启动 / 停止与本版正式包均未在真机验收。版本码提升为 258（基于 build257）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build258 更新说明](docs/releases/build258-changes.zh.md)，验证记录见 [build258 记录](docs/releases/v0.2.1-rc.3-build258-direct-web-launch-20261102.md)。

## 0.2.1-rc.3-20261102.0200-dsh0.2.1-rc.5 / build257（proroot 兼容层修正 fcntl 错误码，跨进程文件锁恢复正常）

- proroot v1.2.8 的 `libproroot-runtime.so` 导出的 `fcntl` 把 `proroot_raw_syscall6` 已返回的 -1 再取反写入 errno，所有失败都变成 EPERM：锁被占用（EAGAIN/EACCES）、EBADF、EINVAL 都丢失。JDK `libnio` 走这个旧符号，另一进程持锁时 `FileChannel.tryLock` 抛 Operation not permitted，Gradle 等跨进程锁失效。
- 先于 vendor 预加载的 `libdsha-proroot-compat.so` 现导出 `fcntl`：`F_DUPFD` / `F_DUPFD_CLOEXEC` 仍经 `RTLD_NEXT` 交给 vendor（保留 drm/seat/netlink dup 跟踪），其余命令直接发内核系统调用并按 glibc 语义设置 errno。NDK 26.3.11579264 锁定构建，旧源码可逐字节复现旧产物；新增 `tools/test-proroot-fcntl.py`，用假 vendor 复现 EPERM 并核对代理在前的结果。proot 引擎不经过该库，不受影响；vendor 的 `mmap` / `mmap64` 有同类写法，本版未改。
- 验证范围：测试包（`.test`）在一台 Android 16 平板上安装；选 proroot 后，新开终端会话的 bash 映射中有 `libproroot-runtime.so` 与 `libdsha-proroot-compat.so`，跨进程 `F_SETLK` 探针返回 0（锁冲突得到 EAGAIN/EACCES，而非 EPERM）；proroot 与默认 proot 下 dsh Web 均能启动并完成一轮对话。主机侧 `test-proroot-fcntl.py` 与源码卫生检查通过。正式包未在真机安装；版本码提升为 257（基于 build256）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build257 更新说明](docs/releases/build257-changes.zh.md)，验证记录见 [build257 记录](docs/releases/v0.2.1-rc.3-build257-proroot-fcntl-20261102.md)。

## 0.2.1-rc.3-20261101.2330-dsh0.2.1-rc.5 / build256（数据与备份二级页重构：页内恢复向导、存储分布、保留副本筛选）

- 恢复改为页内四步向导（选择文件 → 范围与选项 → 预检核验 → 确认恢复），原来的连续弹窗并入页内；因原有作业须先定范围才能预检，顺序为先选范围再核验。旧加密备份原密码、是否恢复 API Key、项目恢复位置放在第二步，密码不跨旋转保存、用完即清。确认按钮按一次即锁定；旋转后流程可续，重启后停在预检的作业重新显示确认步骤。底层仍是原预检、确认与取消作业，确认文案与错误提示不变。
- 自定义导出改为五种范围单选并带说明，勾选包含 API Key 时提示导出文件不加密，选了「所选项目文件」但未选目录时不开始导出；救援与维护工具按数据救援、导出选项、保留数据、恢复与维护分组，各操作仍走原确认弹窗。一键备份健康状态抽到 `util/BackupHealth` 并配单测。
- 存储与文件页加总占用和按类别分布条（`util/StorageUsage`，6 项单测），颜色取自带夜间版本的主题色；保留副本与旧环境页加全部 / 备份副本 / 旧环境 / 插件 / 其他筛选，筛选只影响显示、已勾选记录保留（`util/RetainedFilter`，4 项单测）；储存指南改为可展开问答，原文不变，读屏播报展开状态；自动备份页副本列表加小标题。
- 验证范围：`assembleStandardDebug` 构建通过；全量单测 3057 项中 2 项失败，均为本机环境（Robolectric 不支持 aarch64、adb-wheels 夹具摘要不符）；31 条新文案十语种补齐，i18n、资源与格式检查通过，译文未经母语者核对。未在真机安装验证。版本码提升为 256（基于 build255）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- build255 未单独发布：`UpdateVersionPageTest` 断言「独立描边小按钮」，随包更新说明写的是「各自带描边的小按钮」，Source checks 与正式发布均失败（3087 项中 1 项）。修正提交 `fix(test)` 改断言以符合已写原文；255 的改动随本版提供，是否补发 255 由维护者另定。
- 十语种更新说明见 [build256 更新说明](docs/releases/build256-changes.zh.md)，验证记录见 [build256 记录](docs/releases/v0.2.1-rc.3-build256-data-backup-20261101.md)。

## 0.2.1-rc.3-20261101.2300-dsh0.2.1-rc.5 / build255（运行环境维护页底部操作区：三个独立描边小按钮，主按钮禁用态可辨）

- 运行环境维护页（`ExtractActivity`）底部操作区重排：去掉分隔线，「应急 DSH」「维护记录」等三个次要操作改为各自带描边、浅底和 12dp 圆角的小按钮（彼此 8dp 间距、点按高度 44dp），大字体或窄屏时图标换到文字上方；主按钮禁用态可辨，受限入口与文字链接重新排列。
- 强调状态：更新失败或中断时「应急 DSH」用红色描边加浅红底；就绪检查未通过时「维护记录」用提醒色描边加浅底；状态推导抽到 `util/MaintenanceFooterPolicy` 并配单测，按钮背景与文字颜色由 `ui/ExtractFooter` 切换，颜色取自现有主题并区分日夜。点击行为与维护门禁逻辑不变。
- 验证范围：带资源编译通过，`MaintenanceFooterPolicyTest` 等 7 项单测通过，源码卫生、i18n、Java 格式与文档链接检查通过。未在真机安装验证，更新进行中、失败、就绪检查未通过三种状态的实际观感待核对。版本码提升为 255（基于 build254）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build255 更新说明](docs/releases/build255-changes.zh.md)，验证记录见 [build255 记录](docs/releases/v0.2.1-rc.3-build255-maintenance-footer-20261101.md)。

## 0.2.1-rc.3-20261101.2230-dsh0.2.1-rc.5 / build254（安装与环境页压缩：状态卡、六步行内详情、维护与工具分组）

- 安装与环境主页：四行版本表换成一张状态卡（一句结论、三个可点按复制的版本标签、6 段进度条，「检查全部 / 按需修复」并排；运行中换成取消，维护中断时换成「恢复中断维护」）。六个步骤改为序号徽标加状态色，行高由 64dp 压到 52dp，点按在行内展开详情与「重新检查 / 按需修复」，不再弹窗。检查与修复的禁用规则不变。
- 「首次安装测速」移入「维护与工具」组；「备份并重建环境」改为组内红字一行，确认框须先勾选知情。日志默认收起成一行（最新一行加复制），运行或失败时自动展开。删除不再引用的 `entry_storage_help.xml`。
- 相关页：首次安装测速说明拆成三条要点，结果放进等宽字卡片；环境修复与恢复页返回与标题同行，按钮改为分组行，最近启动记录加状态圆点，按钮文字保持不变以兼容审计。运行环境维护页底栏未在本版改动。
- 验证范围：5 个相关测试类共 35 项通过（新增 `InstallLayoutContractTest` 核对审计依赖的控件 id 与日志框默认可见、高度 144–220dp），源码卫生、i18n、Java 格式与文档链接检查通过；19 条新文案十语种补齐，译文未经母语者核对。未运行完整单测、Lint 与 `LayoutAuditInstrumentation`，未在真机安装验证。版本码提升为 254（基于 build253）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build254 更新说明](docs/releases/build254-changes.zh.md)，验证记录见 [build254 记录](docs/releases/v0.2.1-rc.3-build254-install-env-20261101.md)。

## 0.2.1-rc.3-20261101.2200-dsh0.2.1-rc.5 / build253（终端界面 v4 精修：快捷键分组、统一会话列表、状态圆点）

- 快捷键两排重做：键帽间留 4dp 缝，去掉圆角交界处的黑色菱形；Esc / Tab / Ctrl / Alt / Home / End 底色深一档，方向键加大，^C / ^D / ^Z 与符号键用等宽字；Ctrl、Alt 锁定改为底部指示条。可点区域保持 48dp，自定义键位、横向滚动与长按自定义不变（`util/TerminalExtraKeys` 分类有单测）。
- 侧栏精简：标题栏只留图标、「会话」、结束全部（无会话时隐藏，仍先确认再逐个核验）与关闭；PTY 与简易会话合成一个按创建先后排序的列表（`util/TerminalListOrder`，只管显示顺序，不改会话身份与结束流程），状态改为名字后的小圆点（`util/TerminalSessionDot`），读屏仍读完整状态；底部六个按钮改为无描边底色块。
- 新建会话改为三张卡片（PTY 终端、简易终端、DSH CLI），说明弹窗改为四条要点加状态图例，短屏和大字体下可滚动。未改动 `TerminalSessionOwner` 与 `TerminalTabs`，受管运行时描述不变。
- 验证范围：终端相关单测通过（新增按键分类、状态点、列表顺序测试），i18n、源码卫生、格式与文档链接检查通过；15 条新文案十语种补齐，译文未经母语者核对。未在真机安装验证，夜间、大字体、短屏与 RTL 未核对。版本码提升为 253（基于 build252）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build253 更新说明](docs/releases/build253-changes.zh.md)，验证记录见 [build253 记录](docs/releases/v0.2.1-rc.3-build253-terminal-polish-20261101.md)。

## 0.2.1-rc.3-20261101.2130-dsh0.2.1-rc.5 / build252（自检与诊断页简化：状态 / 结果 / 反馈 / 工具三段，平板两栏）

- 自检与诊断页重排：手机顶栏只留标题，依次为状态卡、检查结果、反馈、工具，原 6 个分组合为 3 段；平板（`layout-sw600dp`）左栏 340dp 放状态、反馈、工具，右栏放检查结果。手机与平板共用 `diagnostic_hero` / `diagnostic_feedback` / `diagnostic_tools` 布局块，旧控件 id 保留。
- 检查结果由 `ui/DiagnosticResultList` 渲染：需要处理的项最前，未核验其次，正常项收成「N 项正常」；结果卡宽度达 560dp 时两列，1.3 倍字体退回一列，旋转后展开状态保持。「未核验」单独成状态（灰点），DSH 未运行时不再误报需要处理；分组只看健康状态，由 `util/DiagnosticSummary` 推导。
- 导出改为弹层：先选故障包（.tar.gz）或文本报告（.txt），复现步骤只在内存中附带不保存；故障包可存到下载目录或另存为，文本报告只能另存为；导出后才显示上次保存的文件。新增 29 条文案，十语种补齐，15 条译文按长度预算压短（未经母语者核对）。
- 验证范围：`DiagnosticActivityRenderingTest` 6 项与 `DiagnosticSummaryTest` 5 项通过，i18n、源码卫生、Java 格式检查与 debug 编译通过；未在真机安装验证，`LayoutAuditInstrumentation` 未运行。版本码提升为 252（基于 build251）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build252 更新说明](docs/releases/build252-changes.zh.md)，验证记录见 [build252 记录](docs/releases/v0.2.1-rc.3-build252-diagnostics-20261101.md)。

## 0.2.1-rc.3-20261101.2100-dsh0.2.1-rc.5 / build251（插件页头部重构：统一状态条、波浪进度、计数徽标）

- 插件页头部重构：标题与「市场 / 管理」同行，放不下时分段换行铺满；「插件管理」带插件数量徽标（`PluginCountBadge`），检测中、列表未确认、刷新失败各有样式；头部换行由 `PluginFlowRow` 负责。
- 市场页「社区插件生态」卡片缩成标题旁链接，粘贴/清空放进输入框尾部，提示按识别结果变色；管理页去掉「已安装插件」标题行，搜索与「检测插件」同行，筛选、排序、下载源排成一行。
- 进度条、状态文字、「取消当前操作」与「检查更新」合成一条状态条（`util/PluginHeaderStrip` 推导显示规则），进度改为 W3 波浪（`util/WaveMotion`，不确定阶段变速、下载按真实百分比回弹推进、成功/失败结束动画），开始和结束任务时高度不变；安全模式恢复按钮移到顶部警示条，下载源说明移入选择弹窗。原有控件 id 与点击行为保留。
- 验证范围：插件相关单测 408 项通过（新增 17 项），Debug 构建、文案、源码与 Java 格式检查通过；未在真机安装验证，`LayoutAuditInstrumentation` 未运行。版本码提升为 251（基于 build250）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build251 更新说明](docs/releases/build251-changes.zh.md)，验证记录见 [build251 记录](docs/releases/v0.2.1-rc.3-build251-plugin-header-20261101.md)。

## 0.2.1-rc.3-20261101.2030-dsh0.2.1-rc.5 / build250（配置快照删不掉，坏快照不再卡死写入，恢复页可删除）

- 修复配置快照“删不掉”及连带卡死：捕获中断（超 4 MiB、链接、源变化、取消）不再留下无元数据的半成品目录；轮换改为先 rename 到 `retiring/` 再清理，删除中断不再产生“元数据在、正文缺”的永久残留；删除失败只写诊断，不把已提交的目录更新报告为失败。
- 选择槽位时坏或悬空（被手动删掉目录）的槽位最先替换，不再让“修复前快照”、新建配置与插件卸载前置全部报 `RECOVERY_CHECKSUM` / `UNSAFE_ROOT`。无法校验的未引用目录移入 `retained/` 隔离区（最多 32 份），不再计入 72 份上限，`RECOVERY_RETENTION_LIMIT` 死锁解除；异常原件仍不自动删除。
- 启动恢复页：损坏快照显示错误码并可删除；普通快照点开后可选“恢复”或“删除”；隔离区可一次清空；均需原生确认与维护屏障，不改动当前配置。`catalog.json` 不可读时回退到 `.previous`；旧状态标记只保留最近 8 份；启动诊断改为记录受控错误码。
- 验证范围：本机 `compileStandardDebugJavaWithJavac` 通过；`ConfigurationSnapshotsTest`、新增 `ConfigurationSnapshotsRetentionTest` 及相关宿主维护测试共 59 项在 JDK 21 + JUnit 4 下通过；源码卫生、UI i18n、资源本地化、运行时描述符、Java 格式与文档链接门禁通过。aarch64 上 AAPT2 守护进程无法启动，Robolectric 界面测试与 Lint 未运行；未在真机安装验证。版本码提升为 250（基于 build249）；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build250 更新说明](docs/releases/build250-changes.zh.md)，验证记录见 [build250 记录](docs/releases/v0.2.1-rc.3-build250-config-snapshot-20261101.md)。

## 0.2.1-rc.3-20261101.1830-dsh0.2.1-rc.5 / build249（CI 编译身份更新：基于 build248 的未签名门禁）

- 版本身份更新为 versionCode 249，基于 build248；内置 DSH、包名与 Mod 发布证书保持不变。
- 本版用于远端 CI 编译与未签名 package 门禁，不预填正式签名 APK、Release 发布、真机安装或覆盖升级结果。
- 十语种更新说明见 [build249 更新说明](docs/releases/build249-changes.zh.md)，验证记录见 [build249 记录](docs/releases/v0.2.1-rc.3-build249-ci-20261101.md)。

## 0.2.1-rc.3-20261101.1700-dsh0.2.1-rc.5 / build248（插件卡片共用绑定：来源徽标、并排状态、底部更多弹层）

- 插件页卡片重构：列表、平板左栏与详情窗格共用同一张卡片。来源用徽标区分，版本写在标题下；配置状态与当前 Web 并排；展开后的版本、位置、来源压成属性表。状态推导抽到 `util/PluginCardState` 并配单测。
- 「更多」改走 `CardSheet`：手机贴底并抬到悬浮导航上方；宽 ≥ 600dp（平板竖屏与横屏）居中限宽，不再通栏贴底。短列表按内容收紧。
- 验证范围：本机相关单测通过；测试包（本机测试证书）在一台一加 PJD110（360dp 竖屏）与一台联想 TB321FU 平板（竖屏 / 横屏）上覆盖安装核对插件页与「更多」弹层。其他机型、语言、深色、短屏与 1.3 倍字体未核对。版本码提升为 248；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build248 更新说明](docs/releases/build248-changes.zh.md)，验证记录见 [build248 记录](docs/releases/v0.2.1-rc.3-build248-plugin-card-20261101.md)。

## 0.2.1-rc.3-20261101.1630-dsh0.2.1-rc.5 / build247（引导授权页压缩：单列表、不分类、每项一行可展开）

- 首次引导第 3 步「按需授权」改为一张单列表：去掉分类标题、分类说明、逐卡「按你的用途推荐」、分组「推荐」标签与顶部圆点；每项授权一行，已开启只显示状态，未开启在行尾给出「允许 / 去开启 / 去设置」，副标题一句话说明开了能做什么。
- 用途说明、跳转提示与其余按钮收进展开区，折叠时不建详情视图；行带展开 / 收起状态与动作，切换后读屏焦点回到该行。展开选择按「卡片 + 是否已满足」记忆（`PermissionExpansion`），每秒轮询重绘不收回，授权状态变化后自动收起。
- 推荐项排前，其余收进「更多授权 · N 项」，无推荐项时全部直接列出；`PermissionGoals` 改为按卡片划分，同组未推荐项不再被顺带排进推荐区。页面说明与底部按钮各缩为一行，与设置页共用、已有译文的句子不变。
- 验证范围：本机 ui + util 单测通过；测试包（本机测试证书）在一台一加 PJD110（360dp 竖屏）上覆盖安装，清数据后从引导第 1 步走到第 3 步核对，推荐 6 项一屏放下。其他机型、语言、深色、短屏与 1.3 倍字体未核对。版本码提升为 247；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build247 更新说明](docs/releases/build247-changes.zh.md)，验证记录见 [build247 记录](docs/releases/v0.2.1-rc.3-build247-permission-compact-20261101.md)。

## 0.2.1-rc.3-20261101.1600-dsh0.2.1-rc.5 / build246（悬浮助手与输入法隔离：其他应用弹键盘时不再上弹）

- 修复悬浮助手窗口未与输入法隔离：收起后的悬浮助手贴在屏幕底部，此前在其他应用里点输入框弹出键盘时，它会随键盘整体上弹，挡住输入区附近内容。
- 原因：`AssistantWindow` 声明了 `SOFT_INPUT_ADJUST_RESIZE`，Android 11+ 的 `ViewRootImpl` 将其兼容换算为 `fitInsetsTypes |= ime()`；`FLAG_NOT_FOCUSABLE` 只保证不成为输入法目标，不阻止窗口按他人输入法区域避让。该设置是 build195 悬浮层内嵌编辑时期的遗留，编辑早已改由 `AssistantActivity` 承担。
- 改为 `SOFT_INPUT_ADJUST_NOTHING` 并显式 `setFitInsetsTypes(systemBars())`：仍避让系统栏，不再响应输入法；读屏登记的悬浮助手排除区随之稳定。流式悬浮条、确认窗与虚拟屏预览为顶部对齐且未声明调整模式，不受影响。
- 验证范围：仅本机/CI 单测；**未在实机测试**。本修复存在不生效的可能（部分系统或 ROM 的窗口管理器仍可能让悬浮助手随键盘上弹），需要用户后续反馈确认。版本码提升为 246；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build246 更新说明](docs/releases/build246-changes.zh.md)，验证记录见 [build246 记录](docs/releases/v0.2.1-rc.3-build246-assistant-ime-20261101.md)。

## 0.2.1-rc.3-20261101.1430-dsh0.2.1-rc.5 / build245（维护门禁三态：记录读不了时给出原因与出口，插件日志可原样隔离）

- 「恢复中断维护」先区分「有待恢复事务」与「记录无法读取」：记录读不了时直接说明是哪类记录、什么原因和下一步，不再只显示原始错误码，也不再对读不了的记录重复执行必然失败的扫描。
- 插件事务日志读不了时，维护页提供「隔离无法读取的插件日志」：原生确认列出的记录后，原样移入 `plugin-install-quarantine` 并留下清单，不删除、不重放；确认后记录有变化即拒绝执行。会话与配置不受影响，相关插件之后可能需要重新安装。应急修复页同样提供该动作。
- 宿主数据、运行时、环境重建等可能半完成的事务读不了时，不提供自动隔离，只给出精确原因并建议导出诊断联系支持，避免跳过回滚导致个人数据不一致。
- 修复新旧数据目录同时存在时的死循环：此前各项检查都会读不了，「选择数据目录」又因此被拒绝；现在选择数据目录不再被这一状态挡住，其他待恢复事务仍须先处理。
- 验证范围：本机单测与插件事务 Python 回归通过；未做设备验证。版本码提升为 245；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build245 更新说明](docs/releases/build245-changes.zh.md)，验证记录见 [build245 记录](docs/releases/v0.2.1-rc.3-build245-maintenance-gate-20261101.md)。

## 0.2.1-rc.3-20261101.0910-dsh0.2.1-rc.5 / build244（插件事务日志修复：「恢复中断维护」不再被旧证明残留锁死）

- 修复「恢复中断维护」永久失败：旧版把插件完成证明原子写在插件事务目录内，写入中断会留下 `.previous`、`.tmp-*` 等残留文件；之后每次检查都报 `PLUGIN_JOURNAL_ID`，恢复卡在「正在停止 Web」，其他修复被 `REPAIR_RECOVER_PENDING_FIRST` 拒绝，Web 无法启动。
- 插件完成证明改存到事务目录之外；旧残留与目录内其他无法识别的条目原样保留，不再阻塞维护，也不会被当作插件操作重放。已遇到此问题的设备覆盖安装后应可通过检查。
- 本版更新了插件事务脚本，覆盖安装会经正常的受管运行环境更新流程替换受管文件，个人数据保持原位。
- 验证范围：本机单测（backup 与 util 包 1689 例）与插件事务 Python 回归通过；尚未在出现该问题的设备上实机验证。版本码提升为 244；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build244 更新说明](docs/releases/build244-changes.zh.md)，验证记录见 [build244 记录](docs/releases/v0.2.1-rc.3-build244-plugin-journal-20261101.md)。

## 0.2.1-rc.3-20261101.0450-dsh0.2.1-rc.5 / build243（手机竖屏修订：悬浮胶囊、部署前检查、引导授权收口；一加真机验收）

- 手机竖屏修订：启动页「停止 / 重启」在窄屏改为主按钮下方两枚等宽描边按钮；Web 恢复核对的长说明移到副标题，大标题只显示短状态。三段就绪轨道（运行环境 / 工作台 / 实际端口）保持原设计。
- 运行环境维护页不再内嵌「本次维护记录」日志框，失败说明框已含原因，完整记录仍从底栏「查看本次维护记录」打开；宽屏无说明时阶段卡占满整行。
- 屏幕操作「按应用自定义」不再显示空白标签；界面语言网格按宽度与字体缩放决定列数，大字体手机退为两列；悬浮条「最多显示行数」1–6 在一行内等分。
- 手机底部导航改为真正叠在内容上的不透明悬浮胶囊；页面底部留出胶囊高度，最后一行可滚出胶囊上方。平板仍用左侧导航，页底余量不变。
- 手机欢迎页把六格检查收成一行摘要，点开看架构、空间与网页内核；平板仍显示完整格子。
- 首次引导「部署前检查」增加网页内核与支持结论：Android 10 及以下或非 arm64 无法部署；鸿蒙/荣耀自研 WebView 标高风险并提示先更新系统 WebView；维护失败页和自检复用同一段本机摘要，可复制给支持。不联网上报。
- 引导授权页进度条只显示推荐项，不再混入「全部 x / 7」空心点；设备命令通道在引导里只推荐 Stellar（走 Shizuku 兼容层），未安装时主按钮打开 GitHub Release，已有 Shizuku 仍可用；Root 与 ADB 只放在设置页。电池优化入口改为打开本应用系统「关于」页（ColorOS 上再点「耗电管理」），不自动申请豁免。
- 引导授权页轮询不再因 Shizuku/Root 文案抖动整页重绘，无障碍短暂断连 1.5 秒内保持已连接，减轻卡片闪烁；完成页「授权」只列引导里的 6 项，不再显示 Root，计数改为「已开启 x / 6」。
- 验证范围：在一台一加 PJD110 手机（360dp 竖屏）上覆盖安装测试包验收，结果以本版验证记录为准；未在其他手机上测试。
- 版本码提升为 243；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。
- 十语种更新说明见 [build243 更新说明](docs/releases/build243-changes.zh.md)，验证记录见 [build243 记录](docs/releases/v0.2.1-rc.3-build243-phone-portrait-20261101.md)。

## 0.2.1-rc.3-20261101.0306-dsh0.2.1-rc.5 / build242（UI v5 方案 A 合入：宽屏双栏、风险分级确认、波浪活动条；仅平板验证）

- 并入 UI v5 方案 A：设置、插件在宽度 ≥840dp 时改为左列表右详情，维护页两列，启动主页按原型重排；设置子页改为平铺分组，多句静态说明只留首句，完整内容收进 ⓘ 弹窗。
- 设备能力授权：开启全局权限、放宽 root 拦截、屏幕操作「全部允许」前弹出红色确认，点空白不可关闭，「取消」在最右；放宽 root 另需 10 秒等待与三项知情勾选。Root 档位与屏幕确认方式改为风险分级卡片，屏幕操作新增应用选择器，虚拟屏单独成行。
- 启动活动条改为波浪进度条：进行中一段波浪沿底轨行进，就绪时铺满并压平成实线；只表达真实状态，不显示百分比。
- 悬浮条设置页压缩为紧凑行并改为即改即存；界面语言改为「跟随系统」加母语名网格；技能页并入设置子页，按页面宽度在单列与左右并排之间切换。
- 新增界面文案补齐十种语言；新增手机竖屏（360dp）与宽屏的 Robolectric 布局回归，以及语言选择小屏用例。
- 验证范围：仅在一台联想平板（TB321FU）上覆盖安装测试包并查看，平板横屏与竖屏均有截图核对，但并非每项改动都在设备上看过。未在任何手机真机上测试；手机竖屏只有 Robolectric 布局回归，不代表真机渲染。
- 版本码提升为 242，按测试包累计覆盖安装次数计（220 + 22），221–241 未单独发布；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5。完整构建、单测、签名与发布范围以本版 CI / Release 回执为准。
- 十语种更新说明见 [build242 更新说明](docs/releases/build242-changes.zh.md)，验证记录见 [build242 记录](docs/releases/v0.2.1-rc.3-build242-ui-v5-scheme-a-20261101.md)。

## 0.2.1-rc.3-20261101.0053-dsh0.2.1-rc.5 / build220（识屏授权悬浮窗竞态修复）

- 修复手机任务右下角悬浮控制窗与识屏授权的竞态：用户点开状态窗后，屏幕控制会立即暂停并撤销旧授权；模型侧随后到达的 `turn/end` / `/app/ui/task-end` 不再无条件关闭这个暂停控制窗。
- 用户展开并暂停的同一任务会保留控制窗，明确点击“继续”后重新观察屏幕，明确点击“停止”后回收；不自动批准、不重放旧授权或结果未知的动作。正常完成仍关闭窗口并回收服务。
- 清理路径在主线程再次核对任务归属和暂停状态，补充展开暂停保留、显式停止回收和正常完成回收的 Android 回归测试；同步更新设备操作指南。
- 补充手机端 ScreenSense 识屏/操作层性能评估与分阶段重构方案，当前仅为设计评估，未把 OCR 或紧凑协议宣称为已落地能力。
- 版本码提升为 220；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5；完整构建、单测、签名和真机验收范围以本版 CI / Release 回执为准。

## 0.2.1-rc.3-20261031.2130-dsh0.2.1-rc.5 / build219（首次引导真机修复与宽屏触屏会话行）

- 收口首次引导重构：4 步引导（欢迎 → 用途 → 授权 → 完成）、后台部署胶囊与整屏运行环境维护页；首次部署与覆盖更新共用同一维护入口。
- 按真机反馈修复：引导内后台部署被系统回收后回到原步骤；「用途」选择真正决定推荐项；电池卡片按系统「允许后台使用」状态判定；识别到 Stellar 时授权页与引导卡按实际管理器命名；电池优化入口直达本应用电池页。
- 覆盖更新恢复整屏维护页；磁盘存在未完成的隔离试运行或维护事务时，先核实旧试运行进程退出并关闭现场，再进入维护/更新；自动开始仍只尝试同一候选一次。
- 宽屏触屏（平板横屏 / CSS ≥1024px）会话行把归档/置顶收进 ⋯ 菜单：修复 280px 侧栏里标题只剩约 55px 的挤压，行内只留「时间 + ⋯」；内置 dsh-web-mobile 带本地先行补丁，上游 PR #256 合并后摘除。
- 版本码提升为 219；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5；十语种更新说明见[build219 更新说明](docs/releases/build219-changes.zh.md)。构建与验收以 CI run 与 Release 回执为准，本轮不声明真机覆盖安装验收。

## 0.2.1-rc.3-20261031.1800-dsh0.2.1-rc.5 / build218（首次引导重构：4 步引导、部署胶囊与整屏维护页）

- 首次引导重构为 4 步（欢迎 → 用途 → 授权 → 完成），替换原 3 页 ViewPager2 `WelcomeActivity` 与独立 `OnboardingPrepareActivity`；`welcomed == false` 仍只进引导，Main、启动恢复页与终端入口继续校验同一契约。
- 首次部署改为应用级后台维护任务，引导页与主界面顶栏用部署胶囊显示阶段，不再整屏等待 `ExtractActivity`；部署期间保持屏幕常亮，成功后才进入完成页并启动一次 Web，引导内部署被清后台后回到引导原步骤（`welcomed` 只在离开引导进入主界面时写入）。覆盖更新恢复为整屏的运行环境维护页（新版式：阶段条 + 阶段列表 + 底部操作），不再进入受限主界面并用弹窗显示进度；磁盘上存在未完成维护事务或未关闭的隔离试运行现场时，维护页、面板与自动开始先改走「恢复中断维护」（核实旧试运行进程退出并关闭现场），不再以 `TRIAL_RECOVERY_REQUIRED` 发起注定失败的更新。自动开始仍按「同一候选只尝试一次」，只有用户在维护页显式选择「进入受限主界面」才留在受限主界面，且不写就绪标记，终端、Web 与插件执行继续通过环境门禁。
- 新增引导第 3 步授权卡片（7 类设备能力 + ADB，按用途推荐、可跳过，全部默认关闭）；通知权限按 Android 13+ 点击申请，电池优化只读检测且不申请豁免；识别并拉起 Stellar 管理器（`roro.stellar.manager`）；无障碍设置文案统一为系统列表实际名称。
- 平板宽 ≥600dp 两栏、手机横屏压缩页头页脚，正文可滚动并适配 1.3 倍字体；部署阶段文案由纯逻辑 `DeployStages` 推断，不推导百分比。版本码提升为 218；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5；十语种更新说明见[build218 更新说明](docs/releases/build218-changes.zh.md)。本轮未在真机覆盖安装验证，构建与验收范围以 CI run 与 Release 回执为准。


## 0.2.1-rc.3-20261031.0700-dsh0.2.1-rc.5 / build217（插件列表读取误判修复：容器内 soname 符号链接）

- 修复插件列表在容器内经 apt 等安装或升级系统库后一直读取失败：Playwright 等工具经 apt 安装 `libsqlite3-0` 时把 `libsqlite3.so.0` 换成指向 `libsqlite3.so.0.8.6` 的符号链接；`RuntimeTools.queryReady` 用 lstat 只认普通文件，于是每次都显示「运行工具或资产不完整」，而 `RootfsInstaller.python` 用跟随链接的 `isFile()` 判断该文件完好，「安装与修复」永远不会改写它。该严格判据自 `fffff8a`（10-27 插件查询解耦）引入。
- 新增纯逻辑 `util/GuestRegularFile`：在 Ubuntu 运行根内逐级解析符号链接（相对链接、guest 绝对路径，以及 `.l2s` 等指回本次运行根的宿主路径），父目录链接同样按 guest 解析，`..` 不越过 guest 根，最多 40 跳；只接受最终落在运行根内的普通文件，`/system`、`/proc`、`/sdcard`、`/data` 等外部绑定、越界与循环链接判为未就绪。链接无论由容器内 proot 模拟的 root 还是外部 su 授予的 root 写入，判断一致；外部 root 写成本 UID 不可读的文件会判为 `UNREADABLE`。
- 插件只读查询与 Python 动态库修复共用该判据；修复只删除无法解析的悬空链接本身后写回随包库，已可用的一项（含 apt 的有效链接）不重写。插件就绪结论变化时写入 `PLUGIN_QUERY_READINESS` 诊断，注明具体文件与原因（如 `usr/lib/aarch64-linux-gnu/libsqlite3.so.0:MISSING`），自动刷新不重复刷屏。
- `GuestRegularFile` 配 JUnit（11 例：apt soname 链接、相对链与 `..` 钳制、guest 绝对链接、宿主镜像路径、外部绑定、悬空/循环/目录/父文件、合并 /usr 父链接、父链接越界、可执行位取目标、路径校验）；该类加入受管运行时启动输入，`runtime-descriptor.json` 随之重新生成。
- 版本码提升为 217；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5；十语种更新说明见[build217 更新说明](docs/releases/build217-changes.zh.md)。构建与验收范围以本轮 CI run 与 Release 回执为准；本轮未在真机上覆盖安装验证。

## 0.2.1-rc.3-20261031.0340-dsh0.2.1-rc.5 / build216（内置移动插件同步上游与文档纠偏）

- 内置 dsh-web-mobile 同步到上游 main 与当前 v3.2.2 标签 `bb0e5e1`：恢复全屏右侧面板层带与壁纸底色（#247），修复宠物悬浮层在弹层/面板下遮挡并吞点击（#248），文件面板手势改读 `data-sidebar-right-open` 并优先认领宠物根节点（#249）。
- DSHA 本地补丁在新上游字节上全部重放：会话输入与抽屉关闭、搜索行、遮罩按压、设置字体族行等差异保留；上游未吸收字体族行补丁，版本仍为 3.2.2。
- 上游锁更新为 232 个成员与归档 SHA-256 `80973622…`（3,284,160 B）；运行时描述符、第三方声明、移动补丁与来源文档同步更新。
- 修正仍停留在 build190 时期的维护文档与关键入口：包名统一为 `io.github.sunsetrne.dsha`、当前版本码/DSH 版本改为 build216 / 0.2.1-rc.5，并删除已不存在的 Low / GeckoView 现行描述。
- 版本码提升为 216；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 为 0.2.1-rc.5；十语种更新说明见[build216 更新说明](docs/releases/build216-changes.zh.md)。构建与验收范围以本轮 CI run 与 Release 回执为准。

## 0.2.1-rc.3-20261031.0300-dsh0.2.1-rc.5 / build215（安装包减重：应急 DSH 对齐 rc.5 共用归档）

- 独立应急（安全启动/救援）DSH 锁经用户明确授权由 0.2.1-rc.3 同步到正式环境同一份 0.2.1-rc.5 归档（127,674,426 B，SHA-256 `91aa4685721ce569e32953295dc6b01b63fe2ac5e1eac8173440656896acdcb2`）；两份归档字节相同，APK 按 `recovery-asset-locations.json` 只保留一份，少一份约 127.7 MB 的 DSH 归档。应急环境仍解压到独立目录，独立锁、runtimeId、会话与启动流程不变；固定输入换为 [`fixed-inputs-20261031`](docs/fixed-build-inputs.md)。
- 修复独立应急首次准备时覆盖层输入名单与描述不一致（build193 起描述改用 `recovery-recipes/<版本>/` 键，Android 仍按顶层 recipe 名核对，会以 `RECOVERY_OVERLAY_INVALID` 失败）：新增 `util/RecoveryOverlayInputs` 按应急锁版本生成名单，配 JUnit 与构建侧对照测试；新增 `recovery-recipes/0.2.1-rc.5/` 两份 recipe。
- 打包排除 BouncyCastle 中应用从不加载的 Picnic 后量子常量表与 CertPathReviewer 文案（压缩后约 1.2 MB）；备份加密用到的 AES-GCM / SHA-256 / PBKDF2 轻量 API 不受影响。
- 版本码提升为 215；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 后缀为 0.2.1-rc.5；十语种更新说明见[build215 更新说明](docs/releases/build215-changes.zh.md)。构建与验收范围以本轮 run 与 Release 回执为准；应急环境在真机上的启动/对话验收未在本轮执行。

## 0.2.1-rc.3-20261030.2130-dsh0.2.1-rc.5 / build214（终端窄抽屉视觉对齐与移动端设置字体行修复）

- 终端手机竖屏/窄窗 224dp 会话抽屉做视觉对齐：底部动作改用短标签并保持 2 列×3 行、单行等高，摘要简化为「N 个会话」（完整分组计数保留在无障碍名称里），头部副标题简化为「独立会话」，抽屉内边距统一到 12dp 网格；宽屏/平板布局不变。
- 修复 DSH Web UI rc.5 手机设置弹层「正文字体 / 代码字体 / 终端字体」行的排版：窄屏下输入框改为独占整行，说明文字不再一字一行、弹层不再被纵向撑大；由内置 dsh-web-mobile 的本地补丁实现，桌面/平板不受影响。
- 版本码提升为 214；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 后缀为 0.2.1-rc.5；构建与验收范围以本轮 run 与 Release 回执为准。
- 十语种更新说明见[build214 更新说明](docs/releases/build214-changes.zh.md)。
- 已在 OnePlus PJD110 / Android 16 安装正式包并完成窄抽屉与 rc.5 设置字体行验收；逐项实测、回归与限制见[build214 真机验收记录](docs/releases/v0.2.1-rc.3-build214-acceptance-20261030.md)。

## 0.2.1-rc.3-20261030.1900-dsh0.2.1-rc.5 / build213（内置 DSH rc.5、终端 CLI 与窄窗抽屉）

- 内置 DSH 升级到上游 dsh-v0.2.1-rc.5：对话 thinking 展示随上游更新；运行时归档按 rc.5 重建（127,674,426 B，SHA-256 `91aa4685721ce569e32953295dc6b01b63fe2ac5e1eac8173440656896acdcb2`），补丁链与 31 个 data/storage 读取包已核对，证据见 [rc.5 数据读取证据](docs/runtime-data-compatibility-rc5.md)。
- 终端新增 DSH CLI 入口：工作台顶栏按钮与「新建会话」选择器可直接打开一个带固定启动提示的 PTY 会话（预检 dsh 版本并说明没有交互式 TUI、headless 的凭据/权限差异、不要在终端起 Web）；不新增会话类型，会话关闭、运行保护与维护屏障语义不变。
- 终端会话抽屉在手机竖屏/窄窗（< 600dp）的最大宽度由 336dp 收窄到 224dp（约减少 1/3），平板/宽窗（≥ 600dp）保持 336dp；遮罩/返回手势区仍至少保留 32dp，内联侧栏宽度不变。
- 224dp 抽屉下会话行按窄窗规则堆叠状态，底部动作保持两列；短窗/横屏的压缩规则不变。
- 版本码提升为 213；DSHA 修订号保持 0.2.1-rc.3，内置 DSH 后缀为 0.2.1-rc.5；构建与验收范围以本轮 run 与 Release 回执为准。
- 十语种更新说明见[build213 更新说明](docs/releases/build213-changes.zh.md)。

## 0.2.1-rc.3-20261030.1506-dsh0.2.1-rc.3 / build212（异常退出后的恢复衔接）

- 首次恢复改为三态判定：原 Web 仍存活则接回；只有确认 Web 与启动器都已退出才清理旧记录并继续启动；无法确认时保留保护。
- 已确认退出时同时退休加密实例记录与旧 PID 记录，避免下次打开仍停在“待确认”。
- 核对旧实例期间点击启动会记录本轮意图，完成后自动续接一次；停止、新代次或已接回时不会重复启动。
- 身份已确认、鉴权短暂失败时最多复核 3 次（500ms/1500ms 有界等待）；仍不通过才保留未确认状态。
- 启动页按原因显示恢复连接、启动器仍在退出、记录不可读、鉴权暂不可用等状态与入口；核对期间启动按钮禁用，重启/安全启动入口仍可点击，会记录本轮启动意图并在完成后自动续接一次。
- 版本码提升为 212；构建与验收范围以本 run 与 Release 回执为准。
- 已在 OnePlus PJD110 / Android 16 安装正式包并通过包名、版本、APK 摘要与签名核对；确认退出后重新打开直接可启动，未再出现“待确认”。逐项步骤、范围与限制见[build212 恢复验收记录](docs/releases/v0.2.1-rc.3-build212-recovery-20261030.md)。
- 十语种更新说明见[build212 更新说明](docs/releases/build212-changes.zh.md)。

## 0.2.1-rc.3-20261030.1245-dsh0.2.1-rc.3 / build211（插件进度条与下载源说明）

- 插件页进度条改为 F 方案：不确定态呼吸、确定态流光，进度只跟随真实 percent。
- 任务成功显示绿色 100% 并停留 700ms；失败显示红色并停在本次任务的最大进度，停留 900ms 后淡出。
- 下载源常驻长说明移入右侧圆形感叹号按钮弹窗，弹窗同时显示最近一次任务结果或报错。
- 插件结果新增显式成功/失败状态：队列已满、后台执行器不可用、文件选择错误等入队前失败也能在弹窗中查看。
- 两处下载源共用同一进度条；补齐深色/浅色 token、减少动效回退与 TalkBack 进度语义。
- 十语种更新说明见[build211 更新说明](docs/releases/build211-changes.zh.md)。

## 0.2.1-rc.3-20261030.0214-dsh0.2.1-rc.3 / build210（内置移动插件同步与门禁修复）

- 内置 dsh-web-mobile 同步到上游最新内容（main @ 04dcd19，版本仍 3.2.2）：新增平板触屏档、文件下载入口与抽屉快捷设置，本地补丁按新上游重新对齐。
- 修复正式包发布校验误报：aapt2 新版本把最低 API 打印为 minSdkVersion，正式包不再被误判为 minSdk 不符。
- 测试包 versionName 后缀回到 -SunsetTest，更新页重新识别本机测试身份。
- 发布工作流的仓库/分支门禁改为当前仓库名与 main，仓库改名后不再被跳过。
- 源码门禁补齐：局域网文案进入十语种目录，语言入口与 DNS 下拉补可访问性属性，弱提示文字与按压态对比度达到 4.5:1。
- HarnessController 门面拆分：打包资产读取移入 PackagedAssets，一键备份不再直接依赖核心配置。
- 宿主回归 14 项全部转为通过；逐项说明与十语种版本见[build210 更新说明](docs/releases/build210-changes.zh.md)。

## 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209（DSH-A-Su 身份与构建改造）

- 应用名改为 DSH-A-Su（Sunset Mod），applicationId 改为 io.github.sunsetrne.dsha；与原版 dsh.android.client 并行安装、数据完全隔离，不覆盖旧版数据。
- 发布签名新建：不再使用历史 E7E3 证书，改用 Mod 自有发布证书；verifyPublishCertificate 改为校验新证书与新包名，期望哈希记录在 ci/release-identity.json。
- 上岛变体并入正式包：上岛能力进入常驻包，不再单独出包，也不再有独立包名。
- 放弃 Android 10 及以下：删除 low / GeckoView 兼容线，最低 Android 11（API 30），仅 arm64-v8a。
- 无障碍声明改为多变体共用：声明放在 main manifest，不限定 packageNames，也不再由 flavor 覆盖；标签与描述更新为「DSH-A-Su 屏幕控制」并精简。
- 仍保留 zh、zh-Hant、en、ru、ja、ko、ar、pt、la、fr 十种语言。
- fr、pt、ru、ar、la 的界面短标签进一步精简，减少占屏。
- 修复拉丁语（la）「下一步」的损坏文案。
- 固定构建输入改为公开仓库的预发布 Release 资产，以 SHA-256 锁定；公开仓库可直接还原完整构建输入（见[固定构建输入文档](docs/fixed-build-inputs.md)）。
- 包名与证书相关的发布工具、CI 断言同步更新；构建与门禁结果以仓库回执为准。
- 逐项说明与十语种版本见[build209 更新说明](docs/releases/build209-changes.zh.md)。

## 0.2.1-rc.3-20261029.0741-dsh0.2.1-rc.3 / build208（合并上游修复）

- 修复带键盘切后台再回前台后输入区停在键盘占位矮视口的问题：回前台核对输入法真实可见性，不可见时清除键盘边距并强制一次布局（#190 原生侧）。
- 修复手机控制把 DSHA 自有悬浮窗（浮条、助手、确认窗）误判为目标遮挡的问题：未登记的非焦点自有悬浮窗按包名身份整窗排除，不再返回 TARGET_WINDOW_OCCLUDED；穿过排除区的坐标轨迹仍被拒绝，悬浮窗完全盖住目标时依旧无可点面积（#210）。
- 硬链接不可用的设备上，实体化旧 pnpm 内容链接的全量复制不再被固定 120 秒超时在中段终止；超时改为 15 分钟兜底真正的挂死，避免复制中断把插件事务留在 pending 卡死下一次启动（#209）。
- 本版同时包含 build207 的 Root 命令三态拦截与上岛版通知增强；仅源码合并，未构建、未安装、未做设备验收。

## 0.2.1-rc.3-20261029.1500-dsh0.2.1-rc.3 / build207（Root 命令三态拦截）

- 新增 Root 命令三态拦截：严格（默认，仅白名单）、放宽（任意命令，但拦截格式化/刷机/重启/清数据等不可逆操作）、完全放宽（不按命令内容拦截）；入口在设置 → 设备能力授权 → Root 命令拦截。
- 放宽与完全放宽只对 Root 通道生效；Shizuku/ADB 通用执行器仍按严格档，短信读取在三个档位都需要原生授权。root 原始命令仅由 uid 0 特权子进程执行，保留超时、256 KiB 输出截断与执行身份校验。
- 严格档补充递归删除存储根和一级目录的拦截；新增 Java 三态/删除边界测试与 guest SHELL 计划测试，Python 与固定格式门禁通过。
- 修复“Root 命令拦截档位”弹窗只显示说明、没有可选项的问题；档位改为独立列表项并合并说明，短屏和 TalkBack 可直接选择。
- 本包为 SunsetRNE 测试身份（非 E7E3）、固定测试签名；按用户要求卸载旧 build206 SunsetRNE 后安装。真机已验证 strict/relaxed/full 与三档短信闸门，范围见[本轮报告](docs/releases/v0.2.1-rc.3-build207-root-policy-20261029.md)，未覆盖全部 ROM/机型。

## 上岛版问答入口（2026-10-28，待构建）

- 点按等待确认的通知/胶囊：直接进入对应会话，页面就绪后把提问卡滚进可视区并聚焦回答框；不自动全屏。
- 岛/通知快速回答：`ask_user_question` 的单问题、单选、1–3 选项显示按钮；点按后经本机一次性令牌端点回答，无需先打开 App。
- 上报端识别 `ask_user_question`（兼容旧名），上报问题文本与选项；`tool/result` 立即撤销“等你确认”。
- 新增 `AgentTaskAnswerReceiver` 与本机回答端点；答案按当前真正待答的问题校验，网页先答/问题结束/选项不匹配/令牌过期都会被拒绝。
- 多选：通知选项按钮变为切换（✓），再点「提交」一次性回传多个标签；状态按任务隔离。
- 自定义答案：通知增加「自己写」（RemoteInput）直接输入；单选时自定义覆盖已选项，多选时与已选项一起提交。
- 多问题或超过 3 个选项：通知只提供「打开回答」，进入会话回答（网页卡片本身支持自定义答案）。
- 锁屏公开版本仍只显示阶段，不含问题文本与选项。
- 修复通知按钮「能看到但点不动」：运行中不再把秒数写进文本，心跳不再每秒重绘通知；
  耗时交给系统 when/计时器，通知内容保持稳定，按钮不再被重绘替换。
- 验证：问题载荷/端点的上报端回归实跑通过；状态机单测与 util+core 本地套件通过（1 项洪泛夹具负载抖动，单独重跑通过）。未真机验收。

## 上岛版任务状态修复（2026-10-28，待构建）

- 修复状态不更新/卡住：上报端在任务存活时每秒心跳；页面身份瞬时不可用不再把任务判死；静默 15 秒先显示「状态暂不可确认」，60 秒按连接中断收敛结束，恢复心跳可复活；丢失 `turn/end` 时 3 秒内按停止/完成/被替换补终态。
- 修复没有完成态：任务结束后通知保持 ongoing 8 秒并显示「完成 / 已停止 / 已达上限 / 已断开」，随后主动取消；非选中任务不再留下永久残留的终态通知。
- 新增细节：展开通知与「Agent 任务」页显示工具名与目标、会话标题、轮次、当前阶段耗时与总耗时；命令只上报 argv[0]，锁屏公开版本仍只显示阶段。
- 修正 36.0/36.1 提升判据：按平台是否存在 `setRequestPromotedOngoing` 区分两代模型，权限查询独立；36.0 设置页显示「无法确认」而非误报未授权。
- 上报端插件字节变化，已重算 `runtime-descriptor.json`；新增状态机和胶囊文案单测。
- 验证：`AgentTaskStateTest`、`IslandTextTest` 通过，Standard Debug Java 编译通过；上报端本地合成回归通过。未做真机验收。

## 0.2.1-rc.3-20261028.1200-dsh0.2.1-rc.3 / build206（本地源码整合）

- 同步私有主分支 `codex/prerelease-190` 的 build206；保留终端“更多…”面板、输出预览/复制/分享/保存、响应式侧栏与缩小后的悬浮球。
- 修正长输出和可见范围裁切丢失多行脱敏上下文的问题；先识别完整保留上下文，再导出所选范围。无法安全生成预览时不输出正文。
- 修正侧栏打开时终端继续持有输入焦点、列表刷新后会话焦点丢失，以及短窗口重排把聚焦按钮滚出可见区的问题。
- 同步版本页中英文回归断言与 build206 十语说明，更新源码版本入口。既有环境身份修改保留，未把上游历史结果当成本次验证。
- 本轮仅本地源码和软件检查，未构建、安装或发布 APK，未运行 Low；Android 界面、CI 与设备验证的实际范围见[本轮记录](docs/source-updates/20261028-terminal-build206-followup.md)。

## 0.2.1-rc.3-20261028.1200-dsh0.2.1-rc.3 / build206（UI v5 补全，同版本第二轮）

- 插件页：卡片行改为图标 + 名称/说明 + 真实多行状态 + 状态点/更多/展开/开关；已安装筛选收进 v5 卡片，分段换轨道滑块；首屏逐项淡入、切换回弹。
- 设置二级页：运行配置、设备能力授权、自检与诊断、关于改走 `shell_page` 组件层（标题/分组/卡片/输入/开关/按钮），重排运行配置与授权分组；保留全部 id、点击与二次确认。
- 动效：定位内页 Activity 统一 260ms 页面淡入上移；居中弹窗 220ms 上移缩放、底部弹层 260ms 上滑；启动检查遮罩改 v5 卡片并在完成后淡出；启动状态点按真实启停状态呼吸。
- 终端侧栏：抽屉打开 300ms + 遮罩 200ms，关闭用出屏快照 220ms；会话行、分组计数、虚线空态、2 列动作用 10–11dp 圆角与软危险「结束全部」对齐原型。
- 不改 versionCode/versionName、包名与签名；仅 Standard。手机安装与真机渲染、TalkBack、字体缩放、横竖屏未验收。范围与边界见[本报告](docs/releases/v0.2.1-rc.3-build206-ui-completion-20261028.md)。

## 0.2.1-rc.3-20261027.1149-dsh0.2.1-rc.3 / build204（源码合并）

- 合并 Sunset build203 的 v5 页面、全屏终端与会话侧栏改进，保留手机与平板布局、附加键以及终端首次点击相关修复。
- 保留 build198 对全局授权切换的修复，以及电脑工作区、扫码配对、连接器和中继的撤除；手机控制、本机与局域网访问保留。
- 虚拟屏启动支持可选 generation 的启动前状态核对；实际原生请求仍只带 coreInstance 和 package，状态预检不是原子代次围栏。
- 统一状态栏安全区并保留十种语言。内置 dsh-web-mobile 3.2.2、DSH rc.3；本轮只做源码合并，手机验收继续暂停。
- 下列 build202、build203 或其他历史包的测试、安装和设备记录只属于其各自构建，不能作为 build204 验收。合并范围以最终源码与后续实际验证为准。

<!-- build202-sunset-history -->
## 0.2.1-rc.3 · build202（2026-10-26，Sunset 独立测试分支历史）

- 界面：把 v5 交互原型的外壳原生化——顶栏品牌/页面语境、手机悬浮胶囊导航、平板 80dp 左侧导航栏、启动主页的语境/大标题/活动条/三格状态/快捷行与日志复制/跟随/导出；插件与设置沿用现有真实功能，不使用原型模拟数据。
- 终端：合入 `Sunset-zhongdua-G` 的全屏终端宿主与会话保护，点击「终端」拉起独立全屏 `TerminalActivity`，返回只关闭页面、不结束会话；侧栏三向态、手机 7+7 / 平板 8+8 附加键与横屏三列两行压缩随构建合入。
- 版本码 202；测试身份 `dsh.android.client.SunsetRNE`，本机测试证书签名（非 E7E3）；内置 `dsh-web-mobile` 保持 3.2.2。范围与验证/APK 摘要见本轮报告。
- 2026-10-27 后续：把 v5 页面组件层铺到设置、插件、运行配置、安装与环境、设备授权、数据与备份、更新、诊断、欢迎/首启、关于与恢复页；新增 `shell_page.xml` 与 `bg_shell_*`，旧内页跟随通用层级色。终端修复侧栏新建/结束会话与“回到输入”首击被触摸焦点吞掉的问题，并移除侧栏白天/黑夜/系统主题分段。APK SHA-256 `a72d0251…ec45`，详见 [替换报告](docs/releases/v0.2.1-rc.3-build202-ui-replacement-20261027.md)。

<!-- build198-sunset-history -->
## 0.2.1-rc.3 · build198（2026-10-26，Sunset 独立测试分支历史）

- 版本码更新为 198；Sunset 独立测试包 applicationId 改为 `dsh.android.client.SunsetRNE2`，正式包 `dsh.android.client` 不变，可与正式版及旧 SunsetRNE 测试版共存。
- 终端悬浮球和工作台“菜单”改为打开会话侧栏：极简/窄工作台用抽屉，≥840dp 工作台内联常驻；侧栏集中新建、切换、重命名、结束核验、返回、显示设置、键盘、粘贴、输入焦点和说明。
- CI run 40248037705 / commit 7eaf914 成功产出 Standard 测试 APK：2519 项测试 0 失败/0 错误/1 允许跳过；APK SHA-256 `dad7d25983f1f7a783d130c0bcad08ad9f53cca7c77fa3d5079b614bd2c231d1`，测试证书非 E7E3。未安装、未操作手机、未做设备验收。
- 后续（2026-10-26）：终端底部附加键改为手机 7+7 / 平板 8+8 两套可自定义布局，显示设置或长按进入编辑；单行自动缩字、固定 48dp 行高，保存只刷新当前 PTY。侧栏三向态与图标化 HTML 预览同步更新。CI run 40282161255 / commit 1ad0290 成功：2545 项测试 0 失败/0 错误/1 允许跳过；APK SHA-256 `ace50b5828a37d8cf173ff7a44c019a51a5fe69759ea400c5032b1fa842a181b`，临时证书 `57855eb3…b049`（非 E7E3）。未安装、未操作手机；原生主题尚未落地。

## 0.2.1-rc.3-20261025.2100-dsh0.2.1-rc.3 / build198（历史软件候选）

合并Sunset19提交；修复授权切换隐藏暂停；撤除整套电脑连接；统一顶部安全区及解耦活动采样等待。Standard软件检查通过，7项Windows链接权限限制已用POSIX补验；用户暂停手机验收，设备仍197。完整范围见 `docs/releases/v0.2.1-rc.3-build198-20261025.md`；不把候选当作已发布或已验收。

<!-- build197-current -->
## 0.2.1-rc.3 · build197（2026-10-25）

- 统一最近任务与主页返回，补齐LAN状态/地址，手机控制接入当前对话。
- 内置十语、系统语言匹配、阿语RTL和自适应主提示，保留用户内容与自定义提示。
- 修复IME观察取消模型、过时poll和权限/进程恢复后的空白主页。
- Standard505项、Release Lint与精确APK检查通过；Android13实际覆盖、模型工具确认/结束、返回复用和正常停止范围见[本轮报告](docs/releases/v0.2.1-rc.3-build197-20261025.md)。仅私有交付、不跑Low。

## 0.2.1-rc.2 · build190 / 插件速度维护2100（2026-10-22）

- 插件页使用独立轻量能力查询，来源证明以两个有界worker执行；保留完整校验、实际加载状态和结果顺序。
- 修正新增worker与旧策略补丁重入冲突，补生产Java升级及精确APK检查；2020失败候选不交付。
- PR119已有画中画修复继续保留并补22项实际布局/策略回归；默认仍关闭。
- 44类每版332项、17宿主及APK7通过；手机有效开关反馈到确认约3.10–3.39秒，整页结果波动，详细范围与未修像素缺口见[本轮记录](docs/releases/v0.2.1-rc.2-build190-ui-performance-20261022.md)。仅私有维护交付。

## 0.2.1-rc.2 · build190 / UI与息屏维护（2026-10-22）

- 修复插件配置写锁/HMR反转；合并受控开关后的重复列表读取，列表不再等待未使用的预设诊断，保持实际加载状态与外部变更刷新。
- 会话改为单击切换；消除侧栏背景按压闪屏与插件/预设等待提示挤动布局，旧预设V1升级通过真实补丁链恢复。
- 可见启动同步申请前台保护，等待真实提升，补齐超时、停止、安全恢复及服务损失的精确代次收尾；电池设置入口不再发起缺权限的直接豁免请求。
- `0.2.1-rc.2-20261022.1817-dsh0.2.1-rc.2` / code190 / `dsh.android.client`；两版关联42类各310项零失败/错误/跳过，14项宿主和APK门禁通过，Lint警告248/161保留。保留原失败与逐次修正，最终手机及限制见[本轮报告](docs/releases/v0.2.1-rc.2-build190-ui-performance-20261022.md)和[English](docs/en/releases/v0.2.1-rc.2-build190-ui-performance-20261022.md)。仅私有维护交付，公开发布继续暂停。

## 0.2.1-rc.2 · build190 / F13（2026-10-21）

- 真实目录控制器独立组合，保留后端/Gateway、Config/name与named类行为，不删除工作区、会话或附件。[操作与边界](docs/plugins.md)。
- FileUploads以精确注册对象退休，避免比较Cordis包装后的回调；保留重复拒绝、旧disposer围栏、上传接收和Session回执。[运行机制](docs/runtime-data-plugin-architecture.md)。
- 区分LIVE/UNKNOWN/EXITED；UNKNOWN保留同DOM/Gecko会话与原生状态面板，不授ready。仅同record/代次/实例/URL/端口通过kernel→HTTP→kernel后创建新registration，旧票不复活，不重发对话或后台启动。[启动与恢复](docs/startup-and-diagnostics.md)。

- Standard选定150类/952项JUnit（3跳过），Low选定150类/952项（76跳过），两版零失败/错误；Release Lint零错误、Standard/Low警告249/162，八项实际APK检查通过。原始失败/纠正/预期负控分别保留，不称一次连续全绿；无设备/ADB、真实账号/模型和新长时验收。[F13记录](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md) · [English](docs/en/releases/v0.2.1-rc.2-build190-fix13-20261021.md)。

下方旧条目的“当前/本轮”按其原时点阅读；已发布F12原记录和URL不改，F11未上传。

## 0.2.1-rc.2 · build190 / F12（2026-10-21）

- 修复插件全依赖同步扫描导致列表与DSH通道阻塞；改为真实bundle筛选、完整异步来源证明、短启停读回及独立刷新，保留事务与未知退出屏障。
- 修复严格恢复既有Web后LAN cookie未发布，以及首次说明响应丢失/Host晚保存/明确revision冲突的处理；未保存不假报成功。
- 保留自动Web端口；说明本机端口复用、origin变化及同WebServer OAuth临时回调路由，不把ADB端口与Web端口混用。
- 补齐分发文档与完整现行英文版。148类/939项每flavor，零失败/错误；Lint零错误，八项实际APK检查通过；无本轮真机/真实账号/新长时验收。见[F12记录](docs/releases/v0.2.1-rc.2-build190-fix12-20261021.md)和[English](docs/en/releases/v0.2.1-rc.2-build190-fix12-20261021.md)。

## 0.2.1-rc.2 · build190 / F11（2026-10-21，当前私有维护版本）

- 当前诊断版本 `0.2.1-rc.2-20261021.1157-dsh0.2.1-rc.2` /190；DSH0.2.1-rc.2、移动UI3.2.1、Ubuntu base10、默认proot/可选proroot及正式包名/E7E3保持。最终软件及精确APK结果见下；附件摘要与远端状态由关联Release及独立外部回执核验。
- 修订LAN后端端口、业务拒绝、回调路径和网络变化连接收尾；适配官方账号授权在内嵌浏览器的异步导航及首次说明Host确认。宿主恢复只识别同一活跃Web，不启动或重放业务；未知证据保留停止屏障及原生工作台。
- 插件按APK受管proof和实际完整内容区分签名、受管、用户/未验证来源；不再凭npm scope、前缀或安装目录漏备用户包。普通失败临时候选与UNKNOWN保留现场分开说明。
- 补齐21篇实际新Markdown和具体操作/权限/失败文档；源码分发清单2135成员/135MD，Java adoption1023。完整源码ZIP继续直接展开固定输入；最终冻结范围以回执为准。
- 本轮暂停设备、ADB、真实账号/模型对话和四/八小时验收；Star快照1827属于原仓库。详见[F11范围与实际结果](docs/releases/v0.2.1-rc.2-build190-fix11-20261021.md)。
- 最终增量构建两版各143类/881项，零失败/错误；Standard/Low跳过3/76。两版Lint零错误、249/162警告，八项实际APK检查通过；原始失败、复核负控及宿主端口占用限制见F11记录。

## 0.2.1-rc.2 · build190 / F10（2026-10-21，已发布历史）

- 以build170为基座迁入后续全局授权、虚拟屏等功能，内置DSH0.2.1-rc.2；按本轮用户决定恢复默认proot，同时保留显式proroot选择。此项覆盖下方DSHA v1.1.6的历史默认，不是宣称proroot上游版本为v1.1.6；当前vendor锁v1.2.8。详见[运行引擎与迁移](docs/runtime-engines.md)。
- 键盘动画减少逐帧整页重排，移动插件3.2.1固定main10e58ce；新增直接保存Download/DSHA的一键无密码备份，先安全停止DSH/任务，不自动重启，外置工作区内容另备。
- 新增项目框架、设备权限、运行环境/数据/插件、引擎、端口、DNS、虚拟屏架构和ADR、官方资料与来源索引；当前配置、历史性能/默认及未运行范围分开。
- 默认Web端口自动选择；备用只在本机记忆、不跨设备迁移。auto/ipv4对Node做特定lookup兼容，ipv4另加glibc no-aaaa，详见[端口](docs/ports-and-networking.md)与[DNS](docs/dns-configuration.md)。
- 软件双版各785项零失败/错误（Standard3、Low76跳过）、两版Release Lint零错误且有警告，正式Standard APK七项实际核验通过。F10当时未验手机；准确包与源码归属见[F10历史发布记录](docs/releases/v0.2.1-rc.2-build190-prerelease-20261020.md)。


## 0.2.0-rc2 正式版 · build170（2026-10-09）

- 由贡献者 [@SunsetRNE](https://github.com/SunsetRNE) 发布正式 Release / Latest；内置 DSH 0.2.0-rc.2、移动 UI 3.0.5，沿用包名和 E7E3 历史签名。对比上一正式版及下载见[发布说明](docs/releases/v0.2.0-rc2-notes.md)。
- 累积插件自动启用与开放依赖安装、无密码 tar.gz 导出、“数据与备份”入口整理、首次安装并行解压与离线工具准备；保留旧备份和数据兼容读取。
- 修复附件直接文档选择、多选与图片草稿保存/恢复、ADB 私有路径及冷目录准备、聊天 shell 的 /tmp；增加网页原生分享/保存、虚拟屏指引、LAN 错误说明与日志显示整理。
- 两版各45项关联检查与 Release Lint、Android13 正式包非破坏性验收通过；历史冷安装42.412秒未达30秒，未扩大无线配对、原vivo16或旧Android/16KiB验收范围。详见[build170](docs/releases/v0.2.21-build170-20261009.md)。
- 发布页仅上传两 APK 和各自校验文件；更新现有中英文主页与 Star History，历史 Release、标签、赞助和致谢信息保留。

## 0.2.7-20261003.1944-dsh0.2.0-rc.2 / 156（2026-10-03，本地修订）

- 重新核对全部236个审计编号，补修旧台账遗漏的凭据导出、特权文件竞态、取消传播、原子记录发布、CI回执和测试夹具问题；逐项事实与实际检查见[本轮交付报告](docs/releases/v0.2.7-build156-20261003.md)。
- 原生v5备份与恢复投影浏览器机器凭据，使用锁定的宿主YAML解析并保留用户provider语义；新导出继续无需密码，历史密文与原件读取继续保留。
- 拆分Web生命周期、配置、LAN、插件脚本、安装、维护工具与PTY职责，应用状态和运行端口按应用实例持有；停止采用稳定事实，显式强制停止仍需用户确认和出生身份核验。
- 原生独立会话启动增加自读出生身份握手；迁移按源与回执证明复用，移动响应改为有界异步压缩，rc2 tooltip补齐清理行为。
- 收敛371处应用文案片段及界面无障碍问题，退休无消费者的生产资产，修复历史原字节的Git属性保护，完善正式依赖/许可与真实夹具门禁。
- 保持正式插件自动启用、开放依赖和终端、独立应急修复确认、未知退出屏障与原包名/E7E3签名。用户追加授权连接真机的非破坏性正式包验收，实际结果与设备范围见本轮报告；未上传GitHub或部署网站。

## 0.2.6-20261003.1704-dsh0.2.0-rc.2 / 155（2026-10-03，本地交付）

- 应用范围统一持有 Web 生命周期状态，桥接路由显式登记；冷安装候选、解压与工具准备拆到协作者，共用有界进程输出轮询。
- Low 预览依据实际能力有限回退；DNS 临时不可用时保留上次有效受管服务器，默认错误报告减少原始日志暴露。
- 自有 Java 的 702 个适用文件通过锁定格式与 token 检查，机器生成类继续严格逐字节验证；两版完整 JUnit、Lint、APK/ELF、E7E3 签名、插件与应急门禁见[build155 本地交付报告](docs/releases/v0.2.6-build155-20261003.md)。
- 本轮未操作手机、执行覆盖安装、上线 GitHub Release 或部署网站。

## 0.2.5-20261003.0922-dsh0.2.0-rc.2 / 154（2026-10-03，本地交付）

- 完成 236 项外部审计按本轮用户约束的逐项处置，拆清启动、维护、冷安装、备份与虚拟屏状态边界；保留现行免审阅插件、开放依赖与终端、无密码用户导出及历史数据读取策略。
- 两版各 1023 项完整单测（0 失败、1 跳过）、Release Lint、正式 APK/ELF、E7E3 v1/v2/v3、插件和独立应急门禁通过；细节与剩余范围见[build154 本地交付报告](docs/releases/v0.2.5-build154-20261003.md)。
- 本轮没有操作手机、执行覆盖安装、上线 GitHub Release 或部署网站；本地网站页面及下载包已按最终 APK 生成并通过 27 项检查。

## 0.2.4-20261002.2238-dsh0.2.0-rc.2 / 153（2026-10-02，本地交付）

- 修复 152 新软件包摘要校验对 Context 平台路径别名误报 PARENT_LINK；只规范化框架提供的应用根，继续拒绝 files/guest 子链接并检查目录身份与 UID。
- 修复重建 begin 在旧目录移动后失败时，回切责任尚未登记的缺口；原件、数据保护副本和失败候选继续保留。
- 应急诊断明确区分环境就绪、当前 linux 状态与待处理事务；没有待处理事务不再被误读为环境就绪。

## 0.2.3-20261001.2129-dsh0.2.0-rc.2 / 152（2026-10-01，本地交付）

- 针对华为 Android16 日志中的 proroot Bash EINVAL / proot mkdir ENOSYS，冷安装先做能力探针，支持关闭 proot seccomp 加速的兼容方式；正式软件包安装一次，退出未确认不切换，成功运行方式保存后才发布就绪。
- 用户备份及副本导出统一无密码 tar.gz，移除新密码和加密选项；内部自动副本导出由宿主解密，旧手动加密原件只在读取时需要原密码，原件保留。
- 修复第三方插件修改 package.name 后，旧启用记录被误记为成功加载的身份核验缺口。
- 保留前后台连接恢复，并验证待确认请求不会被重连重复提交、取消或丢弃。

## 0.2.2-20261001.2046-dsh0.2.0-rc.2 / 151（2026-10-01，本地交付）

- 备份导出统一为真实标准 `.tar.gz`，保持可选密码、v5 完整性与实际备份范围；兼容旧裸 dshbak / tar.gz，导出选择过程中进程回收后要求重新发起。
- 修复 WebView / Gecko 前后台切换后输出连接卡住：恢复时合并重建官方 Connection 和物理 WebSocket，重新同步已产生输出，不重发请求。
- 可见页面为已运行的本机对话补齐前台服务，不依赖 LAN；检查保活线程实际状态，晚到的启动服务消息再次核验手动停止。
- 版本码 150 源码已由 @SunsetRNE 上传至 v0.2.1-build150 标签（72a7af2）。

## 0.2.1-20261001.1835-dsh0.2.0-rc.2 / 150（2026-10-01，本地交付）

- 优化原生插件读取与启停，避免重复发现、注册和依赖树散列；支持下载的第三方插件本地编辑后重启加载。
- 修复 #83 随 DSH 安装提供的可选 bundle 误报缺失，以及 #82 旧预设顶层文件导致迁移失败；成功迁移原始回执不再反复显示。
- 修复 Web 对话带草稿/附件时无法停止，以及 Android MCP 截图路径跨用户不可读和截图资源释放。
- 修复 #84 主题无法切回跟随系统，#86 反向读屏节点占用输出配额；保留零面积文本。
- 版本名分开记录 DSHA 修订号、固定北京时间戳和 DSH 版本；文件名沿用现有名称。两版继续 E7E3 / v1+v2+v3。
- 用户要求本轮跳过真机测试与覆盖安装；仅声明实际软件检查结果。

## 0.2.0-rc2 · build 149（2026-09-30，本地升级版）

- 内置 DSH 升级至 `0.2.0-rc.2`，锁定依赖树、会话兼容读取和受管运行时描述已同步更新。
- 保留 0.1.7 rc1/rc2 数据读取兼容；覆盖安装沿用 `com.dsh.client` 与 E7E3 历史签名，版本码递增到 149。
- 按用户要求开放终端修复 shell、未锁定插件依赖安装和生命周期脚本；插件事务、目录/摘要核验和回滚边界继续保留。
- Standard/Low Release 均使用 E7E3 证书，并完成 Release 构建与覆盖安装交付检查。

详见 [0.2.0-rc2 升级记录](docs/releases/v0.2.0-rc2-20260930.md)。

## 0.1.7-rc2.1 · 2026-09-30 维护修订

- 备用 DSH 在检测到受管 CLI/Node 依赖缺失时可提出 `repair-cli-dependencies`，由宿主从签名 APK 恢复并经过原有停止屏障、摘要核验和隔离试运行；不开放任意 shell、未锁定安装或直接写用户 `node_modules`。
- 队列卡片的插话箭头在回合结束后仍可触屏操作；空闲时改为唤醒新的 follow-up 回合，运行中继续使用 steer，保留忙状态防重复。
- 手动/救援备份改为密码可选；勾选密码时继续使用 v5 AEAD，留空时写入带完整摘要校验的 DSHADATA v5 容器，旧加密备份仍可恢复；自动副本仍使用内部随机密码加密。
- ADB 已验证探活后，若系统仍保留用户开启的无障碍服务但 ROM 已解除绑定，后台请求一次受控重绑定；用户手动关闭服务后不会重新开启，并补齐服务解绑状态清理。

本轮未执行真机功能回归；两 flavor 的单测、Release Lint、E7E3 签名校验和 APK 打包已完成。交付摘要见 [rc2.1 维护修订记录](docs/releases/v0.1.7-rc2.1-maintenance-20260930.md)。

## 0.1.7-rc2.1 · build 148（2026-09-29，本地版）

- 修复插件已下载却停在“待审阅”、无法启用的问题。链接和本地包解析后自动安装并启用，更新和回退也不再要求二次审阅；运行中的 Web 在提交后自动重启以加载插件。
- DSH 网页插件管理允许直接安装和启用第三方插件，网页安装默认启用。升级时只解除旧版自动写入的 `DSHA_REVIEW_REQUIRED` 标记，保留用户主动禁用、安全模式和安装事务。
- Web 在浏览器握手前被重启时，已安装插件复核内容后继续加载，不再误判为“加载未确认”而停用；手动重新启用会重建加载确认记录。
- 修复插件管理搜索框收到键盘回车时可能因焦点转移而崩溃的问题。
- 仍核对插件包格式、路径、摘要和依赖，禁用依赖安装脚本；第三方插件运行时共享 DSHA 的应用权限。

交付摘要与实际验证范围见 [rc2.1 本地交付记录](docs/releases/v0.1.7-rc2.1-build148.md)。

## 0.1.7-rc2 · 正式版 / build 147（2026-09-28）

- 以贡献者身份发布正式 Release / Latest，沿用已验收的 Standard/Low APK、包名及 E7E3 历史签名；同步对应版本源码。
- 相对最近的 0.1.7-alpha2：DSH 升至 rc.2，内置移动插件 3.0.3，新增独立应急 DSH 与按需麦克风授权，完善维护、插件事务、终端、文件访问和授权生命周期。
- 修复插件安装重复加锁卡死，增加自动/npm 官方/npmmirror 下载源、最多三路并发更新查询、锁定版本缓存复用及有限回退。
- 修复旧系统安装退出状态误报（#79），补齐后台 LAN 前台服务与监听恢复（#80）；原 issue 设备与长期 Doze 未在本轮实机覆盖。
- 两版各 807 项单测（806 通过、1 项既有跳过），Release Lint 与 APK/ELF/签名等门禁通过；Android 13 真机各完成 11 项非破坏性验收。
- 更新中英文主页中的后续版本介绍，保留 1.1.10 及更早历史内容和其他贡献者信息；刷新 Star History。

详见[正式版说明与历史版本对比](docs/releases/v0.1.7-rc2-notes.md)。

## 0.1.7-rc2 · build 147（2026-09-25，本地 Release 构建）

- 内置 DSH 更新至 `0.1.7-rc.2`，同步定时任务、快捷键、进行中会话启用新工具、自动审阅流程和插件源改进。
- 修复插件详情/设置页信息缺失、插件安装或设置保存中断后的恢复，以及部分长对话无法继续发送。
- 保留 rc1 的迁移保护、V4 会话链、原件快照、原生插件审阅和同签名覆盖安装；rc2 上游已接管的 Agent 预设标签与模型入口补丁不再强行覆盖。
- Standard/Low Release APK 使用 E7E3 历史发布证书，版本码 147；交付文件位于 `release`。

详见 [rc2 build 147 记录](docs/releases/v0.1.7-rc2-build147.md)。

## 0.1.7-rc1 · build 146（2026-09-23，本地 Release 构建）

- 内置 DSH 更新至 `0.1.7-rc.1`，同步 rc1 的 Session V4、Profile 设置、Agent preset 组合包、Messages-only 适配器和插件兼容检查。
- 覆盖更新前新增 rc1 迁移保护：保留旧 settings、Profile/lock/compatibility 声明、旧 `.agent-presets`、会话源摘要；settings 导入失败可安全重试。
- 旧插件源码同名冲突、缺失依赖和旧 preset 只进入隔离候选，保留当前版本和原声明，不自动启用。
- 对齐 rc1 的内置插件 peer 版本，并修复 subagent 导航补丁锚点；M367FC 真机覆盖后已正常进入 Web。
- Standard/Low Release APK 使用 E7E3 历史发布证书，版本码 146；交付文件位于 `release`。

详见 [rc1 build 146 记录](docs/releases/v0.1.7-rc1-build146.md)。

## 0.1.6-alpha2.1 · build 142（2026-09-21，本地候选）

- 修复 WebView/Gecko 画中画期间流式文字和字体闪烁；PiP 过渡期间保持渲染，退出后再恢复全屏刷新率。
- 数据与备份页的自动备份、存储空间入口改为固定统一卡片布局；Language 弹窗改为一次性窗口测量并延迟语言重建。
- 移植插件列表标题的相对 FAB 让位规则，避免窄屏/平板触控布局重叠与横向溢出。
- 新增 Android 11+ 独立虚拟屏：ADB/Shizuku/Root 通道、应用内/悬浮预览、已安装应用启动、最新帧校验的 AI 点击/滑动/输入/按键工具。

两版 E7E3 正式 APK 已完成签名、资产、Lint 和手机覆盖验收，并写入 `release` 的 alpha2.1 常规文件名。

## 0.1.6-alpha2 · build 141（2026-09-19，本地 Release 构建）

- 修复 DeepSeek Messages 在 Agent Team、子代理投递及 compact 重放中遇到嵌套 `tool-call` 时的 `UNSUPPORTED_CONTENT`；普通工具调用、错误结果和图片结果的协议语义保持不变。
- 系统插件与用户插件分层：备份只归档用户插件源码及依赖，系统插件只记录启停意图并由当前 APK 重建；旧实体与 `.disabled` 冲突时隔离旧字节，避免覆盖更新后继续加载旧插件。
- proroot JSON `per-record` 兼容升级为 V2，按声明表名与字符串目录项重开，不再依赖 `DT_UNKNOWN` 下不可用的 `lstat`。
- 受管试运行记录轮换为最近 3 条成功、5 条失败；未关闭现场永不自动删除，连续 33 次试运行仍可继续。
- 格式化对 WebView、`cache`、`code_cache` 等可再生目录的持续 `ENOTEMPTY` 给出明确警告；会话、插件、配置、API Key 与运行时等核心目录仍严格失败。
- 修复 proroot 真实 `--argv0 /usr/local/bin/node` 未被停止器识别而遗留隔离试运行进程；旧记录绑定的安全 profile 经过两次内核身份核验后可回收。维护状态、安装日志和启动日志改为低频/按修订渲染，网页兼容脚本与成功事件不再重复注入或累计。
- Web 工作台按用户刷新率上限请求高刷，省电模式自动交还系统；滚动位置改为逐帧合并、延迟落盘，触屏插件不再因每个流式消息节点而全页扫描。M367FC/API 37 实测 120 Hz、733 帧滚动 0 dropped / 0 jank、平均 114.1 FPS。
- Standard/Low 各 613 项强制全量单测、Release Lint、插件覆盖升级门禁、E7E3 正式 APK/ELF 审计，以及 M367FC/API 37 平板和 Xiaomi 2410DPN6CC/API 36 手机的非破坏性覆盖均通过；同名 `release` 产物已替换。

详见 [修复与验收记录](docs/releases/v0.1.6-alpha2-build141.md)。

## 0.1.6-alpha2 · build 140（2026-09-19，本地 Release 构建）

- 修复 proroot 将目录项报告为未知类型时，alpha.2 JSON `per-record` 存储写入后重开为空、导致受管更新试运行回滚的问题；不迁移用户数据格式。
- 修复 Web 插件待审阅入口误灰；为旧社区预设补齐 `dsh-workflow-worker-thread` 到 alpha.2 `dsh-workflow-ptc` 的受管兼容链接。
- 真机从 build 139 非破坏性覆盖到 140，proroot 受管更新、DSH/Web 启动、旧会话/API Key、自建预设、Web 上传入口及原生插件管理通过。
- 两版各 574 项单测（573 通过、1 跳过），Release Lint Error/Fatal 为 0；E7E3 签名、16 KiB 对齐和 APK 运行时完整性通过。

详见 [完整记录](docs/releases/v0.1.6-alpha2-build140.md)。

## 0.1.6-alpha2 · build 137（2026-09-18，本地 Release 构建）

- 修复覆盖更新后插件管理仍复用旧脚本和共享依赖链接缓存；受管资产与实际运行时身份绑定，变更后强制重新核验和建链。
- 升级后在空闲且运行时健康时清理精确列出的可再生插件/WebView 缓存，不遍历或删除会话、配置、API Key、工作区和第三方插件原件。
- 启动图标改用 `ic_launcher_v137` / `ic_launcher_round_v137` 资源，补齐自适应和圆形图标；版本码升至 137，解决 MIUI 覆盖更新后长期复用旧图标缓存。
- 两版各 574 项单测（573 通过、1 跳过），Release Lint Error/Fatal 为 0；7 个内置插件的实际 Node 24 + Chrome 启动通过。

详见 [完整记录](docs/releases/v0.1.6-alpha2-build137.md)。

## 0.1.6-alpha2 · build 136（2026-09-18，本地 Release 构建）

- 同步 DSH `0.1.6-alpha.2`，保留 Ubuntu 基础环境 10 和原有用户数据位置。
- 适配 alpha.2 的 Session/InputHub、插件管理、Agent 预设、文件草稿、Subagent 侧栏和客户端脚本拼接接口。
- 新增 Office DOCX/XLSX/PPTX 预览路径；Android 使用系统字体目录，转换结果仍由 DSH 授权文件作用域读取。
- 插件安装、删除和第三方启用统一回到 DSHA 原生审阅与事务入口；插件列表和后台任务状态更新减少重复启动。
- 更新应用图标；悬浮条流式刷新合并、小窗按实际尺寸自适应，新增原生后台任务查看入口。
- 两版各 568 项单测（567 通过、1 跳过），Release Lint 无错误。真机覆盖更新后会话、凭据和目录摘要保持一致，工具回归通过。

详见 [完整记录](docs/releases/v0.1.6-alpha2-build136.md)。

## 0.1.6-alpha1 · build 135（2026-09-17，本地 Release 构建）

- 插件整页滚动、带边框日志、自适应详情卡片和直接语言入口。
- 第三方模型原生配置入口、持续至本次运行结束且可撤销的读屏授权、真实敏感能力开关。
- WebView / Gecko 画中画入口及后台运行检查。
- 默认开启的可配置自动备份、三份验证副本轮换与旧环境受控自动清理。
- 网站本地加入两个社区插件下载条目；公开部署尚未完成。

详见 [完整记录](docs/releases/v0.1.6-alpha1-build135.md)。

## 本地验收：0.1.6-alpha1 / build 134（2026-09-17）

- 按 17 项反馈更新原生模型、设置、插件、设备通道、更新和诊断界面。
- 修复自建预设、子代理触摸、ADB 配对后的连接端口；Android Computer Use 真实六工具验收通过。
- 减少主线程状态检查，插件列表按可视区域渲染；保留原数据与终端结构。
- 两版正式签名 APK 本地交付，未上传。[修改、校验与验收范围](docs/releases/v0.1.6-alpha1-build134.md)。


## 0.1.6-alpha1 (132) · 2026-09-16 · 待真机验收

- 升级完整 DSH 0.1.6-alpha.1，并锁定 Browser/Computer Use、Auto review、Team、SSH 等额外官方组件。
- 原生 UI 采用确认稿的黑白蓝主题与紧凑布局；真实模型配置、社区页、插件详情/删除入口。
- 整合 PR #68 的构建、桥凭据、通知与系统语言修订，以及 PR #70 对话画中画。
- 修复 #69：真实头尾输出、冷安装独立诊断、失败阶段、随包监督会话、显式运行兼容选项与受控重试。
- 两版 Release 构建与本机检查通过；按用户安排尚未安装手机或上传。


> 只记面向用户的变化。完整历史见 [commit log](https://github.com/qiannianhuanxiang/DSHA/commits/main)。

### v0.1.5-rc2.1 / build 131（本地稳定性修订）

- 为设备桥与 LAN 增加读取时生效的请求头限额、绝对截止时间和连接配额；修复零长度、临时响应、分块及 WebSocket 边界，保持附件/SSE 字节流和普通请求可用。
- 插件预览绑定实际 pnpm 冻结锁与依赖快照；安装、回退和中断恢复保留插件组合及激活记录。隔离插件明确审阅后才启用，真实加载失败停用候选并保留诊断与源码。
- 凭据无法读取时保留旧密文，区分未配置、暂时不可用及需要处理；提供重试、重输和明确移除，不再把解密失败冒充空密钥。
- 增加保留副本/旧树的只读清单、逐项检查、导出与预检恢复；未知数据格式不冒充可降级，不新增自动删除。
- 更新中英文状态、短屏页面与安全说明。原包名、历史签名、dsh rc.2、Ubuntu base 10 不变。详见[build 131 交付记录](docs/releases/v0.1.5-rc2.1-build131.md)及[验收证据](docs/stability-acceptance.md)；此前记录保持原样。

### v0.1.5-rc2.1 / v0.1.5-rc2.1low（本地验收交付）

- 修复 #67 的全局插件不存在误报、旧梁神预设 `text` 与新 `prefix` 的兼容，以及维护显示成功但环境未就绪时的自动跳转循环。
- 修复非调试安装中终端刚启动就关闭时的进程核验竞态，保留出生身份、guest 回收和维护屏障。
- 普通覆盖更新只替换受管运行时，不归档 Ubuntu；缺失 Bash 时保留原生救援入口，重建只保护个人数据并保留可恢复原件。
- 新增宿主加密数据导出、预检与按范围恢复、私有已验证副本重新导出；配置重置和固定检查点不再要求旧 Python 可运行。SAF 取消、目标读回和权限变化有独立结果。
- 修复冷环境内置插件依赖、受管更新中的局域网设置补丁与附件原子写入；真实网页健康确认之后才提交新运行时。
- 旧运行时清理前重新核验原件，修改过或未知文件继续保留。版本码 130，历史签名不变。详见[交付与验收范围](docs/releases/v0.1.5-rc2.1-build130.md)。

### v0.1.5-rc2 / v0.1.5-rc2low（正式版）

由贡献者 [@SunsetRNE](https://github.com/SunsetRNE) 发布，版本码 **129**，dsh **0.1.5-rc.2**，Ubuntu 基础环境 **10**，沿用原发布签名。完整内容见[正式版说明与历史对比](docs/releases/v0.1.5-rc2-notes.md)。

- 统一日夜主题、卡片、权限、弹窗与切换动画，完善短屏/大字体；检查日志和诊断报告独立滚动，重排关于弹窗。
- PTY 与简易终端支持独立标签，切换语言及页面保留进程；关闭后复用最小空缺编号，全部关闭后从 1 开始。
- 中英文原生/网页状态同步，修复缓存状态混用语言；用户输入、命令和第三方原始内容保持原文。
- 插件排序与操作框、dsh-web-mobile 2.4.1-dsha.2、真实 Agent 预设切换和宽屏文件入口。
- 修复 #64 多文件选择、旧 WebView/PDF Worker、#63 DNS 兼容；补齐 Shizuku 改包名识别和主动连接恢复。
- 完善端口冲突、慢鉴权、原生恢复与固定配置快照，修复旧平板环境迁移路径；保留数据与进程核验。
- 两版各 383 项单测（382 通过、1 跳过）、Lint 无错误。详见[最终验证范围](docs/releases/v0.1.5-rc2-build129.md)。

### v0.1.5-rc1 / v0.1.5-rc1low（正式版）

- 由贡献者 [@SunsetRNE](https://github.com/SunsetRNE) 发布为 GitHub 正式 Release / Latest；主线汇总此前重构预览改进，历史 Release 与 1.1.10 及更早说明保留。完整对比见[正式版更新介绍](docs/releases/v0.1.5-rc1-notes.md)。

- 修复文件和预览标签的关闭“×”被按钮最小高度撑到右下方的问题，恢复在标签右侧垂直居中；两种浏览内核实际关闭操作通过。同名 APK 已更新。
- dsh 更新至 `0.1.5-rc.1`，提供新版默认模型 DeepSeek-V41-Flash，保留 V4 Flash、Pro 与 Vision 实验模型的选择。
- 保留新版右栏引导页、文件预览、长内容滚动和代码复制，重新适配前端提示组件；输入框回车换行、旧内核 PDF 兼容和移动侧栏继续可用。
- 版本码 116，Ubuntu 基础环境版本 10 不变。alpha.2 自动覆盖升级实测约 7.45 秒，个人数据保持原位；连续启动鉴权约 3.43～3.66 秒。
- 沿用历史发布签名，两个 APK 和校验文件放在 `release`，不覆盖历史版本。详见[版本说明与真机验收](docs/releases/v0.1.5-rc1.md)。

### v1.5-alpha.2 / v1.5-alpha.2low（本地候选版）

- 修复空闲终端及其后台命令未回收，导致「重新解压环境」在维护门禁报错；确认终端退出后再开始备份或更新。
- 修复冷环境中原生扩展缓存被转换为悬空链接，导致首次启动找不到内置插件；统一直接加载随包扩展，并新增首次初始化校验。
- 覆盖升级直接解压独立 dsh 包，跳过 Ubuntu 大归档；冷安装仍完整安装并验证 Ubuntu、Node、Python、curl/git 等工具。
- 复用未变化的网页脚本与源码映射，避免每加载一批插件就重复拼接全部大文件；插件变更和热更新及时失效，同机连续启动约 3.1～3.4 秒。

- 启动页显示阶段耗时、插件名称、版本及实际加载错误，切换页面后仍可查看；保留上次失败输出和退出码，成功重试不再覆盖失败证据。
- 安全启动支持中止当前启动并使用独立基础界面，原插件开关、配置和会话保留；明确的插件故障可选择停用后重启。
- proroot 在鉴权前确认退出时可进行一次 proot 兼容重试；不会因等待时间长自动终止，不改用户保存的运行方式。
- 复用未变化的运行时依赖链接，避免每次注册重写脚本使工具缓存失效；修复左下角附件图标偏移。
- 修复直接重启时退出中进程的命令行变空，被误报为“无法核验本应用进程”的竞态；停止阶段复核内核退出状态，不误杀容器或其他进程。

- dsh 更新至 `0.1.5-alpha.2`，内置移动 UI 更新至 `2.4.0` 并适配官方右栏，保留新版文件预览与文件交付。
- 删除按启动次数自动备份及配置项；移除启动时的全工作区扫描，复用未变化的受管工具。
- 相同 Ubuntu 基础环境直接事务更新 dsh 和内置插件，避免重复搬迁会话、项目和第三方插件；失败可回切。
- 修复旧整数配置的类型异常；插件缺少依赖时保存已有源码并明确提示；恢复依赖使用相对链接。
- 修复旧 WebView / Gecko 的 PDF 预览兼容，新增会话删除入口沿用浏览器鉴权和来源检查。
- 双版本码 115，基础环境版本 10，保留历史发布签名。详见[版本说明](docs/releases/v1.5-alpha.2.md)。

### v1.2.0-rc1.4 / v1.2.0-rc1.4low（预览版）

由贡献者 [@SunsetRNE](https://github.com/SunsetRNE) 推进，以 [GitHub Pre-release](https://github.com/DSH-APP/DSHA/releases/tag/v1.2.0-rc1.4) 发布，感谢原作者及其他贡献者。

- 同版本功能复查：安装六步检查合并探测并显示实时脱敏输出、步骤与耗时；修复静默命令超时、取消后进程残留及终端 exit 后首条输入丢失。
- “显示与运行”逐项补充作用和生效时机，接通启动更新及实际脚本更新入口；修复配置保存与 ADB 授权同步、断线重连和前台提问反馈。
- 备份与环境重建增加任务互斥和实际进程退出屏障；只省略可从 APK 补齐的原版 ADB 缓存，保留修改内容；修复更新通道来源、重建后的安装校验及阶段进度显示。见[功能验收](docs/functional-audit-rc1.4.md)。

- 同版本排版重整：统一顶部标题与返回、间距、按钮主次；设置与配置按功能分组，保存操作固定；重排插件、更新、诊断和备份页面，并完成窄屏、大字体、日夜及短视口的原生布局检查。

- 同版本主题修复：仅保留右上角白天/黑夜快捷切换，移除设置页“界面主题”区域及弹窗，修复默认按钮黑底深字、禁用文字和大字体裁切；主题切换不结束终端，版本名及版本码 113 保持不变。

- 修复四种备份范围、实际数据快照和导出校验；恢复前预检并支持失败回滚，接通启动次数自动备份。
- APK 下载可在页面关闭后继续，并保留取消/进程重建后的续传进度；插件任务增加阶段、百分比及安全取消。
- 完善插件 SemVer 与版本查询、连续启动失败恢复入口、持续运行/省电模式。
- 两种内嵌网页内核支持实际文件保存、弹层返回、图片草稿和阅读位置恢复；修复旧 WebView 启动兼容问题。
- 官网安装参数不再丢失，来源修改后撤下旧入口；增加完整参数二维码与窄屏菜单。详细记录见 [rc1.4 说明](docs/releases/v1.2.0-rc1.4.md)。

### v1.2.0-rc1.2 / v1.2.0-rc1.2low

- 对话页移除「返回 / DSHA 对话 / 浏览器打开」顶部栏，两种网页内核统一全屏显示；系统返回手势、键盘避让和错误重试保留。
- 插件市场新增「前往 dsha.cc 选插件」入口，在浏览器选取后可返回 App 粘贴下载链接或导入插件包。
- 版本码升至 111，沿用历史发布签名与环境版本，可覆盖安装。

### v1.2.0-rc1.1 / v1.2.0-rc1.1low

- 修复首次安装缺少 CA 根证书导致插件 HTTPS 下载失败，GitHub 归档直连官方 codeload；兼容运行时追加退出日志后的结果解析。
- 本地导入优先系统文件选择器，增加备用选择器，处理空返回、ClipData 与旧文件管理器读取授权。
- 两版终端补齐 npm/npx 入口与证书，新增 dsha-plugin install 安装并登记 npm 插件。
- 修正终端 JNI 的 RELRO 布局，解决部分 Android 13 设备加载库时错误报告 Out of memory；保持 16 KB LOAD 对齐。
- 本次修复包版本码为 110，文件名与版本名沿用 rc1.1 / rc1.1low，签名不变。

- 增加 Android 6—12 / arm64 兼容版，与标准版共用插件、安装修复、终端和对话功能；额外携带 Gecko 143，旧系统自动使用兼容内核。
- 兼容版 proot / loader 与终端 JNI 以 API 23 重编，回补 Java API、旧存储权限和低版本布局属性；无线调试配对码仍需 Android 11+。

- 插件页改为分区滚动布局：管理页隐藏链接安装区，名称、说明、开关分行，增加「更多」入口；长错误通过弹窗完整查看，避免挤压列表。
- 补齐安装模块第 2 步：修复 Python、按需联网安装 curl/git/证书，检测真实运行结果；恢复清晰的 1—6 步入口与失败报告，第 4 步使用内置 pnpm。
- 补齐 proot 硬链接模拟目录的映射，修复 apt 安装时 dpkg 报文件不存在的问题。

- 插件市场支持 GitHub 仓库、分支 / 子目录、Release 与 HTTPS 压缩包链接识别，可从分享文字提取链接。
- 本地导入支持 ZIP / TAR / TAR.GZ / TGZ 及多插件包；导出支持多选、作用域插件名与系统保存位置选择。
- 修复第三方插件重新启用、官方核心误报缺失、导入导出结果被刷新覆盖的问题；补齐搜索、筛选、排序与来源复制。
- 插件安装检查发布包入口与依赖；失败保留原插件，混合包明确报告部分成功，临时文件用完清理。
- 标准版最低 Android 11 / arm64，编译与目标 API 37；补齐 Android 17 局域网授权、前台服务类型和全面屏边距。Android 16 核心真机验收通过，Android 11/17 与 16 KB 设备待验证。
- 真机验收补齐 Python 的 SQLite/readline 动态库及离线 pnpm 10.34.5；插件依赖改用文件复制，修复 proot 下硬链接导致更新报错的问题。覆盖安装保留配置和会话。
- 保留完整离线环境，统一 Ubuntu Python，移除 npm 缓存、重复 Python 和其他平台的二进制；压缩资产避免被 aapt 提前展开，两版共用精简后的离线资产。
- 插件卡片长按支持删除第三方插件，清理登记与文件，保留会话、设置和其他插件；内置核心插件不可删除。
- WebView 等待 Cookie 写入完成后再进入对话，自定义端口保持正确，认证失效可重新进入。
- 网页加载或渲染进程失败时展示原因、系统内核版本和重试入口，可直接用系统浏览器打开。
- 对话页接入系统文件选择器，支持附件上传；补齐键盘缩放和网页返回导航。
- 电脑模式保留真实浏览器版本，避免伪装新内核造成能力判断错误。
- 显式声明网络监听 / Wi-Fi 权限；后台无法恢复 ADB 保活时给出状态，并在回到前台后重试。
- 从同版本源码重编 Termux JNI，满足 16 KB ELF 对齐；完整运行环境的 16 KB 真机验证仍待完成。
- 启动 / 停止共用队列，停止按钮不再在主线程等待；启动完成或失败后释放门控，旧进程输出不会覆盖新会话，看门狗尊重用户停止。

### 🚀 v1.2.0-rc1：重构版（预发布）

从零重写、保留原版框架与内容的干净骨架。`dsh` 升级到上游最新 **0.1.2-rc.1**。

- dsh 0.1.2-rc.1：内置 Ubuntu 24.04 rootfs + Node 24 + pnpm，补齐 koffi-linux-arm64 等原生模块（修复 rc.1 WebUI 起不来）
- 覆盖安装不再丢插件/配置：rootfs 版本比对机制，内置包变化时强制干净重解压
- 插件管理：4 个内置插件 + 2 个官方核心带开关（**插件市场暂未开发**）
- 局域网访问：3081 代理自动绑定 + cookie 自动交换 + settings 持久化修复，主页直接显示完整地址
- 无障碍屏幕操作：读屏 / 点按 / 输入 / 按键 / 滑动 / 截屏（Android 11+）
- ADB 无线配对、危险命令守卫、流式悬浮条、终端 PTY、备份恢复、Keystore 加密 API key
- 57 条纯逻辑单元测试
- ⚠️ 预发布版：建议重新安装（先卸载旧版）而非覆盖；反馈加 QQ 群 **975836806**

### 🧩 v1.1.9：插件真的能装上了，停止真的能停下来

**插件市场几乎重做了一遍。** 索引换成 `plugins.json`（2249 条，带 npm 包名映射与中文
描述），装一个插件现在有四级降级：npm 直装 → 作者发布的 Release 预构建包 → 授权后重试
构建 → 容器内 clone + 构建。monorepo 子目录里的插件也能装了（此前会被截成仓库根，
装完插件页空无一物）。装不上的时候不再只甩一句失败：结果可滚动、可选中、可一键复制，
并且插件页会显示「已启用但没加载起来」以及**为什么**（这条状态是 dsh 进程内报回来的，
比 `package.json` 里写着「已注册」可信）。

| 插件相关的其它变化 | |
|---|---|
| 批量操作 | 长按进入多选，一次启用 / 禁用 / 导出 / 删除 |
| 导入 | 认 gzip / zip / tar，按文件头判格式；单插件、多插件、GitHub zip 的多包一层都能识别 |
| 导出 | 卡片上单个插件导出，或整批导出到 `Download/DSHA/插件/` |
| 存档点 | 改插件之前自动留一份可回退的状态（按需生成，不占日常空间） |
| 补丁热开关 | patch 层的开关不用重启，约 1 秒生效 |
| 自愈 | 「装任何插件都失败」的一类根因是自指依赖导致 `ELOOP`，现在启动时自动修；修不了的移进隔离目录并告诉你需要重装哪个 |

**备份：换设备恢复不再是空的。** 对话等热数据在手机上是指向公开目录的符号链接，而 `tar`
默认只存链接本身 —— 同机恢复看不出问题，换设备恢复就是一片空白，而这正是备份功能被宣传
的场景。现在备份会额外把这些数据解引用快照一份。同时备份可以**分范围**：全量 / 只对话 /
只插件，部分备份恢复时只覆盖对应内容（拿只含对话的包恢复，配置与插件保持现状）。

**停止按钮真的能停下来了。** 这条查出两个环境层面的硬事实：`/proc/net/tcp` 在非 root 的
Android 上读不到（所以 `ss`/`netstat`/端口反查全是空的），`/proc` 只看得到同一个 uid 的
进程。所以改成不靠猜命令行：启动时把进程号落盘，停止时按号精确停，并写一个「用户已停止」
标记 —— 看门狗和重启脚本每轮都检查它，看见就自己退出。停完 4 秒和 15 秒各回看一次，
真有东西把它拉起来就把「谁拉的」记进活动日志。另外**息屏保活**补上了：`WAKE_LOCK`
权限声明了很久，却从来没有一处真正获取过，熄屏后容器被冻住，回来什么都停了。

**快了一些。** 内置环境的离线包此前被打包工具白压了一遍（301MB 只压掉 1%），于是首次
解压要多解一层，现在少了这一层，解压缓冲也从 8KB 提到 256KB。插件市场页加了 6 小时
缓存，第二次打开不再等 800KB 下载，断网也能浏览。启动前的自愈脚本从 6 次容器会话合并
成 2 次，活动日志里会写「启动准备耗时」方便对比。

**其它**：`Download/DSHA/` 分成 `存档 / 插件 / 下载` 三个子目录（老位置照旧能读）·
WebUI 里点下载终于有反应了（内嵌浏览器此前完全没接下载回调）· 设备桥到 27 个端点，
新增 `/app/help`（agent 按需查完整清单，不必每轮都背一份）与 `/app/version`（插件做特性
检测）· PTY 终端的显示与输入回显修复（社区 PR #38）· 纯逻辑断言 153 → 300 条，另加
解压往返与停止判据两套端到端测试。

完整说明见 [CHANGELOG.md](https://github.com/qiannianhuanxiang/DSHA/blob/main/CHANGELOG.md)。

> ⚠️ **1.1.9 在发布当天覆盖过一次**（tag 没变，versionCode 98 → 99）。首发那个包在
> 「v1 → v2 重解压内置环境」这条路上有三个问题：① 数据保护跟随符号链接 —— 一次升级把
> 对话复制三遍，空间不够时会在 rootfs 已经删掉之后失败；② `/root/plugin-src/` 整个目录
> 不在保护范围内 —— 用 clone 构建装的插件会静默消失；③ 解压前没有空间预检。
> 如果你在 2026-08-29 早上下过 1.1.9，请重新下载一次 —— 设置页「关于」里能看到
> versionCode，**修复版是 99**。

### 🛠 v1.1.9.1：重解压不再丢工作区，升级中断了还能把数据找回来

**1.1.9 在「发现新版内置环境 → 重解压」这条路上丢了工作区里的东西。** 原来的数据保护
只挑三样复制：`.dsh`、`plugin-src`、各工作区的 `.env` **这一个文件** —— 工作区目录里
其它内容（你写的代码、agent 的产出、下载的文件）随 rootfs 一起被删了，而且不在备份里，
**找不回来**。如果你在 1.1.9（versionCode ≤ 99）上做过重解压、发现工作区空了，不是你的
操作问题，是那一版的缺陷。

1.1.9.1 把数据保护改成**整个 `/root` 挪走**（而不是挑几样复制）：

- 不再有「哪些文件要保」这个判断 —— 它就是之前三次漏东西的共同根源；
- 用移动而不是复制：同一文件系统里瞬间完成、不占额外空间。之前对话几个 GB 的用户
  正是在复制这一步空间不够、导致升级中断（而且那时 rootfs 已经删了）；
- 还原时同名冲突**以你原来那份为准**，工作区、插件源码、配置都原样回去。

**升级中断后数据还在。** 如果重解压中途失败（空间不够、被系统杀、断电），现在启动时会
弹窗告诉你「上次升级没有完成，数据还在」，可以恢复回来；恢复时把现有的 `.dsh` 挪到
`.dsh.pre-recover-<时间>` 而不是覆盖 —— 两份都留着，你自己挑。

**这次没有可回滚的后悔药**：1.1.9.1 修的是「之后的重解压」，**已经丢掉的工作区文件
找不回来**（那版不备份它们）。建议装完这版之后手动做一次完整备份，作为你自己的保险。

> 另外提醒：如果你在 2026-08-29 早上下过 1.1.9（versionCode 98/99），它的 APK 已经
> 被替换成这个修复版 —— 重新下载一次即可，下载页两个版本（1.1.9 与 1.1.9.1）现在
> 是同一个包（versionCode 100）。

### 📺 v1.1.8：AI 输出实时上屏，危险命令就地批准

屏幕顶部多了一条**流式悬浮条**：agent 正在生成的内容像歌词一样实时滚出来，
调工具时显示成「⚙ 正在执行命令: ls -la」——带命令原文，而不只是「正在执行命令」。
底色、不透明度、显示行数、停留时间都能调，带预览按钮，不用等 agent 说话就能试样式。
思考过程（reasoning）可选显示。**默认关闭**：内容会直接显示在屏幕上，旁边的人也看得见。

危险命令的确认也搬到了悬浮条上 —— 原来只有通知和前台弹窗两条渠道，可 agent 干活时
用户往往并不在 App 里。现在是**第三条渠道**而非替代：三条共用同一个 epoch，谁先点谁生效。

免 ROOT 做不到真正的「状态栏歌词」（只有 Flyme/exTHmUI 认 ticker flag，其余机型要
Xposed hook 系统界面），所以走自绘悬浮窗：一次性授权、全 ROM 通用。

**内置移动端适配换成 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)**（MIT）：
窄屏单栏 + 目录抽屉、设置改底部 sheet、状态栏安全区、表格与气泡排版。
旧插件作者长期停更；升级时会自动把它从 profile 摘掉并删除实体（两个插件改造同一批
DOM 元素，同时激活会互相打架），此前手动禁用过的话新插件也保持禁用。

同版修掉的问题里，有几个是「一直没人发现它是坏的」那类：

| 问题 | 根因 |
|---|---|
| 局域网访问打不开 | 剥离 token 时把请求行的 HTTP 版本一起吃掉（`GET /?token=x HTTP/1.1` → `GET /`），后端直接 400 |
| 局域网页面能开但一直转圈 | token 靠浏览器自动带的 `Referer` 生效，而 WebSocket 握手不发 Referer → 必然 401；顺带 token 会随外链泄漏给第三方 |
| 装过 rc 版就再也收不到更新 | 版本号解析把 `7-rc81` 拼成 `781`，比任何正式版都「新」 |
| 脚本热更新永久失效 | 增量清单落后于 assets：下到新文件、校验旧哈希、整批丢弃，界面上什么都不说 |
| 自检脚本一启动就崩 | 正则里 `\(` 写成 `\\(`，模块级 `re.compile` 直接抛错 |

现在这些都有守门人盯着：新增 Fast checks 流水线（清单一致性 + 离线验签 + 纯逻辑断言 +
assets 脚本真编译），发布时证书指纹不匹配直接中止 —— 发一个用户装不上的包比发布失败糟。

### 🔐 v1.1.7：对话数据不再随卸载消失

会话、设置、附件迁到 **内部存储/Documents/dshdata**，原位留私有符号链接。
文件管理器里直接可见、可自行备份，**卸载 App 或换机重装后数据仍在**。

进入 App 会自动申请「所有文件访问」权限并说明用途；授权后立刻迁移，
不必等下次启动。自检新增「对话数据存放位置」一项，明确告诉你现在到底会不会丢 ——
之前这个迁移在缺权限时会**静默跳过**，用户以为安全了其实没有。

刻意留在私有目录的东西：`DSH_HOME` 本体（dsh 维护的 `node_modules` 符号链接，
公开 FUSE 禁止软链）、`.credentials.yaml`（公开区强制 660，且密钥会暴露给其他 App）。
**API Key 改用 Android Keystore 加密**（AES/CBC，密钥不出 Keystore），
备份里的那份也加密后再写 —— 此前是明文进 `Download/DSHA` 公共目录。

同版还修了两个影响日常使用的问题：

| 问题 | 根因 |
|---|---|
| 每条 agent 命令都弹危险确认 | 守卫把我们自己注入的 `source …dsh-guard.sh 2>/dev/null;` 前缀当成用户命令，其中的 `>` 让「覆盖关键路径」判据恒真 |
| 深色模式按钮浅蓝底浅字看不清 | Material3 把 `<Button>` 膨胀成 MaterialButton 并用 `colorPrimary` 填充，**忽略 `android:background`**，对比度只有约 1.3:1 |

### 🚀 v1.1.6 起：默认 proroot 运行时，启动快 5~6 倍

> 历史归属：本节是DSHA v1.1.6当时的默认、旧基准和回退设计，不描述当前build190，也不是proroot上游版本号。当前默认proot、proroot显式可选；当前加载器、shim、缺失的--kill-on-exit及退出监督见[运行引擎专页](docs/runtime-engines.md)。下方原历史正文保留。

传统 proot 基于 ptrace，**每个系统调用要两次上下文切换**；
[proroot](https://github.com/coderredlab/proroot) 改用 LD_PRELOAD + 二进制补丁做
进程内路径翻译，零 ptrace 开销。装完即生效，想用回 proot 可在「配置」页关掉。
真机实测（vivo V2352A / Android 14）关键项合计 **+58%**，
其中 tar 打包 +94%（备份走这条）、stat 密集 +82%（node 模块解析）。

**兜底机制**：运行时文件缺失自动降回 proot；连续 3 次启动失败强制切回并告知；
装机路径始终用 proot。最坏情况只是回到原来的速度，不会让环境不可用 ——
这也是敢把闭源组件设为默认的前提。见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
## 0.1.6-alpha2.1 追加修订

- 虚拟屏创建后可由 AI 工具自动带到 DSHA 前台，应用内预览保留拖动、缩放和最新帧校验。
- 自定义服务商支持受校验的请求头名称/值配置，适配需要 `x-opencode-session` 等头部的网关。
- 修复移动端抽屉手势抢占纵向滚动，以及 Android IME 语音合成提交重复。
