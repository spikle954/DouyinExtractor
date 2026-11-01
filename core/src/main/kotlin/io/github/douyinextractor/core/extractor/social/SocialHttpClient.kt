package io.github.douyinextractor.core.extractor.social

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

data class SocialResponse(val status: Int, val body: String = "", val location: String? = null, val contentType: String? = null)
fun interface SocialTransport { suspend fun fetch(url: String, platform: String): SocialResponse }

/** Ordinary anonymous public-page requests; every redirect is checked by the extractor. */
class SocialHttpClient internal constructor(private val client: OkHttpClient, private val enforceHosts: Boolean) : SocialTransport {
    constructor() : this(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build(), true)
    override suspend fun fetch(url: String, platform: String): SocialResponse = suspendCancellableCoroutine { continuation ->
        require(!enforceHosts || SocialUrls.pageAllowed(url, platform))
        val request = Request.Builder().url(url).header("User-Agent", userAgent(platform))
            .header("Referer", SocialUrls.referer(platform)).header("Accept", "text/html,application/xhtml+xml;q=0.9")
            .header("Accept-Language", "zh-CN,zh;q=0.9").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(SocialRequestFailure.classify(e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val text = if (it.code == 200) {
                            val body = it.body ?: throw SocialRequestFailure(SocialRequestFailure.Kind.MISSING_BODY, it.code)
                            if (body.contentLength() > MAX_BYTES) throw SocialRequestFailure(SocialRequestFailure.Kind.TOO_LARGE, it.code)
                            val source = body.source(); source.request(MAX_BYTES + 1L)
                            if (source.buffer.size > MAX_BYTES) throw SocialRequestFailure(SocialRequestFailure.Kind.TOO_LARGE, it.code)
                            source.readUtf8()
                        } else ""
                        SocialResponse(it.code, text, it.header("Location"), it.header("Content-Type"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    val failure = if (e is IOException) SocialRequestFailure.classify(e, response.code) else e
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        fun userAgent(platform: String) = if (platform == SocialUrls.XIAOHONGSHU) MOBILE_USER_AGENT else USER_AGENT
    }
}
