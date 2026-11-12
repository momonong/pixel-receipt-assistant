# 生活支出分析與帳本補充 Implementation Plan

> **For agentic workers:** Use bounded independent domains with fixed ownership; root integrates and performs the whole-branch review. Steps use checkbox syntax for tracking.

**Goal:** 八個分析方向與無收據記帳在原生 App 可操作，正式金額與舊資料保持不變。

**Architecture:** 同 SQLite 的可修訂註記＋新唯讀投影；手動記帳共用 domain；沿用產品身份與價格核對後端。Android 具名窄化 API、獨立快取與 durable frozen writes。

**Tech Stack:** Kotlin/Compose、FastAPI、SQLite、既有依賴。

**Spec:** [設計與固定介面](2026-11-12-spending-dimensions-design.md)

## Global Constraints

- 正式資料、照片、憑證及其他工作樹保留，不讀寫正式服務，不新增推論、依賴或供應商。
- 只在 feat/spending-dimensions 開發；不提交、合併、推送、部署、操作手機或刪除工作樹。
- 已確認 snapshot 與財務金額不可變，整數 TWD，不把 unknown 當零、不重扣代買或內含折扣。
- 新寫入先保留同一 key/body；未知結果查原 operation，不換 key 重送。請求綁登入世代與篩選範圍。
- 來源明示、缺標籤仍可記帳、舊客戶端保持相容，個人支出 self+gift。
- 使用者已授權直接實作，例行可逆步驟不重問；新發布另計。

## Review Focus

- 混合分類與整張折扣後，分類合計（含待分配）必須等於原帳本金額。
- AI 或補充文字不能指定金額，未知餐別／地址不能被上傳時間／店名字樣補成事實。
- 手動寫入中斷後無半筆、重播／查詢不重複入帳，登入切換不能操作前帳號請求。
- 篩選在分頁前套用，作廢後摘要、分組與明細同源，無日期／零元／大整數明示。
- 原生可讀、可改、可返回；價格身份不同規格不合併，未分配折扣不宣稱最低價。

### Task 1: 可修訂分析註記、無收據記帳與資料相容（backend worker）

Files: backend/src/pixelreceipt_backend/{insight_models.py,insight_store.py,insight_api.py,store.py,api.py,recognition.py,intake_store.py}; deploy/receipt_backup.py；相關 backend tests。Worker 不修改 spending.py/analysis_store.py/insights_projection.py/insights_api.py。

Interfaces: 精確實作設計中的 GET/PUT metadata、POST manual、GET operations。`InsightStore(store).document(connection, entry)` 回 metadata response（entry 是既有 ledger DTO），純讀；`InsightStore(store).draft_document(connection,draft)` 回草稿 metadata response。api.py 掛載 root 提供的 `register_insights_api(api,store)`，worker 自己的寫入 routes 放 insight_api.py，避免與 root 同檔。

- [x] 先補 failing tests：v4 legacy rows/auth/blobs unchanged、混合品項註記／CAS/history、無照片一筆金額300且advance30情境（分別手動self300與advance30，不能憑空拆）、手動原子性與重播、401/403、未知operation404、明確notes餐別與不能由上傳日／店名猜地區。
- [x] 看到正確 RED 後實作 schema5 additive migration、metadata provenance、atomic manual entry、operation reconciliation、現有 recognition 附加 context 與備份支援。
- [x] 價格新增操作以同一 mutation 記錄 lookup；既有 API 相容。與 root 確認精確 helper 後提供合成 JSON fixture。
- [x] targeted tests GREEN；完整後端 suite/Ruff 由 worker 最後跑並留 log，不重跑已充分同版驗證。

### Task 2: 完整維度投影與窄化 MobileApi（root）

Files: backend/src/pixelreceipt_backend/{insights_projection.py,insights_api.py}、backend/tests/test_insights_projection.py；app/.../mobile/MobileApi.kt；新 API allowlist tests。

Interfaces: register_insights_api(api,store) 提供 GET /v1/insights 及 /entries，使用 InsightStore.document。MobileApi 方法完全按設計，舊實作／fake 以預設 UnsupportedOperationException 相容。

- [x] RED：混合食與日用、代買分開、折扣最大餘數、unallocated、同餐合併、城市＋晚餐交叉查詢、前期比較、完整分頁／大整數／缺日期／作廢。
- [x] 最小 deterministic projection／query validation，單一讀取 transaction；HTTP 合成測試驗證401/200及filters echo。
- [x] 新增具名同源 API 與窄化 allowlist；不得讓任意 path/query 或 tokenURL 通過。
- [x] targeted GREEN，與 worker fixture 交叉驗證。

### Task 3: 原生分析、標籤編輯與手動記帳（native-main worker）

Files: app/.../mobile/MobileInsight*.kt、MobileManual*.kt 與既有 MobileScreen/ViewModel/Content/Review/Detail/Help/Spending 等；相關 tests。不修改 MobileApi.kt/MobilePrice*.kt。

Interfaces: 設計中的 MobileApi 具名方法、新投影 JSON、既有 ledger DTO、Price worker 組件。自行擴充 state/action，root 統一 Gradle。

- [x] RED tests：fields/filters strict parsing＋負差額、分類主圖、餐別與縣市交叉篩選、未分類可見、返回範圍、manual frozen write 保存／unknown lookup、account generation、metadata後補、無照片和缺名可確認。
- [x] 實作新的分析頁、篩選、圖列下鑽、metadata form與manual form，所有操作原生、不跳瀏覽器。
- [x] 接入 Price controller/screen；更新說明與同步失效行為；自適應、48dp、字級、鍵盤。
- [x] root full Gradle 後修正自身 compile/tests/lint，保存真正 Compose 多尺寸截圖與互動證據（合成資料）。

### Task 4: 原生商品價格與跨店比較（price worker）

Files: 僅 app/.../mobile/MobilePrice*.kt 與自身 test files。

Interfaces: 設計的 MobileApi products/createProduct/priceReview/savePriceReview/productPrices/analysisOperation；MobilePriceController 與 screen 對接 native-main。額外型別細節協調後寫進本計畫的 note。

- [x] RED：相同產品規格精確identity、理性單價 numerator/denominator、折扣未知顯示、catalog及mapping保存CAS、unknown操作lookup、作廢排除。
- [x] 接既有 API 實作產品清單／建立、帳目品項人工對應、價格核對、跨店歷史含來源與日期。不得依相似名稱自動確認產品身份。
- [x] 未知實付無最低價排名、不能把歷史價當現在價，操作有明確狀態，現有全原生風格。
- [x] root Gradle 後修正自身 compile/tests/lint。

### Task 5: 整體驗證、審查與候選（root）

- [x] 更新使用說明、API／資料契約、版本說明（候選 0.7.0/code14；無發布動作）。
- [x] 執行 ./gradlew testDebugUnitTest lintDebug assembleDebug，backend pytest/Ruff/format，既有 Web tests，讀取同版結果。
- [x] 產生完整 tracked+untracked review package，由 fresh reviewer 對使用者行為審查；重要發現 RED→GREEN 修正，適度完整 gate。
- [x] 封存 source/APK SHA、migration與可操作 Compose／合成流程證據，核對原始工作樹與資料保持。直接交付使用者工程結果及真實未驗證項目，不把 candidate 當正式部署或人工接受。

整合結果：Android full5 505/505＋lint/assemble；backend final4 479 passed／31 deprecation warnings；backend Ruff 56 files、backup11、Web3通過。原生及後端 fresh review Important皆以consumer RED→GREEN關閉，另核對補入／修改日期來源不冒稱印刷。最終封存與交付記錄為獨立候選清單，不代表正式發布或真機接受。
