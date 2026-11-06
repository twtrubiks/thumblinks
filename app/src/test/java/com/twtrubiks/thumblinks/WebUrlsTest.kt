package com.twtrubiks.thumblinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebUrlsTest {
    @Test
    fun plainUrl() {
        assertEquals("https://example.com/article?id=1#top", WebUrls.extract("https://example.com/article?id=1#top"))
        assertEquals("http://example.com", WebUrls.extract("  http://example.com\n"))
    }

    /** 從瀏覽器或其他 App 分享過來，常是「標題＋網址」。 */
    @Test
    fun sharedTextWithTitle() {
        assertEquals("https://example.com/a", WebUrls.extract("週末去爬山 https://example.com/a"))
        assertEquals("https://example.com/a", WebUrls.extract("週末去爬山\nhttps://example.com/a"))
        assertEquals("https://example.com/a", WebUrls.extract("「週末去爬山」https://example.com/a，推薦給你"))
        assertEquals("https://example.com/a", WebUrls.extract("看這篇：https://example.com/a。"))
    }

    /** 第一個看起來像網址、其實不合法（埠號超過範圍）：往下找下一個，不是回 null。 */
    @Test
    fun skipsAnInvalidFirstUrl() {
        assertEquals("https://example.com/a", WebUrls.extract("壞的 https://bad.example:99999/ 好的 https://example.com/a"))
    }

    @Test
    fun firstOfSeveral() {
        assertEquals("https://a.example/", WebUrls.extract("https://a.example/ 和 https://b.example/"))
    }

    @Test
    fun trailingPunctuationIsNotPartOfTheUrl() {
        assertEquals("https://example.com/a", WebUrls.extract("see https://example.com/a."))
        assertEquals("https://example.com/a", WebUrls.extract("(see https://example.com/a)"))
        // 網址自己的括號要留著
        assertEquals("https://en.wikipedia.org/wiki/Java_(programming_language)", WebUrls.extract("https://en.wikipedia.org/wiki/Java_(programming_language)"))
    }

    /** 只認 http:// 與 https:// 開頭，不自動補 https://。 */
    @Test
    fun noUrl() {
        assertNull(WebUrls.extract(""))
        assertNull(WebUrls.extract("週末去爬山"))
        assertNull(WebUrls.extract("example.com/a"))
        assertNull(WebUrls.extract("ftp://example.com/a"))
        assertNull(WebUrls.extract("https://"))
        assertNull(WebUrls.extract("https:///path"))
    }

    @Test
    fun schemeCaseIsKeptAsIs() {
        assertEquals("HTTPS://Example.com/A", WebUrls.extract("HTTPS://Example.com/A"))
    }

    @Test
    fun domainDropsWww() {
        assertEquals("example.com", WebUrls.domain("https://www.example.com/a"))
        assertEquals("blog.example.com", WebUrls.domain("https://blog.example.com/a"))
        assertEquals("127.0.0.1", WebUrls.domain("http://127.0.0.1:8080/a"))
    }
}
