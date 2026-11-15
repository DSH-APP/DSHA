# build352 覆盖升级维护页死锁（T139）：维护期注册与运行时健康回执的顺序冲突

更新时间：2026-11-10。状态：已确认根因，build353 修复。

## 1. 现象

build352 正式包（`0.2.1-rc.5-20261110.0700-dsh0.2.2-rc.1`，runtimeId `b8c59df9`）在真机覆盖安装后，
启动进入运行环境维护页（`ExtractActivity`），`DSHA_UPDATE_RUNTIME_V1` 每次失败并回滚：

```
[MAINTENANCE_TASK_FAILED] DSHA_UPDATE_RUNTIME_V1:运行时更新未完成，原运行时及个人数据已保留： …/host-runtime-operations/<id> ENV_NOT_READY
```

实测设备：OnePlus PJD110 / Android 16（KernelSU）。安装 build352 后连续三次维护失败
（07:17:06、07:19:15、07:26:27），应用一直停在维护页；回滚后 live 运行时为 build351 的 `6f31f111`。

## 2. 证据链（真机）

- 失败事务 `14f68328…` / `2fae2daf…` / `38be659b…` 的 `failed/runtime-706` 是 74,196 B 的新
  `.runtime-descriptor.json`（runtimeId `b8c59df9`），`failed/runtime-5` 是新的
  `managed-assets-v2` 标记（同为 `b8c59df9`）。
- 事务标记顺序：`files-committed` → `settings-committed` → 失败 → `rolling-back` → `rolled-back`。
- `files/runtime-health/` 有 26 份历史健康回执，但**没有** `b8c59df9` 的；`register-managed-asset-id`
  不存在（注册从未成功）。
- 回滚后 `files/linux/.runtime-descriptor.json` 恢复为 `6f31f111`（mtime 04:23），说明失败发生在
  文件切换之后、健康回执写入之前。

## 3. 根因

`EnvironmentMaintenance.update` 在 build352 中把维护 5/5 的静态校验改成
`validateRuntime(proot, true)`，即 `RuntimeTrial.verify` 一开始就注册内置插件。

`HostDataTransaction.commit` 的顺序是：

1. 切换受管文件（`.runtime-descriptor.json` / `managed-assets-v2` 变为新 runtimeId）；
2. 应用 settings；
3. `validation.verify()` —— 这里调用 `RuntimeTrial.verify` → 静态校验 → 注册；
4. 校验返回后，`ManagedRuntimeTransaction.commit` 才写入新 runtimeId 的
   `runtime-health/<id>.json`。

于是注册执行时 `RuntimeInstallationState.isEnvironmentReady()` 必然为 false：
`installedDescriptor()` 已是新 id，而 `rootfsVersionMatches()` 需要
`RuntimeDescriptor.healthy(runtimeHealth(), installed.id())`，新 id 的健康回执尚未写入。

`GuestPluginScripts.builtin("") → preparePlugins()` 第一道检查就是
`if (!preparation.environmentReady()) throw GuestPreparationFailure(ENV_NOT_READY)`，
异常向上冒泡，事务回滚。**注册需要“环境就绪”，而“环境就绪”要等注册所在事务完成才成立。**

## 4. 为什么本地与 CI 没挡住

- 单测的 `ProotBootstrap` / `Preparation` 是 mock，没有复现“descriptor 已切换、health 尚未写入”的真实顺序；
- 发布 CI 只跑单测 / lint / 构建，不执行真机覆盖升级与运行时维护；
- T135 的 P1-6 是 build352 才进入 main 的，build351 及之前没有维护期注册。

## 5. build353 修复

1. 维护事务的静态校验恢复为 `validateRuntime(proot)`（只核验运行时补丁），注册移出事务校验。
2. 事务提交、健康回执写入之后，再调用 `registerBuiltinAfterCommit(controller)`：
   - 走正常注册入口（`registerBuiltinPluginsResult()`，仍校验 `BUILTIN_REGISTER_OK` 回执与分级预算）；
   - 失败不再回滚运行时（运行时已健康），只记录诊断事件
     `MAINTENANCE_REGISTRATION = MAINTENANCE_REGISTER_DEFERRED:<原因>`；
   - 启动路径仍按 T135 的分级预算 / 超时清锁语义重试，用户可直接重试而不是卡死。
3. 维护完成文案区分「注册完成」与「注册待下次启动重试」，不再把注册失败包装成运行时更新失败。
4. 新增纯逻辑 `util/MaintenanceRegistration` 与单测，覆盖成功、普通失败、超长原因、中断、
   无消息异常；决策与顺序不进 Android 依赖。

## 6. 验证计划

- 单测：`MaintenanceRegistrationTest` + 既有 `GuestPluginScriptsTest` / `MaintenancePreStopTest` /
  `StartupRecoveryLifecycleTest` 等回归。
- 真机（PJD110）：351→353 覆盖升级，维护页走完 5/5 并完成注册；检查
  `runtime-health/<build353-runtimeId>.json`、`register-managed-asset-id`、`MAINTENANCE_REGISTRATION`
  事件；再重启一次确认首启不再重复注册。
- 反向验证：故意让注册失败（如临时移除 `register-builtin-plugins.py`）时维护仍应完成、启动可重试。

## 7. 教训

- 事务中间态的“就绪”定义必须显式建模：任何依赖 `environmentReady()` 的动作都不能放在
  文件切换与健康回执之间。
- 涉及覆盖升级顺序的改动必须进真机验收清单，不能只靠 mock 单测与 CI。
- 维护失败文案要带阶段与事务目录，避免只暴露 `ENV_NOT_READY` 这类内部分类。
