package io.github.douyinextractor.core.diagnostics

import io.github.douyinextractor.core.input.DouyinUrls
import io.github.douyinextractor.core.model.ExtractError
import io.github.douyinextractor.core.model.ResolvedVideoLink
import io.github.douyinextractor.core.model.MediaInfo
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.extractor.social.SocialUrls

object DiagnosticReport {
    private val urlPattern = Regex("(?i)(?:https?://|(?:www\\.|v\\.|m\\.)?(?:ies)?douyin\\.com(?=[/?#]|$)|(?:(?:www|m)\\.)?bilibili\\.com(?=[/?#]|$)|b23\\.tv(?=[/?#]|$)|(?:(?:www|v)\\.)?kuaishou\\.com(?=[/?#]|$)|[a-z0-9]{1,32}\\.m\\.chenzhongtech\\.com(?=[/?#]|$)|(?:www\\.)?xiaohongshu\\.com(?=[/?#]|$)|(?:www\\.)?xhslink\\.(?:com|cn)(?=[/?#]|$))[^\\s<>\"，。！？、；（）【】]*")
    private val secretLine = Regex("(?im)(cookie|set-cookie|authorization)\\s*[:=][^\\r\\n]*")

    fun redact(text: String): String = urlPattern.replace(secretLine.replace(text, "$1: [已隐藏]")) {
        safeUrl(it.value)
    }

    private fun safeUrl(url: String): String = SocialUrls.normalize(url)?.substringBefore('?') ?: BilibiliUrls.normalize(url)?.substringBefore('?') ?: DouyinUrls.sanitizeForDiagnostics(url)

    fun format(
        originalText: String,
        parsedUrl: String?,
        resolved: ResolvedVideoLink?,
        error: ExtractError?,
        trace: ResolutionDiagnostics,
        media: MediaInfo? = null,
    ): String = buildString {
        val platform = resolved?.platform ?: parsedUrl?.let(SocialUrls::platform)
        appendLine("拾影 0.6.0 · 视频提取诊断")
        appendLine("链接识别、页面提取和文件保存为不同步骤；点击下载后才保存文件。网页播放和下载会产生流量。")
        appendLine("原始分享文本（URL 查询参数及凭据已隐藏）：")
        appendLine(redact(originalText.take(16_384)))
        appendLine("提取 URL：${parsedUrl?.let(::safeUrl) ?: "未识别"}")
        appendLine("平台：${platform ?: "未识别"}")
        appendLine("${when (platform) { "Bilibili" -> "B站编号"; "Kuaishou" -> "快手作品编号"; "Xiaohongshu" -> "小红书笔记编号"; else -> "aweme_id" }}：${resolved?.awemeId ?: "未识别"}")
        appendLine("规范 URL：${resolved?.canonicalUrl?.let(::safeUrl) ?: "无"}")
        appendLine("最后定位 URL：${resolved?.finalUrl?.let(::safeUrl) ?: "见下方 HTTP 记录"}")
        appendLine("短链定位 HTTP 响应数：${trace.steps().size}（失败请求见说明，不含页面提取和 WebView 请求）")
        trace.steps().forEachIndexed { index, step ->
            appendLine("${index + 1}. ${step.status} ${step.url}")
            appendLine("   Content-Type：${step.contentType ?: "未提供"}")
            step.location?.let { appendLine("   Location：$it") }
        }
        trace.notes().forEach { appendLine("说明：$it") }
        trace.extractionSteps().forEach { appendLine(it) }
        trace.browserPageSteps().forEach { appendLine(it) }
        trace.browserSteps().forEach { appendLine(it) }
        trace.browserNetworkSteps().forEach { appendLine(it) }
        error?.let { appendLine("错误：${it.javaClass.simpleName} · ${it.userMessage}") }
        appendLine("找到 MediaFormat：${media?.formats?.size ?: 0}")
        media?.formats?.forEachIndexed { index, format ->
            appendLine("资源 ${index + 1}：${format.width ?: "未知"} × ${format.height ?: "未知"} | bitrate=${format.bitrate ?: "未知"} | codec=${redact(format.codec?.takeIf { it.isNotBlank() } ?: "未知").take(32)} | source=${redact(format.source).take(100)}")
        }
        appendLine("本报告仅在设备上生成；无 Cookie 或登录信息。")
    }
}
