# PixelReceipt AI

PixelReceipt AI 是以 **Google Pixel 10 Pro Fold** 為主要實機的原生 Android 智慧記帳 App。目標體驗是不必先開 App：使用者先用原廠相機拍照，再從 Android Sharesheet 分享進 App，或稍後透過系統 Photo Picker 補選多張圖片；App 將收據、價標與促銷牌整理成同一筆交易的 evidence inbox，最後交給使用者核對。

目前已實作 **本機收據匯入、中文 OCR 自動擷取、人工核對與確認記帳**：Sharesheet 單圖／多圖、Photo Picker、草稿列表、追加圖片與原圖預覽、交易／品項／人工調整編輯、CAS 保存，以及通過既有本機 gate 後保存 Confirmed。可辨識照片自動帶入品項，也保留完整人工輸入。Gemini Nano、Firebase、拆帳及 Sheets adapters 尚未實作；裝置驗收狀態見下方，不能以 JVM 測試代替實機驗證。

## 核心原則

2026-09-09 整合基線包含圖片匯入 `1a2f624`、人工核對 `d31e104` 與本機 OCR `40e6a82`。主專案重新執行 `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1` 通過；162 項測試結果由 Gradle build cache 還原，0 failures／errors／skipped，lint 無問題，debug APK 組裝成功。這次未重跑裝置測試；真實收據品質、完整 Pixel 操作及保留資料升級仍待驗收。本功能分支已重整人工記帳流程；本次驗證與操作步驟見下方「人工核對與確認記帳」，與整合基線的歷史結果分開記錄。

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

本 worktree 使用人工核對來源的 debug key 本地副本（忽略檔案、不提交）。產出 APK 的憑證 SHA-256 `6fa1a1e710134668a0443876160ee821b3fd044705ef319bbcfb88ee993f4db2` 已與人工核對來源 APK 比對相同。**實體手機現有安裝簽章尚未讀取；更新前仍須比對，不得卸載或清除資料來解決簽章衝突。** application ID 未變更。

輸出 APK：`app/build/outputs/apk/debug/app-debug.apk`

目前 quality gate 包含嚴格 lint（warnings as errors）、domain／ViewModel JVM tests 和 APK 組裝。尚未連接實體 Pixel，因此外螢幕、展開、旋轉、分割視窗與 tabletop 相機行為仍需在對應功能完成後做 device test。

## 本機收據匯入使用方式

1. 首頁按「新增消費・選照片」，或在相簿／相機檢視明細時分享至 PixelReceipt AI。照片保存後**直接進入同一筆消費的填寫畫面**，無須再次建立交易。
2. 沒有照片可按「沒有照片，直接填寫」。正在填寫的交易可以「補照片」；有未保存內容時先選「保存並選照片」。Picker 取消不變更附件；新增照片後須再次核對完整性，重複照片不會重設已核對狀態。
3. 照片屬於整筆消費的附件。Compact／Medium 可在表單上方對照、收起或放大，鍵盤開啟時收起照片區；Expanded（840dp 起）左側對照照片、右側填寫。放大視窗支援雙指縮放、拖曳，返回後保留品項與捲動位置。
4. 每次外部分享代表新消費；若已有交易正在編輯，先保存目前草稿再開新消費，或取消這批分享。完成匯入的照片使用本機副本；原始 URI 失效不影響它。App 資料清除／解除安裝會刪除本機資料，目前沒有備份或匯出功能。

每批最多 20 張、每張 20 MiB、整批讀取 100 MiB；每張最多 5,000 萬像素、單邊 20,000 像素，本機圖片總量上限 1 GiB。目前支援 JPEG／PNG／WebP；HEIC、GIF、AVIF 等會顯示格式不支援，需先轉換。原圖不旋轉、不壓縮、不覆寫；預覽才進行降採樣及 EXIF 方向處理。

同一草稿依**原始 bytes 的 SHA-256**跳過重複圖片；不同草稿可共用實體檔案，但 evidence ID、匯入來源／時間與交易仍獨立，不會自動合併交易。旋轉後重編碼的圖片 bytes 不同，視為不同 evidence。混合成功／失敗會顯示逐張結果；重新選取失敗圖片或整批圖片時，已存在的內容不會重複加入同一草稿。

匯入至少一張成功才會建立新草稿；使用者明確建立的空白草稿除外。取消匯入會撤回本批未提交內容，既有草稿保留。程序中斷不自動重新讀取外部 URI：下次啟動會標示中斷、清理暫存／未引用檔案，再由使用者重選。畫面旋轉使用 ViewModel 保持同一操作；Activity／程序狀態復原使用 operation ID 與 Room 匯入紀錄拒絕重播。匯入中收到另一個分享會顯示忙碌訊息，須完成後重新分享。

目前匯入工作只在前景 UI 流程啟動，沒有 WorkManager 或自動背景重試。只有 Captured／NeedsReview 可追加照片，分析中與已確認交易拒絕追加；保存採原有 CAS，不覆蓋其他 writer 的更新。

## 收據匯入驗證與手動驗收

2026-09-07 匯入基線 gate 通過：113 tests（新增 16）、0 failures／errors／skipped，lint 無問題，debug APK 已產生。主機驗證與限制詳見 [架構文件](docs/ARCHITECTURE.md#收據匯入驗證)。`testDebugUnitTest` 包含 Robolectric 的 SQLite／Room、原生圖片解碼、schema v1 開啟與保留資料、CAS、錯誤與中斷恢復測試。這是第一版持久化 schema，之前沒有 Room DB；因此沒有虛構的 v0→v1 migration。之後 schema／payload 變更必須附 migration，禁止 destructive fallback。

裝置手動驗收（目前尚未連接裝置）：

1. 各做一次 Sharesheet 單圖、多圖與 Photo Picker 單圖、多圖；確認建立新草稿且每張可預覽。
2. 開啟既有草稿補入一張新圖及一張相同圖；確認新增一張、跳過一張，原有圖片與 revision 保留。
3. 匯入中旋轉／重建 Activity，完成後強制停止程序再啟動；草稿／圖片數量不重複且能完整讀取。
4. 成功匯入後移除來源圖片或撤銷來源存取權限，再重啟並預覽本機副本。
5. 混合正常、損壞、不支援及超限圖片，確認逐張結果；在同草稿重選整批，確認成功部分不重複。全失敗不能產生新草稿。
6. 匯入途中按取消或終止程序，再啟動：已提交草稿仍可讀，本批顯示取消／中斷，沒有被當成成功的殘缺草稿。
7. 在 Compact、Expanded、旋轉、分割視窗與大字級分別操作建立、返回、補選、結果捲動與預覽；實體 Fold 的折疊切換也需驗證。

## 人工核對與確認記帳

畫面統一以「消費」表示一筆交易；「照片」是附件，「草稿」是尚未確認的保存狀態。收據、發票與餐廳明細都可以作為照片附件，不要求使用者理解內部資料模型。

1. **消費資料**：手動填商家，日期可稍後補。日期空白保持未知，不自動填今天。照片匯入不表示欄位已被辨識；人工流程不會自動啟動 OCR。
2. **消費品項**：按「新增品項」，填品名、正整數數量與**行合計**。例如 2 份餐點這一列共 120 元，數量填 2、行合計填 120；不再乘以 2，不推算未記錄的單價。完成此品項回清單，可再次展開修改或刪除。照片不必逐列重選；需要精確佐證時才展開「此品項的照片關聯與來源」。切換預覽照片不會建立或確認任何 link。
3. **金額核對**：填整筆交易總額。只有明細另外列出的折扣／費用才展開新增，明確選「整筆消費」或「指定品項」及適用數量；不知道適用範圍可以留待補填。已含在品項行合計的折扣不要再扣一次。空白保持 Unknown，0 是明確已知的零。
4. **保存草稿**：底部固定顯示未保存、保存中、保存未完成、草稿已保存或可確認記帳。草稿允許資料缺漏、總額不平；非法數字／日期要先修正才可保存。返回有「保存並離開」「繼續填寫」「放棄本次修改並離開」。保存失敗或衝突保留輸入，不繼續離開或開啟 Picker。
5. **確認記帳**：確認全部品項與另列加減項已填完整，保存後才可確認。完全平衡為差額 0；容差內為 ±1 最小單位，畫面與確認對話框會列明實際差額，不改數字湊平。確認完成後仍是同一筆消費，顯示唯讀與「完成，返回消費紀錄」。

修改已勾選完整的內容、新增／刪除品項或補入新照片後，要重新勾選完整性。這只是避免沿用過期勾選，沒有放寬 `ReceiptReconciler`／`ReceiptValidator`／`TransitionReceiptStage` 的既有 gate。日期 Unknown 不單獨阻擋確認；缺商家、品名、數量、行合計、總額或加減項適用範圍等仍會阻擋。

所有金額以幣別最小單位的非負整數輸入（TWD 為元）；不接受小數、負號、千分位、科學記號或溢位。未改動的 Fact／provenance、原價、促銷、分攤與 evidence links 保留，未知原價不拿實付額代填。刪除有折扣／促銷／分攤引用的品項會拒絕保存並指出相關位置。

編輯緩衝在 ViewModel／SavedStateHandle，包含非法半成品字串。一般返回有明確保存選擇；Activity saved-state 重建可恢復。**force-stop、移除最近任務及清除資料不保證恢復未保存內容，請先保存草稿。** Room 仍是已保存資料唯一來源。一次最多 100 品項、50 調整，每欄 500 字元。確認後不可重開或修改。

可重現的人工驗收：

1. 在相簿分享一張明細，以及首頁選兩張照片各做一次；應各開啟一筆消費，照片數量清楚，直接看到填寫步驟。
2. 建立「午餐」：三個品項分別填數量／行合計 2／100、1／70、1／30。修改第一項行合計為 120，刪除第二項，保留兩項合計 150；照片附件不被刪除。
3. 交易總額先填 200，確認看到差額 -50 和修正提示。保存離開後再開啟，兩品項與數量 2、1 保留；日期沒填仍未知。總額改 150，重新勾選完整並保存，應顯示完全平衡。
4. 另做一筆行合計 100、總額 99：應顯示容差內、差額 +1；確認對話框仍列出差額。完成後唯讀，原金額仍為 100／99。
5. 未保存時返回：分別試「繼續填寫」「保存並離開」；再試非法總額 `12.`，保存應失敗且留在原交易。填寫中補照片需先保存；新增照片後保留品項並取消完整性勾選。編輯中分享另一張照片不得混入原交易。
6. 在 Compact、Expanded、鍵盤展開及大字級下重做新增／修改／保存／返回；照片放大返回後應保留原品項位置。實體 Fold 的折疊切換、系統相機／相簿分享與閱讀負擔仍需本人驗收。

本次 169 項主機測試、lint、debug build，以及 8 項 Android 案例與 200% 字級／Expanded 回歸均通過。程式與 UI 證據、原流程診斷見 [人工核對驗證](docs/ARCHITECTURE.md#人工核對驗證)。不以測試通過宣稱「直覺」已獲使用者確認。

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
- [ ] 複雜促銷修正、自用／代墊與平分介面。
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

在消費填寫畫面展開「其他工具：本機收據辨識」，勾選**同一交易的收據圖片**，按「辨識收據／重試」。有未保存內容時須先保存。本機逐頁辨識完成後，自動建立品項並進入既有人工核對畫面；保存修正、勾選完整，再通過既有 gate 才能確認記帳。完整人工輸入入口保留。

- 真實路徑是隨 APK 打包的 `com.google.mlkit:text-recognition-chinese:16.0.1`，配合 `receipt-layout-1` 本機解析器。無須下載 Nano，不使用雲端。Nano 本版本未接入，不能將 OCR 可用視為 Nano 可用。
- 本次解析限 **TWD**。優先處理明確的「品名／數量／單價／金額」欄位或「品名 數量 × 單價 行合計」行式；可解析西元／民國日期、交易總額與另列折扣／費用。收據版面沒有明確欄位時仍可產生品名候選，但數量或行金額保持未知。單價不乘成行合計，也不代填未知數量 1。
- 商家採第一個合適文字行作候選，可能需要修正；統編、電話、付款、找零、稅額摘要不作一般品項。複雜促銷、換行品名、無標題欄位、傾斜／模糊與非 TWD 不保證能處理。無任何可用品項會顯示失敗，不以純 OCR 文字當成擷取成功。
- 跨頁同名或差一字的候選保守合組；同頁的重複列保留。跨頁組合的數量與金額保持未知、原文都保留，請核對後填值，若實際是不同品項可新增。其他漏頁、不同 OCR 名稱的重複仍需人工檢查。
- 另列折扣／費用會帶入正負方向與可解析金額，scope 保持未知，請明確選擇整筆或指定品項；已含在價格或稅額摘要中的金額不自行重複扣加。
- 取消、逾時（120 秒）、離開前景或程序中斷不套用未完成結果。旋轉也可能取消辨識；選圖狀態可復原，重試須由使用者啟動。辨識期間不能追加圖片或進入編輯；即使其他 writer 更改 revision、圖片 metadata 或 bytes，結果也會被 CAS／SHA-256 檢查阻擋。
- 重新辨識成功會取代商家、日期、總額、品項及調整，包括保存的人工修正，因此須先通過顯示目前版本與數量的取代確認。失敗保留原草稿。含促銷／分攤關聯的草稿拒絕取代；Confirmed 交易不可辨識或修改。
- 核對畫面可展開辨識原文、圖片及區域座標，保存 analyzer／SDK／parser／schema 版本與來源 SHA-256。座標對應 EXIF 轉正、最多 4096px 的 OCR 圖片，並記錄該座標空間尺寸，原圖 bytes 保持不變。
- 單次最多 20 圖、100 品項、50 調整；OCR 最多 500 行、每行 500 字、總文字 50,000 字。超限拒絕整批，不靜默截斷品項。
- 核對畫面分別顯示「已知金額試算差額」與既有 reconciliation 阻擋原因；試算平衡不代表完整或辨識正確。容差仍是 ±1 最小單位，沒有自動確認。

持久化 draft payload 更新至 **format 3**，向前讀取 format 1／2；舊資料的 extraction 為空、revision 不變。SQL schema 仍為 v1，沒有 destructive migration。升級後不可假設舊 APK 能讀取新版資料。

本次隔離 worktree：`D:\projects\pixel-receipt-assistant\.gradle\worktrees\receipt-auto-extraction`，分支 `feat/receipt-auto-extraction`；起始 SHA `d31e1049e69dd4c6de818ddb963c337e1e808b43` 已包含人工核對。工具與快取均在本 worktree 的 `.gradle/`，不共用其他任務的建置輸出。交付 APK `app/build/outputs/apk/debug/app-debug-manual-review-signature.apk` 已使用人工核對基線的 debug key 簽署，憑證與來源 APK 相同；手機現有安裝簽章仍未比對，**不要卸載或清除資料解決簽章衝突**。一般 `app-debug.apk` 使用本 worktree 獨立測試簽章，供 emulator 驗證，不應直接拿來更新手機。

品質資料與驗證證據請見 [自動擷取驗證](docs/ARCHITECTURE.md#自動擷取驗證)。

2026-09-09 最終技術驗證：**162 項主機測試、0 failures／errors／skipped，lint No issues found，debug build 通過；獨立 Android API 35 x86_64 emulator 的 5 項真實 ML Kit／Compose 測試通過**。涵蓋照片匯入→OCR→自動建立品項→Room→人工核對→使用者確認，以及中文多品項／另列折扣、多頁重疊、空白失敗與畫面辨識按鈕自動導入表單。資料全為合成，真實收據品質及實體 Pixel 驗收未完成。
