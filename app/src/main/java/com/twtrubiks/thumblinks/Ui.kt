package com.twtrubiks.thumblinks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource

/**
 * 把網址放進剪貼簿（清單的長按選單、編輯頁、內建瀏覽器都用這個），再用 [notify] 說「已複製網址」。
 * Android 13 以後系統自己會在畫面下方跳複製的提示，就不再說一次。
 */
fun copyUrl(context: Context, url: String, notify: (String) -> Unit) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("url", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notify("已複製網址")
}

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

/**
 * 愛心：清單每一筆的右邊、編輯頁右上角、清單右上角的「只看愛心」都是它。
 * 按了是實心的紅色，沒按是空心的；無障礙服務（TalkBack、UiAutomator）讀得到勾選狀態。
 */
@Composable
fun HeartToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, description: String, modifier: Modifier = Modifier) {
    IconToggleButton(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier) {
        Icon(
            painterResource(if (checked) R.drawable.ic_favorite else R.drawable.ic_favorite_border),
            contentDescription = description,
            tint = if (checked) HeartRed else LocalContentColor.current,
        )
    }
}

// 淺色、深色底都看得清楚的紅
private val HeartRed = Color(0xFFE91E63)
