# PixelReceipt 0.5.5

Android versionName `0.5.5`／versionCode `12`，沿用 `com.momonong.pixelreceipt.mobile` 與既有永久簽章。本版加入 App 內更新；Git 發布、正式部署、手機安裝及人工接受各以當次交付證據分別核對。

- 設定內檢查版本、顯示大小、下載進度與取消，下載後交 Android 確認覆蓋更新，不開瀏覽器。首次安裝此版本後，往後即可使用此入口。
- 沿用受登入保護的 `/v1/mobile/release`、`/v1/mobile/apk`；固定 HTTPS origin/path、拒 redirect，不新增背景輪詢、提供者、依賴、服務或網路設定。
- 串流最多 128 MiB；大小及 SHA256 均相同才產生完整 APK。安裝前重新檢查實際套件、較新版本及原簽章；FileProvider 只授予更新目錄內單一檔的讀取權限。
- 顯示 Android 安裝來源許可並在返回 App 後繼續；安裝畫面啟動、取消及許可拒絕均不當作更新成功。取消、逾時、檔案遺失或變更均可恢復，不卸載或清除 App 資料。
- 更新工作與帳本操作分開。憑證切換時取消舊更新工作；延遲的舊 401 只可清除同一憑證，不能清掉新登入。認證檔格式與帳本 schema 不變。

## 驗證範圍

驗證使用真實原生 Repository、AtomicFile、HTTPS transport（stub 連線）及 Compose/Robolectric。覆蓋損壞／過大／截斷檔、來源 redirect、取消與逾時、舊版來源、cache 重驗、許可返回、過期登入競態，以及 390dp、320dp／150% 字級和橫向大字版面。合成畫面中的 1.0.0 為測試更新版本，不是已發布版本。

後端僅增加 0.5.5 APK metadata 相容配對；其餘後端與網頁程式 bytes 相同，沿用已核對的 0.5.4 驗證。Android 410 tests、嚴格 lint（No issues found）與 assembleDebug 全部通過。獨立審查的兩項 Important 已以回歸重現並修正，逾時與舊 401 案例均轉綠。簽章及 metadata 檢查另留本機交付證據。上述工程驗證沒有呼叫真實模型、修改正式主機或連接手機；OEM 系統安裝及人工接受仍需實機驗證。
