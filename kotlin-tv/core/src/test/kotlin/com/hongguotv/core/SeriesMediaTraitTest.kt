// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesMediaTraitTest {

    @Test
    fun testDetectNatureRealVsAi() {
        val realSeries = Series("1", "都市无敌战神", "", "战神归来横扫天下", "全80集", "都市 · 战神 · 逆袭")
        assertEquals(DramaNature.REAL, SeriesMediaTrait.detectNature(realSeries))

        val aiSeries = Series("2", "AI修仙：我用算法推演诸天", "", "首部AI生成短剧", "全60集", "AI · 科幻 · 修仙")
        assertEquals(DramaNature.AI, SeriesMediaTrait.detectNature(aiSeries))

        val comicSeries = Series("3", "斗罗封神榜", "", "热门动态漫改编", "全100集", "动漫 · 玄幻 · 动态漫")
        assertEquals(DramaNature.COMIC, SeriesMediaTrait.detectNature(comicSeries))
    }

    @Test
    fun testDetectOrientation() {
        val normalShort = Series("1", "战神归来", "", "", "全80集", "都市")
        assertEquals(ScreenOrientation.PORTRAIT, SeriesMediaTrait.detectOrientation(normalShort))

        val landscapeShort = Series("2", "夜幕潜行（横屏微电影）", "", "", "全10集", "微电影 · 悬疑")
        assertEquals(ScreenOrientation.LANDSCAPE, SeriesMediaTrait.detectOrientation(landscapeShort))

        // 根據寬高比直接判斷
        assertEquals(ScreenOrientation.LANDSCAPE, SeriesMediaTrait.detectOrientation(normalShort, 1920f / 1080f))
        assertEquals(ScreenOrientation.PORTRAIT, SeriesMediaTrait.detectOrientation(normalShort, 1080f / 1920f))
    }
}
