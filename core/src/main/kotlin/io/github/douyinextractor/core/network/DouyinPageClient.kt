package io.github.douyinextractor.core.network

import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.RedirectStep
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

data class DouyinPageResponse(
    val status: Int,
    val contentType: String?,
    val body: String,
    val finalUrl: String,
    val redirects: List<RedirectStep> = emptyList(),
)

fun interface DouyinPageTransport {
    suspend fun fetch(url: String): DouyinPageResponse
}

/** Anonymous, bounded page retrieval. Automatic redirects are always disabled. */
class DouyinPageClient private constructor(
    client: OkHttpClient,
    private val enforceAllowedTargets: Boolean,
) : DouyinPageTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    constructor() : this(DouyinHttpClient.newClient(), true)

    /** Only module tests may target loopback HTTP servers. */
    internal constructor(client: OkHttpClient) : this(client, false)

    override suspend fun fetch(url: String): DouyinPageResponse {
        currentCoroutineContext().ensureActive()
        require(isAllowed(url)) { "仅允许安全的抖音 HTTPS 地址。" }
        var current = url.toHttpUrlOrNull()!!.newBuilder().fragment(null).build().toString()
        val visited = mutableSetOf<String>()
        val steps = mutableListOf<RedirectStep>()
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isAllowed(current) || !visited.add(current)) throw ExtractError.RedirectFailed
            val response = fetchOnce(current)
            currentCoroutineContext().ensureActive()
            if (response.page.status !in REDIRECT_CODES) return response.page.copy(redirects = steps.toList())
            if (redirects >= MAX_REDIRECTS) throw ExtractError.RedirectFailed
            val target = redirectTarget(current, response.location) ?: throw ExtractError.RedirectFailed
            if (target in visited) throw ExtractError.RedirectFailed
            steps += RedirectStep(
                DouyinUrls.sanitizeForDiagnostics(current),
                response.page.status,
                DouyinUrls.sanitizeForDiagnostics(target),
                response.page.contentType?.substringBefore(';')?.take(120),
            )
            current = target
            redirects++
        }
    }

    private suspend fun fetchOnce(url: String): Hop = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DouyinHttpClient.USER_AGENT)
            .header("Referer", "https://www.douyin.com/")
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            .get()
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        // Redirect and failure bodies cannot contribute media and may be unbounded.
                        val body = if (it.code in 200..299) boundedBody(it) else ""
                        Hop(DouyinPageResponse(it.code, it.header("Content-Type"), body, url), it.header("Location"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun boundedBody(response: Response): String {
        val body = response.body ?: return ""
        if (body.contentLength() > MAX_BODY_BYTES) throw IOException("页面超过读取上限。")
        val buffer = Buffer()
        val source = body.source()
        while (true) {
            val count = source.read(buffer, minOf(8192L, MAX_BODY_BYTES + 1L - buffer.size))
            if (count == -1L) break
            if (buffer.size > MAX_BODY_BYTES) throw IOException("页面超过读取上限。")
        }
        return buffer.readString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }

    private fun isAllowed(url: String): Boolean {
        if (enforceAllowedTargets) return DouyinUrls.isAllowed(url)
        if (url.length > DouyinUrls.MAX_URL_LENGTH || url.any { it == '\\' || it.isWhitespace() || it.code < 32 }) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.host in setOf("localhost", "127.0.0.1", "::1") &&
            parsed.username.isEmpty() && parsed.password.isEmpty()
    }

    private fun redirectTarget(current: String, location: String?): String? {
        if (location.isNullOrBlank() || location.any { it == '\\' || it.code < 32 || it.code == 127 }) return null
        return try {
            val text = location.trim()
            val reference = URI(text)
            if (reference.rawUserInfo != null) return null
            if (reference.isAbsolute && reference.rawAuthority == null) return null
            if (text.startsWith("//") && reference.rawAuthority == null) return null
            if (reference.rawAuthority != null) {
                val explicit = if (reference.isAbsolute) text else "${URI(current).scheme}:$text"
                if (!isAllowed(explicit)) return null
            }
            val target = current.toHttpUrlOrNull()?.resolve(text) ?: return null
            val targetString = target.newBuilder().fragment(null).build().toString()
            if (!isAllowed(targetString)) return null
            // Validate conflicting IDs, but preserve query state for the next HTTP request.
            if (enforceAllowedTargets && DouyinUrls.normalize(targetString) == null) return null
            targetString
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }

    private data class Hop(val page: DouyinPageResponse, val location: String?)

    companion object {
        internal const val MAX_BODY_BYTES = 2L * 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
