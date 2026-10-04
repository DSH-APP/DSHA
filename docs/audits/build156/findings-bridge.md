# build156 bridge 核对与修订

本 lane 覆盖分配的 8 个 ID。逐项处置、实际命令、范围与残余见 [review-bridge.json](review-bridge.json)。没有执行手机、ADB、Gradle、APK 构建或上传。

- D-SEC-06：原生及 ADB FILE 不再在路径检查后执行外部 cp/mv/rm；统一描述符执行、逐级 NOFOLLOW、打开后 fstat 再截断、链接与特殊节点拒绝。ADB 新入口绑定当前安装 APK/UID，argv 保持数据，失败及未知结果不回退重放。保留普通文件操作的部分效果语义，不能称整批事务。
- D-SEC-04：主屏敏感确认固定初次 ScreenTarget 包名，聚焦编辑节点与运行/授权代次继续复核；敏感启动在实际 API 前再核对本轮授权。
- D-SEC-05：LAN 关闭及对应停止撤销旧凭据，旧偏好不会复活，cookie 12 小时。UI lane 默认遮蔽 token，明确显示/复制及 HTTP 明文提示。LAN HTTP 是现行契约。
- D-SEC-19：Java 是短信根表的唯一生产者，Python 使用真实计划根表；不可变导出防止回写原生规则。镜像/采用存储路径和遍历祖先继续拒绝。
- D-SEC-03 / D-SEC-14 / D-ADB-01：核对当前 header 鉴权、虚拟显示身份与旧死标记处置；补 transport 门禁、撤掉其它死确认接口。D-NET-01 保留已授权的用户 HTTP 能力，不重启外部报告的全面禁用方案。

跨 lane：应用启动/剪贴板真动作拆到 AppDeviceActions，D-ARCH-06 由 core 记录；共享导出 MIME 由 data 修正并用于 bridge share；ADB 脚本实际字节证明由 runtime 提供，Java 重复版本字面量移除；76 个完整中英文模板由 UI 合目录。

实际检查：新专用 71 项 JVM、27 项 Python 设备策略、29 项 ADB 路由行为、4 项编译路由行为、真实 managed client / peak-chip fetch 兼容均通过；当前七个关键平台源的 Android37 javac 通过；16 个拥有 Java 文件定点 GJF 检查通过。词典由当前目录现场生成。

JVM 的文件句柄夹具不等于 Android/FUSE/syscall 验收；冷 app_process、ROM/隐藏 API、Root/Shizuku、剪贴板、实际虚拟屏和安装仍缺设备证据。完整当前 source closure 被尚未生成的新增 UI R/string/plural 阻挡，real HTTP manager 工具拒绝过时 R；两者不计通过，root 的最终正式资源/构建门禁另行绑定。没有声明全 Android 或模型服务矩阵完成。

补充：虚拟屏 Application 归属软件缺口已实际修订，见 [ownership 修订](bridge-vscreen-ownership.md)。当前完整 release 源的 SDK 编译和真实回环 HTTP 不再受旧 R 阻挡；此前拒绝旧 R 的尝试是历史范围记录，不作为当前失败或设备缺口。
