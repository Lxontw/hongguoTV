package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DanmakuRepositoryTest {
    @Test fun duplicatesAreMergedWithCountAndSortedByEarliestTimestamp() {
        val raw = listOf(
            DanmakuRaw(5200L, "前方高能！"),
            DanmakuRaw(1200L, "太好看了吧"),
            DanmakuRaw(2400L, "前方高能"),
            DanmakuRaw(3800L, "前方高能!! ")
        )
        val merged = DanmakuRepository.mergeAndSort(raw)
        assertEquals(2, merged.size)
        assertEquals(DanmakuItem(1200L, "太好看了吧", 1), merged[0])
        assertEquals("太好看了吧", merged[0].displayText)
        assertEquals(DanmakuItem(2400L, "前方高能！", 3), merged[1])
        assertEquals("前方高能！ × 3", merged[1].displayText)
    }

    @Test fun oversizedOrInvalidTimestampItemsAreFilteredAndCappedAt270() {
        val raw = (1..350).map { DanmakuRaw(it * 100L, "弹幕编号$it") } +
            listOf(
                DanmakuRaw(-10L, "负数时间"),
                DanmakuRaw(1000L, "a".repeat(120))
            )
        val merged = DanmakuRepository.mergeAndSort(raw)
        assertEquals(270, merged.size)
    }

    @Test fun networkFailureInDanmakuReturnsEmptyListWithoutThrowing() {
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor {
                throw IOException("danmaku network timeout")
            }.build()
        )
        val items = repo.danmaku("123456")
        assertTrue(items.isEmpty())
    }

    @Test fun nonNumericExternalEpisodeIdReturnsEmptyDanmakuImmediately() {
        var called = false
        val repo = ContentRepository(
            OkHttpClient.Builder().addInterceptor { chain ->
                called = true
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("{}".toResponseBody()).build()
            }.build()
        )
        assertTrue(repo.danmaku("xifan:1:abc:ep1").isEmpty())
        assertFalse(called)
    }
}
