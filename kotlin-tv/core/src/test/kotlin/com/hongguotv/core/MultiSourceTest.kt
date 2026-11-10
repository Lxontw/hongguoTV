package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MultiSourceTest {
    private fun repository(respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()
    )

    @Test fun xifanHomeDetailAndStreamRouteCorrectlyAndEnforceHttps() {
        val repo = repository { req ->
            when {
                req.url.queryParameter("act") == "recommend" -> {
                    JSONObject().put("data", JSONArray().put(
                        JSONObject().put("source", "2").put("duanju_id", "xf_998").put("title", "稀饭测试短剧").put("total_num", 50)
                    )).toString()
                }
                req.url.queryParameter("duanju_id") == "xf_998" -> {
                    JSONObject().put("data", JSONObject()
                        .put("title", "稀饭测试短剧")
                        .put("episodes", JSONArray()
                            .put(JSONObject().put("video_id", "ep_1"))
                            .put(JSONObject().put("video_id", "ep_2"))
                        )
                    ).toString()
                }
                req.url.queryParameter("video_id") == "ep_1" -> {
                    JSONObject().put("data", JSONObject().put("url", "https://cdn.example.com/xf_1.mp4")).toString()
                }
                req.url.queryParameter("video_id") == "ep_2" -> {
                    JSONObject().put("data", JSONObject().put("url", "http://127.0.0.1/unsafe.mp4")).toString()
                }
                else -> "{}"
            }
        }

        val page = repo.home(1, ContentType.SHORT, "", MediaSource.XIFAN)
        assertEquals(1, page.items.size)
        val series = page.items.single()
        assertEquals("xifan:2:xf_998", series.id)
        assertEquals("稀饭测试短剧", series.title)

        val detail = repo.detail(series.id)
        assertEquals(listOf("xifan:2:xf_998:ep_1", "xifan:2:xf_998:ep_2"), detail.episodes)

        val stream1 = repo.stream("xifan:2:xf_998:ep_1")
        assertEquals("https://cdn.example.com/xf_1.mp4", stream1.url)
        assertNull(stream1.key)

        assertThrows(IOException::class.java) {
            repo.stream("xifan:2:xf_998:ep_2")
        }
    }

    @Test fun hemaFiltersLockedChaptersAndStreamsFreeVodUrl() {
        val repo = repository { req ->
            val props = when {
                req.url.encodedPath.startsWith("/browse") -> {
                    JSONObject().put("bookList", JSONArray().put(
                        JSONObject().put("bookId", "77001").put("bookName", "河马霸总剧").put("totalChapterNum", 80)
                    )).put("totalPage", 5)
                }
                req.url.encodedPath == "/drama/77001" -> {
                    JSONObject()
                        .put("bookInfo", JSONObject().put("bookId", "77001").put("bookName", "河马霸总剧"))
                        .put("chapterList", JSONArray()
                            .put(JSONObject().put("chapterId", "101").put("isLock", false).put("chapterVideoVo", JSONObject().put("vodMp4Url", "https://vod.example.com/hm_101.mp4")))
                            .put(JSONObject().put("chapterId", "102").put("isLock", true).put("isPay", 1))
                        )
                }
                else -> JSONObject()
            }
            val nextData = JSONObject().put("props", JSONObject().put("pageProps", props))
            """<html><body><script id="__NEXT_DATA__" type="application/json">$nextData</script></body></html>"""
        }

        val page = repo.home(1, ContentType.SHORT, "", MediaSource.HEMA)
        assertEquals("hema:77001", page.items.single().id)

        val detail = repo.detail("hema:77001")
        // Locked chapter 102 with no vodMp4Url is filtered out!
        assertEquals(listOf("hema:77001:101"), detail.episodes)

        val stream = repo.stream("hema:77001:101")
        assertEquals("https://vod.example.com/hm_101.mp4", stream.url)
        assertNull(stream.key)
    }

    @Test fun libraryBackupAcceptsHongguoXifanAndHemaIdsAndPersistsNewSettings() {
        val s1 = Series("123456", "红果剧集")
        val s2 = Series("xifan:1:abc_99", "稀饭剧集")
        val s3 = Series("hema:88001", "河马剧集")
        val progress = BackupProgress(s2, "xifan:1:abc_99:ep_01", 0, 12000L, 60000L, false, 1700000000000L)
        val settings = BackupSettings(
            quality = 1080,
            speed = 1f,
            autoNext = true,
            type = ContentType.COMIC,
            frame = VideoFrameMode.FIT,
            seamlessNext = false,
            danmakuEnabled = true,
            mediaSource = MediaSource.XIFAN
        )
        val encoded = LibraryBackup.encode(
            BackupData(
                favorites = listOf(s1, s2, s3),
                history = listOf(progress),
                watched = setOf("xifan:1:abc_99"),
                searches = emptyList(),
                settings = settings
            )
        )
        val decoded = LibraryBackup.decode(encoded)
        assertEquals(listOf("123456", "xifan:1:abc_99", "hema:88001"), decoded.favorites.map { it.id })
        assertEquals("xifan:1:abc_99:ep_01", decoded.history.single().episodeId)
        assertTrue(decoded.settings.danmakuEnabled)
        assertEquals(MediaSource.XIFAN, decoded.settings.mediaSource)
    }
}
