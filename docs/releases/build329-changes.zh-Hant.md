# DSHA 0.2.1-rc.5-20261106.0130-dsh0.2.2-rc.1 / build329

- 新增 GitHub 登入原生頁（T71）：顯示容器 `hosts.yml` 內的帳號、作用域與來源，標明受管 `gh` 是否可用，並以唯讀 `gh api -i /user` 區分 CA/TLS 失敗、token 401、未登入、網路與逾時；提供登入、重新授權與登出。頁面本身不顯示 token。已知缺口：token 仍保存在容器 `hosts.yml`，同 UID 的網頁與外掛可讀，隔離另立 T50，不在本批。
- 修復 `gh` 的 CA 查找（T73）：`RuntimeTools` 改用 canonical 宿主根設定 `DSHA_GH_HOST_ROOT`，`gh` 包裝腳本在 `/data/user/0` 與 `/data/data` 兩個資料視圖查找受管 CA；兩處都缺或 `DSHA_GH_CA` 指向缺失檔案時明確報錯退出，不再靜默回退到缺新根憑證的舊系統 CA。固定輸入不重建。
- 引導第 4 步底欄統一（T72）：歡迎流程完成頁改用與運行環境維護頁相同的 `ExtractFooter` 緊湊兩行佈局，更新中顯示 x/5，恢復主操作位，就緒後進入主介面。
- 驗證範圍：宿主測試、contracts 整組、全樹格式檢查與定向單元測試（更新頁、GitHub 登入、gh 宿主根、引導頁）；本機未做真機驗收。版本碼提升為 329（基於 build328），DSHA 修訂號保持 0.2.1-rc.5，內建 DSH 為 0.2.2-rc.1。
