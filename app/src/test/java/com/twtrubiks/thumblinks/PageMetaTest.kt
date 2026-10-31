package com.twtrubiks.thumblinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageMetaTest {
    private fun parse(head: String, url: String = "https://example.com/blog/post.html", contentType: String? = "text/html; charset=utf-8") =
        PageMeta.parse("<html><head>$head</head><body><p>內文</p></body></html>".toByteArray(), contentType, url)

    @Test
    fun ogImageAndTitle() {
        val meta = parse(
            """
            <meta property="og:title" content="週末去爬山">
            <meta property="og:image" content="https://cdn.example.com/og.jpg">
            <title>週末去爬山 | 我的部落格</title>
            """,
        )
        assertEquals(PageMeta("https://cdn.example.com/og.jpg", "週末去爬山"), meta)
    }

    @Test
    fun imageKeysInOrder() {
        val all = listOf(
            """<link rel="image_src" href="/link.jpg">""",
            """<meta name="twitter:image:src" content="/twitter-src.jpg">""",
            """<meta name="twitter:image" content="/twitter.jpg">""",
            """<meta property="og:image:url" content="/og-url.jpg">""",
            """<meta property="og:image" content="/og.jpg">""",
            """<meta property="og:image:secure_url" content="/og-secure.jpg">""",
        )
        val expected = listOf("link", "twitter-src", "twitter", "og-url", "og", "og-secure")
        // 每多一個優先的標籤，就換成它
        for (i in all.indices) {
            assertEquals("https://example.com/${expected[i]}.jpg", parse(all.take(i + 1).joinToString("\n")).imageUrl)
        }
    }

    @Test
    fun propertyAndNameBothCount() {
        assertEquals("https://example.com/a.jpg", parse("""<meta name="og:image" content="https://example.com/a.jpg">""").imageUrl)
        assertEquals("https://example.com/b.jpg", parse("""<meta property="twitter:image" content="https://example.com/b.jpg">""").imageUrl)
        assertEquals("標題", parse("""<meta name="og:title" content="標題">""").title)
    }

    /** 相對網址以轉址後的最終網址為基準。 */
    @Test
    fun relativeImageUrls() {
        assertEquals("https://example.com/blog/og.jpg", parse("""<meta property="og:image" content="og.jpg">""").imageUrl)
        assertEquals("https://example.com/img/og.jpg", parse("""<meta property="og:image" content="/img/og.jpg">""").imageUrl)
        assertEquals("https://cdn.example.net/og.jpg", parse("""<meta property="og:image" content="//cdn.example.net/og.jpg">""").imageUrl)
        assertEquals(
            "http://example.com/og.jpg",
            parse("""<meta property="og:image" content="og.jpg">""", url = "http://example.com/").imageUrl,
        )
    }

    @Test
    fun baseHrefWins() {
        val meta = parse("""<base href="https://static.example.com/assets/"><meta property="og:image" content="og.jpg">""")
        assertEquals("https://static.example.com/assets/og.jpg", meta.imageUrl)
        val relativeBase = parse("""<base href="/static/"><meta property="og:image" content="og.jpg">""")
        assertEquals("https://example.com/static/og.jpg", relativeBase.imageUrl)
    }

    /** 只收 http／https；前面的不能用就往下找。 */
    @Test
    fun onlyHttpImages() {
        assertNull(parse("""<meta property="og:image" content="data:image/png;base64,AAAA">""").imageUrl)
        assertNull(parse("""<meta property="og:image" content="javascript:alert(1)">""").imageUrl)
        assertNull(parse("""<meta property="og:image" content="   ">""").imageUrl)
        assertEquals(
            "https://example.com/t.jpg",
            parse(
                """<meta property="og:image" content="data:image/png;base64,AAAA"><meta name="twitter:image" content="/t.jpg">""",
            ).imageUrl,
        )
    }

    @Test
    fun noThumbnail() {
        assertEquals(PageMeta(null, "只有標題"), parse("<title>只有標題</title>"))
    }

    @Test
    fun titleFallbacksAndWhitespace() {
        assertEquals("推特標題", parse("""<meta name="twitter:title" content="推特標題"><title>網頁標題</title>""").title)
        assertEquals("網頁 標題", parse("<title>\n   網頁\n\t  標題  </title>").title)
        assertEquals("og 標題", parse("""<meta property="og:title" content="  og&nbsp;&nbsp;標題 "><title>x</title>""").title)
        // og:title 空的就往下找
        assertEquals("網頁標題", parse("""<meta property="og:title" content=" "><title>網頁標題</title>""").title)
        assertNull(parse("<title> </title>").title)
        assertNull(parse("").title)
    }

    @Test
    fun big5FromMetaCharset() {
        val html = """<html><head><meta charset="big5"><title>中文標題</title></head><body>正體中文</body></html>"""
        val meta = PageMeta.parse(html.toByteArray(charset("Big5")), "text/html", "https://example.com.tw/")
        assertEquals("中文標題", meta.title)
    }

    @Test
    fun big5FromContentType() {
        val html = """<html><head><title>中文標題</title></head><body>正體中文</body></html>"""
        val meta = PageMeta.parse(html.toByteArray(charset("Big5")), "text/html; charset=Big5", "https://example.com.tw/")
        assertEquals("中文標題", meta.title)
    }

    @Test
    fun unknownCharsetFallsBackToDocument() {
        val html = """<html><head><meta http-equiv="Content-Type" content="text/html; charset=big5"><title>中文標題</title></head></html>"""
        val meta = PageMeta.parse(html.toByteArray(charset("Big5")), "text/html; charset=no-such-charset", "https://example.com.tw/")
        assertEquals("中文標題", meta.title)
    }
}
