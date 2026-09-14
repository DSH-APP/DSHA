# AIDL 手工等价产物

本项目的 AIDL 源只有一个 `app/src/main/aidl/com/deepseekharness/app/IShellService.aidl`
（两个方法），这里存的是它的人工核对等价产物。

## 什么时候需要

Google 仓库里的 **Linux build-tools 只有 x86_64**：`repository2-3.xml` 中 build-tools
只有 `_linux.zip`，没有 aarch64 变体；`sdkmanager` 按主机架构安装，在 aarch64 主机上
装出来的 `aidl` / `aapt2` 仍是 x86_64，一执行就是

```
loader: reject .../build-tools/36.0.0/aidl: bad machine
```

而 AGP 9.1.1 又硬性要求 build-tools ≥ 36.0.0，装不出别的版本；Debian 包里的 arm64
`aidl` 太老，解析不了新版 `framework.aidl` 的 `@JavaOnlyStableParcelable`。

## 怎么用

`build.sh` 会自动检测主机架构与 `aidl` 的 ELF `e_machine` 是否一致，不一致时把
build-tools 里的 `aidl` 换成一个 shim（原二进制备份为 `aidl.x86_64.disabled`），
shim 直接把这些等价产物拷进 AGP 指定的 `-o` 输出目录。这样 AIDL 任务照常跑完，
下游的 javac / Kotlin 编译能正常拿到惰性输出 provider。

已搭好 qemu-user + binfmt 的主机可设 `DSHA_FORCE_AIDL=1` 跳过 shim，走原生 `aidl`。

恢复原状：

```bash
mv $ANDROID_SDK_ROOT/build-tools/36.0.0/aidl.x86_64.disabled \
   $ANDROID_SDK_ROOT/build-tools/36.0.0/aidl
```

## 改动 AIDL 源时必须同步更新这里

如果给 `.aidl` 加方法，或改了包名，这里的 `Stub` / `Default` / `Proxy` /
`onTransact` 都得跟着改，否则编译能过但 IPC 行为是错的。
