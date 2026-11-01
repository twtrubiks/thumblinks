package com.twtrubiks.thumblinks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.WebExtension
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 內建瀏覽器（GeckoView ＋ uBlock Origin），從清單點一筆或右上角的「瀏覽」打開，一頁就是一個分頁。
 * 分頁本身（[BrowserTab]）放在 [AppContainer]，[tabKey] 是這一頁在導覽 stack 裡的 key。
 * 下面的「加到清單」走一般的新增流程；已經在清單裡的網頁顯示「已在清單」，點了進編輯頁。
 */
@Composable
fun BrowserScreen(
    tabKey: String,
    initialUrl: String?,
    links: List<Link>?,
    onClose: () -> Unit,
    onAdd: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    // 行程被系統收掉再回來時，從最後看的那一頁重新開始
    var lastUrl by rememberSaveable { mutableStateOf(initialUrl) }
    val tab = remember(tabKey) { container.tab(tabKey) { lastUrl } }
    val ublock = container.ublock
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { ublock.start() }
    LaunchedEffect(tab.url) { if (tab.url.isNotEmpty()) lastUrl = tab.url }
    LaunchedEffect(tab.message) {
        tab.message?.let {
            tab.message = null
            snackbar.showSnackbar(it)
        }
    }
    // 網頁自己要打開別的 App（沒有經過使用者點擊）：問一聲
    LaunchedEffect(tab.externalRequest) {
        val uri = tab.externalRequest ?: return@LaunchedEffect
        tab.externalRequest = null
        val result = snackbar.showSnackbar("這個網頁要用其他 App 打開", actionLabel = "打開", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) tab.openExternally(uri)
    }

    // 看得到的那頁才算「正在用」：GeckoView 在背景會降低耗電，uBO 的 popup 也靠這個知道是哪一頁。
    // 網頁開了新視窗（疊上一頁）或關掉它時，換成那一頁
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = tab.session
    DisposableEffect(session, lifecycleOwner) {
        fun active(on: Boolean) {
            if (!session.isOpen) return
            session.setActive(on)
            ublock.setActive(session, on)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> active(true)
                Lifecycle.Event.ON_STOP -> active(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        active(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            active(false)
        }
    }

    FullscreenEffect(tab.fullscreen)

    BackHandler {
        when {
            tab.fullscreen -> tab.session.exitFullScreen()
            tab.canGoBack -> tab.goBack()
            else -> onClose()
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!tab.fullscreen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "回清單") }
                UrlBar(tab, autoFocus = initialUrl == null, modifier = Modifier.weight(1f))
                UblockButton(ublock, ublock.actionFor(tab))
                PageMenu(tab, ublock)
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (tab.loading) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = ::GeckoView,
                // 讀了 tab.session：換頁之後會再呼叫一次，換成那一頁的 session
                update = tab::attach,
                onRelease = tab::detach,
                modifier = Modifier.fillMaxSize(),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        if (!tab.fullscreen) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = tab::goBack, enabled = tab.canGoBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "上一頁")
                }
                IconButton(onClick = { tab.session.goForward() }, enabled = tab.canGoForward) {
                    Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = "下一頁")
                }
                if (tab.loading) {
                    IconButton(onClick = { tab.session.stop() }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "停止") }
                } else {
                    IconButton(onClick = { tab.session.reload() }, enabled = tab.url.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "重新整理")
                    }
                }
                Spacer(Modifier.weight(1f))
                // 網址照字串比對，跟新增時查重複一樣
                val existing = links?.firstOrNull { it.url == tab.url }
                when {
                    existing != null -> FilledTonalButton(onClick = { onEdit(existing.id) }, modifier = Modifier.testTag("in_list")) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(18.dp))
                        Text("已在清單", Modifier.padding(start = 6.dp))
                    }
                    BrowserInput.canAdd(tab.url) -> Button(onClick = { onAdd(tab.url) }, modifier = Modifier.testTag("add_to_list")) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(18.dp))
                        Text("加到清單", Modifier.padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }

    tab.prompt?.let { PromptDialog(it) }
    tab.contextMenu?.let { element ->
        ContextMenuDialog(
            element = element,
            onDismiss = { tab.contextMenu = null },
            onOpen = tab::load,
            onAdd = onAdd,
            onCopied = { tab.message = it },
        )
    }
    ublock.popup?.let { UblockPopup(it, onDismiss = ublock::closePopup) }
}

/** 網頁要全螢幕（影片）時把系統列也藏起來，滑一下會暫時出現。 */
@Composable
private fun FullscreenEffect(fullscreen: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** 網址列：沒在打字時顯示目前的網址；按「前往」時是網址就開、不是就用 DuckDuckGo 搜尋。 */
@Composable
private fun UrlBar(tab: BrowserTab, autoFocus: Boolean, modifier: Modifier) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue(tab.url)) }
    // 點網址列時整個選起來，貼上就是換掉（不然會貼進原本的網址中間）。點下去的那一下還會把游標移到點的位置，那一次不理
    var keepSelectAll by remember { mutableStateOf(false) }
    LaunchedEffect(tab.url, focused) { if (!focused) text = TextFieldValue(tab.url) }
    LaunchedEffect(Unit) { if (autoFocus) requester.requestFocus() }

    fun go(input: String) {
        BrowserInput.resolve(input)?.let(tab::load)
        focus.clearFocus()
    }

    BasicTextField(
        value = text,
        onValueChange = {
            if (it.text.hasNewline()) {
                // 貼上多行的文字（有些螢幕鍵盤的 Enter 也是送換行）：當作按了「前往」
                go(it.text.oneLine())
            } else if (!(keepSelectAll && it.text == text.text)) {
                text = it
            }
            keepSelectAll = false
        },
        modifier = modifier
            .onEnter { go(text.text) }
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused && !focused) {
                    text = text.copy(selection = TextRange(0, text.text.length))
                    keepSelectAll = text.text.isNotEmpty()
                }
                focused = it.isFocused
            }
            .testTag("url_bar"),
        // 打字時長網址換行顯示（貼上之後整串看得到，不用左右拖）；沒在打字時一行。
        // 一直都不是 singleLine：singleLine 中途切換的話 TextField 的捲動方向對不上，會閃退
        singleLine = false,
        maxLines = if (focused) 4 else 1,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { go(text.text) }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (text.text.isEmpty()) {
                        Text("搜尋或輸入網址", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
                if (focused && text.text.isNotEmpty()) {
                    IconButton(onClick = { text = TextFieldValue() }, modifier = Modifier.size(36.dp)) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "清除", Modifier.size(18.dp))
                    }
                }
            }
        },
    )
}

/** uBO 的按鈕：它自己的圖示（關掉過濾時會變灰）＋這一頁擋了幾個。按了打開 popup。 */
@Composable
private fun UblockButton(ublock: Ublock, action: WebExtension.Action?) {
    if (action == null) return
    var icon by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(action.icon) {
        val result = action.icon?.getBitmap(96)
        result?.accept({ icon = it }, { })
        onDispose { result?.cancel() }
    }
    Box(
        Modifier
            .size(48.dp)
            .clickable(onClickLabel = action.title ?: "uBlock Origin") { action.click() }
            .testTag("ublock_button"),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon ?: ublock.icon
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = action.title ?: "uBlock Origin",
                modifier = Modifier.size(26.dp).alpha(if (action.enabled == false) 0.4f else 1f),
            )
        } else {
            Text("uBO", style = MaterialTheme.typography.labelMedium)
        }
        val badge = action.badgeText
        if (!badge.isNullOrEmpty()) {
            Text(
                badge,
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp)
                    .testTag("ublock_badge"),
                color = MaterialTheme.colorScheme.onError,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PageMenu(tab: BrowserTab, ublock: Ublock) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "更多") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("用其他 App 開") },
                enabled = BrowserInput.canAdd(tab.url),
                onClick = {
                    open = false
                    try {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, tab.url.toUri()), null))
                    } catch (e: ActivityNotFoundException) {
                        tab.message = "手機上沒有其他可以打開網址的 App"
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("複製網址") },
                enabled = tab.url.isNotEmpty(),
                onClick = {
                    open = false
                    copyUrl(context, tab.url) { tab.message = it }
                },
            )
            // uBO 的版本與上次檢查更新的時間：看得到它有沒有自己更新（一天最多問一次 AMO）
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Column {
                        Text(ublock.version?.let { "uBlock Origin $it" } ?: "uBlock Origin 還沒裝好")
                        ublock.pendingVersion?.let {
                            Text("$it 已經下載好，下次打開 App 時換上", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            ublock.checkedAt.takeIf { it > 0 }?.let { "上次檢查更新：${CHECKED_AT_FORMAT.format(Instant.ofEpochMilli(it))}" }
                                ?: "還沒檢查過更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = { open = false },
            )
        }
    }
}

private val CHECKED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** 網頁長按連結或圖片時的選單。 */
@Composable
private fun ContextMenuDialog(
    element: org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: (String) -> Unit,
    onCopied: (String) -> Unit,
) {
    val context = LocalContext.current
    val link = element.linkUri
    val src = element.srcUri
    fun item(label: String, action: () -> Unit): @Composable () -> Unit = {
        ListItem(
            headlineContent = { Text(label) },
            modifier = Modifier.clickable {
                onDismiss()
                action()
            },
        )
    }
    val items = buildList {
        if (link != null) {
            add(item("開啟連結") { onOpen(link) })
            if (BrowserInput.canAdd(link)) add(item("加到清單") { onAdd(link) })
            add(item("複製連結網址") { copyUrl(context, link, onCopied) })
        }
        if (src != null && element.type == org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
            add(item("開啟圖片") { onOpen(src) })
            add(item("複製圖片網址") { copyUrl(context, src, onCopied) })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                element.linkText?.takeIf { it.isNotBlank() } ?: element.title ?: link ?: src.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = { Column { items.forEach { it() } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** alert、confirm、prompt、`<select>`、離開頁面與重新送出表單的確認。 */
@Composable
private fun PromptDialog(pending: PendingPrompt) {
    val title = pending.prompt.title?.takeIf { it.isNotBlank() }
    when (val p = pending.prompt) {
        is PromptDelegate.AlertPrompt -> AlertDialog(
            onDismissRequest = pending::dismiss,
            title = title?.let { { Text(it) } },
            text = { Text(p.message.orEmpty()) },
            confirmButton = { TextButton(onClick = pending::dismiss) { Text("確定") } },
        )
        is PromptDelegate.ButtonPrompt -> AlertDialog(
            onDismissRequest = { pending.complete(p.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) },
            title = title?.let { { Text(it) } },
            text = { Text(p.message.orEmpty()) },
            confirmButton = { TextButton(onClick = { pending.complete(p.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }) { Text("確定") } },
            dismissButton = { TextButton(onClick = { pending.complete(p.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }) { Text("取消") } },
        )
        is PromptDelegate.TextPrompt -> {
            var value by remember(p) { mutableStateOf(p.defaultValue.orEmpty()) }
            AlertDialog(
                onDismissRequest = pending::dismiss,
                title = title?.let { { Text(it) } },
                text = {
                    Column {
                        p.message?.let { Text(it, Modifier.padding(bottom = 8.dp)) }
                        OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { pending.complete(p.confirm(value)) }) { Text("確定") } },
                dismissButton = { TextButton(onClick = pending::dismiss) { Text("取消") } },
            )
        }
        is ChoicePrompt -> ChoiceDialog(pending, p)
        is PromptDelegate.BeforeUnloadPrompt -> AlertDialog(
            onDismissRequest = { pending.complete(p.confirm(AllowOrDeny.DENY)) },
            text = { Text("要離開這一頁嗎？輸入的資料可能不會保存。") },
            confirmButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.ALLOW)) }) { Text("離開") } },
            dismissButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.DENY)) }) { Text("留下") } },
        )
        is PromptDelegate.RepostConfirmPrompt -> AlertDialog(
            onDismissRequest = { pending.complete(p.confirm(AllowOrDeny.DENY)) },
            text = { Text("要再送出一次表單嗎？") },
            confirmButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.ALLOW)) }) { Text("再送一次") } },
            dismissButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.DENY)) }) { Text("取消") } },
        )
        else -> LaunchedEffect(p) { pending.dismiss() }
    }
}

/** `<select>`：單選點了就送出，多選勾完按確定。`<optgroup>` 的標題顯示成不能點的一行。 */
@Composable
private fun ChoiceDialog(pending: PendingPrompt, p: ChoicePrompt) {
    val multiple = p.type == ChoicePrompt.Type.MULTIPLE
    val rows = remember(p) { p.choices.flatMap { c -> if (c.items != null) listOf(c) + c.items!!.toList() else listOf(c) } }
    val selected = remember(p) { mutableStateListOf<ChoicePrompt.Choice>().apply { addAll(rows.filter { it.items == null && it.selected }) } }
    AlertDialog(
        onDismissRequest = pending::dismiss,
        title = p.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = {
            Column(Modifier.fillMaxHeight(0.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (choice in rows) {
                    when {
                        choice.separator -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        choice.items != null -> Text(choice.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        else -> Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !choice.disabled) {
                                    if (!multiple) {
                                        pending.complete(p.confirm(choice))
                                    } else if (choice in selected) {
                                        selected.remove(choice)
                                    } else {
                                        selected.add(choice)
                                    }
                                }
                                .padding(vertical = 10.dp)
                                .alpha(if (choice.disabled) 0.4f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multiple) Checkbox(checked = choice in selected, onCheckedChange = null, Modifier.padding(end = 8.dp))
                            Text(choice.label, color = if (!multiple && choice.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) {
                TextButton(onClick = { pending.complete(p.confirm(selected.toTypedArray())) }) { Text("確定") }
            } else {
                TextButton(onClick = pending::dismiss) { Text("取消") }
            }
        },
        dismissButton = if (multiple) ({ TextButton(onClick = pending::dismiss) { Text("取消") } }) else null,
    )
}

/**
 * uBO 的 popup（那顆大電源鈕：這個網站要不要過濾）。uBO 回傳的是一個 GeckoSession，放進 BottomSheet 裡的 GeckoView。
 * BottomSheet 的拖曳關掉：popup 裡可以捲，拖曳會跟它搶；點外面或按返回鍵關。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun UblockPopup(session: org.mozilla.geckoview.GeckoSession, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
    ) {
        // BottomSheet 是另一個視窗，App 根部開的 testTagsAsResourceId 管不到這裡；包著 GeckoView 的 AndroidView 上的 testTag 也讀不到
        Column(Modifier.semantics { testTagsAsResourceId = true }.testTag("ublock_popup")) {
            AndroidView(
                // 用預設的 SurfaceView：TextureView 在模擬器上整塊是黑的（網頁有畫，顯示不出來）。
                // BottomSheet 的拖曳關掉了，只有打開、關掉的動畫，SurfaceView 跟得上
                factory = ::GeckoView,
                update = {
                    if (it.session !== session) {
                        it.setSession(session)
                        session.setActive(true)
                    }
                },
                onRelease = { it.releaseSession() },
                modifier = Modifier.fillMaxWidth().height(560.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 8.dp)) { Text("關閉") }
        }
    }
}
