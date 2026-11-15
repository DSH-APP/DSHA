# 2026-10-27 Sunset 同步与终端选区菜单多语言

本轮将远端分支更新合并到本地 `Sunset-3090-Q`，并修复 PTY 终端长按选字时 Copy / Paste / More… 仅显示英文的问题。此次交付为源码和宿主验证；未推送、发布、构建或安装 APK，未操作手机，不运行 Low。

## 同步来源与保留内容

- 起点：`88e09ee9e3125e462c2260871ed70582b86d3a8c`。
- 远端 Sunset 首轮：`554cbe97aaff6f5c590c752a9da71772b9035d48`，已通过合并提交 `2209188060b0898fdeb07b0ee2a9c68972fc81d5` 纳入。
- 收尾时远端 Sunset 新增 `e8c6f4b66c046ecf02f195ca5ab4e9e647a66516`（build204 版本号/说明更新），第三笔合并纳入其历史。版本冲突继续采用主线 build205；当前十语说明与主线原 build204 历史保持一致，Sunset 独立 build204 说明保留在该远端提交历史。
- 远端默认分支 `codex/prerelease-190`：`c19daa8615d81c194a703eb7ae20b80736db8f0a`，build205；第二笔合并 `f16e03c` 纳入该历史及菜单资源修复。
- 远端 `Sunset-zhongdua-G`：`1d3910c2ccd513d6778d6498ed140ef49e9d0092`，已在 Sunset 合并历史中。
- 本地插件 67 条提示与终端 61 条提示保留；接入远端终端/主页 74 条，并保留远端撤除电脑连接的结果。
- 继续保留 Sunset 插件查询与环境准入解耦修订，以及 Standard CI 的本地语言检查。固定输入仍使用原有 build197 源码归档锁，未退回较旧输入。

合并前建立 `backup/sunset-before-sync-20261027`。用户原有的两处环境身份修改先保存为 `pre-sync-20261027-environment-identity` stash，合并后恢复为未提交修改；原有未跟踪文件不加入本次提交。stash 留作额外备份。

## 菜单实现

锁定依赖 `com.termux.termux-app:terminal-view:0.118.0` 的 [TextSelectionCursorController](https://github.com/termux/termux-app/blob/v0.118.0/terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java) 使用库资源 `copy_text`、`paste_text`、`text_selection_more` 创建浮动菜单。应用此前没有同名翻译覆盖。

新增 [默认选区菜单资源](../../app/src/main/res/values/terminal_selection_strings.xml)，通过现有 [资源生成器](../../tools/localize-android-ui.py) 生成其他九种语言的同名资源。复制复用 `m0102`，粘贴复用远端 `termhome0016`，仅新增 `termselect0001`“更多…”。[补充目录](../../tools/i18n/messages-extra.json) 与八种译文同步维护，译文摘要按实际字节更新。

| 语言 | Copy | Paste | More… |
|---|---|---|---|
| 简体中文 | 复制 | 粘贴 | 更多… |
| 繁體中文 | 複製 | 貼上 | 更多… |
| English | Copy | Paste | More… |
| Русский | Копировать | Вставить | Ещё… |
| 日本語 | コピー | 貼り付け | その他… |
| 한국어 | 복사 | 붙여넣기 | 더보기… |
| العربية | نسخ | لصق | المزيد… |
| Português | Copiar | Colar | Mais… |
| Latina | Exscribe | Insere | Plura… |
| Français | Copier | Coller | Plus… |

同名资源适用于初始布局及切换会话后动态创建的 TerminalView。菜单动作、复制粘贴保护、会话生命周期及终端正文方向沿用既有实现。简易终端使用 Android 系统选字菜单，其 OEM/拉丁语表现不属于此次 Termux 资源覆盖的验证结论。

## 验证范围

- [终端/主页语言回归](../../tools/test-terminal-home-i18n.py)：5 项通过，含十语 760 条生成 Java 字典查找及格式化检查、三项资源名的完整性与唯一性。
- [语言目录测试](../../tools/test-ui-language-catalog.py) 的 `LanguageCatalogTest`：7 项通过；目录 4,828 条 × 10 语，无重复 ID 或中文源。
- [UI 文案扫描测试](../../tools/test-ui-i18n.py)：9 项通过；资源生成 `--resources-only --check` 通过。
- Java17 定向 JUnit：8 类 78 项通过，覆盖 UiText、UiStateText、UiLanguagePreference、TerminalInput、TerminalTabs、TerminalWorkspacePolicy、TerminalExtraKeys、EnvironmentTaskGate。使用生产 SystemLanguage 与当前生成目录，无 Android 资源编译。
- Standard 宿主 6 组通过：固定输入、架构边界、CI 配置、运行时输入、运行时源码证明、宿主测试执行器。完整测试清单 225 项登记检查通过，不表示执行了全部 225 项。
- 运行时描述按最终合并输入生成；实际核对 182 项资产、441 项 launcher 输入，无延后大归档。源码卫生与修改范围空白检查通过。

原始失败保留：本机 Git 默认合并策略触发 proroot 崩溃并遗留空索引锁；核实无活动 Git 进程、锁为空后清理，第二轮使用 `resolve` 三方策略完成逐文件合并。第一次额外 JUnit 编译使用的测试语言替身缺少接口，改用生产 SystemLanguage 后 78 项通过。最终资产检查发现 Sunset 留存的依赖证明仍绑定旧 `d77261bf…` 归档，按本地已与远端匹配的 `36c3c40e…` 真实归档重新生成依赖证明及运行时描述。

本机未执行完整 Android 编译、Robolectric 的实际库资源 ID 合并测试、Release Lint、APK 打包或真机菜单检查。Git 远端读取成功；本轮 GitHub CLI 的 API 访问失败，因此未将历史 CI 结果作为本次源码验证结论。
