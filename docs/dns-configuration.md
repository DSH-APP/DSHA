# DNS 配置与解析边界

本文按 build190 / F10 实际源码解释“配置 → DNS 解析策略”。
Web、能力桥、LAN 与 ADB 的端口另见[端口与网络](ports-and-networking.md)。
运行引擎另见[运行引擎](runtime-engines.md)。
DNS 设置处理名称解析兼容，不是系统 VPN、代理、网络权限或 HTTP 重试开关。

## 三种实际模式

配置值由 `ConfigStore.getDnsMode()` 读取，默认 `auto`。
`ResolverConfig.mode()` 只认可 `ipv4`、`native`，其他值归为 `auto`。
当前 UI 文案是“自动（推荐）”“IPv4”“原生”。

| 配置值 | Node lookup 兼容补丁 | DSHA 自有 glibc `no-aaaa` 段 |
|---|---|---|
| `auto` | 开启窄范围 IPv4 解析重试 | 不添加，撤掉之前的自有段 |
| `ipv4` | 同样开启窄范围重试 | 添加，已有用户指令则不重复添加 |
| `native` | 不替换 Node lookup | 不添加，撤掉之前的自有段 |

“原生”指当前 Ubuntu／进程原有的解析行为，不是把所有查询转交 Android 私有 DNS。
三个模式仍经过相同的 guest `resolv.conf` 协调逻辑。
它们也不会自动修改手机全局 DNS、VPN 或 Android 系统设置。

## auto 只重试特定的 Node 名称解析

[dns-compat.cjs](../app/src/main/assets/dns-compat.cjs) 通过 Node 预加载安装。
它替换 `dns.lookup` 与 `dns.promises.lookup`，并处理 `util.promisify(dns.lookup)`。
同步内置 ESM 导出，使对应 Node 内置模块视图更新。
Symbol 标记防止同一进程重复加载时层层包装。

一次 lookup 的执行规则是：

1. 先调用原生 lookup，保留原参数与正常双栈结果。
2. 仅当错误码是 `EAI_AGAIN` 或 `EAI_FAIL` 才考虑兼容重试。
3. 调用没有指定 family，或 family 为数值 `0`，才允许一次 family `4` 重试。
4. IPv4 成功就返回该结果；仍失败则返回第一次的原错误。

显式 family `4`／`6` 以及字符串 IPv4／IPv6 不因该补丁再降级。
`ENOTFOUND`、参数错误和其他错误不会走这个兼容分支。
`all`、`hints`、`order` 等选项在追加 family `4` 时保留，不原地修改调用者对象。
正常 IPv6 解析成功时没有额外 IPv4 请求。

该补丁没有调用 `setDefaultResultOrder('ipv4first')`。
所以不能把 auto 描述成“IPv4 优先排序”或“关闭 IPv6”。
它也没有替换 `dns.resolve*()`、独立 Resolver 或应用自带的 DoH 客户端。
使用自定义 lookup、清理环境或另建解析栈的插件须按其实际调用另查。

Node 官方说明中，lookup 使用系统 `getaddrinfo`，而 resolve 系列直接使用 DNS 协议。
两条路径的配置和行为不同，详见 [Node 24 DNS 文档](https://nodejs.org/download/release/v24.19.0/docs/api/dns.html#implementation-considerations)。

## IPv4 模式额外改变的是 glibc stub resolver

`ipv4` 会在 guest `/etc/resolv.conf` 追加这段自有配置：

```text
# DSHA managed IPv4 DNS compatibility
options no-aaaa
```

这是对采用该 guest glibc resolver 的程序的 AAAA DNS 查询兼容设置。
它不是 Android 全局 IPv6 禁用，也不是对所有连接做地址家族限制。
glibc 的设计仍允许使用 IPv6 nameserver，以及来自 `/etc/hosts` 的 IPv6 数据。
显式 IPv6 地址连接也不会因为这两行变成 IPv4。
这些边界见 [glibc 的 no-aaaa 设计说明](https://sourceware.org/pipermail/libc-alpha/2022-June/139986.html)。

因此 curl、Python、git 等工具是否受影响，要看各自构建及实际解析器。
采用 glibc 系统查询的调用可能受该配置影响。
使用 c-ares、独立 DNS 库、DoH、缓存或远端代理解析的调用不能一概而论。
`dig` 一类直接发 DNS 查询的工具也不是 Node lookup 补丁的覆盖对象。
Android Java 网络、WebView／Gecko 自己的外网解析不由此 Node 补丁统一接管。

只提供 AAAA 的站点、需要 IPv6 的网络与 DNS64 等环境应谨慎选择 IPv4 模式。
不要因一个请求超时就断言 AAAA 一定有问题。
它不修复证书、路由、限流、服务商拒绝、模型名或 API Key 错误。

## guest resolv.conf 如何生成和保留

协调代码是 [ResolverConfig.java](../app/src/main/java/com/deepseekharness/app/util/ResolverConfig.java)。
实际文件操作在 [RuntimeTools.java](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java)。
Android 侧从当前活动网络 `LinkProperties.getDnsServers()` 读取 DNS 地址。
只接纳校验通过的数字 IPv4／IPv6 地址，并去重，不为此发主机名解析。
代码没有通过 shell 调用 Android `dnsresolver` 命令修改系统服务。

对已有 guest 文件采用以下次序：

1. 去掉 DSHA 自己上次添加的 IPv4 兼容段。
2. 区分用户已有 nameserver 与 DSHA 带起止标记的设备 DNS 段。
3. 用户有 nameserver 时，保留用户配置，移除自有设备 DNS 段。
4. 无用户 nameserver 且读到有效设备 DNS 时，更新自有设备 DNS 段。
5. 暂时读不到设备 DNS 时，保留上次有效的自有段。
6. 没有有效旧段也没有设备 DNS 时，不注入公共 DNS 兜底值。
7. 最后仅在 `ipv4` 模式且用户尚未配置 no-aaaa 时追加自有兼容段。

`search`、其他 `options`、注释和用户 nameserver 不会被统一替换成模板。
用户自己写的 `options no-aaaa` 在 auto／native 下仍会保留。
因此切回 auto 后，若仍抑制 AAAA，应先辨认该指令是不是用户配置。
不能把“切回 auto”解释成删除所有同名选项。

文件是软链接或其他特殊文件时不跟随写入；父目录不存在时也不创建这条配置路径。
这避免 guest 绝对链接误写宿主或外部目标。
只有协调结果确实不同才写入，重复准备应保持幂等。

## 环境变量在什么位置进入进程

`RuntimeTools.applyEnvironment` 设置 `DSHA_DNS_MODE`。
当受管脚本存在时，它向原有 `NODE_OPTIONS` 前置：

```text
--require=/usr/local/share/dsha/dns-compat.cjs
```

已有其他 NODE_OPTIONS 不会因此被整串清空，相同 require 也不会重复加入。
native 模式也可带这个 require，但脚本内部见 `native` 就不替换 lookup。
所以不能仅凭 NODE_OPTIONS 中出现文件名就认定兼容重试已启用。

主启动由 [RuntimeLauncher.java](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java) 组装环境。
它在 `ProcessBuilder` 层注入，命令入口是 `/bin/bash -c`。
这个入口不是登录 shell，不保证读取 `/etc/profile` 或用户 `.profile`。
把 DNS 环境只写进 profile，不能代替这一层注入。

PTY 经 `TerminalRuntime` 复用 launcher 环境，随后进入交互登录 bash。
简易终端也从 launcher 环境开始，再建立自己的独立 shell session。
这些会话仍可被用户脚本修改环境；不能把“最初已注入”写成永久不可修改。
Node 子进程通常继承环境，但插件显式给出新 env 或清掉 NODE_OPTIONS 时要单独验证。
原生可执行程序不会执行 Node 的 CJS 预加载，可能只受 glibc 文件配置影响。

Web 的启动观察器还会追加独立 `--import`；不要修 DNS 时删除观察器或桥接预加载。
环境和命令链的真实回归入口见下方测试定位。

## 保存、下一次启动与网络切换

用户路径是“设置 → 配置 → DNS 解析策略”，选择后按“保存配置”。
模式存在本机 `dns_mode` 偏好中。
保存不把预加载脚本注入已经运行的 Node，也不重启所有终端。
要验证新的主 Web 模式，应正常停止 Web，再启动。
需要相同新环境的终端应先正常关闭，再重新打开对应会话。

`RuntimeTools.prepare` 在准备阶段协调 resolv.conf，位于受管资产快路径返回之前。
`RuntimeLauncher.builder` 在创建新命令进程时也尝试协调。
查询系统网络服务在资产准备锁外进行，避免跨 Binder 查询长时间占锁。
读取网络信息失败不要求启动时一定联网；写文件失败记录警告／DNS_PREPARE 诊断。

这不是持续监听网络变化并实时重配所有旧进程的承诺。
切换 Wi-Fi／移动网络后，新的受管启动会再读取当时的设备 DNS。
已有进程、解析缓存、HTTP 连接池可能保留旧状态，排查时要记录其启动时间。
不能把反复刷新网页等同于重建后端进程环境。

## 备份与恢复范围

新 portable 原生偏好投影明确列有 `dns_mode`，可保留用户的策略选择。
见 [PortablePreferenceProjection.java](../app/src/main/java/com/deepseekharness/app/backup/PortablePreferenceProjection.java)。
旧 `ConfigStore.exportBackupSettings`／`exportPortableSettings` 的字段白名单没有 dns_mode。
因此不同年代、不同导出路径不能一概宣称都携带 DNS 模式。
恢复结果应以实际归档范围和对应读取器为准。

策略名称可迁移不代表旧设备的当前 DNS 地址适合新设备。
下次启动仍按当前网络与现有用户配置协调 guest 文件。
这不是把源手机的 Android 私有 DNS、VPN、网络权限或授权凭据恢复到目标手机。
不要为修 DNS 清空个人配置、会话或整个 Ubuntu。

## 用户如何选择与排查

| 现象／需要 | 建议先做的判断 |
|---|---|
| 正常双栈网络 | 保持 auto，正常 IPv6 结果不会被补丁降级 |
| Node 报 EAI_AGAIN／EAI_FAIL | 核实际模式、预加载与系统解析，再看一次 IPv4 重试结果 |
| 系统 glibc 查询在 AAAA 环节确有兼容问题 | 可明确选择 IPv4，正常停止后重启验证 |
| 要观察未经 Node 兼容替换的行为 | 选择 native，保留用户 resolver 配置后比较 |
| ENOTFOUND／HTTP 401／TLS 错误 | 分别查名称、鉴权或证书，不自动改 DNS 或重发业务 |
| 只某个插件失败 | 查其解析库、代理与环境继承，不把全部请求当 lookup |

Main 的启动记录与诊断可区分环境准备、后端退出和网页就绪。
域名尚未解析成功与 HTTP 已提交但结果未知是不同阶段。
补丁只重试 lookup，不重放 HTTP、模型请求、工具调用或文件写入。
超时、受限网络和 EXECUTION_UNKNOWN 不能成为自动换策略、换通道再执行的依据。
需要恢复正常默认时，明确选 auto、保存并正常重启，保留原失败记录供比较。

## 修改与测试定位

改模式值：同步 ConfigStore、ResolverConfig、RuntimeHostPorts.Settings 与配置页三选项。
改文件协调：覆盖自有段撤销、用户 DNS 优先、临时无网、IPv6 数字地址和软链接跳过。
改 Node 补丁：覆盖 callback／Promise／promisify、family、all/options 与重载幂等。
必须核对一次失败解析最多两次底层 lookup，业务 HTTP 仍只发送一次。
改启动链：检查主 Web、普通命令、PTY、简易终端及子进程环境，不只测登录 profile。

已有入口如下，本文只读核对，没有重新运行测试或连接设备：

- `app/src/test/java/com/deepseekharness/app/util/ResolverConfigTest.java`。
- `app/src/test/java/com/deepseekharness/app/runtime/RuntimePackageCommandsAndroidTest.java`。
- `tools/test-dns-compat.mjs`：含 Linux 原生 getaddrinfo 边界夹具，其他平台该项会 skip。
- `tools/test-dns-compat-device.mjs`、`tools/dns-compat-fixture.c`：名称含 device 不等于已做手机验收。
- `tools/test-build190-command-environment.py`：需指定 Linux Node 与隔离宿主环境，检查实际命令链。

这些回归通过也不能替代具体运营商、VPN、私有 DNS 或模型服务商的真实连接结果。
