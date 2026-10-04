# rc1.3 验收记录 · 2026-09-07

> 历史记录：本文对应文中日期、版本与当时实际执行的范围；旧命令、证书规则、数据策略和“当前产物”不作为本轮构建或验收入口。现行流程见[接手指南](接手指南.md)、[CONTRIBUTING](../CONTRIBUTING.md)与[本轮约束](audits/build154/POLICY.md)。原字节及摘要保存在[历史文档清单](audits/build154/history-document-sources.json)。


## 交付与不变式

最终 APK 位于 `F:\DSHA_RESTART\release`，历史文件保留。标准版版本名 `1.2.0-rc1.3`、minSdk 30；兼容版 `1.2.0-rc1.3low`、minSdk 23。版本码均为 112，compile/target API 37、arm64-v8a；无 Kotlin 业务代码，环境版本仍为 9。

| 文件 | 字节数 | SHA-256 |
|---|---:|---|
| dsha-1.2.0-rc1.3.apk | 222768679 | `609d09a9d1a954b07903da8f88da16a31660a9007e2cbbba5110b34c33ea3340` |
| dsha-1.2.0-rc1.3low.apk | 303555606 | `445144f08fd93bc518395b9e9a6620aaac5619c5d58e7a672acff141da49a6ba` |

两版 APK 的实际签名证书 SHA-256 均为 `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`，与 rc1 / rc1.1 / rc1.2 发布证书一致；生成器拒绝调试包、错误包名、签名、架构或高低版混配。更新清单签名钥匙没有用于 APK。

## 自动与集成验证

- `:app:testStandardDebugUnitTest`：90 项通过，0 失败、0 跳过，覆盖更新通道与版本码、链接解析、敏感信息脱敏及已有纯逻辑不变式。
- `scripts/test-plugin-manager.py`：Android Ubuntu 容器中 18 项通过。验证预览确认边界、摘要不符和篡改拒绝、更新失败恢复现有实体/来源/历史、唯一上一版回退、停用状态保留、安全模式恢复、目录逃逸防护以及 dsh 运行依赖链接。
- `scripts/test-release-manifest.py`：4 项通过，验证发布新预览时保留稳定版、稳定版接续、相同版本重建去重、拒绝倒退及错误签名身份。
- 两版 Release 构建通过，`lintStandardRelease` / `lintLowRelease` 均 0 errors，分别有 385 / 344 warnings；本次没有清理全库既有与新增的全部 Lint 警告。
- 网站 14 项检查通过，涵盖脚本语法、页面与链接、实际 APK 大小和摘要、下载包、安装入口、更新接口和域名关联。

## Android 13 真机

设备为 Redmi M2012K10C，arm64、4 KB 页。使用同源码的调试构建验收；发布 APK 另作签名和包信息检查。保留原应用数据，不卸载主应用、不清空 rootfs，不填写 API Key、不发送模型请求。

1. 通过真实 npm 链接进入 App 预览，显示实际包名、作者信息、版本及兼容提示，确认前不改变安装状态；确认后安装并显示重启提示。网站指定摘要不符时明确拒绝。
2. `dsh-subagent-model-picker` 0.1.0 → 0.1.1 检查及更新 → 回退 0.1.0 → 再更新通过。损坏包保留原插件，已消费预览自动重新获取，更新成功后不误报旧更新提示。
3. 安全模式禁用/恢复第三方插件通过；从启动页执行安全启动，26 秒后就绪并进入完整 dsh 界面。手工禁用后的状态保留由后端测试覆盖。
4. 诊断复制与文件导出内容一致，测试用 API key / token 均脱敏；证书、Python、npm、pnpm 修复完成并可启动。导出通过 Android ActivityResult + 文件写入验证。
5. 真实 HTTPS 8 MiB 测试 APK 下载：进度、取消、重试、大小和摘要核验通过；Android PackageManager 接受预期测试版本，拒绝错误签名、包名及高低版本。系统安装器解析可用，未实际安装测试更新包。
   实际更新页读取线上正式清单成功，默认预览通道，稳定/预览切换正常；当前 112 不误报为可更新，检查后恢复预览通道。
6. 系统 WebView 为 116.0.5845.92；兼容版按原有规则自动使用 Gecko 143。安全启动的 Gecko 页面已截图观察；标准版直接使用系统 WebView 作实际鉴权与页面渲染验证。

测试生成的插件已删除，原有第三方插件恢复启用；安全模式关闭、内核偏好恢复，Web 进程停止。最后恢复兼容调试版 rc1.3。

## 社区目录

- `dsh-session-health` 0.6.0 与 `dsh-subagent-model-picker` 0.1.1 均核验 npm 发布包 SHA-256，检查实际代码及声明许可证。
- 在独立测试 profile 中完成安装与 Web 启动；会话健康插件的本地 overview RPC 返回成功。未执行收费模型请求，也不据此声称所有模型与设备适配。
- 实测发现并修复导入插件无法解析 dsh peer 依赖的问题，加入依赖链接与目录边界测试。目录详情使用真实 App 截图并说明验证范围。

## 官网与发布流程

生产地址 `https://dsha.cc/`，最终 buildId `96d0840218b2b666`。新版发布到独立目录后校验完整清单并原子切换，保留之前的网站与 APK 下载目录；Nginx 配置检查和 HTTPS 健康检查通过。

`/api/updates.json` 与官网 APK 展示来自同一实际发布清单，`/.well-known/assetlinks.json` 包名及证书与发布 APK 一致。安装页提供 Android intent、自定义 scheme 和未安装时下载入口，375px 表单无横向溢出；线上目录可见 8 项及新安装入口。

公网页面、API、APK Content-Length、Range / 非法 Range、404 检查通过。两个正式 APK 均从公网 HTTPS 完整读取并重新计算摘要，与上表逐字节一致。最后一次站点小版本仅更新发布通道保留支持，APK 字节不变。

当前真机使用调试签名，HTTPS App Link 对正式签名的系统自动关联没有作实际安装后验证；已核验域名声明/包信息及自定义 scheme 的实际唤起、确认与安装流程。该限制不代表已在正式签名设备上完成自动关联实测。

## 复现材料

核心原始输出保存在本地 `app/build/rc13-*.txt` / `.log`，公开可复核摘要另存于 `docs/evidence/rc1.3`。真机测试入口为 `app/src/androidTest/java/com/deepseekharness/app/Rc13Instrumentation.java`，需要专用验收设备及明确的测试插件归属参数；不应在普通用户 profile 上直接执行清理场景。

本版按用户要求由贡献者账号 ym2025szz 发布为 GitHub Pre-release。仓库主页仅更新预览版区域，原 README 的 1.1.10 正文和历史发布保持不变。正式签名覆盖安装的依据为 APK 签名、版本码与前版对比，本次手机实际使用的是同签名调试版覆盖测试。
