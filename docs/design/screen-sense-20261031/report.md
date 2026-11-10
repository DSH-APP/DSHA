# 手机端识屏与操作层（ScreenSense）重构评估

状态：**ScreenSense 可行性评估与重构方案；原 build220 基线未落地核心协议**。日期 2026-10-31，文档随 build220 发布（`0.2.1-rc.3` / 内置 DSH `0.2.1-rc.5`）。当前任务分支已落地 P0 的任务隔离紧凑只读预览、codec/store、严格 `/app/sense/state` 路由和节点 action-set 微优化，以及 P0a 的固定合成样本评估（`tools/screen-eval/`）、真实 codec 字符回归闸和 source/quality/partial/readMs/ageMs 元数据；delta/act/wait、事件缓存、OCR、真实页面 instrumentation 与真机性能验收仍未实现。

证据分级：**实测**＝离线脚本产出（合成样本 + DeepSeek V4 tokenizer，脚本见附录 A）；**源码事实**＝当前仓库代码；
**目标/估计**＝需要真机仪器化校准的设计预算，本文不把它写成已测结果。本文不做真机读屏，不接触用户当前画面。

范围：物理主屏（display 0）与独立虚拟屏的**识别、表示、动作**三层；不改变现有授权链、安全边界与 UNKNOWN 不重放语义。
约束：识别全部在手机上完成，不引入远程识别服务器；手机内拉起本地服务允许，但只作为传输/隔离手段。

**目录**：[0 结论](#0-结论) · [1 现状审计](#1-现状审计) · [2 目标](#2-目标与非目标) · [3 架构](#3-总体架构) ·
[4 元素模型](#4-元素模型) · [5 编码](#5-编码-v4给模型看的那一层) · [6 增量与事件](#6-增量缓存与事件) ·
[7 像素兜底](#7-像素兜底本地-ocr) · [8 动作](#8-动作与效果验证) · [9 本地服务](#9-本地服务与传输) ·
[10 性能预算](#10-性能预算与测量) · [11 重构落点](#11-重构落点) · [12 风险](#12-风险与对策) ·
[13 计划](#13-分阶段计划) · [14 验收](#14-验收清单与未验证声明) · [附录](#附录-a测量方法与复现)

## 0. 结论

1. **可行，而且最大的性能收益不在 OCR，而在“不要把同一棵树编码两遍再整棵喂给模型”。**
   合成样本 60 节点的一次快照是 8,093 token；同一份内容用带动作 key 的紧凑语义表只需 515 token（**15.7×**）。
   一个 20 步任务按“每步 2 次请求”算，累计输入 3.45M token、末轮上下文 170.5k；改成“动作一次往返 + 只回增量”后
   是 0.14M / 8.0k（**24× / 21×**）。这不是模型能力问题，是协议问题。

2. **主路径仍然是无障碍树，但要把“遍历 + 归一化 + 稳定 ID + 差分”放进壳（App）里做。**
   模型每步只需要“可操作元素表 + 与上一状态的差异”，不需要 displayId、windowId、bounds、UUID、英文动作全名。

3. **像素路径只在树不可读/不可信时启用。** 本地 OCR（ML Kit 中文 bundled 或 PaddleOCR-ncnn）能把
   Canvas/Flutter/游戏/纯图标界面变成同一套元素表，但它是兜底：全屏 OCR 的量级是 200–450ms，
   是树路径的 10 倍以上。用 ROI、tile 差分和“只 OCR 变化区域”把它压到 60–150ms。

4. **端侧大模型（VLM）不适合交互热路径。** 1–3B int4 权重常驻 1–2GB 内存，单屏推理秒级，
   与“极致性能”直接冲突；它最多做异步“深读/解释”，不能替代结构化识别。端侧小模型（图标分类、
   UI 元素检测）可以在 OCR 之后作为可选项，但不进 P0/P1。

5. **本地服务器不是瓶颈。** 现在的 3090 桥就是本地服务器（loopback + token）：loopback 往返亚毫秒到数毫秒，
   UDS 更低；相对 10–300ms 的识别可忽略。新增本地服务只在“进程隔离/外部工具复用”时有价值，
   不应该为了“性能”去多起一层进程。真正该做的是把现有桥的**返回体变小、请求次数变少**。

6. **建议分两步走**：P0（3–5 天）改编码与动作返回体，立刻拿到 10× 级 token 收益；
   P1（1–2 周）做事件缓存、差分、并发读与遍历优化，拿到毫秒级延迟收益；
   P2（2–4 周）再按真机覆盖率数据决定是否引入 OCR 兜底。P3 才讨论统一工具面与可选本地流。

### 0.1 可行性判断

| 能力 | 判断 | 依据 | 前提/代价 |
|---|---|---|---|
| 语义树紧凑化 + 增量 | **可行（优先）** | 实测 15.7× token 降幅；不改平台 API | 3–5 天；旧端点兼容 |
| 事件缓存 + 稳定判定 + 并发读 | **可行** | 窗口/内容事件已订阅、只是当前丢弃；单线程是现状瓶颈 | 1–2 周；其余事件类型需加订阅；保持不读内容的隐私姿态 |
| 本地 OCR 兜底（中文） | **有条件可行** | ML Kit/PaddleOCR 均离线；全屏 200–450ms | +10–25MB；P2 前先用覆盖率数据证明值得 |
| 纯图标/游戏/Canvas 识别 | **有条件可行** | OCR + 图标分类 + 融合；无树时精度下降 | 需要标注集；复杂图形仍可能不可读 |
| 端侧 VLM 进热路径 | **不可行（当前）** | 1–3B int4、秒级、1–2GB 内存 | 只做异步深读，不做每步循环 |
| 远程识别服务器 | **排除** | 用户明确排除远程识别；隐私/延迟/离线都更差 | — |
| 手机本地服务器 | **可行，但非性能关键** | loopback/UDS 往返亚毫秒到数毫秒 | 只在隔离/外部工具复用时加 |

一句话：**要做的是“壳内结构化 + 增量 + 事件”，不是“更强的模型”或“更多的服务器”。**

## 1. 现状审计

### 1.1 链路

物理屏当前是“按需拉取”的一条同步链：

```
/app/ui/dump | tree
  └─ HttpShellService.appUi()                      (app/src/main/java/com/deepseekharness/app/HttpShellService.java:1561)
      └─ PhoneControlCoordinator / CapabilityBroker 授权与任务绑定
          └─ DshaAccessibilityService.execute()      (DshaAccessibilityService.java:287)
              └─ MainScreenActions.run()             (screen/MainScreenActions.java:266)
                  ├─ ScreenSnapshotReader.read()     → JSON{text, nodes[], treeId, ...}
                  └─ ScreenActionExecutor.execute()  → 语义动作 / 手势
```

* 树：`ScreenSnapshotReader.walk()` 递归遍历 `AccessibilityNodeInfo`，每个上报节点同时写一份 JSON
  和一行中文文本（`ScreenSnapshotReader.java:60-121`）。文本与 JSON 是同一内容的两种编码。
* 上限：访问 4096 节点、深度 24、输出 200 个元素、文本 12,000 字符、遍历软预算 800ms
  （`util/AccessibilityTreeBudget.java:5`、`screen/ScreenReadBudget.java:9`、`ScreenSnapshotReader.java:60`）。
* 节点句柄：每个元素 `AccessibilityNodeInfo.obtain()` 克隆一份，放进 30 秒租约表
  （`screen/ScreenSemanticNode.java:12-22`、`util/ScreenNodeLeaseStore.java:52-66`）。
* 动作：`node` 动作用 treeId + nodeId 一次性消费；坐标手势要求先 `screenshot` 拿到 observationId，
  限最新帧、30 秒、一次（`screen/ScreenActionExecutor.java`）。
* 效果：动作成功只表示 Android 接受了动作，返回固定 `effectVerified:false`，要求“再观察一次”
  （`ScreenActionExecutor.java:231-258`）。于是每个动作至少两次请求：动作 + 重新 dump。
* 事件：`onAccessibilityEvent` 平时直接 return，只为配对码窗口服务
  （`DshaAccessibilityService.java:163-165`、`res/xml/accessibility_service_config.xml:17`）。
  也就是说，界面变了壳不知道，只能等模型再拉一次。
* 截图：`AccessibilityService.takeScreenshot()` → HardwareBuffer → 全尺寸 ARGB_8888 拷贝
  （`screen/ScreenScreenshotCapture.java:89-116`）；`mcp` 格式再压 PNG + Base64
  （`MainScreenActions.java:328-336`），`path` 格式落盘 PNG（`MainScreenActions.java:338-357`）。
* 并发：所有屏幕请求走**单线程** `screen-request-owner`（`util/ScreenRequestExecutor.java:16-28`），
  读与写串行；截图虽然有自己的回调线程，但发起方仍阻塞等 bitmap。遍历与截图无法重叠。

### 1.2 现有输出里真正贵的东西

以 60 节点、55% 可点击的合成屏为例（与真实聊天列表同量级），一次 dump 的 19,868 个字符里：

| 冗余 | 具体表现 | 代价 |
|---|---|---|
| 双份编码 | 同一 label 既在 `text` 行里，又在 `nodes[].text` 里 | 文本单独占 1,832 token（23%） |
| 节点身份 | 每个节点一个 UUID（36 字符），跨快照不复用 | 60 × 约 10 token，且无法跨观察引用 |
| 全量字段 | `clickable/editable/scrollable/enabled/checked/boundsHaveArea` 逐节点展开 | 大量布尔字面量 |
| bounds 两次 | `text` 行有“中心/区域”，JSON 又有 `bounds` 数组 | 同一坐标两份 |
| 动作全名 | `scroll_forward / scroll_backward / long_click` | 每个动作 2–4 token |
| 纯文本节点也带 id | 不可操作节点也发 UUID + 全字段 | 约一半元素是纯文本 |
| 空 description | 大多数节点 `description:""` 仍占位 | 60 × 约 4 token |

结论：不是树太大，是**同一个树被序列化了两次、每次都用最贵的写法**。

### 1.3 成本实测

方法：用同一批合成节点分别生成“现状 dump / 仅 text / 紧凑表 v1 / 拟议 v4”，用 DeepSeek V4 tokenizer 计数
（附录 A 给出脚本与复现命令）。合成样本的绝对 token 会随真实页面波动，**看比例，不看绝对值**。

| 节点数 | 现状 dump | 其中 text | 紧凑表 v1 | 拟议 v4 | 现状 ÷ v4 |
|---:|---:|---:|---:|---:|---:|
| 30 | 4,231 | 988 | 349 | 306 | 13.8× |
| 60 | 8,093 | 1,832 | 644 | 515 | **15.7×** |
| 120 | 16,031 | 3,635 | 1,311 | 1,061 | 15.1× |
| 200 | 26,470 | 5,978 | 2,141 | 1,736 | 15.2× |

折合每个元素：现状约 **135 token/元素**，v4 约 **8.6 token/元素**。不带动作 key 的纯位置编号变体（v3）为 449/895/1,478，token 再低约 15%，但插入/删除元素后动作会指错，不作为推荐口径；v4 的 key 费用见 §4.4。

动作后的增量更便宜：一次“改一条文本 + 增一条消息”的 delta 是 33 token；单字段变化的更小样例为 19–26 token（附录 B）。

20 步任务模型（假设：系统提示 + 工具目录 6k token，前缀全命中缓存；现状每步 2 次请求、拟议每步 1 次）：

| 方案 | 请求数 | 累计输入 | 末轮上下文 | 相对输入成本 |
|---|---:|---:|---:|---:|
| 现状 @60 节点 | 40 | 3.45M | 170.5k | 1.00 |
| 现状 @120 节点 | 40 | 6.54M | 329.2k | 1.90 |
| 拟议：每步全量 @60 | 20 | 0.229M | 17.5k | 0.066 |
| 拟议：每步增量 @60 | 20 | 0.14M | 8.0k | **0.041** |

末轮上下文从 170.5k 降到 8.0k 的意义不只是钱：**170k 已经逼近/超过多数模型的可用窗口**，
真实任务里会出现截断、遗忘和被迫压缩历史；8k 则允许一个任务连续跑几十步。
另外，前缀缓存降低的是计费上的未命中部分，并不降低注意力实际要处理的上下文长度；
170k 上下文仍会拖慢每步解码，并挤压工具结果与系统提示的空间。**token 降幅同时是模型侧延迟降幅**，
这部分没有计入下文的本地识别预算。

工具面本身也贵（每次请求都要带）：

| 工具面 | token（实测） |
|---|---:|
| `android_*` 工具定义 | 1,370 |
| `vscreen_*` 工具定义 | 2,850 |
| 系统操作约束说明 | 549 |
| 截图元数据（一次返回） | 301 |

两套屏幕工具 4.2k token，约等于 8 张紧凑语义表（515 token/张）；统一成一套 `screen_*`（物理屏/虚拟屏用 `display` 参数区分）
可以把它压到 1k 以内。

截图路径另算：1080×2400 的 PNG 常见 1–3MB，Base64 再 +33%；按 28×28 patch 粗估图像 token 约 3,300，
缩到 720p 约 1,470。**一次截图的 token 成本 ≈ 7 张紧凑语义表**，所以它只能是最后手段，不能是默认观察方式。

### 1.4 能力缺口

| 缺口 | 现状 | 后果 |
|---|---|---|
| 语义角色 | 只有 clickable/editable/scrollable 三个布尔 | 模型要自己猜“这是按钮还是输入框” |
| 结构合并 | 文本节点与可点击父节点分开发 | 同一按钮出现两三条，模型容易点错 |
| 稳定身份 | UUID，一次观察一换 | 无法“记住这个元素”，只能每次重读 |
| 增量 | 每次全量 | 动作后必须整棵重读 |
| 事件 | 丢弃 | 无法知道界面已稳定/已变化，只能轮询 |
| 效果验证 | 固定 false | 模型无法确认动作是否生效，只能再观察 |
| 像素兜底 | 无 OCR | 游戏/Canvas/纯图标界面不可读 |
| 长文本 | label 截断 1000 字符、总文本 12k | 长消息/文章读不全 |
| 虚拟屏 | 独立一套工具与身份 | 工具目录接近翻倍（1,370 → 4,220），两套语义 |

## 2. 目标与非目标

“极致性能”在本文里是可验收的数字，不是形容词：

| 编号 | 指标 | 目标 | 现状 |
|---|---|---|---|
| S1 | 快照延迟（树，150 元素） | 命中缓存 ≤1ms；p50 ≤25ms；p95 ≤60ms | 未测；当前为单线程全量遍历 + JSON |
| S2 | 每屏 token（60 元素） | ≤600；动作增量 ≤80 | 8,093（全量）；无增量 |
| S3 | 动作往返 | p50 ≤80ms（树路径，含验证）；95% 动作有 `ver=changed/unchanged` 证据 | 动作与观察分两次请求，效果固定 `false` |
| S4 | 覆盖率 | 主流应用 a11y 可读 ≥90%；空树界面经 OCR 可读 ≥80% | 无 OCR，空树即不可读 |
| S5 | 隐私 | 像素/OCR 100% 本地；不落盘、不进日志；屏幕文本只按当前任务最小化交给模型 | 已按需拉取、不持续记录 |
| S6 | 兼容 | v1 端点冻结；协议版本特性检测 | `BRIDGE_PROTOCOL=3`（`HttpShellService.java:1973`） |

非目标：云端 OCR / 云端 VLM；把端侧大模型放进交互热路径；多指手势；替用户做策略规划；
扩大授权范围或绕过 Android 权限。任何“更快”都不能以放宽 UNKNOWN 不重放、目标代次校验、
密码不读、截图观察凭据这些不变式为代价。

## 3. 总体架构

```
             ┌──────────────────────── 壳内（App 进程） ────────────────────────┐
  A11y 事件 ─▶ EventGate（只读元数据）──▶ dirty / quiescence / 期望匹配           │
  A11y 树   ─▶ TreeCollector ─▶ Normalizer ─▶ ScreenState ─▶ TextCodec ──▶ /app/sense/state
  截图      ─▶ PixelAnalyzer ─▶ OcrEngine ─▶ Fusion ─┘        └─▶ BinaryCodec ─▶ UDS / WS（本地自动化）
                                                   │
                                                   ├─▶ StateDiffer ─▶ Delta ─▶ act 返回体
  act(key) ────▶ ActionRouter ─▶ EffectWatcher ──┘（事件 + 再读 + 差分 → 证据）
             └────────────────────────────────────────────────────────────────────┘
```

四条原则：

1. **树优先、像素兜底、融合只补空。** 像素不是第二套主路径，只在树不可读/不可信时启动。
2. **一次请求给一个自洽状态。** 状态带 `sid`；动作带元素 `key`，服务端在当前状态重新解析并按目标签名校验；
   动作返回的是“新状态 + 差异”，不是“已提交，请再观察”。
3. **只发变化。** 默认回增量；窗口切换、大面积变化、超过 10 步或调用方显式要求时才回全量。
4. **识别与传输解耦。** 进程内直调；跨进程只传有界帧。传输不是瓶颈，不为了“本地服务器”再加一层。

### 3.1 职责边界

| 角色 | 负责 | 不负责 |
|---|---|---|
| Android 平台 | 节点树、事件、截图、手势注入 | 语义理解 |
| 壳（App） | 采集、归一化、OCR、融合、编码、动作派发、效果验证 | 决策“下一步做什么” |
| 模型 | 读紧凑状态/增量，选目标与动作，判断任务完成 | 读像素、记坐标、维护节点句柄 |
| 用户 | 授权、确认敏感操作、纠偏 | 手工搬运屏幕内容 |

这条边界是“极致性能”的前提：模型越少碰原始数据，链路越短、越可复现、越省 token。

一次典型交互（树路径）：

```
LLM:  state                     → S12 12 elements 132 tok (age=0)
LLM:  act k6Rc c expect=change  → ok ver=changed ms=41 D13 12 2 … (33 tok)
LLM:  （不再发 state）            → 直接读增量决定下一步
```

对比现状：`dump`（8k tok）→ `node`（"已提交"）→ 再 `dump`（8k tok），三步两轮。

## 4. 元素模型

### 4.1 字段

| 字段 | 例 | 来源与规则 |
|---|---|---|
| `id` / `key` | `7` / `k6Rc` | 文本用 1..N 序号；可操作元素用 3 字符结构 key（§4.4） |
| `role` | `B` | 由 class + flags + 结构推断（见 4.2） |
| `label` | `"发送"` | `text` 与 `contentDescription` 合并去重；密码节点为 `[PWD]` |
| `hint` | `"搜索"` | `getHintText()`（API 26+），只在非空时输出 |
| `flags` | `!` | `!` 不可用、`*` 选中、`^` 已选、`~` 密码、`%` 焦点 |
| `actions` | `c,l` | 只列当前节点真实支持的动作 |
| `box` | `@512,1180` | 归一化 0–999，仅在 `geom=1` 或元素无 id 时输出 |
| `scroll` | `f/b` | 可滚动容器；带可见范围与位置 |
| `src` | `tree` | `tree / ocr / fusion`，OCR 元素在低置信时标 `?` |

补充：`getStateDescription()`（API 30+）、`getTooltipText()`、`getPaneTitle()`、`CollectionInfo/CollectionItemInfo`
都是低成本高价值的语义来源；当前实现一个都没用。

### 4.2 角色推断

| 角色 | 判定（按优先级） | 说明 |
|---|---|---|
| `I` 输入 | `isEditable()` | 附带是否密码、是否多行、IME 是否聚焦 |
| `L` 滚动容器 | `isScrollable()` 或 class 含 List/Recycler/ScrollView | 列表内容仍逐项输出，不折叠掉可操作项 |
| `B` 按钮 | clickable/long-clickable，且不是容器 | 含图标按钮（label 为空时用 OCR/`?`） |
| `C` 复选 | class CheckBox/RadioButton 或 checkable | 带 `*` |
| `S` 开关 | class Switch/ToggleButton 或 checkable+可点击 | 带 `*` |
| `K` 标签页 | class Tab / 选中态 + 同级重复结构 | 带 `^` |
| `G` 图片 | class ImageView 且无文本、不可点 | 不作为动作目标 |
| `T` 文本 | 其余有文本节点 | 可被 `read(id)` 读全文 |
| `D` 对话框 | 顶层 window 类型 / `TYPE_WINDOW_STATE_CHANGED` | 标模态，提示模型先处理 |

角色不是“猜着好看”，而是让模型少一次推理：`I` 直接 set_text，`B` 直接 click，`L` 直接 scroll，
`T` 只在需要读内容时 read。

### 4.3 合并与裁剪（直接决定 token 和误点率）

| 规则 | 处理 | 收益 |
|---|---|---|
| R1 文本上提 | 可点击祖先 + 纯文本子节点 → 合并成一个 `B`，label 取子文本 | 一个按钮从 2–3 行变 1 行，且模型不会点到不可点的文本上 |
| R2 空容器折叠 | 无 label、无动作、单链子节点的容器不输出 | 典型 Compose/RecyclerView 少 20–40% 行 |
| R3 重复去重 | 同容器内 label、角色、动作都相同的相邻元素只留一个 | 列表占位符/重复图标 |
| R4 密码 | 只输出 `I n [PWD] ~`，不读子节点、不 OCR | 隐私硬约束 |
| R5 WebView | 按行合并文本，链接/按钮保留为 `B` | 网页正文可读且不爆行数 |
| R6 图标按钮 | label 空的可点击元素输出 `B 9 ? c`，OCR 可用时补 label | 不再丢失纯图标入口 |
| R7 不可见 | `!isVisibleToUser`、零面积、完全在窗口外 → 丢弃 | 减少无效元素 |
| R8 同义描述 | `description == text` 只保留一份 | 现状双份的直接来源 |
| R9 列表 | 容器标 `L`，带可见项范围；不把 200 行列表全展开 | 长列表按需 `read/scroll` |
| R10 长文本 | label > 80 字符截断 + `len=`；`read(id)` 取全文 | 既省 token 又不丢内容 |

### 4.4 身份、动作目标与代次

* `sid`：每个窗口的单调快照号；用于增量、验证和“你读的是哪一版状态”，不是动作的唯一闸门。
* 动作目标 `key`：可操作元素带 3 字符结构键（`k` + base62），由 windowId、viewId/class、网格格、
  兄弟序号计算，**不含 label 与选中态**；纯文本节点只用顺序号 `id` 供 `read()` 使用。
* 为什么不是纯位置号：插入/删除会让位置整体位移（聊天新消息、列表刷新、删一条），
  模型思考几秒后再按位置点就会点到别的元素；key 在无关内容变化时保持不变。
* 3 字符 base62 约 23.8 万空间；200 个动作元素的碰撞概率约 8%，碰撞时按固定顺序扩为 4 字符，
  并保证同一快照内唯一；key 里不放 label，避免“文字一改就换目标”。
* 服务端校验：在当前窗口重新解析 key；找不到 → `NODE_GONE`，多解 → `AMBIGUOUS_TARGET`，
  角色/动作不匹配 → `TARGET_CHANGED`，位移 >8dp → `TARGET_MOVED`；**不做模糊匹配、不自动重放**。
  窗口/包名/epoch 变化才整单拒绝（`TARGET_CHANGED`）。
* 无关变化（列表新增一条、时间戳刷新）不会让动作失效 —— 这是与“整快照 sid 必须完全一致”的关键区别，
  也是动作成功率的关键。
* 为什么不用 UUID：36 字符 × 每元素约 10 token，60 元素约 600 token，且跨观察无意义；
  key 只给动作元素（约一半），60 元素约 +66 token（449→515）。**key + 目标级校验**是成本/正确性最优解。

## 5. 编码 v4（给模型看的那一层）

模型不需要知道 windowId、节点类名、像素坐标；它需要知道“这是什么、能不能点、点了会怎样”。
v4 就是按这个目标裁剪的：角色 + 文本 + 动作 + 少量状态，其余一律不进上下文。

### 5.1 语法

```
S<sid> <pkg> <w>x<h> [q=ok|partial|low] [f=<key|id>] [ime=0|1] [src=tree|ocr|fusion]
<key|id> <role> "<label>" [flags] [actions] [@cx,cy]
```

* `q=partial` 表示触到预算被截断，附 `why=time|nodes|depth`；模型知道“没看全”，不会以为界面只有这些。
* `flags`：`!` 不可用、`*` 选中、`^` 已选、`~` 密码、`%` 焦点。
* `actions`：`c` click、`l` long_click、`f` scroll_forward、`b` scroll_backward、`o` focus、`t` set_text。
* `@cx,cy` 归一化坐标，默认不输出；只有 `geom=1`、或元素无 key/id、或需要像素动作时才出现。
* 可操作元素用 `k` + 3 字符 base62（如 `k6Rc`）；纯文本元素用顺序号（如 `7`）。模型原样引用 key 即可操作，不需要换算。
* label 用 JSON 字符串转义（换行、引号），保证可解析；空 label 用 `?`。

### 5.2 样例（合成界面，非用户数据）

动作元素带 `key`，纯文本元素带序号；模型看到什么就原样引用什么。

```
S9 com.example.chat 1080x2400 q=ok f=k3Xn ime=1
k5Hq B "返回" c
k9Wm B "聊天信息" c
k2Lp L "消息列表" fb
1 T "张三 19:02"
2 T "今晚一起吃饭吗？"
3 T "我 19:03"
4 T "好，七点见"
k7Vt B "语音" cl
k3Xn I "输入消息" t
k8Qa B "表情" c
k6Rc B "发送" c
```

12 个元素、210 字符、**132 token**（实测）。同一界面按现状 dump 约 1,619 token（12/60 × 8,093），
差 12.3 倍；元素越多差得越大（大列表 200 元素是 26,470 → 1,736）。

### 5.3 增量

```
D<sid> <prevSid> <changedCount>
+<key> <role> "<label>" [flags] [actions]
+T "<label>"
-<key> | -T "<label>"
~<key> "<new label>" [flags] [actions]
@scroll <key> <0..1>
```

样例（发送一条消息后）：

```
D 13 12 2
~k6Rc B "发送中…"
+T "我 19:04 好，七点见"
```

**33 token**。默认策略：变化元素 <40% 回增量；窗口切换、应用切换、变化 >40%、每 10 步、
或调用方 `full=1` 时回全量。全量/增量都由服务端决定并标注 `mode=full|delta`，模型不需要自己推断。

### 5.4 二进制（本地用，不给模型）

同一批 60 元素：v4 文本 839 字符；手写 varint 原型 **974 字节**，gzip 后 651 字节。
正式实现建议 FlatBuffers/CBOR + zstd level 1（16KB 内 1–3ms），用于：

* UDS/本地自动化的零解析开销；
* 事件流里的状态帧；
* 未来本地小模型的特征输入。

二进制协议不进 LLM 上下文，也不改变 `/app/*` 的文本契约。

## 6. 增量、缓存与事件

### 6.1 事件门（只读元数据）

现有服务只在配对窗口读事件，其余直接返回（`DshaAccessibilityService.java:163`）。新层需要把事件
变成“失效信号”，但**仍然不读内容**：

| 事件 | 用途 | 是否读节点 |
|---|---|---|
| `TYPE_WINDOW_STATE_CHANGED` | 窗口/Activity 切换，全量失效 | 否 |
| `TYPE_WINDOW_CONTENT_CHANGED` | 内容变化，标记 dirty | 否 |
| `TYPE_VIEW_SCROLLED` | 滚动容器失效 | 否 |
| `TYPE_VIEW_TEXT_CHANGED` | 输入框变化（若开启） | 否 |

只在“当前有屏幕任务 + 确实需要新状态”时才读节点。事件本身带 package/class/windowId，足够做失效，
不构成后台录屏。当前配置只订阅 `typeWindowStateChanged|typeWindowContentChanged`
（`accessibility_service_config.xml:17`）；要用滚动/文本事件需显式加订阅类型，并单独评估 Binder 成本。
`notificationTimeout=100` 已在配置里（同文件 :24），可作合并窗口；再加 60ms 静止判定作为“已稳定”。

### 6.2 缓存

* 缓存键：`epoch + windowId + package`；值：不可变 `ScreenState` + 时间戳。
* 命中条件：无 dirty、`age < 150ms`、窗口未变。命中直接返回，附 `ageMs`。
* 失效：事件、动作提交、旋转、IME 显示/隐藏、目标窗口变化、任务结束。
* 空闲时重复 `state` 是 0 成本；但**token 仍按返回内容计**，所以重复读仍应尽量用增量/等待，
  缓存只解决延迟，不解决上下文膨胀。

### 6.3 等待原语（省掉轮询）

```
wait(since=sid, until=<条件>, timeout=3000)
条件：text=、text~=、id=、window=、idle、count>=
→ 命中：{ok:1, hit:1, ms:420, delta:"D 14 13 1 …"}
→ 超时：{ok:1, hit:0, ms:3000, delta:"…"}
```

这是“点发送 → 等消息出现”的正确形态：一次调用，服务端盯事件，命中即返回增量。
模型不用“再 dump 一次看看”，也不会因为动画没结束而读到半成品。

### 6.4 并发

| 通道 | 线程 | 规则 |
|---|---|---|
| 节点采集 | 单线程 | `AccessibilityNodeInfo` 的 Binder 访问串行，避免平台侧竞争 |
| 编码/差分/OCR | 2 线程池 | 与采集解耦；OCR 可并行于下一次树读 |
| 变更（动作） | 单线程 | 与采集互斥；动作前校验 `key/target/epoch`（sid 只用于结果绑定） |
| 读快照 | 与变更读-写锁 | 读可并发；写独占；快照对象不可变 |

现有 `ScreenRequestExecutor` 是单线程队列（`ScreenRequestExecutor.java:16-28`），读与写、读与读全部串行；
在“动作 + 验证”要各读一次的场景下，这会把两次 Binder 遍历串成一条链。改造时保留“变更单线程”，
把只读快照拆成可并发的读通道，是延迟收益最大的一处结构调整。

## 7. 像素兜底（本地 OCR）

### 7.1 何时启用

| 触发条件 | 策略 |
|---|---|
| 可操作元素 <3 且屏幕大部分区域有文本像素 | 全屏 OCR |
| 可点击元素 label 为空 | 只 OCR 该元素外扩 16dp 的 ROI |
| 包/类命中 Canvas、SurfaceView、Unity、Flutter、游戏 | 树 + OCR 融合 |
| WebView 树可读但正文缺失 | OCR 补正文 |
| 用户/模型显式要求“读图里的字” | OCR 指定区域 |

默认**不**因为“树看起来少”就无脑 OCR：先看 `visited`、`q`、可操作元素数，再决定。

### 7.2 采集

1. `AccessibilityService.takeScreenshot(displayId, executor, callback)`（API 30，已声明 `canTakeScreenshot`）。
2. `Bitmap.wrapHardwareBuffer()` 拿硬件位图，**不要**先全尺寸 `copy(ARGB_8888)`（1080×2400 是 10.4MB）。
3. 一次 `Canvas.drawBitmap` 缩放到最长边 1280（约 1.2–2ms/百万像素，量级估计），直接得到 OCR 用软件位图。
4. 需要多 ROI 时优先“一次全屏 OCR + 结果过滤”，多次 OCR 调用有固定开销。
5. PNG 只在真的要发给模型/落盘时编码；OCR 路径全程不编码。

### 7.3 引擎取舍

| 引擎 | 体积量级 | 中文 | 集成成本 | 全屏延迟量级 | 许可/备注 |
|---|---|---|---|---|---|
| ML Kit `text-recognition-chinese`（bundled） | +10–25MB | 好 | 低（Gradle） | 200–450ms | 不依赖 GMS；模型随包；受 Google 条款约束 |
| PaddleOCR-ncnn（PP-OCRv4 mobile int8） | ~8–12MB | 好 | 中（NDK+ncnn） | 150–400ms | Apache-2.0；可裁剪 |
| RapidOCR（ONNX Runtime Mobile） | ~20–30MB | 好 | 中 | 200–500ms | Apache-2.0；运行时偏大 |
| Tesseract4Android | ~15MB | 一般 | 低 | 400–900ms | Apache-2.0；UI 小字差 |

体积/延迟是量级估计，**以构建产物与真机 instrumentation 为准**（P2 门槛）。首选 ML Kit bundled：
不依赖 GMS、集成最快、中文可用；如果 APK 体积或授权条款不可接受，再换 PaddleOCR-ncnn。

### 7.4 融合

1. OCR 行 → 按行高/基线合并成块，过滤 <8px、置信度 <0.5 的碎片。
2. 每个块与树元素求交：完全包含 → 补 label；IoU>0.5 → 视为同一元素；都不满足 → 生成 OCR 元素。
3. 纯图标按钮：可点击 box 内没有文本，取最近块（≤24dp）作为 label；仍无 → `B 9 ? c`。
4. 去重：归一化空白/标点后与已有 label 相同则丢弃 OCR 副本。
5. 低置信统一标 `q=low`，金额/验证码/密码字段**不输出 OCR 结果**（宁可让模型放大截图或问用户）。

### 7.5 tile 差分

* 把画面切成 4×8 网格，每格 16×16 采样哈希；新帧只对变化格 OCR。
* 滚动用内容位移估计（相邻帧行匹配）平移已有 box，只 OCR 新露出的条带。
* 目标：首屏 200–450ms；滚动增量 60–150ms；静止重复请求 0ms（缓存）。

### 7.6 为什么不上端侧 VLM

| 方案 | 内存 | 单屏延迟 | 结论 |
|---|---|---|---|
| 1–3B int4 VLM | 1–2GB | 2–10s | 热路径不可行；与 proot/Web 抢内存 |
| 0.3–0.5B 小 VLM | 0.4–1GB | 0.5–2s | 中文 UI 理解弱，收益不抵成本 |
| 图标分类 CNN | 5–20MB | 5–20ms/区域 | 可选；只解决“纯图标按钮是什么” |

端侧 VLM 只在“用户显式要求解释这一屏”的异步场景有意义，且不得进入每步循环；
这与“极致性能识别”的目标一致：**把像素在本地变成结构，而不是把模型搬到本地。**

同理，即使模型本身支持视觉，也不把截图作为默认输入：一张 1080×2400 截图的 token 是紧凑语义表的 7 倍以上，
还带来延迟、不可复现和隐私暴露；截图只在“OCR 也读不出、用户明确要求看画面”时作为最后手段，
并且默认降采样、只截目标窗口。

## 8. 动作与效果验证

### 8.1 操作集

| op | 机制 | 验证 |
|---|---|---|
| `click` | 节点 `ACTION_CLICK`；节点不可点时用元素锚定手势 | 事件 + 差分 |
| `long_click` | `ACTION_LONG_CLICK` 或 600ms 手势 | 事件 + 差分 |
| `focus` | `ACTION_FOCUS` | 焦点字段 + 差分 |
| `set_text` | `ACTION_SET_TEXT`，先聚焦 | 再读该节点文本比对 |
| `clear_text` | `set_text("")` | 再读文本为空 |
| `scroll` | `ACTION_SCROLL_FORWARD/BACKWARD` | 可见项/滚动位置变化 |
| `press` | `GLOBAL_ACTION_*` | 窗口变化 |
| `tap_element` | 元素中心手势（需树快照新鲜且重新解算 bounds） | 事件 + 差分 |
| `swipe` | 显式坐标手势（仍需截图 observationId） | 事件 + 差分 |
| `wait` | 条件等待 | 命中/超时 |

### 8.2 元素锚定手势的安全性

坐标动作现在必须“先截图拿 observationId”（`ScreenActionExecutor.java` 的 coordinate 分支）。
元素锚定手势是它的补充，不是替代：

* key 来自最近一次快照；派发前在同一采集线程用 key 在当前树重新解析元素；
* 校验 window/package/epoch、角色、动作与 bounds signature；
* 位移 >8dp → `TARGET_MOVED`，key 消失 → `NODE_GONE`，多解 → `AMBIGUOUS_TARGET`，
  窗口/包名变化 → `TARGET_CHANGED`；**不落回旧坐标、不改用文本匹配**；
* 返回 `mechanism=ELEMENT_BOUNDS_GESTURE`，效果仍由事件+差分验证；
* 纯像素元素继续走截图 observation 路径，两条路径的凭据不混用。

这比“截图 5 秒前的坐标”更安全：坐标在派发前一刻重新解算；也比“再截一张图”快 50–90ms。

### 8.3 效果验证

动作提交前登记期望（`expect=change|none|text:"…"|idle`），提交后：

1. 等首个相关事件（≤500ms），再等 60ms 静止；
2. 重读状态（树路径）或复用 OCR 差分（像素路径）；
3. 分类：
   * `ver=changed` + 期望命中 → 动作生效；
   * `ver=changed` + 期望未命中 → 有变化但不是预期目标（附 delta，让模型判断）；
   * `ver=unchanged`（500ms 内无变化）→ 大概率无效，模型可换路径；
   * 派发中断/超时/取消 → `ver=unknown`，**不重放**。
4. 回复永远带 `ms`、`sid`、`delta`；`effectVerified:true` 只在第 3 步观察到差异时出现。

### 8.4 错误分类

`TARGET_CHANGED`（窗口/包名/epoch 变化）、`TARGET_MOVED`（位移超阈值）、`NODE_GONE`（key 消失）、
`AMBIGUOUS_TARGET`（key 多解）、`NOT_ACTIONABLE`、`SYSTEM_REJECTED`、`NO_CHANGE`、`UNKNOWN`、`TIMEOUT`。
每一种都对应“模型下一步该做什么”，而不是一句“失败”。

## 9. 本地服务与传输

先明确分工：**壳负责识别、解析、序列化、动作执行与验证；模型只读结构化文本并做决策。**
屏幕像素、OCR 中间结果、节点句柄都不离开 App 进程（模型拿到的只有壳生成的文本/增量）。

| 方案 | 延迟量级 | 适用 | 结论 |
|---|---|---|---|
| A. 进程内 + 现有 3090 桥（新增 `/app/sense/*`） | 0.3–2ms | DSH 会话 | **推荐**；不新增进程，不改授权链 |
| B. `LocalServerSocket`（UDS，应用私有目录，0600） | 0.1–0.5ms | proot 内工具、本地自动化 | 需要时加；比 TCP 更安全、更快 |
| C. loopback TCP 临时端口 + token 文件 | 0.3–2ms | 第三方/外部工具 | 只在 B 不可用时考虑 |
| D. proot 内独立 Python/Node 识别守护进程 | +1–3ms + 容器开销 | 复用 Python OCR 生态 | 不推荐：多 100–300MB 常驻、生命周期复杂 |
| E. 本地大模型服务（llama.cpp 等） | 秒级 | 异步“深读” | 不进热路径；P3 之后再说 |

A/B/C 的传输差异相对识别（10–300ms）可忽略，所以“拉起本地服务器”不是性能手段；
它的价值是隔离、权限边界和给容器内工具复用。现有 3090 桥已经满足 A，新增端点即可，
不需要再起一个 HTTP 服务。

如果确实要 UDS：

* 套接字放 `getFilesDir()/sense.sock`，目录 0700、socket 0600；只允许同 UID/受信组件；
* 帧格式：4 字节长度 + 1 字节类型 + payload；单帧上限 256KB；状态帧用二进制，动作帧用文本；
* 序号 + 代次：每帧带 `sid`，客户端必须回显；服务端拒绝旧代次；
* 背压：单连接最多 2 个在途请求；事件流合并，丢弃过期帧而不是排队；
* 退出：任务结束、服务销毁、屏幕锁定/切换用户时关闭并清理 socket 文件。

安全底线：只绑 loopback/UDS；token 每进程随机；不写入日志；不导出屏幕文本；
密码字段与安全窗口（FLAG_SECURE）不 OCR、不缓存、不传输。

### 9.1 工具面统一

现在模型看到两套屏幕工具（`android_*` 1,370 token + `vscreen_*` 2,850 token）。
建议收敛成一套 5 个工具，用 `display=0|virtual:<coreInstance>` 区分：

| 工具 | 作用 | 返回 |
|---|---|---|
| `screen_state` | 当前状态（全量/缓存） | 文本状态 + `sid` |
| `screen_act` | 动作 + 验证 + 增量 | `{ok, ver, ms, sid, delta}` |
| `screen_wait` | 条件等待 | 命中/超时 + delta |
| `screen_read` | 读某元素全文 | 文本 |
| `screen_shot` | 截图（最后手段，默认 720p） | 图片 + 元数据 |

工具描述压到 150–250 token/个，总计 ≤1,000；比现状省 3.2k token/请求。
旧工具保留一个版本做兼容，通过 `/app/version` 的 `SCREEN_SENSE=1` 特性检测切换。

## 10. 性能预算与测量

### 10.1 目标预算（待真机校准）

| 阶段 | p50 | p95 | 说明 |
|---|---:|---:|---|
| 缓存命中 `state` | 0.5ms | 2ms | 无事件、窗口未变 |
| 树遍历 150 元素 | 8ms | 20ms | 单线程 Binder；避免 `getActionList` 逐节点 |
| 归一化 + 编码 | 2ms | 5ms | 纯内存，无 JSON |
| 树路径 `state` 合计 | 15ms | 40ms | 冷读 |
| `takeScreenshot` | 40ms | 90ms | 系统回调 |
| 缩放 + 软件位图 | 3ms | 8ms | 单次 drawBitmap |
| 全屏 OCR | 250ms | 450ms | ML Kit 中文，1080→1280 |
| tile 增量 OCR | 80ms | 150ms | 2–4 个变化格 |
| 动作派发 | 2ms | 6ms | 节点动作/手势 |
| 效果验证 | 60ms | 200ms | 事件 + 静止 + 差分 |
| `act` 往返（树） | 25ms | 80ms | 含验证与增量 |
| `act` 往返（OCR） | 200ms | 500ms | 兜底路径 |

这些是**设计预算**，不是实测。P1/P2 用 instrumentation 校准；达不到就调整方案，而不是改口径。

### 10.2 怎么测

* **微基准**：`androidx.benchmark` 或自写 instrumentation，固定 3 个应用（设置、浏览器、聊天），
  每个 100 次，报告 p50/p95/最大；区分冷启动、缓存命中、滚动中、动画中。
* **token 计账**：离线 tokenizer 对“真实录制快照”计数（录制的是节点结构，不是用户数据）；
  CI 里对 60/120/200 三档设阈值（≤600/≤1,100/≤1,800）。CI 不引 6MB tokenizer 时，可用字符数代理
  （v4 60 元素 ≤900 字符、120 元素 ≤1,900 字符），token 由离线脚本定期复核。
* **识别质量**：200 屏标注集（脱敏）上，元素召回 ≥95%、角色准确 ≥90%、label 准确 ≥95%；
  OCR 另计字符错误率（CER）与数字/金额字段的精确率。没有这套标注，不上“准确率”口径。
* **端到端**：脚本化 20 步任务（打开应用 → 搜索 → 进入会话 → 发送 → 返回），记录墙钟、请求数、
  总 token、失败/重试次数；与现状对照。
* **功耗/热**：Battery Historian + 30 分钟混合负载；对比“纯树路径 / 树+OCR / 空闲”。
* **稳定性**：旋转、分屏、PiP、IME、锁屏、切应用、深色模式、1.3 倍字体、WebView。

测量纪律：release 包、关闭动画、固定亮度/网络、每轮前清后台；报告要带设备/系统/构建号；
不把一次样本写成普遍结论。

### 10.3 优化优先级（按收益/成本）

| 优先级 | 改动 | 预期收益 | 成本 |
|---|---|---|---|
| 1 | 去掉 text + nodes 双份编码 | token −23%，零风险 | 0.5 天 |
| 2 | v4 紧凑格式 + 动作 key + 角色/合并 | token −80–95% | 3–5 天 |
| 3 | `act` 返回增量与新状态 | 请求 −50%，token −90% | 1 周 |
| 4 | 事件缓存 + 稳定判定 | 空闲重复读 0ms | 1–2 周 |
| 5 | 遍历优化（动作位掩码、去 clone、复用 Rect） | 树读 −30–50%（待测） | 3–5 天 |
| 6 | 采集与截图并行 | 像素路径 −30–60ms | 3 天 |
| 7 | 截图默认 720p + JPEG/WebP | 编码 −50–100ms，图像 token −55% | 2 天 |
| 8 | OCR + tile 差分 | 空树可读；增量 60–150ms | 2–4 周 |
| 9 | `wait` 原语 | 去掉轮询往返 | 3 天 |
| 10 | 统一工具面 | 工具目录 −3.2k token | 1 周（DSH 侧） |

其中 1–3 是“立刻能做、收益最大、风险最低”的一组；5 的具体数字必须真机测，不能凭直觉写进验收。

## 11. 重构落点

### 11.1 新包 `com.deepseekharness.app.sense`

| 文件 | 职责 | 估行 |
|---|---|---:|
| `SenseService.java` | 生命周期、缓存、会话、单飞、读写锁 | 200 |
| `EventGate.java` | 事件元数据、dirty、静止判定、期望匹配 | 150 |
| `TreeCollector.java` | 迭代遍历、动作位掩码、最少 Binder 调用 | 200 |
| `NodeNormalizer.java` | 合并/裁剪/角色/去重 | 250 |
| `ScreenState.java` | 不可变快照（sid/epoch/elements/quality） | 120 |
| `StateDiffer.java` | 增量 | 120 |
| `TextCodec.java` | v4 文本编码 | 150 |
| `BinaryCodec.java` | varint/CBOR + zstd | 150 |
| `PixelAnalyzer.java` | 截图、缩放、tile、ROI | 250 |
| `OcrEngine.java` / `MlKitOcrEngine.java` | OCR 抽象与实现 | 200 |
| `ActionRouter.java` | 动作派发、元素锚定手势 | 200 |
| `EffectWatcher.java` | 期望、事件窗口、差分验证 | 150 |
| `SenseRoutes.java` | `/app/sense/*` 适配与鉴权 | 150 |

合计约 2,300 行（含注释），加单测/仪器测试约 800 行。

### 11.2 既有文件改动

| 文件 | 改动 |
|---|---|
| `screen/MainScreenActions.java:266` | `run()` 增加 `sense.state/act/wait/read`；旧 `dump/tree/node` 保留并映射到新实现 |
| `util/ScreenRequestExecutor.java:16` | 拆“变更单线程”与“只读并发”；保留 15s 响应上限与 UNKNOWN 语义 |
| `HttpShellService.java:1561` | 新路由、`/app/help` 文案、协议版本升到 4 |
| `screen/ScreenSnapshotReader.java` | v1 输出冻结；新路径走 `TreeCollector + TextCodec` |
| `util/ScreenNodeLeaseStore.java` | 新路径不再长期持有 200 个 clone；动作时按 key 重解析（旧路径保持） |
| `screen/ScreenActionExecutor.java` | 增加 `ELEMENT_BOUNDS_GESTURE` 与期望验证钩子；坐标/截图路径不动 |
| `vscreen/VirtualScreenAccessibility.java` | 输出同一套 `ScreenState`，`display` 参数区分 |
| `res/xml/accessibility_service_config.xml` | 试验 `flagRequestEnhancedWebAccessibility`（需评估 Binder 成本） |

### 11.3 端点与协议

```
GET /app/sense/state?full=1|0&geom=0|1&max=200
GET /app/sense/act?key=k6Rc&op=click&sid=12&expect=change&wait=500
GET /app/sense/wait?since=13&until=text~=%E5%B7%B2%E5%8F%91%E9%80%81&timeout=3000
GET /app/sense/read?id=7&max=4000
（可选）GET /app/sense/stream   WebSocket/SSE，二进制帧
```

* 全部复用现有 token 鉴权、任务绑定、目标校验与重放规则；
* `dump/tree/node` 保留，内部可切到新实现；`BRIDGE_PROTOCOL=4`，`/app/version` 增加 `SCREEN_SENSE=1`；
* 旧客户端按版本号降级到 v1，不 break；
* 回滚：服务端开关 `screen_sense=0` 立即回到 v1 编码/两次请求模型，不改 APK；开关状态写入诊断日志。

### 11.4 测试

* 纯逻辑（`util` 风格，无 Android import）：归一化、角色、合并、编码/解码、差分、期望匹配、代次拒绝。
* Robolectric：用录制的节点结构 fixture（脱敏、非用户数据）跑端到端状态机。
* Instrumentation：微基准 + 空树/Canvas 应用 + WebView + 长列表 + IME。
* 回归负控：旧 `dump` 字节不变；旧 `node` 一次性语义不变；截图 observation 仍拒绝 dump 坐标。

## 12. 风险与对策

| 风险 | 影响 | 对策 | 残留 |
|---|---|---|---|
| a11y 树缺失/残缺（Flutter、Unity、游戏、SurfaceView） | 识别不可用 | OCR 兜底 + 覆盖率抽样 | 复杂图形界面仍可能不可读 |
| OCR 中文/小字/艺术字错误 | 误读、误点 | 置信度门限、金额/验证码不 OCR、低置信标 `?` | 需要真实样本评估 CER |
| 隐私（密码、验证码、聊天内容） | 高 | 密码不读、安全窗口不截、内存不落盘、日志不写文本 | 文本仍会进模型上下文（这是任务本身） |
| 功耗/热 | 中 | 只在任务期识别；OCR 按需；无后台扫描 | 长任务仍需实测 |
| APK 体积/许可 | 中 | bundled 模型按需选型；构建核验体积 | 可能 +10–25MB |
| OEM/ROM 差异 | 中 | 设备矩阵；事件/截图失败降级 | 无法覆盖全部 ROM |
| GC/大堆导致 p95 抖动 | 中 | 新路径不持 clone、少分配；必要时拆 `:sense` 进程 | 拆进程会加 IPC 与生命周期复杂度 |
| 协议/工具面变更 | 中 | 版本特性检测；旧端点冻结；分阶段 | 旧客户端需一版兼容期 |
| 本地服务被滥用 | 中 | loopback/UDS + token + 文件权限；不导出文本 | 同 UID 恶意代码仍可能读内存（非内核沙箱） |
| “已验证”过度宣称 | 高 | 只有观察到差异才 `effectVerified:true`；UNKNOWN 不重放 | 仍需人工复核关键操作 |

## 13. 分阶段计划

| 阶段 | 工期 | 交付 | 退出条件 |
|---|---|---|---|
| P0 格式与动作返回体 | 3–5 天 | v4 编码、`act` 回增量、工具文案 | 60 元素 ≤600 token；动作不再需要第二次 dump；旧端点字节不变 |
| P1 缓存/差分/并发 | 1–2 周 | 事件门、缓存、稳定判定、读写分离、遍历优化 | 冷读 p50 ≤25ms/p95 ≤60ms；缓存命中 ≥60%；陈旧动作 100% 拒绝 |
| P2 像素兜底 | 2–4 周 | 截图缩放、OCR、融合、tile 差分 | 空树界面可读率 ≥80%；OCR p50 ≤400ms；密码/安全窗口 0 泄漏 |
| P3 本地服务与工具统一 | 1–2 周 | UDS/流（可选）、`wait`、5 工具面 | `act` 往返 p50 ≤80ms；工具目录 ≤1k token |
| P4 真机矩阵与功耗 | 1–2 周 | 3 机型 × 6 场景报告、功耗曲线 | 无 p95 回退；长任务温升/耗电在预算内 |

P0/P1 不依赖 OCR，也不依赖任何模型，先做；P2 是否值得做，由 P1 结束时的“空树占比”抽样决定
（建议门槛：空树/低语义屏占任务步数 >5%，或占失败步数 >20%，才投入 OCR）。
按单人投入估算 6–10 周；两人并行可压到 4–6 周，但 P0/P1 的顺序不能反。

## 14. 验收清单与未验证声明

验收（全部满足才算“极致性能识别”落地）：

- [ ] 60 元素全量 ≤600 token，120 元素 ≤1,100，200 元素 ≤1,800（离线 tokenizer 计数；P0a 已有固定样本与字符闸，token 尚未在固定 tokenizer 上复核）；
- [ ] 动作一次调用返回 `sid + delta + ver`，常规路径不再需要第二次 `state`；
- [ ] `ver=changed/unchanged/unknown` 有事件/差分证据；UNKNOWN 不自动重放；
- [ ] `TARGET_CHANGED`/`TARGET_MOVED`/`NODE_GONE`/`AMBIGUOUS_TARGET` 在目标变化时 100% 拒绝；
- [ ] 缓存命中与失效可观测（`ageMs`、`dirty` 原因）；
- [ ] 空树界面 OCR 可读率 ≥80%，密码/安全窗口 0 泄漏；
- [ ] 像素/OCR 结果不落盘、不进日志、不出设备；
- [ ] v1 端点与工具行为不变，协议版本可特性检测；
- [ ] 微基准/端到端/功耗三类报告齐全，含设备与构建号；
- [ ] 旧 `dump` 与 `node` 的负控回归通过。

未验证声明（本文不声称已完成）：

- 所有延迟数字是**设计预算**，未在真机测量；P1/P2 校准后以实测为准。
- token 比例是合成样本 + 离线 tokenizer 的**实测**；绝对 token 随页面节点数与文本长度变化。
- OCR 引擎体积/延迟是**量级估计**，需构建后按 APK 增量与 instrumentation 核验。
- 未验证各家 ROM 的事件投递、截图黑屏、后台限制差异。
- 本文原始评估阶段未实现代码；当前任务分支已落地 P0 只读预览与相关纯逻辑骨架，但尚未完成本节全部验收项，因此不能称为“极致性能识别”最终验收回执。

## 附录 A：测量方法与复现

样本：30/60/120/200 个节点，55% 可点击、4% 输入、5% 滚动，中文标签池（微信式短句）。
“现状 dump”按 `ScreenSnapshotReader` 的真实字段与文本行重建；“紧凑表”为同数据的 v4/早期 v1 编码。
token 计数用 DeepSeek V4 tokenizer（`tokenizer.json`，BPE）离线复现；对给定字符串计数是确定值，
样本代表性另有波动。

原始评估脚本只在临时目录 `/tmp/screen-eval`，下表是当时的结果，未随仓库保存，不能逐字复现。
P0a 起改用仓库内的 `tools/screen-eval/screen_eval.py`：固定 LCG 种子生成 200 个合成元素
（`app/src/test/resources/.../screen-sense-synthetic-200.tsv`），compact 编码逐字节镜像 `ScreenSenseCodec`，
与 Java 单测 `ScreenSenseBudgetTest` 共用同一 SHA-256 golden；tokenizer 只在评估机按 `--tokenizer-sha256`
固定摘要加载，模型文件不入库，未提供时 token 字段为 `null`。

```bash
python3 tools/screen-eval/screen_eval.py --check      # 样本、golden、字符上限
python3 tools/screen-eval/screen_eval.py --summary    # 字符/字节摘要；加 --tokenizer 与 --tokenizer-sha256 才计 token
python3 tools/test-screen-eval.py                     # 自检（host test，fast 阶段）
```

P0a 实测（合成样本、字符数，未计 token）：当前 codec 去掉逐行占位符后，30/60/120/200 元素为
516/963/1,869/3,090 字符（legacy 重建约为 16 倍）。120 元素满足 §10.2 的 ≤1,900 字符代理口径；
**60 元素 963 字符未达 ≤900**，原因是本实现每个元素都带预览 id（附录 B 的纯文本行用位置编号）。
CI 回归上限设为 60≤1,000 / 120≤1,900 / 200≤3,200，是防回退的闸，不是已满足原目标的证明。

P0a 真机只读核对（2026-11-01 19:22Z，测试包 `io.github.sunsetrne.dsha.test` build256 + 本分支，
`tools/screen-eval/device_probe.py --launch com.android.settings`，单台平板、单个原生页面，只记录计数）：

| 项 | 旧 tree | sense/state | sense/state?geom=1 |
|---|---:|---:|---:|
| 元素 / 节点 | 31 | 31 | 31 |
| 访问节点 | 135 | 68 | 68 |
| 回复字符 | 10,277 | 1,162 | 1,731 |
| 墙钟 ms（含桥往返） | 394 | 166 | 198 |
| quality / partial | — | ok / false | ok / false |

前后两次 tree 一致（`pageStable=true`），sense 标签与 tree 31/31 对上；把 `k000` 当 nodeId 交给 `/app/ui/node`
返回 `STALE_NODE`（预览 id 不能当动作凭据）。另在 DSH Web 聊天页（WebView）上观察到：首次查询只返回外壳
（访问 3 个节点），约 0.5 秒后才建出 247 个节点，sense 达到 200 上限返回 `quality=partial`；该页平均每元素约 80 字符，
远高于合成样本，**合成字符预算不能外推到真机页面**。以上只是单页单次结果，不是 p50/p95 或覆盖率结论。

原始评估结果（临时脚本，仅供对照）：

| 文件 | 字符 | token |
|---|---:|---:|
| `cur_60.txt`（现状 dump） | 19,868 | 8,093 |
| `textonly_60.txt`（仅 text 字段） | 3,079 | 1,832 |
| `v4_60.txt`（v4，60 元素） | 839 | 515 |
| `sample_v4.txt`（12 元素示例） | 210 | 132 |
| `sample_delta4.txt`（2 处变化） | 44 | 33 |
| `tooldefs.json`（android 工具面） | 4,161 | 1,370 |
| `vstools.json`（vscreen 工具面） | 8,094 | 2,850 |

说明：合成样本的 55% 可点击高于多数真实页面，因此“现状 dump”的绝对 token 偏保守（真实聊天页可能 30–80 元素）；
比例结论不受影响。tokenizer 只用于评估，不进入 App、不参与运行时。

## 附录 B：v4 完整样例与规模

```
S9 com.example.chat 1080x2400 q=ok f=k3Xn ime=1
k5Hq B "返回" c
k9Wm B "聊天信息" c
k2Lp L "消息列表" fb
1 T "张三 19:02"
2 T "今晚一起吃饭吗？"
3 T "我 19:03"
4 T "好，七点见"
k7Vt B "语音" cl
k3Xn I "输入消息" t
k8Qa B "表情" c
k6Rc B "发送" c
```

增量：

```
D 13 12 2
~k6Rc B "发送中…"
+T "我 19:04 好，七点见"
```

规模（合成样本，60 元素）：v4 文本 839 字符 / 515 token；varint 二进制 974 字节；
gzip 后 651 字节。二进制只用于本地自动化，不发给模型。

## 附录 C：平台依据

- 截图：`AccessibilityService.takeScreenshot()`（API 30+，需 `canTakeScreenshot`）与
  `ScreenshotResult.getHardwareBuffer()` / `Bitmap.wrapHardwareBuffer()`。
- 节点：`AccessibilityNodeInfo`（`getStateDescription`、`getHintText`、`getPaneTitle`、
  `getTooltipText`、`CollectionInfo`），`AccessibilityEvent` 的 `contentChangeTypes`。
- 增强 Web 语义：`AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY`（需实测成本）。
- OCR：Google ML Kit Text Recognition v2（中文 bundled）；PaddleOCR（PP-OCR mobile）。
- 推理后端：NNAPI 已进入弃用流程，新方案优先 LiteRT + 厂商 delegate（QNN/NeuroPilot 等），
  不要为新代码绑定 NNAPI。
- 相关文档：[虚拟屏架构](../../virtual-screen-architecture.md)、[设备权限架构](../../device-permissions-architecture.md)、
  [安全模型](../../security-model.md)、[UI 性能基线](../../ui-performance-20261022.md)。

外部参考：
[AccessibilityService#takeScreenshot](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)) ·
[AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo) ·
[ML Kit Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) ·
[PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) ·
[NNAPI 迁移指南](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)
