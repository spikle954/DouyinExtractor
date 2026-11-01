package io.github.douyinextractor.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinUrlsTest {
    @Test fun `supported video URL shapes converge to a canonical identifier`() {
        // Exactly the supported 25-digit boundary, with leading zeros and beyond Long range.
        val id = "00" + "1".repeat(23)
        listOf(
            "https://douyin.com/video/$id",
            "https://www.douyin.com/video/$id/",
            "https://www.douyin.com/jingxuan?modal_id=$id",
            "https://www.douyin.com/discover?modal_id=$id&from=web",
            "https://www.douyin.com/?modal_id=$id",
            "https://m.douyin.com/share/video/$id/",
            "https://iesdouyin.com/share/video/$id/",
            "https://www.iesdouyin.com/share/video/$id/",
        ).forEach {
            assertEquals(it, id, DouyinUrls.videoId(it))
            assertEquals("https://www.douyin.com/video/$id", DouyinUrls.canonical(requireNotNull(DouyinUrls.videoId(it))))
        }
    }

    @Test fun `identifiers preserve leading zeros without Long conversion`() {
        assertEquals("1", DouyinUrls.videoId("https://www.douyin.com/video/1"))
        assertEquals("00001", DouyinUrls.videoId("https://www.douyin.com/video/00001"))
        assertNull(DouyinUrls.normalize("https://www.douyin.com/video/" + "1".repeat(26)))
        assertNull(DouyinUrls.normalize("https://www.douyin.com/video/-12"))
        assertNull(DouyinUrls.normalize("https://www.douyin.com/video/1.2"))
        assertNull(DouyinUrls.normalize("https://www.douyin.com/video/1/extra"))
    }

    @Test fun `same repeated identifier is accepted but invalid or conflicting values fail`() {
        assertEquals("123", DouyinUrls.videoId("https://www.douyin.com/video/123?modal_id=123&modal_id=123"))
        listOf(
            "https://www.douyin.com/video/123?modal_id=124",
            "https://www.douyin.com/jingxuan?modal_id=123&modal_id=124",
            "https://www.douyin.com/jingxuan?modal_id=",
            "https://www.douyin.com/jingxuan?modal_id",
            "https://www.douyin.com/jingxuan?modal_id=abc",
        ).forEach {
            assertNull(it, DouyinUrls.normalize(it))
            assertNull(it, DouyinUrls.videoId(it))
        }
    }

    @Test fun `normalization upgrades input HTTP but request validation never does`() {
        assertEquals("https://www.douyin.com/video/123", DouyinUrls.normalize("http://www.douyin.com:80/video/123"))
        assertFalse(DouyinUrls.isAllowed("http://www.douyin.com/video/123"))
        assertTrue(DouyinUrls.isAllowed("https://www.douyin.com:443/video/123"))
        assertFalse(DouyinUrls.isAllowed("https://www.douyin.com:80/video/123"))
        assertNull(DouyinUrls.normalize("http://www.douyin.com:443/video/123"))
    }

    @Test fun `normalization is idempotent and leaves routing fields usable`() {
        val source = "https://V.DOUYIN.COM/opaque/?token=a%2Fb%3D&timestamp=123&share_token=opaque&UTM_campaign=ad&iid=private&route=a&route=b#secret"
        val normalized = DouyinUrls.normalize(source)
        assertEquals("https://v.douyin.com/opaque/?token=a%2Fb%3D&timestamp=123&share_token=opaque&route=a&route=b", normalized)
        assertEquals(normalized, DouyinUrls.normalize(requireNotNull(normalized)))
    }

    @Test fun `generic redirect paths stay safe and available without being classified as video`() {
        val url = "https://www.douyin.com/redirect?token=opaque#fragment"
        assertTrue(DouyinUrls.isAllowed(url))
        assertEquals("https://www.douyin.com/redirect?token=opaque", DouyinUrls.normalize(url))
        assertNull(DouyinUrls.videoId(url))
        assertFalse(DouyinUrls.isShortLink(url))
    }

    @Test fun `short links require exactly one opaque token on a recognized short host`() {
        assertTrue(DouyinUrls.isShortLink("https://v.douyin.com/Synthetic_1-/"))
        assertTrue(DouyinUrls.isShortLink("https://v.iesdouyin.com/Synthetic/"))
        assertFalse(DouyinUrls.isShortLink("https://v.douyin.com/"))
        assertFalse(DouyinUrls.isShortLink("https://v.douyin.com/a/b/"))
        assertFalse(DouyinUrls.isShortLink("https://www.douyin.com/Synthetic/"))
        assertFalse(DouyinUrls.isShortLink("https://v.douyin.com/note/"))
    }

    @Test fun `short link query identifiers cannot bypass resolving the actual short code`() {
        listOf("v.douyin.com", "v.iesdouyin.com").forEach { host ->
            val url = "https://$host/Synthetic/?modal_id=123&modal_id=456&token=opaque"
            assertEquals(url, DouyinUrls.normalize(url))
            assertTrue(DouyinUrls.isShortLink(url))
            assertNull(DouyinUrls.videoId(url))
        }
    }

    @Test fun `observed share tracking fields are removed without removing routing parameters`() {
        val url = "https://v.douyin.com/Synthetic/?ts=1&share_track_info=x&share_extra_params=x&activity_info=x&ug_share_id=x&share_video_id=x&from_aid=x&titleType=x&app=x&token=needed&route=needed"
        assertEquals("https://v.douyin.com/Synthetic/?token=needed&route=needed", DouyinUrls.normalize(url))
    }

    @Test fun `sanitized diagnostics retain only public host and path`() {
        val url = "https://v.douyin.com/Synthetic/?token=private&u_code=private#private"
        assertEquals("https://v.douyin.com/Synthetic/", DouyinUrls.sanitizeForDiagnostics(url))
        assertEquals("[invalid URL]", DouyinUrls.sanitizeForDiagnostics("https://private:secret@www.douyin.com/video/123"))
    }

    @Test fun `raw authority and invisible whitespace tricks cannot pass request validation`() {
        listOf(
            "https://@www.douyin.com/video/123",
            "https://www.douyin.com./video/123",
            "https://www.douyin.com:0443/video/123",
            "https://www.douyin.com.evil.example/video/123",
            "https://%77ww.douyin.com/video/123",
            "https://www.douyin.com\\evil/video/123",
            "https://www.douyin.com/vi\ndeo/123",
            "https://www.douyin.com/video/123\u0000",
            "https://www.douyin.com/video/123\u007f",
        ).forEach { assertFalse(it, DouyinUrls.isAllowed(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `canonical rejects path injection`() {
        DouyinUrls.canonical("123/../note/456")
    }
}
