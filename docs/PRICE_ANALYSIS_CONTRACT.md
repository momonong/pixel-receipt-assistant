# 商品價格與折扣：第一版計算契約

本文件定義已實作的中央商品身份、價格與折扣計算契約；HTTP 入口見 [後端文件](../backend/README.md)。它不表示既有資料已由人核對，也不把歷史觀測價格當成目前售價。

## 消費摘要

摘要只統計正式且未作廢帳本。收據日期為篩選日期；草稿、報價、菜單與促銷圖片不形成支出。顯示票面付款、個人支出（自用＋送禮）、代墊及未分配差額，不把它們混成同一數字。

「明列折扣」是已核對收據中 `direction=subtract` 的金額合計，包含已含行金額與另列折扣各一次；它不是與市場最低價的差距，也不是所有可能省下的錢。沒有明列折扣時可顯示本統計值為 0，不能聲稱不存在其他未印出的優惠。無原價證據時，不從實付額反造原價。

總額由後端以整數運算。超出支援範圍時回明確錯誤或未知，不截斷、不使用浮點數，也不把部分頁面合計標成完整摘要。

## 商品身份

每個可比較商品代表固定品牌／品項／規格及一個購買單位。例如「某品牌無糖茶 500 mL／瓶」和「同品牌無糖茶 2 L／瓶」是不同商品。人工確認的商品名稱、規格、每個購買單位說明及可選的容量資訊保存為獨立資料。

正式帳本的每一列可以由 reviewer 指到商品，明確確認「這列數量 1 對應此商品的 1 個購買單位，品項及規格一致」。原始品名、數量與金額保持原樣。不能按相似文字或模型建議自動確認商品身份。

商品身份與價格核對是帳本外的分析註記，有自己的 revision、操作者與歷史；不得改寫已確認帳務。誤配可撤銷／重新核對並留痕；作廢帳本自動退出價格比較。第一版以同商品同規格跨店比較為主，不自動合併不同容量或組合包。

## 每列價格

票面行合計已包含該列數量。單件價格使用「行金額／數量」的精確比例，傳輸 numerator／denominator 或十進位字串，不以浮點數作排序與帳務。

實付行合計的計算：

1. 從票面行合計開始。
2. `included_in_line_totals` 只作折扣證據，不再次加減。
3. 額外加減項明確只適用單一列時，可以直接分到該列。
4. 適用多列或整張的額外加減項，需要獨立的「商品價格分配」核對。用途分配只能回答誰負擔，不足以回答每項商品多少錢。
5. reviewer 可明確選按適用列票面行合計比例分配，或指定各列金額；比例分配使用最大餘數法，同餘數依原始列順序。分配合計須等於加減項金額、不得超出 scope。零分母不能自動分配。
6. 有任何適用但未分配的額外加減項，該列實付價保持未知並退出實付價格排名；已知票面行合計仍可查。
7. 分配後每列不得負數，不能拿另一列補平負數。金額範圍與溢位檢查沿用帳本規則。

價格分配不改變任何人的負擔或原帳本總額，只補充分析依據。資料、映射或分配變更後，分析結果重新以目前核對版本計算，歷史仍可追查。

## 顯示與證據

同商品價格表列出日期、商店、原始品名、數量、票面行合計、可確定的實付單價、分配方法與原收據。歷史最低價是觀測紀錄，不代表今天仍可買到。不同商店需保留來源商店，不以商品名稱推測店家或地點。

菜單、價牌與促銷原圖可保存為獨立證據，後續加入報價觀測時須顯示觀測日、店家與適用條件。未購買的報價與優惠不能出現在消費、實際折扣或已付價格統計。第一版不實作促銷資格自動判斷或「假設在另一店買」的節省承諾。

## 必要驗證

- 相同名稱但不同規格／未確認映射，不會自動合併排名。
- 2 件行合計 189 元得到精確單價 189／2；不再乘 2。
- 行合計 149 元、內含折扣 100 元，實付仍為 149 元；明列折扣為 100 元。
- 多列共用折扣未分配時，受影響列實付未知；核對後各列調整合計與原折扣相同。
- 3 元按同額兩列分配為 2／1，重算一致；0 元基準需要明確指定。
- 作廢後從摘要與比較退出，仍能查看原帳本與核對歷史。
- 超大整數、日期篩選、分頁與字串輸入不得造成錯算、靜默截斷或注入。

## 原生支出分析 API

`GET /v1/spending` 是已認證的唯讀投影，不建立價格註記、不改帳本、不調用推論。
`scope=active_confirmed_ledger`、`currency=TWD`、`amount_unit=TWD_YUAN`。
`summary` 包含 overview 既有七種計算金額及 `explicit_discount_yuan`、`receipt_count`、
`missing_item_name_count`、`receipts_missing_item_names`；不含 overview 的 scope／日期 metadata。
個人支出為 self + gift，代買已經排除；明列折扣只呈現原定義，內含折扣不再次扣款。
所有金額保持整數，不傳浮點百分比；摘要或比較超出 Long.MAX_VALUE 明確回
422 `summary_amount_out_of_range`，不截斷或近似。缺品名依目前帳本顯示名稱統計。

參數 `date_from/date_to` 可各自省略；`compare_from/compare_to` 必須成對，皆為有效
YYYY-MM-DD，包含起訖日；無效或反向日期回 422 `invalid_date_range`，缺比較一端回
422 `comparison_dates_required`。回覆 `comparison=null` 或
`{date_from,date_to,personal_yuan,receipt_count,delta_personal_yuan}`，差額是主期間減比較期間。
比較與主摘要、作廢狀態、品名都在單一 SQLite read transaction 內讀取同一份完整 active
帳本；沒有基期支出時仍回已知的零元與筆數，不造百分比。

`bucket=auto|day|month|year`：有明確日期界限就以界限計算，省略的一端以所選帳目最早／晚
日期定界。auto 在包含邊界最多 90 日選 day；較長但最多 120 曆月選 month；超過選 year。
沒有已知日期且未指定期間時選 day 並回空 series。理論桶數最多 1000（即使資料稀疏也驗證），
超出回 422 `spending_bucket_limit_exceeded`。`series` 按時間排序，元素
`{date_from,date_to,label,personal_yuan,receipt_count}`，起訖裁至查詢界限；label 分別為
YYYY-MM-DD／YYYY-MM／YYYY，只傳有帳目的桶。前端依真實時間定位，不能把相隔數月的
兩桶畫為相鄰日期。全部期間缺日期帳目計入摘要與店家，但不虛構 series 日期；以
`undated_receipt_count/undated_personal_yuan` 表達。期間篩選排除的 active 缺日期筆數是
`excluded_undated_receipt_count`。

`merchants` 按個人支出由高到低、同額依 key 排序，元素
`{key,name,personal_yuan,receipt_count}`。name 為原始店名或 null；None、空字串及純空白
標準化為 null，其餘原文精確保留。key 計算為 UTF-8 canonical JSON 的 SHA-256
（ensure_ascii=false、sort_keys=true、separators=(',', ':')）；這是穩定識別，非憑證。
例如 null 的 JSON bytes 為 `null`，名稱字串保留 JSON 引號，不能與同名字面的字串混淆。

`GET /v1/ledger` 新增 `merchant_key`（64 位 hex，大小寫均接受）與 `undated_only`；
先套用 q、status、日期及上述精確篩選才分頁；不存在的有效 key 回空頁。undated_only=true
與任一日期界限一起使用回 422 `undated_only_date_conflict`。分析明細固定 status=active。
列表每個 entry 增加 merchant_key 作為 representation，不修改 snapshot、事件、schema。
本次沒有 migration、價格重算、帳本寫入或正式資料操作。
