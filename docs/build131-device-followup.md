# build 131 真机补验：全新审计安装

2026-09-14，用户要求继续真机验证。本记录追加本轮结果，不改写先前交付清单和历史验收证据。

设备：Redmi M2012K10C，Android 13 / API 33，arm64，4 KiB 页，系统 WebView 116.0.5845.92。标准版随后同签名覆盖为兼容版，兼容版使用内置 Gecko 完成真实握手。两版均为 build 131 同源码的非调试独立审计包，包名 `com.dsh.client.stabilityaudit`；生产 `com.dsh.client` 未被安装、卸载或修改数据。

本轮开始前新包尚不存在，安装成功后完成离线环境准备与首次运行验证，补齐上轮 USB 安装许可阻挡的这一项。冷环境夹具在解压前创建两份合成对话/项目文件，并验证准备后字节保留；随后真实核对原生模块、鉴权、存储/会话写后重开、网页与后端握手及 guest 退出。它不等于全部首次欢迎/系统授权流程或所有设备冷安装均已验证。

| Flavor | 验收项目 | 结果 | 报告 UUID |
|---|---|---|---|
| standard | runtime | PASS | `7549caef-521f-4ce4-94de-b1f78e76803a` |
| standard | core | PASS | `9728d858-47ae-4058-803b-cfc032764e35` |
| standard | network | PASS | `aa8aeb31-a61d-45d3-8d1e-0e62438ded71` |
| standard | bridge | PASS | `ab37024c-f5e9-4848-bfad-7e20bb31f0d7` |
| standard | credential | PASS | `6aa7c6b3-124b-439e-8d08-bdef6623c38a` |
| standard | workflow | PASS | `80b829f6-3c7e-4252-b72d-9354aecf5456` |
| standard | retained | PASS | `6f2512f9-4ed7-43a0-9a01-ecb3894c75b8` |
| standard | io | PASS | `6e378c40-a20e-4992-b90e-8bfba2b719a3` |
| standard | issue67 | PASS | `333b26e0-e7cf-4735-a907-2f6f9f09e3df` |
| standard | attachments | PASS | `a746e858-c6ce-49a6-a29e-8bae6d90a433` |
| standard | plugin_workflow | PASS | `7145551b-f152-44b8-ae08-f8733ae13abe` |
| standard | plugin_recovery | PASS | `62c6312e-64b5-4e82-9fe1-e1d9c005252b` |
| standard | ui | PASS | `2a19f0be-43b7-49f2-973f-3429529348d2` |
| low | runtime | PASS | `6f0d32d5-b8cc-4e39-9ef7-70f40920614c` |
| low | network | PASS | `08040bbf-4cad-46fb-9a15-2ab13d6cd280` |
| low | credential | PASS | `f5684240-8bd3-4558-a25c-baa97cbcfab5` |
| low | workflow | PASS | `a7e29c28-130d-4241-888a-ca561d82168b` |
| low | retained | PASS | `ffb5255e-9ca3-47f5-b7d4-4bff884045d7` |
| low | io | PASS | `76b12e5c-c6e9-4c05-95ba-8dd4bc33c1ac` |
| low | issue67 | PASS | `10be68d4-8cbc-463d-97c4-9f683900a8e1` |
| low | attachments | PASS | `c490eb94-d2b3-4843-844e-d4527795a59a` |
| low | plugin_workflow | PASS | `b50ddb57-e936-4230-9b9c-f4be7201a10f` |
| low | plugin_recovery | PASS | `a73f54d7-e849-40d4-a149-2f231f92b436` |
| low | ui | PASS | `981c4dd7-875c-4957-a7cd-b860f59a620a` |

共 24 个最终验收批次通过。报告位于 `app/build/backup-device-validation/<UUID>/result.json`，原始输出为同目录 `instrumentation.log`；两版 UI 各有 18 场景和截图。附件测试在私有目录/FUSE 各执行 11 个断言。脚本状态回归使用真实坏插件、管道阻塞与进程终止；凭据永久失效/暂时失败部分仍是明确异常注入，不冒充实际锁屏或生物识别变更。

标准版额外执行宿主核心及本地 3090 桥；两版都执行网络、凭据、无 Bash 备份恢复、旧树救援、SAF 取消、#67 与真实 PTY、私有/FUSE 附件、插件审阅/真实加载失败恢复、原生插件事务恢复和双语短屏/旋转。标准→兼容是同一审计安装的覆盖及受管更新路径，不是第二台设备或独立的兼容版最低 API 冷装。

`bd78f842-1a45-4e02-aa90-f69d772d8acb` 记录电脑端 ADB 5037 连接失败，测试未成功派发，没有应用结果；保持原报告，恢复 ADB 连接后新建批次重测通过。未将其计作 App 缺陷或忽略失败记录。

生产安装最后只读核对：`versionCode=129 minSdk=30 targetSdk=37`, `versionName=0.1.5-rc2`, `lastUpdateTime=2026-09-12 17:44:24`。交付 APK 与受检源码摘要均与原 build 131 清单一致；没有发现需要修改生产代码的新增问题，因此没有生成新版本或替换交付 APK。

机器索引：`app/build/device-followup-build131/summary.json`，含本轮审计包摘要、报告摘要、全部批次及重试记录。精确重跑入口：

```powershell
python tools/run-backup-device-audit.py --serial fmuoeujvonizjj4d --package com.dsh.client.stabilityaudit --apk app/build/outputs/apk/standard/deviceAudit/app-standard-deviceAudit.apk --mode runtime
```

构建沿用 `tools/device-backup-audit.init.gradle`，属性 `-Pdsha.auditPackage=com.dsh.client.stabilityaudit`，任务 `assembleStandardDeviceAudit` / `assembleLowDeviceAudit`；构建日志为 `app/build/device-followup-build131/audit-build.log`。其它模式见上表，兼容版使用对应 low 审计 APK。不得将包名改为正式应用。

仍未连接 Android 6/7、API 30、反馈中的 Android 16 平板或 16 KiB 真机；完整真实历史备份样本、长期息屏/应用能耗、多终端/文件选择完整矩阵的缺口保持。此次通过不能推定这些场景通过。
