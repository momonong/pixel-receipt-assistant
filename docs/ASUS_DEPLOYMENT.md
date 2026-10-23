# 中央服務部署與備份契約

PixelReceipt 0.5.0 的中央後端與認證 APK 下載已部署至 ASUS；本版狀態見 [發行說明](RELEASE_v0.5.0.md)。本文件保留可重用的部署、資料保護與還原契約，不包含現場私有識別、操作紀錄或主機專用發布腳本。

## 程式、資料與入口

- 應用以專屬服務帳號執行單一 Uvicorn worker，僅綁 loopback；SQLite 與原圖採單一寫入來源，不能同時啟用兩份帳本副本。
- 程式採經審閱、不可變的 release，服務帳號不可寫。資料、認證及原圖放在獨立 owned `0700` 目錄，檔案 `0600`，不放 release、Git、static root 或共享目錄。
- 公開入口使用 HTTPS 與明確反向代理 prefix；代理 strip `/receipts`，ASGI 設 `--root-path /receipts`，`--public-origin` 對應公開 Origin。Cookie Path、靜態資源與 redirect 必須一同核對。
- 若採跨機轉送，使用專屬受限 SSH identity，只允許固定 loopback 目的地，禁止 shell／PTY／agent／任意目的地，核對 host key，不關閉驗證。
- CPU、記憶體、程序數與文件檢查限制依目標主機容量及負載核定；模板數值不是當下可用容量或效能保證。不要以停掉其他服務或刪除資料換取部署成功。

公開模板為 [應用 service](../deploy/pixelreceipt.service)、[轉送 service](../deploy/pixelreceipt-forward.service)、[SSH 範例](../deploy/ssh_config.example) 與 [Caddy 範例](../deploy/receipts.caddy.example)。它們不會自動安裝；hostname、port、identity、owner、runtime 與資源限制須依目標環境核對。各主機依 `uv.lock` 建自己的 runtime，不複製開發機 `.venv`，服務啟動時不下載依賴。

## 認證、辨識與 APK

認證角色與 API 見 [後端文件](../backend/README.md) 及 [手機契約](MOBILE_INGEST_CONTRACT.md)。ingest 憑證只能收件與讀取，不能確認或作廢。原生 v2 reviewer session 有獨立 Bearer 與到期／撤銷政策；瀏覽器保留 Secure／HttpOnly／SameSite cookie、Origin 及 CSRF 核對。

正式服務不能使用 `--insecure-local-cookie`。密碼 verifier、token、SSH 私鑰及 provider 憑證只從私有檔案或既定 credential 機制取得，不放 argv、URL、Git 或日誌。首次初始化使用後端 `init` 與互動 `configure-reviewer`；後續發布不可重跑初始化或覆寫現有認證。

辨識為持久化背景工作，原圖與完整 payload 先保存，結果未知時沿原 key 查詢，不換來源重送。文件檢查與衍生圖處理有 admission、deadline 與子程序資源限制；這是資源隔離，不是完整檔案系統／網路 sandbox 或惡意文件安全保證。

APK 與程式 source 分開交付。APK metadata 固定 package、code/name、大小、SHA-256 與 signer；先以簽章工具核對，再提供認證下載。後端的 hash 檢查不代替簽章工具，也不代表手機已安裝。下載認證不得把 token 放到 URL。

## 備份、驗證與新目錄還原

[receipt_backup.py](../deploy/receipt_backup.py) 是 Python 3.12+ 標準庫本機工具，不連線、不管理服務、不刪資料或改既有路徑權限。以資料 owner 執行；來源及目的父目錄須為 owned `0700`、非 symlink，目的目錄必須不存在。

以下路徑均為佔位符，操作前替換為受控私有位置與唯一快照名稱：

```bash
python3 deploy/receipt_backup.py snapshot \
  --source /absolute/private-data \
  --destination /absolute/private-backups/snapshot-unique
python3 deploy/receipt_backup.py verify \
  --source /absolute/private-backups/snapshot-unique
python3 deploy/receipt_backup.py restore \
  --source /absolute/private-backups/snapshot-unique \
  --destination /absolute/private-restore/restore-unique
```

- SQLite backup API 保存包含已提交 WAL 的一致快照，再複製快照引用的 immutable blobs，逐筆核對 SHA-256／大小；不在服務運作時只複製主 DB。
- `integrity_check`、`foreign_key_check`、支援的 `user_version`（1–4）、DB checksum 及 evidence inventory 均須通過。草稿、帳本、事件、價格歷史、收件與冪等紀錄保留。
- 備份依賴原圖不改寫、沒有 GC 的契約；未引用 blob 留在來源，不自動刪除。新增 GC 或原圖改寫前須先調整備份協調。
- 備份包含私人資料及認證檔，並非加密檔；僅放私有儲存，搬到異機或雲端前另定加密、金鑰與保留政策。備份期間不輪替認證檔，manifest 不包含秘密內容或認證 checksum。
- 失敗保留已建立的目錄與 `INCOMPLETE` marker，不啟用、不自動清理或 fallback。
- 還原先驗證快照，只寫全新 private dir，清空 `sessions` 與 `device_sessions`，使備份前手機及瀏覽器登入失效。它不自動輪替 API token／密碼。
- 工具不切換 live dir、不改 owner／unit、不啟動 app。先用隔離資料與 loopback 驗證，再停止專屬寫入者、保留舊資料與相容 release，核對 owner／權限後才切換。

快照頻率、保留份數與異機備份須依實際容量決定；本文件不建立 timer、排程或自動刪除政策。

## 發布驗證與回復

發布前核對當前程式、資料版本、未完成工作、同版本測試、可用備份與受影響服務。checksum 證明 bytes 一致，不能代替程式審查、業務測試或人類接受。

發布後核對 HTTPS 全鏈路、prefix、匿名拒絕、ingest 限權、reviewer 登入／登出／撤銷及原 bytes／hash。用合成資料核對原 key 恢復、revision 衝突、人工確認及已完成／作廢狀態；不以 `systemd active` 代表帳務正確，也不操作私人帳目代替驗收。

程式回復只選與目前資料及 APK 相容的 release，保留最新資料。schema 不相容時走明確遷移或新目錄還原，不盲目 downgrade、覆寫資料或回復整機。共享代理若已有其他變更，停止對帳，不用歷史全檔覆蓋。

程式發布、正式 HTTPS、備份還原演練、APK 下載、手機安裝與人工帳務接受分別記錄。主機專用發布流程與私人操作收據不包含在公開 repository。
