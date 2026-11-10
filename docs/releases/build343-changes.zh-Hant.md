# DSHA 0.2.1-rc.5-20261108.1100-dsh0.2.2-rc.1 / build343

- 進度與骨架（E1 / E4）：新 `DeterminateProgress` 支援條 / 環兩種確定性進度，真實百分比只增不減、`100` 只由完成態寫入；取消只發一次且取消後不再接受進度，完成 / 失敗 / 取消終態停動畫並收起取消；總量未知走不確定態，不偽造進度。`FiniteSkeleton` 有限骨架上限 1–2 秒，資料到達即停，到上限隱藏並回呼靜態提示，無無限 shimmer。
- 清單與頁籤（F1 / F3 / F4）：`StaggerList` 前 8 項僅首次錯峰、依穩定鍵去重，捲動 / 重繪 / 翻頁不重播；`CardTabs` 卡內頁籤橫向可捲動、未選面板 `GONE`、選中鍵隨旋轉恢復；`GroupedList` 分組標題吸頂並可折疊，只改 `translationY`，正文收起後不進讀屏。
- 手風琴與技術說明（B2 / B3）：`Accordion` 建在 `DisclosureCard` 上、單開，收起仍保留後果行與讀屏語意；`TechNote` 頁尾技術說明沿用 `DisclosureCard`，空明細不折疊。
- 應用面：恢復精靈進度改 `DeterminateProgress`（總量取預檢的真實條目 / 位元組，依後端累計已複製量算百分比，成功 / 失敗 / 取消在結果步收口）；備份記錄首次讀取用有限骨架、前 8 條首次錯峰；外掛清單前 8 項錯峰；診斷頁工具卡內「啟動與恢復 / 網路與外掛」兩個頁籤；設定頁環境 / 體驗 / 支援三組吸頂可折疊；裝置能力授權頁多段說明改單開手風琴（螢幕操作說明改插到整列之後）；啟動 / 設定 / 配置頁頁尾統一 `TechNote`，配置頁新增執行方式 / 連接埠 / DNS 與配置存放位置的事實說明，隱私與安全後果不藏也不新增 ⓘ。
- 版本與範圍：版本碼提升為 343（基於已發佈的 build342 / `f5a295a3e`）；DSHA 修訂號維持 `0.2.1-rc.5`，內建 DSH 為 `0.2.2-rc.1`。本版不改程式行為、不改受管輸入、不新增受管執行環境；runtimeId 保持 `2ae3f266…`，fixed-inputs 凍結沿用鎖定的 20261105e；內建 `dsh-web-mobile` 維持上游 3.3.1 與預設啟用不變。
- **未驗證／未驗收**：真機視覺、TalkBack 朗讀與真實作業時延未驗證；確定性進度、有限骨架、錯峰、頁籤、吸頂折疊、手風琴與技術說明在日夜、短屏、1.3 倍字型與 RTL 下的表現未逐項執行。**build343 尚未真機驗收**，驗收項是 342→343 首次覆蓋安裝成功、資料完好、READY 與上述互動在實際裝置上的表現；build338–build342 真機安裝驗收仍待使用者。
- 驗證範圍：發佈身分與前置檢查、宿主 contracts 整組、descriptor `2ae3f266` / 202 assets / 475 launchers、第三方聲明、內建外掛、憑據路徑、semver、host manifest、來源衛生、UI i18n、`generate-changelog --check`、文件連結、全樹 Java 格式、`git diff --check`、`test-vscreen-help`、`test-diagnostic-feedback`；全量 `:app:testStandardDebugUnitTest`（先 `tools/prepare-test-runtime.py`，10 例 aarch64 Robolectric 環境基線失敗如實記錄）與 `:app:lintStandardRelease`；正式簽章 assemble 不在本機執行（無發佈 keystore）。
