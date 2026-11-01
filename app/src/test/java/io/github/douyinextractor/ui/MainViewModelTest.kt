package io.github.douyinextractor.ui

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.diagnostics.BrowserPageEvent
import io.github.douyinextractor.core.browser.BrowserSnapshotResult
import io.github.douyinextractor.core.browser.BrowserRequestTrace
import io.github.douyinextractor.core.browser.BrowserBlockReason
import io.github.douyinextractor.core.extractor.douyin.parser.ParserAttempt
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.model.MediaFormat
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.ResolvedVideoLink
import io.github.douyinextractor.core.network.DouyinHttpResponse
import io.github.douyinextractor.core.network.DouyinTransport
import io.github.douyinextractor.core.network.DouyinUrlResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
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
class MainViewModelTest {
    @Test fun `Bili failures never enter Douyin browser fallback even when automatic browser is enabled`() = runTest(dispatcher) {
        val id = "BV1GJ411x7h7"
        val url = "https://www.bilibili.com/video/$id"
        val model = MainViewModel(resolveLink = { _, _ -> Result.success(ResolvedVideoLink(url, url, id, url, emptyList(), "Bilibili")) },
            extractMedia = { _, _ -> Result.failure(ExtractError.UnsupportedPage) })
        model.receiveShare(url); runCurrent()
        assertTrue(model.state.value.isError)
        assertFalse(model.state.value.browserAvailable)
        assertNull(model.state.value.browserRequest)
        model.startBrowser(); assertNull(model.state.value.browserRequest)
    }

    @Test fun `selecting a Bili part extracts that part and ignores an absent part`() = runTest(dispatcher) {
        val id = "BV1GJ411x7h7"
        val calls = mutableListOf<String>()
        val info = MediaInfo("Bilibili", id, "fixture", null, null, 10000,
            listOf(MediaFormat("https://upos.bilivideo.com/test.mp4", null, null, null, "h264", "video/mp4", null, "test")),
            listOf(io.github.douyinextractor.core.model.VideoPage(1, "one", 1, 10000), io.github.douyinextractor.core.model.VideoPage(2, "two", 2, 10000)))
        val model = MainViewModel(resolveLink = { url, _ -> Result.success(ResolvedVideoLink(url, url, id, url, emptyList(), "Bilibili")) },
            extractMedia = { url, _ -> calls += url; Result.success(info.copy(selectedPage = io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls.page(url))) })
        model.receiveShare("https://www.bilibili.com/video/$id"); runCurrent()
        model.selectPage(999); runCurrent(); assertEquals(1, calls.size)
        model.selectPage(2); runCurrent()
        assertTrue(calls.last().endsWith("?p=2"))
        assertEquals(2, model.state.value.media!!.selectedPage)
        assertFalse(model.state.value.isError)
        assertNull(model.state.value.browserRequest)
    }
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun model(resolve: suspend (String, ResolutionDiagnostics) -> Result<ResolvedVideoLink>) = MainViewModel(
        resolveLink = resolve,
        extractMedia = { _, _ -> Result.failure(ExtractError.UnsupportedPage) },
        autoBrowserInitiallyEnabled = false,
    )

    private fun media(id: String) = MediaInfo("douyin", id, "合成测试", "测试作者", null, 12000, listOf(
        MediaFormat("https://synthetic.douyinvod.com/video.mp4", 720, 1280, 1000000, "h264", "video/mp4", null, "test"),
        MediaFormat("https://synthetic.douyinvod.com/high.mp4", 1080, 1920, 3000000, "h264", "video/mp4", null, "test"),
    ))

    @Test fun `disabled automatic mode keeps manual browser entry after HTTP failure`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.startBrowser()
        assertNull(model.state.value.browserRequest)
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        assertTrue(model.state.value.browserAvailable)
        assertNull(model.state.value.browserRequest)
        model.startBrowser()
        assertEquals("111", model.state.value.browserRequest?.targetId)
        assertEquals("https://www.douyin.com/video/111", model.state.value.browserRequest?.url)
    }

    @Test fun `browser result accepts target media and ignores later closed callback`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(media("111"), "https://www.iesdouyin.com/share/video/111/", listOf(ParserAttempt("BrowserDOM", "已找到资源", 2)), "找到资源"))
        assertEquals("111", model.state.value.media?.id)
        assertNull(model.state.value.browserRequest)
        assertFalse(model.state.value.browserAvailable)
        assertFalse(model.state.value.isError)
        model.finishBrowser(session, "迟到的关闭事件")
        assertFalse(model.state.value.message!!.contains("迟到"))
    }

    @Test fun `reported short share reaches canonical browser and accepts redirected mobile target`() = runTest(dispatcher) {
        val id = "7690790070625783066"
        val resolver = DouyinUrlResolver(DouyinTransport { url ->
            assertEquals("https://v.douyin.com/5A6uFUxGMFA/", url)
            DouyinHttpResponse(302, "https://www.iesdouyin.com/share/video/$id/")
        })
        val model = MainViewModel(resolveLink = resolver::resolve,
            extractMedia = { _, _ -> Result.failure(ExtractError.UnsupportedPage) }, autoBrowserInitiallyEnabled = false)
        model.receiveShare("2.51 复制打开抖音，看看【知物集的作品】测试文案 http://v.douyin.com/5A6uFUxGMFA/ :0pm ipd:/ g@b.an 03/21")
        runCurrent()
        assertEquals(id, model.state.value.resolved?.awemeId)
        assertTrue(model.state.value.message!!.contains("链接已识别"))
        assertTrue(model.state.value.message!!.contains("用网页继续提取"))
        assertNull(model.state.value.browserRequest)
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        val entry = "https://www.douyin.com/video/$id"
        val mobile = "https://m.douyin.com/share/video/$id"
        assertEquals(entry, model.state.value.browserRequest!!.url)
        model.receiveBrowserPage(session, BrowserPageEvent.ENTRY, entry)
        model.receiveBrowserPage(session, BrowserPageEvent.STARTED, "$mobile?token=private-token")
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(media(id), mobile, emptyList(), "目标资源"))
        assertEquals(id, model.state.value.media?.id)
        assertTrue(model.state.value.diagnostics.contains("本机网页打开入口：$entry"))
        assertTrue(model.state.value.diagnostics.contains("本机网页页面开始：$mobile"))
        assertFalse(model.state.value.diagnostics.contains("private-token"))
        assertFalse(model.state.value.isError)
    }

    @Test fun `new browser page clears old page error before its first snapshot`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(null, "https://www.douyin.com/video/111",
            emptyList(), "旧页面错误", ExtractError.WebPlaybackUnavailable))
        model.receiveBrowserPage(session, BrowserPageEvent.ENTRY, "https://www.iesdouyin.com/share/video/111/")
        assertFalse(model.state.value.isError)
        assertFalse(model.state.value.diagnostics.contains("旧页面错误"))
        assertFalse(model.state.value.diagnostics.contains("WebPlaybackUnavailable"))
        model.finishBrowser(session, "已关闭网页")
        assertEquals("已关闭网页", model.state.value.message)
    }

    @Test fun `foreign page and stale navigation callbacks cannot change active report`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        val before = model.state.value
        model.receiveBrowserPage(session - 1, BrowserPageEvent.STARTED, "https://www.douyin.com/video/111")
        model.receiveBrowserPage(session, BrowserPageEvent.STARTED, "https://www.douyin.com/video/222")
        model.receiveBrowserPage(session, BrowserPageEvent.STARTED, "https://www.douyin.com.attacker.example/video/111")
        assertEquals(before, model.state.value)
        model.editInput("new input")
        model.receiveBrowserPage(session, BrowserPageEvent.STARTED, "https://www.douyin.com/video/111")
        assertTrue(model.state.value.diagnostics.isEmpty())
    }

    @Test fun `webpage playback failure stays explicit after browser closes`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(null, "https://www.douyin.com/video/111",
            emptyList(), "页面提示在抖音内观看", ExtractError.WebPlaybackUnavailable))
        assertEquals(ExtractError.WebPlaybackUnavailable.userMessage, model.state.value.message)
        assertTrue(model.state.value.isError)
        model.finishBrowser(session, "已关闭浏览器辅助提取")
        assertNull(model.state.value.browserRequest)
        assertEquals(ExtractError.WebPlaybackUnavailable.userMessage, model.state.value.message)
        assertTrue(model.state.value.isError)
        assertTrue(model.state.value.diagnostics.contains("错误：WebPlaybackUnavailable"))
    }

    @Test fun `later browser checks recover from webpage error and accept target media`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(null, "https://www.douyin.com/video/111",
            emptyList(), "页面提示在抖音内观看", ExtractError.WebPlaybackUnavailable))
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(null, "https://www.douyin.com/video/111",
            emptyList(), "页面正在加载", ExtractError.UnsupportedPage))
        assertFalse(model.state.value.isError)
        assertFalse(model.state.value.diagnostics.contains("WebPlaybackUnavailable"))
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(media("111"), "https://www.douyin.com/video/111",
            emptyList(), "已找到目标资源"))
        assertEquals("111", model.state.value.media?.id)
        assertFalse(model.state.value.isError)
        assertFalse(model.state.value.diagnostics.contains("错误："))
    }

    @Test fun `network diagnostics remain independent and old browser sessions cannot overwrite new ones`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val old = model.state.value.browserRequest!!.sessionId
        val network = BrowserRequestTrace().apply {
            blocked("https://blocked.example/private-path?token=secret", BrowserBlockReason.UNREVIEWED_RESOURCE)
            httpFailure("https://www.douyin.com/aweme/v1/web/aweme/detail/?a_bogus=secret", 403)
        }
        model.receiveBrowserNetwork(old, network)
        model.receiveBrowserSnapshot(old, BrowserSnapshotResult(null, "https://www.douyin.com/video/111",
            emptyList(), "页面提示在抖音内观看", ExtractError.WebPlaybackUnavailable))
        assertTrue(model.state.value.diagnostics.contains("本机网页网络摘要"))
        assertTrue(model.state.value.diagnostics.contains("HTTP 403"))
        assertTrue(model.state.value.diagnostics.contains("blocked.example"))
        assertFalse(model.state.value.diagnostics.contains("secret"))
        assertFalse(model.state.value.diagnostics.contains("private-path"))
        network.networkFailure("https://later.example/private", -2)
        model.finishBrowser(old, "已关闭网页")
        assertFalse(model.state.value.diagnostics.contains("later.example"))
        model.startBrowser()
        val current = model.state.value.browserRequest!!.sessionId
        assertNotEquals(old, current)
        assertFalse(model.state.value.isError)
        assertFalse(model.state.value.diagnostics.contains("本机网页网络摘要"))
        assertFalse(model.state.value.diagnostics.contains("WebPlaybackUnavailable"))
        assertFalse(model.state.value.diagnostics.contains("页面提示在抖音内观看"))
        model.receiveBrowserNetwork(old, network)
        model.receiveBrowserSnapshot(old, BrowserSnapshotResult(null, "https://www.douyin.com/video/111",
            emptyList(), "过期错误", ExtractError.WebPlaybackUnavailable))
        model.finishBrowser(old, "过期关闭")
        assertEquals(current, model.state.value.browserRequest!!.sessionId)
        assertFalse(model.state.value.isError)
        assertFalse(model.state.value.diagnostics.contains("blocked.example"))
        assertFalse(model.state.value.diagnostics.contains("过期"))
        model.receiveBrowserNetwork(current, BrowserRequestTrace().apply {
            networkFailure("https://current.example/?token=hidden", -2)
        })
        assertTrue(model.state.value.diagnostics.contains("current.example"))
        assertFalse(model.state.value.diagnostics.contains("hidden"))
    }

    @Test fun `browser empty or foreign result does not change recognized identity or claim success`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(media("111"), "https://www.douyin.com/video/222", emptyList(), "其他作品"))
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(media("222"), "https://www.douyin.com/video/111", emptyList(), "其他作品"))
        assertNull(model.state.value.media)
        assertEquals("111", model.state.value.resolved?.awemeId)
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(null, "https://www.douyin.com/video/111", emptyList(), "视频元素为0"))
        assertTrue(model.state.value.diagnostics.contains("视频元素为0"))
        assertNull(model.state.value.media)
    }

    @Test fun `editing invalidates open browser and all late callbacks`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        model.editInput("新的分享")
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(media("111"), "https://www.douyin.com/video/111", emptyList(), "找到资源"))
        model.finishBrowser(session, "旧浏览器已关闭")
        assertEquals("新的分享", model.state.value.input)
        assertNull(model.state.value.browserRequest)
        assertNull(model.state.value.media)
        assertTrue(model.state.value.diagnostics.isEmpty())
    }

    @Test fun `reopened browser ignores stale previous session and does not leak diagnostic queries`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val old = model.state.value.browserRequest!!.sessionId
        model.finishBrowser(old, "已关闭网页")
        model.startBrowser()
        val current = model.state.value.browserRequest!!.sessionId
        assertNotEquals(old, current)
        model.receiveBrowserSnapshot(old, BrowserSnapshotResult(media("111"), "https://www.douyin.com/video/111", emptyList(), "找到资源"))
        assertNull(model.state.value.media)
        model.receiveBrowserSnapshot(current, BrowserSnapshotResult(null, "https://www.douyin.com/video/111?token=private-token", emptyList(), "等待页面"))
        assertFalse(model.state.value.diagnostics.contains("private-token"))
        assertEquals(current, model.state.value.browserRequest?.sessionId)
    }

    @Test fun `missing browser capture explains failure and cancellation dismisses window`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.startBrowser()
        val session = model.state.value.browserRequest!!.sessionId
        model.receiveBrowserSnapshot(session, BrowserSnapshotResult(null, null, emptyList(), "网页数据超过限制"))
        assertTrue(model.state.value.diagnostics.contains("网页数据超过限制"))
        model.cancel()
        assertNull(model.state.value.browserRequest)
        assertFalse(model.state.value.browserAvailable)
    }

    @Test fun `login and missing video do not offer browser as permission workaround`() = runTest(dispatcher) {
        for (error in listOf(ExtractError.LoginRequired, ExtractError.VideoNotFound)) {
            val model = MainViewModel(resolveLink = { _, _ -> Result.success(resolved("111")) }, extractMedia = { _, _ -> Result.failure(error) })
            model.receiveShare("https://www.douyin.com/video/111")
            runCurrent()
            assertFalse(model.state.value.browserAvailable)
            model.startBrowser()
            assertNull(model.state.value.browserRequest)
        }
    }

    @Test fun `share extracts matching media and selects a recommended format`() = runTest(dispatcher) {
        val model = MainViewModel(
            resolveLink = { _, _ -> Result.success(resolved("111")) },
            extractMedia = { url, _ -> assertEquals("https://www.douyin.com/video/111", url); Result.success(media("111")) },
        )
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        assertFalse(model.state.value.isError)
        assertEquals(2, model.state.value.media?.formats?.size)
        assertNull(model.state.value.browserRequest)
        assertEquals(1, model.state.value.selectedFormat)
        model.selectFormat(0)
        assertEquals(0, model.state.value.selectedFormat)
        model.selectFormat(20)
        assertEquals(0, model.state.value.selectedFormat)
        assertTrue(model.state.value.diagnostics.contains("MediaFormat：2"))
        assertFalse(model.state.value.diagnostics.contains("synthetic.douyinvod"))
    }

    @Test fun `empty or different video result never claims extraction success`() = runTest(dispatcher) {
        for (result in listOf(media("222"), media("111").copy(formats = emptyList()))) {
            val model = MainViewModel(
                resolveLink = { _, _ -> Result.success(resolved("111")) },
                extractMedia = { _, _ -> Result.success(result) },
            )
            model.receiveShare("https://www.douyin.com/video/111")
            runCurrent()
            assertFalse(model.state.value.isError)
            assertNull(model.state.value.media)
            assertEquals("111", model.state.value.resolved?.awemeId)
            assertEquals("111", model.state.value.browserRequest?.targetId)
        }
    }

    @Test fun `stale extraction cannot overwrite new share or its diagnostics`() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>()
        val model = MainViewModel(
            resolveLink = { url, _ -> Result.success(resolved(url.substringAfterLast('/'))) },
            extractMedia = { url, _ ->
                val id = url.substringAfterLast('/')
                if (id == "111") withContext(NonCancellable) { old.await() }
                Result.success(media(id))
            },
        )
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.receiveShare("https://www.douyin.com/video/222")
        runCurrent()
        old.complete(Unit)
        runCurrent()
        assertEquals("222", model.state.value.media?.id)
        assertFalse(model.state.value.diagnostics.contains("111"))
    }

    @Test fun `extraction cancellation stops request and retains recognized identity`() = runTest(dispatcher) {
        var cancelled = false
        val model = MainViewModel(
            resolveLink = { _, _ -> Result.success(resolved("111")) },
            extractMedia = { _, _ -> try { awaitCancellation() } finally { cancelled = true } },
        )
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertNull(model.state.value.media)
        assertEquals("111", model.state.value.resolved?.awemeId)
        assertFalse(model.state.value.busy)
    }

    @Test fun `extraction timeout retains ID and reports useful failure`() = runTest(dispatcher) {
        val model = MainViewModel(
            resolveLink = { _, _ -> Result.success(resolved("111")) },
            extractMedia = { _, _ -> awaitCancellation() },
        )
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(model.state.value.isError)
        assertEquals("111", model.state.value.resolved?.awemeId)
        assertNull(model.state.value.media)
        assertTrue(model.state.value.diagnostics.contains("60 秒"))
    }

    private fun resolved(id: String) = ResolvedVideoLink(
        "https://www.douyin.com/video/$id", "https://www.douyin.com/video/$id", id,
        "https://www.douyin.com/video/$id", emptyList(),
    )

    @Test fun `incoming share automatically identifies link without claiming media success`() = runTest(dispatcher) {
        var calls = 0
        val model = model { url, _ ->
            calls++
            assertEquals("https://www.douyin.com/video/7691307792850981055", url)
            Result.success(resolved("7691307792850981055"))
        }
        model.receiveShare("复制打开抖音 https://www.douyin.com/video/7691307792850981055 08/01")
        assertTrue(model.state.value.busy)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("7691307792850981055", model.state.value.resolved?.awemeId)
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.isError)
        assertNull(model.state.value.media)
        assertTrue(model.state.value.diagnostics.contains("MediaFormat：0"))
    }

    @Test fun `invalid share never sends a network request`() = runTest(dispatcher) {
        val model = model { _, _ -> fail("Network must not be called"); Result.success(resolved("1")) }
        model.receiveShare("https://douyin.com.attacker.example/video/123")
        runCurrent()
        assertTrue(model.state.value.isError)
        assertNull(model.state.value.resolved)
    }

    @Test fun `new share cannot be overwritten by an older noncooperative completion`() = runTest(dispatcher) {
        val old = CompletableDeferred<Unit>()
        val model = model { url, _ ->
            val id = url.substringAfterLast('/')
            if (id == "111") withContext(NonCancellable) { old.await() }
            Result.success(resolved(id))
        }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.receiveShare("https://www.douyin.com/video/222")
        runCurrent()
        assertEquals("222", model.state.value.resolved?.awemeId)
        old.complete(Unit)
        runCurrent()
        assertEquals("222", model.state.value.resolved?.awemeId)
        assertFalse(model.state.value.busy)
    }

    @Test fun `cancel propagates and clears loading state`() = runTest(dispatcher) {
        var cancelled = false
        val model = model { _, _ -> try { awaitCancellation() } finally { cancelled = true } }
        model.receiveShare("https://v.douyin.com/example/")
        runCurrent()
        model.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(model.state.value.busy)
        assertEquals("已取消解析", model.state.value.message)
        assertNull(model.state.value.resolved)
    }

    @Test fun `editing clears a previous identity and its diagnostics`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.success(resolved("111")) }
        model.receiveShare("https://www.douyin.com/video/111")
        runCurrent()
        model.editInput("新的视频分享内容")
        assertNull(model.state.value.resolved)
        assertTrue(model.state.value.diagnostics.isEmpty())
    }

    @Test fun `timeout leaves a useful failure instead of permanent loading`() = runTest(dispatcher) {
        val model = model { _, _ -> awaitCancellation() }
        model.receiveShare("https://v.douyin.com/example/")
        runCurrent()
        advanceTimeBy(25_001)
        runCurrent()
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.isError)
        assertEquals(ExtractError.PageLoadFailed.userMessage, model.state.value.message)
        assertTrue(model.state.value.diagnostics.contains("25 秒"))
    }

    @Test fun `platform refusal never presents an identified video`() = runTest(dispatcher) {
        val model = model { _, _ -> Result.failure(ExtractError.HttpError(403)) }
        model.receiveShare("https://v.douyin.com/example/")
        runCurrent()
        assertTrue(model.state.value.isError)
        assertNull(model.state.value.resolved)
        assertTrue(model.state.value.message!!.contains("拒绝"))
        assertTrue(model.state.value.diagnostics.contains("HttpError"))
    }

    @Test fun `oversized incoming text is rejected rather than truncating into a different link`() = runTest(dispatcher) {
        val model = model { _, _ -> fail("Network must not be called"); Result.success(resolved("1")) }
        model.receiveShare("x".repeat(MainViewModel.MAX_INPUT) + "https://v.douyin.com/example/")
        runCurrent()
        assertTrue(model.state.value.isError)
        assertTrue(model.state.value.input.isEmpty())
        assertTrue(model.state.value.message!!.contains("过长"))
    }
}

