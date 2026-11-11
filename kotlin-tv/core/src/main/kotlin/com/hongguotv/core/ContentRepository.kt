// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
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
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "", val isAi: Boolean? = null, val isLandscape: Boolean? = null) {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
        .apply {
            if(isAi != null) put("isAi", isAi)
            if(isLandscape != null) put("isLandscape", isLandscape)
        }
    companion object {
        fun fromJson(o: JSONObject) = Series(
            o.str("id"), o.str("title"), o.str("cover"), o.str("description"), o.str("badge"), o.str("tags"),
            if (o.has("isAi")) o.optBoolean("isAi") else null,
            if (o.has("isLandscape")) o.optBoolean("isLandscape") else null
        )
    }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null, val genres: List<GenreFilter> = emptyList())
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val width: Int = 0, val height: Int = 0, val referer: String = "https://novel.snssdk.com/", val userAgent: String = VendorConstants.VIDEO_UA, val hls: Boolean = false)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer(clock)
    private val site = "https://hongguoduanju.com"
    private val xifan = XifanRepository(http)
    private val hema = HemaRepository(http)
    private val reelshort = OverseasRepository(http, MediaSource.REELSHORT)
    private val dramabox = OverseasRepository(http, MediaSource.DRAMABOX)
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private data class CategoryCursor(val offset: Int, val sessionId: String, val genres: List<GenreFilter>, val updatedAt: Long)
    private val categoryCursors=linkedMapOf<Triple<ContentType,String,Int>,CategoryCursor>()
    private val genreCache=java.util.concurrent.ConcurrentHashMap<ContentType,Pair<Long,List<GenreFilter>>>()

    @Volatile var deviceIdentity: Map<String,String> = emptyMap()
        set(value) { field=value.filterValues { it.isNotBlank() }; signer.updateDevice(deviceProfile()) }

    fun inheritDevice(from: ContentRepository) {
        deviceIdentity=from.deviceIdentity
        registrationCookies=from.registrationCookies
    }

    @Volatile var registerProfile: Map<String,String> = emptyMap()
    @Volatile var onDeviceRegistered: ((DeviceRegister.Registered) -> Unit)? = null

    fun registerDevice(): DeviceRegister.Registered? {
        val device=LinkedHashMap(registerProfile).apply { putAll(deviceIdentity) }
        val body=DeviceRegister.payload(device)
        val headers=DeviceRegister.headers(body,device)
        val registered=runCatching {
            val request=Request.Builder().url(DeviceRegister.url())
            headers.forEach { (key,value) -> request.header(key,value) }
            request.post(body.toRequestBody("application/octet-stream".toMediaType()))
            RequestScope.execute(http.newCall(request.build())) { response ->
                if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
                val bytes=response.body?.bytes() ?: throw IOException("设备注册响应为空")
                DeviceRegister.parse(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
                    ?: throw IOException("设备注册被服务端拒绝")
            }
        }.getOrNull()
        if(registered!=null) {
            deviceIdentity=linkedMapOf("device_id" to registered.deviceId,"iid" to registered.installId)
            registrationCookies=LinkedHashMap(registrationCookies).apply { putAll(registered.cookies) }
            onDeviceRegistered?.invoke(registered)
        }
        return registered
    }

    @Volatile var registrationCookies: Map<String,String> = emptyMap()
        set(value) { field=LinkedHashMap(value) }

    private fun deviceProfile(): Map<String,String> =
        LinkedHashMap(VendorConstants.device).apply {
            putAll(deviceIdentity)
        }
    private val rankCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, CatalogPage>>()
    private fun text(url: String, signed: Signer.Request? = null): String {
        val isWeb = url.startsWith(site)
        val defaultUa = if (isWeb) VendorConstants.WEB_UA else VendorConstants.VIDEO_UA
        val maxAttempts = if (isWeb && signed == null) 3 else 1
        var lastException: IOException? = null

        for (attempt in 1..maxAttempts) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", defaultUa)
                if (isWeb) request.header("Connection", "close")
                signed?.headers?.forEach { (k, v) -> request.header(k, v) }
                signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }

                val client = if (isWeb && attempt > 1) {
                    http.newBuilder().readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
                } else if (isWeb) {
                    http.newBuilder().readTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
                } else {
                    http
                }

                return RequestScope.execute(client.newCall(request.build())) { response ->
                    if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
                    val body = response.body ?: throw IOException("内容响应为空")
                    if (body.contentLength() > 16 * 1024 * 1024) throw IOException("内容响应过大")
                    val input = body.byteStream()
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (out.size() <= 16 * 1024 * 1024) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                    val bytes = out.toByteArray()
                    if (bytes.size > 16 * 1024 * 1024) throw IOException("内容响应过大")
                    String(bytes, Charsets.UTF_8)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt == maxAttempts) throw e
            }
        }
        throw lastException ?: IOException("内容请求失败")
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        val vertical = if(o.has("vertical")) o.optBoolean("vertical") else null
        val isLandscape = if(vertical != null) !vertical else null
        val detailObj = o.optJSONObject("video_detail")
        val aiUsage = o.optInt("ai_usage_type", detailObj?.optInt("ai_usage_type", 0) ?: 0)
        val isAi = if(aiUsage > 0) true else null
        return Series(
            o.str("series_id"),
            o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },
            normalizeCoverUrl(o.str("series_cover")),
            o.str("series_intro"),
            o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },
            (0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) },
            isAi = isAi,
            isLandscape = isLandscape
        ).also(SeriesMediaTrait::recordSeriesMetadata)
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        val cached = rankCache[page]
        val now = clock()
        if (cached != null && now - cached.first < 300_000L) {
            return cached.second
        }
        val url = if (page > 1) "$site/rank/hot-comic-drama?page=$page" else "$site/rank/hot-comic-drama"
        val res = ComicRank.parse(text(url), page)
        rankCache[page] = Pair(now, res)
        return res
    }
    fun categoryCatalog(page: Int = 1, type: ContentType = ContentType.COMIC, genreId: String = ""): CatalogPage {
        require(page in 1..100)
        val normalizedGenre = genreId.trim()
        val now = clock()
        val prevCursor = synchronized(categoryCursors) {
            categoryCursors.entries.removeAll { now - it.value.updatedAt > 300_000L || (page == 1 && it.key.first == type && it.key.second == normalizedGenre) }
            categoryCursors[Triple(type, normalizedGenre, page - 1)]
        }
        if (page > 1 && prevCursor == null) {
            return CatalogPage(emptyList(), false, genres = genreCache[type]?.second ?: emptyList())
        }
        val offset = if (page == 1) 0 else prevCursor!!.offset
        val sessionId = if (page == 1) "" else prevCursor!!.sessionId
        val previousGenres = prevCursor?.genres ?: genreCache[type]?.second ?: emptyList()

        val values = LinkedHashMap(deviceProfile())
        for (k in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) values[k] = "73532"
        values["version_name"] = "7.3.5.32"

        val payloadBytes = AppCategoryCatalog.buildPayload(type, normalizedGenre, offset, sessionId, page)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val hosts = listOf(
            "https://api5-normal-lf.fqnovel.com",
            "https://api5-normal-lq.fqnovel.com"
        )
        var lastError: IOException? = null
        for (host in hosts) {
            val attempt = runCatching {
                val signed = signer.sign(host + AppCategoryCatalog.CATEGORY_PATH, values, payloadBytes)
                val rawText = text(signed.url, signed)
                AppCategoryCatalog.parseResponse(rawText, type, offset, previousGenres)
            }
            val parsed = attempt.getOrNull()
            if (parsed != null) {
                if (parsed.genres.size > 2) {
                    genreCache[type] = now to parsed.genres
                }
                if (parsed.hasMore && parsed.nextOffset != null) {
                    synchronized(categoryCursors) {
                        categoryCursors[Triple(type, normalizedGenre, page)] = CategoryCursor(
                            offset = parsed.nextOffset,
                            sessionId = parsed.sessionId,
                            genres = parsed.genres,
                            updatedAt = now
                        )
                        while (categoryCursors.size > 100) categoryCursors.remove(categoryCursors.keys.first())
                    }
                }
                return CatalogPage(parsed.items, parsed.hasMore && page < 100, genres = parsed.genres)
            }
            val ex = attempt.exceptionOrNull()
            lastError = ex as? IOException ?: IOException(ex?.message ?: "App 分类接口暂不可用", ex)
        }
        throw lastError ?: IOException("App 分类接口暂不可用")
    }
    fun defaultGenres(type: ContentType = ContentType.COMIC): List<GenreFilter> {
        genreCache[type]?.second?.takeIf { it.isNotEmpty() }?.let { return it }
        return AppCategoryCatalog.defaultGenresFor(type)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT, genreId: String = "", source: MediaSource = MediaSource.HONGGUO): CatalogPage {
        require(page in 1..100)
        when (source) {
            MediaSource.XIFAN -> return xifan.home(page, genreId)
            MediaSource.HEMA -> return hema.home(page)
            MediaSource.REELSHORT -> return reelshort.home(page)
            MediaSource.DRAMABOX -> return dramabox.home(page)
            MediaSource.HONGGUO -> {}
        }
        if (genreId.isNotBlank()) {
            return categoryCatalog(page, type, genreId)
        }
        if(type==ContentType.COMIC) {
            val res = comicRanking(page)
            return if (res.genres.isNotEmpty()) res else res.copy(genres = defaultGenres(type))
        }
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100), genres = defaultGenres(type))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT, source: MediaSource = MediaSource.HONGGUO): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val normalized = ChineseConverter.toSimplified(keyword).trim()
        val query = if(normalized.isNotBlank()) normalized else keyword.trim()
        when (source) {
            MediaSource.XIFAN -> return xifan.search(query, page)
            MediaSource.HEMA -> return hema.search(query, page)
            MediaSource.REELSHORT -> return reelshort.search(query, page)
            MediaSource.DRAMABOX -> return dramabox.search(query, page)
            MediaSource.HONGGUO -> {}
        }
        // The website search does not constrain content type. Never use it for comics.
        if(type==ContentType.COMIC) return appSearch(query,page,type)
        try {
            val result = appSearch(query, page, type)
            if (result.items.isNotEmpty() || !result.hasMore) return result
        } catch (e: Exception) {
            if (e is SearchSessionExpiredException) throw e
            /* Website fallback for short dramas only. */
        }
        val data = try {
            router("$site/search/${Signer.encode(query)}?page=$page")
        } catch(e: Exception) {
            return CatalogPage(emptyList(), false)
        }
        val section = data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: return CatalogPage(emptyList(), false)
        val rows = section.optJSONArray("searchList") ?: return CatalogPage(emptyList(), false)
        val items = valid(rows.objects().map(::card))
        return CatalogPage(items, items.isNotEmpty() && page < 100)
    }
    fun hotSearches(): List<String> = listOf(
        "短剧最热榜前十名", "都市逆袭", "战神归来", "甜宠", "神医下山", "穿越剧大全",
        "萌宝助攻", "豪门千金", "玄幻修仙", "赘婿逆袭", "古装穿越", "重生复仇"
    )

    fun searchSiblings(series: Series): List<Series> {
        val source = MediaSource.fromSeriesOrEpisodeId(series.id)
        if (source != MediaSource.HONGGUO) return listOf(series)
        val parsed = SeriesSeasons.parse(series.title)
        val base = if (!parsed.matched && (series.title.contains('：') || series.title.contains(':'))) {
            series.title.substringBefore('：').substringBefore(':').trim().ifEmpty { parsed.base }
        } else {
            parsed.base
        }
        val list = mutableListOf<Series>()
        try {
            val page1 = search(base, 1, ContentType.SHORT)
            list.addAll(page1.items)
            if (page1.hasMore) {
                runCatching { search(base, 2, ContentType.SHORT).items }.getOrNull()?.let { list.addAll(it) }
            }
        } catch (_: Exception) {
        }
        val initialSiblings = SeriesSeasons.siblings(series.title, (list + series).distinctBy { it.id })
        val matchedSeasons = initialSiblings.mapNotNull { s ->
            SeriesSeasons.parse(s.title).takeIf { it.matched }?.season
        }.toSet()
        if (parsed.matched || matchedSeasons.size > 1 || (matchedSeasons.maxOrNull() ?: 1) > 1) {
            val probeTargets = listOf(
                listOf(2, 3, 4, 5),
                listOf(6, 7, 8, 9, 10),
                listOf(11, 12, 13, 14, 15),
                listOf(16, 17, 18, 19, 20)
            )
            for (group in probeTargets) {
                val missing = group.filter { it !in matchedSeasons }
                if (missing.isEmpty()) continue
                val suffixQuery = missing.joinToString(" ") { "第${SeriesSeasons.toChineseNumber(it)}季" }
                val query = "$base $suffixQuery".take(80)
                runCatching { search(query, 1, ContentType.SHORT).items }.getOrNull()?.let { list.addAll(it) }
            }
            val afterCompound = SeriesSeasons.siblings(series.title, (list + series).distinctBy { it.id })
            val currentSeasons = afterCompound.mapNotNull { s ->
                SeriesSeasons.parse(s.title).takeIf { it.matched }?.season
            }.toSet()
            val maxKnown = maxOf(currentSeasons.maxOrNull() ?: 1, if (parsed.matched) parsed.season else 1)
            if (maxKnown > 1) {
                val stillMissing = (1..(maxKnown + 1).coerceAtMost(25)).filter { it !in currentSeasons }.take(6)
                for (num in stillMissing) {
                    val q = "${base}第${SeriesSeasons.toChineseNumber(num)}季".take(80)
                    runCatching { search(q, 1, ContentType.SHORT).items }.getOrNull()?.let { list.addAll(it) }
                }
            }
        }
        val all = (list + series).distinctBy { it.id }
        return SeriesSeasons.siblings(series.title, all)
    }

    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=LinkedHashMap(deviceProfile())
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val rawText=text(signed.url,signed).trim()
        if(rawText.isEmpty() || !rawText.startsWith("{")) throw IOException("搜索接口暂不可用")
        val data=JSONObject(rawText)
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                val vertical=if(video.has("vertical")) video.optBoolean("vertical") else null
                val isLandscape=if(vertical!=null) !vertical else null
                val detailObj=video.optJSONObject("video_detail")
                val aiUsage=video.optInt("ai_usage_type",detailObj?.optInt("ai_usage_type",0) ?: 0)
                val isAi=if(aiUsage>0) true else null
                val parsedTags=runCatching {
                    val schemaStr=video.str("category_schema").ifEmpty { detailObj?.str("category_schema").orEmpty() }
                    if(schemaStr.startsWith("[")) {
                        val arr=JSONArray(schemaStr)
                        (0 until minOf(arr.length(),5)).mapNotNull { arr.optJSONObject(it)?.str("name")?.takeIf(String::isNotBlank) }.joinToString(" · ")
                    } else ""
                }.getOrDefault("")
                list+=Series(
                    id,title,normalizeCoverUrl(video.str("cover").ifEmpty { video.str("cover_url") }),
                    video.str("video_desc").ifEmpty { detailObj?.str("series_intro").orEmpty() },
                    if(count>0) "全 $count 集" else video.str("rec_text"),
                    video.str("sub_title").ifEmpty { parsedTags },isAi=isAi,isLandscape=isLandscape
                ).also(SeriesMediaTrait::recordSeriesMetadata)
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        when (MediaSource.fromSeriesOrEpisodeId(id)) {
            MediaSource.XIFAN -> return xifan.detail(id)
            MediaSource.HEMA -> return hema.detail(id)
            MediaSource.REELSHORT -> return reelshort.detail(id)
            MediaSource.DRAMABOX -> return dramabox.detail(id)
            MediaSource.HONGGUO -> {}
        }
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    private class EmptyModelResponseException : IOException("播放接口暂不可用（空响应）")
    private class DeviceRegistrationRefusedException(message: String) : IOException(message)

    private fun registerDeviceOrThrowRefusal(): DeviceRegister.Registered? {
        val device = LinkedHashMap(registerProfile).apply { putAll(deviceIdentity) }
        val body = DeviceRegister.payload(device)
        val headers = DeviceRegister.headers(body, device)
        val result = runCatching {
            val request = Request.Builder().url(DeviceRegister.url())
            headers.forEach { (key, value) -> request.header(key, value) }
            request.post(body.toRequestBody("application/octet-stream".toMediaType()))
            RequestScope.execute(http.newCall(request.build())) { response ->
                if (!response.isSuccessful) {
                    if (response.code in listOf(403, 429)) {
                        throw DeviceRegistrationRefusedException("设备注册被限制（HTTP ${response.code}）")
                    }
                    throw IOException("设备注册失败（HTTP ${response.code}）")
                }
                val bytes = response.body?.bytes() ?: throw IOException("设备注册响应为空")
                val raw = String(bytes, Charsets.UTF_8)
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (json != null && json.optInt("code", json.optInt("Code", 0)) != 0) {
                    throw DeviceRegistrationRefusedException("设备注册被服务端拒绝")
                }
                DeviceRegister.parse(raw, response.headers("Set-Cookie"))
                    ?: throw IOException("设备注册被服务端拒绝")
            }
        }
        val ex = result.exceptionOrNull()
        if (ex is DeviceRegistrationRefusedException) throw ex
        val registered = result.getOrNull()
        if (registered != null) {
            deviceIdentity = linkedMapOf("device_id" to registered.deviceId, "iid" to registered.installId)
            registrationCookies = LinkedHashMap(registrationCookies).apply { putAll(registered.cookies) }
            onDeviceRegistered?.invoke(registered)
        }
        return registered
    }

    internal fun buildPlaybackRequest(
        id: String,
        useVideoTab: Boolean,
        host: String = "https://api5-normal-sinfonlineb.fqnovel.com",
        stampMs: Long = clock()
    ): Signer.Request {
        val khronos = stampMs / 1000L
        val versionCode = if (useVideoTab) "73932" else "74332"
        val versionName = if (useVideoTab) "7.3.9.32" else "7.4.3.32"
        val values = LinkedHashMap(deviceProfile()).apply {
            for (key in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) {
                put(key, versionCode)
            }
            put("version_name", versionName)
            if (useVideoTab) {
                put("video_id", id)
                put("video_id_type", "0")
                put("device_level", "1")
                put("need_all_video_definition", "1")
                put("need_mp4_align", "0")
                put("use_os_player", "0")
                put("video_platform", "1024")
                put("source", "0")
                put("from_video_id", "")
            }
            put("ts", khronos.toString())
            put("_rticket", stampMs.toString())
        }
        val bodyBytes = if (useVideoTab) {
            null
        } else {
            val biz = """{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
            val payload = """{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}"""
            PlayerSigner.gzip(payload.toByteArray(Charsets.UTF_8))
        }
        val query = PlayerSigner.pythonUrlEncode(values)
        val path = if (useVideoTab) {
            "/reading/bookapi/video_tab/multi_video_model/v1/"
        } else {
            "/novel/player/multi_video_model/v1/"
        }
        val url = "$host$path?$query"
        val signature = PlayerSigner.signPlayer(query = query, body = bodyBytes, ts = khronos)
        val aid = values["aid"]?.takeIf { it.isNotBlank() } ?: "8662"
        val headers = linkedMapOf(
            "User-Agent" to VendorConstants.VIDEO_UA.replace("71332", versionCode),
            "Accept" to "application/json; charset=utf-8,application/x-protobuf",
            "x-ss-req-ticket" to signature.getValue("x-ss-req-ticket"),
            "sdk-version" to "2",
            "x-xs-from-web" to "0",
            "passport-sdk-version" to if (useVideoTab) "50561" else "5051452",
            "x-ss-dp" to aid,
            "x-khronos" to signature.getValue("x-khronos"),
            "x-ladon" to signature.getValue("x-ladon"),
            "x-argus" to signature.getValue("x-argus"),
            "x-tt-dt" to "",
            "x-tt-store-region" to "cn",
            "x-tt-store-region-src" to "did"
        )
        if (bodyBytes != null) {
            headers["Content-Type"] = "application/json; charset=UTF-8"
            headers["Content-Encoding"] = "gzip"
            headers["Connection"] = "close"
            signature["x-ss-stub"]?.let { headers["x-ss-stub"] = it }
        } else {
            headers["x-tt-request-tag"] = "t=0;n=0"
        }
        return Signer.Request(url, headers, bodyBytes)
    }

    private fun executePlaybackCandidate(request: Signer.Request, id: String): JSONObject {
        val response = text(request.url, request)
        if (response.isBlank()) throw EmptyModelResponseException()
        val data = JSONObject(response)
        val code = data.optInt("Code", data.optInt("code", 0))
        if (code != 0) throw IOException("播放接口暂不可用（${code}）")
        return videoModelOf(data, id) ?: throw IOException("播放接口暂不可用（没有播放模型）")
    }

    private fun videoModel(id: String): JSONObject {
        val primaryHost = "https://api5-normal-sinfonlineb.fqnovel.com"
        val primaryAttempt = runCatching {
            val signed = buildPlaybackRequest(id, useVideoTab = true, host = primaryHost)
            executePlaybackCandidate(signed, id)
        }
        primaryAttempt.getOrNull()?.let { return it }
        val primaryError = primaryAttempt.exceptionOrNull()
        System.err.println("ContentRepository.videoModel primary video_tab failed: ${primaryError?.javaClass?.name}: ${primaryError?.message}")
        if (primaryError !is EmptyModelResponseException) {
            throw (primaryError as? IOException ?: IOException(primaryError?.message ?: "播放接口暂不可用", primaryError))
        }

        registerDeviceOrThrowRefusal()

        val legacyAttempt = runCatching {
            val signed = buildPlaybackRequest(id, useVideoTab = false, host = primaryHost)
            executePlaybackCandidate(signed, id)
        }
        legacyAttempt.getOrNull()?.let { return it }
        val legacyError = legacyAttempt.exceptionOrNull()
        System.err.println("ContentRepository.videoModel legacy fallback failed: ${legacyError?.javaClass?.name}: ${legacyError?.message}")
        throw (legacyError as? IOException ?: IOException(legacyError?.message ?: "播放接口暂不可用", legacyError))
    }

    private fun videoModelOf(data: JSONObject, expectedId: String = ""): JSONObject? {
        val root = data.optJSONObject("data") ?: return null
        model(root.opt("video_model"))?.let { return it }
        val container = root.optJSONObject("video_model_data") ?: root
        if (expectedId.isNotEmpty()) {
            container.optJSONObject(expectedId)?.let { entry ->
                model(entry.opt("video_model"))?.let { return it }
            }
            if (root.has("video_model_data")) return null
        }
        val entry = container.keys().asSequence().mapNotNull { container.optJSONObject(it) }.firstOrNull() ?: return null
        return model(entry.opt("video_model"))
    }

    private fun model(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun stripFallbackLockoutParams(url: String): String {
        return url
            .replace(Regex("([?&])force_fids=[^&]*&?"), "$1")
            .replace(Regex("([?&])encode_user_tag=[^&]*&?"), "$1")
            .replace("?&", "?")
            .replace("&&", "&")
            .trimEnd('&', '?')
    }

    internal fun fallbackApiCandidates(fallback: String): List<String> {
        val urls = linkedSetOf<String>()
        val stripped = stripFallbackLockoutParams(fallback)
        val anyCodecRegex = Regex("([?&]codec_type=)[0-9]+\\b")
        val incompatibleCodecRegex = Regex("([?&]codec_type=)(?:4|2)\\b")
        if (stripped != fallback && anyCodecRegex.containsMatchIn(stripped)) {
            urls += anyCodecRegex.replace(stripped, "$10")
            urls += anyCodecRegex.replace(stripped, "$13")
            urls += anyCodecRegex.replace(stripped, "$11")
            urls += stripped
        }
        if (incompatibleCodecRegex.containsMatchIn(fallback)) {
            urls += incompatibleCodecRegex.replace(fallback, "$13")
            urls += incompatibleCodecRegex.replace(fallback, "$11")
            urls += incompatibleCodecRegex.replace(fallback, "$10")
            urls += fallback
        } else {
            urls += fallback
            if (anyCodecRegex.containsMatchIn(fallback)) {
                urls += anyCodecRegex.replace(fallback, "$13")
                urls += anyCodecRegex.replace(fallback, "$11")
                urls += anyCodecRegex.replace(fallback, "$10")
            }
        }
        return urls.toList()
    }

    private fun codecRank(codec: String): Int = when (codec.lowercase()) {
        "h264", "avc", "avc1", "" -> 0
        "hevc", "h265", "hvc1", "hev1", "bytevc1" -> 1
        "bytevc2", "bvc2" -> 2
        else -> 1
    }

    private fun parseModelStreams(model: JSONObject, maxQuality: Int): StreamInfo? {
        var hasIncompatible = false
        val rawVideoList = model.opt("video_list")
        val inlineObjects: List<JSONObject> = when (rawVideoList) {
            is JSONArray -> rawVideoList.objects()
            is JSONObject -> rawVideoList.keys().asSequence().mapNotNull { rawVideoList.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        if (inlineObjects.isNotEmpty()) {
            data class PreloadRow(val quality: Int, val codecRank: Int, val url: String, val spadeA: String, val width: Int = 0, val height: Int = 0, val referer: String = "https://novel.snssdk.com/", val userAgent: String = VendorConstants.VIDEO_UA, val hls: Boolean = false)
            val rows = mutableListOf<PreloadRow>()
            for (v in inlineObjects) {
                val mainUrl = cleanUrl(v.str("main_url"))
                if (mainUrl.isEmpty() || (!mainUrl.startsWith("http://") && !mainUrl.startsWith("https://"))) continue
                val meta = v.optJSONObject("video_meta") ?: v
                val codec = meta.str("codec_type").ifEmpty { v.str("codec_type") }.lowercase()
                if (codec in setOf("bytevc2", "bvc2")) {
                    hasIncompatible = true
                    continue
                }
                val ei = v.optJSONObject("encrypt_info")
                val spadeA = ei?.str("spade_a").orEmpty().ifEmpty { v.str("spade_a") }
                val encryptFlag = ei?.opt("encrypt") ?: v.opt("encrypt")
                val explicitlyUnencrypted = encryptFlag == false || encryptFlag == 0 || encryptFlag == "0" || encryptFlag == "false"
                // PR #92 defense: if inline video_list omits spade_a without explicitly marking encrypt=false, skip to fallback_api
                if (spadeA.isEmpty() && !explicitlyUnencrypted && model.has("fallback_api")) {
                    continue
                }
                val height = meta.optInt("vheight", v.optInt("vheight", 0))
                val width = meta.optInt("vwidth", v.optInt("vwidth", 0))
                val defStr = meta.str("definition")
                    .ifEmpty { meta.str("quality_desc") }
                    .ifEmpty { meta.str("quality") }
                    .ifEmpty { v.str("quality_desc") }
                    .ifEmpty { v.str("definition") }
                val qNum = if (height > 0) height else qualityNumber(defStr)
                rows += PreloadRow(qNum, codecRank(codec), mainUrl, spadeA, width, height)
            }
            if (rows.isNotEmpty()) {
                val sorted = rows.sortedWith(compareByDescending<PreloadRow> { it.quality }.thenBy { it.codecRank })
                val selected = sorted.firstOrNull { it.quality <= maxQuality } ?: sorted.last()
                val key = selected.spadeA.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                val qualityDesc = if (selected.quality > 0) "${selected.quality}P" + (if (selected.quality > maxQuality) "（兼容资源）" else "") else "自动"
                return StreamInfo(selected.url, key, qualityDesc, selected.width, selected.height)
            }
        }

        val raw = model.opt("fallback_api")
        val fallback = if (raw == null) "" else when (raw) {
            is JSONObject -> raw.str("fallback_api")
            is JSONArray -> raw.optString(0)
            else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString())
        }
        if (fallback.startsWith("https://")) {
            val expectedVideoId = model.str("video_id")
            val expectedDuration = model.optDouble("video_duration", -1.0)
            for (candidateUrl in fallbackApiCandidates(fallback)) {
                val info = try { JSONObject(text(candidateUrl)).optJSONObject("video_info")?.optJSONObject("data") } catch (_: Exception) { null } ?: continue
                val fallbackVideoId = info.str("video_id")
                if (expectedVideoId.isNotEmpty() && fallbackVideoId.isNotEmpty() && fallbackVideoId != expectedVideoId) continue
                val fallbackDuration = info.optDouble("video_duration", -1.0)
                if (expectedDuration > 0 && fallbackDuration > 0 && kotlin.math.abs(fallbackDuration - expectedDuration) > 1.5) continue
                val videoList = info.optJSONObject("video_list") ?: continue
                val rows = videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
                    val codec = it.second.str("codec_type").lowercase()
                    if (codec in setOf("bytevc2", "bvc2")) {
                        hasIncompatible = true
                        false
                    } else {
                        it.second.str("main_url").isNotEmpty()
                    }
                }.map { (k, o) ->
                    val qNum = qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } })
                    Triple(qNum, codecRank(o.str("codec_type")), o)
                }.sortedWith(compareByDescending<Triple<Int, Int, JSONObject>> { it.first }.thenBy { it.second }).toList()
                val selected = rows.firstOrNull { it.first <= maxQuality } ?: rows.lastOrNull() ?: continue
                val item = selected.third
                var url = cleanUrl(item.str("main_url"))
                val seed = info.str("key_seed")
                if (seed.isNotEmpty()) url = MediaCrypto.decryptUrl(url, decode64(seed))
                if (!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
                val key = item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                val quality = if (selected.first > 0) "${selected.first}P" + (if (selected.first > maxQuality) "（兼容资源）" else "") else "自动"
                val width = item.optInt("vwidth", 0)
                val height = item.optInt("vheight", 0)
                return StreamInfo(url, key, quality, width, height)
            }
        }
        if (hasIncompatible) {
            throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        }
        return null
    }

    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        when (MediaSource.fromSeriesOrEpisodeId(id)) {
            MediaSource.XIFAN -> return xifan.stream(id)
            MediaSource.HEMA -> return hema.stream(id)
            MediaSource.REELSHORT -> return reelshort.stream(id)
            MediaSource.DRAMABOX -> return dramabox.stream(id)
            MediaSource.HONGGUO -> {}
        }
        require(id.matches(Regex("[0-9]{1,30}")))
        val model = try {
            videoModel(id)
        } catch (e: IOException) {
            System.err.println("ContentRepository.stream: videoModel failed for $id: ${e.message}")
            if (e.message?.contains("没有电视可播放的清晰度") == true) throw e
            throw e
        } catch (e: Exception) {
            System.err.println("ContentRepository.stream: videoModel exception for $id: ${e.message}")
            throw IOException(e.message ?: "获取视频播放地址异常", e)
        }
        val stream = parseModelStreams(model, maxQuality)
        if (stream != null) return stream
        throw IOException("未找到可播放的视频流")
    }

    fun danmaku(videoId: String, seriesId: String = ""): List<DanmakuItem> {
        if (!videoId.matches(Regex("[0-9]{1,30}"))) return emptyList()
        val bookId = seriesId.trim().takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: videoId
        return runCatching {
            val rawItems = mutableListOf<DanmakuRaw>()
            var startMs = 0L
            var durationMs = DanmakuRepository.EFFECTIVE_DURATION_MS
            var emptyPages = 0
            val profile = deviceProfile()
            val devId = profile["device_id"].orEmpty()
            val iid = profile["iid"].orEmpty()
            for (pageIdx in 0 until DanmakuRepository.MAX_PAGES) {
                val signed = DanmakuRepository.buildSignedCommentRequest(
                    vid = videoId,
                    bookId = bookId,
                    startMs = startMs,
                    durationMs = durationMs,
                    deviceId = devId,
                    installId = iid,
                    stampMs = clock()
                )
                val rawText = text(signed.url, signed)
                val (pageItems, hasMore, nextOffset) = DanmakuRepository.parsePage(rawText, videoId)
                if (pageItems.isNotEmpty()) {
                    rawItems += pageItems
                    emptyPages = 0
                } else {
                    emptyPages++
                }
                if (rawItems.size >= DanmakuRepository.MAX_ITEMS_PER_EPISODE || !hasMore || emptyPages >= 2) break
                val next = nextOffset?.toLong() ?: break
                if (next <= startMs || next > DanmakuRepository.MAX_OFFSET_MS) break
                startMs = next
                durationMs = minOf(maxOf(durationMs, startMs + DanmakuRepository.EFFECTIVE_DURATION_MS), DanmakuRepository.MAX_DURATION_MS)
            }
            DanmakuRepository.mergeAndSort(rawItems)
        }.getOrDefault(emptyList())
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
