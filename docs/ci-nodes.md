# CI 节点图（GitHub Actions）

更新：2026-11-11 / T141（build-unsigned / sign-verify 拆分 + 测试包两段式）。本文说明 `.github/workflows/` 的节点拆分、
复用方式与耗时依据；正式发布的参数、Secrets 与产物核验仍以 [CI 构建与发布](ci-release.md) 为准。

## 1. 结构

可复用节点（`workflow_call`，只读、不读 Secrets、不设并发组）：

| 节点 | 作用 | 产出 |
|---|---|---|
| `_node-contracts.yml` | 描述符、声明、内置插件、凭据路径、测试清单、源码整洁、i18n、changelog、文档链接 | — |
| `_node-java-format.yml` | `tools/format-java-tree.py --check`（失败时列出文件并上传报告） | `java-format-reports`（仅失败） |
| `_node-inputs.yml` | 成对解析固定输入 → `ci-assets.py` 按 SHA-256 还原 → 可选重建受管运行时并重算描述符 → `ci-handoff.py pack` | 交接 artifact（无压缩、1 天） |
| `_node-unit-tests.yml` | 先按 `tools/robolectric-sdk.lock.json` 准备 android-all（缓存 + 逐个 sha256 核对 + 有界重试），再以 `robolectric.offline` 跑 `:app:testStandardDebugUnitTest`，`full` / `source-only`，可选 `--tests` 与 fork 数 | JUnit XML + `out/unit-tests.json` |
| `_node-lint.yml` | `:app:lintStandardRelease`（未签名模式） | lint 报告 |
| `_node-host-tests.yml` | `run-host-tests.py` 的一个阶段或 `--only` 子集；可选夹具、toolchains、Playwright | `app/build/host-tests` |
| `_node-test-packages.yml` | 三种模式：默认（构建 + 测试证书签名 + 逐项核对）；`unsigned_only`（无 Secrets 编译、核验未签名身份 / 受管资产并交接）；`unsigned_artifact_prefix`（下载同 run 未签名产物，只签名与核对，不跑 Gradle） | 每个 variant 的 APK + 证据 artifact（含公开测试证书），或未签名交接 artifact |

`_node-*.yml` 默认只读、不读 Secrets；唯一例外是 `_node-test-packages.yml`：证书模式需要测试证书 Secrets
（`SUNSET_TEST_KEYSTORE_B64` / `SUNSET_TEST_STORE_PASSWORD`），正式签名 Secrets 仍然绝不进入。
持证书的节点不写 Gradle 共享缓存；只有 `unsigned_only`（不接收任何证书 Secrets）允许缓存，
表达式直接把 `gradle-cache` 绑定成 `inputs.unsigned_only`，调用方无法既传证书又写缓存。

组合 action（`.github/actions/`）：`setup-toolchain`（锁定 JDK/Node + 可选 Gradle 依赖缓存）、
`android-sdk`（锁定 platform/build-tools，可选 NDK；先按实际文件判断是否已预装，缺失才下载，
`timeout 600` 且最多 2 次重试，防止 build353 第 2 次 run 的 sdkmanager 44 分钟挂起）、
`restore-handoff`（下载交接、核对后写入、可选准备 raw/managed 夹具）。

交接（[ci-handoff.py](../tools/ci-handoff.py)）只包含 `ci-assets.py` 白名单与两份重算文件
（`managed-package-proofs.json`、`runtime-descriptor.json`）；打包时拒绝白名单外的已跟踪源码改动，
解包时核对 commit、成员集合与逐文件 SHA-256，全部通过后才覆盖工作区。

## 2. 入口工作流

| 工作流 | 触发 | 节点 |
|---|---|---|
| `ci-fast.yml`（Source checks） | push / PR / 手动 | contracts ‖ java-format ‖ inputs → (unit-source ‖ host-fast) |
| `release-production.yml` | 手动，`main` | bind → (contracts ‖ java-format ‖ host-regression ‖ inputs → (unit ‖ lint ‖ build-unsigned ‖ test-packages-unsigned)) → (sign-verify ‖ test-packages) → publish；`dry_run=true` 时 publish 跳过、由 dry-run-summary 出报告 |
| `ci-package.yml` | 手动，显式输入包 | inputs → (native-repro ‖ package) |
| `unit-tests.yml` | 手动 | inputs → unit（可选 `--tests`、`source-only`、fork 数） |
| `host-tests.yml` | 手动 | inputs → host（`fast` / `runtime`，可选 `--only`、Playwright） |
| `test-package.yml` | 手动 | contracts ‖ inputs → (patch-chains ‖ unit ‖ lint) → package-sign |
| `docs-check.yml` | 文档路径 push / PR / 手动 | links ‖ changelog ‖ i18n |
| `inputs-health.yml` | 每周一 03:17 UTC / 手动 | 下载并核对锁定的固定输入，不写任何东西 |

发布链的安全边界：正式签名 Secrets 只出现在 `sign-verify`，测试证书 Secrets 只出现在 `test-packages`；
两个节点都不写 Gradle 共享缓存、也不在签名阶段运行 Gradle。构建节点（`build-unsigned` /
`test-packages-unsigned`）没有任何 Secrets，只依赖 bind + inputs，与门禁并行，因此可以读写共享缓存。
`contents: write` 只在 `publish`，它不 checkout、不签名，只核对
`SHA256SUMS` 与 `build-info.json` 的 commit/run 绑定，合并（可选）测试包证据后创建 tag 与 prerelease。
任一前置节点失败都不会签名；未签名产物以 `unsigned-receipt.json` 绑定 commit/run/摘要，签名节点复核后才签名，
签名后再逐成员证明载荷未被改动。签名包与测试包证据经 1 天保留的 artifact 交给 `publish`，正式交付物以 Release
资产为准；`dry_run=true` 时 `dry-run-summary` 用同一套证据合并与校验并打印发布计划，不写仓库。

`test-package.yml` 产出 `io.github.sunsetrne.dsha.test`（`-SunsetTest`）。配置 `SUNSET_TEST_KEYSTORE_B64`
（可选 `SUNSET_TEST_STORE_PASSWORD`）时使用该测试证书，便于覆盖升级；否则每次生成一次性密钥。证书不得等于正式证书。

## 3. 耗时依据与压缩

拆分前（build274，run `41450649159` / `41450644843`）：发布 28.1 分钟、Source checks 25.8 分钟，均为单 job 串行。
最大单项是 Java 格式门禁：1385 个文件逐个起两个 JVM（格式化 + token 校验），约 2770 次冷启动，
发布中 895 秒、Source checks 的 contracts 步骤 941 秒。

本次调整：

- `format-java.py` 每批只起一个格式化 JVM（在 `app/build` 私有副本上 `-i`）和一个 token JVM（`--pairs`），
  批次间并行（`--jobs`，默认 min(4, CPU)）；写入模式仍逐批串行。输出与逐文件 stdin 模式逐字节一致
  （40 个打乱文件含 CRLF/去缩进/合并行对照），本机全树 70 秒（原约 15 分钟）。解析失败仍逐文件定位。
- 节点并行：门禁、格式、宿主回归与固定输入同时开始；单测与 Lint 并行。
- `setup-java` Gradle 依赖缓存：依赖字节仍由 `gradle/verification-metadata.xml` 核对；签名节点不使用缓存。
- 单测 fork：`ci-fast` 与手动单测默认 2 个 fork；发布保持 1 个 fork，等 CI 证据稳定后再决定是否放开。

### 实测基线（build356 正式发布 run `42799176202`，成功，15:05）

| 节点 / 步骤 | 耗时 | 说明 |
|---|---|---|
| bind | 10 s | tag / commit / 输入锁核验 |
| inputs | 97 s | 固定输入还原 6 s、受管运行时重建 65 s、打包与上传 9 s |
| contracts / host-fast / java-format | 23 s / 38 s / 124 s | 与 inputs 并行 |
| unit（full） | 7:02 | NDK 安装 24 s、交接还原 41 s、测试本体 5:35，要求 0 failure / 0 skip |
| lint | 3:41 | SDK 24 s、交接 45 s、lint 2:09 |
| build-signed | 5:29 | assemble 4:13（含 Gradle 冷依赖 + v1/v2/v3 签名）、证据打包 11 s、artifact 上传 20 s |
| test-packages（并行） | 5:18 / 5:06 | dev / debug 各自构建 3:19 / 3:28、签名 16–19 s、上传证据约 10 s |
| publish | 35 s | 下载 bundle 13 s、合并、创建 tag 与上传 |

关键路径 = bind 10 s + inputs 97 s + unit 7:02 + build-signed 5:29 + publish 35 s ≈ 15:05。

### T141 拆分后的预计（首次 run 缓存 miss / 其后稳态）

- `build-unsigned` 与 `test-packages-unsigned` 在 inputs 完成后立即开始，与 unit / lint 并行；
  构建节点无 Secrets，可复用 `setup-java` 的 Gradle 依赖缓存（稳态命中），并省掉构建期 v1/v2/v3 签名
  （移到签名节点）。
- 门禁结束（unit 7:02）后：`sign-verify` ≈ 下载未签名 APK + 签名（v1+v2+v3）+ 复验 + 证据 + 上传 ≈ 1:30–2:10；
  `test-packages` 只签名 ≈ 1:00–1:30（并行，不占关键路径）。预计总时长 **10:30–11:10**（−4 分钟，约 −27%）。
- 证据包 298 MB → 约 13 MB（不再重复内嵌 APK）；release-bundle artifact 约 614 MB → 约 330 MB，
  publish 下载与上传各快约 5–7 s。
- 首次 run（Gradle / npm 缓存未命中）收益预计少 30–60 s；`inputs` 的固定输入还原实测仅 6–15 s，
  因此没有为 684 MB 固定输入加 actions/cache（避免 10 GB 配额与 700 MB 缓存读写倒挂）。
- 下一瓶颈是 `unit`（7:02，单 fork）：`forks=2` 与 Gradle `--build-cache` 是后续候选，
  需要单独验证测试隔离与构建缓存正确性，本次不实施。

Robolectric android-all 不经 Gradle 依赖解析，原先在单测中途从 Maven 临时下载，runner 网络抖动时整批 sdk=N 测试类以 `MavenArtifactFetcher` IOException 失败（build273、build277 正式发布各一次）。现由单测节点在测试前准备锁定的 5 个 jar；缺失或摘要不符时单测前即失败，单测中不再联网取 android-all。锁定新 SDK 时同时更新锁文件里的 `size` 与 `sha256`，并与 Maven Central 的 `.sha1` 交叉核对。

## 4. 已删除

私有仓库 `DSHA-Sunset-mod-3090-Q` 专用的 `sunset-3090-q.yml`、`sunset-release.yml`、`terminal-regression.yml`
及 `sunset-release-delivery.py`、`sunset-plugin-release.py`、`ci/terminal-package.py`、`verify-terminal-test-scope.py`
与对应测试已于 2026-11-02 删除，历史记录见 [终端 CI 合同](terminal-ci-20261025.md)。
`tools/sunset-ci-inputs.py` 与 `ci/sunset-inputs.lock.json` 仍是当前固定输入的解析入口，保留。

## 5. 契约测试

- [test-ci-workflows.py](../tools/test-ci-workflows.py)：节点集合与依赖、Secrets/写权限隔离、外部 action 固定、Standard 范围。
- [test-ci-handoff.py](../tools/test-ci-handoff.py)：交接白名单、commit 绑定、篡改与路径穿越拒绝；单测回执的七夹具与计数核对。
