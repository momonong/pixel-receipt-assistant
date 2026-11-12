# 收據管理與支出分析實作計畫

目標與固定介面見 [設計](2026-11-12-receipt-insights-design.md)。基底 c7305d3；分支 feat/receipt-insights。使用者已要求開始開發，沿用本次授權；先完成可檢查候選與工程證據，不發布新版本。

## 任務 1：收據狀態與篩選

- [x] 以真正 MobileBatch／ReceiptMutation 建立狀態優先序與預設可見性回歸，先見失敗。
- [x] 新增 MobileReceiptFilter.kt：固定狀態／配色、服務時間判定、預設／全部／需處理／單一篩選，不改原資料。
- [x] 新增 MobileReceiptFilterSheet.kt，整合 MobileContent 收據清單及所有狀態標籤；保留導航與捲動。
- [x] 驗證已刪除可找回檢視、未知刪除不假成功、不同狀態顏色及對比、旋轉與空清單。

## 任務 2：完整範圍支出資料

- [x] backend/tests/test_spending.py 先測 scope、代買、送禮、折扣、日期／缺日期、店家身份、跨頁、比較、溢位與登入。
- [x] 新增 backend/src/pixelreceipt_backend/spending.py，AnalysisStore/API 串接上述 /v1/spending 與 ledger 篩選；單一讀取快照、無 DB migration 或推論。
- [x] 補 backend/README.md 與分析契約，跑受影響後端與既有分析／API測試。

## 任務 3：原生分析與互動

- [x] 新增 MobileSpendingModels.kt 的嚴格解析／期間／選取測試與 MobileSpendingController.kt 競態、過期、離線案例，先紅再綠。
- [x] MobileApi／Repository 新增同源唯讀請求；獨立私有 AtomicFile 快取，保留原 MobileStore／帳本快照格式。
- [x] MobileViewModel／MobileScreen／MobileAccountingBar 整合目的地、登入切換、返回及帳目異動過期。
- [x] 新增 MobileSpendingScreen.kt／MobileSpendingChart.kt：主支出、期間、趨勢、店家、用途與對應帳目，手機／折疊自適應。
- [x] 真實 Compose 互動測試與多尺寸畫面，反覆檢視並修正最明顯問題。

## 任務 4：整體交付

- [x] 更新使用說明；核對舊資料讀取與請求身分、未知結果及資料保留。
- [x] 執行 ./gradlew testDebugUnitTest lintDebug assembleDebug、完整受影響後端與 Web 檢查。
- [x] 獨立整體審查、必要修正與同版證據；保存候選、實際畫面、限制與未部署狀態。

## 交付核對

- 以 0.6.0／code 13 保存本機候選，沒有新版本合併、推送、正式部署或手機操作。
- 後端 422 tests 已通過；舊手機／服務資料未讀寫，沒有 provider 呼叫。
- 獨立審查 3 Important／1 Minor：負差額、旋轉捲動、離線未讀明細、待確認徽章；以 4 項實際 RED 及針對 GREEN 33 tests 對應，原始審查保留。
- 最終 Android gate、APK 簽章與來源雜湊保存於本次 receipt-insights 工程證據；只有 gate 完成且來源相符後才封存候選。
- 真人手機、TalkBack、實體折疊鉸鏈與真實帳務接受，屬候選發布後的實機驗收，不能由合成 Compose 畫面代替。
