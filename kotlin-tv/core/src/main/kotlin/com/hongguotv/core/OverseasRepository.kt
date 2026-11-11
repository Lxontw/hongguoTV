// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Public English website data only. Locked episodes never yield a playback URL. */
internal class OverseasRepository(http: OkHttpClient, private val source: MediaSource) {
    private val reel = source == MediaSource.REELSHORT
    private val origin = if (reel) "https://www.reelshort.com" else "https://www.dramabox.com"
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(10, TimeUnit.SECONDS).build()
    private val name = source.label
    private fun fail(message: String): Nothing = throw IOException("$name $message")
    private fun plain(value: String) = Jsoup.parse(value).text().trim()
    private fun slug(title: String) = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim().replace(' ', '-')
    private fun safeUrl(value: String) = runCatching { MediaUrlGuard.assertSafeMediaUrl(value) }.getOrDefault("")
    private fun rows(obj: JSONObject, key: String): List<JSONObject> {
        val array = obj.optJSONArray(key) ?: fail("官网资料已变化，请稍后重试")
        if (array.length() > 10000) fail("官网资料数量异常")
        return (0 until array.length()).map { array.optJSONObject(it) ?: fail("官网资料格式异常") }
    }
    private fun request(path: String): JSONObject {
        fun fetch(url: String, redirectsLeft: Int): String {
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
                .header("Referer", "$origin/").header("Accept-Language", "en-US,en;q=0.9").build()
            return RequestScope.execute(client.newCall(request)) { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val target = response.header("Location")?.let { request.url.resolve(it) } ?: fail("官网转址无效")
                    if (redirectsLeft <= 0 || target.scheme != "https" || target.host != request.url.host || target.port != 443
                        || target.username.isNotEmpty() || target.password.isNotEmpty()) fail("官网转址不受支持")
                    return@execute fetch(target.toString(), redirectsLeft - 1)
                }
                if (response.code in listOf(403, 451)) fail("目前地区或网络暂时无法使用")
                if (!response.isSuccessful) fail("连接失败（${response.code}），请稍后重试")
                val body = response.body ?: fail("官网未返回资料")
                val limit = 8L * 1024 * 1024
                val input = body.source()
                input.request(limit + 1)
                if (input.buffer.size > limit) fail("官网资料过大")
                input.readUtf8()
            }
        }
        val html = fetch(origin + path, 3)
        val raw = Jsoup.parse(html).getElementById("__NEXT_DATA__")?.data() ?: fail("官网资料已变化")
        val data = JSONObject(raw)
        val props = data.optJSONObject("props")?.optJSONObject("pageProps") ?: fail("官网资料格式异常")
        if (!reel && props.str("locale").ifEmpty { data.str("locale") } != "en") fail("官网语言不匹配")
        return props
    }
    private fun book(obj: JSONObject): Series? {
        val id = if (reel) obj.str("book_id").ifEmpty { obj.str("_id") } else obj.str("bookId")
        val title = plain(if (reel) obj.str("book_title") else obj.str("bookName").ifEmpty { obj.str("name") })
        if (title.isBlank() || !Regex(if (reel) "[a-zA-Z0-9_]{1,100}" else "[0-9]{1,24}").matches(id)) return null
        val path = slug(title)
        if (reel && (path.length !in 1..200 || !Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(path))) return null
        val fullId = if (reel) "reelshort:$id:$path" else "dramabox:$id"
        val cover = if (reel) obj.str("book_pic") else obj.str("cover").ifEmpty { obj.str("coverWap") }
        return Series(fullId, title, safeUrl(cover), plain(if (reel) obj.str("special_desc") else obj.str("introduction")),
            "公开试看", "$name · 英文")
    }
    private fun catalog(books: List<JSONObject>, hasMore: Boolean = false): CatalogPage {
        val items = books.take(500).mapNotNull(::book).distinctBy { it.id }
        if (books.isNotEmpty() && items.isEmpty()) fail("片单缺少有效剧集编号")
        return CatalogPage(items, hasMore && items.isNotEmpty())
    }
    private fun dramaPage(props: JSONObject, page: Int, pagesKey: String): CatalogPage {
        val pages = props.optInt(pagesKey, -1)
        if (props.optInt("pageNo", -1) != page || pages < 0) fail("官网分页与请求不符")
        return catalog(rows(props, "bookList"), page < pages)
    }
    fun home(page: Int): CatalogPage {
        require(page in 1..10000)
        if (!reel) return dramaPage(request(if (page == 1) "/browse" else "/browse/0/$page"), page, "pages")
        if (page > 1) return CatalogPage(emptyList(), false)
        val shelves = request("/").optJSONObject("fallback")?.optJSONObject("/api/ms/hall/webInfo") ?: fail("官网片单格式已变化")
        val books = rows(shelves, "bookShelfList").flatMap { if (it.optJSONArray("books") != null) rows(it, "books") else emptyList() }.distinctBy { plain(it.str("book_title")) }.take(12)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        val items = books.map { raw ->
            book(raw) ?: run {
                if (System.nanoTime() > deadline) fail("片单加载超时，请按完整剧名搜索")
                val title = plain(raw.str("book_title"))
                search(title, 1).items.singleOrNull { it.title.equals(title, ignoreCase = true) }
                    ?: fail("片单缺少唯一编号，请按完整剧名搜索")
            }
        }
        return CatalogPage(items.distinctBy { it.id }, false)
    }
    fun search(keyword: String, page: Int): CatalogPage {
        require(keyword.isNotBlank() && keyword.length <= 200 && page in 1..50)
        if (reel && page > 1) return CatalogPage(emptyList(), false)
        val encoded = Signer.encode(keyword)
        val props = request(if (reel) "/search?keywords=$encoded" else "/search?searchValue=$encoded&page=$page")
        return if (reel) catalog(rows(props, "books")) else dramaPage(props, page, "totalPage")
    }
    private fun parts(id: String, episode: Boolean = false): List<String> {
        val parts = id.split(':')
        val pattern = if (reel) Regex("reelshort:[a-zA-Z0-9_]{1,100}:[a-z0-9]+(?:-[a-z0-9]+)*(?::[1-9][0-9]{0,3}:[a-zA-Z0-9_]{1,100})?")
            else Regex("dramabox:[0-9]{1,24}(?::[0-9]{1,24})?")
        if (!pattern.matches(id) || parts.size != (if (reel) { if (episode) 5 else 3 } else { if (episode) 3 else 2 }) || (reel && parts[2].length > 200)) fail("剧集编号无效")
        return parts
    }
    private data class Episode(val id: String, val number: Int, val locked: Boolean, val raw: JSONObject)
    private data class Show(val series: Series, val episodes: List<Episode>)
    private fun locked(obj: JSONObject) = listOf("locked", "is_locked", "is_lock", "isCharge").any { obj.opt(it) in listOf(true, 1, "1") }
    private fun show(id: String): Show {
        val p = parts(id)
        val props = request(if (reel) "/movie/${p[2]}-${p[1]}" else "/drama/${p[1]}")
        val raw = props.optJSONObject(if (reel) "data" else "bookInfo") ?: fail("剧集资料为空")
        val series = book(raw) ?: fail("剧集资料无效")
        // Slug is a route hint; the platform's stable book ID must match.
        if (series.id.split(':')[1] != p[1]) fail("返回的剧集与请求不一致")
        val chapters = rows(if (reel) raw else props, if (reel) "online_base" else "chapterList")
        val episodes = chapters.map { ep ->
            val cid = ep.str(if (reel) "chapter_id" else "id")
            val number = ep.optInt(if (reel) "serial_number" else "index", -1) + if (reel) 0 else 1
            if (number !in 1..9999 || !Regex(if (reel) "[a-zA-Z0-9_]{1,100}" else "[0-9]{1,24}").matches(cid)) fail("分集编号无效")
            Episode(if (reel) "$id:$number:$cid" else "$id:$cid", number, if (reel) locked(ep) else ep.opt("unlock") != true, ep)
        }.sortedBy { it.number }
        if (episodes.isEmpty() || episodes.distinctBy { it.id }.size != episodes.size || episodes.distinctBy { it.number }.size != episodes.size) fail("分集编号重复或为空")
        if (!reel && (raw.optInt("chapterCount", raw.optInt("totalChapterNum", -1)) != episodes.size || episodes.map { it.number } != (1..episodes.size).toList())) fail("分集数量不完整")
        return Show(series.copy(id = id), episodes)
    }
    fun detail(id: String): Detail {
        val show = show(id)
        return Detail(show.series, show.episodes.map { it.id })
    }
    fun stream(id: String): StreamInfo {
        val p = parts(id, true)
        val seriesId = p.take(if (reel) 3 else 2).joinToString(":")
        val episode = show(seriesId).episodes.firstOrNull { it.id == id } ?: fail("找不到该集")
        if (episode.locked) fail("本集需在官方平台确认观看权限")
        val raw = if (reel) {
            val data = request("/episodes/episode-${p[3]}-${p[2]}-${p[1]}-${p[4]}?play_time=1").optJSONObject("data") ?: fail("分集资料为空")
            if (data.optInt("serial_number") != episode.number || (data.str("chapter_id").isNotEmpty() && data.str("chapter_id") != p[4])) fail("返回的分集与请求不一致")
            if (locked(data)) fail("本集需在官方平台确认观看权限")
            data
        } else episode.raw
        val url = safeUrl(raw.str(if (reel) "video_url" else "mp4"))
        if (url.isEmpty()) fail("本集未提供公开播放地址")
        val uri = URI(url)
        if (!reel && !(uri.host == "dramaboxdb.com" || uri.host.endsWith(".dramaboxdb.com"))) fail("播放地址不是官方来源")
        val hls = reel && uri.path.endsWith(".m3u8", ignoreCase = true)
        if ((!hls && !uri.path.endsWith(".mp4", ignoreCase = true)) || (!reel && (raw.optBoolean("m3u8Flag") || uri.path.contains("encrypt", ignoreCase = true)))) fail("本集暂未提供电视版支持的公开影片格式")
        return StreamInfo(url, null, "公开试看", referer = "$origin/", userAgent = "Mozilla/5.0", hls = hls)
    }
}
