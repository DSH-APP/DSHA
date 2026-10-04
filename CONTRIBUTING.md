# 构建、验证与提交

先读 [README](README.md)、[AGENTS](AGENTS.md) 和 [本轮采用的约束](docs/audits/build154/POLICY.md)。审计资料的另一会话决策不能覆盖这里的用户要求。

项目特有操作见[现行接手指南](docs/接手指南.md)，职责见[模块图](docs/module-map.md)，约束见[工程规范](docs/engineering-standard.md)与[ADR 0001](docs/adr/0001-build154-boundaries.md)。退休工具与文档原件见 `tools/history` 和对应保存清单；它们不进入APK或自动测试执行。

## 源码与工具身份

Java 17，单模块 `:app`。Standard 使用系统 WebView、最低 API 30；Low 最低 API 23、Gecko 备用内核。两版包名均为 `com.dsh.client`，仅 arm64。

- 应用版本及固定文件名规则：`app/build.gradle`。
- DSH 版本及 npm 内容：`tools/dsh-runtime/package.json`、`package-lock.json`。
- 随包工具：`tools/runtime-tools.lock.json`。
- 内置插件：`app/src/main/assets/builtin-plugins.json`。
- 运行期补丁 active/specialized/retired：`app/src/main/assets/runtime-patches.json`。
- 独立应急归档：`tools/recovery-runtime/lock.json`。正式环境更新不能擅自替换独立应急锁。
- CI 工具与历史发布证书：`ci/toolchain.lock.json`、`ci/release-identity.json`。

文本使用实际 LF，二进制保持原字节。不要通过忽略换行的哈希掩盖包内内容变化。生成的 Java、描述符和证明必须由对应脚本生成。

## 准备输入

离线 rootfs 及大型生成资产不提交。已有资产可以按源码描述符完整核对后用 `tools/ci-assets.py --pack <编号输出.zip>`传递到干净 CI；它不含密钥、用户数据或 APK。CI 必须使用固定 HTTPS 地址和 SHA-256，不能用未知最新包。

完整生成入口：

```bash
python3 tools/prepare-dsh-runtime.py
python3 tools/build-standard-runtime.py
python3 tools/prepare-ubuntu-tools.py
python3 tools/prepare-runtime-tools.py
python3 tools/generate-builtin-plugins.py
python3 tools/generate-credential-paths.py
node tools/prepare-web-compat.mjs
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/prepare-test-runtime.py
```

基础 Ubuntu 原始归档仍是单独固定输入，不能把依赖安装或 dsh 升级误当成基础环境版本升级。独立应急的历史 pinned 副本要保留，不能因正式归档变化自动解除摘要核对。

## 验证

源码快速层不依赖私钥：

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
```

完整输入准备后，运行当前正式源集的单测和 Release Lint：

```bash
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/verify-stability.py --stage software
python3 tools/run-host-tests.py --stage runtime
```

不能运行 AGP/aapt2 的宿主可以使用 `tools/run-unit-tests.py` 作为纯逻辑后备；它编译测试辅助类，但不替代 Android 构建。`-O` 测试被入口拒绝，发布核验的检查也不会被 Python 优化删去。

`ci-fast` 执行源码和协议检查。完整包 `ci-package` 需要核对过的二进制输入，并且只生成未签名包；不得用于安装。仓库保护、线上工作流完成和实际 Linux/Android 矩阵必须以真实运行证据记录，不由配置文件推导。

## 正式打包

显式配置 `DSHA_KEYSTORE`、`DSHA_KEYSTORE_PASSWORD`、`DSHA_KEY_PASSWORD`，可另设历史 `DSHA_KEY_ALIAS`。缺少口令不再隐式使用缺省值。只允许已核对的 E7E3 历史证书，不换成 debug、A3 或临时 CI 证书。

```bash
./gradlew :app:assembleStandardRelease :app:assembleLowRelease
```

两版必须验证 v1/v2/v3、包名、递增版本码、`debuggable=false`、最低 API、ABI、实际资产、ELF、插件与独立应急门禁。`apksigner verify --min-sdk-version 23` 用于明确核对三种签名存在；平台默认最小版本的验证结果不能被误读为旧签名缺失。

用户授权的正式包验收只用同签名覆盖安装，不卸载、不清数据、不注入故障、不生成额外审计 APK。本轮用户要求跳过手机操作，报告必须写明该限制。

通过的软件产物使用固定 `release/dsha-0.2.0-rc2[low].apk` 和 `.apk.sha256`。同版本同 flavor 替换，其他历史版本保留。源码快照、完整台账和检查日志绑定最终 APK 字节；无新证据时不声称已发布到 GitHub 或部署到 dsha.cc。

## 修改与评审

分支默认 `codex/`。保持旧 SharedPreferences 键、数据格式读取、文档 ID、原件与未知进程停止屏障。明确记录更改的职责、持久化字段、协议和验证证据。

修缺陷要用实际生产入口或隔离真实依赖重现；源码约束检查和设备运行证据分别记录。原始数据只读，历史记录标版本与时间；没有硬件证据的情况不写成已验证。所有审计项都有处置和残余，不能用一个白名单或未经校准的分数宣称债务已清。
