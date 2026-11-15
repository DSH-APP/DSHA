# 落地记录：首次引导重构 · 部署胶囊 · 覆盖更新整屏维护页

日期：2026-10-31 · 分支 `feat/onboarding-redesign`（基于 main build217 `5a768d0`）· 设计依据：同目录 [README.md](README.md)、[preview.html](preview.html)、[external-authorizations.md](external-authorizations.md)

本文记录落地时的决策、与原型的差异，以及第 3 项要求“覆盖更新运行时能否使用同一风格”的评估结论。最终验证范围以本轮交付回执为准；**本轮没有在真机上覆盖安装验收**。

## 1. 结构

| 原有 | 现在 | 说明 |
|---|---|---|
| `WelcomeActivity`（3 页 ViewPager2）+ `OnboardingPrepareActivity`（“4 / 4”）+ `OnboardingUi` | `OnboardingActivity`（单 Activity，4 步：欢迎 → 用途 → 授权 → 完成） | 启动契约不变：`welcomed == false` 只进引导；Main / 启动恢复页 / 终端入口照旧校验 |
| `ExtractActivity` 整屏等待首次部署 | 「开始部署」即在后台启动应用级维护任务，进度进入**部署胶囊** | 复用 `BackupTask` 与 `DataProtectionService` 前台通知；部署期间引导页保持屏幕常亮 |
| 覆盖更新后 `MainActivity` 跳整屏 `ExtractActivity` 自动更新 | 仍整屏进入维护页，换成新版式（阶段条 + 阶段列表）；未关闭的隔离试运行现场也先恢复 | 见 §3 |
| `ExtractActivity`（旧版式） | 同一个类，换成引导同款版式（阶段条 + 阶段列表 + 底部操作） | 保留全部 extra、保存态字段与审计依赖（`render()` / `lastRender` / `automaticEntry` / `R.id.extract_*`） |
| 授权只在 设置 → 设备能力授权 | 引导第 3 步 `PermissionPanel`（7 类 + ADB），按第 2 步用途推荐与排序 | 全部默认关闭、可跳过；外跳只由点击触发；返回后 `onResume` 复查 |

共用构件：

- `ui/OnboardingKit`：版式构件（标题 / 按钮 / 状态标签 / 提示条 / 阶段条 / 阶段列表 / 自适应网格 `Grid` / 换行 `FlowRow`），只用 `shell_*` 与 `onb_*` token。
- `util/DeployStages`（纯逻辑，配单测）：把任务快照的进度文案映射为固定阶段。**不推导百分比**；识别不了的进度行保持已到达的阶段，原文照常显示。
- `res/values/onboarding_ui.xml`、`values-night/onboarding_ui.xml`、`drawable/bg_onb_*`、`ic_onb_*`。

## 2. 自适应

| 条件 | 行为 |
|---|---|
| 宽 < 600dp（手机竖屏） | 单栏；胶囊在顶栏正中；底部按钮纵向铺满 |
| 宽 ≥ 600dp（平板、手机横屏、分屏宽窗） | 两栏：左栏为步骤 + 部署进度卡；右栏正文；底部按钮靠右横排，放不下时换行 |
| 内容区 ≥ 约 700dp | 授权卡片两列，单独一张或“宽卡片”（设备命令通道）占满整行 |
| 高 ≤ 480dp（手机横屏） | 压缩页头、页脚与按钮高度 |

判定读当前 `Configuration`（`screenWidthDp` / `screenHeightDp`），旋转与分屏走 Activity 正常重建，步骤与用途选择由 `onSaveInstanceState` 与 `dsha-onboarding` 偏好恢复。正文始终在 `ScrollView` 内，1.3 倍字体与短屏下可完整滚动，底栏始终可见。

## 3. 覆盖更新运行时：能否使用同一风格（结论：同一视觉风格，整屏呈现）

### 结论与决定

覆盖更新与首次部署是**同一个应用级任务**（`BackupTask.updateEnvironment()` → `EnvironmentMaintenance.update()`；首次安装时落到冷部署 `initializeFresh`），进度来源、失败语义、应急入口一致，只是阶段表不同。因此阶段条、阶段列表与失败说明直接复用，不需要另一套视觉。

呈现方式经真机试用后定为：**覆盖更新使用整屏运行环境维护页（`ExtractActivity`，新版式）**，不使用“受限主界面 + 顶栏胶囊 + 弹窗”。理由：覆盖更新期间 Web / 终端 / 插件本就不可用，整屏页把唯一能做的事（等待、重试 / 恢复、查看记录、应急 DSH）放在一处；弹窗形态在失败时容易让用户误以为主界面可用。部署胶囊只用于首次引导内的后台部署（用户此时可以继续完成用途与授权）。

| 场景 | 呈现 |
|---|---|
| 首次部署（引导内） | 后台任务 + 部署胶囊（手机顶栏 / 平板左栏），点开为阶段面板 |
| 覆盖更新 / 环境未就绪 | `MainActivity` 入口门禁整屏进入 `ExtractActivity`；同一候选只自动尝试一次 |
| 未完成维护事务 / 未关闭的隔离试运行现场 | 同样整屏进入维护页，主按钮为“恢复中断维护”，不再发起注定失败的更新 |
| 用户在维护页选“进入受限主界面” | 同一安装身份留在受限主界面，不写就绪标记 |

阶段表：`DeployStages.Mode.UPDATE`（停止 Web → 解压新版 dsh → 内置插件与界面适配 → 切换受管文件 → 隔离试运行），`REBUILD`（保护数据 → 解压 → 逐文件恢复 → 试运行），由任务种类与进度文案识别（`DeployStages.infer`）。

### 安全约束的落地

| 安全约束（AGENTS.md 启动契约 / 已知 trap） | 落地方式 |
|---|---|
| 受限入口不能伪造就绪标记 | 只有用户在维护页显式选择后才登记受限入口；不写 `.offline-extracted`、健康回执或任何就绪证明 |
| 未完成的维护必须先恢复 | `MaintenanceCoordinator.pending()` 或 `files/runtime-trials` 下存在无 `closed` 标记的试运行现场时，重试 / 自动开始都改走 `recoverMaintenance`（`EnvironmentMaintenance.recover` → `RuntimeTrial.recoverPending` 核实进程退出后关闭现场）。试运行现场的检测是 UI 层只读目录检查，不改受管运行时启动输入 |
| 同一候选只自动尝试一次，旋转 / 进程重建不重复覆盖环境 | `RuntimeMaintenanceStarter.autoStart` 沿用 `dsha_environment_upgrade/attempted_identity` 与 `EnvironmentIdentity.shouldAutoStart` |
| 成功文案不是就绪证明 | 维护后未就绪时停留在维护页（`mayAdvanceAfterMaintenance` 不变） |

### 已知现场：`TRIAL_RECOVERY_REQUIRED`

上一次隔离试运行被打断（更新途中清后台、覆盖安装或进程回收）时，`runtime-trials/<id>/` 留下未关闭现场，下一次试运行会以 `TRIAL_RECOVERY_REQUIRED` 拒绝开始。修复前维护页仍显示“重试更新运行时”，点了立即再失败；修复后显示“恢复中断维护”，先关闭旧现场，再可正常更新。

### 刻意保留的旧行为

- 通知点击（`DshaApp.dataProtectionPage`）仍进入 `ExtractActivity` 的 `review_only` 详情页（静态守卫 `tools/test-notification-actions.py` 锁定）。

## 4. 与原型的差异

| 原型 | 落地 | 原因 |
|---|---|---|
| “隔离试运行”是独立阶段（示意命名） | 并入最后一段“检查启动配置与隔离试运行”（首次）/“隔离试运行与就绪检查”（更新） | 真实任务里试运行的进度文案（`正在核验原生模块…` 等）与启动检查交错出现，拆开会来回跳 |
| 首次部署 8 段 | 8 段：准备 / Ubuntu 与 Node / dsh 与内置依赖 / Python 与 pnpm / 应用工具 / curl·git·证书 / 核对并提交安装文件 / 启动检查与试运行 | “核对并提交”对应 `ColdBundleTransaction` 的真实阶段 |
| 空间、端口、su 路径为示意值 | 全部读真实值 | 空间判定 = `expandedEnvironmentBytes()` + 64 MB 余量，与 `ColdBundleTransaction` 的 `COLD_INSTALL_SPACE_REQUIRED` 同一判据 |
| Stellar“可能显示尚未安装 Shizuku（未验证）” | 已识别 `roro.stellar.manager`：状态文案、`<queries>` 包可见性、“打开管理器”入口均支持 Stellar | 不向 Stellar 发送任何未核实的私有广播；Binder 仍只接受 `moe.shizuku.server.IShizukuService` 且核对管理器 UID |

## 5. 新增能力（均只读或仅在点击后发起）

- 通知：Android 13+ 点击后申请 `POST_NOTIFICATIONS`；被拒后改为“去系统通知设置”。
- 电池优化：新增 `PowerManager.isIgnoringBatteryOptimizations` 只读检测；跳转仍是通用电池优化设置，**不申请豁免**（`BatterySettingsNavigationTest` 锁定）。
- 无障碍设置文案统一为系统列表实际显示的「DSH-A-Su 屏幕控制」。

## 6. 验证范围（2026-10-31，提交 `94b736b`）

| 项 | 结果 |
|---|---|
| 本机编译（aarch64 容器，`local-ci.sh`，qemu 运行 Google aapt2） | `:app:compileStandardDebugJavaWithJavac` 通过 |
| 本机单测（Robolectric，`com.deepseekharness.app.ui.*` 全包 + 新增纯逻辑） | 329 例，0 失败 0 跳过 |
| 快速门禁 | source-hygiene、ui-i18n（新增 0）、localize --check、changelog --check、文档链接（0 error）、host-tests manifest、运行时描述符（441 个启动输入未改）均通过；格式化 1228 个文件 0 变更 |
| 测试包 APK | `io.github.sunsetrne.dsha.test` / build218，本地测试证书签名；**本机 `.so` 未 strip**（NDK llvm-strip 为 x86-64），正式包以 x86-64 CI 为准 |
| 远端 CI | 推送分支自动触发 `Source checks`；结果见交付说明 |

## 7. 未验证与后续

- 未在真机上安装、覆盖升级或走完首次部署；系统设置页标题与 OEM 差异、Stellar 兼容层的实际授权交互、部署期间切到系统设置后 ROM 是否结束进程，均待真机核对。
- 本机缺 Robolectric 原生图形库，未做截图验收；`LayoutAuditInstrumentation` 的日夜 / 短屏 / 1.3 倍字体设备验收尚未运行（审计场景表已去掉旧欢迎布局）。
- 原位更新进行中，“启动 Web”按钮在界面上仍可点（环境此时通常仍就绪）；执行层会以“正在执行环境任务”拒绝，终端新会话与插件操作也被各自门禁拦截。后续可让启动页额外读取维护忙状态把按钮置灰。
- 设置 → 设备能力授权（`DeviceGrantsFragment`）仍是旧长表单，可逐步迁移到 `PermissionPanel`。
- 新文案均为中英整句，其余 8 种界面语言回退英文，待补译。
