# 构建、验证与提交

> 当前发布身份：**`dsh.android.client`**；版本名、code190、E7E3及功能保持不变，覆盖下方历史包名规则。与旧 `com.dsh.client` 的数据独立，通过备份导出/恢复迁移。本轮授权同步 DSH-APP/DSHA 与既有私有维护仓库。[本次记录](docs/releases/v0.2.1-rc.2-build190-package-rename-20261021.md)。


先读 [README](README.md)、[AGENTS](AGENTS.md) 和[本轮F13范围](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)。历史审计资料的另一会话决策不能覆盖这里的用户要求。

> F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` / code190：修复目录服务依赖组合、附件resolver退休及后台临时身份失败后的恢复。Standard选定150类/952项JUnit（3跳过），Low选定150类/952项（76跳过），两版零失败/错误；Release Lint零错误、Standard/Low警告249/162，八项实际APK检查通过。原始失败、纠正及负控分开保留，不称一次连续全绿；无设备/ADB、真实账号/模型或新长时验收。[关联Release入口](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.2107) · [范围与证据](docs/releases/v0.2.1-rc.2-build190-fix13-20261021.md)。实际远端可得性由Release与独立回执核验。

已发布F12保留原身份：[原记录](docs/releases/v0.2.1-rc.2-build190-fix12-20261021.md) · [原Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413)；F11仍为未上传本地检查点。

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

可规范化源码使用实际LF，19项必要小型二进制及历史原件保持原字节。`.gitattributes`列出的12项managed/canonical精确bytepin不做行尾转换；不要全库改写或通过忽略换行的哈希掩盖包内内容变化。生成的Java、描述符和证明由对应脚本生成，四处UUID调用补齐共享Ids依赖，保持历史UUID格式。

## 准备输入

Release完整源码ZIP额外包含固定输入包`locked-current-build-inputs.zip`中的原始文件，按项目路径直接展开，不嵌套该ZIP。F13固定输入包为738,101,893 bytes，SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`，由`tools/ci-assets.py --pack`逐SHA核对；该大小不是完整源码ZIP大小。旧738,099,607/738,099,619-byte输入包及12字节行尾差异属于F10及更早历史，不能代替本轮输入身份。配置工具链后按当前recipe构建，无需独立bundle；Git clone可做source-only检查，完整打包仍需固定原始输入。APK中的变换层不能逆还原全部原始rootfs，不提供从APK恢复所有输入的保证。

F13冻结源码2215成员/200篇Markdown，Java adoption 1029项。分发/格式证明与JUnit执行计数分开。固定输入包738,101,893 bytes，SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`，原始输入直接展开到项目路径；不是完整源码ZIP的大小或摘要。精确继承SHA证明与变更叶fresh检查的实际范围见本批冻结格式回执。

历史F8的999项Java证明、此前分批读取/style等价证明及F7宿主Core重绑定失败/修复正负控仍保留原记录；它们不是F13现行adoption范围或测试数。旧软件、APK与设备结论各属原源码/摘要，最终F13软件与精确APK结果由统一回执绑定。

APK的约85MB offline-rootfs是经过打包变换的层，不能还原约304MB原始rootfs；本轮不提供“从APK恢复全部构建输入”的helper或承诺。完整源码ZIP的最终SHA-256写在Release正文，不放进该ZIP自身README形成自引用。

完整源码保留构建必需、已有跟踪且具备notice/锁的小型JNI/vendor/解释器预编译依赖；来源未知或闭源限制照实披露，这不等于所有vendor native均可从源码复现。按实际材料说明来源、许可与构建路径，不为“纯源码”口号删除构建消费者依赖。保留清单还包括27个必需历史构建源码和唯一website测试夹具，不进行网站部署。

不上传私钥、用户数据、设备截图/原日志或历史审计原材料。大生成归档不入Git，Release源码ZIP只收登记并核验的原始大构建输入。`docs/audits/`的JSON仅保留以下9项，维持原路径：前6项为构建/CI消费元数据，后3项为真实历史文档保存/来源元数据。它们不是设备原日志，也不是开放整个历史审计目录的例外：

- `docs/audits/build154/retired-audit-sources.json`
- `docs/audits/build154/engineering-retired-sources.json`
- `docs/audits/build156/retired-test-control-sources.json`
- `docs/audits/build156/third-party-license-sources.json`
- `docs/audits/build156/retired-asset-sources.json`
- `docs/audits/build156/retired-environment-data-sources.json`
- `docs/audits/build154/history-document-sources.json`
- `docs/audits/build156/shellservice-source-provenance.json`
- `docs/audits/build156/source-docs-retained.json`

文档闭合另按[文档总索引](docs/documentation-index.md)中的精确清单纳入6篇真实历史说明、已追回并核对原摘要的Termux历史README及5份本轮新建导航副本。导航副本逐字节复制指定原文，不伪称追回的历史保存件；旧历史原件和上游许可证不改写。其余历史JSON台账、设备原始日志/截图与私有取证不因正文引用自动纳入。

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
./gradlew :app:assembleStandardRelease
```

本轮发布Standard，实际包必须核对`com.dsh.client`、code190、E7E3、`debuggable=false`、minSdk30、arm64、资产、许可、ELF、插件与独立应急门禁；两版受影响测试与Release Lint结果分别记录。若后续另行构建Low，仍需明确核对v1/v2/v3与minSdk23；本轮没有新Low发布或手机验收结论。

本轮暂停所有手机操作，不安装、启动、停止、输入或对话，不为补证重新连接ADB。历史同签名覆盖记录保留原包归属，不转记新包；不卸载、不清数据、不注入故障、不生成额外审计APK。

本地正式Standard路径为 `F:\DSHA_RESTART\release\0.2.1-rc.2\dsha-0.2.1-rc.2.apk`；其他历史版本与旧Low原字节保持。最终APK、源码commit/附件和软件证据精确绑定，检查选择器、缓存/增量、警告、跳过及未跑范围如实记录，不称完整冷网络已验。新私有仓库已创建，Release只附正式APK与完整源码ZIP；没有实际tag/上传结果前不声称发布完成，原仓库与dsha.cc不变。

## 修改与评审

分支默认 `codex/`。保持旧 SharedPreferences 键、数据格式读取、文档 ID、原件与未知进程停止屏障。明确记录更改的职责、持久化字段、协议和验证证据。

修缺陷要用实际生产入口或隔离真实依赖重现；源码约束检查和设备运行证据分别记录。原始数据只读，历史记录标版本与时间；没有硬件证据的情况不写成已验证。所有审计项都有处置和残余，不能用一个白名单或未经校准的分数宣称债务已清。

修改前按[文档总索引](docs/documentation-index.md)定位实现、平台依据和测试；涉及默认值/权限/网络时同步维护专页与迁移说明，不只更新代码注释。
