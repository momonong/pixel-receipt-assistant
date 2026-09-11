# PixelReceipt AI

PixelReceipt AI 是以 **Google Pixel 10 Pro Fold** 為主要實機的原生 Android 智慧記帳 App。目標體驗是不必先開 App：使用者先用原廠相機拍照，再從 Android Sharesheet 分享進 App，或稍後透過系統 Photo Picker 補選多張圖片；App 將收據、價標與促銷牌整理成同一筆交易的 evidence inbox，最後交給使用者核對。

目前已實作 **照片 → 本機辨識建立清單 → 核對內容 → 指定歸屬 → 個人支出／代墊 → 保存與確認**。首頁 Photo Picker 與外部分享匯入後直接開啟同一筆交易的「辨識並建立清單」，單圖不必額外勾選頁面；多圖先選這張收據的頁面。人工輸入是補正與失敗備援。照片不會上傳雲端。

**核心產品驗收仍未完成**：本次可驗證合成收據的流程、帳務及資料保護，但尚未取得獲授權的真實收據測試集，也未安裝到 Pixel。Gemini Nano、Firebase、共同分攤、Sheets 與收款管理不在本次範圍。

## 核心原則

2026-09-09 整合基線包含圖片匯入 `1a2f624`、人工核對 `d31e104` 與本機 OCR `40e6a82`。主專案重新執行 `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1` 通過；162 項測試結果由 Gradle build cache 還原，0 failures／errors／skipped，lint 無問題，debug APK 組裝成功。這次未重跑裝置測試；真實收據品質、完整 Pixel 操作及保留資料升級仍待驗收。這是歷史整合結果；2026-09-11 的完整流程與新驗證見下方「核對、品項歸屬與確認保存」。

主專案此次產生的 `app-debug.apk` 僅為建置驗證產物，未比對手機現有簽章；不要直接用它覆蓋手機版本或以卸載解決簽章衝突。新任務應從最新 `main` 建立隔離功能分支，保留現有 worktree 與測試資料。

- 每件商品各自成列；已知值保存來源，不確定的原價、折扣或適用範圍則明確保留 `Unknown`，不為了湊齊欄位而填零。
- AI 只做分類、文字／欄位擷取、關聯候選與說明；金額、折扣分攤、拆帳和總額一致性全部由可重現、可測試的本機 deterministic pricing rules 判定。
- 缺少證據時保留 `Unknown`，不把網路促銷候選或模型猜測偽裝成已發生的折扣。
- Room 是 single source of truth；Google Sheets 是可重試的單向匯出端，不是第二個主資料庫。
- `AiRouter` 的 SDK-neutral contract 與 local-first 選路已完成；未來接入 ML Kit Gemini Nano 與 Firebase AI Logic adapters 時，只有對「同一 case、同一批圖片內容、同一目的與指定雲端服務」明確同意後才可上雲，APK 不放可直接濫用的 Gemini API key。
- UI 依目前 app window 調整，不以手機型號、外／內螢幕或固定方向猜測版面。

## 已落地的技術基線

| 項目 | 版本／選擇 |
| --- | --- |
| UI | Single activity、Jetpack Compose、Material 3 Adaptive window size class／單雙 pane |
| Architecture | UDF、ViewModel + StateFlow、domain ports、repository adapters |
| Android | `compileSdk 37`、`targetSdk 37`；Phase 1 支援下限採 `minSdk 26` |
| Build | AGP `9.4.0`、Gradle `9.7.1`、JDK `17` |
| Kotlin | `2.4.20` |
| Compose | BOM `2026.08.00` |
| Material 3 Adaptive | `1.3.0` |
| Coroutines | `1.11.0` |

Gradle distribution 有固定 SHA-256，wrapper JAR 也已對照官方 checksum。暫定 application ID 是 `com.momonong.pixelreceipt`；設定 Firebase、OAuth、release signing 或 Play Console 前要先確認，之後不應任意更動。

## 架構摘要

```text
原廠相機 → Android Sharesheet ─┐
                              ├→ 多圖 evidence inbox（本機已實作）
系統 Photo Picker ────────────┘       │
                                     ├→ ReceiptPage／PriceTag／PromotionSign／Other
                                     ├→ Fact + provenance + EvidenceLink（domain 已完成）
                                     └→ AiRouter → 本機 ML Kit 中文 OCR＋收據解析（已接入）
                                         ├→ ML Kit Gemini Nano（未接入）
                                         └→ Firebase AI Logic（未接入；須明確同意）
                                                   ↓
                                    deterministic pricing + scoped adjustment
                                                   ↓
                                    Room（已實作）→ 人工 Review UI（已實作） → Sheets（待實作）
```

Domain 已將 AI recognition facts 與本機 `ReceiptDraft` 分開，模型不能指定本機 ID、revision、workflow stage 或帳務結果。`EvidenceAsset`／`EvidenceReference`／`EvidenceLink` 可表示一張促銷牌對多個收據品項，以及一個品項由多張圖片共同佐證；圖片以 SHA-256 定址保存在 app-private storage，Room 保存草稿、evidence metadata、關聯與匯入結果。資料寫入 contract 使用 revision + compare-and-set，避免 UI 與背景工作同時覆寫；exporter 只接收已驗證的 immutable batch。

完整依賴方向、狀態機、Foldable 行為與安全邊界請看 [架構文件](docs/ARCHITECTURE.md)。

## 目錄

```text
app/src/main/java/com/momonong/pixelreceipt/
├── app/                 Compose composition root
├── core/ui/theme/       App theme
├── domain/ai/           AI capability、consent 與 route contracts
├── domain/model/        Evidence、Fact、Receipt、Promotion、Money
├── domain/port/         Room／AI／Sheets 的介面邊界
├── domain/rules/        促銷定價、matching validation 與對帳規則
├── domain/usecase/      合法狀態轉換與 CAS 協調
├── data/local/          Room schema、versioned codec、CAS repository
├── data/ingestion/      URI 串流、驗證、去重、匯入協調
└── feature/inbox/       草稿列表、圖片預覽、人工核對、ViewModel／StateFlow
```

初期先保留單一 `:app` module；等 evidence ingestion、Room 與 service adapters 進入後，再依實際編譯隔離需求拆 module，避免過早模組化。

## 圖片、折扣與查價策略

- 原廠相機是主要拍攝入口，不要求為了留證據先開 App。Sharesheet 接收 `ACTION_SEND`／`ACTION_SEND_MULTIPLE`；Photo Picker 用來補選既有圖片，匯入後立即複製到 app-private storage。
- Evidence 類別為 `ReceiptPage`、`PriceTag`、`PromotionSign` 與 `Other`；尚未分類不是另一個 enum，而是 `Fact.Unknown`。分類與 matching 都可保存個別信心值及來源，不因模型高信心就視為事實。
- 折扣以有 scope 的 adjustment 表示，例如單一品項、候選品項集合或整筆交易。無法確認適用品項、會員資格、支付方式、門市或日期時，保持 `Unknown` 並交給使用者確認。
- 寶雅官方 [DM](https://www.poya.com.tw/dm/)、[活動](https://www.poya.com.tw/events/) 與[門市資料](https://www.poya.com.tw/store/)只能補充候選。現階段不依賴任何具契約或 SLA 的寶雅商品／實體店價格 API；線上價、全國 DM 或相似品名都不能自動當成該店該次交易的實際折扣。
- 定價引擎只接受已擷取的事實與使用者確認資料，使用整數金額執行 deterministic functions；模型可以提出 function input，但不能自行寫入計算結果。

Nano 不是所有符合 `minSdk 26` 的裝置都保證可用。`minSdk 26` 是 ML Kit Prompt API 的專案下限；實際仍須依裝置、Android／AICore、模型下載與 API availability 做 runtime check。Google 官方也明定 GenAI API inference 只能在 App 是頂層前景應用時執行；離開前景或模型未就緒時保留待處理狀態，再由使用者稍後重試，或針對該批 evidence 明確同意雲端分析。參考：[ML Kit GenAI overview](https://developers.google.com/ml-kit/genai)、[Prompt API setup](https://developers.google.com/ml-kit/genai/prompt/android/get-started)。

## Google Sheets 匯出欄位（規劃）

確認後的每個品項會匯出成 `Raw_Transactions` 的一列：

| 欄位 | 範例 | 說明 |
| --- | --- | --- |
| `transaction_id` | `TX_20260902_001` | 交易唯一識別碼 |
| `line_item_id` | `LINE_001` | 品項唯一識別碼，支援冪等匯出 |
| `date` | `2026-09-02` | 消費日期 |
| `merchant` | `全聯` | 標準化商家 |
| `city` | `臺南市` | 消費縣市 |
| `category` | `居家生活` | 大分類 |
| `sub_category` | `牙刷` | 細分類 |
| `raw_name` | `高露潔齒縫潔淨` | 收據原始文字 |
| `standard_name` | `高露潔 齒縫潔淨牙刷 2入` | 建議後經使用者確認的名稱 |
| `quantity` | `2` | 數量 |
| `original_price` | `378` 或空白 | 有證據的未折總額；未知時不得用實付額代填 |
| `original_price_status` | `KNOWN` | `KNOWN`／`UNKNOWN`／`CONFLICTING`／`NOT_APPLICABLE` |
| `discount_saved` | `189` 或空白 | 已確認折扣；未知與已知零折扣必須可區分 |
| `discount_status` | `KNOWN` | 折扣事實狀態 |
| `net_amount` | `189` | 實付總額 |
| `unit_price` | `94.5` | 實質單價 |
| `pricing_rule_version` | `pricing-v1` | 若由 deterministic engine 推導，保存規則版本 |
| `evidence_asset_ids` | `asset_01,asset_02` | 支撐該列的本機證據 ID |
| `is_personal` | `FALSE` | 是否為自用 |
| `split_party` | `室友` | 代墊或分攤對象 |
| `my_expense` | `0` | 計入個人預算的支出 |

App 內部金額不使用 `Double`，而以幣別最小單位 `Long` 儲存；只有匯出 mapper 會依幣別格式化顯示值。`ReceiptExportRow` 也保留原價與折扣的 `Fact` 狀態，因此缺少價標時仍可匯出實付資料，而不會把未知折扣錯寫成 `0`。

## 建置與檢查

需求：JDK 17、Android SDK Platform 37（SDK Manager 套件名稱 `platforms;android-37.0`）、Android Build Tools 36.0.0。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

PowerShell 本功能 worktree 的建置環境（只設定目前程序，不改全機環境；不提交工具或簽章）：

```powershell
$env:JAVA_HOME = 'D:/projects/pixel-receipt-assistant/.gradle/task-tools/jdk17/jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'D:/projects/pixel-receipt-assistant/.gradle/worktrees/receipt-auto-extraction/.gradle/task-tools/android-sdk'
$env:ANDROID_USER_HOME = "$PWD/.gradle/android-user"
$env:GRADLE_USER_HOME = 'D:/projects/pixel-receipt-assistant/.gradle/worktrees/receipt-auto-extraction/.gradle/task-gradle-home'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1
```

本次核對來源任務閒置後，使用現有 JDK／SDK 與完整 Gradle dependency cache；build outputs、Android user home 與測試 AVD 均獨立。其他 checkout 請指定自己的工具路徑。Windows sandbox 可能無法讀寫 Kotlin／Robolectric 快取；本次以核准後的相同 worktree 命令完成，沒有停用 lint 或測試。

本 worktree 使用人工核對來源的 debug key 本地副本（忽略檔案、不提交）。產出 APK 的憑證 SHA-256 `6fa1a1e710134668a0443876160ee821b3fd044705ef319bbcfb88ee993f4db2` 已與人工核對來源 APK 比對相同。2026-09-09 亦已讀取 Pixel 10 Pro Fold 當時安裝的 APK，比對簽章一致後以 `adb install -r` 成功更新；手機安裝後的 APK SHA-256 與交付檔一致，啟動回報成功。未卸載或清除資料，application ID 未變更；既有收據與草稿內容的完整性仍需使用者實際確認。後續更新仍須核對當時安裝簽章。

輸出 APK：`app/build/outputs/apk/debug/app-debug.apk`

目前 quality gate 包含嚴格 lint（warnings as errors）、domain／ViewModel JVM tests 和 APK 組裝。實體 Pixel 已完成上述更新與啟動檢查；外螢幕、展開、旋轉、分割視窗與 tabletop 相機行為仍需在對應功能完成後做 device test。

## 本機收據匯入使用方式

1. 首頁按「新增消費・選照片」，或在相簿／相機檢視明細時分享至 PixelReceipt AI。照片保存後**直接進入同一筆消費的辨識主畫面**，無須再次建立交易或先填品項。
2. 沒有照片可按「沒有照片，直接填寫」。正在填寫的交易可以「補照片」；有未保存內容時先選「保存並選照片」。Picker 取消不變更附件；新增照片後須再次核對完整性，重複照片不會重設已核對狀態。
3. 照片屬於整筆消費的附件。Compact／Medium 可在表單上方對照、收起或放大，鍵盤開啟時收起照片區；Expanded（840dp 起）左側對照照片、右側填寫。放大視窗支援雙指縮放、拖曳，返回後保留品項與捲動位置。
4. 每次外部分享代表新消費；若已有交易正在編輯，先保存目前草稿再開新消費，或取消這批分享。完成匯入的照片使用本機副本；原始 URI 失效不影響它。App 資料清除／解除安裝會刪除本機資料，目前沒有備份或匯出功能。

每批最多 20 張、每張 20 MiB、整批讀取 100 MiB；每張最多 5,000 萬像素、單邊 20,000 像素，本機圖片總量上限 1 GiB。目前支援 JPEG／PNG／WebP；HEIC、GIF、AVIF 等會顯示格式不支援，需先轉換。原圖不旋轉、不壓縮、不覆寫；預覽才進行降採樣及 EXIF 方向處理。

同一草稿依**原始 bytes 的 SHA-256**跳過重複圖片；不同草稿可共用實體檔案，但 evidence ID、匯入來源／時間與交易仍獨立，不會自動合併交易。旋轉後重編碼的圖片 bytes 不同，視為不同 evidence。混合成功／失敗會顯示逐張結果；重新選取失敗圖片或整批圖片時，已存在的內容不會重複加入同一草稿。

匯入至少一張成功才會建立新草稿；使用者明確建立的空白草稿除外。取消匯入會撤回本批未提交內容，既有草稿保留。程序中斷不自動重新讀取外部 URI：下次啟動會標示中斷、清理暫存／未引用檔案，再由使用者重選。畫面旋轉使用 ViewModel 保持同一操作；Activity／程序狀態復原使用 operation ID 與 Room 匯入紀錄拒絕重播。匯入中收到另一個分享會顯示忙碌訊息，須完成後重新分享。

目前匯入工作只在前景 UI 流程啟動，沒有 WorkManager 或自動背景重試。只有 Captured／NeedsReview 可追加照片，分析中與已確認交易拒絕追加；保存採原有 CAS，不覆蓋其他 writer 的更新。

## 收據匯入驗證與手動驗收

2026-09-07 匯入基線 gate 通過：113 tests（新增 16）、0 failures／errors／skipped，lint 無問題，debug APK 已產生。主機驗證與限制詳見 [架構文件](docs/ARCHITECTURE.md#收據匯入驗證)。`testDebugUnitTest` 包含 Robolectric 的 SQLite／Room、原生圖片解碼、schema v1 開啟與保留資料、CAS、錯誤與中斷恢復測試。這是第一版持久化 schema，之前沒有 Room DB；因此沒有虛構的 v0→v1 migration。之後 schema／payload 變更必須附 migration，禁止 destructive fallback。

裝置手動驗收（以下完整實機案例仍待完成，更新與啟動成功不代表全部通過）：

1. 各做一次 Sharesheet 單圖、多圖與 Photo Picker 單圖、多圖；確認建立新草稿且每張可預覽。
2. 開啟既有草稿補入一張新圖及一張相同圖；確認新增一張、跳過一張，原有圖片與 revision 保留。
3. 匯入中旋轉／重建 Activity，完成後強制停止程序再啟動；草稿／圖片數量不重複且能完整讀取。
4. 成功匯入後移除來源圖片或撤銷來源存取權限，再重啟並預覽本機副本。
5. 混合正常、損壞、不支援及超限圖片，確認逐張結果；在同草稿重選整批，確認成功部分不重複。全失敗不能產生新草稿。
6. 匯入途中按取消或終止程序，再啟動：已提交草稿仍可讀，本批顯示取消／中斷，沒有被當成成功的殘缺草稿。
7. 在 Compact、Expanded、旋轉、分割視窗與大字級分別操作建立、返回、補選、結果捲動與預覽；實體 Fold 的折疊切換也需驗證。

## 核對、品項歸屬與確認保存

1. **辨識建立清單**：選照片或分享後按「辨識並建立清單」。單張直接使用；多張先對照並勾選同張收據頁面。顯示本機處理狀態，可取消／重試；失敗按「改用人工輸入」。不需先填商家或空白品項。
2. **核對清單**：先看品名、數量與行合計；Unknown／Conflicting 會標示待核對。點「修改品項」展開，對照原圖修正，也可補漏列或刪除多辨識的列。行合計包含整列數量，**不再乘以數量**；原始 OCR、候選來源與不確定性保留在按需展開區塊。商家與日期可展開修正，日期空白保留未知。
3. **指定歸屬**：每列按「整列自用／代買／送禮」，或展開「同一列只有部分件數屬於自己」。沒有預選，批次「全部設為自用」須明確確認。非自用品項仍完整保留，不用刪除它們計算個人支出。
4. **核對付款與分配**：只有另外列出的折扣／費用才新增，先確定整筆或品項 scope，再明確選金額比例或自行指定。未分完、範圍不明、數值未知或過期分配，都顯示「待分類／待分配」，不顯示完整個人總額。
5. **保存與確認**：底部保存草稿可保留缺漏及未決分配。全部內容正確的完整性勾選與品項歸屬互相獨立。保存後，完整收據通過既有對帳 gate **且歸屬分配完成**才能確認；確認對話框列出個人支出、代墊與未解差額，摘要可上下捲動，大字級也能查看完整內容。Confirmed 唯讀，照片、完整明細、來源與歸屬一起保留。

本階段帳務語意已由使用者在 2026-09-11 確認：

| 選擇 | 用途／負擔 | 摘要 |
| --- | --- | --- |
| 自用 | 自己使用且自己負擔 | 計入個人支出 |
| 代買 | 替別人購買、預期收回 | 列為代墊，不計入自己負擔 |
| 送禮 | 給別人使用、自己負擔 | 計入個人支出 |
| 未決／未填 | 尚未分類或分配 | 保留待處理，不能確認記帳 |

同列部分件數須填各用途件數；件數不等於購買數量就未完成。使用者確認「按件數比例」後，以印出的**行合計**按件數分配；如果買一送一、各件有價差等不適合平均負擔，改選「自行指定金額」，各用途金額合計須等於行合計，無負擔者明填 0。另列加減項再獨立分配，不重複扣減。

整筆／整列範圍的加減項可由使用者選擇按適用品項的原行合計、各用途已分配金額比例分配。多筆加減項各自使用原行合計，不連乘或猜促銷；scope 只適用部分件數時，必須自行指定各用途金額。整數餘數使用最大餘數法，同餘數依 **自用 → 代買 → 送禮**；完整規則見[架構文件](docs/ARCHITECTURE.md#個人支出與分配規則)。

修改品名、數量或行合計會使該列歸屬待重新確認；其他列保持原決定。修改加減項或其參與品項歸屬會使加減分配待重新確認。既有促銷／allocation 相依不被猜測或重寫。已有歸屬決定的交易禁止以重新辨識取代；尚無歸屬時仍須先保存，明確同意取代當前版本的人工修正。

±1 最小單位容差原封不動。摘要顯示「已分配 − 整筆付款」的未解差額，差額不計入任何人。返回、補圖、外部新分享、寫入失敗或 CAS 衝突仍保留未保存輸入；Confirmed 舊交易缺少歸屬時顯示「未記錄」，不補造分類。

### 本次驗證與仍需驗收

本次分支 `feat/receipt-personal-expense-flow` 從本機及 GitHub 一致的 `032949c5e553b718c35548b8407b46cd565041fa` 建立。獨立工作目錄 `.gradle/worktrees/receipt-personal-expense-flow`；保留其他 worktree。SQL schema 維持 v1，payload format 4 讀取 1／2／3／4，來源記錄與簽章延續原版本；沒有手機安裝、卸載或資料清除。

2026-09-11 主機 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --no-daemon --max-workers=1` 通過（1m 32s）：**188 tests，0 failures／errors／skipped，lint No issues found，兩份 APK 組裝成功**。舊的 169 項主機／8 項 Android 結果只代表前次版本；本次 Android 結果列於下方。

本次裝置證據來自獨立 API 35 x86_64 模擬器 `receipt-expense-test`（`emulator-5582`），使用同一份主 APK；沒有讀取手機私有資料。模擬尺寸只證明各 window size class 的操作，不等於 Pixel Fold 折疊硬體驗收。

| 驗證 | 結果／證據範圍 |
| --- | --- |
| Compact 1080×2400、420 dpi | 全部 9 項 Android 測試通過（50.755s）：既有匯入／Picker／人工核對、4 項實際 OCR pipeline、OCR UI，以及新個人支出完整流程 |
| Expanded 2208×1840、320 dpi | 新完整情境 1 項通過（12.569s）：分享、實際 ML Kit、修正、部分件數、折扣、保存重開與確認 |
| Compact、200% 字級 | 新完整情境 1 項通過（14.163s）；摘要可捲到底，保存及確認操作可達 |
| 程序重啟 | 最終 APK 在專用模擬器 force-stop 後冷啟動成功（1.223s），已保存照片、修正、歸屬、90／65／155 摘要與 Confirmed 唯讀可重開；與測試框架 Activity 重建分開驗證 |
| 資料與人工決定保護 | 主機測試覆蓋部分分配持久化／CAS 衝突、v1–v3 相容、v3 Confirmed 未分類不重寫、修改後分配過期、OCR 不覆蓋歸屬，以及連續不同列編輯不互相覆蓋 |

新情境使用開發用合成圖片：茶 3 件／行合計 100（自用 1、代買 2）、送禮 40、自用麵包 20，再明確指定整筆折扣 5 按金額比例分配。結果為個人支出 **90 = 自用 51 + 送禮 39**、代墊 **65**、整筆付款 **155**、差額 **0**。保留全部 3 列與候選 evidence links，不自動確認完整性。程式自動操作耗時約 12 秒，**不是使用者完成時間或真實品質指標**。

視覺證據：[Compact](docs/images/receipt-flow/expense-compact.png)、[Expanded](docs/images/receipt-flow/expense-expanded.png)、[200% 字級](docs/images/receipt-flow/expense-large-font.png)、[可捲動確認摘要](docs/images/receipt-flow/expense-confirmation-large-font.png)、[冷啟動後明細](docs/images/receipt-flow/expense-restarted.png)。本機原始結果保留於 worktree 的 `.gradle/receipt-expense-delivery-gate.log`、`.gradle/expense-{compact,expanded,large-font}-results.log`、`.gradle/expense-final-cold-start.log` 與 `.gradle/expense-final-{detail,summary}.xml`；測試報告在 `app/build/reports/`。

Sharesheet 的 `onNewIntent` 會使 Android `ActivityScenario` 不再追蹤原 launch intent；新分享情境使用畫面返回重開，另以真實 force-stop 驗證程序重啟。既有人工情境的 Activity 重建測試仍通過。大字級捲動後的 fling 曾讓 Compose 測試等待 idle 停住；改以 scroll semantics 驗證並截圖，未關閉產品捲動或既有檢查。

交付 APK `app/build/outputs/apk/debug/app-debug.apk` 的 SHA-256 為 `9df7b39fa75d3a0ba580a5992c3b694603861476420b8a9d98d066fa606dbc58`。憑證 SHA-256 仍為 `6fa1a1e710134668a0443876160ee821b3fd044705ef319bbcfb88ee993f4db2`，與前次保留資料更新版本的來源 APK 相同；未在手機安裝或重新驗證手機當下簽章。

合成情境診斷曾發現原始 OCR 日期 9/11 被欄位分隔成 `9/1 1`、parser 誤取為 9/01。已以 `receipt-layout-2` 修正日期 token 的資料來源與邊界，保留原始 OCR 及版本紀錄；沒有換模型。此樣本是調整／回歸資料，不算真實或未見品質驗收。

真實樣本尚未取得，以下全部**未評估**：品項漏列／多列，名稱／數量／行金額／交易總額正確數，Unknown／錯誤數，使用者修正／新增／刪除量，以及真人完成時間和卡住位置。需要 5–10 筆授權照片，涵蓋清楚多品項、混合自用／代買、同列多件、另列折扣／費用、多頁或模糊案例。用於調整 parser 的資料必須與最後驗收資料分開；對帳平衡與合成測試都不等於辨識正確。

使用者重測重點：

1. 真實單頁各從首頁與分享進入，辨識後只修正少量內容，完成歸屬、保存、重啟及確認。
2. 自用＋代買＋送禮混合，確認送禮計入自己負擔、代買仍在完整明細及對帳中。
3. 同列 3 件／行合計 100，自用 1 件、代買 2 件，確認 33／67；有不同單件價時改用自行指定。
4. 另列折扣、部分件數 scope、±1 差額及修改金額後重新確認，檢查未決時沒有確定個人總額。
5. 取消／失敗重試、補圖、未保存離開，並在 Pixel 外／內螢幕、200% 字級檢查照片、鍵盤與固定保存操作。

## Roadmap

### Phase 0：原生架構基線（完成）

- [x] 建立可安裝的 Kotlin／Compose Android 專案與 Gradle wrapper。
- [x] 加入 Material 3 Adaptive main/supporting pane 與 Window Size Class policy。
- [x] 建立 UDF、ViewModel、domain model／ports／rules／use case 邊界。
- [x] 建立精確金額驗證、workflow state、revision/CAS 與 idempotent export contract。
- [x] 通過 unit tests、Android lint 與 debug APK build。

### Phase 1A：Evidence-first domain contracts（本 branch 完成）

- [x] `EvidenceAsset`／region／reference 與可審查的多對多 `EvidenceLink`。
- [x] `Fact.Known`／`Unknown`／`Conflicting`／`NotApplicable` 及 observation-level provenance／confidence。
- [x] Receipt line、scoped adjustment、promotion offer／application 與明確 line-to-product mapping。
- [x] 固定價、組合價、買 X 送 Y、百分比、固定額、滿額折的 deterministic pricing engine。
- [x] 收據 reconciliation、BigInteger overflow protection 與 unresolved data 的 confirmation gate。
- [x] Local-first `AiRouter` contract；Nano 前景限制、capability check，以及綁定 case／圖片 SHA-256／purpose／analyzer／service 的 cloud consent。
- [x] 無價標照片時的 `RetailPromotionLookup` port；外部活動只能回 candidate。

### Phase 1B：Evidence-first 收據 MVP adapters（部分完成）

- [x] Room v1 schema、schema 相容性／非破壞性升降版保護測試與 CAS repository adapter。
- [x] Sharesheet、Photo Picker、多圖 evidence inbox 與 app-private 圖片保存實作；實機端到端驗收待完成。
- [x] 使用者明確選取收據頁面，本機中文 OCR＋結構化品項候選；自動圖片分類仍未實作。
- [ ] `MlKitNanoAnalyzer` 與 `FirebaseCloudAnalyzer` adapters；Firebase 路徑強制 App Check／Play Integrity。
- [ ] 寶雅等 retailer source adapters，保存 URL、抓取時間與適用條件，僅產生 candidate。
- [ ] WorkManager 唯一工作與重試策略；不得假設 Nano 能在背景執行。
- [x] 單筆交易人工 evidence／品項核對、明確 scope 加減調整、CAS 保存與唯讀 Confirmed；裝置驗收待完成。
- [x] 自用／代買／送禮、部分件數及個人支出（真實產品驗收待完成）。
- [ ] 複雜促銷修正與共同分攤。
- [ ] Google OAuth 最小權限與 Sheets 冪等匯出。

### Phase 2：自動化

- [ ] 銀行／Google Wallet 通知擷取的明確 opt-in 流程。
- [ ] 歷史品項映射與低信心人工確認。
- [ ] Quick Settings Tile 與快速輸入。

### Phase 3：洞察

- [ ] Looker Studio 品類／地區看板。
- [ ] 日用品消耗與補貨提醒。
- [ ] 單品跨通路歷史實付價格索引。

## Git branch convention

- 功能工作使用 `feat/<topic>`，例如 `feat/promotion-evidence-architecture`。
- 每個 branch 聚焦單一可審查主題；修正與文件若屬於該功能，跟隨同一 branch。

## 收據照片自動擷取與人工核對

匯入後在主畫面按「辨識並建立清單」，單圖直接使用，多圖先選同一交易的收據頁面。本機逐頁辨識後刷新同筆交易的待核對清單，不要求先填資料；已有人工內容時，重新辨識須先保存並明確同意取代，已有歸屬決定則禁止取代。完整操作見上方「核對、品項歸屬與確認保存」。

- 真實路徑是隨 APK 打包的 `com.google.mlkit:text-recognition-chinese:16.0.1`，配合 `receipt-layout-2` 本機解析器。無須下載 Nano，不使用雲端。Nano 本版本未接入，不能將 OCR 可用視為 Nano 可用。
- 本次解析限 **TWD**。優先處理明確的「品名／數量／單價／金額」欄位或「品名 數量 × 單價 行合計」行式；可解析西元／民國日期、交易總額與另列折扣／費用。收據版面沒有明確欄位時仍可產生品名候選，但數量或行金額保持未知。單價不乘成行合計，也不代填未知數量 1。
- 商家採第一個合適文字行作候選，可能需要修正；統編、電話、付款、找零、稅額摘要不作一般品項。複雜促銷、換行品名、無標題欄位、傾斜／模糊與非 TWD 不保證能處理。無任何可用品項會顯示失敗，不以純 OCR 文字當成擷取成功。
- 跨頁同名或差一字的候選保守合組；同頁的重複列保留。跨頁組合的數量與金額保持未知、原文都保留，請核對後填值，若實際是不同品項可新增。其他漏頁、不同 OCR 名稱的重複仍需人工檢查。
- 另列折扣／費用會帶入正負方向與可解析金額，scope 保持未知，請明確選擇整筆或指定品項；已含在價格或稅額摘要中的金額不自行重複扣加。
- 取消、逾時（120 秒）、離開前景或程序中斷不套用未完成結果。旋轉也可能取消辨識；選圖狀態可復原，重試須由使用者啟動。辨識期間不能追加圖片或進入編輯；即使其他 writer 更改 revision、圖片 metadata 或 bytes，結果也會被 CAS／SHA-256 檢查阻擋。
- 重新辨識成功會取代商家、日期、總額、品項及調整，包括保存的人工修正，因此須先通過顯示目前版本與數量的取代確認。失敗保留原草稿。含促銷／分攤關聯的草稿拒絕取代；Confirmed 交易不可辨識或修改。
- 核對畫面可展開辨識原文、圖片及區域座標，保存 analyzer／SDK／parser／schema 版本與來源 SHA-256。座標對應 EXIF 轉正、最多 4096px 的 OCR 圖片，並記錄該座標空間尺寸，原圖 bytes 保持不變。
- 單次最多 20 圖、100 品項、50 調整；OCR 最多 500 行、每行 500 字、總文字 50,000 字。超限拒絕整批，不靜默截斷品項。
- 核對畫面分別顯示「已知金額試算差額」與既有 reconciliation 阻擋原因；試算平衡不代表完整或辨識正確。容差仍是 ±1 最小單位，沒有自動確認。

持久化 draft payload 現為 **format 4**，讀取 format 1／2／3；舊資料缺少 extraction 或歸屬時保持未記錄、revision 不變。SQL schema 仍為 v1，沒有 destructive migration。升級後不可假設舊 APK 能讀取新版資料。

本次隔離 worktree：`D:\projects\pixel-receipt-assistant\.gradle\worktrees\receipt-auto-extraction`，分支 `feat/receipt-auto-extraction`；起始 SHA `d31e1049e69dd4c6de818ddb963c337e1e808b43` 已包含人工核對。工具與快取均在本 worktree 的 `.gradle/`，不共用其他任務的建置輸出。交付 APK `app/build/outputs/apk/debug/app-debug-manual-review-signature.apk` 已使用人工核對基線的 debug key 簽署，憑證與來源 APK 相同；手機現有安裝簽章仍未比對，**不要卸載或清除資料解決簽章衝突**。一般 `app-debug.apk` 使用本 worktree 獨立測試簽章，供 emulator 驗證，不應直接拿來更新手機。

品質資料與驗證證據請見 [自動擷取驗證](docs/ARCHITECTURE.md#自動擷取驗證)。

2026-09-09 最終技術驗證：**162 項主機測試、0 failures／errors／skipped，lint No issues found，debug build 通過；獨立 Android API 35 x86_64 emulator 的 5 項真實 ML Kit／Compose 測試通過**。涵蓋照片匯入→OCR→自動建立品項→Room→人工核對→使用者確認，以及中文多品項／另列折扣、多頁重疊、空白失敗與畫面辨識按鈕自動導入表單。資料全為合成，真實收據品質及實體 Pixel 驗收未完成。
