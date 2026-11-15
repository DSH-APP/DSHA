# 内置修复插件 dsh-fs-search-scope（检索范围，临时）

> 状态：临时修复包，随 DSHA 签名内置；DSH 上游 glob 自行剪掉嵌套工作树后撤除。
> 对应 DSH：0.2.1-rc.5 与 0.2.2-rc.1（`dsh-tool-fs-search`、`dsh-tools` 及 DSHA 必经策略补丁 `DSHA_REQUIRED_POLICY_V1`）。
> 任务来源：看板 T45。

## 1. 问题

DSH 的 `glob` 固定执行 `rg --files --no-ignore --hidden --sort=modified`，只排除 `.git` 等版本库目录。本仓库多会话并行，主检出 `.agents/worktrees/` 下常有几十个工作树：从仓库根检索 `*.java` 时，主检出自身约 1,500 条，加上工作树超过 26,000 条（2026-11-03 本机实测，工作树最多时约 14 万条），`--sort=modified` 还会逐个 stat，输出膨胀足以拖死会话。`grep` 不带 `--no-ignore`，`.gitignore` 生效，不受影响。

## 2. 插件行为

[插件源码](../app/src/main/assets/builtin-plugins/dsh-fs-search-scope/lib/index.js)在 host 层 `tools/execute` 上接管上游 `glob` 的执行，只在 ripgrep 参数里加排除项：

- 剪掉任意深度的 `.agents/worktrees` 目录；
- 剪掉检索根所在仓库 `git worktree list` 列出的、位于检索根之内的**链接工作树**（主工作树不剪）；
- 检索根本身在 `.agents/worktrees` 内（`path` 指向某个工作树或 `.agents/worktrees` 本身）时不接管，结果与上游逐字一致；
- 系统提示追加一句：glob 跳过嵌套工作树，要检索某个工作树请把它作为 `path`。

不变的部分：

- 仍含忽略文件与隐藏文件（`--no-ignore --hidden`）；参数校验、ripgrep 调用、结果上限、渲染、溢出保存全部复用上游导出函数与已注册的 glob 定义；
- 接管前按带补丁的 `dispatchToolBody` 同序调用 `verifyGlobalConsent` → rc5 的 `resolveExecution` 或 0.2.2 的 `executable` → `verifyDispatch`，不跳过、不放宽；任何一步失败即报错且不执行；
- 策略模块加载不到、上游形状不认识、无需剪枝、参数非法、检索根不是目录、嵌套调用（`run_code` 内）时一律交回 `next()` 走原工具；
- 不改 DSH 本体（`/usr/local/lib/node_modules/@deepseek-ai/dsh`）。

已知限制：

- git 子模块（目录内是 `.git` 文件）不剪，只认 `git worktree list` 的登记；未登记的残留目录只在 `.agents/worktrees` 下才被剪掉；
- 检索根所在目录不在 git 仓库内、或本机没有 git 时，只有 `.agents/worktrees` 固定规则生效；
- ripgrep 按「去掉 cwd 前缀后的遍历路径」匹配 `--glob`，绝对路径形式的排除项不生效，所以每个链接工作树同时写成相对 cwd 与 `**/<绝对路径>` 两种形式；
- 本机另外观察到两处与插件无关的 ripgrep 现象（不装插件同样出现）：以绝对路径为根、多线程遍历时偶发 ENOENT 少列文件；以软链接为根直接报 ENOENT。

## 3. 停用与回退

在插件页停用或删除 `dsh-fs-search-scope` 即恢复上游 glob 原样。插件只注册一个 `tools/execute` 中间件和一段系统提示，不改任何上游注册行，也不改 preset。

## 4. 登记位置

按签名内置插件登记（参照 [dsh-fs-read-hint](fs-read-hint-plugin.md)）：`builtin-plugins.json`、`dsha-builtin.txt`、两份签名名单补丁、`managed-runtime-inputs.json` 三行安装登记（`/root/dsha-fs-search-scope/`），由生成器派生 `BuiltinPluginRegistry`。签名名单在运行时归档补丁内，须按锁重建 `dsh-runtime.bin` 并同步 `runtime-descriptor.json`、`managed-package-proofs.json`、`dsh-runtime.inputs.json` 与应急锁；固定构建输入发布须用户同意。

## 5. 验证

[test-fs-search-scope.mjs](../tools/test-fs-search-scope.mjs) 使用 managed 测试运行时（含 DSHA 必经策略补丁）的真实 `ToolRuntime` + `dsh-subprocess-local` + `dsh-tool-fs-search`，夹具是系统临时目录下带链接工作树的 git 仓库：

```bash
python3 tools/run-host-tests.py --stage runtime --only test-fs-search-scope.mjs
```

覆盖：上游契约（未装插件时展开工作树）、仓库根剪枝且保留忽略/隐藏文件、`path` 指向工作树内或上层时与上游一致、子目录内的链接工作树被剪、检索根在工作目录外、超出上限的上游渲染、非法参数报错与上游一致、策略三步顺序与失败不执行、纯函数。

## 6. 撤除条件

满足任一即撤除，不保留空壳：

1. **明确不再需要**：上游 glob 自行剪掉嵌套工作树或提供排除配置；
2. **代码变动导致失效**：升级 DSH 后测试的「上游契约」用例或策略三步用例失败（glob 描述、输出形状、导出函数或策略补丁变化）。失效时插件按设计交回原工具，不会改坏结果，但应撤除而不是修补。

撤除步骤与 dsh-fs-read-hint 相同：删除插件目录与测试，移除各处登记，重新生成 `BuiltinPluginRegistry`，回调测试中的内置数量，按锁重建运行时归档并同步摘要；在本页顶部写明撤除版本与原因。
