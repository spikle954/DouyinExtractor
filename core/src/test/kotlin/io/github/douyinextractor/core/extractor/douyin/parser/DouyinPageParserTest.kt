package io.github.douyinextractor.core.extractor.douyin.parser

import io.github.douyinextractor.core.model.MediaInfo
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class DouyinPageParserTest {
    private val id = "7000000000000000001"
    private val page = "https://www.douyin.com/video/$id"
    private val parser = DouyinPageParser()

    @Test fun routerStateParsesBracesQuotesAndMetadata() {
        val result = RouterDataParser().parse(fixture("router-synthetic.html"), page, id)!!
        assertEquals("Synthetic {title} + escaped \"quote\"", result.title)
        assertEquals("Synthetic author", result.author)
        assertEquals(12500L, result.durationMs)
        assertTrue(result.formats.single().url.endsWith("?synthetic_token=kept"))
        assertNull(result.formats.single().watermarked)
        assertEquals(720, result.formats.single().width)
    }

    @Test fun renderDataParsesPercentEncodedJsonAndPreservesPlus() {
        val result = RenderDataParser().parse(fixture("render-synthetic.html"), page, id)!!
        assertTrue(result.title!!.contains(" + "))
        val literalPlus = fixture("render-synthetic.html").replace("%2B", "+")
        assertTrue(RenderDataParser().parse(literalPlus, page, id)!!.title!!.contains(" + "))
    }

    @Test fun sigiStateParsesScriptElement() {
        assertEquals(id, SigiStateParser().parse(fixture("sigi-synthetic.html"), page, id)!!.id)
    }

    @Test fun applicationJsonParsesNextHydration() {
        assertEquals(id, ScriptJsonParser().parse(fixture("script-json-synthetic.html"), page, id)!!.id)
    }

    @Test fun initialHydrationAssignmentIsSupported() {
        val html = fixture("router-synthetic.html").replace("_ROUTER_DATA", "__INITIAL_STATE__")
        assertEquals(id, ScriptJsonParser().parse(html, page, id)!!.id)
    }

    @Test fun jsonLdCollectsMultipleQualitiesAndDuration() {
        val result = JsonLdParser().parse(fixture("jsonld-synthetic.html"), page, id)!!
        assertEquals(2, result.formats.size)
        assertEquals(12500L, result.durationMs)
        assertEquals("Synthetic author", result.author)
        assertEquals(1080, result.formats.first().width)
    }

    @Test fun canonicalHtmlParsesVideoSources() {
        val result = HtmlMediaParser().parse(fixture("html-media-synthetic.html"), page, id)!!
        assertEquals("Synthetic HTML video", result.title)
        assertNull(result.formats.single().width)
        assertNull(result.formats.single().height)
        assertEquals("https://synthetic.douyinpic.com/cover.jpg", result.coverUrl)
    }

    @Test fun everyFixtureRejectsMismatchedIdentity() {
        listOf("router", "render", "sigi", "script-json", "jsonld", "html-media").forEach { kind ->
            val html = fixture("$kind-synthetic.html").replace(id, "7000000000000000002")
            assertNull("$kind accepted another video's media", parser.parse(html, page, id).media)
        }
    }

    @Test fun actualSanitizedEmptyRouterStateNeverCountsAsSuccess() {
        val result = parser.parse(fixture("empty-router-sanitized.html"), page, id)
        assertNull(result.media)
        assertEquals(6, result.attempts.size)
        assertTrue(result.attempts.all { it.formatCount == 0 })
        assertTrue(result.attempts.first().reason.contains("存在页面数据"))
        assertTrue(result.attempts.drop(1).all { it.reason.contains("未包含") })
    }

    @Test fun malformedRouterFallsThroughToJsonLd() {
        val html = "<script>window._ROUTER_DATA = {not-json};</script>" + fixture("jsonld-synthetic.html")
        val result = parser.parse(html, page, id)
        assertEquals(2, result.media!!.formats.size)
        assertTrue(result.attempts.first().reason.contains("无法解析"))
        assertTrue(result.attempts.any { it.parser == "JsonLdParser" && it.formatCount == 2 })
    }

    @Test fun malformedPercentEncodingDoesNotBlockLaterParsers() {
        val html = "<script id=RENDER_DATA>%ZZ</script>" + fixture("sigi-synthetic.html")
        val result = parser.parse(html, page, id)
        assertNotNull(result.media)
        assertTrue(result.attempts.any { it.parser == "RenderDataParser" && it.reason.contains("无法解析") })
    }

    @Test fun malformedEncodedBlockDoesNotHideLaterRenderData() {
        val html = "<script id=RENDER_DATA>%ZZ</script>" + fixture("render-synthetic.html")
        assertEquals(id, RenderDataParser().parse(html, page, id)!!.id)
    }

    @Test fun balancedLexerIgnoresAssignmentsInStringsCommentsAndRegexes() {
        val assignment = "_ROUTER_DATA = {\"aweme_id\":\"$id\",\"video\":{\"play_addr\":\"https://synthetic.douyinvod.com/a.mp4\"}}"
        val escaped = assignment.replace("\\", "\\\\").replace("\"", "\\\"")
        val html = "<script>const fake = \"$escaped\"; /* $assignment */ // $assignment\nconst regex = /${assignment.replace("/", "\\/")}/;</script>"
        assertNull(RouterDataParser().parse(html, page, id))
    }

    @Test fun malformedFirstStateDoesNotPreventLaterValidStateOfSameKind() {
        val result = RouterDataParser().parse("<script>_ROUTER_DATA = {broken};</script>" + fixture("router-synthetic.html"), page, id)
        assertNotNull(result)
    }

    @Test fun orchestratorDeduplicatesSameResourceAcrossParsers() {
        val result = parser.parse(fixture("router-synthetic.html") + fixture("html-media-synthetic.html"), page, id)
        assertEquals(1, result.media!!.formats.size)
        assertEquals(2, result.attempts.count { it.formatCount > 0 })
        assertEquals("video/mp4", result.media!!.formats.single().mimeType)
    }

    @Test fun htmlWithoutCanonicalIdentityIsNotAccepted() {
        val html = fixture("html-media-synthetic.html").replace("rel=\"canonical\"", "rel=\"alternate\"")
        assertNull(HtmlMediaParser().parse(html, page, id))
    }

    @Test fun multipleHtmlSourcesDoNotInheritLayoutDimensionsAsQuality() {
        val html = fixture("html-media-synthetic.html").replace("</video>",
            "<source src=\"https://synthetic.douyinvod.com/another.mp4\" type=\"video/mp4\" width=1080 height=1920></video>")
        val result = HtmlMediaParser().parse(html, page, id)!!
        assertEquals(2, result.formats.size)
        assertTrue(result.formats.all { it.width == null && it.height == null })
    }

    @Test fun ambiguousHtmlVideosAreRejected() {
        val html = fixture("html-media-synthetic.html").replace("</body>", "<video src=\"https://synthetic.douyinvod.com/recommendation.mp4\"></video></body>")
        assertNull(HtmlMediaParser().parse(html, page, id))
    }

    @Test fun explicitHtmlItemIdentityRejectsRecommendationVideo() {
        val html = fixture("html-media-synthetic.html").replace("<video width", "<video data-aweme-id=\"7000000000000000002\" width")
        assertNull(HtmlMediaParser().parse(html, page, id))
    }

    @Test fun htmlRejectsManifestAudioAndForeignHosts() {
        val html = fixture("html-media-synthetic.html")
        listOf(
            html.replace("video.mp4?", "video.m3u8?"),
            html.replace("type=\"video/mp4\"", "type=\"audio/mp4\""),
            html.replace("synthetic.douyinvod.com", "douyinvod.com.attacker.example"),
        ).forEach { assertNull(HtmlMediaParser().parse(it, page, id)) }
    }

    @Test fun unboundJsonLdRecommendationsAreRejected() {
        val html = "<link rel=canonical href=\"$page\"><script type=application/ld+json>[" +
            "{\"@type\":\"VideoObject\",\"contentUrl\":\"https://synthetic.douyinvod.com/a.mp4\"}," +
            "{\"@type\":\"VideoObject\",\"contentUrl\":\"https://synthetic.douyinvod.com/b.mp4\"}]</script>"
        assertNull(JsonLdParser().parse(html, page, id))
    }

    @Test fun jsonLdForeignIdentityCannotBorrowCanonicalId() {
        val html = "<link rel=canonical href=\"$page\">" + fixture("jsonld-synthetic.html")
            .replace(page, "https://another.example/video/$id")
        assertNull(JsonLdParser().parse(html, page, id))
    }

    @Test fun cappedJsonLdTraversalCannotMakeAnAmbiguousPageAppearSingleVideo() {
        val html = "<link rel=canonical href=\"$page\"><script type=application/ld+json>[" +
            "{\"@type\":\"VideoObject\",\"contentUrl\":\"https://synthetic.douyinvod.com/a.mp4\"}," +
            "0,".repeat(10_100) +
            "{\"@type\":\"VideoObject\",\"contentUrl\":\"https://synthetic.douyinvod.com/b.mp4\"}]</script>"
        val result = parser.parse(html, page, id)
        assertNull(result.media)
        assertTrue(result.attempts.any { it.parser == "JsonLdParser" && it.reason.contains("无法解析") })
    }

    @Test fun malformedOrTooManyJsonLdBlocksPreventAnonymousCanonicalBinding() {
        val anonymous = "<script type=application/ld+json>{\"@type\":\"VideoObject\"," +
            "\"contentUrl\":\"https://synthetic.douyinvod.com/a.mp4\"}</script>"
        val canonical = "<link rel=canonical href=\"$page\">"
        assertNotNull(parser.parse(canonical + anonymous, page, id).media)
        assertNull(parser.parse(canonical + anonymous + "<script type=application/ld+json>{broken}</script>", page, id).media)
        assertNull(parser.parse(canonical + anonymous + "<script type=application/ld+json>{}</script>".repeat(32), page, id).media)
    }

    @Test fun structuredPaywalledMetadataIsNotAccepted() {
        val html = fixture("jsonld-synthetic.html").replace("\"@type\":\"VideoObject\"", "\"@type\":\"VideoObject\",\"requiresSubscription\":true")
        assertNull(JsonLdParser().parse(html, page, id))
    }

    @Test fun openGraphRequiresItsOwnMatchingIdentityAndProgressiveVideo() {
        val html = "<link rel=canonical href=\"$page\"><meta property=og:url content=\"$page\">" +
            "<meta property=og:type content=video.other><meta property=og:video content=\"https://synthetic.douyinvod.com/play\">" +
            "<meta property=og:video:type content=video/mp4>"
        assertNotNull(HtmlMediaParser().parse(html, page, id))
        assertNull(HtmlMediaParser().parse(html.replace("property=og:url", "property=other"), page, id))
    }

    @Test fun nestingLimitAndSizeLimitReturnNoMedia() {
        val nested = "<script>_ROUTER_DATA = " + "[".repeat(65) + "0" + "]".repeat(65) + ";</script>"
        assertNull(parser.parse(nested, page, id).media)
        val oversized = parser.parse(" ".repeat(JsonDocuments.MAX_HTML_CHARS + 1), page, id)
        assertNull(oversized.media)
        assertEquals("PageLimit", oversized.attempts.single().parser)
    }

    @Test fun diagnosticsNeverIncludeMalformedPageValuesOrUrlTokens() {
        val result = parser.parse("<script>_ROUTER_DATA = {\"secret\":\"https://synthetic.douyinvod.com/a.mp4?token=SECRET\",broken};</script>", page, id)
        assertFalse(result.attempts.toString().contains("SECRET"))
        assertFalse(result.attempts.toString().contains("synthetic.douyinvod.com"))
    }

    @Test fun parserCancellationPropagates() {
        val cancelled = object : PageParser {
            override val name = "Cancelled"
            override fun parse(html: String, pageUrl: String, targetId: String): MediaInfo? = throw CancellationException("cancel")
        }
        assertThrows(CancellationException::class.java) { DouyinPageParser(listOf(cancelled)).parse("", page, id) }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/parser/$name")).readText()
}
