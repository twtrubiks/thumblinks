package com.twtrubiks.thumblinks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.OffsetDateTime
import java.time.ZoneOffset

class LinkStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sql = JdbcSql()
    private var clock = OffsetDateTime.of(2026, 10, 31, 21, 5, 0, 123_000_000, ZoneOffset.ofHours(8))
    private val thumbs by lazy { tmp.newFolder("thumbs") }
    private val store by lazy { LinkStore(sql, thumbs) { clock } }

    @After
    fun close() = sql.close()

    @Test
    fun newestFirst() {
        store.add("https://a.example/", "A", Thumbnail.Remote("https://a.example/og.jpg"))
        store.add("https://b.example/", "B", Thumbnail.Uploaded("b.jpg"))

        assertEquals(listOf("https://b.example/", "https://a.example/"), store.all().map { it.url })
    }

    @Test
    fun bothThumbnailKindsRoundTrip() {
        val remote = store.add("https://a.example/", null, Thumbnail.Remote("https://a.example/og.jpg"))
        val uploaded = store.add("https://b.example/", null, Thumbnail.Uploaded("b.jpg"))

        assertEquals(remote, store.find(remote.id))
        assertEquals(uploaded, store.find(uploaded.id))
    }

    @Test
    fun blankTitleMeansNoTitle() {
        assertNull(store.add("https://a.example/", "   ", Thumbnail.Remote("https://a.example/og.jpg")).title)
        assertEquals("B 站", store.add("https://b.example/", "  B 站\n", Thumbnail.Remote("https://b.example/og.jpg")).title)
    }

    /** 匯出的時間就是存進去的這個值：當地時間＋時差，到秒。 */
    @Test
    fun createdAtKeepsOffsetToTheSecond() {
        val link = store.add("https://a.example/", null, Thumbnail.Remote("https://a.example/og.jpg"))

        assertEquals(OffsetDateTime.parse("2026-10-31T21:05:00+08:00"), store.find(link.id)!!.createdAt)
    }

    @Test
    fun sameUrlNotAddedTwice() {
        val first = store.add("https://a.example/", "A", Thumbnail.Remote("https://a.example/og.jpg"))

        val e = assertThrows(DuplicateLinkException::class.java) {
            store.add("https://a.example/", "又一次", Thumbnail.Uploaded("x.jpg"))
        }
        assertEquals(first, e.existing)
        assertEquals(1, store.all().size)
    }

    /** 縮圖必填、而且只能有一種：畫面或之後的匯入寫錯了也存不進來。 */
    @Test
    fun databaseRequiresExactlyOneThumbnail() {
        // 先讓 LinkStore 建表
        store.all()
        for (thumbnails in listOf(arrayOf<Any?>(null, null), arrayOf<Any?>("https://a.example/og.jpg", "a.jpg"))) {
            assertThrows(Exception::class.java) {
                sql.execute(
                    "INSERT INTO links (id, url, thumbnail_url, thumbnail_file, created_at) VALUES ('x', 'https://x/', ?, ?, '')",
                    *thumbnails,
                )
            }
        }
    }

    @Test
    fun deletingRemovesUploadedThumbnail() {
        val file = thumbs.resolve("b.jpg").apply { writeText("jpeg") }
        val keep = thumbs.resolve("keep.jpg").apply { writeText("jpeg") }
        val link = store.add("https://b.example/", null, Thumbnail.Uploaded("b.jpg"))
        store.add("https://c.example/", null, Thumbnail.Uploaded("keep.jpg"))

        store.delete(link.id)

        assertNull(store.find(link.id))
        assertFalse(file.exists())
        assertTrue(keep.exists())
    }
}
