# 插件、会话与侧栏交互性能（2026-10-22）

状态：**2100标准APK已完成所选软件门禁及同签名覆盖、受管恢复及双引擎息屏启动/旧会话检查；2020拒绝、1817和1325历史分别保留，其余未验范围另记**。源码分析、受控宿主、浏览器与手机实测分开；pending不冒充完成，公开发布继续暂停。

[English](en/ui-performance-20261022.md) · [本轮版本范围](releases/v0.2.1-rc.2-build190-ui-performance-20261022.md) · [文档总索引](documentation-index.md) · [前轮启动与初始化](performance-startup-20261022.md)

## 精确2100交付与本轮实测

标准APK为`0.2.1-rc.2-20261022.2100-dsh0.2.1-rc.2` /190 /`dsh.android.client` /E7E3 /非调试，292,198,739字节，SHA-256 `d62f17f23a7949f0f25285ec7afa44cb35de24a6bc91887ba4600023d18ffee0`。常规同名APK已替换，1817原件留history，2020拒绝包不分发，Low172不变。完整源码ZIP与私有上传可得性仍以最终交付回执为准；公开DSHA发布继续暂停。

所选两版各44类/332项JUnit，0失败/错误/skip；host-r2全17入口通过，最终夹具字节复核通过；APK七门禁包含精确归档中六模块的真实Java fresh/repeat/升级链；Release Lint零error/fatal，warning248/161。不等于全部185个host或全源码/设备矩阵，Low未新打包/安装。host-r1为16/17：新增management浏览器测试漏声明browser依赖，隔离HOME下找不到Playwright，在浏览器启动前失败。仅补manifest的browser依赖后全17重跑通过；测试计划SHA从`7670a95677ff3ff79f09c86eca9fcacd39a8f906ff588980e29fb2154f06d6bd`改为`bd9f1ca03c3f3476ed1e0816a5d92847c237c5f31dc352768239127e025e2fd4`。该test-only修订不改变生产/构建/JUnit/APK输入，无需重打包；原失败与分段记录保留，不称一次连续全绿。

Android13正常停止后同签名覆盖，58项受检查用户文件在安装后首次启动前全部字节相同；真实自动恢复待处理维护并达到READY。proot点击后立即息屏，约11.969秒复合观察中已见实际运行时、鉴权与CPU锁；这包含观察调度，不是纯auth或Web-ready测速。醒后实际Web显示原保留会话，新增management端点可用。未卸载/清数据，不把58项检查扩大为全部维护后个人数据验证；随后在同精确2100包正常停止proot，经UI保存proroot，点击Start后立即普通息屏；约7.344秒复合观察已见libproroot、bridge、鉴权与CPU锁且屏幕为Asleep，不是纯auth或Web-ready测速。唤醒后实际Web可用、原60行会话保留，插件管理页显示完整列表。此项不含proroot开关测速、新模型调用或长时测试；切换使用正常停止，无强杀/卸载。

同手机proot后端经桌面Chromium/ADB的页面就绪三轮为**2470.354/1919.402/1406.708ms**，1817仅一个1882.5264ms基线：前两轮更慢，第三轮较快，顺序/缓存与样本不足，不能称普遍整体提速或给因果百分比。新management能力查询首轮约6.6ms，对照旧full查询441.8ms，只是该RPC成本变化，不是整个页面/手机渲染。手机有效r2录像按首次记录pending到目标样式且pending消失的同边界，ON **3.243200秒**、OFF **3.099544秒**、再次OFF **3.394244秒**；这组比1817 ON4.143478/OFF3.571067秒短，仍为3秒级且不是点击派发/后端完成同步时钟。首次ON录像实际在系统控制中心，已作废并恢复方向锁，不混入样本；有效操作后profile回到原`4f110da06dd1c6d3c19ec839a8009a4947e793af0af8db628c1e6b3ff9b88d0a`。

固定构建输入包为695,627,776字节，SHA-256 `417f5b022a99baaa611bb3a68695f29df5e23fc529efd76d5e55b069d8829e18`，与待生成的完整源码ZIP区分。1817 PiP和103.359秒任务仅保留原证据范围，不转记2100像素、Doze或长时通过。

## 新增2020/2100阶段：能力小查询、完整证明并行与升级链修正

当前精确2100包及已验范围见上节。2020候选没有交付通过：同签名覆盖后，运行工具准备明确报PATCH_CHAIN_MISMATCH，受管候选未提交，原环境保留；所比对58项用户文件在覆盖前后字节相同。以下分别保留1325/1817历史实测、2020失败与2100源码修正，不相互转记。

### 2020失败与2100修正：fresh Node通过不等于Java升级链通过

worker V1改写了旧plugin-manager-policy中从零计数第12项patch的完整after执行前缀。它在新鲜模块的Node测试中通过，但生产Java对prepared文件重入及从canonical归档重建时仍需识别旧连续锚点，因此报PATCH_CHAIN_MISMATCH。真实Java算法的独立复现覆盖六个目标模块：前五模块通过，plugin-manager在fresh时通过、prepared-repeat和bundled-managed canonical路径失败。不是端口、用户插件或数据丢失的证明，不能跳过检查让它启动。

V2保留整段旧执行前缀，使用`DSHA_PLUGIN_BUNDLE_READ_WORKERS_V2`标记，不放宽guard、摘要或canonical恢复条件。[正式链回归](../tools/test-plugin-management-patch-chain.py)使用[精确历史V1源码fixture](../tools/fixtures/plugin-bundle-proof-workers-v1-patch.json)，调用真实ManagedPatchChain/ExactTextPatch，仅替换Android UiText本地化依赖。六模块fresh、重复应用、1817升级、坏V1仅一次新canonical恢复、坏canonical拒绝均通过；Node八组也通过。最初这是源码/当前registry门禁；随后精确2100 APK六模块归档字节/真实Java重复链及手机受管恢复均通过，见上节，不反向抹去2020失败。此前V1的Node成功和CRLF/LF等价桥均不足以证明Java入场成功；原失败保留。

### 实际proot后端的插件页基线

桌面Chromium经ADB连接同一手机的1817 proot后端，插件页从点击至观察ready为**1882.5264ms**；先inventory/full约441.8ms，再并行listBundles约1136.5ms与listPlugins约302.6ms。它包含真实后端、传输及桌面渲染，不是手机渲染纯测速；两条并行RPC不能串行相加，不能把所有等待归为文件hash或CPU。

1817手机ON/OFF总pending为**4.143478/3.571067秒**，比1325的6.743/5.726秒观察短，但顺序/缓存及样本有限；这是2020/2100阶段的基线，不是新候选收益或严格因果百分比。旧慢样本仍保留，不因候选实现完成就转记提速。

### management()是独立小合同，完整list不变

前端原来只为managementAvailable读取完整inventory。新增零参数 `management()` 返回新鲜管理能力小结果，不读取预设组合、不承诺插件已激活；旧零参数list及完整entries结果合同保持。Host/remote/aggregate API codec与前端调用者配套，未放宽通用RPC参数/结果校验；只有权威bundles/plugins读回后页面才ready。旧进程内投影方案曾被真实wire合同拦住，已弃用并保留失败，不能将其开发通过当正式方案。

实现入口：[Host能力](../app/src/main/assets/plugin-inventory-management-patch.json)、[前端小查询](../app/src/main/assets/plugin-manager-management-probe-patch.json)、[真实wire回归](../tools/test-plugin-management-probe.mjs)。五份能力recipe（含host/remote/API codec）须同一受管资产集整体升级/回滚。冻结原件CRLF与整合LF有解析等价桥，格式变化不记作性能收益。独审真实ClientRemoteService→HTTP→Host严格codec的5组通过，覆盖缺失/出现/错误、旧list、真实HMR启停、未结算权威列表不假ready；不是新APK/手机认证结果。

### listBundles最多双worker，仍读全部证明字节

[worker补丁](../app/src/main/assets/plugin-bundle-proof-workers-patch.json)仅改变完整证明的调度，不减少总内容字节，不用mtime/size代替证明；结果次序、来源/NOFOLLOW、改文件与目录竞态拒绝、删除目标复核、取消/失败排空保留。单次完整请求最多2worker，底层全局2活跃/32等待边界未扩大。[8组真实证明回归](../tools/test-plugin-bundle-proof-workers.mjs)及原组合通过；注入逐读延迟的结果不是手机存储收益。

过载边界明确保留：18个同时完整查询中17成功、1个PACKAGE_PROOF_BUSY，之后新请求恢复。每请求两个证明任务可能更早耗尽原等待预算，不能写18/18成功或无限并发。单目标读取仍为单包/单worker。

### PR119已有机制，新增实际View回归

当前[PictureInPictureFrame](../app/src/main/java/com/deepseekharness/app/ui/PictureInPictureFrame.java)此前已在measure/layout两阶段检查子View真实isLayoutRequested，并把必要child layout与去重transform分开；不是本轮重新实现。新增[实际View测试](../app/src/test/java/com/deepseekharness/app/ui/PictureInPictureFrameLayoutTest.java)在SDK29/34运行14次，另加[8项Policy](../app/src/test/java/com/deepseekharness/app/util/PictureInPicturePolicyTest.java)共22通过；旧PR base负控14次中6失败，故意关掉measure或layout请求检查也各6失败，区分两阶段真实dispatch。

默认PiP仍关闭、只由用户显式开启；保留固定逻辑viewport、窗口变大只改transform、全屏清缩放及硬件加速设置。此阶段只新增回归，没有用旧PR覆盖后来userEnabled策略。初次缺真实AndroidX R资源/JDK选择错误保留；宿主View不是真实手机WebView合成器、像素或新最终PiP验收。2100新包的PiP像素矩阵未重验。

1817另有用户显式开启PiP的真实流式视频观察：固定位置区间共1052帧，未见整块近黑、近白或均匀空白，且有新输出更新；但第160–162帧顶部约35px旧内容短暂混入，第163帧恢复。这个局部异常未定位/未修，不写零异常或所有PiP问题已解决。原始H264没有可靠墙钟PTS，不把帧号换算为毫秒延迟或token速度；移动入场、旋转、其它设备及长时仍未覆盖。检查后经UI恢复默认OFF，两项本机PiP选择键均为false；不上传原始画面或完整偏好数据。

## 1817及前期已定位与已修改

| 路径 | 源码事实与修改 | 当前证据和边界 |
|---|---|---|
| 插件开关/设置保存 | PluginManager先取profile文件锁再进HMR，旧ConfigEditor顺序相反；统一为File→HMR | 真实模块、文件锁和Cordis生命周期受控复现ABBA互等；是特定并发失败，不是所有请求共享一把通道锁 |
| 插件/默认预设 | 先显示真实pending，保持旧checked/default；等待写入、确认读取、必要空白任务同步后再完成 | pending反馈与busy覆盖确认阶段；失败、取消、需重启和未确认状态继续区分，不假ready |
| 会话列表 | 一次有效鼠标/touch/pen单击直接选择并打开；保留Enter/Space、拖动/取消/长按边界 | 没有300ms定时器或第二击门槛；长列表只通知受影响行，不以媒体查询伪造输入来源 |
| 侧栏浅色闪屏 | 手机抽屉遮罩误匹配普通按钮的全屏浅色`:active`阴影；仅对该遮罩排除box-shadow | 真实Chromium可信touch复现/修复；保留遮罩角色、渐变、抽屉动画与普通按钮反馈；手机待验 |
| 电池设置S02 | 用户点击后开普通电池优化设置，不支持时回退当前包详情 | 不新增豁免权限、不自动白名单，不把发Intent当系统设置已改变 |

### 1. File→HMR：消除可复现锁序倒置

实际插件修改链为 `setBundleEnabled → change(文件锁) → configure(HMR) → publishAndReload → reconcileProfile`。普通配置或“设为默认”经过ConfigEditor；会话中的预设选择则按Agent串行并等待Host树审计，不直接使用该profile文件锁。插件列表也会读取组合诊断，可能等Loader；不能把业务等待一概归因动态Web端口或HTTP公共通道。

[锁序recipe](../app/src/main/assets/config-editor-lock-order-patch.json)将ConfigEditor统一为File→HMR；保留身份/schema、原子持久化、写后真实激活、失败回滚、锁期限和HMR嵌套事务拒绝。没有把pnpm网络安装扩大到HMR临界区。[真实生命周期回归](../tools/test-config-editor-lock-order.mjs)的6组场景包含原实现负控、实际插件启停、慢激活不提前applied、锁超时不重放、失败回滚与无HMR的restart-required。

Windows/Node受控夹具原ABBA路径约**2126.7ms后配置超时**，候选约**275.8ms完成真实配置及插件操作**。这是带控制屏障的固定顺序宿主实验，两个结果语义也不同；不是手机耗时、A-B-B-A交替测速或通用提速百分比。合法写者仍可能排队/超时。另观察到显式重载后watcher再reconcile，当前没有去掉它；不能用mtime/size或“做过一次”替代完整输入与内容证明。

### 2. pending、确认与一次有效会话单击

[插件pending补丁](../app/src/main/assets/plugin-manager-pending-patch.json)显示“正在应用更改并确认状态”，busy持续到权威刷新结算；只按真实读回改变checked。刷新失败解除busy并报告未确认，让用户显式重试。[默认预设补丁](../app/src/main/assets/agent-preset-pending-patch.json)区分保存、确认、同步当前空白任务；保存成功但同步失败时明确说明，不用旧清单同步错误默认，也不改变已开始turn的会话。

1817阶段R3已加入提交屏障与刷新复用：read在实际开始前等待当前mutation Promise，再记录读取/请求serial；同一次提交的changed与RPC完成可共享提交后的权威读取。受控changed-before-unary样本中inventory/bundles/plugins请求由 **2+2+2→1+1+1**；不是所有时序永远只读一次。读取中外部变更、完成后迟到事件、较旧A快照期间B提交均仍触发新读，busy不能拿旧快照当确认。未引入乐观checked或长期结果cache；共享roster等其它去重未自动算完成。



1817配套后端通过[entries投影provider](../app/src/main/assets/plugin-inventory-entries-patch.json)及[manager consumer](../app/src/main/assets/plugin-manager-entries-patch.json)增加同步 `readPluginEntries`，listPlugins只消费原Loader entries，去掉结果未使用的preset/composition await。原顺序、entryId、启停意图、Fiber的active/pending/failed/null及metadata保留；无结果cache，旧export兼容。完整readPluginInventory仍保留原预设组合诊断与等待，listBundles精确内容证明、HMR/写锁/真实激活未删。

[真实Host回归](../tools/test-plugin-inventory-entries.mjs)观察每次listPlugins的未使用composition调用1→0；无关Host加载被屏障卡住时可返回真实loading snapshot，后续读为active。完整inventory仍等原诊断。受控稳定状态及快照等价，不等于所有并发HMR时序字节相同：旧entry先捕获、后await才映射，新映射在同一JS turn较早执行。provider/consumer必须成对升级/回滚，避免缺失named export。

独立R3浏览器检查含17组、重复刷新负控及额外晚事件/交错操作；受控待确认状态下开关坐标与卡高不变。它与entries-only检查只证明所列源码/宿主范围；精确最终包的软件检查已通过；具体手机刷新/性能效果仍须对应实测，不由宿主负控外推。

[会话交互实现](../app/src/main/assets/web-integration/session-interaction.js)与[接线recipe](../app/src/main/assets/session-interaction-patch.json)按实际pointerdown保留输入类型；取消、拖动、位移超过12px、非鼠标长按等不会误开会话。键盘激活直接打开；打开事件仍关闭移动抽屉，不主动focus输入框。没有恢复“先选中、第二击打开”或延迟300ms的建议。

[UI操作浏览器回归](../tools/test-ui-operation-pending.mjs)使用真实模块/React行和受控远端Promise：10组结果覆盖可见pending、一次mutation、刷新新鲜度、失败/取消/需重启、迟到dispose、预设身份边界和真实touch/键盘。[会话专项](../tools/test-session-interaction.mjs)15项通过。它们不是手机延迟/帧率或最终APK通过证明。



预设VIEW_V2保留原intro占位，把阶段status绝对定位在原说明区域内；[真实UI回归](../tools/test-ui-operation-pending.mjs)18组覆盖280/360px、中英文、保存/确认/同步/完成阶段，卡片矩形和滚动位置稳定。这是宿主CSS/React结果，不是手机布局矩阵。旧VIEW_V1遗留独立提示段落曾使短intro锚点误判已补；当前用intro紧接state.error的连续边界，让旧版本触发锁定canonical恢复一次后完整重打。当前registry为VIEW_V2。

[真实Java升级回归](../tools/test-preset-pending-patch-upgrade.py)用[精确旧V1 fixture](../tools/fixtures/agent-preset-pending-view-v1-patch.json)，验证fresh、旧版一次恢复、严格等于canonical V2、幂等和短锚点负控。不是逆向删段落、放宽匹配或跳过内容证明；旧失败保留；该升级专项已纳入最终所选host通过范围，手机预设最终检查仍待补。

### 3. 闪屏：证明的是遮罩按压绘制

[移动补丁管线](../tools/apply-mobile-client-patches.mjs)在固定上游产物上加 `[data-mobile-nav="backdrop"]:active { box-shadow:none; }`，生成[生产client](../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js)。不是只改未参与打包的TS，也没有关闭动画或遮住有效内容。

[可信触摸回归](../tools/test-mobile-backdrop-press.mjs)提取真实rc2主题CSS、移动样式和遮罩模块。基线真实`:active`时半透明白色inset阴影使同一背景像素RGBA **[11,11,12,255]→[45,45,46,255]**；候选阴影为none、像素保持[11,11,12,255]，普通按钮仍保留按压反馈。可信CDP touch与实际`:active`相符，不靠强制伪类或合成PointerEvent。

快速关开、touchCancel、滑动关闭、销毁清理和固定上游重建一致性有宿主证据；[Modal](../tools/test-mobile-modal-behavior.mjs)、[手势](../tools/test-mobile-gesture-guard.mjs)及[补丁闭环](../tools/test-mobile-client-patches.mjs)通过。宿主marker部分由夹具驱动，不证明Android/真实React提交时序；其他RightbarSeat/有效surface重建、几何闪动和所有手机仍待测，未擅改原生主题桥或late-commit。

## 息屏：已有服务与启动窗口分开

[HarnessService](../app/src/main/java/com/deepseekharness/app/HarnessService.java)在已运行持续模式下保留FGS及PARTIAL_WAKE_LOCK；屏幕广播仅刷新锁，不停止Node。[PowerPolicy](../app/src/main/java/com/deepseekharness/app/util/PowerPolicy.java)默认eco=false；eco只在非LAN、无原生任务、息屏且持续已知空闲60秒等条件满足时允许释放Web锁，仍不主动杀Node。未知/过期idle不冒充真空闲。[RuntimeTasks](../app/src/main/java/com/deepseekharness/app/core/RuntimeTasks.java)另保护实际原生任务。

Standard的[WebPreviewActivity](../app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java) `onStop`暂停展示并标记恢复订阅，没有停止Node或重发业务。不得为省电在onStop暂停Node，或把展示取消当模型请求abort。

**S01完整票据已实现，双引擎立即息屏已有实测，完整设备矩阵仍待补：** 1325的慢preflight→MAIN.post后才建立FGS存在窗口；旧14组方法夹具是该历史问题证据。当前[FirstRunWebStart](../app/src/main/java/com/deepseekharness/app/ui/FirstRunWebStart.java)在可见、主线程且RESUMED的用户操作帧内同步请求[HarnessService](../app/src/main/java/com/deepseekharness/app/HarnessService.java)准备保护，覆盖首次安装接续、Launch启动/重启及[StartupRecoveryActivity](../app/src/main/java/com/deepseekharness/app/ui/StartupRecoveryActivity.java)可见恢复入口。不满足可见条件的首次安装请求保留pending，等onPostResume合法窗口再调度，不任意后台startFGS。

[WebStartProtection](../app/src/main/java/com/deepseekharness/app/util/WebStartProtection.java)票据只在本次app会话内存中，绑定UUID、具体service实例和精确generation；实际startForeground、CPU锁已取得且keepalive线程存活才ack promotion。worker在主线程外最多等8秒，准备总预算120秒单调时钟；慢准入不占生命周期monitor，最终有效性检查与beginStart/内部Stop转移原子结合。准备通知不等于后端或网页READY，没有新轮询或持久重放票据。

外部Stop即使合并同代旧Stop也撤销票据；仅精确内部恢复Stop可保留自己的ticket，并须同代真实STOPPED且无stop错误才继续，UNKNOWN/UNCONFIRMED/FAILED不启动第二后端。worker在实际工作前claim精确票据/代次；服务销毁或promotion/type更新失败只安全停止自己仍拥有的已准入代次，迟到旧service不能停止新代。新Stop先建立生命周期围栏，屏幕权限撤销在自己的monitor外完成，再释放worker latch；提交拒绝记录FAILED并收尾，不伪造退出证明，不延长设备许可。

独立冻结快照的5类81项实际JVM/Robolectric测试通过（[owner](../app/src/test/java/com/deepseekharness/app/util/WebStartProtectionTest.java)、[controller](../app/src/test/java/com/deepseekharness/app/core/WebLifecycleStartProtectionTest.java)、[可见入口](../app/src/test/java/com/deepseekharness/app/ui/VisibleWebStartProtectionTest.java)等）；I/O/controller/桥为受控端口，未启动Node或证明Android/OEM执行。原classpath及AppCompat资源初始化失败保留；最终资源完整Gradle已完成所选42类310项/版，详见下节分段边界。统一Java格式化是token等价证据，不独立充当设备实测。

保护覆盖新可见入口及其同代service，不扩称所有旧内部/自动启动已消除竞态。旧recoverWebIfUnchanged、forceStop、recordWebFailure/failedWebPage外层锁及foregroundRetirement→shellHttp.close仍是独立锁序范围；整个进程死亡可能不调onDestroy，继续依原持久进程身份/退出证明恢复。Doze、OEM与网络限制未绕过，不据81项宿主测试宣称省电、降温或全设备保活。

**S02已做源码纠正：** [DeviceGrantsFragment](../app/src/main/java/com/deepseekharness/app/ui/DeviceGrantsFragment.java)不再调用缺权限契约的直接豁免申请，改普通设置→应用详情→明确失败。[5项真实Intent/Robolectric测试](../app/src/test/java/com/deepseekharness/app/ui/BatterySettingsNavigationTest.java)覆盖正常、ActivityNotFound、SecurityException和双入口失败；未新增豁免权限。1325手机已实际打开系统电池设置，前后白名单状态未变；只证明入口导航，不替用户授权或默认豁免。

FGS/Java锁isHeld不保证自然Doze、网络或所有OEM保活；USB充电/调试的息屏不能代表未充电深度Doze。本轮未做完整息屏、功耗或温升矩阵，也不默认改白名单、eco、网络或系统idle。[后端空闲](../tools/test-app-activity.mjs)与[订阅恢复](../tools/test-browser-stream-resume.mjs)已有专项，不扩大为新包手机通过。

## 外部优化建议的取舍

`OPTIMIZATION_CHANGES.md`及模型讨论是参考，不是本轮验证。报告来自不同设备/包与手改树，不能移植其耗时/CPU/模块计数。

- 前轮已经做了Zod语义合并、双鉴权消重、notifier空闲免桥请求及APK应急物理共享；本轮不重新套热改软链方案。应急仍需独立运行根，不等于共享可变正式树。
- 编译缓存、合并guest exec、roster去重值得独立对照；须验证命中、版本失效、不可写退化、两runtime和正常退出。当前没有据参考文档默认启用或报收益。
- 不采纳：300ms等待/恢复双击、任意后台直接startFGS、onStop暂停Node、锁/根mtime当内容证明、无依赖围栏把CAS搬后台、按名字把第三方包当内置。已有文件/身份/事务/退出检查不能为流畅跳过。

## 历史1817交付与1325证据

最终标准APK为 `0.2.1-rc.2-20261022.1817-dsh0.2.1-rc.2` / code190 / `dsh.android.client` / E7E3 / 非调试，292,189,033字节，SHA-256 `8460ee70f5f6946132372737cfc90f097e00e89dc519973af5eb783f833ac60e`。1817交付时曾替换本地常规同名APK；现1817原件及1325等更早包保留history，常规包为前文2100，Low172原字节不变。远端完整源码/附件可得性仍以最终私有回执为准。

最终软件回执为PASS_FINAL_SCOPED_SOFTWARE_WITH_PRESERVED_FAILURE_HISTORY：两版所选各42类/310项JUnit，0失败/错误/skip；14个所选host入口通过；Release Lint均0 error/fatal，warning248/161；精确APK身份/签名/资产等门禁通过，不是全部182个host或全源码测试矩阵。旧Low误用API24 CompletableFuture已改API23兼容FutureTask；两轮JUnit因测试源码Shadows重载及FingerprintManager缺失在编译前失败，改typed Shadow.extract属test-only，原件保留。另一次旧descriptor前置检查拒绝、未进Gradle。最终结果是分段重跑收敛，不称一次连续全绿。

Android13已正常停止后同签名覆盖，58个受检查用户文件在安装后、首次启动前全部逐字节一致；受管更新READY。最终proroot/proot各在点击后立即息屏，期间后端鉴权、实际运行时进程和CPU锁均出现；复合观察约7.063/9.219秒，包含操作/检查时序，**不是纯鉴权或Web-ready测速**。醒后proroot已看到旧会话，proot也加载旧会话；普通USB息屏不证明Doze/OEM或整个中断矩阵。

1817普通息屏模型任务实际完成103.359秒、12步，保留为1817证据；其双引擎立即息屏7.063/9.219秒复合观察及58文件安装前后保持范围不扩大。该103.359秒普通USB息屏不是自然Doze。2020受管准备已拒绝；2100精确包/软件及proot范围见前文，不把1817任务转记2100长时通过。

下列身份和软件/手机结果**只绑定已测1325包，不代表后续候选最终包**。1325标准APK为 `0.2.1-rc.2-20261022.1325-dsh0.2.1-rc.2` / code190 / `dsh.android.client` / E7E3 / 非调试，292,169,844字节，SHA-256 `a2903a74d9a425939c77372031cc7347b0926f7e93617fd041c8b3129c5c638b`。本地常规同名包已替换，前轮d77原件留在history；Low172原字节保留，不把两版软件检查写成两版手机通过。

- 两版所选各11类/74项JUnit，0失败/错误/skip；Release Lint均0 error/fatal，warning为248/161。r1旧registry测试硬编码32，新增后实际为34/40项recipe；修正测试预期后r2完整重跑所选11类，保留r1失败。不是全库测试或一次纯绿过程。
- 官方host6项和extra6项共12个入口在当前完整字节复核后通过，`PASS_FINAL_COMPLETE_BYTE_RECHECK`绑定的是所选runtime fixture；分支原始负控和更早软件计数分别保留，不合并成新全量。软件汇总自身不承担手机/模型结果。
- Android13同签名正常停止覆盖安装；55个受检查用户文件在安装后、首次启动前逐字节一致，旧包未改，无卸载/清数据。不能扩大成维护运行后的全个人数据比对。
- 运行环境受管升级达到READY；生产proot官方鉴权日志12.5秒，私有UI点击/截图观察13.4秒是另一边界，不相减冒称UI渲染耗时或提速。
- 已恢复旧真实DeepSeek对话；从新增空任务对正确旧会话行轻触一次回到老对话。首轮坐标点到新增空行，定位后纠正，原记录保留，不计为旧会话打开失败。
- 实验性会话搜索插件实际ON/OFF成功，受检查profile恢复原SHA（4f110d…）；auto-review原来ON且未改。该一次开关不能代表全部官方/第三方插件或预设组合通过。

1325普通息屏约103秒的DSHA真实模型计时任务已完成，同PIDs及CPU锁保持；USB充电条件不代表自然Doze。proroot的真实原生鉴权为5.1秒，Web实际显示原保留会话；脚本曾只识别node进程名而实际proroot显示bridge，误等约79秒，原失败保留，不能当启动耗时。

1325会话搜索ON/OFF总pending为 **6.743/5.726秒**，没有整体变快证据；这保留为继续减少重复读取和未使用等待的理由，不因功能成功就改写为性能通过。R3及entries-only已随1817复测开关，新观察见2020/2100阶段的1817基线；1325记录不覆盖新结果。侧栏视频与其它手机交互分段仍待分析，不能把宿主像素或ABBA结果当手机显著加速。

## 验证待补与回滚

1325/1817历史范围与2100当前结果分别如上。**仍待补：**更多预设/Stop/服务损失/侧栏覆盖及PiP局部异常调查；完整源码ZIP/私有可得性以最终回执为准。需区分点击反馈、RPC/文件锁/HMR等待、Loader激活、权威读回、React稳定帧和真实功能；pending显示快不等于操作已完成。需核无关RPC仍响应、失败后可继续、意图重启保留、已有turn不误切、后台同会话和Stop不复活旧代。

分支初始fixture输入拦截、浏览器主题/可信触摸采样修正、S02本地测试classpath失败均保留，不冒充一次全绿；本页未新跑这些应用测试。Root递归READ子链接缺口与全设备/长时矩阵仍未解决，不因UI修订扩写安全结论。

回滚时五份management能力recipe整体恢复；双worker recipe可独立撤回，PR119此次仅撤新增测试及登记。其它窄recipe、会话接线、移动管线、S01票据/服务/controller/可见入口整组或S02方法分别恢复，再由统一负责人重建描述符/运行包并回归；先核摘要，不覆盖后来修改，不回滚用户会话、插件意图或外置工作区。锁序回滚会重新带回ABBA风险。
