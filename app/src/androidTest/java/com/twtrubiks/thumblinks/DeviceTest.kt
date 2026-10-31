package com.twtrubiks.thumblinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 只有模擬器才測得到的事，`record.sh` 在錄影前先跑、不錄影：
 * [LinkStore] 的 SQL 在 Android 9 內建的 SQLite（3.22）上跑一輪——JVM 測試用的新版 SQLite 不會擋下新語法。
 * 之後上傳縮圖的縮圖轉正（Bitmap + ExifInterface）也放這裡。
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sql = AndroidSql(context, DB)
    private val thumbs = File(context.cacheDir, "device-test-thumbs").apply { mkdirs() }

    @After
    fun cleanUp() {
        sql.close()
        context.deleteDatabase(DB)
        thumbs.deleteRecursively()
    }

    @Test
    fun linkStoreOnAndroidSqlite() {
        val store = LinkStore(sql, thumbs)
        val remote = store.add("https://a.example/", "  A  ", Thumbnail.Remote("https://a.example/og.jpg"))
        File(thumbs, "b.jpg").writeText("jpeg")
        val uploaded = store.add("https://b.example/", "", Thumbnail.Uploaded("b.jpg"))

        assertEquals(listOf(uploaded, remote), store.all())
        assertEquals("A", remote.title)
        assertNull(uploaded.title)
        assertThrows(DuplicateLinkException::class.java) {
            store.add("https://a.example/", null, Thumbnail.Uploaded("x.jpg"))
        }
        // 縮圖兩個都沒有的資料，Android 的 SQLite 一樣擋得下來
        assertThrows(Exception::class.java) {
            sql.execute("INSERT INTO links (id, url, created_at) VALUES ('x', 'https://x/', '')")
        }

        store.delete(uploaded.id)
        assertEquals(listOf(remote), store.all())
        assertFalse(File(thumbs, "b.jpg").exists())
    }

    private companion object {
        const val DB = "device-test.db"
    }
}
