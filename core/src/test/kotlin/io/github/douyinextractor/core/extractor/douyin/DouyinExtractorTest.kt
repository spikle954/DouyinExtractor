package io.github.douyinextractor.core.extractor.douyin

import io.github.douyinextractor.core.extractor.douyin.parser.DouyinPageParser
import io.github.douyinextractor.core.extractor.douyin.parser.PageParser
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.MediaFormat
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.model.RedirectStep
import io.github.douyinextractor.core.network.DouyinPageResponse
import io.github.douyinextractor.core.network.DouyinPageTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic orchestration tests; none claims the real platform currently returns media. */
class DouyinExtractorTest {
    private val id = "7000000000000000001"
    private val input = "https://www.douyin.com/video/$id"
    private val media = MediaInfo("douyin", id, "Synthetic", null, null, null, listOf(
        MediaFormat("https://v.example.test/video.mp4", 720, 1280, null, "h264", "video/mp4", null, "Synthetic"),
    ))
    private val syntheticParser = DouyinPageParser(listOf(object : PageParser {
        override val name = "SyntheticContract"
        override fun parse(html: String, pageUrl: String, targetId: String): MediaInfo? =
            if (html.contains("synthetic media")) media else null
    }))

    @Test fun `empty desktop state falls back to mobile and stops on target match`() = runBlocking {
        val requests = mutableListOf<String>()
        val events = mutableListOf<ExtractionEvent>()
        val extractor = DouyinExtractor(DouyinPageTransport { url ->
            requests += url
            DouyinPageResponse(200, "text/html; charset=UTF-8", if (requests.size == 2) "<main>synthetic media</main>" else "<script>window.shell=true</script>", url)
        }, syntheticParser)
        assertEquals(media, extractor.extract(input, events::add).getOrThrow())
        assertEquals(2, requests.size)
        assertTrue(requests[1].startsWith("https://www.iesdouyin.com/"))
        assertEquals(2, events.filterIsInstance<ExtractionEvent.Page>().size)
        assertEquals(2, events.filterIsInstance<ExtractionEvent.Parser>().size)
    }

    @Test fun `all empty page states remain unsupported without false verification diagnosis`() = runBlocking {
        var requests = 0
        val html = """<script src="https://example.test/captcha.js"></script><script>window._ROUTER_DATA={"loaderData":{"video_layout":null,"video_(id)/page":{"itemId":"$id"}},"errors":null}</script>"""
        val extractor = DouyinExtractor(DouyinPageTransport { url -> requests++; DouyinPageResponse(200, "text/html", html, url) })
        assertSame(ExtractError.UnsupportedPage, extractor.extract(input).exceptionOrNull())
        assertEquals(3, requests)
    }

    @Test fun `HTTP unauthorized terminates as login required`() = runBlocking {
        var requests = 0
        val extractor = DouyinExtractor(DouyinPageTransport { url -> requests++; DouyinPageResponse(401, "text/html", "", url) })
        assertSame(ExtractError.LoginRequired, extractor.extract(input).exceptionOrNull())
        assertEquals(1, requests)
    }

    @Test fun `not found and throttled pages try every public strategy then preserve status`() = runBlocking {
        for (status in listOf(404, 410, 403, 429, 500)) {
            var requests = 0
            val extractor = DouyinExtractor(DouyinPageTransport { url -> requests++; DouyinPageResponse(status, "text/html", "", url) })
            val failure = extractor.extract(input).exceptionOrNull()
            if (status == 404 || status == 410) assertSame(ExtractError.VideoNotFound, failure)
            else assertEquals(ExtractError.HttpError(status), failure)
            assertEquals(3, requests)
        }
    }

    @Test fun `network failure falls back and never exposes exception page or query in reasons`() = runBlocking {
        var requests = 0
        val events = mutableListOf<ExtractionEvent>()
        val extractor = DouyinExtractor(DouyinPageTransport { url ->
            requests++
            if (requests == 1) throw IOException("secret-cookie=forbidden")
            DouyinPageResponse(200, "text/html", "<main>synthetic media</main>", "$url?did=private-tracker")
        }, syntheticParser)
        assertTrue(extractor.extract(input, events::add).isSuccess)
        val reasons = events.filterIsInstance<ExtractionEvent.Page>().joinToString { it.reason }
        assertFalse(reasons.contains("secret-cookie"))
        assertFalse(reasons.contains("private-tracker"))
        assertFalse(reasons.contains("<main>"))
        assertEquals(2, requests)
    }

    @Test fun `unsafe or mismatching final page never reaches parser`() = runBlocking {
        for (finalUrl in listOf("https://evil.example/video/$id", "https://www.douyin.com/video/7000000000000000002", "$input?modal_id=7000000000000000002")) {
            val extractor = DouyinExtractor(DouyinPageTransport { DouyinPageResponse(200, "text/html", "<main>synthetic media</main>", finalUrl) }, syntheticParser)
            assertSame(ExtractError.RedirectFailed, extractor.extract(input).exceptionOrNull())
        }
    }

    @Test fun `invalid input performs no network request`() = runBlocking {
        val extractor = DouyinExtractor(DouyinPageTransport { error("Must not request invalid input") })
        for (url in listOf("https://evil.example/video/$id", "http://www.douyin.com/video/$id", "https://www.douyin.com/note/$id")) {
            assertFalse(extractor.canHandle(url))
            assertSame(ExtractError.InvalidUrl, extractor.extract(url).exceptionOrNull())
        }
        assertTrue(extractor.canHandle(input))
    }

    @Test fun `cancellation remains cancellation and does not trigger fallback`() = runBlocking {
        var requests = 0
        val extractor = DouyinExtractor(DouyinPageTransport { requests++; throw CancellationException("cancel") })
        try { extractor.extract(input); fail("Cancellation must escape Result") }
        catch (_: CancellationException) { }
        assertEquals(1, requests)
    }

    @Test fun `explicit visible blocking form is verification required`() = runBlocking {
        val extractor = DouyinExtractor(DouyinPageTransport { url -> DouyinPageResponse(200, "text/html", "<main><form>请先完成安全验证</form></main>", url) })
        assertSame(ExtractError.VerificationRequired, extractor.extract(input).exceptionOrNull())
    }

    @Test fun `valid media about verification is not mistaken for blocked access`() = runBlocking {
        val extractor = DouyinExtractor(DouyinPageTransport { url ->
            DouyinPageResponse(200, "text/html", "<main>请先完成安全验证 synthetic media</main>", url)
        }, syntheticParser)
        assertEquals(media, extractor.extract(input).getOrThrow())
    }

    @Test fun `redirect diagnostic event is emitted before final page event`() = runBlocking {
        val events = mutableListOf<ExtractionEvent>()
        val step = RedirectStep(input, 302, "https://www.iesdouyin.com/share/video/$id/", "text/html")
        val extractor = DouyinExtractor(DouyinPageTransport {
            DouyinPageResponse(200, "text/html", "<main>synthetic media</main>", step.location!!, listOf(step))
        }, syntheticParser)
        assertTrue(extractor.extract(input, events::add).isSuccess)
        assertEquals(ExtractionEvent.Redirect(step), events.first())
        assertEquals(step.location, events.filterIsInstance<ExtractionEvent.Page>().single().url)
    }

    @Test fun `hidden form and scripts are not visible blocker evidence`() = runBlocking {
        val bodies = listOf(
            "<form hidden>请先完成安全验证</form>",
            "<form style='display:none'>请先完成安全验证</form>",
            "<main><script>var message='请先完成安全验证';</script><button>登录</button></main>",
        )
        for (html in bodies) {
            val extractor = DouyinExtractor(DouyinPageTransport { url -> DouyinPageResponse(200, "text/html", html, url) })
            assertSame(ExtractError.UnsupportedPage, extractor.extract(input).exceptionOrNull())
        }
    }

    @Test fun `binary content and blank pages are not parsed`() = runBlocking {
        for ((type, body) in listOf("video/mp4" to "<main>synthetic media</main>", "text/html" to " ")) {
            val extractor = DouyinExtractor(DouyinPageTransport { url -> DouyinPageResponse(200, type, body, url) }, syntheticParser)
            assertSame(ExtractError.UnsupportedPage, extractor.extract(input).exceptionOrNull())
        }
    }

    @Test fun `configuration rejects non-platform insecure and ambiguous templates`() {
        for (template in listOf("http://www.douyin.com/video/{id}", "https://evil.example/video/{id}", "https://www.douyin.com/video/1", "https://www.douyin.com/video/{id}?did=tracking", "https://www.douyin.com/note/{id}")) {
            try { DouyinPageConfig(listOf(template)); fail("Invalid configuration must fail") }
            catch (_: IllegalArgumentException) { }
        }
        val mutable = mutableListOf("https://www.douyin.com/video/{id}")
        val config = DouyinPageConfig(mutable)
        mutable.clear()
        assertEquals(1, config.pageTemplates.size)
    }
}
