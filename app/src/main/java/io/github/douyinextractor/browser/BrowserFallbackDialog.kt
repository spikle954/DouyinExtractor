package io.github.douyinextractor.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.douyinextractor.core.browser.BrowserCaptureScript
import io.github.douyinextractor.core.browser.BrowserBlockReason
import io.github.douyinextractor.core.browser.BrowserLocalBlock
import io.github.douyinextractor.core.browser.BrowserRequestTrace
import io.github.douyinextractor.core.browser.BrowserSnapshotParser
import io.github.douyinextractor.core.browser.BrowserSnapshotResult
import io.github.douyinextractor.core.browser.BrowserUrlPolicy
import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.browser.KuaishouBrowserPolicy
import io.github.douyinextractor.core.browser.KuaishouBrowserCapture
import io.github.douyinextractor.core.browser.KuaishouBrowserParser
import io.github.douyinextractor.core.extractor.social.SocialUrls
import io.github.douyinextractor.core.extractor.social.SocialHttpClient
import io.github.douyinextractor.core.diagnostics.BrowserPageEvent
import io.github.douyinextractor.core.model.ExtractError
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream

data class BrowserRequest(val sessionId: Long, val url: String, val targetId: String, val platform: String = "Douyin")

/** Visible, explicitly requested browser session; there is no JavaScript/native bridge. */
@Composable
fun BrowserFallbackDialog(
    request: BrowserRequest,
    onSnapshot: (Long, BrowserSnapshotResult) -> Unit,
    onFinished: (Long, String) -> Unit,
    onNetwork: (Long, BrowserRequestTrace) -> Unit,
    onPage: (Long, BrowserPageEvent, String) -> Unit,
) {
    val context = LocalContext.current
    val platformLabel = if (request.platform == SocialUrls.KUAISHOU) "快手" else "抖音"
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snapshotCallback by rememberUpdatedState(onSnapshot)
    val finishedCallback by rememberUpdatedState(onFinished)
    val networkCallback by rememberUpdatedState(onNetwork)
    val pageCallback by rememberUpdatedState(onPage)
    val controller = remember(request.sessionId) {
        BrowserController(context, request,
            { snapshotCallback(request.sessionId, it) },
            { finishedCallback(request.sessionId, it) },
            { networkCallback(request.sessionId, it) },
            { event, url -> pageCallback(request.sessionId, event, url) })
    }
    DisposableEffect(controller, lifecycle) {
        controller.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ ->
            controller.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }
    Dialog(
        onDismissRequest = { controller.finish("已关闭浏览器辅助提取") },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(8.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("在本机浏览器中提取", style = MaterialTheme.typography.titleLarge)
                Text("等待${platformLabel}页面加载；如出现验证，请手动完成。仅查看当前公开视频，无需登录。", style = MaterialTheme.typography.bodySmall)
                Text("关闭时清理浏览器数据；异常退出后的残留会在下次打开前清理。网页请求由${platformLabel}及其服务提供。", style = MaterialTheme.typography.bodySmall)
                Text(controller.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                AndroidView(factory = { controller.createView() }, modifier = Modifier.fillMaxWidth().weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = controller::switchPage, enabled = controller.ready && !controller.terminal) {
                        Text(if (controller.videoPageEntry) "尝试分享页入口" else "尝试视频页入口")
                    }
                    if (request.platform == SocialUrls.KUAISHOU) TextButton(onClick = controller::zoomOut, enabled = controller.ready && !controller.terminal) {
                        Text("缩小网页")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { controller.finish("已关闭浏览器辅助提取") }) { Text("关闭") }
                    Button(onClick = controller::inspect, enabled = controller.ready && !controller.terminal && !controller.checking) { Text("检查视频资源") }
                }
            }
        }
    }
}

private class BrowserController(
    private val context: Context,
    private val request: BrowserRequest,
    private val onSnapshot: (BrowserSnapshotResult) -> Unit,
    private val onFinished: (String) -> Unit,
    private val onNetwork: (BrowserRequestTrace) -> Unit,
    private val onPage: (BrowserPageEvent, String) -> Unit,
) {
    private val kuaishou = request.platform == SocialUrls.KUAISHOU
    private val platformLabel = if (kuaishou) "快手" else "抖音"
    private val canonical = if (kuaishou) SocialUrls.canonical(SocialUrls.KUAISHOU, request.targetId) else DouyinUrls.canonical(request.targetId)
    private val shareEntry = if (kuaishou) KuaishouBrowserPolicy.sharePage(request.targetId, request.url) else "https://www.iesdouyin.com/share/video/${request.targetId}/"
    var status by mutableStateOf("正在准备临时浏览器…")
        private set
    var ready by mutableStateOf(false)
        private set
    var terminal by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var videoPageEntry by mutableStateOf(request.url.substringBefore('?') == canonical)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var webView: WebView? = null
    private var disposed = false
    private var finished = false
    private var resumed = false
    private var pageGeneration = 0L
    private var lastResultKey: String? = null
    private var providerFailure: String? = null
    private val requestTrace = BrowserRequestTrace()
    private val localBlock = BrowserLocalBlock()
    private var pollingPaused = false
    private var pageStartedAt = SystemClock.elapsedRealtime()
    private var verificationWaiting = false
    private var verificationStartedAt: Long? = null
    private var currentMainPageUrl = request.url

    @SuppressLint("SetJavaScriptEnabled")
    fun createView(): View {
        val frame = FrameLayout(context)
        try {
            // Recent WebView providers otherwise enable inspection automatically in debug APKs.
            WebView.setWebContentsDebuggingEnabled(false)
            val browser = WebView(context)
            webView = browser
            browser.settings.apply {
                if (kuaishou) {
                    userAgentString = SocialHttpClient.USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                setAllowFileAccessFromFileURLs(false)
                @Suppress("DEPRECATION")
                setAllowUniversalAccessFromFileURLs(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
            // This application owns only this WebView; workers must not bypass its request policy.
            ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            browser.webViewClient = client()
            browser.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(permission: PermissionRequest) = permission.deny()
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
                override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                    callback?.onReceiveValue(null)
                    return true
                }
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean = true
            }
            browser.setDownloadListener { _, _, _, _, _ ->
                if (!disposed) status = "请在提取完成后返回拾影，点击「下载视频」保存"
            }
            frame.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (resumed) {
                browser.resumeTimers()
                browser.onResume()
            } else {
                browser.onPause()
                browser.pauseTimers()
            }
            scope.launch {
                try {
                    BrowserSessionData.useSession {
                        ensureActive()
                        if (!disposed && !terminal) {
                            browser.clearCache(true)
                            ready = true
                            load(request.url)
                        }
                        awaitCancellation()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    finish("浏览器初始化失败，请更新 Android System WebView 后重试")
                }
            }
            scope.launch {
                while (isActive && !terminal) {
                    delay(2_000)
                    if (pollingPaused) continue
                    val now = SystemClock.elapsedRealtime()
                    if (kuaishou && verificationWaiting) {
                        if (now - (verificationStartedAt ?: now) >= 600_000) finish("安全验证等待已结束；可重新打开分享页后手动验证")
                        else if (resumed) inspectPage()
                    } else if (now - pageStartedAt >= 90_000) {
                        finish("浏览器等待已结束，仍未找到当前视频资源；可重新尝试")
                    } else if (resumed) inspectPage()
                }
            }
        } catch (_: Exception) {
            destroyView()
            terminal = true
            status = "系统浏览器不可用，请安装或更新 Android System WebView 后重试"
            providerFailure = status
            frame.addView(TextView(context).apply { text = status })
        }
        return frame
    }

    private fun allowsNavigation(url: String): Boolean = if (kuaishou) KuaishouBrowserPolicy.navigation(url, request.targetId)
        else BrowserUrlPolicy.navigation(url) && DouyinUrls.videoId(url) == request.targetId

    private fun load(url: String) {
        if (disposed || terminal || !allowsNavigation(url)) {
            if (!disposed && !terminal) finish("已阻止离开当前${platformLabel}视频页面")
            return
        }
        pageGeneration++
        checking = false
        pollingPaused = false
        pageStartedAt = SystemClock.elapsedRealtime()
        verificationWaiting = false
        verificationStartedAt = null
        lastResultKey = null
        currentMainPageUrl = url
        status = if (kuaishou && !videoPageEntry) "正在打开快手官方分享页；若出现安全验证，请手动完成…" else "正在打开${platformLabel}公开页面…"
        onPage(BrowserPageEvent.ENTRY, url)
        webView?.loadUrl(url)
    }

    fun switchPage() {
        if (!ready || disposed || terminal) return
        videoPageEntry = !videoPageEntry
        load(if (kuaishou) { if (videoPageEntry) KuaishouBrowserPolicy.videoPage(request.targetId) else shareEntry }
            else if (videoPageEntry) DouyinUrls.canonical(request.targetId) else "https://www.iesdouyin.com/share/video/${request.targetId}/")
    }

    fun zoomOut() {
        if (!kuaishou || !ready || disposed || terminal) return
        webView?.zoomOut()
    }

    private fun client() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, resource: WebResourceRequest): Boolean {
            val reason = blockReason(resource)
            if (reason != null) {
                requestTrace.blocked(resource.url.toString(), reason)
                if (resource.isForMainFrame && !disposed) status = "仅支持当前公开视频；登录、其他视频和外部应用跳转已阻止"
            }
            return reason != null
        }

        override fun shouldInterceptRequest(view: WebView, resource: WebResourceRequest): WebResourceResponse? {
            val reason = blockReason(resource)
            if (reason != null) requestTrace.blocked(resource.url.toString(), reason)
            return if (reason == null) null else WebResourceResponse(
                "text/plain", "UTF-8", localBlock.statusCode, localBlock.reasonPhrase,
                localBlock.headers, ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (disposed || terminal) return
            if (url == null || !allowsNavigation(url)) {
                view.stopLoading()
                finish("已阻止离开当前${platformLabel}视频页面")
                return
            }
            pageGeneration++
            checking = false
            pollingPaused = false
            pageStartedAt = SystemClock.elapsedRealtime()
            verificationWaiting = false
            verificationStartedAt = null
            lastResultKey = null
            currentMainPageUrl = url
            status = if (kuaishou) "页面正在加载；如出现安全验证，请手动完成…" else "页面正在加载，最多等待 90 秒…"
            onPage(BrowserPageEvent.STARTED, url)
            if (kuaishou) view.evaluateJavascript(KuaishouBrowserCapture.install, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!disposed && !terminal && url != null && isCurrentPage(url)) {
                status = "页面已加载，正在等待并检查视频资源…"
                onPage(BrowserPageEvent.FINISHED, url)
                if (kuaishou) view.evaluateJavascript(KuaishouBrowserCapture.install, null)
                inspectPage()
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError?) {
            handler.cancel()
            view.post { if (!disposed && error?.url?.let(::isCurrentPage) != false) finish("网页证书验证失败，已停止加载") }
        }

        override fun onReceivedError(view: WebView, resource: WebResourceRequest, error: WebResourceError) {
            requestTrace.networkFailure(resource.url.toString(), error.errorCode)
            if (resource.isForMainFrame && !disposed && isCurrentPage(resource.url.toString())) finish("浏览器未能加载网页，请检查网络后重试")
        }

        override fun onReceivedHttpError(view: WebView, resource: WebResourceRequest, response: WebResourceResponse) {
            // WebView also reports our synthetic 403. It was already counted as a local block.
            if (!requestTrace.httpFailure(resource.url.toString(), response.statusCode,
                    localBlock, response.reasonPhrase, response.responseHeaders)) return
            if (resource.isForMainFrame && !disposed && isCurrentPage(resource.url.toString())) {
                val message = when (response.statusCode) {
                    401 -> "该页面要求登录，当前版本仅支持公开访问"
                    404, 410 -> "该视频页面不存在或已失效"
                    else -> "${platformLabel}页面返回 HTTP ${response.statusCode}，请稍后重试"
                }
                finish(message)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            destroyView()
            finish("系统浏览器进程已退出，请重新打开后重试")
            return true
        }
    }

    private fun blockReason(resource: WebResourceRequest): BrowserBlockReason? {
        val url = resource.url.toString()
        (if (kuaishou) KuaishouBrowserPolicy.resourceBlockReason(url) else BrowserUrlPolicy.resourceBlockReason(url))?.let { return it }
        if (!resource.isForMainFrame || allowsNavigation(url)) return null
        return if (if (kuaishou) SocialUrls.platform(url) == SocialUrls.KUAISHOU else DouyinUrls.isAllowed(url)) BrowserBlockReason.OTHER_VIDEO
            else BrowserBlockReason.UNREVIEWED_RESOURCE
    }

    private fun safePage(url: String) = if (kuaishou) KuaishouBrowserPolicy.safeUrl(url) else DouyinUrls.sanitizeForDiagnostics(url)
    private fun isCurrentPage(url: String): Boolean = allowsNavigation(url) && safePage(url).trimEnd('/') == safePage(currentMainPageUrl).trimEnd('/')

    fun inspect() {
        // A manual retry after a recognized error gets a fresh automatic-check budget if it recovers.
        if (pollingPaused) pageStartedAt = SystemClock.elapsedRealtime()
        inspectPage()
    }

    private fun inspectPage() {
        val browser = webView ?: return
        if (disposed || terminal || checking || !ready || !resumed) return
        if (!allowsNavigation(browser.url.orEmpty())) return
        checking = true
        val generation = pageGeneration
        try {
            browser.evaluateJavascript(if (kuaishou) KuaishouBrowserCapture.snapshot(request.targetId) else BrowserCaptureScript.javascript) { raw ->
                if (disposed || terminal || generation != pageGeneration) return@evaluateJavascript
                scope.launch {
                    try {
                        val parsed = withContext(Dispatchers.Default) {
                            if (kuaishou) KuaishouBrowserParser().parseEvaluationResult(raw, request.targetId)
                                else BrowserSnapshotParser().parseEvaluationResult(raw, request.targetId)
                        }
                        if (disposed || terminal || generation != pageGeneration) return@launch
                        val result = parsed
                        onNetwork(requestTrace)
                        if (kuaishou) {
                            val waiting = result.error == ExtractError.KuaishouVerificationRequired
                            val now = SystemClock.elapsedRealtime()
                            if (waiting && !verificationWaiting) verificationStartedAt = now
                            if (waiting || verificationWaiting) pageStartedAt = now
                            verificationWaiting = waiting
                            if (!waiting) verificationStartedAt = null
                        }
                        pollingPaused = result.error == ExtractError.WebPlaybackUnavailable
                        status = when {
                            result.media != null -> "已找到视频信息，正在返回结果…"
                            result.error == ExtractError.VerificationRequired || result.error == ExtractError.KuaishouVerificationRequired -> "请在下方页面手动完成验证，然后点击检查"
                            result.error == ExtractError.LoginRequired -> "此页面要求登录，本版本仅提取公开访问的视频"
                            result.error == ExtractError.WebPlaybackUnavailable -> "抖音网页提示出错或需在 App 内观看，当前没有可提取的视频。已停止自动检查；可切换页面或关闭。"
                            result.error is ExtractError.ParserError -> "本次页面检查未完成，可以再次检查或尝试另一页面"
                            else -> "尚未找到视频资源；可等待加载，或尝试另一公开页面"
                        }
                        // Suppress repeated identical empty polls; parsing still runs every time.
                        val key = "${result.pageUrl}:${result.media != null}:${result.reason}:${result.attempts}"
                        if (result.media != null || key != lastResultKey) {
                            lastResultKey = key
                            onSnapshot(result)
                        }
                        if (result.media != null) {
                            terminal = true
                            if (!disposed) browser.stopLoading()
                        } else if (kuaishou && result.error == ExtractError.LoginRequired) {
                            finish("此快手页面要求登录，当前仅支持公开访问，已关闭网页")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (!disposed && !terminal) status = "本次页面检查未完成，可以再次检查"
                    } finally {
                        if (!disposed && generation == pageGeneration) checking = false
                    }
                }
            }
        } catch (_: Exception) {
            checking = false
            finish("系统浏览器无法检查页面，请更新 Android System WebView 后重试")
        }
    }

    fun setResumed(value: Boolean) {
        if (disposed) return
        resumed = value
        webView?.let { browser ->
            if (value) {
                browser.resumeTimers()
                browser.onResume()
            } else {
                browser.onPause()
                browser.pauseTimers()
            }
        }
    }

    fun finish(reason: String) {
        if (disposed || finished) return
        finished = true
        terminal = true
        val finalReason = providerFailure ?: reason
        status = finalReason
        webView?.stopLoading()
        onNetwork(requestTrace)
        onFinished(finalReason)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pageGeneration++
        scope.cancel()
        destroyView()
    }

    private fun destroyView() {
        val browser = webView ?: return
        webView = null
        ready = false
        (browser.parent as? ViewGroup)?.removeView(browser)
        runCatching {
            browser.stopLoading()
            browser.onPause()
            browser.clearCache(true)
            browser.clearHistory()
            browser.removeAllViews()
        }
        runCatching { browser.destroy() }
    }
}
