package io.github.douyinextractor.download

import org.junit.Assert.*
import org.junit.Test

class SavedDownloadSearchTest {
    private val douyin = SavedDownload("content://media/1", "摇头晃脑_朋友的酒dj_7690145822168750565.mp4", SaveLocations.DEFAULT_LABEL,
        platform = "Douyin", videoId = "7690145822168750565", quality = "1080P")
    private val bili = SavedDownload("content://media/2", "UP主_游戏视频_BV1GJ411x7h7_P2.mp4", "所选文件夹 / 收藏", platform = "Bilibili", videoId = "BV1GJ411x7h7", page = 2, quality = "720P")

    @Test fun `search matches Chinese author title ID platform folder and quality without case sensitivity`() {
        val all = listOf(douyin, bili)
        for (query in listOf("摇头晃脑", "朋友的酒", "7690145822168750565", "DOUYIN", "Movies", "1080p"))
            assertEquals(listOf(douyin), SavedDownloadHistory.search(all, query))
        for (query in listOf("游戏", "UP主", "bv1gj411x7h7", "B站", "哔哩哔哩", "收藏", "P2"))
            assertEquals(listOf(bili), SavedDownloadHistory.search(all, query))
    }

    @Test fun `empty and multiple keyword searches preserve original order and provide no-match results`() {
        val all = listOf(bili, douyin)
        assertEquals(all, SavedDownloadHistory.search(all, " \n "))
        assertEquals(listOf(bili), SavedDownloadHistory.search(all, "游戏 720p P2"))
        assertTrue(SavedDownloadHistory.search(all, "游戏 1080P").isEmpty())
        assertTrue(SavedDownloadHistory.search(all, "不存在").isEmpty())
    }

    @Test fun `older filenames identify videos and parts without relying on titles`() {
        assertEquals(DownloadIdentity("Douyin", douyin.videoId), douyin.copy(platform = "", videoId = "").identity)
        assertEquals(DownloadIdentity("Bilibili", bili.videoId, 2), bili.copy(platform = "", videoId = "").identity)
        assertNotEquals(bili.identity, bili.copy(page = 1).identity)
        assertNull(DownloadIdentity.fromFileName("标题里有7690145822168750565但不是下载文件.mp4"))
        assertNull(DownloadIdentity.fromFileName("标题_BV1GJ411x7h7_P0.mp4"))
        assertEquals(bili.identity, DownloadIdentity.fromFileName("标题_BV1GJ411x7h7_P2 (1).mp4"))
        assertEquals(douyin.identity, DownloadIdentity.fromFileName("标题_7690145822168750565 (2).mp4"))
    }

    @Test fun `new identity metadata survives persistence and old records remain searchable`() {
        assertEquals(listOf(douyin, bili), SavedDownloadHistory.decode(SavedDownloadHistory.encode(listOf(douyin, bili))))
        val old = SavedDownloadHistory.encode(listOf(bili)).filterKeys { !it.endsWith(".platform") && !it.endsWith(".id") && !it.endsWith(".page") && !it.endsWith(".quality") }
        val restored = SavedDownloadHistory.decode(old)
        assertEquals(bili.identity, restored.single().identity)
        assertEquals(restored, SavedDownloadHistory.search(restored, "B站 P2"))
    }

    @Test fun `search uses original metadata even when the filename title was shortened`() {
        val full = douyin.copy(fileName = "short_7690145822168750565.mp4", title = "完整标题中末尾的重要关键词", author = "完整作者名字")
        val restored = SavedDownloadHistory.decode(SavedDownloadHistory.encode(listOf(full)))
        assertEquals(listOf(full), restored)
        assertEquals(restored, SavedDownloadHistory.search(restored, "末尾 重要关键词"))
        assertEquals(restored, SavedDownloadHistory.search(restored, "完整作者"))
    }
}
