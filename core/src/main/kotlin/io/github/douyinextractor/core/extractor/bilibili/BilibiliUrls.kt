package io.github.douyinextractor.core.extractor.bilibili

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object BilibiliUrls {
    private val hosts = setOf("bilibili.com", "www.bilibili.com", "m.bilibili.com", "b23.tv")
    private val id = Regex("(?:BV[1-9A-HJ-NP-Za-km-z]{10}|av[1-9][0-9]{0,19})")
    private val authority = Regex("(?i)^https?://([a-z0-9.-]+)(?::([0-9]+))?(?=[/?#]|$)")
    fun validId(value: String) = id.matches(value)
    fun canonical(id: String, page: Int = 1): String {
        require(validId(id) && page in 1..10000)
        return "https://www.bilibili.com/video/$id" + if (page == 1) "" else "?p=$page"
    }
    fun normalize(input: String): String? {
        if (validId(input)) return canonical(input)
        val raw = if (input.startsWith("b23.tv/") || input.startsWith("www.bilibili.com/") || input.startsWith("bilibili.com/") || input.startsWith("m.bilibili.com/")) "https://$input" else input
        if (raw.length > 4096 || raw.any { it == '\\' || it.isWhitespace() || it.code < 32 || it.code == 127 }) return null
        val match = authority.find(raw) ?: return null
        if (match.groupValues[1].lowercase() !in hosts) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != if (url.scheme == "https") 443 else 80) return null
        val rawPath = raw.substring(match.value.length).substringBefore('?').substringBefore('#')
        if (url.encodedPath != rawPath.ifEmpty { "/" }) return null
        if (url.host == "b23.tv") {
            if (!url.encodedPath.matches(Regex("/[a-zA-Z0-9]{1,32}/?"))) return null
            return url.newBuilder().scheme("https").port(443).fragment(null).build().toString()
        }
        val reference = url.encodedPath.removePrefix("/video/").removeSuffix("/")
        if (!url.encodedPath.startsWith("/video/") || !validId(reference)) return null
        val parts = url.queryParameterValues("p")
        if (parts.size > 1 || parts.any { it?.toIntOrNull() !in 1..10000 }) return null
        return canonical(reference, parts.singleOrNull()?.toInt() ?: 1)
    }
    fun videoId(url: String): String? = normalize(url)?.toHttpUrlOrNull()?.takeUnless { it.host == "b23.tv" }
        ?.encodedPath?.removePrefix("/video/")?.removeSuffix("/")
    fun page(url: String): Int = normalize(url)?.toHttpUrlOrNull()?.queryParameter("p")?.toIntOrNull() ?: 1
    fun short(url: String) = normalize(url)?.toHttpUrlOrNull()?.host == "b23.tv"

    fun media(input: String): String? = resource(input, setOf("bilivideo.com"))?.takeIf {
        it.toHttpUrlOrNull()?.encodedPath?.endsWith(".mp4", ignoreCase = true) == true
    }
    fun cover(input: String): String? {
        val upgraded = when {
            input.startsWith("http://") -> "https://" + input.removePrefix("http://")
            input.startsWith("//") -> "https:$input"
            else -> input
        }
        return resource(upgraded, setOf("hdslb.com"))
    }
    private fun resource(raw: String, domains: Set<String>): String? {
        if (raw.length > 16384 || raw.any { it == '\\' || it.isWhitespace() || it.code < 32 || it.code == 127 }) return null
        val match = authority.find(raw) ?: return null
        val host = match.groupValues[1].lowercase()
        if (host.endsWith('.') || ".." in host || domains.none { host == it || host.endsWith(".$it") }) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return raw.substringBefore('#')
    }
    internal fun requestAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        return normalize(url) != null || parsed.host == "api.bilibili.com" &&
            Regex("^https://api\\.bilibili\\.com(?::443)?(?:/x/web-interface/view|/x/player/playurl)(?:\\?|$)").containsMatchIn(url)
    }
}
