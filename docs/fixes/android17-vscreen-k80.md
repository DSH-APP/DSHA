# Android 17 / Redmi K80 Ultra 虚拟屏兼容修复

2026-10-12，针对 Redmi K80 Ultra（Android 17 / SDK 37，HyperOS）上虚拟屏创建后失败、预览消失和输入无效的问题。

## 根因与修复

该设备的 `InputManager` 已没有旧的 `getInstance()`，而
`InputManagerGlobal.getInstance()` 与 `injectInputEvent(InputEvent, int)` 仍可用。
`VirtualScreenInput` 在旧入口不存在时懒加载新入口；调用异常、权限异常和注入方法缺失仍按失败处理，不静默回退。

`VirtualScreenCore.create()` 失败时会释放已经创建的显示和 `ImageReader`；会话已开始时会先清空会话状态、停止帧循环并回收帧，清理异常不会覆盖原始错误。

SDK 37 且 ADB 已验证连接时，通道选择为 Root -> ADB -> Shizuku；其余设备和未验证 ADB 保留原顺序。该调整规避了本机 Shizuku 用户服务在 `LoadedApk.makeApplication(true, null)` 崩溃的问题，但不修改 Shizuku 本身。

另一个实际根因来自旧版 ADB shell：`--launch` 返回 `STARTED` 后，连接关闭会连带杀死子进程组，导致虚拟屏核心很快退出。修复在启动核心前建立独立会话（`setsid`，不使用 `-d`），保留启动握手和直接子进程管道，同时让核心脱离无线 ADB 连接生命周期。该问题已用旧版 `adb shell -T -x` 复现并确认。

诊断也已收敛：启动和提交阶段只输出固定的 allowlist 失败码，不记录命令、票据或原始输出；空闲预览阶段的 `VSCREEN_NOT_RUNNING` 不再每 250ms 刷屏。

动作结果未知时不重放，启动票据、单次认证、权限门禁和帧代次校验保持不变。

## 自动化验证

Java 17 编译真实修改类并通过 8 个 JUnit 回归（5 个输入入口、3 个通道顺序）；托管 ADB 诊断测试 7 个通过，Bootstrap 测试 8 个通过。修改文件通过 `git diff --check`。完整 Gradle 正式包未在本机运行，原因是固定离线构建资产不在源码克隆中。

## K80 真机端到端验收

在带补丁的个人签名安装包上通过 DSHA 界面完成以下流程：

- 竖屏创建虚拟显示 31，横屏创建虚拟显示 32；两者均实际渲染 Android Settings 预览。
- 竖屏预览可滑动且画面随滑动滚动；横屏核心保持运行超过 60 秒，心跳持续有效。
- 关闭虚拟屏后，显示记录和核心进程均清理，无残留。

上述安装包使用个人签名，仅用于本机验收；它不是官方历史签名发布包，也不代表官方完整流水线已通过。发布前仍需维护者在固定构建输入上运行 `ci-package.yml`、完成官方历史签名流程和官方测试，再由同一证书的正式包进行覆盖安装验证。
