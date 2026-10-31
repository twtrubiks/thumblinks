package com.twtrubiks.thumblinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 錄影用的展示流程，CI 只錄這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 * 網頁由模擬器上的假網站（[FakeSite]）提供，不連外。全長控制在 150 秒內。
 *
 * 貼網址 → 抓到縮圖與標題 → 改標題 → 加入；分享同一個網址進來 → 已經在清單裡了 → 打開那一筆；
 * 貼沒有縮圖的網址 → 貼圖片網址 → 加入；點一筆用內建瀏覽器打開（已在清單）→ 網址列換一篇 → 加到清單；
 * 長按刪除一筆；打開匯出；切成深色模式再切回來；排序換成舊的在上再換回來。uBlock Origin 要連 AMO，不放在錄影裡（[BrowserTest]）。
 */
@RunWith(AndroidJUnit4::class)
class DemoRecordingTest : UiTest() {
    private val site = FakeSite().start()

    @After
    fun stopSite() = site.close()

    @Test
    fun demo() {
        clearLinks()

        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)
        pause(2500)
        screenshot("01-empty")

        // 貼網址 → 抓到縮圖與標題 → 改標題 → 加入
        setClipboard(site.url("/hiking"))
        find(By.res("add_button")).click()
        waitFor(By.text("貼上"))
        pause(1200)
        find(By.text("貼上")).click()
        waitFor(By.res("thumb_ready"), timeout = 20_000)
        waitFor(By.text(FakeSite.HIKING_TITLE))
        pause(2000)
        screenshot("02-fetched")
        find(By.res("title_input")).text = "陽明山芒草"
        hideKeyboard()
        pause(1500)
        find(By.text("加入")).click()
        // 新增頁的標題欄裡也是這幾個字，要等回到清單
        backOnList()
        waitFor(By.text("陽明山芒草"))
        pause(2000)
        screenshot("03-added")

        // 從瀏覽器分享同一個網址進來：已經在清單裡了，不抓，可以跳到那一筆
        share("山行筆記 ${site.url("/hiking")}")
        waitFor(By.text("已經在清單裡了"), timeout = 20_000)
        pause(2000)
        screenshot("04-duplicate")
        find(By.text("打開那一筆")).click()
        waitFor(By.text("編輯"))
        waitFor(By.text("陽明山芒草"))
        pause(2000)
        find(By.desc("返回")).click()
        backOnList()

        // 沒有縮圖的網頁 → 貼圖片網址 → 加入（標題沿用網頁的 <title>）
        setClipboard(site.url("/plain"))
        find(By.res("add_button")).click()
        find(By.text("貼上")).click()
        waitFor(By.text("這個網頁沒有提供縮圖"), timeout = 20_000)
        pause(2000)
        screenshot("05-no-thumbnail")
        find(By.text("貼圖片網址")).click()
        find(By.res("image_url_input")).text = site.url("/photo.png")
        hideKeyboard()
        pause(1000)
        find(By.text("用這張圖")).click()
        waitFor(By.res("thumb_ready"), timeout = 20_000)
        pause(2000)
        screenshot("06-image-url")
        find(By.text("加入")).click()
        backOnList()
        waitFor(By.text(FakeSite.PLAIN_TITLE))
        pause(2000)
        screenshot("07-list")
        assertEquals(listOf(FakeSite.PLAIN_TITLE, "陽明山芒草"), container.links.all().map { it.title })

        // 點一筆：用內建瀏覽器打開，這一頁已經在清單裡
        // 第一次打開瀏覽器要先把 GeckoView 開起來，模擬器上要好幾秒：等網頁載完
        loadPage(site.url("/hiking")) { find(By.text("陽明山芒草")).click() }
        waitFor(By.res("in_list"))
        pause(1500)
        screenshot("08-browser")
        // 網址列換一篇還沒加過的，按「加到清單」走一樣的新增流程，加完回到瀏覽器
        find(By.res("url_bar")).click()
        pause(800)
        find(By.res("url_bar")).text = site.url("/autumn")
        pause(800)
        loadPage(site.url("/autumn")) { device.pressEnter() }
        waitFor(By.res("add_to_list"))
        pause(1500)
        screenshot("09-browser-not-in-list")
        find(By.res("add_to_list")).click()
        waitFor(By.res("thumb_ready"), timeout = 20_000)
        waitFor(By.text(FakeSite.AUTUMN_TITLE))
        pause(2000)
        find(By.text("加入")).click()
        waitFor(By.res("in_list"), timeout = 20_000)
        pause(2000)
        screenshot("10-browser-added")
        find(By.desc("回清單")).click()
        backOnList()
        waitFor(By.text(FakeSite.AUTUMN_TITLE))
        pause(2000)
        assertEquals(listOf(FakeSite.AUTUMN_TITLE, FakeSite.PLAIN_TITLE, "陽明山芒草"), container.links.all().map { it.title })

        // 長按進編輯頁，刪除一筆（要確認）
        find(By.text("陽明山芒草")).longClick()
        reveal(By.text("刪除這一筆")).click()
        waitFor(By.text("刪除這一筆？"))
        pause(1500)
        screenshot("11-confirm-delete")
        find(By.text("刪除")).click()
        backOnList()
        waitGone(By.text("陽明山芒草"))
        pause(2000)

        // 打開匯出：選格式（存檔畫面是系統的，在 SystemUiTest 測）
        find(By.text("匯出")).click()
        waitFor(By.text("Markdown"))
        pause(2500)
        screenshot("12-export")
        find(By.text("取消")).click()
        pause(1500)
        assertEquals(listOf(FakeSite.AUTUMN_TITLE, FakeSite.PLAIN_TITLE), container.links.all().map { it.title })

        // 深色模式：右上角的按鈕選「深色」，整個 App 換成深色；再換回跟系統（設定會記住，不換回來會留給下一個測試）
        find(By.res("theme_button")).click()
        find(By.text("深色")).click()
        pause(2500)
        screenshot("13-dark")
        find(By.res("theme_button")).click()
        find(By.text("跟系統")).click()
        pause(1500)

        // 排序：右上角選「舊的在上」，兩筆上下對調；再換回新的在上（設定一樣會記住）
        find(By.res("sort_button")).click()
        find(By.text("舊的在上")).click()
        waitUntil { above(FakeSite.PLAIN_TITLE, FakeSite.AUTUMN_TITLE) }
        pause(2000)
        screenshot("14-sort-oldest")
        find(By.res("sort_button")).click()
        find(By.text("新的在上")).click()
        waitUntil { above(FakeSite.AUTUMN_TITLE, FakeSite.PLAIN_TITLE) }
        pause(1500)
    }

    /** 清單上 [upper] 在 [lower] 上面。清單重排時拿到的元件可能馬上就舊了，舊了就當作還沒排好。 */
    private fun above(upper: String, lower: String): Boolean = try {
        val a = device.findObject(By.text(upper))?.visibleBounds
        val b = device.findObject(By.text(lower))?.visibleBounds
        a != null && b != null && a.top < b.top
    } catch (e: StaleObjectException) {
        false
    }
}
