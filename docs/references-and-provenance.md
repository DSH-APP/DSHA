# 资料依据、上游来源与可验证范围

本文是 build190 的事实索引，初版以F10为参考；Root源码与边界的补充核对日期为2026-10-22，范围见第5.1节，其余上游资料仍按2026-10-21的核对范围阅读。
用途是让维护者找到原始资料、版本锁和校验入口，不是官方背书或安全认证。
初版编写只阅读源码、锁文件和上游资料；第5.1节另记后续已执行的隔离宿主检查。文档更正本身不重跑构建、不操作su设备或发布流程。

## 1. 项目与上游之间的关系

| 项目 | 本分支中的角色与依据 | 不能据此声称 |
|---|---|---|
| [ym2025szz/DSHA-prerelase](https://github.com/ym2025szz/DSHA-prerelase) | 当前 README 指向的私有维护与预发布分发仓库；仓库名按实际拼写保留 | DeepSeek 官方 Android 客户端或官方认证分支 |
| [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) | DSHA 工程来源；本地 AGENTS 记录本轮从 build170 基座迁入后续改动 | 当前维护分支与该仓库每个分支、Release 或网站内容完全相同 |
| [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | DeepSeek 官方 Harness 上游；本项目将其 npm 运行时装入 Android 内的 Linux 环境 | 上游维护或批准了 DSHA 原生壳、授权策略、容器补丁或本地 APK |
| [mexiaosqwq/dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) | 第三方移动网页插件上游；本地按精确提交引入后再应用维护差异 | DeepSeek 官方移动 UI，或完全未经修改的上游包 |

工程关系以 [README](../README.md)、[AGENTS](../AGENTS.md)、锁和实际差异共同说明。
公开项目介绍能证明项目公开声明，不能证明某个本地二进制来自某条源码提交。
私有仓库匿名访问可能显示 404；这不证明仓库不存在，也不替代登录后的权限和附件核验。
本文没有检查私有仓库的远端提交或附件状态；实际交付应另核精确 Release 和摘要。
维护者署名、上游组织名、使用其 API、保留许可证，都不等同于组织授权或审计背书。

## 2. 三类依据分别回答什么问题

| 依据 | 能回答 | 不能代替 |
|---|---|---|
| 官方 API / 上游项目文档 | 平台接口、约束、上游自述及许可入口 | 本项目已经正确实现、某 ROM 已兼容 |
| 当前源码、锁与实际资产 | 本次选择的版本、路径、摘要和本地实现契约 | 真机行为、所有依赖来源均已闭合 |
| 绑定 APK 的测试与验收记录 | 所记录环境和步骤中实际观察到的结果 | 未运行的设备、业务、长时或兼容性矩阵 |

阅读时应先确定主张属于哪一类，再选择相应依据。
例如：官方支持 VirtualDisplay，不意味着普通应用可任意注入输入；
JUnit 检查了拒绝分支，也不意味着某台手机上的原生触摸已成功。
F11当时暂停设备验收，不应把历史包或软件结果转记为F11手机验收；当时软件与产物身份见[F11记录](releases/v0.2.1-rc.2-build190-fix11-20261021.md)及其统一回执。本轮已获新的性能真机验收授权，实际执行结果必须另按最终APK记录，不能从旧文档推导通过。
本文没有第三方独立安全审计、Google Play 审核或任何机构认证的证据。

## 3. 当前版本与固定输入索引

下列值从当前文件读取；依赖升级时应先更新锁，再更新本页。
版本号用于辨认组件，最终字节仍以对应 SHA / integrity / descriptor 为准。
本次未重新下载 npm、源码归档或工具链原件重做摘要；深层提交/标签页无法读取时，精确值只按本地锁陈述，不标作远端字节复核通过。

| 组件 | 当前本地选择 | 原始锁或配置 |
|---|---|---|
| DSH 主包 | `@deepseek-ai/dsh` `0.2.1-rc.2` | [package.json](../tools/dsh-runtime/package.json)、[package-lock.json](../tools/dsh-runtime/package-lock.json) |
| 移动插件 | `3.2.1`；实际引入 main 提交见下文 | [mobile-plugin-upstream.lock.json](../tools/mobile-plugin-upstream.lock.json) |
| 受管环境 | Ubuntu 24.04 arm64；DSHA `baseVersion=10` | [runtime-descriptor.json](../app/src/main/assets/runtime-descriptor.json)、[Ubuntu 包锁](../tools/ubuntu-tools/packages.lock.json) |
| 运行时工具 | pnpm `10.34.5`，certifi `2026.7.22` | [runtime-tools.lock.json](../tools/runtime-tools.lock.json) |
| 默认 / 可选引擎 | proot / proroot；后者本地声明 v1.2.8 | [AGENTS](../AGENTS.md)、[THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md) |
| Shizuku API/provider | Standard `13.1.5`；Low `12.2.0` | [app/build.gradle](../app/build.gradle) |
| Low GeckoView | `143.0.20251003115653`，arm64-v8a | [app/build.gradle](../app/build.gradle) |
| Android 身份 | `dsh.android.client`，versionCode `190`；具体 dated versionName 看构建配置 | [app/build.gradle](../app/build.gradle) |
| 独立应急环境 | DSH `0.2.1-rc.2` / base `10`，独立归档摘要 | [recovery-runtime/lock.json](../tools/recovery-runtime/lock.json) |

### DSH 包来源

当前 npm lock 的主包 resolved 为：
[registry.npmjs.org 的精确 0.2.1-rc.2 原包](https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.1-rc.2.tgz)。
它的锁内完整性值为：

```text
sha512-tvY+yhfKRifthbqPe/OK0AYxM5ZkvqfYq/jMYgJcEUxiRRL1Vwra3Ti5TB6AXgSWQdiPHYh/Vp6SJa9yHwMNuw==
```

锁同时保留实际依赖图的 resolved、integrity 和声明许可，不能只核主包名称。
DSHA 对安装、桥接、网页与存储有受管适配，不能把运行归档称为原封不动的官方安装。
修改入口见 [prepare-dsh-runtime.py](../tools/prepare-dsh-runtime.py) 和 [runtime-patches.json](../app/src/main/assets/runtime-patches.json)。

### 移动插件来源

当前锁区分发布标签和后续 main 输入：

- 发布标签记录为 `v3.2.1`，releaseCommit 为 `1844bf21db47a9b942275b8118988e3f6718cf4b`。
- 实际引入提交为 [`10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4`](https://github.com/mexiaosqwq/dsh-web-mobile/tree/10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4)。
- 锁内上游源码归档 SHA-256 为 `9629e585127dd8dd2149bb9f188cef957e5d0f5d4ae57d07f6f881748940e1e5`。
- 上游 client 与本地最终 client 分开记载；最终成员摘要见当前 THIRD_PARTY_NOTICES 与 descriptor。

不要把“包内版本 3.2.1”解释为“只包含该标签原件”。
本地差异由 [apply-mobile-client-patches.mjs](../tools/apply-mobile-client-patches.mjs) 及 [mobile-server](../tools/mobile-server) 管理。
上游升级是否吸收本地补丁，需要逐项核对，不能整批叠加旧补丁。

## 4. 许可与仍未闭合的来源边界

[LICENSE](../LICENSE) 是 DSHA 自有代码的 MIT 许可，保留原版权署名。
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 从当前锁和实际资产生成，逐组件说明来源与许可。
包内许可副本在 [assets/licenses](../app/src/main/assets/licenses)，移动插件另保留自己的 LICENSE。
第三方组件不会因为 APK 顶层 LICENSE 是 MIT 而全部变成 MIT。
许可全文、包锁和二进制来源是三项不同材料；补全一项不能替代另外两项。

| 组件/来源 | 直接 primary 入口与本地依据 | 已知边界 |
|---|---|---|
| proot | [Termux proot](https://github.com/termux/proot)；[Low 构建脚本](../tools/build-low-proot.py) | Low 锁定 v5.1.107.92；Standard 原二进制精确 revision 仍未核实 |
| proroot | [coderredlab/proroot](https://github.com/coderredlab/proroot)；本地 THIRD_PARTY_NOTICES | 本地 v1.2.8 声明为上游专有二进制，不能声称公开源码完整重建或任意改分发 |
| DSHA proroot 兼容层 | [独立兼容层说明](../tools/proroot-compat/README.md)、[ABI 锁](../tools/proroot-compat/abi.lock.json) | 自有 shim/entry 的源码与锁不等于 vendor 五库源码或来源证明 |
| Termux 终端 JNI | [Termux app](https://github.com/termux/termux-app)；[本地上游许可例外](../tools/termux-jni/LICENSE.upstream.md) | terminal 部分许可例外不能外推整个 Termux 工程 |
| Ubuntu 包 | [Ubuntu Security Notices](https://ubuntu.com/security/notices)；本地 Ubuntu 包锁与 rootfs 内版权文件 | USN 是核对入口，不表示已证明当前归档没有漏洞 |
| 其他原始 JNI | THIRD_PARTY_NOTICES 的“实际入库 JNI 成员与来源边界” | libandroidshmem 的原件来源/许可、部分 Standard proot/talloc 的精确版本仍有未核实项 |

Low proot 源码 ZIP 的本地固定 SHA-256 为：
`29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135`。
这是特定输入的校验值，不能用来证明 Standard 原 proot 库来自同一构建。
CHANGELOG 中 DSHA `v1.1.6` 是壳工程的历史版本，不能当作 proroot 上游版本。
对来源未知项应保留 unknown 和待核材料，不应补写猜测的许可证或提交号。
闭源组件的使用与分发需依据其实际许可条款；本页不提供法律授权结论。

## 5. Android、Shizuku与Root上游资料映射

下面的直接链接由相应平台或项目维护；每行只支持所列接口主张。
Android 文档会随版本更新，实际行为还取决于设备 API、targetSdk、权限与系统实现。
引用公开 SDK 时不要顺带把反射使用的内部成员描述为稳定公开 API。

| 主题与 primary 链接 | 支持的主张 | 对 DSHA 的限制与核对点 |
|---|---|---|
| [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay) | 虚拟显示具有显示与 Surface 生命周期，需要释放 | 不保证任意应用可在其中启动或接受输入；看 Core/DisplaySession |
| [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager) | 创建显示及 flags 的平台契约 | flags 的存在不等于调用 UID 有相应权限；看 VirtualScreenPolicy |
| [InputManager](https://developer.android.com/reference/android/hardware/input/InputManager) | 公开输入设备管理 API 的边界 | 文档不能给特权事件注入背书；反射和 input 命令另行检查 |
| [非 SDK 接口限制](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces) | 非公开接口可能受限制且发生变化 | 反射查找成功不等于 invoke 成功，不能推断 OEM 通用兼容 |
| [ADB](https://developer.android.com/tools/adb) | client/server/adbd、shell、设备连接和无线配对的机制 | 不表示 shell 有 Root；配对与连接端口不能由 Web 端口推导 |
| [Shizuku 使用指南](https://shizuku.rikka.app/guide/setup/) | Root、无线调试、电脑启动等使用条件 | 重启/系统限制会影响服务；不提供永久运行或所有权限保证 |
| [Shizuku API 与开发指南](https://github.com/RikkaApps/Shizuku-API) | Binder 接收/死亡、请求权限与 UserService 接入 | SDK 依赖已加载不等于 Binder 存活或用户授权；看 ShizukuShell |
| [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) | 用户启用的无障碍服务、窗口检索与交互能力 | 系统返回的树可能不完整；Canvas、密码、安全窗口另有限制 |
| [SAF / DocumentsProvider](https://developer.android.com/guide/topics/providers/document-provider) | 通过用户选择和 URI 授权访问文档 | URI 不是普通绝对路径；Provider 可远程、取消或拒绝；看 DocumentStreams |
| [MediaStore](https://developer.android.com/training/data-storage/shared/media) | 共享存储集合、ContentResolver 和版本相关访问规则 | 不等于所有文件权限；导出应核实际 URI、写入和发布结果 |
| [MediaRecorder / 麦克风权限](https://developer.android.com/media/platform/mediarecorder) | 音频采集需要相应声明、运行时许可及系统条件 | Web 请求还要核同源与当前页面；不代表允许摄像头或屏幕音频 |
| [前台服务](https://developer.android.com/develop/background-work/services/fgs) | 用户可感知任务、通知及后台启动限制 | FGS 不是不被终止保证，Stop 仍需证明实际进程退出 |
| [PiP](https://developer.android.com/develop/ui/views/picture-in-picture) | Activity 进入/退出画中画及生命周期适配 | 不保证网页缩放缓存已复位；需核当前 PiP 状态与 Insets |
| [软件键盘控制与动画](https://developer.android.com/develop/ui/views/layout/sw-keyboard) | IME 可见性与 Insets 动画协同 | 不证明本机键盘帧率；UI 实现还需覆盖取消、失焦与切页 |
| [WindowInsetsAnimation.Callback](https://developer.android.com/reference/android/view/WindowInsetsAnimation.Callback) | 动画回调及进度处理契约 | 回调存在不代表可以忽略最后布局提交或页面身份 |
| [WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview) | 在 Activity 内嵌网页及 WebView 基本能力 | 系统提供者版本随设备变化；JavaScript bridge 必须有自身来源校验 |
| [Mozilla GeckoView](https://mozilla.github.io/geckoview/) | 可随应用分发的 Gecko 嵌入引擎 | 它与系统 WebView 的版本、权限回调和行为不能互相代验 |

项目对这些接口的组合契约由当前代码定义，入口见：
[虚拟屏架构](virtual-screen-architecture.md)、[设备权限架构](device-permissions-architecture.md)、
[项目总手册](project-architecture-handbook.md) 和 [运行时/数据/插件架构](runtime-data-plugin-architecture.md)。
例如最新帧、Core UUID、节点一次使用与 UNKNOWN 不重放，是 DSHA 的额外约束，不能冒称 Android 官方协议。
系统接受一次事件或节点 action，也不等于应用业务结果已验证。

### 5.1 Root管理器源码：固定核查范围

2026-10-22对维护源码 `140c7ba277fc2a91a9b8fd8cb5a24bc35a5c41c5` 的补充核查读取了下列固定上游文件。提交号固定阅读对象，不表示它是用户手机所装版本或经过DSHA真机认证；这不是对上游整个项目的安全审计。

| 上游与固定提交 | 本次读取的主要文件和机制 | 对DSHA的含义与未证范围 |
|---|---|---|
| Magisk [`af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b`](https://github.com/topjohnwu/Magisk/tree/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b) | [`native/src/core/su/daemon.rs`](https://github.com/topjohnwu/Magisk/blob/af8c2566b07cfa0c2d4b145b88316a07f7d2bb6b/native/src/core/su/daemon.rs)、同目录`su.cpp`、`connect.rs`、`mod.rs`：调用者策略、su参数、身份、shell执行与mount namespace | su路径可见不等于授权；实际命令能力还受身份、capabilities、SELinux与namespace影响，未做该提交的设备矩阵 |
| KernelSU [`6d31a61370838380177dc5c46fb838f6ba7d639a`](https://github.com/tiann/KernelSU/tree/6d31a61370838380177dc5c46fb838f6ba7d639a) | [`kernel/feature/sucompat.c`](https://github.com/tiann/KernelSU/blob/6d31a61370838380177dc5c46fb838f6ba7d639a/kernel/feature/sucompat.c)、`kernel/policy/allowlist.c`、`app_profile.c`、`kernel/supercall/perm.c`、`userspace/ksud/src/su.rs`与`defs.rs`：授权UID、stat/access/exec重定向、profile与su处理 | su可由内核兼容层提供，profile可选择非0 UID；九个路径字符串和metadata单测不能证明兼容 |
| APatch [`e8e4651c16fb5075882a25813c8732285fdb3505`](https://github.com/bmax121/APatch/tree/e8e4651c16fb5075882a25813c8732285fdb3505) | [`apd/src/supercall.rs`](https://github.com/bmax121/APatch/blob/e8e4651c16fb5075882a25813c8732285fdb3505/apd/src/supercall.rs)、`apd.rs`、`cli.rs`、`package.rs`、`app/src/main/cpp/supercall.h`：授权列表、目标身份、su参数与配置路径 | `/data/adb/ap/su_path`可配置；DSHA未实现任意自定义路径发现，未核用户所装管理器/内核组合 |
| KernelPatch [`052c73f93dbb84861218086e60a9962c48ff3f7f`](https://github.com/bmax121/KernelPatch/tree/052c73f93dbb84861218086e60a9962c48ff3f7f) | [`kernel/patch/common/sucompat.c`](https://github.com/bmax121/KernelPatch/blob/052c73f93dbb84861218086e60a9962c48ff3f7f/kernel/patch/common/sucompat.c)、同目录`supercall.c`和`kernel/patch/include/sucompat.h`：授权UID、配置/旧su路径拦截 | 独立选取的上游源码样本；未证明该提交就是上述APatch APK或用户设备包含的KernelPatch版本 |
| Toybox [`6a0c0ef5eeb2c43e4feef9781398c1718b8bddcb`](https://github.com/landley/toybox/blob/6a0c0ef5eeb2c43e4feef9781398c1718b8bddcb/toys/posix/grep.c) | `toys/posix/grep.c`：`-R`递归跟随软链接 | 用于核对递归READ子链接缺口的命令语义，未识别用户ROM中Toybox的实际字节或版本 |

DSHA通过[RootShell](../app/src/main/java/com/deepseekharness/app/RootShell.java)、[RootShellMain](../app/src/main/java/com/deepseekharness/app/RootShellMain.java)、[DeviceShellPolicy](../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java)及[DeviceShellExecutor](../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java)消费su并施加自身受管入口约束。上游授权的是调用身份或profile，不替DSHA实现短信、媒体路径或虚拟屏守卫；这些守卫仍须对照本仓库代码和执行侧回归。

历史文档没有足够的固定提交、文件及结论映射来证明此前已完成三管理器的可追溯源码核查；本节记录此次有限补查，不追认历史实测。已确认主调用链不能推出所有保护完整：[权限架构](device-permissions-architecture.md)第6.2节记载的递归READ子链接覆盖缺口在本节核查基准中仍未修复，尚无真实短信读取或ROM利用证明。已有候选单测、16个Java干运行计划、27项设备策略模拟与29项隔离ADB流程只证明各自层面，不证明真实su授权、UID转换、APK可见性或三管理器兼容。

## 6. 运行时与工具链 primary 资料

| 资料 | 支持的主张 | 本地锚点与局限 |
|---|---|---|
| [DeepSeek Harness 上游](https://github.com/deepseek-ai/deepseek-harness) | 官方项目与使用入口 | 精确安装内容由 npm lock 和本地补丁决定，不能追随 main 浮动 |
| [Node.js Releases](https://nodejs.org/en/about/previous-releases) | Node 主版本生命周期与支持政策 | CI 选择 24 只锁主版本；原归档身份仍需实际输入摘要 |
| [pnpm 10.x 配置](https://pnpm.io/10.x/settings) | CLI、环境和配置来源及包导入选项 | 要读对应版本文档；当前默认站点可能已显示其他主版本 |
| [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) | Wrapper 分发版本与摘要核验机制 | 当前网页版本不是本项目版本；以 properties 的 9.3.1 为准 |
| [Android 构建工具发布资料](https://developer.android.com/build/releases) | AGP/Android 构建工具的官方版本与兼容说明入口 | 工具链兼容仍需结合当前配置，不由一条链接自动成立 |

当前 [ci/toolchain.lock.json](../ci/toolchain.lock.json) 声明：

```text
Java 17        Node 24        Python 3.12
Gradle 9.3.1   AGP 9.1.1
android-37.0   build-tools 36.0.0   NDK 26.3.11579264
```

[ci-toolchain.py](../tools/ci-toolchain.py) 将锁与 Gradle、compile/targetSdk、NDK 和 Wrapper 配置交叉核对。
[gradle-wrapper.properties](../gradle/wrapper/gradle-wrapper.properties) 的 distributionSha256Sum 为：
`b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`。
这锁定 Gradle 分发 ZIP，不等于锁定整个 SDK、Maven 缓存或本机 JDK 的所有字节。
[ci-fast](../.github/workflows/ci-fast.yml) 与 [ci-package](../.github/workflows/ci-package.yml) 使用固定 action commit。
工作流配置可审查，但“仓库包含 CI”不表示某一远端 commit 已实际运行并通过。
完整 package 工作流输出无签名检查产物；正式 E7E3 签名身份需对最终 APK 独立核验。
签名私钥、keystore、令牌和开发者 local.properties 不应作为源码发布材料。

## 7. Git 源码、完整源码 ZIP 与 APK

Git 保留应用源码、资源、测试、锁、必要脚本和已明确说明的小型预编译依赖。
大型 rootfs、DSH、recovery 等生成归档不进入 Git；APK 使用 Release 附件分发。
完整源码 ZIP 则按 [BUILD](../BUILD.md) 包含当前 recipe 所需固定原始输入，直接展开到项目路径。
“完整源码 ZIP”在此指可构建项目和固定输入，不宣称每个闭源/vendor 二进制都有完整源码。
不能只将生成后的 APK 拆开，便声称已恢复全部原始 rootfs 和构建输入。
例如打包压缩/拆分后的 offline-rootfs 与原始 rootfs 输入不是同一文件。

| 本地材料 | 负责约束什么 | 不能替代什么 |
|---|---|---|
| [ci-assets.py](../tools/ci-assets.py) | 输入清单、打包/获取时逐项摘要 | 自行猜测缺失归档或从不同版本混取 |
| [ci-build-inputs.lock.json](../tools/ci-build-inputs.lock.json) | 未被 descriptor 输入覆盖的固定原始构建输入 | vendor 源码来源证明 |
| [managed-runtime-inputs.json](../app/src/main/assets/managed-runtime-inputs.json) | 实际受管安装输入与 archive recipe | 用户数据或授权快照 |
| [runtime-descriptor.json](../app/src/main/assets/runtime-descriptor.json) | 当前受管输入、launcherInputs 和 runtimeId | 仅靠 APK versionName 判断数据格式兼容 |
| [recovery lock](../tools/recovery-runtime/lock.json) | 独立应急环境输入摘要 | 借当前正式 rootfs 充当独立救援依赖 |
| [backup-dependencies.lock.json](../tools/backup-dependencies.lock.json) | 备份依赖版本与 JAR 摘要 | 备份完整性、恢复成功或全部数据格式已核验 |

源码内容、输入包、最终 APK 需要分别保留摘要及来源，不用一个版本字符串串联所有证明。
输入字节未变可以在逐项核对后继承其既有内容证据，但不能写成重跑测试或真机验收。
公开源码材料应剔除设备日志、截图、会话、凭据和历史私有审计包；未知来源项仍须诚实声明。

## 8. 如何复核一个具体交付

1. 从已授权的实际 Release 取得源码 ZIP、APK 和对应摘要，先校验整包字节。
2. 核仓库/Release 身份与所选版本，区分本维护分支与原 DSHA 仓库。
3. 读当前 BUILD，核工具链锁、Wrapper 分发摘要及实际工具版本。
4. 核完整输入是否按正确项目路径展开；缺归档应报告，不能绕过完整资产检查。
5. 核 npm resolved/integrity、mobile 精确 commit、local patches 和原始许可副本。
6. 对当前源重算 descriptor / notice，核实际输入及生成结果，保存独立输出。
7. 检查最终 APK 包名、版本、非调试标志、签名、实际归档和 DEX/manifest，不只读源文件。
8. 将软件、实际 APK 与设备验收分别记录，保留失败、跳过、未执行和继承范围。

上面是维护者复核流程，本页编写没有执行这些构建、安装或设备步骤。
机器校验能提高可核对性，不保证上游无漏洞、不保证隐藏 API 稳定，也不能补齐缺失来源材料。
对外陈述“通过”时应给出具体对象、环境和范围，而不是泛称已安全、已认证或全兼容。

## 9. 更新引用与版本的规则

上游升级、系统 target/API 调整、引擎变更、许可变化或分发方式变化时，需重新核对此页。
对版本主张优先使用锁、不可变提交和精确原包；可变文档页注明核对日期和适用版本。
重定向或 404 应检查官方导航，不能把搜索摘要、转载或非官方教程改作 primary 依据。
官方文档若迁移至 Compose，仍须确认当前 Java/View 实现所用接口，不应照抄新示例。
引用的支持范围发生变化，应同步修改主张，不只是替换链接。

建议在变更说明中分别记录：

- 上游来源 URL、版本/commit、下载原件摘要和许可变化。
- 本地补丁保留/退休清单，以及最终资产与输入 recipe 的变化。
- 受影响代码和回归入口、实际执行结果、跳过原因与待验证设备范围。
- 已知未知项是否补齐；没有新证据时继续保留原来的来源和兼容性限制。

架构变更还应同步 [ADR 0003](adr/0003-virtual-screen-lifecycle-and-input.md) 等相应决定记录。
文档补充不改变 APK 字节，也不能追认此前未做的测试或第三方审计。
