package com.twtrubiks.thumblinks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 編輯一筆：改標題、換縮圖（重新抓、貼圖片網址、上傳）、用瀏覽器開、刪除。網址不能改。 */
@Composable
fun EditScreen(id: String, onBack: () -> Unit, onOpenBrowser: (String) -> Unit) {
    val container = LocalContext.current.container
    // 資料庫在 IO 讀。外層的 null 是還沒讀到；讀到的是 null 表示那一筆不在了（例如已經刪掉），回上一頁
    val loaded by produceState<Lookup?>(null, id) {
        value = Lookup(withContext(Dispatchers.IO) { container.links.find(id) })
    }
    val link = loaded?.link
    when {
        loaded == null -> Screen(title = "編輯", onBack = onBack) {}
        link == null -> LaunchedEffect(Unit) { onBack() }
        else -> EditLink(link, onBack, onOpenBrowser)
    }
}

private class Lookup(val link: Link?)

@Composable
private fun EditLink(link: Link, onBack: () -> Unit, onOpenBrowser: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var title by rememberSaveable { mutableStateOf(link.title.orEmpty()) }
    val draft = rememberThumbDraft(link.thumbnail)
    var refetching by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun leave() {
        draft.discard(container.thumbsDir)
        onBack()
    }

    BackHandler(onBack = ::leave)

    // 原本的縮圖現在載不出來（例如離線）也能只改標題；換了別張的話，那張要載得出來
    val canSave = !saving && (draft.thumbnail == link.thumbnail || draft.ready)

    Screen(title = "編輯", onBack = ::leave, snackbar = snackbar) { modifier ->
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(WebUrls.domain(link.url), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(link.url, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { onOpenBrowser(link.url) }) { Text("用瀏覽器開") }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it.oneLine() },
                modifier = Modifier.fillMaxWidth().onEnter { focus.clearFocus() }.testTag("title_input"),
                label = { Text("標題（選填）") },
                placeholder = { Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )

            ThumbnailEditor(draft = draft, thumbsDir = container.thumbsDir, missingReason = null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        refetching = true
                        scope.launch {
                            val message = when (val result = container.fetcher.fetch(link.url)) {
                                Fetched.Unreachable -> "連不上這個網頁"
                                is Fetched.Page -> {
                                    val image = result.meta.imageUrl
                                    if (image == null) {
                                        "這個網頁沒有提供縮圖"
                                    } else {
                                        draft.set(Thumbnail.Remote(image), container.thumbsDir)
                                        null
                                    }
                                }
                            }
                            refetching = false
                            message?.let { snackbar.showSnackbar(it) }
                        }
                    },
                    enabled = !refetching,
                ) { Text("重新抓縮圖") }
                if (refetching) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp), strokeWidth = 3.dp)
            }

            Button(
                onClick = {
                    val thumbnail = draft.thumbnail ?: return@Button
                    saving = true
                    scope.launch {
                        withContext(Dispatchers.IO) { container.links.update(link.id, title, thumbnail) }
                        draft.committed()
                        saving = false
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave,
            ) { Text("儲存") }

            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("刪除這一筆") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除這一筆？") },
            text = { Text(link.title ?: link.url) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) { container.links.delete(link.id) }
                            draft.discard(container.thumbsDir)
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
