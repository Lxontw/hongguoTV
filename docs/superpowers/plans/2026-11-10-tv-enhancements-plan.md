# 宏果tv 四大進階功能實施計畫 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `hongguoTV` (`kotlin-tv`) 分階段實作：(#2) `.heic` 海報轉 `640px` JPEG 防破圖、(#1) 紅果 App 題材分類篩選與「今日上新」專區、(#4) 播放器時間軸彈幕開關與 `× N` 重複合併、以及 (#5) 多平台片源（稀飯 `Xifan` / 河馬 `Hema`）擴充架構。

**Architecture:** 在 `:core` 模組建立無 Android 依賴的資料解析、簽章、URL 正規化與多片源路由邏輯，並透過 JUnit 4 完整覆蓋離線單元測試；在 `:app` 模組擴充 `Library` 偏好設定、`MainActivity` 題材篩選膠囊列與輕量級 `DanmakuOverlayView` 渲染層。所有功能遵循防禦性降級設計，任何外部異常均不影響主播放流程。

**Tech Stack:** Kotlin 1.9, Java 17, OkHttp 4.12, `org.json`, Android Views / Canvas, AndroidX Media3 (ExoPlayer), JUnit 4.

## Global Constraints

- 最低系統需求維持 `minSdk = 26` (Android 8.0)，`targetSdk = 34`。
- 包名固定為 `com.hongguotv.nativeapp`，不得變動正式發佈簽章設定（` EXPECTED_CERT_SHA256 = 99eb0105...`）。
- 嚴格遵守零重型第三方依賴原則，不引入 Glide/Coil 或 WebView。
- 每個階段（Phase）皆可獨立建置、獨立測試與獨立交付。

---

### Task 1: 海報圖片 `.heic` 轉公開 `640px` JPEG 防破圖 (Feature #2)

**Files:**
- Modify: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt:15-20`
- Modify: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/ContentRepositoryTest.kt`

**Interfaces:**
- Produces: `internal fun normalizeCoverUrl(raw: String): String` (in `com.hongguotv.core`)

- [ ] **Step 1: 撰寫 `.heic` 海報網址轉換與防禦邊界的失敗單元測試**

在 `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/ContentRepositoryTest.kt` 新增測試：

```kotlin
    @Test fun heicNovelPicCoverUrlIsNormalizedToPublic640Jpeg() {
        val raw = "https:\\u002F\\u002Fp3-novel.fqnovelpic.com\\u002Fnovel-pic\\u002Fabc123def~tplv-resize:300:0.heic?rk3s=123&amp;x-expires=999"
        assertEquals(
            "https://p3-novel.byteimg.com/novel-pic/abc123def~tplv-shrink:640:0.image",
            normalizeCoverUrl(raw)
        )
    }

    @Test fun nonHeicOrExternalCoverUrlRemainsUnchangedAfterClean() {
        val raw = "https:\\/\\/example.com\\/cover.jpg?a=1&amp;b=2"
        assertEquals("https://example.com/cover.jpg?a=1&b=2", normalizeCoverUrl(raw))
    }
```

- [ ] **Step 2: 執行測試確認編譯失敗（尚未定義 `normalizeCoverUrl`）**

Run: `./kotlin-tv/gradlew -p kotlin-tv :core:test --tests com.hongguotv.core.ContentRepositoryTest.heicNovelPicCoverUrlIsNormalizedToPublic640Jpeg`
Expected: FAIL (`Unresolved reference: normalizeCoverUrl`)

- [ ] **Step 3: 在 `ContentRepository.kt` 實作 `normalizeCoverUrl` 並套用至海報欄位**

在 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt` 新增：

```kotlin
internal fun normalizeCoverUrl(raw: String): String {
    val cleaned = cleanUrl(raw).trim()
    if (cleaned.isEmpty()) return ""
    return runCatching {
        val uri = java.net.URI(cleaned)
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.rawPath.orEmpty()
        if (scheme in setOf("http", "https") &&
            path.endsWith(".heic", ignoreCase = true) &&
            (host == "fqnovelpic.com" || host.endsWith(".fqnovelpic.com") || host == "byteimg.com" || host.endsWith(".byteimg.com")) &&
            path.startsWith("/novel-pic/") &&
            path.contains('~')
        ) {
            val prefix = path.substringBefore('~')
            "https://p3-novel.byteimg.com$prefix~tplv-shrink:640:0.image"
        } else {
            cleaned
        }
    }.getOrDefault(cleaned)
}
```
並將 `ContentRepository.kt` 與 `ComicRank.kt` 中解析 `cover` / `series_cover` / `cover_url` 的 `cleanUrl(...)` 替換為 `normalizeCoverUrl(...)`。

- [ ] **Step 4: 執行單元測試確認通過**

Run: `./kotlin-tv/gradlew -p kotlin-tv :core:test --console=plain`
Expected: `BUILD SUCCESSFUL`

---

### Task 2: 紅果 App 原生題材分類與「今日上新」專區 (Feature #1)

**Files:**
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/AppCategoryCatalog.kt`
- Modify: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt`
- Modify: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/ContentRepositoryTest.kt`
- Modify: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`

**Interfaces:**
- Produces:
  - `data class GenreFilter(val id: String, val label: String)`
  - `CatalogPage.genres: List<GenreFilter>`
  - `ContentRepository.categoryCatalog(type: ContentType, genreId: String = "", page: Int = 1): CatalogPage`

- [ ] **Step 1: 撰寫 App 分類與「今日上新」解析及游標分頁的單元測試**

在 `ContentRepositoryTest.kt` 新增測試，驗證：
1. `selector_rows` 中的 `category_dim_theme` 解析出 `GenreFilter("cate_12", "玄幻")` 等合法標籤。
2. `tag_info.text == "新剧"` 或 `sub_title_list` 含 `"今日上新"` 時，正確識別並於 `genreId == "new_today"` 篩選出新劇。
3. 當 App 分類接口失敗時，`home()` 自動降級回官網 SSR 榜單。

- [ ] **Step 2: 實作 `AppCategoryCatalog.kt` 與 `ContentRepository.categoryCatalog()`**

在 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/AppCategoryCatalog.kt` 實作：
- 封裝 `/reading/distribution/category/landpage/v/` 的請求 Payload 組裝（使用 `version_code = "73532"`，`version_name = "7.3.5.32"`）。
- 解析 `data.video_data` 與 `data.selector_rows`，提取 `GenreFilter` 清單與 `next_offset` / `session_id` 分頁游標。
- 在 `MainActivity.kt` 頂部導覽下方新增可由遙控器左右選擇的「題材膠囊列（全部 / 今日上新 / 各題材）」。

- [ ] **Step 3: 執行 `:core:test` 與 `:app:lintRelease` 驗證**

Run: `./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintRelease --console=plain`
Expected: `BUILD SUCCESSFUL`

---

### Task 3: 播放時「官方時間軸彈幕」與 `× N` 重複合併 (Feature #4)

**Files:**
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuRepository.kt`
- Create: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuRepositoryTest.kt`
- Create: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuOverlayView.kt`
- Modify: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/Library.kt`
- Modify: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`

**Interfaces:**
- Produces:
  - `data class DanmakuItem(val offsetMs: Long, val text: String, val count: Int = 1)` (`val displayText: String get() = if (count > 1) "$text × $count" else text`)
  - `DanmakuRepository.fetch(videoId: String): List<DanmakuItem>`
  - `Library.danmakuEnabled: Boolean` (預設 `false`)

- [ ] **Step 1: 撰寫彈幕解析、髒資料過濾與 `× N` 重複合併的單元測試**

在 `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuRepositoryTest.kt` 測試：

```kotlin
class DanmakuRepositoryTest {
    @Test fun mergesIdenticalNormalizedDanmakuAndFormatsCountSuffix() {
        val rawItems = listOf(
            DanmakuRaw(1200L, "前方高能！"),
            DanmakuRaw(2500L, "前方高能! "),
            DanmakuRaw(4000L, "前方高能"),
            DanmakuRaw(3000L, "太好看了")
        )
        val merged = DanmakuRepository.mergeAndSort(rawItems)
        assertEquals(2, merged.size)
        assertEquals("前方高能！ × 3", merged[0].displayText)
        assertEquals(1200L, merged[0].offsetMs)
        assertEquals("太好看了", merged[1].displayText)
    }
}
```

- [ ] **Step 2: 實作 `DanmakuRepository.kt` 與 `DanmakuOverlayView.kt`**

1. `DanmakuRepository.kt`：正規化彈幕文字（去除空白與標點比對）、合併重複內容為 `× N`、依 `offsetMs` 排序，並限制單集最多 270 筆；任何網路或簽章錯誤一律回傳 `emptyList()`，絕不拋出例外。
2. `Library.kt`：新增 `var danmakuEnabled: Boolean`（預設 `false`）。
3. `DanmakuOverlayView.kt`：3 軌道頂部 25% 區域輕量級 Canvas 繪製，支援 `syncProgress(positionMs: Long, isPlaying: Boolean)` 與 `reset()`。
4. `MainActivity.kt`：在播放器 `controls` 新增 `弹幕：开/关` 切換鈕。

- [ ] **Step 3: 執行單元測試與 Lint 驗證**

Run: `./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintRelease --console=plain`
Expected: `BUILD SUCCESSFUL`

---

### Task 4: 多平台片源擴充架構（稀飯 `Xifan` / 河馬 `Hema`）(Feature #5)

**Files:**
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/MediaSourceProvider.kt`
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/XifanRepository.kt`
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/HemaRepository.kt`
- Create: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/MultiSourceTest.kt`
- Modify: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/ContentRepository.kt`
- Modify: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`

**Interfaces:**
- Produces:
  - `enum class MediaSource(val id: String, val label: String) { HONGGUO("hongguo", "红果"), XIFAN("xifan", "稀饭"), HEMA("hema", "河马") }`
  - 支援帶命名空間的 `seriesId`（`"xifan:{source}:{id}"`、`"hema:{bookId}"`）與既有純數字紅果 ID 並存。

- [ ] **Step 1: 撰寫多片源 ID 路由、HTTPS 媒體網址白名單校驗與鎖定集過濾測試**

在 `MultiSourceTest.kt` 驗證：
1. 純數字 ID `"7704236550736858174"` 路由至紅果；`"xifan:1:999"` 路由至稀飯；`"hema:888"` 路由至河馬。
2. 非 `https://` 或含帳密的媒體網址被安全攔截。
3. 收藏夾與觀看紀錄可正常序列化與反序列化帶冒號的 `series.id`。

- [ ] **Step 2: 實作 `XifanRepository`、`HemaRepository` 與 `ContentRepository` 路由分發**

1. 放寬 `ContentRepository.detail()` 與 `stream()` 的 ID 檢查，若含 `"xifan:"` 或 `"hema:"` 則委派給對應 Provider；紅果分支仍維持嚴格 `Regex("[0-9]{1,30}")`。
2. 在「設置」頁面或頂部提供片源切換選項（預設「红果」）。

- [ ] **Step 3: 執行完整建置與檢驗**

Run: `bash /home/lxon/Documents/Obsidian-Me/hongguoTV/scripts/build-kotlin.sh`
Expected: `BUILD SUCCESSFUL` 並產出通過簽章驗證的 APK。
