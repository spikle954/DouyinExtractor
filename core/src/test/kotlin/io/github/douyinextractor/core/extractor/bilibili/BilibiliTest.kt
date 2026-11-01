package io.github.douyinextractor.core.extractor.bilibili

import io.github.douyinextractor.core.diagnostics.*
import io.github.douyinextractor.core.download.DownloadNames
import io.github.douyinextractor.core.input.ShareTextParser
import io.github.douyinextractor.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BilibiliTest {
    private val id = "BV1GJ411x7h7"
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun view() = obj("""{"bvid":"$id","aid":123,"title":"Synthetic","owner":{"name":"Author"},"pic":"http://i0.hdslb.com/test.jpg","rights":{"pay":0},"pages":[{"page":1,"cid":100,"duration":10,"part":"one"},{"page":2,"cid":200,"duration":20,"part":"two"}]}""")
    private fun play() = obj("""{"quality":64,"format":"mp4720","video_codecid":7,"timelength":20000,"durl":[{"length":20000,"size":456,"url":"https://outside.example/video.mp4?secret=never","backup_url":["https://upos.bilivideo.com/video.mp4?upsig=a%2Fb%3D&x=1"]}]}""")
    private fun failure(error: ExtractError, block: () -> Unit) {
        try { block(); fail("Expected $error") } catch (e: ExtractError) { assertEquals(error, e) }
    }

    @Test fun `share parser accepts BV AV short and part URLs and drops tracking`() {
        assertEquals(BilibiliUrls.canonical(id), ShareTextParser().parse(id).getOrThrow().url)
        assertEquals("https://www.bilibili.com/video/av123?p=2", ShareTextParser().parse("分享 http://m.bilibili.com/video/av123/?p=2&share_session=secret").getOrThrow().url)
        assertEquals("https://b23.tv/Abc123", ShareTextParser().parse("【合成测试-哔哩哔哩】 https://b23.tv/Abc123").getOrThrow().url)
        assertEquals(BilibiliUrls.canonical(id), ShareTextParser().parse("www.bilibili.com/video/$id").getOrThrow().url)
    }
    @Test fun `ambiguous cross platform shares and embedded URLs cannot select a video`() {
        listOf("https://b23.tv/Abc https://www.douyin.com/video/123", "https://evil.example/?next=https://b23.tv/Abc",
            "https://b23.tv/Abc https://b23.tv/Def").forEach { assertTrue(ShareTextParser().parse(it).isFailure) }
    }
    @Test fun `unsupported routes authority and ambiguous part are refused`() {
        listOf("https://www.bilibili.com.evil.example/video/$id", "https://user@www.bilibili.com/video/$id",
            "https://www.bilibili.com:444/video/$id", "https://www.bilibili.com./video/$id",
            "https://www.bilibili.com/a/../video/$id", "https://www.bilibili.com/video/$id?p=0", "https://www.bilibili.com/video/$id?p=1&p=2",
            "https://www.bilibili.com/video/$id?p=abc", "https://live.bilibili.com/123", "https://www.bilibili.com/bangumi/play/ep123",
            "https://www.bilibili.com/video/$id/extra", "https://b23.tv/a/b").forEach { assertNull(it, BilibiliUrls.normalize(it)) }
    }
    @Test fun `media policy keeps query exact but excludes off platform hosts and separated tracks`() {
        val url = "https://upos.bilivideo.com/test.mp4?upsig=a%2Fb%3D&x=1"
        assertEquals(url, BilibiliUrls.media(url))
        listOf("http://upos.bilivideo.com/a.mp4", "https://bilivideo.com.evil.example/a.mp4", "https://user@upos.bilivideo.com/a.mp4",
            "https://upos.bilivideo.com:444/a.mp4", "https://upos.bilivideo.com/a.m4s", "https://127.0.0.1/a.mp4",
            "https://upos.bilivideo.com./a.mp4").forEach { assertNull(BilibiliUrls.media(it)) }
        assertEquals("https://i0.hdslb.com/a.jpg", BilibiliUrls.cover("http://i0.hdslb.com/a.jpg"))
        assertNull(BilibiliUrls.cover("http://evil.example/a.jpg"))
    }
    @Test fun `maps selected CID validates identity and keeps only official MP4 backup`() {
        val detail = BilibiliMapping.detail(view(), id, 2)
        assertEquals(200L, detail.cid)
        val result = BilibiliMapping.media(detail, play())
        assertEquals(2, result.selectedPage); assertEquals(2, result.pages.size)
        assertTrue(result.title!!.contains("P2 two"))
        assertEquals("720P", result.formats.single().qualityLabel)
        assertTrue(result.formats.single().url.contains("upsig=a%2Fb%3D&x=1"))
        assertNull(result.formats.single().width); assertNull(result.formats.single().height)
        assertEquals("h264", result.formats.single().codec)
        assertEquals(id, result.id)
        assertEquals("av123", BilibiliMapping.detail(view(), "av123", 1).target)
        failure(ExtractError.UnsupportedPage) { BilibiliMapping.detail(view(), "av456", 1) }
        failure(ExtractError.PartNotFound) { BilibiliMapping.detail(view(), id, 3) }
    }
    @Test fun `paid interactive preview and encrypted resources are explicit failures`() {
        val base = view()
        for (flag in listOf("pay", "ugc_pay", "ugc_pay_preview", "arc_pay", "is_stein_gate")) {
            failure(ExtractError.RestrictedContent) { BilibiliMapping.detail(JsonObject(base + ("rights" to obj("""{"$flag":1}"""))), id, 1) }
        }
        val detail = BilibiliMapping.detail(view(), id, 2)
        for (flag in listOf("is_preview", "is_drm", "is_encrypted")) {
            failure(ExtractError.RestrictedContent) { BilibiliMapping.media(detail, JsonObject(play() + (flag to JsonPrimitive(true)))) }
        }
        val shortened = obj("""{"quality":64,"format":"mp4720","durl":[{"length":5000,"url":"https://upos.bilivideo.com/test.mp4"}]}""")
        failure(ExtractError.RestrictedContent) { BilibiliMapping.media(detail, shortened) }
    }
    @Test fun `DASH multi segment and wrong CID never become a complete MP4`() {
        val detail = BilibiliMapping.detail(view(), id, 2)
        failure(ExtractError.UnsupportedMedia) { BilibiliMapping.media(detail, obj("""{"format":"dash","dash":{"video":[]}}""")) }
        failure(ExtractError.UnsupportedMedia) { BilibiliMapping.media(detail, JsonObject(play() + ("durl" to JsonArray(List(2) { play()["durl"]!!.jsonArray[0] })))) }
        failure(ExtractError.UnsupportedPage) { BilibiliMapping.media(detail, JsonObject(play() + ("cid" to JsonPrimitive(999)))) }
    }
    @Test fun `duplicate page identifiers cannot select an ambiguous part`() {
        val pages = view()["pages"]!!.jsonArray
        failure(ExtractError.UnsupportedPage) { BilibiliMapping.detail(JsonObject(view() + ("pages" to JsonArray(listOf(pages[0], pages[0])))), id, 1) }
    }
    @Test fun `public collection metadata does not imply paid bangumi`() {
        assertEquals(id, BilibiliMapping.detail(JsonObject(view() + ("season_id" to JsonPrimitive(456))), id, 1).target)
    }
    @Test fun `observed hdmp4 format is a complete progressive video not a DASH track`() {
        val detail = BilibiliMapping.detail(view(), id, 2)
        assertEquals(1, BilibiliMapping.media(detail, JsonObject(play() + ("format" to JsonPrimitive("hdmp4")))).formats.size)
    }
    @Test fun `short link redirects bind platform identity without following an external target`() = runBlocking {
        var calls = 0
        val extractor = BilibiliExtractor { _, body -> calls++; assertFalse(body); BilibiliResponse(302, location = "https://www.bilibili.com/video/$id?p=2&secret=hidden") }
        val resolved = extractor.resolve("https://b23.tv/Abc", ResolutionDiagnostics()).getOrThrow()
        assertEquals(1, calls); assertEquals("Bilibili", resolved.platform)
        assertEquals(BilibiliUrls.canonical(id, 2), resolved.canonicalUrl)
        val bad = BilibiliExtractor { _, _ -> BilibiliResponse(302, location = "https://evil.example/video/$id") }
        assertEquals(ExtractError.RedirectFailed, bad.resolve("https://b23.tv/Abc", ResolutionDiagnostics()).exceptionOrNull())
    }
    @Test fun `extract requests selected CID and labels actual returned quality not requested quality`() = runBlocking {
        val calls = mutableListOf<String>()
        val extractor = BilibiliExtractor { url, body ->
            assertTrue(body); calls += url
            BilibiliResponse(200, """{"code":0,"data":${if (calls.size == 1) view() else play()}}""")
        }
        val result = extractor.extract(BilibiliUrls.canonical(id, 2)).getOrThrow()
        assertTrue(calls.last().contains("cid=200")); assertTrue(calls.last().contains("qn=80"))
        assertEquals("720P", result.formats.single().qualityLabel)
        assertEquals(2, result.selectedPage)
    }
    @Test fun `API failures cancellation and excessive nesting never return success`() = runBlocking {
        val denied = BilibiliExtractor { _, _ -> BilibiliResponse(200, """{"code":-412}""") }
        assertEquals(ExtractError.PlatformRejected, denied.extract(BilibiliUrls.canonical(id)).exceptionOrNull())
        val deep = BilibiliExtractor { _, _ -> BilibiliResponse(200, "[".repeat(70) + "0" + "]".repeat(70)) }
        assertEquals(ExtractError.UnsupportedPage, deep.extract(BilibiliUrls.canonical(id)).exceptionOrNull())
        val cancelled = BilibiliExtractor { _, _ -> throw CancellationException() }
        try { cancelled.extract(BilibiliUrls.canonical(id)); fail("Cancellation swallowed") } catch (_: CancellationException) { }
    }
    @Test fun `Bili diagnostics remove tracking and signed URLs while keeping public identity`() {
        val text = DiagnosticReport.redact("https://www.bilibili.com/video/$id?p=2&token=secret b23.tv/Abc?share_session=private https://upos.bilivideo.com/a.mp4?upsig=signature")
        assertTrue(text.contains(id)); listOf("secret", "private", "signature").forEach { assertFalse(text.contains(it)) }
    }
    @Test fun `part filenames are distinct and reject path injection`() {
        val first = DownloadNames.fileName("author", "title", "${id}_P1")
        val second = DownloadNames.fileName("author", "title", "${id}_P2")
        assertNotEquals(first, second)
        for (bad in listOf("${id}_P0", "${id}/../../bad", "${id}_P1_P2")) {
            try { DownloadNames.fileName(null, null, bad); fail("Accepted $bad") } catch (_: IllegalArgumentException) { }
        }
    }
}
