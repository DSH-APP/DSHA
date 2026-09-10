# DSHA 1.5-alpha.1 真机验收

日期：2026-09-09。设备：Redmi M2012K10C，Android 13 / API 33，ARM64，4 KB 页。标准版与 low 共用包名，按顺序覆盖安装；原有数据通过生产维护事务迁移。

## 实际发现与修复

1. 旧移动端插件声明了不存在的 dsh 浏览器宿主 peer，导致安全备份失败。现在只放行这类纯宿主接口，真实缺失的运行依赖仍拒绝备份；已安装的依赖继续保存。
2. `/root` 下内置插件无法从 `.dsh/node_modules` 解析 ESM 依赖。受管插件现在明确复用同一份 dsh 依赖，维护提交前验证其真实模块导入。
3. 旧环境内的虚拟挂载占位目录权限为 000，阻止清理。清理器先确认目录归本应用所有，再补遍历权限，不追随外部软链。
4. 重建后缺少 curl、git，证书包配置还需要临时目录。现在通过已校验的 Ubuntu 软件包离线安装基础工具，配置成功后移除 deb 与临时目录，保留正常 dpkg 数据库。
5. 安装页把新版 `publishSessionExclusive as link` 误认为未修复硬链接；冷环境的局域网设置补丁也未立即应用。已修正判定，并让完整检查成为环境提交前的条件。
6. 受管插件借用整个 `node_modules` 目录后，备份误收整套宿主依赖。现在只收集插件明确依赖，测试归档由约 43 MiB 降为约 2.3 MiB。
7. 重复恢复时，同名源码的保留副本发生冲突。现在按内容与文件权限核对，相同副本去重，不同版本另存；连续三次恢复测试确认未提交源码和 Git 历史保留。

## 通过的检查

证据位于项目的 `app/build/dsh-alpha15`；网页命令结果位于 `app/build/functional-evidence`。

| 检查 | 结果与证据 |
|---|---|
| 覆盖升级门禁 | 旧版进入自动维护；失败不自动反复重建。真实备份失败、离线安装失败和恢复冲突均保留或回切原环境 |
| 完整重建 | `device-rebuild-accepted.log`：备份、逐文件验证、离线解压与 dpkg 配置、恢复、完整安装检查、模块加载、提交及旧树清理通过 |
| 数据保留 | `device-evidence/final-migration-summary.json`：原有 2 个会话日志字节不变，26 个插件源码文件在迁移后的目录中摘要一致；2 个派生缓存重建；工作区登记新增本次测试目录 |
| 安装与修复 | `device-install-awake.log`：42 条断言通过；六步检查约 1.4 秒，健康修复约 1.1 秒；切页、重建、取消、失败展示及维护互斥通过 |
| 四种备份范围 | `device-backup-scopes.log`：完整、会话、设置、插件恢复与范围隔离通过，同时验证本地插件依赖和旧 host peer |
| 原生插件管理 | `device-native-plugins.log`：18 条断言通过；实际 npm 安装后可检测，列表显示四项内置插件，启用、停用、再启用及删除通过 |
| 新会话模型往返 | `alpha15-model-start.json`、`alpha15-model-complete.json`：真实官方模型创建并读回独立目录内的 `alpha15-check.txt`，内容为 `DSHA_ALPHA15_OK` |
| 旧会话续聊 | `alpha15-old-verified.json`：已完成的旧会话经真实分支接口迁移后成功续聊；未完成轮次的分支请求被正确拒绝 |
| 标准 WebView | `device-auth-accepted.log`：启动、Cookie 鉴权、停止重启、切换临时端口及切回 3080、后台返回后的页面就绪通过 |
| 草稿与文件侧栏 | `alpha15-file-panel.json`、`alpha15-system-back.json`：文字和图片刷新后保留，文件侧栏进入手机可视区域，系统返回关闭详情并保留编辑器；截图为 `device-evidence/standard-file-panel.png` |
| Gecko 143 | `device-gecko-accepted.log`：通过真实对话入口进入 Gecko，重新鉴权并显示交互页面；等启动遮罩与首次提示处理完成后截图，见 `device-evidence/gecko-stable-accepted.png` |
| 诊断与 PTY | `device-diagnostic-pty.log`：16 条检查通过，真实工具检查、修复入口、PTY 输入和子进程回收通过 |
| 桥接接口 | `device-bridge.log`：65 条断言通过，包括 shell 超时、退出码、输出上限、3090 绑定、token 门控、问答生命周期和清理 |

临时模型凭据只用于验收，结束后恢复原配置；端口与 Gecko 开关也恢复原值。运行目录约 719 MiB，迁移前约 1.41 GiB；该值包含这台设备的数据，不是所有用户的固定占用。

## 安装包与签名

发布候选 APK 使用历史发布证书，位于 `F:\DSHA_RESTART\release`，尚未上传。测试机原来使用另一把调试证书，因此通过同证书的测试副本覆盖，避免卸载和清数据。`release-test-copy-proof.json` 核对测试副本与交付包的代码及资源内容一致；正式交付文件的签名未替换。

最后又安装了两份交付 Release 的测试签名副本，直接通过原生「启动」「进入」按钮验证：标准版进入系统 WebView，low 进入 Gecko，均显示实际对话页面。两份包各有 120 项来源资产核对一致，全部非签名 ZIP 条目也与交付包一致。最终安装包大小和 SHA-256 记录在 `delivery-final.json`、`final-delivery-proof.json`，对应截图为 `device-evidence/final-standard-visible.png` 与 `device-evidence/final-low-visible.png`。

## 覆盖边界与清理

Android 6/7 和真实 16 KB 页设备未连接，本轮不能把静态 ABI/ELF 检查称为这些系统的实测。无线调试处于关闭状态，未重新配对无线 ADB。通知、悬浮输出的新事件适配有自动化检查，但本轮未逐一验证跨厂商后台展示效果。浏览器独立下载/SAF 夹具未计入本轮通过项。

用户要求测试机不保留备份。历史下载备份、本轮六个维护目录中的归档及失败环境均已删除；维护目录和公开 DSHA 下载目录核对为空，临时凭据不再存在。清理结果记录在 `device-evidence/cleanup.json`。此操作不改变正式产品默认保留升级前安全备份的行为。
