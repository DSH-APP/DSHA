# Mod 身份、包名与签名（build209 起；双身份见第 1 节）

本文记录 DSH-A-Su（Sunset Mod）与原版 DSHA 的身份分离方案、可切换的双身份构建，以及 `verifyPublishCertificate` 的校验来源。历史 E7E3 证书的来历见 [BUILD.md](../BUILD.md) 顶部说明与 `docs/releases/` 历史回执。

## 1. 双身份：mod（默认）与 upstream

同一份源码可以按两套互斥的发布身份构建，用 `-Pdsha.identity=mod|upstream` 选择，默认 `mod`。未知值直接让 Gradle 配置失败，不回退到任何一方。两套身份的包名、显示名、测试后缀、默认 key alias 与 `verifyPublishCertificate` 核对的证书都从对应身份文件读取；versionCode / versionName 只写在 `app/build.gradle`，两种身份共用。

| 项 | mod（默认） | upstream |
|---|---|---|
| 身份文件 | `ci/release-identity.mod.json` | `ci/release-identity.upstream.json` |
| 应用显示名 | `DSH-A-Su` | `DSHA` |
| 测试变体显示名 | `DSH-A-Su Test` | `DSHA Test` |
| 正式包名 | `io.github.sunsetrne.dsha` | `dsh.android.client` |
| 测试包名（`-Pdsha.sunsetTest=true`） | `io.github.sunsetrne.dsha.test` | `dsh.android.client.SunsetRNE` |
| 发布证书 SHA-256 | `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d` | `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`（历史 E7E3） |
| 默认 key alias | `dsha-a-su` | `androiddebugkey` |
| 发布工作流 | `release-production.yml` | `release-official.yml` |
| 允许发布的仓库 / 分支 | `SunsetRNE/DSHA-Sunset-mod` 的 `main` | `DSH-APP/DSHA` 的 `main` |
| tag 格式 | `dsha-a-su-build<code>-YYYYMMDD-HHMMSS` | `v<version>`（如 `v0.2.2-rc.1`） |

共用项：versionCode / versionName（`app/build.gradle`）、v1 + v2 + v3 签名方案、Android 11（API 30）/ arm64-v8a。

| 共用项 | 当前值 | 权威来源 |
|---|---|---|
| versionCode / versionName | `356` / `0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1` | `app/build.gradle` |
| 签名方案 | v1 + v2 + v3 | 身份文件 `schemes`（兼容入口 `ci/release-identity.json` 一致） |
| 最低系统 | Android 11（API 30）/ arm64-v8a | `app/build.gradle` `minSdk` / `abiFilters` |

`ci/release-identity.json` 保留为 Mod 身份的兼容入口（本地交付脚本 `verify-stability.py`、`generate-release-manifest.py`、`check-release-prerequisites.py` 等仍读取它）。它的 `packageName`、`certificateSha256`、`schemes` 必须与 `release-identity.mod.json` 一致，由 `python3 tools/release-identity.py --check` 与 `tools/test-release-identity.py` 强制核对；这些本地脚本只服务 Mod 身份。发布签名工具 `tools/sign-release-apks.py` 经 `release-identity.py` 读取 mod 身份文件（与兼容入口同源核对），并新增未签名输入的清单 / “确实未签名” / 签名后载荷逐成员一致的检查；CI 的 `build-unsigned` → `sign-verify` 两段式见 [CI 构建与发布](ci-release.md)。

```bash
python3 tools/release-identity.py --identity upstream --field packageName   # 读取单个字段
python3 tools/release-identity.py --check                                   # 两套身份互斥 + 兼容入口一致
./gradlew :app:printReleaseIdentity -Pdsha.identity=upstream                # 只做配置，打印解析结果
```

### 签名身份绝不混用

- mod 身份只用 Mod 自有证书（a4b0…5d5d）；upstream 身份只用历史 E7E3。两个证书、两个包名在身份文件中互不相同，`release-identity.py --check` 拒绝任何共享。
- 原版 `dsh.android.client` 与本 Mod 的包名不同，不存在覆盖安装关系，Mod 身份**不复用 E7E3**：新包名首次安装即全新数据目录，旧版应用、旧授权与旧签名链都保持原样。
- E7E3 私钥只存在上游维护者 ym2025szz 私有仓库的 Secrets 中。本仓库不保存、不引用任何 E7E3 密钥文件，也不在本仓库用 upstream 身份打正式包。
- 两个发布工作流都由 `bind` 节点的仓库 / 分支守卫决定是否运行，其它节点都依赖 `bind`：在对方仓库里整条发布链直接跳过。
- 测试包的签名证书必须与两个正式证书都不同（`test-package.yml` 有断言）。

## 2. `verifyPublishCertificate` 现在校验什么

`app/build.gradle` 中该任务在 `assembleStandardRelease` / `packageStandardRelease` / `validateSigningStandardRelease` 之前执行，逐项检查：

1. 已通过 `DSHA_KEYSTORE`（或 `-Pdsha.keystore`）提供 keystore 文件；
2. keystore 中 `DSHA_KEY_ALIAS`（默认取所选身份的 `keyAlias`）对应证书的 SHA-256 等于 `ci/release-identity.<identity>.json` 的 `certificateSha256`；
3. `android.defaultConfig.applicationId` 等于同一身份文件的 `packageName`。

任一不符直接抛 `GradleException` 并停止打包。debug 构建、单测与 Lint 不依赖该任务；未配置 keystore 时正式打包会被阻止。

## 3. 私钥与口令

- 私钥与口令**不进入仓库**，也不写入任何文档或 CI 变量明文。
- 本机开发环境的密钥材料保存在仓库之外（例如 `keys/` 目录，权限 600，单独备份）；文件名、口令与别名由持有者保管。
- 构建时通过环境变量注入：

```bash
export DSHA_KEYSTORE=/secure/path/dsha-a-su-release.jks
export DSHA_KEYSTORE_PASSWORD=...
export DSHA_KEY_PASSWORD=...
export DSHA_KEY_ALIAS=dsha-a-su
./gradlew :app:assembleStandardRelease
```

- 增量更新清单（若启用）使用独立 keystore，不与 APK 发布证书混用。

## 4. 轮换与覆盖升级

- 同一包名的 Android 应用只允许同签名覆盖升级。一旦公开分发后更换发布证书，已安装用户必须卸载重装（数据需先导出备份），因此正常情况下**不要更换**。
- 确需轮换时：生成新 keystore → 计算证书 SHA-256 → 同时更新 `ci/release-identity.json` 与 `ci/release-identity.mod.json` → 更新本文与 BUILD.md 的记录 → 在 Release 说明中明确要求用户导出备份并重装。
- 测试包（`io.github.sunsetrne.dsha.test` / `dsh.android.client.SunsetRNE`）可以换用独立测试证书；测试证书哈希不得等于任一正式证书，CI 中有对应断言。

## 5. 与原版并存的后果

| 项目 | 是否共享 |
|---|---|
| 应用数据目录 | 否（包名不同） |
| 无障碍开关 | 否（组件名含包名，可各自启用） |
| 通知 / 悬浮窗 / 存储等系统授权 | 否 |
| 局域网端口 | 否；默认 3082，被占用时自动回退随机端口 |
| 设备桥端口 | 否；`3091→3099→3090` 依次尝试 |
| 备份文件 | 可互相导入（v5 格式），迁移用它 |

## 6. 相关文件

- `ci/release-identity.json`：Mod 身份兼容入口（包名 + 证书哈希 + 签名方案）。
- `ci/release-identity.mod.json` / `ci/release-identity.upstream.json`：两套发布身份；`tools/release-identity.py` 解析与核对。
- `ci/sunset-inputs.lock.json`：固定构建输入的公开资产地址与 SHA-256。
- [docs/fixed-build-inputs.md](fixed-build-inputs.md)：固定输入还原与升级流程。
- [BUILD.md](../BUILD.md)：环境、构建、签名与门禁命令。
