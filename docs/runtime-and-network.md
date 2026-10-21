# 运行时与网络设置选择指南

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`；当前 build190 工作树核验。运行引擎、端口、DNS已经分别有完整专页，本页集中说明如何选择和排查，不重新维护另一套版本或性能数字。

## 先按失败层决定改什么

| 现象或目标 | 先核查 | 相关设置与生效点 |
|---|---|---|
| 需要稳定启动 | [引擎](runtime-engines.md)与启动诊断 | 当前默认proot；显式proroot需重启Web，冷安装按选型 |
| proroot出现加载器兼容问题 | 实际 `runtimeMode`、加载器输出和退出证明 | “使用proroot静态加载器”默认开，下次运行态构建读取；关闭不是通用提速方案 |
| proot在ROM上发生seccomp兼容失败 | 启动失败阶段及实际proot命令 | “禁用proot seccomp加速”默认关，下次启动生效；不承诺统一性能损失数字 |
| 端口冲突或旧URL打不开 | [本轮实际端口和鉴权](ports-and-networking.md) | 自动为默认；手动排除3081/3090，保存后需重启Web |
| DNS lookup失败 | [DNS作用层](dns-configuration.md)、原始错误和实际resolver | auto/ipv4/native在新启动环境读取；不重放已经发送的HTTP或业务操作 |
| 电脑访问手机 | [LAN](lan.md)开关、系统许可和本轮链接 | LAN默认关，使用3081代理；不开放3090桥 |

不要一次切换所有选项。记录原值、失败阶段与实际运行mode，改变一个相关设置，正常停止并确认退出后再启动。确认“生效”要看本轮诊断或生成的真实命令，不能只看配置页已保存。

## 两个兼容开关的适用边界

[ContainerRuntime](../app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java)根据运行mode、系统API与静态加载器偏好组装实际调用；[RuntimeExecution](../app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.java)选择本轮mode。静态加载器分支和源码中的动态分支不能仅因存在就宣称都能由当前UI到达；当前可达性和vendor输入限制见[运行引擎](runtime-engines.md)。

禁用seccomp只作用于proot的对应启动参数，不能改变proroot的路径翻译方式。静态加载器只属于proroot，不会把proot变成“零ptrace”。无当前同设备对照数据时不承诺加速或慢多少，也不把历史vivo基准迁成F10结果。

proroot的鉴权前回退要求明确退出、无插件失败、未进入业务提交，且guest/launcher确认退出；仅允许一次proot重试。慢启动、超时、UNKNOWN不能触发业务重放。试运行回执必须保留真实 `runtimeMode` 和 `fallbackFrom`；proot成功不能写成proroot原生成功。

## 网络设置并非全局开关

自动Web端口只为本轮服务选择并捕获实际端口，备用端口为本机记忆；旧3080默认值迁移与显式固定3080不同。F12的受控真实Host启停voice插件仍使用同一个HTTP对象和端口；原生插件助手走guest命令，不使用Web当前Connection。已证的同步hash阻塞不证明自动端口就是卡死根因，因此不取消自动0；只有实际端口变化才按独立origin规则处理。已启动服务不会因保存设置被无缝搬到新端口。端口绑定竞态的辅助函数存在，不代表生产链承诺自动重试；当前接入边界见[端口专页](ports-and-networking.md)。

DNS的auto和ipv4都只在特定Node lookup错误上做窄解析重试；ipv4另写受管glibc `no-aaaa`，并非关闭系统IPv6。native退出Node兼容路径。第三方解析器、浏览器、ADB和LAN各自有实际作用域；新值不追溯改变旧进程。失败后先检查错误是否属于该作用层，不能用“IPv4优先”概括所有模式。

修改设置、冷启动、受管试运行和普通Web启动应分别验证。详细代码、锁和关联测试由[引擎](runtime-engines.md)、[DNS](dns-configuration.md)、[端口](ports-and-networking.md)提供；本页不增加设备矩阵或当前性能证明。
