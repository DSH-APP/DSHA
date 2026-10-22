# build190 虚拟屏源码迁移

基座为正式 build170 `v0.2.0-rc2` / `8362bba747fcf84c19e436c3dbb594a0fc388a67`；虚拟屏源锁定实验 build187 `ac7bf8eda8b46a22f6412b17ec3574bf895b1876`，没有读取已继续至188/189的工作目录作为迁移原件。

## 功能与边界

- 保留 Standard / API30+ 的原生虚拟屏入口。迁入独立 Core 的端口0绑定、完整出生身份、同一 Socket 的双向 HMAC 证明、一次请求发送和真实只读退出探针。
- 持久记录 START_UNKNOWN / DISCONNECTED / CLOSE_UNKNOWN；未取得退出证明不能再次启动。关闭回应、断开端口及不可读 `/proc` 均不等于原实例退出。持久状态只恢复清理能力，不恢复输入许可或业务动作。
- 迁入流式触摸与手势围栏、前台/悬浮/独立页的预览订阅合并和位图所有权。像素观察与控件树独立；用户中文输入使用本机用户入口，助手动作携带显式授权上下文。
- 工具包升级到0.2.1表面，包含显式 `see → anchor → anchored_click`。调用方需保留原 `coreInstance/generation/frameSeq/observationId`；锚点只消费一次，未知结果不自动重放或回落 tap。
- Root、Shizuku 与 ADB 延续170执行器，新增窄的结构化发送前拒绝边界。Root/Shizuku 返回的旧文本不被升级为远端退出证明；实际启动仍需完整受管握手与成功退出标记。ADB 的启动/退出探针各使用一次性票据。
- Java策略及ADB Python仅扩展受管虚拟屏参数：`--launch --port 0` 或绑定完整出生身份的 `--probe-exit`。普通 app_process 仍拒绝；探针不接受信号参数。其余设备 STOP/FILE 策略保持170行为。
- ADB 虚拟屏专用 `bridge_request` 适配继续使用170固定 `127.0.0.1:3090` 与 `X-Token`，没有引入实验应用服务或动态桥描述符架构。Core自己的HMAC协议和宿主旧桥是不同连接。
- Manager诊断适配170字符串日志；额外只引入有界异常图与隐私过滤纯Java类，没有引入实验诊断服务。Root无完整结果时只存退出码/输出字节数/截断标记，避免把未登记的启动秘密留在状态摘要。

## 本次源码检查

最终检查记录：`artifacts/verification/build190-vscreen/source-checks.json`，每条命令的完整输出在同目录。

- `node tools/test-vscreen.mjs`：通过。
- `node tools/test-vscreen-anchored.mjs`：11项通过；真实stdio initialize/tools/list自然退出，桥传输为受控内存回复，没有执行Core或Android。
- `python tools/test-vscreen-protocol.py`：84项JUnit通过；覆盖协议/出生身份/退出政策/帧与窗口/锚点语法/设备原生受管策略，Gson按既有依赖锁摘要核验。此脚本不把Journal列为Android落盘验收。
- 23个VScreen/ADB测试源码对接主负责人当前StandardRelease编译产物，118项JUnit通过，含真实Robolectric SDK34的Core/Journal/Anchor/预览所有权测试。最终Manager改为无静态可变数组的延迟switch后，单文件javac覆盖层再次118项通过；输入摘要与资源类来源见`09-narrow-source-bindings.json`；手动classpath先后遗漏Robolectric resources及AndroidX runtime R造成的两份失败原日志保留，未把这些环境错误归为功能通过。
- `python tools/test-adb-vscreen-bridge.py`：8项通过。
- `python tools/test-device-shell-policy.py`：27项通过。

扩展检查前曾因新增夹具仍用旧端口/未适配170桥函数失败，已修正；不称一次未经修正的连续全绿。`test-vscreen-bundle.mjs`初次缺fixture而未执行；后使用同锁独立win32/x64 fresh raw（DSH0.2.1-rc.2，79014项完整证明）通过真实Cordis组合、自定义patch/禁用意图保留、reenable和两次真实MCP生命周期，准确15工具。记录见`10-vscreen-bundle.log`与收据中的`bundleCheck`。测试解析hook只用于宿主测试，不写入只读baseline运行时。

主负责人报告统一compile-2通过；本子任务未运行Gradle。严格manager-http检查先缺AGP来源map，map生成后又如实拒绝尚未包含最新端口UI四项资源的新旧R-def不一致；未跳过资源来源证明。两flavor测试、完整资源/总资产与正式签名由主负责人继续执行，本文件不替代该验证。

## 未验范围

build187本身没有完整真机通过证明，本迁移不继承该结论。本轮没有操作手机、生成额外调试/审计APK、发布GitHub/网站/Release或执行长稳。实际三通道、ROM隐藏API、受信任显示创建、真实像素/输入/退出、Android落盘权限、旋转/PiP仍需正式包设备验证。


## 最终fast补查

初次受管fast的这两项都在执行前被`HOST_JDK17_REQUIRED`阻止；原日志未覆盖。明确JDK17和toolchain-root后，`app/build/build190/host-fast-vscreen-recheck/manifest.json`两项通过：协议84项，fallback runner 5项。Runner仅排除真正的`-sources.jar`/`-javadoc.jar`分类器，不再误排Robolectric的`resources-4.15.1.jar`二进制；新增回归用实际编译的resources.jar类及损坏的sources/javadoc样本验证真实classpath行为。Manifest显式声明协议脚本所需JAVA_HOME与GRADLE_USER_HOME。

Cordis组合测试只证明注册、组合和stdio生命周期；其中没有实际工具业务HTTP调用。生产工具连接还需固定170桥适配及真实HTTP回归，不能由该组合测试替代。


## API边界与最终严格HTTP检查

Low仍不支持虚拟屏。Manager可供普通启动、停止、授权撤销和信息查询调用，但仅在`!LOW_ANDROID && SDK_INT >= 30`时经独立API30工厂加载NIO Journal；字段只持有API23安全接口。像素/显示/语义Anchor实现标注真实RequiresApi30；观察owner懒构造，Low或API<30的入口先拒绝，撤销/清理不构造owner。固定kernel boot_id改用有界普通IO；没有加入NewApi suppress，并移除Core原有该suppress。

实际LowRelease BuildConfig=true的两项Robolectric SDK23/34回归通过（17日志），故意使用一读取files就抛错的Context验证Manager constructor/信息/stop/revoke没有Journal访问。该纯边界fixture使用独立NoApkRunner忽略AGP安装包资源配置；已在classpath放入真实Standard min30测试配置验证隔离，绝不表示Standard可以安装API23。

四个Standard功能集成类（AnchoredChain、CloseReconcile、PersistentCleanup、StartReceipt）共26case仅在Low按其真实unsupported边界跳过，Standard仍完整运行；通用协议、输入、退出证据等测试未扩大跳过。最终两flavor总验证由主负责人记录。

严格manager-http最后通过：当前资源逐字节重新AAPT2核对751符号，匹配R-def/R.txt/R.jar；全main/standard当前源码重新编译，33项owned-state JUnit与原11项真实loopback并发/撤销检查通过（16日志）。原HTTP夹具仍是旧Bearer且没有Core出生身份，15日志如实失败；现已仅在测试中改为生产PeerProof+HttpProtocol真实同socket认证、完整出生身份和独立Core端口。晚关闭的HTTP释放回应仍不能代替退出证明，未恢复旧Bearer或取消认证。

另由global迁移负责人补齐并验证了插件真实production bridgeFetch：固定170 loopback3090、私有NOFOLLOW token、X-Token header、GET/POST Unicode、24MiB响应边界、不跟随302、未知结果不重放。`tools/fixtures/vscreen-production-http.mjs`的8项真实HTTP检查使用合成凭据及只替换端口的IO adapter，不是Android设备调用。
