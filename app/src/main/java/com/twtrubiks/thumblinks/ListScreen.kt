package com.twtrubiks.thumblinks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
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
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 首頁就是清單，預設新加的在上面。點一筆用內建瀏覽器打開、長按跳選單（編輯、複製網址）、右邊的愛心切換喜歡的。
 * 右上角是深色模式、排序、只看愛心、空白的瀏覽器，「更多」裡是匯入、匯出；右下角新增。
 * [links] 是 [LinkStore.all] 的順序（新的在上），null 表示還在讀；畫面上照使用者選的排序。
 * 匯入完呼叫 [onImported] 重讀清單。
 */
@Composable
fun ListScreen(
    links: List<Link>?,
    onAdd: () -> Unit,
    onOpen: (Link) -> Unit,
    onEdit: (Link) -> Unit,
    onFavorite: (Link, Boolean) -> Unit,
    onBrowse: () -> Unit,
    onImported: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var choosingFormat by rememberSaveable { mutableStateOf(false) }
    // 存檔畫面回來之前 Activity 可能被重建，選的格式要留著
    var pendingFormat by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
    val order = container.sort.value
    // 只看愛心不存起來：下次打開 App 一樣看到全部，不會以為清單少了東西
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val shown = remember(links, order, favoritesOnly) { links?.let { visibleLinks(it, order, favoritesOnly) } }
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
            val index = visibleLinks(links, order, favoritesOnly).indexOfFirst { it.id == added }
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

    // 匯入：選檔 → 先讀一遍、算好要加幾筆 → 確認後才加。對話框開著時 Activity 不會重建（轉螢幕不重建），用 remember 就好
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var importing by remember { mutableStateOf(false) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val failure = try {
                pendingImport = withContext(Dispatchers.IO) { prepareImport(context, uri) }
                null
            } catch (e: ImportException) {
                e.message
            } catch (e: IOException) {
                "讀不到這個檔案"
            } catch (e: SecurityException) {
                "沒有讀取這個檔案的權限"
            }
            failure?.let { snackbar.showSnackbar("匯入失敗：$it") }
        }
    }
    fun closeImport() {
        pendingImport?.let { pending -> scope.launch(Dispatchers.IO) { pending.discard() } }
        pendingImport = null
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
            HeartToggle(
                checked = favoritesOnly,
                onCheckedChange = {
                    favoritesOnly = it
                    scope.launch { listState.scrollToItem(0) }
                },
                description = "只看愛心",
                modifier = Modifier.testTag("favorites_only"),
            )
            TextButton(onClick = onBrowse) { Text("瀏覽") }
            Box {
                var more by remember { mutableStateOf(false) }
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("more_button")) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "更多")
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text("匯入") }, onClick = {
                        more = false
                        openFile.launch(IMPORT_TYPES)
                    })
                    DropdownMenuItem(text = { Text("匯出") }, enabled = !links.isNullOrEmpty(), onClick = {
                        more = false
                        choosingFormat = true
                    })
                }
            }
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
            links.isNullOrEmpty() -> EmptyList(modifier, onImport = { openFile.launch(IMPORT_TYPES) })
            shown.isEmpty() -> NoFavorites(modifier)
            else -> LazyColumn(
                modifier.fillMaxSize().testTag("link_list"),
                state = listState,
                // 最後一筆不要被右下角的「新增」蓋住
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = Link::id) {
                    LinkRow(
                        it,
                        container.thumbsDir,
                        onClick = { onOpen(it) },
                        onEdit = { onEdit(it) },
                        onCopy = { copyUrl(context, it.url) { message -> scope.launch { snackbar.showSnackbar(message) } } },
                        onFavorite = { favorite -> onFavorite(it, favorite) },
                    )
                }
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

    pendingImport?.let { pending ->
        val plan = pending.plan
        AlertDialog(
            onDismissRequest = { if (!importing) closeImport() },
            title = { Text(if (plan.toAdd.isEmpty()) "沒有可以匯入的" else "匯入 ${plan.toAdd.size} 筆") },
            text = { Text(importSummary(plan)) },
            confirmButton = {
                if (plan.toAdd.isNotEmpty()) {
                    TextButton(enabled = !importing, onClick = {
                        importing = true
                        scope.launch {
                            val message = try {
                                val result = withContext(Dispatchers.IO) {
                                    Import.run(plan, pending.file, container.links, container.thumbsDir, ThumbPrep::saveImported)
                                }
                                importMessage(result)
                            } catch (e: IOException) {
                                // 磁碟滿了之類：已經加進去的留著
                                "匯入到一半失敗：${e.message ?: e.javaClass.simpleName}"
                            }
                            withContext(Dispatchers.IO) { pending.discard() }
                            pendingImport = null
                            importing = false
                            onImported()
                            snackbar.showSnackbar(message)
                        }
                    }) { Text(if (importing) "匯入中…" else "匯入") }
                }
            },
            dismissButton = {
                TextButton(enabled = !importing, onClick = ::closeImport) { Text(if (plan.toAdd.isEmpty()) "關閉" else "取消") }
            },
        )
    }
}

/** 讀好、還沒加的匯入：確認了才加，取消或加完都要 [discard]（關掉 zip、刪掉複製進來的檔案）。 */
private class PendingImport(val file: ImportFile, val plan: ImportPlan, private val copy: File) {
    fun discard() {
        file.close()
        copy.delete()
    }
}

/**
 * 系統的開檔畫面不限類型：Markdown 在 Android 9 常常是 `application/octet-stream`，限了就選不到。
 * 選錯檔案由 [Import.read] 看內容擋下來。
 */
private val IMPORT_TYPES = arrayOf("*/*")

/** 匯入的檔案最大多少：zip 裡的圖一張 100～300 KB，上千張也夠。 */
private const val MAX_IMPORT = 1024L * 1024 * 1024

/**
 * 把選的檔案複製進 cache（zip 要能隨機讀，[java.util.zip.ZipFile] 只吃檔案），讀一遍、算好要加的。
 * 上一次沒清掉的（App 在對話框開著時被系統收掉）一起刪。
 */
private fun prepareImport(context: Context, uri: Uri): PendingImport {
    val dir = File(context.cacheDir, "import").apply {
        deleteRecursively()
        mkdirs()
    }
    val copy = File(dir, "import")
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("打不開 $uri")
    input.use { from ->
        copy.outputStream().use { to ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT) {
                    to.close()
                    copy.delete()
                    throw ImportException("檔案太大了，不像是 ThumbLinks 匯出的")
                }
                to.write(buffer, 0, n)
            }
        }
    }
    val file = try {
        Import.read(copy)
    } catch (e: Exception) {
        copy.delete()
        throw e
    }
    return try {
        PendingImport(file, Import.plan(file) { context.container.links.findByUrl(it) != null }, copy)
    } catch (e: Exception) {
        file.close()
        copy.delete()
        throw e
    }
}

/** 對話框裡的說明：檔案裡有幾筆、哪些會跳過。 */
private fun importSummary(plan: ImportPlan) = buildList {
    add("檔案裡有 ${plan.total} 筆。")
    if (plan.duplicates > 0) add("${plan.duplicates} 筆已經在清單裡，會跳過（清單上的不會被改）。")
    if (plan.broken > 0) add("${plan.broken} 筆讀不出來（少了網址或縮圖），會跳過。")
}.joinToString("\n")

/** 匯入完的結果。 */
private fun importMessage(result: ImportResult) = buildList {
    add("加入 ${result.added} 筆")
    if (result.duplicates > 0) add("${result.duplicates} 筆已經在清單裡")
    if (result.broken > 0) add("${result.broken} 筆讀不出來")
}.joinToString("，")

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
private fun LinkRow(
    link: Link,
    thumbsDir: java.io.File,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onFavorite: (Boolean) -> Unit,
) {
    // 長按跳的選單，之後要加的動作（分享…）也放這裡
    var menu by remember { mutableStateOf(false) }
    Box {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("編輯") }, onClick = {
                menu = false
                onEdit()
            })
            DropdownMenuItem(text = { Text("複製網址") }, onClick = {
                menu = false
                onCopy()
            })
        }
        LinkRowContent(link, thumbsDir, onClick, onLongClick = { menu = true }, onFavorite)
    }
}

@Composable
private fun LinkRowContent(link: Link, thumbsDir: java.io.File, onClick: () -> Unit, onLongClick: () -> Unit, onFavorite: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ThumbShape)
            .combinedClickable(onClickLabel = "打開", onLongClickLabel = "選單", onLongClick = onLongClick, onClick = onClick)
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
        // 自己的點擊範圍（48dp），點愛心不會打開網頁
        HeartToggle(link.favorite, onFavorite, description = "愛心", modifier = Modifier.testTag("favorite_button"))
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
private fun EmptyList(modifier: Modifier, onImport: () -> Unit) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("還沒有連結", style = MaterialTheme.typography.titleLarge)
            Text(
                "按右下角的「新增」貼上網址，會幫你抓縮圖和標題。\n也可以在瀏覽器按「分享」，選 ThumbLinks，\n或按右上角的「瀏覽」，看到喜歡的網頁就「加到清單」。",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "換了手機或重裝過，可以匯入之前匯出的檔案。",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onImport, modifier = Modifier.testTag("import_button")) { Text("從檔案匯入") }
        }
    }
}

/** 開了「只看愛心」但一筆都還沒按。 */
@Composable
private fun NoFavorites(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("還沒有愛心", style = MaterialTheme.typography.titleLarge)
            Text(
                "點清單上每一筆右邊的愛心，就會出現在這裡。\n再按一次右上角的愛心，看全部的清單。",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
