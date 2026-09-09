# PixelReceipt Android 架構

## 目標與範圍

這個專案以 **Google Pixel 10 Pro Fold** 為主要實機，採原生 Android 架構，同時保持對一般 Android 手機與不同視窗尺寸的相容性。現在已完成可建置、可安裝的 Phase 0，以及 Phase 1A 的 evidence／Fact／promotion／pricing／AI routing domain contracts。本機 Sharesheet、Photo Picker、多圖 inbox、Room、人工核對、確認記帳與 ML Kit 中文 OCR／本機收據解析已接入；Gemini Nano、Firebase AI Logic、拆帳與 Google Sheets adapters 仍待實作。下圖的 Nano 與雲端仍是目標架構，現行辨識流程見「自動擷取架構」。

App 支援下限採 `minSdk 26`。這只是安裝下限，不代表每台 Android 8+ 裝置都能執行 Gemini Nano；Nano 必須另外在 runtime 檢查裝置、Android／AICore、模型下載與個別 ML Kit GenAI API 的可用性。

暫定 application ID 是 `com.momonong.pixelreceipt`。開始設定 OAuth、Firebase、正式簽章或上架前，必須先確認這個 ID，之後不要任意更改。

## 架構總覽

```text
原廠相機 ── Android Sharesheet ─┐
                               ├─→ Evidence ingestion ─→ app-private files
系統 Photo Picker ─────────────┘          │
                                         ▼
                         多圖 inbox + evidence classification
                         ReceiptPage / PriceTag / PromotionSign / Other
                         未分類 = Fact.Unknown
                                         │
                                         ▼
                              AiRouter（domain use case 已完成）
                         ┌───────────────┴────────────────┐
                         ▼                                ▼
          ML Kit GenAI / Gemini Nano              Firebase AI Logic
          runtime 支援且 App 在前景               每批明確同意上雲
                         └───────────────┬────────────────┘
                                         ▼
                         extracted facts + candidate links
                                         ▼
                      多對多 matching + deterministic pricing
                      scoped adjustment / Unknown / reconciliation
                                         ▼
                         Room → Adaptive review → Sheets export
```

Compose UI → domain ← data adapters 的依賴方向不變。Domain 不引用 Android URI、ML Kit、Firebase、Room 或 Google Sheets 型別；`AiRouter` 位於 domain use case，透過 `OnDeviceReceiptAnalyzer`／`CloudReceiptAnalyzer` ports 呼叫 adapters（目前為本機中文 OCR，Nano 與 cloud 待實作）。Evidence repository 與 exporter 同樣只傳 domain model 或明確結果。

目前先維持單一 `:app` module，以 package 邊界降低初期複雜度；當 Room、相機與網路 adapter 進入專案後，再依編譯隔離與多人協作需求拆成 `:domain`、`:data`、`:feature-*`。不要只為了形式提早拆 module。

## Package 邊界

```text
com.momonong.pixelreceipt
├── app/                 App composition root
├── core/ui/theme/       共用 Compose theme
├── domain/ai/           AI capability、runtime、consent 與 route contracts
├── domain/model/        Evidence、Fact、Receipt、Promotion、Money
├── domain/port/         Repository、Analyzer、Exporter 邊界
├── domain/rules/        Promotion pricing、matching validation 與 reconciliation
├── domain/usecase/      合法狀態轉換與 revision/CAS 協調
├── data/local/          Room entities／DAO、versioned codec、CAS repository
├── data/ingestion/      私有圖片、驗證、去重、匯入協調
└── feature/inbox/       草稿列表、多圖 inbox、預覽、ViewModel／StateFlow
```

Phase 1A 的 evidence、matching、pricing 與 AI routing contracts 已沿用這些 package 邊界。Phase 1B adapters 也必須遵守相同依賴方向；是否拆 module 由實際編譯隔離需求決定。

## 可折疊裝置策略

- 不用「手機型號」或固定方向判斷版面，永遠依目前 app window 的 size class 決定。
- Compact window（例如外螢幕或分割視窗）使用單 pane 導覽；有足夠寬度時由 Material 3 Adaptive 呈現 main/supporting panes。
- App 不鎖定 portrait/landscape，也不宣告不可 resize。
- 所有畫面採 edge-to-edge，內容套用安全 inset。
- Supporting pane 可滾動，避免內螢幕多工、窄視窗或大字級時裁切。

`AdaptiveLayoutPolicy` 使用官方寬度斷點：Compact `<600dp`、Medium `600–839dp`、Expanded `840–1199dp`、Large `1200–1599dp`、Extra large `>=1600dp`。

## Evidence 匯入（已實作）

原廠相機是預設拍攝入口，使用者不需要先開 PixelReceipt。App 透過 Android Sharesheet 接收 `ACTION_SEND` 與 `ACTION_SEND_MULTIPLE`；若照片已經存在，透過系統 Photo Picker 一次補選一張或多張。兩條路徑都只取得使用者選定的媒體，不要求廣泛相簿權限。實作依據：[接收其他 App 分享的資料](https://developer.android.com/develop/ui/compose/sharing/receive)、[Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)。

收到分享的 URI 後，ingestion adapter 必須在讀取權限仍有效時，將內容串流複製到 app-private storage，再以 content hash 去重；不得把外部 `content://` URI 當成永久檔案位置。不同草稿的每張圖建立獨立 `EvidenceAsset`，同一草稿相同 hash 不重複建立 asset。外部分享始終建立新草稿；Photo Picker 可建立新草稿或向已選草稿補圖。移動、移除、分類與 derived artifacts 不在本次範圍。

Evidence 類型至少包含：

- `ReceiptPage`：發票或交易明細，主要提供實際品名、數量、行小計與交易總額。
- `PriceTag`：貨架價標，可能提供品名、條碼、原價、會員價與有效期間。
- `PromotionSign`：促銷牌或區域活動牌，可能涵蓋多個品項與附帶條件。
- `Other`：已確認為上述三類以外的圖片。

「尚未可靠分類」以 `Fact.Unknown` 表示，不使用 `Other` 冒充。分類結果可帶來源、模型版本與 observation-level confidence，但仍只是 suggestion。原始檔 immutable；`EvidenceRegion` 可引用原圖區域。EXIF 旋轉與降採樣只用於暫時預覽；不保存 derived artifact 或 parent graph。

## Evidence-first 資料流（Phase 1B adapters 規劃）

```text
Sharesheet / Photo Picker（1..N 張）
        ↓
app-private evidence + Room case draft
        ↓
Evidence classification + structured fact extraction
        ↓
receipt lines ↔ observations ↔ promotion/price evidence（多對多）
        ↓
deterministic pricing + reconciliation
        ↓
Room (NeedsReview；允許 Unknown) → Adaptive manual review UI（已實作）
        ↓ 使用者確認 evidence、matching、adjustment 與 split
Room (Confirmed / ExportPending)
        ↓
unique Sheets export job → Exported
```

Room 是唯一可信資料來源。Google Sheets 是單向匯出目的地，不與 UI 形成雙主資料庫，也不在網路失敗時丟失本機草稿。WorkManager job 以 receipt ID 做唯一鍵，讓 retry 保持冪等。

UI 與 worker 都不能直接做無條件的全量覆寫。Use case 驗證合法 stage transition、遞增 revision，再由 repository 以 compare-and-set 原子更新；revision 已改變時回傳 conflict 並重新讀取。AI adapter 只回傳 recognition facts 與 candidate links，不能建立本機 ID、指定 stage、確認促銷或寫入帳務結果。Exporter 只收到已驗證的 immutable batch 和 idempotency key，不得反向讀 Room。

Nano 推論不可假設能在 WorkManager 背景工作執行。需要 Nano 的分析只在 App 可見且符合 API 前景條件時啟動；離開前景時保存進度並保持 pending。WorkManager 可負責可持久化的匯入後處理、經同意的雲端工作與匯出，但 retry 不得繞過 cloud consent 或改變選定的 AI route。

收據生命週期使用明確狀態：

```text
Captured → PendingAnalysis → Analyzing → NeedsReview
                              └───────→ AnalysisFailed
   └──────── 人工核對 ─────────────────→ NeedsReview

NeedsReview → Confirmed → ExportPending → Exported
                                  ├────→ ExportFailed
                                  └────→ AuthorizationRequired
```

`NeedsReview` 不代表資料已完整；它可以合法包含 evidence、matching 或 adjustment 的 `Unknown`。`TransitionReceiptStage` 在進入 `Confirmed` 前必須取得 `ReceiptReconciler.Balanced` 或 `WithinTolerance`；incomplete receipt、未知 merchant／line name／quantity／total／line amount／adjustment amount／scope、pending promotion 或 validation issue 都會回 `ConfirmationBlocked`，且不寫入 repository。

### 人工核對與編輯緩衝

狀態機只新增 `Captured → NeedsReview` 邊：讓已保存圖片直接交給使用者核對，無須虛構 `PendingAnalysis`／`Analyzing` 或 extraction provenance。進入時仍經 `TransitionReceiptStage` 和 repository CAS，不改寫 merchant／其他 Fact。`NeedsReview → Confirmed` 的既有容差和必要資料要求不變；新日期欄位 Unknown 不單獨阻擋既有 gate。

`InboxViewModel` 持有 `ReviewSession`，以 StateFlow 提供原始 snapshot、編輯輸入、busy／dirty／conflict／錯誤狀態。`ReviewInput` 的文字、明確 scope portions 與原始 revision 經 SavedStateHandle 保存，畫面重建不需要把半成品文字寫進 Room。返回可選保存並離開、繼續填寫或明確放棄；保存成功後才執行離開／選照片。force-stop 等沒有 saved-state 復原保證的操作，UI 提醒先保存。編輯限制為 100 品項／50 調整及每欄 500 字元。busy 時抑制重複操作與返回，Confirmed 和其他非 NeedsReview 狀態唯讀。

`ManualReceiptReview` 在 domain 解析非負 Long 金額、正 Int 數量和嚴格 ISO calendar date，輸入不經浮點數。空白保留／轉為 Unknown；未修改的 Fact（包含 Conflicting、NotApplicable、原價與來源）原樣保留。修改值保存 `UserConfirmed` timestamp 及使用者選定的 evidence references；額外指定圖片建立 Confirmed EvidenceLink，不移除原有關聯。新調整為 Other 類型，支援 Add／Subtract 和 Order／Line／LineSet，未做促銷推論、價格查詢或自動分攤。刪除品項／調整只移除其 evidence link，若仍被其他 domain entity 引用則拒絕保存，不能靜默破壞 lineage。

保存可保留尚未齊全／不平衡的草稿，解析錯誤不可保存。確認只作用於已保存 snapshot，由原 `TransitionReceiptStage` 調用 reconciler／validator，再 CAS 寫入同筆交易的 Confirmed 與下一 revision。重複確認不產生新交易。UI 區分 Balanced、WithinTolerance、Unbalanced，顯示 `computed − receipt` 的有號差額；範圍未知、適用數量超限、必要 Fact 不全、混合幣別及既有促銷分攤問題都能阻擋確認。

CAS 衝突保留本地 snapshot 與輸入，讀取最新版供比較；明確放棄後才重新載入，從不自動 rebase／覆寫。寫入錯誤保留輸入並允許重試。保存後 Room Flow 更新列表；再次開啟從 repository 讀取。核對時收到外部分享會先保留待處理 URI，提示保存目前交易後新增另一筆消費，或取消這批分享；不隱性切換或混入附件。待處理 URI 不是持久化資料，程序中斷後可能需重新分享。

## Room 與檔案一致性

`ReceiptApplication` 提供單一 database／repository／importer，ViewModel 只從 Room Flow 派生 StateFlow；UI 的選取與進度不是第二份草稿資料。Domain 不引用 Room／Android。`ReceiptDatabase` v1 保存：

| Table | 用途 |
| --- | --- |
| `drafts` | ID、revision、建立時間、完整 versioned `ReceiptDraft` payload |
| `blobs` | SHA-256 主鍵與原始 byte size；路徑由 hash 推導，不接收外部路徑 |
| `evidence` | 草稿各自的 asset ID、blob FK、完整 evidence metadata／classification Fact |
| `draft_evidence` | draft／asset 外鍵、同草稿唯一 asset 關聯及穩定 position |
| `imports` | operation ID、目標／結果草稿 ID、running／completed／interrupted、逐張結果；不保存外部 URI |

`DraftCodec` 是只用於 app-private DB 的 JSON 格式，使用固定 allowlist tag 保存所有 Fact 狀態、generic values、provenance、links、promotion 與整數 Money；不得拿來解析 AI／外部 JSON。草稿目前寫入 format 3（人工核對基線為 format 2），evidence metadata 繼續 format 1。`ReceiptDraft.transactionDate` 是 ISO 日期 `Fact<String>`；format 1 缺省日期明確遷移為 `Unknown(NotObserved)`，不能依賴 Gson 執行 Kotlin constructor default（Gson 可略過 constructor）。讀取不寫 DB／遞增 revision，下次合法 CAS 才保存新 payload。SQL 表結構無變動，`ReceiptDatabase` 保持 v1，無需 SQL migration；固定 legacy fixture 與實際 SQLite 重開測試驗證相容。缺少日期的損壞 v2 或未知格式拒絕讀取，沒有 destructive fallback。僅支援 v1 的舊 APK 無法讀 v2，勿將降版當成相容操作。

草稿 evidence membership 與關聯表在同一 Room transaction 更新。金額不經過浮點數，既有帳務語意未更動。Gson 欄位名稱是持久化格式的一部分，ProGuard 已保留 domain model 欄位；未來欄位／enum／tag 變更必須提供 payload migration，不能直接改名。

`createDraft` 僅接受 revision 0，重複 ID 回 Conflict；`compareAndSetDraft` 要求 next = expected + 1，使用 SQL revision 條件與 Room transaction 原子寫入 payload／關聯，缺少 evidence 會拒絕，不允許舊 revision 覆蓋新資料。匯入追加允許 Captured／NeedsReview 草稿，保留既有 Fact、links、extraction 與 stage；只有真正新增圖片才將 assemblyStatus 設回 Collecting，重複或取消不改。分析中、Confirmed 及後續狀態仍拒絕追加。批次讀取期間如被其他寫入更新，整批回衝突並回滾新 metadata，不偷偷重套至較新版本。

原圖處理順序為：串流至 UUID `.part` → SHA-256／格式／尺寸／解碼驗證 → sync 原始檔 → rename 至 hash 路徑 → 單次 Room transaction 保存 assets、draft membership、revision 與完成報告。既存 hash 重新核對 bytes／digest 後共用，不覆寫。不同交易僅共用 immutable blob，各有自己的 evidence ID／metadata，絕不以 hash 合併交易。

每批 20 張、每張 20 MiB、整批讀取 100 MiB（含重複與失敗讀取）、單張 50 MP／單邊 20,000 px、圖片目錄總量 1 GiB。逐張串流／解碼，避免一次讀入多張原始圖。支援 JPEG／PNG／WebP，API 28+ 解碼拒絕 partial image；API 26/27 使用 BitmapFactory 與容器結尾完整性檢查，不能保證辨識每一種局部壓縮資料損傷。預覽最多 1600px，含 EXIF 方向處理；原圖完全保留。

單一 importer Mutex 序列化匯入與垃圾清理；未來其他 process／worker 不可繞過此協調器發布或清理檔案。每批完成／失敗／取消後清理 `.part` 與無 `blobs` 記錄的檔案；啟動先把 running 批次標記 interrupted，再清理同類孤兒檔案。DB transaction 失敗不留下成功草稿；程序在 rename 與 commit 之間被殺掉只留下可回收檔案。程序在 commit 後被殺掉，Room 紀錄仍完整。復原不自動重讀來源 URI；使用者重選後同草稿依 hash 去重。成功記錄與使用者明確建立的空白草稿可區分，全部圖片失敗不建立新草稿。

Activity 使用自己的 UUID（不信任分享 extras 裡的 ID），保存於 instance state；ViewModel 避免 Activity 重建重送，Room operation 主鍵阻止已完成／中斷批次重播。Photo Picker 的 target 在啟動時保存在 SavedStateHandle，避免選取途中切換草稿而加入錯誤交易；返回空集合不改 DB。API 舊版由 AndroidX 合約退回系統選取器，仍不需要廣泛相簿權限。使用者取消只撤回尚未提交的整批；已提交資料不撤銷。

首次落地為 schema v1，已保存 KSP 輸出 `app/schemas/.../1.json`。此前無 Room schema／本機資料可遷移，不新增虛構的 v0 migration。測試用匯出的 DDL 建庫與既有資料，讓 Room 開啟時驗證 schema 並確認資料保留；另驗證缺少 migration 的版本失敗且原資料仍在。未來提高 DB version 時必須加入顯式 migration 與舊版資料 fixture，禁止 `fallbackToDestructiveMigration`。

## 收據匯入驗證

2026-09-07 本次在 Windows 以專案局部 Temurin JDK 17／SDK Platform 37.0 執行 `testDebugUnitTest lintDebug assembleDebug`：**113 tests、0 failures、0 errors、0 skipped；lint No issues found；debug APK 組裝成功**。其中本次新增 16 tests（codec 3、Room／ingestion 10、分享 Intent 3）。測試涵蓋完整 Fact/provenance 編碼、實際 Room/SQLite round-trip、schema DDL 相容性、缺少 migration 保護、CAS、混合有效與無效圖片、追加與跨交易去重、operation 重播、取消、資源上限、模擬 crash recovery 及分享 Intent 解析。Robolectric 的圖片測試使用 native graphics；主機測試不等同實體 Android 程序／權限或 UI 驗收。

目前 `adb devices -l` 無連接裝置，未執行 Sharesheet／Photo Picker 實機、真正 Activity 旋轉、OS 撤銷 URI grant、force-stop 後 UI、Compact／Expanded／Fold／分割視窗整合驗證。對應手動步驟在 README。UI 可依 window size class 切換可捲動單／雙 pane，但尚不能宣稱實機視窗驗收通過。

| 驗收項目 | 本次證據與狀態 |
| --- | --- |
| 1. 分享／Picker 建立可開啟草稿 | 分享 Intent 解析與 importer 主機測試通過；系統 Sharesheet／Picker UI 未驗證 |
| 2. 既有草稿追加、不覆蓋 | Room 追加及原 membership／revision 測試通過；裝置 UI 未驗證 |
| 3. 程序重啟仍可讀 | 關閉／重開實際 SQLite DB 與本機圖片解碼通過；OS force-stop 未驗證 |
| 4. 外部權限失效仍可讀 | 本機 bytes／digest 完整性與不重讀來源的 operation replay 通過；OS URI grant 撤銷未驗證 |
| 5. 去重與重建不重播 | 同草稿去重、跨草稿隔離、operation 冪等測試通過；Activity 旋轉／重建未驗證 |
| 6. 混合成功失敗與重試 | 主機混合批次、整批重試／追加結果通過；裝置結果畫面未驗證 |
| 7. Room domain round-trip／CAS | Fact／provenance codec、Room、schema 與舊 revision 拒寫測試通過 |
| 8. 失敗／中斷沒有假成功草稿 | SQL failure rollback、取消、全失敗及模擬程序中斷恢復通過；真實程序中斷未驗證 |
| 9. Compact／Expanded 操作 | UI 已實作；裝置／模擬器視窗驗收未驗證 |

未測突然斷電／儲存硬體故障；原圖檔案有 sync，但不宣稱跨檔案與 SQLite 的硬體掉電原子性。來源 provider 若阻塞讀取，取消可能需要等待目前的讀取返回；沒有新增背景服務或 provider 的硬性 timeout 架構。


## 人工核對驗證

2026-09-08 在原生隔離 worktree、指定起點 `1a2f624e42508a4d90554ee92757c57cdbab5a19` 上，以局部 JDK 17／SDK 37.0 執行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1
```

最終結果 **BUILD SUCCESSFUL，1m 7s；136 tests，0 failures／errors／skipped；lint No issues found**。新增 23 tests：ManualReceiptReview 15、ReviewSession 4、codec 2、實際 Room／SQLite 2；上游 113 tests 全部維持通過。`app/schemas/.../1.json` 沒有改動。

| 驗收 | 主機證據與限制 |
| --- | --- |
| 不使用 AI 完成人工記帳 | `ManualReceiptReviewTest` 驗證合法直接人工轉換、保存及 confirmation gate；`ReceiptPersistenceTest.manualReviewFromImportedPhotoSurvivesDatabaseReopenAndConfirmedReplay` 從實際匯入照片跑至 Confirmed |
| 重開保留 Fact／日期／provenance／evidence | 實際 SQLite 關閉重開、逐 Fact equality、原圖解碼與 links 保存通過；未執行 OS force-stop |
| 未保存內容策略 | `ReviewSessionTest` 驗證 SavedStateHandle 原始字串和 base revision 重建、非法半成品保留及後續保存；UI 返回對話框／真正 Activity 重建待裝置驗收 |
| 缺漏與非法輸入阻擋 | 正整數數量、非負 Long 金額、溢位、小數、分隔符號、非法日期、必要 Fact、缺品項／scope／適用數量等測試通過 |
| 原價／折扣未知 | 原價 Unknown／Conflicting 與其他未修改 Fact 保留；未知日期不改既有 gate；無自動補零／代填 |
| scope 與加減方向 | Order、Line、LineSet、明確適用數量、Add／Subtract 各一次計算，行金額不乘數量；超量阻擋確認 |
| CAS 衝突及恢復 | 保存／確認舊 revision 拒寫；Session 保留輸入、最新版摘要及明確 reload；重載 Confirmed 後唯讀 |
| Confirmed 持久化／冪等 | 同一 receipt ID 和 revision 持久化；重複確認、確認後保存拒絕，不新增交易 |
| payload／schema 相容 | 固定 `legacy-draft-v1.json` fixture、舊資料讀取不寫回、CAS 才升 v2、SQL v1 重開、未知／損壞 format 拒讀；上游非破壞性 schema 保護測試仍通過 |

本機證據：`.gradle/manual-review-final-gate.log`、`app/build/test-results/testDebugUnitTest/TEST-*.xml`、`app/build/reports/tests/testDebugUnitTest/index.html`、`app/build/reports/lint-results-debug.txt`。APK `app/build/outputs/apk/debug/app-debug.apk` 的 SHA-256 為 `1d8d26850b7f835bb4b520004c7a72ed3f6a98db8f17536b3dd4d04d02058bba`。

首次並行 gate 的測試／APK 完成，但 lint 超過六分鐘仍在 `KotlinUFile.getAllCommentsInFile`／`BidirectionalTextDetector`，兩次 thread dump 保存在 `.gradle/manual-review-threads*.txt`，日誌為 `.gradle/manual-review-gate-lint-stall.log`。僅停止本任務該程序後，單一 worker 完整 gate 通過；尚未建立 lint 卡住原因的最小重現，不宣稱是專案或上游工具的確定缺陷。沒有修改 lint 規則或 warnings-as-errors。

`adb devices -l` 本次無裝置；Compose 互動、Compact／Expanded／Fold、大字級／鍵盤、Sharesheet／Picker、OS URI 撤權及真正 force-stop 仍未驗證。README 有手動驗收步驟。APK 使用局部新 debug key，憑證 SHA-256 為 `6fa1a1e710134668a0443876160ee821b3fd044705ef319bbcfb88ee993f4db2`，與上游 APK 不同；既有 key 複製被自動核准審查拒絕，未執行。後續保留資料升級應待授權整合後於原簽章環境建置，不要卸載有資料的 App 來完成此驗收。

### 消費流程重整（feat/receipt-flow-ux）

來源核對：人工核對 worktree `C:/Users/morris/.codex/worktrees/dfa6/pixel-receipt-assistant` 已乾淨提交為 `d31e104`；OCR worktree 已乾淨提交為 `40e6a82`，相關任務均閒置。本分支從已包含兩者的 `fcd673ef718e9bea2ec3f651dee3c080b00d5c02` 建立，沒有降回只有匯入或 payload v2 的版本。SQL schema v1、draft payload format 3（讀取 1／2／3）維持原樣。

本次工作目錄 `D:/projects/pixel-receipt-assistant/.gradle/worktrees/receipt-flow-ux` 是獨立 Git worktree，不能當作快取刪除。未改動其他工作目錄的程式碼，未合併 main、推送或發布。

| 現有情境與診斷（程式 walkthrough；另參考來源任務既有畫面） | 本次具體改動 |
| --- | --- |
| 相簿／相機分享後只到圖片收件匣，還須理解「人工核對」才開始填寫 | 成功匯入直接開啟同一筆消費的三段表單；不自動辨識 |
| 首頁以草稿 UUID 與圖片操作為中心，缺少新增消費動詞 | 首頁主要入口「新增消費・選照片」，另有無照片人工入口；列表用商家、總額與保存狀態辨識 |
| 品項表單全展開且逐列顯示識別碼、來源引用，容易以為每列都要選圖 | 品項清單加單列展開；數量和行合計分開標示；精確照片關聯按需展開 |
| Compact 照片在長捲動表單頂端，輸入數列後要反覆捲回 | 照片區位於表單捲動外，放大後回原位置；鍵盤時收起照片區，短視窗不展開內嵌預覽；Expanded 分左右 pane |
| 核對後不能補照片，迫使使用者事先知道內部階段限制 | 保存後可向同筆 NeedsReview 追加；同一 Room transaction/CAS 保留欄位與來源並重設完整性 |
| 保存和確認都在長頁底部；返回只可繼續或放棄 | 固定保存狀態與主要操作、保存並離開；失敗／衝突不執行後續動作 |
| 完整性在品項之前、錯誤含 UUID／內部狀態 | 完整性移到金額核對最後；欄位解析錯誤就地提示，對帳以第幾個品項／名稱指出位置 |

UI 照片上下文只保存目前預覽的 asset ID，不填入 `ReviewLineInput.evidenceIds`。不要求逐品項來源即可完成既有合法人工 gate；這是原 domain 允許的行為，沒有新增虛構 provenance。使用者明確展開並選擇來源，才沿用原 `ManualReceiptReview` 建立 Confirmed link；原 Candidate／Confirmed／Rejected 關聯均保留。

`ReviewSession.save(afterSaved)` 只在 CAS 寫入成功後繼續離開或開 Picker；解析錯誤、I/O 失敗和衝突保留輸入。追加時 session busy 鎖住編輯，完成後只在 buffer 無修改時 reload；Picker target 固定綁定原交易。`edit` 發現已勾選完整的內容變更時撤回勾選，重新確認仍須使用者明確操作。確認規則、金額計算與資料格式沒有變動。

2026-09-09 最終 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --no-daemon --max-workers=1`：**BUILD SUCCESSFUL（1m 30s），169 項主機測試，0 failures／errors／skipped；lint No issues found，兩份 debug APK 建置完成**。相較整合基線新增 7 項主機案例，包含保存後續動作、保存失敗／衝突、完整性重查、dirty buffer 保護、照片上下文不建立 link、欄位回饋及 Room 補圖保留資料／Confirmed 拒寫。domain、codec、SQL schema、application ID 與建置設定均未更動。

獨立 `receipt-flow-test`／`emulator-5582`，Android API 35 x86_64：**8 項 Android 測試通過（52.372s）**，包括既有 4 項真實 ML Kit pipeline、更新後的 OCR Compose 入口測試及新增 3 項人工流程測試。實際操作包含系統 Photo Picker 選兩張照片、先保存人工品項再追加第三張、取消 Picker 不改資料、ACTION_SEND 進入同一筆消費、照片放大返回、三品項新增／修改／刪除、未保存返回保護、Activity recreate、保存重開、差額 -50、完全平衡、+1 容差與 Confirmed 唯讀。另在 200% 系統字級（21.715s）及 900×760dp Expanded（19.825s）各完成一輪三品項人工操作，均通過。這是 8 個不同 Android 案例加 2 次不同視窗／字級回歸，不是 10 個不同案例。

| 驗收範圍 | 本次證據與觀察 |
| --- | --- |
| 首頁／分享開始消費 | 原生 Picker 真實 UI 與 ACTION_SEND 接收流程通過；分享 Intent 由測試程式送出，尚未用 Pixel 原廠相機／相簿的分享選單驗收 |
| 照片接續同筆交易 | 實際先選兩圖、輸入品項、保存後追加第三圖；資料庫仍同一 ID、同一品項，零虛構 link |
| 三品項增修刪與數量／行合計 | 2／100、1／70、1／30；第一列改 120，刪除第二列；保存後兩列共 150、數量仍 2 與 1 |
| 草稿／未保存保護 | 真正 Activity recreate 與保存離開再開啟通過；非法 `12.`、衝突不離開／不啟動後續行為由 Session 測試驗證 |
| 金額缺漏與差額 | 欄位就地提示；200 對 150 顯示 -50、阻擋確認；Unknown 日期保留，0 不被當成空白 |
| 完全平衡／容差／唯讀 | 150／150 顯示完全平衡；100／99 在表單和確認對話框顯示 +1 容差，確認後兩個原值未被改寫 |
| 原資料保護 | Room 補圖、重開、Fact／provenance／links 保留與舊 payload fixtures 通過；新 APK 未裝到實體手機，未驗證手機原資料升級 |
| 視窗／鍵盤／字級 | Compact 約 411×914dp、Expanded 900×760dp、font scale 2.0；數字鍵盤展開時保存按鈕可見且可點。200% 字級需要較多捲動，不能同時看到整列所有說明 |
| 程序重開／照片保留 | 模擬器 `am force-stop` 後重開，原三照片草稿與已確認紀錄仍在；開啟原草稿可再次顯示照片及 2／100 品項 |

關鍵畫面均為本次實際 App 截圖、合成資料，未以設計稿替代：

- [首頁](images/receipt-flow/home.png)：新增消費與無照片人工入口優先，既有紀錄顯示商家及保存狀態。
- [Compact 照片與品項](images/receipt-flow/photos-compact.png)：三張附件、既有品項與固定保存狀態；長照片縮圖需放大才能讀細字。
- [Expanded 照片並列](images/receipt-flow/photos-expanded.png)：重啟後的同筆草稿，左側照片、右側表單，沒有把選中的照片套用為品項佐證。
- [200% 字級與數字鍵盤](images/receipt-flow/keyboard-large-font.png)：欄位可捲動，保存操作保持在鍵盤上方。
- [容差內](images/receipt-flow/tolerance.png)、[確認完成唯讀](images/receipt-flow/confirmed.png)：差額與完成語意明確分開。

完整本機證據：`.gradle/flow-gate.log`、`app/build/test-results/testDebugUnitTest/TEST-*.xml`、`app/build/reports/lint-results-debug.txt`、`.gradle/flow-ui-compact-final.log`、`.gradle/flow-ui-large-font.log`、`.gradle/flow-ui-expanded.log`。第一次新增 SQLite 案例出現 Windows 原生檔案開啟錯誤，縮短測試方法名稱後通過；未修改資料庫或測試 gate。分享 UI 測試曾因 ActivityScenario 追蹤的 Intent 被合法 onNewIntent 更新而在 teardown 失敗，測試在結束時恢復 launch Intent 後通過，產品仍按 Android 分享流程處理。仍有既有 Compose JUnit4 rule API 的 deprecation 編譯提示，沒有停用 lint。

交付 APK `app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `a5fa1742838c6d1666655eb7b46ecf775f9ebb512d45a22af096a97298b47481`。憑證與人工核對來源 APK 相同，SHA-256 `6fa1a1e710134668a0443876160ee821b3fd044705ef319bbcfb88ee993f4db2`；實體手機現有安裝簽章尚未比對，未安裝、卸載或清除手機資料。

仍需使用者實機確認：Pixel 10 Pro Fold 的實際折疊／展開／分割視窗、原廠相機及相簿分享、真實長明細的閱讀負擔、長時間多品項輸入是否順手，以及手機保留資料升級。截圖檢查另修正了唯讀畫面沿用「照片可稍後補」等填寫提示的問題；完成後只呈現已保存結果與唯讀狀態。模擬器可操作與測試通過不等於使用者已確認直覺。人工可重現步驟集中在 README。

整合注意：保留現有 OCR `ExtractionSession`、payload format 3 與來源稽核資料。人工輸入不自動啟動辨識；OCR 工具藏在按需展開的區塊，成功後刷新同筆已保存表單，重新辨識仍須原本的取代確認。以後若有 background writer，仍必須遵守 revision／圖片 membership 檢查，不能直接套用 UI buffer。

## 多對多 matching

圖片與品項不是一對一：一張促銷牌可能適用發票上的多個品項；同一品項也可能同時由收據行、價標與區域促銷牌佐證。因此不要把 `evidenceId` 直接塞在 line item 上，而要使用可審查的 link entity：

```text
Evidence 1 ──┐                 ┌── ReceiptLine A
Evidence 2 ──┼── EvidenceLink ┼── ReceiptLine B
Evidence 3 ──┘                 └── Adjustment C
```

`EvidenceLink` 已保存 `EvidenceReference`、target、Candidate／Confirmed／Rejected、provenance、optional confidence 與理由。模型 extraction 只能建立 Candidate；只有 deterministic rule 或使用者能 Confirm／Reject。`PromotionParticipant` 另保存 receipt line quantity 到 promotion product mention 的明確 mapping，避免一張 A/B/C/D 大牌被誤套到未購買品項。刪除 link 不代表刪除 evidence；實際 persistence 的 cascade policy 會在 Room schema 定義。

## Adjustment 與 deterministic pricing

促銷不能只記一個含糊的 `discount_saved`。Adjustment 必須有 scope：

- `Line`：只套用一個已確認品項。
- `LineSet`：任選折、組合價或買一送一，套用一組已確認品項。
- `Order`：全單折抵、支付回饋或可證明的整單折讓。
- `Fact.Unknown` scope：知道可能有折扣，但適用品項或範圍無法由證據確定。

門市、日期區間、會員等級、付款方式、最低件數／金額等 eligibility conditions 必須與 promotion candidate 一起保存；`RetailPromotionApplicability` 已定義這些 fallback 邊界，實際 source adapter 尚待實作。`Unknown` 不是金額 `0`；兩者語意不同，不能為了通過對帳而自動互換。

模型只擷取候選規則與參數，之後呼叫有版本、純函式、整數金額的本機 `PromotionPricingEngine`。現已支援固定單價、組合價、買 X 送 Y、比例、固定額與滿額折；eligible lines 必須先由 explicit mapping 選定。所有中間加總使用 `BigInteger`，比例不足一個最小幣別單位時固定向下取整，成功結果保存 rule version。相同輸入與 rule version 必須得到相同結果。收據對帳使用：

```text
sum(line printedTotal) + sum(Add adjustments) - sum(Subtract adjustments)
    = receipt total
```

`referenceOriginalTotal` 只用於節省金額分析，不參與發票總額對帳。個人／代墊 split 的精確不變量會在 review feature 實作時另加；目前尚未宣稱完成。不符合時回傳可解釋的 reconciliation issue，不能讓模型補出一個剛好平衡的數字。使用者確認過的 override 也要保留原值、原因與 revision。

## AI routing 與上雲同意

`AiRouter` 是 SDK-neutral domain use case，不是單一廠商 wrapper。它的 capability、foreground、network 與 consent gates 已完成；兩個實際 adapter 尚待接入：

1. `MlKitNanoAnalyzer`：直接使用 [ML Kit GenAI APIs](https://developers.google.com/ml-kit/genai) 所提供、由 Gemini Nano／AICore 支援的裝置端能力。每次依任務做 availability check；不透過 Gemini 消費者 App，也不能讀取該 App 的對話、登入狀態或私人模型介面。[Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started) 要求 API 26+。
2. `FirebaseCloudAnalyzer`：透過 Firebase AI Logic 呼叫雲端模型，release 依 [Firebase App Check 指引](https://firebase.google.com/docs/ai-logic/app-check) 使用 Play Integrity，且 APK 不含可直接濫用的 Gemini API key。

Router 採 local-first，但「本機不可用」不等於可以自動上雲。`CloudProcessingConsent.Granted` 綁定 case ID、每個 image ID 的 SHA-256、用途、analyzer ID、service ID 與同意時間；任一內容或處理服務不同都會回 `CloudConsentScopeMismatch`。Phase 1B 畫面仍須在送出前列出 evidence、目的、服務與敏感資訊；新增／修改圖片、改變用途或切換服務都要再次確認。拒絕上雲時保留本機／人工流程，不能阻止使用者手動完成記帳。

Nano 的 availability 與 foreground 限制是正常狀態，不是例外：

- `minSdk 26` 僅是 App 支援下限，不能作為 Nano capability signal。
- 現有 contract 回報 `Available`、`Downloadable`、`Downloading`、`Unavailable`；adapter 會再把 SDK 暫時錯誤映射成 typed failure，不可假造本機結果。
- Nano inference 僅在 App 可見的前景執行；背景 worker 不持有或偷渡前景 UI session。
- 雲端 fallback 必須是可見的使用者選擇，並能顯示／撤回尚未送出的 consent。

## 零售商促銷 fallback

外部促銷資料只用來產生 candidate，不是交易真相。`RetailPromotionLookup` port 支援從 merchant、branch hint、交易日與發票品名開始查詢，不要求一定有照片；回傳的 `RetailPromotionCandidate` 強制帶 HTTPS source、publisher、來源種類與抓取時間。寶雅 adapter 尚未實作，可評估官方 [當期 DM](https://www.poya.com.tw/dm/)、[活動](https://www.poya.com.tw/events/)、[最新消息](https://www.poya.com.tw/news/) 與[門市資料](https://www.poya.com.tw/store/)；現階段不依賴任何具契約或 SLA 的寶雅商品／實體店價格 API。

DM 可能有全國、區域專櫃或新店版本；活動也可能受日期、指定門市、會員、品牌、付款方式與售完為止限制。官方線上購物價格同樣不能證明實體門市當日價格。因此 applicability 的日期／門市／會員／付款條件只要缺少就保持 `Fact.Unknown`。即使候選數學上能對帳，也只能用來排序；仍須交易 evidence 的 deterministic proof 或使用者確認，才能成為 applied adjustment。

## 金額與 AI 信任邊界

- 儲存金額一律使用幣別最小單位的 `Long`，禁止 `Double` 參與帳務運算。TWD 的最小單位就是元。
- 加總與差額驗證使用精確整數運算，避免 `Long` overflow 造成漏報。
- AI 僅負責辨識與建議；金額、折扣分配、拆帳與總額一致性由本機規則決定。
- AI structured output 仍視為不可信輸入，必須通過 schema、範圍、幣別與對帳驗證後才能進入 `NeedsReview`。
- 官方 DM、線上價格與第三方查價結果都只是候選 evidence，不能覆寫 receipt fact 或把 `Unknown` 轉成確定值。
- 使用者確認前不得自動匯出。

## 安全邊界

- APK 不放 Gemini API key。雲端 adapter 使用 Firebase AI Logic，release 版強制 App Check / Play Integrity。
- 本機 Nano 不代表預設同意雲端；每批 evidence 上雲前都要取得可稽核的明確 consent，且 cloud retry 必須沿用相同內容與 consent scope。
- Google Sheets 使用使用者 OAuth，僅要求完成匯出所需的最小 scope。
- 收據照片放在 app-private storage；不要求廣泛的共享儲存權限。
- manifest 禁止明文網路、備份與裝置轉移，避免收據和 token 意外外流。
- 模型名稱與 prompt 不寫死在 domain；透過受控設定更新，並為輸出 schema 保留版本。
- 正式發布時使用獨立 release keystore，簽章資料與 service 設定不得提交到 Git。

## 品質門檻

每次準備合併至少執行：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

發佈前再執行 release lint/build、Sharesheet 單圖／多圖與 Photo Picker 測試、URI 權限失效測試、Nano unavailable／離開前景／模型下載測試、cloud consent 拒絕與重試測試、實機外螢幕與展開螢幕測試、旋轉／分割視窗／大字級測試，以及 native dependencies 的 16 KB page-size 檢查。

## 分支慣例

功能工作使用 `feat/<topic>`，例如 `feat/promotion-evidence-architecture`。每個 branch 應聚焦單一可審查主題；屬於該功能的測試與文件跟隨同一 branch。

## 自動擷取架構

`ExtractionControls → ExtractionSession → ExtractReceipt → AiRouter(OnDeviceOnly) → MlKitReceiptAnalyzer → LocalReceiptParser → MapReceiptRecognition → CAS → ReviewSession`。

`MlKitReceiptAnalyzer` 實際呼叫 bundled Chinese Text Recognition。中文 SDK 有時省略欄間空白，或提供包含空白的字元框；adapter 以字元位置與只讀像素空白帶重建分欄，並同時保存原始 OCR 文字。未知欄標保留位置，不擅自當成單價；像素掃描在 Default executor 執行，不阻塞 UI。每次 SDK 只接收一張已驗證 SHA-256 的本機圖片；adapter 順序處理選取頁面，再交本機 parser 處理欄位與多頁候選。`MultipleImageInput` 表示這個 adapter 的協調能力，不是宣稱 ML Kit 或 Nano 單次 API 支援多圖。選路沒有 cloud adapter；固定 bundled model 不需要執行 Nano availability probe，Nano 狀態在 UI 明示為未接入／未檢查。

官方文件核對於 2026-09-09：[ML Kit Text Recognition Android](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) 列出 bundled Chinese 16.0.1 與文字／區域輸出；[Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started) 要求自行檢查 AVAILABLE／DOWNLOADABLE／DOWNLOADING／UNAVAILABLE。本次不用手機型號推論 Nano 能力。

不可信的 `ReceiptRecognition` 只有文字、頁面／行索引與座標，沒有本機 ID、revision、stage 或最終帳務結果。Mapper 檢查長度、數量、座標、來源索引、數值與日期，才產生本機 UUID、Fact 與 Candidate evidence links。來源無法解析保留 Unknown，多個交易欄位候選不一致使用 Conflicting；金額以整數 parsing，僅接受合法千分位及小數部分全為零的 TWD 表示。UI 明確限定 TWD，發現已標示外幣的 OCR 輸出拒絕。

`ReceiptExtractionRecord` 保存來源 SHA-256、版本、帶 rawText 的 EvidenceRegion、警告與原始金額試算／gate 狀態。人工編輯保留這份原始擷取快照，讓修正後的平衡不會冒充原始擷取正確。Region 的寬高定義 OCR EXIF-oriented、降採樣座標空間；畫面提供原圖與原文比對。

沒有在 Room 寫入暫態 Analyzing stage：執行工作保存在 ViewModel，SavedState 只標示中斷，不自動重播；成功結果一次 CAS 寫入 NeedsReview 且保持未核對完整。這是避免取消、程序死亡與暫時性模型失敗把人工入口鎖死的選擇。離開 RESUMED 取消 coroutine；ML Kit Task 不支援真正取消，晚到 callback 只負責釋放資源，不能寫入草稿。SDK 正在使用的 bitmap 不會在取消瞬間提前回收。

套用前重新核對完整 evidence metadata、選取圖片實際 SHA-256 與 expected revision。成功以一次 Room transaction 保存 facts、provenance 與 audit；任何失敗保留原草稿。重新辨識必須對目前 base 版本明確授權取代，不保留另一套可被誤用的最終帳務值。帶促銷／分攤相依的草稿拒絕取代。Confirmed 與後續匯出階段均唯讀。

payload format 3 向前相容 format 1／2：新增 nullable extraction，舊格式未具 audit 時保持 null。SQL schema v1 未改；decoder 不以破壞性重建處理版本。`ReceiptAmountPreview` 是額外的 BigInteger 診斷，既有 `ReceiptReconciler`、`ReceiptValidator`、`TransitionReceiptStage` 及 ±1 容差不變。

## 自動擷取驗證

### 資料盤點與預先品質門檻

2026-09-09 在主專案與人工核對 checkout 的非建置檔案中未找到獲授權的真實收據資料集。未讀取手機 App 私有資料，也未上傳任何照片。`RealOcrPipelineTest` 是固定合成圖片與內嵌人工期望值，僅驗證實際 SDK 技術流程；`ReceiptExtractionTest` 的文字 observations 是軟體測試輸入。

真實資料初步試用門檻（在取得固定資料並評估前提出）：清晰樣本的交易總額及日期全對，商家正確至少 90%；品項漏列率與多列率分別 ≤5%，已匹配品項的數量與行金額 exact match 各 ≥95%。理由是本功能應顯著減少重打，且金額錯誤代價高；小型集合必須同時報原始分子／分母，不能以百分比掩蓋少量案例。模糊／反光案例另外列出是否失敗或保留 Unknown，不把拒絕率混入清晰樣本準確率，也不要求強行產生結果。多頁歧義應阻擋直接確認，資料保護案例要求零覆蓋／零遺失。

仍需 10–20 筆獲授權、可去識別的 TWD 真實交易照片，包含清晰單頁、多品項、多頁含重疊、另列折扣／費用及模糊／反光失敗案例。請提供每張所屬交易與頁序、可辨讀的商家／日期／總額、逐列品名／數量／單價／行合計與另列調整標註；看不清處標 Unknown。固定樣本 ID、SHA-256、授權範圍與標註後，再凍結評估。不得將用來調整 parser 的樣本當成未見測試集。

品質報告必須分列：

| 面向 | 真實收據結果 |
| --- | --- |
| 商家／日期／交易總額 exact match、Unknown、錯誤 | 尚無資料，未評估 |
| 品項漏列／多列數、已匹配名稱／數量／行金額 exact match | 尚無資料，未評估 |
| 原始擷取的金額差額與 reconciliation 阻擋原因 | 尚無資料，未評估 |
| 需修正的交易欄位數、品項欄位數、需新增／刪除列數 | 尚無資料，未評估 |

合成技術流程預期：標準單頁所有指定欄位 exact match、跨頁相同品項只產生一個未解候選、空白圖片不得成功或改動草稿。這些測試不構成真實辨識品質驗收。

### 主機及 Android 證據

2026-09-09 最終 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --no-daemon --max-workers=1` 通過，耗時 2 分 21 秒。主機 **162 tests（人工核對基線 136＋本次 26）、0 failures／errors／skipped**；lint `No issues found`。未停用檢查。Android 測試編譯另有 Compose JUnit4 舊 rule API 的 deprecation 提示，不是失敗或被略過的測試。

獨立 `receipt-ocr-test`／`emulator-5580`，API 35、x86_64、WHPX：**5 tests 全通過，9.83 秒**。`RealOcrPipelineTest` 實際執行 bundled SDK，涵蓋英文單頁、中文多品項與另列折扣、多頁重疊及空白圖失敗；`RealOcrUiTest` 透過實際 Compose 按鈕與真實 SDK 自動進入人工核對，檢查已帶入商家、品名、數量及行金額。沒有以 fake analyzer 代替這五項測試。其餘主機協調測試使用可控 adapter 測取消、衝突與錯誤，不能視為模型驗證。

| 固定合成案例 | 交易欄位 | 品項與金額 | 原始對帳／人工修正 |
| --- | --- | --- | --- |
| 英文單頁 | 商家、日期、總額 3/3 exact | 1/1 品項，名稱／數量／行額各 1/1；無漏列／多列 | 金額差額 0；完整性未確認而阻擋；0 文字欄位需修正，仍須人工勾選完整、保存並確認 |
| 中文兩品項＋折扣 | 商家、日期、總額 3/3 exact | 2/2 品項，名稱／數量／行額各 2/2；折扣額 5，無漏列／多列 | 金額差額 0；完整性及 adjustment scope 未確認而阻擋；金額文字無需修正，須人工指定 1 個 scope 並確認完整 |
| 重疊兩頁 | 共同交易欄位保留來源 | 一個跨頁候選，沒有重複建立兩列 | 數量、行額 Unknown，差額未知；須人工確認兩欄與頁面完整性 |
| 空白圖 | 無可用欄位 | 無品項，不回成功 | 原草稿保持不變，可人工輸入／重試 |

上述案例曾用於 parser 除錯，不是未見驗證集；不能外推真實收據準確率。品質門檻仍未經真實資料驗收。

本 worktree 的證據：`.gradle/extraction-final-build.log`、`app/build/test-results/testDebugUnitTest/TEST-*.xml`、`app/build/reports/lint-results-debug.txt`、`.gradle/real-ocr-final-results.log`、`.gradle/receipt-ocr-review.png`。測試 emulator 已停止，AVD 保留；未安裝到實體手機、未卸載 App、未清除使用者資料。

交付 APK：`app/build/outputs/apk/debug/app-debug-manual-review-signature.apk`，SHA-256 `1810c29cd579f7d0819f47dc57d6cd5895cccfd5c87888934f9be6bf80c5c69d`。已用來源人工核對 `.gradle/android-user/debug.keystore` 的本地副本簽署；憑證 SHA-256 `6fa1a1e710134668a0443876160ee821b3fd044705ef319bbcfb88ee993f4db2` 與來源人工核對 APK 相同。與通過 emulator 測試的 `app-debug.apk` 比較，545 個非簽章 ZIP entries 完全一致。一般 debug APK 的本 worktree 測試憑證是 `36d29f9d2aab4e06aac0fc4eee2076c365193d507ee4fa31e30c5726144d4d89`，不要將它誤作手機升級包。憑證與來源 APK 相同不證明與手機現有安裝相同；更新手機前仍須比對，禁止以卸載解決衝突。

仍未驗證：真實收據品質、實體 Pixel 10 Pro Fold／ARM64、折疊／旋轉／分割視窗、大字級與手機保留資料升級。多頁模糊對應可能需新增／刪除列，OCR 不保證找出全部重複；未知版面與影像品質仍需人工修正。Nano、cloud、菜單等排除項目未接入。

整合注意：本 worktree 位於主 checkout 的 `.gradle/worktrees/` 以符合本任務可寫範圍；它是獨立 Git 工作目錄，不能把整個主 checkout `.gradle/` 當成一般快取刪除。未授權合回 main、推送或刪除 worktree。
