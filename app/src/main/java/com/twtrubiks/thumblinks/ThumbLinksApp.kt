package com.twtrubiks.thumblinks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 整個 App。目前只有骨架：列出清單。加入連結、抓縮圖、上傳、匯出照 CLAUDE.md 的規格一步步長出來，
 * 頁面多了之後導覽也放在這裡（照 treesay 的 `TreeSayApp`：sealed interface 的 Route + rememberSaveable 的 stack）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ThumbLinksApp() {
    val container = LocalContext.current.container
    val links by produceState<List<Link>?>(null) {
        value = withContext(Dispatchers.IO) { container.links.all() }
    }

    ThumbLinksTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    // 讓 UiAutomator 用 By.res(...) 找得到 testTag
                    .semantics { testTagsAsResourceId = true }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("ThumbLinks", style = MaterialTheme.typography.headlineMedium)
                val list = links
                when {
                    list == null -> Unit
                    list.isEmpty() -> Text(
                        "還沒有連結。貼上網址，就會幫你抓縮圖。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = Link::id) { Text(it.title ?: it.url) }
                    }
                }
            }
        }
    }
}
