# DSHA 0.2.1-rc.5-20261107.0140-dsh0.2.2-rc.1 / build335

- build334 真机结论（P0，未通过）：333→334 首次覆盖失败，两台设备都在 trial 收尾停在 `TRIAL_PROCESS_UNCONFIRMED`（事务如 `c4299668…`），运行时更新事务半提交、启动被维护屏障拦住；随后在维护页再执行一次维护即 334/READY、数据完好。build334 记为「首次失败、二次可用」，不写通过。本版按这条现场结论修首次失败路径与恢复后状态。
- trial 收尾对未退 guest 做有界强杀（T103，`a5168f99`）：`manager.stop()` 返回非空时不再立即判 `TRIAL_PROCESS_UNCONFIRMED`。先有界等待本轮启动器 3 秒，再由 `WebProcessManager.forceStopTrialGuest` 按本轮记录、唯一 profile 与保存的出生身份连读复核后，只对本轮 guest 发 SIGKILL，随后重跑记录停止与全局无 Web 核验；仍无法确认才失败，并把脱敏详情写入可导出操作记录与记录目录（成功试用的收尾失败写 `failure.json`，恢复路径写 `recovery-stop.json`，已有失败详情不覆盖）。`TrialRecovery` 走同一顺序，一次 `recover-maintenance` 就能关闭此前停在 guest 未退的旧 trial。强制路径缺出生身份、PID 记录对不上或两次观察变化时 fail-closed，不按裸 PID / 名称扫描。维护页 `pendingTrial` 去掉 2 秒缓存，`onResume` 丢弃 `BackupTask` 的 UI 缓存，外部恢复完成后立即回到「上一版运行时待更新」。
- 恢复按出生身份复核并补写身份证据（T100，`903974db`）：启动恢复与前后台复核共用 `observeRecoveredSession`；严格 PID / 身份文件核验为 UNKNOWN 时降级到完整出生证明（boot / PID / 出生时间 / 命令行 / UID / generation+instance+nonce），严格核验已给出 LIVE / EXITED 则不降级，证明版读不到仍 fail-closed。接回成功后按已核验出生身份补写 `/root/.dsha-web.pid` 与 `.dsha-web.identity`（pid 记录指向其它编号时原样保留），修掉身份文件陈旧 / 被退休后每次启动都落回 `IDENTITY_UNKNOWN` 的代码缺口。终局 UNKNOWN 写入可导出诊断 `WEB_SESSION_RESTORE_FAILURE`（stage / reason / attempts），`IDENTITY_UNKNOWN` 文案给出「停止 → 普通停止无法确认时按身份核验强制结束 → 重启再核对」的下一步。
- 虚拟屏预览改版（T96，`71010565`）：默认不再自动展开 172dp 页内小窗，改为宿主页顶部的蓝色呼吸条（3dp 呼吸线 + 目标应用文字「应用名 · 包名」，视觉 27dp、热区 40dp）；点击打开虚拟识屏页，× 收起。新增三档「提示条（默认）/ 完整预览（旧页内小窗）/ 关闭」，设置 → 设备能力授权 → 虚拟屏预览，选择档位即清除收起意图；收起持久化在应用私有偏好，跨虚拟屏代次与 App 重启保持，不再按 generation 记忆；虚拟屏停止 / 撤销、宿主离开前台、虚拟屏页自身、系统悬浮预览显示时移除。目标只来自 Manager 只读 `targetStatus`，拿不到显示「虚拟屏操作中」，不按启动记录猜测、不取帧、不签发输入凭据；呼吸动画遵循 `UiMotion`，关闭动画或减少动态时静态。
- 覆盖更新对话框文案补全并纳入 release 门禁（T102，`0331a00b`）：补 T91 的「放弃上一版 Web 并强制结束？」与其说明正文，zh / en 入 `tools/i18n/messages.json`，其余 8 个语言（zh-Hant / ru / ja / ko / ar / pt / la / fr）补同一 id 的完整译文，不留空、不用占位；同步更新 `check-source-hygiene` 中 6 个已审阅语言文件的字节钉住哈希，并把 `test-terminal-home-i18n.py` 加入 release-production / release-upstream 的 host-regression 子集，避免再次出现 push 红、release 绿。
- 版本与受管输入：版本码提升为 335（基于 build334）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。T103 / T100 / T96 合并后按 `tools/prepare-runtime-descriptor.py --write` 重算 runtime-descriptor（runtimeId `2d456af3…`）；版本与文档改动不改变它；固定输入未重建，不新增受管运行时或插件。
- 真机验收（2026-11-07 通过）：单台 334→335 首次覆盖安装即成功，trial 收尾未再出现 `TRIAL_PROCESS_UNCONFIRMED`；维护完成进入网页，版本、运行环境、端口、内置插件与个人数据均正常，状态 READY。详见下方「真机验收回执（2026-11-07）」；本回执不外推到其他机型 / ROM / Android 版本矩阵。
- 验证范围：`:app:testStandardDebugUnitTest`、`:app:lintStandardRelease`、宿主 contracts 整组 12 项、全树格式检查、`git diff --check`、文档链接、`check-ui-i18n`、`generate-changelog --check`、`test-terminal-home-i18n.py`（必须绿）、`test-ui-language-catalog.py`、`verify-runtime-descriptor-source`、`prepare-runtime-descriptor --check` 与 host-tests manifest；结果以本机发布准备回执为准。未 push、未合并、未发布、未安装。

## 真机验收回执（2026-11-07）

- 设备：OnePlus PJD110 / Android 16 / WebView 156。
- 安装：从 build334 覆盖安装 build335（versionCode 335 / versionName `0.2.1-rc.5-20261107.0140-dsh0.2.2-rc.1`）。
- 结果：**334→335 首次覆盖维护成功**，trial 收尾未出现 `TRIAL_PROCESS_UNCONFIRMED`；运行环境 READY、端口正常、网页可进；内置插件与个人数据均在。
- 发布侧证据（主会话记录）：tag `dsha-a-su-build335-20261107-014000`，release run 42265617912 success，APK SHA-256 `fc94bf5e…a9d5`（完整值以 Release 资产回执为准）。
- 历史口径：build334 保持「333→334 首次失败、二次维护后可用」的现场结论，本回执不改写它。
- 未验证范围：其他机型 / ROM / Android 版本矩阵；长时间运行、极端存储与非默认端口组合等场景未纳入本次验收，不能把本机结果扩写为全矩阵通过。
