# proot 本体开销调查（T34，2026-11-03）

只调查、出方案；未替换随包二进制，未改运行代码。替换 `libproot.so` 须用户另行明确同意。

## 1. 随包 libproot.so 出处（已核实到上游标签）

| 证据 | 结果 |
|---|---|
| 文件 | `app/src/main/jniLibs/arm64-v8a/libproot.so`，SHA-256 `cbde86eb…f721`，git blob `fc63fe85`；本仓库首次出现于 2026-08-18（`c7bde50a`），之后未变 |
| 内嵌版本串 | `v5.1.107.91`（上游 GNUmakefile 用 `git describe --tags --dirty` 生成；无 `-dirty`、无 `-N-g<hash>` 后缀，说明是干净的标签检出） |
| 上游标签 | [termux/proot `v5.1.107.91`](https://github.com/termux/proot/tree/v5.1.107.91) = `61681c6`（2026-08-15「syscall: don't wait for a sysenter stop the kernel skips」） |
| 符号交叉核对 | 未 strip，609 个函数符号。与上游逐提交的函数名集合对比：含 `is_l2s_file`（7ff389a，08-12 引入），含 `decrement_link_count`（c6880fc，10-23 删除）；`get_l2s_directory` 等 7266fb3（v5.1.107.92，08-22）新增函数不存在。窗口与版本串一致 |
| 本地补丁 | 二进制中不在上游源码出现的函数只有 `handle_option_*`（cli.h 宏生成）、`_start_main`、`__atexit_handler_wrapper`、`pthread_atfork`（工具链/CRT），未发现本地修改痕迹 |
| 构建方 | 不是 Termux 官方包：`.note.android.ident` 为 NDK r26b、API 26，`.comment` 为 clang 17.0.2；官方 5.1.107.102 包为 clang 21/22 且已 strip。官方仓库只保留最新 .deb，无法对 .91 官方包做逐字节比较 |

结论：源码修订可以写成「termux/proot v5.1.107.91（61681c6），第三方以 NDK r26b / API 26 构建，未见本地补丁」；**仍不能声明逐字节可复现**（构建方、编译参数未知）。`THIRD_PARTY_NOTICES.md` 与 `docs/runtime-engines.md` 的「精确修订未核实」可据此更新（属生成文件，需改 `tools/generate-third-party-notices.py` 后重新生成，另开小改动）。

## 2. 上游较新修订有没有降开销

v5.1.107.91 → 当前 v5.1.107.102（52d65a4，2026-11-01）：`src/` 下 46 个文件，+3186/−400。内容是正确性修复，**没有路径翻译或系统调用开销的改进**：

- link2symlink：描述符按链接命名、链接计数 ≥10000、符号链接按符号链接链接、`linkat(AT_SYMLINK_FOLLOW)` 等十余项（与本项目 `--link2symlink` + dpkg/pnpm 场景直接相关）；
- fake_id0：statx 归属记录、chmod 记录写回、renameat2 迁移记录、ACL；
- tracee：fork 事件无 PID / 子进程先死 / execve 线程交接 PID 等竞态修复；
- 新增翻译：`fchmodat2`、`open_tree`、`sendto/sendmsg/sendmmsg` 目标地址。

其中 `58c3b42`（fork 走 sysexit 停顿）、`9bd5307` 等会让 fork 多一次停顿，开销只会略增。换新修订的理由是正确性，不是速度。

## 3. 开销从哪来（按 v5.1.107.91 源码）

当前 argv：`-L --kill-on-exit -0 --link2symlink -b …`（`ContainerRuntime`），默认 seccomp 加速开启。

- **每次带路径的系统调用至少 1 次 ptrace 停顿**（seccomp `RET_TRACE`），停顿里 `canonicalize()` 对路径**每一段**做一次宿主 `lstat()`（`path/canon.c` `substitute_binding_stat`）。没有任何缓存。
- **需要出口停顿（2 次）的调用**：`readlink/readlinkat`（核心表 `FILTER_SYSEXIT`）、`statx`、`faccessat2`；`-0`（fake_id0）和 `--link2symlink` 还给 `newfstatat/fstat/lstat/stat/faccessat` 加了 `FILTER_SYSEXIT`。即 Node 最频繁的 stat 类调用在当前参数下全都是 2 次停顿。
- **realpath 放大**：glibc `realpath` 逐段 `readlink`，非链接返回 EINVAL。深度 d 的路径 → d 次 readlinkat，每次 proot 又对前缀做 1..d 次 lstat，宿主 lstat 约 d²/2 次，停顿 2d 次。这解释了参考数据里「同组祖先目录各被 stat 约 2100 遍」与「readlinkat 2.7 万次 96% 失败」。
- 失败的 readlinkat 在出口阶段 `if ((int) syscall_result < 0) goto end;` 什么都不做，**这次出口停顿纯属浪费**。

成本排序（推断，待第 5 节实测）：ptrace 停顿（两次上下文切换 + 寄存器读写 + `process_vm_readv` 读路径）远贵于宿主 lstat（命中 dentry 缓存的纯内核调用）。所以减少停顿次数优先于减少 lstat 次数。

## 4. 方案评估

| 方案 | 收益 | 正确性风险 | 结论 |
|---|---|---|---|
| A. readlink 入口直答 EINVAL：入口翻译时最后一段已 lstat，若宿主对象不是符号链接、不在 `/proc` 仿真范围、不是 l2s 伪硬链接，直接在入口返回 `-EINVAL`（跳过内核调用与出口停顿） | 每次失败 readlinkat 从 2 次停顿降到 1 次；按参考数据约省 2.6 万次停顿 | 低：只在宿主确认非链接时生效；l2s 伪硬链接本身是宿主符号链接，不会命中；需覆盖 bind 目标、`/proc/self/fd`、空路径 `AT_EMPTY_PATH`、`bufsiz==0`（内核先报 EINVAL，结果一致） | **推荐首选**，补丁小（enter.c readlinkat 分支 + canon 结果标记） |
| B. canonicalize 前缀缓存（缓存「guest 前缀 → 宿主路径，非链接目录」） | 只减 lstat，不减停顿；预计收益有限 | 高：guest 内 rename/mkdir/symlink/rmdir、同 rootfs 多个 proot 实例（Web、终端、插件安装并发）、宿主侧 App 写入都会让缓存过期；pnpm 大量建/删 symlink，目录被替换成链接后会**穿出 rootfs 校验**。只能限定为固定只读祖先（`/usr`、`/usr/local/lib/node_modules` 本身）且每次仍需 inode/mtime 校验，校验本身又是 lstat | **不推荐**，除非 A 实测后 lstat 仍占主导 |
| C. 减少 stat 类出口停顿 | 最大（stat 是最频繁调用） | fake_id0 必须在出口改写 uid/gid/mode；l2s 必须改写链接计数。只能做「快速判断无需改写就提前放行」，而判断条件取决于元数据记录文件，等于在入口多读文件 | 暂不做；可评估 Web 入口是否必须 `-0`（去掉会改变 `getuid()==0` 语义，npm/pnpm 行为可能变，需单独验证） |
| D. 换上游新修订 v5.1.107.102 | 无性能收益，fork 略增 | 中：大量 link2symlink/fake_id0 行为变化，需全量回归 | 作为正确性升级单独立项，可与 A 同包一起做 |
| E. guest 侧减少 realpath（Node 选项、预热缓存） | 可能很大 | 动 dsh 打包/运行参数，看板约束「dsh 打包方式属基线不动」 | 不在本任务范围 |

## 5. 建议的下一步（每步都需用户同意）

1. **可复现构建**：仓库加 `tools/build-proot.py`，固定 termux/proot 标签归档 SHA、`ci/toolchain.lock.json` 的 NDK 26.3、talloc 与 libandroid-shmem 输入，产出 `libproot.so`/`libprootloader*.so` 并核验摘要。先构建 v5.1.107.91 原样，作为对照组（字节不会与现件相同，行为应相同）。
2. **基准**（测试包 `io.github.sunsetrne.dsha.test` 上做，不动正式包）：同机同 rootfs，记录 Web 冷启动到鉴权的时间、`node -e "require('…')"` 模块加载、`find /usr -type f | wc -l`、`dpkg -i` 一个小包、`pnpm install` 一个插件；用 `PROOT_VERBOSE` 或 strace 计数停顿与 readlinkat 结果分布。
3. **实现方案 A**，以 `tools/` 下补丁文件形式维护（类似 `apply-mobile-client-patches.mjs` 的本地差异管理），跑上游 `tests/` 中 readlink/link2symlink 相关用例 + 第 2 步全部场景。
4. 回归清单：`.l2s` 硬链接（dpkg、`ln`）、bind（`/dev/shm`、用户数据 bind）、`--link2symlink` 开/关、终端（PTY 与简易）、插件安装、`PROOT_NO_SECCOMP=1`（关闭加速时 A 同样适用，因为非 seccomp 模式每个调用本来就有出入口两次停顿）。
5. 收益达标后再决定是否同时升级到 v5.1.107.102。

## 6. 证据与复现

- 上游克隆：`git clone https://github.com/termux/proot`；`git rev-list -n1 v5.1.107.91` → `61681c6`。
- 版本串：`strings -n 3 libproot.so | grep '^v5\.'` → `v5.1.107.91`。
- 构建指纹：`readelf -n` / `readelf -p .comment libproot.so`。
- Termux 打包历史：termux-packages `packages/proot/build.sh` 5.1.107.91 bump 为 `8ec1d207`（2026-08-16），SRCURL 为标签归档。
- 关键源码位置（v5.1.107.91）：`src/path/canon.c` `canonicalize()`；`src/syscall/seccomp.c` 核心过滤表；`src/syscall/exit.c` readlink 出口；`src/syscall/enter.c` `PR_readlinkat`；`src/tracee/event.c` `handle_tracee_event()` 出口停顿判定；`src/extension/fake_id0`、`link2symlink` 的 `FilteredSysnum` 表。
- 未做：未在真机运行或计时；停顿与 lstat 的相对成本是推断。
