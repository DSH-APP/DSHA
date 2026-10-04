# 插件下载、文件导入与 npm 修复

> 历史记录：本文对应文中日期、版本与当时实际执行的范围；旧命令、证书规则、数据策略和“当前产物”不作为本轮构建或验收入口。现行流程见[接手指南](接手指南.md)、[CONTRIBUTING](../CONTRIBUTING.md)与[本轮约束](audits/build154/POLICY.md)。原字节及摘要保存在[历史文档清单](audits/build154/history-document-sources.json)。


两版发布文件已替换到 F:\DSHA_RESTART\release，文件名和版本名不变，版本码从 109 升到 110。
证书仍为原发布证书，环境版本保持 9，不触发重新解压。

## 修复内容

1. 随包提供 certifi 2026.7.22 的 Mozilla CA 集合；RuntimeTools 在首次解压、插件操作和终端启动时补齐。
   Python、Node/npm、Git、curl 共用证书，保持 TLS 验证，不需要先运行安装第 2 步。
2. GitHub archive 链接直接转到官方 codeload，保留指定分支或标签。
   插件结果解析允许 JSON 后追加 proot/proroot 退出提示，界面显示实际错误原因。
3. 默认导入优先系统 DocumentsUI，增加「其他文件选择器」的 GET_CONTENT 入口；
   处理 data URI、ClipData、取消/空返回及旧文件管理器的读取授权。
4. 恢复 npm/npx 的可靠启动入口，补齐登录 shell 的 PATH 和证书环境；增加
   `dsha-plugin install 包名@版本`，下载 npm 发布的插件并走同一套校验、依赖准备与登记流程。
5. 新手机发现终端 JNI 的 RELRO 超出了 4 KB 映射范围，旧 Android linker 报
   “can't enable GNU RELRO protection / Out of memory”。改用 max-page-size=16384、
   common-page-size=4096；同时检查 4 KB / 16 KB 页下的 RELRO 覆盖，保持原有 LOAD 对齐。

## 新手机验证

设备为 M2012K10C（Redmi K40 Gaming），Android 13 / API 33、4 KB 页大小。
最初未安装 com.dsh.client，先安装 low debug，再相同签名覆盖 standard debug 和 low debug。

- 两版都通过界面下载并安装用户提供的 Minglink/dsh-infinite-gen-3 master.zip，显示 0.5.0。
- low 通过系统文件选择器导入本地 ZIP；standard 的备用文件选择器也完整返回文件并导入成功。
- 手机始终没有 /etc/ssl/certs/ca-certificates.crt，且没有运行基础工具安装，验证的是随包证书路径。
- 两版 PTY 均成功运行 npm 11.17.0，安装 is-number 7.0.0 并验证模块可加载。
- 两版通过 dsha-plugin 下载 npm 上的 dsh-web-mobile 2.3.0，并在独立测试 profile 登记成功。
  独立 npm 测试目录与临时 ZIP 已清理；用户指定的插件保留在主插件列表中。
- 未调用模型或执行用户插件的提示词/测试脚本。Android 6—12 的旧机和第三方文件管理器没有逐一验收。

3 个结果解析 JUnit 用例、9 个插件 Python 用例通过；两版 Release 构建及 Lint 无错误，
已有非阻断警告保留。标准版 819 个、兼容版 833 个 arm64 ELF 的对齐和 RELRO 检查通过。

## 发布文件

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| dsha-1.2.0-rc1.1.apk | 222706119 字节 / 212.39 MiB | bdc62ffa5c278ede82b50537f03a6b438c42b4bf91a4a36e53c36ebd6ebaa05b |
| dsha-1.2.0-rc1.1low.apk | 303521988 字节 / 289.46 MiB | 509072d2001d5d4e38ca6103dc8a110fb1da11bd8698772265ef86ea51bca263 |

对应 .apk.sha256 已重新生成。原签名 SHA-256 为
e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5，已逐包核对。
