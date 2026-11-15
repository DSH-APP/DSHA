# DSHA 0.2.1-rc.5-20261106.0010-dsh0.2.2-rc.1 / build328

- 修復 0.2.2 環境執行 `gh` 報「managed GitHub CLI is unavailable」：官方 GitHub CLI v2.103.3（linux-arm64）作為第 12 項固定輸入 `gh-cli.tar.gz` 納入受管資產，安裝時只提取固定成員、二次校驗 SHA-256 並原子發佈到 `/opt/gh/bin/gh`；整樹環境重建按受管注入，不再依賴使用者手動安裝。
- gh 包裝腳本在二進位缺失或損壞時按固定 URL 與固定摘要自舉安裝，任一步失敗都 fail-closed 並列印可複製的復原命令；刪除了聲稱「bundled」但實際不存在的誤導文案。
- 新增 `util/MaintenanceToolPaths` 明文契約與重建前有界預檢：`/opt` 作為使用者工具目錄保留，`/usr/local/bin` 屬系統樹不保留，`usr/local/share/dsha` 由受管注入；`/opt` 條目超過 10 萬時在移動舊樹前拒絕重建。
- 驗證範圍：宿主測試、contracts 整組與原始碼/描述符/第三方聲明門禁；本機未做真機驗證。版本碼提升為 328（基於 build327）；APK 因內建 gh 歸檔增約 13.0 MiB。
