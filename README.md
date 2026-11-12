# PixelReceipt

PixelReceipt **0.7.0／code 14** 是原生 Android 收件與記帳 App。正式服務、APK 下載與手機安裝分別核對；App 可從設定檢查更新。

**分享照片與說明 → 中央辨識 → App 內核對與修正 → 一次確認入帳 → 查看已完成帳目。**

未指定用途的品項預設自己用；代買、送人或分攤可逐項調整，既有設定會保留。中央帳本是正式紀錄，本機保存原圖與待同步草稿。AI 只提供候選，最終金額與分攤由確定性整數規則計算；草稿保存不等於入帳。

0.7.0 新增生活分類、餐別與每餐均額、原始店家／品牌／分店、縣市／行政區與時間分析；可交叉篩選及回到對應帳目。分析標籤可以入帳後補填，缺資料不阻記帳。無收據可手動輸入一筆支出；商品身份人工核對後，可在 App 比較同規格商品的跨店歷史價格。詳見 [生活支出分析使用說明](docs/LIVING_SPENDING_ANALYSIS.md) 與 [發行說明](docs/RELEASE_v0.7.0.md)。

0.6.0 新增：收據狀態獨立配色、預設一般收據與篩選；App 內新增支出分析，可切換期間、點趨勢或店家查看帳目，適配一般手機與折疊展開。實付、個人支出、代買及折扣的口徑分開呈現。設計與驗證界線見 [0.6.0 發行說明](docs/RELEASE_v0.6.0.md)。

0.5.5 加入設定內的版本檢查、下載進度與 Android 更新安裝，不需開網站；首次安裝此版後可使用。詳見 [0.5.5 發行說明](docs/RELEASE_v0.5.5.md)。

0.5.4 在原本同一次辨識回覆抽出品項後，再核對補充敘述的用途；自動儲存後仍保留建議，無法對應則明示需核對。新草稿缺日期先填今天，以日曆選擇日期；品名可留空入帳並在總覽標示待補，日後補名不改財務快照。版本與驗證界線見 [發行說明](docs/RELEASE_v0.5.4.md)。正式部署、手機更新與真人新收據接受分別核對；前版 [0.5.3](docs/RELEASE_v0.5.3.md) 紀錄保留。

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

0.6.0 的 Android 453 tests、嚴格 lint 與 APK 建置通過，後端 422 tests、Ruff／format 與 Web 3 個測試檔通過；這是工程證據；正式部署與手機接受另核對。

歷史上 0.5.5 的 Android 410 tests、嚴格 lint 與 APK 建置通過；App 內更新範圍與實機限制見 [發行說明](docs/RELEASE_v0.5.5.md)。

前版 0.5.4 的 Android 390 tests、嚴格 lint 與未簽 APK 建置通過；後端 388 tests、網頁 47 tests 與 Ruff 通過，範圍見 [發行說明](docs/RELEASE_v0.5.4.md)。主機／合成測試涵蓋原生 Compose、實際後端 DTO 契約及 320dp／放大字級；不代表手機更新、真實辨識品質或真人帳務接受。

## 舊版與開發資料

[Android 歷史功能與驗證](docs/LEGACY_ANDROID.md) 保留舊本機帳本、OCR、Nano 與 AppFunctions 紀錄；[Android 領域與歷史本機架構](docs/ARCHITECTURE.md) 說明保留的程式契約。它們不取代目前中央帳本流程。

原圖、帳本資料庫、模型原始輸出、憑證、簽章私鑰及主機專用部署腳本不包含在公開版本。版本變更見 [CHANGELOG](CHANGELOG.md)。
