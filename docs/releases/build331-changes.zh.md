# DSHA 0.2.1-rc.5-20261106.0400-dsh0.2.2-rc.1 / build331

- proroot 下 git 大 diff 崩溃改为 fail-loud（T82）：build330（proroot v1.2.8）下 `git show --stat`、`git diff --stat` / `--numstat` 对某些提交或一次 ≥8 个路径的组合会确定性崩溃——exit 139、stdout 0 字节，stderr 只有 `[proroot] SIGSEGV pc=… lr=…`；命令若接 `| head` / `;`，最终 rc 会被吞成 0，审查与合入可能静默拿到空数据。新增共享封装 `tools/git_guard.py`：命中 rc=139 / -11 或 stderr 含该签名时立即抛出 `GitSigsegvError`（`GIT_PROROOT_SIGSEGV`），不重试、不降级、不返回部分输出，错误里给出 `--name-status` / `--dirstat` / ≤7 路径分片等安全替代；仓库内调用 git 的工具统一改用 `git_output`，新增 `tools/test-git-guard.py` 并登记 host-tests manifest，AGENTS 与运行引擎文档补充本机陷阱与审查/合入安全用法。
- 首次连接提示补丁归档说明（T58）：新增 `docs/design/first-connect-indicator-20261106/README.md`，记录该补丁不合并的结论、目标现象难复现的多层原因、维护与风险代价、以及后续新任务的前置条件，并加入文档索引；旧提交保留为 tag `archive/feat/first-connect-indicator-20261106`（`5918af63`）。这是文档归档，不是功能补丁，不改运行时；旧补丁不进入本版。
- 更正（2026-11-06）：T58 补丁自 build324（`a54f9854`）起已并入并随包，build324–build331 都携带；上一条「旧补丁不进入本版」仅指归档分支快照 `5918af63` 不另行合并，不表示补丁不在包内。本归档不再新增或扩展补丁，也不回退已并入的补丁；build331 本身不改运行时。
- 运行范围：本版不改 fixed-inputs，不改 managed-runtime-inputs 与 runtime-descriptor（runtimeId 仍 `35e3f499`），不新增受管运行时或插件；工具层改动不涉及 proroot 本体，也不改变 GitHub Linux CI 的 git 语义。
- 验证范围：宿主 contracts 整组、全树格式检查（JDK17）、host-tests manifest（251 项，含 git guard 回归）与定向单测（更新页）；本机未做真机验收。版本码提升为 331（基于 build330），DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
