# 内置修复插件 dsh-fs-read-hint（读取提示，临时）

> 状态：临时修复包，随 DSHA 签名内置；上游修复或失效后撤除。
> 上游跟踪：[deepseek-harness discussions #11977](https://github.com/deepseek-ai/deepseek-harness/discussions/11977)。
> 对应 DSH：0.2.1-rc.5（`dsh-tool-fs`、`dsh-fs-observation-policy`、`dsh-tool-str-replace-editor`）。

## 1. 问题

DSH 默认挂载的 `fs-observation-policy` 要求同一会话先用 `read` 工具读过**同一路径**，才允许 `edit` / 覆盖 `write` / `str_replace_editor` 修改；否则以 `FS_NOT_OBSERVED` 拒绝：

```text
cannot modify "<路径>": file has not been read — read the file, then retry
```

本仓库多会话并行，每个会话在 `.agents/worktrees/<type>/<name>/` 下有独立工作树，与主检出存在大量同名文件。模型常读主检出、改工作树副本；报错只给目标路径，模型无从得知“读的是另一份”，往往重读错的副本或凭记忆重试。AGENTS.md 已有约定（读写都用工作树路径），但约定只对读过它的 agent 有效。

## 2. 插件行为

[插件源码](../app/src/main/assets/builtin-plugins/dsh-fs-read-hint/lib/index.js)只做一件事：

- 监听 `fs/observed`，按会话记住 `read`（及成功修改）观察到的路径，每会话最多 4096 条，超出淘汰最早；
- 在 `tools/execute` 上包住 `edit` / `write` / `str_replace_editor`：结果为 `FS_NOT_OBSERVED` 且本会话读过**同文件名、不同路径**的文件时，在报错末尾追加最多 2 个候选（公共路径后缀越长越靠前）：

```text
cannot modify "<worktree>/app/src/X.java": file has not been read — read the file, then retry — note: this session read "<main>/app/src/X.java", a different path with the same file name; read the exact path above, then retry
```

不变的部分：

- 门禁仍拒绝，仍要求读取目标路径本身；不自动读取、不重试、不放行任何修改；
- 错误码 `FS_NOT_OBSERVED`、`FS_STALE_VERSION` 等原样保留；`FS_STALE_VERSION` 不改写；
- 没有同名候选、结果形状不认识（非单段 `Error: <message>` 文本）、插件停用或删除时，报错与上游逐字一致；
- 会话之间互不可见；shell 读取（`cat` / `sed`）本来就不产生观察，也不会出现在候选里。

## 3. 登记位置

按签名内置插件登记（参照 `dsh-launch-target`）：

| 位置 | 内容 |
|---|---|
| [builtin-plugins.json](../app/src/main/assets/builtin-plugins.json)、[dsha-builtin.txt](../app/src/main/assets/dsha-builtin.txt) | 注册事实源；`BuiltinPluginRegistry` 由生成器派生 |
| [plugin-manager-policy-patch.json](../app/src/main/assets/plugin-manager-policy-patch.json)、[auto-review-native-disabled-patch.json](../app/src/main/assets/auto-review-native-disabled-patch.json) | 签名名单（`dshaSignedPluginNames` / `signedWrappers`） |
| [managed-runtime-inputs.json](../app/src/main/assets/managed-runtime-inputs.json) | 三个文件安装到 `/root/dsha-fs-read-hint/` |
| `runtime-descriptor.json`、`managed-package-proofs.json`、`dsh-runtime.inputs.json` | 签名名单在运行时归档补丁内，须按锁重建 `dsh-runtime.bin` 后同步摘要 |

## 4. 验证

[test-fs-read-hint.mjs](../tools/test-fs-read-hint.mjs) 使用真实 rc.5 的 `ToolRuntime` + `dsh-fs-local` + `fs-observation-policy` + `dsh-tool-fs` + `str_replace_editor` 组合，只在 `app/build` 临时目录读写：

```bash
DSHA_TEST_RUNTIME=<raw 测试运行时> node tools/test-fs-read-hint.mjs
```

覆盖：上游报错形状契约、读错副本时三种工具附提示且文件不变、读对路径后正常放行、无候选时逐字不变、候选排序与会话隔离、`FS_STALE_VERSION` 不改写、形状不符不改写、不重复追加、记录上限。

## 5. 撤除条件

满足任一即撤除，不保留空壳：

1. **明确不再需要**：上游在 #11977 或后续版本提供同等提示（例如报错自带已读同名路径或结构化 remediation），或观察策略改为可选且 DSHA 不再挂载；
2. **代码变动导致失效**：升级 DSH 后 `test-fs-read-hint.mjs` 的「上游契约」用例失败（报错文字、错误码、`tools/execute` 结果形状或 `fs/observed` 参数变化），或插件不再能追加提示。失效时插件按设计原样透传，不会改坏报错，但应撤除而不是修补。

撤除步骤：删除插件目录与测试，从 `builtin-plugins.json`、`dsha-builtin.txt`、两份签名名单补丁、`managed-runtime-inputs.json` 移除登记，重新生成 `BuiltinPluginRegistry`，回调各测试中的内置数量，按锁重建运行时归档并同步摘要；在本页顶部写明撤除版本与原因。用户此前的启停意图随登记一起消失，不影响其它插件。
