# 外接设备：ADB 遥控 / 刷机指令参考与「驱动」移植

状态：**参考文档 + 纯逻辑移植**，2026-11-02，基于 build265（`b33e6475`）。本文指令按 AOSP platform-tools
语义核对过写法，**未在本轮真机执行**；能否成功取决于目标机型、Bootloader 状态与厂商限制。
产品化设计（授权、分级确认、预检）以外接设备设计为准
（`docs/design/external-device-20261101/README.md`）。

## 1. 相关变更索引

| 位置 | 内容 | 状态 |
|---|---|---|
| [build243 发布说明](../../releases/v0.2.1-rc.3-build243-phone-portrait-20261101.md) 第 3 节 | 手工流程：平板 root 起容器 adb（`-P 5038` nodaemon），操作 USB 外接的一加 12 做界面验收 | 已发生，无产品化 |
| 分支 `feat/external-device-debug`（`88b3157d`，基于 build252） | `extdev/ExternalDeviceHost`、`util/External{DevicePolicy,ToolPolicy,FastbootFacts,FastbootPlan,Preflight,TargetBinding,ImageProof,ImportQuota}` 等 34 个文件；独立授权、单目标绑定、只读 fastboot 预检、单次 loopback adb 会话 | 已编译、27 条单测，**未真机、未合入**，需 rebase |
| 本分支 `feat/external-usb-driver` | 接手上一行骨架；新增 USB 模式识别、SuperADB 式安装、无线目标、多步会话、工具锁 | 实验性，定向单测通过，未真机 |
| `util/DeviceShellPolicy` | **本机**禁 `fastboot`/`reboot`/`flash`/块设备写入，不因外接能力放宽 | 现行 |

## 2. 大侠阿木工具解析

来源：[一加全能工具箱](https://www.daxiaamu.com/5864/)、[驱动安装教程](https://optool.daxiaamu.com/install_adb_drivers?src=pctool)、
[工具下载目录](https://yun.daxiaamu.com/files/Tool/)。均为 Windows 程序，下面是从公开安装包的内嵌字符串还原的流程，
不是源码，也没有复制其代码或二进制。

### 2.1 「安装驱动」

`adb和fastboot驱动/` 下是 OPPO 官方 USB 驱动、Google `android_winusb.inf`（WinUSB 安装器）、
高通 32/64 位 Fastboot 驱动与 MTK USB 驱动。作用是让 Windows 按 VID/PID 把手机接口绑到 WinUSB。

**Android 宿主不需要也无法安装驱动**：内核直接把设备暴露为 `/dev/bus/usb/BBB/DDD`，App 经
`UsbManager.requestPermission` 授权（或 root 直接打开节点）即可。可移植的只有驱动 INF 隐含的那张识别表
→ 已移植为 [`ExternalUsbModes`](../../../app/src/main/java/com/deepseekharness/app/util/ExternalUsbModes.java)：

| 模式 | 识别依据 | 进入 adb/fastboot 执行链 |
|---|---|---|
| ADB（系统 / recovery / sideload） | 接口 `FF/42/01` | 是 |
| Fastboot / fastbootd | 接口 `FF/42/03` | 是 |
| 高通 EDL（9008） | `05C6:9008` | 否，仅提示 |
| MTK BROM / Preloader | `0E8D:0003`、`0E8D:2000/2001` | 否 |
| 展锐下载 | `1782:4D00` | 否 |
| 三星 Odin | `04E8:685D` | 否 |

接口三元组与厂商无关，比按 VID/PID 列表更可靠；一加 / OPPO 进 fastboot 后常以 Google VID `18D1:D00D` 枚举，
这也是 Windows 上要单独装「高通 Fastboot 驱动」的原因。

### 2.2 SuperADB v4.4.2（20260730）

`adb和fastboot工具/SuperADB.zip` = platform-tools 37.0.0 + `SuperADB.exe`（拖文件到图标上自动处理）。还原出的逻辑：

| 输入 | 处理 |
|---|---|
| `.apk` | `su -c 'id -u'` 判 root；有 root 推到 `/data/local/tmp/` 后 `su -c 'pm install -r …'` 静默安装，否则 `adb install` |
| `.apks` / `.xapk` | 解包分包；root 下 `pm install-create -r -S <总字节>` → 逐个 `pm install-write -S <字节> <会话> aNNN.apk <路径>` → `pm install-commit`，失败 `pm install-abandon`；XAPK 的 OBB 推到 `/sdcard/Android/obb/<包名>/` |
| `.zip` 含 `module.prop` | 依次探测 `magisk -V` / `ksud -V` / `apd -V`，分别 `magisk --install-module`、`ksud module install`、`apd module install`；都没有则推到 `/sdcard/` |
| 其它 `.zip` | 推到 `/sdcard/` |
| `.aab` | 拒绝：「AAB 是发布文件」 |

已移植为 [`ExternalInstallPlan`](../../../app/src/main/java/com/deepseekharness/app/util/ExternalInstallPlan.java)：只生成 argv，恒带 `-s <序列号>`，
远端命令经 `ShellQuote` 转义；模块安装标 L2（坏模块可致目标无法开机）。与原版差异：临时目录改为
`/data/local/tmp/dsha_install` 并在结束时清理；拒绝相对路径、`..`、以 `-` 开头的序列号；`.apkm`（加密）不支持。

## 3. 遥控另一台手机（L0–L1）

以下 `$S` 为目标序列号；**所有命令都在容器内对外接目标执行，不是对本机**。本机执行 `reboot`/`fastboot`
仍会被 `DeviceShellPolicy` 拦截，这是设计。

### 3.1 建立连接

USB + 宿主 root（build243 实际用法；容器自己的 server 占 5037，故另起 5038）：

```bash
# 宿主 root 起 server：只绑 127.0.0.1，绝不加 -a（会暴露到局域网）
su -c 'HOME=<App私有目录> adb -P 5038 nodaemon server' &
adb -P 5038 devices -l          # 容器内客户端
export ANDROID_ADB_SERVER_PORT=5038   # 之后可省略 -P
```

无线调试（Android 11+，两台机同一网络；目标机「开发者选项 → 无线调试 → 使用配对码配对」）：

```bash
adb pair 192.168.1.20:37123 123456   # 配对端口与配对码，每次不同
adb connect 192.168.1.20:41234       # 无线调试主页显示的连接端口
adb tcpip 5555                       # 旧方式：先 USB 连上再切 TCP，重启失效
```

### 3.2 屏幕与输入

```bash
adb -s $S exec-out screencap -p > shot.png
adb -s $S shell screenrecord --time-limit 30 /sdcard/rec.mp4
adb -s $S exec-out uiautomator dump /dev/tty     # 会抑制目标机上其它无障碍服务
adb -s $S shell input tap 540 1200
adb -s $S shell input swipe 540 1800 540 600 300
adb -s $S shell input text 'hello'                # 不支持中文，中文需输入法广播类方案
adb -s $S shell input keyevent KEYCODE_HOME       # BACK / APP_SWITCH / POWER / WAKEUP
adb -s $S shell am start -n com.example/.MainActivity
adb -s $S shell monkey -p com.example -c android.intent.category.LAUNCHER 1
```

验收常用（改的是**目标机**设置，结束后复原）：

```bash
adb -s $S shell wm size 1080x2400 ; adb -s $S shell wm density 420
adb -s $S shell wm size reset ; adb -s $S shell wm density reset
adb -s $S shell settings put system font_scale 1.3
adb -s $S shell cmd uimode night yes
```

实时投屏可用 scrcpy（需目标 `adb` 可达，容器内需图形输出，本项目未集成）。

### 3.3 应用与文件

```bash
adb -s $S install -r -g app.apk
adb -s $S install-multiple -r base.apk split_config.arm64_v8a.apk
adb -s $S uninstall com.example                    # 目标机；本机卸载被策略禁止
adb -s $S push local.bin /sdcard/Download/
adb -s $S pull /sdcard/Download/x.log .
adb -s $S logcat -d -b crash
```

## 4. 刷机指令（L1–L3）

**先读**：刷错分区、降级底层固件、在 root/修补状态下回锁都可能让目标无法开机；一加 / OPPO 部分机型
（ColorOS 16.0.3.501 起）有硬件熔丝防回滚，降级可能 9008 也救不回。镜像必须来自与目标**同机型同版本**的官方包。

### 4.1 进出各模式

```bash
adb -s $S reboot bootloader    # fastboot（Bootloader）
adb -s $S reboot fastboot      # fastbootd（用户态，刷动态分区用）
adb -s $S reboot recovery
adb -s $S reboot sideload      # 直接进 recovery 的 sideload
adb -s $S reboot edl           # 部分高通机型，多数量产机已屏蔽
fastboot -s $S reboot          # 回系统
fastboot -s $S reboot bootloader   # 同理 reboot fastboot / reboot recovery
```

### 4.2 只读预检（L0）

```bash
fastboot devices -l
fastboot -s $S getvar all 2>&1          # getvar 输出在 stderr
fastboot -s $S getvar unlocked          # 真实解锁状态；getprop 可被模块伪装
fastboot -s $S getvar current-slot
fastboot -s $S getvar is-userspace      # yes = fastbootd
fastboot -s $S getvar partition-size:init_boot_a
fastboot -s $S flashing get_unlock_ability
# adb 模式下（需 root）读真实状态：
adb -s $S shell su -c 'cat /proc/bootconfig' | grep -E 'device_state|verifiedbootstate'
```

### 4.3 刷前备份（root 目标）

```bash
adb -s $S shell su -c 'getprop ro.boot.slot_suffix'
adb -s $S shell su -c 'dd if=/dev/block/by-name/init_boot_a of=/sdcard/init_boot_a.img'
adb -s $S pull /sdcard/init_boot_a.img && sha256sum init_boot_a.img
```

### 4.4 单分区刷写（L2）

```bash
# 提取官方镜像（全量包 payload.bin）
payload-dumper-go -p init_boot,boot,vbmeta payload.bin

# GKI 设备（Android 13+ 出厂）root 修补 init_boot，旧设备修补 boot
fastboot -s $S flash init_boot magisk_patched-xxxx.img   # 刷到当前槽
fastboot -s $S flash init_boot_b patched.img            # 指定槽
fastboot -s $S --slot=all flash boot boot.img      # 选项须在子命令前
fastboot -s $S boot boot.img                             # 临时启动不写入，新机型多不支持

# 动态分区（system/vendor/product/odm/my_*）必须在 fastbootd
fastboot -s $S reboot fastboot
fastboot -s $S flash system system.img

fastboot -s $S set_active b      # 切槽，等价 --set-active=b
```

KernelSU LKM 修补（目标或容器有 ksud 时）：`ksud boot-patch -b init_boot.img`（按需加 `--kmi android14-6.1`），
输出 `kernelsu_patched_*.img` 再按上面刷入。

### 4.5 卡刷 / 整包（L2）

```bash
adb -s $S reboot sideload        # 或 recovery 菜单选「从 ADB 安装」
adb -s $S sideload ota.zip       # 末尾报 "failed to read command: Success" 属正常结束
fastboot -s $S update image-xxx.zip      # Google 格式 factory 包
fastboot -s $S flashall                  # 需 ANDROID_PRODUCT_OUT 指向镜像目录
```

厂商 `flash_all.sh`/`.bat` 不要直接交给 shell：先逐行解析成 `fastboot flash|erase|set_active|reboot` 步骤，
有无法识别的行就整份拒绝（外接设备设计 7.9）。

### 4.6 L3：清数据 / 降安全

```bash
fastboot -s $S flashing unlock          # 清空数据；一加/OPPO 需先申请深度测试
fastboot -s $S oem unlock               # 旧机型
fastboot -s $S flashing lock            # 回锁前必须恢复全部官方镜像，否则无法开机
fastboot -s $S --disable-verity --disable-verification flash vbmeta vbmeta.img
fastboot -s $S -w                       # 清 userdata 与 cache
```

## 5. 与本机安全策略的关系

- 以上命令只针对**外接目标**。在本机 `adb-shell` 里执行 `fastboot`/`reboot`/`dd of=/dev/block` 会被
  `DeviceShellPolicy` 拦截（`[POLICY_BLOCKED]`），不能靠 root 档位或改用 Python/Node 绕过。
- 外接 adb server 监听 loopback 不等于只有 DSHA 能连：本机任何应用都能连 `127.0.0.1:5038`，用完即停。
- 目标机的 `getprop` 可被伪装（实测一加 12 伪装成已锁定 / green），安全判定用 `getvar` 或 `/proc/bootconfig`。
- 本文不覆盖 9008 / BROM / Odin 协议、解锁授权绕过和固件下载。

## 6. 移植范围与状态（实验性）

整体以**实验性功能**提交：默认关闭、独立授权、未经真机验收。

- 已接入执行链（`feat/external-usb-driver`，接手 `feat/external-device-debug`）：USB 模式识别进 `/app/extdev/discover`；
  无线 `connect` / `pair`；SuperADB 式安装经 `operation=install` 原生确认后执行；确认后重启到各模式。
- 刷写：只做 `getvar` 只读预检与 dry-run（返回将执行的 `fastboot flash` argv 及执行器拒绝结论），**不执行刷写**。
- adb / fastboot / 加载器字节由 `tools/external-tools/lock.json` 锁定，构建核对证明，运行时版本不符即拒绝并提示
  `apt-get install` 的精确版本。
- 设计、边界与已知限制见外接设备设计第 14 节（`docs/design/external-device-20261101/README.md`）。
