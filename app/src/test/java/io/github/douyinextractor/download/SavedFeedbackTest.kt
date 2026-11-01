package io.github.douyinextractor.download

import org.junit.Assert.*
import org.junit.Test

class SavedFeedbackTest {
    private val identity = DownloadIdentity("Douyin", "7690790070625783066")
    private fun saved(uri: String = "content://saved/1", platform: String = "Douyin", page: Int = 1) =
        SavedDownload(uri, "video.mp4", "Movies/拾影", platform = platform, videoId = identity.videoId, page = page)
    @Test fun restoredHistoryDoesNotTriggerCompletion() {
        val tracker = SavedFeedbackTracker()
        assertTrue(tracker.observe(emptyList(), false).isEmpty())
        assertTrue(tracker.observe(listOf(saved()), true).isEmpty())
        assertTrue(tracker.observe(listOf(saved()), true).isEmpty())
    }
    @Test fun severalQuickCompletionsAreAllObservedExactlyOnce() {
        val tracker = SavedFeedbackTracker()
        tracker.observe(emptyList(), true)
        val batch = listOf(saved("content://saved/2"), saved())
        assertEquals(batch, tracker.observe(batch, true))
        assertTrue(tracker.observe(batch, true).isEmpty())
    }
    @Test fun boundedHistoryEvictionDoesNotReplayRemainingFiles() {
        val tracker = SavedFeedbackTracker()
        tracker.observe(listOf(saved(), saved("content://saved/2")), true)
        assertTrue(tracker.observe(listOf(saved()), true).isEmpty())
    }
    @Test fun foregroundCurrentDirectDownloadMayShowSheet() {
        assertTrue(shouldShowSavedSheet(saved(), identity, identity, true, true, false))
    }
    @Test fun backgroundOrQueuePageNeverOpensSheet() {
        assertFalse(shouldShowSavedSheet(saved(), identity, identity, false, true, false))
        assertFalse(shouldShowSavedSheet(saved(), identity, identity, true, false, false))
        assertFalse(shouldShowSavedSheet(saved(), null, identity, true, true, false))
    }
    @Test fun parsingOrViewingAnotherVideoIsNotInterrupted() {
        assertFalse(shouldShowSavedSheet(saved(), identity, identity, true, true, true))
        assertFalse(shouldShowSavedSheet(saved(), identity, identity.copy(videoId = "other"), true, true, false))
    }
    @Test fun platformAndPartAreIncludedInSheetIdentity() {
        assertFalse(shouldShowSavedSheet(saved(platform = "Bilibili"), identity, identity, true, true, false))
        val bili = identity.copy(platform = "Bilibili", page = 2)
        assertFalse(shouldShowSavedSheet(saved(platform = "Bilibili", page = 1), bili, bili, true, true, false))
        assertTrue(shouldShowSavedSheet(saved(platform = "Bilibili", page = 2), bili, bili, true, true, false))
    }
}
