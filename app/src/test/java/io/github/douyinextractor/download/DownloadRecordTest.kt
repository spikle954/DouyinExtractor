package io.github.douyinextractor.download

import org.junit.Assert.*
import org.junit.Test

class DownloadRecordTest {
    @Test fun `Bili retry retains the original part and Douyin records retain their platform`() {
        assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7?p=2", DownloadRecord(videoId = "BV1GJ411x7h7", platform = "Bilibili", page = 2).reparseUrl)
        assertEquals("https://www.douyin.com/video/123", DownloadRecord(videoId = "123").reparseUrl)
        assertNull(DownloadRecord(videoId = "bad", platform = "Bilibili", page = 2).reparseUrl)
    }
}
