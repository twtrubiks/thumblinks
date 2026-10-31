package com.twtrubiks.thumblinks

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** 首頁就是清單，新加的在上面。右上角匯出、右下角新增。[links] 是 null 表示還在讀。 */
@Composable
fun ListScreen(links: List<Link>?, onAdd: () -> Unit, onOpen: (Link) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var choosingFormat by rememberSaveable { mutableStateOf(false) }
    // 存檔畫面回來之前 Activity 可能被重建，選的格式要留著
    var pendingFormat by rememberSaveable { mutableStateOf<ExportFormat?>(null) }

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
            TextButton(onClick = { choosingFormat = true }, enabled = !links.isNullOrEmpty()) { Text("匯出") }
        },
        fab = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("新增") },
            )
        },
        snackbar = snackbar,
    ) { modifier ->
        when {
            links == null -> Unit
            links.isEmpty() -> EmptyList(modifier)
            else -> LazyColumn(
                modifier.fillMaxSize().testTag("link_list"),
                // 最後一筆不要被右下角的「新增」蓋住
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(links, key = Link::id) { LinkRow(it, container.thumbsDir) { onOpen(it) } }
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

/** 匯出當下的清單（不是畫面上那份，避免中間又有變動），寫進使用者選的位置。 */
private fun export(context: Context, uri: Uri, format: ExportFormat) {
    val container = context.container
    val links = container.links.all()
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
private fun LinkRow(link: Link, thumbsDir: java.io.File, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ThumbShape)
            .clickable(onClick = onClick)
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

@Composable
private fun EmptyList(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("還沒有連結", style = MaterialTheme.typography.titleLarge)
            Text(
                "按右下角的「新增」貼上網址，會幫你抓縮圖和標題。\n也可以在瀏覽器按「分享」，選 ThumbLinks。",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
