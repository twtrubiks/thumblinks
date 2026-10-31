package com.twtrubiks.thumblinks

import androidx.compose.foundation.layout.Box
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

    /** 同一筆可以開兩頁（編輯 → 用瀏覽器開 → 已在清單 → 編輯），每一頁有自己的 [nonce]，關掉的才不會是另一頁。 */
    data class Edit(val nonce: String, val id: String) : Route {
        override val key get() = "edit-$nonce"
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
                is Route.Edit -> "edit|${it.nonce}|${it.id}"
                is Route.Browser -> "browser|${it.nonce}|${it.url ?: ""}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            val parts = it.split("|", limit = 3)
            when (parts[0]) {
                "add" -> Route.Add(parts[1], parts[2].ifEmpty { null })
                "edit" -> Route.Edit(parts[1], parts[2])
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
    fun edit(id: String) = Route.Edit(UUID.randomUUID().toString(), id)
    fun replace(route: Route, with: Route) {
        stack = stack.map { if (it == route) with else it }
        holder.removeState(route.key)
    }

    // 內建瀏覽器的引擎與 uBO 先在背景準備好，點進瀏覽器時不用再等引擎啟動、uBO 讀過濾清單
    LaunchedEffect(Unit) { container.warmUpBrowser() }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            add(sharedText)
            onSharedTextTaken()
        }
    }

    ThumbLinksTheme(container.theme.value) {
        // 底色畫到系統列後面（不然狀態列後面露出的是視窗底色，那是跟著系統的深淺，App 設成深色時狀態列的字會看不見），
        // 內容再讓開系統列與鍵盤
        Surface(
            Modifier
                .fillMaxSize()
                // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                .semantics { testTagsAsResourceId = true },
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val route = stack.last()
                // 新增、編輯頁自己接返回鍵：要先刪掉在那一頁上傳、還沒存的圖；瀏覽器的返回鍵先回上一頁
                holder.SaveableStateProvider(route.key) {
                    when (route) {
                        Route.Home -> ListScreen(
                            links = links,
                            onAdd = { add(null) },
                            onOpen = { browse(it.url) },
                            onEdit = { push(edit(it.id)) },
                            onBrowse = { browse(null) },
                        )
                        is Route.Add -> AddScreen(
                            initialText = route.shared,
                            onBack = { close(route) },
                            onAdded = { close(route) },
                            onOpenExisting = { replace(route, edit(it)) },
                        )
                        is Route.Edit -> EditScreen(id = route.id, onBack = { close(route) }, onOpenBrowser = ::browse)
                        is Route.Browser -> BrowserScreen(
                            tabKey = route.key,
                            initialUrl = route.url,
                            links = links,
                            onClose = { close(route) },
                            onAdd = ::add,
                            onEdit = { push(edit(it)) },
                        )
                    }
                }
            }
        }
    }
}
