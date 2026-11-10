# 紅果短劇 TV v0.9.10 更新說明

## 1. 播放解碼與取源穩定性強化（同步上游 6.6 / 6.7 核心修正）

- **解除 `fallback_api` 的 `bytevc2` 強制鎖定（修復第 62 集等特定集數無法解碼問題）**：
  - 上游備援介面（`vas-lf-x.snssdk.com`）在部分集數會附帶 `force_fids` 與 `encode_user_tag=only_roi_vcube_improve_1` 查詢參數，導致無論請求何種 `codec_type` 均被強制鎖定回傳電視硬體不支援的 `bytevc2` 檔案。
  - 現已自動剝離 `force_fids` 與 `encode_user_tag` 鎖定參數，並優先請求 `codec_type=0`（H.264 / AVC）與 `codec_type=3`（HEVC / ByteVC1），確保電視端穩定取得可硬體解碼的 720P / 1080P 串流。
- **新增 `video_tab/multi_video_model/v1/` 簽章 GET 救援通道（同步 `jackotom/hongguo-drama` v6.7 `#133`）**：
  - 當舊版 `POST /novel/player/video_model/v1/` 或 `multi_video_model/v1/` 回傳空回應時，自動切換至 `7.3.9.32` 簽章 `GET /reading/bookapi/video_tab/multi_video_model/v1/` 介面救援。
  - 支援解析巢狀 `data.video_model_data[vid].video_model` 以及物件（Object）或陣列（Array）格式之 `video_list`，並嚴格核對請求集數 ID。

## 2. 官方時間軸彈幕修復與體驗優化（同步上游 6.6 彈幕協定）

- **升級官方分時彈幕簽章協定**：
  - 全面對齊官方客戶端 `POST https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/{vid}/v1/` 介面規格。
  - 補齊專用標頭（`Comment-Source: 601`、`Server-Channel: 1000`）與評論專用 `X-Gorgon`、`X-Argus`（SM3 + 72 輪變換 + AES-128-CBC）、`X-Ladon` 簽章演算法，並隨附 `book_id`（劇集 ID）與時間視窗請求本體。
- **重複彈幕 × N 合併與狀態提示**：
  - 支援解析官方 `data.data_list` 時間軸座標（`expand.offset_time`）與 `common_list_info.cursor` 雙重分頁信號。
  - 播放控制列手動開啟彈幕時，即時顯示「本集已載入 N 條」或「本集暫無彈幕」提示；暫停狀態下開啟彈幕亦可立即預覽當前時間點附近的彈幕。

## 3. 版本與自動更新資訊

- **版本號**：`0.9.10`（`versionCode = 19`）
- **升級方式**：已安裝舊版（`v0.9.08` / `v0.9.09`）之電視端開啟 App 即可透過內建 OTA 無縫升級，或使用 Downloader 輸入固定短碼／連結直接安裝覆蓋。
