# 插件安装、更新与分享

> 核验基线：2026-10-21（原于 build190/F13 源码核对）。当前发布身份为 build356 / DSH `0.2.2-rc.1` / 单一 Standard；F10–F13、build190 记录属于历史，不代表本版。本文描述 DSH 0.2.2-rc.1 的 DSHA 入口；最终应用身份、软件与 APK 结果见 [README](../README.md) 与本轮 CI / Release 回执，本文不预填测试总数或设备验收结论。

在插件市场粘贴链接、从系统文件选择器导入本地包，或使用网站提供的 DSHA 唤起链接。应用解析实际包，核对格式、路径、包名/版本、实际依赖与摘要，再由插件事务提交并启用。现行正式插件无需原生审阅确认。网站浏览本身不会安装插件；只有用户主动打开安装入口才会继续安装。启停和更新后按应用提示重启 Web。

## 支持的来源与包契约

- GitHub 仓库简写 `owner/repo`、仓库 URL、SSH 地址、tree/blob 子目录链接，以及 archive/codeload/Release 附件。
- Release 页面仅有一个支持的压缩包时可直接解析；有多个候选时选择实际附件链接。
- HTTPS ZIP、TAR、TAR.GZ、TGZ，以及 npm 插件包名；包含多个链接的分享文字须先选择目标。
- 本地导入按实际内容识别格式；文件扩展名和 Provider MIME 不能代替内容核验。系统 DocumentsUI 不可用时可选择其他文件选择器。

`package.json` 必须有合法 npm 名称与版本，声明有效 `dsh.bundle.patch`，每个补丁文件必须存在。`main`或`cordis.entry`在包中声明时才检查对应入口文件存在，并非所有包都必须另外声明它们。普通 npm 库不会因名称包含 dsh 自动成为插件。归档不得通过越界路径、包外链接、同名冲突或已声明但缺失的入口绕过检查。展开及下载限额以当前应用实际检查为准，超限会报告原因。

优先使用作者构建好的 Release 或 npm pack 发布包。Android 容器为 Linux arm64/glibc；依赖包含原生模块时，需要适配该运行环境。声明兼容版本、作者测试和本机实测分别记录，不能把作者说明当作依赖内容或设备可用性证明。

## 依赖、脚本与实际权限

正式依赖安装允许未锁定重解析，使用随包 pnpm 10.34.5 的生产依赖安装；依赖的 `postinstall`、build 等生命周期脚本以及 pnpmfile 可以运行。安装会保存实际锁与目录摘要，后续复用/恢复检查真实历史内容。旧锁不会强制种入新的在线候选；离线恢复不在原件中重新解析依赖。

拉取 npm 包时的 `npm pack --ignore-scripts` 只是只读下载阶段的限制。通常使用只含依赖声明的临时 manifest 准备依赖，这时不执行插件源码自身的 scripts；复用作者原生锁的路径会使用原 manifest。因此不能笼统承诺“不执行安装脚本”，也不能把包下载阶段的参数当作最终安装权限。

插件、脚本和普通终端均在应用 UID 下运行，可读取和修改该 UID 可访问的内容；proot/proroot 不是第三方恶意代码的独立沙箱。包与事务检查提供内容和回切保证，不是对作者代码的安全认证。实际设备操作仍经当前原生能力授权和执行侧策略。请依据真实来源选择第三方代码。详细边界见[安全模型](security-model.md)。

## 提交、失败和用户意图

安装先准备候选，记录原件、固定目标、前后摘要与实际依赖，再切换安装目录。未提交日志受维护门禁保护；恢复必须按本次记录核对进程退出和目录身份。已提交的旧日志不能回滚后来修改的用户数据。失败候选、未知原件和历史依赖不会由临时目录清理隐式删除。

生命周期脚本已经运行后发生网络错误，不自动改源重放安装。普通成功或明确失败的临时准备目录在确认目录身份后可由`candidate_workspace`收尾清理；不是每个失败候选都永久保留。包管理进程/输出管道退出未知时，保留候选并写`.dsha-package-command-unknown.json`，不能清理或自动重试。已进入事务的原件、pending日志与历史依赖按事务契约处理，不能借临时清理隐式删除。完整断电、Android 文件系统和设备行为仍按独立验收记录声明。

同名更新保留用户主动禁用和安全模式意图；升级只移除旧版自动审阅标记。系统插件从当前签名 APK 的受管证明重建，与用户源码和依赖分层。启停、来源信息和实际加载结果以插件管理及启动诊断为准。

原生启用/禁用走`enable-state`/`disable-state`，严格核对包名、Boolean状态和`intentVerified:true`的实际读回，先呈现已核验的启停意图，再独立合并刷新列表。成功不等于组件ready或活loader已加载/卸载，仍须正常重启Web。只读list/refresh不占profile writer锁且可取消；遇配置并发改写或已有writer锁明确报错，保留的旧列表不能称本次新核验。查询与短意图命令的30秒协作预算含排队、准备边界与执行；单次系统I/O、monitor/Binder等待不可抢占，未知退出继续保留原屏障，不自动重试写操作。

## 导出、删除与终端

插件导出包含所选用户插件的真实源码、已有依赖和绑定关系。系统插件字节由当前 APK 提供，用户备份只保留其启停意图。内容未完整、未知或有依赖警告时如实报告，不能改成“完整通过”。

删除可管理位置的第三方插件会移除该插件安装与登记，保留聊天、配置、其他插件、共享依赖与外部导入源。需留存时先导出。内置可选组件使用开关；官方核心也可被明确禁用，但可能使Web无法启动，应从原生插件页重新启用或进入恢复工作台，不借删除/第三方替换修复核心。

内置系统插件 `dsh-launch-target`（启动目标）在 Web「设置」导航注册独立的「启动目标」面板（`settings.section`），插件详情页保留同一张卡片，提供“桌面图标启动后进入：主界面 / Web 界面”。面板管理参数，插件只负责把选择传给原生 App；禁用插件只移除面板，不改变已选的启动目标，关闭直进 Web 请在面板或原生开关里选“主界面”。值只保存在原生 App（3090 桥 `/app/entry-target`，仅 `main` / `web`），默认主界面，不进入任何备份；原生“设置 → 运行配置”有同一开关。选择 Web 时，桌面图标先显示开机加载页，入口门禁放行且鉴权就绪后进入 Web，在 Web 返回到底回到桌面；维护、启动恢复、原实例待确认或待处理的插件链接时仍进入主界面。插件页路由经 DSH `connection` 鉴权与同源检查，浏览器不接触桥 token。

内置临时修复插件 `dsh-fs-read-hint`（读取提示）：DSH 以 `file has not been read` 拒绝修改、而本会话读过同名的另一路径（如主检出与 worktree 副本）时，在报错后附上该路径。只改报错文字，不放行修改；可在插件页单独停用或删除，停用后报错与上游一致。上游跟踪与撤除条件见[专页](fs-read-hint-plugin.md)。

内置临时修复插件 `dsh-fs-search-scope`（检索范围）：glob 默认跳过嵌套 git 工作树（`.agents/worktrees` 与检索根内的链接工作树），`path` 指向工作树内时照常检索；仍含忽略与隐藏文件，执行前照常过 DSHA 必经策略。可单独停用或删除，停用后与上游一致。撤除条件见[专页](fs-search-scope-plugin.md)。

F11按APK提供的受管包proof及实际完整内容区分DSHA签名组件、DSH受管包和用户插件；npm scope、`dsh-`前缀、目录位于DSH安装树或可写`installation.dependencies`都不能提升来源。位于安装树但未获受管证明的包显示用户/未验证；原生管理允许查看、开关与导出，不在该位置自动更新或删除。标准profile中的用户插件仍按正常安装/启停/删除链管理。具体分层和备份复核见[插件运行与权限](plugin-runtime-and-permissions.md)。

发现先筛选小manifest与真实patch bundle，每命令/请求认证一次proof表并按canonical目录去重，不复用跨请求stat缓存。Node真实Host完整FILE/mode/LINK、目录成员与竞态证明使用异步API，最多2个内容扫描并发、64 KiB读取块；删除在提交前对实际目标再次完整核验。已有桌面夹具双list约0.65秒、独立Worker HTTP ping约14毫秒且扫描pending，仅代表该桌面输入，不是手机或最终性能保证，本页未重跑该测试。

普通终端可以使用 `npm` / `npx`，与插件安装共用随包 CA 和 RuntimeTools 环境：

```sh
npm install 包名
npm install -g 工具包名
dsha-plugin install @作者/插件@1.0.0
```

普通 `npm install` 不自动修改 DSH 启用列表；`dsha-plugin` 通过当前插件入口处理实际候选。不要用裸 shell 修改事务现场来绕过维护屏障。

## 内置竖屏适配插件 `dsh-web-mobile`：build336 起恢复默认启用（待真机覆盖升级验收）

> 决策：2026-11-06 用户确认上游已修竖屏问题，按原「恢复默认启用」判据恢复内置移动插件。
> build336 起上游同步到 3.3.1（适配宿主 0.2.2）并恢复默认启用；build327 的默认禁用、事故原因与
> 覆盖更新迁移保留在 [build327 记录](releases/v0.2.1-rc.5-build327-web-mobile-default-disabled-20261105.md)，
> 本次替换、补丁取舍与验证见 [build336 记录](releases/v0.2.1-rc.5-build336-web-mobile-3.3.1-20261106.md)。
> 2026-11-09 已再同步到上游 3.3.5（main `646fe837`，标签 v3.3.5 = `14ecfccf`），补丁策略不变；
> 本次差异、退役成员与验收边界见 [build351 记录](releases/v0.2.1-rc.5-build351-web-mobile-3.3.5-20261109.md)。

### 为什么可以恢复

- 上游 3.3.1（2026-11-06，提交 `4e71eb67de7dac9d8b2572757c81e47aa32a32fb`）明确「适配宿主 0.2.2：
  修掉整页白屏、顶栏开关被裁、文件键消失、页签叠字、文件面板标签条被顶栏盖住」，最低宿主正是当前
  锁定的 0.2.2-rc.1。
- 上游 peer 声明与锁定运行时逐条匹配（`@deepseek-ai/dsh-client-* >=0.2.2-rc.1 <0.3.0-0`、
  `@deepseek-ai/cordis ^4.0.1`，运行时分别为 0.2.2-rc.1 与 4.1.0）；`react` 已标为可选 peer——
  它不在宿主运行时安装树里，必选 peer 会让宿主的依赖门槛判 missing。
- build325 齿轮契约（`DSHA_GEAR_FIT_V1`）与 build324 白屏路径回归仍全绿；默认值恢复后
  `tools/generate-builtin-plugins.py`、`tools/prepare-runtime-descriptor.py --write` 与发布前门禁已重跑。
- build324 白屏的根因（插件用宿主节点「存在性」判据）仍由 DSHA_GEAR_FIT_V1 兜住；上游 3.3.1 的
  0.2.2 适配没有恢复任何已撤回的视口试验，本地补丁也未重放上游已接管的宽屏触屏规则。

### 恢复范围

- [`builtin-plugins.json`](../app/src/main/assets/builtin-plugins.json) 不再声明 `dsh-web-mobile` 的
  `defaultDisabled`：全新安装与覆盖更新都默认启用；插件字节仍由当前 APK 的受管证明重建。
- 用户显式禁用始终优先：`node_modules/<name>.disabled` 存在时注册器只清理旧启用记录，不会改回启用；
  原生「插件」页仍是唯一启停入口。
- 默认禁用机制本身保留（`register-builtin-plugins.py` 的 `apply_default_disabled`、
  `apk-generation` 与 `<name>.enabled` 代次记录），当前注册表没有任何 `defaultDisabled: true` 条目。

### 上游 3.3.5 同步（2026-11-09，build351 候选）

- 从 3.3.1（`4e71eb67`）同步到上游 main `646fe837440f9939228bdba0024d407eaac01128`
  （2026-11-08；v3.3.5 标签 `14ecfccf5a6acb69e3a84229dee328d31801306f`，标签后 3 个修复提交一并取用），
  共 37 个提交。用户可见变化：插件页图标与中英文标题 / 描述、手机档「更多视图」入口、子代理 chip
  让位、抽屉暗层改 frame 伪元素、全屏手势按方向定族（左右 45% 起点区退役）、hero 工作树行排版、
  插件页交还宿主排版、测试门禁空转加固。
- 补丁最小集不变：服务端鉴权 / 安全区 / `DSHA_GEAR_FIT_V1` 齿轮几何让位 / 文件传输 / 多语言；
  **不重放** build336 已删除的会话输入 V4、手机搜索、快捷键可见带、字体族行、遮罩按压染色等旧补丁。
  上游把 `overlay-backdrop-fab` 改名为 `drawer-layer`（3 个成员），旧名进入锁的 `retiredFiles`。
- 新增 `assets/icon.png` 与 `locale/{en,zh}.json` 并加入受管安装表：宿主插件卡片按 `package.json#icon`
  与 `locale/<lang>.json` 的 `meta.title` / `meta.description` 显示图标和标题描述；`lib/file-transfer.js`
  仍由 `tools/file-transfer/file-transfer.js` 作为仅本地追加成员生成。
- `runtime-descriptor` 在含 T130（`4e8c80e0e`）的合并树上重算为 **`6f31f111`**（205 个输入）。

### 仍需完成的真机验收（发布前）

原硬门槛第 3 条要求真机覆盖安装后不白屏、侧栏不重复、footer 设置入口不重复，并验证「升级后默认
启用」本身。本仓库提交只记录软件侧结果与判据引用，**不预写真机通过**；build336 发布前须由用户 /
主会话按 build336 记录逐项验收并补写回执。验收失败时回退 `builtin-plugins.json` 中的一行改动即可
恢复默认禁用（插件字节可保留），不需要回退上游同步。

## 工作区目录选择器与服务注册

网页工作区目录选择器报告 `active Service directoryPickerController unavailable`，表示该次请求所需的Remote服务没有处于活动状态，不等于目录不存在、文件已丢失或需要重装环境。它与Android系统文件选择器、原生备份的SAF目录授权是不同入口；更换备份保存位置不能代替修复这个Web服务。

F13在锁定真实Host中复现了一条耦合路径：原模块的默认入口是`WorkspaceController`，其静态依赖包含`remoteAccess/typert/workspaceRegistry`，而`DirectoryPickerController`原来在该父控制器的构造尾部注册。即使真实目录浏览后端与UI已活动，父控制器等待`workspaceRegistry`时，`directoryPicker/list`仍可返回`gateway/service-unavailable`及上述消息。健康空profile的raw/managed环境原本都能浏览目录；这不是每次启动都漏注册的结论，手机上registry为何未就绪也仍未知，不能将该耦合当作截图唯一原因。

[独立服务补丁](../app/src/main/assets/directory-picker-service-patch.json)将默认入口改为组合插件，分别挂载真实`DirectoryPickerController`和`WorkspaceController`。目录控制器仍依赖真实`directoryPicker`后端；工作区控制器保留原依赖、Config、名称和生命周期。私有Symbol避免默认组合重复注册子控制器，直接调用原named类的路径仍保留原子注册行为。补丁不放宽Gateway认证、RPC参数或后端能力检查，缺失后端仍须明确失败，不能返回伪造的成功目录列表。

这项修复不迁移、删除、重置或覆盖既有工作区、会话和附件，也不清除用户全局配置来制造健康空profile。目录操作失败不自动改选其它路径或重放未确认的写入；外置工作区仍只允许维护私有引用，不遍历删除或改名内容。保留当前数据和脱敏诊断，由正常维护/重启及其退出屏障处理，不能靠清空Ubuntu或删除历史会话绕过服务故障。

目录专项已取得原函数禁用registry负控及修复正控：真实目录list/create/readback、坏segment/相对路径/不存在路径/重复目录拒绝、HTTP鉴权和缺后端拒绝、健康工作区保存与同profile正常退出后两次重开，以及Config/name/named类兼容。它不覆盖损坏保存记录，也不把HMR计入目录已通过范围。相关回归入口为[test-directory-picker-service-host.mjs](../tools/test-directory-picker-service-host.mjs)。文档核验没有操作手机、ADB、真实账号或模型；主机与软件结果不等于手机目录选择验收。

### 独立的附件Agent resolver退休问题

F13后续又在原raw/F12模块确认了相邻但独立的生命周期缺陷。`FileUploads.registerAgentResolver`原来把回调函数直接存在Service属性中；Cordis tracing读取函数属性时会返回新的Proxy，disposer用`this.agentResolver === resolve`比对失败，旧resolver未清。registry/`SessionController`停止后重新注册可能报`file-upload: Agent resolver is already registered`。它与目录组合补丁无关，健康启动检查也不能覆盖这条停启路径；同样不能直接判定为截图的唯一原因。

附件resolver生命周期补丁（rc.5 起，`file-upload-resolver-lifecycle-patch.json`）（DSH 0.2.2-rc.1 上游已改用稳定的 `agentResolution={resolver}` 数据对象，该配方已退休；退休证明见 [test-file-upload-resolver-lifecycle.mjs](../tools/test-file-upload-resolver-lifecycle.mjs)。）在锁定`@deepseek-ai/dsh-client-file-upload/lib/index.js`里使用稳定的`{ resolve }`注册对象作为slot，按该次对象身份退休；调用时再取出回调。仍拒绝同时存在的重复注册，迟到的旧disposer不能清掉新注册，找不到session时仍明确拒绝。补丁不放宽上传接收、session归属、Gateway认证或回执语义，也不删除已有附件或替换用户数据。专项软件回归已冻结：不含目录修复的原CLI可精确复现workspace停止后恢复的重复注册错误；仅加载附件修复后两轮关/开均applied，raw HTTP与typedRPC上传的真实字节落盘读回、冷进程同Session上传及列表保留通过，鉴权、foreignOrigin、415/400及未知Session拒绝仍保留。真实Cordis/Commands/LocalAttachmentStore还检查live重复拒绝、旧disposer围栏、owner退休及同Session回执。回归入口为[test-file-upload-resolver-lifecycle.mjs](../tools/test-file-upload-resolver-lifecycle.mjs)。这是独立附件补丁的隔离主机软件范围，不是目录与附件组合、最终共享运行时/正式APK、所有插件、所有附件端到端或手机已全通过；组合与最终门禁由负责人另做。

## 验证入口与限度

相关宿主回归包括 `tools/test-plugin-dependencies.py`、`tools/test-plugin-transactions.py`、`tools/test-plugin-downloads.py` 和真实锁定运行时的原生插件管理测试；完整门禁入口见[接手指南](接手指南.md)。每次交付报告记录实际运行命令和结果。本页不宣称已完成所有远程源、第三方代码、手机覆盖安装或掉电矩阵。

安装/启停/运行态、系统与用户插件层、Web与原生入口的差别见[环境、数据与插件机制](runtime-data-plugin-architecture.md)。
