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
            when (req.url.encodedPath) {
                "/xifan/drama/portalPage" -> {
                    if (req.url.queryParameter("reqType") == "duanjuCategory") {
                        JSONObject().put("ret", 0).put("result", JSONObject().put("elements", JSONArray().put(
                            JSONObject().put("contents", JSONArray().put(
                                JSONObject().put("categoryItemVo", JSONObject()
                                    .put("categoryId", 68).put("oppoCategory", "都市").put("version", 1))
                            ))
                        ))).toString()
                    } else {
                        JSONObject().put("ret", 0).put("result", JSONObject()
                            .put("hasMore", true)
                            .put("elements", JSONArray().put(
                                JSONObject().put("contents", JSONArray().put(
                                    JSONObject().put("duanjuVo", JSONObject()
                                        .put("source", "tianqiao")
                                        .put("duanjuId", "opo181652")
                                        .put("title", "稀饭测试短剧")
                                        .put("total", 50)
                                        .put("categories", JSONArray().put("都市")))
                                ))
                            ))).toString()
                    }
                }
                "/xifan/search/getSearchList" -> {
                    JSONObject().put("ret", 0).put("result", JSONObject()
                        .put("hasMore", false)
                        .put("elements", JSONArray().put(
                            JSONObject().put("contents", JSONArray().put(
                                JSONObject().put("duanjuVo", JSONObject()
                                    .put("source", "tianqiao")
                                    .put("duanjuId", "opo181652")
                                    .put("title", "稀饭测试短剧")
                                    .put("total", 50))
                            ))
                        ))).toString()
                }
                "/xifan/drama/getDuanjuInfo" -> {
                    JSONObject().put("ret", 0).put("result", JSONObject()
                        .put("source", "tianqiao")
                        .put("duanjuId", "opo181652")
                        .put("title", "稀饭测试短剧")
                        .put("episodeList", JSONArray()
                            .put(JSONObject().put("episodeId", "ep_1").put("index", 1).put("unlock", true).put("playUrl", "https://cdn.example.com/xf_1.mp4"))
                            .put(JSONObject().put("episodeId", "ep_2").put("index", 2).put("unlock", true).put("playUrl", "http://127.0.0.1/unsafe.mp4"))
                            .put(JSONObject().put("episodeId", "ep_3").put("index", 3).put("unlock", false).put("playUrl", "https://cdn.example.com/xf_3.mp4"))
                        )).toString()
                }
                else -> "{}"
            }
        }

        val page = repo.home(1, ContentType.SHORT, "", MediaSource.XIFAN)
        assertEquals(1, page.items.size)
        assertEquals("都市", page.genres.single().label)
        val series = page.items.single()
        assertEquals("xifan:tianqiao:opo181652", series.id)
        assertEquals("稀饭测试短剧", series.title)

        val searched = repo.search("稀饭", 1, ContentType.SHORT, MediaSource.XIFAN)
        assertEquals("xifan:tianqiao:opo181652", searched.items.single().id)

        val detail = repo.detail(series.id)
        assertEquals(listOf("xifan:tianqiao:opo181652:ep_1", "xifan:tianqiao:opo181652:ep_2"), detail.episodes)

        val stream1 = repo.stream("xifan:tianqiao:opo181652:ep_1")
        assertEquals("https://cdn.example.com/xf_1.mp4", stream1.url)
        assertNull(stream1.key)

        assertThrows(IOException::class.java) {
            repo.stream("xifan:tianqiao:opo181652:ep_2")
        }
        assertThrows(IOException::class.java) {
            repo.stream("xifan:tianqiao:opo181652:ep_3")
        }
    }

    @Test fun hemaPostSearchAndGenresWorkCorrectly() {
        val repo = repository { req ->
            if (req.url.encodedPath == "/seo/video/6007") {
                assertEquals("POST", req.method)
                assertEquals("www.kuaikaw.cn", req.header("pname"))
                return@repository JSONObject().put("retCode", 0).put("data", JSONObject()
                    .put("isMore", 1)
                    .put("bookList", JSONArray().put(
                        JSONObject().put("bookId", "41000358232")
                            .put("bookName", "真假总裁")
                            .put("totalChapterNum", "96")
                            .put("bookTypeThree", JSONArray().put(JSONObject().put("name", "现代言情")))
                    ))).toString()
            }
            val props = JSONObject()
                .put("bookList", JSONArray().put(JSONObject().put("bookId", "77001").put("bookName", "河马分类剧").put("totalChapterNum", 60)))
                .put("typeThreeList", JSONArray().put(JSONObject().put("name", "甜宠").put("typeThreeIds", JSONArray().put(12).put(34))))
                .put("totalPage", 3)
            val nextData = JSONObject().put("props", JSONObject().put("pageProps", props))
            """<html><body><script id="__NEXT_DATA__" type="application/json">$nextData</script></body></html>"""
        }

        val home = repo.home(1, ContentType.SHORT, "12-34", MediaSource.HEMA)
        assertEquals("hema:77001", home.items.single().id)
        assertEquals(listOf("", "12-34"), home.genres.map { it.id })
        assertTrue(home.hasMore)

        val search = repo.search("总裁", 1, ContentType.SHORT, MediaSource.HEMA)
        val item = search.items.single()
        assertEquals("hema:41000358232", item.id)
        assertEquals("真假总裁", item.title)
        assertEquals("全 96 集", item.badge)
        assertEquals("河马 · 现代言情", item.tags)
        assertTrue(search.hasMore)
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
