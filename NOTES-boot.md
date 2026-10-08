# 冷启动：进了 web 界面但功能还没热 —— 排查笔记

> worktree `/root/Documents/deepseek-harness/default-workspace/dsha-boot`，分支 `perf/client-boot`（基于 `70e37a7`）。
> 测量环境：用户正在运行的 `dsh web`（`127.0.0.1:3080`，`--no-open`，进程 10:23 起）+ 本机安装树
> `/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/*`。
> 本机**没有任何浏览器**（chromium 全系不存在），所以「WebView 里 parse/execute 的真实毫秒」**量不出来**；
> 下面所有数字都来自**真实 HTTP 响应**与**真实 bundle 字节**，不是估算。

## 0. 结论速览

| 段 | 事实 | 证据 |
|---|---|---|
| 宿主侧 | 插件全部加载完**之后**才打印 URL；进程 → 鉴权就绪 ~3.7s | `/root/dsh-web.log` 51 行，URL 在第 49 行，其前 48 条启动事件 |
| 传输 | 进页面后要拉的客户端 JS：**1 条 bootstrap 组合(13 KB gz) + 2 条 application 组合(5.58 MB + 202 KB gz) + 外壳 4 个资产(475 KB gz)** | 见 §2 |
| **迟滞主体** | 主 application 组合单条 **11,151,862 B 原始 / 5,577,991 B gzip**，其中 **`@deepseek-ai/dsh-client-ui-settings-account` 一个插件占 5,279,960 B 原始 / 3,854,635 B gzip = 69%**，而这 5.28 MB 里 **~5.15 MB 是 8 张内联 base64 PNG 引导页截图** | 见 §2.3 |
| 执行 | 这 12 MB JS 要**先下载、再 parse、再逐个 `apply()`** 67 个插件；`apply` 是串行的，界面先渲染、功能后亮 —— 这就是用户说的"依次启动" | `dsh-client-modules/lib/client.js` + boot graph |

**一句话**：宿主没迟到；迟到的是一坨 **12 MB（gzip 5.58 MB）里 69% 是一张插件的内联截图**的客户端 JS，在手机上边下边 parse 边逐个点亮。

## 1. 客户端启动链路（实测）

```
GET /?token=***        303 换 cookie（no-store，一次性）
GET /                  200  157,659 B 原始 / 56,658 B gzip   ← 无 cache-control（每次冷启动都重下）
  ├─ 内联 <script>  __ModuleLoader__ 队列外壳
  ├─ <link rel=preload as=script> ×2   ← 两条 application 组合（并行预取，不执行）
  ├─ <script src="plugins/??@deepseek-ai/dsh-client-modules/client.js&rev=...">  ← 唯一阻塞 bootstrap
  ├─ <script> globalThis["__DSH_BOOT__"] = {rev, entries[67], batches[3]}   ← 14 KB 级 boot graph
  ├─ DSHA 注入块（core-js es-compat + compat.js + startup.js，内联）
  └─ <script type="module" src="./assets/index-5SrrfWpU.js?dsha-tooltip=rc2-1">  ← Vite 外壳
然后由外壳创建 Loader：
  require.async(每行) → document.createElement('script') → 命中 preload 的组合 URL（不重复下载）
  → 每个 bundle 只注册工厂（懒），materialize 时才跑模块体 → 67 个插件依次 apply()
```

关键实现（`dsh-client-modules@0.2.0-rc.2`）：

- `bootInjections()`（`lib/index.js:452`）按 `queue → application preloads → bootstrap blocking scripts → __DSH_BOOT__` 注入。
  application 组合是 **`script-preload`（`<link rel=preload as=script>`）**，bootstrap 是阻塞 `<script>`。
- 组合按 **行 revision 顺序** 拼接，单个 URL 上限 `MAX_COMBO_URL_BYTES = 3*1024`；本项目因此分成 2 条 application 组合（58 行 + 8 行）。
- 组合/分片响应头是 **`Cache-Control: public, max-age=31536000, immutable`**（`IMMUTABLE_CACHE`，`lib/index.js:160`），并按 `mtimeMs/ctimeMs/size` 派生 revision，**宿主重启后不变** ⇒ WebView 缓存跨次冷启动可命中。
- 并行性：preload 阶段 2 条组合 + bootstrap 并行发出（HTTP/1.1 同源上限 6，够用）；脚本执行按 Loader 行序，`pendingArrival` 让同一 URL 只发一次（`lib/client.js:514`）。
- `executedBundleUrls` 保证一条组合脚本只执行一次；组合失败才退到单资源 URL（每行最多 3 个请求）。
- **没有任何"非首屏延迟"机制**：67 行里 58 行都在启动期随外壳一起 materialize。

## 2. 实测体积与请求数

### 2.1 真实响应（`curl` 到用户正在跑的 3080，带 cookie）

| 资源 | 原始 | gzip | cache-control |
|---|---|---|---|
| `plugins/??@deepseek-ai/dsh-client-modules/client.js&rev=94c4fd0e35fe`（bootstrap） | 40,330 | 13,071 | `public, max-age=31536000, immutable` |
| **application 组合 #1（58 行）** | **11,151,862** | **5,577,991** | `... immutable` |
| application 组合 #2（8 行） | 961,672 | 201,642 | `... immutable` |
| `assets/vendor-CCJJTK99.js` | 740,575 | 209,631 | **无** |
| `assets/vendor-BNsW4eBh.css` | 29,288 | 8,668 | **无** |
| `assets/index-BPHePDI_.css` | 76,338 | 18,607 | **无** |
| `assets/index-5SrrfWpU.js?dsha-tooltip=rc2-1` | 634,098 | 237,708 | **无** |
| `GET /`（渲染后的 index.html） | 157,659 | 56,658 | **无**（且每次重新渲染注入行，本就不该缓） |

- 组合响应头**没有** `content-encoding`（curl 不带 `Accept-Encoding` 时）；带 `Accept-Encoding: gzip` 时宿主返回 gzip（`compression` 中间件，`dsh-host-webserver`）。
- **外壳 4 个资产合计 1,480,299 B 原始 / 474,614 B gzip，且宿主一个缓存指令都不发** ⇒ 每次冷启动都要重下 1.41 MB、宿主每次要现压 1.41 MB。
  实测（本机 aarch64，node zlib level 1）**每次启动 25.06 ms** 纯 gzip CPU，见 §4 第 1 项。

### 2.2 逐插件归属（67 行，各自单独 GET；B = 原始 / gzip）

```
5,279,960 / 3,854,635  @deepseek-ai/dsh-client-ui-settings-account   ← 69% of gzip 总量
  707,673 /   259,096  dsh-web-mobile
  718,077 /   217,715  @deepseek-ai/dsh-client-ui-conversation
  565,414 /   173,204  @deepseek-ai/dsh-client-ui-chat
  421,708 /   107,038  @deepseek-ai/dsh-client-ui-trajectory
  331,154 /    89,047  @deepseek-ai/dsh-client-ui-sidebar-right
  353,197 /    72,467  @deepseek-ai/dsh-cordis-client-runner
  512,769 /    70,715  @deepseek-ai/dsh-api-remotes
  ... （其余 59 行合计原始 3.4 MB / gzip 0.66 MB）
合计 67 行： 原始 12.15 MB / gzip 5.87 MB（分文件测；与组合实测 5.58 MB 的差来自跨文件压缩边界）
```

### 2.3 `dsh-client-ui-settings-account` 的 5.28 MB 是什么

```
$ wc -l .../dsh-client-ui-settings-account/lib/client.js    → 4531 行
$ awk '{print length, NR}' 该文件 | sort -rn | head
  793744 3434   onboarding_welcome_default        = "data:image/png;base64,..."
  785037 3437   onboarding_welcome_dark_default
  751831 3440   onboarding_welcome_zh_default
  746280 3443   onboarding_welcome_zh_dark_default
  569581 3500   onboarding_recharge_zh_dark_default
  510714 3494   onboarding_recharge_dark_default
  461301 3491   onboarding_recharge_default
  454352 3497   onboarding_recharge_zh_default
  ------ 合计 5,072,840 B 单行字符串 = 全文件的 96%
```

即：**8 张引导/充值页 PNG 以 base64 直接内联进客户端 bundle**，base64 熵高所以 gzip 只从 5.28 MB 压到 3.85 MB（73%）。
这 8 张图**只有进"账户/引导"设置页才用得到**，却 100% 计入启动期必下的那条组合。

## 3. 迟滞到底在哪一段（判断 + 证据）

1. 宿主：`/root/dsh-web.log` 第 49 行才打印 URL，之前 48 条启动事件 ⇒ **放你进去时后端已就绪**（另有 ~3.7s 的进程→鉴权）。
2. 传输：进页面后要下的组合合计 5.79 MB gzip，其中 **3.85 MB 是那一个插件的截图**；循环回环带宽再快也要把这 12 MB 解进 WebView 内存。
3. 执行：`dsh-client-modules` 的懒 CJS 模型保证"跑 bundle 只注册工厂"，但壳创建 Loader 时会 `require.async` **全部 67 行**，工厂逐个 materialize → 每个插件 `apply()` 注册侧边栏/输入区/设置页插槽。**这些 apply 是串行的**，界面外壳先画出来、功能后亮 —— 与用户原话"虽然进去，但是真正使用还要过一会儿才行"完全对齐。
4. `dsh-web-mobile`（707 KB，启动期就注入 `<style data-plugin>` + 装一个全文 MutationObserver 协调器）**不是主因**：
   - `installReconciler()`（`lib/client.js:3049`）已经把 mutation 批次 rAF 合并，并且 `record.target.closest('[data-chat-flow]')` 跳过聊天流子树；
   - 样式注入是 `document.head.appendChild(style)` + 一次 `setTimeout(...,0)` 重挂（为了让覆盖规则压住宿主 CSS），量级是毫秒。
   - 结论：它加重启动，但在 5.28 MB 面前是零头。**顺带说明**：DSHA 自己的 `web-integration/startup.js` 反而装了一个 `{childList,subtree,characterData}` 的全文 observer，每次 mutation 批次都做 `querySelector('[data-dsh-boot]')` 全场扫描 —— 见 §4 第 3 项。

## 4. 加速项清单（收益 / 风险 / 是否已实现）

| # | 项 | 收益（估算依据） | 风险 | 状态 |
|---|---|---|---|---|
| 1 | **外壳静态资产加缓存语义**：`dsh-host-frontend-static` 对 dist 资产发 `ETag(+no-cache)`，index 保持 `no-store`，支持 `If-None-Match → 304` | 实测本机：资产 1.41 MB 原始 / 475 KB gzip **不再重下**；宿主每次启动少压 **25.06 ms**（实测 zlib level1）；WebView 少一次 475 KB gunzip。端到端估 **0.05–0.15 s/次冷启动**（宿主机时 + 回环传输 + gunzip），并少 1.41 MB 闪存读 | 低。**刻意不用 `immutable`**：DSHA 会在 APK 更新时原地改写 `dsh-web-frontend/dist/assets/index-*.js`（`tooltip-interaction-patch.json`），immutable 会吃到旧外壳。ETag 由 size+mtime 派生，改写即换标签 | **已实现**（§5） |
| 2 | **`startup.js`：启动进度条 + 合并全场扫描 + 每条事件带 `at` 时间戳** | 进度条不加速，只把"半可用界面"变成有预期的等待；扫描合并把启动期全场 `querySelector` 次数降一个数量级（每宏任务 1 次而不是每 mutation 批次 1 次）；`at` 让**手机侧真实时间线第一次可量** | 低（全部包在 try/catch，失败即静默；不挡点按 `pointer-events:none`；30s 硬上限） | **已实现**（§5） |
| 3 | **拿掉 `dsh-client-ui-settings-account` 的启动期负载**（最高收益） | 组合从 5.58 MB gzip → **1.72 MB gzip（-69%）**；原始 12.15 MB → 6.87 MB。手机 parse+execute 时间按字节近似等比下降 | **中**。正解是上游把 8 张图改成按需 `import()` 分片（`dsh-client-modules` 已支持 `require.async('./client.<name>.js')` 兄弟分片、宿主按需 serve），但上游源码不在本仓；本仓可做的只是"手机侧不启用这个客户端插件"，会丢"账户/引导"设置页 | **未实现**（见下） |
| 4 | 组合分片更细 / 首屏必需插件提前 | 收益有限：请求数已经只有 2 条（URL 上限 3 KiB 决定的），并行度已够 | 中：改的是上游组合算法，宿主侧 patch 会脆弱 | 不建议 |
| 5 | 应用侧预热（提前建 WebView / 预解析端口 / 预取 shell） | 可省外壳那 ~0.5–1 s 的进程创建与首字节 | 中高：`WebPreviewActivity` 的 cookie 必须比 load 先到（`cookies.setCookie` 回调后才 `continueInitialNavigation`），预热会碰到鉴权时序；本机无法验证 | 未做，列为真机实验 |
| 6 | WebView 缓存确认 | 已确认：`WebPreviewActivity` **没有** `setCacheMode`（默认 `LOAD_DEFAULT`）、没有 `clearCache`、没有 `deleteAllData`（只有 `FactoryReset` 才清），cookie 持久 ⇒ `immutable` 的组合**跨次冷启动命中**是成立的 | — | 已查证，无需改动 |
| 7 | 把 `ready` 定义改严（等插件静默才算就绪） | 会让"网页已就绪"更诚实 | **高**：`ready` 现在驱动 `StartupDiagnostics.browserReady`，而它决定插件故障算 STARTUP_ERROR 还是 RUNTIME_ERROR、以及要不要跳 `StartupRecoveryActivity`。改严 = 把慢插件误判成启动失败。**不要动** | 明确不做 |

### 关于第 3 项的可执行建议（可逆、一行）

在 profile 的补丁层（`/root/.dsh/profiles/web/cordis.patch.yml`）加：

```yaml
- id: ui-settings-account
  disabled: true
```

改完必须重启 `dsh web`（本项目 `patchReload: startup`）。回滚就是删掉这两行。
**没有做**是因为它会让手机侧少掉"账户/引导"设置页，属于产品取舍，不能替用户拍板；而且本机无法真机验证它不会让设置导航留一个死条目。

## 5. UI 门控方案的取舍（回答任务第 3 问）

- 门控**不会让启动变快**，只是把"半可用界面"换成"明确的正在启动"。用户原话的痛点是"进去≠能用"，所以诚实的进度比假装可用有价值。
- 但**硬门控（就绪前不放开交互）风险大于收益**：
  - `ready` 目前在"composer 出现"时就报，而真正可用还要 1–3 s；要门控就得引入"插件静默"判据；
  - 判据一旦失灵（某插件永不返回），用户会被**锁在加载页**里 —— 这是比慢更糟的故障；
  - 而这段窗口里用户本来能做的只有"看"，挡不挡差别有限。
- 因此本次实现的是**不挡操作的顶部进度条**（`pointer-events:none`，静默 800 ms 后自收，30 s 硬上限），而不是遮罩式门控。真要做门控，应当配"超过 N 秒自动放开 + 保留取消按钮"。

## 6. 必须真机验证的清单（可复走）

1. **进度条**：打开网页预览 → 顶部出现"正在启动网页功能 n/67" → 功能点亮过程中 n 递增 → 稳定后自动消失；全程可滚动、可点侧边栏（进度条不拦截）。
2. **进度条不会永久残留**：造一个 apply 挂起的插件（或断网让某 bundle 失败），确认 30 s 内自行收起。
3. **客户端时间线（新的可量化口径）**：`[DSHA_PAGE]` 事件现在带 `at`（`performance.now()` 毫秒）。在同一台手机上复走：
   ```bash
   # 网页侧事件现在带 at；用 App 的诊断/启动记录读出 at(ready) - at(第一条 loading)
   # 或者用 Chrome 远程调试（本机没有，需另接 PC）看 Network 的 3 条 /plugins 响应与 Parsing 时间
   ```
   期望：`at(ready)` ≈ 外壳可用时刻；`at(最后一条 active)` ≈ 功能全亮时刻；两者之差就是这个 issue 要压的量。
4. **外壳缓存**：装上新构建后连续冷启动两次，第二次 dist 资产应是 `304`（或直接命中缓存），`/` 仍为 `200`。可复走量法（在手机 root 里）：
   ```bash
   curl -s -D - -o /dev/null -H 'If-None-Match: <上一步响应头里的 etag>' \
     http://127.0.0.1:3080/assets/index-5SrrfWpU.js?dsha-tooltip=rc2-1
   # 期望 HTTP/1.1 304，且 bytes=0
   ```
5. **APK 升级后不得吃旧外壳**：用新构建覆盖安装，确认 `assets/index-*.js` 的 ETag 变了、网页是新版（第 1 项的"改写即换标签"）。
6. **`dsh-client-ui-settings-account` 复测**（若采纳建议）：重启后
   ```bash
   curl -s -o /dev/null -w '%{size_download}\n' -H 'Accept-Encoding: gzip' http://127.0.0.1:3080/plugins/\?\?...
   ```
   主组合应从 5.58 MB gzip 掉到 ~1.7 MB gzip；同时确认设置页仍能打开、导航没有死条目。

## 7. 拿不到的测量（如实说明）

- **WebView 内的 parse/compile/execute 毫秒**：本机无任何浏览器，无法离线复现；只能靠 §6.3 的 `at` 时间线在真机上量。
- **WebView HTTP 缓存是否真的跨次冷启动命中**：只能从代码侧确认未被禁用（§4 第 6 项）+ 响应头正确；真机确认要复走 §6.4。
- **`app/src/main/assets/web-integration/startup.js` 改动后的真机行为**：本机无浏览器，用 `tools/test-startup-boot-progress.mjs` 的假 DOM 夹具覆盖逻辑分支，真机外观仍需 §6.1 验收。

---

## 8. 本次已实现（分支 `perf/client-boot` 的单个提交，未 push / 未开 PR）

只 `git add` 了这 10 个文件：

```
app/src/main/assets/frontend-static-cache-patch.json   (新) 8 条精确补丁 → dsh-host-frontend-static
app/src/main/assets/runtime-patches.json               (改) 登记为 active，markers=DSHA_STATIC_CACHE_V1
app/src/main/assets/web-integration/startup.js         (改) 进度条 + 扫描合并 + at 时间戳
app/src/main/assets/managed-runtime-inputs.json        (改) assetFiles 增补
app/src/main/assets/runtime-descriptor.json            (改) 3 个哈希 + 新增资产 + runtimeId
tools/asset-deployment.json                            (改) packagedFiles 增补
tools/host-tests.manifest.json                         (改) 登记两个新测试
tools/test-frontend-static-cache.mjs                   (新) 对真实模块跑生产配方
tools/test-startup-boot-progress.mjs                   (新) 假 DOM/假定时器跑真实 startup.js
NOTES-boot.md                                          (新) 本文件
```

### 已跑成的验证（都是真跑，不是推理）

| 验证 | 命令 | 结果 |
|---|---|---|
| 缓存补丁语义 | `node tools/test-frontend-static-cache.mjs /usr/local/lib/node_modules/@deepseek-ai/dsh` | **ok**：对真实 `dsh-host-frontend-static` 源码应用生产配方，断言未打补丁时无任何缓存头、index=no-store、资产=no-cache+ETag、候选列表命中 304 无响应体、未命中/无头/六参数旧签名仍 200、改写后换标签、403/404 不变 |
| 缓存补丁端到端 | `/tmp/boot/harness.mjs`（真实 HTTP:3199，加载真模块） | `index → cache-control: no-store`；`assets/vendor-CCJJTK99.js → 200 / 740,575 B / no-cache / W/"b4cdf-1a11aeeab94"`；带 `If-None-Match` → **304 / 0 B**；original（未打补丁）无任何缓存头；404 不变 |
| 页面侧启动脚本 | `node tools/test-startup-boot-progress.mjs` | **ok**：8 组断言（250ms 才出现、只一份、n/4 递增、静默 800ms 自收、挂住插件 30s 兜底、ready 只有一次且与进度解耦、200 次 mutation 只扫 1 次、`at` 单调、重复 apply 不重复计数、DOM 抛错时静默且事件仍上报） |
| 补丁配方幂等/唯一命中 | 两个测试里都按 `ExactTextPatch` 判据断言 | 每条 `before` 在真源码里**唯一命中**；`after` 留下 marker ⇒ 重跑时整条配方跳过 |
| 语法/资产登记 | `node --check startup.js`、`test-runtime-descriptor-inputs.py`、`test-generated-asset-directory.py`、`test-source-text.py`、`test-ui-i18n.py`、`run-host-tests.py --check-manifest` | 全 **ok / PASS**（`--check-manifest` = 127 项） |

### 与本改动无关的既有失败（已核对：全新 worktree 在 HEAD 上同样失败）

- `tools/test-asset-deployment.py` → `ASSET_DEPLOYMENT_MISSING:ubuntu-tools.bin`（生成物未提交）。
- `tools/test-release-layout.py` → `RELEASE_LAYOUT_FILE_MISSING`。
- `app/src/main/assets/ubuntu-tools.manifest.json`、`gradle/verification-metadata.xml`、`tools/i18n/ui-source-baseline.json`、`tools/recovery-runtime/lock.json` 在**全新 worktree 里就已是 `M`**（CRLF/`.gitattributes` 缺省差异），本次**没有** `git add` 它们。

### 没做但值得做的（按收益排序，理由见 §4）

1. 上游把 `dsh-client-ui-settings-account` 的 8 张引导图从内联 data URL 改成按需 `import()` 分片（`dsh-client-modules` 已支持 `require.async('./client.<name>.js')`）⇒ 主组合 5.58 → ~1.72 MB gzip。
2. 若确认手机侧不需要"账户/引导"页，可在 profile 补丁层 `disabled: true` 掉该客户端插件（可逆一行），收益同上。
3. App 侧 WebView 预热（需真机验证鉴权时序）。
