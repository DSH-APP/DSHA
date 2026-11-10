# 全屏终端保留清单：点击即拉起 TerminalActivity

本文件记录 build202 试点中**必须保留**的全屏终端功能与部分优化设计。它们不是本批新写的代码，而是从 `Sunset-zhongdua-G` 合入后已经存在于当前工作区的事实；本批只做设计整理与清单化，防止后续做 v5 外壳时把终端语义改回去。

## 1. 来源与身份

| 项 | 实际值 |
|---|---|
| 合并提交 | `6a0cf185f6c580e73c96bd5d0186ae0cb77f33b1` |
| 来源分支 | `Sunset-zhongdua-G` |
| 第一父提交 | `4452c9d534e087125678d6b2235e824b6cf850a8`（build201 线） |
| 合并日期 | 2026-10-26 12:50:18 +0000 |
| 合并说明 | “同步 Sunset-zhongdua-G 全屏终端与界面优化到 Sunset-3090-Q” |
| 合并规模 | 与 `Terminal/Pty` 相关的路径：**100 个新增（A）+ 12 个修改（M）** |
| 版本码 | `versionCode 202` |
| 版本名 | `0.2.1-rc.3-20261026.2115-dsh0.2.1-rc.3` |
| 正式包名 | `dsh.android.client` |
| 本试点测试身份 | `dsh.android.client.SunsetRNE` |
| 测试签名 | 本机测试证书，**不是 E7E3 正式包** |

来源依据：

- 合并说明与冲突收口：[`git show -s 6a0cf18`] 原文含“合入 TerminalActivity 全屏宿主、会话保护、附加键、侧栏三向态与横屏压缩”“测试包后缀统一 `.SunsetRNE`”。
- 构建配置：[app/build.gradle#L42](../../../app/build.gradle#L42) 定义 `dsha.sunsetTest` / `DSHA_SUNSET_TEST`；[#L56](../../../app/build.gradle#L56) `applicationId "dsh.android.client"`；[#L60-L62](../../../app/build.gradle#L60-L62) `versionCode 202` 与版本名；[#L75-L77](../../../app/build.gradle#L75-L77) standard flavor 在 `sunsetTest` 时加 `.SunsetRNE` 后缀。
- 试点范围：[build202 变更说明](../../releases/build202-changes.zh.md)：“点击「终端」直接拉起独立全屏 TerminalActivity，返回只关闭页面、不结束会话；侧栏三向态、手机 7+7 / 平板 8+8 附加键与横屏三列两行压缩随构建合入。”
- 历史包名区分：build198 的测试包是 `dsh.android.client.SunsetRNE2`（见仓库 `AGENTS.md` 历史条目）；build202 才是 `.SunsetRNE`。两者不能互相覆盖，也不要混写。

## 2. 保留清单

| # | 功能 | 必须保留的行为 | 源码/资源 | 对应测试 |
|---:|---|---|---|---|
| 1 | 全屏宿主 | 同 task、非导出、`singleTop`、`adjustResize`；只承载终端视图，不套主壳 | [TerminalActivity.java](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L41)、[AndroidManifest.xml#L100-L107](../../../app/src/main/AndroidManifest.xml#L100-L107) | `TerminalActivityLifecycleTest`（14） |
| 2 | 点击即拉起 | 底部“终端”是动作，不是切换页；门禁、去重、异常提示；保留来源页与草稿 | [MainActivity.java#L358-L364](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L358-L364)、[#L640-L663](../../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java#L640-L663) | `TerminalNavigationTest`（4） |
| 3 | 会话属于进程级 owner | 页面只持有视图；PTY/简易两组独立；关闭必须指定会话并核验退出 | [PtyTerminalFragment.java#L42](../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java#L42)、[TerminalSessionOwner.java#L17](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java#L17) | `TerminalSessionOwnerTest`（10）、`TerminalTabsTest`（7） |
| 4 | 运行保护 | 仅保护用户可见操作开启的终端；不依赖 Web 服务，不从服务恢复回放 shell；逻辑租约不等于进程退出证明 | [TerminalRuntimeService.java#L25](../../../app/src/main/java/com/deepseekharness/app/TerminalRuntimeService.java#L25)、[TerminalRuntimeProtection.java#L14](../../../app/src/main/java/com/deepseekharness/app/core/TerminalRuntimeProtection.java#L14) | `TerminalRuntimeProtectionTest`（14）、`TerminalRuntimeTest`（1） |
| 5 | PTY 内容面板 | 原生 `TerminalView`；绑定 owner 当前会话；pause/destroyView 只解绑 UI，不关 shell | `PtyTerminalFragment` | `TerminalPtyThreadContractTest`（3）、`TerminalInputContractTest`（9） |
| 6 | 附加键 | 手机 7+7；平板 8+8；每行最多 10 个、最多 2 行；两套布局分别保存在 `term_extra_keys_phone` / `term_extra_keys_tablet`；编辑保存只重建当前 PTY 视图 | [TerminalExtraKeys.java#L21-L25](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExtraKeys.java#L21-L25)、[#L61-L86](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExtraKeys.java#L61-L86)、[ExtraKeysDialog.java#L26](../../../app/src/main/java/com/deepseekharness/app/ui/ExtraKeysDialog.java#L26) | `TerminalExtraKeysTest`（19） |
| 7 | 侧栏三向态 | 系统 / 白天 / 黑夜；只改外观，不重启会话；结束中禁用切换 | [TerminalActivity.java#L355-L382](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L355-L382)、[#L677-L700](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L677-L700)、[ThemeController.java](../../../app/src/main/java/com/deepseekharness/app/ui/ThemeController.java) | `TerminalThemeTest`（6） |
| 8 | 横屏三列两行压缩 | `width > height && height < 1000dp` 用 `shortLandscape`；底栏 6 个动作固定 3 列 × 2 行、44dp、10.5sp、短标签 + 完整无障碍名；竖屏与超高窗口恢复两列三行 | [TerminalWorkspacePolicy.java#L69-L74](../../../app/src/main/java/com/deepseekharness/app/util/TerminalWorkspacePolicy.java#L69-L74)、[TerminalActivity.java#L831-L873](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L831-L873) | `TerminalWorkspacePolicyTest`（7）、`TerminalActivityLifecycleTest` 的短窗/密集底栏用例（14 项内含） |
| 9 | 返回不结束会话 | 返回优先级：弹层 → 会话侧栏 → 文本选择 → IME → 关页面；`leavePage()` 只补同 task 主界面并 `finish()`，绝不 `shutdown/exit/Ctrl+D/stopWeb` | [TerminalActivity.java#L96-L119](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L96-L119)、[#L1746-L1753](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1746-L1753)、[TerminalWorkspacePolicy.java#L16-L28](../../../app/src/main/java/com/deepseekharness/app/util/TerminalWorkspacePolicy.java#L16-L28) | `TerminalActivityLifecycleTest`（14）、`TerminalNavigationTest`（4） |
| 10 | 结束与 UNKNOWN 分离 | 关闭中禁用输入；退出核验失败保留会话与保护；`isCloseUnconfirmed()` 显示“待核验”，不先删条目 | [TerminalActivity.java#L1117-L1178](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1117-L1178)、`TerminalSessionOwner` 的关闭核验 | `TerminalSessionOwnerTest`（10）、`TerminalActivityLifecycleTest` |
| 11 | 重命名不改进程身份 | 重命名只改展示标题；不新建、不迁移会话 | [TerminalActivity.java#L1454-L1476](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java#L1454-L1476) | `TerminalSessionOwnerTest`、`TerminalTabsTest` |

### 2.1 附加键的实际默认值

手机（`Profile.PHONE`，`smallestWidthDp < 600`）：

```text
第 1 行：ESC  /  -  HOME  ↑  END  PGUP
第 2 行：TAB  CTRL  ALT  ←  ↓  →  PGDN
```

平板（`Profile.TABLET`，`smallestWidthDp >= 600`）：

```text
第 1 行：ESC  TAB  CTRL  ALT  ←  ↓  ↑  →
第 2 行：CC  CD  CZ  HOME  END  PIPE  /  -
```

`MAX_KEYS_PER_ROW = 10`、`MAX_ROWS = 2`；`decode()` 对空值/非法值回退到默认布局，`sanitize()` 去重并限制行数/行内键数。默认值来自 [TerminalExtraKeys.java#L71-L86](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExtraKeys.java#L71-L86)，不是从原型截图推断。

## 3. 源码清单

### 3.1 终端宿主与面板

| 文件 | 角色 |
|---|---|
| [TerminalActivity.java](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalActivity.java) | 同 task 全屏工作台；侧栏、底栏、主题段、会话行、返回处理 |
| [TerminalPane.java](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalPane.java) | 宿主持有的内容面板契约 |
| [PtyTerminalFragment.java](../../../app/src/main/java/com/deepseekharness/app/ui/PtyTerminalFragment.java) | 原生 PTY 内容面板 |
| [TerminalFragment.java](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFragment.java) | 简易终端面板 |
| [TerminalFloatingMenu.java](../../../app/src/main/java/com/deepseekharness/app/ui/TerminalFloatingMenu.java) | 页面内悬浮球（非系统悬浮窗） |
| [ExtraKeysDialog.java](../../../app/src/main/java/com/deepseekharness/app/ui/ExtraKeysDialog.java) | 附加键编辑；只写偏好，不新建 Activity/布局 |

### 3.2 会话、策略与运行保护

| 文件 | 角色 |
|---|---|
| [TerminalSessionOwner.java](../../../app/src/main/java/com/deepseekharness/app/core/TerminalSessionOwner.java) | 进程级 owner；PTY/简易两组会话与关闭核验 |
| [TerminalTabs.java](../../../app/src/main/java/com/deepseekharness/app/util/TerminalTabs.java) | 标签身份、显示编号、关闭中状态 |
| [TerminalSession.java](../../../app/src/main/java/com/deepseekharness/app/util/TerminalSession.java) | 简易终端会话与队列输入 |
| [PtySession.java](../../../app/src/main/java/com/deepseekharness/app/PtySession.java) | PTY 出生身份、JNI 握手、退出核验 |
| [TerminalExtraKeys.java](../../../app/src/main/java/com/deepseekharness/app/util/TerminalExtraKeys.java) | 附加键纯逻辑：形态、默认布局、编解码 |
| [TerminalWorkspacePolicy.java](../../../app/src/main/java/com/deepseekharness/app/util/TerminalWorkspacePolicy.java) | 返回层级、内联/抽屉宽度、横屏压缩、悬浮球位置 |
| [TerminalRuntimeService.java](../../../app/src/main/java/com/deepseekharness/app/TerminalRuntimeService.java) | 终端专属前台服务；只在可见 Activity 请求时提升 |
| [TerminalRuntimeProtection.java](../../../app/src/main/java/com/deepseekharness/app/core/TerminalRuntimeProtection.java) | 票据/租约/超时；逻辑租约不是退出证明 |

### 3.3 资源

- [values/colors.xml](../../../app/src/main/res/values/colors.xml)（23 个 `terminal_*`）与 [values-night/colors.xml](../../../app/src/main/res/values-night/colors.xml)（20 个）。
- `app/src/main/res/drawable/bg_terminal_*.xml`、`ic_terminal_*.xml`、`color/terminal_theme_text.xml`、`values/terminal_workspace.xml`。
- 终端输出区固定黑底由 `terminal_surface #000000` 承担；外壳颜色走 `terminal_panel/accent/line/...`，不跟随输出区。

## 4. 测试类清单

`app/src/test/` 下共有 **15 个** `Terminal*Test.java`，合计 **132 个 `@Test`**。数量为源码静态计数，不代表本轮执行结果。

| 测试类 | `@Test` 数 | 类型 | 覆盖重点 |
|---|---:|---|---|
| `core/TerminalPtyThreadContractTest` | 3 | Robolectric | PTY 线程/启动合同 |
| `core/TerminalRuntimeProtectionTest` | 14 | 纯 JVM | 票据、超时、准入、丢失 |
| `core/TerminalSessionOwnerTest` | 10 | 纯 JVM | owner 会话、关闭核验、代次 |
| `runtime/TerminalRuntimeTest` | 1 | 纯 JVM | 运行时保护 |
| `ui/TerminalActivityLifecycleTest` | 14 | Robolectric | 全屏宿主、返回、抽屉、短窗、密集底栏、重建 |
| `ui/TerminalFloatingMenuTest` | 4 | Robolectric | 悬浮球点击/位置/键盘 |
| `ui/TerminalInputContractTest` | 9 | 纯 JVM | 输入依赖与生产接线合同 |
| `ui/TerminalNavigationTest` | 4 | Robolectric | 导航/宿主拉起 |
| `ui/TerminalThemeTest` | 6 | Robolectric | 日夜 token、黑底输出、六动作、图标描述 |
| `util/TerminalExtraKeysTest` | 19 | 纯 JVM | 7+7 / 8+8、编解码、去重、恢复默认 |
| `util/TerminalInputTest` | 10 | 纯 JVM | DSHA 输入策略 |
| `util/TerminalReadyTest` | 1 | 纯 JVM | 就绪判定 |
| `util/TerminalSessionTest` | 23 | 纯 JVM | 握手、EOF、写失败、过期输出 |
| `util/TerminalTabsTest` | 7 | 纯 JVM | 标签身份与显示编号 |
| `util/TerminalWorkspacePolicyTest` | 7 | 纯 JVM | 返回不结束、内联/抽屉、短窗、悬浮球 |
| **合计** | **132** | 5 Robolectric / 10 纯 JVM | — |

合并提交新增 10 个测试类（上表中 `core/TerminalPtyThreadContract`、`core/TerminalRuntimeProtection`、`ui/TerminalActivityLifecycle`、`ui/TerminalFloatingMenu`、`ui/TerminalInputContract`、`ui/TerminalNavigation`、`ui/TerminalTheme`、`util/TerminalExtraKeys`、`util/TerminalInput`、`util/TerminalWorkspacePolicy`），修改 `TerminalSessionOwnerTest` 与 `TerminalTabsTest`；其余 3 个是既有测试。

前一轮终端报告提到的 `TerminalFloatingPanel…` 在仓库中不存在；实际类名是 **`TerminalFloatingMenuTest`**（`TerminalFloatingMenu` 是页面内悬浮球，不是系统悬浮窗）。

## 5. 与 v5 原型终端的差异（不能混用）

| 项 | v5 HTML 原型 | 原生 build202 |
|---|---|---|
| 键数 | 14 键，CSS 固定 7 列 × 2 行 | 手机 7+7；平板 8+8；可编辑 |
| 输入 | 页面内 `<input>`，本地模拟命令 | 原生 `TerminalView` 的 InputConnection；PTY 真进程 |
| 会话 | 页面内存数组，刷新即丢 | 进程级 owner；跨页面/旋转保持 |
| 返回 | 页面内路由 | `TerminalActivity.finish()`，会话不结束 |
| 主题 | 页内 `.app.light` 切换 | 三向态 + 终端 `terminal_*` 资源 |
| 黑底 | CSS `#000` | `terminal_surface #000000` 固定 |
| 字号 | CSS `--terminal-font` 默认 13px | 原生默认 13sp，范围 8–24sp |
| 运行保护 | 无，纯模拟 | `TerminalRuntimeService` + 租约 |
| 横屏压缩 | 原型不模拟原生短窗 | `shortLandscape(<1000dp)` + 密集底栏 |
| 悬浮球 | 页面内模拟 | `TerminalFloatingMenu`，无系统悬浮窗权限 |

结论：**原型只能证明布局与交互意图，不能证明原生附加键数量、IME、PTY 行为或会话保护。** 任何“原型已验 = 原生已验”的写法都不成立。

## 6. 保留边界与暂缓

必须保留（本批不得在 v5 外壳改造中删改）：

- `TerminalActivity` 同 task 全屏宿主与 `AndroidManifest` 的 `exported=false` / `singleTop` / `adjustResize`。
- `MainActivity.openTerminal()` 的统一门禁、去重与“导航返回 false”。
- 进程级 `TerminalSessionOwner`；关闭必须按 `(类型, 会话 ID)` 核验。
- `TerminalRuntimeService` / `TerminalRuntimeProtection` 的可见启动准入与失败保留。
- 返回只 `finish()` 页面，不注入 `exit`/Ctrl+D/HUP，不 `stopWeb`。
- 手机 7+7 / 平板 8+8 与 `term_extra_keys_*` 偏好键名。
- 三向态、横屏三列两行、44dp 触控与完整无障碍名。

暂缓 / 未验（不要写成已完成）：

- **真机验收**：build202 变更说明明确未做真机安装、TalkBack、字体缩放、横竖屏全量验收。本批也没有运行 Gradle、没有装 APK。
- 进程被系统杀死/强停/重启后的原 PTY 接回：不承诺、不自动重放历史命令。
- 同一会话组两个可见宿主同时渲染：owner 的 observer 单槽约束未变，首版仍按单可见终端设计。
- bracketed paste、中文 IME 组合输入、硬件 Ctrl/Alt、软键盘与 TUI（vim/tmux/htop）的完整矩阵：需要真机单独验。
- RTL 壳 / 1.3×、2× 字体 / OEM ROM 后台限制：未验。
- 原型 14 键外观不能作为原生 8+8 平板的验收依据。

## 7. 证据边界

- 本文的“存在”均来自当前工作区源码静态读取与 `git show`/`git diff` 元数据；**没有运行 `gradle`，没有执行任何测试类**。
- 测试类数量是 `grep -c '@Test'` 的静态结果（15 类 / 132 项）；仓库历史 CI 数字（如 2551 tests）属于其他轮次，不转记为本轮结果。
- 未安装 build202 APK，未在实体手机或平板上操作终端；所有“保留”是代码合同与设计约束，不是设备验收结论。
- 合并来源分支名来自合并提交说明；本地只有 `Sunset-3090-Q` 分支包含该提交，`Sunset-zhongdua-G` 未作为本地分支保留。
