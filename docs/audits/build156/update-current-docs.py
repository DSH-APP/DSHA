"""One-time build156 current-guide edits; retired original documents stay untouched."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[3]

plugins = '''# 插件安装、更新与分享

本页描述当前 DSH **0.2.0-rc.2** 的 DSHA 入口。具体应用版本和本轮已执行的验证范围见 [README](../README.md) 与对应交付报告；旧设备报告不能作为新包的验收回执。

在插件市场粘贴链接、从系统文件选择器导入本地包，或使用网站提供的 DSHA 唤起链接。应用解析实际包，核对格式、路径、包名/版本、实际依赖与摘要，再由插件事务提交并启用。现行正式插件无需原生审阅确认。网站浏览本身不会安装插件；只有用户主动打开安装入口才会继续安装。启停和更新后按应用提示重启 Web。

## 支持的来源与包契约

- GitHub 仓库简写 `owner/repo`、仓库 URL、SSH 地址、tree/blob 子目录链接，以及 archive/codeload/Release 附件。
- Release 页面仅有一个支持的压缩包时可直接解析；有多个候选时选择实际附件链接。
- HTTPS ZIP、TAR、TAR.GZ、TGZ，以及 npm 插件包名；包含多个链接的分享文字须先选择目标。
- 本地导入按实际内容识别格式；文件扩展名和 Provider MIME 不能代替内容核验。系统 DocumentsUI 不可用时可选择其他文件选择器。

`package.json` 必须有合法 npm 名称与版本，符合当前 bundle 声明，并有实际存在的 patch 和入口。普通 npm 库不会因名称包含 dsh 自动成为插件。归档不得通过越界路径、包外链接、同名冲突或缺失入口绕过检查。展开及下载限额以当前应用实际检查为准，超限会报告原因。

优先使用作者构建好的 Release 或 npm pack 发布包。Android 容器为 Linux arm64/glibc；依赖包含原生模块时，需要适配该运行环境。声明兼容版本、作者测试和本机实测分别记录，不能把作者说明当作依赖内容或设备可用性证明。

## 依赖、脚本与实际权限

正式依赖安装允许未锁定重解析，使用随包 pnpm 10.34.5 的生产依赖安装；依赖的 `postinstall`、build 等生命周期脚本以及 pnpmfile 可以运行。安装会保存实际锁与目录摘要，后续复用/恢复检查真实历史内容。旧锁不会强制种入新的在线候选；离线恢复不在原件中重新解析依赖。

拉取 npm 包时的 `npm pack --ignore-scripts` 只是只读下载阶段的限制。通常使用只含依赖声明的临时 manifest 准备依赖，这时不执行插件源码自身的 scripts；复用作者原生锁的路径会使用原 manifest。因此不能笼统承诺“不执行安装脚本”，也不能把包下载阶段的参数当作最终安装权限。

插件、脚本和普通终端均在应用 UID 下运行，可读取和修改该 UID 可访问的内容；proot/proroot 不是第三方恶意代码的独立沙箱。包与事务检查提供内容和回切保证，不是对作者代码的安全认证。实际设备操作仍经当前原生能力授权和执行侧策略。请依据真实来源选择第三方代码。详细边界见[安全模型](security-model.md)。

## 提交、失败和用户意图

安装先准备候选，记录原件、固定目标、前后摘要与实际依赖，再切换安装目录。未提交日志受维护门禁保护；恢复必须按本次记录核对进程退出和目录身份。已提交的旧日志不能回滚后来修改的用户数据。失败候选、未知原件和历史依赖不会由临时目录清理隐式删除。

生命周期脚本已经运行后发生网络错误，不自动改源重放安装。取消、超时或无法确认退出时保留对应现场和屏障；完整断电、Android 文件系统和设备行为仍按独立验收记录声明。

同名更新保留用户主动禁用和安全模式意图；升级只移除旧版自动审阅标记。系统插件从当前签名 APK 的受管证明重建，与用户源码和依赖分层。启停、来源信息和实际加载结果以插件管理及启动诊断为准。

## 导出、删除与终端

插件导出包含所选用户插件的真实源码、已有依赖和绑定关系。系统插件字节由当前 APK 提供，用户备份只保留其启停意图。内容未完整、未知或有依赖警告时如实报告，不能改成“完整通过”。

删除第三方插件会移除该插件安装与登记，保留聊天、配置、其他插件、共享依赖与外部导入源。需留存时先导出。内置可选组件使用开关，官方核心由受管环境更新，不由第三方包替换。

普通终端可以使用 `npm` / `npx`，与插件安装共用随包 CA 和 RuntimeTools 环境：

```sh
npm install 包名
npm install -g 工具包名
dsha-plugin install @作者/插件@1.0.0
```

普通 `npm install` 不自动修改 DSH 启用列表；`dsha-plugin` 通过当前插件入口处理实际候选。不要用裸 shell 修改事务现场来绕过维护屏障。

## 验证入口与限度

相关宿主回归包括 `tools/test-plugin-dependencies.py`、`tools/test-plugin-transactions.py`、`tools/test-plugin-downloads.py` 和真实锁定运行时的原生插件管理测试；完整门禁入口见[接手指南](接手指南.md)。每次交付报告记录实际运行命令和结果。本页不宣称已完成所有远程源、第三方代码、手机覆盖安装或掉电矩阵。
'''

skills = '''# DSHA 设备操作技能

`agent-skills/` 是两个技能的唯一源。网站构建从此复制实际 SKILL.md 和仓库 MIT 许可；网站目录中的历史测试记录不能证明修改后的文档已在当前手机复验。

| 技能 | 用途 |
|---|---|
| `device-shell` | 通过应用管理的设备通道执行明确授权的 Android 命令，并核对身份与结果。 |
| `screen-ocr-operator` | 使用实际授权的读屏/截图接口核对目标、方向和坐标，再小步操作。 |

将技能目录复制到当前 Agent 的技能搜索目录，例如 `~/.agents/skills/` 或项目的 `.agents/skills/`，保留 `<name>/SKILL.md` 结构。它们是工作流文档，不通过“导入插件包”安装。

Root、Shizuku 或 ADB 任一通道在“设备能力授权”就绪后可使用；原生层在发送前选择实际通道。先用 `/root/dsh-bin/adb-shell "id"` 核对返回身份，不能假设总是 uid=2000、要求先配 ADB 或借裸 adb 绕过入口。结果未知时不换通道重放。

屏幕、短信、录音等能力有各自的授权与运行代次，不能从备份继承。截图可能含私人内容；文件结果只清理本次工具明确创建的临时文件，不删除用户历史原件。原生 PNG 结果与当前读屏接口是否可用，以实际应用、系统和授权为准。图片发送到用户选择的模型服务时，适用该服务的请求与费用规则。

技能不包含密钥、设备序列号或连接地址。确需直接调用本机桥，使用技能中受管私有请求头文件示例，不把 token 放入 URL、参数或日志。当前源码文档检查不等于所有 Agent、模型和 Android 设备组合通过。
'''

build = '''# DSHA 现行构建说明

先读 [README](README.md)、[AGENTS](AGENTS.md)、[贡献流程](CONTRIBUTING.md) 与[接手指南](docs/接手指南.md)。应用版本以 `app/build.gradle` 为准，DSH 依赖以 `tools/dsh-runtime/package.json`/锁文件为准，完整交付和未验证范围以本轮报告为准。

本轮只做本地正式产物与软件验证，不操作手机、不生成额外调试/审计 APK、不上传 GitHub 或部署网站。JVM 测试名称中的 Debug 表示测试变体，不授权 assembleDebug 或安装额外 APK。

## 环境与 flavor

| 输入 | 现行要求 |
|---|---|
| JDK / 原生语言 | Java 17，无 Kotlin |
| Gradle / AGP | 仓库 Wrapper 9.3.1 / AGP 9.1.1 |
| Android SDK | `platforms;android-37.0`、build-tools 36.0.0；compile/target API 37 |
| NDK | 26.3.11579264；终端与 Low proot 按各自受管入口使用 API 23 与真实 ELF 对齐检查 |
| Python / Node | 生成器 Python 3.9+，CI 锁定版本见 `ci/toolchain.lock.json`；Node 24 |
| Standard | minSdk 30，系统 WebView，arm64-v8a |
| Low | minSdk 23，额外 Gecko 备用内核，arm64-v8a |

两版共用 `com.dsh.client` 与用户数据，不能并排安装。最低 API、ELF 16 KiB 对齐与实际 Android 6/7/16 KiB 设备可用性是不同证据。Windows 可以开发和构建；Linux 特有用例不能用 Windows 跳过结果代替。

配置本机 `local.properties` 的 `sdk.dir`，并将 JAVA_HOME、ANDROID_SDK_ROOT/ANDROID_HOME、DSHA_PYTHON 指向已有工具。不要复制开发者的绝对工具链目录。版本与固定 APK 文件名由 `app/build.gradle` 的 `versionCode`、`versionName` 和 `dshaApkNameVersion` 定义，不在本页再维护另一组当前数字。

## 输入准备与证明

离线 rootfs 和大型生成物不提交；固定输入、生成順序与 CI 传递方式见 CONTRIBUTING。Ubuntu 基础环境身份独立于 DSH 与 UI 版本。`offline-rootfs.bin` / `dsh-runtime.bin` 为 split-runtime-v1，独立应急 pinned 输入由 `tools/recovery-runtime/lock.json` 约束，不能用正式环境作为救援依赖。

受管输入发生变化时，按实际生成入口更新资产，再显式生成证明：

```bash
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/prepare-test-runtime.py
```

生成的证明需与真实字节核对；普通 Gradle 构建使用 check，不能靠旧缓存跳过新输入。当前夹具由 `app/build/test-runtimes/current.json` 及源/内容指纹约束，不能把任一旧目录称为当前运行时。

## 软件验证

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \\
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

Windows 使用 `gradlew.bat`，并以真实 Python 可执行文件代替不可用的系统别名。无法运行 AGP/aapt2 时，`tools/run-unit-tests.py` 是纯逻辑后备，不替代 Android 全编译、APK 或完整资源门禁。平台跳过、未运行项与 Lint 警告如实分类，不写成通过。

`tools/verify-plugin-upgrade-gate.py` 检查当前自动提交/启用、用户禁用与安全模式、实际依赖、插件事务及受管资产。正式插件允许未锁定解析、生命周期脚本和 pnpmfile；不能把历史“原生审阅/依赖冻结”的测试口径复活为当前要求。

## 正式打包与签名

显式提供已有 `DSHA_KEYSTORE`、`DSHA_KEYSTORE_PASSWORD`、`DSHA_KEY_PASSWORD`；可配置历史 `DSHA_KEY_ALIAS`。签名身份以 `ci/release-identity.json` 为准，保持 E7E3，不以 debug、A3 或新证书替代。密钥文件名含 debug 不表示正式 APK 可调试；必须核对实际 `debuggable=false`。

```bash
./gradlew :app:assembleStandardRelease :app:assembleLowRelease
```

软件交付需两版完整单测、Release Lint、实际 APK/资产/ELF、签名、插件/应急门禁和精确摘要回执。JNI 静态审计使用对应 release APK，不推荐旧 debug 产物。Low 必须保留 v1；用 `apksigner verify --min-sdk-version 23` 明确核对 v1/v2/v3。

产物固定放 `release/dsha-0.2.0-rc2.apk`、`release/dsha-0.2.0-rc2low.apk` 及 `.apk.sha256`。同版本同 flavor 按当前授权替换，其他历史版本保留；`app/build` 中间包不冒充交付。源码快照、台账和报告绑定最终字节。

`ci-package` 的未签名完整包只用于软件验证，不用于手机安装或 release。配置文件不能证明受保护环境、签名线上运行和仓库保护已配置；实际部署与发布需要相应证据。

## 手机与历史范围

本轮不执行手机操作。以后在对应授权范围内验收，只用同签名正式 APK 覆盖安装，保留对话、配置、插件与文件；不卸载、不清数据、不对正式数据强杀或注入故障。设备回执须绑定实际包摘要、包名、版本码、证书及非破坏性结果，不能从旧报告移用。

旧 build130、build142 及早期调试/独立审计包的步骤与设备结果属于原日期/产物。修改前本页精确原字节保存在 `tools/history/documents/BUILD-build155.md`，摘要见 [本轮保留清单](docs/audits/build156/source-docs-retained.json)；该原件不参与部署或测试发现。历史备份、配置、旧数据与未知原件保护继续维护，退休工具不重新激活。

Linux tmpfs 发布回归不证明持久磁盘/掉电/Android FUSE，Android 6/7、厂商 Root/Shizuku、16 KiB 和外部模型范围依实际回执声明。更多职责与控制见[模块图](docs/module-map.md)和[安全模型](docs/security-model.md)。
'''

retained = ROOT / "tools/history/documents/BUILD-build155.md"
if not retained.exists():
    retained.parent.mkdir(parents=True, exist_ok=True)
    retained.write_bytes((ROOT / "BUILD.md").read_bytes())
original = retained.read_bytes()
record = {"schemaVersion": 1, "items": [{"path": "BUILD.md", "retainedSource": retained.relative_to(ROOT).as_posix(), "sha256": hashlib.sha256(original).hexdigest(), "bytes": len(original), "use": "historical original only; not current build instructions"}]}
(ROOT / "docs/audits/build156/source-docs-retained.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

for relative, text in (("docs/plugins.md", plugins), ("agent-skills/README.md", skills), ("BUILD.md", build)):
    (ROOT / relative).write_text(text, encoding="utf-8", newline="\n")
print("updated current plugin guide and canonical skill README")
