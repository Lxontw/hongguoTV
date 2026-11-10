// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class XifanRepository(private val http: OkHttpClient) {
    companion object {
        const val API_BASE = "https://api-duanju.cenguigui.cn"
        private val safeToken = Regex("^[a-zA-Z0-9_-]{1,96}$")
        private val safeSource = Regex("^[a-zA-Z0-9_-]{1,32}$")
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", VendorConstants.WEB_UA)
            .build()
        return RequestScope.execute(http.newCall(req)) { response ->
            if (!response.isSuccessful) throw IOException("稀饭片源请求失败（HTTP ${response.code}）")
            val body = response.body?.string()?.trim() ?: throw IOException("稀饭片源响应为空")
            if (!body.startsWith("{")) throw IOException("稀饭片源响应格式无效")
            JSONObject(body)
        }
    }

    private fun parseSeries(obj: JSONObject): Series? {
        val rawId = obj.str("duanju_id").ifEmpty { obj.str("book_id").ifEmpty { obj.str("id") } }
        val source = obj.str("source").ifEmpty { "1" }
        if (!safeSource.matches(source) || !safeToken.matches(rawId)) return null
        val title = obj.str("title").ifEmpty { obj.str("name") }.trim()
        if (title.isEmpty()) return null
        val cover = normalizeCoverUrl(obj.str("cover").ifEmpty { obj.str("pic") })
        val desc = obj.str("desc").ifEmpty { obj.str("intro") }
        val epCnt = obj.optInt("total_num", obj.optInt("episode_cnt", 0))
        val badge = obj.str("remarks").ifEmpty { if (epCnt > 0) "全 $epCnt 集" else "稀饭剧场" }
        val tags = obj.str("type_name").ifEmpty { obj.str("category") }
        return Series(
            id = "xifan:$source:$rawId",
            title = title,
            cover = cover,
            description = desc,
            badge = badge,
            tags = if (tags.isNotBlank()) "稀饭 · $tags" else "稀饭片源"
        )
    }

    private fun extractList(root: JSONObject): JSONArray {
        root.optJSONArray("data")?.let { return it }
        val dataObj = root.optJSONObject("data") ?: return JSONArray()
        return dataObj.optJSONArray("list")
            ?: dataObj.optJSONArray("data")
            ?: dataObj.optJSONArray("items")
            ?: JSONArray()
    }

    fun home(page: Int, genreId: String = ""): CatalogPage {
        val offset = ((page - 1).coerceAtLeast(0)) * 20
        val url = if (genreId == AppCategoryCatalog.NEW_TODAY_ID) {
            "$API_BASE/api/duanju/api.php?act=latest&offset=$offset"
        } else {
            "$API_BASE/api/duanju/api.php?act=recommend&offset=$offset"
        }
        val root = getJson(url)
        val arr = extractList(root)
        val items = arr.objects().mapNotNull(::parseSeries).distinctBy { it.id }
        return CatalogPage(
            items = items,
            hasMore = items.size >= 10 && page < 50,
            genres = listOf(
                GenreFilter("", "全部推荐"),
                GenreFilter(AppCategoryCatalog.NEW_TODAY_ID, "今日上新")
            )
        )
    }

    fun search(keyword: String, page: Int): CatalogPage {
        val encoded = Signer.encode(keyword)
        val url = "$API_BASE/api/duanju/search.php?name=$encoded&page=$page"
        val root = getJson(url)
        val arr = extractList(root)
        val items = arr.objects().mapNotNull(::parseSeries).distinctBy { it.id }
        return CatalogPage(items = items, hasMore = items.size >= 10 && page < 50)
    }

    fun detail(seriesId: String): Detail {
        val parts = seriesId.split(':')
        require(parts.size == 3 && parts[0] == "xifan" && safeSource.matches(parts[1]) && safeToken.matches(parts[2])) {
            "稀饭剧集标识无效"
        }
        val source = parts[1]
        val duanjuId = parts[2]
        val url = "$API_BASE/api/duanju/api.php?duanju_id=$duanjuId&source=$source"
        val root = getJson(url)
        val dataObj = root.optJSONObject("data") ?: root
        val series = parseSeries(dataObj.put("source", source).put("duanju_id", duanjuId))
            ?: Series(id = seriesId, title = dataObj.str("title").ifEmpty { "稀饭剧集" })
        val epArr = dataObj.optJSONArray("episodes")
            ?: dataObj.optJSONArray("video_list")
            ?: dataObj.optJSONArray("list")
            ?: root.optJSONArray("data")
            ?: throw IOException("稀饭分集暂不可用")
        val episodes = mutableListOf<String>()
        for (ep in epArr.objects()) {
            val vid = ep.str("video_id").ifEmpty { ep.str("id").ifEmpty { ep.str("ep_id") } }
            if (safeToken.matches(vid)) {
                val fullEpId = "xifan:$source:$duanjuId:$vid"
                if (fullEpId !in episodes) episodes += fullEpId
            }
        }
        if (episodes.isEmpty()) throw IOException("稀饭分集列表为空")
        return Detail(series.copy(id = seriesId), episodes)
    }

    fun stream(episodeId: String): StreamInfo {
        val parts = episodeId.split(':')
        require(parts.size == 4 && parts[0] == "xifan" && safeSource.matches(parts[1]) && safeToken.matches(parts[2]) && safeToken.matches(parts[3])) {
            "稀饭分集标识无效"
        }
        val source = parts[1]
        val videoId = parts[3]
        val url = "$API_BASE/api/duanju/api.php?video_id=$videoId&source=$source"
        val root = getJson(url)
        val dataObj = root.optJSONObject("data") ?: root
        val rawPlayUrl = dataObj.str("url")
            .ifEmpty { dataObj.str("play_url") }
            .ifEmpty { dataObj.str("video_url") }
            .ifEmpty { root.str("url") }
        if (rawPlayUrl.isEmpty()) throw IOException("稀饭片源未返回播放地址")
        val safeUrl = MediaUrlGuard.assertSafeMediaUrl(rawPlayUrl)
        val quality = dataObj.str("quality").ifEmpty { "高清" }
        return StreamInfo(url = safeUrl, key = null, quality = quality)
    }
}
