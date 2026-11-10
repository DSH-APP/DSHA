# 终端操作面板、会话侧栏自适应与悬浮球调整

日期：2026-10-27。基于本地 `Sunset-3090-Q` 分支 `ca75f5b` 的工作区源码，保持既有 versionCode 205。本记录属于源码修改与软件检查，不是 APK、发布或设备验收回执。开始时已有的环境身份实现及测试修改未纳入本次修改范围。

## 完成的行为

- PTY 选字工具条的“更多…”现在打开“终端操作”面板，显示当前会话名，提供粘贴文本、查看终端输出、显示设置、快捷按键、会话管理五项入口。初始终端与切换会话后替换的原生视图共用接线；菜单回调核对会话、owner、视图及生命周期身份。切换会话或离开页面会关闭旧菜单。
- 输出预览分别保存打开时的“当前可见屏幕”和“已保留的历史输出”，当前屏幕跟随用户实际滚动位置。复制、分享和另存为文本使用相同快照；打开系统保存位置选择器后继续使用选择时的文本，不重新读取当前终端。旋转使用 Fragment ViewModel 保持快照；进程重建后显示预览失效，不自动获取其他会话内容。
- 输出使用现有敏感数据遮盖规则，最终单份文本上限为 65,536 个 Java 字符。长历史保留最近内容并提示截断；预算若切开软换行逻辑行，会舍弃该残缺逻辑行。单个超长逻辑行无法形成预览时，显示明确提示并禁用导出。该规则不保证识别所有秘密，也不修复可见屏幕自然从逻辑行中间开始时缺少前文的限制。
- 粘贴确认分开呈现来源/多行说明与等宽文本预览；正文在短屏中滚动，底部确认按钮保留。沿用原来的目标会话校验、危险控制字符处理和不追加回车规则。
- 输入中断按钮改为“关闭提示”和“恢复输入”；不会自动重发旧输入。重命名补充留空恢复默认名称及最多 80 字符的说明。
- 会话侧栏按自身实际宽度、可用高度和字体缩放调整。动作区采用 3/2/1 列，保留至少 48dp 触摸高度；窄栏或大字体下，会话状态移到名称下方。固定区域无法为会话列表保留足够空间时，头部、新建、列表和底部操作共同滚动。保留 RTL、焦点、列表相对滚动位置与键盘避让，并缓存布局输入，避免每轮测量反复设置文字和间距。
- 悬浮球从 52dp 外观缩为约 40dp，完整点击/拖动区域为 48dp；贴边、拖动、位置恢复及键盘避让保持。
- 十语目录新增 81 条：既有遗漏 56 条、扩大终端调用链检查找到的 3 条、输入/重命名约定 3 条，以及新增面板和预览 19 条。总目录为 4,909 条 × 10 语，新增 ID 为 `termhome0075` 至 `termhome0155`。检查覆盖完整终端调用链、辅助文案调用、三目模板和布局资源。

## 已执行的软件检查

| 检查 | 结果与范围 |
| --- | --- |
| JDK 17 主机 JUnit | 62 项通过，0 失败。覆盖输出快照、敏感数据处理、输入策略、侧栏布局策略、快捷按键及已有输入接线合同。 |
| 输出快照专项 | 11 项，使用实际 TerminalEmulator/TerminalBuffer；覆盖可见区、历史、快照不可变、Unicode 与软换行、脱敏、最终长度上限、残缺逻辑行、滚动边界和备用屏幕。 |
| 终端十语检查 | 7/7 通过，包含生成 Java 字典的实际查找与格式化测试。 |
| 通用语言检查 | `test-ui-i18n.py` 9/9、`test-ui-language-catalog.py` 9/9；新增最后一条译文后再次执行终端完整检查及全局 `check-ui-i18n.py`，均通过。 |
| 全局文案门禁 | 新增问题 0、失效声明 0；既有 baseline 保持。 |
| Android 语言资源检查 | resources-only check 通过，84+178 个资源名 × 10 语；本次没有需要更新的资源 XML。 |
| Java 格式 | 12 个本次 Java 文件使用固定 google-java-format 1.22.0 处理，精确 Java token 一致；4 个新增文件已登记格式覆盖清单。 |
| 补丁空白 | `git diff --check` 通过。 |

主机 JUnit 使用实际终端模拟器与缓冲区，仅为 Android Base64、TerminalSession 类型和系统语言入口提供主机适配；Android API 常量来自核对发布摘要的公开 API 定义包。没有运行 Android 界面、真实 PTY 或原生进程。主机测试入口与各轮日志保存在本地 `artifacts/terminal-more-20261027/`，未作发布资产。

初次主机编译因缺少 KeyHandler 的 Android 常量依赖失败；补齐依赖后先通过 49 项，再加入长输出回归和原有输入接线合同，最终 62 项通过。新增文案词法测试的首轮夹具转义失败也已修正后完整重跑，未将最初尝试计为通过。

## 尚未验证

已尝试下列 Standard 测试命令；构建脚本在配置前报告缺少带 `android-37` 平台包的 Android SDK，以退出码 1 结束：

```bash
bash build.sh :app:testStandardDebugUnitTest \
  --tests com.deepseekharness.app.util.TerminalOutputSnapshotTest \
  --tests com.deepseekharness.app.util.TerminalWorkspacePolicyTest \
  --tests com.deepseekharness.app.ui.TerminalFloatingMenuTest \
  --tests com.deepseekharness.app.ui.TerminalActivityLifecycleTest \
  --tests com.deepseekharness.app.ui.TerminalThemeTest \
  --tests com.deepseekharness.app.ui.TerminalInputContractTest \
  --tests com.deepseekharness.app.ui.TerminalNavigationTest
```

因此完整 Android 编译、Robolectric 视图用例和真机交互均未完成。已补充但未执行的界面用例涵盖 180dp 短窗口、2 倍字体、IME/RTL、焦点恢复及悬浮球尺寸。系统分享/保存选择器、旋转与 TalkBack 的实际显示还需在具备 SDK 的环境及设备上验收。

本轮未构建、安装或发布 APK，未操作手机，未运行 Low 任务，未改变版本或签名身份。
