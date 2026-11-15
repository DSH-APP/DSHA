# DSHA 0.2.1-rc.5-20261106.0700-dsh0.2.2-rc.1 / build332

- 本版只更正 T58 歸檔文件的事實口徑：首次連線提示補丁自 build324（`a54f9854`）起已併入並隨包，build324–build332 都攜帶；歸檔 tag `archive/feat/first-connect-indicator-20261106`（`5918af63`）只是同一補丁的獨立分支快照，不另行合併，也不表示補丁不在包內。
- 無新功能、無行為變化、不改執行環境：不重建 fixed-inputs，不改 managed-runtime-inputs 與 runtime-descriptor（runtimeId 仍 `35e3f499`），不新增受管執行環境或外掛。
- 驗證範圍：宿主 contracts 整組、全樹格式檢查（JDK17）、host-tests manifest 與定向單元測試（更新頁）；本機未做真機驗收。版本碼提升為 332（基於 build331）；DSHA 修訂號保持 0.2.1-rc.5，內建 DSH 為 0.2.2-rc.1。
