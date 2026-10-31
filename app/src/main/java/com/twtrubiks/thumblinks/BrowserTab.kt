package com.twtrubiks.thumblinks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse

const val TAG = "ThumbLinks"

/** 網頁要使用者回應的對話框（alert、confirm、prompt、`<select>`），回應時 [complete]。 */
class PendingPrompt(val prompt: BasePrompt, private val result: GeckoResult<PromptResponse>) {
    fun complete(response: PromptResponse) = result.complete(response)

    fun dismiss() = result.complete(prompt.dismiss())
}

/**
 * 內建瀏覽器的一個分頁：一個 GeckoSession 與畫面要用的狀態。放在 [AppContainer]，轉螢幕、
 * 進出新增頁都不會重新載入。delegate 都在主執行緒被呼叫，直接改 Compose 的狀態。
 * 下載、上傳檔案不做；定位、相機等權限一律拒絕。
 */
class BrowserTab(private val context: Context, private val runtime: GeckoRuntime, initialUrl: String?) {
    val session = GeckoSession()

    var url by mutableStateOf(initialUrl.orEmpty())
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var prompt by mutableStateOf<PendingPrompt?>(null)
        private set
    var contextMenu by mutableStateOf<ContextElement?>(null)

    /** 給使用者看的一句話（例如「不支援下載」），顯示完設回 null。 */
    var message by mutableStateOf<String?>(null)

    /** uBO 在這個分頁自己的按鈕狀態（擋了幾個的 badge），畫面用 [Ublock.actionFor] 跟預設值合起來。 */
    var ublockAction by mutableStateOf<WebExtension.Action?>(null)

    init {
        session.navigationDelegate = Navigation()
        session.progressDelegate = Progress()
        session.contentDelegate = Content()
        session.promptDelegate = Prompts()
        session.permissionDelegate = Permissions()
        session.open(runtime)
        if (url.isNotEmpty()) session.loadUri(url)
    }

    fun load(uri: String) {
        url = uri
        session.loadUri(uri)
    }

    fun close() {
        prompt?.dismiss()
        session.close()
    }

    /** 網頁內容的行程掛掉或被系統收掉：重開一個再載入一次。 */
    private fun recover() {
        loading = false
        fullscreen = false
        if (session.isOpen) session.close()
        session.open(runtime)
        if (url.isNotEmpty()) session.loadUri(url)
    }

    /**
     * 交給手機上別的 App（`mailto:`、`tel:`、`intent:`…）。只在使用者點了之後才開，網頁不能自己叫出別的 App。
     * `intent:` 照 Chrome 的做法：拿掉指定的元件，找不到 App 時改開 `browser_fallback_url`。
     */
    private fun openExternally(uri: String) {
        try {
            val intent = if (uri.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri.toUri())
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && BrowserInput.canAdd(fallback)) load(fallback) else message = "手機上沒有可以打開這個連結的 App"
            }
        } catch (e: Exception) {
            Log.w(TAG, "打不開 $uri", e)
            message = "打不開這個連結"
        }
    }

    private inner class Navigation : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<ContentPermission>, hasUserGesture: Boolean) {
            if (url != null) this@BrowserTab.url = url
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@BrowserTab.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            this@BrowserTab.canGoForward = canGoForward
        }

        override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny>? {
            if (!BrowserInput.isWeb(request.uri)) {
                if (request.hasUserGesture) openExternally(request.uri)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            // 只有一個分頁：target=_blank 在同一個分頁開。沒有使用者點擊的彈出視窗（廣告）不理
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                if (request.hasUserGesture) load(request.uri)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            return null
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            load(uri)
            return null
        }
    }

    private inner class Progress : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            loading = true
            progress = 0
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            this@BrowserTab.progress = progress
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            loading = false
            // release 冒煙測試看 logcat 的這一行，確認壓過的 GeckoView 真的載入了網頁
            Log.i(TAG, "網頁載入完成 success=$success $url")
        }
    }

    private inner class Content : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            this@BrowserTab.title = title.orEmpty()
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            fullscreen = fullScreen
        }

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) {
            if (element.linkUri != null || element.srcUri != null) contextMenu = element
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            message = "不支援下載檔案"
        }

        override fun onCrash(session: GeckoSession) {
            Log.w(TAG, "網頁的行程掛了，重新載入 $url")
            recover()
        }

        override fun onKill(session: GeckoSession) {
            Log.w(TAG, "網頁的行程被系統收掉了，重新載入 $url")
            recover()
        }
    }

    /** alert、confirm、prompt、`<select>`、離開頁面與重新送出表單的確認。其他（上傳檔案、選日期…）交給預設：直接取消。 */
    private inner class Prompts : PromptDelegate {
        private fun ask(p: BasePrompt): GeckoResult<PromptResponse> {
            this@BrowserTab.prompt?.dismiss()
            val result = GeckoResult<PromptResponse>()
            val pending = PendingPrompt(p, result)
            // 網頁自己把對話框收掉（例如換頁了）時，畫面上的也收掉
            p.delegate = object : PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: BasePrompt) {
                    if (this@BrowserTab.prompt === pending) this@BrowserTab.prompt = null
                }
            }
            this@BrowserTab.prompt = pending
            return result.then { response ->
                if (this@BrowserTab.prompt === pending) this@BrowserTab.prompt = null
                GeckoResult.fromValue(response)
            }
        }

        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt) = ask(prompt)

        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt) = ask(prompt)

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt) = ask(prompt)

        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt) = ask(prompt)

        override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt) = ask(prompt)

        override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt) = ask(prompt)
    }

    private inner class Permissions : PermissionDelegate {
        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: PermissionDelegate.Callback) {
            callback.reject()
        }

        // 靜音的自動播放（很多網站的影片預覽）放行，其他（定位、通知、有聲自動播放、DRM…）一律拒絕
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> =
            GeckoResult.fromValue(
                if (perm.permission == PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
            )

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) {
            callback.reject()
        }
    }
}
