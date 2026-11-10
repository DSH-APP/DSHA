# src/client/ · 浏览器半区背景

改动 `src/client/` 下任意文件时本文件会自动进入上下文；改宿主半区（`src/` 根下那几个 `.ts`）时不出现。
这里放**客户端半区的实现契约与易踩点**；`src/client/effects/` 下每个模块的背景另见
`src/client/effects/AGENTS.md`（改该目录文件时与本文件一起进入上下文）。

来源：原根 `AGENTS.md` 的「Architecture / Conventions」两节（从根 AGENTS.md 下沉，原文未改写）。

## 入口与两个 slot

`src/client/index.tsx` injects `['slots', 'layout', 'locale', 'sessionLogDownload', 'sessions']`。
`inject` 是硬依赖，名单只放 entry 真读的服务（`workspaces` 已在 #86 移除）。`apply()` 注册 locale 字典、
注入一个 `<style data-plugin>` 标签、装效果、注册两个 slot：

- `conversation.session.header.actions` → `MobileNavToggle`（`order: 10`）：抽屉开关 + Files 按钮。
- `conversation.input.left` → `ComposerFileButton`（`id: mobile-nav-file-upload`，`order: 10`）：常驻的
  composer 文件入口。宿主 0.1.6 删掉了回形针附件按钮，「文件」行只剩 "+" 列表里那一条；本控件坐在
  加号旁的 tools 车道。它的 tap 弹插件的两选项小浮层（`effects/composer-file-picker.ts`：
  上传图片 / 上传附件），把选择交回宿主自己的隐藏 `input[type=file]`——intake 校验、上传与可用性都
  仍归宿主。会话作用域——hero/空白相位仍以 "+" 菜单为唯一文件入口。
- 会话日志导出**不在 slot 里**：宿主没有顶部动作槽位（`sidebar.brand.mark` / `.name` /
  `toggle.badge` 都是 single 且被品牌占用），所以 `effects/drawer-session-log.ts` 往抽屉品牌行
  （`[class*="sidebarCol"] [class*="logoRow"]`）注入一颗 `data-mobile-nav="session-log"` 的图标按钮
  （2026-10-18 issue #225 起，见 `docs/specs/2026-10-18-drawer-session-log-icon.md`）。原来的
  `sidebar.footer.action` 胶囊已删除——不要再把整行控件挂回抽屉页脚。
- 一键设置齿轮**也不在 slot 里**：`effects/drawer-quick-settings.ts` 往抽屉页脚账户行注入
  `data-mobile-nav="quick-settings"`，点击复刻「账户 launcher → 菜单第一项」把设置一步打开，
  同时给账户菜单节点打标记、由 CSS 收起菜单里那份设置项（2026-10-20 建、2026-10-21 改，见
  `docs/specs/2026-10-20-drawer-quick-settings.md`）。宿主没有 settings slot；
  触感反馈特性已移除。

## 全树协调器（reconciler）

- `core/reconciler-core.ts` 是**零 import** 的 DOM-free 引擎：任务注册表、脏键路由（`scopes`）、
  合并到 rAF 的刷新调度、逐任务错误隔离。
- `effects/reconciler-adapter.ts` 是薄浏览器适配器：`document.documentElement` 上一个
  `MutationObserver` 把记录映射成脏键（`attributeName`，或树变更的 `'*'`）喂给 `core.note()`，并用
  `installMobileEffect` 驱动激活/停用。（`phone-chrome.ts` 曾承担此职，P1 拆分后搬走。）
- 任务只在移动断点激活时跑，每动画帧合并一次。`stats-line` 保持 `scopes: ['*', 'characterData']`——
  树键因为它的槽随树重建，文本键因为 TPS 更新可能以原地 `nodeValue` 改写到达。**加键安全；删掉树键
  正是这行要防的失效模式**。
- 已注册任务：`frame-marker`、`header-metrics`、`preview-fullscreen-toggle`、`preview-close-sync`、
  `sheet-rise-replay`、`file-viewer-open-marker`、`stats-line`、`drawer-layer`、`panel-back-exit`、
  `drawer-session-log`、`image-lightbox-zoom`、`question-fullscreen`（后两个由各自效果在移动档自注册）。

## 分层与依赖

`platform/` 是每个 effect 共享的浏览器基础设施原语——`frame.ts`（`getFrame`/`findFrame` + 记忆化标记
查找）、`breakpoints.ts`（`MOBILE_QUERY`/`DESKTOP_QUERY`/`TOUCH_QUERY`、`installMobileEffect`）、
`device.ts`（`detectIosWebKit`）。它无特性语义、不拥有 DOM 标记。

**依赖方向 `index.tsx → effects → platform → core`**；`components/` 可用 `platform/` + `core/` 但**绝不**
用 `effects/`，`effects/` 不为基础设施 import 另一个 effect——`tests/architecture.test.ts` 逐条强制
（运行时环、层方向、`core/` DOM 纯净、行预算、模块级状态、entry inline 查询），台账只许缩不许涨；
`test:core` 会跑它，所以新增环或新增超行文件是**红 CI**，不是 review 意见。

## 样式

`styles/index.ts` 按 `base → layout → compat → misc` 顺序拼接并注入一个 `<style data-plugin>` 标签。
移动规则目标查询 `(max-width: 1023px) and (pointer: coarse)`（每个顶层媒体块都要与 `MOBILE_QUERY`
同步）；misc.css.ts 的桌面隐藏块是它的精确补集，必须保持未安装形态。

CSS 写在 `styles/`，不写进组件文件；保留拼接顺序与完整 CSS 注释/分段边界。**该顺序是行为契约，不是
排版偏好**：aionui 探索器/预览两列的平板档「居中不铺满」压在 compat 的铺满规则之上（两者同 (0,1,0)
且都 `!important`，只靠 misc 在后分胜负——把顺序翻过来就退回铺满，用 `order-flip.mjs`
复现）。

## 选择器与 DOM 标记

先用稳定的 `data-*` 标记与结构选择器，再考虑哈希类。躲不开的哈希类一律**子串匹配**（`[class*=_frag]`），
**绝不用属性后缀**（`[class$=…]`）——class 属性常带额外 token 或尾空格，而后缀测试跑在整条属性值上，
会静默漏匹配（全库迁移时实测）。选择器要限定到它自己的区域，前缀重叠的片段用 `:not` 防误伤；树行用
`[class*="_treeRow"]`，区分目录/文件时排除 `[class*="_treeArrowEmpty"]`。

DOM 标记即跨模块状态契约：`data-mobile-nav="frame"`、`data-mobile-nav="hit"`（常驻透明命中层：
点空白关闭 + 弹层 z 门控，`[data-active]` 是门控信号）、`data-sidebar-collapsed`、
`data-aionui-explorer-open`、`data-aionui-preview-open`、`data-mobile-preview-full`、
`data-mobile-nav="stats"`、`data-mobile-nav="stats-ring"` + overlay 体系五标记（`stats-ring-reserve` /
`stats-tps-reserve` / `stats-tps` / `stats-ring-dock` / `stats-tps-row`，#104）、`data-file-viewer-open`
（dsh-file-viewer 兼容布局的 frame 级门，锚 `.dsfv-panel`）、`data-mobile-nav="session-delete"`
（菜单项探针键）、`delete-dialog-backdrop` + `delete-dialog`（确认弹层）、`data-mobile-nav-dim`
（frame 级暗层开关，`1`/`0`，画 frame 的 `::after`）、`data-mobile-nav-ios`
（挂 `<html>`，iOS 专属 CSS 门）。

**读标记要让静态检查看得见**：一律用选择器字符串 `querySelector('[data-mobile-nav="x"]')` 或
`dataset.mobileNavXxx` 比较，**不要写** `getAttribute('data-mobile-nav') === 'x'`——属性名与值分开写时
没有任何扫描能认出这个读点（`scripts/css-structure-check.mjs` 的「CSS 消费的标记必须有产出」检查也看不见），
日后改名会静默失配。新增标记必须同时有消费者（CSS 选择器或代码里的 `querySelector`）；**只有生产者的标记是
死重量，别留**（2026-11-08 审计实测：报出来的「零引用」标记里真实死重量只有 1 个，其余全是扫描没认出读法）。

## 效果与资源纪律

- 每个长生命周期样式标签、监听器、计时器或 `MutationObserver` 都放进
  `ctx.effect(() => { ...; return disposer }, label)`；宽度敏感的效果用 `installMobileEffect` 在
  `matchMedia(MOBILE_QUERY)` 变化时重武装，保证宽→窄切换有效；查询串从 `platform/breakpoints.ts`
  取常量，别硬编码。
- 往第三方 React 拥有的 DOM 里注入节点时用幂等 `ensure()`/reparent 逻辑；dispose 时清理搬走的节点、
  observer、属性与监听器。
- DSH 服务一律走声明的 fiber `inject` 名单与 slot `inject` props 获取；本地镜像用 React state，
  跨效果状态用 `data-*` 标记。
- 客户端运行时效果目前都是同步 DOM 工作，除非新契约要求异步，就照这个模式写。诊断失败用 debug 徽章
  捕获的 `error`/`unhandledrejection` 输出，别吞异常。

## 代码风格

- TypeScript：单引号、不加分号、导出显式返回类型、installer 命名 `install<Domain>`。
- 客户端内相对 import 必须带 `.ts`/`.tsx` 扩展名；`tsconfig.client.json` 为 CommonJS 产物重写它们。
  DSH 模块增强与 SlotMap/Context 类型用 type-only import。
- **`effects/` 的 `../` import 能用（A/B 实测，旧禁令已证伪）**：跨目录相对 import 正常解析，
  bundle 里落成正确的 `require("./x.js")`。新代码不必为旧禁令绕路加镜像常量——能 import 就直接 import。
  **仍要守的**：`reconciler-core.ts` 保持零 import；task 模块拿 `ReconcilerTask` 类型走 `import type`
  （编译期擦除、不进 bundle）。
- locale：先加 `zh`，再在带类型的 `en` 里镜像同名键；`MobileNavKey` 由 `zh` 推导。**命名空间 id 一律带
  包前缀**（`dshWebMobileNav`、`dshWebMobileNav.attachments`、`dshWebMobileNavFileShare`）：宿主的
  `locale.register` 遇到重复命名空间会**抛错**，而该抛错会让整个 entry 失败、Web 起不来——下游 fork
  还会整包抄我们的源码（dsh-pocket 就抄了，#165）。新命名空间一律以 `dshWebMobileNav` 开头；
  `tests/locale-namespace.test.ts` 钉住这一族。

## 移动端行为与弹层优先级

保持移动端专属行为与弹层优先级：捕获阶段的抽屉处理器必须让位给 `[aria-modal="true"]` 弹层，并忽略会话行
操作按钮。打开的抽屉要求 `transform: none`（而不是等价的 `translateX(0)`），否则 fixed 后代拿不到正确的
包含块。
