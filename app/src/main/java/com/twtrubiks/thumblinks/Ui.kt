package com.twtrubiks.thumblinks

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource

/**
 * 長的網址、標題在輸入框裡換行顯示（單行的框貼上長網址後，要拖回前面很難），但內容只有一行：
 * 換行（貼上的、實體鍵盤的 Enter）換成空白。網址欄收到換行時當作按了「前往」，用 [hasNewline] 判斷。
 */
fun String.oneLine(): String = replace(NEWLINES, " ")

fun String.hasNewline(): Boolean = any { it == '\n' || it == '\r' }

private val NEWLINES = Regex("[\r\n]+")

/**
 * 換行顯示的輸入框（不是 singleLine）按實體鍵盤的 Enter 會插入換行：先攔下來做 [action]（前往、抓縮圖…）。
 * 螢幕鍵盤的「前往」「完成」還是走 KeyboardActions。
 */
fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.key != Key.Enter && it.key != Key.NumPadEnter) return@onPreviewKeyEvent false
    if (it.type == KeyEventType.KeyDown) action()
    true
}

/**
 * 每一頁的骨架：上面的標題列（[onBack] 有給就有返回鍵）、右上角的 [actions]、右下角的 [fab]、下面的提示。
 * [content] 拿到的 Modifier 已經讓開標題列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回") }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = fab,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding: PaddingValues ->
        content(Modifier.padding(padding).consumeWindowInsets(padding))
    }
}
