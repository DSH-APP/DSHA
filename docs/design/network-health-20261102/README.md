# 网络健康检测（一期：只检测，不自动修复）

> 状态：已确认（2026-11-02），一期已实现，未上真机。实验性。
> 基线：build274（`413453e3`）。关联：build151 前台服务补齐、build190 F11 / `b7bee248` 后台保护、build267 切网刷新 DNS。

## 1. 为什么做

切后台断网、切网后无外网这类问题，过去都是用户反馈后再靠人工 `dumpsys` 逐层排查（2026-11-02 一次排查记录见第 8 节）。
断网发生时模型 API 本身就不可达，所以检测必须是 App 原生、确定性的，不依赖 AI 助手。

一期只做三件事：**发现、记录、告诉用户**。不重启 Web，不改系统设置，不重放请求。
先用真机证据确认哪类故障最常见，再在二期决定自动修复哪一类。

## 2. 背景机制（决定了检测什么）

- Android 15+ 有按 UID 生效的后台网络防火墙链（`dumpsys netpolicy` 中的 `blocked=APP_BACKGROUND`）。
  App 进程状态低于前台服务时，整个 UID 的 socket 被切断。
- 容器里的 proot / node 与 App 同一个 UID，又不是 Android 组件，无法单独豁免；
  **能否联网完全取决于 App 进程是否被前台服务（`HarnessService`）撑住**。
- 本机（Lenovo TB321FU / Android 16）实测：每次切后台 procstate 由 `TOP` 降到 `FGS`，
  能力位 `---NFUAT` 含 `N`（受限网络放行），`effective=NONE`，即当前版本正常时不断网。
- 因此一期的核心检测是：**DSH 在跑但前台服务不在**，以及**系统已经告诉我们被拦截**。

## 3. 检测项

| # | 故障 | 信号 | 触发时机 | 代价 |
|---|---|---|---|---|
| D1 | 系统拦截本 App 网络 | `NetworkSnapshot.blocked == true`（来自已有 `onBlockedStatusChanged`） | `NetworkStateMonitor` 事件 | 零，已有回调 |
| D2 | Web 在跑但前台服务缺失 | `HarnessController.requiresRuntimeProtection()` 为真，且本进程没有前台服务 | 进程转后台、网络事件、手动体检 | 一次本进程服务查询 |
| D3 | 容器 DNS 过期 | `ResolverConfig.describe(...)` 的 `resolv_state` 不是 `current` | 网络 AVAILABLE / CHANGED 后延迟 5 秒复核；手动体检 | 读一个小文件 |
| D4 | 有网络但出不去 | 宿主侧 DNS 解析 + HTTPS `HEAD` 探测超时或失败 | 只在 D1–D3 触发后补测一次，以及手动体检；**不做后台周期探测** | 一次网络请求 |
| D5 | 进程曾被冻结 / 深度休眠 | 心跳间隔远大于预期（`elapsedRealtime` 差值） | Web 运行期间 60 秒心跳；回到前台时结算 | 一个轻量定时 |

说明：

- **D2 怎么判断前台服务**：`HarnessService.foregroundPromoted` 是私有字段，且该类被运行时描述符钉住（见第 6 节）。
  一期改用 `ActivityManager.getRunningServices()` 读取本应用 `HarnessService` 的 `foreground` 标志
  （API 26+ 后该接口仍返回调用方自己的服务）。真机上要核对它与 `dumpsys activity services` 的 `isForeground` 一致。
- **D4 为什么在宿主侧探测**：App Java 进程与容器同 UID，受同一条防火墙规则。宿主探测便宜，不必拉起 proot。
  它不能发现「只有容器内坏」的问题（如 resolv.conf 指向不可达地址），这部分由 D3 覆盖；
  真实容器内探测（`getent` + `curl`）需要取环境任务锁，一期不做，放到二期。
- **探测目标**：一期固定为 DSH 默认模型服务 `api.deepseek.com`（只连主机根路径，不带 key、Cookie、路径）。读取用户自定义模型主机需要解析 DSH 配置，放到二期。
  只看能否建立 TLS 并收到任意 HTTP 状态码；401/403 视为通。
- **D5 的局限**：心跳停了只说明进程没被调度，不区分是 Doze、厂商冻结还是系统挂起，报告里如实写「疑似冻结」。省电模式下空闲本就允许 CPU 休眠，D5 不计入。
- **D2 的启动窗口**：Web 启动中（`isStarting`）由 `WebStartProtection` 先拿前台服务，此时不判缺失。

## 4. 判定结论

纯逻辑放 `util/NetworkHealthPolicy`（零 Android import，配单测），输入是上述信号，输出一个结论和证据：

| 结论 | 条件 | 给用户的话（草案） |
|---|---|---|
| `OK` | 无异常 | — |
| `SYSTEM_BLOCKED` | D1 | 系统正在限制 DSHA 后台联网。回到 DSHA 可恢复；常发生时请在电池设置中允许后台运行。 |
| `FGS_MISSING` | D2（可伴随 D1） | DSH 仍在运行，但后台保护未开启，切到后台会断网。回到 DSHA 即可重新开启。 |
| `DNS_STALE` | D3 | 容器 DNS 仍是上一个网络的地址，正在等待刷新。 |
| `EGRESS_FAILED` | D4 失败，且 D1–D3 正常 | 手机有网络，但无法连接模型服务。可能是 VPN、代理或服务端问题。 |
| `SUSPECT_FROZEN` | D5 | DSHA 在后台约 N 分钟未运行，可能被系统或省电策略冻结。 |

同一结论 10 分钟内只记一次新事件（计数累加），避免切网风暴刷屏。

## 5. 记录与呈现

- **记录**：私有目录 `files/nethealth/events.txt`（`DSHA_NET_HEALTH_V1`，每行 `时间|结论|次数|证据`），环形保留最近 200 条，临时文件改名写入。
  字段：时间、结论、证据（transport、vpn、validated、blocked、procstate 档位、resolv_state、探测耗时/错误类型）。
  **不记录** IP、SSID、运营商、DNS 服务器地址、URL 路径、key。
- **界面**：只放诊断页（已确认，启动页不加状态点）。`ui/DiagnosticActivity` 工具卡增加「网络健康」行，点开为底部面板：当前结论、最近 10 条事件、「立即体检」、「复制报告」。
  报告格式沿用 `DSHA_NET_V1` 风格的 `key=value`，方便用户直接贴到 issue。
- **通知**：一期不发任何通知（已确认）。DSHA 有时需要明确在后台工作（如识屏、跨应用操作），通知会打扰体验并打断屏幕识别；所有结论只记录，在诊断页查看。

## 6. 落点与运行时钉住约束

运行时描述符的 `launcherTrees` 包含整个 `device/`、`runtime/`、`diagnostics/` 目录，
`HarnessService`、`HttpShellService`、`DshaApp`、`core/DiagnosticLog`、`core/WebLifecycleController` 也在 `launcherSources` 中。
改动它们会换 runtimeId，所有用户覆盖更新后都要再跑一次运行时更新。

一期只新增、不修改被钉住的文件：

| 新增 / 修改 | 是否钉住 | 内容 |
|---|---|---|
| 新包 `nethealth/NetworkHealthMonitor.java` | 否（已核对 `nethealth/` 不在 `launcherTrees`） | 订阅 `NetworkStateMonitor`，收集 D1–D5，调用策略，写记录 |
| 新包 `nethealth/NetworkHealthProbe.java` | 否 | D4 宿主探测（DNS 5 秒限时 + HTTPS HEAD） |
| `util/NetworkHealthPolicy.java` + 测试 | 否 | 纯判定、去重、环形记录序列化 |
| `ui/NetworkHealthSheet.java`、`ui/DiagnosticActivity`（+1 行）、`layout/diagnostic_tools.xml`、`values/network_health_strings.xml` | 否 | 工具卡「网络健康」行与底部面板 |
| `ForegroundActivity.java` | 否（已核对） | 首次 resume 时惰性 `NetworkHealthMonitor.ensureStarted`；`onActivityStopped` 时触发一次 D2 复核 |
| `tools/i18n/messages-extra.json` + `translations-extra/*.json`、生成的 `values-*/ui_strings.xml`、`tools/check-source-hygiene.py` 字节钉、`tools/java-format-files.json` | 否 | 19 条新文案十语登记（`nh274a01`–`a19`） |

只读复用（不改）：`NetworkStateMonitor.addListener/current`、`ResolverRefresher.roots`、`ResolverConfig.describe`、
`HarnessController.requiresRuntimeProtection/isUserStopped`。

**二期才做**（需改钉住文件，单独评估）：在 `/app/net` 输出健康结论，让 agent 也能读；在 `HarnessService` 内直接上报前台服务状态。

## 7. 明确不做

- 不自动重启 Web、不重放请求、不按超时触发任何恢复动作。
- 不申请电池优化豁免，不尝试绕过厂商冻结或系统防火墙。
- 不在后台周期性发起外网探测（耗电，且后台被拦时探测本身也失败，没有新信息）。
- 不把「检测正常」写成「后台不会断网」的保证。

## 8. 依据（2026-11-02 本机只读排查）

- `dumpsys netpolicy`：`UID=10307 ... blocked=APP_BACKGROUND, allowed=FOREGROUND|TOP|NOT_IN_BACKGROUND|METERED_FOREGROUND, effective=NONE`；
  `Restricted networking mode: false`；历史中 `TOP ↔ FGS(---NFUAT)` 交替。
- `dumpsys activity services`：`HarnessService isForeground=true foregroundId=1001 types=0x40000000`。
- 容器链路：resolv.conf 托管且 `current`；glibc / Node 解析正常；DeepSeek / GitHub / npm HTTPS 正常；无 IPv6 出口；流量经 VPN。
- 历史缺口：build147 前前台服务只由启动按钮或 LAN 回前台拉起；build151 改为任意界面补齐；
  F11（`bc78b00f`）修复接回原 Web 期间不拉前台服务；`b7bee248` 先拿前台服务再启动 Web。

## 9. 已确认（2026-11-02）

- Q1 不发通知：`FGS_MISSING` 只记录。
- Q2 探测目标用当前模型服务主机，不带任何凭据：可以。
- Q3 只放诊断页，启动页不加。
- Q4 Web 运行期间 60 秒心跳：可以（不持锁）。

## 10. 验证计划

- 单测：`NetworkHealthPolicyTest` 覆盖六种结论、组合优先级（`FGS_MISSING` > `SYSTEM_BLOCKED` > `DNS_STALE` > `EGRESS_FAILED`）、10 分钟去重、环形截断、报告不含 IP/路径。
- 真机（测试包 `io.github.sunsetrne.dsha.test`，不在正式包上做）：
  1. 正常切后台 10 分钟：应保持 `OK`，与 `dumpsys netpolicy` 的 `effective` 对照；
  2. 开关 VPN、WiFi ↔ 移动数据、飞行模式往返：观察 D3 是否出现并在刷新后恢复；
  3. 熄屏 30 分钟以上：观察 D5；
  4. `FGS_MISSING` 无法在不改钉住文件的前提下稳定注入，用策略单测覆盖，真机只验证 D2 读取值与 `dumpsys` 一致。
- 验收范围如实声明：只代表测过的机型与系统版本。
