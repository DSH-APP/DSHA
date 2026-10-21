# 3090 桥、文件交换与 DocumentsProvider

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；本文核对当前 build190 工作树的生产路由、文件策略及相关测试源码。本文不是本轮设备或接口执行回执。桥协议以实际 `/app/version` 和 `/app/help` 为准。

## 调用前先辨认入口

3090 是应用内部设备桥，只监听 IPv4 `127.0.0.1` 和 IPv6 `::1`，不是对外 Web 服务。DSH Web 端口动态选择，LAN 访问另由 3081 代理提供，见[端口说明](ports-and-networking.md)和[LAN 操作](lan.md)。给 Web 发请求、给 Android 桥发命令、打开文件 Provider 是三条不同链路。

[HttpShellService](../app/src/main/java/com/deepseekharness/app/HttpShellService.java)当前报告 `BRIDGE_PROTOCOL=3`。调用方先检查版本/能力，使用协议兼容判断，不用应用显示版本推断端点存在。桥 token 由宿主写入 `/root/.dsh/.bridge_headers`；推荐保持凭据在文件中：

```sh
curl -s -H @/root/.dsh/.bridge_headers http://127.0.0.1:3090/app/version
curl -s -G -H @/root/.dsh/.bridge_headers \
  http://127.0.0.1:3090/app/readfile --data-urlencode 'path=/root/report.md'
```

鉴权只接受**一个** `X-Token` 头；空 token、重复头、URL 中的 `token` 参数均拒绝，Cookie 不作为桥凭据。规则由[BridgeCredentialAuth](../app/src/main/java/com/deepseekharness/app/util/BridgeCredentialAuth.java)实现。桥不提供 CORS 放行；这不能变成“任意同 UID 插件受隔离”的保证。插件与终端可以使用该 UID 可访问的内容，见[安全模型](security-model.md)。不要把 token、头文件内容或带凭据命令复制到报告。

## 端点地图与请求边界

[BridgeRoutes](../app/src/main/java/com/deepseekharness/app/util/BridgeRoutes.java)定义33条精确路径和两个前缀。以下按用途分组，参数和返回值仍需查看当前运行时 `/app/help`；此表不是允许任意路径或命令的通配规则。

| 用途 | 路径 | 主要限制 |
|---|---|---|
| 健康和版本 | `/health`、`/app/version`、`/app/help` | 仍经桥鉴权；协议能力与实际状态分开 |
| 设备与应用 | `/app/device`、`/app/apps`、`/app/launch` | 实际用户、目标和 Android 能力仍校验 |
| 物理屏、虚拟屏 | `/app/ui/*`、`/app/vscreen/*` | [最新观察/节点/帧与代次](virtual-screen-architecture.md)，禁止未知结果重放 |
| 剪贴板、文件与用户交互 | `/app/clip`、`readfile`、`export`、`share`、`open`、`vibrate`、`ask`、`notify`、`toast` | 同名项均带 `/app/`；前台限制、路径和大小限制独立适用 |
| 位置、传感器、手电 | `/app/location`、`sensors`、`sensor`、`torch` | 默认关闭；DSHA 开关和系统权限分别检查 |
| 插件与悬浮条 | `/app/plugins`、`/app/overlay` | 加载状态上报不是安装证明；悬浮窗另需系统能力 |
| 任务 | `/app/tasks/context`、`event`、`claim`、`ack` | task 身份、代次与确认状态，不是通用 shell 接口 |
| 设备执行与许可 | `/device/plan`、`execute`、`vscreen/start`、`vscreen/commit`、`consent`；`/app/native-policy`；`/confirm`、`/exec` | 白名单、许可和单次派发身份；未知结果不切通道重放 |

未明确支持的 HTTP 方法返回405；桥默认 GET，只有当前代码明确列出的虚拟屏 POST 路由接收对应正文。不能把“路由存在”理解为任意 POST 或带正文 GET 可执行。头部解析由[HttpProtocol](../app/src/main/java/com/deepseekharness/app/util/HttpProtocol.java)限制：首行96 KiB、单头16 KiB、累计128 KiB、100头、单调时钟总5秒；正文和鉴权后的执行预算另算。设备命令原文上限8192字符，不用更小的头预算截断合法百分号编码命令。

常见失败需按层处理：401先检查当前桥身份；405先检查方法；`DISABLED`/`NO_PERMISSION`由用户设置或 Android 授权处理；`[POLICY_BLOCKED]`/126是拒绝，确认按钮不能放行；`[EXECUTION_UNKNOWN]`/125表示可能已执行，先观察实际状态。[设备通道](device-channels.md)解释124/125/126及不重放规则。

## 读取、导出和分享文件

[BridgePathPolicy](../app/src/main/java/com/deepseekharness/app/util/BridgePathPolicy.java)同时核对原始路径、规范化路径及解析后的实际目标。`/root/.dsh`、`.ssh`、`.android`、云凭据和 `.dsha-` 运行状态等不能经桥读取/导出；宿主 `/data/data`、`/data/user` 私有区也受护栏约束。rootfs 位于应用私有目录这一事实不会把整个 rootfs 错封，也不会放过其内凭据。软链接不能绕过复核。

| 操作 | 输入与上限 | 如何理解结果 |
|---|---|---|
| `/app/readfile` | 绝对路径；文本最多256 KiB；目录输出有累计限制 | `FORBIDDEN`不是文件不存在；`SOURCE_CHANGED`表示采集期间身份变化，不能称稳定快照 |
| `/app/export` | 工作产物最多64 MiB；显示名拒绝 `/` 和 `..` | 保存到 `Download/DSHA`；`TOO_LARGE`/`BAD_NAME`需修改目标或名称，不扩大权限 |
| `/app/share` | 文本或允许的外部存储文件路径；文件最多2 GiB | 打开系统分享流程不证明接收方已发送或保存 |

这些限制不等于 v5 数据备份；导出对话和配置应使用[备份恢复](backup-and-restore.md)。不要把桥凭据护栏当成整个 guest 的文件沙箱，也不要借普通终端绕过用户拒绝的设备操作。

## 网页已有 File/Blob 与系统文档入口

网页 `window.DSHA.shareFile/saveFile` 接收已有 `File`/`Blob` 的字节，不接收本机路径。只在支持 WebMessageListener 的系统 WebView、当前运行实例、实际本机端口和顶层同源页面注入。分享上限32 MiB，保存2 GiB；分享返回 `chooser-opened`只证明选择器已打开，保存必须写入并读回摘要。取消为 `AbortError`。文件名和下载属性、临时读取授权及24小时分享缓存详见[Web 文件接口](web-file-sharing.md)。Gecko 或普通浏览器应先做接口检测，不能假设存在该原生桥。

[DshaDocumentsProvider](../app/src/main/java/com/deepseekharness/app/DshaDocumentsProvider.java)是系统文件选择器/文件管理器里的另一个入口，根为 `dsha-root`、根文档 ID 为 `root`。调用方应把文档 ID 当作稳定的不透明标识，不能拼接或解析它来推断物理路径；[DocumentPaths](../app/src/main/java/com/deepseekharness/app/util/DocumentPaths.java)仍保留旧路径 ID 与新逻辑位置兼容。

核心 anchor（包括 `linux/ubuntu/root/.dsh`、`user-data-v5/dsh`）不可改名/删除；这不意味着任意子内容只读。guest 绝对链接按所选 rootfs 解析，最多40层，越界拒绝。删除树时不跟随链接去删除目标；写入先打开 FD、复核 `/proc/self/fd` 身份后才截断。Provider 通过 Android 的 `MANAGE_DOCUMENTS` 合同授权访问，不是导出给任意网页的通用路径 API。用户通过文件管理器主动编辑仍可能影响工作数据，维护期间应遵守应用提示。

SAF 流共用[DocumentStreams](../app/src/main/java/com/deepseekharness/app/backup/DocumentStreams.java)，保留 AFD offset/length 与取消行为；不能在主线程等远端 Provider。外置工作区维护只断开私有引用，不遍历删除、覆盖或改名外部内容。

## 修改和验证入口

桥鉴权/路由/路径分别有 `BridgeCredentialAuthTest`、`BridgeRoutesTest`、`BridgePathPolicyTest`、`BridgeFileSnapshotTest`；文件 ID 有 `DocumentPathsTest`，网页传输有 `WebTransferPolicyTest`。位于[纯逻辑测试目录](../app/src/test/java/com/deepseekharness/app/util/)。这些是关联入口，本文没有重新执行 Android、Provider、分享接收方或所有端点矩阵。麦克风来源和系统授权另见[浏览器内核](browser-engines.md)，截图范围见[权限架构](device-permissions-architecture.md)。
