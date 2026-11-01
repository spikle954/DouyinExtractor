package io.github.douyinextractor.core.extractor.douyin.mapping

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Narrow public playback alternative. Does not sign requests, import cookies or alter CDN URLs. */
object DouyinPlaybackSource {
    private const val HOST = "aweme.snssdk.com"
    private const val WATERMARKED = "/aweme/v1/playwm/"
    private const val PLAY = "/aweme/v1/play/"

    fun isWatermarked(url: String): Boolean {
        val safe = MediaUrlPolicy.media(url) ?: return false
        val parsed = safe.toHttpUrlOrNull() ?: return false
        return parsed.host == HOST && parsed.encodedPath == WATERMARKED
    }

    /** A candidate, not proof of availability or absence of an author's embedded logo. */
    fun alternative(url: String): String? {
        val safe = MediaUrlPolicy.media(url) ?: return null
        val parsed = safe.toHttpUrlOrNull() ?: return null
        if (parsed.host != HOST || parsed.encodedPath != WATERMARKED) return null
        val ids = parsed.queryParameterValues("video_id")
        if (ids.size != 1 || ids.single()?.matches(Regex("[a-zA-Z0-9_-]{1,128}")) != true) return null
        // Do not reinterpret signed or explicitly watermarked requests. Unknown parameters fail closed.
        if (parsed.queryParameterNames.any { it !in setOf("video_id", "ratio", "line", "media_type", "watermark", "logo_name") }) return null
        if (parsed.queryParameterValues("watermark").any { it != "0" }) return null
        // Replace only this exact path; query encoding, ordering and values remain byte-for-byte intact.
        val pathStart = safe.indexOf('/', safe.indexOf("://") + 3)
        if (pathStart < 0 || safe.substring(pathStart).substringBefore('?') != WATERMARKED) return null
        return MediaUrlPolicy.media(safe.substring(0, pathStart) + PLAY + safe.substring(pathStart + WATERMARKED.length))
    }
}
