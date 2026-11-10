# DSHA 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209

- 應用程式名稱改為 DSH-A-Su（Sunset Mod），applicationId 改為 io.github.sunsetrne.dsha；與原版 dsh.android.client 可並行安裝、資料完全隔離，不覆蓋舊版資料。
- 發佈簽章重建：不再使用歷史 E7E3 憑證，改用 Mod 自有發佈憑證；verifyPublishCertificate 改為核對新憑證與新套件名，預期雜湊記錄在 ci/release-identity.json。
- 上島變體併入正式包：上島能力進入常駐套件，不再單獨出包，也不再保留獨立套件名。
- 放棄 Android 10 及以下：刪除 low / GeckoView 相容線，最低 Android 11（API 30），僅 arm64-v8a。
- 無障礙聲明改為多變體共用：聲明置於 main manifest，不限定 packageNames，也不再由 flavor 覆寫；標籤與說明更新為「DSH-A-Su 螢幕控制」並精簡。
- 仍保留 zh、zh-Hant、en、ru、ja、ko、ar、pt、la、fr 十種語言。
- fr、pt、ru、ar、la 的介面短標籤進一步精簡，減少佔屏。
- 修復拉丁語（la）「下一步」的損壞文案。
- 固定建置輸入改為公開倉庫的預發佈 Release 資產，以 SHA-256 鎖定；公開倉庫可直接還原完整建置輸入（見[固定建置輸入文件](../../docs/fixed-build-inputs.md)）。
- 套件名與憑證相關的發佈工具、CI 斷言同步更新；建置與門檻結果以倉庫回執為準。
