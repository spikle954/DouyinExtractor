package io.github.douyinextractor.download

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.douyinextractor.core.download.DownloadLabels
import io.github.douyinextractor.core.download.DownloadPhase
import io.github.douyinextractor.ui.GroupCard
import io.github.douyinextractor.ui.StatusMessage

@Composable
fun DownloadQueuePanel(onOpen: () -> Unit) {
    val runtime = DownloadRuntime.get(LocalContext.current)
    val current by runtime.state.collectAsStateWithLifecycle()
    val queued by runtime.queued.collectAsStateWithLifecycle()
    if (current.active || queued.isNotEmpty()) Surface(onClick = onOpen, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(if (current.active) "正在下载 · ${queued.size} 个等待" else "${queued.size} 个待办任务", style = MaterialTheme.typography.titleSmall)
                    Text(if (current.active) current.title.ifBlank { current.fileName } else "查看队列，继续下载或重新解析",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
            if (current.active) TransferProgress(current)
        }
    }
}

@Composable
private fun TransferProgress(record: DownloadRecord) {
    val percent = record.progress.percent
    val modifier = Modifier.fillMaxWidth().height(4.dp)
    if (percent == null || record.phase != DownloadPhase.DOWNLOADING)
        LinearProgressIndicator(modifier)
    else LinearProgressIndicator(progress = { percent / 100f }, modifier = modifier)
}

@Composable
fun DownloadQueueScreen(onReparse: (String) -> Unit, onHistory: () -> Unit) {
    val runtime = DownloadRuntime.get(LocalContext.current)
    val current by runtime.state.collectAsStateWithLifecycle()
    val queued by runtime.queued.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<DownloadRecord?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (current.active) item {
            GroupCard {
                Text(when (current.phase) {
                    DownloadPhase.PREPARING -> "准备下载"
                    DownloadPhase.VERIFYING -> "正在检查视频文件"
                    DownloadPhase.SAVING -> "正在保存到手机"
                    else -> "正在下载"
                }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(current.title.ifBlank { current.fileName }, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${current.quality} · ${SaveLocations.display(current.location)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TransferProgress(current)
                Text("${current.progress.percent?.let { "$it% · " }.orEmpty()}${DownloadLabels.bytes(current.progress.bytes)} / ${current.progress.total?.let(DownloadLabels::bytes) ?: "总大小未知"}",
                    style = MaterialTheme.typography.bodyLarge)
                if (current.phase == DownloadPhase.DOWNLOADING) Text(
                    "${DownloadLabels.bytes(current.progress.bytesPerSecond)}/s · ${current.progress.remainingSeconds?.let { "约 ${it / 60} 分 ${it % 60} 秒" } ?: "剩余时间估算中"}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { runtime.cancel(current.token) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消当前下载") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("待办（${queued.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (queued.isNotEmpty()) TextButton(onClick = { runtime.clearWaiting() }) { Text("清空待办") }
            }
        }
        if (queued.isEmpty()) item {
            GroupCard {
                Icon(Icons.AutoMirrored.Outlined.PlaylistAddCheck, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (current.active) "没有等待任务" else "队列已空", style = MaterialTheme.typography.titleSmall)
                Text("解析视频后可加入队列，按添加顺序逐个下载。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(queued, key = { _, item -> item.record.token }) { index, item ->
            Surface(onClick = { detail = item.record }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${item.record.title.ifBlank { item.record.fileName }}", style = MaterialTheme.typography.titleSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${item.record.quality} · ${SaveLocations.display(item.record.location)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { runtime.removeQueued(item.record.token) }) {
                            Icon(Icons.Outlined.Close, "移出队列：${item.record.title.ifBlank { item.record.fileName }}")
                        }
                    }
                    if (item.needsReparse) StatusMessage(item.record.message.ifBlank { "需要重新解析，再加入队列" }, error = true)
                    else Text("等待下载", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.needsReparse) item.identity.reparseUrl?.let { url ->
                        TextButton(onClick = { onReparse(url) }) { Text("重新解析") }
                    }
                }
            }
        }
        if (!current.active && queued.any { !it.needsReparse }) item {
            Button(onClick = { runtime.resumeQueue() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("继续下载队列") }
        }
        if (!current.active && current.phase != DownloadPhase.IDLE) item {
            GroupCard {
                StatusMessage(current.message, error = current.phase in setOf(DownloadPhase.FAILED, DownloadPhase.INTERRUPTED))
                if (current.phase == DownloadPhase.COMPLETE) TextButton(onClick = onHistory) { Text("查看已保存的视频") }
                else if (queued.none { it.record.token == current.token }) current.reparseUrl?.let { url ->
                    TextButton(onClick = { onReparse(url) }) { Text("重新解析上一个任务") }
                }
            }
        }
        item { Text("支持后台下载，通知可查看进度。应用重启后的待办需重新解析；移出队列不会删除已保存文件。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    detail?.let { record -> AlertDialog(onDismissRequest = { detail = null }, title = { Text("任务信息") },
        text = { androidx.compose.foundation.text.selection.SelectionContainer {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(record.fileName)
                Text("保存位置：${SaveLocations.display(record.location)}")
                Text("资源：${record.quality.ifBlank { "未提供" }}")
                Text("视频编号：${record.videoId}" + if (record.platform == "Bilibili") " · P${record.page}" else "")
            }
        } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }) }
}
