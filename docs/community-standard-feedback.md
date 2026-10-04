# 对 dsh-community-standard v0.15 的反馈（DSHA 视角）

> 历史草稿：下方设备、Gecko 与 Android 8+ 描述对应编写时范围，不代表 build154。
> 当前 Standard 使用系统 WebView/API30+，Low 使用兼容运行时与 Gecko/API23+；实际验证范围见当前交付报告。

> 待发送到 [omdsh-dev/community](https://github.com/omdsh-dev/community) issue。
> 状态：草稿，未发送。

## 先说一件事：我们暂时**不能**提交 Host Descriptor

按 [spec/host-descriptor.md](https://github.com/oh-my-dsh/dsh-community-standard/blob/main/spec/host-descriptor.md) §2.4：

> 宿主**必须**只声明自己实际实现并能保持语义的条目 —— **不许声明「大概支持」**。

DSHA 目前一个契约坐标都没有实现：我们的插件走的是 dsh 原生的 Cordis patch + bundle
机制，没有 `commands.dsh` 的 flat action leaf、没有宿主管理的 `storage.dsh`、
也没有 `messages.dsh` 的版本化信封。按规范写下去只能是 `capabilities: []`，
那既没有信息量，也容易被误读成「已经接入标准」。

所以这份反馈只提供**证据**，不领认证。等真的实现了适配层再提交 Descriptor。

（顺带说：规范把「诚实」写进 §2.7 和五态不得互相升级，这一点很对我们的胃口 ——
我们自己这轮就栽在「以为有地方处理、实际没有」上，详见下面对原则 5 的支持。）

---

## DSHA 是什么

**DeepSeek Harness 安卓启动器**（[qiannianhuanxiang/DSHA](https://github.com/qiannianhuanxiang/DSHA)）——
APK 内置容器运行时 + 离线 Ubuntu 24.04 arm64 rootfs，rootfs 内跑 Node 24 + 官方 dsh，
Web UI 由 GeckoView 呈现。免 ROOT、免 Termux、arm64 + Android 8+。

按 RFC 0001 §3.2 的分类，DSHA 同时是 **Web UI 宿主**和**启动器**，
而且据我们所知是目前唯一的**移动端**宿主。

这个身份是这份反馈的全部价值来源：**我们踩的坑里有一半是桌面和终端宿主永远遇不到的。**

---

## 对 §9 第 1 问的回答：有第四项，而且没有它移动端做不成

> v0.15 三个契约坐标的选择与裁剪是否合适？有没有「没有它这件事做不成」的第四项？

**有：执行环境的硬约束声明。**

不是 UI 契约（那归 RFC 0002），不是权限（那不构成安全边界），
而是「插件对运行环境可以做哪些假设」。

现有 Descriptor 的 `execution` 只有 `environment: "node"` 和
`trustMode: "trusted-in-process"`。对桌面宿主这够了 —— Node 就是 Node。
但在移动端，同一个 `"node"` 底下藏着四个会让插件直接炸掉的差异：

### ① 宿主随时会消失，而且是常态

Android 8+ 的前台服务契约要求 `startForegroundService` 拉起的服务 5 秒内
必须 `startForeground()`，否则系统抛 `ForegroundServiceDidNotStartInTimeException`
**强杀**。系统内存压力下也会直接回收。

RFC 0001 §3.6 已经写了「崩溃、断电、强杀时不保证 deactivate 调用送达，
插件清理必须设计为可重复」—— 方向完全正确。但**桌面上这是异常路径，
移动端是日常路径**。插件作者需要知道这个概率差异，才会真的把清理写成幂等，
而不是写完注释「正常情况下会走到这里」。

我们自己吃过这个亏：一次「点重启十秒后闪退」的排查里，
系统强杀不产生 Java 异常，crash.log 是空的，我们查了五个版本才定位。

### ② 文件系统语义受限，而且不是权限问题

- **硬链接不可用**：Android 私有目录禁止真硬链接。这让 pnpm 的
  content-addressable store 策略整体失效，只能强制 `package-import-method=copy`。
- **符号链接时好时坏**：容器内可用，从 Android 侧操作同一份 rootfs 则不一定。
  我们为「内置插件安置」改了六个版本（`Files.createSymbolicLink` → 容器内
  `ln -sfn` → `cp -a` → 纯 Java 复制 → 四路 fallback），
  最后只有**复制**是可靠的。

插件如果假设 `fs.link()` 可用、或者假设 `node_modules` 里的 symlink 能建起来，
在这里直接失败。这既不是 `permissions`（用户授权解决不了），
也不是 capability 缺失（Node 的 API 明明在），
而是**平台把语义抽走了**，现有 schema 没有位置表达它。

### ③ 进程命名空间不隔离

我们的容器基于 proot（ptrace 模拟 chroot），**PID 不隔离** ——
容器内 `/proc` 看到宿主的全部进程。一条看起来无害的 `pkill -f node`
会把信号送给承载整个环境的容器进程乃至 App 自己。

插件只要 spawn 子进程并试图按名字清理，就有概率把宿主杀掉。
这个坑我们踩了两次才彻底改成「`pgrep` 出候选后逐个核对
`/proc/<pid>/cmdline`，带容器启动器关键字的一律跳过」。

### ④ 网络与存储路径不是 POSIX 直觉

`/sdcard` 走 FUSE、App 私有目录不可被其他进程访问、
后台网络会被系统按省电策略掐断。插件里「写个临时文件到共享目录让另一个进程读」
这类模式在这里不成立。

### 具体建议

在 `execution` 下加一个**可选**对象，缺省即「无特殊约束」，
这样桌面宿主完全不受影响：

```jsonc
"execution": {
  "environment": "node",
  "trustMode": "trusted-in-process",
  // 新增，可选。列出的每一项都是「插件不能想当然」的点。
  "constraints": {
    "hostMayVanish": true,          // deactivate 不保证送达，且这是常态而非异常
    "hardlinks": false,             // fs.link() 不可用
    "symlinks": "unreliable",       // 可建但不保证跨进程可见
    "pidNamespace": "shared",       // 容器内可见宿主进程，按名字杀进程有风险
    "sharedFilesystem": false       // 无法通过共享路径与外部进程交换文件
  }
}
```

理由：

- **静态可分析**（原则 1）：纯静态 JSON，协商器不必执行代码就能判断。
- **可以进协商**：插件将来若声明 `requires.constraints`，
  协商器就能在安装前给出「这插件需要硬链接，本宿主不提供」——
  正是标准想解决的「装上才知道炸」。
- **fail closed 友好**（原则 5）：宿主宁可声明约束，也不要让插件在近似语义上跑出
  「看起来成功」的结果。
- **不是新维度**：它描述的是 `environment` 的实际语义，
  而不是 `hostType` / `isRemote` 那种被 §2.7 正确否掉的压缩抽象。

如果觉得 v0.15 冻结在即不宜加字段，退一步的做法是：
先在 registry 里登记一个私有条目（`x-io.github.dsha.constraints/v1alpha1`），
由我们这边实践，等有第二个受限宿主（比如 Termux 内跑的宿主、或 WSL）
再考虑收进公共基线。

---

## 对 §9 第 2、3 问：我们没有足够证据，不回答

`messages.dsh/v1alpha1` 的 payload 字段边界和 MCP `ContentBlock` 对齐 ——
DSHA 目前不消费消息事件（我们只是承载官方 Web UI），
没有实测数据。按原则 8 的精神，没有证据就不该发表意见。

---

## 对原则 5「fail closed」的强烈支持，附一组反例

> 上游不再暴露某项能力所需的观察点时，Adapter 必须下线对应 capability 并报告原因，
> **不能用私有 patch 猜测语义、返回「看起来成功」的近似结果**。

这条应该加粗放在最前面。我们这一轮的 bug 有很大比例是它的反例：

| 反例 | 后果 |
| --- | --- |
| 自检检查补丁标记 `FIX2`，而实际已升到 `FIX4` | 好的报成坏的，形成「脚本说已打过、自检说没打、怎么修都是 ❌」的死锁 |
| 自检用 `dependencies` 里的 `link:` 声明判断插件可用，而 dsh 只认 `node_modules` | 坏的报成好的，把排查引向完全错误的方向 |
| 提示写「重开 App 会自动补齐」，实际没有任何代码处理 | 同类假承诺出现了六次，用户按提示反复重试却毫无变化 |
| 插件安置逻辑写了两套，改动一直落在没被调用的那一套上 | 连续六个版本「修好了」，实际一次都没执行过 |

每一条的形状都一样：**返回了「看起来成功」**。
所以我们特别认同 §4.4 那条表述底线（只能声称「通过 v0.15 Host conformance」，
不能表述为「安全插件」或「官方认证」）——
在这个生态里，克制的措辞本身就是功能。

---

## 会照做的两件事（不依赖标准落地）

### 市场五态

`声明兼容 / 等待授权 / 已实测 / 不兼容 / 未知`，以及「不得互相升级」和
「展示但禁用不兼容插件，而不是隐藏」。

我们的插件市场目前只有「已安装 / 未安装」，装不上才知道不兼容。
这五态直接可用，且不需要等标准晋级。

「不要隐藏」那条尤其有共鸣 —— 我们出过一个真实故障：
启动前校准会静默摘掉解析不到的插件，用户看到的是「插件凭空消失」，
然后以为是 App 坏了。现在改成记入活动日志并在自检里显示。

### 安装前预检

我们已经按现有插件的真实结构（`package.json` 的 `dsh` 字段）做了一版：
检测 `workspace:*` 依赖、检测「声明了 dist 但需现场构建」、
命中就查 npm registry 是否有同名包并自动改用。

顺便提供一条可能对标准有用的实测信息：**pnpm ≥ 11 默认拒绝执行 git 依赖的
`prepare` 脚本**，加上多数插件仓库不含构建产物，
「从 git URL 装插件」这条路在新版 pnpm 下基本走不通。
dsh-TUI 的 README 也明确写了这一点。
如果标准将来涉及分发层（§4.5 提到 packaging / distribution 归后续提案），
这可能是个需要正面处理的现实约束。

---

## 关于成为第二个宿主

§4.4 要求 v0.15 晋级需要**至少两个独立宿主产品/集成**，
目前 dsh-TUI 认领了第一批。

DSHA 有意愿做第二个，但要说清前提：我们现在没有实现任何契约坐标，
真做需要写一层适配（把 `commands.dsh` 映射到我们的插件注册、
`storage.dsh` 映射到 profile 内的私有目录）。
在 v0.15 schema 冻结、registry 条目定案之前，我们不打算动实现 ——
按原则 8，参考实现不是标准，而跟着 Draft 改实现的成本会全部浪费。

**冻结之后我们愿意做**，而且能提供一个其他宿主给不了的东西：
真实移动端环境的 conformance evidence。上面那四类约束，
只有在真机上才测得出来 —— 我们自己的容器跑在 proot 里，
连「从 Android 侧操作 rootfs」的限制都测不出来，
每次都得装包到手机上验证。
