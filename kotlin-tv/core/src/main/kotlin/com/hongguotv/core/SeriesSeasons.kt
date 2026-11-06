// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object SeriesSeasons {
    private val CN_DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )
    private const val CN_TEN = '十'

    private val SUFFIX = Regex(
        "[\\s·\\-—_]*(?:[（(\\[【]\\s*)?(?:第\\s*([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})\\s*[季部期]" +
            "|([0-9]{1,2})\\s*[季部期]" +
            "|(?i:season)\\s*([0-9]{1,2})" +
            "|(?i:s)\\s*([0-9]{1,2}))(?:\\s*[）)\\]】])?\\s*$"
    )

    internal fun chineseNumber(value: String): Int? {
        if (value.isEmpty()) return null
        if (value.all { it.isDigit() }) return value.toIntOrNull()
        val ten = value.indexOf(CN_TEN)
        if (ten < 0) {
            var sum = 0
            for (ch in value) sum = sum * 10 + (CN_DIGITS[ch] ?: return null)
            return sum
        }
        val head = value.substring(0, ten)
        val tail = value.substring(ten + 1)
        val tens = if (head.isEmpty()) 1 else (CN_DIGITS[head.single()] ?: return null)
        val ones = if (tail.isEmpty()) 0 else (CN_DIGITS[tail.single()] ?: return null)
        return tens * 10 + ones
    }

    fun toChineseNumber(n: Int): String = when {
        n <= 0 -> n.toString()
        n < 10 -> listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[n]
        n == 10 -> "十"
        n in 11..19 -> "十" + listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[n - 10]
        n in 20..99 -> {
            val tens = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[n / 10]
            val ones = if (n % 10 == 0) "" else listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")[n % 10]
            "${tens}十$ones"
        }
        else -> n.toString()
    }

    data class Parsed(val base: String, val season: Int, val matched: Boolean)

    fun parse(title: String): Parsed {
        val clean = title.trim()
        val hit = SUFFIX.find(clean) ?: return Parsed(clean, 1, false)
        val raw = hit.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return Parsed(clean, 1, false)
        val number = chineseNumber(raw) ?: return Parsed(clean, 1, false)
        if (number <= 0 || number > 99) return Parsed(clean, 1, false)
        return Parsed(clean.substring(0, hit.range.first).trim(), number, true)
    }

    fun base(title: String): String = parse(title).base

    fun season(title: String): Int = parse(title).season

    fun badge(title: String): String = "第 " + season(title) + " 季"

    fun label(seriesTitle: String, baseTitle: String = base(seriesTitle)): String {
        val p = parse(seriesTitle)
        if (p.matched) return "第 ${p.season} 季"
        val cleanBase = base(baseTitle).trim()
        val cleanSeries = seriesTitle.trim()
        if (cleanBase.isNotEmpty() && cleanSeries.startsWith(cleanBase) && cleanSeries.length > cleanBase.length) {
            val rest = cleanSeries.substring(cleanBase.length).trim().trimStart('：', ':', '·', '-', '—', '_', ' ')
            if (rest.isNotEmpty()) {
                val shortRest = if (rest.length > 8) rest.take(8) + "…" else rest
                return shortRest
            }
        }
        return "第 1 季"
    }

    fun normalize(title: String): String = normalizeRaw(base(title))

    private fun normalizeRaw(text: String): String = buildString {
        for (ch in text) {
            val half = when (ch) {
                in '０'..'９' -> ch - 0xFEE0
                in 'Ａ'..'Ｚ' -> ch - 0xFEE0
                in 'ａ'..'ｚ' -> ch - 0xFEE0
                '　' -> ' '
                else -> ch
            }
            if (half.isWhitespace()) continue
            if (half in "，,。.、；;：:！!？?·-—_~～()（）[]【】《》\"'“”‘’") continue
            append(half.lowercaseChar())
        }
    }

    private fun isSubtitleSpinOff(baseTitle: String, candidateTitle: String): Boolean {
        val cleanBase = base(baseTitle).trim()
        val cleanCand = candidateTitle.trim()
        if (cleanBase.length < 3 || !cleanCand.startsWith(cleanBase) || cleanCand.length <= cleanBase.length) return false
        val rest = cleanCand.substring(cleanBase.length).trim()
        return rest.startsWith("：") || rest.startsWith(":")
    }

    fun siblings(currentTitle: String, candidates: List<Series>): List<Series> {
        val parsedCurrent = parse(currentTitle)
        val baseTitle = if (!parsedCurrent.matched && (currentTitle.contains('：') || currentTitle.contains(':'))) {
            val prefix = currentTitle.substringBefore('：').substringBefore(':').trim()
            if (prefix.length >= 3 && candidates.any { parse(it.title).let { p -> p.matched && normalizeRaw(p.base) == normalizeRaw(prefix) } }) {
                prefix
            } else {
                parsedCurrent.base
            }
        } else {
            parsedCurrent.base
        }
        val key = normalizeRaw(baseTitle)
        if (key.isEmpty()) return emptyList()
        return candidates
            .filter { cand ->
                normalize(cand.title) == key || isSubtitleSpinOff(baseTitle, cand.title)
            }
            .sortedWith(
                compareBy<Series> {
                    val p = parse(it.title)
                    when {
                        p.matched -> p.season
                        normalize(it.title) == key -> 1
                        else -> 999
                    }
                }.thenBy { it.title }
            )
    }
}
