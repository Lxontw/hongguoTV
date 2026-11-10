// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.text.Normalizer

data class DanmakuRaw(val offsetMs: Long, val text: String)

data class DanmakuItem(val offsetMs: Long, val text: String, val count: Int = 1) {
    val displayText: String
        get() = if (count > 1) "$text × $count" else text
}

object DanmakuRepository {
    const val MAX_ITEMS_PER_EPISODE = 270
    const val MAX_TEXT_LENGTH = 100
    const val MAX_OFFSET_MS = 10_800_000L // 3 hours
    const val MAX_PAGES = 3

    private val stripRegex = Regex("[\\s\\p{P}\\p{S}\\p{C}]+")

    internal fun normalizeKey(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        return stripRegex.replace(nfkc, "")
    }

    private class Bucket(val minOffsetMs: Long, val firstText: String, var count: Int)

    fun mergeAndSort(raw: List<DanmakuRaw>): List<DanmakuItem> {
        val buckets = LinkedHashMap<String, Bucket>()
        for (item in raw) {
            if (item.offsetMs < 0L || item.offsetMs > MAX_OFFSET_MS) continue
            val cleanText = item.text.replace('\n', ' ').replace('\r', ' ').trim()
            if (cleanText.isEmpty() || cleanText.length > MAX_TEXT_LENGTH) continue
            val key = normalizeKey(cleanText).ifEmpty { cleanText }
            val existing = buckets[key]
            if (existing == null) {
                buckets[key] = Bucket(item.offsetMs, cleanText, 1)
            } else {
                existing.count += 1
                if (item.offsetMs < existing.minOffsetMs) {
                    buckets[key] = Bucket(item.offsetMs, existing.firstText, existing.count)
                }
            }
        }
        return buckets.values
            .sortedBy { it.minOffsetMs }
            .take(MAX_ITEMS_PER_EPISODE)
            .map { DanmakuItem(it.minOffsetMs, it.firstText, it.count) }
    }

    fun parsePage(rawJson: String): Triple<List<DanmakuRaw>, Boolean, Int?> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return Triple(emptyList(), false, null)
        val root = JSONObject(trimmed)
        if (root.optInt("code", -1) != 0) return Triple(emptyList(), false, null)
        val data = root.optJSONObject("data") ?: root
        val list = data.optJSONArray("data_list")
            ?: data.optJSONArray("comment_list")
            ?: data.optJSONArray("list")
            ?: return Triple(emptyList(), false, null)
        val items = mutableListOf<DanmakuRaw>()
        for (obj in list.objects()) {
            val comment = obj.optJSONObject("comment") ?: obj
            val contentObj = comment.optJSONObject("content_info") ?: comment
            val text = contentObj.str("content").ifEmpty { comment.str("text") }.trim()
            if (text.isEmpty()) continue
            val posMs = when {
                comment.has("video_position") -> {
                    val v = comment.optDouble("video_position", -1.0)
                    if (v > 10000.0 && !comment.str("video_position").contains('.')) v.toLong() else (v * 1000.0).toLong()
                }
                comment.has("offset_ms") -> comment.optLong("offset_ms", -1L)
                comment.has("danmu_time") -> comment.optLong("danmu_time", -1L)
                else -> (items.size * 2500L) + 1200L
            }
            if (posMs in 0..MAX_OFFSET_MS) {
                items += DanmakuRaw(posMs, text)
            }
        }
        val hasMore = data.optBoolean("has_more", false)
        val nextOffset = data.optInt("offset", -1).takeIf { it > 0 } ?: data.optInt("next_offset", -1).takeIf { it > 0 }
        return Triple(items, hasMore, nextOffset)
    }
}
