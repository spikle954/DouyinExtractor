package io.github.douyinextractor.core.network

import io.github.douyinextractor.core.input.DouyinUrls
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Header-only transport: resolving a share URL never downloads the video body. */
fun interface DouyinTransport {
    suspend fun fetch(url: String): DouyinHttpResponse
}

data class DouyinHttpResponse(
    val status: Int,
    val location: String? = null,
    val contentType: String? = null,
)

/** Cookies stay in this client's memory and are never imported, persisted, or logged. */
class DouyinHttpClient private constructor(
    private val client: OkHttpClient,
    private val enforceAllowedTargets: Boolean,
) : DouyinTransport {
    constructor() : this(newClient(), true)

    /** Module-internal test seam; public construction always checks the target allowlist. */
    internal constructor(client: OkHttpClient) : this(client, false)

    override suspend fun fetch(url: String): DouyinHttpResponse {
        require(!enforceAllowedTargets || DouyinUrls.isAllowed(url)) { "仅允许安全的抖音 HTTPS 地址。" }
        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                // No Accept-Encoding override: OkHttp owns transparent gzip negotiation.
                .get()
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = DouyinHttpResponse(it.code, it.header("Location"), it.header("Content-Type"))
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            })
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        internal fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .cookieJar(SessionCookieJar())
            .build()
    }
}

/** Bounded cookie storage; OkHttp's matching includes domain, path, and Secure. */
internal class SessionCookieJar : CookieJar {
    private val cookies = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        removeExpired()
        for (cookie in cookies) {
            val key = "${cookie.name}\n${cookie.domain}\n${cookie.path}"
            this.cookies.remove(key)
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies[key] = cookie
        }
        while (this.cookies.size > MAX_COOKIES) this.cookies.remove(this.cookies.keys.first())
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        removeExpired()
        return cookies.values.filter { it.matches(url) }
    }

    private fun removeExpired() {
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { it.value.expiresAt <= now }
    }

    companion object {
        private const val MAX_COOKIES = 100
    }
}
