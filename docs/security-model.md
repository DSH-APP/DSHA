# DSHA 安全模型 —— 它能碰到你手机的什么

DSHA 在手机上运行 Ubuntu 与 dsh，还可以通过授权后的 ADB 或 Shizuku 操作设备。
这份文档说清楚**边界在哪**：哪些东西 agent 碰得到、哪些碰不到、哪些要你点头才行、
以及每项权限一旦给出去意味着什么。

看不懂哪一条就当它是危险的 —— 你可以只用最小配置（不给 ADB、不给文件权限、
不开局域网），DSHA 在那种状态下依然能正常跑 dsh。

> English version: [security-model.en.md](security-model.en.md)

---

## 一句话总结

**设备命令采用始终开启的白名单。** 1.5-alpha.1 的 2026-09-09 权限修订允许查询各目录，
拒绝高危目录写入、修改系统和未知命令；普通用户应用停止前必须刷新完整应用清单。
读取能否成功仍取决于 Android、ADB 或 Root 实际授予的权限，不能强行读出系统拒绝的文件。

这层保护覆盖随包 `adb-shell`、3090 `/exec` 和 Shizuku Binder。它不是内核文件系统沙箱：
Ubuntu、插件和终端仍在 DSHA 的 Android UID 下运行；任意 Python/Node 代码、共享存储直接访问、
自写 ADB 客户端以及其它 UI 能力不受此解析器强制约束。提示词要求不得绕过，但不能称为安全隔离。
需要防御恶意插件或任意代码时，仍需单独的进程/UID 和存储隔离设计。

---

## agent 默认能碰到什么

APK 装完、什么权限都不给的情况下：

| 范围 | 能不能 | 说明 |
|---|---|---|
| 容器内的 Ubuntu 环境 | ✅ 完全可控 | 这是它的工作区。`apt install`、编译、跑服务都在这里 |
| DSHA 自己的私有目录 | ✅ 可读写 | rootfs 就在这里（`/data/data/com.dsh.client/`），Android 的应用沙箱只允许 DSHA 自己访问 |
| 共享存储（`/sdcard`） | 取决于 Android 授权 | 挂载指向手机共享存储；设备命令禁止写相册等目录，直接代码访问仍由 Android 权限决定 |
| 其它应用的数据 | ❌ 不能 | Android 应用沙箱隔离，`/data/data/<其它包名>` 读不到 |
| 系统分区 | ❌ 不能写 | 没有 root 就写不了 `/system` |
| 拨号、短信、通讯录、位置、摄像头、麦克风 | ❌ 不能 | DSHA 根本没申请这些权限，可以在系统设置里自己核对 |

> `/sdcard` 是容器挂载入口，挂载本身不会授予 Android 文件访问权限。撤销系统存储授权可收紧直接代码的访问范围。
> 容器挂载点在 `ContainerRuntime.BINDS` 里，自行构建时可以去掉。

---

## 需要你授权的能力

每一项都是**默认关闭**的，开关都在 App 里，随时可以撤。

### ADB 无线调试（「工作区」页）

设备连接具有 `shell` 用户权限（uid 2000）。随包入口只执行白名单查询、普通文件操作和经过完整分组检查的用户应用停止。
系统设置写入、安装/卸载、清应用数据被拒绝。ADB 凭据仍存在容器中，因此这层保护不能阻止任意代码自写 ADB 客户端。

- 配对码只用一次，之后靠密钥对维持，DSHA 不保存你的配对码
- 撤销方式：系统设置 →「无线调试」关掉，或撤销全部调试授权
- ADB 不能做的：读其它应用的私有数据、拿 root

### 短信读取（「设置 → 设备能力授权」，0.1.5-rc1.1 新增）

只有当前 Android 用户的严格 `content query --uri content://sms` 查询可申请这项授权。默认每条查询需确认；用户在原生界面确认预授权后，助手和插件可通过 ADB 查询号码、正文、时间等字段，其中可能包含验证码。关闭后，后续查询恢复逐次确认。该授权不从系统备份或换机迁移恢复。

预授权不允许发送、修改、删除短信，不扩展到通讯录等其他内容提供者，也不能替代 Android 的实际访问权限。Shizuku 通道暂不执行这类敏感查询。授权约束作用于随包设备桥，不构成阻止任意容器代码的独立 UID 沙箱。

### root shell（「配置」页，默认关）

随包设备命令使用 Root 时仍受同一白名单保护，不能放行高危目录或系统修改；容器的 root 不等于手机 Root。

- 需要设备本身已 root（KernelSU / Magisk），DSHA 不提供也不申请 root
- 开关是 `allow_root_shell`，默认 false
- 除非你清楚自己在做什么，别开

### 「所有文件访问」（首次启动会引导）

**给出去意味着什么**：DSHA 可以读写整个共享存储。它的用途是把对话数据放到
`Documents/dshdata`，这样**卸载重装数据不丢**。

- 拒绝的后果：数据留在私有目录，**卸载即丢**（自检会明确告知当前处于哪种状态）
- 挂载路径可达不等于获得读取权限；系统授权会改变 DSHA 及其容器代码能访问的共享存储范围

### 悬浮窗（「配置」页的流式悬浮条，默认关）

**给出去意味着什么**：DSHA 可以在其它应用之上画东西。

- 它只用来显示 AI 输出和危险命令的批准按钮，不读屏、不截屏
- 内容会显示在屏幕上 —— **旁边的人也看得见**，这也是它默认关闭的原因

### 局域网访问（默认关）

**打开意味着什么**：同一个 Wi-Fi 下的设备可以访问你手机上的 dsh Web UI。

- 有 token 鉴权，**fail-closed**：token 缺失或不对一律拒绝，不存在「token 为空就放行」
- token 首次命中后写成 `SameSite=Strict` Cookie，之后不再出现在 URL 里 —— 防止它随外链泄漏
- 公共 Wi-Fi 下别开。token 强度够，但你的手机会在网络上暴露一个端口

---

## agent 想干危险的事时会发生什么

原生 `DeviceShellPolicy` 解析单条命令并重建参数列表，不执行用户提供的 shell 脚本。

- 允许目录查询与读取；根目录、顶层系统目录、DCIM、Pictures、Android/data、Android/obb 及其子目录只读。
- 普通共享存储子目录（例如 Download）与 `/data/local/tmp` 内的明确文件操作允许。复制只读源文件到普通目录允许；移动源文件属于写入，会被拦截。
- 拒绝 dd、分区/格式化工具、块设备写入、SELinux 修改、系统设置/属性写入、挂载、刷机和不认识的命令。
- 拒绝脚本、管道、重定向、变量/命令展开、模糊进程匹配及不能确认的参数。实际写入前核对规范路径和符号链接；检查失败即拒绝。
- 停止前获取全部应用并分用户/系统两组；普通用户应用直接停止，系统应用、系统 UID、DSHA/Shizuku 自身及未知目标被保护。PID 先映射为唯一普通应用，再按包名停止，避免 PID 复用误伤。
- 每批目标全部验证后再执行。部分目标受保护时整批拒绝。旧确认开关、Root 和 `DSH_INTERNAL` 均不能跳过随包入口校验。

拦截返回 `[POLICY_BLOCKED]` 和退出码 126，不弹“仍然允许”的确认框。旧 `/confirm` 只对已知只读查询返回 YES；文件写入和应用停止必须通过能现场核验的执行入口。
原有无障碍点击、分享等 App 能力的授权机制独立存在，不属于设备 shell 白名单的强制边界。
详见[本次权限修订与验证记录](device-permissions-2026-09-09.md)。

---

## 你的密钥和数据放在哪

| 东西 | 放在哪 | 怎么保护 |
|---|---|---|
| DeepSeek API key | App 的 SharedPreferences | Android Keystore 加密（AES/CBC），**密钥不出 Keystore**，别的应用拿不到 |
| 备份里的 API key | 备份包内 `.dsh/.dsha-apikey` | 同一把 Keystore 密钥加密。**换设备后解不开**（这是设计如此，不是 bug），可以在「配置」页关掉「备份包含 key」 |
| 对话记录 | `Documents/dshdata`（公开）或私有目录 | ⚠️ 放在公开目录时，**任何拿到存储权限的应用都能读**。这是「卸载不丢数据」的代价 |
| 备份文件 | `Download/DSHA/` | ⚠️ 同上，公共目录。里面有你的**全部对话记录**。分享这个文件前请想清楚 |
| dsh 自己的凭据 | `.dsh/.credentials.yaml` | 刻意留在私有目录，不迁到公开区。但它**会进备份** |
| 3090 桥 token | `.dsh/.bridge_token` | 私有目录，权限 600。**属于这台机器，已从备份排除**（`LOCAL_DEVICE_FILES`）|

**我们不收集任何数据。** DSHA 没有遥测、没有统计上报、没有崩溃收集。
唯一的对外网络请求是：你点检查更新时访问 GitHub API、热更新脚本时访问
raw.githubusercontent.com、以及安装环境时访问你自己选的那个镜像源。
API 请求由 dsh 直接发给 DeepSeek，不经过我们任何地方。

---

## 你怎么验证装到手机上的 APK 是真的

从 GitHub 手动侧载 APK 的分发方式，来源验证是最该做的一环：

```bash
# 1. 哈希（证明文件没被改过）
sha256sum -c deepseekharness-arm64-vX.Y.Z.apk.sha256

# 2. 构建证明（证明它来自本仓库这个 tag 的 CI，不是别人重打的包）
gh attestation verify deepseekharness-arm64-vX.Y.Z.apk --repo qiannianhuanxiang/DSHA
```

签名证书指纹在发布流水线里逐次核对，**不匹配直接中止发布** ——
一个签名不对的包用户根本装不上（Android 只允许同签名覆盖安装），
发出去比发布失败糟得多。

---

### 桥的凭据护栏（v0.1.5 起）

`/app/export` 与 `/app/readfile` 原先接受任意绝对路径。已确认的攻击链是：

```
容器内一行 curl → /app/export?path=/root/.dsh/.bridge_token
  → 文件落到 /sdcard/Download/DSHA/（任何有存储权限的应用可读）
  → 另一应用拿到桥 token → 读屏 / 点按 / 输入 / 执行设备命令
```

同样可被带走的是 `.dsh/.credentials.yaml`（API key 与会话密钥）与 `.dsh/adbkeys/adbkey`（ADB 私钥）。

现在的判据在 `util/BridgePathPolicy`（纯逻辑，有单测）：

- 拒绝 `.dsh`、`.ssh`、`.android`、`.aws`、`.kube`、`.dsha-*` 等凭据区；空值与相对路径一律拒绝；
- 目录穿越、重复斜杠、反斜杠等混淆写法先规范化再判；
- 字符串判据之后再用 `getCanonicalPath()` 复核，挡住「先建软链接指向凭据」的绕法；
- 列出上层目录时跳过凭据区条目（名字本身也是情报）。

桥的正当用途（`/app/export?path=/root/report.md`）不受影响。

### 备份里的本机凭据（v0.1.5 起）

备份包会落到 `Download/DSHA/`，任何有存储权限的应用都能读。此前打进包里的
本机设备凭据因此等价于「公开」：

| 文件 | 处理 |
|---|---|
| `.dsh/.bridge_token` | 整文件排除（本机 loopback 桥的共享凭据）|
| `.dsh/.anonymous-user-id` | 整文件排除（本机标识）|
| `.dsh/.credentials.yaml` | **字段级剔除**：删 `records.client-connection/browser-session`，保留 `refs`（用户 API key）|

字段级而不是整文件的原因：`refs` 里是用户换机后仍要用的 API key，
`records` 里那条是本机登录 cookie 的 HMAC 签名密钥 —— 恢复后旧 cookie 早已失效，
dsh 会在记录缺失时自动重新生成（`dsh-client-connection` 的 `initializeSecret()`）。

恢复流程在提交成功后会调用 `HttpShellService.resetTokenAfterRestore()` 让本机
凭据重新对齐 —— 老备份（仍带别的机器的 token）恢复后不会再出现
「需要 token，请在 DSHA 应用内打开」。

## 已知的弱点

不藏着：

| 弱点 | 现状 |
|---|---|
| `danger-full-access` | Android sepolicy 挡住 bubblewrap，dsh 没有沙箱。容器内的 agent 对容器有完全控制权 |
| 3090 桥无 Android 权限保护 | 桥绑 `127.0.0.1/::1`，**同一台手机的任意应用都能连接**，唯一防线是随机 token。token 一旦泄露（例如经 `/app/export` 导出到公共目录），该应用即可读屏、点按、输入与执行设备命令 |
| 设备入口保护不是 OS 沙箱 | 随包入口采用原生白名单；任意容器代码、可读 ADB 凭据及直接共享存储仍由 Android 沙箱限定 |
| 备份在公共目录 | 全部对话记录明文躺在 `Download/DSHA/`，任何有存储权限的应用可读 |
| ~~`.credentials.yaml` 进备份~~ | **已修**：按字段剔除本机记录，保留用户的 API key。备份包里的 `refs`（`DEEPSEEK_API_KEY` 等）仍在，`records.client-connection/browser-session`（本机登录 cookie 的签名密钥）被剔除；恢复后由 dsh 的 `initializeSecret()` 自动重新生成 |
| `/sdcard` 默认可达 | agent 默认就能读相册和下载目录，目前没有开关 |
| 凭据区已加桥护栏，但备份仍会带走 | `/app/export`、`/app/readfile` 现在拒绝 `.dsh`、`.ssh`、`.android` 等凭据区（见下）；**但备份包仍会把 `.credentials.yaml` 写到公共目录**，这一项还没解决 |
| 签名密钥待轮换 | 线上包用的是一把 debug keystore（历史原因，换掉会让所有人无法覆盖升级）。密钥轮换按 APK Signature Scheme v3 rotation 单独排期 |

发现别的问题请开 issue，或者到 QQ 群 975836806 说。安全相关的问题优先处理。
