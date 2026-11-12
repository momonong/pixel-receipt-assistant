# 生活支出分析與帳本補充設計

使用者於本對話同意：「好 就把這些都補上吧」。範圍是前一則提出的生活分類、餐別、品牌與分店、縣市地區、消費時間、商品價格、折扣、支出歸屬，以及沒有收據的手動記帳。延續全原生 App、自動帶入、缺資料仍能記帳、可事後補填。這次是實作與本機驗證，未新增合併、推送、正式部署或手機操作授權。

## 方式與邊界

比較：把分類塞入原財務快照會要求重做既有帳目；只在手機存標籤會讓中央統計不同步；採用同一 SQLite 中可修訂的分析註記，與既有不可變帳務分開。手動記帳仍走相同確定性 domain 計算，無照片且來源明示為手動。維持 Kotlin/Compose、FastAPI/SQLite、整數 TWD 元與既有依賴。

- 以已交付 0.6.0/main `efae3f1406df1b1bae0b762977202953ddd41882` 為基底，沿用現有隔離工作樹；分支 `feat/spending-dimensions`。
- 資料庫 schema 5 新增註記、註記歷史及 ledger_metadata 的非秘密穩定帳本 UUID；不得修改原有 ledger snapshot、照片、認證或已確認數值。測試 v4→v5 舊列逐值保持及備份／還原。
- 外部圖像與文字均為不可信輸入。模型只提出候選，分類為分析標籤；金額全部由 domain／投影整數運算。
- 不增加依賴、地圖／定位服務、供應商、模型下載或推論。更新現有辨識輸出契約可接收附加的印刷地址／時間及分類候選，但驗證只用合成輸入，不呼叫供應商。
- 舊 v1/v2 草稿、0.6.0 spending、ledger、overview API 與客戶端保持相容。新功能使用獨立 `/v1/insights` 投影。
- 品牌不得用原始店名取代；分店不自動合併。地區只使用收據地址、明確補充或使用者輸入，不用目前手機位置，也不由店名中的城市字樣猜測。
- 單筆儲存、手動建立、商品建立與價格核對均須保留 frozen request／同一 idempotency key。結果未知先查原 operation，不能換 key 重送。登入世代、舊 401、離線與回應範圍不得破壞新狀態。

## 分析註記契約

`GET /v1/drafts/{draft_id}/insights` 與 `GET /v1/ledger/{ledger_id}/insights` 回傳：

```
{draft_id, ledger_id: UUID|null, revision: 0..LongMax,
 document: {brand,branch,address,city,district,channel,transaction_time,date_source,items:[...]},
 sources: {...}, updated_at: ISO|null}
```

`document` 是完整文件。可空字串欄位先 trim 後變 null，最多 500 字；transaction_time 為 `HH:mm` 或 null。

- brand、branch、address、city、district 為 string|null。
- channel：`in_store|online|unknown`。
- date_source：`receipt|user|default_today|unknown`，不把上傳日當收據事實。
- items 必須恰好覆蓋現有 candidate 所有 item_position，每列唯一且指向存在的品項。
- 每項：`{item_position,category,subcategory,meal,meal_group}`。
- category：`food|clothing|housing|transport|education|leisure|health|household|other|unknown`，顯示為食、衣、住、行、育、樂、醫療、日用、其他、未分類。
- subcategory：string|null（上限 100 字）；meal：`breakfast|lunch|dinner|late_night|snack|unknown|not_applicable`；meal_group：string|null（上限 100 字）。
- 非食類預設 not_applicable；食類無明確資訊預設 unknown，不能拿上傳時間或品名隨意決定早餐／午餐。notes 中明確餐別優先於通用規則。明確交易時間仍只是餐別建議，使用者可改。
- `sources` 是伺服器產生的分析來源說明，不接受客戶端假造來源。來源區分 user、notes、receipt、rule、unknown。原始辨識資料保留。
- 未有註記時 revision=0，讀取只產生候選，不偷偷寫入。已儲存使用者選擇優先。註記可在入帳後修改，作廢帳目只讀。

PUT 相同路徑，reviewer-only，body `{expected_revision,document}` 與 `Idempotency-Key`（App 使用 UUID）。CAS 409 不覆蓋別人的修改，重播完全相同 key/body 回同一結果。`GET /v1/analysis-operations/{operation_id}` reviewer-only 回 `{operation_id,kind,target_id,result}`；未成功過的 key 回 404。只公開新分析／手動與價格相關操作，不能成為任意 mutation dump。

## 無收據記帳

`POST /v1/manual-entries`，reviewer-only，Idempotency-Key，body：

```
{receipt_date: YYYY-MM-DD, merchant: string|null, description: string|null,
 amount_yuan: positive integer <= LongMax, purpose: self|gift|advance,
 notes: string|null, analysis: document}
```

一筆代表一次支出的一列，quantity=1。手機預設今天與 self，用日曆；金額由使用者輸入。description 可空，merchant 空時內部用「手動記帳」，介面仍可顯示未填商家。analysis.items 只含 item_position=0。送出鈕是「確認記帳」，顯示金額與歸屬，明確人工操作後才建立 active ledger。必須在單一 SQLite write transaction 建立 manual draft、review、不可變 snapshot、ledger event、註記及 mutation，失敗不能留半筆。回覆既有 ledger entry DTO。正常 ledger／overview／作廢同步適用，不能混成收據照片或虛構 evidence。

## 新支出投影（root 負責）

`GET /v1/insights`，沿用認證，參數：date_from/date_to、成對 compare_from/compare_to、bucket=auto|day|month|year、category、meal、brand_key、branch_key、city_key、district_key、merchant_key、channel、day_type=weekday|weekend、undated_only。字串維度 key 沿用 canonical JSON SHA256；null 也有穩定 key。category/meal 直接用 enum。日期含起訖；缺一比較日、錯誤 enum/key、過大桶範圍應明確拒絕。

回覆：

```
{scope:"active_confirmed_ledger",currency:"TWD",amount_unit:"TWD_YUAN",
 filters:{...全部已解析參數...},generated_at:ISO,bucket,
 summary:{personal_yuan,self_yuan,gift_yuan,advance_yuan,receipt_count,item_count,
          explicit_discount_yuan,missing_item_name_count},
 comparison:null|{date_from,date_to,personal_yuan,receipt_count,delta_personal_yuan},
 series:[{date_from,date_to,label,personal_yuan,receipt_count}],
 groups:{categories:[group],subcategories:[group],meals:[group],brands:[group],branches:[group],
         cities:[group],districts:[group],merchants:[group],weekdays:[group],times:[group]},
 coverage:{uncategorized_personal_yuan,unknown_city_personal_yuan,unknown_meal_personal_yuan,
           undated_receipt_count,undated_personal_yuan,estimated_date_receipt_count,
           pending_price_receipt_count,unallocated_personal_yuan}}
group={key,label:string|null,personal_yuan,receipt_count,item_count,
       meal_count?:integer,average_yuan?:{numerator:integer,denominator:positive integer}|null}
```

只統計 active confirmed ledger，self+gift 為個人支出，advance 另列。可交叉篩選例如本月＋台南＋晚餐＋某品牌。類別與餐別按品項篩選；商家、地區及日期按帳目篩選；摘要是所選品項實際負擔，不把整張混合收據都算入單一分類。

原財務 line_shares 先形成每項 self/gift/advance，另列加減項按原有 purpose allocation 在該 adjustment scope 的品項間以整數最大餘數分配，平手依 item_position。內含折扣不能重扣。若無合法非負分配或無基準，殘額在分類／細分類／餐別的「待分配」group（key=unallocated）明示，不能捏造商品單價；分類合計含待分配應等於總個人支出。收據層的縣市／商家／時段仍保留原帳目身份，讓點選明細與該組金額一致。超大整數回錯誤，不浮點近似。

餐數以一次帳目＋餐別為預設一餐；同日同餐別且使用者設定相同 meal_group 可合併，多品項不增加餐數。同帳目同餐別只有一個非空代號時，該代號涵蓋其他未填列；若有多個不同非空代號，保守維持原帳目的一餐，不任意選一個跨帳目合併。全額折扣後個人餐費為 0，仍保留實際有個人負擔的原餐次；純代買不算個人餐次。均額用精確分子／分母，App 顯示時才格式化。餐別未知仍有食類金額；非食類在投影正規化為 not_applicable（不改原註記），不進餐費與餐別篩選。

`GET /v1/insights/entries` 接收同一篩選及 limit=20/offset，先篩選再分頁，回 `{entries:[既有entry + matched_personal_yuan + insights],total_count,limit,offset,filters}`。Root 的投影在同一 SQLite read transaction 讀取 ledger、註記及價格狀態，保留讀取一致性。

## 原生 UI

沿用既有青綠／暖白設計、中文系統字與 Material3，不新增第三方圖表。分析入口仍單一，首屏顯示選定期間的個人支出、主分類橫條、資料待補提示；進入「餐別／店家／地區／時間／用途／折扣」可切換 group。單一篩選 sheet 能清除與顯示條件，點圖列看同範圍帳目，返回保留位置。一般手機單欄，寬螢幕可並排，放大字級回單欄，48dp 觸控與文字標籤。

帳目／核對中提供「分析標籤」折疊區或獨立原生編輯頁，預填可改、保存有明確回饋；不要求逐列必填。帳本有「手動記帳」入口。商品價格入口在分析中，讀既有產品及價格 API，支援新建商品、人工對應品項／規格、確認額外折扣分配、同商品跨店歷史比較，並連回帳目。未知實付價格不能參與最低價排名；歷史最低不是目前售價。

## Android 介面分工

Root 擴充 `MobileApi.kt` 具名方法與窄化同源 allowlist：

```
suspend fun insights(token:String,path:String):String
suspend fun insightMetadata(token:String,id:String,ledger:Boolean):String
suspend fun saveInsightMetadata(token:String,id:String,ledger:Boolean,body:String,key:String):String
suspend fun createManualEntry(token:String,body:String,key:String):String
suspend fun analysisOperation(token:String,key:String):String
suspend fun products(token:String,query:String="",offset:Int=0):String
suspend fun createProduct(token:String,body:String,key:String):String
suspend fun priceReview(token:String,id:String):String
suspend fun savePriceReview(token:String,id:String,body:String,key:String):String
suspend fun productPrices(token:String,id:String,offset:Int=0):String
```

`ledgerScope(token)` 只接受 GET /v1/session 實際回覆的 ledger_id，驗證 UUID 後才授權寫入或查詢未確定操作；不以 token 本身當帳本身份。

Native-main worker 擁有其他既有 Mobile*.kt 及新的 MobileInsight*/MobileManual*，但不修改 MobileApi.kt 或 MobilePrice*.kt。Price worker 只新增 MobilePrice*.kt 與自身測試，輸出：

```
internal data class PriceUiState(...)
internal class MobilePriceController(api:MobileApi, scope:CoroutineScope,
  token:()->String?, unauthorized:(String)->Unit, changed:()->Unit, journalDir:File,
  accountScope:()->String?)
  val state:StateFlow<PriceUiState>
  fun open(ledgerId:String?=null); fun close(); fun refresh(); fun createProduct(...)
  fun selectProduct(id:String); fun loadMore(); fun saveReview(ledgerId:String,body:String)
  fun accountChanged()
@Composable internal fun MobilePriceScreen(state:PriceUiState,controller:MobilePriceController,
  onLedger:(String)->Unit,onBack:()->Unit)
```

兩 Android worker 可直接協調函式細節並告知 root，保留單一 owner。所有新資料儲存與未知操作獨立 AtomicFile，不能污染既有收據索引；未知寫入 journal 放 noBackupFilesDir，不能由系統快取清除。純衍生分析快取獨立保存；token 仍從既有 vault 提供，不能保存到分析 cache。私人檔只記 token fingerprint→先前驗證 ledger_id 的唯讀 hint，冷啟離線時可讀同 token 的舊報表，不能藉此授權寫入、operation lookup 或恢復 pending。換 token 仍需重新取得已驗證帳本身份；登入切換重設請求世代。Root 統一跑 Gradle，避免同工作樹並行 Gradle。

## 交付驗證

合成資料覆蓋混合全聯品項／代買／整張及內含折扣／無餐別／無城市／缺日期／同餐多張／人工補名、註記歷史、CAS、idempotency、作廢及完整分頁，v4→5 不改原列。App 覆蓋多重篩選、手動寫入未知結果、重建、登入切換、離線、同筆標籤編輯、產品身份與單價、320dp150%/390dp/600dp/840dp。完整 Gradle testDebugUnitTest lintDebug assembleDebug、後端 pytest/Ruff、Web 既有測試，新的不可變候選與證據。工程、正式部署、手機與人工接受分別回報。

## 整合裁定

- 縣市僅合併四組已知官方地名台／臺字形（臺北市、臺中市、臺南市、臺東縣），保留原始註記，不推測地址。原始店家使用既有 merchant_identity，可在未補品牌時直接篩選。
- 折扣欄位明示 discount_scope=matched_receipts_including_advances，是符合帳目全張明列折扣，不能當選中品項折扣或市場節省。
- 回傳的每個分組與完整時間序列都必須與同份摘要對帳；App 嚴格核對，拒絕用矛盾圖表冒充正常報表。
- 無收據 manual 帳目的建立及事後編輯都不能標成收據印刷日期；舊版 manual source 但確實附有照片 evidence 的草稿不受此限制。

- 註記內部以 candidate revision 及 items SHA 綁定；品項結構變更會清舊品項標籤、保留帳目層標籤，metadata effective revision 隨之改變。A→B→A 仍拒絕舊 CAS；原始模型的 item_position 建議也不跨結構套用。確認與作廢不因相同 items 額外改分析版本。
- 未確定寫入結果與目前頁面 target 是不同狀態；A 操作完成不得替換 B 的編輯內容或下次寫入對象。各原生子頁有獨立返回上下文，巢狀來源帳目不改寫原分析返回位置。
