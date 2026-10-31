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

/** 畫面。首頁是清單，新增與編輯從它往裡走。[key] 用來分開每一頁的 rememberSaveable。 */
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
}

/** 轉螢幕、切到背景再回來時，走到哪一頁要留著。 */
private val StackSaver = listSaver<List<Route>, String>(
    save = { stack ->
        stack.map {
            when (it) {
                Route.Home -> "home"
                is Route.Add -> "add|${it.nonce}|${it.shared ?: ""}"
                is Route.Edit -> "edit|${it.id}"
            }
        }
    },
    restore = { saved ->
        saved.map {
            val parts = it.split("|", limit = 3)
            when (parts[0]) {
                "add" -> Route.Add(parts[1], parts[2].ifEmpty { null })
                "edit" -> Route.Edit(parts[1])
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
    fun pop() {
        val top = stack.last()
        stack = if (stack.size > 1) stack.dropLast(1) else listOf(Route.Home)
        holder.removeState(top.key)
    }
    fun replaceTop(route: Route) {
        holder.removeState(stack.last().key)
        stack = stack.dropLast(1) + route
    }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            push(Route.Add(UUID.randomUUID().toString(), sharedText))
            onSharedTextTaken()
        }
    }

    ThumbLinksTheme {
        Surface(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                .semantics { testTagsAsResourceId = true },
        ) {
            val route = stack.last()
            // 新增、編輯頁自己接返回鍵：要先刪掉在那一頁上傳、還沒存的圖
            holder.SaveableStateProvider(route.key) {
                when (route) {
                    Route.Home -> ListScreen(
                        links = links,
                        onAdd = { push(Route.Add(UUID.randomUUID().toString(), null)) },
                        onOpen = { push(Route.Edit(it.id)) },
                    )
                    is Route.Add -> AddScreen(
                        initialText = route.shared,
                        onBack = ::pop,
                        onAdded = ::pop,
                        onOpenExisting = { replaceTop(Route.Edit(it)) },
                    )
                    is Route.Edit -> EditScreen(id = route.id, onBack = ::pop)
                }
            }
        }
    }
}
