package io.github.douyinextractor.ui

import io.github.douyinextractor.core.browser.BrowserSnapshotResult
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.MediaFormat
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.model.ResolvedVideoLink
import io.github.douyinextractor.core.network.DouyinHttpResponse
import io.github.douyinextractor.core.network.DouyinTransport
import io.github.douyinextractor.core.network.DouyinUrlResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticBrowserTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun resolved(id: String) = ResolvedVideoLink("https://www.douyin.com/video/$id",
        "https://www.douyin.com/video/$id", id, "https://www.douyin.com/video/$id", emptyList())
    private fun model(error: ExtractError = ExtractError.UnsupportedPage) = MainViewModel(
        resolveLink = { url, _ -> Result.success(resolved(url.substringAfterLast('/'))) },
        extractMedia = { _, _ -> Result.failure(error) },
    )

    @Test fun `first parse or incoming share enters browser without intermediate failure or second click`() = runTest(dispatcher) {
        for (incomingShare in listOf(false, true)) {
            var resolveCalls = 0
            var extractCalls = 0
            val id = "7691299102264413480"
            val resolver = DouyinUrlResolver(DouyinTransport { url ->
                resolveCalls++
                assertEquals("https://v.douyin.com/3UxwpV4kkUo/", url)
                DouyinHttpResponse(302, "https://www.iesdouyin.com/share/video/$id/")
            })
            val model = MainViewModel(resolveLink = resolver::resolve,
                extractMedia = { _, _ -> extractCalls++; Result.failure(ExtractError.UnsupportedPage) })
            val observed = mutableListOf<HomeState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect { observed += it } }
            val share = "6.66 复制打开抖音，看看【鸣潮的作品】共鸣者瞬间 | 心 御者，同我赴这场朝月喜事吧。 ... https://v.douyin.com/3UxwpV4kkUo/ :2pm 10/10 eba:/ o@q.RX"
            if (incomingShare) model.receiveShare(share) else { model.editInput(share); model.parse() }
            runCurrent()
            assertEquals(1, resolveCalls)
            assertEquals(1, extractCalls)
            assertEquals(id, model.state.value.browserRequest?.targetId)
            assertEquals("https://www.douyin.com/video/$id", model.state.value.browserRequest?.url)
            assertTrue(observed.none { it.isError })
            assertTrue(model.state.value.message!!.contains("自动进入网页"))
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.media)
            assertFalse(model.state.value.diagnostics.contains("\n错误："))
            assertTrue(model.state.value.diagnostics.contains("普通页面步骤：UnsupportedPage"))
            val session = model.state.value.browserRequest!!.sessionId
            model.startBrowser() // A duplicate tap cannot create a second browser session.
            assertEquals(session, model.state.value.browserRequest!!.sessionId)
            val media = MediaInfo("Douyin", id, "合成回归数据", null, null, 22_000, listOf(
                MediaFormat("https://synthetic.douyinvod.com/test.mp4", 1080, 1920, null, null, "video/mp4", null, "test")))
            model.receiveBrowserSnapshot(session, BrowserSnapshotResult(media,
                "https://m.douyin.com/share/video/$id", emptyList(), "目标匹配"))
            assertEquals(id, model.state.value.media?.id)
            assertNull(model.state.value.browserRequest)
            assertFalse(model.state.value.isError)
        }
    }

    @Test fun `closing automatic browser does not automatically reopen and manual retry has new session`() = runTest(dispatcher) {
        val model = model()
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        val first = model.state.value.browserRequest!!.sessionId
        model.finishBrowser(first, "已关闭网页")
        advanceTimeBy(120_000)
        runCurrent()
        assertNull(model.state.value.browserRequest)
        assertTrue(model.state.value.browserAvailable)
        model.startBrowser()
        val second = model.state.value.browserRequest!!.sessionId
        assertNotEquals(first, second)
        model.finishBrowser(first, "迟到的关闭")
        assertEquals(second, model.state.value.browserRequest!!.sessionId)
    }

    @Test fun `old noncooperative HTTP failure cannot reopen browser over a new share`() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>()
        val model = MainViewModel(
            resolveLink = { url, _ -> Result.success(resolved(url.substringAfterLast('/'))) },
            extractMedia = { url, _ ->
                if (url.endsWith("111")) withContext(NonCancellable) { old.await() }
                Result.failure(ExtractError.UnsupportedPage)
            },
        )
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.receiveShare("https://www.douyin.com/video/222")
        runCurrent()
        val current = model.state.value.browserRequest!!
        old.complete(Unit)
        runCurrent()
        assertEquals("222", current.targetId)
        assertEquals(current, model.state.value.browserRequest)
        assertFalse(model.state.value.diagnostics.contains("111"))
    }

    @Test fun `cancel or editing prevents late automatic handoff`() = runTest(dispatcher) {
        for (edit in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val model = MainViewModel(resolveLink = { _, _ -> Result.success(resolved("111")) },
                extractMedia = { _, _ ->
                    withContext(NonCancellable) { release.await() }
                    Result.failure(ExtractError.UnsupportedPage)
                })
            model.receiveShare("https://www.douyin.com/video/111")
            runCurrent()
            if (edit) model.editInput("changed") else model.cancel()
            release.complete(Unit)
            runCurrent()
            assertNull(model.state.value.browserRequest)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.media)
        }
    }

    @Test fun `turning automatic mode off during request is respected and survives new input`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val model = MainViewModel(resolveLink = { _, _ -> Result.success(resolved("111")) },
            extractMedia = { _, _ -> release.await(); Result.failure(ExtractError.UnsupportedPage) })
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.setAutoBrowserEnabled(false)
        release.complete(Unit)
        runCurrent()
        assertNull(model.state.value.browserRequest)
        assertTrue(model.state.value.browserAvailable)
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        assertFalse(model.autoBrowserEnabled.value)
        assertNull(model.state.value.browserRequest)
        model.startBrowser()
        assertNotNull(model.state.value.browserRequest)
    }

    @Test fun `only page parsing and verification failures automatically continue`() = runTest(dispatcher) {
        for (error in listOf(ExtractError.UnsupportedPage, ExtractError.VerificationRequired,
            ExtractError.ParserError("test", "synthetic"))) {
            val model = model(error)
            model.receiveShare("https://www.douyin.com/video/111")
            runCurrent()
            assertNotNull(error.javaClass.simpleName, model.state.value.browserRequest)
            assertFalse(model.state.value.isError)
        }
        for (error in listOf(ExtractError.InvalidUrl, ExtractError.LoginRequired, ExtractError.VideoNotFound,
            ExtractError.PageLoadFailed, ExtractError.HttpError(403), ExtractError.HttpError(429))) {
            val model = model(error)
            model.receiveShare("https://www.douyin.com/video/111")
            runCurrent()
            assertNull(error.javaClass.simpleName, model.state.value.browserRequest)
            assertTrue(model.state.value.isError)
        }
        val unresolved = MainViewModel(resolveLink = { _, _ -> Result.failure(ExtractError.UnsupportedPage) },
            extractMedia = { _, _ -> error("Must not extract without an identity") })
        unresolved.receiveShare("https://v.douyin.com/test/")
        runCurrent()
        assertNull(unresolved.state.value.browserRequest)
    }

    @Test fun `real webpage failure after automatic handoff remains an error after close`() = runTest(dispatcher) {
        val model = model()
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        val session = model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(null,
            "https://www.douyin.com/video/111", emptyList(), "官方错误页", ExtractError.WebPlaybackUnavailable))
        model.finishBrowser(session, "已关闭网页")
        assertNull(model.state.value.media)
        assertTrue(model.state.value.isError)
        assertEquals(ExtractError.WebPlaybackUnavailable.userMessage, model.state.value.message)
        assertTrue(model.state.value.diagnostics.contains("错误：WebPlaybackUnavailable"))
        runCurrent()
        assertNull(model.state.value.browserRequest)
    }
}
