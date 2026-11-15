# DSHA 0.2.1-rc.5-20261106.0400-dsh0.2.2-rc.1 / build331

- proroot 下 git 大 diff 崩潰改為 fail-loud（T82）：build330（proroot v1.2.8）下 `git show --stat`、`git diff --stat` / `--numstat` 對某些提交或一次 ≥8 個路徑的組合會確定性崩潰——exit 139、stdout 0 位元組，stderr 只有 `[proroot] SIGSEGV pc=… lr=…`；命令若接 `| head` / `;`，最終 rc 會被吞成 0，審查與合併可能靜默拿到空資料。新增共享封裝 `tools/git_guard.py`：命中 rc=139 / -11 或 stderr 含該簽名時立即拋出 `GitSigsegvError`（`GIT_PROROOT_SIGSEGV`），不重試、不降級、不返回部分輸出，錯誤裡給出 `--name-status` / `--dirstat` / ≤7 路徑分片等安全替代；儲存庫內呼叫 git 的工具統一改用 `git_output`，新增 `tools/test-git-guard.py` 並登記 host-tests manifest，AGENTS 與執行引擎文件補充本機陷阱與審查/合併安全用法。
- 首次連線提示補丁歸檔說明（T58）：新增 `docs/design/first-connect-indicator-20261106/README.md`，記錄該補丁不合併的結論、目標現象難重現的多層原因、維護與風險代價、以及後續新任務的前置條件，並加入文件索引；舊提交保留為 tag `archive/feat/first-connect-indicator-20261106`（`5918af63`）。這是文件歸檔，不是功能補丁，不改執行環境；舊補丁不進入本版。
- 更正（2026-11-06）：T58 補丁自 build324（`a54f9854`）起已併入並隨包，build324–build331 都攜帶；上一條「舊補丁不進入本版」僅指歸檔分支快照 `5918af63` 不另行合併，不表示補丁不在包內。本歸檔不再新增或擴充補丁，也不回退已併入的補丁；build331 本身不改執行環境。
- 執行範圍：本版不改 fixed-inputs，不改 managed-runtime-inputs 與 runtime-descriptor（runtimeId 仍 `35e3f499`），不新增受管執行環境或外掛；工具層改動不涉及 proroot 本體，也不改變 GitHub Linux CI 的 git 語義。
- 驗證範圍：宿主 contracts 整組、全樹格式檢查（JDK17）、host-tests manifest（251 項，含 git guard 回歸）與定向單元測試（更新頁）；本機未做真機驗收。版本碼提升為 331（基於 build330），DSHA 修訂號保持 0.2.1-rc.5，內建 DSH 為 0.2.2-rc.1。
