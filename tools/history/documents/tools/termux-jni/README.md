# Termux JNI 16KB 重编

2026-09-10 rc1.1（版本码 118 修正版）：新增 `dsha-pty.c` / `dsha-pty.h`，构建脚本先校验原版源码，再在构建目录生成六处固定锚点补丁。子进程 `setsid` 后自读 `/proc/self/stat`，通过仅用于本次 fork 的 socketpair 回传；父进程登记 PID、PPID、启动时间与独立会话号后确认，子进程才允许 exec。握手失败在原生 waitFor 启动前回收刚创建的子进程。Java 不再在启动后单次读取身份，也不在停止时临时认领未知 PID。

目标仍为 API 23，LOAD 16 KiB 对齐，RELRO 同时通过 4 KiB / 16 KiB 映射检查，JNI 导出集合不变。SHA-256：`0c6b87759cb35f2ae8265155d3d669d511b43dbddab5831b6f55904833676147`。非调试版 Android 16 已验证真实 proot/proroot 维护、快速退出和握手拒绝；详细记录见 `docs/releases/v0.1.5-rc1.1.md`。

2026-09-10 rc1.1：保留上游 `termux.c`，增加独立 `dsha-process.c` 查询内核会话号，避免终端维护核验无关应用的受限 `/proc` 条目。默认构建 API 23，保留 `max-page-size=16384`、`common-page-size=4096`；原有 5 个 JNI 导出及新查询导出已核验。当前 SHA-256：
`f021d162842890f8a6c52c77d67b5f205e38f37bbd835e18e8c89b5d6195923c`。

下方为此前版本的构建记录。

2026-09-05 后续修复：仍为 API 23，max-page-size=16384、common-page-size=4096，
避免 RELRO 延伸到未映射的 4 KB 空洞导致旧 linker 报 ENOMEM。现行 SHA-256：
`52bb4a06db4a2805fc35f7d33892a78563dce5d64f1f558d8713330cc0aefe15`。
Android 13 真机终端及 npm 安装通过；旧哈希均为历史记录。

兼容版现使用 `-MinApi 23` 重编同一份 C 源码（同时适用标准版），输出 SHA-256 为
`bc3de9dc57d0e886866b95ef077619317704ebc7261c674d14824349da8fcdc8`。
下方旧 API 26 哈希保留为历史记录；复现兼容版请加 `-MinApi 23`。

## 来源

- [Termux v0.118.0 原版 termux.c](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-emulator/src/main/jni/termux.c)，提交 `6e2689f55295fa444be8ac8592c527c2c5ef3253`；该文件原样保留，现行构建生成上述身份握手补丁。
- 保留 `LICENSE.upstream.md`（含 terminal-emulator 的 Apache 2.0 例外）及 `LICENSE-2.0.txt` 全文。

## 重编命令

在仓库根目录的 PowerShell 执行：

```powershell
& ./tools/termux-jni/build.ps1 -Ndk 'F:/DSHA/_toolchains/android-sdk/ndk/26.3.11579264'
```

使用已有 NDK r26d / clang 17.0.2，目标 `aarch64-linux-android26`。
真实链接参数包含 `-Wl,-z,max-page-size=16384` 和 `-Wl,-z,common-page-size=16384`。
脚本不下载工具、不调用 Gradle，校验后输出至 `app/src/main/jniLibs/arm64-v8a/libtermux.so`。

## 验证摘要

- ELF64 / AArch64，SONAME `libtermux.so`；三个 LOAD 段均为 `0x4000`，16KB 地址同余检查通过。
- 与原 0.118.0 AAR 的 5 个 JNI 导出完全一致；由 `javap` 核对现有 Java 的 4 个 native 方法签名，加载名仍为 `termux`。
- 动态依赖仅系统 `libc.so`、`libdl.so`。两次独立链接得到相同文件，大小 **9576 字节**。
- SHA-256：`411e90ce4cbd1defd7ee19cb5fd62b430c055de2c7ef447a1d846da26ec56f02`。
- GNU Build ID：`c5b7315b045907ebbce37ad12a414332d1779ffe`。

本任务未修改 Gradle、Java、Manifest，未做备份、全量测试或设备验证。
父任务负责 `pickFirsts` 去重与最终 APK 核对；若后续 strip 改变哈希，结合 Build ID、LOAD 对齐和 JNI 导出确认选中重编库。
