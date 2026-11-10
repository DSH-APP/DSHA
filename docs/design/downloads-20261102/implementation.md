# 下载记录页重设计：实施说明（实验性 UI 渲染更新）

状态：**实验性**。已按 [preview.html](preview.html) 实施到 [FileTransfersActivity](../../../app/src/main/java/com/deepseekharness/app/ui/FileTransfersActivity.java)，只改界面渲染，不改 `DownloadEngine` 的传输、校验、保存与移除语义。合入后需做统一视觉验收，结论写入当次发布记录。

## 范围

- 列表：标题 + 实时摘要、四段等宽筛选、「需要处理 → 正在传输 → 按创建日期」分组、每行一个主操作 + ⋮ 菜单、右上角「移除已结束记录」。
- 详情：手机与平板竖屏整页推入，返回保留列表滚动；`screenWidthDp ≥ 840`（`SettingsPanes.TWO_PANE_DP`）为左列表右详情。
- 危险操作只在管理区与 ⋮ 菜单；取消已有进度的下载需二次确认。
- 纯逻辑在 [TransferListModel](../../../app/src/main/java/com/deepseekharness/app/util/TransferListModel.java)，由 [TransferListModelTest](../../../app/src/test/java/com/deepseekharness/app/util/TransferListModelTest.java) 锁定。

## 与原型的差异

- 时间显示「创建时间」：`DownloadTask.createdAt` 是创建时刻，不是完成时刻。
- 第一步标签为「下载文件」：目录里「下载」已用作其他语言的「保存」。
- 列表用 ScrollView 原地刷新（记录上限 200），未换 RecyclerView。

## 已验证（宿主）

定向单测 TransferListModel / NativeFileShare / PendingFileAction 共 16 项通过；四项界面语言门禁、十语目录加载、文档链接、测试清单、格式化登记通过；7 个 Java 文件经固定 google-java-format 检查无差异。全树格式检查第 25 批落到本机 JDK 21 中止，未得到完整结论。

## 统一视觉验收清单（未做）

- 日夜两套主题；中文 / 英文 / 阿拉伯语（RTL）；1.0 与 1.3 倍字体；短屏。
- 手机竖屏、平板竖屏、平板横屏；旋转时二级页与双栏选中项互转、滚动位置恢复。
- 各状态卡：完成、下载中、排队、暂停、校验、保存中、待选位置、各类失败、取消。
- 网页发起的分享 / 另存为：提示条、自动弹出选择器、取消选择后的提示。
- TalkBack：行「查看详情」动作、分段按钮选中态、三步进度朗读、重建后焦点保留。
- `LayoutAuditInstrumentation` 尚未覆盖本页，验收前需补入口或人工核对。
