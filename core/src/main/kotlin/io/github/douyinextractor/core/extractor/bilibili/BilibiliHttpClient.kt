package io.github.douyinextractor.core.extractor.bilibili

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

data class BilibiliResponse(val status: Int, val body: String = "", val location: String? = null)
fun interface BilibiliTransport { suspend fun fetch(url: String, body: Boolean): BilibiliResponse }

/** Anonymous bounded public JSON requests. Short-link redirects are resolved by the caller. */
class BilibiliHttpClient internal constructor(private val client: OkHttpClient, private val enforceHosts: Boolean) : BilibiliTransport {
    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build(), true)

    override suspend fun fetch(url: String, body: Boolean): BilibiliResponse = suspendCancellableCoroutine { continuation ->
        require(!enforceHosts || BilibiliUrls.requestAllowed(url))
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Referer", REFERER).header("Accept", "application/json,text/html;q=0.8").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val text = if (body && it.code == 200) {
                            val responseBody = it.body ?: throw IOException("Missing body")
                            if (responseBody.contentLength() > MAX_BYTES) throw IOException("Body too large")
                            val source = responseBody.source()
                            source.request(MAX_BYTES + 1L)
                            if (source.buffer.size > MAX_BYTES) throw IOException("Body too large")
                            source.readUtf8()
                        } else ""
                        BilibiliResponse(it.code, text, it.header("Location"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val REFERER = "https://www.bilibili.com/"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
