# PixelReceipt AI (智慧細粒度記帳助理)

專為 Google Pixel 生態打造的無伺服器（Serverless）智慧記帳應用。透過 Gemini 多模態語意辨識，解決傳統記帳軟體「無法自動拆解消費細項」、「無法精確歸屬促銷折讓」與「代墊拆帳繁瑣」的痛點，並自動將乾淨的結構化數據同步至個人的 Google 試算表。

---

## 核心設計理念

* **細粒度單品記錄（Line-item Level）**：不只記總金額，每件商品（如：火鍋肉盤、洗髮精、牙刷）獨立成列，支援後續交叉分析。
* **真實促銷扣抵還原**：自動解析「任選折扣」、「買一送一」與「全店折讓」，計算個別商品的實質取得單價與省下金額（`discount_saved`）。
* **零伺服器維護成本**：Android 本端執行，直接透過 SDK 調用 Gemini API，以 Google 試算表作為雲端資料庫，無需自架後端與主機。
* **品名自動標準化**：將實體店發票上的縮寫（如 `淨男士洗髮清爽`、`舒適辨型保濕架`）自動對齊通用分類（`洗髮精`、`刮鬍刀`）。

---

## 系統架構

```
[紙本消費明細 / 發票照片] ──┐
                          ├──> [Pixel CameraX / 圖片選取]
[語音輸入 / 刷卡推播文字] ──┘                 │
                                              ▼
                                 [Gemini Flash API (Structured Outputs)]
                                 • 商家與縣市萃取
                                 • 縮寫標準化與大/細分類
                                 • 折扣關聯分攤與省錢額計算
                                              │
                                              ▼
                                 [互動式確認介面 (Compose UI)]
                                 • Checklist 勾選自用 vs 代墊
                                 • 補齊折讓或確認品項
                                              │
                                              ▼
                                 [同步入庫 (雙軌儲存)]
                                 ├──> 本機端 SQLite (Room DB) 快速查詢與比對快取
                                 └──> Google Sheets API (雲端明細扁平表)

```

---

## 資料欄位規格 (Google Sheets Schema)

每筆消費細項以一列（Row）為單位寫入試算表中的 `Raw_Transactions` 工作表：

| 欄位名稱 | 類型 | 範例 | 說明 |
| --- | --- | --- | --- |
| `transaction_id` | String | `TX_20260902_001` | 該張發票或單次交易的唯一識別碼 |
| `date` | Date | `2026-09-02` | 消費日期（YYYY-MM-DD） |
| `merchant` | String | `全聯` | 標準化商家名稱 |
| `city` | String | `臺南市` | 消費所屬縣市 |
| `category` | String | `居家生活` | 消費大類（餐飲、居家生活、交通、娛樂等） |
| `sub_category` | String | `牙刷` | 標準化商品細項標籤 |
| `raw_name` | String | `高露潔齒縫潔淨` | 發票或明細上的原始文字 |
| `standard_name` | String | `高露潔 齒縫潔淨牙刷 2入` | 辨識後的完整商品名稱 |
| `quantity` | Integer | `2` | 購買數量 |
| `original_price` | Decimal | `378.00` | 牌告未折原總價 |
| `discount_saved` | Decimal | `189.00` | 該商品享受到的折扣或買一送一省下金額 |
| `net_amount` | Decimal | `189.00` | 扣除促銷後的實付總額 |
| `unit_price` | Decimal | `94.50` | 實質單價（`net_amount` / `quantity`） |
| `is_personal` | Boolean | `FALSE` | 是否為個人自用消費 |
| `split_party` | String | `室友` | 若為代墊，記錄代墊對象或平分註記 |
| `my_expense` | Decimal | `0.00` | 最終計入個人生活預算的實際支出 |

---

## Gemini 結構化輸出定義 (JSON Schema)

發送圖片或明細文字至 Gemini API 時，指定使用以下 JSON Schema 解析：

```json
{
  "type": "OBJECT",
  "properties": {
    "merchant": { "type": "STRING", "description": "商家標準化簡稱，如：全聯、萬客什鍋、7-Eleven" },
    "city": { "type": "STRING", "description": "消費地點所屬縣市，若無明確地址則依店家分店資訊推斷" },
    "transaction_date": { "type": "STRING", "description": "格式 YYYY-MM-DD" },
    "total_invoice_amount": { "type": "NUMBER", "description": "整張發票最終付款金額" },
    "items": {
      "type": "ARRAY",
      "items": {
        "type": "OBJECT",
        "properties": {
          "raw_name": { "type": "STRING" },
          "standard_name": { "type": "STRING" },
          "category": { "type": "STRING" },
          "sub_category": { "type": "STRING" },
          "quantity": { "type": "INTEGER" },
          "original_price": { "type": "NUMBER" },
          "discount_saved": { "type": "NUMBER", "description": "若該品項有買一送一或任折，填入折扣正值，無則為 0" },
          "net_amount": { "type": "NUMBER", "description": "折抵後的實際應付金額" }
        },
        "required": ["raw_name", "standard_name", "category", "sub_category", "quantity", "net_amount"]
      }
    }
  },
  "required": ["merchant", "total_invoice_amount", "items"]
}

```

---

## 開發技術棧 (Tech Stack)

* **平台**：Android (Target SDK 34+，優化適配 Google Pixel)
* **語言與架構**：Kotlin、MVVM Architecture、Jetpack Compose (UI)
* **相機與影像**：CameraX、Google ML Kit (本機條碼/QR Code 快速解碼)
* **AI 推論**：
* 雲端：Google GenAI SDK for Android (`gemini-2.5-flash` / `gemini-1.5-flash`)
* 端側輔助（規劃中）：Gemini Nano (透過 Android AICore)


* **資料儲存**：
* 本地快取：Room (SQLite)
* 雲端數據：Google Sheets API v4 (透過 Google OAuth 2.0 授權登入個人帳號)



---

## 開發里程碑與路線圖

### Phase 1: MVP 核心入庫 (當前目標)

* [ ] 建立 Android 專案與 Jetpack Compose 介面。
* [ ] 整合 CameraX，支援拍照明細聯與發票證明聯。
* [ ] 串接 Gemini Flash API，套用結構化 JSON Schema 解析品項、折讓與標準化名稱。
* [ ] 實作單筆消費審查頁面（Checklist 勾選自用/代墊、平分計算）。
* [ ] 串接 Google Sheets API，將明細寫入指定試算表。

### Phase 2: 自動化與本機快取

* [ ] 實作 `NotificationListenerService`，監聽特定銀行與 Google 錢包刷卡推播。
* [ ] 建立本機 Room 資料庫快取（歷史品項映射表，降低重複 API 呼叫次數）。
* [ ] 加入 Pixel 快捷開關（Quick Settings Tile），支援一鍵彈出浮動錄音輸入。

### Phase 3: 消耗追蹤與採購決策

* [ ] 試算表端整合 Looker Studio，產出縣市消費地圖與品類統計看板。
* [ ] 根據歷史購買間隔，計算日常用品（洗沐、耗材）的預計消耗日與補貨警示。
* [ ] 單品歷史價格索引（查詢特定品項在不同通路的歷史最低實付單價）。
