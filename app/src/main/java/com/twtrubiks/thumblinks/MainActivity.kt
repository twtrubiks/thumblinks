package com.twtrubiks.thumblinks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    /** 別的 App 分享進來、還沒開新增頁的文字。 */
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系統列透明、圖示深淺跟著系統的深色模式
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 轉螢幕重建時 intent 還是同一個分享，那一頁已經開過了
        if (savedInstanceState == null) sharedText = intent.sharedText()
        setContent { ThumbLinksApp(sharedText) { sharedText = null } }
    }

    /** App 已經開著時分享進來（launchMode 是 singleTask，不會再開一個 MainActivity）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.sharedText()?.let { sharedText = it }
    }
}

/** `ACTION_SEND`、`text/plain`：瀏覽器分享的多半是網址，有的 App 是「標題＋網址」，有的把標題放在 SUBJECT。 */
private fun Intent.sharedText(): String? {
    if (action != Intent.ACTION_SEND) return null
    return listOfNotNull(getCharSequenceExtra(Intent.EXTRA_TEXT), getCharSequenceExtra(Intent.EXTRA_SUBJECT))
        .joinToString("\n")
        .ifBlank { null }
}
