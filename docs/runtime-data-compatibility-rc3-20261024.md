# DSH rc3 数据读取证据（build193）

本记录只针对当前交付 build192 所用 `0.2.1-rc.2` 到 `0.2.1-rc.3` 的数据读取。它不从 DSH 版本号推导格式，不修改个人数据，不证明旧运行时可安全回退，也不把宿主夹具结果写成手机验收。

## 字节与格式核对

旧端绑定正式 `<local-workspace>`：APK SHA-256 `f1b332bc087e999bf9bd8954a70c9c7ad869d0a04193933d446854f0e31fbf3c`；包内 `dsh-runtime.bin` SHA-256 `4d3a8da40af164725f01a6cd4d56559e13239dd706441ac1f1a53c3acd3af827`，与该包描述符一致。不能把检查时源码树内另一份归档 `85344ab5…` 代替 build192 的归档。

新 raw 来自实际 `npm ci --ignore-scripts` 锁定的 rc3 树。全部 DSH 执行文件共 1066 项，1044 项相同、22 项新增或变化。核心数据与配置的 31 个包、75 个执行模块在 rc2/rc3 raw 之间全部相同。包括 storage/json/domain、session/persistence/jsonl、V0–V4 codec/catalog、projection/cache/query/sqlite、attachments、settings/config-editor/app-boot/atomic-write、credentials、presets、workspace、jobs、本机 home 和 loader。

正式 build192 的六个受管数据模块含本地适配，不能只拿官方 raw 对比。已从上述精确 APK 取出 75 个模块，并核对旧 host managed 中对应模块全部相同；使用本轮真实 builder 的 specialized patch 与 stable registry 对 rc3 raw 逐模块投影，所得 75 个模块又与 build192 全部相同。该投影没有生成归档；最终新归档及 managed 夹具仍应独立回读核对。

持久化依赖的 `dsha-runtime-fs`、`dsha-session-compat` 两组 helper 共四文件也与正式192包内字节逐项一致。基于上述实际读写链字节一致性和真实 API 交叉验证，描述符仅新增 `dsh-0.2.1-rc.2` → `0.2.1-rc.3` 的前向读取边；没有扩写旧 `0.1.7`/`0.2.0` 边，也没有生成反向读取边。

实际格式常量保持：session V4（历史 V0–V3 由现有 codec/catalog 处理）、attachments/v1、credentials document V1、projection cache V7（兼容记录 V3–V6）、derived SQLite schema V8、workspace domain V2。JSON storage 的版本由各 domain descriptor 声明；profile 继续读取 `package.json`、`cordis.patch.yml` 和 bundle layers。格式相同的结论来自这些实际实现与字节，而非 rc2/rc3 名称。

LLM 的模型列表读取、客户端界面与统计有上游变化；`dsh-llm-deepseek` 内 file-store、upload-index、file-id 与请求序列化区域保持相同。锁中另有 23 项非 DSH 依赖变化，原清单保留；本记录没有据此声称全部服务、模型或插件业务语义相同。

## 真实 API 交叉读写

运行 `tools/test-dsh-data-cross-runtime.mjs --old OLD_RUNTIME --new NEW_RUNTIME --report REPORT_JSON`。两个目录是显式选择的历史与当前安装树；每阶段使用新的 Node 进程。报告绑定工具 SHA、两端锁与 marker SHA，以及 75 个数据模块的 SHA，并在结束后重新核对输入。

五阶段：旧端写入 → 新端只读 → 新端追加及新建 → 新进程新端重开 → 新进程旧端读取新写。只读阶段逐文件核对整个隔离数据树的 SHA 与成员不变。实际执行：

- single/per-record JSON 全局值及嵌套、中文、null、布尔、数组记录；新端追加后双端重开。
- V4 none/zstd 会话写入、追加和新建；V3 none/zstd 通过真实 codec 写入，再由真实 reader 迁入 V4、追加并保留 V3 原件字节。
- 普通文件与流式文件附件；真实 Sharp 归一化图像的保存、重开、双代读取与数据 SHA。
- 真实本机 credentials provider 引用的双代写读；只有固定夹具值，无用户凭据。
- 真实 profile reader 与 composeEntries 读取 YAML；序列化使用锁定 YAML 库。这一项不等于完整 ConfigEditor/Loader 在线修改流程验证。

正式192 managed → rc3 raw 的最终五阶段已通过，结果 `data-cross-build192-to-rc3-raw-final.json`。更早 raw 对 raw 的五阶段及含 V3 的扩展也通过。首个 Linux raw 在 Windows 缺 Koffi 本机模块而失败；第二次因夹具键名用了连字符、违反真实 credential ref 规则而失败。原失败报告保留，修正为合法固定键名后重跑，不称首次全绿。

## 适用边界

全部详细报告位于本轮材料目录 `app/build/build193-issue183-plugin-env-20261023/upstream-rc3/`：`data-build192-archive-binding.json`、`data-build192-host-managed-binding.json`、`data-all-dsh-executable-fingerprints.json`、`data-reader-projection.json`、`data-transitive-lock-changes.json` 与交叉 API 报告。该目录在交付工作区 F 盘，不应与实际源码 S 盘混同。

未在这里验证真实手机数据、全部历史会话形状、完整配置在线写入、SQLite 搜索重建、全部图像格式/尺寸、全部用户插件、模型服务或业务任务。完整数据检查状态仍保留 `unknown`；本记录不能生成已知格式或删除旧原件的授权。

`DataFormatEvidence` 继续绑定整个 `dsh-runtime.bin` 和实际持久化配置补丁，归档改变仍改变读写指纹。旧端在少量夹具中能读到新写数据，只说明这些样本可读；回退仍要求既有精确指纹及真实 RuntimeTrial，不能取消退出屏障，也不能重放未知业务结果。
