# GitHub 凭据原生化（T50 设计稿）

状态：**设计稿，未实现，待用户确认**。

参考基线：build315 / `8f2c99ba` / `io.github.sunsetrne.dsha` / 单一 Standard。
核对范围：现有 `KeyVault`、`ConfigStore`、3090 `HttpShellService`、`BridgeCredentialFiles`、`credential-paths.json`，以及 T49 的受管 `gh` 包装脚本与 GitHub 代理边界。

本稿只定义方案，不改生产代码、不改 `docs/security-model.md`、不改 T49 分支资产。用户确认后再拆实现任务；实现前必须重新核对 `origin/main` 与 T49 的最终提交。

## 0. 结论

1. GitHub 登录由 Android 壳完成。首选 GitHub OAuth Device Flow：壳向 GitHub 请求 device code，用户在浏览器确认，壳轮询并以 `/user` 回读校验账号；容器不执行 OAuth，refresh token 不经过桥。按需 access token 只在受管 `gh`/Git helper 请求时经过 broker。
2. 使用专用 GitHub OAuth App 的 `client_id`，不借用 `gh` CLI 自带的 client id；设备流不需要把 `client_secret` 放进 APK。client id 不是凭据，但应由签名 APK 固定，不能由容器或远端配置覆盖。若 Q3 通过，`offline_access` 由壳固定追加到 profile scope，容器不能自行追加。
3. access token 与 refresh token 一起放 Android Keystore AES/GCM 密文文件，密文写入 `Context.getNoBackupFilesDir()` 下的受控文件；不放 rootfs、SharedPreferences、DocumentsProvider、用户导出或备份。Keystore 别名与现有 `dsh_api_key` 分开。
4. 容器只在启动一次 `gh` 或 Git HTTPS 请求时经 3090 桥按需取得 access token。桥只返回 access token，不返回 refresh token；`gh` wrapper 不把 token 写入用户可见 stdout/stderr、命令参数、hosts.yml、shell history 或诊断文件，Git helper 仅按 Git credential protocol 写入 Git 消费的 password 字段。
5. 新增的 GitHub 路由使用已有 `X-Token` 桥鉴权，并采用 POST、严格主机白名单、固定 consumer 枚举和当前 bridge generation 检查。增加端点本身不提高 `BRIDGE_PROTOCOL`；在 `/app/version` 增加能力标记，调用方按能力探测。
6. 这不是同 UID 沙箱。guest、终端和插件与 DSHA 共享 Android UID，仍可能读取宿主私有文件、调用同一桥、使用 `app_process`/Binder 或观察同 UID 进程环境；原生化主要堵住明文 token 持久落盘于 rootfs、备份、模型输出和普通外部文件的路径，运行中的 wrapper/helper 仍会在 guest 进程内短暂持有 access token。文案和安全模型不能宣称“容器无法取得 token”。
7. 旧 `root/.config/gh/hosts.yml` 不自动静默导入。设置页提供用户主动的“迁移旧 gh 登录”；验证、密文读回和提交成功后才删除精确的旧 GitHub token 文件。用户拒绝或格式不受支持时保留原件并明确提示，不能假装已原生化。

## 1. 当前源码事实

| 事实 | 当前边界 | 设计影响 |
|---|---|---|
| `KeyVault` | Android Keystore AES/GCM；默认别名 `dsh_api_key`；密文由调用方保存 | GitHub 必须用独立别名和独立存储，不得复用 API Key 的迁移/备份语义 |
| `ConfigStore` | API Key 在 SharedPreferences 中保存 Keystore 密文，并注册已知秘密供日志脱敏 | GitHub token 不进入该键空间；新增 `GitHubCredentialStore`，元数据和 token 同一密文载荷 |
| 3090 桥 | IPv4/IPv6 loopback；单个 `X-Token` 头；URL 中的 `token`、重复头和 Cookie 均拒绝 | GitHub 路由复用桥身份，不能把 token 放 URL；请求和响应都要禁止缓存 |
| `.bridge_token` / `.bridge_headers` | 容器可读，桥启动时由宿主对账并以 0600 写入 | 容器能调用 GitHub 路由是既有同 UID 能力，不把桥 token 当作隔离边界 |
| `BridgeCredentialFiles` | token/headers 原子发布、0600、读回核验；`credential-paths` 已把机器状态列为外部/备份排除项 | GitHub 密文文件不走该 rootfs 机制；旧 `hosts.yml` 仍需迁移与备份排除设计 |
| `HttpShellService` | `BridgeRoutes` 精确路由；`/app/version` 和 `/app/help` 供能力探测；默认处理多数读操作 | 新路由必须显式登记、显式方法检查、独立正文上限和错误码，不能借 `/exec` 或 `/app/readfile` |
| T49 | 将把 `gh` 与 GitHub 代理作为 RuntimeTools 受管资产写入；当前登录仍是 `gh auth login --web` + `hosts.yml` | T50 提供后续切换契约，不改 T49 代码；T49 最终 wrapper 应调用本稿 broker |
| `credential-paths.json` | 已拒绝 `root/.config/gh` 等 guest 凭据路径；规则生成和备份实现有单测 | 实现阶段补齐旧 hosts 的具体迁移/恢复断言，不能只依赖字符串黑名单 |

### 1.1 现状风险

T49 的当前兼容路径会让 `gh auth login --web` 在容器内完成登录，并把 OAuth token 放在 `root/.config/gh/hosts.yml`。该文件可能被同 UID 的 agent、插件、终端、备份输入或用户主动文件操作读取。桥的 `X-Token` 只能保护桥路由，不能阻止同 UID 代码调用桥或读宿主数据。

本稿不把现有 token 迁移成“另一个可读的 rootfs 文件”，也不把 `GH_TOKEN` 写入全局运行环境。包装脚本的 token 仅存在于一次子进程调用的内存/环境中，暴露面仍需照实记录。

## 2. 目标与非目标

### 2.1 目标

- 新环境首次登录由壳完成，不需要模型编写 OAuth 或 YAML 脚本。
- access/refresh token 不以明文落在 rootfs；不进入备份、用户导出、DocumentsProvider、普通桥读文件和持久日志。
- 设置页能显示账号状态、授权范围、access token 的过期状态；不显示 token、refresh token 或完整敏感标识。
- 设置页能清除本机凭据，并能跳转 GitHub 授权设置让用户撤销远端授权。
- `gh` 和 Git HTTPS 能按需取得 token；`gh` 代理仍只负责网络兼容和域名白名单，不保存 token。
- access token 过期时由壳在单飞锁内刷新；refresh token 轮换期间不并发重放。
- 迁移失败、Keystore 暂时不可用、设备锁定、网络未知结果都保留原记录并给出可行动状态。

### 2.2 非目标

- 不把同 UID 的 guest、插件或终端变成安全沙箱。
- 不在本任务实现 GitHub App 注册、组织策略、GitHub Enterprise 全部主机、SSH 私钥或 PAT 粘贴导入。
- 不让容器选择任意 OAuth scope、任意上游主机或任意代理目标。
- 不通过 `gh` CLI 的默认 OAuth client id 登录。
- 不在 bridge 中提供通用“读取任意 Keystore secret”接口。
- 不把 token 注入所有 shell/PTY；只为受管 `gh` 和 Git helper 的一次调用取得。
- 不自动替用户在 GitHub 远端撤销 OAuth App。没有 client secret 的原生设备流不能安全地调用需要应用密钥的 revoke API；本机删除和打开 GitHub 授权设置必须分开说明。

## 3. 推荐架构

```text
用户
 │  Settings → GitHub → 设备登录
 ▼
DSHA Android 壳
 ├─ GitHubAuthManager
 │   ├─ OAuth Device Flow（github.com）
 │   ├─ /user 身份校验
 │   └─ refresh 单飞 / 过期处理
 ├─ GitHubCredentialStore
 │   ├─ Android Keystore: dsha_github_credential_v1
 │   └─ noBackupFilesDir/github-credential-v1.enc
 └─ 3090 GitHubCredentialBroker
     ├─ /app/github/status（只返回非秘密状态）
     ├─ /app/github/login（只返回设备码状态，不返回 token）
     ├─ /app/github/credential（POST，返回一次按需 access token）
     └─ /app/github/logout（本机清除，需用户确认语义）
              ▲
              │ X-Token + 当前 bridge generation
              │
rootfs / RuntimeTools
 ├─ T49 管理的 /root/dsh-bin/gh wrapper
 ├─ dsha-git-credential helper
 ├─ gh-proxy.mjs（只做 GitHub 域名 CONNECT/恢复）
 └─ gh.real / git
```

桥返回 token 是为了兼容 `gh` 的 `GH_TOKEN` 输入合同；这不是理想的隔离设计，而是现有 CLI 与 Android 壳之间的最小适配面。未来若使用原生 GitHub API 代理，可再取消 token 返回，但不在 T50 首次实现范围。

### 3.1 身份和状态对象

`GitHubCredentialStore` 的加密载荷建议使用版本化、严格大小限制的二进制/JSON envelope；下面是逻辑字段，不代表明文文件格式：

```json
{
  "schema": 1,
  "host": "github.com",
  "accountId": 123456,
  "login": "example",
  "scopes": ["public_repo"],
  "accessToken": "<secret>",
  "accessExpiresAt": 0,
  "refreshToken": "<secret-or-absent>",
  "refreshExpiresAt": 0,
  "updatedAt": 0
}
```

规则：

- `host` 目前只能是 `github.com`；不允许容器传入任意替代 host。
- `accountId` 是 `/user` 校验后的数值身份，不能以调用方声称的 login 覆盖。
- `scopes` 以 GitHub 返回的实际范围为准；请求范围和实际范围都不得只看 UI 文案。
- 无 refresh token 的响应是合法状态（例如该 OAuth App/实例未启用过期 token）；不能把“没有 refresh token”当作格式损坏。首版只支持 `github.com`，不据此扩展 Enterprise host。
- access/refresh token 与非秘密元数据一并加密，避免 SharedPreferences、日志和备份暴露账号关联信息。
- 读取接口返回 `CredentialRead` 风格的状态：`MISSING`、`AVAILABLE`、`TEMPORARILY_UNAVAILABLE`、`REAUTH_REQUIRED`、`REFRESH_UNKNOWN`、`UNREADABLE`。暂时不可读不自动删除密文。

## 4. 原生登录流程

### 4.1 首选：OAuth Device Flow

GitHub 官方文档说明 device flow 适用于没有浏览器内嵌能力的 CLI/headless 客户端；device code 默认约 15 分钟有效，轮询间隔由服务端返回，`slow_down` 会增加间隔。access token 过期模式下通常同时返回 refresh token；access token 默认 8 小时、refresh token 不使用约 6 个月。实现必须按响应字段和错误码处理，不能硬编码这些期限为永恒合同。

流程：

1. 用户在设置页点击“登录 GitHub”，选择权限档位。默认只请求最小公开仓库档位；需要私有仓库、Actions 或 gist 时分别显示范围并要求用户确认。
2. 壳向固定 `https://github.com/login/device/code` 发 HTTPS POST，只带 APK 固定的专用 `client_id`、native UI 选定的 profile scope，以及（Q3 通过时）由壳固定追加的 `offline_access`。禁止通过 guest、3090 或 T49 代理请求 OAuth。
3. 壳校验响应结构、`device_code`/`user_code` 长度、`verification_uri` 主机与 HTTPS；UI 显示 user code，并用 `ACTION_VIEW` 打开 `https://github.com/login/device`。不把 device code 写日志或复制到模型输出。
4. `GitHubAuthManager` 在后台按服务端 `interval` 轮询 `https://github.com/login/oauth/access_token`，上限为 device code 的有效期；`authorization_pending` 继续，`slow_down` 使用服务端新间隔，`access_denied`、`expired_token`、`device_flow_disabled` 变成明确失败状态。界面销毁不能让轮询线程泄漏，应用停止时取消并清理 device code 内存。
5. 收到 token 后立即用 Android 网络栈向 `https://api.github.com/user` 发 Bearer 请求，并核对返回的 account id/login。登录账号改变时先在 UI 显示新账号，不静默覆盖旧账号。
6. 只有 access token、可选的 refresh token、账号信息和实际 scopes 都通过校验，且密文文件原子写入并读回后，才发布 `READY` 状态。写入失败保留旧凭据，不报告“登录成功”；若账号与现有账号不同，先要求用户确认再替换。
7. 登录完成后清理 device code、HTTP body、临时 byte array；不把 OAuth 响应写入活动日志、crash report、桥响应或 rootfs。

设备流不需要 client secret，适合 APK；若未来改用 authorization-code flow，必须使用 PKCE、不可预测 state、固定 redirect URI，并重新评审，不能把 client secret 编进 APK。

### 4.2 Scope 档位（推荐，待用户确认）

| 档位 | 初始 scope | 用途 | 风险/限制 |
|---|---|---|---|
| 公开仓库 | `public_repo` | 公开仓库的 clone、issue、PR 等 | 不访问私有仓库；仍允许该 OAuth App 范围内的写操作 |
| 私有仓库 | `repo` | 私有仓库及公开仓库的 GitHub 工作流 | OAuth scope 较粗，涵盖用户可见仓库；只在用户明确选择时请求 |
| 私有仓库 + Actions | `repo workflow` | 需要编辑/触发 Actions workflow 的场景 | `workflow` 额外扩大写入范围，单独确认 |
| gist | `gist` | 仅需要 gist 的场景 | 默认不请求；不与仓库档位隐式合并 |

不请求 `delete_repo`、`admin:*`、`write:packages`、组织管理等高危 scope。scope 一旦扩大，界面显示新的范围并再次确认；容器命令不得临时追加 scope。若未来改 GitHub App，应以安装级细粒度权限替代 OAuth App 的粗范围，但这属于另一个设计任务。

### 4.3 Refresh 与并发

- access token 剩余时间小于 60 秒时，broker 进入 `refresh` 单飞锁；同一时刻只允许一个 refresh 请求，其他请求等待同一结果。
- refresh 请求只发送固定 client id、`grant_type=refresh_token` 和当前 refresh token；不把 scope 参数带入刷新，scope 以 GitHub 返回值为准。
- 收到新 pair 后先验证 `/user` 的 account id 与已存账号一致，再原子替换整个 envelope。refresh token 是轮换的，不能先覆盖旧值再验证。
- refresh HTTP 超时/连接中断属于结果未知：不得盲目重复使用或重放 refresh 请求；状态置 `REFRESH_UNKNOWN`，保留密文现场但停止向容器发 token，提示用户重新登录或由实现阶段按 GitHub 返回合同恢复。
- 明确收到 `bad_refresh_token`、`invalid_grant` 或身份不匹配时，原 pair 不能继续使用，状态置 `REAUTH_REQUIRED`；是否删除原密文取决于用户是否点击“清除”，不能在异常路径静默擦除。
- access token 被 GitHub API 返回 401 时最多触发一次 broker refresh；不得重放原来的 gh/git 业务命令。业务命令结果仍由 gh/git 自己报告，UNKNOWN 规则不变。

## 5. Keystore 与文件存储

### 5.1 存储合同

推荐新增 `GitHubCredentialStore`，复用 `KeyVault` 的 AES/GCM 习惯但不复用 API Key 别名：

- Keystore alias：`dsha_github_credential_v1`，只允许 `ENCRYPT|DECRYPT`，随机 GCM IV，KeyStore 密钥不可导出。
- 密文目标：`Context.getNoBackupFilesDir()/github-credential-v1.enc`，父目录和文件按 `NOFOLLOW`/regular-file 核验，文件 0600，正文设定小上限（例如 32 KiB）。
- 写入：在同一 no-backup 目录创建随机临时文件，写入 envelope、flush、fsync、原子 rename、同步目录，再重新打开并解密读回；任一步失败保留旧文件。
- 读取：设备锁定、KeyStore 暂时错误、密文认证失败分别映射为不同状态；认证失败不生成替代密钥，不把旧记录当成“未登录”。
- 删除：用户确认后先删除密文并同步目录，再按需要删除 Keystore alias；删除结果未知时报告未知，不自动重新登录或重放删除。
- token 只在 broker 发放前短暂解密；优先使用 byte array 并在 finally 清零。Java `String`/子进程环境无法完全避免复制，残余风险必须写入验收记录。

`noBackupFilesDir` 不是同 UID 的安全边界；它的作用是让 Android 普通备份/设备迁移不复制该文件。Android Keystore key 也不应假设能跨设备恢复；恢复后出现“密文在、key 不可读”时必须保留并提示重新登录，而不是换 key 解密。

### 5.2 备份、导出和文件入口

- `GitHubCredentialStore` 的文件永不进入 `BackupScope`、用户 v5 导出、自动副本、DocumentsProvider、`/app/readfile`、`/app/export` 或 rootfs snapshot。
- `noBackupFilesDir` 不是 backup 规则的唯一证明；实现阶段要在 Android backup 规则、`BackupScope`、`DataRootPolicy`、`BridgePathPolicy` 和 DocumentsProvider 测试中分别断言。
- `credential-paths.json` 继续把 `root/.config/gh` 作为 guest 凭据路径拒绝；实现迁移后要加一条旧 `hosts.yml` 含 token 时“备份不带、恢复不落地”的回归。不要把 GitHub 密文宿主路径加入允许导出表。
- 旧 hosts 的 token 若尚未迁移，用户明确导出/备份时也不应随新数据带走；处理方式是排除并在 UI 显示“旧登录尚未迁移”，不是把 token 明文复制到新归档。
- 日志脱敏使用 `SensitiveData`；token/refresh token/device code 不作为诊断字段，不把带 token 的 GitHub URL、Authorization header 或 JSON body传给活动日志。

## 6. 3090 桥接口契约

以下名称是设计契约，用户确认后再落到 `BridgeRoutes`/`HttpShellService`。所有响应采用 `Cache-Control: no-store`，错误正文不得回显 token、refresh token、device code 或 Authorization header。

### 6.1 `/app/github/status`

- 方法：GET；只读，无正文。
- 鉴权：现有单个 `X-Token`，当前 bridge generation；不能用 URL 参数、Cookie 或第二个 header。
- 返回：JSON 非秘密状态，例如：

```json
{
  "schema": 1,
  "state": "READY",
  "host": "github.com",
  "login": "example",
  "accountId": 123456,
  "scopes": ["public_repo"],
  "accessExpiresAt": 0,
  "refreshExpiresAt": 0
}
```

- `state` 允许 `MISSING`、`LOGIN_PENDING`、`READY`、`EXPIRED`、`REAUTH_REQUIRED`、`REFRESH_UNKNOWN`、`UNAVAILABLE`。不返回 access/refresh token、密文路径或 Keystore alias。
- `accountId`、login、scope 也属于用户数据；只有已鉴权的桥客户端读取，日志仍不记录完整 payload。

### 6.2 `/app/github/login`

- 方法：POST；正文仅允许 `{"profile":"public|private|private-workflow|gist"}`，上限很小；profile 由 native policy 再校验，不能提交任意 scope。
- 作用：启动或返回同一 native login session 的非秘密状态，供设置页和 T49 的兼容 `gh auth login --web` 使用；不能直接由容器替用户选择高权限 scope。
- 成功只返回 `requestId`、`verificationUri`、`userCode`、`expiresAt`、`pollAfterMs` 等设备流状态；不返回 token。`userCode` 视为短期敏感值，不写日志，不进入持久状态。
- 推荐 UI 作为唯一正常入口；桥调用应导致 native UI/通知出现，并由用户在设备上完成确认。没有用户完成授权时，容器不得把 `authorization_pending` 当登录成功。
- 只允许一个 login session；旧 session 失效后不能复用 device code。桥停止、用户撤销或超时会取消 session。

### 6.3 `/app/github/credential`

- 方法：POST；正文仅允许：

```json
{"consumer":"gh|git","host":"github.com"}
```

- `consumer` 只是兼容/诊断字段，不是安全身份；host 必须精确是 `github.com`，拒绝 IP、localhost、任意子域名和重定向主机。
- 壳先读状态，必要时按 §4.3 refresh，再在响应中只给一次 access token。绝不返回 refresh token、Keystore 密文或 token 文件路径；未登录返回稳定错误码，不返回 GitHub 原始错误 body。
- 返回头和 JSON 都禁止缓存；桥服务不把响应正文写入日志。wrapper 读取 body 后立即把响应 buffer 清零，不能 `tee` 到 stdout/stderr。
- 该端点必须有当前桥 generation、运行任务和桥鉴权检查；桥停止前已发出的 token 无法从 GitHub 侧按 lease 撤销，因此响应中不宣称“token 只对本次命令有效”。
- 一次业务请求的 token 取得不是业务命令确认。`gh`/`git` 的写操作仍受 GitHub 授权和现有用户/模型工作流影响；本端点不凭空增加设备命令确认。

### 6.4 `/app/github/logout`

- 方法：POST；正文 `{"mode":"local|local-and-open-settings"}`。
- 容器调用只能请求 native UI 确认，不能静默擦除或伪造远端撤销；设置页点击是规范入口。
- `local` 原子删除本机密文，`local-and-open-settings` 在本机删除后打开 `https://github.com/settings/connections/applications/<client_id>`，由用户在 GitHub 页面撤销 OAuth App 授权。
- 成功响应只表示本机状态已提交/页面已请求打开，不表示 GitHub 远端授权已撤销；未知结果不自动重试。

### 6.5 能力探测和错误

- `BRIDGE_PROTOCOL` 保持当前值；`/app/version` 增加固定键 `GITHUB_CREDENTIALS=1`，旧插件看到未知键应忽略。需要改变现有字段含义或删除端点时才另行升协议。
- `/app/help` 只说明端点用途和“token 不得直接打印/记录”的调用合同，不输出实际 token 或可复制的秘密示例。
- 建议错误码：`GITHUB_AUTH_UNAVAILABLE`、`GITHUB_NOT_CONFIGURED`、`GITHUB_REAUTH_REQUIRED`、`GITHUB_REFRESH_UNKNOWN`、`GITHUB_HOST_UNSUPPORTED`、`GITHUB_METHOD_REQUIRED`、`GITHUB_PROFILE_INVALID`、`GITHUB_LOGIN_BUSY`。
- `401/405/409/503` 仍按桥现有 HTTP 语义；错误正文固定为可行动的短消息。不要将 GitHub 的 `error_description` 原样回传给模型日志。

## 7. T49 包装脚本和 Git 的接入合同

T50 不修改 T49 的资产实现；下面是 T49 在 T50 用户确认并实现后需要对齐的接口。

### 7.1 `gh` wrapper

- 默认路径不读 `root/.config/gh/hosts.yml`，也不接受容器内手工设置的 token 作为 native credential 的证明。
- 普通 `gh` 调用先请求 `/app/github/credential`，在 wrapper 进程中设置 `GH_TOKEN`，再 `exec` 真正的 gh；不把 token 作为 `env` 命令的 argv，不用 `set -x`，不写 token 到诊断。进程结束前 `unset` 只能减少 shell 留存，不能消除同 UID 进程观察风险。
- `gh auth status` 读取 `/app/github/status`，只显示 login/scope/expiry；不调用 `gh` 自己的 hosts parser，不把 token 打到屏幕。
- `gh auth login --web` 转为 `/app/github/login` 的 native flow，并把 verification URL/code 显示给用户；它不在容器内请求 OAuth token。当前 T49 过渡实现仍可保留旧行为，切换前必须给出显式版本迁移说明。
- `gh auth logout` 调用 `/app/github/logout`，由壳完成本机删除；不在 guest 里直接删除宿主凭据或假装远端已撤销。
- `DSHA_GH_RAW=1` 只能表示调试/兼容请求，不得让正式 wrapper 绕过 GitHub 域名白名单、直接读取 hosts token 或把 token 写入日志；若保留该变量，需在设置页/安全模型明确其同 UID 风险。

### 7.2 Git HTTPS helper

提供受管 `dsha-git-credential` helper：

- 只接受 Git credential protocol 的 `protocol=https`、`host=github.com`；拒绝 SSH、任意其他 host、带 query 的凭据请求和把 token 放进 URL 的形式。
- helper 经桥取得 access token，只向 Git 消费的 credential-protocol stdout 输出（这是唯一允许 token 出现在 stdout 的例外；不能写用户可见 stderr、日志或其它 stdout）：

```text
protocol=https
host=github.com
username=x-access-token
password=<access token>

```

- helper 不打印日志，不写缓存，不修改用户 `.gitconfig`；RuntimeTools 通过受管环境/配置注入 helper，且用户显式设置的其他 host helper 不被覆盖。
- `git ls-remote`、clone、fetch、push 的业务返回仍由 Git 决定；helper 不在一次 UNKNOWN 后自动重复业务命令。token 获取失败只返回 credential unavailable，不伪造空密码。
- GitHub proxy 只负责 T49 规定的 DNS/TLS/CONNECT 兼容和允许域名；它不能保存 access/refresh token，不把 token 加到 URL 或代理日志。

## 8. 旧 hosts.yml 迁移

### 8.1 默认策略

旧凭据已在容器中可读，继续保留会持续违反目标；但自动读取并删除用户凭据也可能造成不可逆误操作。因此采用“发现即提示，用户主动迁移”：

1. 壳在启动/设置页只读取文件类型、大小、canonical 身份和是否存在，不向容器返回正文；状态显示 `LEGACY_PRESENT`。
2. 用户点击“迁移旧 gh 登录”后，先取得现有 RuntimeTasks/维护屏障，避免 gh、PTY、备份同时写 hosts；不要在 guest 中运行 YAML parser。
3. 宿主侧使用有界、严格的 GitHub hosts 结构读取器，只接受单一 `github.com` 记录、预期键集合、无 alias/重复键/超长值；不接受任意 YAML 指令、脚本或多文档。解析失败不猜测字段，不删除原件。
4. 读出的 token 立即以 Android 网络栈调用 `/user`，核对 token 类型、账号 id 和 host；成功后按 §5 原子写入密文并解密读回。
5. 只有密文提交和 readback 成功，且用户再次确认，才原子改写旧 `hosts.yml`，从已核验的 GitHub 记录去除 `oauth_token`；只有文件只剩该记录且无其它需保留字段时才可删除文件。改写/删除只针对受核验的普通文件，不跟随链接、不递归删除 `.config/gh`。若文件包含其他 host 或未知字段，默认不改整文件，进入 `LEGACY_REVIEW_REQUIRED`，由用户重新登录/手动清理。
6. 迁移完成后 wrapper 使用 native broker；旧 hosts 缺失不应让 `gh` 回退到容器登录。迁移失败保留原件并显示“仍有明文旧凭据”，不能报告 READY。

### 8.2 新登录与旧文件并存

- 新 native 登录成功时，如果检测到旧 GitHub hosts，仍需用户确认是否删除旧明文记录；不因新密文存在就静默删除未知主机的凭据。
- 旧文件可能属于旧账号。`/user` 返回的账号必须展示并由用户确认；不把“token 可用”当作用户同意绑定新账号。
- 设备恢复/覆盖更新不从归档恢复 native token。覆盖安装保持 Android app 私有目录和 Keystore 的实际系统行为，但实现不得依赖归档或版本号推导 token 可恢复。

## 9. 设置页行为

设置页增加 GitHub 账户区域（建议归入配置/接入，不改现有 API Key 键名）：

| 状态 | 显示 | 可用动作 |
|---|---|---|
| 未登录 | `未连接` | 登录；选择权限档位 |
| 登录中 | 账号代码、剩余时间、取消 | 返回登录页；重新生成 code 前先取消旧 session |
| 已连接 | login、授权范围、access token 到期时间 | 刷新状态、重新授权、仅本机清除、打开 GitHub 授权设置 |
| 需重新授权 | 原因（过期/远端撤销/refresh 失效） | 重新登录；保留或清除旧记录 |
| 暂时不可用 | 设备锁定/Keystore/网络原因 | 解锁、重试；不自动清密文 |
| 旧登录待迁移 | “发现容器内旧 gh 凭据” | 用户主动迁移、重新登录、查看处理说明 |

不显示 access/refresh token、完整 device code、Keystore 路径或桥 token。清除按钮必须是原生确认，结果显示“本机已删除”与“远端仍需在 GitHub 设置撤销”的区别。语言切换后按当前状态重新渲染，不能缓存 token 状态文案。

## 10. 威胁边界与剩余风险

### 10.1 得到的保护

- 普通 Android 应用不能读取 DSHA 私有 no-backup 文件或 Keystore key。
- rootfs 内不再保存 GitHub access/refresh token；rootfs 快照、项目导出、用户 v5 归档和 DocumentsProvider 不带 token。
- 普通命令输出、活动日志、bridge status/help、错误诊断不含 token；模型正常调用 `gh` 看不到 token 字符串。
- refresh token 不出桥；容器只能在 token endpoint 允许的 GitHub.com host 上取得 access token。
- 专用 client id、固定 host、scope 档位、`/user` 身份核验和过期轮换减少错误账号/错误主机绑定。

### 10.2 明确不能得到的保护

| 剩余风险 | 现实能力 | 文档/实现要求 |
|---|---|---|
| 同 UID 读取宿主 | guest 的裸系统调用可能绕过 proot 看到 files/no_backup/shared_prefs | 不宣称 no-backup 防住同 UID；真机证据与 T42 R5 同类分别记录 |
| 同 UID 调桥 | 容器已持有 `.bridge_headers`；恶意插件可直接 POST credential endpoint | endpoint 只做路由/host/状态限制，不能声称 caller 是可信 wrapper |
| 同 UID 使用 Binder/Keystore | `app_process`/Binder 路径可能代表 DSHA UID 使用 Keystore | 原生化降低文件暴露，不是密钥使用隔离；不要宣称不可使用 |
| 运行中进程环境 | `GH_TOKEN` 对 gh 子进程可见，同 UID 调试工具可能观察 | 尽量短生命周期、不进 argv/日志；验收报告写出剩余可观测窗口 |
| GitHub OAuth 授权过宽 | OAuth App `repo` 是粗范围，可能覆盖用户可见私有仓库 | 默认公开 scope；私有/Actions/gist 分档和二次确认，未来评估 GitHub App |
| 远端授权未撤销 | 本机删除不等于 GitHub revoke | UI 明确打开 GitHub connections 页面，不能误报远端已撤销 |
| 用户主动导出项目 | 用户可能把 `.env`、旧 hosts 或命令输出主动放入项目 | 现有用户项目内容政策仍适用；原生 token 不写入这些路径 |
| 恶意网络/重定向 | OAuth/`/user`/refresh 若任意跟随重定向会泄露请求 | 使用平台 TLS、固定 HTTPS host、拒绝跨 host redirect；GitHub Enterprise 另评 |

## 11. 与其它任务的边界

| 任务 | 交叉点 | 约定 |
|---|---|---|
| T49 gh 托管资产 | wrapper、proxy、`gh auth login --web`、Git helper | T49 先完成托管资产与 GitHub host allowlist；T50 实现时只按 §7 接入，不把 token 存入 T49 资产；两边冲突由主会话协调 |
| T42 ADB 原生化 | Keystore、no-backup、同 UID R1/R5、备份不带凭据 | 复用“降低文件暴露、不等于沙箱”的表述；不共用 alias、协议或迁移代码 |
| T44 原生授权完整性 | 同 UID 代码可改原生状态/调用能力 | 不引用 T44 分支代码；实现前将本稿 §10 风险与用户已定的 T44 结论对齐 |
| 备份/恢复 | `credential-paths.json`、BackupScope、旧 hosts | 新 token 不进归档；旧明文 hosts 迁移前排除，恢复不落地；补真实恢复测试 |
| Settings/UI | ConfigFragment/SettingsFragment、语言/短屏 | 状态行、确认、错误码走现有 UiText/DshaDialogBuilder，不改 API Key 历史键名 |
| RuntimeTools | managed wrapper/env、rootfs guest 目录 | T50 不把原生密文复制到 rootfs；只写短期 helper 与环境注入，不持久化 token |

## 12. 实施分期（用户确认后）

### Phase 0：定案与取证

- 注册/确认专用 OAuth App、开启 device flow，确认 client id、GitHub.com-only 范围、scope 档位和过期 token 策略。
- 在 `.test` 包验证 Android Keystore AES/GCM、no-backup 行为、设备锁定/恢复后的状态；不读取或写入用户真实 token。
- 定案 bridge 方法/JSON、是否允许 `gh auth login --web` 兼容入口、旧 hosts 多主机策略。

### Phase 1：壳侧凭据和登录

- 新增 `GitHubCredentialStore`、`GitHubAuthManager`、`GitHubCredentialStatus`；KeyStore alias 与 API Key 分开。
- 实现 device flow、`/user` 校验、refresh 单飞、状态机和 native 设置页；补中英文/短屏/旋转/后台恢复。
- 只在 `.test` 包用测试 OAuth App 试跑；生产 client id/实际 token 不进测试夹具或仓库。

### Phase 2：桥 broker

- 新增精确路由、方法/正文上限、host/profile allowlist、no-store headers、错误码和能力探测。
- 令牌响应走 POST，禁止 URL/query/log/cache；补当前 generation、桥停止、设备锁定、refresh unknown 语义。
- 先让 `status` 工作，再让受控 `credential` 工作；不要把 `/app/readfile` 或 `/exec` 改成凭据入口。

### Phase 3：T49 wrapper 与迁移

- T49 wrapper 调 broker；`gh auth status/login/logout` 按 §7 处理；Git helper 只接受 github.com HTTPS。
- 用户主动迁移旧 hosts；成功后删除精确旧 token；补备份/导出/恢复/Provider 排除。
- 过渡版本可保留旧登录但必须在 UI 明示“未原生化”，下一版本再移除容器登录 fallback；不能无提示静默改变用户账号。

### Phase 4：测试、交付与后续

- 运行 §13 的 host/Android/device 分层验收；报告实际设备和网络范围，不把单测写成 Keystore/设备矩阵证明。
- 评估 GitHub App 细粒度权限、GitHub Enterprise profile、原生 GitHub API proxy；均不作为 T50 首次实现的隐含需求。

## 13. 验收清单

### 13.1 Host/纯逻辑

- OAuth response parser：字段缺失、超长、重复键、`authorization_pending`、`slow_down`、`expired_token`、`access_denied`、无 refresh token。
- `GitHubCredentialStore`：独立 alias、AES/GCM authentication failure、原子写/读回、NOFOLLOW、symlink、大小上限、设备锁定、暂时失败不清除、删除状态。
- refresh：并发单飞、旋转 pair 原子替换、身份不匹配、网络 UNKNOWN 不重放、明确 bad refresh 进入 reauth。
- bridge：GET/POST 方法矩阵、单个 X-Token、拒绝 URL token/Cookie/重复头、host allowlist、consumer/profile 枚举、query/body 不回显秘密、generation 变化、no-store。
- wrapper/helper：`gh` wrapper 的 token 不出用户可见 stdout/stderr/argv/log；Git helper 仅把 token 放在 Git 消费的 credential-protocol stdout 的 `password` 字段，不写用户可见 stderr/日志或其它输出；`GH_TOKEN` 只传给 gh 子进程；Git helper 拒绝 SSH/非 GitHub host；无 hosts fallback；代理只允许 GitHub 域名。
- 备份规则：旧 hosts token 不入备份/导出，恢复不落地；native no-backup 文件不被任何归档或 Provider 发现。

### 13.2 Android `.test` 包

- 新登录成功、用户取消、device code 过期、无网络、系统时钟异常、旋转/后台/重启后状态。
- 设备解锁前读取返回暂不可用，解锁后原 pair 仍在；Keystore key 损坏时不生成替代 key。
- 设置页显示 login/scope/expiry，不显示 token；本机清除后 status 为 missing；打开 GitHub settings 只证明 Intent 请求，不证明远端撤销。
- 迁移 fixture：单一 github.com 旧 hosts 成功迁移并删除；未知 host/重复键/软链接/多主机进入 review，不删除原件；迁移失败不报告 READY。
- `/app/readfile`、`/app/export`、DocumentsProvider、备份和活动日志找不到 native token；桥 credential endpoint 只有 POST 且返回 access token 时不写日志。

### 13.3 用户授权后的有限真机核对

在用户明确同意并使用专用测试 OAuth App/测试账号后：

- `gh repo view`、`git ls-remote` 只读成功；必要的写操作使用专用测试仓库，不能用生产仓库。
- token 过期/refresh 的真实结果按 GitHub 返回记录；无法等待八小时则用受控测试 fixture，不把模拟结果当真实 refresh 证据。
- Android 普通备份/导出、文件选择器和桥读文件都不出现 token；T50 不保存 token/日志/截屏作为仓库附件。
- 真实测试报告写出 Android 版本、引擎、网络、OAuth host、测试账号范围和未测试的 Enterprise/Root/插件场景。

## 14. 待用户确认的问题

| 编号 | 问题 | 推荐答案 | 未定前的限制 |
|---|---|---|---|
| Q1 | OAuth 应用 | 注册 SunsetRNE 专用 OAuth App，开启 Device Flow；client id 进入签名 APK，不用 gh 自带 id | 不实现登录代码 |
| Q2 | 默认权限 | 默认 `public_repo`；`repo`、`workflow`、`gist` 分档并逐项确认 | 不请求私有/Actions scope |
| Q3 | token 有效期 | 请求 `offline_access`/启用 expiring token；按 GitHub 返回期限处理，无 refresh 也兼容 | 不假定固定 8h/6mo 为永久合同 |
| Q4 | 桥鉴权 | 复用现有 X-Token + POST + generation + github.com allowlist；明确不能防同 UID | 不新增虚假的独立 secret |
| Q5 | 旧 hosts | 默认不自动导入；用户点击迁移后严格验证、密文 readback，再删除单一 GitHub 记录 | 不静默删除用户文件 |
| Q6 | 登录入口 | 设置页为规范入口；`gh auth login --web` 兼容调用同一个 native flow | T49 先保留当前行为但标过渡 |
| Q7 | Enterprise | 首版仅 github.com | 不允许任意 host 参数 |
| Q8 | 远端撤销 | 本机清除与打开 GitHub connections 页面分开；不在 APK 放 client secret | 不把本机删除文案写成远端已撤销 |

用户确认 Q1–Q8 后，主会话再把本稿拆成实现任务；在此之前不改 Java、资产、桥路由或 T49 wrapper。

## 15. 参考来源

- [GitHub OAuth App device flow](https://docs.github.com/en/enterprise-cloud@latest/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow)：device code、轮询间隔、错误码、access/refresh token 与过期语义。
- [GitHub OAuth authorization overview](https://docs.github.com/en/enterprise-cloud@latest/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)：OAuth App 的 scopes、`/user` 校验、redirect 与 token 使用合同。
- [GitHub OAuth access-token refresh](https://docs.github.com/en/enterprise-cloud@latest/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#refreshing-an-access-token)：refresh token 轮换和失效后的重新授权边界。
- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)：Keystore 密钥不可导出、用途和平台限制；本地设备证据不能由官方文档替代。
- [Android `Context.getNoBackupFilesDir()`](https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir())：不参与自动备份的宿主目录合同；不能解释成同 UID 隔离。
- 仓库现有 [T42 ADB 原生化设计](adb-native-20261103/README.md)：Keystore/no-backup、备份排除和同 UID 风险的既有表述；T50 不复用其 alias 或实现。

## 16. 待同步文档（实现合入时）

本稿当前只占用本文件；下列文件是用户确认并实现后由主会话协调的同步清单，本次不直接修改：

| 文档 | 需要同步的事实 |
|---|---|
| `docs/security-model.md` / `docs/security-model.en.md` | GitHub access/refresh token 的 Keystore/no-backup 位置、旧 `hosts.yml` 迁移与备份排除；3090 broker 只发 access token；同 UID 代码仍可读宿主、调用桥或观察运行中环境，不能宣称沙箱或 token 不可取得；本机清除不等于 GitHub 远端撤销。 |
| `docs/adr/0002-current-threat-model.md` | 新增 GitHub 凭据资产、OAuth scope/host 边界、bridge 暴露面和同 UID 剩余风险；区分“减少明文持久化”与“不能阻止同 UID 滥用”。 |
| `docs/bridge-and-files.md` | 新增 `/app/github/status`、`login`、`credential`、`logout` 的方法、X-Token、generation、POST/no-store、`github.com` allowlist 和错误语义；明确 token 不可经 `readfile`/`export` 取得。 |
| `docs/runtime-data-plugin-architecture.md` / `docs/backup-and-restore.md` | `GitHubCredentialStore` 不属于 rootfs、用户归档、自动副本、DocumentsProvider 或普通备份；旧 `hosts.yml` 的迁移、恢复隔离和维护屏障。 |
| `docs/device-permissions-architecture.md` | 设置页 GitHub 登录、清除、重新授权和设备锁定/Keystore 暂不可用状态；不把 OAuth 账户状态当作 Root/Shizuku/ADB 能力授权。 |
| `docs/documentation-index.md` | 增加本设计稿入口；实现完成后将“设计稿，未实现”更新为实际状态并链接验收记录。 |
| `AGENTS.md` / `AGENTS.en.md` | 当前架构地图、`data/` 与 `bridge/` 新组件职责、凭据/备份边界、T49 wrapper 接口和同 UID 限制；不把原生化写成安全隔离。 |
| `CHANGELOG.md` | 只记录实际交付的壳登录、迁移、桥能力和验证范围；不提前记录未实现的 OAuth App、真实设备或 GitHub 网络验收。 |

以上同步必须在实现提交中按实际代码和测试结果更新；若 Q1–Q8 未确认，保留本稿的“设计稿，未实现”状态，不改共享文档。
