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
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
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
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
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
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
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
    private fun fetchPreloadStream(id: String, maxQuality: Int): StreamInfo? {
        val q = LinkedHashMap(VendorConstants.preloadDevice)
        q["_rticket"] = clock().toString()
        val query = q.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val url = "${VendorConstants.PRELOAD_URL}?$query"
        val payload = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"dr_scene":"preload","mixed_video_id_map":{"1004":["$id"]}}"""
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VendorConstants.PRELOAD_UA)
            .header("Accept", "application/json; charset=utf-8,application/x-protobuf")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .post(payload.toByteArray().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val responseText = try {
            RequestScope.execute(http.newCall(request)) { response ->
                if (!response.isSuccessful) return@execute null
                val body = response.body ?: return@execute null
                body.string()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        if (responseText.isBlank()) return null
        val data = try { JSONObject(responseText) } catch (_: Exception) { null } ?: return null
        if (data.optInt("Code", data.optInt("code", 0)) != 0) return null
        val map = data.optJSONObject("data") ?: return null
        val entry = map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: return null
        val model = entry.optJSONObject("video_model") ?: runCatching { JSONObject(entry.str("video_model")) }.getOrNull() ?: return null
        return parseModelStreams(model, maxQuality)
    }

    private fun parseModelStreams(model: JSONObject, maxQuality: Int): StreamInfo? {
        val list = model.optJSONArray("video_list")
        if (list != null && list.length() > 0) {
            data class PreloadRow(val quality: Int, val url: String, val spadeA: String)
            var hasIncompatible = false
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
                val height = meta?.optInt("vheight", 0) ?: 0
                val defStr = meta?.str("definition")?.ifEmpty { meta.str("quality") }.orEmpty()
                val qNum = if (height > 0) height else qualityNumber(defStr)
                val ei = v.optJSONObject("encrypt_info")
                val spadeA = ei?.str("spade_a") ?: v.str("spade_a")
                rows += PreloadRow(qNum, mainUrl, spadeA)
            }
            if (rows.isEmpty() && hasIncompatible) {
                throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
            }
            if (rows.isNotEmpty()) {
                val sorted = rows.sortedByDescending { it.quality }
                val selected = sorted.firstOrNull { it.quality <= maxQuality } ?: sorted.last()
                val key = selected.spadeA.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                val qualityDesc = if (selected.quality > 0) "${selected.quality}P" + (if (selected.quality > maxQuality) "（兼容资源）" else "") else "自动"
                return StreamInfo(selected.url, key, qualityDesc)
            }
        }

        val raw = model.opt("fallback_api") ?: return null
        val fallback = when (raw) {
            is JSONObject -> raw.str("fallback_api")
            is JSONArray -> raw.optString(0)
            else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString())
        }
        if (!fallback.startsWith("https://")) return null
        val info = try { JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") } catch (_: Exception) { null } ?: return null
        val videoList = info.optJSONObject("video_list") ?: return null
        var hasIncompatible = false
        val rows = videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            val codec = it.second.str("codec_type").lowercase()
            if (codec in setOf("bytevc2", "bvc2")) {
                hasIncompatible = true
                false
            } else {
                it.second.str("main_url").isNotEmpty()
            }
        }.map { (k, o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }), k, o) }.sortedByDescending { it.first }.toList()
        if (rows.isEmpty() && hasIncompatible) throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val selected = rows.firstOrNull { it.first <= maxQuality } ?: rows.lastOrNull() ?: return null
        val item = selected.third
        var url = cleanUrl(item.str("main_url"))
        val seed = info.str("key_seed")
        if (seed.isNotEmpty()) url = MediaCrypto.decryptUrl(url, decode64(seed))
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key = item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality = if (selected.first > 0) "${selected.first}P" + (if (selected.first > maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url, key, quality)
    }

    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        try {
            val preload = fetchPreloadStream(id, maxQuality)
            if (preload != null) return preload
        } catch (e: IOException) {
            if (e.message?.contains("没有电视可播放的清晰度") == true) throw e
        } catch (_: Exception) {}

        try {
            val payload = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
            val signed = signer.sign("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/", VendorConstants.device, payload)
            val dataStr = text(signed.url, signed)
            if (dataStr.isNotBlank()) {
                val data = JSONObject(dataStr)
                if (data.optInt("Code", data.optInt("code", 0)) == 0) {
                    val map = data.optJSONObject("data")
                    val entry = map?.optJSONObject(id) ?: map?.keys()?.asSequence()?.mapNotNull { map.optJSONObject(it) }?.firstOrNull()
                    if (entry != null) {
                        val model = entry.optJSONObject("video_model") ?: runCatching { JSONObject(entry.str("video_model")) }.getOrNull()
                        if (model != null) {
                            val stream = parseModelStreams(model, maxQuality)
                            if (stream != null) return stream
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (e.message?.contains("没有电视可播放的清晰度") == true) throw e
        } catch (_: Exception) {}

        return StreamInfo("$site/video/public/$id/360p.mp4", null, "360P")
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
