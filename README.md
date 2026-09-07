# PixelReceipt AI

PixelReceipt AI 是以 **Google Pixel 10 Pro Fold** 為主要實機的原生 Android 智慧記帳 App。目標體驗是不必先開 App：使用者先用原廠相機拍照，再從 Android Sharesheet 分享進 App，或稍後透過系統 Photo Picker 補選多張圖片；App 將收據、價標與促銷牌整理成同一筆交易的 evidence inbox，最後交給使用者核對。

目前狀態：**Phase 0 原生 Android／Foldable 基線與 Phase 1A evidence-first domain contracts 已完成**。專案可編譯並產生 debug APK；Unknown／provenance、多對多 evidence link、促銷適用品項、純函式定價、收據對帳、確認 gate、AI routing 與零售商 fallback port 都已落地。Sharesheet／Photo Picker、多圖 inbox UI、Room，以及 ML Kit、Firebase、OAuth、Sheets 的實際 adapters 仍是下一步，現在不能視為可用功能。

## 核心原則

- 每件商品各自成列；已知值保存來源，不確定的原價、折扣或適用範圍則明確保留 `Unknown`，不為了湊齊欄位而填零。
- AI 只做分類、文字／欄位擷取、關聯候選與說明；金額、折扣分攤、拆帳和總額一致性全部由可重現、可測試的本機 deterministic pricing rules 判定。
- 缺少證據時保留 `Unknown`，不把網路促銷候選或模型猜測偽裝成已發生的折扣。
- Room 是 single source of truth；Google Sheets 是可重試的單向匯出端，不是第二個主資料庫。
- `AiRouter` 的 SDK-neutral contract 與 local-first 選路已完成；未來接入 ML Kit Gemini Nano 與 Firebase AI Logic adapters 時，只有對「同一 case、同一批圖片內容、同一目的與指定雲端服務」明確同意後才可上雲，APK 不放可直接濫用的 Gemini API key。
- UI 依目前 app window 調整，不以手機型號、外／內螢幕或固定方向猜測版面。

## 已落地的技術基線

| 項目 | 版本／選擇 |
| --- | --- |
| UI | Single activity、Jetpack Compose、Material 3 Adaptive supporting pane |
| Architecture | UDF、ViewModel + StateFlow、domain ports、repository adapters |
| Android | `compileSdk 37`、`targetSdk 37`；Phase 1 支援下限採 `minSdk 26` |
| Build | AGP `9.4.0`、Gradle `9.7.1`、JDK `17` |
| Kotlin | `2.4.10` |
| Compose | BOM `2026.08.00` |
| Material 3 Adaptive | `1.3.0` |
| Coroutines | `1.11.0` |

Gradle distribution 有固定 SHA-256，wrapper JAR 也已對照官方 checksum。暫定 application ID 是 `com.momonong.pixelreceipt`；設定 Firebase、OAuth、release signing 或 Play Console 前要先確認，之後不應任意更動。

## 架構摘要

```text
原廠相機 → Android Sharesheet ─┐
                              ├→ 多圖 evidence inbox（adapter/UI 待實作）
系統 Photo Picker ────────────┘       │
                                     ├→ ReceiptPage／PriceTag／PromotionSign／Other
                                     ├→ Fact + provenance + EvidenceLink（domain 已完成）
                                     └→ AiRouter（選路已完成；SDK adapters 待實作）
                                         ├→ ML Kit Gemini Nano（支援時、前景執行）
                                         └→ Firebase AI Logic（同批證據明確同意後）
                                                   ↓
                                    deterministic pricing + scoped adjustment
                                                   ↓
                                    Room（待實作）→ Review UI → Sheets（待實作）
```

Domain 已將 AI recognition facts 與本機 `ReceiptDraft` 分開，模型不能指定本機 ID、revision、workflow stage 或帳務結果。`EvidenceAsset`／`EvidenceReference`／`EvidenceLink` 可表示一張促銷牌對多個收據品項，以及一個品項由多張圖片共同佐證；實際檔案匯入與 Room persistence 尚待 adapter 實作。資料寫入 contract 使用 revision + compare-and-set，避免 UI 與背景工作同時覆寫；exporter 只接收已驗證的 immutable batch。

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
└── feature/home/        Adaptive UI、contract、ViewModel
```

初期先保留單一 `:app` module；等 evidence ingestion、Room 與 service adapters 進入後，再依實際編譯隔離需求拆 module，避免過早模組化。

## 圖片、折扣與查價策略（規劃）

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

需求：JDK 17、Android SDK Platform 37、Android Build Tools 36.0.0。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

輸出 APK：`app/build/outputs/apk/debug/app-debug.apk`

目前 quality gate 包含嚴格 lint（warnings as errors）、domain／ViewModel JVM tests 和 APK 組裝。尚未連接實體 Pixel，因此外螢幕、展開、旋轉、分割視窗與 tabletop 相機行為仍需在對應功能完成後做 device test。

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

### Phase 1B：Evidence-first 收據 MVP adapters（下一階段）

- [ ] Room schema、migration tests 與 repository adapter。
- [ ] 原廠相機 Sharesheet、Photo Picker、多圖 evidence inbox 與 app-private 圖片保存。
- [ ] ReceiptPage／PriceTag／PromotionSign／Other classifier，以及 OCR／structured extraction adapter。
- [ ] `MlKitNanoAnalyzer` 與 `FirebaseCloudAnalyzer` adapters；Firebase 路徑強制 App Check／Play Integrity。
- [ ] 寶雅等 retailer source adapters，保存 URL、抓取時間與適用條件，僅產生 candidate。
- [ ] WorkManager 唯一工作與重試策略；不得假設 Nano 能在背景執行。
- [ ] 單筆交易 evidence／品項核對、折扣修正、自用／代墊與平分介面。
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
