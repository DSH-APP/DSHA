# 系统画中画与网页宿主

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`，并对照本轮工作树源码。本页没有新增手机验收；历史 PiP 结果只适用于相应记录绑定的 APK。

## 开启与生效

画中画默认关闭。配置中的选择由 [ConfigStore](../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) 写入本机明确选择键；旧版默认开启值、归档中的 `pictureInPicture=true` 或系统授权均不能证明用户本机选择，因此恢复设置不会自行开启 PiP。

进入 PiP 还要求 Android API 26+、设备支持、系统允许该应用使用画中画、当前网页内容就绪、当前 DSH 代次有效且未停止、未锁屏、宿主没有结束。Standard 本身 minSdk30；Low 在低于26的系统上没有系统 PiP。条件集中在 [PictureInPictureActivity](../app/src/main/java/com/deepseekharness/app/ui/PictureInPictureActivity.java) 与 [PictureInPicturePolicy](../app/src/main/java/com/deepseekharness/app/util/PictureInPicturePolicy.java)。开关保存后由运行中的宿主偏好观察器刷新；系统拒绝进入时保留普通页面，不把偏好保存成功当成已进入小窗。

| 用户动作 | 当前行为 |
|---|---|
| 网页中回桌面或切应用 | 满足条件时，Android8–11 显式进入；Android12+ 使用受门禁约束的 auto-enter |
| 网页返回原生 Main | 隐藏会话宿主、打开原生页，不隐式进入 PiP |
| 打开文件选择器、系统浏览器等 | 暂停 auto-enter，避免主动外跳留下额外小窗 |
| 关闭应用内 PiP 或撤销系统许可 | 宿主重新检查许可；若正处小窗，请求隐藏当前会话 |
| 小窗中停止 DSH | 本代次失效，小窗监控结束空宿主；新一次启动使用有效的新宿主 |

## 同一网页、代次与任务

全屏、PiP 与隐藏状态使用同代的网页实例，网页不应因为小窗切换而重新创建 DSH 会话。浏览器身份包含 generation 与 instance；停止、重启或换内核后，旧页面不能继续接收新代次操作。[BrowserPresentationState](../app/src/main/java/com/deepseekharness/app/util/BrowserPresentationState.java) 管显示状态，[BrowserConversationRoute](../app/src/main/java/com/deepseekharness/app/ui/BrowserConversationRoute.java) 管当前身份路由。

Home 后重新点桌面图标可恢复仍有效的后台网页；用户明确返回 Main、打开终端/插件等原生目的地会取消该待恢复意图。[BrowserTasks](../app/src/main/java/com/deepseekharness/app/ui/BrowserTasks.java) 优先路由到原任务，核对内容和身份后才恢复。空宿主只在确认该 Task 只有自身时移除整个 Task；旧版本混合 Task 中的原生页面不能一起删除。已结束的空宿主退休后，新代次不会复用遗留 PiP 几何状态。

PiP 的“横向/纵向/自动”改变网页逻辑视口的宽高，让网页重新排版；不会旋转设备或文字。系统比例限制在 `1:2.39` 至 `2.39:1`，超长内容采用比例钳制与留边。小窗实际尺寸仍由 Android/ROM 管理。

## 全屏恢复与故障排查

[PictureInPictureFrame](../app/src/main/java/com/deepseekharness/app/ui/PictureInPictureFrame.java) 在全屏恢复时按系统当前 `isInPictureInPictureMode()` 同步 compact 状态，并幂等恢复子 View 的 scale 为1、translation 为0；几何缓存不是“已清除变换”的证据。过渡期间继续绘制网页，避免 `onPause/onStop` 早于 PiP callback 导致黑屏。

若没有小窗，依次检查本机开关、系统 PiP 许可、页面是否就绪、是否正在外跳/锁屏/停止。若恢复后显示缩小，记录系统 PiP 状态、运行代次与是否复用了已结束宿主；不要清数据或重建 Ubuntu 作为普通排查步骤。PiP 可能覆盖控件，设备检查应核对真正可见区域，不能直接使用底层布局坐标。

相关回归入口：[PictureInPictureOptInTest](../app/src/test/java/com/deepseekharness/app/ui/PictureInPictureOptInTest.java)、[PictureInPicturePolicyTest](../app/src/test/java/com/deepseekharness/app/util/PictureInPicturePolicyTest.java)、[BrowserPresentationStateTest](../app/src/test/java/com/deepseekharness/app/util/BrowserPresentationStateTest.java)、[BrowserTasksTest](../app/src/test/java/com/deepseekharness/app/ui/BrowserTasksTest.java)。这些软件门禁不能替代设备上的 SystemUI、Task 和两种网页内核检查。网页内核差异见[浏览器内核](browser-engines.md)。
