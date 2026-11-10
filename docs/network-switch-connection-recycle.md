# 切网后回收失效的外网连接（T37，实验性）

> **修复声明**：本仓库在提交 `0607535a`（分支 `fix/stream-stall-network`，基于 build299 `a1aabfaa`）加入了一项**实验性**修复：Android 默认网络切换、断网恢复、通过验证或解除后台限制后，容器里的 Node 会回收失效的外网连接，卡住的模型请求会在大约 20 秒内报成可重试的连接错误，不再等满 300 秒流空闲超时。
>
> 这项修复**只经过宿主测试**（假服务与真实 dsh 模型适配器），**没有做真机切网验证**。它不重放请求，不改超时和重试策略；覆盖不到的情况见[范围与限制](#范围与限制)。是否进入发布版本、进入哪一版，以 [CHANGELOG](../CHANGELOG.md) 和对应的 [releases/](releases/) 记录为准。

## 现象

网络抖动或手机切后台后，AI 助手概率性卡在“加载中”：重试几次后屏幕上没有任何输出；点停止后也没有可显示的内容。

## 原因

本机会话日志里三例（2026-11-02 至 11-03）是同一种模式：

1. 几个会话在同一毫秒报 `TRANSPORT`（`Connection error` / `terminated`），说明是网络层面的抖动；
2. `llm-retry` 在 1–3 秒内快速重试 1–3 次；
3. 最后一次重试发出后 80–165 秒内，连响应头都没收到；
4. 用户点停止后，`turn/end aborted` 立即写入。停止已经送达，只是这次尝试没有任何内容可显示。

另有一例等满 300 秒，报 `pi-ai stream idle timeout after 300000ms`。

模型请求走 Node 内置 `fetch`（undici 全局连接池）。切网后，旧 TCP 连接仍然绑在旧网络和旧源地址上，可能已经失效却不报错。复用这种连接、或正在这种连接上等待的请求，只能等 `dsh-llm-pi-ai` / `dsh-llm-deepseek` 的流空闲看门狗（`streamIdleTimeoutMs`，默认 300000 ms）。Android 应用进程的 `os.networkInterfaces()` 只能看到 `lo`，Node 自己无法判断源地址是否还在，所以由宿主告知网络变化。

## 机制

```text
NetworkStateMonitor（全 App 唯一的默认网络监听）
  └─ NetworkEpochPublisher：NetworkEpochPolicy 判定需要时
       写 <rootfs>/run/dsha-network/epoch（单行 ASCII 代次）
            │
容器内 Node 进程（NODE_OPTIONS 预加载 net-epoch.cjs）
  └─ 有外网连接时每 2 秒读一次代次；代次变化后：
       · 空闲的外网连接：立即关闭，后续请求和重试一律新建连接
       · 正在使用的外网连接：宽限 20 秒，期间收到过任何字节就保留；
         一个字节都没收到才关闭（错误码 DSHA_NETWORK_CHANGED）
            │
fetch 报 TypeError → 适配器归为 TRANSPORT → llm-retry 按原策略重试
```

### 何时推进代次（`NetworkEpochPolicy`）

| 网络事件 | 推进 | 理由 |
|---|---|---|
| 默认网络换了（如 Wi-Fi → 移动数据），或从无网络到有网络 | 是 | 旧连接绑在旧网络上 |
| 同一网络从未验证变为已验证 | 是 | 之前的连接可能落在不通的路径上 |
| 后台网络限制解除（blocked → 未 blocked） | 是 | 限制期间的连接可能已被丢弃 |
| 断网本身 | 否 | 没有网络时新建连接也不会成功，保留仍可能恢复的连接 |
| DNS、信号、验证丢失、进入 blocked | 否 | 与连接是否失效无关，或此时重建无益 |

代次正文由开机后单调时间与进程内序号组成，App 重启后不会与旧值相同。Node 第一次看到的代次只作为基线，不触发回收。

### Node 侧（`net-epoch.cjs`）

- 通过 undici `diagnostics_channel`（`client:connected`、`client:sendHeaders`、`request:trailers`、`request:error`）跟踪连接与使用状态，不替换、不包装全局 dispatcher，因此与 `dsh-http-proxy` 安装的代理 dispatcher 共存。
- 只跟踪外网连接：主机名或对端地址为 `localhost`、`127.0.0.0/8`、`::1` 的连接（3090 桥、MCP、本机代理）一律不动。经本机代理转发的外网请求因此也不在覆盖范围内。
- 没有外网连接时不轮询；轮询计时器 `unref`，不阻止进程退出。
- 代次文件用 `O_NOFOLLOW | O_NONBLOCK` 打开，只接受 1–64 字节、`[0-9a-z.-]` 的普通文件；链接、FIFO、超长或格式不符一律视为“没有代次”。
- 有连接被关闭时，向 stderr 写一行：

  ```text
  [dsha-net] default network changed; closed N idle|stalled upstream connection(s)
  ```

## 配置

| 环境变量 | 默认 | 作用 |
|---|---|---|
| `DSHA_NET_EPOCH=0` | 未设 | 整体关闭；连接与超时行为回到修复前 |
| `DSHA_NET_EPOCH_GRACE_MS` | `20000` | 使用中连接的宽限期，接受 1000–120000 |
| `DSHA_NET_EPOCH_FILE` | `/run/dsha-network/epoch` | 仅供测试改代次文件位置 |
| `DSHA_NET_EPOCH_TEST_LOOPBACK=1` | 未设 | 仅供测试，把本机假服务当外网连接 |

宽限期取 20 秒，是为了不误关长时间推理、还没有任何输出的流。代价是切网后最坏约 20 秒（加至多 2 秒轮询）才进入重试。

## 范围与限制

- **未真机验证**：没有在手机上实测 Wi-Fi ↔ 移动数据切换、切后台再回来。
- **网络没有切换时不起作用**：中转服务本身迟迟不回（例如 504 `first_output_timeout`）、NAT 静默丢弃连接但 Android 没有报告网络变化，仍然要等 `streamIdleTimeoutMs`。需要更快失败时，可以在对应提供方配置里单独调小 `streamIdleTimeoutMs`；这会改用户个人配置，本修复不代改。
- **只覆盖 Node 内置 fetch / undici**：curl、git、pip、Python 等其它进程不受影响；工作线程有自己的 `globalThis`，按 `NODE_OPTIONS` 各自加载。
- **不改 dsh 本体**：没有运行时补丁；停止、`llm-retry` 次数与退避、看门狗时长都不变。
- **旧环境**：运行根里没有 `net-epoch.cjs` 时，`RuntimeTools` 不加这项预加载。

## 排查

1. 切网后还卡：在 dsh 日志里搜 `[dsha-net]`。没有这一行，说明没有连接被回收，可能是网络没有被报告为切换，或宽限期内仍有数据到达。
2. 在容器终端里看代次是否推进：`cat /run/dsha-network/epoch`，切一次网再看，值应该变化。
3. 怀疑误关：设 `DSHA_NET_EPOCH=0` 后重启 Web 对比，或调大 `DSHA_NET_EPOCH_GRACE_MS`。

## 代码与测试

| 位置 | 内容 |
|---|---|
| [net-epoch.cjs](../app/src/main/assets/net-epoch.cjs) | Node 预加载，回收逻辑 |
| [NetworkEpochPolicy.java](../app/src/main/java/com/deepseekharness/app/util/NetworkEpochPolicy.java) | 何时推进代次、代次格式（纯逻辑） |
| [NetworkEpochPublisher.java](../app/src/main/java/com/deepseekharness/app/runtime/NetworkEpochPublisher.java) | 订阅网络监听，写各运行根的代次文件 |
| [RuntimeTools.java](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java) | 启动发布者；`NODE_OPTIONS` 加预加载 |
| [dsha-runtime-env.sh](../app/src/main/assets/dsha-runtime-env.sh) | 终端登录环境同样加预加载 |
| [test-net-epoch.mjs](../tools/test-net-epoch.mjs) | 9 个场景：空闲、不回头、发一半停住、持续出字、代次不变、首次基线、开关、回环、链接文件 |
| [NetworkEpochPolicyTest.java](../app/src/test/java/com/deepseekharness/app/util/NetworkEpochPolicyTest.java) | 推进与不推进的网络事件、代次格式 |

相关文档：[端口与网络](ports-and-networking.md)、[DNS 配置](dns-configuration.md)。
