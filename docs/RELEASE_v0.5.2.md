# PixelReceipt 0.5.2

Android `versionName=0.5.2`、`versionCode=9`，application ID 沿用 `com.momonong.pixelreceipt.mobile`。既有永久 signer 與 App 資料須保留；不解除安裝或清除資料。中央服務版號、資料庫 schema 與認證規則不變，僅在認證 APK metadata 允許清單新增版本配對。

## 新增行為

- 首次送出新收件前，照片已保存且核對完成後，比對這台裝置中目前標為「收據」的整組相同原圖。命中時提示「可能已分享過」，列出舊收件的時間、狀態與必要差異；不自動選取、合併或送出。
- 可取消返回並保留新照片與所有說明，查看原收件，或明確選擇「仍另建一筆並送出」。相同原圖可以合法屬於不同交易。
- 新草稿的整批／逐圖說明、角色、參考圖、頁序及辨識來源保留；內容或候選變動時重新核對，過期或取消後的確認不能送出，快速連點只產生一次新請求。
- 結果尚不確定的舊收件優先列出，開啟不自動重送；後續明確同步接續原請求。既有已送出請求重試不重新提醒、不換請求內容。已完成可看原帳目，已刪除／已作廢僅供查看，不復活。

這是本機提示，不是交易唯一性判定，也不代表中央或其他裝置沒有重複。重新拍攝、裁切、壓縮、增減頁面或改變照片角色可能漏判；沒有提示仍須核對交易分組。不新增模糊／語意查重、模型呼叫或中央查重 API。

## 驗證與交付狀態

本版離線完整 Android gate `testDebugUnitTest lintDebug assembleDebug`：355 tests、0 failures／errors／skipped，嚴格 lint 無問題，未簽 APK 建置成功。可能重複提醒新增 20 案，涵蓋 production Repository／Store 與真 MainActivity／ViewModel／Compose 互動；同版本重跑不另計新案例。

後端離線全套 pytest：316 tests 通過，Ruff 通過。APK metadata 的新版本正例先在原允許清單下失敗，再加入 `(9, "0.5.2")`；`9/0.5.1`、`8/0.5.2` 與 `10/0.5.2` 等錯配仍拒絕。僅產生本機未簽候選 APK，不代表永久簽章下載包或手機更新已交付。

主機合成測試沿用真 MainActivity／ViewModel／Compose 與 production Repository／Store、fake API，涵蓋 390dp、320dp／150% 字級、長列表及短橫向捲動。取消焦點限定 Keyboard 輸入模式；未涵蓋完整鍵盤／TalkBack、真機分享、OEM Keystore、真 HTTP、OS process death 或真人帳務接受。同鎖／CAS 審查與受控交錯測試不等於多程序壓測。

Git 推送／合併、永久簽章、正式部署、手機更新與人工接受分別核對，本文件不把本機候選當成已部署更新。操作見 [手機使用說明](MOBILE_APP.md)；前版紀錄見 [0.5.1](RELEASE_v0.5.1.md)。
