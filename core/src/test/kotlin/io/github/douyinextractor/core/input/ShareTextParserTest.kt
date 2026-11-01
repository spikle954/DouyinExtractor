package io.github.douyinextractor.core.input

import io.github.douyinextractor.core.model.ExtractError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShareTextParserTest {
    private val parser = ShareTextParser()

    @Test fun `user supplied Linda share text extracts the opaque short token`() {
        assertEquals("https://v.douyin.com/9jWJDrnGGCI/", parse(fixture("linda-share.txt")))
    }

    @Test fun `screenshot transcriptions preserve each token without claiming availability`() {
        val shares = fixture("screenshot-shares.txt").lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals("https://v.douyin.com/5AXSheWSgNw/", parse(shares[0]))
        assertEquals("https://v.douyin.com/KrKvnlWJML4/", parse(shares[1]))
    }

    @Test fun `Chinese wording punctuation and surrounding mixed whitespace are removed`() {
        val examples = listOf(
            "\t复制打开抖音\n\rhttps://v.douyin.com/Synthetic_A/  abc:/",
            "链接：https://v.douyin.com/Synthetic_A/。复制打开",
            "（https://v.douyin.com/Synthetic_A/），",
            "看看[https://v.douyin.com/Synthetic_A/].",
            "看看「https://v.douyin.com/Synthetic_A/」",
            "\"https://v.douyin.com/Synthetic_A/\"",
            "https://v.douyin.com/Synthetic_A/复制打开抖音",
            "https://v.douyin.com/Synthetic_A/…",
            "https://v.douyin.com/Synthetic_A/：复制打开",
            "https://v.douyin.com/Synthetic_A/:/",
        )
        examples.forEach { assertEquals(it, "https://v.douyin.com/Synthetic_A/", parse(it)) }
    }

    @Test fun `direct bare and HTTP links normalize before use`() {
        assertEquals("https://douyin.com/video/123", parse("douyin.com/video/123"))
        assertEquals("https://v.douyin.com/Synthetic/", parse("v.douyin.com/Synthetic/"))
        assertEquals("https://www.douyin.com/video/123", parse("HTTP://WWW.DOUYIN.COM:80/video/123"))
        assertEquals("https://www.iesdouyin.com/share/video/123/", parse("https://www.iesdouyin.com/share/video/123/"))
    }

    @Test fun `tracking is removed while opaque routing data is preserved`() {
        val url = parse("https://v.douyin.com/Synthetic/?u_code=private&token=a%2Fb%3D&share_sign=secret&route=x&route=y&utm_source=chat#fragment")
        assertEquals("https://v.douyin.com/Synthetic/?token=a%2Fb%3D&route=x&route=y", url)
    }

    @Test fun `unsupported pages and contradictory identifiers never become a video`() {
        listOf(
            "https://www.douyin.com/",
            "https://www.douyin.com/user/synthetic",
            "https://www.douyin.com/note/123?modal_id=123",
            "https://www.douyin.com/live/123?modal_id=123",
            "https://www.iesdouyin.com/share/note/123",
            "https://www.douyin.com/video/123?modal_id=456",
            "https://www.douyin.com/jingxuan?modal_id=123&modal_id=456",
            "https://www.douyin.com/video/wrong?modal_id=123",
            "https://v.douyin.com/",
        ).forEach(::invalid)
    }

    @Test fun `hostile URLs cannot hide an allowed URL in their path query or credentials`() {
        listOf(
            "https://evil.example/?next=https://v.douyin.com/Synthetic/",
            "https://evil.example/https://v.douyin.com/Synthetic/",
            "https://evil.example/?next=\"https://v.douyin.com/Synthetic/\"",
            "https://evil.example/?next='https://v.douyin.com/Synthetic/'",
            "https://evil.example/?next=「https://v.douyin.com/Synthetic/」",
            "ftp://evil.example/https://v.douyin.com/Synthetic/",
            "evil.example/?next=https://v.douyin.com/Synthetic/",
            "https://v.douyin.com.evil.example/Synthetic/",
            "https://evil.example@v.douyin.com/Synthetic/",
            "https://v.douyin.com@evil.example/Synthetic/",
            "https://@v.douyin.com/Synthetic/",
            "https://v.douyin.com:8443/Synthetic/",
            "https://v.douyin.com\\@evil.example/Synthetic/",
            "https://v.douyin.com./Synthetic/",
            "https://%76.douyin.com/Synthetic/",
            "https://douyіn.com/video/123", // Cyrillic i, synthetic homograph.
            "evil.v.douyin.com/Synthetic/",
            "notdouyin.com/video/123",
        ).forEach(::invalid)
    }

    @Test fun `empty input unrelated text and bare identifiers fail without exceptions`() {
        listOf("", "   \r\n", "复制打开抖音", "1234567890123456789", "https://example.com/").forEach(::invalid)
    }

    @Test fun `different links are ambiguous while duplicate tracking variants are harmless`() {
        invalid("https://v.douyin.com/First/ https://v.douyin.com/Second/")
        assertEquals(
            "https://v.douyin.com/Same/",
            parse("https://v.douyin.com/Same/?u_code=one\nhttps://v.douyin.com/Same/?u_code=two"),
        )
    }

    @Test fun `oversized input and URL fail instead of being truncated`() {
        invalid("https://v.douyin.com/Valid/ " + "文".repeat(ShareTextParser.MAX_INPUT_LENGTH))
        invalid("https://v.douyin.com/" + "a".repeat(DouyinUrls.MAX_URL_LENGTH))
        invalid("https://v.douyin.com/Valid/ https://v.douyin.com/" + "a".repeat(DouyinUrls.MAX_URL_LENGTH))
        val prefix = "https://v.douyin.com/Boundary/ "
        assertEquals("https://v.douyin.com/Boundary/", parse(prefix + "文".repeat(ShareTextParser.MAX_INPUT_LENGTH - prefix.length)))
    }

    private fun parse(text: String): String = parser.parse(text).getOrThrow().url

    private fun invalid(text: String) {
        assertSame(text.take(100), ExtractError.InvalidUrl, parser.parse(text).exceptionOrNull())
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/input/$name")).readText()
}
