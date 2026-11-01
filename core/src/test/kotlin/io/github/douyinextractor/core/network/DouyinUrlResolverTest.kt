package io.github.douyinextractor.core.network

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.model.ExtractError
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DouyinUrlResolverTest {
    @Test fun `direct video resolves without a network request`() = runTest {
        val transport = FakeTransport { error("Direct video URLs must not be fetched") }
        val result = DouyinUrlResolver(transport).resolve(VIDEO).getOrThrow()
        assertEquals(ID, result.awemeId)
        assertEquals(VIDEO, result.canonicalUrl)
        assertEquals(VIDEO, result.finalUrl)
        assertTrue(result.redirects.isEmpty())
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `all supported redirect status codes resolve an ID without fetching its page`() = runTest {
        for (status in listOf(301, 302, 303, 307, 308)) {
            val transport = FakeTransport { DouyinHttpResponse(status, VIDEO, "text/html; charset=utf-8") }
            val resolved = DouyinUrlResolver(transport).resolve(SHORT).getOrThrow()
            assertEquals(listOf(SHORT), transport.requests)
            assertEquals(ID, resolved.awemeId)
            assertEquals(status, resolved.redirects.single().status)
            assertEquals("text/html", resolved.redirects.single().contentType)
        }
    }

    @Test fun `relative locations and protocol relative trusted destinations work`() = runTest {
        val transport = FakeTransport { url ->
            when (url) {
                SHORT -> DouyinHttpResponse(302, "/next/")
                "https://v.douyin.com/next/" -> DouyinHttpResponse(307, "//www.iesdouyin.com/share/video/$ID/")
                else -> error("Unexpected request")
            }
        }
        val resolved = DouyinUrlResolver(transport).resolve(SHORT).getOrThrow()
        assertEquals(ID, resolved.awemeId)
        assertEquals(VIDEO, resolved.canonicalUrl)
        assertEquals(2, transport.requests.size)
        assertEquals(2, resolved.redirects.size)
        assertEquals("https://v.douyin.com/next/", resolved.redirects.first().location)
        assertEquals("https://www.iesdouyin.com/share/video/$ID/", resolved.redirects.last().location)
    }

    @Test fun `input http can be upgraded before the initial request`() = runTest {
        val transport = FakeTransport { DouyinHttpResponse(302, VIDEO) }
        assertTrue(DouyinUrlResolver(transport).resolve(SHORT.replace("https:", "http:")).isSuccess)
        assertEquals(listOf(SHORT), transport.requests)
    }

    @Test fun `intermediate server query state is preserved while diagnostics omit it`() = runTest {
        val intermediate = "https://www.douyin.com/redirect?share_sign=required-secret&share_id=routing-secret&token=opaque-secret"
        val transport = FakeTransport { url ->
            when (url) {
                SHORT -> DouyinHttpResponse(302, "$intermediate#discard-fragment")
                intermediate -> DouyinHttpResponse(302, VIDEO)
                else -> error("Server redirect query state was changed: $url")
            }
        }
        val resolved = DouyinUrlResolver(transport).resolve(SHORT).getOrThrow()
        assertEquals(listOf(SHORT, intermediate), transport.requests)
        assertEquals(ID, resolved.awemeId)
        assertFalse(resolved.redirects.toString().contains("secret"))
        assertFalse(resolved.redirects.toString().contains("share_sign="))
    }

    @Test fun `query only redirect retains the final path segment`() = runTest {
        val initial = "https://v.douyin.com/start"
        val intermediate = "$initial?token=required-secret"
        val transport = FakeTransport { url ->
            when (url) {
                initial -> DouyinHttpResponse(302, "?token=required-secret")
                intermediate -> DouyinHttpResponse(302, VIDEO)
                else -> error("Query-only redirect changed the path: $url")
            }
        }
        val resolved = DouyinUrlResolver(transport).resolve(initial).getOrThrow()
        assertEquals(listOf(initial, intermediate), transport.requests)
        assertEquals(initial, resolved.redirects.first().location)
        assertFalse(resolved.redirects.toString().contains("required-secret"))
    }

    @Test fun `parent relative redirect resolves path before checking the next hop`() = runTest {
        val transport = FakeTransport { url ->
            when (url) {
                SHORT -> DouyinHttpResponse(302, "https://www.iesdouyin.com/share/intermediate/start?token=secret")
                "https://www.iesdouyin.com/share/intermediate/start?token=secret" ->
                    DouyinHttpResponse(302, "../video/$ID/")
                else -> error("Unexpected request")
            }
        }
        val resolved = DouyinUrlResolver(transport).resolve(SHORT).getOrThrow()
        assertEquals(ID, resolved.awemeId)
        assertEquals("https://www.iesdouyin.com/share/video/$ID/", resolved.redirects.last().location)
        assertEquals(2, transport.requests.size)
    }

    @Test fun `unsafe input is rejected without any requests`() = runTest {
        val transport = FakeTransport { error("Rejected input must not be fetched") }
        for (url in listOf(
            "https://douyin.com.evil.example/video/$ID",
            "https://evil.example/",
            "https://name:password@v.douyin.com/start/",
            "https://v.douyin.com:8443/start/",
            "file:///tmp/video",
            "https://127.0.0.1/start/",
        )) {
            assertEquals(ExtractError.InvalidUrl, DouyinUrlResolver(transport).resolve(url).exceptionOrNull())
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `unsupported landing and profile inputs do not fetch`() = runTest {
        val transport = FakeTransport { error("Unsupported input must not be fetched") }
        for (url in listOf("https://www.douyin.com/", "https://www.douyin.com/user/example")) {
            assertEquals(ExtractError.UnsupportedPage, DouyinUrlResolver(transport).resolve(url).exceptionOrNull())
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `unsafe redirect destinations are rejected before fetching even with a video ID`() = runTest {
        for (location in listOf(
            "https://evil.example/video/$ID",
            "https://www.douyin.com.evil.example/video/$ID",
            "//evil.example/video/$ID",
            "http://www.douyin.com/video/$ID",
            "https://user:password@www.douyin.com/video/$ID",
            "https://@www.douyin.com/video/$ID",
            "//@www.douyin.com/video/$ID",
            "///www.douyin.com/video/$ID",
            "https:/www.douyin.com/video/$ID",
            "https://www.%64ouyin.com/video/$ID",
            "https://www.douyin.com./video/$ID",
            "https://www.douyin.com:8443/video/$ID",
            "https://www.douyin.com:0443/video/$ID",
            "https:\\www.douyin.com/video/$ID",
            "https://127.0.0.1/video/$ID",
            "file:///video/$ID",
            "javascript:alert(1)",
        )) {
            val transport = FakeTransport { DouyinHttpResponse(302, location) }
            assertEquals(ExtractError.RedirectFailed, DouyinUrlResolver(transport).resolve(SHORT).exceptionOrNull())
            assertEquals(listOf(SHORT), transport.requests)
        }
    }

    @Test fun `missing empty and malformed locations fail clearly`() = runTest {
        for (location in listOf(null, "", "  ", "://", "https://[broken", "https://www.douyin.com:bad/", "/bad path/")) {
            val transport = FakeTransport { DouyinHttpResponse(302, location) }
            assertEquals(ExtractError.RedirectFailed, DouyinUrlResolver(transport).resolve(SHORT).exceptionOrNull())
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun `self redirects and multi hop loops stop before another repeated request`() = runTest {
        val self = FakeTransport { DouyinHttpResponse(302, SHORT + "#same") }
        assertEquals(ExtractError.RedirectFailed, DouyinUrlResolver(self).resolve(SHORT).exceptionOrNull())
        assertEquals(1, self.requests.size)
        val loop = FakeTransport { url ->
            DouyinHttpResponse(302, if (url == SHORT) "https://v.douyin.com/next/" else SHORT)
        }
        assertEquals(ExtractError.RedirectFailed, DouyinUrlResolver(loop).resolve(SHORT).exceptionOrNull())
        assertEquals(2, loop.requests.size)
    }

    @Test fun `eight redirects are accepted and longer chains are bounded`() = runTest {
        var acceptedIndex = 0
        val accepted = FakeTransport {
            acceptedIndex++
            DouyinHttpResponse(302, if (acceptedIndex == 8) VIDEO else "https://v.douyin.com/hop$acceptedIndex/")
        }
        assertTrue(DouyinUrlResolver(accepted).resolve(SHORT).isSuccess)
        assertEquals(8, accepted.requests.size)
        var rejectedIndex = 0
        val rejected = FakeTransport { DouyinHttpResponse(302, "https://v.douyin.com/hop${++rejectedIndex}/") }
        assertEquals(ExtractError.RedirectFailed, DouyinUrlResolver(rejected).resolve(SHORT).exceptionOrNull())
        assertEquals(9, rejected.requests.size)
    }

    @Test fun `successful HTTP without an ID does not claim a deleted video`() = runTest {
        val transport = FakeTransport { DouyinHttpResponse(200, contentType = "text/html") }
        assertEquals(ExtractError.UnsupportedPage, DouyinUrlResolver(transport).resolve(SHORT).exceptionOrNull())
    }

    @Test fun `explicit missing and login responses are typed`() = runTest {
        for (status in listOf(404, 410)) {
            val transport = FakeTransport { DouyinHttpResponse(status) }
            assertEquals(ExtractError.VideoNotFound, DouyinUrlResolver(transport).resolve(SHORT).exceptionOrNull())
        }
        val login = FakeTransport { DouyinHttpResponse(401) }
        assertEquals(ExtractError.LoginRequired, DouyinUrlResolver(login).resolve(SHORT).exceptionOrNull())
    }

    @Test fun `other HTTP failures retain only their status`() = runTest {
        for (status in listOf(300, 304, 403, 429, 500, 503)) {
            val transport = FakeTransport { DouyinHttpResponse(status) }
            assertEquals(ExtractError.HttpError(status), DouyinUrlResolver(transport).resolve(SHORT).exceptionOrNull())
        }
    }

    @Test fun `network failure is typed and exception secrets do not enter diagnostics`() = runTest {
        val diagnostics = ResolutionDiagnostics()
        val transport = FakeTransport { throw IOException("Cookie: private-secret") }
        assertEquals(ExtractError.PageLoadFailed, DouyinUrlResolver(transport).resolve(SHORT, diagnostics).exceptionOrNull())
        assertFalse(diagnostics.notes().joinToString().contains("private-secret"))
        assertTrue(diagnostics.steps().isEmpty())
    }

    @Test fun `redirect trace redacts queries before exposing it`() = runTest {
        val transport = FakeTransport { DouyinHttpResponse(302, "$VIDEO?token=private-secret&share_id=private-secret") }
        val resolved = DouyinUrlResolver(transport).resolve("$SHORT?token=private-secret").getOrThrow()
        assertFalse(resolved.redirects.toString().contains("private-secret"))
        assertFalse(resolved.redirects.toString().contains("token="))
    }

    @Test fun `cancellation thrown by transport propagates`() = runTest {
        val resolver = DouyinUrlResolver(DouyinTransport { throw CancellationException("cancelled") })
        try {
            resolver.resolve(SHORT)
            fail("Cancellation must be thrown, not returned as a generic failure")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test fun `cancelling resolution cancels suspended transport`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var transportCancelled = false
        val resolver = DouyinUrlResolver(DouyinTransport {
            entered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                error("Unreachable")
            } finally {
                transportCancelled = true
            }
        })
        val pending = async { resolver.resolve(SHORT) }
        runCurrent()
        assertTrue(entered.isCompleted)
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(transportCancelled)
    }

    private class FakeTransport(private val answer: (String) -> DouyinHttpResponse) : DouyinTransport {
        val requests = mutableListOf<String>()
        override suspend fun fetch(url: String): DouyinHttpResponse {
            requests += url
            return answer(url)
        }
    }

    companion object {
        private const val ID = "7691949525241531674"
        private const val VIDEO = "https://www.douyin.com/video/$ID"
        private const val SHORT = "https://v.douyin.com/start/"
    }
}
