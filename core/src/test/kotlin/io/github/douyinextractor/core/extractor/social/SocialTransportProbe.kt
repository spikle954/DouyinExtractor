package io.github.douyinextractor.core.extractor.social

import io.github.douyinextractor.core.diagnostics.ResolutionDiagnostics
import io.github.douyinextractor.core.input.ShareTextParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*

/** Opt-in public request comparison. No cookies, raw page content or signed resource URLs in output. */
object SocialTransportProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val input = System.`in`.bufferedReader().readLine()
        val url = ShareTextParser().parse(input).getOrThrow().url
        for (http1 in listOf(false, true)) {
            val protocols = if (http1) listOf(Protocol.HTTP_1_1) else listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            val client = OkHttpClient.Builder().protocols(protocols).cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
            val trace = ResolutionDiagnostics()
            val result = SocialExtractor(SocialHttpClient(client, true)).extract(url, trace)
            println(buildJsonObject {
                put("configuredProtocols", protocols.joinToString()); put("success", result.isSuccess)
                put("error", result.exceptionOrNull()?.javaClass?.simpleName)
                put("formats", result.getOrNull()?.formats?.size ?: 0); put("trace", trace.notes().joinToString("\n"))
            })
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
