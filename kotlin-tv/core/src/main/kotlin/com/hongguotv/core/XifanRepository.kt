// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class XifanRepository(private val http: OkHttpClient) {
    companion object {
        const val API_BASE = "https://xifan-api-cn.youlishipin.com"
        private const val COMMON_QUERY = "version=2001001&androidVersionCode=28"
        private val safeToken = Regex("^[a-zA-Z0-9_-]{1,200}$")
        private val safeSource = Regex("^[a-zA-Z0-9_-]{1,200}$")
        private val defaultGenres = listOf(
            GenreFilter("68:都市:1", "都市"),
            GenreFilter("68:青春:1", "青春"),
            GenreFilter("81:现代言情:1", "现代言情"),
            GenreFilter("81:豪门:1", "豪门")
        )
    }

    private var cachedGenres: Pair<Long, List<GenreFilter>>? = null
    private val streamUrlCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 500
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()
        return RequestScope.execute(http.newCall(req)) { response ->
            if (!response.isSuccessful) throw IOException("稀饭片源请求失败（HTTP ${response.code}）")
            val body = response.body?.string()?.trim() ?: throw IOException("稀饭片源响应为空")
            if (!body.startsWith("{")) throw IOException("稀饭片源响应格式无效")
            JSONObject(body)
        }
    }

    private fun parseSeries(obj: JSONObject): Series? {
        val rawId = obj.str("duanjuId").ifEmpty { obj.str("duanju_id").ifEmpty { obj.str("book_id").ifEmpty { obj.str("id") } } }
        val source = obj.str("source").ifEmpty { "1" }
        if (!safeSource.matches(source) || !safeToken.matches(rawId)) return null
        val title = obj.str("title").ifEmpty { obj.str("name") }.replace(Regex("<[^>]*>"), "").trim()
        if (title.isEmpty()) return null
        val cover = normalizeCoverUrl(obj.str("coverImageUrl").ifEmpty { obj.str("cover").ifEmpty { obj.str("pic") } })
        val desc = obj.str("desc").ifEmpty { obj.str("intro") }
        val epCnt = obj.optInt("total", obj.optInt("total_num", obj.optInt("episode_cnt", 0)))
        val badge = obj.str("remarks").ifEmpty { if (epCnt > 0) "全 $epCnt 集" else "稀饭剧场" }
        val catArr = obj.optJSONArray("categories")
        val catTags = if (catArr != null) {
            (0 until minOf(catArr.length(), 4)).mapNotNull { i ->
                catArr.optString(i).replace(Regex("<[^>]*>"), "").trim().takeIf { it.isNotBlank() }
            }.joinToString(" · ")
        } else {
            obj.str("type_name").ifEmpty { obj.str("category") }
        }
        return Series(
            id = "xifan:$source:$rawId",
            title = title,
            cover = cover,
            description = desc,
            badge = badge,
            tags = if (catTags.isNotBlank()) "稀饭 · $catTags" else "稀饭片源"
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

    private fun parsePortalOrSearchList(root: JSONObject): Pair<List<Series>, Boolean> {
        val resultObj = root.optJSONObject("result")
        if (resultObj != null) {
            if (root.has("ret") && root.optInt("ret", -1) != 0) {
                throw IOException("稀饭片源暂不可用")
            }
            val elements = resultObj.optJSONArray("elements") ?: JSONArray()
            val items = mutableListOf<Series>()
            for (el in elements.objects()) {
                val contents = el.optJSONArray("contents") ?: continue
                for (c in contents.objects()) {
                    val vo = c.optJSONObject("duanjuVo") ?: c
                    parseSeries(vo)?.let { items += it }
                }
            }
            val deduped = items.distinctBy { it.id }
            val hasMore = resultObj.optBoolean("hasMore", false) && deduped.isNotEmpty()
            return deduped to hasMore
        }
        val arr = extractList(root)
        val items = arr.objects().mapNotNull(::parseSeries).distinctBy { it.id }
        return items to (items.size >= 10)
    }

    fun genres(forceRefresh: Boolean = false): List<GenreFilter> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedGenres?.takeIf { now - it.first < 300_000L }?.let { return it.second }
        }
        val fetched = runCatching {
            val root = getJson("$API_BASE/xifan/drama/portalPage?reqType=duanjuCategory&$COMMON_QUERY")
            if (root.has("ret") && root.optInt("ret", -1) != 0) throw IOException("分类接口不可用")
            val elements = root.optJSONObject("result")?.optJSONArray("elements") ?: JSONArray()
            val list = mutableListOf<GenreFilter>()
            val seenIds = HashSet<String>()
            for (el in elements.objects()) {
                val contents = el.optJSONArray("contents") ?: continue
                for (c in contents.objects()) {
                    val cat = c.optJSONObject("categoryItemVo") ?: continue
                    val subs = cat.optJSONArray("subCategories")
                    val targets = if (subs != null && subs.length() > 0) subs.objects() else listOf(cat)
                    for (t in targets) {
                        val catId = t.optInt("categoryId", 0)
                        val name = t.str("oppoCategory").replace(Regex("<[^>]*>"), "").trim()
                        val ver = t.optInt("version", 1).coerceAtLeast(1)
                        val slug = "$catId:$name:$ver"
                        if (catId > 0 && name.isNotEmpty() && seenIds.add(slug)) {
                            list += GenreFilter(slug, name)
                        }
                    }
                }
            }
            list
        }.getOrDefault(emptyList())
        val finalGenres = if (fetched.isNotEmpty()) fetched else defaultGenres
        cachedGenres = now to finalGenres
        return finalGenres
    }

    fun home(page: Int, genreId: String = ""): CatalogPage {
        val availableGenres = genres()
        val selected = availableGenres.firstOrNull { it.id == genreId }
            ?: availableGenres.firstOrNull { it.label == "都市" }
            ?: availableGenres.first()
        val parts = selected.id.split(':')
        val catId = parts.getOrNull(0) ?: "68"
        val catName = parts.getOrNull(1) ?: "都市"
        val catVer = parts.getOrNull(2) ?: "1"
        val offset = (page - 1).coerceAtLeast(0) * 30
        val url = "$API_BASE/xifan/drama/portalPage?reqType=aggregationPage&offset=$offset&categoryId=$catId&categoryNames=${Signer.encode(catName)}&categoryVersion=$catVer&$COMMON_QUERY"
        val root = getJson(url)
        val (items, hasMore) = parsePortalOrSearchList(root)
        return CatalogPage(
            items = items,
            hasMore = hasMore && page < 50,
            genres = availableGenres
        )
    }

    fun search(keyword: String, page: Int): CatalogPage {
        val encoded = Signer.encode(keyword.trim())
        val url = "$API_BASE/xifan/search/getSearchList?keyword=$encoded&pageIndex=$page&$COMMON_QUERY"
        val root = getJson(url)
        val (items, hasMore) = parsePortalOrSearchList(root)
        return CatalogPage(items = items, hasMore = hasMore && page < 50)
    }

    fun detail(seriesId: String): Detail {
        val parts = seriesId.split(':')
        require(parts.size == 3 && parts[0] == "xifan" && safeSource.matches(parts[1]) && safeToken.matches(parts[2])) {
            "稀饭剧集标识无效"
        }
        val source = parts[1]
        val duanjuId = parts[2]
        val url = "$API_BASE/xifan/drama/getDuanjuInfo?source=$source&duanjuId=$duanjuId&$COMMON_QUERY"
        val root = getJson(url)
        val resultObj = root.optJSONObject("result")
        if (resultObj != null) {
            if (root.has("ret") && root.optInt("ret", -1) != 0) throw IOException("稀饭片源暂不可用")
            val retSource = resultObj.str("source")
            val retDuanjuId = resultObj.str("duanjuId")
            if (retSource.isNotEmpty() && retSource != source) throw IOException("稀饭返回的剧集来源不一致")
            if (retDuanjuId.isNotEmpty() && retDuanjuId != duanjuId) throw IOException("稀饭返回的剧集编号不一致")
            val series = parseSeries(resultObj) ?: Series(id = seriesId, title = resultObj.str("title").ifEmpty { "稀饭剧集" })
            val epArr = resultObj.optJSONArray("episodeList") ?: throw IOException("稀饭分集暂不可用")
            val epEntries = epArr.objects()
                .filter { safeToken.matches(it.str("episodeId")) && it.optBoolean("unlock", false) }
                .sortedBy { it.optInt("index", 0) }
            val episodes = mutableListOf<String>()
            synchronized(streamUrlCache) {
                for (ep in epEntries) {
                    val vid = ep.str("episodeId")
                    val fullEpId = "xifan:$source:$duanjuId:$vid"
                    val playUrl = ep.str("playUrl")
                    if (playUrl.isNotBlank()) {
                        runCatching { MediaUrlGuard.assertSafeMediaUrl(playUrl) }.getOrNull()?.let { safe ->
                            streamUrlCache[fullEpId] = safe
                        }
                    }
                    if (fullEpId !in episodes) episodes += fullEpId
                }
            }
            if (episodes.isEmpty()) throw IOException("该剧集暂无免费可播分集")
            return Detail(series.copy(id = seriesId), episodes)
        }

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
        val duanjuId = parts[2]
        val videoId = parts[3]
        val url = "$API_BASE/xifan/drama/getDuanjuInfo?source=$source&duanjuId=$duanjuId&$COMMON_QUERY"
        val root = getJson(url)
        val resultObj = root.optJSONObject("result")
        if (resultObj != null) {
            if (root.has("ret") && root.optInt("ret", -1) != 0) throw IOException("稀饭片源暂不可用")
            val epArr = resultObj.optJSONArray("episodeList") ?: throw IOException("稀饭分集暂不可用")
            val ep = epArr.objects().firstOrNull { it.str("episodeId") == videoId }
                ?: throw IOException("稀饭未找到该分集")
            if (!ep.optBoolean("unlock", false)) throw IOException("该分集当前锁定，请在稀饭官方 App 确认可看范围")
            val rawPlayUrl = ep.str("playUrl")
            if (rawPlayUrl.isEmpty()) throw IOException("稀饭片源未返回播放地址")
            val safeUrl = MediaUrlGuard.assertSafeMediaUrl(rawPlayUrl)
            return StreamInfo(url = safeUrl, key = null, quality = "高清")
        }

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
