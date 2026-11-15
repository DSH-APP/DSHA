# 宽屏触屏平板侧栏会话行挤压（dsh-web-mobile ≥1024px）

> **状态：已由上游接管（2026-11-06 同步 dsh-web-mobile 3.3.1 / `4e71eb67`）。** 上游
> [issue #255](https://github.com/mexiaosqwq/dsh-web-mobile/issues/255) 的修复已进入固定快照
> （`712999a`，PR #257），DSHA 本地补丁 `DSHA_WIDE_TOUCH_ROW_ACTIONS_V1` 已从
> `tools/apply-mobile-client-patches.mjs` 删除；`tools/test-mobile-client-patches.mjs` 改为校验
> 上游规则与手机壳名单逐字一致。修复方向不变：行内只保留「时间 + ⋯」，归档/置顶留在 ⋯ 菜单。
> 记录基线（历史）：build218 / DSH 0.2.1-rc.5 / dsh-web-mobile 3.2.2（上游 main `bb0e5e15`，`v3.2.2` 标签同指）。

## 1. 结论

同一台设备旋转后左侧栏外框宽度相同（都是 280px），差异全部发生在会话行内部：
横屏 CSS 视口正好是 **1024px**，跨过手机壳断点（`max-width:1023px`），进入
`@media (min-width:1024px) and (pointer:coarse)` 的宽屏触屏档。该档把会话行
`_rowActions` 常显并放大成 3 个 **40×40** 热区，却没有像手机壳那样把归档/置顶
收进 ⋯ 菜单，也没有隐藏宿主的 `.time`；280px 侧栏里标题只剩约 **55px**。

## 2. 现场与实测

| 项目 | 横屏 | 竖屏 |
|---|---|---|
| 物理屏幕 | 2560×1600 | 1600×2560 |
| WebView dpr | 2.5 | 2.5 |
| CSS 视口 | **1024×640** | **640×1024** |
| 命中分支 | 原生侧栏 + 平板触屏档 | 手机壳抽屉 `[data-mobile-nav="frame"]` |
| 侧栏宽 | 280（可见边界 278） | 280（`min(88vw, 280px)`） |
| 会话行距 | 46px（`min-height:44` + 2px） | 34px（宿主 32 + 2px） |
| 标题可用宽 | **≈55px**（3–4 字 + …） | ≈160px |
| 行尾元素 | 时间 + ⋯ + 归档 + 置顶 | 时间 + ⋯ |

量测方法（设备截图，不进仓库）：截图 2560×1600；`新会话` 按钮 CSS 38px、实测 95 物理像素
→ dpr 2.5；行距按标题文本基线聚类（横屏 115、竖屏 85 物理像素）；标题/时间/按钮宽按文本与
图标像素起止换算。横屏 CSS 宽 1024 正好落在 `max-width:1023` 与 `min-width:1024` 之间。

## 3. 根因链路

1. [breakpoints.ts:19](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/platform/breakpoints.ts#L19)
   `MOBILE_QUERY = '(max-width: 1023px) and (pointer: coarse)'`；
   [breakpoints.ts:36](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/platform/breakpoints.ts#L36)
   `TABLET_TOUCH_QUERY = '(min-width: 1024px) and (pointer: coarse)'`。宿主
   `SIDEBAR_AUTO_COLLAPSE = 1024`，所以 1024 不收成 rail，直接是原生侧栏。
2. 竖屏 640 走手机壳：frame 作用域内
   [layout.css.ts:3456](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/styles/layout.css.ts#L3456)
   常显 ⋯；同一文件
   [layout.css.ts:3469](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/styles/layout.css.ts#L3469)
   按 aria-label 精确匹配，把归档/置顶隐藏（#154 的结论）；行仍是宿主的 32px。
3. 横屏 1024 走
   [misc.css.ts:409](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/styles/misc.css.ts#L409)
   的宽屏触屏档：`_rowActions` 常显、行 `min-height:44px`、按钮 40×40、gap 4
   （[misc.css.ts:421](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/styles/misc.css.ts#L421)、
   [misc.css.ts:428](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/styles/misc.css.ts#L428)）。
   **这一档没有隐藏归档/置顶的对应规则。**
4. 宿主行 `.YDXeBa_sessionRow` 原本是 hover 设计：32px 行、16px 图标、gap 15，且
   `:hover` 时 `.YDXeBa_time` 隐藏。平板档只强制 actions 常显、不触发 hover，所以
   time 仍常显；三个大热区加时间常驻，是宿主从未设计过的组合。
5. 占位：`3×40 + 2×4 = 128px`；`280 − 12×2`（侧栏 padding）`− 8×2`（行 padding）
   `− 16`（slot）`− 10`（title 外边距）`− ~30`（time）`≈ 55px` 留给标题。

## 4. 上游处置

- 上游 issue：[mexiaosqwq/dsh-web-mobile#255](https://github.com/mexiaosqwq/dsh-web-mobile/issues/255)
  （2026-10-31，OPEN，无 label）。
- 上游 PR：[mexiaosqwq/dsh-web-mobile#256](https://github.com/mexiaosqwq/dsh-web-mobile/pull/256)
  （2026-10-31，OPEN；分支 `SunsetRNE:fix/255-wide-touch-row-actions`，1 个 commit，
  含源码、测试、重建的 `lib/` 与补丁登记库存）。
- 2026-10-31 核对：上游 main `bb0e5e15` 的 `src/client/styles/misc.css.ts:409` 仍在，
  `layout.css.ts:3469` 只作用于手机壳 frame，宽屏触屏档没有同类规则。
- DSHA 内置 3.2.2 从 build210（上游 `04dcd19`）起包含平板档；build216 同步到 `bb0e5e15`
  后行为不变。上游暂无重复 issue；#154 是手机档同源结论，#183/#241 不覆盖宽屏触屏档。
- 结论：上游合并前由 DSHA 补丁带着跑；合并并同步固定快照后摘除补丁。

## 5. 修复方向 A（已落地）

在 [misc.css.ts:409](../app/src/main/assets/builtin-plugins/dsh-web-mobile/src/client/styles/misc.css.ts#L409)
的 `@media (min-width: 1024px) and (pointer: coarse)` 内追加与手机壳同源的规则：

```css
[class*="sessionRow"] [class*="_rowActions"] :is(
  [aria-label="归档会话"], [aria-label="取消归档"],
  [aria-label="置顶会话"], [aria-label="取消置顶"],
  [aria-label="Archive session"], [aria-label="Unarchive session"],
  [aria-label="Pin session"], [aria-label="Unpin session"]
) {
  display: none !important;
}
```

边界：

- 只影响 ≥1024 且粗指针的会话行；搜索行 `searchResultRow` 不含 `_rowActions`，不受影响。
- ⋯ 仍是 40×40 热区；行高仍 44（触屏取舍，本轮不改）。
- 归档/置顶/取消归档/取消置顶/分叉/删除仍在 ⋯ 菜单内，不丢入口。
- 只精确匹配宿主 zh/en 的 8 个 aria-label；第三方注册到同一槽位的按钮与其他语言静默不隐藏，
  不会误伤（与 #154 同一策略）。
- 保留时间与置顶指示的宿主逻辑；修复后行尾与竖屏一致，只有行距仍不同。

### 本地落地（2026-10-31）

- [apply-mobile-client-patches.mjs](../tools/apply-mobile-client-patches.mjs) 在
  `①′` 热区规则之后、`① 隐藏「更多视图」` 之前插入同一段隐藏规则（锚点替换，不手改上游
  源），带 `@patch id: wide-touch-row-actions` 与产物标记 `DSHA_WIDE_TOUCH_ROW_ACTIONS_V1`。
- 内置 [client.js](../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js) 已用
  `node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --write`
  重新生成；`--check` 与 `generate-builtin-plugins.py --verify` 均通过。
- [test-mobile-client-patches.mjs](../tools/test-mobile-client-patches.mjs) 新增用例：
  规则必须落在 `(min-width:1024px) and (pointer:coarse)` 档内、名单与手机壳 #154 逐字一致
  （8 个标签）、`display:none !important`。
- 上游 PR #256 合入并同步快照后，按 §7 删除本块与对应测试分支。

### 为什么不选 B / C

- **B（常显 actions 时隐藏 time）**：标题只回到约 95px，仍然挤，且与手机壳不一致。
- **C（只把 40px 热区缩到 32）**：只回收 24px，标题约 80px，仍然不够。
- **加宽侧栏**：宿主默认 280 可拖，但不能依赖用户设置，也不解决 time + 3 图标常显的状态。

## 6. 前后验收（要求差异明显）

### 6.1 硬指标

| 指标 | 修复前（build218 实测） | 修复后（目标；内置 client 已改） |
|---|---|---|
| 行尾元素 | 时间 + ⋯ + 归档 + 置顶 | 时间 + ⋯ |
| 行内归档/置顶按钮 | 可见 | 隐藏（⋯ 菜单可点） |
| 标题可用宽 | ≈55px（3–4 字 + …） | **≥140px**（按占位推算约 143） |
| 行距 | 46px | 46px（不变） |
| 竖屏 640×1024 | 约 160px / 34px 行距 | 不变 |
| 鼠标桌面（pointer:fine） | 宿主 hover | 不变 |

视觉差异（同一行示意，单位为 CSS px）：

```text
修复前（横屏 1024×640，侧栏 280px）：
  [○] 示例会话标题…          14分钟  ⋯  ▢  ☆
      └ 标题 55px ┘          └30┘  └──── 128px：3 个 40×40 ────┘

修复后（方向 A）：
  [○] 示例会话标题（完整可见，约 10 字以上）  14分钟  ⋯
      └────────── ≥140px ──────────┘        └30┘  └40┘
```

行高不改（仍 46px）；差异集中在行尾：4 个元素变 2 个、标题从 3–4 字恢复到 10 字以上。

### 6.2 控制台量测

在 1024×640、`pointer:coarse` 的 WebView 控制台执行：

```js
const row = [...document.querySelectorAll('[class*="sessionRow"]')]
  .find(el => el.querySelector('[class*="_title"]'));
const w = el => el ? Math.round(el.getBoundingClientRect().width) : 0;
const shown = [...row.querySelectorAll('[class*="_rowActions"] button')]
  .filter(b => b.getClientRects().length > 0);
console.log({
  rowHeight: Math.round(row.getBoundingClientRect().height),
  titleWidth: w(row.querySelector('[class*="_title"]')),
  timeWidth: w(row.querySelector('[class*="_time"]')),
  visibleActions: shown.map(b => b.getAttribute('aria-label')),
});
```

判定：`titleWidth >= 140`；`visibleActions` 只剩 ⋯（label 含会话标题，不含「归档/置顶」）；
`rowHeight` 仍为 44。竖屏同脚本应保持「时间 + ⋯」且 `titleWidth ≈ 160`。

### 6.3 真机验收

> 2026-10-31：内置 `client.js` 已含补丁，但本机仍运行 build218 安装包；下面两项要等包含
> 本次改动的 APK 安装后复测，届时把前后数字与截图结论写回本文。

- 同一台 2560×1600 @2.5 设备横屏 1024×640：修复前后各截一张侧栏会话行图，按 §6.1 核对；
  截图只在本机/验收记录引用，不进仓库。
- 竖屏 640×1024：布局不得变化。
- 桌面鼠标窗口 ≥1024：hover 才出 actions 的宿主行为不得变化。
- ⋯ 菜单内归档/取消归档/置顶/取消置顶/分叉/删除均可用；中英文各测一次隐藏规则。

## 7. 实施记录（完整流程）

1. **上游跟踪（已执行）**：issue #255、PR #256（2026-10-31）。上游合并后按固定提交更新
   `tools/mobile-plugin-upstream/`，重放本地补丁并 `python3 tools/generate-builtin-plugins.py --verify`。
2. **本地补丁（已执行）**：按方向 A 在 [apply-mobile-client-patches.mjs](../tools/apply-mobile-client-patches.mjs)
   增加锚点补丁（`@patch id: wide-touch-row-actions` + 产物标记
   `DSHA_WIDE_TOUCH_ROW_ACTIONS_V1`），只改 `lib/client.js`，不改上游源；覆盖 §5 的 8 个 aria-label。
3. **验证（已执行）**：
   ```bash
   node tools/test-mobile-client-patches.mjs
   python3 tools/generate-builtin-plugins.py --verify
   python3 tools/check-source-hygiene.py
   ```
   真机侧按 §6.2/§6.3 量测与截图，等新 APK 安装后补写。
4. **收尾（待上游）**：上游 PR #256 合入并同步后，删除 `wide-touch-row-actions` 整块、
   `DSHA_WIDE_TOUCH_ROW_ACTIONS_V1` 测试分支与本文的本地补丁段，重新生成产物；
   更新 [CHANGELOG](../CHANGELOG.md) 与发布说明。

## 8. 风险与边界

- 本地补丁已改内置 `client.js`（下一版 APK 生效）；当前安装的 build218 行为不变，真机前后
  数字与截图待新包复测。
- 隐藏归档/置顶只发生在 ≥1024 触屏档；桌面鼠标完全不受影响。
- 依赖宿主 aria-label 的 zh/en 文案；宿主换文案时规则静默失效（按钮照常显示），不会误隐藏。
- 行距仍为 46px、与竖屏 34px 不同，这是触屏热区取舍，不在本轮修复范围。
- 本机横屏 CSS 宽正好 1024，属于断点边界；其他 ≥1024 的触屏平板（如 1280）同样命中宽屏
  触屏档，侧栏默认仍是 280px，挤压程度相同（除非用户手动加宽侧栏）。
