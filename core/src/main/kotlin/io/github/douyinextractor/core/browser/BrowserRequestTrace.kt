package io.github.douyinextractor.core.browser

import java.net.URI
import java.util.Locale

/** Fixed reasons only: arbitrary page strings must not enter a diagnostic report. */
enum class BrowserBlockReason(val label: String) {
    TRACKING("统计请求"),
    ACCOUNT("账户请求"),
    UNSAFE_URL("不安全地址"),
    UNREVIEWED_RESOURCE("未审核资源"),
    OTHER_VIDEO("其他视频页面"),
    UNSUPPORTED_SCHEME("不支持的协议"),
}

/**
 * In-memory request diagnostics, without headers, response bodies, or full URLs.
 *
 * URL queries, fragments and opaque path components are never retained. The only URL-derived
 * strings retained are a strictly validated hostname and a category chosen from fixed constants.
 * This classification describes a path's shape; it does not grant permission to load the URL.
 * At most 64 distinct events are kept, with least-recently-seen eviction. Totals include evicted
 * events; a retained event's count covers its current residence in the bounded collection.
 */
class BrowserRequestTrace {
    private enum class Kind(val label: String) { BLOCKED("阻止"), HTTP("HTTP 错误"), NETWORK("网络错误") }

    private data class Endpoint(val host: String, val category: String)
    private data class Event(val kind: Kind, val endpoint: Endpoint, val detail: String)

    private val entries = LinkedHashMap<Event, Int>(MAX_ENTRIES + 1, 0.75f, true)
    private var blockedCount = 0
    private var httpCount = 0
    private var networkCount = 0
    private var evictionCount = 0

    @Synchronized
    fun blocked(url: String, reason: BrowserBlockReason) {
        blockedCount = increment(blockedCount)
        record(Event(Kind.BLOCKED, endpoint(url), reason.label))
    }

    @Synchronized
    fun httpFailure(url: String, status: Int, localBlock: BrowserLocalBlock? = null,
                    reason: String? = null, responseHeaders: Map<String, String>? = null): Boolean {
        if (localBlock?.matches(status, reason, responseHeaders) == true) return false
        httpCount = increment(httpCount)
        record(Event(Kind.HTTP, endpoint(url), "HTTP $status"))
        return true
    }

    @Synchronized
    fun networkFailure(url: String, code: Int) {
        networkCount = increment(networkCount)
        record(Event(Kind.NETWORK, endpoint(url), "错误码 $code"))
    }

    @Synchronized
    fun summary(): String = buildString {
        append("资源阻止：${countText(blockedCount)} 次；HTTP 错误（不含本机拦截）：${countText(httpCount)} 次；")
        append("网络错误：${countText(networkCount)} 次；保留记录：${entries.size} 类")
        if (evictionCount > 0) append("（部分早期记录已省略）")
    }

    /** A detached snapshot; later callbacks cannot mutate a report already being copied. */
    @Synchronized
    fun details(): List<String> = entries.map { (event, count) ->
        "${event.kind.label} | ${event.endpoint.host} | ${event.endpoint.category} | " +
            "${event.detail} | 次数 ${countText(count)}"
    }

    private fun record(event: Event) {
        entries[event] = increment(entries[event] ?: 0)
        if (entries.size > MAX_ENTRIES) {
            val oldest = entries.entries.iterator()
            oldest.next()
            oldest.remove()
            evictionCount = increment(evictionCount)
        }
    }

    private fun endpoint(url: String): Endpoint {
        if (url.isEmpty() || url.length > MAX_URL_LENGTH || url.any {
                it == '\\' || it.isWhitespace() || it.code < 32 || it.code == 127
            }) return INVALID_ENDPOINT
        val host = AUTHORITY.find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: return INVALID_ENDPOINT
        if (host.length > 253 || host.split('.').any { !HOST_LABEL.matches(it) }) return INVALID_ENDPOINT
        val parsed = try { URI(url) } catch (_: Exception) { return INVALID_ENDPOINT }
        if (!parsed.scheme.equals("https", ignoreCase = true) ||
            !parsed.host.equals(host, ignoreCase = true) || parsed.rawUserInfo != null ||
            (parsed.port != -1 && parsed.port != 443)) return INVALID_ENDPOINT
        return Endpoint(host, category(host, parsed.path.orEmpty().lowercase(Locale.ROOT)))
    }

    private fun category(host: String, path: String): String {
        // These strings are constants, never interpolated from the request's path.
        if (host in PAGE_HOSTS) {
            if (path.trimEnd('/') == "/aweme/v1/web/aweme/detail") return "/aweme/v1/web/aweme/detail/"
            if (path.startsWith("/aweme/v1/web/")) return "/aweme/v1/web/…"
            if (path.startsWith("/share/video/") || path.startsWith("/video/")) return "视频页面"
        }
        val segments = path.split('/')
        if (segments.any { it in ACCOUNT_SEGMENTS }) return "账户相关路径"
        if (segments.any { it in TRACKING_SEGMENTS }) return "统计相关路径"
        if (segments.any { it in SECURITY_SEGMENTS }) return "安全校验相关路径"
        return when (path.substringAfterLast('.', "")) {
            "js", "mjs" -> "JS 资源"
            "css" -> "CSS 资源"
            "json" -> "JSON 资源"
            "jpg", "jpeg", "png", "webp", "gif", "avif", "svg", "ico" -> "图片资源"
            "woff", "woff2", "ttf", "otf" -> "字体资源"
            "mp4" -> "MP4 资源"
            "m3u8", "mpd" -> "流媒体清单"
            else -> "其他路径"
        }
    }

    private fun increment(value: Int): Int = if (value >= MAX_COUNT) MAX_COUNT else value + 1
    private fun countText(value: Int): String = if (value >= MAX_COUNT) "≥$MAX_COUNT" else value.toString()

    companion object {
        const val MAX_ENTRIES = 64
        private const val MAX_COUNT = 1_000_000
        private const val MAX_URL_LENGTH = 16_384
        private val INVALID_ENDPOINT = Endpoint("[invalid URL]", "地址已隐藏")
        private val AUTHORITY = Regex("(?i)^https://([a-z0-9.-]+)(?::443)?(?=[/?#]|$)")
        private val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val PAGE_HOSTS = setOf("www.douyin.com", "douyin.com", "m.douyin.com", "www.iesdouyin.com", "iesdouyin.com")
        private val ACCOUNT_SEGMENTS = setOf("passport", "oauth", "oauth2", "login", "sso", "accounts")
        private val TRACKING_SEGMENTS = setOf("log-sdk", "slardar", "monitor", "monitor_web", "monitor_browser", "log", "logs", "collect", "report", "track", "tracking", "analytics", "tea")
        private val SECURITY_SEGMENTS = setOf("captcha", "secsdk", "security-secsdk", "rc-client-security", "rc-verifycenter", "verifycenter")
    }
}
