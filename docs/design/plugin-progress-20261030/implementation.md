# 插件页进度条 · F 方案落地记录

日期：2026-10-30 · build211

## 决定

- 采用设计预览里的 **F · 混合**：不确定态呼吸、确定态流光。
- 直接替换 `plugin_list_header.xml` 里的平台 `ProgressBar`，不新增第二条进度条。
- 成功收束为绿色 100%，失败收束为红色、停在本次任务见过的最大 percent；两者短暂停留后淡出。
- 删除下载源按钮下方的常驻长说明；原文案移入下载源右侧新增的圆形感叹号按钮弹窗，弹窗同时显示最近一次任务结果（失败时就是报错）。
- 两处（插件市场 / 插件管理）继续共用同一个 header 和同一个 `pluginBusy`。

## 改动

| 文件 | 内容 |
|---|---|
| `app/src/main/java/com/deepseekharness/app/ui/PluginTaskProgress.java` | 新增 4dp 自绘控件：呼吸 / 流光 / 绿色成功 / 红色失败，时间相位推进，不用无限 `ValueAnimator` |
| `app/src/main/res/layout/plugin_list_header.xml` | `ProgressBar` → `PluginTaskProgress`；移除常驻 hint；下载源按钮右侧新增 `btnPluginDownloadInfo` |
| `app/src/main/res/drawable/bg_plugin_info.xml`、`ic_plugin_info.xml` | 圆形按钮底与圆圈感叹号矢量 |
| `app/src/main/java/com/deepseekharness/app/ui/PluginFragment.java` | `setTask` / `finish(success, maxPercent)` / `hide`；按 `State.result` 收束；弹窗写入原文案 + 最近结果 |
| `app/src/main/java/com/deepseekharness/app/core/PluginRepository.java` | `State` 新增 `result`（NONE / SUCCESS / FAILURE）；最终 drain、队列满、执行器不可用、选择错误、清理未确认等路径写入结果 |
| `app/src/main/res/values/shell_ui.xml`、`values-night/shell_ui.xml` | 新增 `shell_sheen`：日间 38% 白、夜间 16% 白 |
| `app/src/test/java/com/deepseekharness/app/ui/PluginFragmentRenderingTest.java` | 进度断言改读 `PluginTaskProgress.percent()` |
| `tools/i18n/messages.json` 与 8 份翻译 | `plugin_net_5` 保留并在弹窗中使用，不再常驻页面 |

## 参数

| 项 | 值 |
|---|---|
| 条高 / 圆角 | 4dp / 高度一半 |
| 轨道 | `shell_raised` |
| 填充 | `shell_accent` |
| 不确定态 | 26% 宽光带，2.0s 呼吸，透明度 .32 → .92，scaleX .82 → 1.22 |
| 确定态 | 240ms `cubic-bezier(.22, 1, .36, 1)` 填充；58% 宽流光，2.2s linear |
| 减少动效 | 不确定态静态 34% 光带；确定态直接落位、不画流光 |
| 成功收束 | 绿色 100%，停 700ms，淡出 240ms |
| 失败收束 | 红色停在本次任务见过的最大 percent（无 percent 时整条红），停 900ms，淡出 240ms |
| 结果判定 | `State.result`：最终 drain 按 `completed` 写 SUCCESS / FAILURE；队列满、执行器不可用、选择错误、清理未确认写 FAILURE。Fragment 在 busy 结束且见过 busy 时收束；入队前的报错只进弹窗，不强行把没出现过的进度条拉出来 |
| 报错覆盖 | 弹窗记录任意 `result != NONE` 的 `State.message`，包括投递前的“环境未就绪”“队列已满”“文件选择器不可用”等 |
| 下载源说明 | 不再常驻；圆形感叹号按钮弹窗显示 `plugin_net_5` 原文 + 最近一次任务结果 |
| 无障碍 | 节点 class 设为 `ProgressBar`；确定态带 `RangeInfo`；只在 25/50/75/100 播报 |

## 验证

已执行：

- `PluginTaskProgress.java` 用 JDK 17 + android-37 `android.jar` 单独 `javac` 通过（仅 `RangeInfo.obtain` 的 API 34 弃用提示，minSdk 30 仍需用它）。
- `python3 -B tools/check-ui-i18n.py`：无新增文案 sink。
- `python3 -B tools/prepare-ui-languages.py`：4929 条 × 10 语言生成通过。
- `python3 -B tools/localize-android-ui.py --resources-only --check`：通过。
- `tools/test-ui-i18n.py`、`tools/test-ui-language-catalog.py`、`tools/test-source-text.py`、`tools/test-dsha-ui-regressions.py`：通过。
- `tools/check-source-hygiene.py`：通过（翻译字节 pin 保持原样，原文案恢复）。
- `git diff --check`：通过。

未执行：

- 完整 `:app:testStandardDebugUnitTest` / `assembleStandardRelease`：本机没有可用的 Android SDK 平台、构建工具与完整 Gradle 依赖缓存，无法在这里跑完整构建。
- 真机像素、刷新率、深色模式、1.3× / 2× 字体与 TalkBack 验收：需要在设备上补。
- 结果判定改为显式 `State.result`，不再靠操作快照顺序或文案猜；入队前的失败也能进弹窗。进度条只在本次页面确实出现过 busy 时才做绿 / 红收束，避免“没开始任务却弹出一条红条”。
- 失败收束的“当前 percent”取本次 busy 期间见过的最大值；若任务在安装阶段转入 `percent = -1` 后失败，会用最大值而不是最后一条状态里的 -1。
