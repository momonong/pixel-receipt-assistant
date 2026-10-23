# PixelReceipt 手機收件與原生核對契約

收件 schema：`pixelreceipt-mobile-ingest-1`。Android 0.5.0 提供收件與原生核對，ASUS 為中央資料來源；API Quota Broker 為預設來源，已配置的本機模型可另行選用。所有路徑相對既有 HTTPS API 根 `https://momonong.me/receipts/`。APK 只連中央後端，不取得 Broker／模型憑證。

## 登入與權限

`POST /v1/mobile/login`，`Content-Type: application/json`：

```json
{"device_id":"00000000-0000-4000-8000-000000000001","device_name":"Pixel 手機","password":"<人工輸入，僅 HTTPS 與記憶體>"}
```

`device_id` 為 APK 自行產生且持久保存的 UUID；`device_name` 1..100 字元；password 1..1024 字元。未知欄位拒絕。成功 200：

```json
{"token_type":"Bearer","access_token":"<只在登入回應出現>","expires_at":"2026-11-20T00:00:00+00:00","device_id":"00000000-0000-4000-8000-000000000001","role":"ingest"}
```

裝置 token 有效 30 天；同裝置重新登入撤銷上一 token。DB 只存 hash。`POST /v1/mobile/logout`（裝置 Bearer，空 body）撤銷當前 token，回 `{"status":"logged_out"}`。401 時 APK 保留待傳資料並要求登入，不自動用密碼重試。裝置可讀中央資料、上傳 evidence、建立 intake；不能 review／confirm／void／建立商品價格核對。Dashboard reviewer 可 `GET /v1/devices` 取得 `devices`，`DELETE /v1/devices/{device_id}` 撤銷該裝置所有 token。browser reviewer 原 8 小時 session 不變。

## 原生 reviewer 登入與裝置撤銷（v2）

`POST /v1/mobile/login` 保留上述 ingest 契約；既有 token 不自動升權。原生核對使用 `POST /v2/mobile/login`，JSON 同為 `device_id`、`device_name`、`password`，沿用既有 reviewer 密碼 verifier、HTTPS／Origin、8 KiB 輸入限制、body deadline 與共用登入 rate limit。

成功 200 回傳 `token_type="Bearer"`、獨立 `prm_` 前綴的 `access_token`、`role="reviewer"`、原 `device_id`、ISO8601 `expires_at`。期限固定 30 天、不滑動延長；只保存 token hash、不發瀏覽器 cookie。必須同時有正確前綴、有效 session 與 canonical 裝置標記才有 `reviewer/mobile_session` 身分；失效時不 fallback 舊 ingest 權限。原 web 8 小時 cookie／Origin／CSRF 契約維持，native token 不能當 cookie 使用，`GET /v1/session` 對 native 回 `auth_method="mobile_session"`、`csrf_token=null`。

原生 reviewer Bearer 可接既有 `GET /v1/drafts/{id}`、`PUT /v2/drafts/{id}/review`、`POST /v2/drafts/{id}/confirm`、`GET /v1/ledger` 與 `GET /v1/ledger/{id}`。金額與入帳仍由確定性後端核對；草稿保存不等於確認。confirm 維持 revision CAS 與原 Idempotency-Key；斷線或未知結果不能換 key 重複入帳。帳本 list 的 `entries` 與 detail 都是 ledger entry，候選、用途與 calculation 位於 `snapshot`；現行作廢狀態讀外層 `status`，確認快照不改寫。

`POST /v2/mobile/logout` 使用 native Bearer 時只撤銷當前 session，不影響同裝置其他 native session、舊 ingest token 或瀏覽器登入。使用舊 device ingest token 時沿用原本裝置 token 登出行為。v1 logout 入口保留，亦能處理 native 當前 session。

reviewer 的 `GET /v1/devices` 聚合 legacy 裝置與有效 native sessions，同 UUID 列一筆；native-only 名稱為「手機 App」。`DELETE /v1/devices/{device_id}` 在同一 transaction 撤銷該裝置 legacy token 與全部 native sessions，其他裝置及瀏覽器 session 不受影響。裝置重新輸入密碼可另行登入，不能以本機刪 token 代替中央撤銷。

## 原圖與收件

先以既有 `POST /v1/evidence` 逐張上傳原始 binary（JPEG／PNG／WebP；20 MiB 上限），回傳 `sha256` 等 metadata。此端點只保存，絕不啟動模型。既有原圖回讀／hash 契約不變。v1 intake 只收單頁圖片；PDF 仍可透過舊 evidence／draft 契約使用。

`POST /v1/intakes`，Bearer、JSON、`Idempotency-Key`（1..128，`[A-Za-z0-9][A-Za-z0-9._:-]*`）：

```json
{
  "schema":"pixelreceipt-mobile-ingest-1",
  "purpose":"purchase",
  "engine":"broker",
  "user_notes":"第一張收據，第二張是牙膏價標；牙膏幫朋友買。",
  "evidence":[
    {"sha256":"<64 lowercase hex>","role":"receipt","notes":null},
    {"sha256":"<64 lowercase hex>","role":"price_tag","notes":"牙膏價標"}
  ]
}
```

`purpose`=`purchase|reference`；`engine`=`broker|local`；`user_notes` 必填、允許 null，最大 4000 字元；每圖 `notes` 必填、允許 null，最大 1000 字元。`evidence` 有順序、1..20、不重複 hash，必須已保存；role=`receipt|price_tag|promotion|menu|other`。`purchase` 至少一張 receipt，否則 422；只有菜單／促銷應選 reference。每個 intake 代表一筆交易，不同交易由 APK 先分組。不可變快照保存原圖 hash／順序／角色／備註／metadata、整批說明、來源引擎與版本。`source.label`／`review_note` 不替代輸入說明。

新建 201、同 key 同 payload 重送 200、同 key 換內容 409。APK **送出前持久保存原 key、完整 payload、上傳 hash**；timeout／408／5xx 或不確定寫入結果沿原 key／payload 重送，不產生新 key。回應為完整 Intake DTO：

```json
{
  "id":"<UUID>","schema":"pixelreceipt-mobile-ingest-1",
  "input":{"schema":"pixelreceipt-mobile-ingest-1","purpose":"purchase","engine":"broker","user_notes":null,"evidence":[]},
  "evidence_snapshot":[],"status":"queued","error_code":null,
  "job_key":"<server generated opaque original key>","draft_id":null,
  "draft_status":null,"ledger_id":null,"ledger_status":null,
  "reference_candidate":null,"result":null,
  "created_at":"<ISO UTC>","updated_at":"<ISO UTC>","polling_finished":false
}
```

`input.evidence` 與 snapshot 在實際回應為完整已驗證列表；上述空列表僅省略示意。`result` 完成後保存模型原文、provider/model、finish_reason、response_truncated、adapter/prompt 版本與衍生圖來源 hashes。模型輸出只接受純 JSON，或整段恰為單一 `json` code fence（僅去外層 fence／空白）；前後散文、多段 fence、其他語言標籤均不抽取／修復。保留原文及 candidate_parser 的 version／attempted／json_fence_removed；仍以原嚴格 JSON／Pydantic 驗為既有 `pixelreceipt-central-candidate-1`，金額仍 TWD 整數元；無法判讀保持 null，無自動入帳。

## 查詢、狀態與恢復

`GET /v1/intakes/{id}` 回同完整 DTO。`GET /v1/intakes?limit=20&offset=0` 回 `{"intakes":[...],"limit":20,"offset":0}`（limit 1..100）；列表為 summary：id/schema/input（僅 purpose/engine/user_notes）/status/error_code/job_key/draft_id/draft_status/ledger_id/ledger_status/created_at/updated_at/polling_finished，不載入原圖清單或模型原文；點選後 GET 單筆讀完整內容。APK 以查詢更新進度，離開 App 不取消伺服器工作。`GET /v1/recognition` 回 `{"engines":{"broker":{"available":false,"reason":"recognition_not_configured"},"local":{"available":false,"reason":"recognition_not_configured"}}}`，供送出前顯示。伺服器配置缺少時 intake 保留但 status=unavailable，不能假稱辨識成功。

`GET` detail 與 list 每筆新增可空帳務投影：`draft_status=needs_review|confirmed|voided|null`、`ledger_id=<ID>|null`、`ledger_status=active|voided|null`。資料來自實際關聯 draft 與 ledger 最新事件；沒有 draft 的歷史／reference 收件三欄均為 null。辨識 `status` 語意不變，`succeeded` 不表示已入帳，亦不會因查詢狀態重跑辨識或建立另一筆帳務。

狀態：`queued` 已保存 → `preparing` 建立有資源限制的衍生圖 → `submitting` 原 job key／完整正規化 Broker payload 已先保存 → `processing` Broker 已收件 → `succeeded`；另有 `failed`（明確失敗）、`unavailable`（未配置）、`unknown`（執行結果未知）。unknown 只用原 key 查 status/result，不再 POST、不換 endpoint/provider、不切 4090。重啟從原持久工作恢復；每次最多一個應用辨識工作，Broker 本身序列 admission 保持。查詢持久退避 10／20／40／60 秒封頂、提交後監看最多 15 分鐘；到限將 unknown 保留並標 `polling_finished=true` 待人工核對，原工作絕不重送，其他獨立 intake 可繼續 admission。沒有 APK 自動重新辨識／重試按鈕。

`succeeded` purchase 建立唯一 `needs_review` draft，`draft_id` 提供 Dashboard 深連結 `https://momonong.me/receipts/#draft/<draft_id>`；reference 則只保存 `reference_candidate`，`draft_id=null`，無法確認消費。使用者仍須逐項人工核對用途／折扣／完整性後，透過既有 reviewer 流程確認。上述 v1 ingest token 不能確認；v2 reviewer session 可在 App 內依同一核對／確認契約操作。

## 錯誤與驗收邊界

穩定錯誤 `{"detail":"code"}`，不回顯密碼／token／完整 request。401 認證、403 reviewer required、409 key／狀態、413 body、415 MIME、422 DTO／證據、429 登入 rate limit、503 儲存／檢查忙碌。未認證登入有 8 KiB／body deadline 與共用 rate gate。

本契約的離線 fixture／合成 HTTP 驗證，不等於真 Broker／4090、Android 分享、正式部署或人類帳務接受。每項驗收由原目標對話以同版本證據核對。

## 模型輸入封裝與 APK 下載

兩個固定配置 mode：vision（1文字＋最多20圖片parts）與 document_pdf（1文字＋1真PDF文件part）。目前部署使用 document_pdf；provider/model 由部署配置明確指定。逐張原圖經資源限制子程序產生最長邊1600／最大384KiB JPEG，PDF再逐頁引用JPEG，不同時解碼20張大圖；保留原圖hash、衍生JPEGhash、頁序與PDFhash。這是傳輸封裝，不是APK接收PDF分享。總media8MiB／HTTPbody16MiB／result4MiB取較小實例限制；完整payload先 routes/explain，再用原key queue；不要求所有提供者structured_output。

認證後 `GET /v1/mobile/release` 回 version_code、version_name、package_name、sha256、size_bytes、signer_sha256、download_url=/receipts/v1/mobile/apk。本版已部署 version_code=7、version_name=0.5.0、package_name=com.momonong.pixelreceipt.mobile，沿用永久 signer；後端只接受已確認的固定 code/name 配對。下載時以認證 metadata／APK hash 核對實際版本。`GET /v1/mobile/apk` 為 APK binary attachment，無path參數，未配置404；token不得放URL。檔案只由受控部署提供，後端啟動串流核對SHA／大小、下載串流讀取，不把APK塞進sourcebundle。Dashboard提供登入後下載連結。後續版本需先核對後端 metadata 相容策略與發布通道，見 [部署契約](ASUS_DEPLOYMENT.md)；本版下載資訊見 [發行說明](RELEASE_v0.5.0.md)。
