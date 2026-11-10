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
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val width: Int = 0, val height: Int = 0)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer(clock)
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()

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
            cleanUrl(o.str("series_cover")),
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
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        val normalized = ChineseConverter.toSimplified(keyword).trim()
        val query = if(normalized.isNotBlank()) normalized else keyword.trim()
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
                    id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),
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
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    private fun videoModel(id: String, allowReRegister: Boolean = true): JSONObject {
        val hosts = listOf(
            "https://api5-normal-lq.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com"
        )
        val biz = """{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
        val videoMap = """{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}"""
        val candidates = listOf(
            "/novel/player/video_model/v1/" to """{"video_id":"$id","biz_param":$biz}""",
            "/novel/player/multi_video_model/v1/" to videoMap
        )
        var failure: IOException? = null
        var emptyResponseCount = 0
        for (host in hosts) {
            for ((path, body) in candidates) {
                val attempt = runCatching {
                    val payload = body.toByteArray()
                    val cookie = registrationCookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }.takeIf { it.isNotEmpty() }
                    val signed = signer.sign(host + path, deviceProfile(), payload)
                    val request = if (cookie == null) signed else signed.copy(headers = signed.headers + linkedMapOf("Cookie" to cookie))
                    val response = text(signed.url, request)
                    if (response.isBlank()) throw IOException("播放接口暂不可用（空响应）")
                    val data = JSONObject(response)
                    val code = data.optInt("Code", data.optInt("code", 0))
                    if (code != 0) throw IOException("播放接口暂不可用（${code}）")
                    videoModelOf(data) ?: throw IOException("播放接口暂不可用（没有播放模型）")
                }
                val model = attempt.getOrNull()
                if (model != null) return model
                val ex = attempt.exceptionOrNull()
                System.err.println("ContentRepository.videoModel candidate $host$path failed: ${ex?.javaClass?.name}: ${ex?.message}")
                failure = ex as? IOException ?: IOException(ex?.message ?: "播放接口暂不可用", ex)
                if (ex?.message == "播放接口暂不可用（空响应）") {
                    emptyResponseCount++
                    if (allowReRegister && emptyResponseCount >= 1) {
                        System.err.println("ContentRepository.videoModel received empty response, refreshing device registration immediately...")
                        val refreshed = registerDevice()
                        if (refreshed != null) {
                            return videoModel(id, allowReRegister = false)
                        }
                    }
                }
            }
        }
        if (allowReRegister) {
            System.err.println("ContentRepository.videoModel all candidates failed, attempting registerDevice refresh...")
            val refreshed = registerDevice()
            if (refreshed != null) {
                return videoModel(id, allowReRegister = false)
            }
        }
        throw failure ?: IOException("播放接口暂不可用")
    }

    private fun videoModelOf(data: JSONObject): JSONObject? {
        val root = data.optJSONObject("data") ?: return null
        model(root.opt("video_model"))?.let { return it }
        val entry = root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull() ?: return null
        return model(entry.opt("video_model"))
    }

    private fun model(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun fallbackApiCandidates(fallback: String): List<String> {
        val urls = linkedSetOf<String>()
        val codecRegex = Regex("([?&]codec_type=)(?:4|2)\\b")
        if (codecRegex.containsMatchIn(fallback)) {
            urls += codecRegex.replace(fallback, "$13")
            urls += codecRegex.replace(fallback, "$11")
            urls += fallback
        } else {
            urls += fallback
            val anyCodecRegex = Regex("([?&]codec_type=)[0-9]+\\b")
            if (anyCodecRegex.containsMatchIn(fallback)) {
                urls += anyCodecRegex.replace(fallback, "$13")
                urls += anyCodecRegex.replace(fallback, "$11")
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
        val list = model.optJSONArray("video_list")
        if (list != null && list.length() > 0) {
            data class PreloadRow(val quality: Int, val codecRank: Int, val url: String, val spadeA: String, val width: Int = 0, val height: Int = 0)
            val rows = mutableListOf<PreloadRow>()
            for (i in 0 until list.length()) {
                val v = list.optJSONObject(i) ?: continue
                val mainUrl = cleanUrl(v.str("main_url"))
                if (mainUrl.isEmpty() || (!mainUrl.startsWith("http://") && !mainUrl.startsWith("https://"))) continue
                val meta = v.optJSONObject("video_meta")
                val codec = meta?.str("codec_type")?.lowercase().orEmpty()
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
                val height = meta?.optInt("vheight", 0) ?: 0
                val width = meta?.optInt("vwidth", 0) ?: 0
                val defStr = meta?.str("definition")?.ifEmpty { meta.str("quality") }.orEmpty()
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
