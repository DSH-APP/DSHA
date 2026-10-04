# DSHA 现行构建说明

先读 [README](README.md)、[AGENTS](AGENTS.md)、[贡献流程](CONTRIBUTING.md) 与[接手指南](docs/接手指南.md)。应用版本以 `app/build.gradle` 为准，DSH 依赖以 `tools/dsh-runtime/package.json`/锁文件为准，完整交付和未验证范围以本轮报告为准。

本轮只做本地正式产物与软件验证，不操作手机、不生成额外调试/审计 APK、不上传 GitHub 或部署网站。JVM 测试名称中的 Debug 表示测试变体，不授权 assembleDebug 或安装额外 APK。

## 环境与 flavor

| 输入 | 现行要求 |
|---|---|
| JDK / 原生语言 | Java 17，无 Kotlin |
| Gradle / AGP | 仓库 Wrapper 9.3.1 / AGP 9.1.1 |
| Android SDK | `platforms;android-37.0`、build-tools 36.0.0；compile/target API 37 |
| NDK | 26.3.11579264；终端与 Low proot 按各自受管入口使用 API 23 与真实 ELF 对齐检查 |
| Python / Node | 生成器 Python 3.9+，CI 锁定版本见 `ci/toolchain.lock.json`；Node 24 |
| Standard | minSdk 30，系统 WebView，arm64-v8a |
| Low | minSdk 23，额外 Gecko 备用内核，arm64-v8a |

两版共用 `com.dsh.client` 与用户数据，不能并排安装。最低 API、ELF 16 KiB 对齐与实际 Android 6/7/16 KiB 设备可用性是不同证据。Windows 可以开发和构建；Linux 特有用例不能用 Windows 跳过结果代替。

配置本机 `local.properties` 的 `sdk.dir`，并将 JAVA_HOME、ANDROID_SDK_ROOT/ANDROID_HOME、DSHA_PYTHON 指向已有工具。不要复制开发者的绝对工具链目录。版本与固定 APK 文件名由 `app/build.gradle` 的 `versionCode`、`versionName` 和 `dshaApkNameVersion` 定义，不在本页再维护另一组当前数字。

## 输入准备与证明

离线 rootfs 和大型生成物不提交；固定输入、生成順序与 CI 传递方式见 CONTRIBUTING。Ubuntu 基础环境身份独立于 DSH 与 UI 版本。`offline-rootfs.bin` / `dsh-runtime.bin` 为 split-runtime-v1，独立应急 pinned 输入由 `tools/recovery-runtime/lock.json` 约束，不能用正式环境作为救援依赖。

受管输入发生变化时，按实际生成入口更新资产，再显式生成证明：

```bash
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/prepare-test-runtime.py
```

生成的证明需与真实字节核对；普通 Gradle 构建使用 check，不能靠旧缓存跳过新输入。当前夹具由 `app/build/test-runtimes/current.json` 及源/内容指纹约束，不能把任一旧目录称为当前运行时。

## 软件验证

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

Windows 使用 `gradlew.bat`，并以真实 Python 可执行文件代替不可用的系统别名。无法运行 AGP/aapt2 时，`tools/run-unit-tests.py` 是纯逻辑后备，不替代 Android 全编译、APK 或完整资源门禁。平台跳过、未运行项与 Lint 警告如实分类，不写成通过。

`tools/verify-plugin-upgrade-gate.py` 检查当前自动提交/启用、用户禁用与安全模式、实际依赖、插件事务及受管资产。正式插件允许未锁定解析、生命周期脚本和 pnpmfile；不能把历史“原生审阅/依赖冻结”的测试口径复活为当前要求。

## 正式打包与签名

显式提供已有 `DSHA_KEYSTORE`、`DSHA_KEYSTORE_PASSWORD`、`DSHA_KEY_PASSWORD`；可配置历史 `DSHA_KEY_ALIAS`。签名身份以 `ci/release-identity.json` 为准，保持 E7E3，不以 debug、A3 或新证书替代。密钥文件名含 debug 不表示正式 APK 可调试；必须核对实际 `debuggable=false`。

```bash
./gradlew :app:assembleStandardRelease :app:assembleLowRelease
```

软件交付需两版完整单测、Release Lint、实际 APK/资产/ELF、签名、插件/应急门禁和精确摘要回执。JNI 静态审计使用对应 release APK，不推荐旧 debug 产物。Low 必须保留 v1；用 `apksigner verify --min-sdk-version 23` 明确核对 v1/v2/v3。

产物固定放 `release/dsha-0.2.0-rc2.apk`、`release/dsha-0.2.0-rc2low.apk` 及 `.apk.sha256`。同版本同 flavor 按当前授权替换，其他历史版本保留；`app/build` 中间包不冒充交付。源码快照、台账和报告绑定最终字节。

`ci-package` 的未签名完整包只用于软件验证，不用于手机安装或 release。配置文件不能证明受保护环境、签名线上运行和仓库保护已配置；实际部署与发布需要相应证据。

## 手机与历史范围

本轮不执行手机操作。以后在对应授权范围内验收，只用同签名正式 APK 覆盖安装，保留对话、配置、插件与文件；不卸载、不清数据、不对正式数据强杀或注入故障。设备回执须绑定实际包摘要、包名、版本码、证书及非破坏性结果，不能从旧报告移用。

旧 build130、build142 及早期调试/独立审计包的步骤与设备结果属于原日期/产物。修改前本页精确原字节保存在 `tools/history/documents/BUILD-build155.md`，摘要见 [本轮保留清单](docs/audits/build156/source-docs-retained.json)；该原件不参与部署或测试发现。历史备份、配置、旧数据与未知原件保护继续维护，退休工具不重新激活。

Linux tmpfs 发布回归不证明持久磁盘/掉电/Android FUSE，Android 6/7、厂商 Root/Shizuku、16 KiB 和外部模型范围依实际回执声明。更多职责与控制见[模块图](docs/module-map.md)和[安全模型](docs/security-model.md)。
