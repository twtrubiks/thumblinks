package com.twtrubiks.thumblinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
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
        // 網頁可能在 uBO 準備好之前就載完了，uBO 剛裝好、還在讀過濾清單時 badge 是「!」：
        // 重新整理，直到 badge 是這一頁擋下的數量
        val blocked = poll(90_000) {
            waitFor(By.desc("重新整理"), timeout = 30_000).click()
            poll(10_000) { badge()?.toIntOrNull()?.takeIf { it >= 1 } }
        }
        assertTrue("uBO 沒有擋下任何東西，badge：${badge()}", (blocked ?: 0) >= 1)
        // uBO 換頁時會先清掉 badge 再重算，截圖前再等它出現一次
        poll(10_000) { badge()?.toIntOrNull() }
        screenshot("browser-02-ublock-badge")

        // uBO 的 popup（電源鈕那一頁）放在 BottomSheet 裡
        // BottomSheet 是另一個視窗：看它的「關閉」（popup 的內容在 GeckoView 裡讀不到）
        find(By.res("ublock_button")).click()
        waitFor(By.text("關閉"), timeout = 20_000)
        pause(4000)
        screenshot("browser-03-ublock-popup")
        find(By.text("關閉")).click()
        waitGone(By.text("關閉"))
    }

    @Test
    fun newWindowsStackOnTheSameTab() {
        clearLinks()
        launchApp()
        waitFor(By.text("還沒有連結"), timeout = 30_000)
        find(By.text("瀏覽")).click()
        loadPage(site.url("/opener")) { go(site.url("/opener")) }

        // var w = window.open(); w.location = …：疊一頁新的。以前會在同一頁打開 about:blank，一片白
        loadPage(site.url("/autumn")) { tapPage(0.23f) }
        waitForUrl(site.url("/autumn"))
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

    /** uBO 擋了幾個（badge 的字）。badge 一直在更新，拿到的元件可能馬上就舊了，舊了就當作還沒有。 */
    private fun badge(): String? = try {
        device.findObject(By.res("ublock_badge"))?.text
    } catch (e: StaleObjectException) {
        null
    }

    /** 網址列打字、按「前往」。點下去會整個選起來，`text =` 會整個換掉。 */
    private fun go(url: String) {
        find(By.res("url_bar")).click()
        find(By.res("url_bar")).text = url
        device.pressEnter()
    }

    private fun waitForUrl(url: String, timeout: Long = 30_000) = waitUntil(timeout) { device.findObject(By.res("url_bar"))?.text == url }

    /**
     * 點網頁上 [fraction]（由上往下）高度的地方。GeckoView 外面那層的 testTag UiAutomator 讀不到，
     * 網頁的範圍用上面的網址列與下面的「上一頁」推出來。
     */
    private fun tapPage(fraction: Float) {
        val top = find(By.res("url_bar")).visibleBounds.bottom
        val bottom = find(By.desc("上一頁")).visibleBounds.top
        device.click(device.displayWidth / 2, top + ((bottom - top) * fraction).toInt())
    }
}
