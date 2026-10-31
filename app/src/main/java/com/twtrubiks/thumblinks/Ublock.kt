package com.twtrubiks.thumblinks

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.util.concurrent.TimeUnit

/**
 * uBlock Origin。不打包進 APK，跟 Firefox 一樣從 Mozilla 附加元件網站（AMO）裝有簽章的版本：
 * 第一次打開瀏覽器時裝，之後裝好的會留在 GeckoView 的設定檔裡；一天最多檢查一次 AMO 有沒有新版。
 * 擋了幾個（badge）每個分頁各自記在 [BrowserTab.ublockAction]；按下去的 popup（那顆大電源鈕）是 [popup]。
 */
class Ublock(private val runtime: GeckoRuntime, private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    var extension by mutableStateOf<WebExtension?>(null)
        private set
    var icon by mutableStateOf<Bitmap?>(null)
        private set

    /** 所有分頁共用的按鈕狀態（圖示、標題）；每個分頁自己的（badge）在 [BrowserTab.ublockAction]，用 [actionFor] 合起來。 */
    var defaultAction by mutableStateOf<WebExtension.Action?>(null)
        private set

    /** 打開中的 popup。關掉時呼叫 [closePopup]。 */
    var popup by mutableStateOf<GeckoSession?>(null)
        private set

    private val controller get() = runtime.webExtensionController
    private var started = false
    private val tabs = mutableListOf<BrowserTab>()

    /** 在主執行緒呼叫；重複呼叫沒關係。上次裝失敗（例如沒有網路）的話，這次再試一次。 */
    fun start() {
        if (started) return
        started = true
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            // 只同意 uBO 自己的權限；不給無痕模式（沒有無痕）、不給 Mozilla 的資料收集
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>,
            ) = GeckoResult.fromValue(WebExtension.PermissionPromptResponse(extension.id == ID, false, false))

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>,
            ) = GeckoResult.fromValue(if (extension.id == ID) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
        }
        controller.list().accept({ list ->
            val installed = list?.firstOrNull { it.id == ID }
            if (installed == null) install() else ready(installed).also { maybeUpdate(installed) }
        }, { e ->
            Log.w(TAG, "列不出已經裝的擴充功能", e)
            install()
        })
    }

    private fun install() {
        Log.i(TAG, "從 AMO 安裝 uBlock Origin")
        controller.install(AMO_URL).accept({ installed ->
            if (installed != null) {
                prefs.edit { putLong(CHECKED_AT, now()) }
                ready(installed)
            }
        }, { e ->
            Log.w(TAG, "uBlock Origin 裝不起來，下次打開瀏覽器再試", e)
            started = false
        })
    }

    private fun maybeUpdate(installed: WebExtension) {
        if (now() - prefs.getLong(CHECKED_AT, 0) < TimeUnit.DAYS.toMillis(1)) return
        controller.update(installed).accept({ updated ->
            prefs.edit { putLong(CHECKED_AT, now()) }
            if (updated != null) ready(updated)
        }, { e -> Log.w(TAG, "uBlock Origin 檢查更新失敗", e) })
    }

    private fun ready(installed: WebExtension) {
        // release 冒煙測試看 logcat 的這一行，確認壓過的 GeckoView 從 AMO 裝好了 uBO
        Log.i(TAG, "uBlock Origin ${installed.metaData.version} 可以用了")
        extension = installed
        installed.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                // session 是 null：所有分頁的預設值（圖示、標題）
                defaultAction = action
            }

            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) = openPopup()

            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action) = openPopup()
        })
        // popup 裡的「打開控制台」之類會開新分頁：只有一個分頁，在目前的分頁開
        installed.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(extension: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
                closePopup()
                details.url?.let { url -> tabs.lastOrNull()?.load(url) }
                return null
            }
        })
        installed.metaData.icon.getBitmap(96).accept({ icon = it }, { e -> Log.w(TAG, "uBO 的圖示讀不出來", e) })
        tabs.forEach(::attachTo)
    }

    /** 分頁開起來時登記，uBO 好了之後才接得上它的 badge。 */
    fun register(tab: BrowserTab) {
        tabs += tab
        attachTo(tab)
    }

    fun unregister(tab: BrowserTab) {
        tabs -= tab
    }

    /** 正在看的分頁：uBO 的 popup 用 `tabs.query({active: true})` 找要顯示哪一頁的統計。 */
    fun setActive(tab: BrowserTab, active: Boolean) = controller.setTabActive(tab.session, active)

    private fun attachTo(tab: BrowserTab) {
        val ext = extension ?: return
        tab.session.webExtensionController.setActionDelegate(ext, object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                tab.ublockAction = action
            }
        })
    }

    /** 這個分頁上 uBO 按鈕該長怎樣；uBO 還沒好是 null。 */
    fun actionFor(tab: BrowserTab): WebExtension.Action? {
        if (extension == null) return null
        val own = tab.ublockAction ?: return defaultAction
        return defaultAction?.let(own::withDefault) ?: own
    }

    private fun openPopup(): GeckoResult<GeckoSession> {
        closePopup()
        val session = GeckoSession()
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            // popup 自己呼叫 window.close()
            override fun onCloseRequest(session: GeckoSession) = closePopup()
        }
        session.open(runtime)
        popup = session
        return GeckoResult.fromValue(session)
    }

    fun closePopup() {
        popup?.close()
        popup = null
    }

    companion object {
        const val ID = "uBlock0@raymondhill.net"

        /** AMO 上最新的簽章版，會轉址到 `ublock_origin-<版本>.xpi`。 */
        const val AMO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
        private const val CHECKED_AT = "ublock_checked_at"
    }
}
