package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class SeriesSeasonsTest {
    @Test
    fun parsesChineseAndArabicSeasons() {
        val p1 = SeriesSeasons.parse("天幕直播：古人每周来我家第四季")
        assertTrue(p1.matched)
        assertEquals("天幕直播：古人每周来我家", p1.base)
        assertEquals(4, p1.season)

        val p2 = SeriesSeasons.parse("天幕直播：古人每周来我家第1季")
        assertTrue(p2.matched)
        assertEquals("天幕直播：古人每周来我家", p2.base)
        assertEquals(1, p2.season)

        val p3 = SeriesSeasons.parse("天幕直播：古人每周来我家")
        assertFalse(p3.matched)
        assertEquals("天幕直播：古人每周来我家", p3.base)
        assertEquals(1, p3.season)
    }

    @Test
    fun groupsSiblingsInAscendingSeasonOrder() {
        val s1 = Series("1", "天幕直播：古人每周来我家第一季")
        val s2 = Series("2", "天幕直播：古人每周来我家第二季")
        val s3 = Series("3", "天幕直播：古人每周来我家第三季")
        val s4 = Series("4", "天幕直播：古人每周来我家第四季")
        val other = Series("99", "不同的短剧第一季")

        val siblings = SeriesSeasons.siblings("天幕直播：古人每周来我家第四季", listOf(s3, s1, other, s4, s2))
        assertEquals(listOf("1", "2", "3", "4"), siblings.map { it.id })
    }

    @Test
    fun parsesParenthesizedAndSpinOffSeasons() {
        val p1 = SeriesSeasons.parse("异界诡仙（第一季）")
        assertTrue(p1.matched)
        assertEquals("异界诡仙", p1.base)
        assertEquals(1, p1.season)

        val s1 = Series("1", "万妖图录传第一季")
        val s8 = Series("8", "万妖图录传第八季")
        val s14 = Series("14", "万妖图录传第十四季")
        val spinoff = Series("99", "万妖图录传：那妖魔是姜大人")
        val unrelated = Series("100", "万妖图录传之别人家的剧")

        val list = SeriesSeasons.siblings("万妖图录传第一季", listOf(s14, spinoff, unrelated, s1, s8))
        assertEquals(listOf("1", "8", "14", "99"), list.map { it.id })
        assertEquals("第 1 季", SeriesSeasons.label(s1.title, "万妖图录传"))
        assertEquals("第 14 季", SeriesSeasons.label(s14.title, "万妖图录传"))
        assertEquals("那妖魔是姜大人", SeriesSeasons.label(spinoff.title, "万妖图录传"))
    }

    @Test
    fun detectsOrientationAndCachesAcrossDetail() {
        val searchCard = Series("7701567939715025944", "满院亲戚全是上古大妖第六季", badge = "全 272 集", tags = "奇幻 · 都市", isAi = true, isLandscape = true)
        SeriesMediaTrait.recordSeriesMetadata(searchCard)
        // Even if detail response lacks isLandscape/isAi, cached orientation and >115 episodes keep it LANDSCAPE + COMIC
        val detailCard = Series("7701567939715025944", "满院亲戚全是上古大妖第六季", badge = "全272集", tags = "奇幻 · 都市", isAi = null, isLandscape = null)
        assertEquals(ScreenOrientation.LANDSCAPE, SeriesMediaTrait.detectOrientation(detailCard))
        assertEquals(DramaNature.COMIC, SeriesMediaTrait.detectNature(detailCard))

        // Real romance drama in search with vertical=true (isLandscape=false) stays PORTRAIT + REAL
        val romance = Series("7688352674999176254", "总裁是我前女友", badge = "全 80 集", tags = "恋爱 · 都市 · 职场", isAi = null, isLandscape = false)
        assertEquals(ScreenOrientation.PORTRAIT, SeriesMediaTrait.detectOrientation(romance))
        assertEquals(DramaNature.REAL, SeriesMediaTrait.detectNature(romance))
    }
}
