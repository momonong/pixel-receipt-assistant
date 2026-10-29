# PixelReceipt

PixelReceipt **0.5.2／code 9** 是原生 Android 收件與記帳 App：

**分享照片與說明 → 中央辨識 → App 內核對與修正 → 一次確認入帳 → 查看已完成帳目。**

未指定用途的品項預設自己用；代買、送人或分攤可逐項調整，既有設定會保留。中央帳本是正式紀錄，本機保存原圖與待同步草稿。AI 只提供候選，最終金額與分攤由確定性整數規則計算；草稿保存不等於入帳。

0.5.2 新增送出前的本機可能重複提醒：相同整組收據照片可查看舊收件，或明確另建一筆，不自動合併。沿用未入帳收據刪除、帳目作廢與中央有效支出摘要。Git 發布、部署、永久簽章 APK 及手機更新分別驗證；版本與驗證界線見 [發行說明](docs/RELEASE_v0.5.2.md)。前版紀錄保留於 [0.5.1](docs/RELEASE_v0.5.1.md) 與 [0.5.0](docs/RELEASE_v0.5.0.md)。

## 使用

- 一筆交易分享一批收據、價標或促銷照片，補上整批或逐圖說明。
- 首次送出前若本機找到相同整組收據照片，會列出候選讓你選擇；取消保留新草稿，同圖也可合法屬於不同交易。
- 核對頁先呈現店家、日期、實付與品項；點單項才展開編輯與用途分配，原圖可在 App 放大。
- 編輯自動保存草稿；最後「確認入帳」才確認目前內容與完整性。斷線與未知結果沿同一請求查核，不另建重複帳目。
- 未入帳收據可由更多操作確認刪除；已完成帳目可作廢並保留原因。作廢不計有效支出，帳本可切換查看作廢歷史，使用說明隨時可開啟。

完整操作、缺照片、差額與版本衝突處理見 [手機使用說明](docs/MOBILE_APP.md)。現行 App ID 為 `com.momonong.pixelreceipt.mobile`；舊 `com.momonong.pixelreceipt` App 資料不會自動搬移。

## 介面預覽

[收據操作預覽與測試路徑](docs/previews/receipt-actions/README.md) 提供可離線開啟的刪除／作廢範例互動，仍使用虛構資料，不連接真實帳本；原生 App 已依確認的行為整合，操作與耐久同步契約見手機使用說明。

## 架構與契約

| 部分 | 責任 |
| --- | --- |
| Android Kotlin／Jetpack Compose | 分享匯入、原圖、耐久草稿、原生核對與帳本 |
| Python／FastAPI／SQLite 中央服務 | 認證、不可變收件、人工核對、整數計算與正式帳本 |
| 辨識來源 | API Quota Broker 優先，已配置的本機模型為可選來源；結果僅為候選 |

核對、帳本與說明留在 App，使用者不必切到外部瀏覽器完成記帳。未知值保持未知，已含行金額的折扣不重複扣除；未確認草稿與參考價格不計為消費。

- [後端與 HTTP API](backend/README.md)
- [手機收件／原生核對契約](docs/MOBILE_INGEST_CONTRACT.md)
- [商品價格與折扣契約](docs/PRICE_ANALYSIS_CONTRACT.md)
- [部署與備份契約](docs/ASUS_DEPLOYMENT.md)
- [Ingest CLI（歷史 dot 入口）](docs/DOT_INTEGRATION.md)

## 建置與測試

Android 需要 JDK 17、Android SDK Platform 37 與專案鎖定的 Gradle 依賴：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

後端需要 Python 3.12+ 與 uv，在 `backend/` 執行：

```bash
uv sync --locked
uv run --locked pytest -q
```

本版 Android 355 tests、嚴格 lint 與未簽 APK 建置通過；後端 316 tests 與 Ruff 通過，範圍見 [0.5.2 發行說明](docs/RELEASE_v0.5.2.md)。主機／合成測試涵蓋原生 Compose、實際後端 DTO 契約及 320dp／放大字級；不代表手機更新、真實辨識品質或真人帳務接受。

## 舊版與開發資料

[Android 歷史功能與驗證](docs/LEGACY_ANDROID.md) 保留舊本機帳本、OCR、Nano 與 AppFunctions 紀錄；[Android 領域與歷史本機架構](docs/ARCHITECTURE.md) 說明保留的程式契約。它們不取代目前中央帳本流程。

原圖、帳本資料庫、模型原始輸出、憑證、簽章私鑰及主機專用部署腳本不包含在公開版本。版本變更見 [CHANGELOG](CHANGELOG.md)。
