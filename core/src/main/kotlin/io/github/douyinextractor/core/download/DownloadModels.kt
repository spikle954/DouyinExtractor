package io.github.douyinextractor.core.download

import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import io.github.douyinextractor.core.extractor.bilibili.BilibiliUrls
import io.github.douyinextractor.core.extractor.social.SocialUrls

enum class DownloadPhase { IDLE, PREPARING, DOWNLOADING, VERIFYING, SAVING, COMPLETE, CANCELLED, FAILED, INTERRUPTED }

data class DownloadProgress(val bytes: Long = 0, val total: Long? = null, val bytesPerSecond: Long = 0) {
    val percent: Int? get() = total?.takeIf { it > 0 }?.let { ((bytes.toDouble() / it) * 100).toInt().coerceIn(0, 100) }
    val remainingSeconds: Long? get() = total?.takeIf { bytesPerSecond > 0 && it >= bytes }?.let { (it - bytes) / bytesPerSecond }
}

enum class DownloadProblem(val userMessage: String) {
    UNSAFE_URL("视频地址或跳转不受支持，请重新解析"),
    EXPIRED("视频地址已过期或访问被拒绝，请重新解析后下载"),
    HTTP("视频服务器暂时不可用，请稍后重试"),
    NOT_VIDEO("服务器没有返回完整的 MP4 视频，请重新解析或选择其他资源"),
    TOO_LARGE("当前版本支持单个视频最大 2 GB"),
    NETWORK("下载中断或网络超时，可重试；重试会从头下载"),
    STORAGE("无法保存文件，请检查剩余空间和文件夹权限后重试"),
    INVALID_VIDEO("文件未通过视频检查，请重新解析或选择其他资源"),
}

class DownloadException(val problem: DownloadProblem, val httpCode: Int? = null) : IOException(problem.userMessage)

object DownloadNames {
    fun fileName(author: String?, title: String?, videoId: String): String {
        val bili = videoId.substringBefore("_P")
        val part = videoId.substringAfter("_P", "")
        require(videoId.startsWith("KS_") && SocialUrls.validId(SocialUrls.KUAISHOU, videoId.removePrefix("KS_")) ||
            videoId.startsWith("XHS_") && SocialUrls.validId(SocialUrls.XIAOHONGSHU, videoId.removePrefix("XHS_")) ||
            videoId.matches(Regex("[0-9]{1,24}")) || BilibiliUrls.validId(bili) &&
            (videoId == bili || part.toIntOrNull() in 1..10000 && part.matches(Regex("[1-9][0-9]*"))))
        fun clean(value: String?, fallback: String): String {
            val normalized = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFC)
                .replace(Regex("[\\p{Cc}\\p{Cf}<>:\"/\\\\|?*]"), "_")
                .replace(Regex("\\s+"), " ").trim(' ', '.')
            val text = normalized.ifBlank { fallback }
            val reserved = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")
            return if (reserved.matches(text)) "_$text" else text
        }
        fun limit(value: String, bytes: Int): String {
            val result = StringBuilder()
            for (cp in value.codePoints().toArray()) {
                val next = String(Character.toChars(cp))
                if ((result.toString() + next).toByteArray(Charsets.UTF_8).size > bytes) break
                result.append(next)
            }
            return result.toString().trimEnd(' ', '.')
        }
        return "${limit(clean(author, "作者"), 48)}_${limit(clean(title, "视频"), 120)}_$videoId.mp4"
    }
}

object DownloadLabels {
    fun bytes(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", value / 1_000_000_000.0)
        value >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.ROOT, "%.1f KB", value / 1_000.0)
        else -> "$value B"
    }
}
