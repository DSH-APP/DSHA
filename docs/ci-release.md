# CI 构建与发布（公开仓库正式成品）

更新：2026-11-10 / build353（正式 Release 附带共存测试包 + dry_run）。工作流：[`.github/workflows/release-production.yml`](../.github/workflows/release-production.yml)、[`.github/workflows/_node-test-packages.yml`](../.github/workflows/_node-test-packages.yml)。
目标仓库：`SunsetRNE/DSHA-Sunset-mod`（`main` 分支）。本文说明如何用 GitHub Actions 产出并发布
**正式签名成品**（`io.github.sunsetrne.dsha`），以及需要哪些仓库 Secrets、产物如何核验。

官方身份（`dsh.android.client` / 历史 E7E3）走 [`.github/workflows/release-official.yml`](../.github/workflows/release-official.yml)：
节点与门禁相同，构建传 `-Pdsha.identity=upstream`，只在 `DSH-APP/DSHA` 的 `main` 运行，tag 为 `v<version>`
（如 `v0.2.2-rc.1`），签名使用本仓库 Secrets 中的历史发布证书；旧的 `release-upstream.yml` 已随官方身份接续删除。
两个工作流在对方仓库直接跳过；身份对照见 [Mod 身份与签名](mod-identity-and-signing.md)。

## 1. 边界

- 只做 **Standard（Android 11+ / arm64）正式包**；不运行 low/Gecko 兼容线，不再有独立上岛包。
- 只由 `workflow_dispatch` 显式触发；运行必须绑定精确 40 位 commit，且该 commit 就是 `main` 分支当前 HEAD。
- 发布目标是 **tag + GitHub prerelease**（预发布），不覆盖历史 Release，不创建正式 stable release。
- 固定输入（约 684 MiB，12 项）不进 git，由 [固定构建输入](fixed-build-inputs.md) 的预发布资产按 SHA-256 还原。
- 未验证范围：工作流通过只证明该 commit 在 GitHub 托管的 `ubuntu-24.04` 上编译、门禁、单测、签名与发布流程成功；
  不证明真机安装、覆盖升级、厂商 ROM 兼容、功耗或外部模型服务。

## 2. 触发参数

| 参数 | 要求 | 示例 |
|---|---|---|
| `release_tag` | 新 tag，格式 `dsha-a-su-build<版本码>-YYYYMMDD-HHMMSS` | `dsha-a-su-build353-20261110-080000` |
| `expected_sha` | `main` 上待发布的完整 40 位 commit | `0123abcd…` |
| `test_packages` | 同 run 附带发布的共存测试包：`debug+dev`（默认）/ `debug` / `none` | `debug+dev` |
| `dry_run` | `true` 时照常构建与校验（含测试包），但不创建 tag/Release | `true` |

`release_tag` 对应的日历时间必须合法；tag 已存在时工作流在任何构建前失败，不会覆盖已有发布。
`dry_run=true` 同样要求一个未被占用的新 tag（只是不创建它），用于在正式发布前验证整条链。

```bash
# 正式发布（默认附带 .debug + .dev 共存测试包）
gh workflow run release-production.yml \
  --repo SunsetRNE/DSHA-Sunset-mod \
  --ref main \
  -f release_tag=dsha-a-su-build353-20261110-080000 \
  -f expected_sha=<40位commit>

# dry_run 验证：构建、签名、校验、合并证据全部执行，不创建 tag/Release
gh workflow run release-production.yml \
  --repo SunsetRNE/DSHA-Sunset-mod \
  --ref main \
  -f release_tag=dsha-a-su-build353-20261110-080000 \
  -f expected_sha=<40位commit> \
  -f test_packages=debug+dev \
  -f dry_run=true
```

## 3. 仓库 Secrets

正式签名私钥只保存在仓库 Actions Secrets（写入后不可读回），不进仓库、不进日志：

| Secret | 内容 |
|---|---|
| `DSHA_KEYSTORE_B64` | Mod 发布 keystore（`dsha-a-su-release.jks`）的 base64 |
| `DSHA_KEYSTORE_PASSWORD` | store password |
| `DSHA_KEY_PASSWORD` | key password（通常与 store password 相同） |
| `DSHA_KEY_ALIAS` | 默认 `dsha-a-su` |

```bash
base64 -w0 dsha-a-su-release.jks | gh secret set DSHA_KEYSTORE_B64 \
  --repo SunsetRNE/DSHA-Sunset-mod
gh secret set DSHA_KEYSTORE_PASSWORD --repo SunsetRNE/DSHA-Sunset-mod --body "$PW"
gh secret set DSHA_KEY_PASSWORD --repo SunsetRNE/DSHA-Sunset-mod --body "$PW"
gh secret set DSHA_KEY_ALIAS --repo SunsetRNE/DSHA-Sunset-mod --body dsha-a-su
```

runner 把 keystore 解码到 `$RUNNER_TEMP`（权限 077），构建后 `if: always()` 删除；口令用 `::add-mask::` 遮蔽。
证书的公开 SHA-256 记录在 [`ci/release-identity.json`](../ci/release-identity.json)，私钥本身不公开。

共存测试包使用**独立测试证书**，只保存在下面两个 Secrets，绝不与正式证书混用：

| Secret | 内容 |
|---|---|
| `SUNSET_TEST_KEYSTORE_B64` | 测试 keystore（PKCS12，alias `sunset-test`）的 base64 |
| `SUNSET_TEST_STORE_PASSWORD` | 测试 keystore 的 store / key 口令 |

正式发布要求这两个 Secret 存在（`require_configured_key: true`）；缺失时 `test-packages` 节点直接失败，
不会退化为一次性证书。测试证书必须与 Mod、upstream 两个正式证书都不同，该断言在签名节点内实跑。
`on demand` 的 [test-package-variants.yml](../.github/workflows/test-package-variants.yml) 不强制要求
Secrets：未配置时使用一次性证书（只能全新安装，不能覆盖升级）。

## 4. 工作流节点

工作流拆为节点（结构与耗时依据见 [CI 节点图](ci-nodes.md)）；构建与门禁节点都 checkout `expected_sha`，publish 与 dry-run-summary 只下载同一 run 的 artifact：

1. **bind**：`expected_sha == GITHUB_SHA`、tag 格式与日历时间、固定输入锁（`ci/sunset-inputs.lock.json`）解析、
   `ci/release-identity.json` 的包名检查；tag 或 Release 已存在时在任何构建前失败。
2. **并行门禁**（都依赖 bind）：
   - **contracts**：固定输入/描述符/第三方声明/内置插件/凭据路径/测试清单/源码整洁/UI i18n/changelog/文档链接。
   - **java-format**：`tools/format-java-tree.py --check`。
   - **host-regression**：设备桥、shell 策略、虚拟屏 anchor、插件事务、发布与 CI 脚本、宿主测试清单等子集。
   - **inputs**：按锁 URL + SHA-256 还原 12 项、按 npm lock 重建受管 DSH 运行时并重算描述符，打包为同 run 交接。
3. **unit** 与 **lint**（依赖 inputs，二者并行）：
   完整单测 `-Pdsha.sunsetTest=true -Pdsha.sourceOnlyTests=false`，要求 0 failure / 0 error / 0 skipped，
   且七个外部夹具用例各执行一次并通过；`:app:lintStandardRelease`。
4. **build-signed**（依赖以上全部）：还原交接、对下载的 JUnit XML 重新核对并写出 `out/full-unit-tests.json`，
   用 Secrets 配置 `DSHA_KEYSTORE*` 执行 `:app:assembleStandardRelease`（不带 `-Pdsha.sunsetTest`）；
   `apksigner` 证书 SHA-256 必须等于 `ci/release-identity.json`，`aapt2 dump badging` 核对包名、
   versionCode/versionName、minSdk 30、arm64、非 debug、标签 `DSH-A-Su`；生成证据 zip、`build-info.json`、
   `SHA256SUMS` 与发布说明，`if: always()` 删除私钥，再以 1 天保留的 artifact 交给 publish。
5. **test-packages**（与 build-signed 并行，`test_packages != none` 时；复用
   [`_node-test-packages.yml`](../.github/workflows/_node-test-packages.yml)）：按 `debug+dev` / `debug` 的
   matrix 构建 `:app:assembleStandardDebug -Pdsha.debugPackage=true -Pdsha.unsignedPackage=true`（debug）与
   `:app:assembleStandardRelease -Pdsha.devPackage=true -Pdsha.unsignedPackage=true`（dev），
   用测试证书 Secrets 签名，逐项核对包名后缀（`.debug` / `devSuffix`）、versionName 后缀
   （`-SunsetDebug` / `-SunsetDev`）、debuggable、证书不等于正式/上游证书，并跑
   `verify-dsh-upgrade-apk` / `verify-recovery-apk` / `verify-third-party-apk` / `verify-apk-managed-assets`；
   持测试证书的节点同样 `gradle-cache: 'false'`，私钥只在 `RUNNER_TEMP` 内且 `if: always()` 删除。
   每个 variant 产出 1 天保留的证据 artifact（APK、`build-info.json`、`SHA256SUMS`、公开测试证书）。
6. **publish**（唯一有 `contents: write` 的节点，不 checkout、不接触 Secrets）：核对 `SHA256SUMS` 与
   `build-info.json` 的 commit / run / attempt；下载测试包证据，用随证据下发、并按 `build-info.json` 摘要核对的
   [`tools/release-test-packages.py`](../tools/release-test-packages.py) 合并 `testPackages` 段、`SHA256SUMS`
   与发布说明「共存测试包」小节，再次 `sha256sum -c`；最后创建 tag（指向该 commit）+ prerelease 并上传
   正式 APK、证据 zip、`build-info.json`、`SHA256SUMS`，以及启用时的测试包 APK 与测试证书。
   `dry_run=true` 时 publish 跳过，由 **dry-run-summary** 执行同样的下载/合并/校验，并把将创建的命令与资产摘要
   写进 job summary。

## 5. 产物与核验

| 产物 | 说明 |
|---|---|
| `dsha-a-su-<commit8>.apk` | 正式签名成品，包名 `io.github.sunsetrne.dsha` |
| `dsha-a-su-debug-<commit8>.apk` | `.debug` 共存测试包（debuggable，测试证书签名，`debug+dev` 或 `debug` 时） |
| `dsha-a-su-dev-<commit8>.apk` | `.dev` 共存测试包（release 构建、非 debuggable，测试证书签名，`debug+dev` 时） |
| `dsha-a-su-test-cert.pem` | 测试证书公钥；与正式证书不同，只用于核对测试包签名 |
| `dsha-a-su-build-evidence.zip` | 单测 XML、Lint、签名输出、badging、`build-info.json` |
| `build-info.json` | commit、run ID、APK/固定输入 SHA-256、证书、版本、单测汇总；附带测试包时为每个 variant 追加 `testPackages` 条目 |
| `SHA256SUMS` | 正式 APK/证据/构建信息与全部附带测试包资产的 SHA-256 |

安装或分发前至少核对：

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs dsha-a-su-<commit8>.apk   # 证书 SHA-256 必须为 a4b0413c…5d5d
aapt2 dump badging dsha-a-su-<commit8>.apk | head -3      # io.github.sunsetrne.dsha / build 版本码
# 共存测试包（默认资产）：证书必须与正式证书不同、包名带 .debug / .dev、.dev 非 debuggable
apksigner verify --print-certs dsha-a-su-debug-<commit8>.apk
aapt2 dump badging dsha-a-su-dev-<commit8>.apk | head -3
```

`build-info.json` 的 `assetBundleSha256` 必须等于 [固定构建输入](fixed-build-inputs.md) 记录的 bundle SHA-256，
`certificateSha256` 必须等于 `ci/release-identity.json`，`commit` 必须与 tag 指向的 commit 一致。

## 6. 失败与重试

- tag 已存在、`expected_sha` 与运行 commit 不符、固定输入 SHA-256 不符、证书不符、单测有失败/跳过、
  Lint/格式/文档链接失败：工作流在创建任何 tag 或 Release 前失败，现场日志与上传证据保留。
- 失败后**不要**使用同一 tag 重跑：修正源码后换新 commit 与新 tag；旧 tag 若已创建需人工确认后再处理。
- publish 只接受本 attempt 的 build-signed 产物：单独重跑 publish 会因 attempt 不符被拒绝（避免对结果未知的发布自动重放）。
  先核对远端 tag/Release 实际状态，再用 Re-run all jobs 或换新 tag 重新触发。
- 上传资产失败时 Release 可能只创建了 tag：核对 `gh release view <tag> --json assets`，不要盲目 `--clobber`。
- `dry_run=true` 不会创建 tag/Release，但其余门禁与测试包构建照跑；dry-run-summary 会打印将创建的 tag、
  资产清单与 SHA-256。验证通过后用**同一 `release_tag` / `expected_sha`** 以 `dry_run=false` 重新触发即可发布。
- `test-packages` 节点失败时整条链失败（不发布）：测试证书缺失、被拒绝或与正式证书相同都视为发布门禁失败。
- 本工作流不下载/安装到手机，不做真机验收；设备侧结论必须另附实际 APK、操作与回执。

## 7. 与本地构建的关系

本地 `./gradlew :app:assembleStandardRelease` 与 CI 使用相同的 `verifyPublishCertificate` 与
`ci/release-identity.json`，但 CI 额外要求固定输入、完整单测、Lint、格式与证据归档全部通过。
本地中间包、未签名 CI 包（`-Pdsha.unsignedPackage=true`）不得当作正式交付。

## 8. 测试包变体（on demand）

正式 Release 默认附带 `.debug` / `.dev` 共存测试包（见第 4、5 节），构建逻辑抽在共享节点
[`_node-test-packages.yml`](../.github/workflows/_node-test-packages.yml)。
[test-package-variants.yml](../.github/workflows/test-package-variants.yml) 复用同一节点，只由
`workflow_dispatch` 触发，只上传 Actions artifact（14 天），不创建 tag/Release，也不读取正式签名私钥：

| 目标 | 包名 | 说明 |
|---|---|---|
| `prod-unsigned` | `io.github.sunsetrne.dsha`（upstream：`dsh.android.client`） | 正式包名 unsigned 验证包，**不可安装**，不接触正式签名 |
| `debug` | 正式包名 + `.debug` | `debuggable=true` 的 debug 构建，测试证书签名，供开发调试 |
| `dev` | 正式包名 + `.dev` | release 构建、非 debuggable，测试证书签名，供开发/测试环境覆盖升级 |

输入：`identity`（mod/upstream）、`targets`（all/debug/dev/prod-unsigned）、`run_unit_tests`、`quick`（跳过完整单测与 patch-chains，仅用于 dev 迭代）。两个测试包共用 `SUNSET_TEST_KEYSTORE_B64`；未配置时使用一次性密钥，只能全新安装，不能覆盖升级。测试证书必须与两个正式证书都不同；`prod-unsigned` 的产物不得签名或安装。
