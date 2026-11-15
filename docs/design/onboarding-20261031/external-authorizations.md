# 首次引导 · 应用外授权步骤说明（7 类授权 + ADB）

> **用途**：新首次引导合并“首次部署运行时”与 7 类授权；HTML 交互原型只模拟 DSHA 应用内页面。凡在 DSHA 之外完成的步骤（系统设置页、系统权限弹窗、Shizuku/Stellar 管理器、Root 管理器授权）不在 HTML 中模拟，以本文为准；原型只画“去授权”按钮与返回后的状态文案。
> **依据**：当前工作树源码（HEAD `fece7a8`，build216；`minSdk 30` / `targetSdk 37`，`app/build.gradle:60-61`），现有 docs 只作参考。路径相对仓库根，`.../` = `com/deepseekharness/app/`；正文 `X.java:行` 为简写，完整路径见文末“源码核对清单”。引号内为源码中文原文。
> **状态**：仅静态核对源码，**未在真机验证**。

## 0. 通用对接约定

- **不在启动时自动申请**：现有首次流程只做环境检查，文案“环境准备会进行真实检查。现有环境和个人数据会按原有维护规则处理；设备控制权限可以稍后单独设置。”（`OnboardingPrepareActivity.java:42-45`）。AGENTS.md:105 要求“所有敏感能力（ADB、Root、短信、传感器、位置、悬浮窗、LAN）默认关闭、可撤销；最小配置下 DSHA 照常跑 dsh”。7 类授权都做成可跳过的独立卡片，外跳只由用户点击触发。
- **返回后复查**：现有页面都在 `onResume` 重读系统状态（`DeviceGrantsFragment.java:692-707`、`AccessibilitySetupActivity.java:113-117`、`OverlayFragment.java:304-309`、`AssistantActivity.java:533-552`），设备授权页另有 1 秒轮询，但只刷新 Root/Shizuku 状态行和 DSHA 本地确认开关 `FullPermissionControls`（`DeviceGrantsFragment.java:44-53`）。“已打开设置页”不等于“已授权”。
- **系统列表里的名称**：应用显示名是 `DSH-A-Su`，测试包为 `DSH-A-Su Test`（`app/build.gradle:65`），源码文案多写“DSHA”。外部步骤里要按显示名查找。
- **拒绝后不反复弹**：参照局域网权限只自动询问一次（偏好 `local_network_permission_asked`，`MainActivity.java:84-95, 848-854`）；被拒后只留“去系统设置”入口和原因。
- **未知不等于未开**：无障碍读不到设置时为 `UNKNOWN`，注释要求“不能报「未开启」害用户反复去开”（`DshaAccessibilityService.java:38-43`）。
- **授权不随备份迁移**：AGENTS.md:127“本机授权状态不能从用户归档生效”。
- **系统权限与 DSHA 开关分开**：授予系统权限不会打开 DSHA 功能开关（悬浮条、Root、ADB 均默认关）。
- **不自动重放**：外部授权完成后不得自动重试被拒的业务动作（如 `AssistantService.java:365-374` 的“不会自动重放”）。
- **与运行时部署的顺序**：仅 ADB 配对要求环境已就绪（`AdbPairActivity.java:484-487`）；Shizuku“不依赖 ADB 开关或 Ubuntu 环境”（`ShizukuShell.java:14`）；Root 经 `app_process` 执行、不经容器（`RootShell.java:183-188`）；存储授权影响容器挂载，宜在首次启动 Web 前完成。

## 总览

| # | 类别 | 外部界面 | 返回后判定 | DSHA 侧开关 |
|---|---|---|---|---|
| 1 | 存储读写 | 系统“所有文件访问”页 | `Environment.isExternalStorageManager()` | 无 |
| 2 | Shizuku/Stellar | 第三方管理器及其授权框 | `Shizuku.pingBinder()` / `Shizuku.checkSelfPermission()` | 无（源码未见） |
| 3 | Root | Root 管理器授权 | 执行 `id`，结果缓存于 `RootShell.lastResult` | 「允许 Root Shell」，默认关 |
| 4 | 无障碍 | 系统无障碍详情页；Android 13+ 受限设置 | `enabledState()` + `connected()` | 无（另有“暂停屏幕操作”） |
| 5 | 通知 | 系统运行时权限框 / 应用通知设置 | `POST_NOTIFICATIONS` + `areNotificationsEnabled()` + 渠道 | 无 |
| 6 | 悬浮窗 | 系统“显示在其他应用上层”页 | `Settings.canDrawOverlays()` | 「显示悬浮条」，默认关 |
| 7 | 电池优化 | 系统电池优化列表 / 应用详情 | **源码未见检测** | 无 |
| + | ADB 无线配对 | 开发者选项 → 无线调试 | `DeviceBridgeService.adbState` + `AdbBridge.status` | 「启用 ADB 设备通道」，默认关 |

## 1. 存储读写（所有文件访问 MANAGE_EXTERNAL_STORAGE）

| 项 | 内容 |
|---|---|
| DSHA 入口 | 设备授权页行「管理文件访问权限」（`app/src/main/res/layout/fragment_device_grants.xml:52-55`）→ `app/src/main/java/.../ui/DeviceGrantsFragment.java:828` `openAllFilesAccess`；清单 `AndroidManifest.xml:35-37` |
| Intent | `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` + `package:<包名>`（:829-835）；失败退 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 总列表（:838）；再失败 toast“打开设置失败：%s”（:840） |
| 应用外操作 | 在 DSHA 的“所有文件访问权限”页打开允许开关（AOSP 通用）；若落到总列表，先找到 DSHA 再打开 |
| 返回判定 | `onResume` → `refreshAllFilesStatus`（:703, :859-884）：“已开启：可读写系统允许的共享存储；其他应用私有目录仍受系统限制” / “未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件” |
| 撤销 | 同一系统页关闭；DSHA 内无撤销入口 |
| 对接要点 | 唯一判据 `StorageAccess.granted`（`StorageAccess.java:19-26`）：未授权时容器不挂 `/storage/emulated/0`→`/sdcard`（`ContainerRuntime.java:322-339`），网页文件选择器拒绝并提示“未获得文件访问授权：网页暂不能打开手机文件。请先在“设备授权”页允许文件访问。”（`WebPreviewActivity.java:1222-1233`）。外部备份扫描另行直接调用 `Environment.isExternalStorageManager()`，判断能否看到全部文件（`ExternalBackupScanner.java:78-85`）。截图“无需所有文件访问”（AGENTS.md:158）。卡片应写明“不开也能用，仅限私有目录” |
| 未验证/风险 | 挂载在组装 proot/proroot 参数时读取（`ContainerRuntime.java:110, 248`），推断授权/撤销后需重启 Web/终端才生效；撤销时系统是否结束 DSHA 进程未验证；Android 6–10 分支（:845-855）在 minSdk 30 下不可达 |

## 2. Shizuku / Stellar

| 项 | 内容 |
|---|---|
| DSHA 入口 | 「打开 Shizuku 管理器」→ `app/src/main/java/.../ui/DeviceGrantsFragment.java:529` `openShizuku`；「授权 Shizuku」/「验证 Shizuku 连接」→ 同文件 :169-199（按钮文案切换 :559-560）；提示“先在 Shizuku 管理器启动服务，再返回授权。手机重启后通常需要重新启动服务。”（`ui_refinement.xml:19`） |
| 管理器识别 | `ShizukuManagerCompat.manager()` 先取权限 `moe.shizuku.manager.permission.API_V23` 的**声明者**包；查不到再按 `util/ShizukuManagers` 的固定白名单顺序取第一个已安装的兼容管理器（目前只有 Stellar），`kind()` / `isStellar()` 供文案判断（`ShizukuManagerCompat.java:29-70`）。清单 `<queries>` 已增加 `roro.stellar.manager`，否则 Android 11+ 包可见性会让 `getApplicationInfo` 查不到。`launchIntent()` 为 null 时 toast“尚未安装 Shizuku 或 Stellar，请先安装并在管理器中启动服务”（`DeviceGrantsFragment.java:531-535`） |
| API 调用 | 未收到 Binder：`ShizukuManagerCompat.reconnect` 发 `rikka.shizuku.intent.action.REQUEST_BINDER`，只接受管理器 UID、描述符 `moe.shizuku.server.IShizukuService` 的单次回调，15 秒超时；解析不到导出 Activity 时改为直接打开管理器。识别为 Stellar 时不发任何 broadcast / 自定义协议（其 `REQUEST_BINDER` 是 `roro.stellar.intent.action.REQUEST_BINDER` receiver，协议未核实），直接打开 Stellar 主界面让用户检查服务与「Shizuku 兼容层」。已连接未授权：`Shizuku.requestPermission(code)`，60 秒无回调记“授权请求尚未完成，请重试”（`ShizukuShell.java:107-145`）。已授权：`Shizuku.bindUserService` 绑定 `ShellService`（:146-222），验证执行 `ShizukuShell.exec("id")`（`DeviceGrantsFragment.java:189-193`） |
| 应用外操作 | ①安装并打开管理器，按其自身指引启动服务（启动方式源码未描述）；用 Stellar 时点「打开管理」进入 Stellar 主界面，启动服务并确认已开启「Shizuku 兼容层」；②回 DSHA 点「授权 Shizuku」，在管理器弹出的授权框中允许 DSHA；③若管理器有隐藏模式，需允许 DSHA（源码状态文案提及） |
| 返回判定 | `onResume` 调 `ShizukuShell.ensureBound`（`DeviceGrantsFragment.java:700`），每秒刷新 `ShizukuShell.userStatus`（`ShizukuShell.java:87-105`）；验证中显示“正在验证设备通道…”，结果在“设备通道验证”弹窗原样显示（`DeviceGrantsFragment.java:498-527`） |
| 撤销 | 只能在管理器内撤销授权或停止服务；DSHA **没有** Shizuku 开关（源码未见对应偏好）。只要授权还在，且 Root 未启用或找不到 su，普通设备命令就走 Shizuku；短信与强制通道请求除外（`HttpShellService.java:1067-1073`）。撤销后 `ensureBound` 发现无权限即断开（`ShizukuShell.java:153-156`） |

状态原文（`ShizukuShell.java:87-105`）：
- 未连接、识别到 Stellar：“已安装 Stellar，Shizuku 兼容层尚未连接。请在 Stellar 中启动服务并确认已开启「Shizuku 兼容层」，回到 DSHA 后点授权。”（`UiText.choose` 内有对应英文整句）
- 未连接、识别到声明 API_V23 的管理器：“管理器已安装，服务尚未连接。点击授权重新连接；若仍未连接，请启动管理器服务，并检查隐藏模式是否允许 DSHA。”；未识别任何管理器：“尚未安装 Shizuku。安装并启动后可在这里授权。”
- “服务已连接，尚未授权 DSHA。点击下方授权。” / “已授权 · 设备 Shell 已连接，可直接使用，无需 ADB 配对” / “已授权，连接失败：%s。点击下方重试。” / “已授权，正在连接设备 Shell…”

**Stellar**：本轮已实现识别、入口与专属状态文案，并有 JVM 单测；**仍未在真机验证**（只做静态核对与单测）。事实与实现（提交 `bc03f6a2d7c95167d1853e24d3e52587634e7c58`，2026-10-31 用其源码压缩包复核）：
- 包名 **`roro.stellar.manager`**：其 [`manager/build.gradle`](https://github.com/roro2239/Stellar/blob/bc03f6a2d7c95167d1853e24d3e52587634e7c58/manager/build.gradle) 的 `defaultConfig.applicationId` 明确写死该值，与 [manager 清单](https://github.com/roro2239/Stellar/blob/bc03f6a2d7c95167d1853e24d3e52587634e7c58/manager/src/main/AndroidManifest.xml) 的 `android:sharedUserId="roro.stellar.manager"` 同名；已写入 `util/ShizukuManagers.STELLAR`、`ShizukuManagersTest` 与清单 `<queries>`。
- 协议差异：manager 清单没有声明 `API_V23` 的 `<permission>`（该提交全文检索 `API_V23` 无命中），因此权限声明者查询查不到；其 REQUEST_BINDER 是 `roro.stellar.intent.action.REQUEST_BINDER` **receiver**，不是 DSHA 发送的 `rikka.shizuku.intent.action.REQUEST_BINDER` activity。`ShizukuManagerCompat.reconnect` 对 Stellar **不发任何 broadcast / 自定义协议**（协议未核实，安全上不猜），直接 `getLaunchIntentForPackage` 打开其 LAUNCHER `MainActivity`，让用户检查服务与「Shizuku 兼容层」；这条路径与“找不到导出 Activity 就打开管理器”的既有分支同源，UID / 描述符 / 单次回调 / 15 秒超时检查**原样保留、未放松**。
- Binder 推送：服务端 `BinderSender.kt` 在应用进程启动/进入前台时调用 `BinderDistributor.sendShizukuBinderToUserApp`，经 `<包名>.shizuku` Provider 推送 `moe.shizuku.api.BinderContainer`，并跳过申请 `moe.shizuku.manager.permission.MANAGER` 的包；`ShizukuServiceIntercept : IShizukuService.Stub()` 的描述符仍是 `moe.shizuku.server.IShizukuService`。兼容层默认启用（`StellarConfig.shizukuCompatEnabled = true`），可在 Stellar 管理器设置页关闭；客户端需配置 `ShizukuProvider`（DSHA 已配置，`AndroidManifest.xml`）。
- 兼容层关闭：`ShizukuServiceIntercept.enforceEnabled()` 抛 `SecurityException`，且不推送兼容 Binder。此时 DSHA 显示“已安装 Stellar，Shizuku 兼容层尚未连接…”，点授权只打开 Stellar；60 秒超时只写入内部 `failure`，状态行不会变（推断，**未真机验证**）。
- 识别实现：`ShizukuManagerCompat.manager()` 先查权限声明者，查不到再按 `ShizukuManagers.knownPackages()` 顺序 `pm.getApplicationInfo`；选择逻辑在纯逻辑 `util/ShizukuManagers`，JUnit4 单测覆盖“权限声明者优先 / 只有 Stellar / 都没有 / 白名单顺序”。
- 未验证点：Stellar 兼容层是否识得以 `API_V23` 作 `android:permission` 的 `ShellService`（清单未改）；Binder 推送时机（进程启动 / 进入前台）在真机上的实际表现；「打开管理」跳到 Stellar 后返回 DSHA 时状态是否即时刷新；`API_V23` 授权框在 Stellar 下的实际交互。

- 对接要点：服务未连接时“授权”按钮先尝试重连，不直接弹授权框；Stellar 用户点「打开管理」进入 Stellar 主界面，需在 Stellar 内启动服务并确认「Shizuku 兼容层」开启，返回 DSHA 后点授权；手机重启后服务通常需重新启动，不把一次授权当永久可用。
- 风险：60 秒超时文案只写入 `failure`，未授权分支不显示，用户只看到状态没变；服务 8 秒未就绪返回 `[SHIZUKU_SERVICE_NOT_READY]`（`ShizukuShell.java:275-277`）。

## 3. Root

| 项 | 内容 |
|---|---|
| DSHA 入口 | 开关「允许 Root Shell」→ `app/src/main/java/.../ui/DeviceGrantsFragment.java:136-145`（`ConfigStore.setRootShellAllowed`，`ConfigStore.java:245-251`，键 `allow_root_shell` 默认 false）；「申请授权并验证 root」→ 同文件 :150-167；档位「更改」→ :570-623 |
| API 调用 | 打开开关不调用 su（`RootShell.java:15`“仅在用户开启开关并执行设备操作时请求 su”）。验证 = `RootShell.exec(ctx, "id", -1)`：从 9 个固定候选（`RootShellCandidates.java:10-18`）选 uid 0、不可组/他写的可执行文件（`RootShell.java:59-76`），执行 `su -c "CLASSPATH=<APK> /system/bin/app_process /system/bin RootShellMain"`，命令经 stdin（:183-198），65 秒收集上限（:199-200）；`RootShellMain` 非 uid 0 即拒绝（`RootShellMain.java:16`）。`id` 属严格档只读白名单（`DeviceShellPolicy.java:101-103`） |
| 应用外操作 | 在 Root 管理器中允许 DSHA（提示文案“首次执行请在 root 管理器中授权 DSHA”，`ui_refinement.xml:18`）；弹窗或预先授权以管理器为准，源码不区分；DSHA 未内置管理器（`docs/security-model.md:36`） |
| 返回判定 | “设备通道验证”弹窗原样显示 `id` 输出（`DeviceGrantsFragment.java:517-521`）；状态行 `RootShell.status`（`RootShell.java:34-47, 82-90`），原文见下 |
| 撤销 | DSHA：关闭开关 → 执行返回“[POLICY_BLOCKED] 未允许 root shell”（`RootShell.java:102-104`），并调用 `VirtualScreenManager.revoke()`（`DeviceGrantsFragment.java:142`）；外部：在 Root 管理器撤销 DSHA |

- 前置 toast（`DeviceGrantsFragment.java:153-161`）：“请先启用「允许 Root Shell」”；“未找到 su；请确认手机已 root，且 root 管理器允许 DSHA 使用”。
- 状态原文：“未启用 · 开启后可直接申请 root 授权”；“未找到 su，可使用 Shizuku 或 ADB 通道”；“su：%s\n尚未验证 root 授权”；“su：%s\nroot 通道已响应 · 设备命令仍受策略保护”；“su：%s\nroot 授权或执行超时，请在 root 管理器查看 DSHA 授权”；“su：%s\nroot 授权或执行失败：%s”；末行“命令拦截档位：%s”。
- 拦截档位（需先开 root，:572-583）：“严格（默认）· 仅放行白名单内的命令” / “放宽 · 允许任意命令，仍拦截不可逆操作” / “完全放宽 · 不按命令内容拦截”；非严格须再确认“确认放宽 root 命令拦截？”（:599-614）；缺失/非法值回落严格（`ConfigStore.java:253-257`）；“仅对 root 通道生效；Shizuku/ADB 通用执行器仍按严格档。”（:565-566）
- 对接要点：引导内只做“开开关 + 验证”，档位只读显示严格档，放宽留在设备授权页二次确认（`docs/security-model.md:28`）；验证会触发外部授权，必须由用户点击。
- 风险：开关开且找到 su 时命令一律先走 root（`HttpShellService.java:1056-1057, 1067-1070`），管理器拒绝也不退到 Shizuku/ADB，验证失败时应提示关闭开关；`lastResult` 是进程内缓存（`RootShell.java:50`），外部撤销后状态仍显示“已响应”直到下次执行；管理器无响应最长约 65 秒；候选与 metadata 检查不等于管理器识别（AGENTS.md:57）。

## 4. 无障碍

| 项 | 内容 |
|---|---|
| DSHA 入口 | 「设置屏幕操作 · 两步完成」（`ui_refinement.xml:21`，`DeviceGrantsFragment.java:81-84`）→ `app/src/main/java/.../ui/AccessibilitySetupActivity.java:68` `openSettings` |
| Intent | `"android.settings.ACCESSIBILITY_DETAILS_SETTINGS"` + `Intent.EXTRA_COMPONENT_NAME`=`DshaAccessibilityService`（:70-76）；失败退 `Settings.ACTION_ACCESSIBILITY_SETTINGS`（:80）；再失败 toast“请打开系统设置 → 无障碍 → DSHA 配对助手。”（:85） |
| 应用外操作 | “点下方按钮 → 已下载的应用 / 服务 → DSHA 配对助手 → 开启「使用服务」，确认系统提示。”（:32）；“部分手机路径是：设置 → 更多设置 → 无障碍 → 已下载的服务。”；“Android 13+ 若提示“受限设置”，在系统应用详情右上角菜单允许受限设置，再返回无障碍页面。只有你确认信任已安装的 DSHA 时才启用。”（:62） |
| 返回判定 | 设置页 `onResume→refresh`（:93-117，`connected()`/`enabled()`）：“已连接，可以在对话中使用 Computer Use。” / “开关已开启，正在等待系统连接；可重新开关服务。” / “尚未开启，请完成第 1 步。”。设备授权页用三态 `enabledState()`（`DshaAccessibilityService.java:44-60`）+ `connected()`（:275-277），另有“未开启 · 点上方按两步完成设置” / “尚未连接 · 点上方查看设置步骤”（`DeviceGrantsFragment.java:887-913`） |
| 撤销 | 系统无障碍页关闭服务（“你可以随时在系统无障碍设置中关闭服务。”，:48）；断开时撤销屏幕授权（`DshaAccessibilityService.java:578-600`）。DSHA 内「暂停屏幕操作并撤销预授权」只改 DSHA 确认策略，不关系统服务（`DeviceGrantsFragment.java:88-113`） |
| 对接要点 | 区分“开关已开”与“服务已连接”；UNKNOWN 不报未开启；“开启后仍未连接，可关闭再开启服务，并允许 DSHA 后台运行。”可作卡片帮助；ADB 自动读码依赖本项（`AdbPairActivity.java:201-204`） |
| 未验证/风险 | **文案与实际服务名不一致**：系统列表显示 `accessibility_service_label`=“DSH-A-Su 屏幕控制”（`build197_language.xml:4`），步骤与 toast 却写“DSHA 配对助手”；设置页用的 `enabled()` 把 UNKNOWN 当作未开（`DshaAccessibilityService.java:63-75`），与授权页三态不一致；Android 11 上详情页 action 能否直达未验证；受限设置菜单位置因系统而异 |

## 5. 通知（POST_NOTIFICATIONS）

| 项 | 内容 |
|---|---|
| DSHA 入口 | 设备授权页**无**通知入口。现有：`app/src/main/java/.../assistant/AssistantService.java:290` `notificationSettingsIntent`（用于 `AssistantWindow.java:154-158`、`AssistantActivity.java:114-117`「打开系统通知设置」）；运行时申请仅 `UpdateActivity.java:94-99`（点下载，API 33+）；上岛 `IslandSupport.java:133` `settingsIntent`（`IslandFragment.java:68-79`「打开系统实时活动设置」）；清单 `AndroidManifest.xml:22, 25` |
| Intent / API | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`（API<26 退应用详情，:291-298）；上岛先试 `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`（可解析时），否则普通通知设置（`IslandSupport.java:133-146`） |
| 应用外操作 | Android 13+ 系统权限框选允许；或在应用通知设置打开总开关——“请在系统设置 → 应用 → DSHA → 通知中允许通知，并开启“手机任务状态”通知类别；该通知提供停止入口。”（`AssistantService.java:367-370`）；上岛在系统“实时活动”页允许 |
| 返回判定 | `stopNotificationAvailable`（:200-230）：API 33+ 查 `POST_NOTIFICATIONS`、`areNotificationsEnabled()`、渠道 `floating-assistant`（“手机任务状态”）非 `IMPORTANCE_NONE`、渠道组未屏蔽；`AssistantActivity` 返回时显示“返回原对话继续”或“手机任务状态”（:538-545）。上岛 `IslandSupport.state`（:94-100）：“已允许：任务运行时会显示在状态栏胶囊上。（%s）” / “系统支持，但本应用当前未被允许。请用上面的按钮在系统设置里打开「实时活动」。（%s）” / “当前系统版本不支持实时活动（需要 Android 16 及以上），只会显示普通通知。（%s）” / “无法确认系统状态。（%s）” |
| 撤销 | 系统应用通知页关闭总开关或“手机任务状态”类别；上岛在“实时活动”页关闭 |
| 依赖 | 手机任务开始前须有停止通知，否则拒绝 `ASSISTANT_STOP_NOTIFICATION_REQUIRED`（`AssistantService.java:81, 130-131`）；执行中丢失则暂停任务（:260-288）；无前台界面且通知被拒时日志写“确认必然超时拒绝”（`HttpShellService.java:2730-2735, 2932-2942`）；任务通知发送失败仅降级（`AgentTaskNotifications.java:548-549`） |
| 对接要点 | 运行时请求只在用户点击后发起；被拒后只给“打开系统通知设置”；授权后调用 `stopNotificationAvailable` 复查（它会顺带创建渠道） |
| 未验证/风险 | 渠道只在三处按需创建：`stopNotificationAvailable`（通过权限检查之后，:202-218）、`offerOverlayPermission`（:413-418）、服务 `onCreate`（:487-492）。首次引导若没走过这些路径，系统通知页可能还看不到“手机任务状态”（推断）；`notificationPermissionIntent`（:404-406）源码未见调用方；`IslandFragment` 无 `onResume`，状态只在建页时计算（:64）；`UpdateActivity` 不论授予与否都继续下载（:271-273）；多次拒绝后系统不再弹框属 AOSP 行为，未验证 |

## 6. 悬浮窗（SYSTEM_ALERT_WINDOW）

| 项 | 内容 |
|---|---|
| DSHA 入口 | `app/src/main/java/.../ui/OverlayFragment.java:280` `openOverlayPermission`（「预览悬浮条」与保存时，:58-78, :335）；`ConfigFragment.java:323-332`；`AssistantActivity.java:119-125, 199-208`；`VirtualScreenOverlayController.java:44-58`（但 `show()` 在 `app/src` 中未见调用方）；手机任务被拒时 `AssistantService.offerOverlayPermission` 发通知“允许显示手机任务状态”/“点按设置悬浮窗权限，然后返回原对话继续”（:408-440）；清单 `AndroidManifest.xml:26` |
| Intent / API | `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` + `package:<包名>`；`OverlayFragment` 失败退应用详情，再失败 toast“请在系统设置中允许 DSHA 显示在其他应用上层。”（:286-300） |
| 应用外操作 | 在“显示在其他应用上层”列表中找到 DSH-A-Su 并打开允许。Android 11 起该 Intent 忽略 `package:`，总是进入列表页，不进入单应用页（[Android 11 权限变更](https://developer.android.com/about/versions/11/privacy/permissions)；minSdk 30 下始终如此）。源码路径文案：“系统设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → DSHA”（`AssistantService.java:371-374`）；“系统设置 → 应用 → DSHA → 悬浮窗”（`ConfigFragment.java:330`） |
| 返回判定 | `Settings.canDrawOverlays`（`OverlayController.permitted`，:129-136）。`OverlayFragment.onResume`：“悬浮窗权限已开启” / “需要悬浮窗权限，点击“预览悬浮条”即可授权。”（:270-278）；`AssistantActivity.onResume`：“已允许悬浮窗。请返回原对话明确继续；不会自动执行。” / “手机任务需要悬浮窗权限”（:546-551） |
| 撤销 | 系统同页关闭；DSHA 内关闭「显示悬浮条」只拆除悬浮条（`OverlayFragment.java:326`、`ConfigFragment.java:235`），不撤销系统权限 |
| 使用方 | 悬浮条 `OverlayController.java:827`、助手窗 `AssistantWindow.java:89-90, 163`、确认窗 `AssistantConfirmationWindow.java:60, 138`、虚拟屏浮窗 `VirtualScreenOverlayController.java:85`，均为 `TYPE_APPLICATION_OVERLAY`；手机任务无此权限即拒绝 `ASSISTANT_OVERLAY_PERMISSION_REQUIRED`（`AssistantService.java:132-135`） |
| 对接要点 | 授予系统权限≠开启悬浮条：`overlay_stream` 默认 false（`OverlayController.java:47, 138-145`）；“没权限时一切 push 直接丢弃，不弹系统弹窗骚扰”（:129）；实体屏手机任务开始前检查通知与悬浮窗（`AssistantService.java:130-135`），读屏/点按依赖无障碍连接（`DshaAccessibilityService.java:270-277`），引导可把三者合并说明 |
| 未验证/风险 | 各处跳转的实现与回退不一致：`ConfigFragment` 无应用详情回退；`VirtualScreenOverlayController` 该处无 try/catch，且 `show()` 未见调用方，生产路径只调用 `hideStopped`（`VirtualScreenManager.java:189, 203-204`），虚拟屏悬浮预览入口是否可达未验证；返回后系统状态是否立即可读未真机验证 |

## 7. 电池优化

| 项 | 内容 |
|---|---|
| DSHA 入口 | 「设置电池优化」（`ui_strings.xml:78`）→ `app/src/main/java/.../ui/DeviceGrantsFragment.java:801-825` `openBatteryOpt`/`openBatterySettings`；提示“后台断连时，可允许 DSHA 后台运行并减少电池限制。厂商仍可能暂停后台；恢复前先确认无线调试已开启，再点「验证已有连接」。”（`ui_strings.xml:42`） |
| Intent | 只导航 `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（无 data，:812）；失败退 `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:`（:817-821）；都失败 toast“无法打开电池优化设置”（:805）。注释：“只在用户点击后导航到系统设置；不申请或自动修改电池优化白名单。”（:808） |
| 清单/检测 | main/debug/deviceAudit 三份清单均**未声明** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（main 见 `AndroidManifest.xml:5-41`）；`app/src` **未见** `isIgnoringBatteryOptimizations`；单测锁定从不使用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（`BatterySettingsNavigationTest.java:47-54, 116-123`） |
| 应用外操作 | 在系统电池优化列表中找到 DSHA 改为不优化；若落到应用详情，在其电池项解除限制（AOSP 通用，名称因系统而异；源码文案只写“允许 DSHA 后台运行并减少电池限制”） |
| 返回判定 | **无**：源码不检测，设备授权页也无状态行 |
| 撤销 | 同一系统页改回优化 |
| 对接要点 | 引导只能写“已打开系统设置，请自行确认”，不能显示“已完成”；如需状态，要新增 `PowerManager.isIgnoringBatteryOptimizations` 读取（新代码，未实现）；不得改为直接申请豁免，否则违反 :808 注释与上述单测 |
| 未验证/风险 | 厂商仍可能暂停后台（源码文案已提示）；总列表需用户自行查找 DSHA；依赖库合并后的清单未经构建核对 |

## 8. 补充：ADB 无线配对（第三种设备通道）

| 项 | 内容 |
|---|---|
| DSHA 入口 | 开关「启用 ADB 设备通道（默认关闭）」→ `app/src/main/java/.../ui/DeviceGrantsFragment.java:223-239`（偏好 `adb_enabled` 默认 false，`DeviceBridgeService.java:96-99`）；「ADB 无线配对」→ :240-252 → `app/src/main/java/.../ui/AdbPairActivity.java` |
| Intent / API | `"android.settings.WIRELESS_DEBUGGING_SETTINGS"`，失败退 `ACTION_APPLICATION_DEVELOPMENT_SETTINGS`，再失败提示“请手动进入开发者选项 → 无线调试 → 使用配对码配对设备”（`AdbPairActivity.java:210-221`）；Android 17 需 `ACCESS_LOCAL_NETWORK`（`LocalNetworkAccess.java:9-16`，进入配对页时申请，:61, :70-73）；配对在容器内用 `adb-pair.py` 完成 TLS1.3-PSK + SPAKE2（`AdbBridge.java:25-29, 447-477`） |
| 应用外操作 | 开发者选项 → 无线调试 → 使用配对码配对设备；帮助文案：“找不到开发者选项时，到关于手机连续点按系统版本号。…配对端口来自 6 位码弹窗，连接端口来自无线调试主页面，两者不能互换。”（:168）；“自动读取”需无障碍，监听 120 秒（:196-207, :355-382） |
| 返回判定 | `computeAdbStatus`（`DeviceGrantsFragment.java:768-799`）：“ADB 已关闭。开启后才会保持连接。” / “尚未配对：点下方「ADB 无线配对」，配对后还会验证连接。” / “配对密钥已保存，连接尚未验证。%s” / “连接已验证 · 端口 %s%s” / “正在重连无线调试…%s” / “配对已失效，请重新配对。” |
| 撤销 | 关闭开关或配对页断开：“ADB 已关闭，配对密钥保留。需要时可验证连接重新启用。”（`AdbPairActivity.java:145-157`）；系统侧在无线调试页移除已配对设备或关闭无线调试（AOSP 通用） |
| 对接要点 | 配对前须环境就绪（“环境未就绪，请先完成环境安装”，:484-487），合并引导中排在运行时部署之后；连接验证后会自动 `--grant-keepalive` 为 DSHA 授予 `WRITE_SECURE_SETTINGS`，供开机/保活自动开启无线调试（`AdbBridge.java:517-537`、`BootReceiver.java:38-60`、`DeviceBridgeService.java:595-614`），引导需明示 |
| 未验证/风险 | `WRITE_SECURE_SETTINGS` 源码未见撤销入口；配对码与配对端口都是一次性的（:421），任务被系统中断后结果不确定（:348-352） |

## 源码核对清单（本次实际打开核对）

- `AGENTS.md:57, 103, 105, 107, 127, 158, 159, 172`；`docs/security-model.md:28, 36, 38`
- `app/build.gradle:60-61, 65, 451-452`；`.../ui/FullPermissionControls.java:13-14`
- `app/src/main/AndroidManifest.xml:5-49, 147-153, 212-233`；`app/src/debug/AndroidManifest.xml`、`app/src/deviceAudit/AndroidManifest.xml`（全文，确认无电池豁免权限）
- `app/src/main/res/layout/fragment_device_grants.xml:16-62`
- `app/src/main/res/values/ui_strings.xml:13, 23-25, 34, 42, 44, 55, 69, 78`；`values/ui_refinement.xml:18-21`；`values/build197_language.xml:3-4`；`xml/accessibility_service_config.xml`
- `app/src/main/java/.../ui/DeviceGrantsFragment.java:44-53, 81-199, 223-254, 478-623, 692-707, 768-913`
- `app/src/main/java/.../ShizukuShell.java:14-139, 141-216, 270-281`；`.../ShizukuManagerCompat.java:17-162`；`.../DshaApp.java:155`
- `app/src/main/java/.../RootShell.java:15-121, 175-220`；`.../RootShellMain.java:16`；`.../util/RootShellCandidates.java:10-18`
- `app/src/main/java/.../core/ConfigStore.java:245-266`；`.../util/Constants.java:12, 65, 68`；`.../util/DeviceShellPolicy.java:101-106`
- `app/src/main/java/.../HttpShellService.java:1043-1084, 2730-2735, 2882-2887, 2932-2942`
- `app/src/main/java/.../ui/AccessibilitySetupActivity.java:11-122`；`.../DshaAccessibilityService.java:38-75, 270-281, 552-600`
- `app/src/main/java/.../assistant/AssistantService.java:34-55, 78-135, 199-299, 365-374, 398-440, 482-505`
- `app/src/main/java/.../assistant/AssistantActivity.java:65-127, 199-208, 519-552`；`.../assistant/AssistantWindow.java:65-77, 89-90, 154-164`；`.../assistant/AssistantConfirmationWindow.java:56-65`
- `app/src/main/java/.../IslandSupport.java:76-146`；`.../ui/IslandFragment.java:46-133`；`.../ui/UpdateActivity.java:91-100, 271-273`；`.../AgentTaskNotifications.java:542-550`
- `app/src/main/java/.../OverlayController.java:47, 125-145, 827`；`.../ui/OverlayFragment.java:37-80, 270-336`；`.../ui/ConfigFragment.java:228-251, 323-332`；`.../vscreen/VirtualScreenOverlayController.java:24-74, 85`；`.../vscreen/VirtualScreenManager.java:189, 198-207`
- `app/src/main/java/.../StorageAccess.java:15-26`；`.../runtime/ContainerRuntime.java:100-121, 240-251, 320-339`；`.../ui/WebPreviewActivity.java:1215-1233`；`.../backup/ExternalBackupScanner.java:78-85`
- `app/src/test/java/.../ui/BatterySettingsNavigationTest.java:47-54, 116-123`
- `app/src/main/java/.../ui/AdbPairActivity.java:36-221, 355-406, 471-545`；`.../bridge/AdbBridge.java:25-51, 294-308, 446-537`；`.../bridge/LocalNetworkAccess.java:7-35`；`.../ui/MainActivity.java:62-95, 843-861`；`.../BootReceiver.java:18-73`；`.../DeviceBridgeService.java:96-99, 595-614`
- `app/src/main/java/.../ui/OnboardingPrepareActivity.java:12-56`；`.../ui/WelcomeActivity.java:25-101`
- 外部（不可信，仅引用事实）：Stellar `bc03f6a` 的 `README.md`、`manager/src/main/AndroidManifest.xml`、`server/.../BinderSender.kt`、`server/.../util/ProviderDiscovery.kt`、`server/.../shizuku/ShizukuServiceIntercept.kt`、`server/.../StellarConfig.kt`，以及源码快照中对 `API_V23` 的全文检索；Android Developers《Android 11 的权限更新》中关于 `ACTION_MANAGE_OVERLAY_PERMISSION` 的段落

## 未在真机验证声明

本文结论全部来自 2026-10-30 对当前工作树源码与 Stellar 公开仓库的静态阅读：未安装 APK、未操作手机、未运行构建或测试。系统设置页的实际标题与菜单位置、OEM 差异，Shizuku/Stellar/Root 管理器的实际授权交互，以及文中标“推断/未验证”的条目，都需在新引导实现后按目标机型真机核对，证据记录到 `docs/releases/`。
