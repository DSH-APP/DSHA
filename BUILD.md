# DSHA 现行构建说明

> 当前发布身份：**`dsh.android.client`**；版本名、code190、E7E3及功能保持不变，覆盖下方历史包名规则。与旧 `com.dsh.client` 的数据独立，通过备份导出/恢复迁移。本轮授权同步 DSH-APP/DSHA 与既有私有维护仓库。[本次记录](docs/releases/v0.2.1-rc.2-build190-package-rename-20261021.md)。


先读 [README](README.md)、[AGENTS](AGENTS.md)、[贡献流程](CONTRIBUTING.md) 与[接手指南](docs/接手指南.md)。应用版本以 `app/build.gradle` 为准，DSH 依赖以 `tools/dsh-runtime/package.json`/锁文件为准，完整交付和未验证范围以本轮报告为准。

> F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` / code190：修复目录服务依赖组合、附件resolver退休及后台临时身份失败后的恢复。Standard选定150类/952项JUnit（3跳过），Low选定150类/952项（76跳过），两版零失败/错误；Release Lint零错误、Standard/Low警告249/162，八项实际APK检查通过。原始失败、纠正及负控分开保留，不称一次连续全绿；无设备/ADB、真实账号/模型或新长时验收。[关联Release入口](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.2107) · [范围与证据](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)。实际远端可得性由Release与独立回执核验。

已发布F12保留原身份：[原记录](docs/releases/v0.2.1-rc.2-build190-fix12-20261021.md) · [原Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413)；F11仍为未上传本地检查点。

本机本轮源码在 `C:\Users\18768\.codex\worktrees\build190-from170\DSHA_RESTART`；`F:\DSHA_RESTART`的旧173检出不是当前构建源。其他机器使用新私有仓库最终tag/完整commit或对应源码附件。

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

Git保留完整应用、资源、锁定生成/校验脚本，以及构建必需且有notice/锁的小型JNI/vendor/解释器预编译依赖；大型rootfs、DSH和recovery生成归档不入Git。来源未知或闭源的vendor限制按实际材料保留，不能宣称所有vendor native均可从源码复现。精确边界与9个显式审计JSON白名单见[CONTRIBUTING](CONTRIBUTING.md)：原6项构建输入元数据加3项历史文档/来源元数据，不开放整个audit目录。

Release完整源码ZIP额外包含固定输入包`locked-current-build-inputs.zip`中的原始文件，按项目路径直接展开，不嵌套该ZIP。F13固定输入包为738,101,893 bytes，SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`，由`tools/ci-assets.py --pack`逐SHA核对；该大小不是完整源码ZIP大小。旧738,099,607/738,099,619-byte输入包及12字节行尾差异属于F10及更早历史，不能代替本轮输入身份。配置工具链后按当前recipe构建，无需独立bundle；Git clone可做source-only检查，完整打包仍需固定原始输入。APK中的变换层不能逆还原全部原始rootfs，不提供从APK恢复所有输入的保证。

可分发源码写LF原字节；`.gitattributes`中的12项managed/canonical bytepin及历史原件保留精确原字节，不能全库规范化已签名输入。小型二进制输入19项按清单保留，不以文本行尾规则改写。共享UUID校验由Ids提供，补齐四处调用依赖并保持历史格式。

F13冻结源码2215成员/200篇Markdown，Java adoption 1029项。分发/格式证明与JUnit执行计数分开。固定输入包738,101,893 bytes，SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`，原始输入直接展开到项目路径；不是完整源码ZIP的大小或摘要。精确继承SHA证明与变更叶fresh检查的实际范围见本批冻结格式回执。

历史F8的999项Java证明、此前分批读取/style等价证明及F7宿主Core重绑定失败/修复正负控仍保留原记录；它们不是F13现行adoption范围或测试数。旧软件、APK与设备结论各属原源码/摘要，最终F13软件与精确APK结果由统一回执绑定。

Ubuntu基础环境身份独立于DSH与UI版本。`offline-rootfs.bin` / `dsh-runtime.bin`为split-runtime-v1，独立应急pinned输入由`tools/recovery-runtime/lock.json`约束，不能用正式环境作为救援依赖。没有大归档的源码检出不能直接当成完整APK，也不能跳过完整性门禁。

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
./gradlew :app:assembleStandardRelease
```

本轮发布Standard APK，软件验证覆盖受影响的两版测试与Release Lint，并核对实际Standard APK/资产/ELF、签名、许可、插件/应急门禁及精确摘要。JUnit选择类、跳过、缓存复用与未跑范围分别记录，不冒称全库clean或完整冷网络构建。JNI静态审计使用对应Release APK，不推荐旧Debug产物；旧Low原件保持，不把Low软件检查写成新Low交付或设备验收。

本地Standard正式产物为 `F:\DSHA_RESTART\release\0.2.1-rc.2\dsha-0.2.1-rc.2.apk`，对应摘要在本地核对并随发布记录。历史版本和旧Low字节保留，`app/build`中间包不冒充交付。现有私有仓库Git不收大生成归档；Release只附正式APK与含固定构建输入的完整源码ZIP，SHA-256写在Release正文，源码ZIP自身README不写该ZIP摘要以免自引用。私钥、用户数据、设备截图/日志和历史取证材料不上传。必要9个显式审计元数据、27个历史构建源码与唯一website测试夹具按交付清单保留，不等于上传历史现场。2054、2330/F9与0140/F10为已发布历史；本页当前为20261021.2107/F13；最终软件及精确APK结果见F13记录，源码commit、两附件及远端状态由关联Release与独立外部回执绑定，旧APK和历史报告不能替代本轮证明。

`ci-package` 的未签名完整包只用于软件验证，不用于手机安装或 release。配置文件不能证明受保护环境、签名线上运行和仓库保护已配置；实际部署与发布需要相应证据。

## 手机与历史范围

本轮全部手机操作已暂停，不进行覆盖安装、启停、输入或模型对话，也不为补证恢复ADB。保留用户数据、会话、配置、插件与未知运行状态；不通过“正常Stop”文案猜测已退出。旧设备回执绑定各自APK和时点，不能移用为新包实测。

旧 build130、build142 及早期调试/独立审计包的步骤与设备结果属于原日期/产物。修改前本页精确原字节保存在 `tools/history/documents/BUILD-build155.md`，摘要见 [本轮保留清单](docs/audits/build156/source-docs-retained.json)；该原件不参与部署或测试发现。历史备份、配置、旧数据与未知原件保护继续维护，退休工具不重新激活。

Linux tmpfs 发布回归不证明持久磁盘/掉电/Android FUSE，Android 6/7、厂商 Root/Shizuku、16 KiB 和外部模型范围依实际回执声明。更多职责与控制见[模块图](docs/module-map.md)和[安全模型](docs/security-model.md)。

维护源码前先看[项目总手册](docs/project-architecture-handbook.md)和[文档总索引](docs/documentation-index.md)；引擎、端口、DNS的当前默认值与历史迁移分别有专页。
