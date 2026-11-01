package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.extractor.social.SocialUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A visible public-work session. Account pages and unrelated documents remain blocked. */
object KuaishouBrowserPolicy {
    private val assets = setOf("yximgs.com", "kwimgs.com", "kwaicdn.com", "ksyuncdn.com", "djvod.ndcimgs.com", "wskwai.com", "wsukwai.com")
    private val authority = Regex("(?i)^https://([a-z0-9.-]+)(?::443)?(?=[/?#]|$)")
    private val account = listOf("/passport/", "/oauth/", "/oauth2/", "/login/", "/sso/", "/accounts/", "/account/")
    private val tracking = listOf("/log/", "/logs/", "/collect/", "/report/", "/track/", "/tracking/", "/analytics/", "/monitor/")
    fun videoPage(id: String) = SocialUrls.canonical(SocialUrls.KUAISHOU, id) + "?location=app_share"
    fun sharePage(id: String, landing: String? = null): String {
        require(SocialUrls.validId(SocialUrls.KUAISHOU, id))
        return landing?.let(SocialUrls::normalize)?.takeIf {
            navigation(it, id) && it.substringBefore('?').contains("/fw/photo/")
        } ?: SocialUrls.kuaishouMobilePage(id)
    }
    fun navigation(url: String, id: String) = SocialUrls.validId(SocialUrls.KUAISHOU, id) &&
        SocialUrls.pageAllowed(url, SocialUrls.KUAISHOU) && SocialUrls.videoId(url) == id
    fun safeUrl(url: String) = SocialUrls.normalize(url)?.substringBefore('?') ?: "[未确认网页]"
    fun resourceBlockReason(url: String): BrowserBlockReason? {
        if (url.isBlank() || url.length > 16_384 || url.any { it == '\\' || it.isWhitespace() || it.code < 32 || it.code == 127 }) return BrowserBlockReason.UNSAFE_URL
        if (!url.startsWith("https://", true)) return BrowserBlockReason.UNSUPPORTED_SCHEME
        val host = authority.find(url)?.groupValues?.get(1)?.lowercase() ?: return BrowserBlockReason.UNSAFE_URL
        val parsed = url.toHttpUrlOrNull() ?: return BrowserBlockReason.UNSAFE_URL
        if (host != parsed.host || host.endsWith('.') || ".." in host || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return BrowserBlockReason.UNSAFE_URL
        val path = "/" + parsed.pathSegments.joinToString("/").lowercase().trim('/') + "/"
        if (account.any { it in path }) return BrowserBlockReason.ACCOUNT
        if (tracking.any { it in path }) return BrowserBlockReason.TRACKING
        if (host in setOf("www.kuaishou.com", "kuaishou.com", "captcha.zt.kuaishou.com") ||
            SocialUrls.platform(url) == SocialUrls.KUAISHOU || assets.any { host == it || host.endsWith(".$it") }) return null
        return BrowserBlockReason.UNREVIEWED_RESOURCE
    }
}
