package io.github.douyinextractor.core.download

import io.github.douyinextractor.core.extractor.douyin.mapping.MediaUrlPolicy
import io.github.douyinextractor.core.network.DouyinHttpClient
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.extractor.bilibili.BilibiliHttpClient
import io.github.douyinextractor.core.extractor.social.SocialUrls
import io.github.douyinextractor.core.extractor.social.SocialHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Anonymous streaming transport. Signed queries are used verbatim, never logged or persisted. */
class VideoDownloader internal constructor(
    client: OkHttpClient,
    private val allowed: (String) -> Boolean,
    private val maxBytes: Long = MAX_BYTES,
    private val referer: String = "https://www.douyin.com/",
    private val userAgent: String = DouyinHttpClient.USER_AGENT,
) {
    constructor(platform: String = "Douyin") : this(OkHttpClient.Builder().build(),
        { when (platform) { "Bilibili" -> BilibiliUrls.media(it) != null; "Douyin" -> MediaUrlPolicy.media(it) != null; SocialUrls.KUAISHOU, SocialUrls.XIAOHONGSHU -> SocialUrls.media(it, platform) != null; else -> false } },
        MAX_BYTES, when (platform) { "Bilibili" -> BilibiliHttpClient.REFERER; SocialUrls.KUAISHOU, SocialUrls.XIAOHONGSHU -> SocialUrls.referer(platform); else -> "https://www.douyin.com/" },
        when (platform) { "Bilibili" -> BilibiliHttpClient.USER_AGENT; SocialUrls.KUAISHOU, SocialUrls.XIAOHONGSHU -> SocialHttpClient.userAgent(platform); else -> DouyinHttpClient.USER_AGENT })
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES).build()

    suspend fun download(url: String, file: File, progress: (DownloadProgress) -> Unit): Long = withContext(Dispatchers.IO) {
        var complete = false
        try {
            var next = url
            val visited = mutableSetOf<String>()
            for (hop in 0..5) {
                ensureActive()
                if (!allowed(next) || !visited.add(next)) throw DownloadException(DownloadProblem.UNSAFE_URL)
                val request = Request.Builder().url(next)
                    .header("User-Agent", userAgent)
                    .header("Referer", referer)
                    .header("Accept", "video/mp4,application/octet-stream;q=0.9,*/*;q=0.1")
                    .header("Accept-Encoding", "identity").build()
                val call = client.newCall(request)
                // A sibling observes parent cancellation even while execute/read blocks on the IO pool.
                val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    ensureActive()
                    call.execute().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            val location = response.header("Location") ?: throw DownloadException(DownloadProblem.UNSAFE_URL)
                            next = next.toHttpUrlOrNull()?.resolve(location)?.toString()
                                ?: throw DownloadException(DownloadProblem.UNSAFE_URL)
                        } else {
                            if (response.code in setOf(401, 403, 404, 410)) throw DownloadException(DownloadProblem.EXPIRED, response.code)
                            if (response.code != 200) throw DownloadException(DownloadProblem.HTTP, response.code)
                            if (response.header("Content-Encoding")?.lowercase()?.let { it != "identity" } == true)
                                throw DownloadException(DownloadProblem.NOT_VIDEO)
                            val body = response.body ?: throw DownloadException(DownloadProblem.NOT_VIDEO)
                            val type = body.contentType()?.let { "${it.type}/${it.subtype}" }
                            if (type != null && type !in setOf("video/mp4", "video/x-m4v", "application/octet-stream", "binary/octet-stream"))
                                throw DownloadException(DownloadProblem.NOT_VIDEO)
                            val total = body.contentLength().takeIf { it >= 0 }
                            if (total != null && total > maxBytes) throw DownloadException(DownloadProblem.TOO_LARGE)
                            var bytes = 0L
                            val started = System.nanoTime()
                            var last = started
                            progress(DownloadProgress(total = total))
                            storage { file.outputStream().buffered() }.use { output ->
                                val input = body.byteStream()
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    bytes += read
                                    if (bytes > maxBytes) throw DownloadException(DownloadProblem.TOO_LARGE)
                                    storage { output.write(buffer, 0, read) }
                                    val now = System.nanoTime()
                                    if (now - last >= 250_000_000) {
                                        progress(DownloadProgress(bytes, total, (bytes * 1_000_000_000.0 / (now - started).coerceAtLeast(1)).toLong()))
                                        last = now
                                    }
                                }
                                storage { output.flush() }
                            }
                            if (bytes == 0L || total != null && total != bytes) throw DownloadException(DownloadProblem.NOT_VIDEO)
                            ensureActive()
                            Mp4Inspector.verify(file)
                            progress(DownloadProgress(bytes, total ?: bytes))
                            complete = true
                            return@withContext bytes
                        }
                    }
                } finally { guard.cancel() }
            }
            throw DownloadException(DownloadProblem.UNSAFE_URL)
        } catch (error: IOException) {
            ensureActive()
            if (error is DownloadException) throw error
            throw DownloadException(DownloadProblem.NETWORK)
        } finally {
            if (!complete) file.delete()
        }
    }
    private inline fun <T> storage(block: () -> T): T = try { block() }
        catch (_: IOException) { throw DownloadException(DownloadProblem.STORAGE) }
    companion object { const val MAX_BYTES = 2_000_000_000L }
}
