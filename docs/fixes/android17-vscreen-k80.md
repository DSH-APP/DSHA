# Android 17 / Redmi K80 Ultra 虚拟屏兼容修复

2026-10-11，基于源码 `648f4253db8ca4e7c28b6d8466388d7cad8ed667`。

## 故障与行为变化

Redmi K80 Ultra（dali，25060RK16C），Android 17 / SDK 37，HyperOS
`OS4.0.0.8.XONCNXM`，DSHA build170 创建虚拟屏失败，实际错误为
`VSCREEN_NoSuchMethodException`。

该设备的 `InputManager` 已没有 `getInstance()`，而
`InputManagerGlobal.getInstance()` 与 `injectInputEvent(InputEvent, int)` 存在。
`VirtualScreenInput` 保留旧系统入口，仅在旧单例方法不存在时懒加载新入口。
调用异常、权限异常及注入方法缺失保持失败，不触发回退。

`VirtualScreenCore.create()` 失败时现在释放已创建的显示和 ImageReader；若会话
已开始，则先清空会话状态、停止帧循环并回收帧。清理异常不会遮蔽原始错误。

此设备的 Shizuku 用户服务还会在 `ShizukuServiceStarter` 调用
`LoadedApk.makeApplication(true, null)` 时崩溃，尚未进入 DSHA 用户服务。
因此 SDK 37+ 在 ADB 已验证连接时，按 Root → ADB → Shizuku 选择通道。
其余系统和未验证 ADB 保留原顺序；各通道的原有授权门禁保持生效。
这不修复 Shizuku 管理器本身；只启用 Shizuku 而没有可用 ADB 的设备仍可能失败。
动作结果未知时不重放，启动票据、单次认证和帧代次校验没有变动。

## 验证范围

Java 17 编译真实修改类，通过 8 个 JUnit 回归：5 个输入入口测试、3 个通道顺序
测试。通道顺序测试用仅供编译的服务依赖桩，不执行通道启动或可用性探测。
修改过的 Java 文件与新文档通过 `git diff --check`。

K80 真机以已授权 ADB shell（UID 2000）运行隔离探针。最新核心探针编译的是
修复后的 `VirtualScreenCore` 与 `VirtualScreenInput`；辅助类从已安装 APK 加载，
编译桩未打进探针 dex。调用核心内部路由，未安装替代 APK，也未覆盖启动认证。

实际输出：

```text
PASS core displayId=16 size=1008x1792 launch=true preview=true input=true staleRejected=true
PASS core displayId=17 size=1792x1008 launch=true preview=true input=true staleRejected=true
PASS patched core session lifecycle and cleanup
PASS displayId=18 size=1008x1792 key=true touch=true frame=true
PASS displayId=19 size=1792x1008 key=true touch=true frame=true
PASS portrait, landscape, injection, frames and display cleanup
```

覆盖横竖屏创建、Settings 启动、JPEG 预览、按键/触摸注入、旧代次拒绝、切换尺寸
及会话关闭。探针结束后另行检查无残留测试显示和核心进程。
这不是安装后经 DSHA 界面的端到端验收，也不代表完整正式包构建通过。

正式环境可运行：

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*VirtualScreenInputTest' --tests '*VirtualScreenChannelsTest'
```

本机未运行完整 Gradle 构建：固定离线运行资产不在源码克隆中。
现有快速检查另有基线错误，未作为本修复的一部分修改：

- 源码卫生：五个既有文件 CRLF、`star-history.yml` 的 action 未固定到提交。
- 测试清单：未收录 `test-mobile-reasoning-defaults.mjs`、
  `test-mobile-refresh.mjs`、`test-web-file-actions.mjs`。

## 覆盖安装尚未完成

手机仍安装原始 build170，包名 `com.dsh.client`，APK 备份已保存。
历史证书 SHA-256 为
`e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`。
本机没有对应私钥。GitHub 账号已通过 Git Credential Manager 正式授权；该账号
对官方仓库仅有读取权限，没有写入权限，无法访问官方发布环境的签名密钥。
修复可通过个人 fork 与 PR 提交，安装版仍待官方签名构建和覆盖安装验收。

维护者可应用补丁，在同一提交上准备固定离线输入，通过 `ci-package.yml` 完整
检查，再用 `release.yml` 的 `package_run_id` 调用历史签名流程。核对
`SIGNED-HISTORICAL-CERTIFICATE` 产物的证书、包名与非调试属性后，使用 `adb
install -r` 覆盖安装 Standard 正式版，并从 DSHA UI 验证创建、预览和输入。
不通过卸载、清数据或换签名来替代覆盖安装。
