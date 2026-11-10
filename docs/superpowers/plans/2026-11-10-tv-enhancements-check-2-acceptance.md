# 宏果tv 四大進階功能實機遙控器與發布驗收清單 (TV UX & Release Check 2)

> **文件用途**：Android TV 實機（`192.168.1.151:5555`）遙控器方向鍵（D-pad）操作流暢度、視覺排版與遠端 OTA 升級驗收標準。
> **對應規格**：[`2026-11-10-tv-enhancements-sdd.md`](file:///home/lxon/Documents/Obsidian-Me/hongguoTV/docs/superpowers/specs/2026-11-10-tv-enhancements-sdd.md)

---

## 1. 電視遙控器（D-Pad）焦點導航驗收

- [ ] **題材分類膠囊列導航（Feature #1）**：
  - 在「首頁」從頂部導覽列按 `↓`，焦點能穩定落入「題材篩選列」（全部推荐 / 今日上新 / 玄幻 / 修仙 / 末世…）。
  - 在題材篩選列按 `←` / `→` 可平順切換題材並自動水平捲動至畫面中央，不會跑出螢幕外。
  - 按 `確認（OK）` 切換題材後，海報網格重置回第 1 頁，且焦點保持在當前選中的題材按鈕上，不會迷失焦點（Focus Loss）。
  - 從第一排海報網格按 `↑` 可回到當前選中的題材按鈕，再按 `↑` 回到頂部導覽列。
- [ ] **播放器彈幕開關與渲染（Feature #4）**：
  - 播放影片時按遙控器 `↑` 呼出控制列，可透過 `←` / `→` 移至「`弹幕：关`」按鈕。
  - 按 `確認（OK）` 切換為「`弹幕：开`」後，畫面頂部 25% 區域開始飄過半透明描邊彈幕，且重複刷屏的彈幕以 `內容 × N` 合併顯示。
  - 彈幕開啟狀態下：
    - 按 `確認（OK）` 暫停影片，畫面上的彈幕立即原地暫停；恢復播放後繼續移動。
    - 按 `←` / `→` 快退或快進，彈幕立即清空並對齊新的播放時間點，不會出現整坨彈幕瞬間湧出的「彈幕雪崩」現象。
    - 無縫連播（Seamless Next）切換至下一集時，自動載入下一集的彈幕時間軸。
    - 繪製過程無掉幀，不遮擋影片下方台詞字幕。
- [ ] **海報載入與記憶體表現（Feature #2）**：
  - 連續在「今日上新」與「搜尋結果」快速向下翻頁 5 頁以上，原本 `.heic` 格式的新劇封面皆能正常顯示為清晰的 JPEG 海報，無黑框破圖。
  - 透過 `adb shell dumpsys meminfo com.hongguotv.nativeapp` 檢查，Graphics / Java Heap 穩定受控於 `ArtworkLoader` 快取上限內。
- [ ] **多平台片源切換與收藏混合展示（Feature #5）**：
  - 切換至「稀飯」或「河馬」片源時，可正常瀏覽分類、搜尋、進入詳情頁並播放分集。
  - 將外部片源劇集加入「收藏」並觀看數秒後，切回「紅果」主源：在「收藏」與「最近觀看」頁面中，紅果劇集與外部片源劇集皆可正常顯示並點擊續播。

---

## 2. 遠端家人無縫更新（OTA）與 Downloader 發布驗收

每次發布新版前，必須完成以下最終交付核對：

- [ ] **1. 簽章憑證指紋一致性驗證**：
  - 執行 `python3 tools/publish-release.py --apk dist/HongguoTV.apk --notes docs/RELEASE_<version>.md`。
  - 確認輸出顯示：
    `Signer SHA-256: 99eb01056b352a8ee5ee9cc15cd31ad9cec8ade44bf2b28f58632eaec5805490 (VERIFIED)`
- [ ] **2. 版本號遞增檢查**：
  - `versionCode` 必須嚴格大於上一版（例如 `17` -> `18`），`versionName` 對應更新（例如 `0.9.09`）。
- [ ] **3. GitHub Release 四大構件完整性**：
  - 確認最新 Release 包含：
    1. `HongguoTV.apk`（供固定連結下載）
    2. `hongguotv-kotlin-<version>-android8.apk`（供 App 內 OTA 更新器校驗下載）
    3. `update.json`（含正確 `versionCode`、`size`、`sha256`、`notes`）
    4. `SHA256SUMS`
- [ ] **4. Downloader 固定入口同步**：
  - 確認 `v0.9.0` Release 下的 `HongguoTV.apk` 亦已由腳本自動覆寫為最新版，確保家人無論輸入 `/releases/latest/download/HongguoTV.apk` 或 `/releases/download/v0.9.0/HongguoTV.apk` 都能載到最新版。
