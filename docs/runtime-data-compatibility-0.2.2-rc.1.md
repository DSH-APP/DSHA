# DSH 0.2.2-rc.1 数据读取证据（T55，宿主夹具）

本记录只针对正式运行时 `0.2.1-rc.5` → `0.2.2-rc.1` 的**前向读取**证据。它不从版本号推导格式，不修改个人数据，不授权回退，也不是手机验收。与 rc5 记录不同，本轮**数据读取模块字节已变化**，证据来自真实 API 交叉读写，不是逐字节相同。

## 输入

| 项 | 值 |
|---|---|
| 旧运行时 | 本机已安装的受管 rc5 树（`/usr/local/lib/node_modules/@deepseek-ai/dsh`，含 DSHA 受管补丁），rc5 锁 SHA-256 `f9fa1805…` |
| 新运行时 | 0.2.2-rc.1 raw 锁定树（锁 SHA-256 `f327e64f…`），加 `tools/build-dsh-runtime.py` 的 `patched_content` 对数据模块的投影：storage-json（`DSHA_PROROOT_DIRENT_FALLBACK_V3` / `DSHA_PROROOT_DIRECT_RECORD_HINTS_V2`）、session-persistence-jsonl（直连提示 + 受管发布器）、fs-local / attachment-local 受管发布器、session-format-v2-to-v3 兼容包装，以及 `dsha-runtime-fs`、`dsha-session-compat` |
| 未投影 | 稳定补丁链（如 rc1-settings-migration、config-editor-lock-order）。本测试的读取链不调用这些模块的补丁路径 |
| 工具 | `tools/test-dsh-data-cross-runtime.mjs`（SHA-256 `ca8bc33b…`，默认边已改为 rc5 → 0.2.2-rc.1），Node v24.19.0，Linux arm64 / proot |

新运行时是**构建器投影的夹具树**，不是最终 `dsh-runtime.bin`。正式发布前须对最终归档解出的受管树再跑一次同一命令。

## 读取模块字节对比（31 个包）

- 相同 17 个：storage、session-persistence、session-format、catalog、v1-to-v2、v2-to-v3、projection-cache、session-query(-sqlite)、atomic-write、credentials(-local)、agent-preset、workspace、jobs-local、output-retention、home-paths。
- 变化 14 个：storage-json、storage-domain、session、session-persistence-jsonl、v0-to-v1、v3-to-v4、session-projection、attachment(-local)、settings、config-editor、app-boot、permission-presets、cordis-plugin-loader。
  其中一部分差异来自旧侧已打的稳定补丁，另一部分是上游改动，本记录不逐一区分。

## 真实 API 交叉读写

```bash
node tools/test-dsh-data-cross-runtime.mjs --old <rc5 受管树> --new <0.2.2 投影树> --report <report.json>
```

结果 `PASS_FINITE_HOST_CROSS_READ_WRITE`，五个阶段全部通过：old-write（rc5 写）、new-read（0.2.2 只读，数据树成员与 SHA 不变）、new-write、new-reopen（新进程）、old-read-new（rc5 读 0.2.2 写出的数据）。覆盖范围与 rc5 记录相同：single/per-record JSON、V4 none/zstd 会话与追加、V3 迁入 V4 并保留原件、文件与流式附件、Sharp 图像、credentials、profile 读取。

`old-read-new` 通过只说明**这些表面**在降级后可读，不代表整体可以降级，见下节。

## 单向写入（降级后旧版读不了或会变化）

1. **schedule 单元 v1 → v2**。0.2.2 的 schedule domain 声明 `version: 2, compatibleVersions: [1]`，使用 single 布局。实测（storage-json 层，按真实描述符）：
   - 0.2.2 只读打开 rc5 写出的 v1 文件时，字节不变；
   - 0.2.2 第一次写入后，整单元重新标记为 `version: 2`；
   - 之后 rc5 打开该单元报 `version-mismatch: stored version 2 != expected 1`，**整个定时任务单元都读不了**，不是只丢失单条记录。
2. **app-boot 改写 profile `package.json`**。启动时 `dropRetiredBundles` 会从任意 profile 的 bundles 中删掉 `@deepseek-ai/dsh-experimental-schedule-bundle`；`normalizeShippedProfile` 会把 `headless` profile 的精确旧元组规范为模板。只在命中时写回。web profile 一般不命中，但 ConfigurationSnapshots 可能看到这次改写。本条来自源码审读，没有实测。

因此不声明 rc5 读取 0.2.2 数据的回退边。DSHA 现有回退门（`canReadDataWrittenBy` 加归档指纹）本来就拒绝这条回退。

## storage-json 语义变化（V3）

0.2.2 上游仍然用 `withFileTypes`（d_type）枚举目录，也没有处理“刚发布的项暂时不出现在父目录枚举”的问题。DSHA 的处理：

- 不枚举 unit 根目录，按 descriptor 逐个直达声明的表；
- 表内用字符串 `readdir`，加上进程内提示；
- 非预期的文件系统错误照常抛出。

本轮采纳的上游新语义：

- 已声明的表目录只要存在（即使为空）或 `global.json` 存在，就抑制 legacy bootstrap；
- `deleteRecords` 批量删除。提示按每个键的实际结果更新：暂存或跳过的键忘记；回滚成功的键重新登记，因为 rename 回原路径会产生新的目录项；回滚失败时 unit 置为 `unknown-state`，后续操作全部拒绝。

`tools/test-storage-json-proroot-dirent.mjs` 已扩展覆盖以上场景，并在投影模块上通过。删除 remember、staged-forget 或空目录抑制逻辑，测试都会失败，说明它确实能抓住这些回归。

## 未验证

- 最终 `dsh-runtime.bin` 解出的受管树（本记录用的是投影树）；
- 真机 proroot 下的写后重开（RuntimeTrial 存储核验）；
- 用真实 schedule 插件跑 v1 → v2（只在 storage-json 层用真实描述符验证）；
- app-boot profile 改写的实测，以及它与 ConfigurationSnapshots 的交互；
- 真实手机数据、SQLite 重建、完整 ConfigEditor 在线写入、所有历史会话形状、回退授权。
