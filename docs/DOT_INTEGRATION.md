# Ingest CLI 與手動候選匯入

本文件沿用歷史名稱 DOT_INTEGRATION；目前手機主流程為 [APK 收件與原生核對](MOBILE_APP.md)，不依賴 dot 或 Codex 對話交接。這裡只保留 ingest HTTP CLI 的用途與安全邊界，不包含私人對話、附件或操作紀錄。

## 範圍

[scripts/receipt_api.py](../scripts/receipt_api.py) 是 Python 3.12+ 標準庫、POSIX state lock 的 ingest-only client，提供 `health`、`upload`、`create-draft`、`get-draft`、`list-drafts`。它不提供 review／confirm／void，也不接受 reviewer 憑證參數；後端另行執行角色限制。

CLI 適合把已取得的原檔與可核對候選保存到中央服務。它不會自動辨識圖片、不代表 dot 可轉交原始附件或喚醒任何對話，也不會自動傳訊或代替人類確認帳務。

## 連線與憑證

公開連線必須 HTTPS；HTTP 只接受 literal loopback IP，不接受 LAN HTTP 或 `localhost` DNS 別名。請求使用 `PixelReceipt-Ingest/1.0` User-Agent，拒絕全部 redirect，不轉送 bearer、不自行換 URL、不使用系統 proxy 或自動重試。

token 只由 `PIXELRECEIPT_INGEST_TOKEN` 或 owned `0600` 私有 token file 取得，同時配置會拒絕。可用 `--token-file` 或 `PIXELRECEIPT_INGEST_TOKEN_FILE` 指定檔案位置；秘密值不放 argv、URL、Git、報告或輸出。

以下在 repository 根目錄執行。路徑與草稿 ID 是佔位符，須換成當地私有位置及 API 回傳值：

```bash
export PIXELRECEIPT_API_URL='https://momonong.me/receipts'
export PIXELRECEIPT_INGEST_TOKEN_FILE='/absolute/private/ingest-token'
python3 scripts/receipt_api.py health
python3 scripts/receipt_api.py upload --file '/absolute/private/original.jpg'
python3 scripts/receipt_api.py create-draft \
  --body '/absolute/private/create-draft.json' \
  --request-state '/absolute/private/client-state/receipt.json'
python3 scripts/receipt_api.py get-draft 'DRAFT_ID_FROM_API'
python3 scripts/receipt_api.py list-drafts --limit 20 --offset 0
```

request-state 目錄須 owned `0700`，state／lock 檔 owned `0600`，拒絕 symlink；CLI 不替既有路徑改權限。成功輸出含候選資料，應視為私人內容；錯誤只輸出固定代碼與 HTTP 狀態，不回顯伺服器 error body 或 redirect Location。

## 原檔、候選與重試

1. 取得實際原檔後上傳，核對 API 回傳的 SHA-256／大小／頁數，填入 [CreateDraft schema](../backend/create-draft.schema.json) 的 evidence。上傳只保存原 bytes，不啟動模型。
2. 候選依 [後端契約](../backend/README.md) 產生，`source.kind=ai_candidate` 的 provider／model 只記實際可證實來源。TWD 金額為整數元，未知用 null，零與未知不同；AI 不能填入最終用途或帳本值。
3. 每筆人類定義的單一交易固定一個 request-state。送出前保存 URL、canonical body SHA 與 Idempotency-Key 並 fsync；同 state 重跑沿用同 key，不保存 bearer。
4. timeout、斷線或程序中斷表示寫入結果未知。保留原 body／state，重跑完全相同命令；不刪 state、換 key、改 endpoint 或另建交易。
5. 同 key 改內容回409；CLI 在 URL／body 與 state 不符時先拒絕。已建立草稿要修正，須由 reviewer 取得現行 revision 後人工核對，不能用新 state 假裝修改既有交易。

同檔原 bytes 以 SHA 去重，不會自行合併不同交易。菜單、價牌與促銷只能當參考證據，不直接當已發生的消費；手機 `reference` 收件的明確分流見 [手機契約](MOBILE_INGEST_CONTRACT.md)。

## 狀態與限制

POSIX flock 防止同一 state 同時送出，程序結束鎖自動釋放；殘留 lock 檔不代表仍在執行。CLI 沒有自動建立下一筆或背景輪詢。

exit code：0成功、2本機輸入／state錯誤、3 HTTP錯誤／redirect拒絕、4寫入結果未知、5唯讀傳輸／回應錯誤。任何失敗保留原 key。

CLI／後端合成測試、正式 HTTPS、原始附件交接、辨識品質與真人帳務接受是不同證據。當前發行驗證見 [0.5.0 發行說明](RELEASE_v0.5.0.md)；本文件不宣稱任何第三方對話或附件通道已可用。
