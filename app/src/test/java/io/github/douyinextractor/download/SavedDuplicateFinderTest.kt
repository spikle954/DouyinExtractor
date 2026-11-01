package io.github.douyinextractor.download

import org.junit.Assert.*
import org.junit.Test

class SavedDuplicateFinderTest {
    private val file = SavedDownload("content://media/1", "作者_标题_BV1GJ411x7h7_P2.mp4", SaveLocations.DEFAULT_LABEL,
        platform = "Bilibili", videoId = "BV1GJ411x7h7", page = 2)

    @Test fun `duplicate reminder requires accessible file and exact platform work and part`() {
        assertEquals(file, SavedDuplicateFinder.find(listOf(file), file.identity!!) { SavedFileAccess.AVAILABLE }!!.item)
        assertNull(SavedDuplicateFinder.find(listOf(file), file.identity!!.copy(page = 1)) { fail("Unrelated part must not be probed"); SavedFileAccess.AVAILABLE })
        assertNull(SavedDuplicateFinder.find(listOf(file), DownloadIdentity("Douyin", file.videoId, 2)) { fail("Unrelated platform must not be probed"); SavedFileAccess.AVAILABLE })
    }

    @Test fun `deleted files never block a new download and inaccessible files are not claimed available`() {
        assertNull(SavedDuplicateFinder.find(listOf(file), file.identity!!) { SavedFileAccess.MISSING })
        assertEquals(SavedFileAccess.UNAVAILABLE, SavedDuplicateFinder.find(listOf(file), file.identity!!) { SavedFileAccess.UNAVAILABLE }!!.access)
    }

    @Test fun `another accessible copy takes precedence over a deleted or permission-lost copy`() {
        val second = file.copy(uri = "content://media/2")
        val third = file.copy(uri = "content://media/3")
        val result = SavedDuplicateFinder.find(listOf(file, second, third), file.identity!!) {
            when (it) { file.uri -> SavedFileAccess.MISSING; second.uri -> SavedFileAccess.UNAVAILABLE; else -> SavedFileAccess.AVAILABLE }
        }!!
        assertEquals(third, result.item)
        assertEquals(SavedFileAccess.AVAILABLE, result.access)
    }

    @Test fun `old filename-derived identity can trigger a reminder without persisted new metadata`() {
        val older = file.copy(platform = "", videoId = "", page = 1)
        assertEquals(file.identity, older.identity)
        assertEquals(older, SavedDuplicateFinder.find(listOf(older), file.identity!!) { SavedFileAccess.AVAILABLE }!!.item)
    }
}
