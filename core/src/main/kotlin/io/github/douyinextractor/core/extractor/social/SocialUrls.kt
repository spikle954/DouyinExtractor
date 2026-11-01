package io.github.douyinextractor.core.extractor.social

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Platform-specific boundaries. Access tokens are ephemeral, never part of canonical identities. */
object SocialUrls {
    const val KUAISHOU = "Kuaishou"
    const val XIAOHONGSHU = "Xiaohongshu"
    const val KUAISHOU_SHARE_LOCATION = "app_share"
    private val ksHosts = setOf("kuaishou.com", "www.kuaishou.com", "v.kuaishou.com", "v.m.chenzhongtech.com")
    // Official share redirects use both v.m and varying single-label mobile subdomains.
    // Only this suffix and the /fw/photo/{id} route are admitted, not arbitrary chenzhongtech hosts.
    private fun ksMobileHost(host: String) = host.matches(Regex("[a-z0-9]{1,32}\\.m\\.chenzhongtech\\.com"))
    private val xhsHosts = setOf("xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com", "www.xhslink.com", "xhslink.cn", "www.xhslink.cn")
    private val authority = Regex("(?i)^https?://([a-z0-9.-]+)(?::([0-9]+))?(?=[/?#]|$)")
    private val ksId = Regex("[A-Za-z0-9]{8,32}")
    private val xhsId = Regex("[a-f0-9]{24}")
    fun validId(platform: String, id: String) = when (platform) { KUAISHOU -> ksId.matches(id); XIAOHONGSHU -> xhsId.matches(id); else -> false }
    fun label(platform: String) = when (platform) { KUAISHOU -> "快手"; XIAOHONGSHU -> "小红书"; else -> "视频平台" }
    fun referer(platform: String) = when (platform) { KUAISHOU -> "https://www.kuaishou.com/"; XIAOHONGSHU -> "https://www.xiaohongshu.com/"; else -> error("Unsupported platform") }
    fun canonical(platform: String, id: String): String {
        require(validId(platform, id))
        return referer(platform) + if (platform == KUAISHOU) "short-video/$id" else "explore/$id"
    }
    /** A public official share route verified to redirect to the same selected work. */
    fun kuaishouMobilePage(id: String): String {
        require(validId(KUAISHOU, id))
        return "https://v.m.chenzhongtech.com/fw/photo/$id"
    }
    fun platform(input: String): String? = trusted(input, true)?.host?.let { when { it in ksHosts || ksMobileHost(it) -> KUAISHOU; it in xhsHosts -> XIAOHONGSHU; else -> null } }
    fun normalize(input: String): String? {
        val raw = if (input.contains("://")) input else "https://$input"
        val url = trusted(raw, true) ?: return null
        val platform = platform(raw) ?: return null
        val path = url.encodedPath
        val id = pathId(url, platform)
        val short = when {
            url.host == "v.kuaishou.com" -> path.matches(Regex("/[A-Za-z0-9_-]{1,64}/?"))
            platform == KUAISHOU && url.host in setOf("kuaishou.com", "www.kuaishou.com") -> path.matches(Regex("/f/[A-Za-z0-9_-]{1,64}/?"))
            platform == XIAOHONGSHU && url.host in xhsHosts.filter { it.contains("xhslink") } -> path.matches(Regex("/(?:[ao]/)?[A-Za-z0-9_-]{1,64}/?"))
            else -> false
        }
        if (id == null && !short) return null
        val builder = url.newBuilder().scheme("https").port(443).fragment(null).query(null)
        // Only original routing/verification fields; no cookies, account IDs or tracking metadata.
        val fields = if (platform == XIAOHONGSHU) setOf("xsec_token", "xsec_source") else
            setOf("photoId") + if (id != null && url.host in setOf("kuaishou.com", "www.kuaishou.com")) setOf("location") else emptySet()
        for (name in fields) {
            val values = url.queryParameterValues(name)
            if (values.size > 1 || values.any { it == null || it.length > 1024 || it.any { c -> c.code < 32 || c == '\\' } }) return null
            values.singleOrNull()?.let { value ->
                if (name == "photoId" && (!validId(KUAISHOU, value) || id != null && id != value)) return null
                // Official mobile-to-desktop redirects use this fixed public page route marker.
                // Dropping it can return a site shell with no target state. Do not retain arbitrary
                // location values, utm_* fields, share tokens or user identifiers.
                if (name != "location" || value == KUAISHOU_SHARE_LOCATION) builder.addQueryParameter(name, value)
            }
        }
        return builder.build().toString()
    }
    fun videoId(input: String): String? {
        val normalized = normalize(input) ?: return null
        val url = normalized.toHttpUrlOrNull() ?: return null
        return pathId(url, platform(normalized) ?: return null)
    }
    private fun pathId(url: HttpUrl, platform: String): String? {
        val path = url.encodedPath.removeSuffix("/")
        val id = when {
            platform == KUAISHOU && url.host in setOf("www.kuaishou.com", "kuaishou.com") && path.startsWith("/short-video/") -> path.removePrefix("/short-video/")
            platform == KUAISHOU && ksMobileHost(url.host) && path.startsWith("/fw/photo/") -> path.removePrefix("/fw/photo/")
            platform == XIAOHONGSHU && url.host in setOf("www.xiaohongshu.com", "xiaohongshu.com") && path.startsWith("/explore/") -> path.removePrefix("/explore/")
            platform == XIAOHONGSHU && url.host in setOf("www.xiaohongshu.com", "xiaohongshu.com") && path.startsWith("/discovery/item/") -> path.removePrefix("/discovery/item/")
            else -> return null
        }
        return id.takeIf { validId(platform, it) }
    }
    fun pageAllowed(input: String, platform: String) = trusted(input, false) != null && platform(input) == platform && normalize(input) != null
    fun media(input: String, platform: String): String? = resource(input, platform, false)
    /** Pages can advertise cleartext CDN addresses. Only offer the same approved resource over HTTPS. */
    fun pageMedia(input: String, platform: String): String? {
        val upgraded = if (input.startsWith("http://")) "https://" + input.removePrefix("http://") else if (input.startsWith("//")) "https:$input" else input
        return media(upgraded, platform)
    }
    fun cover(input: String, platform: String): String? {
        val upgraded = if (input.startsWith("http://")) "https://" + input.removePrefix("http://") else if (input.startsWith("//")) "https:$input" else input
        return resource(upgraded, platform, true)
    }
    private fun resource(input: String, platform: String, cover: Boolean): String? {
        val url = trusted(input, false, 16384) ?: return null
        val domains = when (platform) {
            KUAISHOU -> if (cover) setOf("yximgs.com", "kwimgs.com", "kwaicdn.com") else setOf("djvod.ndcimgs.com", "kwaicdn.com", "ksyuncdn.com", "yximgs.com", "kwimgs.com")
            XIAOHONGSHU -> setOf("xhscdn.com")
            else -> return null
        }
        if (domains.none { url.host == it || url.host.endsWith(".$it") }) return null
        if (!cover) {
            val path = url.encodedPath.lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".m4s") || path.endsWith(".ts") || path.endsWith(".aac") || path.endsWith(".m4a") || path.endsWith(".flv")) return null
            if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp") || path.endsWith(".svg")) return null
            if (platform == KUAISHOU && !path.endsWith(".mp4")) return null
        }
        // Keep signed query encoding exactly as provided by the page; never reconstruct it.
        return input.substringBefore('#')
    }
    private fun trusted(raw: String, allowHttp: Boolean, max: Int = 4096): HttpUrl? {
        if (raw.length > max || raw.any { it == '\\' || it.isWhitespace() || it.code < 32 || it.code == 127 }) return null
        val match = authority.find(raw) ?: return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.host != match.groupValues[1].lowercase() || url.host.endsWith('.') || ".." in url.host) return null
        val expectedPort = if (url.scheme == "https") 443 else 80
        if ((!allowHttp && url.scheme != "https") || url.port != expectedPort || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val rawPath = raw.substring(match.value.length).substringBefore('?').substringBefore('#')
        if (rawPath.ifEmpty { "/" } != url.encodedPath) return null
        return url
    }
}
