# 启动与初始化性能调查（2026-10-22）

状态：**标准APK、分段软件检查及同台冷流程/双引擎启动已验；完整业务覆盖及交付仍待补齐**。修改前基准是维护源码 `140c7ba277fc2a91a9b8fd8cb5a24bc35a5c41c5`。当前集成构建候选为 `0.2.1-rc.2-20261022.0243-dsh0.2.1-rc.2` / code190 / `dsh.android.client`，不是最终交付通过声明。最终交付回执必须另绑定版本、签名、资产和测试。已落地源码、宿主夹具通过、手机观察和完整性能收益是不同证据。公开DSHA上传、PR、发布继续暂停；本轮完成后仅向既有私有维护仓库交付。

[English](en/performance-startup-20261022.md) · [启动诊断](startup-and-diagnostics.md) · [安装维护](install-and-maintenance.md) · [运行引擎](runtime-engines.md) · [安全模型](security-model.md)

## 1. 实际可访问的现场与两类运行时实验

主负责人通过已连接手机的ADB及现有授权读取了实际可访问的应用日志、运行状态和选定配置。本报告整理者未独立操作设备。旧记录属于 `com.dsh.client` / code190 / 2026-10-19安装状态，不等于本轮新包 `dsh.android.client` 的受控实验。初始手机记录为Android13/API33、USB充电，后续各轮供电、温度、缓存和系统负载未全部固定。原始手机日志、token、cookie、配对密钥、会话及原始路径不随源码发布。

### 1.1 历史过程还原

实际找到五次相关记录，而非只选择两次。下表日期均为2026-10-19、UTC+8；秒数相对各自启动诊断零点，未包含前段准入预检或用户启动前等待。

| 开始时间 | 请求 / 实际运行时 | 过程与结果 | 后端鉴权 | 网页壳事件 |
|---|---|---|---:|---:|
| 18:23:44 | proroot → proot | proroot于8.6秒以126退出；确认退出后一次回退；3个旧插件兼容警告 | 68.1 s | 服务就绪，未记对应壳完成事件 |
| 18:52:35 | proroot → proot | proroot于9.2秒以126退出；一次回退；同类插件警告 | 76.6 s | 455.0 s |
| 19:13:42 | proroot → proroot | 无回退行 | 17.8 s | 44.5 s |
| 19:29:18 | proot → proot | 正常proot路径 | 31.6 s | 158.4 s |
| 20:27:49 | proot → proot | 正常proot路径，最终记录ready | 32.0 s | 37.7 s |

前两次不能算proroot成功。网页事件还包含人工进入时机、旧说明/配置及前端处理，455秒或158秒不是引擎纯加载时间；结束时保存的elapsed也可能包含会话存续。插件兼容状态、缓存、准备过程及用户动作不同，17.8秒与31.6秒不能当作引擎A/B因果对照。

### 1.2 新增基准与观察器失败

| 对象 | 已观察值 | 正确边界 |
|---|---|---|
| 基准包首次应用初始化观察 | 文件提交43.763 s | 之后仍有约34.45秒trial观察，原观察的trial/健康最终闭环未完整核实；与下列独立冷测速是不同操作。**不能称44秒完整初始化或核心可用。** |
| 精确基准APK独立空根冷测速 | 完整流程65.128 s | 真实renderer、trial、进程退出已确认；文件提交39.316 s，trial的recordCloseEnd为21.750 s，都是总流程内子集，不能再加到65.128 s上。实际运行时proot |
| ca056基准包首次普通proot Web启动 | 58.9 s鉴权链接 | 仅后端；网页、说明保存、配置、工作区及真实核心请求另验 |
| ca056基准包warm proot正常重启 | 18.2 s真实鉴权 | OS温缓存、未drop cache；不是双引擎多次中位数 |
| warm观察器原结果 | 超时报错已保留 | 漏读磁盘日志，后续实际鉴权另核；不当作后端真实卡死，不删除原误报 |
| 0243标准APK生产proroot三次启动 | 原生诊断鉴权6.8 / 5.2 / 5.4 s | 三次均实际proroot，无生产回退；不同于观察器轮询/进入网页/核心请求时钟。缓存及顺序未固定，不报严格因果百分比 |
| 0243受管更新的隔离trial | 实际proot，recordCloseEnd16.980 s | 所选proroot先以126退出后回退；renderer、存储写后重开、guest/launcher退出通过。**不能称proroot trial成功** |
| 0243标准APK同入口独立冷测速 | 完整42.772 s，文件提交24.465 s | 实际proot；与65.128 s基准同流程。两轮完整hash/publish、真实renderer、数据/会话重开及guest/launcher退出通过；不是实际模型请求完成 |
| 0243标准APK生产proot三次启动 | 原生诊断鉴权12.8 / 12.5 / 12.3 s | DSH/插件加载10.5 / 10.0 / 9.8 s；requested及actual均proot，网页壳ready。基准温启动鉴权18.2 s、加载15.9 s，顺序/缓存未固定 |
| 0243独立应急 | READY→STOPPED，closed落盘 | 真独立根、工具/HTTP鉴权、空工作区及欢迎说明Continue/重载通过；无凭据，未验AI对话或正式写修复 |
| 0243标准APK | 292,159,963字节，包检查通过 | Node隔离hook样本见6.1；资源短采样见7.1，没有功耗/温升改善结论 |

65.128秒来自同台手机的精确基准APK，SHA-256为 `ca0562e5df373eed4a4546540d05a8c06aafbce5cac15c877b53915fb0444d19`。本机原始回执为 `baseline-cold-probe.json`；观察器外部轮询耗时68.703秒不是内部冷测速。它使用真实空私有根和签名资产，保留现有数据；[ColdSetupTiming.Scope](../app/src/main/java/com/deepseekharness/app/core/ColdSetupTiming.java)使用自身隔离设置，尽管用户当前profile选择proroot，本次记录的 `runtimeMode` 明确是proot，不能按当前profile归入proroot。候选包已重复相同测速入口，总42.772秒；缓存未清、先基准后候选的固定顺序及各单次样本仍限制因果结论，不能外推通用提速百分比。这一冷测速终点包括隔离网页与读写核验，不代替真实模型请求成功。

本轮精确0243标准APK已完成同签名停止覆盖和受管更新。生产proroot首次到实际创建前的准备约3.4秒，插件注册本身2.4秒；后两轮注册0.8/1.1秒。6.8/5.2/5.4秒是原生日志中的完整官方鉴权链接事件，外部轮询可晚数百毫秒甚至漏读日志；第二轮原观察器超时及后续磁盘读回保留。基准proroot约9.0秒捕获与这些轮次缓存、操作和顺序不同，不能据此把差值全部归因于本轮优化。受管trial的proroot失败与生产proroot成功分别记录，不互相覆盖。

proot只统计 `candidate-proot-corrected-1/2/3` 三次真实新代次。早先名为 `candidate-proot-1` 的脚本轮没有成功发起新代次，现场仍为proroot，已作废；不能按文件名计为proot。proroot另一次误导航触发的4.9秒不纳入预定三轮统计。原失败/误导航及补测分别保留，三轮网页ready只证明壳事件，不把它写成真实对话成功。

## 2. 完整调用链与不同时间零点

[Application](../app/src/main/java/com/deepseekharness/app/DshaApp.java)、[MainActivity](../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java)、[WebLifecycleController](../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java)及[EnvironmentMaintenance](../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java)的计时边界不同。

| 阶段 | 实际链路 / 工作 | 到达后仍不能声称 |
|---|---|---|
| 系统创建进程 | ActivityManager事件；DshaApp初始化偏好/语言、主题、监听、任务和后台作业安排 | UI显示或后台任务完成 |
| 原生检查UI | MainActivity显示检查覆盖层；新用户先Welcome | 环境/Web可用 |
| 旧实例恢复与环境门 | restoreSession与pending/readiness；内核→HTTP→再次内核 | 可以忽略UNKNOWN/退出证明另建guest |
| 必要初始化/更新 | 独立候选、摘要、事务、实际RuntimeTrial、健康回执 | 解压完文件即整个环境可用 |
| Main及启动派发 | FirstRunWebStart或LaunchFragment，受generation及一次派发围栏约束 | Main显示即后端可用 |
| 准入与准备 | requestStart先预检；StartupPipeline停止旧guest/launcher/LAN、工具、事务恢复、CAS、内置注册等 | 诊断0.0秒包括所有点击/准入前等待 |
| launcher/guest创建 | RuntimeExecution/RuntimeLauncher选择实际引擎、端口、环境并创建固定入口进程 | Node/HTTP/前端完成 |
| DSH/插件初始化 | CLI安装/配置解析、依赖与动态库、Host/插件组合、实际监听 | 同名loading多次必然是完整重复初始化 |
| 后端鉴权链接 | 完整官方启动行、实际端口与本次身份 | DOM挂载、模型配置、全部工具可用 |
| 浏览器鉴权及页面 | PreviewAuth/DshAuthSession：token→cookie303、cookie→根页200，WebView/Gecko加载资源 | onPageFinished就是核心可用 |
| 网页壳ready | startup.js检查无boot且composer或shell槽位存在 | 说明已保存或真实对话成功 |
| 核心功能可用 | 同次运行真实完成约定核心操作并核对结果，无未知重放 | 所有模型/插件/设备均通过 |

首次初始化的trial已包含隔离后端，不再当普通首启重复统计。系统事件建立进程创建时刻，应用日志只建立自己的零点。页面ready保留原语义，性能修改不提前发就绪。

## 3. 已批准落地的窄优化

这些是已确认重复工作的缩减。基准首次鉴权链接在58.9秒出现，其中DSH/插件加载阶段53.8秒；不能把58.9秒全部记为Node/Host等待。整机前后对照已有部分观测，但完整匹配实验仍待补。

### 3.1 进入Web的单一鉴权所有者

- 问题/位置：[LaunchFragment.enterWeb](../app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java)先交换cookie再导航，浏览器的[PreviewAuth](../app/src/main/java/com/deepseekharness/app/ui/PreviewAuth.java)又完整交换。真实生产类与loopback合成HTTP夹具证实首次新页2次exchange、4请求。
- 修改：Launch保留generation/URL、RESUMED/视图、单飞和导航异常重试，直接导航；浏览器原owner仍完整核token、cookie、HTTP200、取消/迟到及代次。无长期凭据缓存。
- 收益：该路径真少1次exchange、2请求和串行后台线程。更早显示浏览器只改变等待位置，不能记作后端加速。
- 实测/验证：10项定向宿主通过，旧源在新契约下负控预期失败。每响应人工60ms的例测约362→180ms，样本/宿主调度不足以外推手机比例；实际Activity/Gecko/Back/旋转/后台组合另验。
- 风险/回滚：错误更早在浏览器宿主显示，保留错误/重试/返回。只恢复Launch及匹配debug/测试契约，不回退其他修改或用户数据。

### 3.2 同次launcher复用已准备的preload

- 问题：[RuntimeLauncher.builder](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java)绑定参数和环境各prepare一次。
- 修改：本次builder局部复用同一完整校验目录；独立environment/bindPreloads及下一次builder仍核验，无全局文件状态缓存。
- 收益/结果：warm宿主prepare调用2→1、preload内容读4→2；9项专项及包含真实TerminalRuntime编译的10项关联通过。手机秒数待测，不归因DSH模块加载间隔。
- 风险/回滚：内容、NOFOLLOW、package proof、根/退出/代次/回退都保留；同UID并发修改仍非强沙箱。仅恢复对应Java与测试，不回退运行数据。

### 3.3 解压会话不可变目录路由缓存

- 问题：[AndroidTrustedAssetFileSystem.directoryHandle](../app/src/main/java/com/deepseekharness/app/runtime/AndroidTrustedAssetFileSystem.java)重复split相同父路径及prefix，产生瞬态分配。
- 修改：每FS实例64条LRU，仅缓存不可变parts/prefixes；并行worker各持独立实例。verifyRoot/lstat/类型/inode/NOFOLLOW open/fstat顺序不变，不缓存stat、FD、摘要或授权。
- 宿主结果：实际归档顺序83,098路由，5轮预热/10轮测量，纯JDK解析中位41.16→7.32ms，累计分配151,868,144→10,098,504字节。约141.8MB是累计瞬态分配差，不是手机峰值内存或解压秒数。
- 验证/风险：19组真实前后方法体fake-OS结果/有序trace一致；独立3个削弱root/NOFOLLOW/inode的负控被拒，5项缓存定向通过。64条存活路由增加有界对象，fake OS不是Android真实syscall/竞态证据。
- 回滚：只恢复该Java和新增测试；归档、完整摘要、事务、恢复格式不变。

### 3.4 空闲任务通知免桥请求

- 问题：[dsh-task-notifier](../app/src/main/assets/builtin-plugins/dsh-task-notifier/lib/index.js)没有活动任务/待上报终态仍每tick读凭据并context/claim。
- 修改：先判断真实工作，context延迟到需要时；保留活动心跳、控制领取、终态重试及owner/sequence/HMR。
- 宿主结果：同600逻辑tick，初始空闲HTTP/凭据读601→0，完成/取消后600→0；活动event600/claim600保持，首次需要时另1次context。这不是手机运行600秒。
- 验证/风险：完整进度夹具和独立4项并发/生命周期反方检查通过，旧原件在新空闲断言下预期失败。1Hz轻量timer和内存遍历仍在；没有零唤醒、实测省电/降温结论，未改电源策略。
- 回滚：恢复插件及测试，由统一构建负责人重生成/核验资产；不改任务数据。

## 4. 初次初始化规模与五种场景

修改前精确APK `ca0562e5…` 的六大归档：48,796成员、45,328普通文件、展开普通字节1,021,556,198（覆盖使最终净量略小）。DSH为34,327文件/595,400,166字节，其中18,501文件≤4KiB；base为7,222文件/294,712,627字节。小文件量是真实扫描结果；宿主扫描时间不是Android解压速度。

真实冷链：[ColdBundleTransaction](../app/src/main/java/com/deepseekharness/app/runtime/ColdBundleTransaction.java)→[ColdBundleExtractor](../app/src/main/java/com/deepseekharness/app/runtime/ColdBundleExtractor.java)。base/DSH早已双线程独立候选并行，随后Python/support/pnpm、工具及groups。Ubuntu configured-overlay-v1使用签名预置覆盖层及实际容器探针。prepared/publish两遍完整内容摘要保护不同切换边界，随后trial和健康回执继续执行，不能删检查或改用stat缓存证明提交。

| 场景 | 动作与边界 |
|---|---|
| 首装首启 | eligibleFresh只接收空私有环境；提取/完整摘要/发布/trial/健康均计入初始化，files committed只是中间事件 |
| 初始化后二启 | 查布局/标记、入口、descriptor/health；正常readiness读回执，不重展开所有归档。恢复活实例和新建guest分组 |
| 升级首启 | 比实际受管/attempted identity；同base10通常只stage DSH/插件，个人数据及Ubuntu原位，保留事务/trial/退出核验，不因APK版本直接重装 |
| 清Android缓存 | files中的环境/健康记录不应随cache消失；临时文件按规则重建，需核OEM清理实际范围，禁止生产清数据模拟 |
| 中断/失败恢复 | 保留cold intent/launch/publishing/committed，先恢复事务并核guest退出；非空失败树不当空根覆盖。旧失败槽保留，新槽可能重提取，不保证任意断点续解 |

旧包独立空根总115.042秒记录：base/DSH43.135秒，Python/pnpm2.149秒、工具1.063秒、Ubuntu覆盖层4.459秒、探针至解压完成0.558秒、prepared摘要10.391秒、publish摘要/切换10.222秒、trial记录35.753秒，另7.225秒未细分。它们是stage通知间wall time，不是某函数CPU。trial中native-ready→storage26.727秒发生在插件initialize入口标记之前，不能误称26.7秒磁盘写入。新43.763秒文件提交与旧115.042秒全初始化终点不同，不直接相减报收益。同台精确基准APK独立冷测速65.128秒是后续候选的对照起点，也不与旧115.042秒直接相减；39.316秒文件提交和21.750秒trial为嵌套阶段，剩余工作不能仅由两数相减认定为某个函数耗时。

### 4.1 精确基准与候选的同入口冷流程

| 计时边界 | 精确基准APK | 0243精确APK | 边界与结论 |
|---|---:|---:|---|
| 完整独立冷测速 | 65.128 s | 42.772 s | 观察少22.356 s；含完整提交、trial/真实renderer及退出，不是仅隐藏等待页 |
| base/DSH解压阶段 | 23.913 s | 13.177 s | 阶段事件之间wall time；Python等后续步骤另计 |
| 文件提交时点 | 39.316 s | 24.465 s | 从完整流程零点累计，含解压和两次hash/publish，不再与解压阶段相加 |
| trial recordCloseEnd | 21.750 s | 15.844 s | trial自己的子时钟，包含renderer/退出，嵌套于完整流程；不能与总计相加 |

两轮均保留完整内容摘要和publish、真实网页核验、数据会话重开及受管guest/launcher退出。候选确实少19,322个待解压文件，路由缓存也少重复分配，生产proot与proroot样本的鉴权等待较早前观察短；这些共同支持本设备该流程的实际工作与观测时间减少。固定执行顺序、温缓存和缺少重复冷样本使各修改的独立贡献无法精确分离，不能把22.356秒全部归因于某一优化或当作所有手机保证。Node隔离hook样本另见6.1；它未覆盖trial的全部等待，不预设storage之前等待的唯一根因。

## 5. 工作量、后台、延迟、隐藏分别记账

| 分类 | 本轮适用项 | 能说明什么 |
|---|---|---|
| 真减少总工作 | 少一次鉴权/同次prepare、重复路由解析及空闲桥请求 | 宿主调用/分配计数有证据，整机收益另测 |
| 转移后台 | Main后Web后台启动原已存在 | 不是本轮新增收益，并发仍可能争CPU/IO |
| 延迟首次使用 | context到实际任务才获取 | 空闲免做，有任务仍做，不称工作完全删除 |
| 等待界面变化 | Launch早导航，浏览器仍真实鉴权 | 导航快不等于backend/core ready提前 |

缓冲解压、短文件JNI、固定FD/目录缓存、小文件批量SHA和base/DSH并行早已存在，不算本轮新优化。配置中10秒probe预算不证明启动必等10秒；open-in-app实际按相关请求懒执行。源码有循环/重复不代表它已被测定为关键瓶颈。

## 6. 依赖与无效功能的决策

Zod合并已由统一负责人**纳入精确0243标准APK，包验证及部分生产手机路径通过，完整验收仍待完成**：root4.6.7→4.4.3并合并57份嵌套4.4.3，semver允许不等于语义等价。归档SHA-256为 `85b108dd8017dcfdc8bbfb7c87773ea78b00ada320fb64ae4105de959e6250b5`。实际候选少19,322普通文件、155,077,846展开字节及21,246,550 gzip字节，非Zod成员逐项未变。这是真实减少待解压工作量；归档变化量不等于最终APK体积变化，更不等于手机提速。精确APK实测292,159,963字节，相比314,315,600字节基准少22,155,637字节；APK还包含代码、其它资产与压缩布局变化，不能把全部包差都记为Zod gzip差。

选定实际schema/codec、ToolRuntime、CLI/profile及守卫夹具有通过证据，原过时pipeline夹具失败/准备超时保留。模块实例合并、root版本变化、用户插件、独立救援仍需最终门禁。原隔离评估发现旧救援锁会迫使打包两份DSH、可能增包约127.3MB；当前已由负责人同步[救援锁](../tools/recovery-runtime/lock.json)，共享资产生成已通过，解决的是该构建层风险。精确APK的恢复/完整资产检查已通过，确认所用共享映射及归档；独立应急真机的只读启动/页面/停止已另实测（8.2），AI对话及正式写修复仍未验；不能将生成、包检查和功能实测混为同一证据。

| 分类 | 例子与决策 |
|---|---|
| 已确认无效 | cleanupCompleted为空；去调用启动收益可忽略，不为减行数破坏接口/恢复 |
| 高度疑似 | 旧完整格式化writer/helper；pending读取仍有恢复意义，不整批删或清标记 |
| 需进一步验证 | ScreenSerialExecutor/ScreenTargetEvidence无普通生产调用，但需核测试/反射/JNI/外部入口 |
| 仍有用途 | DeviceFileCore字符串CLI、策略生成副本、旧事务读取、双flavor差异依赖；不误删 |

### 6.1 实际手机的隔离Node hook对照

主负责人取得同台原生proroot、`untrusted_app`域、同boot/脚本、相同三个hook模块且无invalid参数的隔离启动样本。两次都使用新鲜core-only profile，排除原生桥和用户插件，从原生Terminal进入容器后开始；不能与正式58.9/18.2/9.0秒或health trial混比。基准/候选后端鉴权分别5.065/2.705秒，插件树激活的loader await为4.483/2.157秒；这些是这个带观察器的样本，不是普通生产启动或完整前端可用时间。它支持依赖/模块加载在该样本中有可观等待变化，不足以单独证明全部差值由Zod引起。

候选1,597与基准5,010次load是**包含鉴权后5秒观察窗的累计hook事件**，不是唯一模块数、文件总数或纯启动模块数。没有不带hook的plain对照；hook/进程编排自身有成本，且固定基准后候选、暖缓存、样本有限。不得据此推广普遍因果或通用百分比，也不把该计数与按包名BFS的541个manifest计数混用。

本机分析 `runtime/isolated-proroot-paired-analysis.json`将其定位为插件树激活聚合等待：包含模块解析/读取/解析执行、服务注入与异步激活，尚无排他的CPU/IO分解或唯一插件归因。load/resolve累计duration可能重入，不能当互不重叠阶段求和。child exit0有证据，但不把它单独扩大为所有guest/launcher闭环；精确APK/descriptor仍以统一交付回执绑定。

## 7. CPU、内存、功耗与热量

目前有源码行为、宿主计数和本包前后台各约25秒手机短采样，**没有受控手机功率/温升前后对照**。USB充电、系统/其它应用、环境温度及ADB采样会干扰，不凭代码宣称已查明并解决发热。

| 阶段 | 源码已确认 / 待测 |
|---|---|
| 启动 | Node/Host解析与模块/插件、guest准备；宿主样本541次manifest/~1.06MB、约1362次exists，需测CPU/IO；Zod候选不减少按包名BFS |
| 空闲/任务 | 无任务桥请求已省，1Hz轻timer仍在；原生任务页初始化后也有1Hz维护，不能称零唤醒 |
| 虚拟屏核心 | 活跃显示约33ms检查ImageReader，新帧可分配bitmap；已有共享preview/帧缓存。实际开销取决帧产出，没有改采样，不声称解决闪烁/触控 |
| 后台 | eco默认false，策略可能持CPU/WiFi锁；eco须新鲜idle等条件；独立ADB有有界probe/退避。保留连续运行和UNKNOWN/维护保护，不擅自砍能力当节电 |
| 日志/集成 | bounded历史重写和约2秒活动快照路径存在，尚未测其主导锁竞争/IO/发热，不据此盲改 |

后续按启动、空闲、核心、后台记录CPU、RSS/PSS、线程/进程数、读写字节、GC、thermal与充电，尽量同设备/配置多次给中位数及范围。低电量不中断授权验收；关机/权限丢失保留中断结果，不补造数据。

### 7.1 当前精确APK的前后台短采样

`resource-observation-summary.json`汇总前台25.000秒/6点、后台25.015秒/6点；均为同一组5个应用UID进程，出生身份核对一致，没有模型或工具请求。它只观察当前APK，不是基准包/候选的功耗A/B。

| 原始指标 | 前台空闲 | 后台空闲 | 解释边界 |
|---|---:|---:|---|
| 同出生身份进程CPU ticks增量 | 253 | 228 | 保留原始tick；未假定CLK_TCK/HZ，不换算CPU秒或使用率 |
| read_bytes增量 | 40,960 | 8,192 | 该UID进程内核IO计数，非所有系统/renderer IO |
| write_bytes增量 | 831,488 | 958,464 | 后台仍有实际写入；不能称无后台工作 |
| VmRSS相加范围（KiB） | 776,524–802,256 | 786,904–788,056 | 共享映射可能重复计入，不是唯一物理占用或整应用PSS |
| 线程数相加范围 | 107–107 | 106–106 | 仅该5进程短窗，不能据此证明长时无泄漏 |
| nativeMain PSS首/末（KiB） | 223,699→194,182 | 190,506→190,369 | 只nativeMain，不是整进程树，也不能把首末下降归为优化收益 |
| 电池温度 / 电量 | 35.9–36.0°C / 27% | 35.9–36.0°C / 27% | USB充电下的电池温度，非CPU结温/室温；无省电或降温结论 |

浏览器renderer若运行在单独UID则未包含；其它应用、系统负载、充电与采样开销未受控。该窗口未发现应用UID进程集合变动，但不足以证明无泄漏、长时稳定或发热根因已解决。CPU/GC/线程/IO在真实核心任务、锁屏/长后台、不同供电和更长窗口的完整矩阵仍待测。

## 8. 独立验证与回滚

独立审查分别接受四项窄优化的宿主范围，整机结论继续按已完成路径和待验项区分：单鉴权/401/取消及旧源负控；任务owner/HMR/终态重试与反方并发；真实路由方法体trace和3个削弱守卫负控；launcher内容修改/链接事实及下次重验证。fake OS、历史classpath重编译、loopback服务都不冒充Android全栈。

最终须测请求/实际proot/proroot、首启/后二启、升级识别、缓存失效、中断、首次说明保存、配置读写、目录选择、插件、正常停止及真实核心请求。UNKNOWN不重发业务/换通道，进程创建不显示成核心可用，失败与修正分别保留。

回滚按每项精确摘要/范围恢复对应生产文件与测试，再统一生成资产；已纳入的依赖归档须连同lock/recipe/descriptor和救援锁、受管事务一起核验回滚，不手工覆盖用户根或回滚对话。历史APK/Low172和数据保留，不卸载/清数据/删外置工作区。

### 8.1 最终软件与精确包检查快照

本机 `software-summary.json` 为 **PASS_SCOPED_SEGMENTED**，不是一次不中断的全绿结果：两版各184类/1,143项，failures/errors均0；Standard跳过3项、Low跳过76项。原两项旧fixture失败保留，修正后单类10项在两版补跑，合并结果不冒充原始一次通过。Release Lint为0 error/fatal，warning分别249/162；警告和skip不是零。

32项宿主门禁由28项新跑和4项精确源代码及raw/managed字节匹配后复用证据组成，不写成32项全新执行。APK identity、manifest、signature、recovery、licenses及full-assets六项检查通过；首轮stale pointer输入失败保留，实际精确原始证明补跑后才收口。native字节逐项未变，引用的是字节一致而非重新做所有原生设备矩阵。构建为增量；Low仅软件检查，不安装手机。

精确标准APK：SHA-256 `d77bf985a5f9ec49be0667625894bcd16c5f3a23df2210a837527af8a172a947`，292,159,963字节；`dsh.android.client` / code190 / `0.2.1-rc.2-20261022.0243-dsh0.2.1-rc.2` / E7E3 / 非调试。源码ZIP及私有上传仍由最终交付回执绑定，包通过不等于所有功能、长时或功耗通过。Root递归READ的子链接检查缺口仍未修复，见[安全模型](security-model.md)；本轮没有用真实敏感文件验证利用。

### 8.2 精确新独立应急的手机边界

本轮新应急runtimeId为 `41c4ba0813eabf310cba14a5020d80bc7e01a7604bdca8aea02de961eef1fb20`。`candidate-ready-recovery-summary.json`记录READY；主负责人在实际手机确认独立运行根、工具核验、HTTP鉴权和RecoveryWeb空白工作区。新欢迎说明点击Continue可关闭，重载该应急页面不再出现预览说明；这只覆盖此次应急profile/页面重载，不扩大到所有设备或普通生产profile。

没有模型凭据时仍显示添加API Key，这是配置提示，**不算AI对话验收成功**。未确认任何正式环境写修复。正常停止后，`candidate-stopped-recovery-summary.json`记录STOPPED且closed落盘，现场及诊断原件保留。只读应急启动/网页/停止证据不能替代已确认写修复的事务/授权矩阵。

### 8.3 三次前后台返回的有限检查

`candidate-background-return.json`记录三次间隔执行Home→桌面启动器返回。实际 `topResumedActivity` 均为WebPreviewActivity，应用UID进程集合及身份保持一致；没有另建Web代次的证据。它覆盖这三次返回，不等于强杀恢复、所有ROM/PiP/旋转或长时矩阵，也没有模型请求。

首轮helper错读本系统不存在的 `mResumedActivity` 字段导致空断言，原helper/失败保留；改读实际 `topResumedActivity` 后三轮通过。这是观察器字段错误，不能写成应用发生闪退后被修复。原观察耗时含调度和人工间隔，不报告成精确热启动延迟。

### 8.4 停止收尾与有限文件比对

本轮手机最后正常停止，仅新包原生主进程保留，选择维持proot；从手机读回的安装APK SHA-256仍与本地精确 `d77bf985…` 包一致。不是通过强杀或清数据制造“退出”。旧包未执行本轮修改。

`data-comparison-summary.json`只核对新包本次fresh正式 `.dsh` HOME所选受管helper/cache的49个普通文件：前后均49，42字节相同，0增加/0删除，7项变化已分类。两份registry/proofs精确对应新签名APK；3份pyc仅前16字节头变化、body相同；repair日志严格追加；依赖链接cache只变managedAssetId/stamp。这些是维护派生文件，**不是全部个人配置、会话、插件或外置工作区的数据完整性比对**，不能扩大为全部用户数据无变更结论。

## 9. 最终验收待补

本地精确标准APK已按上述范围验过；私有交付以[同日版本报告](releases/v0.2.1-rc.2-build190-performance-20261022.md)和统一回执为准，公开上传仍暂停。以下未完成范围不意味着故障已消失：

- 精确APK身份及软件范围已在8.1节确认；最终交付副本、源码ZIP、私有commit与附件可得性仍待回执。
- 已有实际proot/proroot三轮启动及壳ready；进一步严格匹配顺序/缓存、细分准入/浏览器与真实核心结果仍有缺口。
- 同入口新空根完整流程已对照，文件提交/trial分列；中断、不同升级路径和缓存失效等完整矩阵未完成。
- 已覆盖独立应急只读启动/网页/停止及Node隔离hook样本；应急AI对话/写修复、其它插件及安全/业务路径仍有缺口，保持8.1中的分段、skip、原失败及复用边界。
- 当前APK仅有7.1短窗CPU/内存/IO；真实业务、长时/中断和功耗矩阵未完成，无功率/温升对照时保留未测，不称省电降温。
- 私有库最终commit、完整源码ZIP/APK可得性与摘要；公开发布继续暂停。

本机详证据在build190-perf-20261022的architecture/runtime/initialization/energy/dedup/validation及负责人设备回执索引；仅发布适合分发的汇总和源/测试，不发布手机现场。
