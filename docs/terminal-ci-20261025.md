# 终端 Standard CI 产包合同（2026-10-25）

> 已退役（2026-11-02）：私有仓库 `DSHA-Sunset-mod-3090-Q` 的工作流与配套脚本已从本仓库删除，本文仅作历史记录；文中路径可在 `git log` 中查到。当前 CI 见 [CI 节点图](ci-nodes.md)。

## 边界与已核实身份

- 当前基座：`Sunset-zhongdua-G`，`02f80abeee37f3b107544714c8544ada5a1c40b9`，build197；2026-10-26 build198 将 versionCode 提升到 198、Sunset 测试包包名后缀改为 `.SunsetRNE2`，并补上悬浮球/菜单的会话侧栏；固定输入仍以 build197 为来源，终端内核与运行保护未改。最终产物须绑定 Lead 实际提交的新 SHA，不能拿该基座或参考分支的绿灯替代。
- 仓库：私有 `SunsetRNE/DSHA-Sunset-mod-3090-Q`，默认分支 `codex/prerelease-190`。Git `ls-remote` 和 GitHub API 分别核对；本轮现有 API 账号为 `SunsetRNE`。首次核对时目标终端分支尚未发布，由 Lead 统一提交、push、等待并下载验收。
- 参考 `origin/Sunset-3090-Q` 精确为 `a36112c39d3345535ff2e55dfe1c368228990104`。其 [run 40178009756 / attempt 1](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40178009756) 的 source、APK、完整 Standard 单测、七夹具检查、Lint、签名、APK 校验、上传步骤确实成功；历史发布 job skipped。只迁入这类可验证经验，不 merge，不用 build196 的产品文件覆盖当前 build197。
- 本轮没有触发/重跑远端 CI，没有 tag、Release、公开上传、手机操作或安装权限。新工作流只请求 `contents: read`。

本机 `/root/dsh-bin/gh` 是现有可用的 proot 路径包装器；不用裸 Go CLI 的客体路径猜测替代，不改 HOME，不输出凭据配置，也不读取旧测试私钥 artifact。

## 工作流

`.github/workflows/terminal-regression.yml`（已删除） 仅接受本私有仓库的 `Sunset-zhongdua-G` push 或该分支手动 dispatch；checkout 固定 `github.sha`。两个必要 job：

1. **Terminal source contracts**：当前描述符、notice、builtin、credential、Java 格式、host manifest、source hygiene、i18n、changelog 和文档链接；运行 7 个已登记的输入/CI/描述符/架构宿主回归入口。此 job 不声称执行 Android source-only 或全量单测。
2. **Terminal Standard signed test APK**：严格恢复原始输入 → 保存原始输入回执 → 当前 npm lock/recipe 重建 DSH、备份证明和描述符 → 当前 raw/managed 夹具 → 4 个真实 Java 补丁/独立应急宿主回归 → **未筛选 Standard 全量 JUnit** → 七夹具与终端类门禁 → Standard Release Lint → Standard unsigned assemble → 测试签名 → 精确包身份/ABI/SDK/资产/应急/第三方校验 → APK 与公有证据分别上传。

不会运行 `testLow*` / `lintLow*` / `assembleLow*`；[ci-fast.yml](../.github/workflows/ci-fast.yml) 仅追加终端分支的 push 排除项，防止重复触发其历史双 flavor 流程，不改旧分支工作流的任务合同。现有 Gradle `verifyBackupDependencies` 仍会解析两版 release classpath；这不等于执行 Low 任务，是否进一步按请求 flavor 收窄由 Lead 决定。

完整测试命令显式设置 `dsha.sourceOnlyTests=false`、`dsha.sunsetTest=true`、`dsha.unsignedPackage=true`，无 `--tests`、无资产 `-x`，并 `--rerun-tasks`，避免把旧 up-to-date/source-only 结果当本轮完整执行。CI 保持 x86_64 默认 Robolectric/Conscrypt，不引入本机 ARM 的替代配置。

### runtimeId 与 UI-only 的区别

本轮同时改变 PTY/终端所有权与前台保护，不是仅改 UI。当前 [受管输入合同](../app/src/main/assets/managed-runtime-inputs.json) 已把 TerminalSessionOwner、TerminalTabs、PtySession 等列入 launcherSources；[生成器](../tools/prepare-runtime-descriptor.py) 将所有 launcherInputs 纳入 runtimeId，[生产 RuntimeDescriptor](../app/src/main/java/com/deepseekharness/app/backup/RuntimeDescriptor.java) 也按相同字段重算并拒绝伪造旧身份。因此冻结后重新生成描述符会改变 runtimeId，不能为“UI-only”说法手调 ID、删旧字段或放松核验。归档 pin、Ubuntu10、DSHrc3 不因此改变；新身份沿既有维护屏障、暂存和 RuntimeTrial 事务处理，不授权运行中热覆写。新增负责前台保护的协作者需由 Lead 纳入 launcherSources 并统一生成，纯 UI 布局不必因此纳入该安全合同。

### 固定工具链

[工具链锁](../ci/toolchain.lock.json)：ubuntu-24.04 / Java17 / Node24 / Python3.12 / Gradle9.3.1 / AGP9.1.1 / Android37.0 / build-tools36.0.0 / NDK26.3.11579264。平台包通过 runner 的明确 SDK 根定位 `sdkmanager`，不假设裸命令在 PATH。

2026-10-25 重新 `git ls-remote` 核对所有使用的 action tag 和 SHA 一致：checkout v7.0.1 `3d3c42e5aac5ba805825da76410c181273ba90b1`；setup-java v6.1.0 `b843ebf9b913146b38da2eaa7aeddf76074ae1ad`；setup-node v7.1.0 `949feb2413d6458794dcd2491c4babbbce0c15c1`；upload-artifact v7.0.2 `cf430e030ddbb5b0abf93d22962f4752f3646cd9`。不复制未经重新核实的历史日历结论，不用浮动版本或不安全 Node 回退。

## 完整构建输入

默认使用 [当前固定输入锁](../ci/sunset-inputs.lock.json)：

- [build197 私有 Release](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/releases/tag/v0.2.1-rc.3-20261025.2000)，API `target_commitish` 为当前基座完整 SHA。
- 完整源码 asset ID **673911569**，`DSHA-Sunset-mod-3090-Q-0.2.1-rc.3-20261025.2000-source.zip`。
- 实际下载 **717482691 bytes**，SHA-256 **`b10df2d3b3eeccfd5aee61aba1c8ed116ad1e92a5258f2831f7fcd4df61f6241`**，与 API digest 相同。
- [ci-assets.py](../tools/ci-assets.py) 先校验整个 ZIP，再检查成员白名单/路径/类型/重复/大小，暂存后逐 current pin 验证，成功才恢复输入。只选择下面 12 项，不覆盖或执行附件中的任何产品源码。不从 APK 倒灌已转换运行产物。
- 本轮已在独立临时目录实际提取 12 项并验证 10 个二进制当前 pin。两个 `.inputs.json` 来自已整包验证的源码归档；它们不是独立二进制 pin，当前 recipe 仍由后续生成器验证/重建，不能仅改摘要掩盖过期输入。

| 白名单输入 | bytes | SHA-256 |
|---|---:|---|
| `app/libs/dsha-connect.aar` | 9920250 | `5ba19a2d675c9bbd6f7700901ad1f529a1baf0adaaba43413792a4fd26c88c48` |
| `app/src/main/assets/adb-wheels.tar.gz` | 9951074 | `173000bd60f8d58f90f988320b9b675540806139da9eae64ea78a0e6ecf5d9d4` |
| `app/src/main/assets/dsh-runtime.bin` | 127670584 | `36c3c40e14a665f30531f31678f1b384ee6ed152d5114b7509998af03089baeb` |
| `app/src/main/assets/dsh-runtime.inputs.json` | 11103 | `121a0d3894ef075344c4a140c78ea8e20e03ae253948a4b216e9ed70860d1398` |
| `app/src/main/assets/glibc-python.tar.gz` | 6565414 | `d3f472848bf56dc856f23bc7a20d546703075b4851fd3de9cb07936b52feba9d` |
| `app/src/main/assets/offline-rootfs.bin` | 304037588 | `0a680b5689b900a5313f53a8ae69c11cb76a12aeb47d75a071cdd7162747c00f` |
| `app/src/main/assets/pnpm-runtime.bin` | 3837861 | `08873508c20e5a4af5ffb93a02509aac62a24b91826d8abce9f0d71e9453ce9d` |
| `app/src/main/assets/python-support.bin` | 904360 | `9f8299ea3fa7a1cf84c23087ae80f584599a828585f3175f92da575955a63464` |
| `app/src/main/assets/ubuntu-tools.bin` | 29919475 | `44eeebf82768323fd110be7ed07dffb4d07f369019451b6dfb2cfe5ccc84f052` |
| `app/src/main/assets/ubuntu-tools.inputs.json` | 592 | `b5f1a66c66ee8ece40b98d673557071b0dd8bb455cf731ca33c21d9b563c32a2` |
| `tools/recovery-runtime/archives/recovery-dsh-runtime.bin` | 127670584 | `36c3c40e14a665f30531f31678f1b384ee6ed152d5114b7509998af03089baeb` |
| `tools/recovery-runtime/archives/recovery-rootfs.bin` | 85783057 | `b9fe8a0460bc0d0e96f1cec0d65d46c9ff71a10c6a8d05a390b732a81d30cef3` |

以上输入还不等于全部构建依赖：需要当前 Git 源码/受管小型 JNI 和 vendor、锁定 Gradle/Maven artifacts（包括 Linux aapt2 classifier）、npm 锁定包及 host 夹具依赖、Android SDK/NDK；下载仍依赖各上游可达性和 runner 磁盘。`dsha-connect.aar` 从 build195 开始连 Java 编译也需要，不能省掉后声称 source-only 可编译。正式/独立应急 DSH 归档这次原始字节相同，但运行根/锁和验证合同独立。

手动覆盖只接受 URL+SHA 成对输入，`asset_archive_format` 为 `bundle` 或 `source-archive`；半对即失败。终端工作流不读取旧分支 `SUNSET_ASSET_*` 仓库变量，避免环境中的 build196 覆盖当前默认锁。私有 Release API token 仅用于恢复步骤，HTTPS 跨域重定向不得继续带认证头。

## 七项夹具 scope

`tools/verify-terminal-test-scope.py`（已删除，七夹具核对现由 [verify-unit-results.py](../tools/verify-unit-results.py) 承担） 核对每份原始 JUnit XML 的 tests/failures/errors/skipped 与真实 testcase 一致，拒绝重复身份、隐藏失败、未知 skip。以下每个方法必须恰好一次通过：

1. `runtime.AdbWheelBundleTest.realBundleRebuildsOnlyOmittedEntriesAndPreservesRestoredWheel`：真实随包 wheel 归档。
2. `runtime.RuntimeToolsRegistryAndroidTest.completeProductionChainAppliesAllSpecializedStagesAndIsRepeatable`：真实 raw/managed 完整生产补丁链。
3. 同类 `lanRetirementRejectsModuleDriftAndPreservesOriginalBytes`：模块漂移拒绝且原字节保留。
4. 同类 `lanRetirementRejectsUnknownVersionAndContractDrift`：未知版本/合同漂移拒绝。
5. 同类 `oldLanExactReplacementAndIdempotenceRemainStrict`：旧 LAN 精确替换与幂等。
6. 同类 `previousRuntimeGenerationCannotReceiveCurrentOverlayInPlace`：不同运行时代次不能原地接当前覆盖。
7. `util.ManagedPatchRegistryIntegrationTest.everyActiveModuleRepairsAsAWholeAndRetiredUiNeverRuns`：真实当前 raw 模块的整体修复及退休 UI 不执行。

全限定名前缀均为 `com.deepseekharness.app.`。唯一允许的 skip 是 Standard 不适用的 `vscreen.VirtualScreenLowBoundaryTest.lowFlavorRemainsUnsupportedEvenOnAModernAndroidVersion`；这不是运行 Low。缺夹具不允许 skip，不能把该 allowlist 扩大以“修绿”。

另逐类要求终端回归执行，当前清单：`core.TerminalSessionOwnerTest`、`core.TerminalRuntimeProtectionTest`、`core.TerminalPtyThreadContractTest`、`runtime.TerminalRuntimeTest`、`util.TerminalSessionTest`、`util.TerminalReadyTest`、`util.TerminalTabsTest`、`util.TerminalWorkspacePolicyTest`、`util.TerminalInputTest`、`ui.TerminalInputContractTest`、`ui.TerminalNavigationTest`、`ui.TerminalFloatingMenuTest`、`ui.TerminalActivityLifecycleTest`，共13类。任一类缺失或 skip 即失败；最终类名需随 Lead 实际冻结保持一致。类计数不是代码覆盖率证明。

## 测试签名与 artifact

保持现有显式 `sunsetTest` 开关：applicationId 为 `dsh.android.client.SunsetRNE2`（当前 Gradle `.SunsetRNE2` 后缀，与旧 `.SunsetRNE` 测试包独立共存），versionCode 与当前 Gradle 版本一致（build198），versionName 为当前完整版本加 `-SunsetTest`，非调试、arm64-v8a、minSDK30、targetSDK37。生产 E7E3 证书禁止用于本测试包。

仅签名步骤可读已有 `SUNSET_TEST_KEYSTORE_B64` secret；可选 `SUNSET_TEST_STORE_PASSWORD`，未配置时兼容历史测试口令。没有 secret 则本次 runner 临时生成 RSA3072 测试证书与随机口令。私钥只写明确的 `RUNNER_TEMP` 文件，以 `umask 077` 保护，不进入 Git、cache 或任何 artifact，`always()` 收尾核目标后删除。仅公有证书可上传。

**临时签名不保证与任何旧 Sunset 测试包同证书，不能承诺覆盖安装；不读取旧 artifact 私钥来解决。** 与 E7E3 正式包也不能互相覆盖。此任务没有安装验收。

`ci/terminal-package.py`（已删除） 记录 repository/branch/workflow/full SHA/run ID/attempt、实际版本/包名/ABI、APK bytes/SHA、实际证书 SHA、原输入12项与整个输入包 SHA、重建运行时 SHA、当前 runtimeId 与完整测试回执。其版本直接核当前 Gradle，不把 build196 写死迁入。签名验证后还核公有 PEM 与 APK signer 同证书、APK 不含 keystore；保存回执前重新读取 XML 门禁，而非只信 JSON 摘要。

| artifact | 内容 |
|---|---|
| `dsha-terminal-<8hex>.apk` | 单文件 `archive:false`，由实际文件名命名，最长32 ASCII |
| `terminal-evidence` | ZIP：SHA256SUMS、公有证书、build-info、fixed-inputs、full-unit-tests、签名/badging/精确APK验证回执 |
| `terminal-source` | source 宿主报告，失败也上传 |
| `terminal-tests` | Standard原始JUnit、runtime宿主和Lint报告、已生成验证日志，失败也上传 |

默认保留14天。不是 Release，也没有 tag。APK 和证据分开上传，不能把 `archive:false` 当多文件接口。

## Lead 运行及下载验收

1. 冻结产品/UI/Java 后更新描述符、格式清单和 i18n，跑本地门禁；统一 commit/push 到目标分支。不要用旧源码文件替代冲突或重新生成的产品输入。
2. 按最终完整 SHA 查询 `terminal-regression.yml` 的实际 run，核同仓库/分支/SHA/run_attempt，两必要 job 与输入恢复、完整测试、七夹具、Lint、assemble、sign、精确APK验证、upload 均真正执行。
3. 下载同 run/attempt 的 APK 和 evidence，核 API artifact ID/digest/expired、实际文件类型与字节。解开 evidence 后执行 `sha256sum -c SHA256SUMS`；APK原文件与证据放同目录。artifact存在不等于已下载验证。
4. `apksigner verify --verbose --print-certs`、`aapt2 dump badging` 与 build-info 交叉核包名/当前版本/非调试/ABI/SDK/证书；核完整 commit/run/attempt。精确内容验证需要同提交及对应的原始输入、当前 raw/managed 夹具，不能悄悄省略缺少的夹具检查。
5. 单列本地准备、已push、CI、下载、签名/内容核验、安装（本轮不做）各状态。

本机保留原件（不进入 Git）：

```text
/tmp/dsha-ci-reference-20261025/build197-source.zip
/tmp/dsha-ci-reference-20261025/build197-inputs/
/tmp/dsha-ci-reference-20261025/original-inputs.json
```

## 本轮准备验证与失败记录

- 当前 Git/API 身份、参考分支精确成功 job/step、build197资产 metadata、实际整包下载 SHA、12项白名单隔离恢复及10二进制pin：通过。
- CI helper/工作流/包身份/XML负控及完整回执集成：22项；输入对解析：5项；ci-assets恢复边界：24项，合计51项通过。包括七项分别 missing/skipped/failure/error/duplicate、未知skip、缺终端类、伪造XML计数、生产证书/错误版本/ABI拒绝，以及实际临时ZIP/报告形成回执后的错attempt/测试摘要篡改/夹带私钥成员负控（不使用真实私钥）。
- `py_compile`、toolchain/source-lock解析及 `git diff --check` 通过。host manifest登记216项通过；仓库文档链接检查51篇/1235有效/0警告/0错误（其发现范围不包含所有未跟踪新文档，新文档本地相对链接另检）。
- 一批检查在输出测试/语法/文档通过后，末尾Git调用遭本机 `proroot SIGSEGV`、exit139；随即只读核对工作区状态并单独重跑全部51测试与 `git diff --check` 成功，环境失败原件保留，不推断为Git写入或CI失败。
- 首次语法解析尝试发现本机缺 PyYAML、Node缺 yaml/js-yaml，均以非零退出保留。Lead随后提供 PyYAML 后实际 YAML解析、19个bash步骤语法和3个inline Python脚本语法通过；不把前次失败改记为一次连续通过。
- 工作中10条source合同检查8通过；descriptor因并行编辑的 `PtySession.java` 摘要过期失败，i18n因终端界面8项新拼接失败，已交Lead收尾，不放宽门禁。此处不称工作区冻结全绿。
- 此准备任务未在本机执行 Android全量、Lint、assemble或签名，也未运行新远端工作流、下载新APK、安装。上述预计门禁不替代其真实结果。

## 实际运行记录（Lead 执行，逐 SHA/attempt 归属）

| 运行 | 提交 | 结果 |
|---|---|---|
| [40201270323](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40201270323) / a1 | `6666aaa0` | source job 全通过；apk job 在生产源码编译通过后，**测试源码**编译失败（新测试的 Robolectric 通用 `shadowOf()` 触发 Android37 已移除的 `FingerprintManager` 类型）。未执行 JUnit、未签包。 |
| [40203536948](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40203536948) / a1 | `2a0af1a` | source job 全通过；完整 Standard 单测 2514 项执行、1 项失败：虚拟屏 `VirtualScreenObservedPixelsTest` 在自身 25MB 位图分配处 `OutOfMemoryError`，与终端改动的断言无关。 |
| 40203536948 / a2（同提交重跑失败作业） | `2a0af1a` | 单测、七项真实夹具、13 类终端门禁、Release Lint、未签名 Standard 打包均实际通过；签名步骤的回执核验把 aapt2 badging 最低 API 键名写死为 `sdkVersion`，报 `TERMINAL_APK_SDK`，APK 未上传。 |
| [40207701003](https://github.com/SunsetRNE/DSHA-Sunset-mod-3090-Q/actions/runs/40207701003) / a1 | `ff4dbb0` | **两必要作业成功、无跳过步骤**；2514 项单测 0 失败/0 错误/1 允许跳过，七夹具与 13 类终端门禁、Lint、assemble、签名、精确 APK 校验与上传均执行。APK 已下载并独立核验（307,823,326 字节，SHA-256 `df3a96d5…56f58`，临时测试证书，未安装）。 |

三项修订均不放宽门禁：测试改用 `Shadow.extract` 精确类型端口；测试 JVM 上限提高到 2048m 以消除与断言无关的位图 OOM；回执同时接受 `sdkVersion`/`minSdkVersion` 并仍精确要求 30/37，且在 build-info 记录实际键名（本次为 `minSdkVersion`）。后续运行单独记账，不能把上表合并成一次通过。


