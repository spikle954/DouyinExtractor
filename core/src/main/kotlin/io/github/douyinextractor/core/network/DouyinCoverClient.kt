package io.github.douyinextractor.core.network

import io.github.douyinextractor.core.extractor.douyin.mapping.MediaUrlPolicy
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.extractor.social.SocialUrls
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Fetches only declared platform covers; no disk cache, credentials, or external redirects. */
class DouyinCoverClient private constructor(private val client: OkHttpClient, private val enforceHosts: Boolean) {
    constructor() : this(DouyinHttpClient.newClient(), true)
    internal constructor(client: OkHttpClient) : this(client.newBuilder().followRedirects(false).followSslRedirects(false).build(), false)

    suspend fun fetch(url: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val biliUrl = BilibiliUrls.cover(url)
        val bili = biliUrl != null
        val social = listOf(SocialUrls.KUAISHOU, SocialUrls.XIAOHONGSHU).firstOrNull { SocialUrls.cover(url, it) != null }
        require(!enforceHosts || MediaUrlPolicy.cover(url) != null || bili || social != null) { "封面地址不受支持" }
        val request = Request.Builder().url(biliUrl ?: social?.let { SocialUrls.cover(url, it) } ?: url)
            .header("User-Agent", DouyinHttpClient.USER_AGENT)
            .header("Referer", if (bili) "https://www.bilibili.com/" else social?.let(SocialUrls::referer) ?: "https://www.douyin.com/")
            .header("Accept", "image/jpeg,image/png,image/webp")
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw IOException("封面未返回成功响应")
                        val body = it.body ?: throw IOException("封面内容为空")
                        val type = body.contentType()?.let { media -> "${media.type}/${media.subtype}" }
                        if (type !in setOf("image/jpeg", "image/png", "image/webp")) throw IOException("封面类型不支持")
                        if (body.contentLength() > MAX_BYTES) throw IOException("封面过大")
                        val source = body.source()
                        source.request(MAX_BYTES + 1L)
                        if (source.buffer.size > MAX_BYTES) throw IOException("封面过大")
                        val bytes = source.readByteArray()
                        if (continuation.isActive) continuation.resume(bytes)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
    companion object { const val MAX_BYTES = 4 * 1024 * 1024 }
}
