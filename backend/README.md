# PixelReceipt 中央帳本與核對 Dashboard

這是 Python／FastAPI、SQLite 與原始 bytes blob store 的中央服務：保存原圖與候選、人類核對／用途分配、確定性確認入帳／作廢，以及獨立的商品身份與價格分析。Android 0.5.0／code 7 與相應後端已部署至 ASUS；[中央服務](https://momonong.me/receipts/) 提供登入後的 Dashboard 與 APK 下載。手機更新與真人帳務接受待驗，見 [0.5.0 發行說明](../docs/RELEASE_v0.5.0.md)。

主流程是 APK 將原圖、整批說明與逐圖角色／備註交給中央 intake，由後端透過已配置來源辨識，再在 App 內人工核對與確認入帳。精確 DTO／狀態見 [手機契約](../docs/MOBILE_INGEST_CONTRACT.md)。原生 App 使用 v2 reviewer session；舊 v1 device ingest token 保留收件權限，不自動升權。purchase 成功只形成 needs_review，reference 只保存價格候選，不形成消費草稿。未配置 engine 明示 unavailable，不假裝辨識。

核對頁會透過 reviewer-only `GET /v1/drafts/{draft_id}/intake-context` 顯示整批原說明、逐圖角色與備註。關聯只讀取中央 intake 保存的草稿 ID 並核對原圖順序；舊草稿沒有關聯時回傳 `intake: null`，不從來源標籤推測。說明視為不可信文字，僅供對照，不會自動分配用途或改寫金額。Dashboard 的五個主頁、草稿與帳本明細會同步 URL，重新整理或重新登入後回到通過 allowlist 的同一頁；未儲存核對仍需先確認是否離開。


上傳原圖完整保存；衍生 JPEG 每張 384 KiB／最長邊 1600，合計 8 MiB；完整序列化 request 16 MiB、result 4 MiB。部署以固定 `document_pdf` 路由按圖片順序封裝成真 PDF，接既有 Google 文件能力；v1 intake 仍只收圖片，沒有宣稱 APK PDF 分享完成。`vision` 是另一個已測可配置 mode，不能由 adapter 存在推定現場路由可用。完整 payload 先落盤、routes/explain 通過後才標 submitting 並 queue POST；不重試 POST。結果未知沿原 key GET，保存原 raw outcome／來源／模型／截斷／版本及衍生 hashes。候選 parser 僅接受純 JSON 或完整單一 `json` code fence，僅移除外層 fence／空白；原文不變，另記 parser 版本、是否執行與是否去 fence，仍拒絕散文抽取、重複鍵、非整數金額或截斷結果。

`--recognition-config /etc/pixelreceipt-recognition/recognition.json` 為可選啟用配置，keys：enabled=true、base_url（固定 http://127.0.0.1:port）、token_file、input_mode(vision|document_pdf)、provider/model（可 null）、max_media_bytes=8388608、max_request_bytes=16777216、max_result_bytes=4194304。provider/model 由部署配置明確指定。無 redirect、環境 proxy、POST retry 或 unknown fallback。配置採 owner 0400/0600，或精確 `/etc/pixelreceipt-recognition/recognition.json` root:服務群組 0440；合法 client token 另由 systemd credential 精確路徑 `/run/credentials/pixelreceipt.service/client_token` 讀取，不把 provider/admin key 當 client token，不進 DB／回應／日誌。

單 worker 查詢持久化退避，10／20／40／60 秒封頂，提交後最多監看 15 分鐘；unknown 到限保留原 key 與完整 journal、標 polling_finished 待人工核對，不重新提交。其他獨立 intake 可繼續走 Broker admission，Broker 自身序列與 quota gate 仍適用。大檔／DB 工作在 thread，解碼／PDF 封裝在既有資源限制子程序，網路為 30 秒整次 deadline 的 asyncio HTTP，查詢及關閉不等待長模型執行。worker 儲存錯誤可從 recognition diagnostics 觀察。

`--mobile-release-dir /srv/pixelreceipt/mobile-current` 為可選 APK 交付；固定 release.json＋app.apk，啟動串流核對 hash／大小，所有檔案 root readonly，僅認證後 GET `/v1/mobile/release`、`/v1/mobile/apk`，APK 用 FileResponse 串流下載。metadata 的 signer hash 是部署核對結果，不由後端猜測 APK 簽章。未配置回 404。

## 本機使用

需要 Python 3.12+ 與 uv。每個工作樹自行依 lock 建環境，不修改全域 Python，也不從其他主機複製 `.venv`。在 `backend/` 執行：

```bash
uv sync --locked
uv run --locked pixelreceipt-backend init
uv run --locked pixelreceipt-backend configure-reviewer
uv run --locked pixelreceipt-backend serve \
  --static-dir src/pixelreceipt_backend/static \
  --public-origin http://127.0.0.1:18991 --insecure-local-cookie
```

預設資料位置是**該命令工作目錄**的 `.pixelreceipt-private/`；可用 `--data-dir` 指定專屬絕對路徑。`init` 建立 `0700` 目錄與 `0600 auth-token`，不印 token；重跑不覆寫。`configure-reviewer` 透過 getpass 兩次輸入 12–1024 字元密碼，只寫 `reviewer-password` 的 scrypt verifier，不回顯秘密，也不覆寫既有 verifier。現有 private dir 不能包含其他服務資料；根目錄、home、symlink、共用權限或不明 schema 會拒絕，不替舊路徑 chmod／清理。`serve` 不會因路徑打錯建立新資料庫。

launcher 固定 `127.0.0.1`、預設 port `18991`、單 worker、HTTP concurrency 16／keep-alive 5 秒，不信任 proxy headers、不開 CORS／Swagger／公開 DB 或 blob mount，關閉 access log。`--insecure-local-cookie` 只用於上述明確 loopback 開發；正式服務必須 Secure cookie、HTTPS `--public-origin`。ASUS 正式服務使用 `--root-path /receipts`，代理 strip 同一 prefix，static 與 cookie Path 隨之對齊；不能以本機 listener 單獨當作正式部署證據。設定與備份契約見 [部署與備份](../docs/ASUS_DEPLOYMENT.md)，本版狀態見 [發行說明](../docs/RELEASE_v0.5.0.md)。

## 認證與角色

| 角色／來源 | 可做的事 |
| --- | --- |
| 匿名 | 取得登入頁與 allowlist 中的 Dashboard 靜態程式；無法讀取私人 API／原圖 |
| ingest bearer：私有 `auth-token` 或程序環境 `PIXELRECEIPT_TOKEN` | health、原檔上傳、建立候選，以及讀取草稿／帳本／商品／分析；不能人工 review、confirm、void 或建立價格核對 |
| v1 裝置 ingest bearer | `/v1/mobile/login` 以密碼換取30天裝置 token；可讀取與收件，不能 review／confirm／void；可撤銷 |
| v2 原生 reviewer session | `/v2/mobile/login` 回傳獨立 `prm_` Bearer、role=reviewer、device_id、expires_at；有效30天，支援 App 內核對／確認；`/v2/mobile/logout` 只撤銷當前 session |
| 瀏覽器 reviewer session | `GET/POST /auth/login` 私有密碼登入；HttpOnly／SameSite=Strict cookie，預設 Secure、8 小時、server-side 可撤銷；變更需正確 Origin 與 `X-CSRF-Token` |
| 可選 reviewer bearer：私有 `reviewer-token` 或 `PIXELRECEIPT_REVIEWER_TOKEN` | 相同 reviewer 權限；必須與 ingest token 不同，只供明確授權管理用途，不交給模型或 ingest CLI |

密碼 verifier 也可由程序環境 `PIXELRECEIPT_REVIEWER_PASSWORD_HASH` 提供；環境與檔案都是秘密來源，不放入 argv、URL、Git、報告、前端或聊天。瀏覽器登入先取得 nonce form；cookie 登入／登出及修改都做 Origin／CSRF 核對，`Origin: null` 拒絕。`Referrer-Policy: same-origin` 保留同源 form 的 Origin。`GET /v1/session` 只供 reviewer；cookie session 回當前 CSRF／到期時間，native session 回 `auth_method=mobile_session` 與 `csrf_token=null`。native Bearer 不發 cookie，仍須 HTTPS 及有效 server-side session；token 只保存 hash。`GET /v1/devices` 聚合手機登入，`DELETE /v1/devices/{device_id}` 撤銷該裝置 legacy token 與全部 native sessions，不影響其他裝置或瀏覽器。這是個人服務的角色界線，沒有多使用者／多租戶 ACL。

repo 根目錄的 [ingest CLI](../scripts/receipt_api.py) 只有 health／原檔 upload／create/get/list draft，使用 `PIXELRECEIPT_INGEST_TOKEN` 或 owned `0600` token 檔。它不接 reviewer token、不提供確認入帳，持久保存原 Idempotency-Key，timeout／408／5xx 結果未知時仍使用同一 state。使用方式見 [ingest CLI 契約](../docs/DOT_INTEGRATION.md)。

## HTTP 入口

下表路徑相對 API 根；已部署的公開 prefix 是 `/receipts/`。所有私人 API 均需認證；寫入表列 reviewer 的操作不能由 ingest 執行。

| 方法與路徑 | 契約／權限 |
| --- | --- |
| `GET /health` | 中央服務 scope、DB schema version；不代表 dot 或辨識品質可用 |
| `POST /v1/mobile/login`、`/logout` | 舊 device ingest 登入／登出；不升為 reviewer |
| `POST /v2/mobile/login`、`/logout` | 原生 reviewer 登入／單 session 登出；既有密碼 verifier、HTTPS／Origin、rate limit |
| `GET /v1/devices`、`DELETE /v1/devices/{device_id}` | reviewer 的手機裝置列表／整部裝置撤銷 |
| `POST /v1/intakes`、`GET /v1/intakes`、`/{id}` | 不可變收件／查詢；recognition status 與正式 draft_status、ledger_id、ledger_status 分開 |
| `GET /v1/recognition` | 已配置辨識來源的可用狀態，不代表辨識品質 |
| `GET /v1/mobile/release`、`/apk` | 認證 metadata／APK 下載，未配置時404 |
| `POST /v1/evidence` | 原始 JPEG／PNG／WebP／PDF binary body；不是 multipart、URL 或伺服器任意路徑；回 SHA-256／大小／頁數／尺寸 |
| `GET /v1/evidence/{sha256}`、`.../content` | metadata／hash 驗證後的原 bytes attachment |
| `POST /v1/drafts` | [CreateDraft schema](create-draft.schema.json)，`Idempotency-Key` 必填；201 新建、200 重送，初始 `needs_review` |
| `GET /v1/drafts`、`/{id}`、`/{id}/history` | 候選／原始候選／證據／來源／revision／歷史；列表可篩 needs_review／confirmed／voided |
| `PUT /v1/drafts/{id}/review` | reviewer 的舊候選替換契約；會清除完整核對／用途分配，不能用它直接入帳；[schema](review-draft.schema.json) |
| `PUT /v2/drafts/{id}/review` | reviewer 完整核對、完整性與用途分配；revision CAS；[schema](review-receipt.schema.json) |
| `POST /v2/drafts/{id}/confirm` | reviewer、CAS、Idempotency-Key；依確定性 gate 建 immutable ledger snapshot；[schema](confirm-receipt.schema.json) |
| `GET /v1/ledger`、`/{id}` | 原帳本／確認快照／事件；列表支援 literal q、receipt_date 範圍、active／voided、分頁 |
| `POST /v1/ledger/{id}/void` | reviewer、原因／CAS／Idempotency-Key；只追加作廢事件，保留確認快照；[schema](void-ledger.schema.json) |
| `GET/POST /v1/products` | 列表／reviewer 建立固定身份；POST 需 Idempotency-Key，不按近似名稱合併 |
| `GET/PUT /v1/ledger/{id}/price-review`、`GET .../history` | 人工商品／價格分配的獨立 revision；PUT 僅 reviewer，與用途分配及帳本金額分開 |
| `GET /v1/products/{id}/prices`、`GET /v1/overview` | 已確認且未作廢的比較／支出摘要；日期篩選與全範圍統計先於分頁 |

價格 DTO／錯誤與完整例子見 [price-api-contract-examples.json](price-api-contract-examples.json)，計算定義見 [價格契約](../docs/PRICE_ANALYSIS_CONTRACT.md)。各列表 limit 1–100、offset 0–100000；日期為有效 `YYYY-MM-DD`、範圍包含邊界。查詢字串是 literal substring，不能變成 SQL／glob。資料與來源文字一律視為不可信內容，前端不以 innerHTML 執行它。

## 金額、核對與價格

`currency=TWD`、`amount_unit=TWD_YUAN`；`*_yuan` 為 **TWD 整數元，不乘 100**。金額 0–Long.MAX_VALUE，quantity 1–Int.MAX_VALUE 或 null；拒絕 bool、數字字串、浮點／科學記號、重複 JSON key、過深／額外欄位。未知是 `null`，已知零才是 `0`，不預設 quantity=1、不補推單價／原價／總額。品名保持原文與順序，最多 100 列、50 筆 adjustment；包裝含量是最多六位小數的十進位字串，與金額分開。

票面行合計已含該列數量，不再乘 quantity。`included_in_line_totals` 折扣只保存證據，不再扣一次；`separate_from_line_totals` 才另外加減，未知 effect／scope 會阻擋確認。人類必須確認收據完整、商家／日期／數量／金額等必要事實與每列用途；用途為 self／gift／advance，個人支出=self+gift。拆分與整單調整使用確定性整數運算、明確 scope 和最大餘數規則。App 初次建立編輯時，只將未指定用途的品項預設為 self；既有送人、代買或分攤保留。這是可見、可改的核對預設，不取代最後的人類確認。

允許 ±1 元對帳容差，但非零差額需人類明確接受，差額獨立列示，不分配給任何用途。超出容差、必要資料未知、用途／調整未核對或超範圍會保留 blocker；不能以湊合計通過取代原件完整性。確認後禁止改寫候選／用途；訂正是作廢留痕後建立另一筆草稿，舊確認快照仍保留。

商品是人工確認的品牌／品項／固定規格／購買單位。價格核對是帳本外分析註記，修改或撤銷映射仍留歷史，不改確認快照。多列／整張額外折扣需另做商品價格分配；用途分配不能直接當價格分配。未映射或適用折扣未分配的列，實付價保持未知並退出排名。單價用精確比例（如 189／2）比較，不用浮點數排序；最低價是歷史觀測，不能當作今天仍可購買或已省下的錢。全範圍聚合／最低價逐筆計算後才分頁，仍需 ASUS 的實際容量驗證。

## 保存、冪等與輸入限制

原圖保持 bytes，以 SHA-256 去重；同檔可參照於不同交易，去重不會自行合併收據。頁面由呼叫者明確分組；PDF 多頁不自動等於同一交易。來源 provider／model／label 是 provenance 敘述，不證明有執行模型或人類確認。原始候選／證據／source 保留，不隨人工修訂覆寫。

草稿 key 為 1–128 字元、首字元英數、其餘英數或 `._:-`；同 key 同內容回現行版本，換內容 409；review CAS 過期回 409，須重讀後由人處理，不自動覆蓋。confirm／void／product key 各有持久操作關聯；unknown outcome 保留原 key／目標／內容回查，不更換 key 或另建交易重送。

單檔上限 20 MiB；JPEG／PNG／WebP 需 MIME 相符、完整解碼、單幀、每邊不超過 20000、最多 50M 像素；PDF 最多 100 頁，拒絕加密、actions／JavaScript／附件／表單／annotations。上傳在讀 body **之前**取得單一 slot；忙碌 503 `inspection_busy`、body 超時 60 秒回 408 `upload_timeout`。Linux parser 子程序 CPU soft／hard 5／6 秒、AS 384 MiB、wall 10 秒、CORE 0、NOFILE 32，最小 env、不繼承憑證、取消／timeout kill+reap，不做 in-process fallback。這是資源隔離，非完整 sandbox；合法大檔也可能遭資源拒絕，整體記憶體仍需 systemd cap 與實機驗證。

有效 API no-store；原圖 attachment。常見錯誤：401 認證、403 reviewer／Origin／CSRF、409 revision／key／狀態衝突、413 大小、415 媒體、422 不合法輸入或計算範圍、503 DB／完整性／隔離檢查不可用、507 evidence 上限。錯誤不回顯原候選或 parser stderr。總 evidence 上限 1 GiB，不含 SQLite／歷史，也不等於磁碟配額。

目前 DB user_version=4；v1→v2 人類核對／ledger、v2→v3 商品分析、v3→v4 intakes／device_sessions 為明確 additive migration，未知／不符的既有 schema 拒絕，不破壞性 fallback。沒有自動 GC、刪除原圖或保證對管理者防竄改。DB 與 blob 不放 public root、Git 或靜態目錄；使用 [備份／還原工具](../deploy/receipt_backup.py) 的一致 SQLite snapshot + 原圖驗證，不能在運作時只複製主 DB。還原僅寫全新 private dir，必須清除 sessions 及 device_sessions，不自動切換 live／重啟。

## 驗證與交付狀態

在 `backend/`：

```bash
uv sync --locked
uv run --locked ruff check src tests
uv run --locked ruff format --check src tests
uv run --locked pytest -q
```

repo 根目錄另跑 `python3 -m unittest discover -s scripts/tests -v`；真實 v3 Store 備份回歸需使用已安裝此 backend 的 Python（例如 `uv run --project backend --locked python -m unittest discover -s scripts/tests -p test_backup_store_v3.py -v`），標準庫環境沒有安裝 backend 時該項會明確 skip。打包工具只建立 allowlist review candidate，SHA 完整性不等於功能驗收；不夾帶 runtime／私人資料，也不自行部署。

本版後端 **283 tests 通過**；Android **304 tests**、嚴格 lint 與 APK 建置通過。公開 scripts 測試 **32 passed／1 historical migration skipped**；該歷史案例需要隔離的舊 v3 runtime 與外部凍結 v4 source，不屬於一般 current-runtime 測試。私有主機部署工具測試不列為公開 release gate。

測試使用合成 bytes／文字與隔離資料庫，不能代替真實辨識品質、正式資料還原演練或真人帳務接受。ASUS 後端與0.5.0認證 APK 下載已部署，手機更新與人工接受待驗；當前公開範圍見 [發行說明](../docs/RELEASE_v0.5.0.md)。
