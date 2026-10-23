# Changelog

## [0.5.0] - 2026-10-24

### 新增與改善

- 分享照片與說明後，在原生 Android App 內核對、修正、設定用途、確認入帳及查看中央帳本。
- 未指定用途預設自己用，保留既有送人、代買與分攤；單項點開才展開編輯，底部保留一次明確入帳動作。
- 自動保存耐久草稿與未完成輸入；未知 PUT 先讀正式版本，未知確認保留同一 idempotency key／內容，避免重複入帳。
- 原生 reviewer 登入／登出、裝置撤銷與 v1 ingest 相容；收件狀態包含正式草稿／帳本投影。
- 收件匣、詳情與帳本一致顯示已完成或已作廢；獨立帳本分頁／明細快取，並提供原圖放大與常駐使用說明。

### 驗證與發布

- Android：304 tests 通過，嚴格 lint 無問題，APK 建置成功；原生 Compose 涵蓋 390dp、320dp／150% 字級。
- 後端：283 tests 通過。核對與金額仍由確定性後端規則處理，不因模型輸出而自動入帳。
- Dashboard JavaScript：3個測試檔通過。
- 公開 scripts：32 passed／1 historical migration skipped；歷史案例需隔離舊 runtime 與外部凍結 source。
- 0.5.0／code 7 已以既有永久簽章發布至 ASUS 認證下載；手機更新與真人帳務接受待驗。
- APK SHA-256：`db76079ca55ecc5e880465a47a4bb0434d9e7a7273ac58464ec545faf21998d9`。

完整內容與限制見 [0.5.0 發行說明](docs/RELEASE_v0.5.0.md)。更早的 Android 功能與各階段驗證保留於 [歷史文件](docs/LEGACY_ANDROID.md)；不將本次測試數套用到過往版本。
