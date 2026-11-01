package io.github.douyinextractor.core.browser

import io.github.douyinextractor.core.model.ExtractError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** All URLs and data are synthetic; these tests never request a real video. */
class BrowserSnapshotParserTest {
    private val parser = BrowserSnapshotParser()
    private val id = "7683370277783000950"
    private val page = "https://www.iesdouyin.com/share/video/$id/"
    private val mp4 = "https://synthetic.douyinvod.com/sample.mp4?token=private"

    @Test fun `decodes Android evaluateJavascript quoted JSON once`() {
        val result = parser.parseEvaluationResult(callback(snapshot(videos = listOf(video()))), id)
        val media = requireNotNull(result.media)
        assertEquals(id, media.id)
        assertEquals(mp4, media.formats.single().url)
        assertEquals(1080, media.formats.single().width)
        assertEquals(1920, media.formats.single().height)
        assertEquals(12_500L, media.durationMs)
        assertNull(media.formats.single().watermarked)
        assertFalse(result.reason.contains("private"))
        assertTrue(result.reason.contains("可见视频：1"))
    }

    @Test fun `accepts direct JSON document for internal callers`() {
        assertNotNull(parser.parseEvaluationResult(snapshot(videos = listOf(video())).toString(), id).media)
    }

    @Test fun `rejects repeated string wrapping rather than recursively decoding`() {
        val doubleWrapped = JsonPrimitive(callback(snapshot(videos = listOf(video())))).toString()
        assertTrue(parser.parseEvaluationResult(doubleWrapped, id).error is ExtractError.ParserError)
    }

    @Test fun `extracts target-bound hydrated state without DOM video`() {
        val state = """{"loaderData":{"video":{"aweme_id":"$id","desc":"Synthetic title","video":{"play_addr":{"url_list":["$mp4"]}}}}}"""
        val result = parse(snapshot(states = listOf(state("_ROUTER_DATA", state))))
        assertEquals("Synthetic title", result.media?.title)
        assertTrue(result.media?.formats?.single()?.source?.startsWith("BrowserState:_ROUTER_DATA") == true)
    }

    @Test fun `handles percent encoded render state and plus without rewriting URL`() {
        val state = """{"aweme_id":"$id","desc":"A+B","video":{"play_addr":{"url_list":["$mp4"]}}}"""
        val encoded = java.net.URLEncoder.encode(state, "UTF-8").replace("+", "%20")
        assertEquals("A+B", parse(snapshot(states = listOf(state("RENDER_DATA", encoded)))).media?.title)
    }

    @Test fun `observed empty router is not media success`() {
        val empty = """{"loaderData":{"video_(id)/page":{"itemId":"$id","videoInfoRes":null}}}"""
        val result = parse(snapshot(states = listOf(state("_ROUTER_DATA", empty))))
        assertNull(result.media)
        assertEquals(ExtractError.UnsupportedPage, result.error)
        assertTrue(result.reason.contains("状态块：1"))
    }

    @Test fun `does not import arbitrary globals as video state`() {
        val state = """{"aweme_id":"$id","video":{"play_addr":{"url_list":["$mp4"]}}}"""
        assertNull(parse(snapshot(states = listOf(state("evil", state)))).media)
    }

    @Test fun `other target state cannot be used`() {
        val state = """{"aweme_id":"123","video":{"play_addr":{"url_list":["$mp4"]}}}"""
        assertNull(parse(snapshot(states = listOf(state("SIGI_STATE", state)))).media)
    }

    @Test fun `wrong browser page rejects even target-bound state`() {
        val result = parse(snapshot(videos = listOf(video()), pageUrl = "https://www.douyin.com/video/123"))
        assertNull(result.media)
        assertNull(result.pageUrl)
        assertEquals(ExtractError.UnsupportedPage, result.error)
    }

    @Test fun `lookalike page cannot authorize capture`() {
        val result = parse(snapshot(videos = listOf(video()), pageUrl = "https://www.douyin.com.evil.invalid/video/$id"))
        assertNull(result.media)
    }

    @Test fun `canonical disagreement rejects target result`() {
        assertNull(parse(snapshot(videos = listOf(video()), extra = mapOf("canonical" to JsonPrimitive("https://www.douyin.com/video/123")))).media)
    }

    @Test fun `multiple canonical declarations reject target result`() {
        assertNull(parse(snapshot(videos = listOf(video()), extra = mapOf("canonicalConflict" to JsonPrimitive(true)))).media)
    }

    @Test fun `sanitizes page URL query in returned diagnostics`() {
        val result = parse(snapshot(pageUrl = "$page?private=secret"))
        assertEquals(page, result.pageUrl)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun `blob URL reports unsupported playback without promoting resource hints`() {
        val result = parse(snapshot(videos = listOf(video("blob:https://www.douyin.com/abc")), extra = mapOf(
            "resources" to JsonArray(listOf(JsonPrimitive(mp4))),
        )))
        assertNull(result.media)
        assertTrue(result.reason.contains("blob：1"))
        assertTrue(result.attempts.any { "blob 播放地址" in it.reason })
    }

    @Test fun `source attribute alone is not current loaded media`() {
        assertNull(parse(snapshot(videos = listOf(video("").with("src", mp4)))).media)
    }

    @Test fun `two unbound visible videos are ambiguous`() {
        assertNull(parse(snapshot(videos = listOf(video(), video("https://synthetic.douyinvod.com/other.mp4")))).media)
    }

    @Test fun `visible recommendation cannot replace a hidden target video`() {
        val videos = listOf(video().with("visible", false).with("targetId", id),
            video("https://synthetic.douyinvod.com/recommendation.mp4"))
        assertNull(parse(snapshot(videos = videos)).media)
    }

    @Test fun `explicit target binding permits media amidst another visible video`() {
        val result = parse(snapshot(videos = listOf(video().with("targetId", id), video("https://synthetic.douyinvod.com/other.mp4"))))
        assertEquals(mp4, result.media?.formats?.single()?.url)
    }

    @Test fun `single visible video explicitly bound to another ID is rejected`() {
        assertNull(parse(snapshot(videos = listOf(video().with("targetId", "123")))).media)
    }

    @Test fun `conflicting ancestor video IDs are rejected`() {
        assertNull(parse(snapshot(videos = listOf(video().with("identityConflict", true)))).media)
    }

    @Test fun `hidden video is not a runtime result`() {
        assertNull(parse(snapshot(videos = listOf(video().with("visible", false)))).media)
    }

    @Test fun `layout dimensions cannot replace loaded intrinsic dimensions`() {
        assertNull(parse(snapshot(videos = listOf(video().with("width", 0)))).media)
        assertNull(parse(snapshot(videos = listOf(video().with("readyState", 0)))).media)
    }

    @Test fun `encrypted video is excluded including from static HTML fallback`() {
        val html = """<link rel="canonical" href="$page"><video src="$mp4"></video>"""
        assertNull(parse(snapshot(videos = listOf(video().with("encrypted", true)), extra = mapOf("html" to JsonPrimitive(html)))).media)
    }

    @Test fun `HLS audio and unsafe origin URLs are excluded`() {
        listOf("https://synthetic.douyinvod.com/media.m3u8", "https://synthetic.douyinvod.com/sound.mp3",
            "https://evil.invalid/video.mp4", "http://synthetic.douyinvod.com/video.mp4",
            "data:video/mp4,AAAA").forEach { assertNull(it, parse(snapshot(videos = listOf(video(it)))).media) }
    }

    @Test fun `explicit non MP4 MIME blocks misleading filename`() {
        assertNull(parse(snapshot(videos = listOf(video().with("mimeType", "application/vnd.apple.mpegurl")))).media)
    }

    @Test fun `extensionless DOM resource requires MP4 declaration`() {
        val extensionless = "https://synthetic.douyinvod.com/video/data/abc/"
        assertNull(parse(snapshot(videos = listOf(video(extensionless)))).media)
        assertNotNull(parse(snapshot(videos = listOf(video(extensionless).with("mimeType", "video/mp4")))).media)
        assertNotNull(parse(snapshot(videos = listOf(video("$extensionless?mime_type=video_mp4")))).media)
    }

    @Test fun `too many observed videos cannot use a truncated ambiguous set`() {
        assertNull(parse(snapshot(videos = listOf(video()), extra = mapOf("videoCount" to JsonPrimitive(33)))).media)
    }

    @Test fun `verification requires visible blocker evidence and yields to found media`() {
        val extra = mapOf("blocker" to obj("verification" to true))
        assertEquals(ExtractError.VerificationRequired, parse(snapshot(extra = extra)).error)
        assertNull(parse(snapshot(videos = listOf(video()), extra = extra)).error)
        assertEquals(ExtractError.UnsupportedPage, parse(snapshot(extra = mapOf("html" to JsonPrimitive("<script src='captcha.js'></script>")))).error)
    }

    @Test fun `login blocker produces typed login failure`() {
        assertEquals(ExtractError.LoginRequired, parse(snapshot(extra = mapOf("blocker" to obj("login" to true)))).error)
    }

    @Test fun `observed webpage error produces typed failure while retaining snapshot observations`() {
        val empty = """{"loaderData":{"video_(id)/page":{"itemId":"$id","videoInfoRes":null}}}"""
        val result = parse(snapshot(states = listOf(state("_ROUTER_DATA", empty)),
            extra = mapOf("blocker" to obj("webPlaybackUnavailable" to true))))
        assertNull(result.media)
        assertEquals(ExtractError.WebPlaybackUnavailable, result.error)
        assertTrue(result.reason.contains("抱歉出错了／请尝试在抖音内观看"))
        assertTrue(result.reason.contains("状态块：1"))
        assertTrue(result.reason.contains("可见视频：0"))
        assertTrue(result.attempts.any { it.parser == "BrowserState:_ROUTER_DATA" })
        assertFalse(result.reason.contains("删除"))
        assertFalse(result.reason.contains("私密"))
    }

    @Test fun `verified runtime video wins over webpage error label`() {
        val result = parse(snapshot(videos = listOf(video()),
            extra = mapOf("blocker" to obj("webPlaybackUnavailable" to true))))
        assertNotNull(result.media)
        assertNull(result.error)
    }

    @Test fun `target page state wins over webpage error label`() {
        val state = """{"aweme_id":"$id","video":{"play_addr":{"url_list":["$mp4"]}}}"""
        val result = parse(snapshot(states = listOf(state("_ROUTER_DATA", state)),
            extra = mapOf("blocker" to obj("webPlaybackUnavailable" to true))))
        assertNotNull(result.media)
        assertNull(result.error)
    }

    @Test fun `error labels in untrusted HTML alone cannot assert visible webpage error`() {
        val html = """<div hidden class="error"><p>抱歉出错了</p><p>请尝试在抖音内观看</p></div>"""
        assertEquals(ExtractError.UnsupportedPage, parse(snapshot(extra = mapOf("html" to JsonPrimitive(html)))).error)
        assertEquals(ExtractError.UnsupportedPage, parse(snapshot(extra = mapOf("blocker" to obj("webPlaybackUnavailable" to false)))).error)
        assertEquals(ExtractError.UnsupportedPage, parse(snapshot()).error)
    }

    @Test fun `visible verification and login retain actionable precedence over webpage error`() {
        assertEquals(ExtractError.VerificationRequired, parse(snapshot(extra = mapOf(
            "blocker" to obj("verification" to true, "webPlaybackUnavailable" to true)))).error)
        assertEquals(ExtractError.LoginRequired, parse(snapshot(extra = mapOf(
            "blocker" to obj("login" to true, "webPlaybackUnavailable" to true)))).error)
    }

    @Test fun `oversized HTML is omitted but valid local state can still be processed`() {
        val result = parse(snapshot(videos = listOf(video()), extra = mapOf("html" to JsonPrimitive("x".repeat(1_048_577)))))
        assertNotNull(result.media)
        assertTrue(result.attempts.any { "HTML 超过大小限制" in it.reason })
    }

    @Test fun `oversized snapshot rejected before processing media`() {
        val raw = snapshot(videos = listOf(video()), extra = mapOf("unused" to JsonPrimitive("x".repeat(3 * 1024 * 1024)))).toString()
        assertTrue(parser.parseEvaluationResult(raw, id).error is ExtractError.ParserError)
    }

    @Test fun `deep callback JSON and deeply nested state rejected`() {
        val deep = "[".repeat(100) + "0" + "]".repeat(100)
        assertTrue(parser.parseEvaluationResult(deep, id).error is ExtractError.ParserError)
        assertNull(parse(snapshot(states = listOf(state("SIGI_STATE", deep)))).media)
    }

    @Test fun `malformed null and unknown version callbacks are bounded failures`() {
        listOf("null", "undefined", "\"not JSON\"", "{", "{}", "{\"version\":2}").forEach {
            assertTrue(it, parser.parseEvaluationResult(it, id).error is ExtractError.ParserError)
        }
    }

    @Test fun `capture failure does not imply verification`() {
        val result = parse(snapshot(extra = mapOf("captureFailed" to JsonPrimitive(true))))
        assertEquals(ExtractError.UnsupportedPage, result.error)
    }

    private fun parse(snapshot: JsonObject) = parser.parseEvaluationResult(callback(snapshot), id)
    private fun callback(snapshot: JsonObject) = JsonPrimitive(snapshot.toString()).toString()
    private fun snapshot(
        videos: List<JsonObject> = emptyList(), states: List<JsonObject> = emptyList(), pageUrl: String = page,
        extra: Map<String, JsonElement> = emptyMap(),
    ) = JsonObject(mapOf(
        "version" to JsonPrimitive(1), "pageUrl" to JsonPrimitive(pageUrl), "canonical" to JsonPrimitive(page),
        "readyState" to JsonPrimitive("complete"), "title" to JsonPrimitive("Synthetic video"),
        "videos" to JsonArray(videos), "videoCount" to JsonPrimitive(videos.size), "states" to JsonArray(states),
        "html" to JsonPrimitive("<html></html>"),
    ) + extra)
    private fun video(url: String = mp4) = obj(
        "currentSrc" to url, "visible" to true, "width" to 1080, "height" to 1920,
        "readyState" to 4, "duration" to 12.5, "encrypted" to false,
    )
    private fun state(name: String, json: String) = obj("name" to name, "json" to json)
    private fun JsonObject.with(name: String, value: Any?) = JsonObject(this + (name to primitive(value)))
    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { it.first to primitive(it.second) })
    private fun primitive(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
