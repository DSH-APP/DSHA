# CI 节点图（GitHub Actions）

更新：2026-11-10 / build353（正式 Release 附带共存测试包）。本文说明 `.github/workflows/` 的节点拆分、
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
| `_node-test-packages.yml` | matrix 构建 `.debug` / `.dev` 共存测试包，用独立测试证书签名并逐项核对身份、签名与受管资产 | 每个 variant 的 APK + 证据 artifact（含公开测试证书） |

`_node-*.yml` 默认只读、不读 Secrets；唯一例外是 `_node-test-packages.yml`：它需要测试证书 Secrets
（`SUNSET_TEST_KEYSTORE_B64` / `SUNSET_TEST_STORE_PASSWORD`），正式签名 Secrets 仍然绝不进入，
持证书的节点也不写 Gradle 共享缓存（与 `build-signed` 同策略）。

组合 action（`.github/actions/`）：`setup-toolchain`（锁定 JDK/Node + 可选 Gradle 依赖缓存）、
`android-sdk`（锁定 platform/build-tools，可选 NDK）、`restore-handoff`（下载交接、核对后写入、可选准备 raw/managed 夹具）。

交接（[ci-handoff.py](../tools/ci-handoff.py)）只包含 `ci-assets.py` 白名单与两份重算文件
（`managed-package-proofs.json`、`runtime-descriptor.json`）；打包时拒绝白名单外的已跟踪源码改动，
解包时核对 commit、成员集合与逐文件 SHA-256，全部通过后才覆盖工作区。

## 2. 入口工作流

| 工作流 | 触发 | 节点 |
|---|---|---|
| `ci-fast.yml`（Source checks） | push / PR / 手动 | contracts ‖ java-format ‖ inputs → (unit-source ‖ host-fast) |
| `release-production.yml` | 手动，`main` | bind → (contracts ‖ java-format ‖ host-regression ‖ inputs → (unit ‖ lint)) → (build-signed ‖ test-packages) → publish；`dry_run=true` 时 publish 跳过、由 dry-run-summary 出报告 |
| `ci-package.yml` | 手动，显式输入包 | inputs → (native-repro ‖ package) |
| `unit-tests.yml` | 手动 | inputs → unit（可选 `--tests`、`source-only`、fork 数） |
| `host-tests.yml` | 手动 | inputs → host（`fast` / `runtime`，可选 `--only`、Playwright） |
| `test-package.yml` | 手动 | contracts ‖ inputs → (patch-chains ‖ unit ‖ lint) → package-sign |
| `docs-check.yml` | 文档路径 push / PR / 手动 | links ‖ changelog ‖ i18n |
| `inputs-health.yml` | 每周一 03:17 UTC / 手动 | 下载并核对锁定的固定输入，不写任何东西 |

发布链的安全边界：正式签名 Secrets 只出现在 `build-signed`，测试证书 Secrets 只出现在 `test-packages`；
两个节点都不写 Gradle 共享缓存。`contents: write` 只在 `publish`，它不 checkout、不签名，只核对
`SHA256SUMS` 与 `build-info.json` 的 commit/run 绑定，合并（可选）测试包证据后创建 tag 与 prerelease。
任一前置节点失败都不会签名。签名包与测试包证据经 1 天保留的 artifact 交给 `publish`，正式交付物以 Release
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

拆分后的耗时尚未在 GitHub 上实测；每个节点各自 checkout 与安装工具链，会增加约 1–2 分钟固定开销。
关键路径预计为 inputs → unit → build-signed。

Robolectric android-all 不经 Gradle 依赖解析，原先在单测中途从 Maven 临时下载，runner 网络抖动时整批 sdk=N 测试类以 `MavenArtifactFetcher` IOException 失败（build273、build277 正式发布各一次）。现由单测节点在测试前准备锁定的 5 个 jar；缺失或摘要不符时单测前即失败，单测中不再联网取 android-all。锁定新 SDK 时同时更新锁文件里的 `size` 与 `sha256`，并与 Maven Central 的 `.sha1` 交叉核对。

## 4. 已删除

私有仓库 `DSHA-Sunset-mod-3090-Q` 专用的 `sunset-3090-q.yml`、`sunset-release.yml`、`terminal-regression.yml`
及 `sunset-release-delivery.py`、`sunset-plugin-release.py`、`ci/terminal-package.py`、`verify-terminal-test-scope.py`
与对应测试已于 2026-11-02 删除，历史记录见 [终端 CI 合同](terminal-ci-20261025.md)。
`tools/sunset-ci-inputs.py` 与 `ci/sunset-inputs.lock.json` 仍是当前固定输入的解析入口，保留。

## 5. 契约测试

- [test-ci-workflows.py](../tools/test-ci-workflows.py)：节点集合与依赖、Secrets/写权限隔离、外部 action 固定、Standard 范围。
- [test-ci-handoff.py](../tools/test-ci-handoff.py)：交接白名单、commit 绑定、篡改与路径穿越拒绝；单测回执的七夹具与计数核对。
