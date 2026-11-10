# 宏果tv 四大進階功能技術與安全檢核清單 (Technical & Security Check 1)

> **文件用途**：開發階段之程式碼審查（Code Review）、安全性邊界、記憶體控制與單元測試驗收標準。
> **對應規格**：[`2026-11-10-tv-enhancements-sdd.md`](file:///home/lxon/Documents/Obsidian-Me/hongguoTV/docs/superpowers/specs/2026-11-10-tv-enhancements-sdd.md)
> **對應計畫**：[`2026-11-10-tv-enhancements-plan.md`](file:///home/lxon/Documents/Obsidian-Me/hongguoTV/docs/superpowers/plans/2026-11-10-tv-enhancements-plan.md)

---

## 1. Feature #2：海報 `.heic` 轉 `640px` JPEG 檢核項

- [ ] **網域白名單驗證（Domain Guard）**：
  - 僅對 `fqnovelpic.com`、`*.fqnovelpic.com`、`byteimg.com`、`*.byteimg.com` 網域下且路徑以 `/novel-pic/` 開頭的 `.heic` 圖片進行重寫。
  - 禁止將其他外部網域或非 `/novel-pic/` 路徑強制改寫至 `p3-novel.byteimg.com`，避免破圖。
- [ ] **跳脫字元先行清理**：
  - 必須先經過 `cleanUrl()` 將 `\u002F`、`\/`、`&amp;` 還原為標準字元後再解析 `URI`，防止因跳脫斜線導致 `URI.rawPath` 解析失敗。
- [ ] **放棄舊 HEIC 過期簽章參數**：
  - 轉換為 `~tplv-shrink:640:0.image` 時，必須捨棄原 `.heic` URL 後方的 `?rk3s=...&x-expires=...` 簽章查詢字串（公開 JPEG 變體不需簽章，保留舊簽章反而會因參數不匹配回傳 403）。
- [ ] **單元測試覆蓋**：
  - 涵蓋含 Unicode 跳脫的 `.heic` URL、大寫 `.HEIC`、一般 `.jpg`/`.png` URL、空字串與非法 URL。

---

## 2. Feature #1：題材分類篩選與「今日上新」檢核項

- [ ] **簽章與版本號隔離**：
  - `/reading/distribution/category/landpage/v/` 請求必須使用 `version_code = "73532"`、`version_name = "7.3.5.32"`，不得汙染播放接口 (`71332`) 或搜尋接口 (`72232`) 的設備參數。
- [ ] **題材 Slug 格式防禦**：
  - 從上游 `selector_rows` 解析出的題材 ID 必須符合 `^cate_\d{1,8}$` 正規表示式，丟棄空白名稱或重複 ID。
- [ ] **分頁游標（Cursor）嚴格檢查**：
  - 當 `has_more == true` 時，`next_offset` 必須大於當前 `offset`，且 `session_id` 長度介於 `1..4096`；若上游回傳重複 offset，立即停止翻頁（`hasMore = false`），防止電視往下捲動時陷入無限重複卡片。
- [ ] **無縫降級（Fallback）**：
  - 當 App 分類接口回傳空字串、非 0 狀態碼或網路逾時，必須自動切換回既有官網 SSR 榜單（`comicRanking` / `router`），不得讓首頁白屏。

---

## 3. Feature #4：官方時間軸彈幕與 `× N` 合併檢核項

- [ ] **預設關閉與狀態持久化**：
  - `Library.danmakuEnabled` 初次安裝或升級後預設必須為 `false`，確保不習慣彈幕的長輩或家人開啟即享乾淨畫面。
- [ ] **記憶體與網路負載上限（DoS 防護）**：
  - 單集彈幕請求最多拉取 **3 頁（上限 270 筆）**，單次 HTTP 回應大小上限 `2 MB`，逾時上限 `8 秒`。
  - 遇到連續 2 個空頁（即使上游回傳 `has_more = true`）立即停止翻頁。
  - 單筆彈幕文字長度超過 `100` 字元或 `offset_time < 0` 或 `offset_time > 86_400_000`（24 小時）直接過濾丟棄。
- [ ] **重複彈幕合併（`× N`）精確性**：
  - 比對前須先正規化字串（NFKC、轉小寫、移除空白與常見標點符號 `！!？?。，,~～`），例如 `"前方高能！"` 與 `"前方高能!! "` 應合併為 `"前方高能！ × 2"`，並保留最早出現的 `offsetMs`。
- [ ] **錯誤完全靜默（Non-blocking）**：
  - 彈幕屬於附加體驗，任何 HTTP 錯誤、JSON 解析錯誤或簽章失效，一律捕捉並回傳空清單 `emptyList()`，**嚴格禁止呼叫 `playerFailure()` 或彈出 Toast 干擾觀影**。

---

## 4. Feature #5：多平台片源擴充（`Xifan` / `Hema`）檢核項

- [ ] **ID 命名空間向下相容**：
  - 紅果劇集 ID 維持純數字 `^[0-9]{1,30}$`，既有 `native-library-v1` 的收藏、待看、觀看進度與已看標記完全不需遷移即可讀取。
  - 稀飯 ID 嚴格驗證 `^xifan:[a-zA-Z0-9_-]{1,64}:[a-zA-Z0-9_-]{1,128}$`。
  - 河馬 ID 嚴格驗證 `^hema:[0-9]{1,30}$`。
- [ ] **媒體串流地址安全守衛（Media URL Guard）**：
  - 外部片源解析出的播放地址必須為 `https://`，嚴禁 `http://` 明文、嚴禁包含 `username/password`、嚴禁重導向至內網 IP（`127.0.0.1`、`192.168.*`、`10.*`）。
- [ ] **付費鎖定集過濾**：
  - 對於外部片源標記為未解鎖 / 付費的集數，明確提示「本集為來源鎖定集」，不抓取試看預覽流。

---

## 5. 自動化建置與靜態檢查指令

在提交任何程式碼前，必須依序通過以下檢查且零錯誤：

```bash
# 1. 執行核心模組全量單元測試
./kotlin-tv/gradlew -p kotlin-tv :core:test --rerun-tasks --console=plain

# 2. 執行 Android 正式版 Lint 靜態分析
./kotlin-tv/gradlew -p kotlin-tv :app:lintRelease --console=plain

# 3. 建置正式簽章 APK 並驗證憑證指紋
bash scripts/build-kotlin.sh
python3 tools/publish-release.py --apk dist/HongguoTV.apk --notes docs/RELEASE_0.9.08.md
```
