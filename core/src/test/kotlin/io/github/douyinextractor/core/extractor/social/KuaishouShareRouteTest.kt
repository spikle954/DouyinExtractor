package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.diagnostics.DiagnosticReport
import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.input.ShareTextParser
import io.github.douyinextractor.core.model.ExtractError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class KuaishouShareRouteTest {
    private val id = "3xucdsfddb9cz4m"
    private val canonical = "https://www.kuaishou.com/short-video/$id"
    private val route = "$canonical?location=app_share"
    private val shell = "<script>window.__APOLLO_STATE__={\"defaultClient\":{}};</script>"
    private val video = "<script>window.__APOLLO_STATE__={\"defaultClient\":{\"VisionVideoDetailPhoto:$id\":{\"id\":\"$id\",\"photoUrl\":\"https://v1.kwaicdn.com/sample.mp4\"}}};</script>"
    @Test fun `only fixed official share marker survives tracking cleanup`() {
        val raw = "$canonical?utm_source=app_share&location=app_share&userId=PRIVATE&shareToken=SECRET"
        assertEquals(route, SocialUrls.normalize(raw))
        assertEquals(route, ShareTextParser().parse("分享 $raw").getOrThrow().url)
        assertEquals(id, SocialUrls.videoId(route)); assertEquals(canonical, SocialUrls.canonical(SocialUrls.KUAISHOU, id))
        assertEquals(canonical, SocialUrls.normalize("$canonical?location=PRIVATE"))
        assertNull(SocialUrls.normalize("$route&location=app_share"))
        assertNull(SocialUrls.normalize("$route&location=PRIVATE"))
    }
    @Test fun `route marker cannot broaden accepted hosts paths identities or platforms`() {
        assertNull(SocialUrls.normalize("https://www.kuaishou.com.evil.test/short-video/$id?location=app_share"))
        assertNull(SocialUrls.normalize("https://www.kuaishou.com/profile/$id?location=app_share"))
        assertNull(SocialUrls.normalize("$route&photoId=3xanotherid1234"))
        assertEquals("https://v.kuaishou.com/Abc123", SocialUrls.normalize("https://v.kuaishou.com/Abc123?location=app_share"))
        val xhs = "https://www.xiaohongshu.com/explore/6411cf99000000001300b6d9"
        assertEquals(xhs, SocialUrls.normalize("$xhs?location=app_share"))
    }
    @Test fun `official mobile redirect route is retained and returns exact target`() = runBlocking {
        val visited = mutableListOf<String>(); val trace = ResolutionDiagnostics()
        val extractor = SocialExtractor { url, _ ->
            visited += url
            when (url) {
                canonical -> SocialResponse(200, shell, contentType = "text/html")
                SocialUrls.kuaishouMobilePage(id) -> SocialResponse(302,
                    location = "$canonical?utm_source=app_share&utm_medium=app_share&utm_campaign=app_share&location=app_share&shareToken=SECRET&userId=PRIVATE")
                route -> SocialResponse(200, video, contentType = "text/html")
                else -> error("Unexpected page")
            }
        }
        val media = extractor.extract(canonical, trace).getOrThrow()
        assertEquals(listOf(canonical, SocialUrls.kuaishouMobilePage(id), route), visited)
        assertEquals(id, media.id); assertEquals(canonical, media.canonicalUrl)
        assertFalse(visited.any { it.contains("utm_") || it.contains("SECRET") || it.contains("PRIVATE") })
        assertTrue(trace.notes().any { it.contains("已保留官方分享路由标记") })
    }
    @Test fun `direct qualified share page keeps marker through resolve and extract`() = runBlocking {
        val visited = mutableListOf<String>()
        val extractor = SocialExtractor { url, _ -> visited += url; SocialResponse(200, if (url == route) video else shell) }
        val trace = ResolutionDiagnostics()
        val link = extractor.resolve(route, trace).getOrThrow()
        assertEquals(canonical, link.canonicalUrl); assertEquals(route, link.extractionUrl); assertTrue(visited.isEmpty())
        assertEquals(id, extractor.extract(link.extractionUrl!!, trace).getOrThrow().id)
        assertEquals(listOf(route), visited)
    }
    @Test fun `route diagnostics hide all queries even while request uses the marker`() = runBlocking {
        val trace = ResolutionDiagnostics()
        val extractor = SocialExtractor { _, _ -> SocialResponse(200, video) }
        val link = extractor.resolve(route, trace).getOrThrow()
        val report = DiagnosticReport.format("$route&shareToken=SECRET", route, link, null, trace)
        assertFalse(report.contains("location=")); assertFalse(report.contains("SECRET")); assertFalse(report.contains("app_share"))
        assertTrue(report.contains(canonical))
    }
    @Test fun `qualified redirect never accepts another work or unsafe route`() = runBlocking {
        for (next in listOf("https://www.kuaishou.com/short-video/3xanotherid1234?location=app_share",
            "http://www.kuaishou.com/short-video/$id?location=app_share", "$route&location=PRIVATE")) {
            var calls = 0
            val extractor = SocialExtractor { _, _ -> calls++; SocialResponse(302, location = next) }
            assertTrue(extractor.extract(canonical).isFailure); assertEquals(1, calls)
        }
    }
}
