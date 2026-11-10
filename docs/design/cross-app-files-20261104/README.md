# 跨应用文件访问（SAF 读写）

> 2026-11-04。设备能力授权页新增「跨应用文件访问」，助手通过 `/app/saf/*` 读写用户授权的目录。

## 为什么需要

Android 的 UID 隔离让 `/data/data/<别的包名>` 对容器不可见，`adb-shell` 也不一定能读。
SAF 是唯一不看 UID 的正规通道，但**只有用户能在系统文档选择器里授权**，助手自己找不到这棵树；
没有桥接端点时，用户授了权，助手也读不到。

## 机制

```
用户 ──ACTION_OPEN_DOCUMENT_TREE──► 系统 DocumentsUI（持 MANAGE_DOCUMENTS）
                                        │ ② 代 DSHA 去 query 目标 Provider
                                        ▼
                                  目标 App 的 DocumentsProvider（自己的 uid，读自己沙箱）
                                        │ ③ 用户点「使用此文件夹」→ 系统发 tree 授权券
                                        ▼
                              DSHA takePersistableUriPermission（READ｜WRITE）
                                        │ ④ 凭券 openDocument / createDocument
                                        ▼
                              UI 浏览 + /app/saf/* 给助手读写
```

- 谁会出现：自带 `DocumentsProvider` 的应用、被 MT 一类工具注入过 Provider 的应用。
  **普通应用的 `/data/data` 不会出现**——这是系统隔离，不是功能缺失。
- 目标应用进程不在或不应答时返回 `PROVIDER_ERROR`/`TIMEOUT`；Provider 在 binder 内完全忽略取消时，后台线程仍会被占用到它返回为止。

## 选择器：优先系统 DocumentsUI

`ACTION_OPEN_DOCUMENT_TREE` 是隐式意图，厂商文件管理器可以把默认处理程序改成自家界面；那种界面
往往看不到 SD 卡 / U 盘等存储挂载点，甚至不按 SAF 合同返回可持久化的 tree 授权（一加 C16 / ColorOS
报障，T126）。授权页与备份目录选择因此改为**显式指向系统 DocumentsUI**
（`com.android.documentsui` / `com.google.android.documentsui`，代码见 `ui/SystemDocumentPicker`）：

- 图标清单 `<queries>` 同时声明两个系统包与 `OPEN_DOCUMENT_TREE` 意图，否则 Android 11+ 的包可见性
  会让查询看不到系统选择器；
- 发起选择时先查可见且已导出的系统入口并设置 component；没有系统选择器时退回原默认解析，不阻断流程；
- 默认处理程序被厂商改写时，授权页弹窗说明「可能看不到挂载点」，给出三个入口：系统文件选择器 /
  跳到接管者的应用信息页（清「默认打开」）/ 明确继续用默认界面。用户选择不被持久化，不改变系统默认；
- 这只是选择界面的路由，授权仍由系统逐目录发放，`/app/saf/*` 的授权簿、路径护栏与写入开关完全不变。

存储挂载点能不能选到仍取决于系统 DocumentsUI 与 ROM 是否暴露对应 root；本模块不申请
`MANAGE_EXTERNAL_STORAGE`，也不扩大任何权限声明。

## 授权与持久化

| 项 | 做法 |
|---|---|
| 真相来源 | `ContentResolver.getPersistedUriPermissions()` |
| 本地记录 | `SharedPreferences("saf_grants")`：授权 URI、每棵树的「允许写入」、以及**本功能新拿到**的读/写位 |
| 权限 | `takePersistableUriPermission(READ \| WRITE → READ → WRITE)`；写入能力由本地开关二次约束 |
| 上限 | 8 棵 tree；`..`、非 tree URI、DSHA 自己的 documents 根一律拒绝 |
| 撤销 | 只 `releasePersistableUriPermission` 本功能新拿到的那部分，再清本地记录 |

持久 URI 授权按「App + URI」记在系统里、**不分功能**：备份、日志导出等其它功能也会对用户选的目录
`takePersistableUriPermission`。因此在 `take` 之前先查系统里已有的读/写位，只把本次新增的位记进
`acquired:<uri>`；满额回滚与页面「移除」都只释放这部分，其它功能已有的授权保持有效。

`take` 之前还会先写一条待确认记录（原有位 + 本次可能拿到的位）。进程在 `take` 与本地登记之间被杀时，
下次打开授权页或调用任一 `/app/saf/*` 会按记录释放「当前有、原有没有」的位，避免系统里留下授权簿
不知道的持久授权占额度。

## 端点（3090 桥，协议版本保持 3，纯加法）

| 端点 | 上限 | 说明 |
|---|---|---|
| `trees` | — | 授权列表：id/app/label/readable/writable/valid |
| `ls` / `stat` | 500 项 | 目录 / 单条 |
| `read` | 256 KiB/次 | 文本直返；`base64=1` 读二进制；`offset=` 分段且不得越界 |
| `write` | 16 KiB | 小文本（受查询串预算约束，更大的走 `push`） |
| `push` / `pull` | 32 MiB | 容器文件 ↔ 目标，大文件的正确路径 |
| `mkdir` / `rm` | — | `rm` 两段式 + 一次性 token：不带 confirm 只返回 `NEEDS_CONFIRM`（目标+子项数+token，60 秒），再带 `confirm=<token>` 才删除；`confirm=1` 不再放行 |

`read` 的 `offset` 先用 `stat` 得到的大小校验（大小未知时按 32 MiB 兜底）：不可 seek 的 Provider
不会为了一个越界偏移白读整份文件。只给写权限的树在添加时会被拒绝（`WRITE_ONLY`）；读端点要求读位。

安全约束：

- `tree` 只认授权簿里的短 id（绝不接受调用方直接给 authority/URI）；写/删要求该树 `writable`。
- 容器路径复用 `BridgePathPolicy` 的域边界；凭据区不可读写。
- `SafLocalFiles` 用 `O_NOFOLLOW | O_NONBLOCK` 打开并核对 `lstat/fstat` 的 dev/ino；`st_nlink > 1`
  的硬链接与 proot `--link2symlink` 的 `.l2s` 实体一律拒绝——否则容器里 `ln` 一个凭据文件就能绕过
  按路径/文件名的判据；`O_NONBLOCK` 保证 lstat 与 open 之间被换成 FIFO 时不会挂住桥线程。
- 新建文档不再依赖父目录列表：`createDocument` 返回后校验 authority、授权树、非空 documentId、
  Provider 的 parent 列（不可用时用 `isChildDocument`）以及返回显示名与请求名一致（`createdChildOf`）；
  大目录（>500 项）或列表延迟时仍可新建。两种归属判据都不可用时失败关闭；归属校验失败不删除
  返回 URI（可能是既有文档，避免数据丢失），树外 URI 仍拒绝。
- 一次公开操作共用一个 15 s 截止时间（`SafTree.Deadline`，独立 token，结束时只移除自己的
  callback）；`resolve`/`entry` 复用外层 deadline，不再嵌套计时。`query`/`openFileDescriptor`
  带 `CancellationSignal`；`createDocument`/`deleteDocument` 在框架层没有 signal，无法中断，
  返回后立刻复核截止并映射 `TIMEOUT`。
- 容器路径除了 `O_NOFOLLOW` 与 dev/ino 复核，还在打开后用 `readlink /proc/self/fd/<fd>`
  核对内核记录的真实路径；祖先目录在判据与 open 之间被换成符号链接时返回 `PATH_CHANGED`，
  `/proc` 不可读时失败关闭（`PATH_UNVERIFIED`）。
- 列表锁只保护 prefs/系统券快照；目录名在 `add` 时缓存，`/app/saf/trees` 走不查询 Provider
  的轻量列表，界面补查带 `CancellationSignal` + 截止时间。释放失败时保留 `acquired`/待确认
  记录，下次访问重试。

## 界面

- 入口：设置 → 设备能力授权 → 系统权限「跨应用文件访问」（行副标题报已授权数量 / 失效数）。
- 管理页 `SafAccessFragment`：说明、选择文件夹、已授权列表（允许写入开关 / 浏览 / 移除）。
- 浏览页 `SafBrowseFragment`：只读列目录 + 文本预览（≤64 KiB），二进制提示用 `/app/saf/pull`。

## 已知限制

- 只读浏览页不提供写/删入口（写由助手按授权执行，减少误操作）。
- `rm` 是两段式 + 一次性 token：第一次只探测并返回 `NEEDS_CONFIRM`（目标路径、目录子项数、60 秒有效的 token），第二次必须带 `confirm=<token>`；token 绑定 tree/path/探测时的目标身份，消费即删。`confirm=1` 一律返回 `CONFIRM_REQUIRED`，未知 token 同样拒绝。目录内容递归删除前先逐层只读预检；任何一层超过 500 子项直接 `SCAN_LIMIT`，不先删任何项（分页递归删除留待后续）。不再用「先列目录判空/非空」分支，避免检查与删除之间的 TOCTOU。Provider 仍可能在确认后按自身语义处理并发新增子项。
- 32 MiB 以上的单文件不支持（`pull`/`push` 各有上限）；需要的话先分段。
- 目标应用卸载或授权被系统撤销后，条目显示「已失效」，需用户移除后重选。
- 系统 DocumentsUI 被 ROM 移除或禁用时只能退回默认选择器；那种界面若看不到挂载点，属于本机缺少系统
  文档选择器，DSHA 不会用 `MANAGE_EXTERNAL_STORAGE` 或直接路径遍历替代用户授权。
- 只给写权限（Provider 未给读位）的树在添加时会被拒绝；旧版本留下的只写记录读端点/浏览页不可用，移除后重新授权读写即可。
- `push` 覆盖已有文件时先 `"wt"` 截断目标；Provider 超时/取消/长度不符可能留下半截目标（无法回滚，属于已知限制；重要文件请先备份）。
- `write` 的写后校验依赖 `COLUMN_SIZE`：Provider 返回 -1 时只能依赖写调用本身成功，无法再比对
  长度；极端情况下「同长度的错误内容」不在当前校验能力内（保留该限制，等待 Provider 提供可核
  对的大小或摘要）。
- 早期版本（第二轮 head）登记的授权没有 `acquired:` 归属记录：升级后「移除」只清本地记录，
  系统里的持久授权无法判断是本功能还是其它功能所有，因此保留不释放；需要时可在系统设置里
  手动撤销。

## 测试

- `SafPathsTest`：纯逻辑（路径规范化、MIME、文本判定）。
- `SafGrantsTest`（Robolectric）：授权归属/配额/只写拒绝（`WRITE_ONLY`，旧只写记录的处理见「已知限制」）/
  `listLight` 零 Provider 查询/label 截止与忽略取消的 Provider 行为。
- `SafLocalFilesTest`（Robolectric）：受控 `Os` + `ParcelFileDescriptor` 生命周期影子验证硬链接/FIFO/`.l2s`、
  真实路径复核、祖先换链拒绝、anchored 目录 fd 在 close 后失效、创建/发布/预置硬链接不被截断、NOT_FOUND/新建文件分支。
- `SafTreeTest`（Robolectric）：删根/文件与目录两段式 token（`NEEDS_CONFIRM`/`CONFIRM_REQUIRED`/`CONFIRM_EXPIRED`/`CONFIRM_MISMATCH`、一次性消费、目录内容递归删除）、offset、树外 URI 清理、`SCAN_LIMIT`、
  取消/错误码映射、慢 write/mkdir TIMEOUT、write/read 成功路径。
- `SafBridgeTest`（Robolectric）：`trees` 零查询、`rm` token/空路径/confirm=1 拒绝、未知/失效/只读/写开关关闭、
  push/pull 参数校验与成功/失败路径（临时 rootfs）。
- `SystemDocumentPickerTest`（Robolectric）：系统 DocumentsUI 与厂商处理程序共存时强制系统入口、OEM 接管可识别、
  系统选择器缺失/未导出时退回默认解析、系统意图选择器不算接管、tree URI 结果与起始目录只经合同转发。
- `app/src/test/java/com/deepseekharness/app/util/BridgeRoutesTest.java`：SAF 路由匹配。
- `tools/test-saf-local-files.py`：真实 Linux syscall 祖先换链负控 5 例（朴素 O_NOFOLLOW 仍读到凭据、
  readlink 不匹配即拒绝、锚定遍历拒绝换链、rename 后 fd 不变、父 fd 发布不跟随路径）。

依赖按 `tools/robolectric-sdk.lock.json` 固定，CI 在 `.github/workflows/_node-unit-tests.yml` 里用
`python3 tools/prepare-robolectric-sdks.py --dir ...` 逐个核对 sha256 后以
`DSHA_ROBOLECTRIC_DEPENDENCY_DIR` 跑单测。

## 让助手自己找得到（提示词，不做成工具）

沿用 `dsh-fs-search-scope` 的「一小段系统提示」写法，不新起 MCP 工具服务：

- 提示并入已签名的内置插件 `builtin-plugins/dsh-device-shell-guide` 的 `promptText`
  （不新增插件、不改 `plugin-manager-policy-patch.json` / `auto-update` 签名集合与插件数量），
  只多一行：`■ 跨应用文件（SAF）：用户授权过的目录用 /app/saf/* 读写（trees 拿 id；写完回读；
  writable=false 时 READ_ONLY）。`
- `/app/help` 里也有「跨应用文件访问（SAF）」小节，由 `UiText.choose` 的追加块提供：
  中英各一份，不占用 `x1970977` 主文案目录条目，因此不需要为新增小节重翻十种语言；
- 端点清单、错误码与硬约束留在桥侧与帮助里，提示只负责「告诉模型这条路存在、写前写后注意什么」。
