package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.input.DouyinUrls
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Limits a temporary browser session to official pages and their platform asset families. */
object BrowserUrlPolicy {
    private val assetDomains = setOf(
        "douyinvod.com", "douyinstatic.com", "douyinpic.com", "byteimg.com", "pstatp.com",
        "bytecdn.cn", "ibytedtos.com",
    )
    // Shared hosting families are not a blanket trust boundary: these paths were observed in the
    // public mobile page. See docs/WEBVIEW_RESEARCH.md. New blocked hosts require review.
    private val observedAssetPaths = mapOf(
        "lf-douyin-mobile.bytecdn.com" to listOf("/obj/growth-douyin-share/growth/douyin_ug/static/"),
        "lf-c-flwb.bytetos.com" to listOf("/obj/rc-client-security/web/glue/", "/obj/rc-client-security/web/dn/"),
        "lf-security.bytegoofy.com" to listOf("/obj/security-secsdk/runtime.js"),
        "lf1-cdn-tos.bytegoofy.com" to listOf("/obj/goofy/secsdk/secsdk-lastest.umd.js"),
        "lf3-cdn-tos.bytegoofy.com" to listOf("/obj/goofy/secsdk/secsdk-lastest.umd.js"),
        "lf6-cdn-tos.bytegoofy.com" to listOf("/obj/goofy/secsdk/secsdk-lastest.umd.js"),
        "lf-rc1.yhgfb-cn-static.com" to listOf("/obj/rc-verifycenter/sec_sdk_build/4.0.10/captcha/"),
        "lf-rc2.yhgfb-cn-static.com" to listOf("/obj/rc-verifycenter/sec_sdk_build/4.0.10/captcha/"),
        "privacy.zijieapi.com" to listOf("/api/web-cmp/sdk/"),
    )
    private val authority = Regex("(?i)^https://([a-z0-9.-]+)(?::443)?(?=[/?#]|$)")
    private val scheme = Regex("(?i)^([a-z][a-z0-9+.-]*):")
    private val trackingPaths = listOf(
        "/obj/static/log-sdk/", "/slardar/", "/monitor/", "/monitor_web/", "/log/", "/logs/",
        "/collect/", "/report/", "/track/", "/tracking/", "/analytics/", "/tea/",
        "/monitor_browser/",
    )
    private val accountPaths = listOf("/passport/", "/oauth/", "/oauth2/", "/login/", "/sso/", "/accounts/")

    fun navigation(url: String): Boolean = DouyinUrls.isAllowed(url) &&
        url.toHttpUrlOrNull()?.let { pathBlockReason(it) == null } == true

    fun resource(url: String): Boolean = resourceBlockReason(url) == null

    /** Returns the fixed diagnostic reason for a denied request; null means permitted. */
    fun resourceBlockReason(url: String): BrowserBlockReason? {
        if (url.isBlank() || url.length > 16_384 || url.any {
                it == '\\' || it.isWhitespace() || it.code < 32 || it.code == 127
            }) return BrowserBlockReason.UNSAFE_URL
        val protocol = scheme.find(url)?.groupValues?.get(1) ?: return BrowserBlockReason.UNSAFE_URL
        if (!protocol.equals("https", ignoreCase = true)) return BrowserBlockReason.UNSUPPORTED_SCHEME
        val host = authority.find(url)?.groupValues?.get(1)?.lowercase() ?: return BrowserBlockReason.UNSAFE_URL
        if (host.startsWith('.') || host.endsWith('.') || ".." in host) return BrowserBlockReason.UNSAFE_URL
        val parsed = url.toHttpUrlOrNull() ?: return BrowserBlockReason.UNSAFE_URL
        if (parsed.scheme != "https" || parsed.host != host || parsed.port != 443 ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return BrowserBlockReason.UNSAFE_URL
        pathBlockReason(parsed)?.let { return it }
        val path = "/" + parsed.pathSegments.joinToString("/")
        val observedAsset = observedAssetPaths[host]?.any {
            if (it.endsWith('/')) path.startsWith(it) else path == it
        } == true
        if (DouyinUrls.isAllowed(url) || assetDomains.any { host == it || host.endsWith(".$it") } || observedAsset) return null
        // This only refines the reason for an already denied target; it adds no load permission.
        if (host.all { it.isDigit() || it == '.' } || '.' !in host ||
            host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return BrowserBlockReason.UNSAFE_URL
        }
        return BrowserBlockReason.UNREVIEWED_RESOURCE
    }

    private fun pathBlockReason(parsed: HttpUrl): BrowserBlockReason? {
        // Decode individual path segments before checking; %63ollect must not evade the policy.
        val path = "/" + parsed.pathSegments.joinToString("/").lowercase().trim('/') + "/"
        if (accountPaths.any { it in path }) return BrowserBlockReason.ACCOUNT
        if (trackingPaths.any { it in path }) return BrowserBlockReason.TRACKING
        return null
    }
}
