# build155 继续执行检查点

用户在 build154 交付后要求“继续修复完”。本轮仍不操作手机、不新建调试/审计APK、不发布GitHub/网站；最终统一本地正式APK/源码/236条台账与报告后停止。

版本：155 / `0.2.6-20261003.1704-dsh0.2.0-rc.2`；原包名、E7E3历史证书、v1/v2/v3、Ubuntu10与DSH rc2固定。APK常规路径仍是 release/dsha-0.2.0-rc2[low].apk。

已完成并冻结：

- bridge155：HttpShellService显式BridgeRoutes/handler表，29端点；原unknown=200 [NO_CMD]、HEAD=405、4个POST端点以及鉴权/租约/正文/代次等保持。4宿主矩阵+3JUnit+定向SDK编译通过。
- runtime155：ColdBundleTransaction/Extractor/ToolsInstaller分拆，ProcessPipePoller统一两种输出收集；19JUnit、Standard/Low窄编译通过，真正guest退出判据不改。
- compat155：真实WebView提供器/能力与一次renderer回退；共享测试HTML；退休models导航；本机DNS；Root可信候选；默认错误报告仅结构状态。独立复核发现DNS暂不可读会删旧受管配置，已经修复并8JUnit通过：保留最后有效DNS，合法新值替换，用户值优先；严格IPv4/IPv6纯字面量无查询。新补丁单独格式化/token证明。
- 根：DshaApp/ApplicationOwner/HarnessSessionState明确应用状态；旧真实App门面共享队列与代次，隔离测试Context独立；Main.current强引用删除，旧仪表化源码改weak active()；WebReadyTasks/WebLaunchCommand与精确LAN IPv4。11真实JUnit通过，runtime155只读复核无确凿阻断。
- 样式：704明确Java项，28批，659首轮改动；固定GJF1.22.0与javac真实token证明。ShellService原件因来源证据保护排除。最终只读check遇1次注释缩进收敛，UiLanguagePreferenceTest已定点write/check，test/util156批复查clean；其余27批输入SHA匹配clean报告。最终当前证据 java-format-current-style.json；对首轮仅DNS2语义+1测试注释不同。CI已接全树门禁。
- Linux真实测试：Docker引擎不可用，WSL根只读，不修系统。Linux fixture改用os.tmpdir，runner支持--linux-tempdir；实际 /dev/shm tmpfs Node24.12.0、9断言/100发布/32并发PASS。证据 app/build/audit-build155/linux-shm。不是Android FUSE/实磁盘掉电证据；旧154失败回执保持。

`final_delivery154` 已受命实际执行155收尾，现收到最终启动信号。根不再改生产源或跑Gradle，与它只读核对结果。它已补8新helper到launcherSources并注册2host入口，106条清单覆盖通过。需要替换此前 provisional source-freeze.json 后启动正式源快照；一次全套完整Gradle/host/APK门禁，发现失败针对实际原因修复并有界复查。

预检新增修订已再次冻结：生成器字节门禁发现 CredentialPathRules 被格式化；已经连同 BuiltinPluginRegistry 恢复为规范生成字节并显式排除，UiMessages 本来只在 app/build/generated。最终样式范围 **702（701 app + token helper）**，164 main/util 定向check通过，其他27批当前SHA不变；两生成器严格check/verify通过。旧704证明只属于首轮阶段，不当最终样式范围。Static architecture规则仅允许 DshaApp 的唯一lazy composition-root构造点；UI/notification旧断言适配GJF空白，行为要求不减。Host fast初次4失败已修这些真实门禁原因；raw内容证明通过，新managed fixture已生成，无NPM重解析。最终executor须据最后两生成类字节重写descriptor后运行完整host/Gradle。

最终须：两flavor完整单测/Lint、实际APK/ELF/签名/plugin/recovery、全host（--linux-tempdir /dev/shm）、实际release/sidecar SHA、完整源码ZIP含逐文件及生成/隐私排除清单、155继承236台账、架构与剩余范围报告。网站若后续独立变更仅跑网站检查并绑定精确增量，不重复无关完整Gradle。报告路径 docs/releases/v0.2.6-build155-20261003.md。

不要把旧残余原样当当前错误：InstallProbe.patchScript旧LAN重复已经退休；两退休UI补丁不算活跃缺陷；历史备份读者与最终完整性/源核验读取是兼容/保护契约。来源法律/失真历史SHA、真机/OEM/Android6/7/16KiB/Root/后台/FUSE仍如实未知，不编造通过、评分或覆盖安装。

主代理最后独立只读核对当前APK的aapt/apksigner回执、release现物/sidecar、完整源码ZIP逐文件摘要及236台账无模糊pending，再给自包含最终答复并停止。
