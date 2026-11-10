# 固定构建输入（公开仓库预发布资产）

更新：2026-11-05（build328 gh 根因修复：新增第 12 项固定输入 `gh-cli.tar.gz`，官方 GitHub CLI v2.103.3 linux-arm64 归档，作为受管 `opt/gh/bin/gh` 的唯一来源；S1 的 0.2.2-rc.1 运行时与应急归档保持不变；build330 起 APK 内把同一字节改名为 `gh-cli.bin` 打包，源固定输入名不变、bundle 不重建）。当前为已上传并发布（prerelease）的 `fixed-inputs-20261105e` 归档（发布核验见下）；
锁文件为 [`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json)。

## 为什么固定输入不放进 git

8 个大型固定二进制/压缩包（`offline-rootfs.bin` 最大 304 MB，`gh-cli.tar.gz` 13.7 MB）加上 2 个独立应急归档，加上 2 个 provenance JSON，合计约 684 MiB（bundle 实测 716,787,167 B）。
放进 git 会超过仓库与 git 单文件限制、永久膨胀历史，且这些字节可由受管 recipe 重新生成、以 SHA-256 固定校验；
它们不是手写源码，不需要随每次提交分发。因此它们只作为**GitHub Release 资产**发布：

- 仓库里保留锁文件（URL、SHA-256、format、release、asset）与每个输入在 [`app/src/main/assets/runtime-descriptor.json`](../app/src/main/assets/runtime-descriptor.json)、
  [`tools/ci-build-inputs.lock.json`](../tools/ci-build-inputs.lock.json)、[`app/src/main/assets/adb-wheels.lock.json`](../app/src/main/assets/adb-wheels.lock.json)、
  [`tools/recovery-runtime/lock.json`](../tools/recovery-runtime/lock.json) 中的 pin；
- 大文件本体放在预发布资产里，克隆后按锁还原，任何字节不一致都会在任何写入前拒绝。

2 个 provenance `*.inputs.json` 是小文本，仍以 tracked 文件形式保留在仓库中；它们同时也被打进资产包，
使恢复集合保持 12 项、单一来源。还原后的字节与仓库内文件一致（`--source-archive` 恢复同样受 pin 流程约束）。

`.gitignore` 仍列出 10 个不入库输入；两个 `*.inputs.json` 因为已被 git 跟踪，`.gitignore` 不会生效，属于预期状态。

## 资产内容（12 项，STORED zip）

| ZIP 成员 | 字节 | 当前 pin 来源 |
|---|---:|---|
| `app/src/main/assets/offline-rootfs.bin` | 304037588 | `runtime-descriptor.json` |
| `app/src/main/assets/dsh-runtime.bin` | 131056363 | `runtime-descriptor.json` |
| `app/src/main/assets/ubuntu-tools.bin` | 29919475 | `runtime-descriptor.json` |
| `app/src/main/assets/pnpm-runtime.bin` | 3837861 | `runtime-descriptor.json` |
| `app/src/main/assets/python-support.bin` | 904360 | `tools/ci-build-inputs.lock.json`（大小 + 摘要） |
| `app/src/main/assets/glibc-python.tar.gz` | 6565414 | `runtime-descriptor.json` |
| `app/src/main/assets/adb-wheels.tar.gz` | 9951074 | `app/src/main/assets/adb-wheels.lock.json` |
| `app/src/main/assets/gh-cli.tar.gz` | 13661485 | [`tools/gh-cli/packages.lock.json`](../tools/gh-cli/packages.lock.json)（归档 + 成员 + 许可摘要） |
| `app/src/main/assets/dsh-runtime.inputs.json` | 11629 | provenance（不单独做二进制 pin） |
| `app/src/main/assets/ubuntu-tools.inputs.json` | 592 | provenance（不单独做二进制 pin） |
| `tools/recovery-runtime/archives/recovery-rootfs.bin` | 85783057 | `tools/recovery-runtime/lock.json` |
| `tools/recovery-runtime/archives/recovery-dsh-runtime.bin` | 131056363 | `tools/recovery-runtime/lock.json` |

覆盖 Ubuntu 离线 rootfs、DSH runtime、ubuntu tools、pnpm runtime、Python 支持、glibc Python、ADB wheels、
GitHub CLI（gh）以及独立应急 DSH 的 rootfs / runtime 归档。正式归档为内置 DSH `0.2.2-rc.1`
（131,056,363 B / SHA-256 `c1bd68330a10776f304a92a19d8f82c184c1abf523275a9e28dbb2c2e764e4fe`）；
S1 按已确定决策将独立应急锁同步到同一 `0.2.2-rc.1` 归档字节，T54 与 T58 合并重建运行时后应急锁再次同步到同一字节；正式与应急仍保留独立锁和解压运行根（`recovery-asset-locations.json` 只映射包内物理字节），
锁与验证合同相互独立，后续正式升级不会自动改动应急锁。**build328 新增 `gh-cli.tar.gz` 不改变应急锁**：`tools/recovery-runtime/lock.json` 与两份 recovery 归档字节均未改动，仍由本 bundle 原样携带。

**APK 打包名与源名分离（build330）**：源固定输入名始终是 `app/src/main/assets/gh-cli.tar.gz`，本 bundle 与
`runtime-descriptor.json` 的 pin 都不变；APK 内的成员名是 `assets/gh-cli.bin`。原因是 AGP 9.1.1 的资产合并会对
`.gz` 自动 gunzip 并去掉扩展名，官方 build328/329 APK 里只剩 `assets/gh-cli.tar`，受管安装按 `gh-cli.tar.gz`
打开必然失败。`tools/asset-deployment.json` 的 `sourceTransforms` 与 `tools/prepare-standard-assets.py` 负责改名，
`RuntimeTools` 按打包名打开（旧包回退源名）；`tools/verify-apk-managed-assets.py` 在正式发布与测试包的构建链上
核对实际 APK 成员的名称、逐字节摘要与 gh 固定成员，防止再次静默改名。

## 锁定与还原

[`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json) 的字段：

```json
{
  "schema": 1,
  "url": "https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/fixed-inputs-20261105e/dsha-a-su-fixed-inputs-20261105e.zip",
  "sha256": "be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5",
  "format": "bundle",
  "release": "fixed-inputs-20261105e",
  "asset": "dsha-a-su-fixed-inputs-20261105e.zip",
  "bytes": 716787167
}
```

[`tools/sunset-ci-inputs.py`](../tools/sunset-ci-inputs.py) 在没有显式 `SUNSET_DISPATCH_*` / `SUNSET_REPOSITORY_*` 成对覆盖时读取该锁，
输出 `url` / `sha256` / `format`；CI 只在 `format == "source-archive"` 时给 [`tools/ci-assets.py`](../tools/ci-assets.py) 加 `--source-archive`，
`bundle` 直接按 12 项固定成员集合恢复。手动覆盖必须 URL + SHA 成对，半对即失败。

还原命令（由锁下载，CI 使用的路径）：

```bash
python3 tools/ci-assets.py --url "<lock.url>" --sha256 "<lock.sha256>"
```

已有本地归档时：

```bash
python3 tools/ci-assets.py --archive dsha-a-su-fixed-inputs-20261105e.zip \
  --sha256 be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5
```

`ci-assets.py` 的校验顺序：整包 SHA-256 → 成员白名单/路径/类型/重复与总大小 → 解压到暂存目录后逐项对照
`runtime-descriptor.inputs`、`ci-build-inputs.lock.json`、`adb-wheels.lock.json`、`recovery-runtime/lock.json` 的 pin →
全部通过后才原子替换目标文件。任一环节失败都不会覆盖已有输入，更不会执行归档中的任何内容。

## 当前资产

| 项目 | 值 |
|---|---|
| release tag | `fixed-inputs-20261105e`（prerelease，target `main`，2026-11-05 16:07:48Z 发布） |
| release id | `435405154` |
| asset | `dsha-a-su-fixed-inputs-20261105e.zip` |
| bytes | `716787167` |
| SHA-256 | `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5` |
| 下载直链 | `https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/fixed-inputs-20261105e/dsha-a-su-fixed-inputs-20261105e.zip` |

> **上传与发布核验：已上传并发布（prerelease）。** 本 bundle 由 build328 会话在本机以 `tools/ci-assets.py --pack` 生成（成员 12 项、STORED），
> 远端核对：release id `435405154`，asset id `713925229`（仅审计），资产名 `dsha-a-su-fixed-inputs-20261105e.zip`，`size=716787167`，`state=uploaded`，
> digest `sha256:be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`；2026-11-05T16:07:48Z 由 Draft 发布为公开 prerelease，
> 随后匿名直链实测 HTTP 200。直链由 tag + 资产文件名确定，不依赖 asset id；若后续在同 tag 内 `--clobber` 替换资产，文件名不变但内容与 SHA-256 会变，必须同步更新锁。
> 重打包可复现性：同一输入集合重跑 `--pack` 必须得到同一 SHA-256（build328 已实测；`--pack` 前会按 pin 逐项校验）。

## 升级流程（新预发布资产）

1. 输入或 pin 变化后重新打包（STORED，成员集合由 `ci-assets.py` 的 `allowed()` 决定）：

   ```bash
   python3 tools/ci-assets.py --pack /root/DSHA/build-inputs/dsha-a-su-fixed-inputs-YYYYMMDD.zip
   ```

   记录输出的 `sha256` 与 `bytes`；`--pack` 会先按当前 pin 校验 12 项输入，失败则不生成资产。
2. 在公开仓库新建预发布（tag 用新日期，例如 `fixed-inputs-YYYYMMDD`），notes 写明成员清单、bytes、SHA-256 与用途；
   上传 STORED bundle：

   ```bash
   gh release create fixed-inputs-YYYYMMDD --repo SunsetRNE/DSHA-Sunset-mod \
     --title '固定构建输入' --prerelease --notes-file <notes>
   gh release upload fixed-inputs-YYYYMMDD dsha-a-su-fixed-inputs-YYYYMMDD.zip \
     --repo SunsetRNE/DSHA-Sunset-mod
   ```

   大文件上传耗时长，建议后台执行并核对上传后 API 的 `size`。旧资产保留，便于追溯；常规升级不复用旧 tag。
3. 核对上传结果（资产名/大小/状态；asset id 只留作审计，不写进锁）：

   ```bash
   gh api repos/SunsetRNE/DSHA-Sunset-mod/releases/tags/fixed-inputs-YYYYMMDD \
     --jq '.assets[] | {id, name, size, state}'
   ```

4. 更新 [`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json)：`url` 用确定性直链
   `https://github.com/SunsetRNE/DSHA-Sunset-mod/releases/download/<tag>/<asset>`（由 tag 与文件名确定，不依赖 asset id），
   并更新 `sha256`、`format`（`bundle`）、`release`、`asset`、`bytes`；同步更新
   [`tools/test-sunset-ci-inputs.py`](../tools/test-sunset-ci-inputs.py) 中针对该锁的断言。
5. 验证：`python3 tools/test-sunset-ci-inputs.py` 通过，且用 `ci-assets.py` 实际还原一次（或至少核对资产 `size` 与本地 bundle 一致，
   并抽样下载校验 SHA-256）。`ci-assets.py` 还原时会逐项校验 `runtime-descriptor.json`、`ci-build-inputs.lock.json`、
   `adb-wheels.lock.json`、`recovery-runtime/lock.json` 的 descriptor/输入 pin；pin 需要变化时由对应生成器/评审流程单独处理，
   不能靠改锁迁就。

### 同一 tag 内替换（clobber）

只有修错（例如上传中断留下坏资产、notes 写错）才允许对**同一 tag** 使用 `gh release upload --clobber`；
替换会改变 asset id，必须同步更新锁，并在提交说明里记录替换原因。正常输入升级应使用新 tag + 新日期文件名，
保留旧资产与旧 SHA-256 证据。

## 相关文件

- [`ci/sunset-inputs.lock.json`](../ci/sunset-inputs.lock.json)：当前资产锁。
- [`tools/ci-assets.py`](../tools/ci-assets.py)：打包 / 下载 / 校验 / 还原唯一入口。
- [`tools/sunset-ci-inputs.py`](../tools/sunset-ci-inputs.py)：CI 输入解析（锁与显式成对覆盖）。
- [`tools/test-sunset-ci-inputs.py`](../tools/test-sunset-ci-inputs.py)：锁与解析行为的宿主回归。
- [`tools/test-ci-assets.py`](../tools/test-ci-assets.py)：打包/恢复成员集合与 pin 校验回归。
- [`app/src/main/assets/runtime-descriptor.json`](../app/src/main/assets/runtime-descriptor.json)、[`tools/ci-build-inputs.lock.json`](../tools/ci-build-inputs.lock.json)、
  [`app/src/main/assets/adb-wheels.lock.json`](../app/src/main/assets/adb-wheels.lock.json)、[`tools/recovery-runtime/lock.json`](../tools/recovery-runtime/lock.json)：逐项 pin 来源。
