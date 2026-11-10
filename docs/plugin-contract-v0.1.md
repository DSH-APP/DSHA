# DSHA 插件参考约定 v0.1

这是一份插件作者参考，不是新 SDK、加载器或权限系统。当前 DSH 仍加载 Node/Cordis 包；已有插件继续使用现有包格式，无须改写。本文以随包 DSH `0.2.1-rc.3` 的源码为基线，新增 `dsha` 元数据和目录分工均为**可选建议**；当前安装器不会据此创建 venv、安装依赖或授予能力。本文描述本机插件扩展，不提供电脑连接或配对协议。

## 当前可用的包契约

| 项目 | 当前要求与消费者 |
|---|---|
| `name`、`version` | 合法 npm 包名和有效 semver；安装器校验，DSH 模型请求扩展也需要实际插件版本 |
| `dsh.bundle.patch` | 必需；包内存在的补丁路径，或按顺序组合的非空路径数组；普通 npm 库不会自动成为插件 |
| `main`、`cordis.entry` | 声明时入口文件必须存在；并非每种 bundle 都必须同时声明这两个字段 |
| `type`、`exports`、依赖 | 遵循实际 Node 包解析；ESM 示例使用 `type: module`；依赖范围必须覆盖实际运行版本 |
| `apply(ctx)`、`inject` | 现有 Cordis 插件入口和服务依赖；服务注册、事件订阅与资源退休使用其生命周期 |
| profile | `dsh.profile.bundles` 选择 bundle，`dependencies`/`node_modules` 提供解析；安装成功、启用意图和当前已加载是三件事 |
| `dsh.client` | 现有 Web 客户端插件声明；浏览器环境不提供 Node/Python，也不因 `platform: web` 获得 Android 原生桥 |

以[安装器](../app/src/main/assets/plugin-manager.py)、[注册器](../app/src/main/assets/register-builtin-plugins.py)和锁定上游的加载错误为准。`dsh` 是现有上游命名空间，不把本文建议字段塞进去。当前代码没有本文 `dsha` 字段的消费器；这也不构成对所有版本、任意未知字段处理方式的承诺。

包通过现行格式、路径、内容与事务检查后自动提交并启用；用户主动禁用和安全模式意图仍保留。普通 `npm install` 不一定修改 profile。原生插件管理提示正常重启 Web 后生效，不承诺任意插件热替换。锁文件推荐随包提供；现行策略仍允许未锁定解析、生命周期脚本和 pnpmfile，并记录实际结果，本文不收紧这些入口。

## 同一个 Ubuntu，独立的插件依赖

手机中的 Ubuntu 是共享 Linux arm64/glibc 用户空间。Node 插件直接按现有机制加载；需要 Python 的插件保留薄 Node/Cordis 入口，由它启动自己的 Python worker。Python 源码不是另一种可直接导入的 DSH 插件类型。

Python 插件共用基础 Python，但各自使用独立 `venv` 和依赖锁。创建前实际检查 `python3 -m venv` 及所需构建工具，缺失时报告具体前提，不把基础 Python 就绪当作所有扩展模块齐备。venv 防止依赖版本互相覆盖，不隔离文件、进程或秘密，也不把 proot/proroot 变成强沙箱。不要在插件安装时全局升级 Python 包或覆盖随包 Node/DSH。Android arm64 的 wheel、外部命令及 ABI 要逐项核对；跨平台使用须重建对应环境，不能直接复用手机 venv 或 Linux 原生模块。

工具型 worker 可以沿用现有 MCP stdio 适配器，参照[虚拟屏插件](../app/src/main/assets/builtin-plugins/dsh-tool-vscreen/lib/index.js)；它仍需真实 MCP server、匹配的 peer dependency 和明确的启动/调用失败处理。普通 worker 也可使用插件自有的有界 stdin/stdout 协议；协议名称不能冒充宿主已经支持的接口。

## 代码、环境和数据的位置

下表前五项是现有事实，其余是作者可选择的目录建议。`$DSH_HOME` 当前默认为 `/root/.dsh`；代码应从实际入口 URL 定位自身，不能把所有插件都当作 `plugin-src` 导入包。

| 职责 | 路径与约束 |
|---|---|
| 原生导入的用户源码 | `$DSH_HOME/plugin-src/<npm包名>`；npm、共享 profile 或其它 profile 的包可能在别处 |
| 当前 Web profile | `$DSH_HOME/profiles/web/package.json`、`cordis.patch.yml` 和 `node_modules`；由现有管理链维护 |
| 签名内置插件源码 | 仓库 `app/src/main/assets/builtin-plugins/`；guest 实体位置逐项读取 `builtin-plugins.json`，通常 `/root/dsha-*`；内部集成另在 `app-integration/` |
| DSH 受管代码 | `/usr/local/lib/node_modules/@deepseek-ai/dsh`；不作为插件写入用户数据的目录 |
| 用户逻辑数据根 | guest 始终使用 `/root/.dsh`；[UserDataLayout](../app/src/main/java/com/deepseekharness/app/backup/UserDataLayout.java)选择 legacy `files/linux/ubuntu/root/.dsh` 或持久 `files/user-data-v5/dsh` 并映射，作者不依赖 Android 物理路径 |
| 独立依赖环境（建议） | `$DSH_HOME/plugin-envs/<包名>/<平台和Python ABI>/<环境ID>`；可重建，但有修改或未知状态的旧环境不能直接当缓存删 |
| 插件持久数据（建议） | `$DSH_HOME/plugin-data/<包名>`；与发布源码分开，记录自己的数据 schema；更新前校验迁移和回切条件 |
| 缓存、日志、临时文件（建议） | 分别放 `$DSH_HOME/plugin-cache/<包名>`、`plugin-logs/<包名>`、`plugin-tmp/<包名>/<任务ID>`；有界日志，不记录 token、密钥或未脱敏截图；临时清理只处理本任务确认拥有的目标 |
| 用户项目 | 使用用户实际选择的 workspace，不强制迁到上述目录；外置工作区维护只断开私有引用，不遍历清理其内容 |

目录建议没有自动创建、备份收录、迁移或清理保证。不要把源码同级的 `.venv`、`node_modules` 或源码目录里的数据库当作稳定数据位置；旧插件已有布局仍可继续使用，迁移必须显式保留原件并核对结果。跨平台恢复后重新构建环境候选，不能静默重解析并覆盖历史依赖，再宣称是原字节恢复。

## 可选 `dsha` 元数据提案

下面是**尚未被宿主实现的建议格式**，仅供作者说明需求。它不替代现有必需字段，不进入系统授权。现在采用它的插件必须自行完成 worker 和路径处理；旧插件不需要新增这些字段。

```json
{
  "dsha": {
    "schema": 1,
    "platforms": ["linux-arm64"],
    "workers": [{
      "id": "python",
      "runtime": "python-venv",
      "entry": "worker/main.py",
      "requirements": "requirements.lock",
      "transport": "json-once"
    }],
    "capabilities": {
      "required": [],
      "optional": ["android.screen.observe", "android.screen.input"]
    },
    "data": { "schema": 1, "directory": "plugin-data/dsh-example-python" }
  }
}
```

`schema` 标识本提案版本；`platforms` 是作者声明的目标；`workers` 描述包内 worker 和依赖输入；`capabilities` 是需求清单；`data` 记录插件自有持久布局。上述 capability 字符串是说明标签，**不是当前桥的能力枚举或可调用端点**。这些声明不证明平台兼容、依赖已安装或授权有效；宿主未来若采纳，须另行定义校验、版本协商和迁移规则。

## 宿主提供、需要授权与不保证

| 类别 | 实际边界 |
|---|---|
| 宿主提供 | 当前正式环境中的 Ubuntu、随包 Python/Node/pnpm、锁定 DSH/Cordis、插件安装事务及当前可用的本机桥；每项就绪以实际检查为准 |
| 能声明需求、仍需授权 | 读屏、截图、输入、通知、麦克风、定位、传感器、文件分享；分别核对 Android 权限、用户策略、前台条件、目标、运行代次和最新帧。ADB、Root、Shizuku 另需真实系统能力 |
| 不保证 | 插件间秘密隔离、任意代码强沙箱、guest root 等于 Android root、所有 wheel/原生二进制兼容、任意 PC 程序可在手机运行、后台永久存活、Python直接加载、无限传输或通用热加载 |

本机桥端口由安装实例发现，当前优先 3090、占用时 3091–3099；不能永远缓存 3090。参考随包[端口读取器](../app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/bridge-port.cjs)，再以当前 `/app/version`、`/app/help` 核对协议和端点。凭据仅按当前 `X-Token` 机制传递，不放进 URL、argv、网页全局变量或报告。本机桥凭据只用于当前安装的本机桥，不作为插件自有外部服务的凭据。

声明能力不会批准操作。“全部允许”只影响对应 DSHA 确认域，不取消系统能力、目标、帧/节点身份和代次核验。权限撤销、owner切换、连接断开或会话停止后，旧请求必须失效。收到 `UNKNOWN`/`EXECUTION_UNKNOWN` 时先观察实际状态，不换通道或自动重放写操作。

## 示例一：纯 Node/Cordis 插件

以下三个文件构成一个最小 ESM bundle；它只添加一段说明，不需要 Android 权限。

`package.json`：

```json
{
  "name": "dsh-example-note",
  "version": "0.1.0",
  "type": "module",
  "main": "lib/index.js",
  "exports": { ".": "./lib/index.js" },
  "dsh": { "bundle": { "patch": "./cordis.patch.yml" } },
  "peerDependencies": {
    "@deepseek-ai/cordis": "^4.0.1",
    "@deepseek-ai/dsh-system-prompt": "0.2.1-rc.3"
  }
}
```

`cordis.patch.yml`：

```yaml
- insert:
    - id: example-note
      name: dsh-example-note
```

`lib/index.js`：

```js
export const name = 'dsh-example-note';
export const inject = ['systemPrompt'];
export function apply(ctx) {
  ctx.systemPrompt.section({
    name: 'example:note', order: 300,
    text: () => '插件只报告实际结果；执行结果未知时先检查状态。'
  });
}
```

服务注册跟随 Cordis 作用域退休；需要计时器、socket 或子进程时再登记对应 effect/disposer，不在模块顶层启动永久资源。

## 示例二：Node 适配器和独立 Python venv worker

此例只做一次无副作用健康检查，使用 Python 标准库。包名为 `dsh-example-python`，`package.json`、补丁和 peer dependency 沿用示例一并替换名称，可附上前述 `dsha` 提案字段。包内另放以下文件；`requirements.lock` 为空文件，表示本例没有第三方 Python 依赖。

作者或明确的安装步骤创建环境，当前宿主**不会根据元数据执行**这些命令。生产安装应构建新候选并在成功后切换；下列新路径仅用于首次示例：

```sh
# 在共享 Ubuntu 内执行；环境ID由作者为此候选确定。
python3 -m venv /root/.dsh/plugin-envs/dsh-example-python/example-v1
/root/.dsh/plugin-envs/dsh-example-python/example-v1/bin/python -m pip install -r requirements.lock
```

`worker/main.py`：

```python
import json
import sys

if sys.argv[1:] != ["--health"]:
    raise SystemExit("unsupported operation")
print(json.dumps({"schema": 1, "state": "ready"}), flush=True)
```

`lib/index.js`：

```js
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
export const name = 'dsh-example-python';
export const inject = ['systemPrompt'];
const run = promisify(execFile);
export async function apply(ctx) {
  const home = process.env.DSH_HOME || '/root/.dsh';
  const python = join(home, 'plugin-envs', name, 'example-v1', 'bin', 'python');
  const worker = fileURLToPath(new URL('../worker/main.py', import.meta.url));
  const abort = new AbortController();
  ctx.effect(() => () => abort.abort());
  const { stdout } = await run(python, [worker, '--health'], {
    signal: abort.signal, timeout: 5000, maxBuffer: 4096,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', PYTHONNOUSERSITE: '1' }
  });
  const health = JSON.parse(stdout);
  if (health.schema !== 1 || health.state !== 'ready') throw Error('WORKER_NOT_READY');
  ctx.systemPrompt.section({ name: 'example:python', order: 300,
    text: () => '本插件的独立 Python 环境健康检查通过。' });
}
```

`timeout`/abort 是健康检查的取消手段，不证明任意业务或派生进程树已经停止。生产 worker 必须有自己的关闭协议、请求ID、期限、输出上限和退出证据；不能将启动失败写成 ready，不能在未知结果后自动重跑业务。需要持久状态时显式选用插件数据目录，不能写回源码或 venv。

## 作者交付检查

交付构建后的真实 bundle、入口、worker、依赖输入和许可；描述已支持的平台和版本，保留安装/启用/加载/运行的不同状态。更新在新候选里核对代码、环境与数据兼容，已提交历史不能回滚后来数据；停止或依赖脚本结果未知时保留现场。日志和错误应说明失败发生在哪一层，不能用空成功、无限重试或清空用户环境遮盖问题。

本文只定义参考约定。正式能力、运行时内容、软件检查和设备验收分别以当前 APK、实现代码及本轮回执为准；更多现行机制见[插件操作](plugins.md)、[插件运行与权限](plugin-runtime-and-permissions.md)和[安全模型](security-model.md)。
