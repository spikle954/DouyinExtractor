package io.github.douyinextractor.core.input

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure URL handling. Only [normalize] upgrades an HTTP user input to HTTPS. */
object DouyinUrls {
    const val MAX_URL_LENGTH = 4096

    private val hosts = setOf(
        "douyin.com", "www.douyin.com", "v.douyin.com", "m.douyin.com",
        "iesdouyin.com", "www.iesdouyin.com", "v.iesdouyin.com",
    )
    private val shortHosts = setOf("v.douyin.com", "v.iesdouyin.com")
    private val idPattern = Regex("[0-9]{1,25}")
    private val authorityPattern = Regex("(?i)^https?://([^/?#]+)")
    private val hostPortPattern = Regex("(?i)^([a-z0-9.-]+)(?::([0-9]+))?$")
    private val schemePattern = Regex("(?i)^[a-z][a-z0-9+.-]*:")
    private val shortPathPattern = Regex("[a-zA-Z0-9_-]+")
    private val nonVideoRoutes = setOf("note", "live", "webcast", "slides", "image", "images")
    private val trackingParameters = setOf(
        "did", "iid", "u_code", "share_sign", "share_app_id", "share_iid", "share_link_id",
        "share_item_id", "share_source", "share_channel", "share_scene",
        "share_version", "share_from", "share_to", "share_medium", "share_platform",
        "share_device_id", "enter_from", "enter_method", "previous_page",
        "from", "from_ssr", "with_sec_did", "sec_user_id", "checksum", "user_id",
        "author_id", "region", "mid", "im_seq", "is_copy_url", "is_from_webapp",
        "ts", "share_track_info", "share_extra_params", "activity_info", "ug_share_id",
        "share_video_id", "from_aid", "titletype", "app",
    )

    /**
     * Removes known share tracking fields, but retains unknown query fields because short-link
     * routing may need them. Generic allowlisted paths are retained for intermediate redirects;
     * ShareTextParser decides which initial pages are supported.
     */
    fun normalize(url: String): String? {
        val input = url.trim()
        if (input.length > MAX_URL_LENGTH) return null
        val absolute = if (schemePattern.containsMatchIn(input)) input else "https://$input"
        val parsed = parseTrusted(absolute, allowHttp = true) ?: return null
        if (identity(parsed) is Identity.Invalid) return null
        return parsed.newBuilder().apply {
            if (parsed.scheme == "http") scheme("https").port(443)
            fragment(null)
            parsed.queryParameterNames.forEach { name ->
                if (name.lowercase().startsWith("utm_") || name.lowercase() in trackingParameters) {
                    removeAllQueryParameters(name)
                }
            }
        }.build().toString().takeIf { it.length <= MAX_URL_LENGTH }
    }

    /** HTTPS host boundary for requests and every redirect; it never upgrades insecure URLs. */
    fun isAllowed(url: String): Boolean = parseTrusted(url, allowHttp = false) != null

    fun videoId(url: String): String? {
        val parsed = parseTrusted(url, allowHttp = false) ?: return null
        return (identity(parsed) as? Identity.Video)?.id
    }

    fun isShortLink(url: String): Boolean {
        val parsed = parseTrusted(url, allowHttp = false) ?: return false
        if (parsed.host !in shortHosts || identity(parsed) is Identity.Invalid) return false
        val path = parsed.pathSegments.dropLastWhile { it.isEmpty() }
        return path.size == 1 && shortPathPattern.matches(path.single()) &&
            path.single().lowercase() !in nonVideoRoutes
    }

    fun canonical(id: String): String {
        require(idPattern.matches(id)) { "Invalid video identifier" }
        return "https://www.douyin.com/video/$id"
    }

    /** Query fields can carry user identifiers or opaque routing tokens; never put them in logs. */
    fun sanitizeForDiagnostics(url: String): String {
        val parsed = parseTrusted(url, allowHttp = false) ?: return "[invalid URL]"
        return parsed.newBuilder().query(null).fragment(null).build().toString()
    }

    private fun parseTrusted(input: String, allowHttp: Boolean): HttpUrl? {
        if (input.isEmpty() || input.length > MAX_URL_LENGTH || input.any {
                it == '\\' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f
            }) return null
        // Check the original authority too: an encoded host, trailing dot, empty userinfo, or
        // browser-style backslash rewriting must not turn an untrusted spelling into a match.
        val authority = authorityPattern.find(input)?.groupValues?.get(1) ?: return null
        val hostPort = hostPortPattern.matchEntire(authority) ?: return null
        if (hostPort.groupValues[1].lowercase() !in hosts) return null
        val parsed = input.toHttpUrlOrNull() ?: return null
        if (parsed.host !in hosts || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.scheme != "https" && !(allowHttp && parsed.scheme == "http")) return null
        val expectedPort = if (parsed.scheme == "https") 443 else 80
        val explicitPort = hostPort.groupValues[2]
        if (parsed.port != expectedPort || (explicitPort.isNotEmpty() && explicitPort != expectedPort.toString())) return null
        return parsed
    }

    private fun identity(url: HttpUrl): Identity {
        // A short link's query is routing data, not authoritative video metadata. A copied or
        // attacker-supplied modal_id must never bypass resolving the actual opaque short code.
        if (url.host in shortHosts) return Identity.Other
        val path = url.pathSegments.dropLastWhile { it.isEmpty() }
        if (path.any { it.lowercase() in nonVideoRoutes }) return Identity.Invalid
        val videoRoute = path.firstOrNull() == "video" ||
            (path.firstOrNull() == "share" && path.getOrNull(1) == "video")
        val routeId = when {
            path.size == 2 && path[0] == "video" -> path[1]
            path.size == 3 && path[0] == "share" && path[1] == "video" -> path[2]
            else -> null
        }
        if (videoRoute && (routeId == null || !idPattern.matches(routeId))) return Identity.Invalid
        val modalIds = url.queryParameterValues("modal_id")
        if (modalIds.any { it == null || !idPattern.matches(it) }) return Identity.Invalid
        val ids = (modalIds.filterNotNull() + listOfNotNull(routeId)).distinct()
        return when {
            ids.size > 1 -> Identity.Invalid
            ids.size == 1 -> Identity.Video(ids.single())
            else -> Identity.Other
        }
    }

    private sealed interface Identity {
        data class Video(val id: String) : Identity
        data object Other : Identity
        data object Invalid : Identity
    }
}
