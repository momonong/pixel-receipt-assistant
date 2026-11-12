# PixelReceipt 0.7.0

Android versionName 0.7.0／versionCode 14，沿用套件及永久簽章身份。發行日期為 2026-11-13；工程、Git 交付、正式服務、手機與人工接受分開驗證。這份說明描述版本內容，正式部署結果以本次維運交付紀錄為準。

## 可操作範圍

生活分類／細分類、餐別與每餐均額、原始店家／品牌／分店、縣市／行政區、日期／週間／時段、用途與收據明列折扣，支援範圍內交叉查詢。分類、餐別與店家／地區點選回同條件帳目；日期下鑽保留完整桶區間。正常手機單欄，寬螢幕及字級自適應。

分析標籤與財務快照分離，可在核對前／入帳後補填，保留來源及修訂歷史；未知值不阻止帳務確認。新增無收據手動支出，以既有 domain 整數規則及單一 write transaction 建立。

App 原生商品目錄與人工身份核對、額外折扣價格分配、同規格跨店歷史單價與來源帳目已串接。原始商品名稱不因人工映射被覆寫；未知實付不參與排名，歷史最低不冒稱目前價格。

## 資料與相容

- 中央 schema 4→5 為 additive migration：新增註記、歷史與穩定 ledger_id；既有財務 snapshot、照片 bytes、認證及舊資料列保留，備份工具支援 5。
- 原 API 保持相容；新增 `/v1/insights`、`/v1/insights/entries`、草稿／帳目 `/insights`、`/v1/manual-entries`、受限 operation lookup。`/v1/session` 回非秘密 ledger_id。
- 新寫入在私人 noBackupFilesDir 保存 frozen key/body；同帳本換 token 可查原 operation，不同帳本隔離。登入世代與查詢範圍核對，舊 401 不清除新登入。
- 補入或改動的消費日期不自動聲稱是原始印刷日期；未保存預設今天來源的舊核對，只能確定是人工輸入／接受，不能重建不存在的來源。
- 所有金額為整數 TWD 元；個人支出自用＋送人，代買另列，內含折扣不重扣。收據層折扣明示含代買；無合法品項分配的差額明示待分配。
- 不新增依賴、供應商、定位服務、模型權重或付費備援。辨識 context 契約可接附加候選，未執行真實推論。

## 驗證狀態

同版完整 Android testDebugUnitTest／lintDebug／assembleDebug 通過（505 tests、零失敗／略過）；後端 pytest 479 passed（31 個既有套件棄用警告）、Ruff check／format 56 檔通過，備份／還原 11 tests 與 Web 3 test files 通過。真正 Compose 已檢查 320dp／150% 字級、390dp、600dp、840dp及鍵盤；後端與原生各一次 fresh review 的重要發現均修正並補回歸。APK／source SHA 以獨立交付清單為準；這些是工程證據。

本機驗證不代表正式 schema 5 遷移、公開 APK 更新、真機安裝、TalkBack 或使用者帳務核對已完成。更新正式服務前需備份與核對同一作業，不可只把新 APK 配舊後端。

使用方式見 [生活支出分析](LIVING_SPENDING_ANALYSIS.md)；財務及價格定義見 [價格分析契約](PRICE_ANALYSIS_CONTRACT.md)。

## 手機更新

正式後端與 APK 應同步升級。更新發布後，在 App「設定」按「檢查更新」，下載完成後由 Android 確認覆蓋安裝；沿用相同套件與永久簽章，不需要解除安裝或清除資料。自動化工程驗證不代表已在手機完成本次安裝。
