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
    fun findByUrlComparesTheExactString() {
        val link = store.add("https://a.example/x", "A", Thumbnail.Remote("https://a.example/og.jpg"))

        assertEquals(link, store.findByUrl("https://a.example/x"))
        // 不做正規化：多一個斜線、換大小寫都當作不同的網址
        assertNull(store.findByUrl("https://a.example/x/"))
        assertNull(store.findByUrl("https://A.example/x"))
    }

    @Test
    fun updateTitleAndThumbnailKeepsTheRest() {
        val link = store.add("https://a.example/", "A", Thumbnail.Remote("https://a.example/og.jpg"))
        store.add("https://b.example/", "B", Thumbnail.Remote("https://b.example/og.jpg"))

        val updated = store.update(link.id, "  新標題 ", Thumbnail.Uploaded("a.jpg"))

        assertEquals(link.copy(title = "新標題", thumbnail = Thumbnail.Uploaded("a.jpg")), updated)
        assertEquals(updated, store.find(link.id))
        // 排序還是加入的順序
        assertEquals(listOf("https://b.example/", "https://a.example/"), store.all().map { it.url })
        assertNull(store.update(link.id, "", Thumbnail.Remote("https://a.example/2.jpg"))!!.title)
        assertNull(store.update("no-such-id", "x", Thumbnail.Remote("https://a.example/2.jpg")))
    }

    @Test
    fun favoriteIsKeptWhenEditing() {
        val link = store.add("https://a.example/", "A", Thumbnail.Remote("https://a.example/og.jpg"))
        val other = store.add("https://b.example/", "B", Thumbnail.Remote("https://b.example/og.jpg"))
        assertFalse(link.favorite)

        store.setFavorite(link.id, true)
        assertTrue(store.find(link.id)!!.favorite)
        assertFalse(store.find(other.id)!!.favorite)

        // 編輯頁存標題、縮圖時不能把愛心蓋掉
        store.update(link.id, "新標題", Thumbnail.Remote("https://a.example/2.jpg"))
        assertTrue(store.find(link.id)!!.favorite)

        store.setFavorite(link.id, false)
        assertFalse(store.find(link.id)!!.favorite)
    }

    /** 手機上已經有的清單（還沒有 favorite 欄位的表）：補上欄位，原本的每一筆都留著、都不是愛心。 */
    @Test
    fun oldTableGetsFavoriteColumn() {
        sql.execute(
            """
            CREATE TABLE links (
                id TEXT PRIMARY KEY,
                url TEXT NOT NULL UNIQUE,
                title TEXT,
                thumbnail_url TEXT,
                thumbnail_file TEXT,
                created_at TEXT NOT NULL,
                CHECK ((thumbnail_url IS NULL) <> (thumbnail_file IS NULL))
            )
            """.trimIndent(),
        )
        sql.execute(
            "INSERT INTO links (id, url, title, thumbnail_url, created_at) VALUES ('old', 'https://old.example/', '舊的', 'https://old.example/og.jpg', '2026-10-30T09:12:00+08:00')",
        )

        val old = store.find("old")!!
        assertEquals("舊的", old.title)
        assertFalse(old.favorite)
        store.setFavorite("old", true)
        // 下次打開 App 再建一次 LinkStore：欄位已經有了，不能再加一次
        assertTrue(LinkStore(sql, thumbs).find("old")!!.favorite)
    }

    /** 換掉上傳的縮圖時，舊的檔案要刪；換成同一張（只改標題）時不能刪。 */
    @Test
    fun replacingUploadedThumbnailDeletesTheOldFile() {
        val old = thumbs.resolve("old.jpg").apply { writeText("jpeg") }
        val new = thumbs.resolve("new.jpg").apply { writeText("jpeg") }
        val link = store.add("https://a.example/", "A", Thumbnail.Uploaded("old.jpg"))

        store.update(link.id, "只改標題", Thumbnail.Uploaded("old.jpg"))
        assertTrue(old.exists())

        store.update(link.id, "A", Thumbnail.Uploaded("new.jpg"))
        assertFalse(old.exists())
        assertTrue(new.exists())

        store.update(link.id, "A", Thumbnail.Remote("https://a.example/og.jpg"))
        assertFalse(new.exists())
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
