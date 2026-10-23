# PixelReceipt 0.5.0

發行日期：2026-10-24。Android `versionName=0.5.0`、`versionCode=7`，application ID 為 `com.momonong.pixelreceipt.mobile`。本版後端與認證 APK 下載已部署至 ASUS；手機安裝更新與真人帳務接受仍待驗。

## 這一版的流程

**分享同筆交易的照片與說明 → 中央辨識 → App 內核對與修正 → 確認入帳 → 已完成帳目。**

核對首頁像一張收據，呈現店家、日期、實付與品項。點單項才展開數量、金額與用途；未指定用途預設自己用，既有送人、代買與分攤保留。編輯自動保存草稿，最後一次「確認入帳 NT$…」才確認目前內容及收據完整性。核對、帳本、使用說明與本機原圖放大都在原生 App，無須切到外部瀏覽器。

中央帳本是正式紀錄；本機保留原圖與待同步草稿。已完成與已作廢狀態在收件匣、詳情及帳本一致呈現，包括查詢歷史草稿及在其他位置完成的紀錄。

## 帳務與恢復

- AI 提供候選，合計、差額與用途分攤由中央確定性整數規則計算。
- 票面行金額不再乘數量，已含行金額的折扣不會再次扣除；未知值不補成零。
- 部分或無效輸入先耐久保存在本機；合法草稿才更新中央計算。autosave 不提交完整性確認或直接入帳。
- 未知 PUT 先讀正式 revision／內容；最終確認的固定 body、key 與 dispatch 狀態耐久保存，斷線、旋轉與重啟沿同一請求查核。
- 版本衝突保留手機編輯，讀中央版本只供比較，明確選擇後才套用；已送出且未知的確認不另建請求。
- 帳本分頁 cursor 與明細快取分開，避免開啟歷史明細造成下一頁漏筆。

## 登入與相容性

原生核對使用 `/v2/mobile/login` 的 reviewer session，沿用加密 token vault；密碼只供當次登入、不保存。舊 v1 ingest token 繼續收件，首次原生核對需在設定重新登入一次。原生登出只撤銷當前 session；裝置管理可撤銷該裝置全部手機登入。

本版沿用既有手機套件與永久 signer，不自動搬移舊 `com.momonong.pixelreceipt` App 的資料。後端內部套件版本仍為 `0.3.0`；Android 產品版本與後端套件版本各自維護。

## 驗證與下載完整性

| 範圍 | 本版結果 |
| --- | --- |
| Android 主機測試 | 304 tests，0 failures／errors／skipped |
| Android 靜態檢查與組裝 | 嚴格 lint 無問題，APK 建置成功 |
| 新原生流程測試 | 29 tests：HTTP、耐久狀態與真 Compose |
| Compose 版面 | 390dp、320dp／150% 字級，含正常、錯誤及完成／作廢狀態 |
| 後端回歸 | 283 tests 通過 |
| Dashboard JavaScript | 3 個測試檔通過 |
| 公開 scripts | 32 passed／1 historical migration skipped；歷史案例需隔離舊 runtime 與外部凍結 source |
| APK 簽署 | 既有永久憑證、單一 signer；簽署前後非簽章內容逐 bytes 一致 |
| 正式服務 | ASUS 後端及認證 APK 下載已部署 |
| 手機更新與人工接受 | 待驗證 |

從 [中央服務](https://momonong.me/receipts/) 登入後下載本版 APK。檔案資訊：

```text
pixelreceipt-0.5.0-code7-app-signed.apk
size: 60,409,979 bytes
SHA-256: db76079ca55ecc5e880465a47a4bb0434d9e7a7273ac58464ec545faf21998d9
certificate SHA-256: 81c75f167b0f9d8bdb5771f43dcc0dbed56e93ae038767f2b60383096fa3cfed
```

一般本機 debug APK 不等同正式更新包；更新必須核對 package、版本、檔案 hash 與簽章，保留現有 App 資料。

## 限制與待驗項目

- 主機與合成測試不能代替真實收據品質、手機分享／鍵盤／OEM Keystore、實際更新及真人核對接受。本次沒有代替使用者確認任何帳目。
- 歷史或其他裝置帳目若缺本機原圖，顯示「此裝置未保存原圖」；未加入二進位下載或 PDF 預覽。
- 已送出的原批次不能追加原圖。缺照片時保留原草稿，完整照片另分享一批，避免新舊批次重複確認；缺品項可在 App 補登。
- 已 dispatch 且結果未知的確認遇中央不同版本時，保留原 key／內容並等待正式狀態查核。

操作見 [手機使用說明](MOBILE_APP.md)，API 見 [手機契約](MOBILE_INGEST_CONTRACT.md) 與 [後端文件](../backend/README.md)；部署與備份見 [部署契約](ASUS_DEPLOYMENT.md)。歷史本機功能另見 [Android 歷史文件](LEGACY_ANDROID.md)。
