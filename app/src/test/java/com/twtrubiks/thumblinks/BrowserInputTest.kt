package com.twtrubiks.thumblinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BrowserInputTest {
    @Test
    fun fullUrlsAreKept() {
        assertEquals("https://example.com/a?b=1#c", BrowserInput.resolve("  https://example.com/a?b=1#c "))
        assertEquals("http://127.0.0.1:8080/x", BrowserInput.resolve("http://127.0.0.1:8080/x"))
        assertEquals("HTTPS://Example.com", BrowserInput.resolve("HTTPS://Example.com"))
        assertEquals("about:blank", BrowserInput.resolve("about:blank"))
    }

    @Test
    fun hostLikeTextGetsHttps() {
        assertEquals("https://example.com", BrowserInput.resolve("example.com"))
        assertEquals("https://github.com/twtrubiks", BrowserInput.resolve("github.com/twtrubiks"))
        assertEquals("https://www.gov.tw/", BrowserInput.resolve("www.gov.tw/"))
        assertEquals("https://localhost:3000", BrowserInput.resolve("localhost:3000"))
        assertEquals("https://192.168.1.1/admin", BrowserInput.resolve("192.168.1.1/admin"))
        assertEquals("https://中文.tw", BrowserInput.resolve("中文.tw"))
    }

    @Test
    fun everythingElseIsASearch() {
        assertEquals("https://duckduckgo.com/?q=%E9%99%BD%E6%98%8E%E5%B1%B1+%E8%8A%92%E8%8D%89", BrowserInput.resolve("陽明山 芒草"))
        assertEquals("https://duckduckgo.com/?q=kotlin", BrowserInput.resolve("kotlin"))
        // 有空白就不是網址
        assertEquals("https://duckduckgo.com/?q=example.com+%E6%98%AF%E4%BB%80%E9%BA%BC", BrowserInput.resolve("example.com 是什麼"))
        assertEquals("https://duckduckgo.com/?q=1.5", BrowserInput.resolve("1.5"))
        assertEquals("https://duckduckgo.com/?q=a%26b%3Dc", BrowserInput.resolve("a&b=c"))
    }

    @Test
    fun blankIsNothing() {
        assertNull(BrowserInput.resolve(""))
        assertNull(BrowserInput.resolve("   "))
    }

    @Test
    fun webSchemesStayInTheBrowser() {
        for (uri in listOf("https://a.example/", "http://a.example/", "about:blank", "data:text/html,hi", "blob:https://a/1", "moz-extension://x/popup.html")) {
            assertTrue(uri, BrowserInput.isWeb(uri))
        }
        for (uri in listOf("mailto:a@b.c", "tel:0912345678", "intent://scan/#Intent;scheme=zxing;end", "market://details?id=x", "line://msg")) {
            assertFalse(uri, BrowserInput.isWeb(uri))
        }
    }

    @Test
    fun onlyHttpPagesCanBeAdded() {
        assertTrue(BrowserInput.canAdd("https://a.example/"))
        assertTrue(BrowserInput.canAdd("http://127.0.0.1:1234/x"))
        assertFalse(BrowserInput.canAdd("about:blank"))
        assertFalse(BrowserInput.canAdd("moz-extension://x/popup.html"))
        assertFalse(BrowserInput.canAdd(""))
    }

    @Test
    fun errorPageShowsTheReasonAndTheEscapedUrl() {
        val uri = BrowserInput.errorPage("https://a.example/?q=<script>&x=\"1\"", "找不到這個網站")
        val prefix = "data:text/html;charset=utf-8;base64,"
        assertTrue(uri.startsWith(prefix))
        val html = String(Base64.getDecoder().decode(uri.removePrefix(prefix)))
        assertTrue(html, "<h1>打不開這個網頁</h1><p>找不到這個網站</p>" in html)
        assertTrue(html, "https://a.example/?q=&lt;script&gt;&amp;x=&quot;1&quot;" in html)
        assertFalse(html, "<script>" in html)
        assertTrue(BrowserInput.isWeb(uri))
    }
}
