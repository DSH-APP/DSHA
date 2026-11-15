# 终端工作台落地与本地回归说明

日期：2026-10-25（Asia/Shanghai）。基线 build197 `02f80abeee37f3b107544714c8544ada5a1c40b9`，工作分支 `Sunset-zhongdua-G`。

> 2026-10-26 build198 将 versionCode 升到 198、Sunset 测试包后缀改为 `.SunsetRNE2`，并补上悬浮球/工作台“菜单”的会话侧栏（极简与窄窗口抽屉、≥840dp 内联）。最终 commit `7eaf914` 的 [CI run 40248037705](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40248037705) 成功产出 Standard 测试 APK（2519 项测试，0 失败/0 错误/1 允许跳过）；未安装、未操作手机、未做设备验收。本文 build197 的包名、版本、交互和交付回执按历史记录阅读；build198 详见[本轮报告](<releases/v0.2.1-rc.3-build198-terminal-sidebar-20261026.md>)。

> 2026-10-26 build198 后续源修订：commit `f8117b6` 在 [run 40262205574](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40262205574) 成功产出同 versionCode 的 Standard 测试 APK（2526 tests / 0 fail / 0 error / 1 allowed skip，Lint 0 error/346 warnings）。新增平板内联/抽屉重判定、深色侧栏、短窗密集底栏和“结束全部”逐项核验。未安装、未操作手机；签名仍为临时测试证书。详见[平板 follow-up 报告](<releases/v0.2.1-rc.3-build198-terminal-tablet-20261026.md>)。

> 本文记录实现范围与回归方法，不将 HTML 模拟、宿主 JUnit 或 CI 构建冒称为手机验收。目标是独立测试包；不发布 Release、不覆盖正式版、不自动安装或操控手机。最终提交、CI run/attempt、附件与 SHA 以实际交付回执为准。

## 已落实的设计

- **内容优先**：同 task 的非导出终端宿主；默认黑底极简、原生 TerminalView 几乎占满工作区，系统栏与输入法安全区保留。会话/显示设置按需展开。页面内 52dp 菜单球可拖动、取消和吸边，不申请跨应用悬浮权限。可选工作台在当前窗口 ≥840dp 时显示侧栏。
- **页面不等于会话**：Main 和终端宿主销毁只解绑；返回保留 Main 原根页/子页及草稿。PTY/simple 会话仍归同一进程 owner；所有会话关闭后重进不自动新建。新进程不能拿旧 Bundle 的 ID 恢复会话或重放命令。
- **独立运行保护**：终端专属 specialUse 前台服务，真实 promotion 后才能创建；精确票覆盖准备和未发布 fork，不依赖 Web 服务。服务丢失是 LOST，不是安全退出，也不自动重启。
- **输入分工**：原生 IME、快捷键、文本粘贴、Enter、焦点恢复分开；方向键按 emulator 模式映射。Ctrl/Alt 一次性消费并在失焦/切会话时清除；两排附加键至少 48dp，窄屏横滚。菜单/硬键/选择回调等应用可见粘贴入口需确认，不附加 Enter；简易模式粘贴只写草稿。
- **明确状态**：页面返回、结束进程、正在核验、退出未确认分开。失败保留 ID、占号、脱敏错误和运行保护，禁止新输入。用户明确重试核验，不自动重发业务。PTY 自然退出核验完成后可保留历史；未确认不能伪装清理完成。
- **兼容与许可**：Termux terminal-view/emulator 保持固定 0.118.0；独立编写工作台，没有移植 GPL 的 Termux App/额外键实现。proot/proroot、Ubuntu10、DSH rc3、包内运行归档锁定版本及正式包身份不升级。

三个非终端主 Tab 沿用当前生产实现；本次没有把预览中的插件/备份/授权模拟动作移植为真实执行，不合并参考分支的无关插件产品改造。

## 运行时身份的重要边界

本次不只是 UI：改变了终端创建准入与退出状态投影。既有受管描述符将 PTY、owner、Tabs 等启动器源码纳入 hash，新增服务及保护协调器也纳入同一合同。因此 `runtimeId` 应按生成器重算，**不能声称 UI-only 身份不变**。归档版本与 Ubuntu 基础版本保持；已有测试安装若涉及受管更新仍走原维护屏障/RuntimeTrial 事务，不做运行中热覆盖。独立测试包与正式版数据隔离。

## 本机软件验证与失败记录

- 工作区原无 JDK/SDK。已安装 Ubuntu 开发工具 JDK17、JUnit4、PyYAML；未修改 Android 设备权限或文件。
- 工作台纯策略 JUnit：5 项通过。首跑因本机默认 US-ASCII 导致中文注释编译失败，显式 `-encoding UTF-8` 后重跑通过，不计作首次全绿。
- 输入纯逻辑与真实 0.118 KeyHandler 合同：19 项通过；KeyHandler 来自原固定 AAR，不是 mock。新增简易多行 Send 合同首跑遇格式化后的源码断言失配，改为语义等价的空白归一后全套重跑通过。
- 后台保护、owner、Tabs：31 项纯 JVM 检查通过；owner 的 Android/PTy 依赖使用 compile-only 桩，不是 JNI/Android 验收。
- 新增实际 Activity 导航、Back、重建、菜单拖动与主 Looper 合同测试，执行结果以 CI JUnit XML 为准，不能由文件存在推算。
- CI/输入门禁宿主 51 项通过，YAML 解析及 19 个 shell/3 个内联 Python 语法检查通过。完整 build197 源 ZIP 实算 SHA，白名单 12 个原始输入及其当前 pin 已验证。
- 本地 `build.sh` Android 测试因缺 android-37 SDK 退出，完整 Android 编译/Standard 单测/Lint/打包交 GitHub Actions。
- 部分本地 ripgrep/Git 子进程遇到 proroot 目录遍历异常或 SIGSEGV；实际独立测试退出码与 Git 检查失败分开记录，不把它掩盖为产品测试通过。

### CI 运行记录（失败原件全部保留，不合并成一次全绿）

| 运行 | 提交 | 结果 | 证据 |
|---|---|---|---|
| run 40201270323 / attempt1 | `6666aaa0` | 测试源码编译失败：新测试使用 Robolectric 通用 `Shadows.shadowOf()` 时触发 Android37 已移除的 `FingerprintManager` 类型；未执行 JUnit、未产包 | 失败日志与 run JSON 已保存 |
| run 40203536948 / attempt1 | `2a0af1a` | 完整 Standard 单测 2514 项执行，1 项失败：`VirtualScreenObservedPixelsTest.oversizedSingleImageDoesNotAllocateObservationOrChangeObserved` 在自身 3072×2049 位图分配处 `OutOfMemoryError`（虚拟屏用例，非终端相关）；终端新增用例未失败 | 失败日志已保存 |
| run 40203536948 / attempt2（同一提交重跑失败作业） | `2a0af1a` | 单测、七项真实夹具、13 个终端类门禁、Release Lint、未签名 Standard 打包全部通过；签名核验仅因打包回执把 badging 最低 API 键名写死为 `sdkVersion` 而报 `TERMINAL_APK_SDK`，APK 未上传 | 失败日志已保存 |
| [run 40207701003 / attempt1](<https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40207701003>) | `ff4dbb0` | **两个必要作业均成功、无跳过步骤**；单测 2514/0失败/0错误/1项允许的平台不适用跳过；七项真实夹具与13类终端门禁通过；Lint、assemble、签名、精确 APK 校验、上传均实际执行 | 已下载 APK 与公开证据并独立核验 |

对应修订（均不降低断言、不跳过用例）：测试改用 `Shadow.extract` 精确类型端口；新增独立终端通知的身份断言从 10 项更新为 11 项并锁定 9065；测试进程内存上限提高到 2048m 以消除与断言无关的 25MB 位图 OOM；打包回执同时接受 `sdkVersion`/`minSdkVersion` 两种 aapt2 拼写但仍精确要求 30/37，并在回执中记录实际使用的键名。后续 CI 结果单独归属新 SHA。

### 本次测试包（下载后已核验，未安装）

- CI 附件：[dsha-terminal-ff4dbb07.apk](<https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40207701003/artifacts>)（需该私有仓库权限）；证据附件 `terminal-evidence`。
- 307,823,326 字节，SHA-256 `df3a96d5241ef1a4de87e3d8aa38d60a1f78080c252613c5226ff5614e456f58`；API artifact digest、`SHA256SUMS` 与实际下载字节一致。
- 包名 `dsh.android.client.SunsetRNE`，versionCode 197，versionName `0.2.1-rc.3-20261025.2000-dsh0.2.1-rc.3-SunsetTest`，minSdk 30 / targetSdk 37 / arm64-v8a、非调试；v3 签名证书 SHA-256 `734533591482541dbe06e1322a0dd220b313d0cf33b84a76be11a38df575806f`（本次 runner 临时生成，非正式 E7E3，也**不保证覆盖**旧 Sunset 测试包）。
- 清单核对：`.ui.TerminalActivity` `exported=false`、`singleTop`；`.TerminalRuntimeService` `exported=false`、`stopWithTask=false`、`foregroundServiceType=specialUse` 且带专用 subtype 说明；APK 内无私钥类成员。
- runtimeId 为 `f1df01ed6431cce6a56a2b339e4a412fac443f872520ac474480a0f315f80073`（终端启动保护合同变化后重新生成）；Ubuntu 基础版本 10 与 DSH `0.2.1-rc.3` 归档字节未改。


## 本地设备回归清单（本轮尚未执行）

安装前核对 APK SHA、独立包名 `dsh.android.client.SunsetRNE`、证书。测试证书非正式 E7E3；若本次使用临时测试密钥，不保证覆盖已有同包名测试安装。不要为安装测试包删除正式版数据；先备份自己的测试数据，再决定测试安装策略。

| 场景 | 预期 |
|---|---|
| 设置子页→终端→返回 | 原子页/草稿不丢；没有结束会话 |
| 连点终端/通知再次进入 | 单一同 task 宿主；不重复创建 shell |
| 旋转、主题/语言切换、返回 Home 再进 | 同进程原会话、输出和模式保留 |
| 仅终端运行，Web 未启动 | 终端保护通知存在；系统拒绝时不创建 |
| Web+终端→停止 Web | 终端不因 Web 服务退休被结束 |
| 新建两类/多会话、重命名、取消结束 | 身份独立；取消无副作用；结束仅影响指定 ID |
| 结束失败/未确认 | 保留条目、错误、保护；输入禁用；显式核验可达 |
| 全部结束→返回→重进 | 空态保持，显式新建可用 |
| 中文/日文/Emoji 组合输入与硬件 Ctrl/Alt/Enter | 无拆词、重复发送或粘修饰键 |
| 粘贴单行/多行/末尾换行/控制符 | 来源/风险提示；不隐式附加 Enter；多行需用户确认 |
| vim/tmux/top + IME/横竖屏/字号 | 行列 resize 正确；方向键模式正确；回看位置合理 |
| 320/360dp、840dp、短横屏、分屏 | 关键按钮可达、球不越界、按键不小于48dp |
| TalkBack、1.3/2倍字体、RTL、明暗主题 | 文字状态可识别，球可无需拖动复位；终端保持LTR |
| 强停/进程死亡后再开 | 不伪装恢复、不重放旧命令；提示手动新建 |

### 必须保留的未验/限制

1. 未安装、启动或控制手机；没有真实 IME/TUI/后台 Doze/OEM 存活和 LayoutAuditInstrumentation style 的设备结论。
2. 固定 TerminalView 为 final。应用菜单、终端选择粘贴回调、硬键/鼠标入口可确认；**输入法自身剪贴板直接 commitText 无法可靠区别粘贴与合法组合输入**，保留原生行为，不声称该路径也被确认拦截。
3. 多行粘贴去掉末尾 CR/LF 并不保证任意 shell 不执行内部换行；确认文案明确风险，支持的应用沿 emulator bracketed paste 语义。
4. 简易 STOPPED/FAILED 标签保守持有保护，需显式结束核验；不是“进程已退出就自动释放所有保护”。前台服务也不保证系统永远不回收。
5. 本次没有重做三个非终端主 Tab、升级 Termux 依赖或改变设备命令安全策略。

## 回滚

尚未安装则无需设备回滚。源码可回到上述 build197 基线，保留已有设计预览和用户未提交内容；不要用回退 APK 或手改 runtimeId 绕过受管数据/运行时校验。对已安装独立测试版的回退，先导出测试数据并按签名/版本和既有维护机制操作；不得为了测试删除正式应用数据。

## 文件导航

- [终端行为合同](<terminal.md>)
- [CI 固定输入与交付合同](<terminal-ci-20261025.md>)
- [原预览](<design/terminal-20261025/preview.html>)（仅模拟，保留历史设计来源）
- [前期实施指南](<design/terminal-20261025/implementation-guide.md>)（历史计划，不作为本轮验收记录）
