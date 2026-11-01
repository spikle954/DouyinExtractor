package io.github.douyinextractor.core.extractor.douyin.mapping

import org.junit.Assert.*
import org.junit.Test

class MediaUrlPolicyTest {
    @Test fun `observed platform CDN retains signatures with strict authority boundaries`() {
        val url = "https://v5-hl-mly-ov.zjcdn.com/synthetic.mp4?token=not-a-real-token%2B1"
        assertEquals(url, MediaUrlPolicy.media(url))
        listOf("https://zjcdn.com.evil.example/a.mp4", "https://evilzjcdn.com/a.mp4",
            "http://v5-hl-mly-ov.zjcdn.com/a.mp4", "https://user@v5-hl-mly-ov.zjcdn.com/a.mp4",
            "https://v5-hl-mly-ov.zjcdn.com:444/a.mp4", "https://v5-hl-mly-ov.zjcdn.com./a.mp4")
            .forEach { assertNull(it, MediaUrlPolicy.media(it)) }
    }
    @Test fun `signed public video URLs retain opaque query data`() {
        val url = "https://synthetic.douyinvod.com/playwm/?token=a%2Fb%3D&route=1&route=2"
        assertEquals(url, MediaUrlPolicy.media(url))
        assertEquals(url, MediaUrlPolicy.media("$url#discard"))
    }

    @Test fun `host and scheme boundary rejects URL confusion local destinations and downgrade`() {
        listOf(
            "http://synthetic.douyinvod.com/a.mp4", "https://127.0.0.1/a.mp4", "https://[::1]/a.mp4",
            "https://douyinvod.com.evil.example/a.mp4", "https://evildouyinvod.com/a.mp4",
            "https://user@douyinvod.com/a.mp4", "https://@douyinvod.com/a.mp4", "https://douyinvod.com:444/a.mp4",
            "https://douyinvod.com:0443/a.mp4", "https://douyinvod.com./a.mp4", "https://%64ouyinvod.com/a.mp4",
            "https://douyinvod.com\\@evil.example/a.mp4", "https://douyinvod.com/a .mp4", "//douyinvod.com/a.mp4",
            "data:video/mp4,AAAA", "blob:https://douyinvod.com/a", "https://douyinpic.com/cover.jpg",
        ).forEach { assertNull(it, MediaUrlPolicy.media(it)) }
    }

    @Test fun `manifest and audio URLs cannot be presented as progressive video`() {
        listOf("a.M3U8", "a.mpd", "a.mp3", "a.m4a", "play?format=hls", "play?mime=audio%2Fmp4", "play?mime=application%2Fdash%2Bxml")
            .forEach { assertNull(it, MediaUrlPolicy.media("https://synthetic.douyinvod.com/$it")) }
    }

    @Test fun `image CDN allowance is separate from playable resource allowance`() {
        assertEquals("https://synthetic.douyinpic.com/a.jpg", MediaUrlPolicy.cover("https://synthetic.douyinpic.com/a.jpg"))
        assertNull(MediaUrlPolicy.cover("https://synthetic.douyinpic.com.evil.example/a.jpg"))
    }
}
