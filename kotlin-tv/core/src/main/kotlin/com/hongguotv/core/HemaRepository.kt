// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class HemaRepository(private val http: OkHttpClient) {
    companion object {
        const val SITE_BASE = "https://www.kuaikaw.cn"
        private val numericId = Regex("^[0-9]{1,30}$")
    }

    private val streamUrlCache = ConcurrentHashMap<String, String>()

    private fun fetchNextData(path: String): JSONObject {
        val req = Request.Builder()
            .url(SITE_BASE + path)
            .header("User-Agent", VendorConstants.WEB_UA)
            .build()
        val html = RequestScope.execute(http.newCall(req)) { response ->
            if (!response.isSuccessful) throw IOException("河马片源请求失败（HTTP ${response.code}）")
            response.body?.string() ?: throw IOException("河马片源响应为空")
        }
        val marker = html.indexOf("id=\"__NEXT_DATA__\"")
        if (marker < 0) throw IOException("河马页面缺少 __NEXT_DATA__")
        val openBrace = html.indexOf('{', marker)
        val closeScript = html.indexOf("</script>", openBrace)
        if (openBrace < 0 || closeScript <= openBrace) throw IOException("河马页面数据格式异常")
        val jsonText = html.substring(openBrace, closeScript).trim()
        return JSONObject(jsonText).optJSONObject("props")?.optJSONObject("pageProps")
            ?: throw IOException("河马页面缺少 pageProps")
    }

    private fun parseBook(obj: JSONObject): Series? {
        val bookId = obj.str("bookId").ifEmpty { obj.str("book_id").ifEmpty { obj.str("id") } }
        if (!numericId.matches(bookId)) return null
        val title = obj.str("bookName").ifEmpty { obj.str("title").ifEmpty { obj.str("name") } }.trim()
        if (title.isEmpty()) return null
        val cover = normalizeCoverUrl(obj.str("coverWap").ifEmpty { obj.str("cover").ifEmpty { obj.str("bookPic") } })
        val desc = obj.str("introduction").ifEmpty { obj.str("desc") }
        val total = obj.optInt("totalChapterNum", obj.optInt("chapterCount", 0))
        val badge = if (total > 0) "全 $total 集" else "河马剧场"
        val tagsArr = obj.optJSONArray("categoryList") ?: obj.optJSONArray("tags")
        val tags = if (tagsArr != null) {
            (0 until minOf(tagsArr.length(), 4)).mapNotNull { i ->
                tagsArr.optJSONObject(i)?.str("name")?.takeIf(String::isNotBlank) ?: tagsArr.optString(i).takeIf(String::isNotBlank)
            }.joinToString(" · ")
        } else ""
        return Series(
            id = "hema:$bookId",
            title = title,
            cover = cover,
            description = desc,
            badge = badge,
            tags = if (tags.isNotBlank()) "河马 · $tags" else "河马剧场"
        )
    }

    fun home(page: Int): CatalogPage {
        val pageProps = fetchNextData("/browse/0/1/$page")
        val list = pageProps.optJSONArray("bookList")
            ?: pageProps.optJSONObject("seoData")?.optJSONArray("bookList")
            ?: pageProps.optJSONArray("list")
            ?: JSONArray()
        val items = list.objects().mapNotNull(::parseBook).distinctBy { it.id }
        val totalPages = pageProps.optInt("totalPage", pageProps.optInt("pages", 20))
        return CatalogPage(
            items = items,
            hasMore = items.isNotEmpty() && page < minOf(totalPages, 50),
            genres = listOf(GenreFilter("", "全部推荐"))
        )
    }

    fun search(keyword: String, page: Int): CatalogPage {
        val encoded = Signer.encode(keyword)
        val pageProps = fetchNextData("/search?searchValue=$encoded&page=$page")
        val list = pageProps.optJSONArray("bookList")
            ?: pageProps.optJSONArray("searchList")
            ?: pageProps.optJSONArray("list")
            ?: JSONArray()
        val items = list.objects().mapNotNull(::parseBook).distinctBy { it.id }
        return CatalogPage(items = items, hasMore = items.size >= 10 && page < 20)
    }

    fun detail(seriesId: String): Detail {
        val parts = seriesId.split(':')
        require(parts.size == 2 && parts[0] == "hema" && numericId.matches(parts[1])) { "河马剧集标识无效" }
        val bookId = parts[1]
        val pageProps = fetchNextData("/drama/$bookId")
        val bookInfo = pageProps.optJSONObject("bookInfo") ?: pageProps
        val series = parseBook(bookInfo.put("bookId", bookId))
            ?: Series(id = seriesId, title = bookInfo.str("bookName").ifEmpty { "河马剧集" })
        val chapters = pageProps.optJSONArray("chapterList")
            ?: bookInfo.optJSONArray("chapterList")
            ?: throw IOException("河马分集暂不可用")
        val episodes = mutableListOf<String>()
        for (ch in chapters.objects()) {
            val chapterId = ch.str("chapterId").ifEmpty { ch.str("id") }
            if (!numericId.matches(chapterId)) continue
            val isFree = !ch.optBoolean("isLock", false) && ch.optInt("isPay", 0) == 0 && ch.optInt("unlockStatus", 1) != 0
            val vodUrl = ch.optJSONObject("chapterVideoVo")?.str("vodMp4Url")
                .orEmpty()
                .ifEmpty { ch.str("vodMp4Url").ifEmpty { ch.str("mp4Url").ifEmpty { ch.str("videoUrl") } } }
            if (!isFree && vodUrl.isBlank()) continue
            val fullEpId = "hema:$bookId:$chapterId"
            if (vodUrl.isNotBlank()) {
                runCatching { MediaUrlGuard.assertSafeMediaUrl(vodUrl) }.getOrNull()?.let { safe ->
                    streamUrlCache[fullEpId] = safe
                }
            }
            if (fullEpId !in episodes) episodes += fullEpId
        }
        if (episodes.isEmpty()) throw IOException("该剧集暂无免费可播分集")
        return Detail(series.copy(id = seriesId), episodes)
    }

    fun stream(episodeId: String): StreamInfo {
        val parts = episodeId.split(':')
        require(parts.size == 3 && parts[0] == "hema" && numericId.matches(parts[1]) && numericId.matches(parts[2])) {
            "河马分集标识无效"
        }
        val cached = streamUrlCache[episodeId]
        if (cached != null) {
            return StreamInfo(url = MediaUrlGuard.assertSafeMediaUrl(cached), key = null, quality = "高清")
        }
        val bookId = parts[1]
        val chapterId = parts[2]
        val pageProps = fetchNextData("/episode/$bookId/$chapterId")
        val chapterInfo = pageProps.optJSONObject("chapterInfo") ?: pageProps
        val rawUrl = chapterInfo.optJSONObject("chapterVideoVo")?.str("vodMp4Url")
            .orEmpty()
            .ifEmpty { chapterInfo.str("vodMp4Url").ifEmpty { chapterInfo.str("mp4Url").ifEmpty { chapterInfo.str("videoUrl") } } }
        if (rawUrl.isEmpty()) throw IOException("该分集为付费锁定分集或未提供播放地址")
        val safeUrl = MediaUrlGuard.assertSafeMediaUrl(rawUrl)
        streamUrlCache[episodeId] = safeUrl
        return StreamInfo(url = safeUrl, key = null, quality = "高清")
    }
}
