# DSHA 0.2.1-rc.5-20261108.1100-dsh0.2.2-rc.1 / build343

- 进度与骨架（E1 / E4）：新 `DeterminateProgress` 支持条 / 环两种确定性进度，真实百分比只增不减、`100` 只由完成态写入；取消只发一次且取消后不再接受进度，完成 / 失败 / 取消终态停动画并收起取消；总量未知走不确定态，不伪造进度。`FiniteSkeleton` 有限骨架上限 1–2 秒，数据到达即停，到上限隐藏并回调静态提示，无无限 shimmer。
- 列表与页签（F1 / F3 / F4）：`StaggerList` 前 8 项仅首次错峰，按稳定键去重，滚动 / 重绘 / 翻页不重播；`CardTabs` 卡内页签横向可滚动、未选面板 `GONE`、选中键随旋转恢复；`GroupedList` 分组标题吸顶并可折叠，只改 `translationY`，正文收起后不进读屏。
- 手风琴与技术说明（B2 / B3）：`Accordion` 建在 `DisclosureCard` 上、单开，收起仍保留后果行与读屏语义；`TechNote` 页尾技术说明沿用 `DisclosureCard`，空明细不折叠。
- 应用面：恢复向导进度改为 `DeterminateProgress`（总量取预检的真实条目 / 字节，按后端累计已复制量算百分比，成功 / 失败 / 取消在结果步收口）；备份记录首次读取用有限骨架、前 8 条首次错峰；插件列表前 8 项错峰；诊断页工具卡内「启动与恢复 / 网络与插件」两个页签；设置页环境 / 体验 / 支持三组吸顶可折叠；设备能力授权页多段说明改单开手风琴（屏幕操作说明改插到整行之后）；启动 / 设置 / 配置页页尾统一 `TechNote`，配置页新增运行方式 / 端口 / DNS 与配置存放位置的事实说明，隐私与安全后果不藏也不新增 ⓘ。
- 版本与范围：版本码提升为 343（基于已发布的 build342 / `f5a295a3e`）；DSHA 修订号保持 `0.2.1-rc.5`，内置 DSH 为 `0.2.2-rc.1`。本版不改代码行为、不改受管输入、不新增受管运行时；runtimeId 保持 `2ae3f266…`，fixed-inputs 冻结沿用锁定的 20261105e；内置 `dsh-web-mobile` 保持上游 3.3.1 与默认启用不变。
- **未验证 / 未验收**：真机视觉、TalkBack 朗读与真实作业时延未验证；确定性进度、有限骨架、错峰、页签、吸顶折叠、手风琴与技术说明在日夜、短屏、1.3 倍字体与 RTL 下的表现未逐项执行。**build343 尚未真机验收**，验收项是 342→343 首次覆盖安装成功、数据完好、READY 与上述交互在真实设备上的表现；build338–build342 真机安装验收仍待用户。
- 验证范围：发布身份与前置检查、宿主 contracts 整组、descriptor `2ae3f266` / 202 assets / 475 launchers、第三方声明、内置插件、凭据路径、semver、host manifest、源卫生、UI i18n、`generate-changelog --check`、文档链接、全树 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 环境基线失败如实记录）与 `:app:lintStandardRelease`；正式签名 assemble 不在本机执行（无发布 keystore）。 结果以本机发布准备回执与本次 CI run 为准。
