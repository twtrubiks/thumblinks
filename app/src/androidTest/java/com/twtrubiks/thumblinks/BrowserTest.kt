package com.twtrubiks.thumblinks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 內建瀏覽器的 uBlock Origin：從 AMO 安裝（要連外，CI 的模擬器連得到）、真的擋掉廣告、popup 打得開。
 * 不錄影，`record.sh` 在 SystemUiTest 之後先 `pm clear` 再跑，所以每次都是第一次安裝。截圖存成 `browser-*.png`。
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
}
