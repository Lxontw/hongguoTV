// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.ConcurrentHashMap

enum class DramaNature(val label: String, val badgeColor: Int) {
    AI("AI", 0xFF6366F1.toInt()),          // 靛藍 (AI 製作)
    COMIC("漫劇", 0xFF8B5CF6.toInt()),       // 紫色 (動態漫/漫劇)
    REAL("真人", 0xFF0D9488.toInt())         // 墨綠青色 (真人實拍)
}

enum class ScreenOrientation(val label: String, val ratioText: String, val isLandscape: Boolean) {
    PORTRAIT("直式", "9:16", false),
    LANDSCAPE("橫式", "16:9", true);

    val iconText: String get() = ratioText
}

object SeriesMediaTrait {
    // 全域快取：seriesId -> 已知的解析度寬高比 (width / height)
    private val aspectCache = ConcurrentHashMap<String, Float>()
    // 全域快取：seriesId -> 是否已知為 AI / 漫劇
    private val natureCache = ConcurrentHashMap<String, DramaNature>()
    // 全域快取：seriesId -> 已知的播放方向 (直式/橫式)
    private val orientationCache = ConcurrentHashMap<String, ScreenOrientation>()

    fun recordVideoDimension(seriesId: String, width: Int, height: Int) {
        if (seriesId.isNotBlank() && width > 0 && height > 0) {
            val ratio = width.toFloat() / height.toFloat()
            aspectCache[seriesId] = ratio
            orientationCache[seriesId] = if (ratio >= 1.15f) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT
        }
    }

    fun recordNature(seriesId: String, nature: DramaNature) {
        if (seriesId.isNotBlank()) {
            natureCache[seriesId] = nature
        }
    }

    fun recordOrientation(seriesId: String, orientation: ScreenOrientation) {
        if (seriesId.isNotBlank()) {
            orientationCache[seriesId] = orientation
        }
    }

    fun recordSeriesMetadata(series: Series) {
        if (series.id.isBlank()) return
        if (series.isLandscape != null) {
            orientationCache[series.id] = if (series.isLandscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT
        }
        val text = "${series.title} ${series.tags} ${series.description}".lowercase()
        val epCount = Regex("([0-9]{2,4})\\s*集").find(series.badge)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (COMIC_KEYWORDS.any { text.contains(it) } || epCount >= 115 || (series.isLandscape == true && FANTASY_COMIC_KEYWORDS.any { text.contains(it) })) {
            natureCache[series.id] = DramaNature.COMIC
        } else if (series.isAi == true) {
            natureCache.putIfAbsent(series.id, DramaNature.AI)
        }
    }

    // 動態漫 / 動畫 / 漫畫 特徵關鍵字
    private val COMIC_KEYWORDS = listOf(
        "漫剧", "动漫", "动画", "动态漫", "漫画", "二次元", "3d", "动画片"
    )

    // AI 生成劇特徵關鍵字
    private val AI_KEYWORDS = listOf(
        "ai", "aigc", "虚拟", "ai生成", "算法"
    )

    // 修仙、玄幻、大妖、末世、無限流等動漫題材關鍵字（微短劇中這些題材幾乎全是動態漫/AI動漫）
    private val FANTASY_COMIC_KEYWORDS = listOf(
        "修仙", "修真", "灵根", "杂灵根", "仙界", "凡尘", "仙尊", "神尊",
        "至高", "混元", "渡劫", "魔神", "天道", "洪荒", "宗门", "炼丹",
        "飞升", "道侣", "老祖", "法器", "神王", "武魂", "斗罗", "斗破",
        "聚宝盆", "仙盆", "修罗", "吞噬", "遮天", "完美世界",
        "大妖", "万妖", "百妖", "捉妖", "斩妖", "鎮魔", "镇魔", "妖魔",
        "诡异", "無限流", "无限流", "神明", "成神", "封神", "灵兽", "御兽",
        "玄幻", "仙侠", "异界", "系统", "末世"
    )

    private val LANDSCAPE_KEYWORDS = listOf(
        "横屏", "横版", "微电影", "电影", "16:9", "宽屏", "动画", "动态漫", "漫剧", "3d"
    )

    private val PORTRAIT_KEYWORDS = listOf(
        "竖屏", "竖版", "9:16"
    )

    /**
     * 辨識劇集本質：真人影片 vs AI / 漫劇
     */
    fun detectNature(series: Series): DramaNature {
        natureCache[series.id]?.let { return it }

        if (series.isAi == true) {
            recordSeriesMetadata(series)
            return natureCache[series.id] ?: DramaNature.AI
        }

        val text = "${series.title} ${series.tags} ${series.description}".lowercase()

        // 1. 直接命中漫畫、動漫、動態漫、3D
        if (COMIC_KEYWORDS.any { text.contains(it) }) return DramaNature.COMIC

        // 2. 命中 AI、AIGC
        if (AI_KEYWORDS.any { text.contains(it) }) return DramaNature.AI

        // 3. 集數特徵：一般真人短劇多為 60~105 集，超過 115 集以上的微短劇（特別是長篇連載或多季劇）幾乎為動態漫/漫劇
        val episodeCount = Regex("([0-9]{2,4})\\s*集").find(series.badge)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (episodeCount >= 115) {
            return DramaNature.COMIC
        }

        // 4. 命中修仙/玄幻/大妖/末世等動漫題材（若未明確標記為直屏真人）
        if (series.isLandscape != false && FANTASY_COMIC_KEYWORDS.any { text.contains(it) }) {
            // 若是現代都市愛情/總裁且集數 <= 105 且無妖魔神明關鍵字，才保留為真人
            val isPureRomance = (text.contains("总裁") || text.contains("都市爱情") || text.contains("豪门") || text.contains("闪婚"))
                && !text.contains("妖") && !text.contains("仙") && !text.contains("魔") && !text.contains("神明") && !text.contains("末世")
            if (!isPureRomance) {
                return DramaNature.COMIC
            }
        }

        return DramaNature.REAL
    }

    /**
     * 辨識螢幕播放方向：直式 (9:16) vs 橫式 (16:9)
     */
    fun detectOrientation(series: Series, aspectRatio: Float? = null): ScreenOrientation {
        // 1. 優先讀取動態快取或傳入之寬高比（真實播放流解析度最準確）
        val cachedRatio = aspectRatio ?: aspectCache[series.id]
        if (cachedRatio != null && cachedRatio > 0f) {
            return if (cachedRatio >= 1.15f) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT
        }

        // 2. Series 模型中自帶的 API 判定（例如搜尋 API vertical 欄位或漫劇榜）
        if (series.isLandscape != null) {
            val orient = if (series.isLandscape) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT
            if (series.id.isNotBlank()) orientationCache[series.id] = orient
            return orient
        }

        // 3. 讀取先前從片單/搜尋快取下來的方向
        orientationCache[series.id]?.let { return it }

        val text = "${series.title} ${series.tags} ${series.description}".lowercase()

        // 4. 明確標記直屏
        if (PORTRAIT_KEYWORDS.any { text.contains(it) }) return ScreenOrientation.PORTRAIT

        // 5. 明確標記橫屏
        if (LANDSCAPE_KEYWORDS.any { text.contains(it) }) return ScreenOrientation.LANDSCAPE

        // 6. 漫劇、動漫、AI修仙劇幾乎全為橫屏 16:9
        val nature = detectNature(series)
        if (nature == DramaNature.COMIC || nature == DramaNature.AI) {
            return ScreenOrientation.LANDSCAPE
        }

        // 7. 預設傳統短劇為直屏
        return ScreenOrientation.PORTRAIT
    }
}
