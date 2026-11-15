# 发布流水线耗时压缩（T141）：构建 / 签名拆分、缓存、SDK 预装与证据瘦身

更新时间：2026-11-11。状态：已实现（工作流 + 工具 + 静态门禁）；GitHub 上的实测需合并后以
`dry_run=true` 的发布 run 验证（工作会话不 push / 不 dispatch）。

## 1. 目标与约束

用户要求压缩正式发布（`release-production.yml`）的耗时，并保留全部门禁严格度。硬约束：

- unit / format / lint / contracts / host 门禁一项都不能跳过或放宽；
- 持正式私钥的节点不写共享缓存（本方案进一步收紧为：签名节点不运行 Gradle、不读也不写任何缓存）；
- 正式签名密钥策略不变（证书 SHA-256 仍由 `ci/release-identity.mod.json` 强制核对，
  v1 / v2 / v3 三个签名方案仍全部要求）；
- 不 push、不 tag、不 dispatch 真实发布；验证用本地门禁 + 合并后的 `dry_run=true` 发布 run。

## 2. 基线测量（真实 run，非估算）

数据来自 GitHub Actions API 的逐 job / 逐 step 时间（UTC）：

| run | 版本 | 结论 | 总时长 | 关键路径 |
|---|---|---|---|---|
| `42799176202` | build356 | success | 15:05 | bind 10 s → inputs 97 s → unit 7:02 → build-signed 5:29 → publish 35 s |
| `42744086209` | build353 | success | 15:43 | bind 9 s → inputs 76 s → unit 8:06 → build-signed 5:23 → publish 73 s |
| `42741991734` | build353 尝试 1 | failure（测试包） | — | 门禁全绿，`test-packages` 上传步骤失败 |

build356 各节点耗时（同一 run 内并行段一起列出）：

| 节点 / 步骤 | 耗时 | 备注 |
|---|---|---|
| inputs | 97 s | 固定输入还原 6 s；受管 dsh 运行时重建（`npm ci` + bundle）65 s；打包 / 上传 9 s |
| unit（full） | 7:02 | android-sdk 24 s、交接还原 41 s、Robolectric 缓存 7 s、测试本体 5:35 |
| lint | 3:41 | android-sdk 24 s、交接 45 s、`lintStandardRelease` 2:09 |
| build-signed | 5:29 | assemble 4:13（含 Gradle 冷依赖下载 + v1/v2/v3 签名）；证据打包 11 s；artifact 上传 20 s |
| test-packages（dev / debug） | 5:18 / 5:06 | 构建 3:19 / 3:28；签名 16–19 s；证据上传约 10 s |
| publish | 35 s | 下载 bundle 13 s、合并测试包证据、创建 tag/Release 并上传 |

关键观察：

1. **unit 是最大单项**（7:02），且在 inputs 之后才启动；build-signed 还排在 unit/lint 之后才**开始**构建，
   等于把 4:13 的编译完全串在 7 分钟测试之后。
2. 证据包 298,050,193 B 与正式 APK 316,705,177 B 几乎一样大：证据 zip 里重复内嵌了 APK。
3. `android-sdk` 步骤每次 `sdkmanager` 联网；runner 镜像（`actions/runner-images` 的
   `Ubuntu2404-Readme.md`）已预装 `android-37.0`、`build-tools 36.0.0`，但 NDK 只有 27.3 / 29 / 30，
   本项目锁定的 26.3.11579264 仍需下载。build353 第 2 次 run 的 `sdkmanager` 曾挂起 44 分钟，无超时保护。
4. 固定输入还原实测只有 6–15 s（GitHub CDN 到 runner 很快），不是瓶颈。
5. `test-packages` 与 `build-signed` 并行，两者同时结束 → 它当前不占关键路径；但若只拆分正式构建，
   它会立刻变成新的关键路径（5:18），所以在同一改动里把它也拆成「编译 / 签名」两段。

## 3. 六项方向的结论

| # | 方向 | 结论 | 依据 |
|---|---|---|---|
| 1 | build / sign 拆分 | **实施** | 构建节点无 Secrets，可与门禁并行并复用 Gradle 依赖缓存；签名节点只做签名与复验 |
| 2 | 固定输入缓存 | **部分实施**：受管运行时 npm 缓存；整包固定输入不缓存 | 还原仅 6–15 s；684 MB 进 actions/cache 会占 10 GB 配额且读写倒挂 |
| 3 | SDK / NDK 预装与超时 | **实施**（预装检测 + 超时 / 重试）；NDK 不缓存 | 镜像已有 platform / build-tools；NDK 26.3 未预装，解压后约 2.9 GB，缓存收益接近 0 |
| 4 | 测试包变体编译缓存 | **实施**：共享节点新增 `unsigned_only` / `unsigned_artifact_prefix` 两段式，只有无证书编译段可缓存 | 见第 5 节；签名仍在全部门禁之后 |
| 5 | 证据包瘦身 | **实施**：证据 zip 不再内嵌 APK | 298 MB → 约 13 MB；`SHA256SUMS` 仍校验 APK + 证据 + build-info |
| 6 | 量化与回退 | **实施**：本文 + `.SESSION-DONE` + 回退说明 | 见第 6、7 节 |

未实施但已评估的候选（需要单独验证，不塞进本次）：

- **unit `forks=2`**：预估 7:02 → 约 4:00，是下一瓶颈；但 Robolectric 并发隔离与夹具语义需要单独实测。
- **Gradle `--build-cache`**：`~/.gradle/caches` 已被 `setup-java` 缓存覆盖，但启用构建缓存后
  需要证明任务缓存键不会让发布包含陈旧产物；本次只启用依赖缓存（依赖仍由 `gradle/verification-metadata.xml` 核对）。
- **NDK 预装替代**：把 `ndkVersion` 升到镜像自带的 27.3 会改变原生库构建链，属于构建输入变更，不在提速范围内。

## 4. 工作流实现（release-production.yml）

```
bind ─┬─► contracts ──────────────────────────────┐
      ├─► java-format ────────────────────────────┤
      ├─► host-regression ────────────────────────┤
      └─► inputs ─┬─► unit ───────────────────────┤
                  ├─► lint ───────────────────────┤
                  ├─► build-unsigned（无 Secrets，可缓存）
                  └─► test-packages-unsigned（无 Secrets，可缓存）
   （门禁与两个 unsigned 构建并行）→ sign-verify / test-packages（持各自证书，无缓存）→ publish
```

**build-unsigned**（`needs: [bind, inputs]`）

- `gradle-cache: 'true'`、`android-sdk ndk: true`、还原 inputs 交接；
- `:app:assembleStandardRelease -Pdsha.identity=mod -Pdsha.unsignedPackage=true` 产出未签名 APK；
- 未签名阶段先跑两道门禁（都在无 Secrets 节点，签名不会掩盖结果）：
  - [`tools/sign-release-apks.py --check-only`](../../tools/sign-release-apks.py)：文件名必须是 `*-unsigned.apk`、
    `apksigner verify` 必须失败（确实未签名）、`aapt2` badging 的包名 / versionCode / versionName
    （与源码和 `output-metadata.json` 双重比对）/ minSdk 30 / arm64-v8a / 非 debuggable / 非测试身份；
  - [`tools/verify-apk-managed-assets.py`](../../tools/verify-apk-managed-assets.py)：受管资产名、逐字节摘要与
    gh 固定成员（此门禁需要本次构建的 `app/build/generated/standardAssets`，只能在构建节点运行）。
- 用 `unsigned-receipt.json` 绑定 commit / runId / runAttempt / APK SHA-256 / 元数据与受管资产回执摘要，
  1 天保留的 artifact 交给签名节点。

**sign-verify**（`needs: [bind, contracts, java-format, host-regression, inputs, unit, lint, build-unsigned]`）

- `gradle-cache: 'false'`、`android-sdk ndk: 'false'`、不运行 Gradle；
- 下载未签名交接 + unit JUnit XML + lint 报告，重新 `verify-unit-results.py --mode full --require-fixtures`；
- 复核交接回执与 APK 字节（commit / run / attempt / SHA-256 / 元数据 / 受管资产摘要）；
- 用 [`tools/sign-release-apks.py`](../../tools/sign-release-apks.py) 签名：签名前用 keytool 核对 keystore
  证书等于身份文件；`apksigner sign` 显式 `--v1/--v2/--v3-signing-enabled true`；签名后复验证书、
  三个 scheme 全部 `Verified using … : true`、badging 身份，且**除 `META-INF/` 外每个 ZIP 成员的 CRC32
  与大小必须与未签名输入一致**（签名不改载荷）；
- 打包证据（不再内嵌 APK）、`build-info.json`、`SHA256SUMS`、发布说明；`if: always()` 删除私钥；
  1 天保留的 artifact 交给 publish。

**test-packages-unsigned / test-packages**（共享节点 `_node-test-packages.yml` 的两段式）

- `test-packages-unsigned`（`needs: [bind, inputs]`，不传任何 secrets）：`unsigned_only: true` 构建
  `.debug` / `.dev` 未签名包，核验身份 / 确实未签名 / 受管资产后上传交接 artifact；
- `test-packages`（`needs` 含全部门禁 + 上一步）`unsigned_artifact_prefix: release-test-unsigned`：
  下载交接、复核绑定、只签名与校验（不再运行 Gradle），逐成员证明载荷未被改动；
- 共享节点的缓存表达式直接写成 `gradle-cache: ${{ (inputs.unsigned_only && 'true') || 'false' }}`，
  调用方无法既传证书写缓存；默认 combined 模式与历史行为一致，on-demand 工作流不受影响。
- **失败语义**：publish 只把 `test_packages=none` 时的 skipped 视为放行；测试包 unsigned 编译失败导致的
  skipped 会挡住发布（避免“测试包失败但 Release 照发”）。

**inputs**：受管运行时重建前恢复
`dsha-dsh-runtime-npm-${{ runner.os }}-${{ hashFiles('tools/dsh-runtime/package-lock.json') }}` 缓存，
并用 `prepare-dsh-runtime.py --cache` 让 `npm ci` 走本地缓存；缓存内容仍受 lock 的 integrity 校验。
npm 缓存键只含 lock 哈希，与源码 / 身份无关，可在发布与其它 run 之间共享。

**android-sdk action**：先按实际文件判断（`platforms/<ver>/android.jar`、`build-tools/<ver>/aapt2`、
`ndk/<ver>/source.properties` 的 `Pkg.Revision`），已预装项不再联网；缺失项才 `sdkmanager`，
单次 `timeout 600`、最多 2 次，失败后复核实际文件，避免半截安装。

**证据包**：`dsha-a-su-build-evidence.zip` 现在只含单测 XML、Lint、`apk-signature.txt`、`apk-badging.txt`、
`apk-signing.json`、`build-info.json`、`full-unit-tests.json`；APK 是独立 Release 资产，由 `SHA256SUMS`
与 `build-info.json` 的 `apkSha256` 绑定。`sign-verify` 上传的 `release-bundle` 从约 614 MB 降到约 330 MB。

## 5. 预计收益

| 指标 | 拆分前（实测） | 拆分后（预计） | 变化 |
|---|---|---|---|
| 正式发布总时长 | 15:05 | 10:30–11:10 | **−4 分钟（约 −27%）** |
| 门禁结束到 publish 前 | 5:29（build-signed） | 1:30–2:10（sign-verify） | −3:20 左右 |
| 构建期签名 | 在 assemble 内（v1/v2/v3） | 移到签名节点 | 不增加总工作量 |
| 证据包 | 298 MB | 约 13 MB | −285 MB |
| release-bundle artifact | 约 614 MB | 约 330 MB | −284 MB |
| 固定输入缓存 | — | 不实施 | 还原仅 6–15 s |
| npm 运行时缓存 | 冷 65 s | 预计 20–35 s | −30–45 s（稳态） |

关键路径（预计）：`bind 10 s + inputs ~55–97 s + unit 7:02 + sign-verify 1:30–2:10 + publish 35 s`。
首次 run 因 Gradle / npm 缓存未命中，收益预计少 30–60 s；其后稳态命中。若后续把 unit 改为 2 fork，
总时长有望进入 7–8 分钟区间（未实施）。

## 6. 验证与门禁

本地（本次实跑，见 `.SESSION-DONE` 回执）：

- `tools/test-ci-workflows.py`（46 例）：节点集合与 `needs`、Secrets / 写权限隔离、签名节点缓存为 false、
  构建节点无 Secrets 且缓存为 true、未签名交接绑定、签名工具覆盖证书 / 方案 / 载荷、测试包两段式、
  publish 的 skipped 语义、证据 zip 不含 APK；
- `tools/test-release-signing.py`（15 例）：清单校验、载荷比对、scheme / 证书钉值、源码版本绑定；
- 相关既有回归：`test-ci-assets.py`、`test-ci-handoff.py`、`test-release-identity.py`、
  `test-release-test-packages.py`、`test-verify-apk-managed-assets.py`；
- `run-host-tests.py --check-manifest`（252 项）、`check-source-hygiene.py`、`check-ui-i18n.py`、
  `check-document-links.py`、`generate-changelog.py --check`、`format-java-tree.py --check`
  （仅确认工作流 / 文档改动不破坏这些门禁）。

**本机真实工具演练**（不接触正式私钥）：用 `aapt2` 生成一个与源码版本一致的合成未签名 APK
（包名 `io.github.sunsetrne.dsha`、versionCode 356、minSdk 30、含 `lib/arm64-v8a`），配一次性 keystore 与
被 monkeypatch 的假身份：

- `--check-only` 通过并写出 receipt；已签名输入被拒（`UNSIGNED_APK_IS_SIGNED`）、错误包名被拒
  （`UNSIGNED_APK_PACKAGE_MISMATCH`）；
- 用真实 `apksigner 0.9` 走完整 sign + verify：v1/v2/v3 全部 `true`、证书钉值、签名前后载荷 3/3 成员一致，
  写出 `apk-signature.txt` / `apk-badging.txt` / `apk-signing.json`；篡改后的签名包被
  `SIGNED_APK_VERIFY_FAILED` 拒绝；
- 用非发布证书签名会被 `PUBLISH_CERTIFICATE_MISMATCH` 拒绝（从未触碰正式私钥）。

演练发现并修正了一个真实问题：`apksigner verify --min-sdk-version 30` **不会**实际校验 v1，
只会打印 `Verified using v1 scheme (JAR signing): false`（API 30 不要求 v1）；工具改为
`--min-sdk-version 23` 复核（此时 v1 被真实校验），APK 自身 minSdk 30 仍由 badging 断言。
旧版 `sign-release-apks.py` 在 `--min-sdk-version 30` 下断言 v1 成立，在真实工具下其实不成立——
这也是它一直没进发布链的原因之一。

GitHub 实测（合并后、由主会话执行）：以**未占用的新 tag** + `dry_run=true` 触发一次
`release-production.yml`，验证 `build-unsigned` → `sign-verify` / `test-packages-unsigned` → `test-packages`
全部成功、证据包大小与 `SHA256SUMS` 自洽；通过后再做下一次正式发布。这一步不能由工作会话 dispatch。

## 7. 回退方案

- 工作流与工具改动集中在一个提交；回退用 `git revert <提交>`（或把 `release-production.yml`、
  `_node-test-packages.yml`、`_node-inputs.yml`、`android-sdk/action.yml`、`tools/sign-release-apks.py`
  恢复到上一版）即可回到「单 build-signed」路径，不涉及数据 / 版本 / 运行时格式。
- 回退不影响已发布资产：Release 资产与 `SHA256SUMS` / `build-info.json` 的内容契约不变
  （新增的 `apk-signing.json` 只是证据包内附加文件；证据包不再含 APK 时 `SHA256SUMS` 仍覆盖 APK 与证据）。
- 若只想临时回到旧行为而不 revert：把 `build-unsigned` 的产物改回由 `sign-verify` 用 Gradle 直接构建签名
  需要代码改动，因此不建议“开关式”回退；直接 revert 更清晰。
- 已发布/已排队 run 不受影响：工作流版本固定在触发时的 commit。

## 8. 风险与未知

- **首次真实运行未经 GitHub 实测**（工作会话不能 push / dispatch）：签名路径已用真实 `aapt2` /
  `apksigner 0.9` 在本机用合成 APK 全流程演练（见第 6 节），但 316 MB 真实 APK 的 v1 签名耗时与
  artifact 传输时间仍是估算（1:30–2:10）；`dry_run=true` 的发布 run 才能给出实测。
- **artifact 体积变化**：未签名 APK 现在多传输一次（upload + download），预计 +20–30 s；被证据包瘦身
  与并行化抵消。
- **缓存配额**：Gradle 依赖缓存 + npm 缓存 + Robolectric 缓存共用仓库 10 GB；NDK 与固定输入未进缓存，
  仍留有余量；LRU 淘汰时首次 run 会变慢，功能不受影响。
- **测试包两段式**依赖 `_node-test-packages.yml` 的新输入；`test-package.yml` / `test-package-variants.yml`
  使用默认 combined 模式，行为不变（静态契约已覆盖）。
- 本机无法验证 GitHub 托管的 `ubuntu-24.04` 镜像内容：预装检测以实际文件为准，预装清单变化时只是
  回到下载路径，不会失败。
