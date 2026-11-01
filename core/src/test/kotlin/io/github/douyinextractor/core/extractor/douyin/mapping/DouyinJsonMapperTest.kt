package io.github.douyinextractor.core.extractor.douyin.mapping

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DouyinJsonMapperTest {
    private val id = "1234567890123456789"

    @Test fun `maps exact target metadata and every distinct declared quality`() {
        val data = javaClass.getResource("/mapping/multi-quality.json")!!.readText()
        val media = DouyinJsonMapper.map(Json.parseToJsonElement(data), id, "fixture")!!
        assertEquals(id, media.id)
        assertEquals("Synthetic fixture: multiple progressive video qualities", media.title)
        assertEquals("Synthetic author", media.author)
        assertEquals(42000L, media.durationMs)
        assertEquals("https://synthetic.douyinpic.com/fixture-cover.jpg", media.coverUrl)
        assertEquals(3, media.formats.size)
        assertFalse(media.formats.any { "unrelated" in it.url })
        val avc = media.formats.first { it.codec == "h264" }
        assertEquals(720, avc.width)
        assertEquals(1280, avc.height)
        assertEquals(2000000L, avc.bitrate)
        assertEquals(10000000L, avc.contentLength)
        assertTrue(avc.url.endsWith("?token=a%2Fb%3D&x=1"))
        assertNull(avc.watermarked)
        assertEquals(false, media.formats.first { it.codec == "h265" }.watermarked)
        assertTrue(media.formats.any { "/playwm/" in it.url })
    }

    @Test fun `camel case identified video works without inventing absent dimensions or codec`() {
        val media = map("""{"awemeDetail":{"awemeId":"$id","title":"Camel","video":{"playAddr":{"urlList":["https://synthetic.douyinvod.com/play/?id=fixture"]}}}}""")!!
        assertEquals("Camel", media.title)
        assertEquals(1, media.formats.size)
        with(media.formats.single()) { assertNull(width); assertNull(height); assertNull(codec); assertNull(watermarked); assertNull(mimeType) }
    }

    @Test fun `unidentified video or only unrelated recommendation is not success`() {
        assertNull(map("""{"video":{"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}"""))
        assertNull(map("""{"itemId":"$id","recommendations":[{"aweme_id":"999","video":{"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}]}"""))
    }

    @Test fun `conflicting identifiers cannot authorize an entity`() {
        assertNull(map("""{"aweme_id":"$id","id":"999","video":{"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}"""))
    }

    @Test fun `generic id binds only a video entity not a resource object`() {
        assertNotNull(map("""{"id":"$id","video":{"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}"""))
        assertNull(map("""{"video":{"id":"$id","play_addr":"https://synthetic.douyinvod.com/a.mp4"}}"""))
    }

    @Test fun `image posts and music resources are not ordinary video candidates`() {
        assertNull(map("""{"aweme_id":"$id","images":[{}],"video":{"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}"""))
        assertNull(map("""{"aweme_id":"$id","music":{"play_url":"https://synthetic.douyinvod.com/a.mp4"}}"""))
        assertNull(map("""{"aweme_id":"$id","video":{"mime_type":"audio/mp4","play_addr":"https://synthetic.douyinvod.com/a.mp4"}}"""))
    }

    @Test fun `DRM and stream manifests are excluded even when fields resemble play address`() {
        listOf(
            """"is_drm":true,"play_addr":"https://synthetic.douyinvod.com/a.mp4"""",
            """"play_addr":{"encryption_info":{"key":"fixture"},"url_list":["https://synthetic.douyinvod.com/a.mp4"]}""",
            """"format":"hls","play_addr":"https://synthetic.douyinvod.com/play/"""",
            """"play_addr":"https://synthetic.douyinvod.com/playlist.m3u8"""",
        ).forEach { video -> assertNull(map("""{"aweme_id":"$id","video":{$video}}""")) }
    }

    @Test fun `invalid one variant does not hide another valid format`() {
        val media = map("""{"aweme_id":"$id","video":{"bit_rate":[{"is_drm":true,"play_addr":"https://synthetic.douyinvod.com/protected.mp4"},{"play_addr":"https://synthetic.douyinvod.com/public.mp4"}],"play_addr":"https://evil.example/video.mp4"}}""")!!
        assertEquals(listOf("https://synthetic.douyinvod.com/public.mp4"), media.formats.map { it.url })
    }

    @Test fun `nested variant audio-only flag and address license prevent false video success`() {
        assertNull(map("""{"aweme_id":"$id","video":{"bit_rate":[{"audio_only":true,"play_addr":"https://synthetic.douyinvod.com/a.mp4"}]}}"""))
        assertNull(map("""{"aweme_id":"$id","video":{"play_addr":{"license_url":"https://synthetic.douyinvod.com/license","url_list":["https://synthetic.douyinvod.com/a.mp4"]}}}"""))
    }

    @Test fun `zero encryption indicators and explicit false watermark do not block public video`() {
        val media = map("""{"aweme_id":"$id","video":{"drm_type":0,"is_encrypted":false,"is_watermarked":false,"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}""")!!
        assertEquals(false, media.formats.single().watermarked)
    }

    @Test fun `sparse variant and alternate download do not inherit primary stream properties`() {
        val media = map("""{"aweme_id":"$id","video":{"width":1080,"height":1920,"bitrate":8000000,"data_size":9999,"codec":"h264","play_addr":"https://synthetic.douyinvod.com/base.mp4","download_addr":"https://synthetic.douyinvod.com/download.mp4","bit_rate":[{"play_addr":"https://synthetic.douyinvod.com/alternate.mp4"}]}}""")!!
        with(media.formats.first { "base.mp4" in it.url }) {
            assertEquals(1080, width); assertEquals(1920, height); assertEquals("h264", codec)
            assertEquals(8000000L, bitrate); assertEquals(9999L, contentLength)
        }
        media.formats.filter { "base.mp4" !in it.url }.forEach {
            assertNull(it.width); assertNull(it.height); assertNull(it.codec); assertNull(it.bitrate); assertNull(it.contentLength)
        }
    }

    @Test fun `explicit unsupported video containers are excluded from MP4 phase`() {
        assertNull(map("""{"aweme_id":"$id","video":{"mime_type":"video/webm","play_addr":"https://synthetic.douyinvod.com/play/"}}"""))
        assertNull(map("""{"aweme_id":"$id","video":{"play_addr":"https://synthetic.douyinvod.com/movie.mov"}}"""))
    }

    @Test fun `numeric id retains precision beyond JavaScript safe integer`() {
        assertEquals(id, map("""{"aweme_id":$id,"video":{"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}""")!!.id)
    }

    @Test fun `null or malformed numeric metadata stays unknown`() {
        val media = map("""{"aweme_id":"$id","desc":null,"video":{"duration":-1,"width":999999999,"height":"oops","bitrate":-5,"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}""")!!
        assertNull(media.title); assertNull(media.durationMs)
        with(media.formats.single()) { assertNull(width); assertNull(height); assertNull(bitrate) }
    }

    @Test fun `walk is bounded and does not return partial answer on excessive structure`() {
        val valid = """{"aweme_id":"$id","video":{"play_addr":"https://synthetic.douyinvod.com/a.mp4"}}"""
        assertNull(map("[" + valid + "," + List(12001) { "0" }.joinToString(",") + "]"))
        assertNull(map("[".repeat(50) + valid + "]".repeat(50)))
    }

    private fun map(data: String) = DouyinJsonMapper.map(Json.parseToJsonElement(data), id, "test")
}
