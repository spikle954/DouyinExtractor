package io.github.douyinextractor.core.model

/** Quality uses declared pixel area, then bitrate, then known dimensions; missing data ranks last. */
object MediaFormatSelector {
    private val quality = compareBy<MediaFormat> {
        if (it.width != null && it.height != null) it.width.toLong() * it.height else -1L
    }.thenBy { it.bitrate ?: -1L }
        .thenBy { it.height ?: -1 }.thenBy { it.width ?: -1 }
        .thenBy { it.url }

    fun highestQuality(formats: List<MediaFormat>): MediaFormat? = formats
        .filter { (it.width != null && it.height != null) || it.bitrate != null }
        .maxWithOrNull(quality)

    /** Prefer a declared non-watermarked stream, then unknown, then watermarked; codec and quality break ties. */
    fun recommended(formats: List<MediaFormat>): MediaFormat? = formats.maxWithOrNull(
        compareBy<MediaFormat> { when {
            it.watermarked == true -> 0
            it.watermarked == false -> 3
            it.watermarkFreeCandidate -> 2
            else -> 1
        } }
            .thenBy { compatibility(it.codec) }.then(quality),
    )

    private fun compatibility(codec: String?): Int = when (codec?.lowercase()) {
        "h264", "h.264", "avc", "avc1" -> 2
        null, "", "unknown" -> 1
        else -> if (codec.lowercase().startsWith("avc1.")) 2 else 0
    }
}
