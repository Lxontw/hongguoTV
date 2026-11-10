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

    @Test fun officialCommentListPayloadAndSigningProduceExpectedHeadersAndItems() {
        val fixedNonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        val req = DanmakuRepository.buildSignedCommentRequest(
            vid = "7704236617325612056",
            bookId = "7697561772433099800",
            startMs = 0L,
            durationMs = 3_600_000L,
            deviceId = "1905892595378490001",
            installId = "1905892595382586002",
            stampMs = 1_762_800_000_123L,
            nonce = fixedNonce
        )
        assertTrue(req.url.startsWith("https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/7704236617325612056/v1/?"))
        assertEquals("601", req.headers["Comment-Source"])
        assertEquals("1000", req.headers["Server-Channel"])
        assertEquals("", req.headers["X-SS-STUB"])
        assertTrue(req.headers["X-Gorgon"]?.startsWith("8404") == true)
        assertTrue(req.headers["X-Argus"]?.isNotBlank() == true)
        assertTrue(req.headers["X-Ladon"]?.isNotBlank() == true)
        assertNotNull(req.body)

        val samplePayload = """
            {
              "code": 0,
              "data": {
                "data_list": [
                  {
                    "comment": {
                      "common": {
                        "group_id": "7704236617325612056",
                        "status": "1",
                        "content": { "text": "重生密码正确" }
                      },
                      "expand": { "offset_time": 1520 }
                    }
                  },
                  {
                    "comment": {
                      "common": {
                        "group_id": "7704236617325612056",
                        "status": "2",
                        "content": { "text": "未审核脏数据" }
                      },
                      "expand": { "offset_time": 2000 }
                    }
                  }
                ],
                "common_list_info": {
                  "has_more": true,
                  "cursor": "{\"end_offset_time\":180000}"
                }
              }
            }
        """.trimIndent()
        val (items, hasMore, nextMs) = DanmakuRepository.parsePage(samplePayload, "7704236617325612056")
        assertEquals(1, items.size)
        assertEquals(DanmakuRaw(1520L, "重生密码正确"), items[0])
        assertTrue(hasMore)
        assertEquals(180000, nextMs)
    }
}
