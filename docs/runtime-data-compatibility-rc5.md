# DSH rc5 数据读取证据（build213）

本记录只针对正式运行时 `0.2.1-rc.3` → `0.2.1-rc.5` 的**前向读取**证据。它不从 DSH 版本号推导数据格式，不修改个人数据，不证明旧运行时可安全回退，也不把宿主夹具结果写成手机验收。独立应急运行时当时仍固定 `0.2.1-rc.3`（`tools/recovery-runtime/lock.json`），不在本记录覆盖范围；build215 起应急锁另行同步到 rc.5，见下方说明。

## 归档与身份

| 项 | 值 |
|---|---|
| 旧正式归档（build212 固定输入） | `app/src/main/assets/dsh-runtime.bin` 原字节，127,671,447 bytes，SHA-256 `a02cc5a42727238491ee36d4b0b46461d035dd6f8fca22580eb19d8456fbefc3` |
| 新正式归档（本轮 rc5） | 127,674,426 bytes，SHA-256 `91aa4685721ce569e32953295dc6b01b63fe2ac5e1eac8173440656896acdcb2` |
| 新 runtimeId | `07e9a20888a7a5b6475c5963acd1cc518d7993f067a337afba9379a8787fb530`（最终 descriptor，含 build213 终端 launcher 改动） |
| rc3 锁 | SHA-256 `5d37cba4af2ec8b1afe1e72512acbcf2107be0d12f14c4180b8a058879e38b81` |
| rc5 锁 | SHA-256 `f9fa18056f0641e29c27c45b39cea3d6ec6535e7d61319c263a510d626561e6b` |
| 描述符 dataRead | `['dsh-0.2.1-rc.3', 'dsh-0.2.1-rc.5']`（只新增这一条前向边，未扩写更老边） |

旧归档 SHA 与 build213/214 的 `tools/recovery-runtime/lock.json` 中 `recovery-dsh-runtime.bin` 相同；当时正式升级后应急仍使用该固定字节，不随该轮替换。build215 经用户明确授权把应急锁同步到本表的新正式归档（`91aa4685…acdcb2`），APK 内共用一份字节；这是一次独立的锁变更，本记录的数据读取证据不因此扩写到应急运行时。

## 原始字节核对

两个 raw 树都由实际 `npm ci --os=linux --cpu=arm64 --libc=glibc --ignore-scripts` 生成：旧树使用 rc3 锁，新树使用本轮 rc5 锁。对照 `tools/test-dsh-data-cross-runtime.mjs` 中 31 个数据/配置包（共 75 个 `.js` 模块），逐文件 SHA-256 比较：

- 新增 0 个、缺失 0 个、字节变化 0 个；
- 75 个数据读取模块在 rc3/rc5 raw 之间逐字节相同；只有各包 `package.json` 的版本字段为 `0.2.1-rc.5`。
- 30 个包版本从 `0.2.1-rc.3` 变为 `0.2.1-rc.5`，但数据读取实现字节没有变化。

覆盖包：`dsh-storage`、`dsh-storage-json`、`dsh-storage-domain`、`dsh-session`、`dsh-session-persistence`、`dsh-session-persistence-jsonl`、`dsh-session-format`、`dsh-session-format-catalog`、`dsh-session-format-v0-to-v1`…`v3-to-v4`、`dsh-session-projection`、`dsh-session-projection-cache`、`dsh-session-query`、`dsh-session-query-sqlite`、`dsh-attachment`、`dsh-attachment-local`、`dsh-settings`、`dsh-config-editor`、`dsh-app-boot`、`dsh-atomic-write`、`dsh-credentials`、`dsh-credentials-local`、`dsh-agent-preset`、`dsh-workspace`、`dsh-jobs-local`、`dsh-permission-presets`、`dsh-output-retention`、`dsh-home-paths`、`cordis-plugin-loader`。

正式包内还有 DSHA 自己的受管补丁。把旧正式归档与本轮新归档按同一 builder/注册表投影后，上述 75 个模块在两边也逐字节相同（`data-cross-managed-rc3-to-rc5.json` 的 `inputs.old.files` 与 `inputs.new.files` 全部相等）。受管补丁（`DSHA_ATOMIC_PUBLISH_V1`、storage-json proroot 目录回退、session 直连提示等）是构建时固定转换，不是本轮升级新增的语义变化。

## 真实 API 交叉读写

命令（本机 Linux arm64 / proot 环境，Node v24.19.0）：

```bash
node tools/test-dsh-data-cross-runtime.mjs \
  --old app/build/data-compat/managed-rc3 \
  --new app/build/data-compat/managed-rc5 \
  --old-version 0.2.1-rc.3 --new-version 0.2.1-rc.5 \
  --report app/build/data-compat/data-cross-managed-rc3-to-rc5.json
```

两个目录是从**实际归档**解出的受管树（rc3 来自固定输入归档，rc5 来自本轮新归档），不是 raw 树；每阶段使用新的 Node 进程，工具 SHA-256 `feeacd0afaa810344ae04eaff2ea00b19f35f66382f93e23733048e3e3aa7edc`。报告结果 `PASS_FINITE_HOST_CROSS_READ_WRITE`，五阶段全部通过：

| 阶段 | 结果 |
|---|---|
| `old-write`：rc3 写 JSON/会话/附件/凭据/profile | PASS |
| `new-read`：rc5 只读；整个隔离数据树成员与 SHA 不变 | PASS |
| `new-write`：rc5 追加与新建 | PASS |
| `new-reopen`：新进程 rc5 重开 | PASS |
| `old-read-new`：新进程 rc3 读取新写数据 | PASS |

覆盖：single/per-record JSON 全局值与嵌套记录；V4 none/zstd 会话写入与追加；V3 none/zstd 经真实 codec 写入并由真实 reader 迁入 V4（保留 V3 原件字节）；普通文件与流式文件附件；真实 Sharp 归一化图像；真实本机 credentials 双代引用；真实 profile reader 与 `composeEntries`（固定夹具值，无用户凭据）。

未覆盖（报告中 `excluded` 原样保留）：真实手机数据、图像格式/尺寸矩阵、SQLite 查询重建、完整 ConfigEditor 在线写入、所有历史会话形状、业务任务重放、回退授权。

### 一次失败记录（保留原始原因）

直接在 raw 树上跑同一工具时，rc3 与 rc5 的 upstream `link(2)` 发布调用在本机 proot 的 `--link2symlink` 环境下表现为目录项 `DT_LNK`，工具的只读阶段成员校验因此报 `unexpected nonregular fixture file`。这不是数据内容变化，也不是 rc5 的回归；正式归档里的受管发布器已把该调用替换为受管 `publishExclusive` 路径，改用实际受管树后五阶段通过。记录此差异是为了说明本证据来自受管归档字节，而不是把 raw 树的环境差异当作通过。

## 格式常量与边界

由于数据读写实现字节相同，实际格式常量保持：session V4（历史 V0–V3 由现有 codec/catalog 读取）、attachments/v1、credentials document V1、projection cache V7（兼容记录 V3–V6）、derived SQLite schema V8、workspace domain V2；JSON storage 版本由各 domain descriptor 声明；profile 继续读取 `package.json`、`cordis.patch.yml` 与 bundle layers。以上结论来自实际模块字节与真实读写，不是从版本名推导。

上游本轮非数据变化（对话 thinking 展示、`dsh-client-ui-settings-account` 等）不改变上述读取链。它们不构成本记录对全部业务语义、模型服务或插件行为的声明。

本机材料 `app/build/data-compat/data-cross-managed-rc3-to-rc5.json` 是宿主回执，不进入交付包；它不授权删除旧数据、不生成已知格式，也不产生回退边。`DataFormatEvidence` 仍绑定整个 `dsh-runtime.bin` 和实际持久化配置补丁；完整格式状态仍保持 `unknown`，回退仍须既有精确指纹与真实 `RuntimeTrial`。
