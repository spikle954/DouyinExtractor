package io.github.douyinextractor.download

import io.github.douyinextractor.core.download.DownloadPhase
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.extractor.social.SocialUrls

data class DownloadIdentity(val platform: String, val videoId: String, val page: Int = 1) {
    val reparseUrl: String? get() = DownloadRecord(videoId = videoId, platform = platform, page = page).reparseUrl
    companion object {
        fun from(media: MediaInfo) = DownloadIdentity(media.platform, media.id, if (media.platform == "Bilibili") media.selectedPage else 1)
        fun fromFileName(name: String): DownloadIdentity? {
            Regex("_(KS_[A-Za-z0-9]{8,32}|XHS_[a-f0-9]{24})(?: \\([1-9][0-9]{0,5}\\))?\\.mp4$").find(name)?.groupValues?.get(1)?.let {
                return if (it.startsWith("KS_")) DownloadIdentity(SocialUrls.KUAISHOU, it.removePrefix("KS_")) else DownloadIdentity(SocialUrls.XIAOHONGSHU, it.removePrefix("XHS_"))
            }
            Regex("_(BV[A-Za-z0-9]{10}|av[1-9][0-9]{0,19})_P([1-9][0-9]{0,4})(?: \\([1-9][0-9]{0,5}\\))?\\.mp4$").find(name)?.let {
                val part = it.groupValues[2].toIntOrNull()?.takeIf { number -> number in 1..10000 } ?: return null
                return DownloadIdentity("Bilibili", it.groupValues[1], part)
            }
            return Regex("_([1-9][0-9]{5,19})(?: \\([1-9][0-9]{0,5}\\))?\\.mp4$").find(name)?.let { DownloadIdentity("Douyin", it.groupValues[1]) }
        }
    }
}

data class QueuedDownload(val record: DownloadRecord, val request: DownloadRequest? = null) {
    val identity get() = DownloadIdentity(record.platform, record.videoId, record.page)
    val needsReparse get() = request == null
}

enum class QueueAddResult { ADDED, ALREADY_QUEUED, FULL }

/** One claimed item and a bounded FIFO. Only live requests hold media URLs. */
class DownloadQueue {
    private var current: QueuedDownload? = null
    private val waiting = mutableListOf<QueuedDownload>()

    @Synchronized fun active(): QueuedDownload? = current
    @Synchronized fun pending(): List<QueuedDownload> = waiting.toList()
    @Synchronized fun snapshot(): List<QueuedDownload> = listOfNotNull(current) + waiting

    @Synchronized fun add(item: QueuedDownload): QueueAddResult {
        if (current?.identity == item.identity) return QueueAddResult.ALREADY_QUEUED
        val existing = waiting.indexOfFirst { it.identity == item.identity }
        if (existing >= 0) {
            if (!waiting[existing].needsReparse) return QueueAddResult.ALREADY_QUEUED
            waiting[existing] = item
            return QueueAddResult.ADDED
        }
        if (snapshot().size >= LIMIT) return QueueAddResult.FULL
        waiting += item
        return QueueAddResult.ADDED
    }

    @Synchronized fun claim(): QueuedDownload? {
        if (current != null) return current
        val index = waiting.indexOfFirst { !it.needsReparse }
        if (index < 0) return null
        return waiting.removeAt(index).also { current = it }
    }

    @Synchronized fun finish(token: String, failed: DownloadRecord? = null): Boolean {
        val item = current?.takeIf { it.record.token == token } ?: return false
        current = null
        if (failed != null) waiting += item.copy(record = failed.copy(savedUri = null), request = null)
        return true
    }

    @Synchronized fun remove(token: String): Boolean = waiting.removeAll { it.record.token == token }
    @Synchronized fun clearWaiting() { waiting.clear() }

    @Synchronized fun restore(items: List<QueuedDownload>, completedToken: String? = null) {
        current = null
        waiting.clear()
        items.filterNot { it.record.token == completedToken }.distinctBy { it.identity }.take(LIMIT).forEach {
            waiting += it.copy(request = null, record = it.record.copy(phase = DownloadPhase.INTERRUPTED, savedUri = null,
                message = "应用重启后需要重新解析，再加入队列"))
        }
    }

    companion object { const val LIMIT = 20 }
}

/** Safe queue recovery metadata only. Never writes request URLs or headers. */
object DownloadQueueCodec {
    fun encode(items: List<QueuedDownload>): Map<String, String> = buildMap {
        put("count", items.take(DownloadQueue.LIMIT).size.toString())
        items.take(DownloadQueue.LIMIT).forEachIndexed { index, item ->
            val record = item.record
            put("$index.token", record.token)
            put("$index.id", record.videoId)
            put("$index.platform", record.platform)
            put("$index.page", record.page.toString())
            put("$index.name", record.fileName)
            put("$index.location", record.location)
            record.folderUri?.let { put("$index.folder", it) }
            put("$index.quality", record.quality)
            put("$index.title", record.title)
            put("$index.author", record.author)
        }
    }

    fun decode(values: Map<String, String>): List<QueuedDownload> =
        (0 until (values["count"]?.toIntOrNull() ?: 0).coerceIn(0, DownloadQueue.LIMIT)).mapNotNull { index ->
            val token = values["$index.token"]?.takeIf { it.matches(Regex("[a-f0-9-]{1,64}")) } ?: return@mapNotNull null
            val id = values["$index.id"] ?: return@mapNotNull null
            val platform = values["$index.platform"]?.takeIf { it in setOf("Douyin", "Bilibili", SocialUrls.KUAISHOU, SocialUrls.XIAOHONGSHU) } ?: return@mapNotNull null
            val page = values["$index.page"]?.toIntOrNull()?.takeIf { it in 1..10000 } ?: return@mapNotNull null
            if (platform == "Douyin" && !id.matches(Regex("[1-9][0-9]{5,19}"))) return@mapNotNull null
            if (platform == "Bilibili" && !id.matches(Regex("BV[A-Za-z0-9]{10}|av[1-9][0-9]{0,19}"))) return@mapNotNull null
            if (platform in setOf(SocialUrls.KUAISHOU, SocialUrls.XIAOHONGSHU) && (!SocialUrls.validId(platform, id) || page != 1)) return@mapNotNull null
            QueuedDownload(DownloadRecord(token = token, videoId = id, platform = platform, page = page,
                fileName = values["$index.name"].orEmpty(), location = values["$index.location"].orEmpty(),
                folderUri = values["$index.folder"]?.takeIf { it.startsWith("content://") }, quality = values["$index.quality"].orEmpty(),
                title = values["$index.title"].orEmpty().take(2000), author = values["$index.author"].orEmpty().take(200)))
        }.distinctBy { it.record.token }
}

sealed interface DownloadSubmission {
    data object STARTED : DownloadSubmission
    data object QUEUED : DownloadSubmission
    data object ALREADY_QUEUED : DownloadSubmission
    data object FULL : DownloadSubmission
    data object NOT_READY : DownloadSubmission
    data class Saved(val duplicate: SavedDuplicate) : DownloadSubmission
}

enum class SavedFileAccess { AVAILABLE, MISSING, UNAVAILABLE }
data class SavedDuplicate(val item: SavedDownload, val access: SavedFileAccess)

object SavedDuplicateFinder {
    fun find(items: List<SavedDownload>, identity: DownloadIdentity, probe: (String) -> SavedFileAccess): SavedDuplicate? {
        var unavailable: SavedDuplicate? = null
        for (item in items.filter { it.identity == identity }) {
            val access = probe(item.uri)
            if (access == SavedFileAccess.AVAILABLE) return SavedDuplicate(item, access)
            if (access == SavedFileAccess.UNAVAILABLE && unavailable == null) unavailable = SavedDuplicate(item, access)
        }
        return unavailable
    }
}
