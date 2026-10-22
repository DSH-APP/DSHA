# 构建、签名、源码分发与证据

> **当前发布（2026-10-23）**：[v0.2.1-rc.2 正式版](releases/v0.2.1-rc.2-notes.md)，沿用2100标准APK / `dsh.android.client`。用户已授权以贡献者身份直接公开发布至 DSH-APP/DSHA，不创建PR；覆盖此前公开暂停规则。下方较早的“仅私有交付/公开暂停”按当时记录阅读，不作为当前发布规则；本次发布不修改应用实现与APK字节。

核验日期：2026-10-21。当前为F11/1157/code190工作树，参考已发布F10 `087724c`；按当前构建脚本、CI锁和发布规则整理。可执行构建步骤以[BUILD](../BUILD.md)为入口；最终测试与实际APK结果见[F11记录](releases/v0.2.1-rc.2-build190-fix11-20261021.md)，附件与远端状态由关联Release和独立外部回执核验。

## 身份分别来自哪里

| 身份 | 事实源 | 维护规则 |
|---|---|---|
| APK code / name / 文件名版本 | [app/build.gradle](../app/build.gradle)的`versionCode/versionName/dshaApkNameVersion` | 三者含义不同；文件名可维持常规版本，不由tag猜code |
| DSH内容 | [package.json](../tools/dsh-runtime/package.json)及[锁](../tools/dsh-runtime/package-lock.json) | 固定实际包内容，不用APK版本推导数据格式 |
| Ubuntu基础身份 | 分包recipe、固定输入与受管descriptor | UI变更不自动递增base；普通DSH更新不等于重建Ubuntu |
| runtimeId/契约 | [prepare-runtime-descriptor.py](../tools/prepare-runtime-descriptor.py)生成的实际内容证明 | 内容摘要与启动器/桥/数据契约分开；不可手改成期望字符串 |
| 发布tag/源码commit | 实际Git提交和Release | commit不是APK SHA；先冻结源码再生成附件与外部回执 |
| 包名/历史证书 | [ci/release-identity.json](../ci/release-identity.json) | `dsh.android.client`，E7E3完整SHA-256，v1/v2/v3；不换debug/A3签名 |

不是所有版本字段都应改成同一个数字。变更哪一层就核哪一层的调用方、锁和生成器，最后将源码、输入、APK和Release绑定。源码ZIP自身SHA写在外部Release/回执，不写入ZIP内文件形成自引用。

## Git与完整源码ZIP

Git保存应用源码、资源、锁、recipe、校验脚本及必要且有notice/锁的小型二进制输入；大型rootfs、DSH、recovery生成归档不入Git。只有源码的检出可做source-only检查，不能把缺大归档构建成“正式精简包”。

完整源码ZIP另外包含经 [ci-assets.py](../tools/ci-assets.py)逐SHA核对的固定原始输入，并按项目路径直接展开，不嵌套输入ZIP。F11当前输入包738,103,383 bytes，SHA-256 `f482503ac88b71b2f31cee802df557c637589dfeee69aca2ee8900608f8cdba3`；它不是完整源码ZIP的大小或摘要。构建仍需配置JDK17、Android SDK/NDK、Python与Node等工具链。APK中的变换层不能逆还原全部原始构建输入；不要从APK推测补齐任意latest或旧缓存。

Windows本机当前源码在build190 worktree；F盘旧173不是当前构建源。其他机器从关联完整commit/tag或完整源码附件取得同一输入。LF规则、managed bytepin、小型二进制、历史原件和6项必需审计JSON白名单见[CONTRIBUTING](../CONTRIBUTING.md)，不做全库行尾重写或上传用户/设备原始资料。

## 分阶段验证

下面是责任分组，不宣称任意一次脚本等于整组通过。关联命令和真实结果应写入本轮回执；检查失败保留原件，补跑另记。

| 阶段 | 入口 | 必须保留的结果 |
|---|---|---|
| 源码与导航 | `check-source-hygiene.py`、`check-document-links.py`、host manifest检查 | 精确文件集、现行断链、历史本地证据分类，不能靠忽略现行断链通过 |
| 固定输入与descriptor | `ci-assets.py`、`verify-runtime-descriptor-source.py`及生成器check | 实际输入SHA、缺失项、哪些内容复用旧逐字节证明 |
| 宿主/浏览器 | `run-host-tests.py`各stage、真实锁定runtime夹具 | 环境、选择集、平台跳过、预期负控，不称手机实测 |
| 两flavor JUnit / Release Lint | Gradle对应任务 | 执行类/XML的tests/failures/errors/skipped、缓存/增量、Lint错误及警告 |
| 精确APK内容 | `audit-standard-apk.py`、`verify-dsh-upgrade-apk.py` | APK身份、受管资产/DSH、manifest/DEX、对应源码与SHA |
| 应急/插件/许可 | `verify-recovery-apk.py`、`verify-plugin-upgrade-gate.py`、`verify-third-party-apk.py` | 独立应急锁与资产位置、插件策略、notice及实际APK内容 |
| 签名与交付 | Android `apksigner verify --verbose --print-certs`及发布负责人清单 | 包名/code、`debuggable=false`、证书、ABI/API、APK/ZIP大小SHA、远端附件状态 |

[check-release-prerequisites.py](../tools/check-release-prerequisites.py)只无密钥检查发布身份JSON格式/包名/证书字段，不能替代实际APK签名、软件回执或手机验收。使用既有正式密钥环境，不把私钥、密码或凭据写进命令回显与源码。历史A3为旧验收规则，本轮正式发布/覆盖验收统一E7E3；不得卸载后用新证书替代。

实际“七项APK门禁”是具体交付时的选集与日志，不等于上表每一行只运行一次工具；精确组成和执行结果需看那次回执，不从本页推算已通过项目数。脚本参数以当前`--help`/入口为准，避免将历史审计工具默认路径指向新交付。

## 测试数字如何读

F10各124类/785项、fix1各511项、build170各45项分别是其选择集和执行时点；它们不是静态Java文件数或 `@Test`文本计数。动态/参数化测试、flavor条件、skip、测试环境初始化失败和重跑都会改变口径。源码有某个测试不代表本轮执行，缓存通过也需明示。

统计应保留：对应源码/输入、任务名和选择类、XML集合、tests/failures/errors/skipped、初轮失败、补跑原因、增量与缓存、未跑范围。原始本机回执不在源码包时明确标本机材料；Markdown中引用其名字不意味着读者可以独立下载复算。历史发布摘要和现行源码结论不能相互挪用，见[文档缺口映射](documentation-gap-matrix.md)。

## 本地交付、发布与设备是三个结论

本地APK核验后按用户规则放入 `release/<文件名版本>/`，只放常规APK与`.apk.sha256`；同版本旧件进`history/`，历史Low原字节保留。源码及证据分别进入`artifacts/source/`和`artifacts/deliveries/`，不能把`app/build`中间产物称交付。目录检查不是GitHub上传证明。

发布仅在已授权范围内核对实际仓库、commit/tag、APK/完整源码ZIP及远端附件摘要；不会因文档补齐自动发布、修改官网或原仓库。新APK软件通过不继承旧手机实测，本轮暂停设备/ADB/对话和持续四/八小时验收。

若以后按授权做正式覆盖，应先核对同包名、E7E3、非调试和版本，先正常停止并核退出；不卸载、不清数据、不在正式数据做强杀/坏插件等注入。[更新说明](update.md)描述当前安装器边界，旧173受管安装记录不能套用到当前F11的build170基座。完整冷网络、Android6/7、16KiB、ROM/外部模型矩阵只能按各自真实证据声明。
