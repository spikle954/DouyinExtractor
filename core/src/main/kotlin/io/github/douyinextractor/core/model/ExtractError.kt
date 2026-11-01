package io.github.douyinextractor.core.model

sealed class ExtractError(val userMessage: String) : Exception(userMessage) {
    data object InvalidUrl : ExtractError("链接格式无法识别，请粘贴抖音、哔哩哔哩、快手或小红书视频分享内容")
    data object NotVideo : ExtractError("这是图文或图集作品，当前只支持普通视频")
    data object ShareUnavailable : ExtractError("平台未开放此分享页面，请回到原 App 重新复制完整分享链接；仍失败时可能需登录或网页暂不可用")
    data object RedirectFailed : ExtractError("短链接跳转失败，请重新复制视频分享链接")
    data object PageLoadFailed : ExtractError("网络请求未完成，请检查网络后重试")
    data object VideoNotFound : ExtractError("链接不存在或已失效，请回到原平台重新分享")
    data object VerificationRequired : ExtractError("抖音要求进行浏览器验证")
    data object KuaishouVerificationRequired : ExtractError("快手要求进行浏览器验证，请在当前网页中手动完成")
    data object LoginRequired : ExtractError("平台要求登录后访问，当前版本暂不支持")
    data object RestrictedContent : ExtractError("当前仅支持公开普通视频，暂不支持付费、会员、试看或互动内容")
    data object PlatformRejected : ExtractError("平台暂时拒绝访问，请稍后重试")
    data object UnsupportedMedia : ExtractError("平台未提供当前支持的完整 MP4；暂不支持音视频分离、分段或加密媒体")
    data object PartNotFound : ExtractError("链接指定的分 P 不存在，请检查链接中的 p 参数")
    data object WebPlaybackUnavailable : ExtractError("抖音网页提示出错，并要求在抖音内观看；当前网页未提供视频资源")
    data object UnsupportedPage : ExtractError("当前页面未提供可识别的视频资源，暂时无法提取")
    data class HttpError(val code: Int) : ExtractError(
        if (code == 403 || code == 429 || code == 412) "网络请求被平台拒绝，请稍后再试" else "平台暂时无法响应（HTTP $code）"
    )
    data class ParserError(val parser: String, val reason: String) : ExtractError("当前页面结构暂未支持")
}
