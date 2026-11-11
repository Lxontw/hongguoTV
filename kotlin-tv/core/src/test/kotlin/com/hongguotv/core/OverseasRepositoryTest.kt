package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OverseasRepositoryTest {
    private fun repository(respond: (Request) -> String) = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        assertEquals("en-US,en;q=0.9", chain.request().header("Accept-Language"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("<script id='__NEXT_DATA__'>${respond(chain.request())}</script>".toResponseBody()).build()
    }.build())
    private fun props(json: String) = JSONObject().put("props", JSONObject().put("pageProps", JSONObject(json))).toString()
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IOException) {} }

    @Test fun dramaRoutesCatalogSearchDetailAndOnlyPublicMp4() {
        var unlocked = true
        var video = "https://cdn.dramaboxdb.com/demo.mp4"
        val repo = repository { request ->
            when (request.url.encodedPath) {
                "/browse", "/search" -> props("""{"locale":"en","pageNo":1,"pages":2,"totalPage":2,"bookList":[{"bookId":"123","bookName":"Demo"}]}""")
                "/drama/123" -> props("""{"locale":"en","bookInfo":{"bookId":"123","bookName":"Demo","chapterCount":1},"chapterList":[{"id":"456","index":0,"unlock":$unlocked,"mp4":"$video"}]}""")
                else -> error("Unexpected route ${request.url}")
            }
        }
        assertEquals("dramabox:123", repo.home(source=MediaSource.DRAMABOX).items.single().id)
        assertTrue(repo.search("Demo", source=MediaSource.DRAMABOX).hasMore)
        assertEquals(listOf("dramabox:123:456"), repo.detail("dramabox:123").episodes)
        assertEquals("https://www.dramabox.com/", repo.stream("dramabox:123:456").referer)
        unlocked = false
        rejects { repo.stream("dramabox:123:456") }
        unlocked = true
        video = "https://evil.example/demo.mp4"
        rejects { repo.stream("dramabox:123:456") }
        video = "https://cdn.dramaboxdb.com/encrypt/demo.mp4"
        rejects { repo.stream("dramabox:123:456") }
    }

    @Test fun reelRoutesShelfSearchEpisodeAndRejectsWrongOrLockedEpisode() {
        var locked = false
        var chapterId = "ep1"
        var videoUrl = "https://cdn.example.org/video.mp4"
        val book = """{"book_id":"abc","book_title":"My Show","online_base":[{"chapter_id":"ep1","serial_number":1}]}"""
        val repo = repository { req -> when(req.url.encodedPath) {
            "/" -> props("""{"fallback":{"/api/ms/hall/webInfo":{"bookShelfList":[{"type":0},{"books":[$book]}]}}}""")
            "/search" -> props("""{"books":[$book]}""")
            "/movie/my-show-abc" -> props("""{"data":$book}""")
            "/episodes/episode-1-my-show-abc-ep1" -> props("""{"data":{"serial_number":1,"chapter_id":"$chapterId","locked":$locked,"video_url":"$videoUrl"}}""")
            else -> error("Unexpected route ${req.url}")
        } }
        assertEquals("reelshort:abc:my-show", repo.home(source=MediaSource.REELSHORT).items.single().id)
        assertEquals(1, repo.search("My Show", source=MediaSource.REELSHORT).items.size)
        assertFalse(repo.home(page=2, source=MediaSource.REELSHORT).hasMore)
        assertEquals(listOf("reelshort:abc:my-show:1:ep1"), repo.detail("reelshort:abc:my-show").episodes)
        assertEquals("https://cdn.example.org/video.mp4", repo.stream("reelshort:abc:my-show:1:ep1").url)
        videoUrl = "https://cdn.example.org/video.m3u8"
        val hls = repo.stream("reelshort:abc:my-show:1:ep1")
        assertTrue(hls.hls)
        RemoteVideo(OkHttpClient(), hls).use { it.prepare(); it.warm() }
        locked = true
        rejects { repo.stream("reelshort:abc:my-show:1:ep1") }
        locked = false; chapterId = "wrong"
        rejects { repo.stream("reelshort:abc:my-show:1:ep1") }
    }

    @Test fun dramaRejectsWrongLocaleAndMismatchedBook() {
        val wrongLocale = repository { props("""{"locale":"zh","pageNo":1,"pages":0,"bookList":[]}""") }
        rejects { wrongLocale.home(source=MediaSource.DRAMABOX) }
        val wrongBook = repository { props("""{"locale":"en","bookInfo":{"bookId":"999","bookName":"Wrong"},"chapterList":[]}""") }
        rejects { wrongBook.detail("dramabox:123") }
    }
    @Test fun followsOfficialRedirectButRejectsCrossOriginRedirect() {
        var location = "/drama/123/demo"
        var calls = 0
        val repo = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (chain.request().url.encodedPath == "/drama/123") {
                response.code(301).message("Moved").header("Location", location).body("".toResponseBody()).build()
            } else {
                val data = props("""{"locale":"en","bookInfo":{"bookId":"123","bookName":"Demo","chapterCount":1},"chapterList":[{"id":"456","index":0,"unlock":false}]}""")
                response.code(200).message("OK").body("<script id='__NEXT_DATA__'>$data</script>".toResponseBody()).build()
            }
        }.build())
        assertEquals(listOf("dramabox:123:456"), repo.detail("dramabox:123").episodes)
        assertEquals(2, calls)
        location = "https://evil.example/drama/123"
        rejects { repo.detail("dramabox:123") }
        assertEquals(3, calls)
    }

}
