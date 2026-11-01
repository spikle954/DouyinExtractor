package io.github.douyinextractor.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.douyinextractor.core.download.DownloadLabels
import io.github.douyinextractor.ui.AdaptiveActions
import java.text.DateFormat
import java.util.Date

private fun SavedDownload.displayTitle() = title.ifBlank { fileName }
private fun SavedDownload.metadata(): String = listOfNotNull(
    when (identity?.platform) { "Bilibili" -> "哔哩哔哩 · P${identity?.page}"; "Douyin" -> "抖音"; "Kuaishou" -> "快手"; "Xiaohongshu" -> "小红书"; else -> null },
    author.takeIf { it.isNotBlank() }, bytes.takeIf { it > 0 }?.let(DownloadLabels::bytes),
).joinToString(" · ")
private fun SavedDownload.dateLabel() = savedAt.takeIf { it > 0 }?.let {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
}.orEmpty()

@Composable
fun SavedVideosPanel(onOpen: () -> Unit) {
    val runtime = DownloadRuntime.get(LocalContext.current)
    val history by runtime.history.collectAsStateWithLifecycle()
    val actions = rememberSavedVideoActions()
    var detail by remember { mutableStateOf<SavedDownload?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近保存", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpen) { Text("全部（${history.size}）") }
        }
        if (history.isEmpty()) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("还没有保存的视频", style = MaterialTheme.typography.bodyLarge)
                Text("完成下载后，可在这里播放、分享或查找文件。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column {
                history.take(2).forEachIndexed { index, item ->
                    SavedRecordRow(item, actions, compact = true, onDetails = { detail = item })
                    if (index == 0 && history.size > 1) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    detail?.let { SavedDetailsDialog(it, actions, onDismiss = { detail = null }) }
}

@Composable
fun SavedHistoryScreen() {
    val runtime = DownloadRuntime.get(LocalContext.current)
    val history by runtime.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<SavedDownload?>(null) }
    val actions = rememberSavedVideoActions()
    val matches = remember(history, query) { SavedDownloadHistory.search(history, query) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), singleLine = true,
            shape = MaterialTheme.shapes.medium, label = { Text("搜索下载记录") }, placeholder = { Text("标题、作者、编号或位置") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "清除搜索内容") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        Text(if (query.isEmpty()) "共 ${history.size} 条记录" else "找到 ${matches.size} 条 / 共 ${history.size} 条",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (matches.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.VideoLibrary, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (history.isEmpty()) "还没有下载记录" else "没有匹配的记录", style = MaterialTheme.typography.titleSmall)
                    Text(if (history.isEmpty()) "先粘贴链接，解析并保存一段视频。" else "试试标题、作者或视频编号。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(matches, key = { _, item -> item.uri }) { index, item ->
                val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 0.dp,
                    topEnd = if (index == 0) 20.dp else 0.dp, bottomStart = if (index == matches.lastIndex) 20.dp else 0.dp,
                    bottomEnd = if (index == matches.lastIndex) 20.dp else 0.dp)
                Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        SavedRecordRow(item, actions, compact = false, onDetails = { detail = item })
                        if (index < matches.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            item { Text("保留最近 ${SavedDownloadHistory.LIMIT} 条记录。更早的视频仍在原文件夹，移动或删除后的文件可能无法打开。",
                modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    detail?.let { SavedDetailsDialog(it, actions, onDismiss = { detail = null }) }
}

@Composable
private fun SavedRecordRow(item: SavedDownload, actions: SavedVideoActions, compact: Boolean, onDetails: () -> Unit) {
    var menu by remember(item.uri) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SavedVideoThumbnail(item)
        Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClickLabel = "播放${item.displayTitle()}", role = androidx.compose.ui.semantics.Role.Button, onClick = { actions.play(item.uri) }),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(item.metadata(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact) {
                val detailMetadata = listOf(item.quality, item.dateLabel()).filter { it.isNotBlank() }.joinToString(" · ")
                if (detailMetadata.isNotBlank()) Text(detailMetadata, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(SaveLocations.display(item.location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "${item.displayTitle()}的操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("播放视频") }, leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) }, onClick = { menu = false; actions.play(item.uri) })
                DropdownMenuItem(text = { Text("打开保存文件夹") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, onClick = { menu = false; actions.folder(item) })
                DropdownMenuItem(text = { Text("分享文件") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = { menu = false; actions.share(item.uri) })
                DropdownMenuItem(text = { Text("文件信息") }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { menu = false; onDetails() })
            }
        }
    }
}

@Composable
fun SavedDetailsDialog(item: SavedDownload, actions: SavedVideoActions, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("文件信息") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer { Text(item.fileName) }
            SelectionContainer { Text("已保存到：${SaveLocations.display(item.location)}") }
            Text(SaveLocations.findingHint(item.location), style = MaterialTheme.typography.bodySmall)
            Text(listOf(item.metadata(), item.dateLabel()).filter { it.isNotBlank() }.joinToString("\n"))
            if (item.quality.isNotBlank()) Text("所选资源：${item.quality}（页面信息）", style = MaterialTheme.typography.bodySmall)
            item.identity?.let { Text("视频编号：${it.videoId}" + if (it.platform == "Bilibili") " · P${it.page}" else "", style = MaterialTheme.typography.bodySmall) }
            SavedFileButtons(item, actions)
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
fun SavedFileButtons(item: SavedDownload, actions: SavedVideoActions) {
    Button(onClick = { actions.folder(item) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("打开保存文件夹")
    }
    AdaptiveActions(first = { modifier -> OutlinedButton(onClick = { actions.play(item.uri) }, modifier = modifier) {
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("播放")
    } }, second = { modifier -> OutlinedButton(onClick = { actions.share(item.uri) }, modifier = modifier) {
        Icon(Icons.Outlined.Share, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("分享")
    } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedSuccessSheet(item: SavedDownload, details: SavedVideoDetails?, onDismiss: () -> Unit) {
    val actions = rememberSavedVideoActions()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null, containerColor = MaterialTheme.colorScheme.surfaceContainer,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = .4f)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SavedVideoThumbnail(item, Modifier.size(56.dp, 72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("视频已保存", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(item.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(item.bytes.takeIf { it > 0 }?.let(DownloadLabels::bytes),
                        details?.let { "${it.width} × ${it.height} · ${it.durationMs / 1000} 秒" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭保存成功提示") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("已保存到", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(SaveLocations.display(item.location), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SavedFileButtons(item, actions) }
        }
    }
}
