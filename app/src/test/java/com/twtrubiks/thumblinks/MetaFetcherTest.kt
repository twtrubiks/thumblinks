package com.twtrubiks.thumblinks

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MetaFetcherTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun html(head: String) = MockResponse.Builder()
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body("<!doctype html><html><head>$head</head><body>內文</body></html>")
        .build()

    private fun fetch(path: String, timeoutMillis: Long = 15_000) =
        MetaFetcher(client, timeoutMillis).fetchBlocking(server.url(path).toString())

    @Test
    fun readsOgImageAndTitleWithBrowserHeaders() {
        server.enqueue(html("""<meta property="og:image" content="/og.jpg"><meta property="og:title" content="週末去爬山">"""))

        val result = fetch("/article")

        assertEquals(Fetched.Page(PageMeta(server.url("/og.jpg").toString(), "週末去爬山")), result)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(MetaFetcher.USER_AGENT, request.headers["User-Agent"])
        assertEquals("zh-TW,zh;q=0.9,en;q=0.8", request.headers["Accept-Language"])
    }

    /**
     * `<head>` 很長（內嵌的 script、style 動輒幾百 KB），og:image 在 200 KB 之後：一次 read 只拿到一小塊（8 KB），
     * 要一直讀到 1 MB 或讀完。只讀第一塊的話很多網站會抓不到縮圖，其他測試的 og:image 都在最前面，抓不出來。
     */
    @Test
    fun findsOgImageAfterALongHead() {
        val script = "<script>" + "x".repeat(200 * 1024) + "</script>"
        server.enqueue(html("""$script<meta property="og:image" content="/og.jpg"><title>很長的 head</title>"""))

        assertEquals(Fetched.Page(PageMeta(server.url("/og.jpg").toString(), "很長的 head")), fetch("/long-head"))
    }

    /** 跟著轉址，相對網址以轉址後的網址為準。 */
    @Test
    fun followsRedirectsAndResolvesAgainstFinalUrl() {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/posts/2026/hiking.html").build())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/blog/2026/hiking.html").build())
        server.enqueue(html("""<meta property="og:image" content="cover.jpg"><title>爬山</title>"""))

        val result = fetch("/s/abc") as Fetched.Page

        assertEquals(server.url("/blog/2026/cover.jpg").toString(), result.meta.imageUrl)
        assertEquals("爬山", result.meta.title)
    }

    /** 網址本身就是圖：拿它當縮圖，標題留空。 */
    @Test
    fun imageUrlIsItsOwnThumbnail() {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(ByteArray(64))).build())

        val url = server.url("/photo").toString()
        assertEquals(Fetched.Page(PageMeta(url, null)), MetaFetcher(client).fetchBlocking(url))
    }

    @Test
    fun pageWithoutThumbnail() {
        server.enqueue(html("<title>沒有縮圖的網頁</title>"))

        assertEquals(Fetched.Page(PageMeta(null, "沒有縮圖的網頁")), fetch("/plain"))
    }

    @Test
    fun httpErrorsAreUnreachable() {
        for (code in listOf(404, 410, 500, 503)) {
            server.enqueue(MockResponse.Builder().code(code).body("<title>錯誤頁</title>").build())
            assertEquals("HTTP $code", Fetched.Unreachable, fetch("/gone"))
        }
    }

    @Test
    fun connectionRefusedIsUnreachable() {
        val url = server.url("/a").toString()
        server.close()

        assertEquals(Fetched.Unreachable, MetaFetcher(client).fetchBlocking(url))
    }

    @Test
    fun timeoutIsUnreachable() {
        server.enqueue(html("<title>太慢</title>").newBuilder().headersDelay(3, TimeUnit.SECONDS).build())

        val start = System.nanoTime()
        assertEquals(Fetched.Unreachable, fetch("/slow", timeoutMillis = 300))
        assertTrue("整體逾時要準時停下來", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
    }

    /** 只讀前 1 MB：後面就算很大、很慢也不等它。 */
    @Test
    fun readsAtMostOneMegabyte() {
        val head = """<html><head><meta property="og:image" content="/og.jpg"><title>很長的網頁</title></head><body>"""
        val body = Buffer().writeUtf8(head).write(ByteArray(8 * 1024 * 1024) { 'x'.code.toByte() })
        // 每 50 毫秒送 256 KB：讀 1 MB 約 0.2 秒，整份 8 MB 要 1.6 秒以上，會撞上 1 秒的逾時
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/html")
                .body(body)
                .throttleBody(256 * 1024, 50, TimeUnit.MILLISECONDS)
                .build(),
        )

        val result = fetch("/huge", timeoutMillis = 1_000)

        assertEquals(Fetched.Page(PageMeta(server.url("/og.jpg").toString(), "很長的網頁")), result)
    }

    @Test
    fun big5PageWithoutCharsetHeader() {
        val bytes = """<html><head><meta charset="big5"><title>中文標題</title></head><body>正體中文</body></html>"""
            .toByteArray(charset("Big5"))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body(Buffer().write(bytes)).build())

        assertEquals(Fetched.Page(PageMeta(null, "中文標題")), fetch("/big5"))
    }

    @Test
    fun big5PageFromCharsetHeader() {
        val bytes = """<html><head><title>中文標題</title></head></html>""".toByteArray(charset("Big5"))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html; charset=big5").body(Buffer().write(bytes)).build())

        assertEquals(Fetched.Page(PageMeta(null, "中文標題")), fetch("/big5"))
    }
}
