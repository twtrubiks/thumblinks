package com.twtrubiks.thumblinks

import android.content.ClipboardManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AddPhase { IDLE, NO_URL, DUPLICATE, FETCHING, DONE }

/**
 * 新增一筆：貼上（或分享進來）→ 先查重複 → 抓縮圖與標題 → 預覽、改標題 → 加入。
 * [initialText] 是別的 App 分享過來的文字，一打開就開始抓。沒有縮圖就不能加入。
 */
@Composable
fun AddScreen(initialText: String?, onBack: () -> Unit, onAdded: () -> Unit, onOpenExisting: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var input by rememberSaveable { mutableStateOf("") }
    var phase by rememberSaveable { mutableStateOf(AddPhase.IDLE) }
    // 正在抓或抓好的那個網址；之後改了輸入框，要重新抓才能加入
    var url by rememberSaveable { mutableStateOf<String?>(null) }
    // 畫面上的結果是哪個網址抓的：重試同一個網址又沒抓到縮圖時，留著使用者自己貼的、上傳的圖與改過的標題
    var doneUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var fetchRequest by rememberSaveable { mutableIntStateOf(0) }
    var duplicateId by rememberSaveable { mutableStateOf<String?>(null) }
    var unreachable by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    // 進行中的旗標不跨轉螢幕：協程會跟著舊畫面取消，留著 true 的話按鈕就永遠按不下去
    var saving by remember { mutableStateOf(false) }
    var sharedTaken by rememberSaveable { mutableStateOf(false) }
    val draft = rememberThumbDraft(null)

    fun submit(text: String) {
        focus.clearFocus()
        val found = WebUrls.extract(text)
        input = found ?: text.trim()
        if (found == null) {
            phase = AddPhase.NO_URL
            return
        }
        url = found
        phase = AddPhase.FETCHING
        fetchRequest++
    }

    fun leave() {
        draft.discard(container.thumbsDir)
        onBack()
    }

    BackHandler(onBack = ::leave)

    // 轉螢幕之後 phase 還是 FETCHING 的話，重新抓一次
    LaunchedEffect(fetchRequest) {
        val target = url ?: return@LaunchedEffect
        if (phase != AddPhase.FETCHING) return@LaunchedEffect
        // 網址已經在清單裡就不抓
        val existing = withContext(Dispatchers.IO) { container.links.findByUrl(target) }
        if (existing != null) {
            duplicateId = existing.id
            phase = AddPhase.DUPLICATE
            return@LaunchedEffect
        }
        val result = container.fetcher.fetch(target)
        val retry = doneUrl == target
        val meta = (result as? Fetched.Page)?.meta
        unreachable = result == Fetched.Unreachable
        if (!retry || title.isBlank()) title = meta?.title.orEmpty()
        val image = meta?.imageUrl
        if (image != null || !retry) draft.set(image?.let(Thumbnail::Remote), container.thumbsDir)
        doneUrl = target
        phase = AddPhase.DONE
    }

    LaunchedEffect(initialText) {
        if (initialText != null && !sharedTaken) {
            sharedTaken = true
            submit(initialText)
        }
    }

    val current = WebUrls.extract(input)
    val showResult = phase == AddPhase.DONE && current == url

    Screen(title = "新增連結", onBack = ::leave) { modifier ->
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        if (phase == AddPhase.NO_URL) phase = AddPhase.IDLE
                        // 貼上多行的文字（有些螢幕鍵盤的 Enter 也是送換行）：當作按了「前往」
                        if (it.hasNewline()) submit(it) else input = it
                    },
                    modifier = Modifier.weight(1f).onEnter { submit(input) }.testTag("url_input"),
                    label = { Text("網址") },
                    placeholder = { Text("https://…") },
                    trailingIcon = if (input.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = {
                                input = ""
                                if (phase == AddPhase.NO_URL) phase = AddPhase.IDLE
                            }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "清除") }
                        }
                    },
                    // 長網址換行顯示，貼上之後整串看得到
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit(input) }),
                )
                OutlinedButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text.isNullOrBlank()) {
                        input = ""
                        phase = AddPhase.NO_URL
                    } else {
                        submit(text)
                    }
                }) { Text("貼上") }
            }

            when {
                phase == AddPhase.FETCHING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("正在抓縮圖與標題…", Modifier.padding(start = 12.dp))
                }
                phase == AddPhase.NO_URL -> Text("找不到網址：要是 http:// 或 https:// 開頭的網址", color = MaterialTheme.colorScheme.error)
                phase == AddPhase.DUPLICATE && current == url -> Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已經在清單裡了", style = MaterialTheme.typography.titleMedium)
                        Text(url.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            val id = duplicateId ?: return@TextButton
                            draft.discard(container.thumbsDir)
                            onOpenExisting(id)
                        }) { Text("打開那一筆") }
                    }
                }
                !showResult -> Button(onClick = { submit(input) }, enabled = input.isNotBlank()) { Text("抓縮圖") }
            }

            if (showResult) {
                ThumbnailEditor(
                    draft = draft,
                    thumbsDir = container.thumbsDir,
                    missingReason = if (unreachable) "連不上這個網頁" else "這個網頁沒有提供縮圖",
                )
                if (unreachable) {
                    TextButton(onClick = { submit(input) }) { Text("重試") }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.oneLine() },
                    modifier = Modifier.fillMaxWidth().onEnter { focus.clearFocus() }.testTag("title_input"),
                    label = { Text("標題（選填）") },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                )
                Button(
                    onClick = {
                        val target = url ?: return@Button
                        val thumbnail = draft.thumbnail ?: return@Button
                        saving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { container.links.add(target, title, thumbnail) }
                                draft.committed()
                                onAdded()
                            } catch (e: DuplicateLinkException) {
                                // 抓的時候還沒有，這段時間裡（例如又從別的 App 分享了一次）加進去了
                                duplicateId = e.existing.id
                                phase = AddPhase.DUPLICATE
                            } finally {
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = draft.ready && !saving,
                ) { Text("加入") }
                if (!draft.ready && draft.thumbnail == null) {
                    Text(
                        "沒有縮圖不能加入：貼一個圖片網址，或從手機選一張圖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
