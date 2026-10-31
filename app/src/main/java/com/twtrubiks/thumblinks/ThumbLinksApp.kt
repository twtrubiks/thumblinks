package com.twtrubiks.thumblinks

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 畫面。首頁是清單，新增、編輯、內建瀏覽器從它往裡走。[key] 用來分開每一頁的 rememberSaveable（瀏覽器的分頁也用它）。 */
private sealed interface Route {
    val key: String

    data object Home : Route {
        override val key = "home"
    }

    /** [shared]：別的 App 分享過來的文字，一打開就開始抓。 */
    data class Add(val nonce: String, val shared: String?) : Route {
        override val key get() = "add-$nonce"
    }

    data class Edit(val id: String) : Route {
        override val key get() = "edit-$id"
    }

    /** 內建瀏覽器的一個分頁。[url] 是一打開要載入的網址，null 是空白頁（網址列等著輸入）。 */
    data class Browser(val nonce: String, val url: String?) : Route {
        override val key get() = "browser-$nonce"
    }
}

/** 轉螢幕、切到背景再回來時，走到哪一頁要留著。 */
private val StackSaver = listSaver<List<Route>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Route.Home -> "home"
                is Route.Add -> "add|${it.nonce}|${it.shared ?: ""}"
                is Route.Edit -> "edit|${it.id}"
                is Route.Browser -> "browser|${it.nonce}|${it.url ?: ""}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            val parts = it.split("|", limit = 3)
            when (parts[0]) {
                "add" -> Route.Add(parts[1], parts[2].ifEmpty { null })
                "edit" -> Route.Edit(parts[1])
                "browser" -> Route.Browser(parts[1], parts[2].ifEmpty { null })
                else -> Route.Home
            }
        }
    },
)

/**
 * 整個 App。導覽照 treesay 的 `TreeSayApp`：sealed interface 的 Route ＋ rememberSaveable 的 stack，不另外引入 Navigation 套件。
 * [sharedText]：別的 App 分享進來的文字（[MainActivity] 接住的），收到就開一頁新增，處理完呼叫 [onSharedTextTaken]。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ThumbLinksApp(sharedText: String? = null, onSharedTextTaken: () -> Unit = {}) {
    val container = LocalContext.current.container
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Route>(Route.Home)) }
    val holder = rememberSaveableStateHolder()
    // 每換一頁（新增完、編輯完回來）重讀一次清單；讀的時候畫面上留著舊的，不會閃一下空白
    val links by produceState<List<Link>?>(null, stack) {
        value = withContext(Dispatchers.IO) { container.links.all() }
    }

    fun push(route: Route) {
        stack = stack + route
    }
    // 關掉「那一頁」而不是最上面那一頁：存檔的時候剛好又分享進來一個網址，關掉的不能是新開的那頁
    fun close(route: Route) {
        stack = (stack - route).ifEmpty { listOf(Route.Home) }
        holder.removeState(route.key)
        if (route is Route.Browser) container.closeTab(route.key)
    }
    fun browse(url: String?) = push(Route.Browser(UUID.randomUUID().toString(), url))
    fun add(shared: String?) = push(Route.Add(UUID.randomUUID().toString(), shared))
    fun replace(route: Route, with: Route) {
        stack = stack.map { if (it == route) with else it }
        holder.removeState(route.key)
    }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            add(sharedText)
            onSharedTextTaken()
        }
    }

    ThumbLinksTheme(container.theme.mode) {
        Surface(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                .semantics { testTagsAsResourceId = true },
        ) {
            val route = stack.last()
            // 新增、編輯頁自己接返回鍵：要先刪掉在那一頁上傳、還沒存的圖；瀏覽器的返回鍵先回上一頁
            holder.SaveableStateProvider(route.key) {
                when (route) {
                    Route.Home -> ListScreen(
                        links = links,
                        onAdd = { add(null) },
                        onOpen = { browse(it.url) },
                        onEdit = { push(Route.Edit(it.id)) },
                        onBrowse = { browse(null) },
                    )
                    is Route.Add -> AddScreen(
                        initialText = route.shared,
                        onBack = { close(route) },
                        onAdded = { close(route) },
                        onOpenExisting = { replace(route, Route.Edit(it)) },
                    )
                    is Route.Edit -> EditScreen(id = route.id, onBack = { close(route) }, onOpenBrowser = ::browse)
                    is Route.Browser -> BrowserScreen(
                        tabKey = route.key,
                        initialUrl = route.url,
                        links = links,
                        onClose = { close(route) },
                        onAdd = ::add,
                        onEdit = { push(Route.Edit(it)) },
                    )
                }
            }
        }
    }
}
