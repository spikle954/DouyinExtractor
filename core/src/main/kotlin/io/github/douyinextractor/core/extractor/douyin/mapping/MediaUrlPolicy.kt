package io.github.douyinextractor.core.extractor.douyin.mapping

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Hosts are platform-owned URL families, never a general-purpose remote URL fetch allowance. */
object MediaUrlPolicy {
    private val mediaDomains = setOf(
        "douyinvod.com", "douyin.com", "iesdouyin.com", "snssdk.com", "amemv.com", "bytecdn.cn",
        // Observed 2026-10-03: public aweme.snssdk.com play response redirects over TLS to this CDN.
        "zjcdn.com",
    )
    private val coverDomains = mediaDomains + setOf("douyinpic.com", "byteimg.com", "pstatp.com", "ibytedtos.com")
    private val authority = Regex("(?i)^https://([a-z0-9.-]+)(?::(443))?(?=[/?#]|$)")
    private val unsupportedExtensions = setOf(
        "m3u8", "m3u", "mpd", "mp3", "m4a", "aac", "ogg", "wav", "flac", "webm", "mov", "mkv",
        "ts", "m4s", "vtt", "jpg", "jpeg", "png", "gif", "webp", "avif",
    )
    private val unsupportedFormats = unsupportedExtensions + setOf("hls", "dash", "audio")

    /** Retains signed query data verbatim; never rewrites watermarked or insecure resource URLs. */
    fun media(url: String): String? = validate(url, mediaDomains)?.takeUnless { valid ->
        val parsed = valid.toHttpUrlOrNull() ?: return null
        parsed.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase() in unsupportedExtensions ||
            parsed.queryParameterNames.any { name ->
                val values = parsed.queryParameterValues(name).filterNotNull().map { it.lowercase() }
                when (name.lowercase()) {
                    "format", "mime_type", "mimetype", "mime", "type" -> values.any {
                        it in unsupportedFormats || it.startsWith("audio/") || "mpegurl" in it || "dash+xml" in it
                    }
                    else -> false
                }
            }
    }

    fun cover(url: String): String? = validate(url, coverDomains)

    private fun validate(url: String, domains: Set<String>): String? {
        if (url.isBlank() || url.length > 16_384 || url.any {
                it == '\\' || it.isWhitespace() || it.code < 32 || it.code == 127
            }) return null
        val host = authority.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        if (host.endsWith('.') || host.startsWith('.') || ".." in host ||
            domains.none { host == it || host.endsWith(".$it") }) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host != host || parsed.port != 443 ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return url.substringBefore('#')
    }
}
