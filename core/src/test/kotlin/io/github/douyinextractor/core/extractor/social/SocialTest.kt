package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.diagnostics.*
import io.github.douyinextractor.core.input.ShareTextParser
import io.github.douyinextractor.core.download.DownloadNames
import io.github.douyinextractor.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SocialTest {
    private val ks = "3xgp7zntw6w2zqq"
    private val xhs = "6411cf99000000001300b6d9"
    private val ksUrl = "https://www.kuaishou.com/short-video/$ks"
    private val xhsUrl = "https://www.xiaohongshu.com/explore/$xhs"
    private val mp4 = "https://v1.kwaicdn.com/upic/example.mp4?sig=A%2FB&x=1"
    private val xhsMp4 = "https://sns-video-bd.xhscdn.com/stream/example.mp4?sign=A%2FB&x=1"
    private fun page(data: String, marker: String = "__APOLLO_STATE__") = "<html><body><script>window.$marker=$data;</script></body></html>"
    private fun ksState(photo: String = "\"id\":\"$ks\",\"photoUrl\":\"$mp4\"", key: String = ks) = "{\"defaultClient\":{\"VisionVideoDetailPhoto:$key\":{$photo}}}"
    private fun xhsState(note: String = "\"noteId\":\"$xhs\",\"type\":\"video\",\"video\":{\"media\":{\"stream\":{\"h264\":[{\"masterUrl\":\"$xhsMp4\",\"width\":1080,\"height\":1920,\"duration\":5433}]}}}", key: String = xhs) =
        "{\"note\":{\"noteDetailMap\":{\"$key\":{\"note\":{$note}}}}}"
    private fun failure(html: String, platform: String = SocialUrls.KUAISHOU, id: String = ks) = try {
        SocialPageMapping.parse(html, platform, id); null
    } catch (e: ExtractError) { e }

    @Test fun `complete share text and scheme-less short links are recognized`() {
        assertEquals("https://v.kuaishou.com/7Eo9eugR", ShareTextParser().parse("https://v.kuaishou.com/7Eo9eugR 打了个出租车，打开快手！").getOrThrow().url)
        assertEquals("https://xhslink.cn/o/abcDEF123", ShareTextParser().parse("文案 xhslink.cn/o/abcDEF123").getOrThrow().url)
        assertEquals("https://xhslink.com/a/Ab12", SocialUrls.normalize("http://xhslink.com/a/Ab12"))
    }
    @Test fun `two different platforms and URLs embedded in hostile URIs never select silently`() {
        assertTrue(ShareTextParser().parse("$ksUrl $xhsUrl").isFailure)
        assertTrue(ShareTextParser().parse("https://evil.test/?url=\"$ksUrl\"").isFailure)
        assertTrue(ShareTextParser().parse("https://evil.test/$xhsUrl").isFailure)
    }
    @Test fun `only precise platform hosts ports paths and identities are allowed`() {
        for (url in listOf("https://www.kuaishou.com.evil.test/short-video/$ks", "https://user@www.kuaishou.com/short-video/$ks",
            "https://www.kuaishou.com:444/short-video/$ks", "https://www.kuaishou.com./short-video/$ks", "https://www.kuaishou.com/profile/$ks",
            "https://www.kuaishou.com/x/../short-video/$ks", "https://www.kuaishou.com/short-video/%33xgp7zntw6w2zqq",
            "https://www.xiaohongshu.com/user/profile/$xhs", "https://127.0.0.1/explore/$xhs")) assertNull(url, SocialUrls.normalize(url))
        assertEquals(ks, SocialUrls.videoId("https://v.m.chenzhongtech.com/fw/photo/$ks?photoId=$ks"))
        assertNull(SocialUrls.normalize("https://v.m.chenzhongtech.com/fw/photo/$ks?photoId=3xotherphoto1234"))
        assertNull(SocialUrls.videoId("https://v.kuaishou.com/ABCdef?photoId=$ks"))
    }
    @Test fun `public share access parameters survive while tracking is removed`() {
        val result = SocialUrls.normalize("$xhsUrl?xsec_token=A%2FB%3D&xsec_source=app_share&share_id=TRACKING")!!
        assertTrue(result.contains("xsec_token=A%2FB%3D")); assertTrue(result.contains("xsec_source=app_share")); assertFalse(result.contains("TRACKING"))
        assertNull(SocialUrls.normalize("$xhsUrl?xsec_token=A&xsec_token=B"))
        assertEquals(xhs, SocialUrls.videoId(result))
        assertEquals(xhsUrl, SocialUrls.canonical(SocialUrls.XIAOHONGSHU, xhs))
    }
    @Test fun `media policy keeps exact signatures but rejects foreign and segmented resources`() {
        assertEquals(mp4, SocialUrls.media(mp4, SocialUrls.KUAISHOU))
        assertEquals(xhsMp4, SocialUrls.media(xhsMp4, SocialUrls.XIAOHONGSHU))
        assertNotNull(SocialUrls.media("https://k0u.example.djvod.ndcimgs.com/x.mp4", SocialUrls.KUAISHOU))
        for (url in listOf("http://v1.kwaicdn.com/v.mp4", "https://evil.test/v.mp4", "https://v1.kwaicdn.com.evil.test/v.mp4", "https://v1.kwaicdn.com/a.m3u8", "https://v1.kwaicdn.com/a.mp4/../image.jpg")) assertNull(SocialUrls.media(url, SocialUrls.KUAISHOU))
        for (ext in listOf("m3u8", "mpd", "m4s", "aac", "m4a", "jpg", "flv")) assertNull(SocialUrls.media("https://sns-video-bd.xhscdn.com/file.$ext", SocialUrls.XIAOHONGSHU))
        assertNull(SocialUrls.media(mp4, SocialUrls.XIAOHONGSHU))
    }
    @Test fun `cover upgrade remains limited to each platform`() {
        assertEquals("https://p3.a.yximgs.com/a.jpg", SocialUrls.cover("http://p3.a.yximgs.com/a.jpg", SocialUrls.KUAISHOU))
        assertNull(SocialUrls.cover("https://evil.test/a.jpg", SocialUrls.XIAOHONGSHU))
    }
    @Test fun `target photo and note must match even when other recommendations provide media`() {
        assertEquals(ExtractError.UnsupportedPage, failure(page(ksState(key="3xotherphoto1234"))))
        assertEquals(ExtractError.UnsupportedPage, failure(page(ksState("\"id\":\"3xotherphoto1234\",\"photoUrl\":\"$mp4\""))))
        assertEquals(ExtractError.UnsupportedPage, failure(page(xhsState(key="6411cf99000000001300b6d8"), "__INITIAL_STATE__"), SocialUrls.XIAOHONGSHU, xhs))
        assertEquals(ExtractError.UnsupportedPage, failure(page(xhsState("\"noteId\":\"6411cf99000000001300b6d8\",\"type\":\"video\""), "__INITIAL_STATE__"), SocialUrls.XIAOHONGSHU, xhs))
    }
    @Test fun `kuaishou Apollo references provide dimensions and only the bound author`() {
        val state = """{"defaultClient":{"VisionVideoDetailPhoto:$ks":{"id":"$ks","duration":5433,"manifest":{"type":"id","id":"manifest"}},"manifest":{"adaptationSet":[{"type":"id","id":"set"}]},"set":{"representation":[{"type":"id","id":"rep"}]},"rep":{"url":"$mp4","width":720,"height":1280,"avgBitrate":1753},"detail":{"photo":{"type":"id","id":"VisionVideoDetailPhoto:$ks"},"author":{"type":"id","id":"author"}},"unrelatedAuthor":{"name":"wrong"},"author":{"name":"correct"}}}"""
        val media = SocialPageMapping.parse(page(state), SocialUrls.KUAISHOU, ks)
        assertEquals("correct", media.author); assertEquals(5433L, media.durationMs)
        assertEquals(720, media.formats.single().width); assertEquals(1_753_000L, media.formats.single().bitrate)
        assertEquals(mp4, media.formats.single().url)
    }
    @Test fun `circular Apollo references and missing target resources are not accepted`() {
        val state=ksState("\"id\":\"$ks\",\"manifest\":{\"type\":\"id\",\"id\":\"VisionVideoDetailPhoto:$ks\"}")
        assertEquals(ExtractError.UnsupportedPage, failure(page(state)))
    }
    @Test fun `xiaohongshu typed stream metadata and backups are supported`() {
        val media=SocialPageMapping.parse(page(xhsState(), "__INITIAL_STATE__"), SocialUrls.XIAOHONGSHU, xhs)
        assertEquals(SocialUrls.XIAOHONGSHU, media.platform); assertEquals(1080, media.formats.single().width)
        assertEquals(5433L, media.durationMs); assertEquals(xhsMp4, media.formats.single().url)
    }
    @Test fun `mobile share state has an exact note binding and CDN HTTP is upgraded before downloading`() {
        val note="\"noteId\":\"$xhs\",\"type\":\"video\",\"desc\":\"sample\",\"video\":{\"media\":{\"stream\":{\"h264\":[{\"masterUrl\":\"${xhsMp4.replace("https://","http://")}\",\"format\":\"mp4\"}]}}}"
        val state="{\"noteData\":{\"data\":{\"noteData\":{$note}}}}"
        val info=SocialPageMapping.parse(page(state,"__INITIAL_STATE__"),SocialUrls.XIAOHONGSHU,xhs)
        assertEquals("sample",info.title);assertEquals(xhsMp4,info.formats.single().url)
        assertNull(SocialUrls.media(xhsMp4.replace("https://","http://"),SocialUrls.XIAOHONGSHU))
        assertEquals(ExtractError.UnsupportedPage,failure(page(state.replace(xhs,"6411cf99000000001300b6d8"),"__INITIAL_STATE__"),SocialUrls.XIAOHONGSHU,xhs))
    }
    @Test fun `DRM state and segmented formats cannot be exposed as progressive videos`() {
        val drm=xhsState().replace("\"stream\":","\"video\":{\"drmType\":1},\"stream\":")
        assertEquals(ExtractError.RestrictedContent,failure(page(drm,"__INITIAL_STATE__"),SocialUrls.XIAOHONGSHU,xhs))
        val hls=xhsState().replace("\"masterUrl\":","\"format\":\"hls\",\"masterUrl\":")
        assertEquals(ExtractError.UnsupportedPage,failure(page(hls,"__INITIAL_STATE__"),SocialUrls.XIAOHONGSHU,xhs))
    }
    @Test fun `image notes and atlases are identified without pretending images are videos`() {
        assertEquals(ExtractError.NotVideo, failure(page(ksState("\"id\":\"$ks\",\"photoType\":\"VERTICAL_ATLAS\",\"photoUrl\":\"$mp4\""))))
        assertEquals(ExtractError.NotVideo, failure(page(xhsState("\"noteId\":\"$xhs\",\"type\":\"normal\""), "__INITIAL_STATE__"), SocialUrls.XIAOHONGSHU, xhs))
        assertEquals(ExtractError.UnsupportedPage, failure("<meta property='og:video' content='$mp4'>"))
    }
    @Test fun `bare undefined is handled without modifying strings or running page code`() {
        val state=ksState("\"id\":\"$ks\",\"photoUrl\":\"$mp4\",\"caption\":\"undefined\",\"unused\":undefined")
        assertEquals("undefined", SocialPageMapping.parse(page(state),SocialUrls.KUAISHOU,ks).title)
        assertEquals(ExtractError.UnsupportedPage,failure("<script>const text='window.__APOLLO_STATE__=${ksState()}';</script>"))
        assertEquals(ExtractError.UnsupportedPage,failure("<script>/* window.__APOLLO_STATE__=${ksState()}; */</script>"))
        assertEquals(ExtractError.UnsupportedPage,failure("<script>window.__APOLLO_STATE__=fetch('https://evil.test')</script>"))
    }
    @Test fun `oversized and over-nested page data is bounded`() {
        assertThrows(IllegalArgumentException::class.java) { SocialPageMapping.parse("x".repeat(2*1024*1024+1),SocialUrls.KUAISHOU,ks) }
        assertEquals(ExtractError.UnsupportedPage,failure(page("[".repeat(70)+"0"+"]".repeat(70))))
    }
    @Test fun `short redirects follow only the same platform and keep canonical free of tokens`() = runBlocking {
        val visited=mutableListOf<String>()
        val extractor=SocialExtractor { url, _ -> visited+=url; SocialResponse(302, location="$xhsUrl?xsec_token=SECRET&xsec_source=app_share") }
        val link=extractor.resolve("https://xhslink.com/a/Ab12",ResolutionDiagnostics()).getOrThrow()
        assertEquals(xhsUrl,link.canonicalUrl);assertTrue(link.extractionUrl!!.contains("SECRET"));assertEquals(1,visited.size)
        val report=DiagnosticReport.format(link.originalUrl,link.originalUrl,link,null,ResolutionDiagnostics())
        assertFalse(report.contains("SECRET"));assertFalse(report.contains("xsec_token"))
    }
    @Test fun `foreign redirects downgrade cross-platform target changes and loops are rejected`() = runBlocking {
        for(next in listOf("https://evil.test/v", "$xhsUrl", "http://www.kuaishou.com/short-video/$ks")) {
            var calls=0;val extractor=SocialExtractor { _,_->calls++;SocialResponse(302,location=next) }
            assertEquals(ExtractError.RedirectFailed,extractor.resolve("https://v.kuaishou.com/Ab12",ResolutionDiagnostics()).exceptionOrNull());assertEquals(1,calls)
        }
        val loop=SocialExtractor { url,_->SocialResponse(302,location=url) }
        assertEquals(ExtractError.RedirectFailed,loop.resolve("https://v.kuaishou.com/Ab12",ResolutionDiagnostics()).exceptionOrNull())
        val changed=SocialExtractor { _,_->SocialResponse(302,location="https://www.kuaishou.com/short-video/3xotherphoto1234") }
        assertEquals(ExtractError.UnsupportedPage,changed.extract(ksUrl).exceptionOrNull())
    }
    @Test fun `platform refusal and inaccessible share pages give actionable errors`() = runBlocking {
        assertEquals(ExtractError.PlatformRejected,SocialExtractor { _,_->SocialResponse(403) }.extract(ksUrl).exceptionOrNull())
        assertEquals(ExtractError.ShareUnavailable,SocialExtractor { _,_->SocialResponse(302,location="https://www.xiaohongshu.com/404?token=SECRET") }.extract(xhsUrl).exceptionOrNull())
        assertEquals(ExtractError.LoginRequired,SocialExtractor { _,_->SocialResponse(401) }.extract(xhsUrl).exceptionOrNull())
    }
    @Test fun `cancellation propagates out of resolve and extract`() = runBlocking {
        val extractor=SocialExtractor { _,_->throw CancellationException("cancel") }
        try {extractor.resolve("https://v.kuaishou.com/Ab12",ResolutionDiagnostics());fail()}catch(_:CancellationException){}
        try {extractor.extract(ksUrl);fail()}catch(_:CancellationException){}
    }
    @Test fun `parser sees only HTML and anonymous extraction yields target identity`() = runBlocking {
        assertEquals(ExtractError.UnsupportedPage,SocialExtractor { _,_->SocialResponse(200,page(ksState()),contentType="application/json") }.extract(ksUrl).exceptionOrNull())
        val info=SocialExtractor { _,_->SocialResponse(200,page(ksState()),contentType="text/html; charset=utf-8") }.extract(ksUrl).getOrThrow()
        assertEquals(ks,info.id);assertEquals(ksUrl,info.canonicalUrl);assertEquals(mp4,info.formats.single().url)
    }
    @Test fun `new file names carry platform namespaces and invalid IDs cannot become paths`() {
        assertTrue(DownloadNames.fileName("author","title","KS_$ks").endsWith("_KS_$ks.mp4"))
        assertTrue(DownloadNames.fileName("author","title","XHS_$xhs").endsWith("_XHS_$xhs.mp4"))
        assertThrows(IllegalArgumentException::class.java) {DownloadNames.fileName(null,null,"KS_../../escape")}
    }
}
