package io.github.douyinextractor.download

import io.github.douyinextractor.core.download.DownloadPhase
import io.github.douyinextractor.core.download.DownloadProgress
import org.junit.Assert.*
import org.junit.Test

class SavedDownloadsTest {
    private val first = SavedDownload("content://media/external_primary/video/media/1", "标题_123.mp4",
        SaveLocations.DEFAULT_LABEL, "content://com.android.externalstorage.documents/document/primary%3AMovies%2F%E6%8B%BE%E5%BD%B1", 1024, 1791345600000)

    @Test fun `older successful records keep their actual original location`() {
        val old = DownloadRecord(phase = DownloadPhase.COMPLETE, savedUri = first.uri, fileName = first.fileName,
            location = SaveLocations.LEGACY_PATH, progress = DownloadProgress(1024))
        val saved = old.savedDownload()!!
        assertEquals("手机存储 / Download / DouyinExtractor", SaveLocations.display(saved.location))
        assertEquals("primary:Download/DouyinExtractor", SaveLocations.documentId(saved.location))
        assertNotEquals(SaveLocations.DEFAULT_LABEL, SaveLocations.display(saved.location))
        assertTrue(SaveLocations.findingHint(saved.location).contains("旧版"))
    }

    @Test fun `active or unsuccessful downloads never appear as saved files`() {
        DownloadPhase.entries.filter { it != DownloadPhase.COMPLETE }.forEach { phase ->
            assertNull(DownloadRecord(phase = phase, savedUri = first.uri).savedDownload())
        }
        assertNull(DownloadRecord(phase = DownloadPhase.COMPLETE).savedDownload())
        assertNull(DownloadRecord(phase = DownloadPhase.COMPLETE, savedUri = "https://media.example/video.mp4?token=private").savedDownload())
    }

    @Test fun `history survives restart with original URI filename folder time and size`() {
        val custom = first.copy(uri = "content://provider/tree/user%3AVideos/document/user%3AVideos%2Fitem",
            folderUri = "content://provider/tree/user%3AVideos/document/user%3AVideos", location = "所选文件夹 / 我的视频")
        val result = SavedDownloadHistory.decode(SavedDownloadHistory.encode(listOf(custom, first)))
        assertEquals(listOf(custom, first), result)
        assertEquals(custom.folderUri, result.first().folderUri)
        assertEquals(custom.location, result.first().location)
    }

    @Test fun `repeated completion is deduplicated without losing older saved files`() {
        val second = first.copy(uri = "content://media/external_primary/video/media/2", fileName = "第二个.mp4")
        val once = SavedDownloadHistory.add(listOf(first), second)
        assertEquals(listOf(second, first), SavedDownloadHistory.add(once, second))
        val newTask = DownloadRecord(phase = DownloadPhase.DOWNLOADING, fileName = "还没完成.mp4")
        assertNull(newTask.savedDownload())
        assertEquals(listOf(second, first), once)
    }

    @Test fun `history is bounded and malformed records do not become saved media`() {
        val many = (1..80).map { first.copy(uri = "content://media/external_primary/video/media/$it") }
        val result = SavedDownloadHistory.add(many, first.copy(uri = "content://media/external_primary/video/media/100"))
        assertEquals(50, result.size)
        assertEquals(result, SavedDownloadHistory.decode(SavedDownloadHistory.encode(result)))
        assertEquals(emptyList<SavedDownload>(), SavedDownloadHistory.decode(mapOf("count" to "2147483647", "0.uri" to "https://example/video.mp4", "0.name" to "bad")))
        assertEquals(emptyList<SavedDownload>(), SavedDownloadHistory.decode(mapOf("count" to "-1")))
        assertEquals(listOf(first), SavedDownloadHistory.decode(SavedDownloadHistory.encode(listOf(first, first))))
    }

    @Test fun `folder hints distinguish phone storage storage volumes and unknown providers`() {
        assertEquals("手机存储 / 下载 / 我的 视频", SaveLocations.documentLabel("primary:下载/我的 视频/"))
        assertEquals("存储卷 1234-ABCD / Videos", SaveLocations.documentLabel("1234-ABCD:Videos"))
        assertEquals("手机存储", SaveLocations.documentLabel("primary:"))
        assertNull(SaveLocations.documentId("所选文件夹 / documents"))
        assertTrue(SaveLocations.findingHint(SaveLocations.DEFAULT_LABEL).contains("相册"))
        assertFalse(SaveLocations.findingHint("所选文件夹 / documents").contains("相册"))
    }
}
