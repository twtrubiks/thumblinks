package com.twtrubiks.thumblinks

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = OffsetDateTime.parse("2026-11-01T10:00:00+08:00")
    private val exportedAt = OffsetDateTime.parse("2026-10-31T21:05:00+08:00")

    // 匯出的那支手機
    private val fromSql = JdbcSql()
    private val fromThumbs by lazy { tmp.newFolder("from-thumbs") }
    private val from by lazy { LinkStore(fromSql, fromThumbs) { now } }

    // 匯入的這支手機
    private val toSql = JdbcSql()
    private val toThumbs by lazy { tmp.newFolder("to-thumbs") }
    private val to by lazy { LinkStore(toSql, toThumbs) { now } }

    @After
    fun close() {
        fromSql.close()
        toSql.close()
    }

    private fun link(url: String, title: String?, at: String, favorite: Boolean = false, thumbnail: Thumbnail = Thumbnail.Remote("$url/og.jpg")) =
        from.add(url, title, thumbnail, OffsetDateTime.parse(at), favorite)

    private fun exported(format: ExportFormat, links: List<Link> = from.all()): File {
        val out = ByteArrayOutputStream()
        Export.write(links, format, exportedAt, fromThumbs, out)
        return file(out.toByteArray())
    }

    private fun file(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }

    private fun file(text: String) = file(text.toByteArray())

    /** 存圖：照原樣複製（手機上是 ThumbPrep，DeviceTest 測）。 */
    private val copy: (ByteArray, File) -> String? = { bytes, dir ->
        "${java.util.UUID.randomUUID()}.jpg".also { File(dir, it).writeBytes(bytes) }
    }

    private fun import(file: File, saveImage: (ByteArray, File) -> String? = copy): ImportResult = Import.read(file).use { f ->
        Import.run(Import.plan(f) { to.findByUrl(it) != null }, f, to, toThumbs, saveImage)
    }

    private fun comparable(links: List<Link>) = links.map { listOf(it.url, it.title, it.createdAt, it.favorite, (it.thumbnail as? Thumbnail.Remote)?.url) }

    /** JSON 匯出再匯入：網址、標題、縮圖、時間、愛心都一樣，清單的順序也一樣。 */
    @Test
    fun jsonRoundTrip() {
        link("https://a.example", "A", "2026-10-01T08:00:00+08:00", favorite = true)
        link("https://b.example", null, "2026-10-02T08:00:00+08:00")
        link("https://c.example", "C [置頂] \\", "2026-10-03T08:00:00+08:00", favorite = true)

        assertEquals(ImportResult(added = 3, duplicates = 0, broken = 0), import(exported(ExportFormat.JSON)))
        assertEquals(comparable(from.all()), comparable(to.all()))
    }

    /** 匯出時是照標題排的也一樣：照檔案裡的時間從舊到新加，清單上新的在上。 */
    @Test
    fun jsonKeepsTheOrderByTimeWhateverTheFileOrder() {
        link("https://a.example", "C", "2026-10-01T08:00:00+08:00")
        link("https://b.example", "A", "2026-10-02T08:00:00+08:00")
        link("https://c.example", "B", "2026-10-03T08:00:00+08:00")
        val byTitle = sortLinks(from.all(), SortOrder.TITLE)

        import(exported(ExportFormat.JSON, byTitle))

        assertEquals(listOf("https://c.example", "https://b.example", "https://a.example"), to.all().map { it.url })
    }

    /** Markdown 沒有時間與愛心：用匯入的時間、沒有愛心；順序照檔案（上面的是新的）。 */
    @Test
    fun markdownRoundTrip() {
        link("https://en.wikipedia.org/wiki/Java_(programming_language)", "Java [置頂] C:\\ 的筆記", "2026-10-01T08:00:00+08:00", favorite = true)
        link("https://b.example/a b", null, "2026-10-02T08:00:00+08:00", thumbnail = Thumbnail.Remote("https://b.example/og image.jpg"))

        assertEquals(ImportResult(added = 2, duplicates = 0, broken = 0), import(exported(ExportFormat.MARKDOWN)))
        val links = to.all()
        assertEquals(from.all().map { listOf(it.url, it.title, it.thumbnail) }, links.map { listOf(it.url, it.title, it.thumbnail) })
        assertTrue(links.all { it.createdAt == now && !it.favorite })
    }

    @Test
    fun markdownLineFormat() {
        val parsed = Import.parseMarkdown(
            """
            # 不是連結的行不理

            [![標題 \[1\]](https://e.com/og.jpg)](https://e.com/a)

            [![https://e.com/b](images/x.jpg)](https://e.com/b)
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ImportedLink("https://e.com/a", "標題 [1]", "https://e.com/og.jpg", null, false),
                ImportedLink("https://e.com/b", null, "images/x.jpg", null, false),
            ),
            parsed,
        )
    }

    /** 有上傳的縮圖就是 zip：圖存進 thumbs（新的檔名），兩種格式都可以。 */
    @Test
    fun zipRoundTripWithUploadedImages() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        File(fromThumbs, "up.jpg").writeBytes(jpeg)
        link("https://a.example", "A", "2026-10-01T08:00:00+08:00")
        link("https://b.example", "B", "2026-10-02T08:00:00+08:00", favorite = true, thumbnail = Thumbnail.Uploaded("up.jpg"))

        for (format in ExportFormat.entries) {
            to.all().forEach { to.delete(it.id) }
            assertEquals(ImportResult(added = 2, duplicates = 0, broken = 0), import(exported(format)))
            val b = to.findByUrl("https://b.example")!!
            val name = (b.thumbnail as Thumbnail.Uploaded).fileName
            assertNotEquals("up.jpg", name)
            assertArrayEquals(jpeg, File(toThumbs, name).readBytes())
            assertEquals(format == ExportFormat.JSON, b.favorite)
            assertEquals(Thumbnail.Remote("https://a.example/og.jpg"), to.findByUrl("https://a.example")!!.thumbnail)
        }
    }

    /** 已經在清單裡的跳過、手機上那筆不動；同一個檔案裡重複的只算一次；再匯入一次全部跳過。 */
    @Test
    fun duplicatesAreSkipped() {
        link("https://a.example", "A", "2026-10-01T08:00:00+08:00", favorite = true)
        link("https://b.example", "B", "2026-10-02T08:00:00+08:00")
        val mine = to.add("https://a.example", "我改過的標題", Thumbnail.Remote("https://mine.example/og.jpg"))
        val twice = from.all() + from.all().last()
        val file = exported(ExportFormat.JSON, twice)

        assertEquals(ImportResult(added = 1, duplicates = 2, broken = 0), import(file))
        assertEquals(mine, to.findByUrl("https://a.example"))
        assertEquals(ImportResult(added = 0, duplicates = 3, broken = 0), import(file))
    }

    /** 網址不是 http(s)、少了縮圖、縮圖指到檔案裡沒有的圖、圖解不開：那一筆跳過，其他照加。 */
    @Test
    fun brokenLinksAreSkipped() {
        val json = """
            {"links": [
              {"url": "https://ok.example", "thumbnail": "https://ok.example/og.jpg"},
              {"url": "ftp://bad.example", "thumbnail": "https://bad.example/og.jpg"},
              {"url": "https://no-thumb.example"},
              {"thumbnail": "https://no-url.example/og.jpg"},
              {"url": "https://missing-image.example", "thumbnail": "images/missing.jpg"}
            ]}
        """.trimIndent()
        assertEquals(ImportResult(added = 1, duplicates = 0, broken = 4), import(file(json)))

        val zip = zip("links.md" to "[![x](images/x.jpg)](https://x.example)\n".toByteArray(), "images/x.jpg" to byteArrayOf(1))
        assertEquals(ImportResult(added = 0, duplicates = 0, broken = 1), import(zip) { _, _ -> null })
    }

    /** 舊的 JSON 沒有愛心、多了不認得的欄位、時間寫錯、檔案開頭有 BOM，都照樣讀。 */
    @Test
    fun lenientJson() {
        val json = "\uFEFF" + """
            {"exported_at": "x", "version": 2, "links": [
              {"url": "https://a.example", "title": "  多個   空白\n換行 ", "thumbnail": "https://a.example/og.jpg",
               "created_at": "昨天", "tags": ["x"]}
            ]}
        """.trimIndent()
        assertEquals(ImportResult(added = 1, duplicates = 0, broken = 0), import(file(json)))
        val a = to.findByUrl("https://a.example")!!
        assertEquals("多個 空白 換行", a.title)
        assertEquals(now, a.createdAt)
        assertFalse(a.favorite)
    }

    @Test
    fun notAThumbLinksFile() {
        assertThrows(ImportException::class.java) { Import.read(file("隨便一段文字\nhttps://a.example\n")) }
        assertThrows(ImportException::class.java) { Import.read(file("""{"links": "x"}""")) }
        assertThrows(ImportException::class.java) { Import.read(file("{ 不是 JSON")) }
        assertThrows(ImportException::class.java) { Import.read(zip("other.txt" to byteArrayOf(1))) }
        assertThrows(ImportException::class.java) { Import.read(file("PK 壞掉的 zip".toByteArray())) }
        assertNull(to.findByUrl("https://a.example"))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file(out.toByteArray())
    }
}
