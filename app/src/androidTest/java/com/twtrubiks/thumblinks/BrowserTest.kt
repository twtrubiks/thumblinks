package com.twtrubiks.thumblinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 內建瀏覽器：uBlock Origin 從 AMO 安裝（要連外，CI 的模擬器連得到）、真的擋掉廣告、popup 打得開；
 * 網頁開的新視窗疊在同一個分頁上、`window.close()` 回到原本那頁；載不出來時有錯誤頁。
 * 網頁的內容 UiAutomator 不一定讀得到，只看我們自己畫的網址列，點網頁時用座標。
 * 不錄影，`record.sh` 在 SystemUiTest 之後先 `pm clear` 再跑，所以每次都是第一次安裝 uBO。截圖存成 `browser-*.png`。
 */
@RunWith(AndroidJUnit4::class)
class BrowserTest : UiTest() {
    private val site = FakeSite().start()

    @After
    fun stopSite() = site.close()

    @Test
    fun ublockFromAmoBlocksAds() {
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)

        // 右上角的「瀏覽」：空白頁，網址列等著輸入
        find(By.text("瀏覽")).click()
        find(By.res("url_bar")).click()
        find(By.res("url_bar")).text = site.url("/ads")
        device.pressEnter()

        // 第一次打開瀏覽器時從 AMO 下載安裝，裝好之後工具列才出現 uBO 的按鈕
        waitFor(By.res("ublock_button"), timeout = 120_000)
        screenshot("browser-01-ublock-installed")
        // 網頁可能在 uBO 裝好之前就載完了：重新整理，讓 uBO 過濾這一次的請求
        poll(30_000) {
            find(By.desc("重新整理")).click()
            poll(10_000) { device.findObject(By.res("ublock_badge")) }
        }
        val badge = requireNotNull(device.findObject(By.res("ublock_badge"))) { "uBO 沒有擋下任何東西" }.text
        assertTrue("uBO 擋下的數量：$badge", (badge.toIntOrNull() ?: 0) >= 1)
        screenshot("browser-02-ublock-badge")

        // uBO 的 popup（電源鈕那一頁）放在 BottomSheet 裡
        find(By.res("ublock_button")).click()
        waitFor(By.res("ublock_popup"), timeout = 20_000)
        pause(4000)
        screenshot("browser-03-ublock-popup")
        find(By.text("關閉")).click()
        waitGone(By.res("ublock_popup"))
    }

    @Test
    fun newWindowsStackOnTheSameTab() {
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)
        find(By.text("瀏覽")).click()
        go(site.url("/opener"))
        waitForUrl(site.url("/opener"))
        waitFor(By.desc("重新整理"), timeout = 30_000)
        pause(1500)

        // var w = window.open(); w.location = …：疊一頁新的。以前會在同一頁打開 about:blank，一片白
        tapPage(0.23f)
        waitForUrl(site.url("/autumn"))
        waitFor(By.desc("重新整理"), timeout = 30_000)
        pause(1500)
        screenshot("browser-04-new-window")
        // 新視窗沒有上一頁：關掉它，回到開它的那頁
        find(By.desc("上一頁")).click()
        waitForUrl(site.url("/opener"))
        pause(1500)

        // 登入的小視窗：載完之後改了開它的那頁的網址（#done）再 window.close()，要回到開它的那頁
        tapPage(0.69f)
        waitForUrl(site.url("/popup"))
        waitForUrl(site.url("/opener#done"), timeout = 20_000)
        pause(1000)
        screenshot("browser-05-popup-closed")

        // 找不到的網站：顯示錯誤頁（GeckoView 沒有內建的，以前一片白）
        go("http://thumblinks.invalid/")
        poll(30_000) {
            device.executeShellCommand("logcat -d -s ThumbLinks:W").takeIf { "網頁載入失敗" in it && "thumblinks.invalid" in it }
        } ?: fail("沒有呼叫 onLoadError")
        pause(2000)
        screenshot("browser-06-error-page")
    }

    /** 網址列打字、按「前往」。點下去會整個選起來，`text =` 會整個換掉。 */
    private fun go(url: String) {
        find(By.res("url_bar")).click()
        find(By.res("url_bar")).text = url
        device.pressEnter()
    }

    private fun waitForUrl(url: String, timeout: Long = 30_000) = waitUntil(timeout) { device.findObject(By.res("url_bar"))?.text == url }

    /** 點網頁上 [fraction]（由上往下）高度的地方。 */
    private fun tapPage(fraction: Float) {
        val web = find(By.res("web_view")).visibleBounds
        device.click(web.centerX(), web.top + (web.height() * fraction).toInt())
    }
}
