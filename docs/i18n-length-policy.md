# 界面文案长度预算与精简规则

本文说明 DSHA Android 界面文案（`tools/i18n/` 十语目录）的长度预算、筛选口径、占位符约束和新增文案的维护流程。适用于十种固定界面语言：`zh`、`zh-Hant`、`en`、`ru`、`ja`、`ko`、`ar`、`pt`、`la`、`fr`；本规则不新增、不删除语言。

## 为什么需要预算

界面（按钮、开关、标签、标题、提示行）的可用宽度有限。中文源串很短时，西文/俄文/阿拉伯文译文如果逐字直译，会在小屏或分栏布局里折行、截断或挤压控件。预算按中文源串长度给出译文上限，用来约束**短标签**，不处理整句说明。

## 预算规则

以中文源串 `zh` 的 Unicode 字符数（码点）为基准，按下列区间给出译文长度上限：

| 中文源串长度 | 适用语言译文上限（字符数） |
| --- | --- |
| `1 <= len(zh) <= 8` | `<= 24` |
| `9 <= len(zh) <= 12` | `<= 32` |
| `len(zh) > 12` | 不设短标签预算（整句/说明文本按原质量翻译） |

适用语言：`fr`、`pt`、`ru`、`ar`、`la`。`en` 为源语言不改；`ja`、`ko` 与中文（`zh`、`zh-Hant`）本轮不设此预算。

长度按 Unicode 码点计数（`len(str)`），不计 UTF-16 代理对；换行符计入长度，但含换行的条目按下方口径跳过。

## 筛选口径：只处理界面标签资源

预算只施加于**实际作为 Android 界面标签资源**的文案，即由 `tools/i18n` 生成到 `app/src/main/res/values-*/ui_strings.xml` 的条目，来源包括：

- 布局 XML 中 `android:text` / `hint` / `contentDescription` / `label` / `title` / `summary` / `prompt` 直接引用的文案；
- `app/src/main/res/values/*.xml` 中手工命名的字符串，其文本与目录中的 `zh` 完全一致（`named` 映射）。

其余动态状态、终端帮助、日志、错误说明等长文本不在本规则的短标签范围内；它们仍保持完整语义，按普通翻译质量维护。

以下条目即使命中长度区间也跳过，不做压缩：

1. 整句：源串以 `。`、`！`、`？`、`.`、`!`、`?` 结尾；
2. 含换行（实际换行或转义 `\n`）的多行文本；
3. 含代码、路径、URL 的说明文本（如 `/root/...`、`curl ...`、`--su`、`key=value`、`()`、`://` 等）；
4. 以数字/占位符为主的长说明文本（如端口说明、格式模板），压缩会破坏可读性或参数语义。

## 硬约束

- 占位符必须保持：`%s`、`%d`、`%%` 的数量、类型与出现顺序完全不变；`uiFormat` 条目由 `tools/ui_language_catalog.py::format_signature` 校验，禁止增删或重排参数。
- 不硬删关键信息：压缩以“可以更短地表达同一含义”为前提；专有名词、设备名、数值、单位、否定语义（“不/未/仅”）不得丢失。
- 不改变 JSON 结构、条目 id、语言集合；不改 `en` 源串。
- 只允许人工审阅后的改写：不跑机械替换，不把说明句截断成残句。
- 无法在预算内安全表达的条目，保留原文并在交付报告中列出 `id` 与原因；不为达标牺牲语义。

## 本轮执行结果（2026-10-29）

- 处理范围：258 个界面标签资源 id（`ui_strings.xml` 的 `used` 85 + `named` 176 的并集）中命中的 83 个 id。
- 处理量：239 处（`fr` 67、`pt` 52、`ru` 46、`la` 49、`ar` 25）；目标集处理后剩余超长 **0**。
- 按跳过口径未处理的命中：7 个 id、24 处（整句/代码类，见下）；如需继续压缩，应先确认不是说明文本。
- 全目录（4,923 条目含补充目录）同口径剩余超长：**4,456 处**（`fr` 1,118、`pt` 993、`ru` 945、`la` 948、`ar` 452，1,270 个 id）。这批是状态提示、终端帮助与说明长句，后续可按同一预算分批处理；处理时仍按本文的跳过口径与占位符约束执行。

本轮按口径跳过的界面标签（保持原文）：

| id | zh | 跳过原因 |
| --- | --- | --- |
| `m0025` | 还没有日志。 | 整句 |
| `m0137` | 插件链接或 npm 包名 | 代码/包名说明 |
| `ui2_launch_hint` | 随时开始你的下一项任务。 | 整句 |
| `ui2_settings_hint` | 让工作台按你的习惯运行。 | 整句 |
| `ui2_ws2_0` | 保存在本机，可手动导出。 | 整句 |
| `ui2_data_sub` | 导出、恢复与文件共享。 | 整句 |
| `x1970546` | 无法打开系统画中画设置。 | 整句 |

## 如何新增文案

1. 在 `tools/i18n/messages.json`（或补充目录 `messages-extra.json`）新增条目：`id` 唯一，`zh` 与 `en` 必填；含 `%s/%d/%%` 的模板必须标记 `"uiFormat": true`，并在所有语言的译文中保持相同参数签名。
2. 在 `tools/i18n/translations/<lang>.json`（补充目录对应 `translations-extra/<lang>.json`）为**全部十种语言**补齐键；缺键会让 `load_catalog` 在打包前失败。短标签按本文预算书写，长说明保持完整语义。
3. 运行 `python3 tools/localize-android-ui.py --resources-only` 重新生成 `values-*/ui_strings.xml`（以及 `app/src/standard/res/*/edition.xml`）；不要手改生成文件。
4. 运行门禁：`python3 tools/check-ui-i18n.py`、`python3 tools/test-ui-language-catalog.py`、`python3 tools/localize-android-ui.py --resources-only --check`、`python3 tools/check-source-hygiene.py`。
5. 字节钉住：`tools/check-source-hygiene.py` 的 `language_source_pins` 固定了 `tools/i18n/translations/{ar,fr,ja,ko,pt,ru}.json` 与 `translations-extra/{ar,fr,ja,ko,pt,ru}.json`；改动这些文件后必须把对应条目的 SHA-256 刷新为最终字节再跑门禁。`la`、`zh-Hant` 目录当前不在该钉内。
6. 网页目录（`tools/i18n/web-translations/`）由 `app/src/main/assets` 的生成资产与 `runtime-descriptor` 的 generator pin 管理：改动网页译文后必须重生成资产并更新相应哈希清单，不能只改 JSON。

## 核验命令

```bash
python3 tools/check-ui-i18n.py
python3 tools/test-ui-language-catalog.py
python3 tools/localize-android-ui.py --resources-only --check
python3 tools/check-source-hygiene.py
```

四项全绿表示：源码词法检查、十语目录/格式/生成资源一致性、Android 资源确定性生成、以及字节钉与行尾/依赖身份检查均通过。
