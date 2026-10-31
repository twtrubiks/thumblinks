package com.twtrubiks.thumblinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 錄影用的展示流程，CI 只錄這個 class，`scripts/ci/record.sh` 會在執行期間錄下整個螢幕。
 * 網頁由模擬器上的假網站（[FakeSite]）提供，不連外。全長控制在 150 秒內。
 *
 * 貼網址 → 抓到縮圖與標題 → 改標題 → 加入；分享同一個網址進來 → 已經在清單裡了 → 打開那一筆；
 * 貼沒有縮圖的網址 → 貼圖片網址 → 加入；刪除一筆；打開匯出。
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
        find(By.text("新增")).click()
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

        // 沒有縮圖的網頁 → 貼圖片網址 → 加入（標題沿用網頁的 <title>）
        setClipboard(site.url("/plain"))
        find(By.text("新增")).click()
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
        waitFor(By.text(FakeSite.PLAIN_TITLE))
        pause(2000)
        screenshot("07-list")
        assertEquals(listOf(FakeSite.PLAIN_TITLE, "陽明山芒草"), container.links.all().map { it.title })

        // 刪除一筆（要確認）
        find(By.text("陽明山芒草")).click()
        reveal(By.text("刪除這一筆")).click()
        waitFor(By.text("刪除這一筆？"))
        pause(1500)
        screenshot("08-confirm-delete")
        find(By.text("刪除")).click()
        // 回到清單，只剩另一筆
        waitFor(By.text(FakeSite.PLAIN_TITLE))
        waitGone(By.text("陽明山芒草"))
        pause(2000)

        // 打開匯出：選格式（存檔畫面是系統的，在 SystemUiTest 測）
        find(By.text("匯出")).click()
        waitFor(By.text("Markdown"))
        pause(2500)
        screenshot("09-export")
        find(By.text("取消")).click()
        pause(1500)
        assertEquals(listOf(FakeSite.PLAIN_TITLE), container.links.all().map { it.title })
    }
}
