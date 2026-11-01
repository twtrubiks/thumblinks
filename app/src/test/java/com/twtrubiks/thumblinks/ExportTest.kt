package com.twtrubiks.thumblinks

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream

class ExportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val article = Link(
        id = "6f1d2c3b-0000-4000-8000-000000000001",
        url = "https://example.com/article",
        title = "標題",
        thumbnail = Thumbnail.Remote("https://example.com/og.jpg"),
        createdAt = OffsetDateTime.parse("2026-10-31T20:00:00+08:00"),
        favorite = true,
    )
    private val uploaded = Link(
        id = "0b6c1c1e-4f1a-4b1e-9a0e-3f0f2d7c9a11",
        url = "https://example.org/page",
        title = null,
        thumbnail = Thumbnail.Uploaded("5d7e9a10-1111-4222-8333-944455556666.jpg"),
        createdAt = OffsetDateTime.parse("2026-10-30T09:12:00+08:00"),
    )
    private val exportedAt = OffsetDateTime.parse("2026-10-31T21:05:00.456+08:00")

    /** CLAUDE.md 的範例原樣：一筆一行，筆與筆之間空一行；沒有標題時替代文字用網址。 */
    @Test
    fun markdownMatchesTheSpec() {
        assertEquals(
            """
            [![標題](https://example.com/og.jpg)](https://example.com/article)

            [![https://example.org/page](images/0b6c1c1e-4f1a-4b1e-9a0e-3f0f2d7c9a11.jpg)](https://example.org/page)

            """.trimIndent(),
            Export.markdown(listOf(article, uploaded)),
        )
    }

    @Test
    fun markdownEscapesAltText() {
        assertEquals("""a\\b \[c\] d e""", Export.altText("a\\b [c]\nd\r\ne"))
        val link = article.copy(title = "[置頂] C:\\ 的\n筆記")
        assertEquals(
            "[![\\[置頂\\] C:\\\\ 的 筆記](https://example.com/og.jpg)](https://example.com/article)\n",
            Export.markdown(listOf(link)),
        )
    }

    @Test
    fun markdownEncodesUrlSpecials() {
        assertEquals("https://e.com/a%20b%28c%29%3Cd%3E?q=中文&x=[1]", Export.linkTarget("https://e.com/a b(c)<d>?q=中文&x=[1]"))
        val link = article.copy(
            url = "https://en.wikipedia.org/wiki/Java_(programming_language)",
            thumbnail = Thumbnail.Remote("https://e.com/og image.jpg"),
        )
        assertEquals(
            "[![標題](https://e.com/og%20image.jpg)](https://en.wikipedia.org/wiki/Java_%28programming_language%29)\n",
            Export.markdown(listOf(link)),
        )
    }

    /** CLAUDE.md 的範例原樣：縮排兩格、中文不跳脫、沒有標題寫 null、時間到秒、愛心 true／false 都寫。 */
    @Test
    fun jsonMatchesTheSpec() {
        assertEquals(
            """
            {
              "exported_at": "2026-10-31T21:05:00+08:00",
              "links": [
                {
                  "url": "https://example.com/article",
                  "title": "標題",
                  "thumbnail": "https://example.com/og.jpg",
                  "created_at": "2026-10-31T20:00:00+08:00",
                  "favorite": true
                },
                {
                  "url": "https://example.org/page",
                  "title": null,
                  "thumbnail": "images/0b6c1c1e-4f1a-4b1e-9a0e-3f0f2d7c9a11.jpg",
                  "created_at": "2026-10-30T09:12:00+08:00",
                  "favorite": false
                }
              ]
            }

            """.trimIndent(),
            Export.json(listOf(article, uploaded), exportedAt),
        )
    }

    @Test
    fun fileNamesAndMimeTypes() {
        val day = LocalDate.of(2026, 10, 31)
        assertEquals("thumblinks-2026-10-31.md", Export.fileName(listOf(article), ExportFormat.MARKDOWN, day))
        assertEquals("thumblinks-2026-10-31.json", Export.fileName(listOf(article), ExportFormat.JSON, day))
        assertEquals("thumblinks-2026-10-31.zip", Export.fileName(listOf(article, uploaded), ExportFormat.MARKDOWN, day))
        assertEquals("text/markdown", Export.mimeType(listOf(article), ExportFormat.MARKDOWN))
        assertEquals("application/json", Export.mimeType(listOf(article), ExportFormat.JSON))
        assertEquals("application/zip", Export.mimeType(listOf(uploaded), ExportFormat.JSON))
    }

    @Test
    fun remoteOnlyIsASingleUtf8File() {
        val out = ByteArrayOutputStream()
        Export.write(listOf(article), ExportFormat.MARKDOWN, exportedAt, tmp.root, out)
        assertEquals(Export.markdown(listOf(article)), out.toByteArray().toString(Charsets.UTF_8))
    }

    /** 有上傳的縮圖就打包：文字檔＋images/<id>.jpg；網路上的縮圖照樣寫網址、不下載。 */
    @Test
    fun uploadedThumbnailsMakeAZip() {
        val thumbs = tmp.newFolder("thumbs")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        thumbs.resolve((uploaded.thumbnail as Thumbnail.Uploaded).fileName).writeBytes(jpeg)

        for (format in ExportFormat.entries) {
            val out = ByteArrayOutputStream()
            Export.write(listOf(article, uploaded), format, exportedAt, thumbs, out)
            val entries = unzip(out.toByteArray())

            assertEquals(listOf("links.${format.extension}", "images/${uploaded.id}.jpg"), entries.keys.toList())
            val text = entries.getValue("links.${format.extension}").toString(Charsets.UTF_8)
            val expected = if (format == ExportFormat.MARKDOWN) Export.markdown(listOf(article, uploaded)) else Export.json(listOf(article, uploaded), exportedAt)
            assertEquals(expected, text)
            assertArrayEquals(jpeg, entries.getValue("images/${uploaded.id}.jpg"))
        }
    }

    @Test
    fun missingUploadedFileFailsInsteadOfWritingABrokenZip() {
        assertThrows(FileNotFoundException::class.java) {
            Export.write(listOf(uploaded), ExportFormat.JSON, exportedAt, tmp.root, ByteArrayOutputStream())
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }
}
