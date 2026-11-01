package io.github.douyinextractor.core.extractor.social

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only fixed categories escape the transport. Exception messages can contain URLs or credentials. */
internal class SocialRequestFailure(val kind: Kind, val status: Int? = null) : IOException(kind.name) {
    enum class Kind(val label: String) {
        DNS("域名解析失败"), TLS("HTTPS 安全连接失败"), TIMEOUT("连接或读取超时"),
        CONNECT("无法建立服务器连接"), CONNECTION("网络连接中断"),
        TOO_LARGE("页面超过 2 MiB 限制"), MISSING_BODY("响应未提供页面内容"),
    }
    val canRetryConnection: Boolean get() = status == null && kind in setOf(Kind.TIMEOUT, Kind.CONNECT, Kind.CONNECTION)
    val canTryOtherPage: Boolean get() = kind in setOf(Kind.DNS, Kind.TIMEOUT, Kind.CONNECT, Kind.CONNECTION)
    companion object {
        fun classify(error: IOException, status: Int? = null): SocialRequestFailure {
            if (error is SocialRequestFailure) return error
            val causes = generateSequence<Throwable>(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
            val kind = when {
                causes.any { it is SSLException } -> Kind.TLS
                causes.any { it is UnknownHostException } -> Kind.DNS
                causes.any { it is InterruptedIOException } -> Kind.TIMEOUT
                causes.any { it is ConnectException } -> Kind.CONNECT
                else -> Kind.CONNECTION
            }
            return SocialRequestFailure(kind, status)
        }
    }
}
