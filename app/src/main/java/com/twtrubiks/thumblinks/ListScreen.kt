package com.twtrubiks.thumblinks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 首頁就是清單，預設新加的在上面。點一筆用內建瀏覽器打開、長按進編輯頁。
 * 右上角是深色模式、排序、空白的瀏覽器與匯出，右下角新增。
 * [links] 是 [LinkStore.all] 的順序（新的在上），null 表示還在讀；畫面上照使用者選的排序。
 */
@Composable
fun ListScreen(links: List<Link>?, onAdd: () -> Unit, onOpen: (Link) -> Unit, onEdit: (Link) -> Unit, onBrowse: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var choosingFormat by rememberSaveable { mutableStateOf(false) }
    // 存檔畫面回來之前 Activity 可能被重建，選的格式要留著
    var pendingFormat by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
    val order = container.sort.value
    val shown = remember(links, order) { links?.let { sortLinks(it, order) } }
    val listState = rememberLazyListState()
    // 回到清單時先畫的是舊的那份，新的讀進來時 LazyColumn 會跟著原本的第一筆走，新加的那筆可能被擠到畫面外、
    // 看起來像沒加進來：多了新的一筆（links 的第一筆換了、原本的還在）就捲到它
    var newest by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(links) {
        if (links.isNullOrEmpty()) return@LaunchedEffect
        val previous = newest
        val added = links.first().id
        newest = added
        if (previous != null && previous != added && links.any { it.id == previous }) {
            val index = sortLinks(links, order).indexOfFirst { it.id == added }
            if (index >= 0) listState.scrollToItem(index)
        }
    }

    val createFile = rememberLauncherForActivityResult(CreateFile()) { uri ->
        val format = pendingFormat
        pendingFormat = null
        if (uri == null || format == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) { export(context, uri, format) }
                "匯出好了"
            } catch (e: IOException) {
                "匯出失敗：${e.message ?: e.javaClass.simpleName}"
            } catch (e: SecurityException) {
                "匯出失敗：沒有寫入那個位置的權限"
            }
            snackbar.showSnackbar(message)
        }
    }

    Screen(
        title = "ThumbLinks",
        actions = {
            ChoiceButton(
                tag = "theme_button",
                description = "深色模式：${container.theme.value.label}",
                icon = container.theme.value.icon,
                options = ThemeMode.entries,
                selected = container.theme.value,
                label = { it.label },
                optionIcon = { it.icon },
                onSelect = container.theme::set,
            )
            ChoiceButton(
                tag = "sort_button",
                description = "排序：${order.label}",
                icon = R.drawable.ic_sort,
                options = SortOrder.entries,
                selected = order,
                label = { it.label },
                onSelect = {
                    container.sort.set(it)
                    scope.launch { listState.scrollToItem(0) }
                },
            )
            TextButton(onClick = onBrowse) { Text("瀏覽") }
            TextButton(onClick = { choosingFormat = true }, enabled = !links.isNullOrEmpty()) { Text("匯出") }
        },
        fab = {
            // Material3 的 ExtendedFloatingActionButton 把 text 包在 clearAndSetSemantics 裡，「新增」不會給無障礙服務
            // （TalkBack、UiAutomator 都讀不到），所以名字放在圖示的 contentDescription；測試與 record.sh 用 testTag 找
            ExtendedFloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.testTag("add_button"),
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = "新增") },
                text = { Text("新增") },
            )
        },
        snackbar = snackbar,
    ) { modifier ->
        when {
            shown == null -> Unit
            shown.isEmpty() -> EmptyList(modifier)
            else -> LazyColumn(
                modifier.fillMaxSize().testTag("link_list"),
                state = listState,
                // 最後一筆不要被右下角的「新增」蓋住
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = Link::id) { LinkRow(it, container.thumbsDir, onClick = { onOpen(it) }, onLongClick = { onEdit(it) }) }
            }
        }
    }

    if (choosingFormat && !links.isNullOrEmpty()) {
        val zip = Export.needsZip(links)
        AlertDialog(
            onDismissRequest = { choosingFormat = false },
            title = { Text("匯出 ${links.size} 筆") },
            text = {
                Column {
                    if (zip) {
                        Text(
                            "有上傳的縮圖，會打包成一個 zip（清單＋images/）。",
                            Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    for (format in ExportFormat.entries) {
                        ListItem(
                            headlineContent = { Text(format.label) },
                            supportingContent = { Text(Export.fileName(links, format, LocalDate.now())) },
                            modifier = Modifier.clickable {
                                choosingFormat = false
                                pendingFormat = format
                                createFile.launch(
                                    CreateFile.Input(
                                        mimeType = Export.mimeType(links, format),
                                        fileName = Export.fileName(links, format, LocalDate.now()),
                                    ),
                                )
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingFormat = false }) { Text("取消") } },
        )
    }
}

/** 匯出當下的清單（不是畫面上那份，避免中間又有變動），照畫面上的排序，寫進使用者選的位置。 */
private fun export(context: Context, uri: Uri, format: ExportFormat) {
    val container = context.container
    val links = sortLinks(container.links.all(), container.sort.value)
    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
        Export.write(links, format, OffsetDateTime.now(), container.thumbsDir, out)
    } ?: throw IOException("打不開 $uri")
}

/** 系統的存檔畫面，MIME 類型在按下去時才決定（Markdown、JSON 或 zip）。 */
private class CreateFile : ActivityResultContract<CreateFile.Input, Uri?>() {
    data class Input(val mimeType: String, val fileName: String)

    override fun createIntent(context: Context, input: Input): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(input.mimeType)
        .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

@Composable
private fun LinkRow(link: Link, thumbsDir: java.io.File, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ThumbShape)
            .combinedClickable(onClickLabel = "打開", onLongClickLabel = "編輯", onLongClick = onLongClick, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbImage(link.thumbnail, thumbsDir, Modifier.width(128.dp).aspectRatio(THUMB_RATIO).clip(ThumbShape))
        Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                link.title ?: link.url,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                WebUrls.domain(link.url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 右上角的圖示按鈕，按下去從 [options] 選一個（深色模式、排序），目前的打勾。 */
@Composable
private fun <E> ChoiceButton(
    tag: String,
    description: String,
    icon: Int,
    options: List<E>,
    selected: E,
    label: (E) -> String,
    optionIcon: ((E) -> Int)? = null,
    onSelect: (E) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Icon(painterResource(icon), contentDescription = description)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    leadingIcon = optionIcon?.let { { Icon(painterResource(it(option)), contentDescription = null) } },
                    trailingIcon = if (option == selected) ({ Icon(painterResource(R.drawable.ic_check), contentDescription = "目前的設定") }) else null,
                )
            }
        }
    }
}

private val ThemeMode.icon: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.drawable.ic_contrast
        ThemeMode.LIGHT -> R.drawable.ic_light_mode
        ThemeMode.DARK -> R.drawable.ic_dark_mode
    }

@Composable
private fun EmptyList(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("還沒有連結", style = MaterialTheme.typography.titleLarge)
            Text(
                "按右下角的「新增」貼上網址，會幫你抓縮圖和標題。\n也可以在瀏覽器按「分享」，選 ThumbLinks，\n或按右上角的「瀏覽」，看到喜歡的網頁就「加到清單」。",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
