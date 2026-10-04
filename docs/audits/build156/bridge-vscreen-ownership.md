# build156 虚拟屏 Application 归属修订

此前 VirtualScreenManager 的 context/token/generation/port/heartbeat/clock 等仍是 mutable static，不能因硬件唯一就称已完成归属。当前 DshaApp 使用 ApplicationOwner 唯一持有 Manager，状态和预览/UI/节点缓存均为该实例的协作者；Context-free 调用者持有实例或用现有 Context 解析同一拥有者，无 global current/default fallback。

原 LOCK/ACTIONS、请求快照、epoch/generation、票据、立即撤销和旧 close fence 均保留。更改只涉及实际调用边界及一处 legacy backup token-reset Context，未重拆路由，未改 privileged Core RPC 协议或进程停止判据。Standalone VirtualScreenCore 仍是独立进程权威，不用 DshaApp；这不等于其所有 static 实现必须永存。

实际当前 main/standard Release 生产源码对 SDK37/新 R/受锁依赖编译通过；25 项 JVM、6 项真实回环 HTTP 场景通过；9 项 source-bound 结构检查及 71 项桥纯行为通过；15 个 Java 定点 GJF 零差异。实例隔离、票据撤销、旧响应/关闭/回调与新节点缓存的关系都有行为检查。Owner JVM 夹具显式旁路 Android 构造，不能声称实际 Android 窗口/权限已验。

完整事实、命令、最终 source/token SHA 与缺口见 [bridge-vscreen-ownership.json](bridge-vscreen-ownership.json)。未操作手机/ADB/Gradle/签名/安装；正式包设备验收与全发布门禁由 root 绑定。
