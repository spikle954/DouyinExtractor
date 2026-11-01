package io.github.douyinextractor.download

import io.github.douyinextractor.core.download.DownloadPhase

/** Public folder names and local file records; never includes a remote media URL. */
object SaveLocations {
    const val DEFAULT_PATH = "Movies/拾影"
    const val LEGACY_PATH = "Download/DouyinExtractor"
    const val DEFAULT_LABEL = "手机存储 / Movies / 拾影"

    fun documentLabel(id: String): String? {
        val volume = id.substringBefore(':', "")
        if (volume.isBlank() || ':' !in id) return null
        val path = id.substringAfter(':').trim('/').split('/').filter(String::isNotBlank).joinToString(" / ")
        val root = if (volume == "primary") "手机存储" else "存储卷 $volume"
        return if (path.isEmpty()) root else "$root / $path"
    }

    fun display(stored: String): String = when {
        stored == DEFAULT_PATH -> DEFAULT_LABEL
        stored == LEGACY_PATH -> documentLabel("primary:$LEGACY_PATH")!!
        stored.startsWith("primary:") || stored.substringBefore(':').matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")) ->
            documentLabel(stored) ?: "所选文件夹"
        stored.isBlank() -> "所选文件夹"
        else -> stored
    }

    fun documentId(stored: String): String? = when {
        stored == DEFAULT_PATH || stored == DEFAULT_LABEL -> "primary:$DEFAULT_PATH"
        stored == LEGACY_PATH || stored == display(LEGACY_PATH) -> "primary:$LEGACY_PATH"
        stored.startsWith("primary:") || stored.substringBefore(':').matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")) -> stored
        else -> null
    }

    fun findingHint(location: String): String = when (documentId(location)) {
        "primary:$DEFAULT_PATH" -> "文件管理 → 手机存储 → Movies → 拾影；也可在相册中查找「拾影」相册，首次显示可能稍有延迟。"
        "primary:$LEGACY_PATH" -> "这是旧版保存的位置：文件管理 → 手机存储 → Download → DouyinExtractor。"
        else -> "文件保存在你选择的文件夹。点击「打开保存文件夹」查看，或在文件管理器中按文件名搜索。"
    }
}

data class SavedDownload(
    val uri: String,
    val fileName: String,
    val location: String,
    val folderUri: String? = null,
    val bytes: Long = 0,
    val savedAt: Long = 0,
    val platform: String = "", val videoId: String = "", val page: Int = 1, val quality: String = "",
    val title: String = "", val author: String = "",
) {
    val identity: DownloadIdentity? get() = if (platform.isNotBlank() && videoId.isNotBlank()) DownloadIdentity(platform, videoId, page)
        else DownloadIdentity.fromFileName(fileName)
}

fun DownloadRecord.savedDownload(): SavedDownload? =
    savedUri?.takeIf { phase == DownloadPhase.COMPLETE && it.startsWith("content://") }?.let {
        SavedDownload(it, fileName, location, folderUri, progress.bytes, savedAt, platform, videoId, page, quality, title, author)
    }

/** Bounded, newest-first history. Removing an old record never removes the actual video. */
object SavedDownloadHistory {
    const val LIMIT = 50

    fun add(previous: List<SavedDownload>, item: SavedDownload): List<SavedDownload> =
        (listOf(item) + previous.filterNot { it.uri == item.uri }).take(LIMIT)

    fun encode(items: List<SavedDownload>): Map<String, String> = buildMap {
        put("count", items.take(LIMIT).size.toString())
        items.take(LIMIT).forEachIndexed { index, item ->
            put("$index.uri", item.uri)
            put("$index.name", item.fileName)
            put("$index.location", item.location)
            item.folderUri?.let { put("$index.folder", it) }
            put("$index.bytes", item.bytes.toString())
            put("$index.time", item.savedAt.toString())
            put("$index.platform", item.platform)
            put("$index.id", item.videoId)
            put("$index.page", item.page.toString())
            put("$index.quality", item.quality)
            put("$index.title", item.title)
            put("$index.author", item.author)
        }
    }

    fun decode(values: Map<String, String>): List<SavedDownload> =
        (0 until (values["count"]?.toIntOrNull() ?: 0).coerceIn(0, LIMIT)).mapNotNull { index ->
            val uri = values["$index.uri"]?.takeIf { it.startsWith("content://") && it.length <= 4096 } ?: return@mapNotNull null
            val name = values["$index.name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SavedDownload(uri, name, values["$index.location"].orEmpty(),
                values["$index.folder"]?.takeIf { it.startsWith("content://") },
                values["$index.bytes"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                values["$index.time"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                values["$index.platform"].orEmpty(), values["$index.id"].orEmpty(),
                values["$index.page"]?.toIntOrNull()?.takeIf { it in 1..10000 } ?: 1, values["$index.quality"].orEmpty(),
                values["$index.title"].orEmpty().take(2000), values["$index.author"].orEmpty().take(200))
        }.distinctBy { it.uri }

    fun search(items: List<SavedDownload>, query: String): List<SavedDownload> {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.isEmpty()) return items
        return items.filter { item ->
            val identity = item.identity
            val text = listOf(item.fileName, item.title, item.author, SaveLocations.display(item.location), item.quality,
                identity?.videoId.orEmpty(), when (identity?.platform) {
                    "Bilibili" -> "哔哩哔哩 Bilibili B站 P${identity.page}"
                    "Douyin" -> "抖音 Douyin"
                    "Kuaishou" -> "快手 Kuaishou"
                    "Xiaohongshu" -> "小红书 Xiaohongshu"
                    else -> ""
                }).joinToString(" ")
            words.all { text.contains(it, ignoreCase = true) }
        }
    }
}
