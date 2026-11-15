# DSHA 0.2.1-rc.5-20261106.0010-dsh0.2.2-rc.1 / build328

- 修复 0.2.2 环境执行 `gh` 报「managed GitHub CLI is unavailable」：官方 GitHub CLI v2.103.3（linux-arm64）作为第 12 项固定输入 `gh-cli.tar.gz` 纳入受管资产，安装时只提取固定成员、二次校验 SHA-256 并原子发布到 `/opt/gh/bin/gh`；整树环境重建按受管注入，不再依赖用户手工安装。
- gh 包装脚本在二进制缺失或损坏时按固定 URL 与固定摘要自举安装，任一步失败都 fail-closed 并打印可复制的恢复命令；删除了声称「bundled」但实际不存在的误导文案。
- 新增 `util/MaintenanceToolPaths` 明文契约与重建前有界预检：`/opt` 作为用户工具目录保留，`/usr/local/bin` 属系统树不保留，`usr/local/share/dsha` 由受管注入；`/opt` 条目超过 10 万时在移动旧树前拒绝重建。
- 验证范围：宿主测试、contracts 整组与源码/描述符/第三方声明门禁；本机未做真机验证。版本码提升为 328（基于 build327）；APK 因内置 gh 归档增大约 13.0 MiB。
