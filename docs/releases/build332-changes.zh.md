# DSHA 0.2.1-rc.5-20261106.0700-dsh0.2.2-rc.1 / build332

- 本版只更正 T58 归档文档的事实口径：首次连接提示补丁自 build324（`a54f9854`）起已并入并随包，build324–build332 都携带；归档 tag `archive/feat/first-connect-indicator-20261106`（`5918af63`）只是同一补丁的独立分支快照，不另行合并，也不表示补丁不在包内。
- 无新功能、无行为变化、不改运行时：不重建 fixed-inputs，不改 managed-runtime-inputs 与 runtime-descriptor（runtimeId 仍 `35e3f499`），不新增受管运行时或插件。
- 验证范围：宿主 contracts 整组、全树格式检查（JDK17）、host-tests manifest 与定向单测（更新页）；本机未做真机验收。版本码提升为 332（基于 build331）；DSHA 修订号保持 0.2.1-rc.5，内置 DSH 为 0.2.2-rc.1。
