package io.github.douyinextractor.download

/** Observe saved-history additions, including quick queue transitions; never replay restored records. */
class SavedFeedbackTracker {
    private var previous: Set<String>? = null
    fun observe(items: List<SavedDownload>, ready: Boolean): List<SavedDownload> {
        if (!ready) return emptyList()
        val known = previous
        previous = items.map { it.uri }.toSet()
        return if (known == null) emptyList() else items.filter { it.uri !in known }
    }
}

fun shouldShowSavedSheet(item: SavedDownload, startedHere: DownloadIdentity?, visible: DownloadIdentity?,
    resumed: Boolean, resultVisible: Boolean, parsing: Boolean): Boolean =
    resumed && resultVisible && !parsing && startedHere != null && item.identity == startedHere && visible == startedHere
