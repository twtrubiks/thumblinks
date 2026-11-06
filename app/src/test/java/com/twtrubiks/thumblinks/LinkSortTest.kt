package com.twtrubiks.thumblinks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class LinkSortTest {
    private val time = OffsetDateTime.parse("2026-10-31T20:00:00+08:00")

    private fun link(id: String, url: String, title: String?) = Link(id, url, title, Thumbnail.Remote("https://img.example/$id.jpg"), time)

    // LinkStore.all() 的順序：新的在上
    private val links = listOf(
        link("e", "https://www.youtube.com/watch?v=1", "banana"),
        link("d", "https://github.com/a", "Cherry"),
        link("c", "https://example.org/no-title", null),
        link("b", "https://youtube.com/watch?v=2", "Apple"),
        link("a", "https://github.com/b", "apple"),
    )

    private fun ids(order: SortOrder) = sortLinks(links, order).map { it.id }

    @Test
    fun newestIsTheStoreOrder() {
        assertEquals(listOf("e", "d", "c", "b", "a"), ids(SortOrder.NEWEST))
    }

    @Test
    fun oldestIsReversed() {
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(SortOrder.OLDEST))
    }

    @Test
    fun titleIgnoresCaseAndUsesTheUrlWithoutATitle() {
        // Apple 與 apple 一樣：照新的在上（b 比 a 新）；沒有標題的用網址 https://…，排在 c 開頭的後面
        assertEquals(listOf("b", "a", "e", "d", "c"), ids(SortOrder.TITLE))
    }

    @Test
    fun domainDropsWwwAndKeepsNewestFirstWithinADomain() {
        // example.org、github.com（d 比 a 新）、youtube.com（www. 拿掉，e 比 b 新）
        assertEquals(listOf("c", "d", "a", "e", "b"), ids(SortOrder.DOMAIN))
    }

    @Test
    fun emptyStaysEmpty() {
        for (order in SortOrder.entries) assertEquals(emptyList<Link>(), sortLinks(emptyList(), order))
    }

    @Test
    fun favoritesOnlyKeepsTheOrder() {
        val marked = links.map { if (it.id in setOf("a", "d")) it.copy(favorite = true) else it }

        assertEquals(listOf("e", "d", "c", "b", "a"), visibleLinks(marked, SortOrder.NEWEST, favoritesOnly = false).map { it.id })
        assertEquals(listOf("d", "a"), visibleLinks(marked, SortOrder.NEWEST, favoritesOnly = true).map { it.id })
        assertEquals(listOf("a", "d"), visibleLinks(marked, SortOrder.TITLE, favoritesOnly = true).map { it.id })
        assertEquals(emptyList<String>(), visibleLinks(links, SortOrder.NEWEST, favoritesOnly = true).map { it.id })
    }
}
