# D-ARCH-06：3090 桥路由现状与修订边界

`HttpShellService.handle` 在有界读头、运行代次检查和 HTTP 方法/正文检查后，用约 30 个 `if/else` 分支同时选择端点、解析参数、执行设备动作并生成响应。`/app/ui/`、`/app/vscreen/` 是两个命名空间，后者已用 `VscreenBridgeRequest` 做精确子路由和 POST 正文验证；其它端点必须精确匹配。现有链还把未知端点和缺少 `cmd` 的响应行为耦合在尾部，重构需保留。

修订方案：提取无 Android 依赖的 `BridgeRoutes` 精确路由表，返回带有处理器标识、路由范围及命令参数需求的匹配结果。`HttpShellService` 保持 HTTP 读头、鉴权、`RuntimeTasks` 和响应封装边界；把 Android 端点动作移到独立分发方法，按表的标识调用专门处理器。虚拟屏的正文解析仍在鉴权和运行任务保护后执行，错误码与 deadline 不变。未知子路径不能落入敏感精确端点。以真实 Java 路由行为矩阵、JUnit 和源码接线检查同时验证。

此项只收敛路由选择与端点执行责任；设备通道、授权 generation、screen/live fence、shell 策略和插件策略均沿用原执行方法。没有设备验收，本轮不据宿主验证宣称真机通过。

## 完成情况

`BridgeRoutes` 现为无 Android 依赖的精确端点表；`HttpShellService` 的处理器表将路径与既有执行方法一一绑定，`handle` 只负责协议、鉴权、生命周期、任务 lease 和统一响应。未知路径仍返回历史 `[NO_CMD]`；`/confirm` 与 `/exec` 空命令仍返回 `[NO_CMD]`。虚拟屏的专有 HTTP 失败与超时只在虚拟屏处理器中转换成协议响应，其它处理器的旧异常行为保留。

实际生产 Java 路由矩阵（全部端点、敏感后缀、命名空间和虚拟屏非法子路径）4 项通过；新 JUnit 3 项通过；使用现有 SDK/JDK/已编译依赖对 `HttpShellService` 与 `BridgeRoutes` 定向 javac 编译通过。Gradle 定向编译在先决 `prepareRuntimeDescriptor` 处因当前源码摘要待主流程更新而停止，本项未修改描述符。未做 Android 设备 HTTP 端到端验收。
