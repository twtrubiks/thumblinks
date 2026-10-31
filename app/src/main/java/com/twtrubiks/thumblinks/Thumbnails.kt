package com.twtrubiks.thumblinks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** og:image 建議的比例，清單和預覽都用它裁切。 */
const val THUMB_RATIO = 1.91f

val ThumbShape = RoundedCornerShape(12.dp)

/** 一張縮圖：網路上的圖或上傳後存在 [thumbsDir] 的檔案。[onResult]：載得出來 true、載不出來 false。 */
@Composable
fun ThumbImage(thumbnail: Thumbnail, thumbsDir: File, modifier: Modifier = Modifier, onResult: ((Boolean) -> Unit)? = null) {
    val model: Any = when (thumbnail) {
        is Thumbnail.Remote -> thumbnail.url
        is Thumbnail.Uploaded -> File(thumbsDir, thumbnail.fileName)
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop,
        onSuccess = { onResult?.invoke(true) },
        onError = { onResult?.invoke(false) },
    )
}

/**
 * 這一頁正在選的縮圖。網路上的圖要 Coil 真的載得出來才算數（404、不是圖都當作沒有）。
 * [uploadedHere] 是在這一頁上傳、還沒存進清單的檔案：又換了別張或放棄這一頁時要刪掉。
 */
@Stable
class ThumbDraft(
    thumbnail: Thumbnail?,
    uploadedHere: String? = null,
    loadedUrl: String? = null,
    failedUrl: String? = null,
) {
    var thumbnail by mutableStateOf(thumbnail)
        private set
    var uploadedHere by mutableStateOf(uploadedHere)
        private set
    var loadedUrl by mutableStateOf(loadedUrl)
        private set
    var failedUrl by mutableStateOf(failedUrl)
        private set

    /** 可以存了：上傳的圖，或已經載得出來的網路圖。 */
    val ready: Boolean
        get() = when (val t = thumbnail) {
            null -> false
            is Thumbnail.Uploaded -> true
            is Thumbnail.Remote -> loadedUrl == t.url
        }

    val failed: Boolean get() = (thumbnail as? Thumbnail.Remote)?.url.let { it != null && it == failedUrl }

    fun set(new: Thumbnail?, thumbsDir: File) {
        val pending = uploadedHere
        if (pending != null && (new as? Thumbnail.Uploaded)?.fileName != pending) {
            File(thumbsDir, pending).delete()
            uploadedHere = null
        }
        thumbnail = new
    }

    fun setUploaded(fileName: String, thumbsDir: File) {
        set(Thumbnail.Uploaded(fileName), thumbsDir)
        uploadedHere = fileName
    }

    fun loaded(url: String, ok: Boolean) {
        if (ok) {
            loadedUrl = url
            if (failedUrl == url) failedUrl = null
        } else {
            failedUrl = url
            if (loadedUrl == url) loadedUrl = null
        }
    }

    /** 存進清單了：檔案歸清單管，之後不能再由這一頁刪。 */
    fun committed() {
        uploadedHere = null
    }

    /** 放棄這一頁：這一頁上傳的檔案刪掉。 */
    fun discard(thumbsDir: File) = set(null, thumbsDir)

    companion object {
        val Saver = listSaver<ThumbDraft, String?>(
            save = {
                listOf(
                    (it.thumbnail as? Thumbnail.Remote)?.url,
                    (it.thumbnail as? Thumbnail.Uploaded)?.fileName,
                    it.uploadedHere,
                    it.loadedUrl,
                    it.failedUrl,
                )
            },
            restore = {
                val thumbnail = it[0]?.let(Thumbnail::Remote) ?: it[1]?.let(Thumbnail::Uploaded)
                ThumbDraft(thumbnail, it[2], it[3], it[4])
            },
        )
    }
}

@Composable
fun rememberThumbDraft(initial: Thumbnail?): ThumbDraft = rememberSaveable(saver = ThumbDraft.Saver) { ThumbDraft(initial) }

/**
 * 縮圖的預覽，加上兩個補救（也拿來換掉抓到的圖）：貼圖片網址、從手機選一張。
 * [missingReason] 是沒有縮圖時要寫的原因（「這個網頁沒有提供縮圖」／「連不上這個網頁」）。
 */
@Composable
fun ThumbnailEditor(draft: ThumbDraft, thumbsDir: File, missingReason: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var urlFieldOpen by rememberSaveable { mutableStateOf(false) }
    var imageUrl by rememberSaveable { mutableStateOf("") }
    var imageUrlError by rememberSaveable { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var uploadFailed by rememberSaveable { mutableStateOf(false) }

    // Android 9、10 沒有照片挑選器時，會自動退回系統的檔案畫面
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        preparing = true
        uploadFailed = false
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { ThumbPrep.save(it, thumbsDir) }
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                }
            }
            preparing = false
            if (name == null) uploadFailed = true else draft.setUploaded(name, thumbsDir)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(THUMB_RATIO)
                .clip(ThumbShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                // release 冒煙測試用 uiautomator dump 等這個 tag，確認縮圖真的載出來了
                .testTag(if (draft.ready) "thumb_ready" else "thumb_preview"),
            contentAlignment = Alignment.Center,
        ) {
            val thumbnail = draft.thumbnail
            if (thumbnail != null) {
                ThumbImage(thumbnail, thumbsDir, Modifier.fillMaxSize()) { ok ->
                    (thumbnail as? Thumbnail.Remote)?.let { draft.loaded(it.url, ok) }
                }
            } else {
                Text("沒有縮圖", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preparing) CircularProgressIndicator(Modifier.size(32.dp))
        }

        val problem = when {
            uploadFailed -> "這張圖讀不出來，換一張試試"
            draft.thumbnail == null -> missingReason
            draft.failed -> "這張圖載不出來"
            else -> null
        }
        if (problem != null) {
            Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { urlFieldOpen = !urlFieldOpen }) { Text("貼圖片網址") }
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !preparing,
            ) { Text("從手機選圖") }
        }

        if (urlFieldOpen) {
            fun use() {
                val url = WebUrls.extract(imageUrl)
                imageUrlError = url == null
                if (url != null) {
                    imageUrl = url
                    uploadFailed = false
                    draft.set(Thumbnail.Remote(url), thumbsDir)
                    urlFieldOpen = false
                    focus.clearFocus()
                }
            }
            OutlinedTextField(
                value = imageUrl,
                onValueChange = {
                    imageUrl = it
                    imageUrlError = false
                },
                modifier = Modifier.fillMaxWidth().testTag("image_url_input"),
                label = { Text("圖片網址") },
                placeholder = { Text("https://…") },
                singleLine = true,
                isError = imageUrlError,
                supportingText = if (imageUrlError) ({ Text("找不到網址") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { use() }),
            )
            Button(onClick = ::use, enabled = imageUrl.isNotBlank()) { Text("用這張圖") }
        }
    }
}
