# PixelReceipt AI

PixelReceipt AI 是以 **Google Pixel 10 Pro Fold** 為主要實機的原生 Android 智慧記帳 App。目標體驗是不必先開 App：使用者先用原廠相機拍照，再從 Android Sharesheet 分享進 App，或稍後透過系統 Photo Picker 補選多張圖片；App 將收據、價標與促銷牌整理成同一筆交易的 evidence inbox，最後交給使用者核對。

目前已實作 **本機收據匯入、中文 OCR 自動擷取、人工核對與確認記帳**：Sharesheet 單圖／多圖、Photo Picker、草稿列表、追加圖片與原圖預覽、交易／品項／人工調整編輯、CAS 保存，以及通過既有本機 gate 後保存 Confirmed。可辨識照片自動帶入品項，也保留完整人工輸入。Gemini Nano、Firebase、拆帳及 Sheets adapters 尚未實作；裝置驗收狀態見下方，不能以 JVM 測試代替實機驗證。

## 核心原則

2026-09-09 整合基線包含圖片匯入 `1a2f624`、人工核對 `d31e104` 與本機 OCR `40e6a82`。主專案重新執行 `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1` 通過；162 項測試結果由 Gradle build cache 還原，0 failures／errors／skipped，lint 無問題，debug APK 組裝成功。這次未重跑裝置測試；真實收據品質、完整 Pixel 操作及保留資料升級仍待驗收。UI 流程重整尚未實作。

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

PowerShell 本次任務局部環境（工具位於 `.gradle/`，不提交、不更動全機環境）：

```powershell
$env:JAVA_HOME = (Get-ChildItem .gradle/task-tools/jdk17 -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$PWD\.gradle\task-tools\android-sdk"
$env:ANDROID_USER_HOME = "$PWD\.gradle\android-user"
$env:GRADLE_USER_HOME = "$PWD\.gradle\task-gradle-home"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

其他 checkout 請設定自己的 JDK／SDK；不假設上述忽略目錄會隨 Git 同步。依賴均置於 version catalog；此次嚴格 lint 要求的 Kotlin Compose plugin 更新至 2.4.20。

此原生 worktree 的工具與依賴從既有安裝複製至本地忽略目錄；沒有改寫原專案。Windows 沙箱可能阻擋 AGP 的 `debug.keystore.lock` 檔案正規化；本次需在核准後以相同 worktree、相同局部環境執行 gate，沒有改到其他工作目錄執行。debug keystore 也只放在忽略目錄，不提交。

本 worktree 產物使用局部新建的 debug key，**與上游 APK 簽章不同，不能直接覆蓋安裝上游版本**。自動核准審查未允許複製既有私密金鑰，因此未複製。保留資料升級驗收應於後續獲授權整合後，在原有簽章環境重新建置；不要為了安裝此 APK 卸載已有資料的 App。

輸出 APK：`app/build/outputs/apk/debug/app-debug.apk`

目前 quality gate 包含嚴格 lint（warnings as errors）、domain／ViewModel JVM tests 和 APK 組裝。尚未連接實體 Pixel，因此外螢幕、展開、旋轉、分割視窗與 tabletop 相機行為仍需在對應功能完成後做 device test。

## 本機收據匯入使用方式

1. 從相簿選一張或多張圖片，透過分享選單選擇 PixelReceipt AI；每次外部分享預設建立**新草稿**。
2. 或在 App 點「選取圖片建立草稿」。也能先建立空白草稿，再按「補選圖片」。取消 Photo Picker 不修改草稿。
3. 開啟草稿查看多圖 inbox，點圖片列預覽原圖。Compact／Medium 使用列表與明細單 pane，Expanded（840dp 起）同時呈現列表與明細。
4. 已成功匯入的圖片完全使用本機副本；重新啟動、原始 URI 失效或來源圖片刪除不影響副本。App 資料清除／解除安裝會刪除本機資料，目前沒有備份或匯出功能。

每批最多 20 張、每張 20 MiB、整批讀取 100 MiB；每張最多 5,000 萬像素、單邊 20,000 像素，本機圖片總量上限 1 GiB。目前支援 JPEG／PNG／WebP；HEIC、GIF、AVIF 等會顯示格式不支援，需先轉換。原圖不旋轉、不壓縮、不覆寫；預覽才進行降採樣及 EXIF 方向處理。

同一草稿依**原始 bytes 的 SHA-256**跳過重複圖片；不同草稿可共用實體檔案，但 evidence ID、匯入來源／時間與交易仍獨立，不會自動合併交易。旋轉後重編碼的圖片 bytes 不同，視為不同 evidence。混合成功／失敗會顯示逐張結果；重新選取失敗圖片或整批圖片時，已存在的內容不會重複加入同一草稿。

匯入至少一張成功才會建立新草稿；使用者明確建立的空白草稿除外。取消匯入會撤回本批未提交內容，既有草稿保留。程序中斷不自動重新讀取外部 URI：下次啟動會標示中斷、清理暫存／未引用檔案，再由使用者重選。畫面旋轉使用 ViewModel 保持同一操作；Activity／程序狀態復原使用 operation ID 與 Room 匯入紀錄拒絕重播。匯入中收到另一個分享會顯示忙碌訊息，須完成後重新分享。

目前匯入工作只在前景 UI 流程啟動，沒有 WorkManager 或自動背景重試。後續已進入分析或記帳狀態的草稿不能透過此匯入入口追加圖片，避免更動已確認 evidence。

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

1. 開啟草稿，先補齊圖片，再按「人工核對」。`Captured → NeedsReview` 是合法人工入口，不執行或假造 AI 分析。進入核對後沿用既有匯入限制，不再追加圖片。
2. 對照照片輸入商家、交易日期（`YYYY-MM-DD`）、品項名稱、正整數數量、收據行金額及收據總額。Compact 使用可捲動表單與圖片預覽，Expanded 同時顯示證據及表單。
3. 金額輸入幣別最小單位整數（TWD 為元）；行金額是該行合計，**不再乘以數量**。不接受負數、小數、千分位、科學記號或溢位。空白保持 Unknown；日期未知依既有 gate 不單獨阻擋確認，不會補成今天。
4. 可新增／刪除品項，以及收據**另列**的人工調整。調整金額非負，由「加上／扣除」決定方向，範圍選整筆或明確指定品項及適用數量（單項或多項）；未知範圍可保存但阻擋確認。已含在收據行金額中的折扣不要再加一筆扣除。
5. 各區可追加指定核對圖片，保存使用者 provenance 與 Confirmed evidence links；既有關聯保留。未修改的 Fact／provenance、原價、促銷及分攤資料原樣保留，未知原價不以實付額或零代填。沒有另列調整不等於已知零折扣。刪除仍被調整／促銷／分攤引用的品項會拒絕保存並指出引用。
6. 勾選「收據完整」並保存修改。表單顯示解析錯誤、必要 Fact 缺漏、缺少品項、不合法 scope／幣別／既有促銷分攤等阻擋原因。完整 gate 沿用 `ReceiptReconciler`／`ReceiptValidator`／`TransitionReceiptStage`。
7. 「完全平衡」差額為 0；「容差內」允許差額 ±1 最小單位。畫面與確認對話框均顯示實際差額，不調整資料湊平。保存後且 gate 通過才可確認；Confirmed 保存在同一交易，列表及明細顯示唯讀，不建立另一筆交易，也不提供重開／修改。

未保存輸入存於 ViewModel 與 SavedStateHandle 的編輯緩衝，包含非法的半成品文字與原始 CAS revision；Room 仍是已保存交易的唯一來源。返回時可繼續編輯或明確放棄。Activity saved-state 復原可回到緩衝；**force-stop、移除最近任務或清除資料不保證復原未保存輸入，執行前請先保存**。一次限 100 品項、50 調整，各欄位最多輸入 500 字元。

保存／確認遇 CAS 衝突時保留原輸入，顯示最新版摘要，禁止自動覆蓋；可保留畫面比對，或經對話框明確放棄輸入並重新載入，再重新核對。遇暫時性寫入失敗保留輸入供重試。人工核對中收到分享時會提示先離開，再重新分享。

人工核對基線（format 2）的交易日期新增為 `Fact<String>`；本辨識版本寫入 format 3（見下方）。舊 draft payload format 1 的缺省日期遷移為 `Unknown(NotObserved)`；讀取不修改 DB 或 revision，下次 CAS 寫入 format 2。SQL schema 仍為 v1，沒有新增 SQL 欄位，亦沒有 destructive fallback。更新後不應降回僅理解 payload v1 的 APK；舊 decoder 會拒絕 v2，不能將它當成可安全降版。

2026-09-08 人工核對完整 gate：**136 tests（新增 23）、0 failures／errors／skipped，lint No issues found，debug build 成功**。執行 `testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1`，未停用任何檢查；最終耗時 1 分 7 秒。主機證據、涵蓋範圍和限制見 [人工核對驗證](docs/ARCHITECTURE.md#人工核對驗證)。

人工核對裝置手動驗收（本次 `adb devices -l` 無裝置，下列 UI 操作均未驗證）：

1. 匯入兩張照片，進核對後切換／放大預覽；輸入商家、合法日期、品項數量 2／行金額 100／總額 100，指定圖片依據、勾選完整，保存後重開並確認。
2. 查看 Confirmed 列表及明細；force-stop 後重啟，確認資料／日期／品項／調整／圖片仍在且唯讀。不得以卸載重裝代替保留資料升級。
3. 編輯 `12.` 或不合法日期後返回，驗證放棄提示；選繼續編輯，旋轉／重建 Activity，原始文字仍在，沒有被當成已保存值。
4. 分別留空必要欄位、數量填 0、小數／超長金額、空品項、未勾完整，驗證具體原因。未知原價與未知日期不得被填零或推測。
5. 行合計 100／總額 99，確認顯示「容差內、差額 1」；總額 98 顯示差額 2 且不能確認。另測差額 -1 與完全平衡。
6. 行合計 100，另列扣除 10、加上 5，總額 95；分別指定整筆、單項及多品項 scope，驗證只加減一次。適用數量超過購買數量時不能確認。
7. 用除錯器／測試 writer 更新同筆 revision，原畫面保存／確認均應顯示衝突並保留輸入；檢查最新版後放棄並重載，若最新版已 Confirmed，立即唯讀。
8. Compact、Expanded、旋轉、分割視窗、大字級及實體 Fold 折疊切換，檢查表單、軟鍵盤、長列表、返回提示與確認對話框。這些裝置驗收不由主機測試代替。

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

從圖片草稿勾選**同一交易的收據圖片**，按「辨識收據／重試」。本機逐頁辨識完成後，自動建立品項並進入既有人工核對畫面；保存修正、勾選完整，再通過既有 gate 才能確認記帳。完整人工輸入入口保留。

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
