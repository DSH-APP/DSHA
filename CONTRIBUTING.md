# 构建、验证与提交

> 当前发布身份：`DSH-A-Su` / `io.github.sunsetrne.dsha` / `0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1` / versionCode 356 / 单一 Standard；使用 Mod 自有发布证书。完整身份与签名入口见 [README](README.md)、[AGENTS](AGENTS.md) 与 [ci/release-identity.json](ci/release-identity.json)。

先读 [README](README.md)、[AGENTS](AGENTS.md)、[BUILD](BUILD.md) 与[文档总索引](docs/documentation-index.md)。F10–F13、build190 等记录只描述各自当时的源码、APK、测试与设备状态，不能覆盖当前要求，也不能当作本版证据。

当前固定输入由 [ci/sunset-inputs.lock.json](ci/sunset-inputs.lock.json) 锁定：公开预发布资产 `fixed-inputs-20261105e`，716,787,167 B，SHA-256 `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`，由 `tools/ci-assets.py` 还原。大归档与离线 rootfs 不入 Git；只有源码的检出不能构建完整 APK。

项目特有操作见[接手指南](docs/接手指南.md)，职责见[模块图](docs/module-map.md)，约束见[工程规范](docs/engineering-standard.md)与[ADR 0001](docs/adr/0001-build154-boundaries.md)。`tools/history` 只保留退休工具与原件，不进入 APK 或自动测试。

## 源码与工具身份

Java 17，单模块 `:app`。单一 Standard：系统 WebView、最低 API 30、arm64-v8a；包名 `io.github.sunsetrne.dsha`，测试变体 `io.github.sunsetrne.dsha.test`。Low/GeckoView 兼容线已删除，不再声明 Android 10 及以下可用。

- 应用版本及固定文件名规则：`app/build.gradle`。
- DSH 版本及 npm 内容：`tools/dsh-runtime/package.json`、`package-lock.json`。
- 随包工具：`tools/runtime-tools.lock.json`。
- 内置插件：`app/src/main/assets/builtin-plugins.json`。
- 运行期补丁 active/specialized/retired：`app/src/main/assets/runtime-patches.json`。
- 独立应急归档：`tools/recovery-runtime/lock.json`。正式环境更新不能擅自替换独立应急锁。
- CI 工具与 Mod 发布身份：`ci/toolchain.lock.json`、`ci/release-identity.json`。

可规范化源码使用实际LF，19项必要小型二进制及历史原件保持原字节。`.gitattributes`列出的12项managed/canonical精确bytepin不做行尾转换；不要全库改写或通过忽略换行的哈希掩盖包内内容变化。生成的Java、描述符和证明由对应脚本生成，四处UUID调用补齐共享Ids依赖，保持历史UUID格式。

## 准备输入

Release 完整源码 ZIP 额外包含固定输入包 `locked-current-build-inputs.zip` 中的原始文件，按项目路径直接展开，不嵌套该 ZIP。当前固定输入为 `fixed-inputs-20261105e`，716,787,167 B，SHA-256 `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`，由 `tools/ci-assets.py --pack` 逐 SHA 核对；该大小不是完整源码 ZIP 大小。配置工具链后按当前 recipe 构建，无需独立 bundle；Git clone 可做 source-only 检查，完整打包仍需固定原始输入。APK 中的变换层不能逆还原全部原始 rootfs。

build190 的 F13 冻结统计、旧 738,101,893 B 输入包与 F10/F11 记录属于历史；它们不能代替当前输入身份，也不能把旧软件或设备结论转记为本版结果。当前软件、APK 与发布结果由本轮 CI run 与 Release 回执绑定。

历史 F8 的 999 项 Java 证明、此前分批读取 / style 等价证明及 F7 宿主 Core 重绑定失败 / 修复正负控保留原记录；它们不是当前 adoption 范围或测试数。

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
./gradlew :app:testStandardDebugUnitTest :app:lintStandardRelease
python3 tools/verify-stability.py --stage software
python3 tools/run-host-tests.py --stage runtime
```

不能运行 AGP/aapt2 的宿主可以使用 `tools/run-unit-tests.py` 作为纯逻辑后备；它编译测试辅助类，但不替代 Android 构建。`-O` 测试被入口拒绝，发布核验的检查也不会被 Python 优化删去。

`ci-fast` 执行源码和协议检查。完整包 `ci-package` 需要核对过的二进制输入，并且只生成未签名包；不得用于安装。仓库保护、线上工作流完成和实际 Linux/Android 矩阵必须以真实运行证据记录，不由配置文件推导。

## 正式打包

显式配置 `DSHA_KEYSTORE`、`DSHA_KEYSTORE_PASSWORD`、`DSHA_KEY_PASSWORD`，可另设 `DSHA_KEY_ALIAS`（默认 `dsha-a-su`）。缺少口令不再隐式使用缺省值。正式包只允许 `ci/release-identity.json` 记录的 Mod 发布证书，不使用 debug、E7E3 或临时证书。

```bash
./gradlew :app:assembleStandardRelease
```

当前只发布 Standard，实际包必须核对 `io.github.sunsetrne.dsha`、versionCode 219、`debuggable=false`、minSdk 30、arm64、资产、许可、ELF、插件与独立应急门禁。Low 已删除，不再构建、发布或声明 Android 10 及以下可用。

正式 CI 发布走 [`.github/workflows/release-production.yml`](.github/workflows/release-production.yml)：`workflow_dispatch` 绑定精确 commit，从公开预发布资产还原固定输入，跑完整单测 / Java 格式 / Lint / 文档门禁，用仓库 Secrets 中的证书签名，并创建 tag + prerelease、上传 APK、证据 zip、`build-info.json` 与 `SHA256SUMS`；默认同 run 另附 `.debug` / `.dev` 共存测试包与公开测试证书（`test_packages=none` 时不附），`dry_run=true` 只构建校验、不建 tag/Release。触发参数与核验见 [CI 构建与发布](docs/ci-release.md)。

不上传私钥、用户数据、设备截图或原日志；不安装、不注入故障、不生成额外审计 APK，除非当次任务明确授权。最终 APK、源码 commit、附件与证据必须精确绑定；检查选择器、缓存 / 增量、警告、跳过及未跑范围如实记录。没有实际 run、tag 与上传结果前不声称发布完成。

## 修改与评审

分支默认 `codex/`。保持旧 SharedPreferences 键、数据格式读取、文档 ID、原件与未知进程停止屏障。明确记录更改的职责、持久化字段、协议和验证证据。

修缺陷要用实际生产入口或隔离真实依赖重现；源码约束检查和设备运行证据分别记录。原始数据只读，历史记录标版本与时间；没有硬件证据的情况不写成已验证。所有审计项都有处置和残余，不能用一个白名单或未经校准的分数宣称债务已清。

修改前按[文档总索引](docs/documentation-index.md)定位实现、平台依据和测试；涉及默认值/权限/网络时同步维护专页与迁移说明，不只更新代码注释。
