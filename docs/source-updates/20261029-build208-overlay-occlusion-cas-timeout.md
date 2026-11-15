# build208：自有悬浮窗遮挡误判（#210）与 CAS 实体化超时（#209）

本轮基于 build207（`0.2.1-rc.3-20261028.0843-dsh0.2.1-rc.3`）源码，处理公开仓库两条新
issue。源码版本为 `0.2.1-rc.3-20261029.0741-dsh0.2.1-rc.3` / build208；本轮未构建、
安装或发布 APK，未操作手机。

## #210：自有悬浮窗被计为目标遮挡

vivo V2307A / Android 16 实测：开着状态浮条时对目标应用截图恒返回
`TARGET_WINDOW_OCCLUDED`，随后滑动报 `FRAME_NOT_OBSERVED`；关闭浮条立即恢复。
遮挡窗口是 DSHA 自己的浮条（`dsh.android.client`，`TYPE_APPLICATION_OVERLAY`，
`NOT_FOCUSABLE/NOT_TOUCH_MODAL`）。

既有机制：`OwnedScreenOverlay` 允许自有多窗体发布精确身份（windowId + 实测边界）
以获得排除；`MainScreenWindowPolicy.region` 命中登记排除时不计遮挡。缺口在于
排除必须逐窗登记，而发布只覆盖部分窗体（如 `AssistantConfirmationWindow` 只
clear 从不 publish），且登记在悬浮窗重建、windowId 漂移后失配——此时自有浮窗
重新变成遮挡物。

修复：`MainScreenWindowPolicy.ownFallbackExclusion` 新增按包名身份的整窗排除
fallback——`ScreenTargetResolver` 对同包名、非焦点、未登记（或登记失配）的窗口
构造整窗排除。包名在 Android 内是精确身份；排除区内的坐标轨迹仍被
`ScreenWindowRegion.containsTrajectory` 拒绝，完全盖满目标时
`hasUnexcludedArea` 依旧为 false，行为与登记路径一致。可聚焦的自有 Activity
窗口不适用（可能是观察目标本身），未登记规则不变（其他应用绝不排除）。

测试：新增 `MainScreenWindowPolicyTest` 六项——fallback 判据（未登记/已登记/
可聚焦/他包/无效边界）与组合行为（不遮挡、不参选、轨迹拒绝、避开可用、
盖满仍拒绝）。

## #209：link() 不可用设备的 CAS 实体化超时

Xiaomi 平板 8 Pro / HyperOS 3.0 / kernel 6.6 上 `link()` 被 ROM 拒绝
（独立实测 Permission denied），实体化旧 pnpm 内容链接退化为逐文件全量复制
（用户实测一例 420MB、143 秒被 SIGKILL），错误码
`PLUGIN_CAS_COPY_TIMEOUT`，随后弹回启动恢复页；复制中断还会把插件事务留在
pending，卡死下一次启动。

`RuntimeExecution.collectCasLinks` 的固定 120 秒超时按快设备的正常耗时定值，
对慢设备的合法复制而言是中途强杀。脚本本身已带进度回调（逐条推送诊断）与
取消票据（`check_cancel`），超时只需兜底真正的挂死。修复：超时改为 900 秒，
并注明依据。未改 python 脚本的复制算法；issue 建议的「增量对齐 / 按体积缩放 /
健康检查不看裸 LISTEN」需要更多设备证据，本轮不动。

## 验证范围与限制

- Windows 宿主、source-only 模式：`MainScreenWindowPolicyTest`、`screen` 包
  定向测试通过；`check-source-hygiene.py` 与十语 changelog 门禁通过。
- #210 的修复是纯逻辑窗口几何判定：遮挡排除/轨迹拒绝的语义由现有单测锁定，
  但「真机浮条不再触发 OCCLUDED」依赖 Android 无障碍窗口清单的实际行为，
  设备验收继续暂停，留待恢复后确认。
- #209 只放宽宿主侧超时；无 link() 不可用设备的复现环境，实际耗时改善未验证。
- 未构建、安装或发布 APK；未操作手机。
