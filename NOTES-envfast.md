# DSHA 首启 / 前置环境准备提速 — 排查记录（阶段产出）

状态：测量完成 + 已实现 1 项（`TreeDigestCache`）。所有数字都给了依据；标「看不到」的是容器内确实无法观测的。

设备事实（只读获取）：
- 容器根 = `/data/data/com.dsh.clienu/files/linux/ubuntu`（来自 `/proc/mounts`）。
- **`/data/data/com.dsh.clienu/` 在容器内不可读**（`No such file or directory`，uid=0 也一样），
  所以 App 侧日志、`cold-install-probes/*.json`、`runtime-health/*.json` 都**看不到**。
- 解压会把文件 mtime 设成安装时刻（不是归档里的时间），所以文件 mtime 就是真实写入时刻 —— 可当时间线用。
  例外：`ubuntu-tools.bin` 是 `configured-overlay-v1` 预配置覆盖层，里面保留了原 deb 的 2011–2021 mtime。

---

## 1. 阶段耗时表

实测来源：`find -newermt` / `-printf '%T+'`、`/root/.dsha-*-version`、`/root/.dsh/**` mtime、
`repair-builtin.log`、`.dsha-rc1-migration/{prepare,receipt}.json`、`/usr/local/share/dsha/managed-assets-v2`。

| # | 阶段 | 区间（UTC，实测） | 耗时 | 输入字节 | 依据 |
|---|---|---|---|---|---|
| 1 | rootfs + dsh-runtime **并行**解包（含 node 122MB、dsh 439MB、npm） | 09:53:29.680 → 09:53:38.86 | **≈9.2 s** | 压缩 85.8 MB(`offline-rootfs.bin`) + 117 MB(`dsh-runtime.bin`)；展开 793.9 MiB | 目录/文件 mtime 直方图；`/boot` 与 `dsh/node_modules/@agentclientprotocol` 同为 09:53:29.680 ⇒ 两流同时开始 |
| 2 | glibc Python 解包 | … → 09:53:38.936 | 含在上面 | `glibc-python` + `python-support.bin` 0.9 MB | `/usr/bin/python3.12` mtime；`.dsha-python-version` 09:53:39.348 |
| 3 | 离线 pnpm 解包 | → 09:53:39.384 | 含在上面 | `pnpm-runtime.bin` 3.8 MB | `dsha-pnpm/bin/pnpm.cjs` mtime |
| 4 | 受管资产落盘（约 70 条 installs + 3 棵树） | 09:53:39.99 → 09:53:41.10 | **≈1.1 s** | 小件合计 < 1 MB（`ca-certificates.crt` 240 KB 等） | `ca-certificates.crt` 09:53:40.032、`managed-assets-v2` 09:53:41.104 |
| 5 | **离线工具覆盖层**（`configured-overlay-v1`，**不是 dpkg**） | → 09:53:41.144（文件收尾 09:53:43） | **≈0.1 s**（含校验收尾） | `ubuntu-tools.bin` 28.5 MB，28 个包 | `.dsha-ubuntu-tools-version` 09:53:41.144 = manifest `packageLockSha256`；`ubuntu-tools.layout`=`configured-overlay-v1` |
| — | **小计：解包 + 工具就绪** | 09:53:29.68 → 09:53:41.14 | **≈11.5 s**（到 09:53:43 收尾 ≈14 s） | 写入 **793.9 MiB / 24 441 文件 / 5 126 目录 / 1 233 链接** | 见下「吞吐」 |
| 6 | **提交 + 隔离试运行核验**（App 侧） | 09:53:43 → 09:54:08.35 | **≈25 s** | — | **容器内看不到**；由代码推断：`ColdBundleTransaction` 的 `prepared`/`publish` + `EnvironmentMaintenance.initializeFresh` 里 `RuntimeTrial.verify` 完整跑一次隔离试运行（assets/nativeModules/process/authentication/localApi/renderer/dataRead/dataWrite/storageFreshReopened/processExited 十项） |
| 7 | 589 链接 + 新建 web profile + 注册 8 内置插件 | 09:54:08.35 → 09:54:10.76 | **≈2.4 s** | — | `repair-builtin.log` 09:54:08 段；`profiles/` 09:54:10.036、`profiles/web/node_modules` 09:54:10.060、`/storage` 09:54:10.760 |
| 8 | rc1 迁移（空迁移） | 09:54:08.480 → 09:54:21.488 | **13.0 s** | `inputs:{}`（**无内容**） | `prepare.json.createdAt`=1791453248 / `receipt.json.verifiedAt`=1791453261；两文件均 `inputs:{} sources:[] presets:[] sessions:[]` |
| 9 | 首次 web 启动 → 可开会话 | 09:54:21.5 → 09:55:04.896 | **≈43 s** | — | `sessions/`、`workspace.json` 09:55:04；**可能含用户点击时间，不可全算作机器耗时** |
| — | **首启合计** | 09:53:29.68 → 09:55:04.9 | **≈95 s** | — | 上表逐段相加 |

### 吞吐核算（第 1–5 段）

- 写入 **793.9 MiB / 14 s ≈ 56.7 MiB/s**，同时完成 ~31 k 次元数据操作（24 441 文件 + 5 126 目录 + 1 233 链接）。
- 压缩输入约 242 MB（85.8 + 117 + 28.5 + 6.5 + 3.8 + 0.9）→ 展开 794 MiB ≈ 3.3×，即压缩侧 ≈17 MB/s。
- **fsync 占比实测**（本机 f2fs `/tmp`，4 000 × 32 KiB，等价实验）：
  | 写法 | 耗时 | 速率 |
  |---|---|---|
  | 不 fsync | 0.353 s | 354.3 MiB/s |
  | 每文件 fsync（批 128，与代码一致） | 1.362 s | 91.8 MiB/s |
  ⇒ **fsync ≈ 0.252 ms/文件**。按 24 441 文件 + 5 126 目录折算 ≈ **7.4 s**，
  即 **14 s 解包里约一半是 fsync durability 屏障，不是解压 CPU**。
- 参考：内容 SHA-256 vs 只读元数据遍历（同一设备，`/usr/local/lib/node_modules`，
  405.1 MiB / 15 301 文件 / 3 207 目录）：
  | 操作 | 耗时 | 速率 |
  |---|---|---|
  | 逐文件 sha256 | 5.877 s | **69 MiB/s** |
  | 只读元数据遍历 `find -printf` | 0.232 s | — |
  | 每节点 `stat` 遍历 | 0.294 s | — |
  ⇒ 元数据遍历比内容哈希**便宜约 20–25 倍**。

---

## 2. 瓶颈结论（一次性 vs 每次重复，带代码/数据证据）

### 2.1 一次性（首启才有）
- 解包 793.9 MiB（11.5 s）——**已经并行**：`ColdSplitExtraction.run()` 起 2 个线程（rootfs→base、
  dsh-runtime→`split-dsh-<uuid>`），实测 mtime 交错证明走的就是这条并行路径；
  `ColdBundleTransaction` 恒走 `transaction.candidate()`，所以 `coldCandidate != null`，并行的 split 分支必然生效。
- 隔离试运行（≈25 s，`RuntimeTrial.verify`）**与**真实首启（≈43 s）**各跑一次完整启动**，即首启付两次启动成本。这是安全不变量（十项健康核验后才提交），不建议删。
- rc1 迁移 13 s：**空迁移也花 13 s**（`inputs:{}`、`sources:[]`、`sessions:[]`），这是可疑的一次性浪费，未定位到具体耗时点（容器内看不到它的 stdout）。
- 589 链接 + 8 插件注册 ≈2.4 s。

### 2.2 每次启动重复做的（已核查，**都不是瓶颈**）
| 疑点 | 结论 | 证据 |
|---|---|---|
| 每次重算大文件 sha256 | **否**。整棵受管树只在「健康确认」时哈希一次，结果存 `runtime-health/<id>.json`，正常就绪读回执 | `ManagedRuntimeAssets.java:115` 注释 + `confirmHealth()` 写回执 |
| 每次重建 589 symlink | **否，有 stamp 缓存**。`runtime_links_stamp()` 只 stat **94** 条（包目录+`package.json`+bundle/shared `node_modules`+`@scope` 目录 + 8 个内置的 dir/package.json/node_modules），键 = `{version:2, managedAssetId:<64hex>, stamp}` 整体相等即命中 | `register-builtin-plugins.py:137-194`；日志 09:54:08 是 MISS（"已补充 589 …"），10:09:05 / 10:23:48 是 HIT（"均已就绪，无需改动"）；`/root/.dsh/node_modules` 仅 152 K（194 链接） |
| 每次 `dpkg --unpack/--configure` | **否，当前构建根本不跑 dpkg** | `ubuntu-tools.layout`=`configured-overlay-v1` ⇒ `ColdToolsInstaller.configured()` 只解预配置覆盖层，并校验 `archiveSha256` + `var/lib/dpkg/status` 摘要；`.dsha-ubuntu-tools-version` = `packageLockSha256` |
| 是否联网下载 certifi | **否**。`runtime-tools.json` 的 certifi URL 只是元数据；唯一消费者 `plugin-dependencies.py:runtime_tool_identity()` 只取 `pnpm` 块 | 全仓无 `files.pythonhosted.org` 的下载代码；`profiles/web/node_modules` 恰好 8 项、无 `.pnpm`/lockfile ⇒ 首启**没跑 pnpm install** |
| 压缩格式/实现 | gzip(RFC1952)+tar，单线程 `java.util.zip.Inflater`，64 KiB 压缩缓冲，外面套 `BufferedInputStream(262144)`；拷贝缓冲 256 KiB | `StrictGzipInputStream`、`LegacyTarReader:66`、`ArchiveFileBoundary.copyBuffer` |
| 是否 APK 内二次压缩（双重解压） | **否** | `app/build.gradle:104` `noCompress += ['gz','xz','bin','ja','tar','whl']` |
| 重复扫描 / 重复 mkdir | 解压后有一次性全量复核（`AndroidTrustedAssetFileSystem.finishFiles()` 重列每个目录 + 逐文件 lstat + 逐脏目录 fsync），属**签名成员证明 + TOCTOU 不变量**，不可删 | `AndroidTrustedAssetFileSystem.java:655-718` |

### 2.3 真正的瓶颈（按实测占比排序）
1. **fsync durability 屏障 ≈7.4 s / 14 s 解包（≈53 %）** —— `queueSync` 每文件一次 + `finishFiles` 每脏目录一次，共 ≈29.5 k 次 fsync，0.252 ms/次。**不可在无 JNI 的情况下合并**（`NativeStorage.flushFiles` 是 native）。
2. **候选树被全量 SHA-256 两次 ≈23 s**（其中 **≈11.5 s 纯冗余**）。
   `ColdInstallTransaction.prepared()` 算一次 `BackupTree.digest(candidate.linux())` 存进 `prepared.json`；
   `publish()` 又算一次做比对 —— 两次之间 `candidate.linux()` **没有任何写入**
   （之间只写 `directory/{prepared.json,guest-closed,publishing}`，都在被哈希根之外）。
   794 MiB @ 69 MiB/s ⇒ 单次 ≈11.5 s。→ **已实现缓存，见第 3 节。**
3. **两次完整启动（隔离试运行 + 真实首启）≈68 s** —— 安全设计，不建议动；要省只能减启动本身的开销。
4. rc1 迁移空转 13 s —— 可疑，待真机带 stdout 定位。

---

## 3. 加速项清单（收益估算 + 风险 + 是否已实现）

| # | 项目 | 提速估算（依据） | 正确性风险 | 状态 |
|---|---|---|---|---|
| 1 | 复用未变化候选树的摘要（元数据签名 + 缓存），消除 `publish()` 的第二次全量哈希 | **≈8–11 s**：单次全量 794 MiB@69 MiB/s≈11.5 s；签名遍历 ≈1.1 s（29 567 节点；`list()` 走 anchored 约 15 组件 open，5 126 目录）；首次多付 2×1.1 s ⇒ 23.0 s → 14.8 s | 低。签名覆盖 相对路径/类型/权限/大小/mtime/dev/inode/链接目标，**与 `Node.same()` 同等强度**（代码库各处已用它作 SOURCE_CHANGED 依据）；缓存前后各算一次签名，只有两次相等才登记，摘要不会与签名脱钩；树真变化时签名不同 ⇒ 走全量哈希 ⇒ 仍报 `COLD_PREPARED_CHANGED` | **已实现** |
| 2 | 把 `queueSync` 的逐文件 fsync 合并为提交点一次 `syncfs()` | **≈7 s**（29.5 k × 0.252 ms） | **中高**。需新增 JNI（`android.system.Os` 无 syncfs/sync）；改为「结束时一次性落盘」会弱化中途 ENOSPC 早期发现，且崩溃窗口语义变化，评审成本高 | 未实现（建议单独评审） |
| 3 | rc1 迁移空迁移快速返回 | 上限 **13 s**（实测区间），实际能省多少未定位 | 中。迁移是要保留数据的路径，必须保证「无输入」判定本身可靠 | 未实现（待真机 stdout） |
| 4 | 用 `AndroidTreeFileSystem` 的批量原生 stat 做签名遍历 | 第 1 项的签名遍历从 ≈1.1 s 降到 ≈0.3–0.5 s，即再多省 **≈1 s** | 中。`AndroidTreeFileSystem` 是 Android 专有路径，本机单测只能覆盖通用分支 | 未实现（可选优化） |
| 5 | 把资产再切分/多线程解压（>2 流） | 收益有限：解压已 2 线程，且 ≈53 % 时间是 fsync，不受线程数影响 | 高（改资产格式 + 打包契约） | 不建议 |
| 6 | certifi 等小件打进资产避免联网 | **0 s** | — | 无需做：**首启本来就不联网**（见 2.2） |
| 7 | 首启/冷启动分离、进度、后台线程 | 已有进度：`InstallPipeline` 每 8 MiB 追加「已读取离线包 N MiB」，`ExtractionProgress.onStage` 报阶段 | — | 基本已具备（主线程阻塞**未确认**，见第 5 节） |

---

## 4. 已改动的文件

worktree：`/root/Documents/deepseek-harness/default-workspace/dsha-envfast`，分支 `perf/env-setup-speed`（base `70e37a7`）。

- 新增 `app/src/main/java/com/deepseekharness/app/backup/TreeDigestCache.java`
  —— 纯逻辑：元数据签名 + 摘要缓存，未变化时不再读文件内容。
- 新增 `app/src/test/java/com/deepseekharness/app/backup/TreeDigestCacheTest.java`（7 个用例）
- 改 `app/src/main/java/com/deepseekharness/app/backup/ColdInstallTransaction.java`
  —— 新增事务内 `TreeDigestCache` 字段 + `digestOf(File)`，把 4 处 `BackupTree.digest(...)` 改为走缓存。
- 改 `tools/java-format-files.json` —— 登记上面两个新文件（仅 +2 行）。

## 5. 验证情况

跑成的：
- `tools/format-java.py --check`：3 个文件全 **PASS**（google-java-format 1.22.0，0 changed）。
- `TreeDigestCacheTest`：**OK (7 tests)**。含「第二次 digest 不产生任何内容读取」断言（用计数代理 FS）。
- `ColdInstallTransactionTest`：**OK (11 tests)** —— 含「prepared 之后篡改候选 ⇒ publish 必须抛
  `COLD_PREPARED_CHANGED`」，证明缓存没有削弱变化检测。
- 度量实验：fsync 对比、内容哈希 vs 元数据遍历、mtime/find 时间线（均只读或只在 `/tmp` 等价目录）。
- 跑法（本机无 aapt2，绕开 gradle）：
  ```
  javac -d /tmp/out -cp "<android.jar>:<gradle 缓存 jars>" \
        -sourcepath app/src/main/java:app/src/test/java:app/build/generated/uiLanguage \
        app/src/test/java/com/deepseekharness/app/backup/TreeDigestCacheTest.java
  java -cp /tmp/out:... org.junit.runner.JUnitCore com.deepseekharness.app.backup.TreeDigestCacheTest
  ```
  （JUnit 4.13.2 / hamcrest 1.3 jar 已按 `run-unit-tests.py` 期望的路径放进
  `~/.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/local/`；
  `UiMessages` 用 `tools/prepare-ui-languages.py --output app/build/generated/uiLanguage` 生成。）

没跑成的 / 看不到的：
- `tools/run-unit-tests.py` 全量：需要 `R.jar`/`R.txt`（aapt2 在 aarch64 跑不了），本机无法整树编译。
- **App 侧 09:53:43→09:54:08 那 ≈25 s 的内部拆分**：容器内无日志，`/data/data/com.dsh.clienu` 不可读，
  只能按代码判断是 `RuntimeTrial.verify` 隔离试运行。
- rc1 迁移 13 s 具体花在哪里：看不到它的 stdout。
- 主线程是否被解压阻塞：**未确认**。

必须真机验的清单：
1. 首启总时长与分段：用 App 内「首次安装测速」页（`ColdSetupSpeedActivity` → `ColdSetupTiming.run()`），
   它会把每段 `elapsedMillis` 写到 `files/cold-install-probes/latest.json`。**这是现成的可复走方法。**
2. 本改动的实际收益：真机装 APK 后对比 `prepared.json` 写入时刻 → `publish` 完成时刻，
   应比改动前少 ≈11 s；核对 `var/lib/dpkg/status`、`.dsha-ubuntu-tools-version`、
   `runtime-descriptor.json`、`.offline-extracted` 全部仍然正确、无「假绿」。
3. 反向用例：解压完成后、`publish` 之前人为改动候选树，必须仍然抛 `COLD_PREPARED_CHANGED` 并回滚。
4. 「每次冷启动」回归：确认 `confirmHealth` 仍只跑一次、`runtime-health/<id>.json` 命中、
   589 链接仍走 HIT（`repair-builtin.log` 应打印"无需改动"）。
5. 建议顺带测：rc1 迁移在**全新**环境下的真实耗时（现在测到 13 s 但无输入）。
