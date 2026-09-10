# DSHA 1.5-alpha.1 适配与验证

标准版和 low 使用 dsh `0.1.5-alpha.1`，应用版本码 114、环境版本 10。交付路径固定为 `F:\DSHA_RESTART\release`。本机验证与 Android 13 真机验收已完成，包含真实官方模型往返；尚未上传发布。详见[真机验收记录](device-acceptance-alpha15-2026-09-09.md)。

9 月 9 日追加输入与系统栏修订，当前同名安装包已包含回车换行、状态栏避让及 Gecko 键盘视口重排；最终哈希和本轮证据见[修订记录](input-insets-fix-2026-09-09.md)。下文保留环境升级阶段的验收结果。

## 已实现

- 新版依赖由 `tools/dsh-runtime/package-lock.json` 锁定。固定源码标签 `dsh-v0.1.5-alpha.1`，提交 `5dda764ed3aa172535a7967b06ff95d9cbfe536a`。
- 登录以实际 303、签名 Cookie、HTTP 200 判断成功，覆盖就绪重试、取消、换端口、重启和预览生命周期。日志完整行脱敏，避免跨读取块泄漏 token。
- 原生管理页显示四个用户内置插件，隐藏官方核心与内部集成模块；持久插件检测覆盖 Web/终端/其他 profile/导入来源，临时插件使用当前运行代际的公开快照并限制可操作项。
- 备份格式 4 保存插件依赖图和独立版本、循环引用、未登记源码及 Git 历史；接受格式 3。四种范围、损坏归档、磁盘不足和提交回滚均有回归。
- 升级先验证 `.dsh` 与个人目录快照，再切换旧环境、解压新环境、恢复并逐文件验证。验证新运行时后提交；成功才清理旧运行环境与个人文件临时包，保留对话/配置安全归档。
- 原子发布适配 Android 私有目录硬链接限制，保持 EEXIST/并发排他与源文件不变。V3 兼容迁移修正两种已确认的 rc.1 记录形状，原始旧日志不改写，官方目标校验不放宽。
- 四个内置插件与内部集成已按新版事件和客户端接口适配；浏览器检查发现并修复附件接口变动造成的图片草稿误报和恢复失效。
- 真机进一步修复宿主 peer 备份阻塞、内置 ESM 依赖解析、000 目录清理、共享运行时误入备份及连续恢复的保留源码冲突。
- curl/git/证书和依赖由 `tools/ubuntu-tools/packages.lock.json` 锁定为 28 个 Ubuntu 软件包。索引经 Ubuntu 签名验证，下载逐包检查 SHA-256；新环境通过 dpkg 离线配置后删除安装包。提交环境前复用安装页的完整检查。

## 本机构建证据

证据输出在忽略目录 `app/build/dsh-alpha15`，下列路径相对于项目根目录。

| 验证 | 结果 / 证据 |
|---|---|
| Standard / low JUnit | 各 281 项，280 通过、1 项 Windows 宿主条件跳过（POSIX shell 取消）；零失败。`app/build/test-results/test*DebugUnitTest` |
| 两版 Release Lint、签名构建 | `device-cold-probe-build.log`、`device-repeat-fix-build.log`：成功；Lint 零错误，仍有警告 |
| Linux Python 回归 | `linux-repeated-restore.log`：备份 31、个人目录 9、插件检测 8、原有插件管理 27 全通过，无跳过 |
| Linux 实际会话与原子发布 | 同上：11 项通过，含普通/zstd V3、旧记录迁移、并发独占与旧内核降级 |
| JavaScript 兼容与内置插件 | `test-app-activity.mjs`、`test-web-drafts.mjs`、`test-web-compat.mjs` 共 12 项；`test-dsh-builtins.mjs` 3 项通过 |
| 真实手机尺寸网页 | `host-mobile-final.log`、`host-mobile-final/browser-*.png`：393×852，实际工作区选择、文字/图片重载恢复、文件侧栏可视区域与返回关闭，零页面脚本错误 |
| ARM64 原生运行 | `arm-native-check.log`：QEMU + 最终 rootfs Node 24.19.0/glibc 2.39，sharp 编解码、Koffi libc 调用、真实 flock、PTY 模块加载通过 |
| 最终 APK 内容 | `apk-delivery-contents.json`：两版实际 dsh 版本、补丁、内置插件、附件 API、共享模块、rootfs 摘要和 28 个 Ubuntu 包的摘要通过 |
| APK 原生文件 | `standard-offline-tools-elf.json`、`low-offline-tools-elf.json`：所有已扫描 ELF 为 arm64，16 KB 对齐和 RELRO 映射通过；包含新增工具中的 104 个 ELF |
| 版本、签名、哈希 | `delivery-final.json`、最终签名验证与 `release/*.apk.sha256` |
| 锁文件离线复现 | `locked-runtime-build.log`：npm ci 从缓存离线安装 539 个包，重建输出与第一次覆盖层完全一致 |

离线覆盖层 SHA-256：`ab68fc69f354b947dc458e6fae1eb2eb22a9cac6d7bb510981f071cd72e38d4b`。
最终 rootfs SHA-256：`ccaca069a7696ca64814206078194cf951c63d1f4b4765fb5ac4bcb0ad15d956`；展开普通文件 496,639,624 字节，506 个全局别名指向单份依赖树。

## 重新构建

保留既有原始 `app/src/main/assets/offline-rootfs.bin` 和 Ubuntu Python、ADB 等基础资产，不原地改写 rootfs。`offline-rootfs.version` 已纳入源码追踪。新版覆盖层和输入证明为生成文件，不提交。

先运行 `python tools/prepare-dsh-runtime.py --cache app/build/dsh-alpha15/npm-cache`，再运行 `python tools/prepare-ubuntu-tools.py`。两者在缓存已填充时都可追加 `--offline`；npm 生命周期脚本不在宿主运行，deb 的维护脚本只在新解压的 Ubuntu 中由 dpkg 执行。随后通过 `./build.sh` 执行两版 `test*DebugUnitTest`、`lint*Release`、`assemble*Release`。可用 `tools/verify-dsh-upgrade-apk.py` 传入最终两个 APK 路径复核内容。

发布构建仍须配置历史 APK 签名 `DSHA_KEYSTORE`。资产生成器会核对覆盖层摘要、当前补丁、构建器和依赖锁；任一不一致会失败，不能静默装回旧版。`scripts/prepare-dsh-alpha-runtime.sh`、`scripts/offline-provision.sh` 和 `scripts/ci/android-build.yml` 是旧版流程存档，不能作为本版的构建入口；本版使用上述锁文件覆盖层流程。

## 设备覆盖

Android 13 / 4 KB 页设备上的覆盖迁移、真实模型、系统 WebView、Gecko、安装、插件、备份、PTY 和桥接结果见[真机验收记录](device-acceptance-alpha15-2026-09-09.md)。Android 6/7、真实 16 KB 页内核和其他厂商的后台行为仍需补充；本轮没有重新配对无线 ADB，不据此宣称所有 Android 版本或所有外部设备能力验证通过。
