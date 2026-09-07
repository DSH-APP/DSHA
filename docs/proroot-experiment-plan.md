# proroot 容器运行时抽象层 —— 实施计划

状态：计划阶段（抽象层骨架已落地，尚未接通 proroot）
制定日期：2026-08-24

---

## 一、这件事到底在赌什么

把「怎么进 rootfs 执行命令」抽象成可切换的运行时，让 proot 与
[proroot](https://github.com/coderredlab/proroot) 并存。

proroot 用 LD_PRELOAD + 二进制补丁做进程内路径翻译，**零 ptrace 开销**；
proot 每个 syscall 要两次上下文切换。node 启动、pnpm 安装都是 syscall 密集型，
所以理论收益不小 —— Termux 社区已有人反馈「superior performance compared to
standard PRoot」（proroot issue #21）。

但它是**闭源二进制**，单人维护，作者已把重心转向另一个项目 proroom。
Termux 官方因为闭源拒绝打包。所以这只能是实验功能，默认关闭。

### 已确认的事实（查证过，不是推测）

| 项 | 结论 | 来源 |
|---|---|---|
| 体积 | 5 个 .so 共约 665 KB | GitHub release v1.2.8 asset size |
| 参数 | `-r` / `-w` / `-0` / `-b` / `--link2symlink`，与 proot 一一对应 | README |
| 需要的环境变量 | `PROROOT_TMP_DIR` 必须指向可写目录（App 进程内） | README |
| 测过的负载 | Node 22/24、Python 3.12、Git、Chromium/Playwright、OpenClaw、Codex | release notes |
| 验证机型 | Galaxy Flip4（Android 16）、Lenovo Tab（Android 15） | README |
| 崩溃诊断 | 自带 SIGSEGV/SIGABRT dump → `proroot-sigsegv-maps.txt` | v1.2.8 notes |
| 许可 | Proprietary，允许在项目中使用，禁止分发修改版 | README |

### 已确认的风险（同样查证过）

**l2s 结构大概率不兼容。** proot 的实现是三层：
`目标 symlink → 中间路径（$PROOT_L2S_DIR/.l2s.<名>0001）→ 最终文件（中间路径.<链接数>）`。
proroot 的 README 写的是「anchor + symlink groups」，术语不同；v1.2.8 还提到
`MAX_MEMBERS` 上限 —— proot 没有这个概念。**我们的 `flatten-l2s.py` 完全建立在
proot 的命名和层次上（21 处引用），换过去很可能直接失效**，那意味着备份又会 100% 失败。

**MAX_MEMBERS 上限本身是隐患。** pnpm store 会产生大量硬链接，撞上限就报 ENOSPC
（v1.2.8 修的就是这个 bug 的一个变体）。我们的 rootfs 里 pnpm store 是大户。

**闭源意味着出问题只能等作者。** 没有源码就没法自己修，而作者更新已经变慢。

---

## 二、现有代码对 proot 的依赖有多深

实测统计（`grep -c`）：

```
HarnessController.java   229 处     绝大多数是 proot.execAndRead(...) 这类调用 → 走抽象层没问题
ProotBootstrap.java       40 处
其余 Java 10 个文件       1-6 处

flatten-l2s.py            21 处     ← 整个脚本为 proot 的 .l2s 机制而写
selftest.py               17 处
fs-write-patch.sh          9 处
pnpm-env-fix.sh            7 处
check-dsh-dupes.py         4 处
```

### 五类真正的硬依赖（换运行时后行为可能变）

1. **`.l2s` 硬链接模拟结构** —— `flatten-l2s.py` 的实体化与悬空清理全靠它。
2. **`lstat` 被劫持** —— 全项目刻意用 `os.readlink()` 而非 `os.path.islink()`
   （proot 伪造 `st_nlink` 导致 lstat 不可信）。proroot 是拦 libc 而非拦 syscall，
   行为大概率不同，那些变通可能变成累赘、甚至判断反了。
3. **pnpm 硬链接策略** —— `pnpm-env-fix.sh` 写 `package-import-method=copy`，
   理由是 proot 下硬链接是模拟的。若 proroot 的硬链接更可靠，这设置该撤掉。
4. **`write` 工具悬空链接** —— `fs-write-patch.sh` 打「改用 rename 发布」的补丁，
   根因是 proot 的 `link()` 模拟。proroot 下可能不必要（打了应也无害）。
5. **PID 不隔离 + 进程识别** —— `safeKillWebCmd` 靠 `/proc/<pid>/cmdline` 含
   `proot` 排除宿主进程。**换 proroot 后关键字不匹配，那条自杀保护直接失效** ——
   这是抽象层改动已经引入的 bug，不是潜在风险。

### 一个尚未验证的前提

proroot 是 LD_PRELOAD 方案，**只能拦住动态链接的程序**。静态链接的二进制、
或自己直接发 syscall 的程序会绕过它。dsh 依赖树里有 12 个 `.node` 原生模块
（node-pty / koffi / sharp …）。README 的测试列表覆盖 Playwright/Chromium，
所以大概率可用，但没有保证。

---

## 三、分阶段计划

原则：**每阶段结束都能独立验证，且失败不影响现有功能。**

### 阶段 0：抽象层骨架（已完成）

- [x] `ContainerRuntime` 接口 + `Proot` / `Proroot` 两个实现
- [x] `ProotBootstrap.baseProotArgv()` 委托给 `runtime()`
- [x] `applyProotEnv()` 顺带调 `runtime().applyEnv()`
- [x] proroot 不可用时自动降回 proot
- [x] bind 列表统一到 `BINDS` 常量（避免两处各写一份）
- [x] 编译通过，默认行为零变化

验证：装包后什么都别改，跑一次自检 + 启动 Web，确认与上一版无差别。

### 阶段 1：修掉抽象层引入的问题 + 建立兼容性探针

**1.1 `safeKillWebCmd` 去掉 proot 硬编码**

改为按「当前运行时的启动器路径」排除，而不是匹配字符串 `proot`。
两个运行时的启动器文件名不同（`libproot.so` / `libproroot.so`），
用当前 runtime 提供的标识来排除。

**1.2 写一份运行时兼容性探针脚本 `runtime-probe.py`**

在当前运行时下实测五类假设，输出结构化结论。这样切换后不用凭感觉判断：

| 探针 | 方法 | 判定 |
|---|---|---|
| 硬链接是否真实 | `ln a b` 后比 `stat -c %h` 与 inode | 真实 / 模拟 |
| l2s 命名结构 | 造硬链接后扫目录，看有没有 `.l2s.*` 及其层次 | proot 式 / 其它 / 无 |
| lstat 可信度 | 对已知符号链接调 `os.path.islink()` | 可信 / 被劫持 |
| /proc/self/exe | 读它，看是否泄漏宿主路径 | 正确 / 泄漏 |
| 原生模块 | `require('node-pty')` 等逐个 load | 各自 OK / 失败 |
| PID 可见性 | `pgrep -f <宿主包名>` 是否有结果 | 隔离 / 不隔离 |

探针必须**只读**（除了在临时目录造几个测试文件），跑完自己清理。

验证：在 proot 下先跑一遍，把结论作为基线记进文档 —— 这样切到 proroot 后
一比对就知道哪些假设变了。这一步在容器内就能做（容器本身跑在 proot 内）。

### 阶段 2：下载与开关

**2.1 下载器**

使用固定的离线组件包，并在包内校验各文件哈希。v1.2.8 的五个哈希
release notes 里全给了，硬编码进 APK 当基线比只信 HTTPS 强：

```
a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01  libproroot.so
8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34  libproroot-runtime.so
1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f  libproroot-bridge.so
51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee  libproroot-linker.so
06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0  libproroot-stub-loader.so
```

落地到 `filesDir/proroot/`，设置可执行位。**不进 APK** —— 闭源二进制内置进
发行包是信任问题，不是体积问题。

**2.2 配置页开关**

- 单选：proot（默认）/ proroot（实验）
- 选 proroot 时若未下载 → 引导下载，不静默失败
- 切换后明确提示「需重启 Web 生效」
- 显示当前实际生效的运行时（可能因降级与所选不同）

**2.3 强制回退开关**

连续 N 次用 proroot 启动失败 → 自动切回 proot 并告知。
实验功能不能让环境变成不可用，这条是硬要求。

验证：下载 → 校验 → 切换 → 启动 → 跑探针，对比阶段 1 的基线。

### 阶段 3：按探针结论逐项适配

只做探针**实测为不兼容**的项，不预先猜。可能的工作：

- `flatten-l2s.py` 增加 proroot 结构识别（或明确判定「该运行时无需实体化」）
- `pnpm-env-fix.sh` 按硬链接是否真实决定要不要写 `package-import-method=copy`
- `readlink` 变通处按 lstat 可信度分支
- `fs-write-patch.sh` 保留（幂等，无害）

### 阶段 4：性能对比

加一个对比入口：同一组命令在两个运行时下各跑一遍，记录耗时。

候选命令（覆盖不同 syscall 强度）：

```
node -e "1"                      解释器冷启动
node -e "require('fs')…"         文件系统密集
pnpm --version                   进程创建
tar -czf /dev/null <小目录>       大量 stat/read
dsh --version                    真实负载
```

**没有数据就不推广。** 如果实测提速不到 20%，考虑到闭源与维护风险，
这个功能就该停在实验阶段甚至撤掉 —— 这个判断点写在这里，避免到时候
因为「已经做了这么多」而舍不得。

---

## 四、明确不做的事

- **不改装机路径**。解压、安装六步一律用 proot。切换只影响「执行命令」这一层，
  最坏情况是慢一点，不会装不上。
- **不内置 proroot 二进制**。
- **不做自动切换**。不根据设备情况自动选运行时 —— 用户明确选择才切。
- **不支持切换后共用同一个 rootfs 的「无缝」承诺**。如果 l2s 结构不兼容，
  在 proot 下产生的硬链接组在 proroot 下可能读不到，反之亦然。
  这种情况要在切换时明确警告，而不是假装无事。

---

## 五、验证矩阵：什么能在容器里验，什么必须真机

| 项 | 容器（proot 内） | 真机 |
|---|---|---|
| 编译、接口正确性 | ✅ | |
| 探针脚本本身的逻辑 | ✅ | |
| proot 基线数据 | ✅ | |
| proroot 能否启动 | ❌ 容器已在 proot 内，套不了 | ✅ 必须 |
| l2s 结构对比 | ❌ | ✅ 必须 |
| 原生模块加载 | ❌ | ✅ 必须 |
| 性能对比 | ❌ 容器有 proot 开销叠加 | ✅ 必须 |
| 从 Android 侧操作 rootfs 的限制 | ❌ 测不出 | ✅ 必须 |

**容器跑在 proot 内，测不出「套一层别的运行时」的行为** —— 这一点这轮已经
栽过（Java NIO 建符号链接在容器里成功、真机失败）。所以阶段 2 之后的每一步
都需要真机验证，计划里不能假装容器验过就算完。

---

## 六、参考实现：andClaw（决定性收获）

proroot 作者自己的 App [coderredlab/andClaw](https://github.com/coderredlab/andClaw)
**是开源的**，结构与 DSHA 几乎一致（Android App → proroot → Ubuntu arm64 →
Node.js → AI agent），连外围设计都撞车（前台服务保活、开机自启、看门狗恢复）。

`ExecutionRuntime.kt` 只有 349 字节，就是个枚举 —— **它本身就是双运行时可切换的**，
作者也保留了 proot 作后备。这直接验证了本方案的架构。

从代码里拿到的四个答案：

| 问题 | 计划里的判断 | 实际 |
|---|---|---|
| l2s 清理是否需要适配 | 标为**最大风险**，`flatten-l2s.py` 可能要重写 | **全项目零处 l2s 代码** → proroot 下不必跑 |
| pnpm 硬链接策略 | 需适配 | 零处特殊处理，不需要 `package-import-method=copy` |
| .so 能否按需下载 | 计划下载到 filesDir | **不行**。Android 10+ W^X 不允许从可写目录执行 → 必须进 jniLibs |
| 杀进程怎么做 | 容器内 pgrep + 排除 proot | 记 pid + 核对 `/proc/<pid>/cmdline` + `android.os.Process.killProcess` |

另外三条只有读代码才知道的适配项：
- `-b <cacheDir>/shm:/dev/shm` —— proroot 不带 `libandroid-shmem.so`，
  POSIX 共享内存要用真实目录顶（注释写明是 Chromium 用）；
- bind 一律写成 `host:guest` 显式形式，单参数形式未必解析；
- `PROROOT_LIB_PATH` / `PROROOT_LINKER_PATH` 显式设，不靠 README 说的自动发现。

## 七、进度

- [x] 阶段 0 抽象层骨架
- [x] 阶段 1.1 杀进程改 Android 侧 pid 精确杀（照 andClaw），safeKillWebCmd 排除两种启动器
- [x] 阶段 1.3 读 andClaw 参考实现，据此对齐参数与环境变量
- [x] 阶段 2.1 五个 .so 进 jniLibs（W^X 决定，非选择）+ THIRD_PARTY_NOTICES.md
- [x] 阶段 2.2 配置页开关（默认 proot）
- [x] 阶段 2.3 强制回退：连续 3 次启动失败自动切回 proot
- [x] 阶段 2.4 自检新增「容器运行时」项，区分所选与实际生效
- [ ] 阶段 3 真机验证 proroot 能否启动（容器测不了：本身就在 proot 内）
- [x] 阶段 4.1 性能对比入口（设置页「🏁 运行时性能对比」）
      · execAndReadWith(runtime, cmd) 可指定运行时执行，不依赖当前选择
      · 四组命令：解释器冷启动 / 文件系统调用 / 进程创建 / 目录遍历
      · 每组跑 4 次**丢掉第一次**（预热文件缓存，否则先跑的运行时吃亏），
        后 3 次取**中位数**（手机上单次噪音大，中位数比平均抗离群）
      · 报告末尾直接给结论：达到/未达 20% 判断线
- [x] 阶段 4.2 真机实测（2026-08-24，vivo V2352A / Android 14）

### 实测数据

```
★容器冷启动            45ms      19ms   +58%
★解释器冷启动          133ms     104ms   +22%
★模块解析             131ms     106ms   +19%
★stat 密集            635ms     113ms   +82%
★文件读写             448ms     118ms   +74%
★进程创建             202ms     217ms    -7%
　目录遍历              89ms      30ms   +66%
　文本管道              37ms      26ms   +30%
　Python 冷启动         68ms      47ms   +31%
　tar 打包             608ms      38ms   +94%

★ 关键项合计         1594ms     677ms   +58%
  全部合计           2396ms     818ms   +66%
```

**数据可信度**：数值杂乱（19/26/30/37/45/47…）而非上一版那种整齐的 100 倍数，
说明 100ms 量化问题已消除；而且提速幅度**与理论预期吻合** ——
syscall 越密集提速越大（stat +82%、文件读写 +74%、tar +94%），
这正是 ptrace 每 syscall 两次上下文切换的开销分布。

**对 DSHA 的实际意义**（按痛点排序）：

| 场景 | 提速 | 说明 |
|---|---|---|
| tar 打包 | **+94%**（608→38ms，16 倍） | **备份**走这条路。用户报过「备份很慢/失败」 |
| stat 密集 | **+82%** | node 模块解析的主体，影响每次启动 |
| 文件读写 | +74% | 会话写入、插件安装 |
| 目录遍历 | +66% | 安装期扫描 |
| 容器冷启动 | +58% | 每次 execAndRead 都摊到 |

**唯一变慢项：进程创建 -7%**。fork/exec 走的是另一条路径，
LD_PRELOAD 方案在这里没有优势（甚至略有注入开销）。
dsh 的工具调用会 fork 子进程，所以这一项要留意 ——
但 -7% 的绝对值只有 15ms，相对其它项的收益可以接受。

**结论**：关键项 +58%，远超动手前定的 20% 判断线。功能保留，继续推进。

- [ ] 阶段 5 真实使用验证（跑分≠可用）：
      Web 能否正常启动、agent 工具调用是否正常、备份/恢复是否成功、
      终端交互是否正常、12 个 .node 原生模块是否都能加载
- [ ] 阶段 6 观察期后决定是否从「默认关」升到「默认开」

阶段 1.2 的探针脚本**暂时不做** —— 原本是为了猜行为差异，
现在有开源参考直接给了答案，写探针的性价比大幅下降。
等真机跑起来若发现异常再补。
