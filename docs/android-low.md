# Android 6—12 兼容版

> 历史记录：本文对应文中日期、版本与当时实际执行的范围；旧命令、证书规则、数据策略和“当前产物”不作为本轮构建或验收入口。现行流程见[接手指南](接手指南.md)、[CONTRIBUTING](../CONTRIBUTING.md)与[本轮约束](audits/build154/POLICY.md)。原字节及摘要保存在[历史文档清单](audits/build154/history-document-sources.json)。


交付文件位于 `F:\DSHA_RESTART\release`：`dsha-1.2.0-rc1.2low.apk` 与同名 `.apk.sha256`。
当前包大小 303,522,074 字节（289.46 MiB）；SHA-256：
`dcc64d925ea1697e930753378e95724125cd4688dd58335348bd0c3887928403`。
本轮全屏和插件网站入口见 [rc1.2 发布记录](release-rc1.2-2026-09-06.md)；此前下载、导入和终端修复见 [修复与真机记录](plugin-download-npm-fix-2026-09-05.md)。
V1/V2/V3 签名验证通过，证书与旧正式包一致。

兼容版版本名为 1.2.0-rc1.2low，versionCode 111，minSdk 23；与标准版共享
applicationId com.dsh.client、配置路径、发布证书及离线环境版本 9。
只提供 arm64-v8a；32 位 ARM 手机无法安装。没有设置 maxSdk，Android 13+ 建议使用标准版。

## 功能与差异

插件链接识别、导入、导出、删除、开关、布局、安装六步修复、PTY 终端、备份与本机对话共用 main 源码。
low 仅增加 Gecko 预览和旧系统权限，不复制一份功能代码。标准版不会依赖 Gecko。

- Android 6/7 或 Chrome 版本低于 118 的 WebView 自动使用内置 Gecko 143；配置页也可手动启用。
- Gecko 接入本机 token 鉴权、返回、桌面模式、重试和系统文件选择上传；外部导航交给系统浏览器。
- Java API 使用 core library desugaring 回补，Python/pnpm 命令更新不再调用 API 26 的 Files。
- Android 6—10 恢复存储权限声明，Android 10 申请 legacy storage；11+ 继续使用原有所有文件访问设置。
- proot/loader 以 NDK API 23 重编，避免原二进制依赖 libc 的 Android N/O 符号；终端 JNI 也以 API 23 重编。
  getifaddrs 的新系统网络兼容路径通过动态查找，旧系统保留自身 netlink 行为。保留 link2symlink 和 L2S 挂载。

系统能力不能由 APK 补出：无线调试配对码需 Android 11+；无障碍手势需 Android 7+，
无障碍截图需 Android 11+。旧系统可继续使用受支持的读屏/节点操作、Shizuku 或已建立的 ADB 通道。

## 构建

使用 BUILD.md 的 JDK/SDK/Python 环境，配置原发布密钥后执行：

```powershell
./gradlew.bat :app:assembleLowRelease
```

产物：app/build/outputs/apk/low/release/app-low-release.apk。
标准版任务为 assembleStandardRelease；build.sh 无参数默认只构建 standard debug。

重编原生组件：

```powershell
python tools/build-low-proot.py --ndk F:/DSHA/_toolchains/android-sdk/ndk/26.3.11579264
./tools/termux-jni/build.ps1 -MinApi 23
```

两版同包名、同签名、同版本码 110，可在系统版本允许时相互覆盖；不能同时安装。
相对旧 rc1（108）为升级。Android 6 需要 V1 签名，发布前应使用 apksigner 在 API 23—32 范围验证。

## 验证范围

- assembleLowDebug、assembleLowRelease、lintLowDebug、lintLowRelease 和标准版 Java 编译通过。
  low release Lint 为 0 errors，保留已有样式、翻译等警告，没有用 baseline 跳过 API 错误。
- proot/loader、终端 JNI 对照 NDK API 23 系统导出符号核查，未发现缺失的强制依赖。
- Gecko AAR 声明最低 API 21；terminal-view/emulator 0.118 的 API 26 自动填充回调已核查，
  其 minSdk 24 声明只在 low manifest 中覆盖，Java 回调由相应版本系统调用，JNI 已重编。
- 在已连接的 Android 16 / 4 KB 手机验证了重编 proot 启动、Gecko 鉴权及首页渲染、插件列表读取和 PTY。
  这只能确认新路径可运行，不代替 Android 6—12 真机验收。
- 目前没有 Android 6—12 设备，尚未逐版本验证旧内核、厂商 ROM、长时保活、无线 ADB 和上传；
  Node 24 及原生插件在旧内核上的行为还需要对应设备确认。

Gecko 143 是面向旧系统的固定版本，后续新内核不再支持 Android 6/7，不能承诺与新版浏览器具有相同更新周期。
相关说明：[Mozilla 最低 Android 版本](https://support.mozilla.org/en-US/kb/will-firefox-work-my-mobile-device)、
[Android Java API 回补](https://developer.android.com/studio/write/java8-support)。
