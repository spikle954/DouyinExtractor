package io.github.douyinextractor.core.extractor.douyin.mapping

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DouyinPlaybackSourceTest {
    private val base = "https://aweme.snssdk.com/aweme/v1/playwm/"

    @Test fun `public alternative retains original video id and encoded query verbatim`() {
        val query = "?video_id=fixture_VIDEO-1&ratio=720p&line=%30&media_type=4&logo_name=fixture%5Flogo"
        assertEquals("https://aweme.snssdk.com/aweme/v1/play/$query", DouyinPlaybackSource.alternative(base + query))
        assertTrue(DouyinPlaybackSource.isWatermarked(base + query))
        assertFalse(DouyinPlaybackSource.isWatermarked(base.replace("playwm", "play") + query))
    }

    @Test fun `no rewriting arbitrary domains paths CDN signatures or insecure URLs`() {
        listOf(
            "http://aweme.snssdk.com/aweme/v1/playwm/?video_id=fixture",
            "https://aweme.snssdk.com.evil.example/aweme/v1/playwm/?video_id=fixture",
            "https://synthetic.douyinvod.com/aweme/v1/playwm/?video_id=fixture",
            "https://aweme.snssdk.com:8443/aweme/v1/playwm/?video_id=fixture",
            "https://user@aweme.snssdk.com/aweme/v1/playwm/?video_id=fixture",
            "https://aweme.snssdk.com/a/../aweme/v1/playwm/?video_id=fixture",
            base + "extra?video_id=fixture",
            base + "?video_id=fixture&X-Bogus=signature",
            base + "?video_id=fixture&token=signature",
            base + "?video_id=fixture&watermark=1",
            base + "?video_id=fixture&watermark=0&watermark=1",
            base + "?video_id=fixture&unreviewed=1",
        ).forEach { assertNull(it, DouyinPlaybackSource.alternative(it)) }
    }

    @Test fun `missing ambiguous or malformed media identity has no alternative`() {
        listOf("", "?video_id=", "?video_id=a&video_id=b", "?video_id=a%2Fb", "?video_id=" + "a".repeat(129))
            .forEach { assertNull(DouyinPlaybackSource.alternative(base + it)) }
    }

    @Test fun `mapper binds alternative to exact target and preserves original without invented metadata`() {
        val data = """{"aweme_id":"123","video":{"width":720,"height":1280,"data_size":900,"is_watermark":false,"play_addr":{"url_list":["${base}?video_id=fixture"]}}}"""
        assertNull(DouyinJsonMapper.map(Json.parseToJsonElement(data), "999", "fixture"))
        val formats = DouyinJsonMapper.map(Json.parseToJsonElement(data), "123", "fixture")!!.formats
        assertEquals(2, formats.size)
        val candidate = formats.single { it.watermarkFreeCandidate }
        assertNull(candidate.watermarked)
        assertNull(candidate.width); assertNull(candidate.height); assertNull(candidate.bitrate)
        assertNull(candidate.codec); assertNull(candidate.contentLength)
        val original = formats.single { !it.watermarkFreeCandidate }
        assertEquals(base + "?video_id=fixture", original.url)
        assertEquals(true, original.watermarked)
        assertEquals(720, original.width)
    }

    @Test fun `protected target never produces a public alternative`() {
        val data = """{"aweme_id":"123","video":{"is_drm":true,"play_addr":"${base}?video_id=fixture"}}"""
        assertNull(DouyinJsonMapper.map(Json.parseToJsonElement(data), "123", "fixture"))
    }

    @Test fun `already declared playback resource keeps its precise metadata`() {
        val play = base.replace("playwm", "play") + "?video_id=fixture"
        val data = """{"aweme_id":"123","video":{"play_addr":"${base}?video_id=fixture","play_addr_h264":{"width":1920,"height":1080,"url_list":["$play"]}}}"""
        val formats = DouyinJsonMapper.map(Json.parseToJsonElement(data), "123", "fixture")!!.formats
        assertEquals(2, formats.size)
        val explicit = formats.single { it.url == play }
        assertEquals(1920, explicit.width)
        assertEquals("h264", explicit.codec)
        assertFalse(explicit.watermarkFreeCandidate)
    }
}
