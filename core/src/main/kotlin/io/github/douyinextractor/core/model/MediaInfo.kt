package io.github.douyinextractor.core.model

data class MediaInfo(
    val platform: String,
    val id: String,
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val durationMs: Long?,
    val formats: List<MediaFormat>,
    val pages: List<VideoPage> = emptyList(),
    val selectedPage: Int = 1,
    val canonicalUrl: String? = null,
)

data class VideoPage(val number: Int, val title: String, val cid: Long, val durationMs: Long?)

/** Page resource or explicitly identified public playback candidate; availability requires download validation. */
data class MediaFormat(
    val url: String,
    val width: Int?,
    val height: Int?,
    val bitrate: Long?,
    val codec: String?,
    val mimeType: String?,
    val watermarked: Boolean?,
    val source: String,
    val contentLength: Long? = null,
    val watermarkFreeCandidate: Boolean = false,
    val qualityLabel: String? = null,
)
