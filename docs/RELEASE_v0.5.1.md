# PixelReceipt 0.5.1

Android `versionName=0.5.1`、`versionCode=8`，application ID 沿用 `com.momonong.pixelreceipt.mobile`。既有永久 signer 與 App 資料須保留；不解除安裝或清除資料。本機工程、Git 發布、正式部署、簽章下載包及手機更新分別核對。

## 新增行為

- 收件匣、詳情及帳本的更多操作提供單次確認。刪除未入帳收據不影響帳本支出；採耐久邏輯刪除，原圖、原始資料與備份保留，沒有復原按鈕。
- 已入帳帳目使用作廢，原因預設未註明。作廢後不計有效支出，原帳目與原因保留；帳本可勾選查看作廢歷史，操作後留在原分頁。
- 未知刪除／作廢在重啟後查原 ID，必要重試沿用原 revision、key、原因與 body；不以另送辨識來取得中央 ID。匯入／保存／確認尚未解決時阻擋不安全操作並說明下一步。
- 中央有效支出小卡使用全部有效帳目的權威統計；分頁與明細快取分開，舊明細失效或刷新，跨裝置刪除不被舊投影復活。操作已確認但刷新未完成時顯示尚未同步。

## 驗證與交付狀態

Android 離線完整 gate `testDebugUnitTest lintDebug assembleDebug`：335 tests、0 failures／errors／skipped，嚴格 lint 無問題，未簽 APK 建置成功。新增 31 個回歸涵蓋本機／中央刪除、draft-only 與 null revision、未知回應重啟、重複點擊、版本衝突、已作廢遠端結果、快取／統計與真實 Compose 確認畫面；HTTP 契約測試使用本次後端產生的虛構資料 DTO。

主機 Compose 驗證涵蓋 390dp、320dp 與 150% 字級；這不代表真實手機分享、OEM Keystore、更新安裝或真人帳務接受。本次自動化不刪除或作廢任何真實帳目。

Git 發布、永久簽章、正式部署、手機更新與人工接受的狀態由原目標對話核對後記錄；本文件不將候選 APK 當成已發布更新。前版已部署 APK 與簽章證據仍見 [0.5.0](RELEASE_v0.5.0.md)。操作說明見 [MOBILE_APP](MOBILE_APP.md)。
