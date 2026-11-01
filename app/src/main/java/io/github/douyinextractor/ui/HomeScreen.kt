package io.github.douyinextractor.ui

import android.content.ClipboardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.douyinextractor.BuildConfig
import io.github.douyinextractor.browser.BrowserFallbackDialog
import io.github.douyinextractor.core.model.MediaFormatSelector
import io.github.douyinextractor.core.model.MediaFormat
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.extractor.social.SocialUrls
import io.github.douyinextractor.download.*
import kotlinx.coroutines.launch
import java.util.Locale

private const val HOME = "home"
private const val RESULT = "result"
private const val QUEUE = "queue"
private const val HISTORY = "history"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(model: MainViewModel, onAutoBrowserChanged: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val autoBrowserEnabled by model.autoBrowserEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val runtime = remember { DownloadRuntime.get(context) }
    val download by runtime.state.collectAsStateWithLifecycle()
    val history by runtime.history.collectAsStateWithLifecycle()
    val queued by runtime.queued.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val largeText = LocalDensity.current.fontScale > 1.3f
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var route by rememberSaveable { mutableStateOf(HOME) }
    var menu by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    var pages by remember(state.media?.id) { mutableStateOf(false) }
    var startedHere by remember { mutableStateOf<DownloadIdentity?>(null) }
    var saved by remember { mutableStateOf<SavedDownload?>(null) }
    var savedDetails by remember { mutableStateOf<SavedVideoDetails?>(null) }
    val tracker = remember { SavedFeedbackTracker() }
    val reportBody = state.diagnostics.let { if (it.startsWith("拾影 ")) it.substringAfter('\n', "") else it }
    val report = "拾影 ${BuildConfig.VERSION_NAME} · 提取与下载诊断\n$reportBody\n独立下载任务状态（可能来自此前任务，与本次解析结果分开）：\n下载状态：${download.phase}\n下载作品：${download.videoId.ifBlank { "无" }}\n传输字节：${download.progress.bytes}\n下载说明：${download.message}\n媒体地址、保存 URI 和凭据不写入诊断。"

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { startedHere = null; saved = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.input, state.busy) {
        if (state.busy) { route = HOME; startedHere = null; saved = null }
    }
    LaunchedEffect(state.media) {
        if (state.media != null) {
            focus.clearFocus(); keyboard?.hide()
            if (route == HOME || route == RESULT) route = RESULT
            else if (snackbars.showSnackbar("视频解析完成", "查看", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) route = RESULT
        }
    }
    LaunchedEffect(history, download.ready) {
        val additions = tracker.observe(history, download.ready)
        if (additions.isNotEmpty()) {
            val visible = state.media?.let(DownloadIdentity::from)
            val candidate = additions.firstOrNull { shouldShowSavedSheet(it, startedHere, visible,
                lifecycle.currentState == Lifecycle.State.RESUMED, route == RESULT, state.busy) }
            startedHere = null
            if (candidate != null) {
                saved = candidate
                savedDetails = download.details.takeIf { download.savedUri == candidate.uri }
            } else if (lifecycle.currentState == Lifecycle.State.RESUMED) {
                val message = if (additions.size > 1) "已保存 ${additions.size} 个视频" else "视频已保存"
                scope.launch { if (snackbars.showSnackbar(message, "查看记录", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) route = HISTORY }
            }
        }
    }
    BackHandler(enabled = route != HOME && !WindowInsets.isImeVisible && saved == null) {
        route = HOME; startedHere = null
    }
    fun go(next: String) { focus.clearFocus(); keyboard?.hide(); route = next; if (next != RESULT) startedHere = null }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(title = { Text(when (route) { RESULT -> "解析结果"; QUEUE -> "下载队列"; HISTORY -> "下载记录"; else -> "拾影" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                navigationIcon = { if (route != HOME) IconButton(onClick = { go(HOME) }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回链接输入") } },
                actions = {
                    if (!largeText && route != QUEUE) IconButton(onClick = { go(QUEUE) }) {
                        BadgedBox(badge = { val count = queued.size + if (download.active) 1 else 0; if (count > 0) Badge { Text(count.toString()) } }) {
                            Icon(Icons.Outlined.Download, "下载队列，${queued.size + if (download.active) 1 else 0} 个任务")
                        }
                    }
                    if (!largeText && route != HISTORY) IconButton(onClick = { go(HISTORY) }) { Icon(Icons.Outlined.History, "查看下载记录") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "更多选项") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (largeText) {
                                DropdownMenuItem(text = { Text("下载队列（${queued.size + if (download.active) 1 else 0}）") }, onClick = { menu = false; go(QUEUE) }, leadingIcon = { Icon(Icons.Outlined.Download, null) })
                                DropdownMenuItem(text = { Text("下载记录") }, onClick = { menu = false; go(HISTORY) }, leadingIcon = { Icon(Icons.Outlined.History, null) })
                            }
                            DropdownMenuItem(text = { Text("隐私说明") }, onClick = { menu = false; privacy = true }, leadingIcon = { Icon(Icons.Outlined.PrivacyTip, null) })
                            DropdownMenuItem(text = { Text("查看诊断信息") }, onClick = { menu = false; diagnostics = true }, leadingIcon = { Icon(Icons.Outlined.Info, null) })
                            DropdownMenuItem(text = { Text("拾影 ${BuildConfig.VERSION_NAME}") }, enabled = false, onClick = {})
                        }
                    }
                })
        }, snackbarHost = { SnackbarHost(snackbars) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            when (route) {
                QUEUE -> DownloadQueueScreen(onReparse = { url ->
                    go(HOME)
                    if (SocialUrls.platform(url) == SocialUrls.XIAOHONGSHU) {
                        model.editInput("")
                        Toast.makeText(context, "请从小红书重新复制这条视频的完整分享链接，再解析加入队列", Toast.LENGTH_LONG).show()
                    } else model.receiveShare(url)
                }, onHistory = { go(HISTORY) })
                HISTORY -> SavedHistoryScreen()
                RESULT -> state.media?.let { media ->
                    DownloadPanel(media, state.selectedFormat, onStarted = { startedHere = it }) { folder, downloadAction ->
                        Column(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text("解析完成", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                } }
                                item { MediaSummary(media, onPages = { pages = true }) }
                                item { ResourceChoices(media, state.selectedFormat, model::selectFormat) }
                                item { folder() }
                                item { Text("支持后台排队，可继续解析其他视频。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                item { TextButton(onClick = { go(HOME) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("解析其他视频") } }
                                item { DownloadQueuePanel(onOpen = { go(QUEUE) }) }
                                item { LinkInformation(state) }
                            }
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                Column {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { downloadAction() }
                                }
                            }
                        }
                    }
                } ?: InputScreen(state, model, autoBrowserEnabled, onAutoBrowserChanged, { go(QUEUE) }, { go(HISTORY) }, { go(RESULT) })
                else -> InputScreen(state, model, autoBrowserEnabled, onAutoBrowserChanged, { go(QUEUE) }, { go(HISTORY) }, { go(RESULT) })
            }
        }
    }
    saved?.let { SavedSuccessSheet(it, savedDetails, onDismiss = { saved = null }) }
    if (pages) state.media?.takeIf { it.platform == "Bilibili" }?.let { media ->
        AlertDialog(onDismissRequest = { pages = false }, title = { Text("选择要下载的分 P") },
            text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(media.pages, key = { it.number }) { page ->
                    Row(Modifier.fillMaxWidth().selectable(selected = page.number == media.selectedPage, role = Role.RadioButton,
                        onClick = { pages = false; model.selectPage(page.number) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = page.number == media.selectedPage, onClick = null)
                        Text("P${page.number} · ${page.title}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } }, confirmButton = { TextButton(onClick = { pages = false }) { Text("关闭") } })
    }
    if (diagnostics) AlertDialog(onDismissRequest = { diagnostics = false }, title = { Text("视频提取诊断") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("诊断包含分享文案，发送前请检查；链接跟踪参数和凭据已隐藏。", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = { copyText(context, "拾影诊断", report) }) { Text("复制诊断") } },
        dismissButton = { TextButton(onClick = { diagnostics = false }) { Text("关闭") } })
    state.browserRequest?.let { browser -> key(browser.sessionId) {
        BrowserFallbackDialog(browser, model::receiveBrowserSnapshot, model::finishBrowser, model::receiveBrowserNetwork, model::receiveBrowserPage)
    } }
    if (privacy) PrivacyDialog(onDismiss = { privacy = false })
}

@Composable
private fun InputScreen(state: HomeState, model: MainViewModel, autoBrowser: Boolean, onAutoBrowserChanged: (Boolean) -> Unit,
    onQueue: () -> Unit, onHistory: () -> Unit, onResult: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var options by rememberSaveable { mutableStateOf(false) }
    fun parse() { focus.clearFocus(); keyboard?.hide(); if (state.busy) model.cancel() else model.parse() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            GroupCard {
                Text("粘贴视频链接", style = MaterialTheme.typography.titleMedium)
            Text("抖音、哔哩哔哩、快手、小红书\n含快手极速版，完整分享文案也可以。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = state.input, onValueChange = model::editInput,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 136.dp), minLines = 3, maxLines = 6,
                    placeholder = { Text("在这里粘贴链接或分享内容") }, shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { parse() }),
                    trailingIcon = { if (state.input.isNotEmpty()) IconButton(onClick = { model.editInput("") }) { Icon(Icons.Outlined.Close, "清除链接输入") } })
                AdaptiveActions(first = { modifier -> OutlinedButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                    model.editInput(text)
                }, modifier = modifier) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("粘贴") } },
                    second = { modifier -> Button(onClick = { parse() }, modifier = modifier.heightIn(min = 52.dp), enabled = state.busy || state.input.isNotBlank()) {
                        Text(if (state.busy) "取消解析" else "解析视频")
                    } })
                TextButton(onClick = { options = !options }, contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("解析选项"); Spacer(Modifier.width(8.dp)); Icon(if (options) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (options) Row(Modifier.fillMaxWidth().toggleable(value = autoBrowser, role = Role.Switch, onValueChange = onAutoBrowserChanged),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("需要时自动打开网页", style = MaterialTheme.typography.bodyMedium)
                        Text("解析或接收分享后，必要时打开抖音或快手公开网页；加载或播放会产生流量。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoBrowser, onCheckedChange = null)
                }
            }
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.message?.let { message -> item { StatusMessage(message, error = state.isError) } }
        if (state.media != null && !state.busy) item {
            // The result remains available after returning to the input screen.
            TextButton(onClick = onResult, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看上一次解析结果") }
        }
        if (state.browserAvailable && !state.busy && state.browserRequest == null) item {
            GroupCard {
                Text("从当前作品网页继续提取", style = MaterialTheme.typography.titleSmall)
                Text("普通请求未提供视频资源，可打开官方网页继续检查。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = model::startBrowser, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("用网页继续提取") }
            }
        }
        item { DownloadQueuePanel(onOpen = onQueue) }
        item { SavedVideosPanel(onOpen = onHistory) }
        if (state.resolved != null) item { LinkInformation(state) }
        item { Text("也可在系统分享菜单中选择「拾影」\n本机解析 · 无广告 · 无统计 SDK", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable
private fun MediaSummary(media: MediaInfo, onPages: () -> Unit) {
    var fullTitle by remember(media.id) { mutableStateOf(false) }
    val scale = LocalDensity.current.fontScale
    GroupCard {
        @Composable fun Info(modifier: Modifier) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(media.title ?: "页面未提供标题", style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(media.author, media.durationMs?.let(::durationLabel)).joinToString(" · ").ifBlank { "作者与时长未提供" },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when (media.platform) { "Bilibili" -> "哔哩哔哩 · P${media.selectedPage}"; "Kuaishou" -> "快手 · 公开视频"; "Xiaohongshu" -> "小红书 · 视频笔记"; else -> "抖音 · 公开视频" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (scale > 1.6f) { VideoCover(media.coverUrl); Info(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            VideoCover(media.coverUrl); Info(Modifier.weight(1f))
        }
        if (media.platform == "Bilibili" && media.pages.size > 1) OutlinedButton(onClick = onPages, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("选择分 P · 当前 P${media.selectedPage} / ${media.pages.size}")
        }
        TextButton(onClick = { fullTitle = true }) { Text("完整作品信息") }
    }
    if (fullTitle) AlertDialog(onDismissRequest = { fullTitle = false }, title = { Text("作品信息") },
        text = { SelectionContainer { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(media.title ?: "页面未提供标题")
            Text("作者：${media.author ?: "未提供"}")
            Text("时长：${media.durationMs?.let(::durationLabel) ?: "未提供"}")
            Text("编号：${media.id}" + if (media.platform == "Bilibili") " · P${media.selectedPage}" else "")
        } } }, confirmButton = { TextButton(onClick = { fullTitle = false }) { Text("关闭") } })
}

@Composable
private fun ResourceChoices(media: MediaInfo, selected: Int, onSelect: (Int) -> Unit) {
    val recommended = MediaFormatSelector.recommended(media.formats)
    val highest = MediaFormatSelector.highestQuality(media.formats)
    GroupCard {
        Text("选择视频资源", style = MaterialTheme.typography.titleMedium)
        media.formats.forEachIndexed { index, format ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = selected == index, role = Role.RadioButton, onClick = { onSelect(index) }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = selected == index, onClick = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (media.platform == "Bilibili") "${format.qualityLabel ?: "MP4"} · 线路 ${index + 1}" else "资源 ${index + 1}", style = MaterialTheme.typography.titleSmall)
                    val badges = listOfNotNull(if (format == recommended) "推荐" else null, if (format == highest) "最高质量" else null).joinToString(" · ")
                    if (badges.isNotBlank()) Text(badges, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(formatLabel(format), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(if (media.platform == "Bilibili") "保存当前分 P，线路为同一画质的备选地址。暂不支持登录、会员及音视频分离资源。"
            else if (media.platform == "Douyin") "优先无水印候选源，可用性需下载验证。作者自带文字或标志会保留，画质以实际文件为准。"
            else "保存平台公开页面提供的视频资源。画质与水印以实际文件为准，不处理作者自带文字或标志。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkInformation(state: HomeState) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    state.resolved?.let { link -> Column {
        TextButton(onClick = { expanded = !expanded }) { Text("链接信息"); Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }
        if (expanded) GroupCard {
            Text("视频编号：${link.awemeId}", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(link.canonicalUrl, style = MaterialTheme.typography.bodySmall) }
            if (state.media == null) Text("编号已识别，尚未取得视频资源。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { copyText(context, "视频链接", link.canonicalUrl) }) { Text("复制标准链接") }
        }
    } }
}

@Composable
private fun PrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("隐私说明") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text("本应用不收集、不上传、不出售任何个人信息。视频解析在设备本地进行。\n\n没有广告、统计或开发者服务器。剪贴板仅在你点击粘贴时读取。哔哩哔哩、快手和小红书直接访问平台公开页面或接口，不导入登录 Cookie。小红书分享访问参数仅用于本次提取，不写入队列、记录或诊断。\n\n解析或主动分享后，必要时会打开当前作品的抖音或快手网页，产生流量。可在首页解析选项关闭；设置只保存在本机。\n\n网页执行平台自己的脚本，遵循平台的数据处理规则；过滤已知统计地址，但无法保证拦截全部网络行为。不导入登录信息，正常关闭时清理本应用的网页 Cookie 和缓存。\n\n下载直接连接平台媒体服务器，不携带登录 Cookie。保存目录、最近一次任务、最近 50 条保存记录、最多 20 个排队任务的编号、分 P、画质、限长标题与作者，以及临时文件只保存在本机。媒体直链不写入队列，进程退出后需重新解析，不自动恢复下载。\n\n搜索、重复检查和缩略图只处理本机记录及对应文件，不扫描整个存储、不上传搜索词。缩略图仅短暂保存在内存中。可播放已有视频或再次下载；移除待办不删除文件。后台下载使用前台服务、通知和临时唤醒锁，不新增媒体读取或整个存储权限。")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

private fun durationLabel(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
private fun formatLabel(format: MediaFormat): String = listOf(
    if (format.width != null && format.height != null) "${format.width} × ${format.height}" else "分辨率未提供",
    format.codec?.takeIf { it.isNotBlank() } ?: "编码未提供",
    format.contentLength?.let { String.format(Locale.ROOT, "%.1f MB", it / 1_000_000.0) } ?: "大小未提供",
    when { format.watermarked == true -> "有水印"; format.watermarked == false -> "页面标记无水印"; format.watermarkFreeCandidate -> "无水印候选源"; else -> "水印未知" },
).joinToString(" · ")
private fun copyText(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

