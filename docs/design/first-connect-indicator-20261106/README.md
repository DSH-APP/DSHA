# 首次连接提示补丁（T58）：归档说明（归档分支不合并；补丁自 build324 起已随包）

- 日期：2026-11-05（UTC）
- 基线：build330 / `8ff5a37e` / 内置 DSH 0.2.2-rc.1
- 状态：**归档分支不合并；补丁本身已随 build324 并入并随包**。旧分支已删除，保留 tag `archive/feat/first-connect-indicator-20261106`（提交 `5918af63`，与本文档同批推送；该 tag 是独立分支快照，不另行合并）。
- 范围：只记录结论与后续边界；本归档不重建 fixed-inputs、不新增或扩展受管补丁，也不回退已并入的补丁。T58 补丁已随 build324（`a54f9854`）并入并随包，build324–build331 都携带。
- 相关任务：T58（`TASKS.md` 的 T58 详情）。

## 0. 如果你是从旧分支 / tag / 旧讨论找过来

结论已经在这里：**T58 的补丁已随 build324（`a54f9854`）并入并随包，build324–build331 都携带；`5918af63` 只是同一补丁的独立分支快照，不另行合并**。补丁自 build324 起生效，首次进入闪黄/绿的目标场景在已随包的版本上无法稳定复现；切页返回出现黄色徽章是另一个可复现现象，但与 T58 补丁“仅静默页面内首次连接”的规则一致，不是漏修。不要 cherry-pick `5918af63`，也不要从该分支重建固定输入。需要处理切页黄标时，按第 5 节新开任务。

## 1. 它原本要解决什么

build325 时代的目标：DSH 0.2.2 首次进入设置/页面时，不再闪黄色「重新连接中」和绿色「已连接」。补丁通过 5 处字符串替换修改 `@deepseek-ai/dsh-client-ui-settings-general/lib/client.js`：

- 模块级 `hasConnectedOnceInPage` 记录本页面是否连上过；
- `dshaAdvanceFirstConnect` / `dshaConnectionIndicator` 在 `firstConnectPending` 时抑制黄/绿，真实断开仍显示红色；
- 连接中计时与 `showRecovery` 同步调整。

补丁文件是 `app/src/main/assets/first-connect-indicator-patch.json`，绑定 `sourceSha256` / `packageIntegrity` / `dshVersion`，并带 `tools/test-first-connect-indicator.mjs` 测试。

## 2. 为什么难复现（用户与 @main 的结论）

不是单一缺陷，至少四层叠加：

1. DSHA 的手机竖屏适配插件与上游 `dsh-client-ui-settings-general` 的写法不标准（状态放在模块/组件层，连接状态与挂载时机耦合）；
2. 上游打包/压缩算法：补丁是对压缩后 bundle 的字符串替换，换版本或压缩参数就可能失配；
3. 厂商 WebView 魔改：连接状态、定时器、重连时机的实现差异，使“首次进入闪一下”在不同机型/内核上表现不同；
4. 目标现象本身是短时、偶发、依赖时序的 UI 闪动，缺少稳定复现窗口。

结果：build330 已随包该补丁（自 build324 起），无法再稳定复现“首次进入闪黄/绿”；若要独立验证补丁收益，对照基线需回到 build323 及更早。

## 3. 为什么归档分支不合并

- **归档分支无新增收益**：同一补丁已由 `a54f9854` 并入 build324 并随包，重复合并 `5918af63` 不增加功能；build330 已携带该补丁，单看 build330 也无法区分「补丁生效」与「问题本就不出现」，独立验收需回到 build323 及更早的对照基线；
- **维护成本高且脆**：对压缩后上游 bundle 做字符串替换，上游一升级/改压缩就可能 patch 不上；DSH 版本、模块完整性、`sourceSha256` / `packageIntegrity` 全部绑定，需要随每个 DSH 版本重做；
- **代价不成比例**：该补丁属于受管运行时补丁，落地要重建 0.2.2 运行时归档、重算 `dsh-runtime.inputs.json` / `runtime-descriptor` / `managed-package-proofs`，并触发 fixed-inputs 重打包与 notices 检查；为一个不稳定 UI 闪动不划算；
- **风险**：全局抑制“连接中/已连接”可能掩盖真实重连；该补丁只静默“页面内首次”，跨页/重挂载的语义还需要产品规则，不能靠字符串补丁猜。

## 4. 当前真实行为（不要误报为 T58 漏修）

- 切到其他页面再返回时，仍可能出现黄色「加载中/重新连接中」徽章；`hasConnectedOnceInPage` 是模块级、按页面生命周期记录，换页/新页面实例/真实重连都会重新进入 connecting 分支。这与补丁设计一致。
- 该现象可复现，但“切页返回时该不该显示黄标”是产品规则问题，不是 T58 能回答的；未定义规则前不继续打补丁。
- build330 已包含 T58（自 build324 起随包），当前设备表现就是上游原状 + 既有受管补丁（含 T58 的首次连接抑制）；切页黄标仍按本节说明，不是漏修。

## 5. 如果以后要修（新任务前置条件）

必须新开任务，基于最新 main（build330+），不要动旧分支：

1. 可复现基线：设备型号、Android/WebView 版本、DSH 版本、proroot/proot、具体页面路径；
2. 连接状态时间线：切页前 / 返回后 / 黄标出现 / 消失 / 真实断连重连，记录 `connectionState` 与挂载/卸载；
3. 产品规则：跨页面是否保留“已连接”事实？黄标只在真实断连重连时出现，还是页面重新挂载也允许？
4. 实现方式优先上游支持点/JS 层稳定钩子，不再对压缩 bundle 做字符串替换；若必须 patch，绑定 DSH 版本与摘要并补正反例测试；
5. 门禁与范围：固定输入、descriptor、notices、host tests、真机矩阵（至少覆盖厂商 WebView 差异）；构建号由主会话分配；
6. 如果产品决定“切页返回黄标是正确状态”，则不需要补丁，关闭问题即可。

## 6. 参考

- 归档 tag：`archive/feat/first-connect-indicator-20261106` → `5918af63`（`git show 5918af63 --name-status`；注意本机 proroot 下 `--stat` 可能 SIGSEGV）
- 旧提交内容：`app/src/main/assets/first-connect-indicator-patch.json`、`tools/test-first-connect-indicator.mjs`、`docs/releases/v0.2.1-rc.5-build325-first-connect-indicator-20261105.md`
- 任务：T58；相关讨论：T61（覆盖安装白屏与受管补丁链）
- 环境陷阱：T82（proroot 下 git `--stat` SIGSEGV）；不要用 `| head` 吞 rc

## 核验与边界

本文只记录归档决定与可复现观察；未对旧补丁做 build330 真机验收，未声明它修好了任何问题。日期与结论以本文件提交时点为准；后续修复以新任务记录为准。
