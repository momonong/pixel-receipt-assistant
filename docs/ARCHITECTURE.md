# PixelReceipt Android 架構

## 目標與範圍

這個專案以 **Google Pixel 10 Pro Fold** 為主要實機，採原生 Android 架構，同時保持對一般 Android 手機與不同視窗尺寸的相容性。現在已完成可建置、可安裝的 Phase 0，以及 Phase 1A 的 evidence／Fact／promotion／pricing／AI routing domain contracts。原廠相機 Sharesheet、Photo Picker、多圖 inbox、Room、ML Kit、Firebase AI Logic 與 Google Sheets adapters 會在 Phase 1B 逐項接入；下圖的外部整合仍是目標架構，不代表已經可用。

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

Compose UI → domain ← data adapters 的依賴方向不變。Domain 不引用 Android URI、ML Kit、Firebase、Room 或 Google Sheets 型別；`AiRouter` 位於 domain use case，透過 `OnDeviceReceiptAnalyzer`／`CloudReceiptAnalyzer` ports 呼叫未來 adapters。Evidence repository 與 exporter 同樣只傳 domain model 或明確結果。

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
└── feature/home/        UI contract、ViewModel、adaptive screen
```

Phase 1A 的 evidence、matching、pricing 與 AI routing contracts 已沿用這些 package 邊界。Phase 1B adapters 也必須遵守相同依賴方向；是否拆 module 由實際編譯隔離需求決定。

## 可折疊裝置策略

- 不用「手機型號」或固定方向判斷版面，永遠依目前 app window 的 size class 決定。
- Compact window（例如外螢幕或分割視窗）使用單 pane 導覽；有足夠寬度時由 Material 3 Adaptive 呈現 main/supporting panes。
- App 不鎖定 portrait/landscape，也不宣告不可 resize。
- 所有畫面採 edge-to-edge，內容套用安全 inset。
- Supporting pane 可滾動，避免內螢幕多工、窄視窗或大字級時裁切。

`AdaptiveLayoutPolicy` 使用官方寬度斷點：Compact `<600dp`、Medium `600–839dp`、Expanded `840–1199dp`、Large `1200–1599dp`、Extra large `>=1600dp`。

## Evidence 匯入（Phase 1B 規劃）

原廠相機是預設拍攝入口，使用者不需要先開 PixelReceipt。App 透過 Android Sharesheet 接收 `ACTION_SEND` 與 `ACTION_SEND_MULTIPLE`；若照片已經存在，透過系統 Photo Picker 一次補選一張或多張。兩條路徑都只取得使用者選定的媒體，不要求廣泛相簿權限。實作依據：[接收其他 App 分享的資料](https://developer.android.com/develop/ui/compose/sharing/receive)、[Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)。

收到分享的 URI 後，ingestion adapter 必須在讀取權限仍有效時，將內容串流複製到 app-private storage，再以 content hash 去重；不得把外部 `content://` URI 當成永久檔案位置。每張圖會建立獨立 `EvidenceAsset`，多張 evidence 可由使用者放入同一個 transaction case，也可以稍後移入、移出或重新分類。這些 domain types 已存在；URI copy、hashing 與 case UI 尚待 adapter 實作。

Evidence 類型至少包含：

- `ReceiptPage`：發票或交易明細，主要提供實際品名、數量、行小計與交易總額。
- `PriceTag`：貨架價標，可能提供品名、條碼、原價、會員價與有效期間。
- `PromotionSign`：促銷牌或區域活動牌，可能涵蓋多個品項與附帶條件。
- `Other`：已確認為上述三類以外的圖片。

「尚未可靠分類」以 `Fact.Unknown` 表示，不使用 `Other` 冒充。分類結果可帶來源、模型版本與 observation-level confidence，但仍只是 suggestion。原始檔 immutable；`EvidenceRegion` 可引用原圖區域。旋轉／降採樣 derived artifact 與 parent graph 是 Phase 1B persistence 工作，尚未落地。

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
Room (NeedsReview；允許 Unknown；adapter 待實作) → Adaptive review UI
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

NeedsReview → Confirmed → ExportPending → Exported
                                  ├────→ ExportFailed
                                  └────→ AuthorizationRequired
```

`NeedsReview` 不代表資料已完整；它可以合法包含 evidence、matching 或 adjustment 的 `Unknown`。`TransitionReceiptStage` 在進入 `Confirmed` 前必須取得 `ReceiptReconciler.Balanced` 或 `WithinTolerance`；incomplete receipt、未知 merchant／line name／quantity／total／line amount／adjustment amount／scope、pending promotion 或 validation issue 都會回 `ConfirmationBlocked`，且不寫入 repository。

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
