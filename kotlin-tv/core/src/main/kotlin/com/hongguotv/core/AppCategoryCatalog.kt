// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class GenreFilter(val id: String, val label: String)

object AppCategoryCatalog {
    const val CATEGORY_PATH = "/reading/distribution/category/landpage/v/"
    const val NEW_TODAY_ID = "new_today"
    private val validGenreSlug = Regex("^cate_[0-9]{1,8}$")

    val fallbackComicGenres = listOf(
        GenreFilter("", "全部推荐"),
        GenreFilter(NEW_TODAY_ID, "今日上新"),
        GenreFilter("cate_14000018", "奇幻玄幻"),
        GenreFilter("cate_14000024", "都市修仙"),
        GenreFilter("cate_14000016", "系统爽文"),
        GenreFilter("cate_14000015", "末世求生"),
        GenreFilter("cate_14000019", "重生逆袭")
    )

    val fallbackShortGenres = listOf(
        GenreFilter("", "全部推荐"),
        GenreFilter(NEW_TODAY_ID, "今日上新"),
        GenreFilter("cate_751", "逆袭"),
        GenreFilter("cate_739", "甜宠"),
        GenreFilter("cate_738", "战神"),
        GenreFilter("cate_741", "神医"),
        GenreFilter("cate_740", "重生")
    )

    fun defaultGenresFor(type: ContentType): List<GenreFilter> = when (type) {
        ContentType.COMIC -> fallbackComicGenres
        ContentType.SHORT -> fallbackShortGenres
    }

    fun sceneAndGenre(type: ContentType): Pair<String, String> = when (type) {
        ContentType.COMIC -> "comic_series" to "comic_series"
        ContentType.SHORT -> "default" to "short_play"
    }

    fun buildPayload(type: ContentType, genreId: String, offset: Int, sessionId: String, page: Int): JSONObject {
        val (reqScene, genre) = sceneAndGenre(type)
        val isNewToday = genreId == NEW_TODAY_ID
        val themeArray = JSONArray()
        if (!isNewToday && genreId.isNotEmpty() && validGenreSlug.matches(genreId)) {
            themeArray.put(genreId)
        }
        val selectItems = JSONObject()
            .put("genre", JSONArray().put(genre))
            .put("sort", JSONArray().put(if (isNewToday) "online_time" else "hot_score"))
            .put("category_dim_theme", themeArray)
            .put("category_dim_role", JSONArray())
            .put("category_dim_epoch", JSONArray())
            .put("gender", JSONArray())
            .put("online_time", if (isNewToday) JSONArray().put("days_7") else JSONArray())
            .put("creation_status", JSONArray())

        return JSONObject()
            .put("filter_ids", "")
            .put("req_scene", reqScene)
            .put("offset", offset)
            .put("need_selector_panel", page == 1 && !isNewToday)
            .put("limit", if (isNewToday) 30 else 20)
            .put("select_items", selectItems)
            .put("session_id", sessionId)
            .put("req_type", if (offset == 0) "default" else "only_content")
            .put("client_req_type", 3)
    }

    data class ParsedCategoryPage(
        val items: List<Series>,
        val genres: List<GenreFilter>,
        val hasMore: Boolean,
        val nextOffset: Int?,
        val sessionId: String
    )

    fun parseResponse(rawText: String, type: ContentType, currentOffset: Int, previousGenres: List<GenreFilter>): ParsedCategoryPage {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) throw IOException("App 分类接口返回空响应")
        val root = JSONObject(trimmed)
        if (!root.has("code") || root.optInt("code", -1) != 0) throw IOException("App 分类接口暂不可用")
        val data = root.optJSONObject("data") ?: throw IOException("App 分类缺少数据")
        val videoData = data.optJSONArray("video_data") ?: throw IOException("App 分类结构异常")

        val extractedGenres = mutableListOf(
            GenreFilter("", "全部推荐"),
            GenreFilter(NEW_TODAY_ID, "今日上新")
        )
        val selectorRows = data.optJSONArray("selector_rows")
        if (selectorRows != null && selectorRows.length() > 0) {
            val seen = linkedSetOf("", NEW_TODAY_ID)
            for (row in selectorRows.objects()) {
                if (row.str("type") != "category_dim_theme") continue
                val items = row.optJSONArray("items") ?: continue
                for (item in items.objects()) {
                    val slug = item.str("selector_item_id")
                    val label = item.str("show_name")
                    if (validGenreSlug.matches(slug) && label.isNotBlank() && seen.add(slug)) {
                        extractedGenres += GenreFilter(slug, label)
                    }
                }
            }
        }
        val finalGenres = when {
            extractedGenres.size > 2 -> extractedGenres
            previousGenres.size > 2 -> previousGenres
            else -> defaultGenresFor(type)
        }

        val seriesList = mutableListOf<Series>()
        val seenIds = HashSet<String>()
        for (video in videoData.objects()) {
            val id = video.str("series_id")
            val title = video.str("title").ifEmpty { video.str("series_title").ifEmpty { video.str("series_name") } }
            if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank() || !seenIds.add(id)) continue
            val contentType = video.optInt("content_type", 0)
            if (type == ContentType.COMIC && contentType != 0 && contentType != 1004) continue
            if (type == ContentType.SHORT && contentType != 0 && contentType != 1 && contentType != 1004) continue

            val count = video.optInt("episode_cnt", 0)
            val labels = mutableListOf<String>()
            val tagInfoText = video.optJSONObject("tag_info")?.str("text").orEmpty()
            if (tagInfoText.isNotBlank()) labels += tagInfoText
            video.optJSONArray("sub_title_list")?.objects()?.forEach { sub ->
                val content = sub.str("content")
                if (content.isNotBlank() && content !in labels) labels += content
            }
            val releasedToday = "今日上新" in labels || "新剧" in labels
            val cleanTags = labels.filter { it != "新剧" && it != "今日上新" }.take(4).joinToString(" · ")
            val badgePrefix = if (releasedToday) "今日上新 · " else ""
            val badgeCount = if (count > 0) "全 $count 集" else video.str("episode_right_text").ifEmpty { "近期上新" }
            val vertical = if (video.has("vertical")) video.optBoolean("vertical") else null
            val isLandscape = if (vertical != null) !vertical else if (type == ContentType.COMIC) true else null
            val aiUsage = video.optInt("ai_usage_type", 0)
            val isAi = if (aiUsage > 0 || type == ContentType.COMIC) true else null

            seriesList += Series(
                id = id,
                title = title,
                cover = normalizeCoverUrl(video.str("cover").ifEmpty { video.str("series_cover") }),
                description = video.str("video_desc").ifEmpty { video.str("series_intro") },
                badge = badgePrefix + badgeCount,
                tags = cleanTags,
                isAi = isAi,
                isLandscape = isLandscape
            ).also(SeriesMediaTrait::recordSeriesMetadata)
        }

        val hasMoreRaw = data.optBoolean("has_more", false)
        val nextOffset = data.optInt("next_offset", -1).takeIf { it > currentOffset }
        val nextSessionId = data.str("session_id")
        val validNext = hasMoreRaw && nextOffset != null && nextSessionId.isNotBlank() && nextSessionId.length <= 4096
        return ParsedCategoryPage(
            items = seriesList,
            genres = finalGenres,
            hasMore = validNext,
            nextOffset = if (validNext) nextOffset else null,
            sessionId = if (validNext) nextSessionId else ""
        )
    }
}
