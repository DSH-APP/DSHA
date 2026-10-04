# 当前实现来源核验

Shizuku `ShellService` 的旧文件头标注 CFR 0.152；该标注本身不能证明外部反编译对象、原作者身份或许可不兼容。可确认的仓库来源记录为提交 `5f984e4029a5223bcb37dc7d6d290268d2449609` 的该路径；其更早外部来源没有本轮可核验材料，不作法律定性。

build156 对本地可用 Git 历史逐次读取原始 blob，找到该路径的 10 次记录；最早可见提交为 `a3a0b00321402f20fc4efc91d3f9c83d3ecd01d4`。各次原字节摘要及 CFR 头是否存在见[来源核验记录](audits/build156/shellservice-source-provenance.json)。此证据仅延长仓库内可追溯历史，不补猜外部原始对象或授权；本轮保留该源文件的当前原字节及标注。

当前执行路径使用公开 `IShellService` 协议、Android ProcessBuilder、仓库自己的 DeviceShellExecutor/DeviceShellPolicy 与 BoundedProcessRunner。绑定能力、30秒总期限、有界输出、仅特权进程退出及进程所有权仍由实际回归验证。没有把删除旧注释作为来源证明，也没有未经证据认定侵权。

第三方版本、实际资产摘要、上游来源及随包许可见 THIRD_PARTY_NOTICES.md 和 build154 审计。锁内可选外平台组件不自动等同实际分发。专有 proroot 的源码审计和跨平台逐字节可复现仍为不同限制；任何无法核验的来源都应保留明确 unknown，而不是补猜。
