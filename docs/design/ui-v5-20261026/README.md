# DSHA UI v5 设计落点文档：来源、完整性核验与试点边界

日期：2026-10-26  
分支：`Sunset-3090-Q`  
读取时 HEAD：`6a0cf185f6c580e73c96bd5d0186ae0cb77f33b1`（merge：同步 Sunset-zhongdua-G 全屏终端与界面优化）  
性质：**离线 HTML 交互原型的设计整理 + Android 落点建议；本批没有改代码、没有运行 Gradle、没有安装或操作手机。**

本目录只回答三个问题：v5 原型到底是什么、它的数字是否站得住、哪些内容能进本试点、哪些只能暂缓。代码事实来自当前工作区读取；工作区另有 Lead 的未提交改动，本文不把未提交内容当成既成事实。

## 1. 交付物

| 文件 | 内容 |
|---|---|
| [README.md](README.md) | 来源、身份、完整性核验、验证数字与试点边界 |
| [design-tokens.md](design-tokens.md) | 深浅两套颜色 token、字号/圆角/间距/动效，以及与工作区 `shell_ui.xml` 实际资源名的对齐 |
| [page-map.md](page-map.md) | 四根导航、16 个主视图、11 个设置落点与现有原生类的对照 |
| [terminal-preservation.md](terminal-preservation.md) | 全屏终端“点击即拉起”的保留清单、源码与测试类 |
| [preview/home-phone.png](preview/home-phone.png) | 启动主页完整日志（780×1688，412,575 bytes） |
| [preview/terminal-tablet.png](preview/terminal-tablet.png) | 平板黑底终端（2360×1640，222,949 bytes） |
| [preview/engine-menu.png](preview/engine-menu.png) | 运行引擎 / DNS 菜单位置（2360×1640，556,147 bytes） |

三张预览图直接复制自 `preview/` 原始字节，未重新编码、未裁剪；SHA-256 见第 4 节。它们不是真机截图。

## 2. 来源与身份

### 2.1 原件

- ZIP：`/storage/emulated/0/Download/DSHA/DSHA-UI-v5-完整分享包-20261026.zip`
- 解压目录：`<workspace>/ui-v5-extract/DSHA-交互原型-v5-20261026/`（目录名带日期后缀，ZIP 内根目录同名）
- 包内 46 个文件；`SHA256SUMS.txt` 有 45 条（不包含自身），因此 45 条覆盖除摘要清单外的全部包内文件。

### 2.2 `delivery.json` 声明的身份

| 字段 | 值 |
|---|---|
| `revision` | `interaction-v5` |
| `kind` | `offline-html-prototype` |
| `entry` | `打开原型.html` |
| `bytes` / `sha256` | `551350` / `f853010f3760eaedd82a4a53f405bf7cf3f6b8e9b4461639f0befd85f03f847a` |
| `catalogEntries` | `29` |
| `mainViews` | `16` |
| `variants` | A「自适应双栏工作台」/ B「沉浸式聚焦」 |
| `simulationOnly` | `true` |
| `externalNetworkRequests` | `0` |
| 验证 | 6 组共 `724` 项，`javascriptErrors` / `consoleErrors` / `externalNetworkRequests` 均为空 |
| 应用性质 | `package.noNativeApplication = true` |

`使用说明.txt` 同样写明：“这是可交互的界面原型，不是 APK。安装、授权、终端、维护任务以及工作区内容均为本地模拟，不连接 Android 设备或模型服务。” 因此：

- 本文把它当作**离线 HTML 交互原型**，不当作 APK、不当作 Android 运行时证据。
- 原型里的终端、文件、备份、授权和模型输出全部是内存模拟；刷新页面即重置。
- 原型验证是 Chromium 内的交互断言，不是视觉审美评分，也不是 Android 真机或原生性能结论。

## 3. 完整性核验：实算结果

### 3.1 命令与输出

```console
$ sha256sum "/storage/emulated/0/Download/DSHA/DSHA-UI-v5-完整分享包-20261026.zip"
3124fa810093103215d315048765b1575133197821d49a25fe84bb3f60e37af5  /storage/emulated/0/Download/DSHA/DSHA-UI-v5-完整分享包-20261026.zip

$ stat -c '%s' "/storage/emulated/0/Download/DSHA/DSHA-UI-v5-完整分享包-20261026.zip"
3021268
```

用 Python 标准库读取 ZIP 成员（不执行包内脚本）：

```console
$ python3 -c "import zipfile;print(len(zipfile.ZipFile(ZIP).infolist()))"
46
```

包内入口与 `dist` 产物：

```console
$ python3  # 读取 ZIP 成员后求 SHA-256
551350 bytes  f853010f3760eaedd82a4a53f405bf7cf3f6b8e9b4461639f0befd85f03f847a  DSHA-交互原型-v5-20261026/打开原型.html
551350 bytes  f853010f3760eaedd82a4a53f405bf7cf3f6b8e9b4461639f0befd85f03f847a  DSHA-交互原型-v5-20261026/dist/dsha-shell-redesign.html
```

包内摘要逐条核验：

```console
$ python3  # 对 SHA256SUMS.txt 的 45 条逐条比对 ZIP 成员
ZIP members checked: 45, OK=45, FAIL=0
```

在工作区解压目录直接执行 `sha256sum -c SHA256SUMS.txt`，只有两项失败：

```console
dist/dsha-shell-redesign.html: FAILED
打开原型.html: FAILED
sha256sum: WARNING: 2 computed checksums did NOT match
```

原因是解压目录中的这两个文件当前内容与 Linux 重建结果一致（549,332 bytes / `78593042…`），不再等于包内 551,350-byte 版本；其余 43 条与包内摘要一致。

### 3.2 按 `src` + `build.py` 重建

为了不污染素材目录，先复制到 `/tmp` 再构建：

```console
$ cp -r <workspace>/ui-v5-extract/DSHA-交互原型-v5-20261026 /tmp/v5rebuild
$ cd /tmp/v5rebuild && python3 build.py
/tmp/v5rebuild/dist/dsha-shell-redesign.html
549,332 bytes; standalone, no network dependencies

$ sha256sum dist/dsha-shell-redesign.html 打开原型.html
7859304291496a75e5ec4270430205c5bf9dca74c541871dbb59b45d512f9f05  dist/dsha-shell-redesign.html
7859304291496a75e5ec4270430205c5bf9dca74c541871dbb59b45d512f9f05  打开原型.html
```

与包内产物相比：`551,350 - 549,332 = 2,018` bytes。逐行比对显示两个文件都是 2,018 行，且**每一行只差一个回车符**：

```console
$ python3  # 统计行尾
pkg CRLF: 2018, bare LF: 0, bytes 551350
rebuild CRLF: 0, bare LF: 2018, bytes 549332
strip CR equal: True

$ diff --strip-trailing-cr -q /tmp/pkg-entry.html /tmp/v5rebuild/打开原型.html
（无输出，退出码 0）
```

### 3.3 结论：字节不可复现，但内容可复现

| 对象 | bytes | SHA-256 | 说明 |
|---|---:|---|---|
| ZIP 整包 | 3,021,268 | `3124fa81…` | 本次实算；ZIP 自身不在 `SHA256SUMS.txt` 内 |
| 包内 `打开原型.html` / `dist` | 551,350 | `f853010f…` | 与 `delivery.json`、`SHA256SUMS.txt` 一致 |
| Linux 按 `src` 重建 | 549,332 | `78593042…` | 与包内产物不同 |
| 解压目录当前 `dist` / 入口 | 549,332 | `78593042…` | 与重建结果一致；43/45 通过摘要校验 |

因此，**不能把包内 `dist` 当成可由当前 `src` 在 Linux 上逐字节复现的构建产物**：差的是行尾，不是界面内容；去掉 CR 后两份文件字节完全一致。接入、审查和后续原生化一律以 `src/` 为准，包内 `dist` 只作为“发布时的快照”。

还有一个证据绑定问题：6 份验证报告的 `input_sha256` 全部是 `f853010f…`（包内 551,350-byte 构建），不是 Linux 重建的 `78593042…`。也就是说，**724 项验证绑定的是发布快照，不是当前 Linux 重建产物**。两者内容等价（仅 CRLF 差异），但形式上的摘要不一致，写报告时不能混用。

## 4. 724 项验证怎么读

| 报告 | `checks` 条数 | 文件内 `passed` 字段 | JS / console / 外部请求错误 |
|---|---:|---|---|
| `verification/results.json` | 138 | 无该字段 | 0 / 0 / 0 |
| `verification/atelier/results.json` | 204 | 无该字段 | 0 / 0 / 0 |
| `verification/refinement-v3/results.json` | 170 | 无该字段 | 0 / 0 / 0 |
| `verification/polish-v4/results.json` | 120 | 120 | 0 / 0 / 0 |
| `verification/polish-v4/controls/results.json` | 35 | 35 | 0 / 0 / 0 |
| `verification/iteration-v5/results.json` | 57 | 57 | 0 / 0 / 0 |
| **合计** | **724** | — | — |

`724` 是 6 份报告 `checks` 数组长度之和（138+204+170+120+35+57）。前三份报告没有写 `passed` 字段，但验证脚本的 `check(name, ok)` 在断言失败时会直接 `raise AssertionError`，只有全部完成才会写出 `results.json`；加上三份报告均记录 0 个 JS/控制台错误，可以把这 724 条理解为“已执行且未失败的断言名”。不过，严格说 **JSON 里能直接证明“全部 passed”的只有 120 + 35 + 57 = 212 条**，其余 512 条要靠脚本语义补证——这是引用该数字时应保留的限定。

`delivery.json` 的验证范围原文：“仅浏览器内模拟交互；不是视觉审美评分、Android真机或原生性能结论。”

## 5. 本试点的已声明范围（build202）

工作区里成文的试点范围在 [build202 变更说明](../../releases/build202-changes.zh.md)（该文件当前也是 `git status` 的 `??`，尚未提交）：

- 原生外壳：顶栏品牌与页面语境、手机悬浮胶囊导航、平板左侧导航栏、启动主页的信息层级与活动条、日志复制/跟随/导出。工作区已有未提交的 `ui/ShellRail.java`、`ui/ShellActivityTrack.java` 与 `res/layout-sw600dp/` 支撑，本文只把工作区状态当作“声明范围”，不当作已提交事实。
- 终端：合入 Sunset-zhongdua-G 的全屏宿主与会话保护；点击「终端」直接拉起独立全屏 `TerminalActivity`；返回只关页面、不结束会话；三向态侧栏、手机 7+7 / 平板 8+8 附加键、横屏三列两行压缩随构建合入。
- 沿用：插件与设置页继续使用现有真实功能，不接入原型模拟数据；文件、备份、诊断等仍沿用现有实现，未按原型重做。
- 身份：`versionCode 202`、`0.2.1-rc.3-20261026.2115-dsh0.2.1-rc.3`、测试包 `dsh.android.client.SunsetRNE`、本机测试证书，**不是 E7E3 正式包**。
- 边界：未做真机安装、TalkBack、字体缩放与横竖屏全量验收。

本文后续统一使用三种口径：

- **原型现状**：HTML 里真实存在、可交互，但仍是本地模拟。
- **本试点计划**：build202 已声明要做、或当前原生代码已具备的落点。
- **暂缓**：原型有、原生没有，或原生有但本批不按原型重做。

## 6. 三态口径总表

| 能力 | 原型现状 | 本试点计划 | 暂缓 / 边界 |
|---|---|---|---|
| 顶部品牌与页面语境 | 有 | build202 已声明落到原生外壳 | 真机视觉未验 |
| 四根导航 | 侧栏/底栏 4 项：启动、插件、设置、终端 | 手机悬浮胶囊、平板左侧导航 | 系统手势/大字体未验 |
| 启动主页 + 完整日志 | 有，含日志复制/跟随/导出 | build202 已声明 | 日志数据仍以真实运行状态为准 |
| 插件页 | 完整模拟市场/安装流程 | 沿用原生 `PluginFragment` 真实功能 | 原型模拟数据不接入 |
| 设置页 | 10 个列表项 + 1 个目录直达连接页 | 沿用原生 `SettingsFragment` 真实功能 | 不按原型重排 |
| 文件/备份/任务/组件/诊断 | 有模拟交互 | 沿用现有原生实现 | 原型中的编辑、比较、选择性恢复未重做 |
| 全屏终端 | HTML 内模拟终端，非原生 | build202 已合入 `TerminalActivity` | 真机 IME/TUI/TalkBack 未验 |
| 语言 | 10 语言选择，文案仍简体中文 | 沿用原生 `LanguageController` 语言机制 | 完整多语翻译、RTL 未做 |
| 虚拟屏 | 原型无对应主视图 | — | 原生仅有 `VirtualScreen*` 策略/桥，无页面 |
| 设备能力授权 | 可模拟 | 沿用原生 `DeviceGrantsFragment` | 真实系统权限不因原型改变 |

## 7. 关键数字汇总

| 指标 | 实测值 | 来源 |
|---|---:|---|
| ZIP 整包 | 3,021,268 bytes / `3124fa81…` | `sha256sum` |
| 包内入口与 `dist` | 551,350 bytes / `f853010f…` | ZIP 成员实算 |
| Linux 重建 | 549,332 bytes / `78593042…` | `python3 build.py` |
| 行尾差异 | 2,018 行 / 2,018 bytes（CRLF vs LF） | 逐行统计 |
| 包内摘要 | 45 条，45 通过 / 0 失败 | Python 逐条比对 |
| 解压目录摘要 | 45 条，43 通过 / 2 失败 | `sha256sum -c` |
| 验证 | 6 组 / 724 条 / 0 JS / 0 console / 0 外部请求 | 6 份 `results.json` |
| 目录条目 | 29（编写 30，v5 删除“快捷键操作指南”后 29） | `studioCatalog` + `polish-settings.js` |
| 主视图 | 16 | `renderPageContent` 13 + `refinement.js` 3 |
| 设置列表 / 目录直达 | 10 / 1（连接页） | `settingGroups` 过滤后 |
| 终端测试 | 15 个类 / 132 个 `@Test` | `app/src/test/**/Terminal*Test.java` |
| 外壳资源（未提交） | `shell_ui.xml` 17 色 × 2 / 20 尺寸 × 2；`shell_launch.xml` 25 尺寸 / 16 样式 | 工作区 `git status` 为 `??` |
| 原型终端键 | 14 键（7×2） | `polish-terminal.js` |
| 原生附加键 | 手机 7+7，平板 8+8 | `TerminalExtraKeys.defaults()` |

三张预览图实测：

| 文件 | 像素 | bytes | SHA-256 |
|---|---:|---:|---|
| `home-phone.png` | 780×1688 | 412,575 | `0aa348b24ae2d67804e76fd4d04741022e77cef391aef097bb58cf36dc85bc35` |
| `terminal-tablet.png` | 2360×1640 | 222,949 | `db3651d562ee00cacac0604a991f4839bbebfd3b30eb33696e8bbaaf8010cb81` |
| `engine-menu.png` | 2360×1640 | 556,147 | `e18525e959ea433885c85a523df0c1720cfc795e47c019466a9e58a90a198cca` |

## 8. 发现的矛盾与风险

1. **包内 `dist` 与 Linux 重建字节不同**：551,350 vs 549,332，差 2,018 bytes，正好是 2,018 个 CR。内容等价，但“包内 dist 可由 src 逐字节复现”这个说法不成立。见第 3 节。
2. **验证摘要绑定发布快照**：6 份报告 `input_sha256 = f853010f…`，不是 Linux 重建的 `78593042…`。引用 724 项时不能声称它验证了当前重建文件。
3. **任务描述称 token 在 `atelier.css`，实际不是**：`atelier.css` 只定义 7 个派生 token；任务列出的 15 个基础 token（另加 `--blue-rgb`、`--shadow` 共 17 个）在 `src/base.html` 的内联样式里，其中 10 个又被 `src/refinement.css` 覆盖。按文件单独抄会得到两套不同颜色。详见 [design-tokens.md](design-tokens.md)。
4. **`shell_*` 已存在但未提交**：工作区 `values/shell_ui.xml`、`values-night/shell_ui.xml`（各 17 色 + 20 尺寸）与 `values/shell_launch.xml`（25 尺寸 + 16 样式）都是 `git status` 的 `??`。实际名称与最初设想不同：`--lift` → `shell_raised`，`--muted` → `shell_text_secondary`，`--subtle` → `shell_text_muted`，`--green/amber/red` → `shell_good/warn/bad`，`--sheet` → `shell_nav_bg`；`--shadow`/`--well` 等尚无资源。文档已按实际文件对齐，但提交批次未定，且与既有 `terminal_*`（23/20 个）两套并存。
5. **原型不是原生终端**：HTML 终端是 14 键（7×2）、模拟输入、无真实 PTY；原生平板是 8+8。不能拿原型截图证明原生附加键数量或键盘行为。详见 [terminal-preservation.md](terminal-preservation.md)。
6. **724 中有 512 条没有 `passed` 字段**：只能通过脚本“失败即抛异常”的语义补证；严格 JSON 证据只有 212 条。
7. **原型“帮助/指南/虚拟屏”等页面没有一对一的原生落点**：`guide` 无独立原生页，虚拟屏只有 `VirtualScreen*` 策略/桥、没有 UI 页面。页面映射中这些只能标“暂缓”。
8. **工作区不是干净状态**：读取时 `app/build.gradle`、`LaunchFragment.java`、`MainActivity.java` 已有 Lead 的未提交改动；`shell_ui.xml`/`shell_launch.xml`、`bg_shell_*`、`ShellRail.java`、`ShellActivityTrack.java` 以及 `build202-changes.*` 仍是 `??`。本文引用的 `versionCode 202`、`shell_*` 名称都与当前工作区一致，但不代表已提交、已构建或已安装。
9. **原型验证不是真机验收**：build202 变更说明明确未做真机安装、TalkBack、字体缩放、横竖屏全量验收；本目录也不声称已做。

## 9. 复现命令

```bash
# 1) 整包摘要
sha256sum "/storage/emulated/0/Download/DSHA/DSHA-UI-v5-完整分享包-20261026.zip"

# 2) 包内 45 条摘要核对（Python 标准库；不执行包内脚本）
cd <workspace>/ui-v5-extract/DSHA-交互原型-v5-20261026
python3 - <<'PY'
import zipfile, hashlib
z = zipfile.ZipFile('/storage/emulated/0/Download/DSHA/DSHA-UI-v5-完整分享包-20261026.zip')
prefix = 'DSHA-交互原型-v5-20261026/'
ok = bad = 0
for line in open('SHA256SUMS.txt', encoding='utf-8'):
    line = line.strip()
    if not line:
        continue
    h, _, name = line.partition('  ')
    got = hashlib.sha256(z.read(prefix + name)).hexdigest()
    ok += got == h
    bad += got != h
print(f'OK={ok} FAIL={bad}')
PY

# 3) 在 /tmp 重建，避免覆盖素材
rm -rf /tmp/v5rebuild
cp -r <workspace>/ui-v5-extract/DSHA-交互原型-v5-20261026 /tmp/v5rebuild
cd /tmp/v5rebuild && python3 build.py
sha256sum dist/dsha-shell-redesign.html 打开原型.html
```

没有运行 `gradle`、没有安装 APK、没有操作实体手机；本目录只写文档与复制预览图。

## 10. 边界

- 所有“已实现”表述均指**仓库源码或 build202 变更说明中已声明**，不是本批真机验收。
- 所有“计划”表述均指 build202 试点范围；Lead 的后续提交可能改变细节，以代码审查为准。
- 所有颜色、尺寸建议是设计落点，不是当前 APK 的实际渲染结果。
- 预览图是 Chromium 浏览器截图，不是 Android 截图。
