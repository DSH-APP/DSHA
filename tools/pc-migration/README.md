# 手机 DSH 数据 → 电脑端

把手机里 DSHA 容器中的 DSH 数据导成**电脑端能直接接着用**的便携包，以及在电脑上恢复它的工具。

## 先分清两件事

| 需求 | 现在能不能做 | 怎么做 |
|---|---|---|
| 用**电脑浏览器**打开手机上的 DSH 对话（同一个会话、同一份数据） | ✅ 已经能做 | DSHA 配置页打开「允许局域网访问」，启动页复制 `http://<手机IP>:3081/?token=…` 到电脑浏览器。见下文「局域网代理」 |
| 把**数据搬到**电脑，在电脑上本地跑 dsh 继续用 | ✅ 本目录的工具 | 手机端导出 → 传到电脑 → 电脑端恢复 |

两者解决的问题不同：前者是「远程看手机」，后者是「数据落地到电脑」。

## 局域网代理（已经存在，不用改代码）

* `dsh` 本体**只能绑 `127.0.0.1`**：`--host 0.0.0.0` 被 CLI 直接拒绝
  （`@deepseek-ai/dsh-web-app/lib/startup.js`：「intentionally not supported yet for safety:
  it would expose remote code execution to the network」），Web server 的 host 配置也只接受
  `127.0.0.1` / `0.0.0.0` 两个字面量。
* DSHA 因此自带一层代理：`LanProxyService` 绑 `0.0.0.0:3081` → 转发到 `127.0.0.1:<web 端口>`。
  * 凭据是独立的 256 位 `dsha_lan` token（query 或 cookie，常数时间比较），**不复用** dsh 的
    `dsh-auth-*` 浏览器 cookie；后者只在内存里，且请求转发时由代理注入、响应里剥掉。
  * 开关默认关闭：配置页「允许局域网访问」→ `ConfigStore.isLanMode()`。
  * Android 17 还要 `ACCESS_LOCAL_NETWORK`（`bridge/LocalNetworkAccess`）。
  * 流量**不加密**，只在可信 Wi-Fi 上用；token 会随链接泄露，关掉 LAN 或重启 dsh 即失效。

## 便携包工具

三个文件，零第三方依赖：手机端用 `python3`（容器自带），电脑端用 `node`（跑 dsh 就有）。

```
dsha-portable.py            手机端：打包
dsha-portable-restore.mjs   电脑端：规划 / 恢复
selftest.mjs                端到端自检（在手机容器里跑）
portable-plan.json          策略真源：白名单、排除理由、凭据规则
```

### 手机端：导出

```bash
cd /root/Documents/deepseek-harness/default-workspace/dsha-lan/tools/pc-migration

python3 dsha-portable.py plan                  # 只打印会打包什么
python3 dsha-portable.py export                # 默认落到 /sdcard/Download/DSHA/
python3 dsha-portable.py export --with-workspace /root/Documents/my-project
python3 dsha-portable.py export --with-credentials   # 见「凭据」
```

输出目录默认 `/sdcard/Download/DSHA/` —— 容器里的 `/sdcard` 就是外部存储，用户可以在文件管理器里
直接拿走。**不要**走 `/app/export`：那个端点只收单文件、上限 64 MiB，而且会拒绝 `.dsh` 凭据区。

### 电脑端：恢复

```bash
# 1. 先看它打算干什么（只读，不写任何文件）
node dsha-portable-restore.mjs plan --package DSHA-portable-<时间戳>.tar.gz

# 2. 落地；已有的 ~/.dsh 会整体改名为 ~/.dsh.before-restore-<时间戳>
node dsha-portable-restore.mjs apply --package <包> --workspace-dir ~/work

# 3. 想在电脑上「接着聊」旧会话，加 --rewrite-cwd
node dsha-portable-restore.mjs apply --package <包> --workspace-dir ~/work --rewrite-cwd

# 4. 起界面
dsh web --no-open
```

## 包里有什么、没有什么

**进包（白名单，认不出来的都不进）**

| 路径 | 说明 |
|---|---|
| `sessions/` | 会话日志（zstd jsonl）。`session.lock` 不进包，恢复端会重建 |
| `storages/workspace.json` | 工作区注册表；恢复时按 `--workspace-dir` 改写其中的绝对路径 |
| `attachments/` | 附件对象仓 |
| `profiles/<profile>/cordis.patch.yml` | 你的设置层（模型、字号、主题…） |
| `llm-deepseek/` | 文件 API 缓存（按账号作用域，非设备绑定） |
| `.credentials.yaml` | **只在 `--with-credentials` 时**，且必须过滤成功 |

**不进包**

| 路径 | 为什么 |
|---|---|
| `storages/session_projcache` | 可重建；`identity.cwd` 是绝对路径，`version` 还绑定 dsh 版本 |
| `profiles/*/node_modules`、`profiles/*/package.json` | 设备侧 pnpm 链接，`package.json` 里写着 `link:/root/dsha-*`，PC 上必然失效 |
| `node_modules/`、`cache/`、`__pycache__/` | DSHA 塞进去的随包依赖与可再生缓存 |
| `.bridge_token` / `.bridge_headers` / `.bridge_status` | 3090 桥的本机共享凭据；导出等于交出设备控制权 |
| `.anonymous-user-id` | harness-home 级匿名身份；迁移会让两台机器共用一个 id |
| `.runtime-descriptor.json`、`.runtime-health.json`、`.offline-*`、`.dsha-*` | 设备侧受管运行时与生命周期状态 |
| `*.py`、`*.cjs`、`builtin-plugins.json`、`runtime-tools.json`、`plugin-*` | 全部是 DSHA 的随包脚本与受管资产，不是用户数据 |
| `profiles/*/pnpm-workspace.yaml` | 设备侧 profile 布局 |

## 凭据

* **默认不含 `.credentials.yaml`。** 到电脑上第一次启动 dsh 时重新填一次 API Key 即可。
* 用户显式加 `--with-credentials` 时，也必须先过 `credential-yaml-filter.cjs` 做**字段级**剔除：
  删掉 `records` 里所有 `client-connection/*`（浏览器会话签名密钥 —— 与本机 + 本轮 dsh 绑定，
  搬到电脑上没有用处，留着只是泄露面），只保留 `refs` 里的用户 API Key 引用。
* **过滤失败就整个不带凭据**，不回退成「原样打包」。
* 恢复端 fail-closed：落地前再查一遍，只要 `.credentials.yaml` 里还剩 `client-connection/`
  记录，就整份丢弃并明确告知。落地后 `chmod 600` —— dsh 会拒绝群组/他人可读的凭据文件。
* 包内**绝不含**桥凭据、DSHA 随包脚本、设备侧插件。

## 会话的绝对路径（cwd）

会话日志 header 里记着手机上的**绝对工作目录**，dsh 用 `projectKey(cwd)` 推导日志所在目录，
读的时候还会核验「目录名 ↔ header.cwd」一致，不一致就报 corrupt。所以：

* **默认（保原样）**：日志字节原封不动，零格式风险。电脑上能看到全部历史，但旧会话的 cwd
  在电脑上不存在，想「接着聊」会别扭。
* **`--rewrite-cwd`**：把整棵 `sessions/` 转成**明文** `session.vN.jsonl` 并改写 `header.cwd`，
  同时往 `profiles/<p>/cordis.patch.yml` 追加：

  ```yaml
  - id: session-persistence-jsonl
    name: "@deepseek-ai/dsh-session-persistence-jsonl"
    config:
      root: "<新的 $DSH_HOME>/sessions"
      compression: none
  ```

  两点必须注意：
  1. 补丁是 `target[key] = value` **整键替换**，不是深合并 —— 只写 `compression` 会把 bundle
     里的 `root` 抹掉，所以 `root` 必须一起写。
  2. 转明文会让**整个** sessions 根变成明文编码，而 dsh 拒绝同一个根混用两种编码
     （`checkRootEncoding` / `rejectOppositeArtifact`），所以覆盖必须整棵做，不能只改几个会话。

## 自检

```bash
node selftest.mjs          # 在手机容器里跑：导出 → 恢复 → 逐项断言
node selftest.mjs --keep   # 保留临时目录自己再看
```

45 条断言，覆盖：`plan` 不写盘、包内路径无逃逸、白名单真的拦住了设备侧文件、会话数与包内快照
逐字节一致、默认恢复保 zstd、`--rewrite-cwd` 除 cwd 外正文逐字节无损、`compression: none` 覆盖
（含 `root`）、`workspace.json` 路径改写、凭据字段级剔除与 600 权限、含 `../` 的包被拒绝。

App 侧策略镜像在 `app/src/main/java/com/deepseekharness/app/util/PortableExportPlan.java`，
配 `app/src/test/java/.../PortableExportPlanTest.java`（5 个用例，锁定白名单与凭据规则）。

## 还没验证的（必须真机 / 真电脑上过一遍）

1. **在真实电脑上跑 `dsha-portable-restore.mjs`**：本地只验到「包正确、落地产物自洽」，
   没有在 Windows / macOS 的 `tar.exe`（bsdtar）上跑过解包。
2. **`--rewrite-cwd` 后的会话能否真的被 dsh 读起来**：本地证到「明文 jsonl 除 cwd 外逐字节无损、
   header 与目录名自洽、覆盖行与 bundle 的 id/name 匹配」，但没有起第二个 dsh 去实际打开它
   （本轮刻意不动用户正在用的 dsh）。
3. **旧会话「接着聊」**：改写后的 cwd 上真正发起一轮对话，确认工具调用的工作目录正确。
4. **包大小与传输**：工作区很大时包会很大，`/sdcard` 直写没有 64 MiB 限制，但手机剩余空间与
   传输方式（U 盘 / 网盘 / `adb pull`）要按实际情况选。
5. **导出瞬间正在写入的会话**：快照取的是导出那一刻，最后几条事件可能没进包。要完整就先停 dsh
   再导，或导两次。
