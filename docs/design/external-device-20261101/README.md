# 外接设备调试与刷机（ExternalDevice）需求与设计

状态：**P1 调试 / 安装已接入执行链，刷写只到只读预检与 dry-run，未经真机验收**。2026-11-02 在 `feat/external-usb-driver`（基于 build265）接手 `feat/external-device-debug`（`88b3157d`）。

现状（源码事实，详见第 14 节）：独立授权开关、单目标绑定（USB 序列号或无线 IPv4:端口）、序列号/产品/指纹复核、USB 模式识别、无线 connect / pair、只读调试 shell、确认后重启到 system / bootloader / recovery / fastbootd、确认后安装 APK / APKS / XAPK / 模块 ZIP、只读 fastboot 预检及刷写 dry-run。**不执行** flash / sideload / set_active / erase / unlock，不接入无 root USB 桥。adb / fastboot / 加载器字节由 `tools/external-tools/lock.json` 锁定。本文第 3 节实测仍全部为只读操作。

证据分级：**已发生**＝会话中实际做过的操作；**实测**＝本轮只读探测结果；**源码事实**＝当前仓库代码；
**外部资料**＝第 12 节所列来源，未经本机复现；**设计**＝本文提出、尚未实现。

## 0. 结论

1. 把 build243 验收时的手工流程（平板 root 起 adb server，容器 `adb -P 5038` 操作另一台一加）做成产品能力「外接设备」，并在此基础上支持刷机。
2. **刷机只针对经 USB 外接、已绑定序列号的目标设备。** 本机（运行 DSHA 的设备）的刷机、重启、写块设备禁令完全不变，`DeviceShellPolicy` 不放宽一个词。
3. 外接通道是**独立能力、独立授权、独立执行器**，不复用、不兜底本机 Root / Shizuku / ADB 三通道，也不受「全局授权」顺带开启。
4. 按风险分四级；会清数据、降安全的操作每次都要原生确认，没有「始终允许」。
5. **目标设备上报的属性不可信。** 实测测试机已解锁并 root，但 `getprop` 被模块伪装成「已上锁 / green」；预检必须读 `/proc/bootconfig` 或 fastboot `getvar`。
6. **无 root 路线可行且工作量可控**：App 经 `UsbManager` 授权拿到 USB fd，在 App 内做 USB↔TCP 桥，容器用原版 `adb connect` / `fastboot -s tcp:` 访问，不必移植 termux-adb 补丁。建议先做 root 路线（P1/P2）验证业务，再做无 root 桥（P3）。

## 1. 背景

**已发生**（会话「延续 @@点击这个全局授权弹窗」，2026-10-31～11-01；记录见 [build243 发布说明](../../releases/v0.2.1-rc.3-build243-phone-portrait-20261101.md) 第 3 节）：

- 平板进入 USB 主机模式，一加出现在 `/dev/bus/usb/001/002`，节点 `system:system 660`，容器内 adb 以 App UID 运行，打不开，`adb devices` 为空。
- 用户因热点网速太差否决无线调试，明确要求「直接用 root」。
- 用 su 拉起容器内 adb：`HOME` 指向 App 私有 cache、`-P 5038`（5037 已被容器自己的 server 占用）、nodaemon 模式后台运行（daemon 模式自我 re-exec 会让 ld.so 参数错位）。
- 中途误加 `-a`，server 暴露到热点网络，随即停掉改为只绑本机。
- 该 root adb 进程一直存活（2026-11-01 17:1x 仍在，PID 16198），没有 PID 身份记录，也没有人负责停止。

当时给出的产品化建议（`ExternalAdbHost`、单独授权、只绑本机、验收脚本进 `tools/`）**未实现**。本轮新增需求：在此基础上支持刷机类操作。

## 2. 目标与非目标

### 2.1 目标

| 编号 | 能力 | 风险级 |
|---|---|---|
| G1 | 发现并绑定 USB 外接设备（adb / fastboot / fastbootd / recovery / sideload 各模式） | L0 |
| G2 | 外接 adb：shell、install、截图、`wm size/density` 等验收操作 | L1 |
| G3 | 重启到 system / bootloader / recovery / fastbootd | L1 |
| G4 | `fastboot flash <分区> <镜像>`：boot、init_boot、vendor_boot、dtbo、recovery 等单分区 | L2 |
| G5 | `adb sideload` 卡刷 OTA 包 | L2 |
| G6 | `fastboot update` / 厂商线刷整包脚本（`flash_all.sh` 类） | L2，脚本须先解析成受管步骤 |
| G7 | 切换 A/B 槽位（`fastboot set_active`） | L2 |
| G8 | 解锁 / 回锁 Bootloader（`flashing unlock/lock`、`oem unlock` 类） | L3 |
| G9 | 关闭 vbmeta 校验（`--disable-verity --disable-verification flash vbmeta`） | L3 |

### 2.2 非目标

- 刷写**本机**任何分区；在本机执行 `fastboot`、`reboot`、写 `/dev/block`。本机禁令由 `DeviceShellPolicy` 继续负责，本能力不提供例外。
- 局域网 / 无线目标的刷机。无线 adb 可用于 L0–L1，L2 以上只允许 USB 直连。
- 鸿蒙设备、高通 EDL（9008）、联发科 BROM/SP Flash Tool 等非 fastboot 协议。
- 绕过厂商解锁授权（一加 / OPPO 深度测试、小米解锁等待期等）。
- 自动下载固件。镜像只来自用户放入的文件。

## 3. 实测环境与结果（2026-11-01，只读）

### 3.1 宿主（运行 DSHA）

| 项 | 值 |
|---|---|
| 机型 | 联想 TB321FU，Android 16（SDK 36） |
| Root | KernelSU，`adb-shell --su` 实测 `uid=0`，上下文 `u:r:ksu:s0` |
| USB | 主机模式；目标机节点 `/dev/bus/usb/001/002`，`system:system 660` |
| 遗留 adb server | PID 16198，`adb -P 5038 nodaemon server`，`ss` 实测只监听 `127.0.0.1:5038` |
| 容器工具 | `/usr/bin/adb` 1.0.41、`/usr/bin/fastboot` 34.0.4-debian（与 termux-adb 补丁基线同版本） |

### 3.2 目标（一加 12，PJD110，序列号 `<目标序列号>`）

| 项 | 值 | 来源 |
|---|---|---|
| 系统 | ColorOS `PJD110_16.0.10.501(CN01)`，Android 16，补丁 2026-08-05 | getprop |
| 出厂系统 | ColorOS 14（一加 12 首发版本） | 外部资料 |
| Root | KernelSU **LKM 模式**（`ksud 1.3.2`，`kernelsu` 内核模块已加载），修补 `init_boot`，KMI `android14-6.1`，内核 6.1.141 | ksud / lsmod |
| adb shell 身份 | **默认即 `uid=0`**（KSU 对 shell 授 root），`su -c` 亦可 | id |
| 原厂 init_boot 备份 | `/data/adb/ksu/ksu_backup_*`，8 MiB，与分区等大；SHA-256 与当前两槽均不同（当前活动槽 `_b` 为修补后镜像） | sha256sum |
| BL **真实**状态 | `vbmeta.device_state = unlocked`，`verifiedbootstate = orange` | `/proc/bootconfig` |
| BL **伪装**状态 | `ro.boot.flash.locked=1`、`device_state=locked`、`verifiedbootstate=green` | getprop（被模块改写） |
| 伪装 / 隐藏模块 | Treat Wheel、TEESimulator-RS（tricky_store）、Play Integrity Fix、HMA-OSS；另有 LSPosed、ReZygisk、Magic Mount-rs、爱玩机工具箱 | `/data/adb/modules` |
| 分区布局 | A/B + 动态分区 + Virtual A/B；活动槽 `_b`；`boot` 192 MiB、`init_boot` 8 MiB；`ab_ota_partitions` 含 xbl/abl/tz 等全部底层固件 | getprop / blockdev |
| AVB rollback index | `vbmeta_a/b` = 0；`vbmeta_system_a` = 1777593600（2026-05-01）、`vbmeta_system_b` = 1785542400（2026-08-01）；vbmeta flags = 0（校验未关闭） | 读 vbmeta 头 |
| 硬件防回滚熔丝 | **读不到**（在 XBL/qfuse 侧，不在 bootconfig 与 vbmeta 中） | — |

### 3.3 工具链探测

| 探测 | 结果 |
|---|---|
| 以 su + ld.so 执行容器 fastboot | 可执行，`fastboot devices` 返回 0（目标处于 adb 模式，列表为空，符合预期） |
| 容器 fastboot TCP 传输 | `fastboot -s tcp:127.0.0.1:<port>` 被识别并尝试连接（无监听时报连接失败） |
| 容器内 `SCM_RIGHTS` 传 fd | 同容器两进程间经 Unix socket 传 fd 成功；abstract socket 可绑定 |
| App(Java) → 容器传 fd | **未测**（需要 App 侧代码） |
| fastboot 模式下 `getvar all` | **未测**（需重启目标，用户暂不同意） |

**附带发现**：本机 `adb-shell --su "… fastboot devices"` 未被拦截，说明当前宿主 Root 档位不是 strict/relaxed（`irreversible()` 含 `fastboot`）。这与外接设计无冲突，但证明「本机禁刷」目前依赖用户所选 Root 档位，full 档下模型可以直接在宿主上调 root fastboot。外接能力落地时不能依赖 full 档，且应在 full 档下也禁止经 `adb-shell` 执行 fastboot（见 8.6）。

## 4. 技术约束

- adb 走 server（smartsocket），容器内客户端连 `127.0.0.1:<port>` 即可，server 才需要打开 USB 节点。
- **fastboot 没有 server**，每次调用都直接打开 USB 节点（外部资料：termux-adb README 明确指出这一点导致其 fastboot 较慢）。root 路线下只能每次以 root 执行。
- 设备切模式会重新枚举 USB：总线地址变化，序列号通常不变。绑定按序列号 + 产品信息，不按 `/dev/bus/usb/x/y`。
- **目标属性不可信**：root 设备普遍装有属性伪装模块（3.2 实测）。任何安全判定都不能只用 `getprop`；adb 模式读 `/proc/bootconfig`（需 root），fastboot 模式用 `getvar unlocked` 等 bootloader 返回值。
- **adb shell 即 root**：在 KSU / Magisk 授权 shell 的设备上，`adb shell dd of=/dev/block/...` 不经 fastboot 就能写分区。shell 等价写法必须纳入分级（5.2）。
- 本机命令策略：`DeviceShellPolicy.BLOCK` 与 `irreversible()` 均含 `fastboot`、`reboot`、`flash`；外接能力**不得借用 Root full 档**执行，否则「外接」与「本机」在审计上无法区分。

## 5. 总体架构（设计）

```
容器 / dsh ──HTTP──▶ 3090 桥 /app/extdev/*（token + 当前任务身份）
                         │
                         ▼
               ExternalDeviceHost（App，独立协作者）
               ├─ ExternalAdbHost        P1：root 常驻 adb server，仅 loopback，PID+出生身份
               ├─ ExternalFastbootRunner P2：root 单次执行 fastboot，argv 由 App 组装
               ├─ UsbTransportBridge     P3：UsbManager fd ↔ 本机 TCP（adb / fastboot 两种成帧）
               ├─ ExternalTargetBinding  序列号绑定、本机排除、跨模式追踪
               └─ FlashJournal           每步操作回执（rootfs 外，私有目录）
                         │
                         ▼
                 USB 外接目标设备
```

- 新协作者放 `extdev/` 包，**不塞进 `HarnessController` / `DeviceShellExecutor`**。纯逻辑（argv 组装、风险分级、预检判定、线刷脚本解析、帧解析）放 `util/` 并配单测。
- **容器不直接拿到 root fastboot。** dsh 只能调用 `/app/extdev/*` 的结构化操作（`op=flash&partition=boot&file=...`），由 App 组装 argv 后执行。不提供透传任意 fastboot 参数的端点。

### 5.1 ExternalAdbHost（P1，root）

- 以 su 启动容器内 adb：`HOME` 指向 App 私有目录，nodaemon、固定 `-P`（默认 5038，冲突时取空闲端口），**禁止 `-a`**，启动后用 `ss -ltn` 核验只有 `127.0.0.1`（3.1 已实测可行）。
- 记录 PID 与出生身份；停止、看门狗、复用判定与 `WebProcessManager` 同规则，不按端口或名称杀进程。
- 能力关闭、App 停止、用户撤销 root 授权时停止 server；不随开机自启。首次落地时一并接管或提示清理遗留的手工 server。

### 5.2 ExternalFastbootRunner（P2，root）

- 每次以 su + ld.so 单次执行容器 fastboot（3.3 已实测可执行），argv 来自 `util/` 组装器，强制带 `-s <已绑定序列号>`。
- 执行前核对 fastboot 二进制 SHA-256 与受管清单一致（同 UID 插件可替换 rootfs 内文件）。
- 镜像只接受 App 私有导入区（文件选择器导入后复制并计算 SHA-256），不接受任意路径。
- 单步无 65 秒上限，改为「无输出超时 + 进度」；超时或断线一律 `EXECUTION_UNKNOWN`，**不重放**。

### 5.3 UsbTransportBridge（P3，无 root）

外部资料表明三条路线：

| 路线 | 做法 | 评估 |
|---|---|---|
| A. 移植 termux-adb | 补丁版 adb/fastboot 经 Unix socket 收 `UsbManager` 的 fd | 补丁基于 android-tools 34.0.4（与容器同版），MIT 许可；但 termux 产物是 bionic，需对 glibc 重编并维护补丁分支 |
| B. **App 内 USB↔TCP 桥（推荐）** | App 持有 `UsbDeviceConnection`，按协议成帧转发到 `127.0.0.1:<随机端口>`；容器用原版 `adb connect 127.0.0.1:N`、`fastboot -s tcp:127.0.0.1:N` | 不改容器工具；adb TCP 与 USB 使用同一 apacket 格式，桥只需按 24 字节头切分；fastboot TCP 为 `FB01` 握手 + 8 字节大端长度前缀，桥负责 USB 包与 TCP 帧互转；容器 fastboot 已实测识别 `tcp:` |
| C. Java 完整实现 adb/fastboot 客户端 | 不用容器工具 | 工作量最大，且要自己维护 sideload、sparse 等细节，不推荐 |

路线 B 注意事项：

- 经 TCP 时容器看到的序列号是 `127.0.0.1:N`，绑定身份以桥上报的 USB 序列号为准，桥按端口一一映射到已授权设备。
- adb 鉴权仍由目标机弹窗确认 RSA 公钥，与 USB 一致。
- 本机任何应用都能连 loopback 端口：桥端口随机、只在任务期间监听，并要求连接后首包携带一次性令牌（容器经 3090 桥领取），否则断开。
- fastboot 下载大镜像依赖 USB bulk 吞吐，桥需零拷贝或大缓冲；吞吐需真机测量。
- App(Java) → 容器的 fd 传递只在路线 A 需要；路线 B 不跨进程传 fd。

## 6. 风险分级与确认

### 6.1 分级

| 级 | 操作 | 确认 | 预检 |
|---|---|---|---|
| L0 只读 | `devices`、`getvar all`、`getprop`、`/proc/bootconfig`、截图、读屏 | 开启能力即可 | — |
| L1 可恢复 | adb shell / install / `wm`、重启到各模式 | 任务内首次确认 | 目标已绑定 |
| L2 写分区 | flash 单分区、sideload、update / 线刷脚本、`set_active` | **每次**原生确认，显示序列号、分区、文件名、SHA-256、大小 | 见 7 |
| L3 清数据 / 降安全 / 可能变砖 | 解锁 / 回锁 BL、关闭 vbmeta 校验、`-w` / `erase userdata`、刷入比当前更旧的底层固件或整包 | **每次**原生确认 + 输入目标序列号后四位；文案写明具体后果 | 见 7，另需二次读取真实解锁状态 |

- 确认是原生弹窗，绑定「序列号 + 操作 + 文件摘要 + 数据代次」；HTTP 接口不能自行确认，模型不能代答，全局确认策略不覆盖 L2/L3。
- 没有「仍然允许」或「本任务内不再询问」。

### 6.2 adb shell 内的等价写法

外接 adb shell 对目标不做白名单（否则调试无意义），但以下文本级匹配按 L2/L3 处理：`dd of=/dev/block`、`> /dev/block`、`blockdev --setrw`、`reboot bootloader|recovery|fastboot|edl`、`update_engine_client`、`bootctl set-active`、`recovery --wipe`、`ksud boot-patch|boot-restore`、`magisk --install`。实测目标 shell 默认就是 root（3.2），这条不是假想风险。文本级检测**不宣称覆盖所有等价写法**，写进已知弱点。

## 7. 刷写预检（L2/L3）

逐项检查，任一失败即拒绝，结果写入回执：

1. 目标仍是绑定的序列号与产品，且为 USB 连接。
2. 电量 ≥ 30%（`getvar battery-level` 或 adb 读取；读不到时用户手动确认）。
3. **真实解锁状态**：adb 模式读 `/proc/bootconfig` 的 `androidboot.vbmeta.device_state` / `verifiedbootstate`；fastboot 模式读 `getvar unlocked`。`getprop` 结果只作参考，与真实值冲突时在确认框中显示「目标正在伪装锁定状态」。
4. **回锁禁令**：满足任一条件即拒绝回锁（不是确认，是拒绝）——`verifiedbootstate` 为 orange/yellow；存在 `/data/adb/ksu`、`/data/adb/magisk` 等 root 痕迹；任一分区摘要与用户提供的官方整包不一致；无法证明当前为官方签名系统。理由：修补后的 init_boot / boot 在锁定状态下无法通过验证启动，回锁几乎必然无法开机。
5. **防回滚 / 降级**：
   - 记录当前 `ro.build.display.id` 与 vbmeta / vbmeta_system rollback index（3.2 已实测可读）。
   - 待刷镜像带 AVB footer 或整包带 vbmeta 时，比较 rollback index；**低于当前值即拒绝**（AVB 本身会拒绝启动）。
   - 刷入整包或 xbl/abl/tz 等底层固件且版本早于当前版本时，按 L3 处理并显示「一加 / OPPO 部分机型存在硬件熔丝防回滚，降级可能永久变砖、9008 也无法救回；本机无法读取熔丝状态」。不可判断版本时同样按 L3。
6. `getvar current-slot` / `slot-count`：记录刷前槽位，提示目标槽位；默认只刷活动槽。
7. 动态分区（`super` 内的 system / vendor / product / my_* 等）要求处于 fastbootd（`getvar is-userspace` = yes），否则拒绝并提示 `reboot fastboot`。
8. 镜像大小不超过 `getvar partition-size:<分区>`；镜像头可识别时核对类型（boot 魔数 `ANDROID!`、AVB `AVB0`、sparse 魔数等）。对 GKI 设备，刷 root 应针对 `init_boot` 而非 `boot`（实测测试机 KSU 修补的是 init_boot），刷 `boot` 时提示确认意图。
9. 厂商线刷脚本先解析成受管步骤（只认 `fastboot ... flash|erase|set_active|reboot` 形式），展示完整步骤后整体确认一次，其中 L3 步骤单独确认；含无法识别的行即拒绝，不交给 shell。
10. 宿主保持亮屏与充电提示；刷写中断线时立即停止后续步骤，记 UNKNOWN。

**备份**：flash boot / init_boot / vendor_boot / dtbo / vbmeta 前，若 adb shell 有 root，先 `dd` 读出两槽原分区存入私有目录并记录 SHA-256（3.2 实测 root shell 可读块设备）；KSU / Magisk 自带的原厂备份（如 `/data/adb/ksu/ksu_backup_*`）登记为还原候选，但须与官方整包中的同名镜像比对后才标记「原厂」。读不到时明确提示「无原分区备份」。

## 8. 安全模型需补充的已知弱点

落地时写进 [security-model.md](../../security-model.md) 与 [权限架构](../../device-permissions-architecture.md)：

1. **loopback 不是 UID 隔离。** 本机任何应用都能连 `127.0.0.1:5038` 的 root adb server，对目标获得完整（且常为 root 的）shell。P1 缓解：随机端口 + 只在能力开启期间存活；P3 桥加一次性令牌。不能宣称只有 DSHA 能用。
2. 外接 adb shell 对**目标**没有命令白名单；6.2 的检测是文本级。
3. 容器内 fastboot / adb 以 root 执行，二进制来自 rootfs，同 UID 插件可替换；执行前核对 SHA-256。
4. **目标属性可被伪装**，任何基于 getprop 的安全判定都不可靠（3.2 实测）。
5. 硬件防回滚熔丝状态读不到；降级风险只能提示，不能判定。
6. 本机禁刷依赖宿主 Root 档位：full 档下 `adb-shell --su` 可执行 fastboot（3.3 附带发现）。外接能力开启后，应在 full 档也拒绝经 `adb-shell` 调用 fastboot / adb server，统一走 `/app/extdev/*`，否则无法区分目标与审计。
7. 刷机失败可能导致目标无法开机；DSHA 不提供救砖，也不对厂商 bootloader 行为负责。root 路线依赖外部 root 管理器，DSHA 不是 root 管理器。

## 9. 权限与界面（设计）

- 设置 → 设备能力授权 新增「外接设备（USB 调试与刷机）」：默认关闭、可撤销、不从备份恢复、不被全局授权开启、不出现在首次引导。
- P1/P2 开启条件：本机 root shell 开关已开启且 su 实测 uid 0。P3 只需 `UsbManager` 系统授权弹窗。
- 卡片显示：通道（root / USB 授权）、端口、已绑定目标（型号、序列号、当前模式、真实解锁状态）、最近一次刷写回执。
- 系统提示词与 `/app/help` 同步说明：「本机禁止刷机；外接设备刷机只能经 `/app/extdev/*` 且需原生确认」。

## 10. 分期计划

| 期 | 内容 | 依赖 |
|---|---|---|
| P0 | 本文；`tools/external-adb.sh`（start / stop / status，只绑本机、PID 记录、禁 `-a`）把手工流程固化 | — |
| P1 | `ExternalAdbHost` + `ExternalTargetBinding` + 设置开关；L0/L1；验收脚本（装包、切 360/411dp、1.3 倍字体、深色、逐页截图到 `artifacts/`） | root |
| P2 | `ExternalFastbootRunner` + 预检 + 分级确认 + `FlashJournal`；G3–G9 | P1，需目标可重启做真机验收 |
| P3 | `UsbTransportBridge` 路线 B，无 root；与 P1/P2 共用绑定、分级与回执 | P1 的绑定与分级 |

P3 可与 P2 并行开发：两者共用 `util/` 的分级、预检与 argv 组装，只是传输层不同。P3 的 adb 部分不需要重启目标即可验证。

## 11. 测试与验收

**单测（`util/`）**：

- argv 组装：恒带 `-s`；分区名白名单与非法字符拒绝；镜像路径只能在私有导入区；无透传参数。
- 风险分级：每个 G 项映射到正确级别；6.2 的 shell 文本检测正反例。
- 本机排除：本机序列号、`localhost:*`、本机 IP 永远拒绝绑定。
- 预检：电量、真实解锁状态与 getprop 冲突、回锁禁令（orange / root 痕迹）、rollback index 低于当前、fastbootd、大小超限、序列号漂移各自拒绝。
- bootconfig 解析：以 3.2 的实测片段为夹具（去掉 chipid 等标识）。
- 线刷脚本解析：合法脚本展开为步骤；含 `rm`、管道、变量展开、未知命令的脚本整体拒绝。
- P3 帧解析：adb 24 字节头切分、fastboot `FB01` 握手与 8 字节长度前缀，含半包 / 粘包。
- 回归：`DeviceShellPolicy` 对本机 `fastboot` / `reboot` 的拦截在新能力开启后仍然生效。

**真机验收**（P2，须用可救回的测试机）：

- 进 bootloader 读 `getvar all`，与 3.2 的 bootconfig 结果对照。
- 刷当前版本的原厂 init_boot 到非活动槽 → `set_active` → 开机 → 切回。
- sideload 当前或更新版本的官方 OTA（**不做降级**）。
- 刷写中拔线：后续步骤停止、回执 UNKNOWN、不自动重放。
- 回锁请求在 3.2 状态下被拒绝（不实际执行回锁）。
- 宿主侧：能力开启状态下尝试对本机 `adb-shell fastboot` / `reboot`，按 8.6 被拒绝。

**P3 验收**（无需重启）：经桥 `adb connect` → `devices` → `shell id` → 安装测试 APK → 截图，对比 5038 root 路线的吞吐。

声明范围按实测机型写，不扩写成全厂商兼容。

## 12. 外部资料

- 一加 12 型号 PJD110：[onfix 固件页](https://onfix.cn/rom/996737)
- 一加 2026-07-24 深度测试新规（ColorOS 16 出厂 / 升级 ColorOS 17 机型需申请；账号满 60 天、实名人脸、一年 4 台、名额制、审核约 7 个工作日、有效期 14 天；Q&A 称不满足条件的旧机型解锁不受影响）：[凤凰网转载](https://tech.ifeng.com/c/8v1N00Aaxti)
- 2025-10 版本政策（无答题、无名额限制）：[IT之家](https://m.ithome.com/html/892978.htm)
- 国行老机型升 ColorOS 17 后无法解锁 / 进不了 fastboot 的用户报告（仅读到搜索摘要）：[XDA](https://xdaforums.com/t/cn-version-coloros-17-cannot-unlock-bootloader-cannot-enter-fastboot-do-not-upgrade-system.4806298/)
- ColorOS 16.0.3.501 起硬件熔丝防回滚：[一加社区](https://bbs.oneplus.com/thread/2072278403284729856)、[安兔兔](https://www.antutu.com/doc/136102)、[9to5Google](https://9to5google.com/2026/01/20/oneplus-update-starts-bricking-phones-when-you-try-to-install-older-software/)；一加 12 是否受影响说法不一（[NGA 用户反馈](https://ngabbs.com/read.php?tid=47319954)）
- 无 root 外接 adb/fastboot：[termux-adb](https://github.com/nohajc/termux-adb)（MIT，android-tools 34.0.4 补丁，`termux-usb` fd + Unix socket）；手机给手机线刷实践：[XDA Bugjaeger 教程](https://xdaforums.com/t/guide-flash-roms-on-the-go-with-another-phone-without-pc.4518275/)
- `UsbDeviceConnection.getFileDescriptor()` 交给 native 的做法：[AOSP libusb 说明](https://android.googlesource.com/platform/external/libusb/+/refs/tags/aml_ads_341517040%5E1..refs/tags/aml_ads_341517040/#4)、[android-tools #169](https://github.com/nmeum/android-tools/issues/169)
- fastboot TCP 协议（`FB01` 握手、8 字节大端长度前缀）：[AOSP fastboot README](https://android.googlesource.com/platform/system/core/+/46d46dc46446f14f26fbe8fb102dd36c1dfc1229/fastboot)、[Fuchsia Transport 文档](https://docs.fuchsia.dev/cpp/fastboot/_ZTVN8fastboot9TransportE.html)

## 13. 待定问题

1. 测试机在 fastboot / fastbootd 下的 `getvar all`（需重启，用户暂缓）。
2. 一加 12 是否已触发硬件熔丝防回滚；目前没有只读方法确认，可能只能依据厂商公告或版本号推断。
3. 测试机若升级到 ColorOS 17，是否会失去 fastboot 入口（外部报告）；验收期间建议关闭目标机系统自动更新。
4. 线刷脚本是否只支持 `.sh`，还是同时解析厂商 `.bat` / 一加 `Super Flasher` 类整包。
5. P3 桥的 USB 吞吐与 `adb sideload` / 大镜像下载在 Java 层的性能，需真机测量。
6. 宿主 Root 档位为 full 时，是否在外接能力开启前就收紧 `adb-shell` 对 fastboot 的放行（8.6），还是只在开启后收紧。

## 14. 2026-11-02 接手后的实现（源码事实，未真机）

| 项 | 实现 |
|---|---|
| 无线目标 | 用户确认：安装类操作允许无线。`isWirelessTarget` 只收 IPv4 字面量 + 端口，拒回环 / 0.0.0.0 / 组播 / 广播；宿主再按本机全部网卡地址拒绝，并拒绝与本机 `Build.DEVICE` + `Build.FINGERPRINT` 都相同的目标（同型号同版本另一台改用 USB）。fastboot、预检、刷写仍只认 USB |
| 多步会话 | 每次操作仍是一个专属 nodaemon server（只绑 loopback、用完回收）；同一会话内顺序执行多步：无线先 `connect` 并核对输出，再 `wait-for-device`，再执行业务步骤。步骤边界用每次随机 nonce，目标输出不能伪造；未开始的步骤记 `STEP_NOT_RUN`，未知结果记 UNKNOWN 且不重放 |
| 执行守卫 | `ExternalToolPolicy` 在 App 侧和 root helper 内各核一次：`install -r` / `install-multiple -r`（≤20 个分包）/ `push` 的本地文件必须在私有导入区；`push` 目标只能是 `/data/local/tmp/dsha_install/aNNN.apk`、`module.zip`、`/sdcard/<名>`、`/sdcard/Android/obb/<包名>/<含包名的 obb>`；`adb shell` 单串只放行 `ExternalInstallPlan` 生成的形状（往返转义一致才算），其余仍走只读白名单；`adb reboot` 只收 bootloader / recovery / fastboot；`fastboot` 只收 `devices`、`getvar`、`reboot [bootloader|fastboot|recovery]` |
| 安装 | `operation=install&value=<导入名>`：先只读描述（种类、L1/L2、SHA-256、大小、目标与代次）→ 原生确认显示这些值 → `install()` 按同一摘要与代次复核后执行。APK：目标 shell 有 root 时推送后 `pm install`，否则 `adb install -r`；APKS / XAPK：解到私有临时目录（文件名自生成，防 zip-slip，总量 ≤4 GiB），root 走 pm 会话（会话号解析不出即停，commit 前确定失败才 abandon），否则 `install-multiple`；XAPK 的 OBB 推到对应目录；模块 ZIP 按 Magisk / KernelSU / APatch 安装（L2），无管理器则推到 `/sdcard/`；AAB 拒绝 |
| 重启 | L1，每次确认；目标已在 bootloader / fastbootd 时改用 `fastboot reboot`。edl / sideload 不开放 |
| 刷写验证 | 用户要求：**只做验证，不实际刷写**（当前使用的分支不稳定）。`/app/extdev/preflight` 读 `getvar` 事实并给出 `dryRunArgv`（将执行的 `fastboot -s <序列号> flash <分区> <文件>`）与 `executorVerdict`（执行器对该 argv 的裁决，当前为 `EXTERNAL_FASTBOOT_WRITE_BLOCKED`），`executed=false`。FLASH / SIDELOAD / SET_ACTIVE 仍返回 POLICY_BLOCKED |
| 工具锁 | `tools/external-tools/lock.json` 锁 Ubuntu noble arm64 的 `adb` / `fastboot` `1:34.0.4-1build3` 与 `libc6 2.39-0ubuntu8.9`（deb 大小、SHA-256、包内文件摘要、入口软链目标）；`tools/prepare-external-tools.py` 生成 `external-tool-proof.json`（schema 2），构建任务 `verifyExternalToolProof` 要求证明与锁逐字节一致，`--verify-debs` 下载 / 复用缓存并解包核对。运行时 `ExternalToolProof` 要求入口解析到锁定文件且字节一致，失败时提示 `apt-get install` 的精确版本。旧证明里的 ld.so 摘要与当前 libc6 不符且无来源，已替换 |
| 工具分发 | 锁只核验字节，**不随 APK 分发 deb**；rootfs 里的工具仍由用户安装或容器自带，版本不符时外接能力 fail-closed |

已知限制：

- adb 退出码在部分版本上对 `connect` 恒为 0，已按输出再判；`pm` / `adb install` 失败返回 0 的情况按 `Failure [` 等输出再判，不能覆盖所有 ROM 的措辞。
- 分包全部安装，不按目标 ABI / 屏幕密度筛选；部分系统可能拒绝不匹配的 split。
- `wait-for-device` 遇到未授权目标会等到会话期限，结果为失败而不是即时提示。
- 每次业务操作前的身份复核是一次额外会话（约多一次 server 启动）。
- 无线目标的自连排除基于本机网卡地址与设备指纹，不能覆盖 NAT / 端口转发回连本机等情况；无线 adb 仍是 loopback root server 连出，第 8 节已知弱点 1 不变。

