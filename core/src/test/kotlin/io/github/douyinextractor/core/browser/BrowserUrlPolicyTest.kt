package io.github.douyinextractor.core.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test fun `navigation only allows official HTTPS pages`() {
        assertTrue(BrowserUrlPolicy.navigation("https://www.iesdouyin.com/share/video/123/"))
        assertFalse(BrowserUrlPolicy.navigation("http://www.douyin.com/video/123"))
        assertFalse(BrowserUrlPolicy.navigation("https://www.douyin.com.evil.invalid/video/123"))
        assertFalse(BrowserUrlPolicy.navigation("snssdk1128://aweme/detail/123"))
        assertFalse(BrowserUrlPolicy.navigation("https://synthetic.douyinvod.com/video.mp4"))
    }

    @Test fun `permits observed official mobile page asset families`() {
        listOf(
            "https://lf-douyin-mobile.bytecdn.com/obj/growth-douyin-share/growth/douyin_ug/static/app.js",
            "https://lf-security.bytegoofy.com/obj/security-secsdk/runtime.js",
            "https://lf-c-flwb.bytetos.com/obj/rc-client-security/web/glue/a.js",
            "https://sf1-cdn-tos.douyinstatic.com/favicon.ico",
            "https://synthetic.douyinvod.com/video.mp4",
            "https://synthetic.douyinpic.com/cover.jpg",
        ).forEach { assertTrue(it, BrowserUrlPolicy.resource(it)) }
    }

    @Test fun `blocks known analytics endpoints and libraries`() {
        listOf(
            "https://lf3-cdn-tos.bytescm.com/obj/static/log-sdk/collect/sdk.js",
            "https://lf3-short.ibytedapm.com/slardar/collect",
            "https://mon.snssdk.com/monitor/collect/", "https://mcs.snssdk.com/list",
            "https://mon.zijieapi.com/monitor/", "https://www.douyin.com/aweme/v1/web/collect/",
            "https://synthetic.bytescm.com/%63ollect/data",
        ).forEach { assertFalse(it, BrowserUrlPolicy.resource(it)) }
    }

    @Test fun `rejects unsafe authority encodings and local schemes`() {
        listOf(
            "https://douyinstatic.com.evil.invalid/a.js", "https://douyinstatic.com./a.js",
            "https://evil@douyinstatic.com/a.js", "https://@douyinstatic.com/a.js",
            "https://douyinstatic.com:444/a.js", "https://%64ouyinstatic.com/a.js",
            "http://douyinstatic.com/a.js", "file:///sdcard/secrets", "content://private",
            "data:text/javascript,alert(1)", "https://127.0.0.1/a.js", "https://[::1]/a.js",
            "https://evil.invalid\\@douyinstatic.com/a.js", "https://www.bytedance.com/a.js",
        ).forEach { assertFalse(it, BrowserUrlPolicy.resource(it)) }
    }

    @Test fun `official mainpage API remains available but log route blocked`() {
        assertTrue(BrowserUrlPolicy.resource("https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=123"))
        assertFalse(BrowserUrlPolicy.resource("https://www.douyin.com/monitor_web/collect/"))
        assertFalse(BrowserUrlPolicy.resource("https://www.douyin.com/passport/web/login/"))
    }

    @Test fun `shared CDN hosts and path prefixes are not blanket permissions`() {
        listOf(
            "https://synthetic.bytecdn.com/a.js", "https://lf-douyin-mobile.bytecdn.com/other/a.js",
            "https://synthetic.bytescm.com/a.js", "https://synthetic.bytegoofy.com/a.js",
            "https://lf-security.bytegoofy.com/obj/security-secsdk/runtime.js/other",
            "https://synthetic.bytetos.com/a.js", "https://privacy.zijieapi.com/other/api/",
        ).forEach { assertFalse(it, BrowserUrlPolicy.resource(it)) }
        assertTrue(BrowserUrlPolicy.resource("https://privacy.zijieapi.com/api/web-cmp/sdk/app.js"))
    }

    @Test fun `allowed resources have no block reason`() {
        listOf(
            "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=123",
            "https://lf-security.bytegoofy.com/obj/security-secsdk/runtime.js",
            "https://synthetic.douyinvod.com/video.mp4?token=opaque",
            "https://privacy.zijieapi.com/api/web-cmp/sdk/app.js",
        ).forEach { assertNull(it, BrowserUrlPolicy.resourceBlockReason(it)) }
    }

    @Test fun `account and telemetry paths have distinct fixed reasons`() {
        listOf(
            "https://www.douyin.com/passport/web/login/",
            "https://www.douyin.com/%6fauth2/authorize",
            "https://www.douyin.com/sso/callback",
            "https://www.douyin.com/accounts/profile",
        ).forEach {
            assertEquals(it, BrowserBlockReason.ACCOUNT, BrowserUrlPolicy.resourceBlockReason(it))
            assertFalse(BrowserUrlPolicy.navigation(it))
        }
        listOf(
            "https://lf3-cdn-tos.bytescm.com/obj/static/log-sdk/collect/sdk.js",
            "https://www.douyin.com/aweme/v1/web/collect/",
            "https://synthetic.bytecdn.cn/%63ollect/data",
            "https://www.douyin.com/monitor_browser/collect/",
        ).forEach { assertEquals(it, BrowserBlockReason.TRACKING, BrowserUrlPolicy.resourceBlockReason(it)) }
    }

    @Test fun `non HTTPS schemes are reported without enabling any new navigation`() {
        listOf(
            "http://www.douyin.com/video/123", "file:///sdcard/private",
            "content://private", "data:text/html,private", "snssdk1128://aweme/detail/123",
            "intent://aweme/detail/123#Intent;scheme=snssdk1128;end",
        ).forEach {
            assertEquals(it, BrowserBlockReason.UNSUPPORTED_SCHEME, BrowserUrlPolicy.resourceBlockReason(it))
            assertFalse(BrowserUrlPolicy.navigation(it))
        }
    }

    @Test fun `malformed credentials and local targets receive unsafe reason`() {
        listOf(
            "", "//www.douyin.com/video/123", "https://@www.douyin.com/video/123",
            "https://secret:secret@douyinstatic.com/a.js", "https://douyinstatic.com:444/a.js",
            "https://%64ouyinstatic.com/a.js", "https://douyinstatic.com./a.js",
            "https://douyinstatic.com..evil.invalid/a.js", "https://evil.invalid\\@douyinstatic.com/a.js",
            "https://127.0.0.1/a.js", "https://[::1]/a.js", "https://localhost/a.js",
            "https://camera.local/a.js", "https://private.internal/a.js",
        ).forEach { assertEquals(it, BrowserBlockReason.UNSAFE_URL, BrowserUrlPolicy.resourceBlockReason(it)) }
    }

    @Test fun `otherwise unreviewed hosts and paths remain unreviewed`() {
        listOf(
            "https://synthetic.bytecdn.com/a.js", "https://lf-douyin-mobile.bytecdn.com/other/a.js",
            "https://douyinstatic.com.evil.invalid/a.js", "https://www.bytedance.com/a.js",
            "https://lf-security.bytegoofy.com/obj/security-secsdk/runtime.js/other",
        ).forEach { assertEquals(it, BrowserBlockReason.UNREVIEWED_RESOURCE, BrowserUrlPolicy.resourceBlockReason(it)) }
    }
}
