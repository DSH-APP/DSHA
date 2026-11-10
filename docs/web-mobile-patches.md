# 内置移动插件 dsh-web-mobile 的 DSHA 本地补丁

> **当前状态（build351 候选，2026-11-09 同步上游 3.3.5）**：以上游 3.3.5（适配宿主 0.2.2，
> main `646fe837`）为基座，**完全追随上游**；只保留「宿主集成必需且上游不覆盖」的最小集
> （服务端鉴权 / 安全区 / 齿轮几何让位）与两项用户可见能力（原生文件传输、多语言/live-locale）。
> 会话输入 V4、手机搜索、快捷键弹层可见带、字体族行换行、遮罩按压染色仍不重放；本次上游
> 37 个提交的差异见下方「2026-11-09」小节。真机覆盖升级验收仍须在 build351 发布前补写，见
> [插件页](plugins.md)与 [build351 记录](releases/v0.2.1-rc.5-build351-web-mobile-3.3.5-20261109.md)。

本文记录 [`tools/apply-mobile-client-patches.mjs`](../tools/apply-mobile-client-patches.mjs) 对固定上游
`dsh-web-mobile` 3.3.5（[`646fe837440f9939228bdba0024d407eaac01128`](https://github.com/mexiaosqwq/dsh-web-mobile/commit/646fe837440f9939228bdba0024d407eaac01128)，
2026-11-08；发布标签 v3.3.5 = `14ecfccf`，标签后 3 个修复提交一并取用）
的本地差异。上游产物不手改；改动写进补丁脚本，再生成
[`app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js`](../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js)。

## 2026-11-09：同步上游 3.3.5 与补丁锚点复核

### 上游事实（只读核对）

- 固定提交 `646fe837…`（main，2026-11-08）；`v3.3.5` 标签 `14ecfccf` 之后还有 3 个提交
  （`7e08d6a` README 只留近三版、`1402ca8` 插件页交还宿主排版、`646fe83` 合并 PR #292）。
  相对 `4e71eb67`（v3.3.1）共 37 个提交。
- 主要变化：插件页图标与中英文标题描述（`assets/icon.png`、`locale/{en,zh}.json`、
  `package.json#icon` / `exports ./locale/*.json`）、手机档「更多视图」入口、子代理 chip 让位、
  抽屉暗层收敛到 frame 伪元素、`overlay-backdrop-fab` 改名 `drawer-layer`、全屏手势按方向定族
  （左右 45% 起点区退役，`startZonePxFor` / `hitTestStart` / `filesZoneHit` 删除）、hero 工作树行
  排版、插件页交还宿主排版、测试门禁空转加固。
- 服务端三模块 `lib/{index,compress,delete-session}.js` 本次未变，上游摘要与锚点继续命中，
  DSHA 的完整替换不需要跟着改。
- `lib/client.js` 摘要变为 `8a17dd03b5de35f1aea52c93d6e3352fcd43c781de380639f60a93bf6040a26a`；
  上游源码归档 SHA-256 `15a51f4018485d930e66b5734b4421da2a32fbca59e20219b68f4c7fe34c7515`
  （3,291,698 B）。

### 补丁复核

- `DSHA_GEAR_FIT_V1`：上游仍保留旧的 `:has(> button[data-phase], > [role="status"])` 守卫，
  齿轮几何让位补丁照旧命中并替换；`test-mobile-gear-contract.mjs` 6 项通过。
- 安全区：上游仍只有 frame 顶部使用 DSHA 变量，其余 `env()` 处继续由单层包装覆盖，未产生嵌套。
- 文件传输 / 多语言：`applyFileTransferClient`、`applyMobileLocaleBundlePatch` 与
  `--locale-sources` 的锚点全部命中；多语言键集删除了上游 3.3.5 移除的 nav `backdrop`
  （抽屉暗层改名后不再有该文案），其余键与插值占位符不变。
- 服务端：`tools/mobile-server/` 三个替换文件继续按上游固定摘要与唯一锚点核验，未改内容。
- 退役成员：`overlay-backdrop-fab` 的 3 个成员（`.d.ts` / `.d.ts.map` / `.ts`）进入
  `mobile-plugin-upstream.lock.json#retiredFiles`，`prepare-mobile-plugin.py --write` 删除旧产物。
- 纯上游 3.3.5 = **241** 个上游成员（`lib/**` + `src/**` + `locale/*.json` + `assets/icon.png` +
  4 个根文件）；部署产物 243 个 = 241 + `lib/file-transfer.js` 与其 `.map`。
  `locale/*.json` 与 `assets/icon.png` 已加入 `managed-runtime-inputs.json` 的安装表，
  宿主插件卡片可读取图标与本地化标题 / 描述。

### 验证

```bash
python3 tools/prepare-mobile-plugin.py --upstream-dir <fixed-3.3.5-tree> --check
python3 tools/prepare-mobile-plugin.py --upstream-dir <fixed-3.3.5-tree> --write
node tools/test-mobile-client-patches.mjs
node tools/test-mobile-update.mjs
node tools/test-mobile-gear-contract.mjs
node tools/test-mobile-locales.mjs
node tools/test-mobile-server-patches.mjs
node tools/test-mobile-server-streams.mjs
node tools/test-mobile-reasoning-defaults.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/verify-runtime-descriptor-source.py
```

## 2026-11-06：同步上游 3.3.1（宿主 0.2.2）与补丁取舍

### 上游事实（只读核对）

- 固定提交 `4e71eb67…`（v3.3.1，2026-11-06）；上游 README 的 3.4.0 更新内容写明「适配宿主
  0.2.2：修掉整页白屏、顶栏开关被裁、文件键消失、页签叠字、文件面板标签条被顶栏盖住」。
- peer 声明 `@deepseek-ai/dsh-client-* >=0.2.2-rc.1 <0.3.0-0`、`@deepseek-ai/cordis ^4.0.1`，
  与 `tools/dsh-runtime/package-lock.json`（0.2.2-rc.1 / cordis 4.1.0）逐条匹配；`react` 是
  可选 peer（3.4.0/3.3.0 曾是必选 peer，而宿主运行时安装树没有 react，会被依赖门槛判 missing）。
- 上游删除了 `lib/file-transfer.js`；DSHA 的 `tools/mobile-server/index.js` 仍 import 它，因此该模块
  改列为「仅本地追加成员」，字节仍由 `tools/file-transfer/file-transfer.js` 核验。

### 仍保留的 DSHA 差异（C 基线 = 纯上游 + 以下五类）

| 类别 | 具体差异 | 上游 3.3.1 是否覆盖 | 保留理由 | 回归 |
|---|---|---|---|---|
| 宿主集成 | `DSHA_GEAR_FIT_V1`：footer 齿轮按实测行宽让位（`dshaGearFits`，写 `data-mobile-nav-quick-fit`） | 否：上游仍是旧的 `:has(> button[data-phase], > [role="status"])` 存在性判据；DSH 0.2.2 首连不渲染指示灯时判错 | build324 白屏/侧栏重复事故的同类「依赖宿主节点存在」根因；本修复只看几何 | `tools/test-mobile-gear-contract.mjs`（6 项） |
| 宿主集成 | 安全区变量：`env(safe-area-inset-*)` → `var(--dsha-safe-area-inset-*, env(...))`（跳过上游已包好的一处，不嵌套） | 部分：仅 frame 顶部用了 DSHA 变量，其余约 20 处仍是 `env()` | Android WebView 不向网页报告系统栏 inset；由原生发布实际值 | `tools/test-mobile-client-patches.mjs`（安全区单层包装） |
| 安全 | `tools/mobile-server/{index,compress,delete-session}.js` 替换上游服务端入口 | 否：上游删除路由无 `connection.requestRejection`（仅同源校验）、压缩 patch 是进程级全局、删除生命周期无 AgentHandle 捕获 | 删除会话必须经宿主鉴权；压缩只作用于当前 webServer 端口；live-session 经公开句柄安全删除 | `tools/test-mobile-server-patches.mjs`、`test-mobile-auth/delete/streams`、`test-mobile-reasoning-defaults` |
| 用户可见 | 原生文件传输：`lib/file-transfer.js`（上游 3.3.1 已删该成员）+ 客户端 `FileDeliveryActions` / `installNativeFileActions` + `/api/dsha/files/{prepare,release}` | 否：上游只剩浏览器分享/下载 | DSHA 原生「保存到 / 分享 / 下载记录 / 图片保存」通道；32 MiB 分享限制与失败回退 | `tools/test-mobile-file-delivery.mjs`（浏览器）、`test-mobile-reasoning-defaults`（服务端闭包） |
| 用户可见 | 多语言/live-locale：`mobileNavLocales` 等 8 种附加语言（zh-Hant/ru/ja/ko/ar/pt/la/fr）与 drawer 按钮实时换语言 | 否：上游只注册 `zh`/`en` | DSHA 客户端支持 10 种语言；否则移动壳标签回退英文 | `tools/test-mobile-locales.mjs`、`test-mobile-locales-browser.mjs` |

### 已摘除的旧补丁（build336 / C）

- `DSHA_SESSION_INPUT_V4`（会话行触摸单击 / 抽屉关闭语义）：上游 3.3.1 自带 DSHA 两段式
  `data-dsha-session-select` + `dsha-session-open` 集成（synthetic `detail=0` 点击）；按
  「完全追随上游」删除本地改写，行为回到上游实现。若真机发现单击失效需另立任务。
- 手机快捷键弹层搜索行保留：上游按 ≤767px 隐藏搜索；按上游行为删除本地保留（手机端无搜索）。
- 快捷键弹层可见带 CSS（`--dsha-mobile-visible-vh` / `--dsha-mobile-viewport-top` 覆盖）：随
  搜索行一起删除；上游 `viewport-meta` 仍发布这些变量供其自身布局使用。
- `DSHA_FONT_FAMILY_ROW_V1`（设置字体族行换行）：上游不覆盖，但按上游优先策略删除；保留原
  记录见下方「2026-10-30」小节（已标注摘除）。
- `DSHA_BACKDROP_PRESS_V1`（遮罩按压不发亮）：上游 3.3.1 遮罩是带 `role="button"` 的 `div`，
  仍会命中通用 `:active` inset 染色；按上游优先策略删除，深色主题按压会恢复上游的亮闪。
  `tools/test-mobile-backdrop-press.mjs` 随之退役（build336）。
- 0.2.1 DOM 的通用弹层可见区 CSS、旧快捷键首次焦点守卫实现（`tools/mobile-shortcut-guard.js` /
  `tools/mobile-focus-shadow.js`）：不再进入补丁链；上游 0.2.2 自带同义守卫。这两个文件当前
  无引用，清理另行处理。
- `DSHA_WIDE_TOUCH_ROW_ACTIONS_V1`：上游 712999a（PR #257）已自带同一条名单（此前已摘除）。

### C 相对纯上游多出的范围（一句话）

纯上游 3.3.1 = 238 个上游文件；C 只多出：服务端 3 模块 + `lib/file-transfer.js`（DSHA 服务端闭包）、
安全区变量包装、`DSHA_GEAR_FIT_V1`（CSS + JS）、多语言/live-locale 字典与源码补丁、文件传输
客户端动作。共五处差异，全部有上方理由与回归；其余全部回到上游字节。

### 验证

```bash
python3 tools/prepare-mobile-plugin.py --upstream-dir <fixed-3.3.1-tree> --write
node tools/test-mobile-client-patches.mjs
node tools/test-mobile-update.mjs
node tools/test-mobile-gear-contract.mjs
node tools/test-mobile-locales.mjs
node tools/test-mobile-server-patches.mjs
node tools/test-mobile-server-streams.mjs
node tools/test-mobile-reasoning-defaults.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/verify-runtime-descriptor-source.py
```

## 2026-10-30：rc.5 设置弹层字体族行排版（`DSHA_FONT_FAMILY_ROW_V1`，build336 已摘除）

> **已摘除（build336，C 基线）**：该补丁按「完全追随上游」删除；下方为历史记录。
> 代价：手机设置弹层的字体族行仍是上游布局（长标题可能被压窄），属已接受的已知回归。

### 现象与根因

真机（Android WebView，DSH 0.2.1-rc.5 + dsh-web-mobile 3.2.2 手机 sheet）打开
「设置 → 通用设置 → 更多字体设置」后，「正文字体 / 代码字体 / 终端字体」每行右侧固定
240px 输入框，左侧标题+说明被压成约 1 字/行，设置弹层被纵向撑高。

宿主 `@deepseek-ai/dsh-client-ui-theme` 的 `FontFamilyRow` 结构（0.2.1-rc.5，已按
`app/build/locked-dsh-runtime` 的固定运行时核对）：

```html
<div class="bVCLcG_row">                          <!-- .row: flex, 不换行 -->
  <div class="bVCLcG_rowText">…标题 + 说明…</div>   <!-- flex:1, padding-right:48px -->
  <span class="_wrap_14sgc_1 vnGTiq_field">        <!-- .field: flex:0 240px -->
    <input spellcheck="false" autocomplete="off" maxlength="200">
  </span>
</div>
```

关键点：rc.5 的 primitives `Input` 把原生 `<input>` **包在 `<span class="_wrap…">` 里**，
`FontFamilyRow` 的 `className`（`vnGTiq_field`，即 240px 规则）落在这个 wrapper 上，
输入框本身不是行的直接子节点。手机 sheet 内容宽约 340px，扣掉 240px 控件与
`rowText` 的 48px 右内距后，文字列几乎没有可用宽度。

### 上游核验（2026-10-31）

- GitHub `mexiaosqwq/dsh-web-mobile` 的 `main` 已从 `04dcd19f…` 前进到
  [`bb0e5e151ca549fef506e829020a5d7138074711`](https://github.com/mexiaosqwq/dsh-web-mobile/tree/bb0e5e151ca549fef506e829020a5d7138074711)：
  9 个改动提交（另有 1 个合并提交）集中在全屏右侧面板层带 / 底色、宠物悬浮层遮罩与命名根节点、文件面板手势标记
  （#247 / #248 / #249）；`v3.2.2` 标签当前也指向该提交。0 个 open PR。
- 固定上游 `lib/client.js` 中 `bVCLcG` / `rowText` / `FontFamily` 命中数仍为 0，
  即插件没有针对该字体行的移动端规则；本次上游改动没有吸收本补丁。
- 上游 open issue #252、#231 是会话输入框（composer）与按钮间距问题，与本字体行无关。
- 本次同步按新上游字节重放全部本地补丁，client 的各处补丁锚点、file-transfer、locale 与
  server 补丁均保持唯一；`prepare-mobile-plugin.py --write` 重新生成产物并通过摘要核验。

结论：上游暂无此修复，DSHA 需要本地 CSS 补丁。

2026-11-06 复核（宿主升级到 0.2.2-rc.1、上游同步到 3.3.1 / `4e71eb67`）：

- 本机安装树 `@deepseek-ai/dsh-client-ui-theme` 0.2.2-rc.1 仍是 `FontFamilyRow` +
  `.row` / `.rowText` / `.field`，`Input` 仍把 `<input>` 包在 `span[class*="_wrap"]` 内，
  `maxLength: 200`、`spellCheck: false`、`autoComplete: "off"` 未变，补丁选择器继续命中；
- 固定上游 3.3.1 的 `lib/client.js` 仍无 `FontFamilyRow` / `rowText` / `maxlength=200` 选择器，
  本补丁继续保留。

### 补丁位置与规则

`tools/apply-mobile-client-patches.mjs` 注入的 CSS 模板新增
`@patch id: font-family-row-wrap-rc5` 与产物标记 `DSHA_FONT_FAMILY_ROW_V1`，只在
`@media (max-width: 1023px) and (pointer: coarse)` 下生效：

- 作用域：`[aria-modal="true"][data-shortcut-modal="settings"]`，只进设置弹层；
- 行判定：`:has(> input[spellcheck="false"][autocomplete="off"][maxlength="200"])`
  以及真实 rc.5 DOM 的 `:has(> span[class*="_wrap"] > input[…同上…])`；
- 命中后：行 `flex-wrap: wrap; row-gap: 8px`，`rowText` 与控件各占一行
  （`flex:1 1 100%`），文字列 `padding-right:0`。

选择器依据（均在固定 rc.5 运行时核对）：

- 扫描 `node_modules/@deepseek-ai/**` 全部 client bundle，`maxLength: 200` 只出现在
  `dsh-client-ui-theme` 的 `FontFamilyRow`；`spellcheck=false` + `autocomplete="off"`
  进一步限定到该输入框。
- 字号行（正文字号 / 代码字号 / 终端字号）是 stepper 结构，没有 `input`，不会命中；
- `:has` 保持单层。插件仓库有过「嵌套 `:has()` 整条被 Chromium 丢弃」的教训。

### 验证

```bash
node tools/test-mobile-client-patches.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/check-source-hygiene.py
```

`test-mobile-client-patches.mjs` 断言产物含 `DSHA_FONT_FAMILY_ROW_V1`、两种 DOM 形态的
选择器齐全、同一选择器只有单层 `:has`，且部署产物与 `applyMobileClientPatches()` 输出
逐字节一致。

另用 rc.5 固定前端（真实 React / `dsh-web-mobile` 产物 / 真实 theme client）在 Chromium
无头环境复现并量测（本地验证，不进仓库）：

- 设置弹层内 3 个字体行：`flex-wrap:wrap`，文字列 328px、控件 328px、无横向溢出；
  未打补丁的同一结构文字列 92px、控件 240px；
- 3 个字号行保持 `nowrap`、无 input；
- `data-shortcut-modal="shortcuts"` 的同类结构保持 `nowrap`；
- 视口 1280px 时设置弹层内字体行保持 `nowrap` 与 240px 控件。

### 真机验收（build214，2026-10-30）

在 OnePlus PJD110 / Android 16（1440×3168，4.0×，360dp 逻辑宽）安装 build214 正式包后实测
「设置 → 通用设置 → 更多字体设置」：

- 正文字体：说明 `[80,1928,1340,2072]` 整宽两行；输入框 `[112,2120,1308,2216]` 独占下一行；
- 代码字体：说明 `[80,2464,1340,2612]` 整宽；输入框 `[112,2656,1308,2756]` 独占下一行；
- 代码字号等 stepper 行保持原布局，未命中补丁；
- 「终端字体」行在可视区下方：WebView 内容无障碍滚动返回 `NODE_SCROLL_REGION_UNAVAILABLE`，
  主屏截图被系统悬浮窗标记遮挡、不签发坐标触控，未单独滚入目视；该行与已实测两行共用同一
  `DSHA_FONT_FAMILY_ROW_V1` 规则，无头三行实测覆盖。

完整记录见[build214 真机验收记录](releases/v0.2.1-rc.3-build214-acceptance-20261030.md)。

### 重新生成与退出条件

```bash
node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --write
```

退出条件：上游给字体行提供手机档换行排版，或宿主不再把 `.field` 固定成 240px；
届时删除 `font-family-row-wrap-rc5` 整块并重新生成产物。

## 2026-10-31：宽屏触屏档会话行收起归档/置顶（`DSHA_WIDE_TOUCH_ROW_ACTIONS_V1`，已摘除）

> **已摘除（2026-11-06，同步 3.3.1）**：固定上游已包含 712999a（PR #257）的同一条规则，
> DSHA 补丁脚本删除了整块；下面的现象、上游核验与验证记录只作为历史，不再对应当前产物。

### 现象与根因

内置 3.2.2 的 `@media (min-width: 1024px) and (pointer: coarse)` 档把会话行
`_rowActions` 常显，并把 ⋯ / 归档 / 置顶三颗按钮放大到 40×40（gap 4）；宿主只在
`:hover` / `menuOpen` 时隐藏 `.time`，触屏没有 hover，于是 280px 侧栏里同时常显
「时间 + ⋯ + 归档 + 置顶」，标题可用宽只剩约 55px（实测 1024×640 / dpr 2.5）。
手机壳（≤1023）自 #154 起已按 aria-label 收起归档/置顶，宽屏触屏档缺同一条规则。

### 上游核验（2026-10-31）

- issue [#255](https://github.com/mexiaosqwq/dsh-web-mobile/issues/255)；
  PR [#256](https://github.com/mexiaosqwq/dsh-web-mobile/pull/256)（OPEN，SunsetRNE，
  分支 `fix/255-wide-touch-row-actions`，含源码、测试、重建的 `lib/`）。
- 固定上游 `bb0e5e15` 仍无该规则；`layout.css.ts:3469` 只作用于手机壳 frame。
- 上游维护窗口暂停，DSHA 先打本地补丁；PR 合并后摘除。

### 补丁位置与规则

[apply-mobile-client-patches.mjs](../tools/apply-mobile-client-patches.mjs) 在宽屏触屏档
`①′` 热区规则之后、`① 隐藏「更多视图」` 之前做锚点替换，注入带
`@patch id: wide-touch-row-actions` 与产物标记 `DSHA_WIDE_TOUCH_ROW_ACTIONS_V1` 的规则：

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

只命中 ≥1024 + 粗指针的会话行；⋯ 仍保留 40×40 热区，归档/置顶/分叉/删除仍在 ⋯ 菜单。

### 验证

```bash
node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --check
node tools/test-mobile-client-patches.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/check-source-hygiene.py
```

`test-mobile-client-patches.mjs` 断言：规则位于 ≥1024 档内、名单与手机壳逐字一致
（8 个 zh/en 标签）、`display:none !important`。真机前后量测等包含本补丁的 APK 安装后
补写，指标见[平板会话行挤压记录](web-mobile-tablet-rows-20261031.md)。

### 退出条件

内置上游同步到包含 PR #256 的提交后：删除 `wide-touch-row-actions` 整块、对应测试分支与
本文件该段，重新生成 `lib/client.js` 并核验。

## 2026-11-05：侧栏 footer 齿轮让位改实测宽度（`DSHA_GEAR_FIT_V1`）

### 现象与根因

build324 真机覆盖安装后主界面白屏、侧栏 footer 出现「重复叠加」观感；禁用内置
`dsh-web-mobile` 后两个现象都消失。补丁链侧取证（[build324 白屏排查](releases/v0.2.1-rc.5-build324-blank-screen-20261105.md)）
确认 323→324 的运行树只差两条补丁，而手机上唯一可见差异是：手机是 drawer 模式
（`viewport < 768`），`wide` 因此为真，宿主 `ConnectionIndicator` 本来真实渲染；T58
（`first-connect-indicator-patch.json`）让首次连接不再渲染指示灯。

上游（3.2.2 起，3.3.1 仍保留）`dsh-web-mobile` 的 `drawer-quick-settings-chip-guard` 恰好用
`:has(> button[data-phase], > [role="status"])` 判断「这一行里有没有指示灯」来决定是否
让出 footer 齿轮（该行只有 260px，连接条中文约 136px、英文约 190px）。指示灯消失后
判据永远得到「放得下」：插件的渲染因此**依赖宿主某个子节点存在**，宿主一改首次连接的
渲染方式，让位口径就失准。

### 补丁位置与规则

[apply-mobile-client-patches.mjs](../tools/apply-mobile-client-patches.mjs) 用四处锚点替换：

- 守卫规则改为只认插件自己的属性，不再 `:has()` 宿主节点：
  `[data-mobile-nav="quick-settings-wrap"][data-mobile-nav-quick-fit="0"] { display: none !important; }`
- 新增纯判据 `dshaGearFits(rowWidth, usedWidth)`（导出给宿主测试）：行内容宽度 − 其它
  可见孩子宽度 ≥ `GEAR_SLOT_PX`（40 = 按钮 30 + 右外边距 6 + 4 余量）才放得下；行宽
  不可用（≤ 0 / 非有限数）按「放不下」处理，保守保留账户菜单里的设置项。
- `effects/drawer-quick-settings` 的 `gearAvailable()` 改为实测行宽与其它可见孩子的
  宽度和（含外边距，跳过 `display:none` / `visibility:hidden`），结论幂等写入
  `data-mobile-nav-quick-fit`；`syncMenuEntry` 用同一个结论决定是否收起账户菜单设置项，
  保证任何时刻只有一条设置入口。

产物标记 `DSHA_GEAR_FIT_V1`，位于 `lib/client.js` 的
`effects/drawer-quick-settings.js` 模块与 CSS；上游文件本身不改。

### 验证

```bash
node tools/apply-mobile-client-patches.mjs tools/mobile-plugin-upstream/lib/client.js --check
node tools/test-mobile-client-patches.mjs
node --test tools/test-mobile-gear-contract.mjs
python3 tools/generate-builtin-plugins.py --verify
python3 tools/check-source-hygiene.py
```

`test-mobile-gear-contract.mjs` 用最小 DOM 语义跑真实产物：**没有指示灯时**齿轮正常注入并
标记放得下；指示灯出现后按实测宽度让位、消失后恢复；行宽不足时保守让位；重复 `ensure`
不重复注入。真机验收（build325 覆盖安装）由主会话装测试包后按实际结果补写。

### 退出条件

宿主 `settings.launcher` 槽位提供独立的次要动作位（或连接条不再占用该行宽度）后，删除
`drawer-quick-settings-fit-guard` 整块与 `DSHA_GEAR_FIT_V1` 判据，重新生成 `lib/client.js`
并核验；届时本段一并删除。
